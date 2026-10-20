import fcntl
import json
import os
import shutil
import signal
import socket
import struct
import subprocess
import tempfile
import time

from test_backend import config, invoke, wait_for, write_file


def manage(binary: str, env: dict, *args: str, input: str = "", success: bool = True) -> dict:
    result = subprocess.run([binary, *args], input=input, capture_output=True, text=True, env=env, timeout=45)
    assert (result.returncode == 0) == success, result.stdout + result.stderr
    return json.loads(result.stdout)


def daemon_pid(home: str) -> int:
    with socket.socket(socket.AF_UNIX) as client:
        client.connect(os.path.join(home, "control.sock"))
        return struct.unpack("3i", client.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))[0]


def running_pid(home: str) -> int:
    try:
        return daemon_pid(home)
    except (FileNotFoundError, ConnectionRefusedError):
        return 0


def guardian_stopped(home: str) -> bool:
    with open(os.path.join(home, "guardian.lock"), "rb") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return True
        except BlockingIOError:
            return False


def check_guardian_restarts(binary: str, env: dict):
    home = env["CW_TEST_HOME"]
    manage(binary, env, "install")
    try:
        manage(binary, env, "start")
        wait_for(lambda: running_pid(home) != 0)
        pid = daemon_pid(home)
        for index, delay in enumerate((1, 2, 4, 8, 1, 1, 2, 4, 8)):
            if index == 4:
                time.sleep(6)  # The test binary uses a five-second stability window.
            started = time.monotonic()
            os.kill(pid, signal.SIGKILL)
            wait_for(lambda: running_pid(home) not in (0, pid), timeout=delay + 8)
            assert time.monotonic() - started >= delay - 0.1, "Restart skipped its backoff"
            pid = daemon_pid(home)
        os.kill(pid, signal.SIGKILL)
        wait_for(lambda: guardian_stopped(home))
        assert running_pid(home) == 0, "Guardian exceeded five consecutive short failures"
        manage(binary, env, "start")
        wait_for(lambda: running_pid(home) != 0)
        invoke(binary, env, "stop")
        wait_for(lambda: guardian_stopped(home))
        assert running_pid(home) == 0, "Guardian restarted a normally stopped backend"
    finally:
        manage(binary, env, "uninstall")


if __name__ == "__main__":
    assert os.geteuid() == 0, "Run in the isolated Linux test environment as root"
    binary = os.path.abspath("build/native-tests/test-daemon")
    root = tempfile.mkdtemp(prefix="cleanupwatcher-test-")
    home = os.path.join(root, "backend")
    env = dict(os.environ, CW_TEST_HOME=home)
    modules = os.path.join(root, "modules")
    module = os.path.join(modules, "cleanupwatcher")
    scope = os.path.join(root, "files")
    try:
        assert not manage(binary, env, "inspect", "0")["present"]
        assert not os.path.exists(home), "Inspection created backend state"
        assert manage(binary, env, "configure", input="CW\n", success=False)["error"] == "offline"
        assert not os.path.exists(home), "Configuration created an uninstalled backend"
        os.mkdir(modules)
        os.mkdir(os.path.join(modules, "other"))
        other = os.path.join(modules, "other", "precious.txt")
        write_file(other, "sentinel")
        manage(binary, env, "module-install")
        with open(os.path.join(module, "module.prop"), encoding="utf-8") as stream:
            assert "version=v1.0\n" in stream.read()
        action = os.path.join(module, "action.sh")
        assert os.access(action, os.X_OK)
        with open(action, encoding="utf-8") as stream:
            assert "/system/bin/am start --user 0 -n com.jasongzy.cleanupwatcher/.MainActivity\n" in stream.read()
        with open(os.path.join(module, "service.sh"), encoding="utf-8") as stream:
            assert stream.read() == (
                "#!/system/bin/sh\n[ -x /data/adb/cleanupwatcher/cleanupwatcherd ] && "
                "/data/adb/cleanupwatcher/cleanupwatcherd start\n"
            )
        assert not os.path.exists(home), "Module installation created backend state"
        assert manage(binary, env, "inspect", "1")["module"] == "enabled"
        metadata = manage(binary, env, "inspect", "1")
        assert metadata["moduleVersionCode"] == metadata["requiredModuleVersionCode"] == 1
        assert metadata["moduleVersionName"] == "v1.0"
        version = manage(binary, env, "version")
        assert version == {"versionCode": 5}
        assert metadata["requiredBackendVersionCode"] == version["versionCode"]
        properties = os.path.join(module, "module.prop")
        with open(properties, encoding="utf-8") as stream:
            original = stream.read()
        for code, expected in [("2", 2), ("0", 0), ("-1", 0), ("1x", 0), ("2147483648", 0), ("", 0)]:
            write_file(properties, original.replace("versionCode=1\n", f"versionCode={code}\n"))
            assert manage(binary, env, "inspect", "1")["moduleVersionCode"] == expected
        for content in [original.replace("versionCode=1\n", ""), original + "versionCode=1\n"]:
            write_file(properties, content)
            assert manage(binary, env, "inspect", "1")["moduleVersionCode"] == 0
        write_file(properties, original.replace("\n", "\r\n").replace("id=cleanupwatcher\r\n", "id=cleanupwatcher\n"))
        assert manage(binary, env, "inspect", "1")["moduleVersionCode"] == 1
        write_file(properties, original)
        for value, expected in [("version=v0.9\n", "v0.9"), ("", ""), ("version=v1.0\nversion=v2.0\n", "")]:
            write_file(properties, original.replace("version=v1.0\n", value))
            assert manage(binary, env, "inspect", "1")["moduleVersionName"] == expected
        write_file(properties, original)
        write_file(os.path.join(module, "disable"), "")
        assert manage(binary, env, "inspect", "1")["module"] == "disabled"
        write_file(os.path.join(module, "remove"), "")
        assert manage(binary, env, "inspect", "1")["module"] == "removing"
        manage(binary, env, "module-install")
        assert not os.path.exists(os.path.join(module, "remove"))
        assert not os.path.exists(os.path.join(module, "disable"))
        manage(binary, env, "install")
        installed = os.path.join(home, "cleanupwatcherd")
        assert os.access(installed, os.X_OK)
        assert manage(installed, env, "version") == version
        with open(binary, "rb") as source, open(installed, "rb") as target:
            assert source.read() == target.read()
        os.mkdir(scope)
        initial = config(scope, pattern="*.tmp")
        assert manage(binary, env, "configure", input=initial) == {"configured": True}
        assert not os.path.exists(os.path.join(home, "control.sock"))
        assert not manage(binary, env, "inspect", "1")["boot"]
        assert manage(binary, env, "boot-start") == {"starting": False}
        manage(binary, env, "start")
        wait_for(lambda: os.path.exists(os.path.join(home, "control.sock")))
        first_pid = daemon_pid(home)
        status = invoke(binary, env, "status")
        assert status["versionCode"] == metadata["requiredBackendVersionCode"]
        assert "versionName" not in status
        assert status["rules"] == initial
        changed = config(scope, pattern="*.log")
        assert manage(binary, env, "configure", input=changed)["rules"] == changed
        for _ in range(3):
            manage(binary, env, "start")
        assert daemon_pid(home) == first_pid, "Starting twice replaced the running daemon"
        invoke(binary, env, "stop")
        wait_for(lambda: not os.path.exists(f"/proc/{first_pid}"))
        pending = config(scope, pattern="*.old")
        assert manage(binary, env, "configure", input=pending) == {"configured": True}
        manage(binary, env, "start")
        wait_for(lambda: os.path.exists(os.path.join(home, "control.sock")))
        first_pid = daemon_pid(home)
        assert invoke(binary, env, "status")["rules"] == pending
        invoke(binary, env, "pause")
        invoke(binary, env, "stop")
        wait_for(lambda: not os.path.exists(f"/proc/{first_pid}"))
        manage(binary, env, "start")
        wait_for(lambda: os.path.exists(os.path.join(home, "control.sock")))
        first_pid = daemon_pid(home)
        assert invoke(binary, env, "status")["paused"]
        invoke(binary, env, "resume")
        assert not invoke(binary, env, "settled")["checking"]
        assert not invoke(binary, env, "status")["paused"]
        assert invoke(binary, env, "status")["rules"] == pending
        manage(binary, env, "module-uninstall")
        assert not os.path.exists(module)
        assert daemon_pid(home) == first_pid, "Module removal affected the backend"
        assert not invoke(binary, env, "status")["boot"]
        manage(binary, env, "module-install")
        invoke(binary, env, "rules", config(scope, pattern="*.log"))
        invoke(binary, env, "pause")
        invoke(binary, env, "boot", "1")
        stage = os.path.join(scope, ".cleanupwatcher")
        os.mkdir(stage, 0o700)
        preserved = os.path.join(stage, "preserved.item")
        write_file(preserved, "recover me")
        assert invoke(binary, env, "rules", "CW\n")["error"] == "retained"
        assert manage(binary, env, "uninstall", success=False)["error"] == "retained"
        assert os.path.exists(installed) and os.path.exists(preserved)
        assert not os.path.exists(os.path.join(home, "removing"))
        assert manage(binary, env, "configure", input="CW\n", success=False)["error"] == "retained"
        os.remove(preserved)
        unknown = os.path.join(home, "unknown")
        os.mkdir(unknown)
        write_file(os.path.join(unknown, "precious.txt"), "sentinel")
        assert manage(binary, env, "uninstall", success=False)["error"].startswith("uninstall_unknown")
        assert os.path.exists(installed) and os.path.exists(os.path.join(unknown, "precious.txt"))
        shutil.rmtree(unknown)
        rules = os.path.join(home, "rules")
        with open(rules, encoding="utf-8") as stream:
            saved = stream.read()
        os.remove(rules)
        os.symlink(other, rules)
        assert manage(binary, env, "uninstall", success=False)["error"].startswith("uninstall_unknown")
        assert os.path.islink(rules) and os.path.exists(other)
        os.remove(rules)
        write_file(rules, saved)
        manage(binary, env, "start")
        wait_for(lambda: os.path.exists(os.path.join(home, "control.sock")))
        manage(binary, env, "uninstall")
        assert not os.path.exists(home) and not os.path.exists(stage)
        assert os.path.exists(module), "Backend removal affected the module"
        time.sleep(2)
        assert not os.path.exists(home), "A guardian recreated removed state"
        assert not manage(binary, env, "inspect", "0")["present"]
        assert not os.path.exists(home)
        unknown = os.path.join(module, "unknown")
        write_file(unknown, "keep")
        assert manage(binary, env, "module-uninstall", success=False)["error"].startswith("uninstall_unknown")
        assert os.path.exists(os.path.join(module, "service.sh"))
        os.remove(unknown)
        service = os.path.join(module, "service.sh")
        os.remove(service)
        os.symlink(other, service)
        assert manage(binary, env, "module-uninstall", success=False)["error"].startswith("uninstall_unknown")
        assert os.path.islink(service) and os.path.exists(other)
        os.remove(service)
        manage(binary, env, "module-uninstall")
        assert not os.path.exists(module) and os.path.exists(other)
        os.symlink(os.path.join(modules, "other"), home)
        assert manage(binary, env, "uninstall", success=False)["error"] == "management"
        assert os.path.exists(other) and os.path.islink(home)
        os.remove(home)
        manage(binary, env, "uninstall")
        manage(binary, env, "module-uninstall")
        with open(other, encoding="utf-8") as stream:
            assert stream.read() == "sentinel"
        manage(binary, env, "install")
        manage(binary, env, "configure", input=config(os.path.join(scope, "missing")))
        manage(binary, env, "uninstall")
        assert not os.path.exists(home) and os.path.exists(other)
        check_guardian_restarts(binary, env)
        print("Lifecycle, removal, guardian backoff, stability reset and preservation checks passed")
    finally:
        subprocess.run([binary, "ctl", "stop"], capture_output=True, env=env, timeout=10)
        assert os.path.abspath(root).startswith("/tmp/cleanupwatcher-test-")
        shutil.rmtree(root)
