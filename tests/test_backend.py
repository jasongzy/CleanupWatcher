import ctypes
import json
import os
import resource
import select
import shutil
import socket
import subprocess
import tempfile
import time
from collections.abc import Callable


def invoke(binary: str, env: dict, verb: str, payload: str = "") -> dict:
    result = subprocess.run([binary, "ctl", verb], input=payload, text=True, capture_output=True, env=env, timeout=15)
    assert result.returncode == 0, result.stdout + result.stderr
    return json.loads(result.stdout)


def encoded(value: str) -> str:
    return value.encode().hex() or "-"


def config(
    root: str,
    action: int = 0,
    pattern: str = "*.log",
    recursive: bool = True,
    literal: bool = False,
    rule_id: str = "test",
) -> str:
    fields = [
        encoded(rule_id),
        encoded(root),
        encoded(pattern),
        str(action),
        str(int(recursive)),
        "1",
        str(int(literal)),
        "-",
    ]
    return "CW\n" + " ".join(fields) + "\n"


def wait_for(predicate: Callable[[], bool], timeout: float = 8.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.05)
    raise AssertionError("Timed out waiting for an event-driven cleanup")


def write_file(path: str, content: str = "sample"):
    with open(path, "w", encoding="utf-8") as stream:
        stream.write(content)


def restrict_descriptors():
    resource.setrlimit(resource.RLIMIT_NOFILE, (9, 9))


def subscribe(env: dict):
    client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    client.settimeout(8)
    client.connect(os.path.join(env["CW_TEST_HOME"], "control.sock"))
    client.sendall(b"observe\n")
    client.shutdown(socket.SHUT_WR)
    return client


def thread_count(pid: int) -> int:
    with open(f"/proc/{pid}/status", encoding="utf-8") as stream:
        return next(int(line.split()[1]) for line in stream if line.startswith("Threads:"))


def check_status_subscription(binary: str, env: dict, root: str, pid: int):
    invoke(binary, env, "rules", "CW\n")
    invoke(binary, env, "settled")
    wait_for(lambda: thread_count(pid) == 2)
    for _ in range(12):
        with subscribe(env) as client, client.makefile("r", encoding="utf-8") as stream:
            initial = json.loads(stream.readline())
            assert initial["watches"] == 0 and initial["sequence"] > 0
        wait_for(lambda: thread_count(pid) == 2)

    relay = subprocess.Popen([binary, "observe"], env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    try:
        assert json.loads(relay.stdout.readline())["watches"] == 0
        relay.stdin.close()
        relay.wait(timeout=3)
        assert relay.returncode == 0
    finally:
        if relay.poll() is None:
            relay.kill()
            relay.wait(timeout=3)
        relay.stdout.close()
    wait_for(lambda: thread_count(pid) == 2)

    scope = os.path.join(root, "subscription")
    for name in ("one", "two", "three"):
        os.makedirs(os.path.join(scope, name))
        write_file(os.path.join(scope, name, "junk.log"))
    rules = config(scope, 3, "*", False, rule_id="empty")
    rules += config(scope, rule_id="logs").split("\n", 1)[1]
    invoke(binary, env, "clear-records")
    with subscribe(env) as client, client.makefile("r", encoding="utf-8") as stream:
        initial = json.loads(stream.readline())
        with open(f"/proc/{pid}/stat", encoding="utf-8") as stats:
            before = sum(map(int, stats.read().split()[13:15]))
        assert not select.select([client], [], [], 0.4)[0], "Idle subscription sent an unsolicited snapshot"
        with open(f"/proc/{pid}/stat", encoding="utf-8") as stats:
            after = sum(map(int, stats.read().split()[13:15]))
        assert after - before <= 3, "Idle subscription kept the backend awake"
        invoke(binary, env, "rules", rules)
        sequence = initial["sequence"]
        counts = []
        started = time.monotonic()
        while True:
            state = json.loads(stream.readline())
            assert state["sequence"] >= sequence
            sequence = state["sequence"]
            counts.append(state["watches"])
            deleted = sum(entry["deleted"] for entry in state["records"])
            if deleted == 6 and state["watches"] == 1 and not state["checking"]:
                break
            assert time.monotonic() - started < 8, state
        assert 4 in counts, counts
        invoke(binary, env, "clear-records")
        while json.loads(stream.readline())["records"]:
            pass
    wait_for(lambda: thread_count(pid) == 2)
    invoke(binary, env, "rules", "CW\n")


def check_slow_subscriber(binary: str, env: dict, root: str, pid: int):
    scope = os.path.join(root, "slow-subscriber")
    os.mkdir(scope)
    rules = "CW\n" + "".join(
        config(scope, pattern="x" * 200, recursive=False, literal=True, rule_id=f"large-{index}").split("\n", 1)[1]
        for index in range(128)
    )
    invoke(binary, env, "rules", rules)
    invoke(binary, env, "settled")
    with subscribe(env) as client:
        relay = subprocess.Popen([binary, "observe"], env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        try:
            wait_for(lambda: thread_count(pid) >= 4)
            for index in range(40):
                invoke(binary, env, "boot", str(index % 2))
                time.sleep(0.05)
            started = time.monotonic()
            assert invoke(binary, env, "pause")["paused"]
            assert time.monotonic() - started < 3, "Slow subscriber blocked control commands"
            relay.stdin.close()
            relay.wait(timeout=3)
            assert relay.returncode == 0, "Blocked stdout prevented subscription cancellation"
            time.sleep(3.2)
            data = bytearray()
            while chunk := client.recv(65536):
                data.extend(chunk)
            lines = data.split(b"\n")
            assert len(lines) > 1, "Subscription did not deliver a complete snapshot"
            for line in lines[:-1]:
                assert json.loads(line)["rules"] == rules
        finally:
            if relay.poll() is None:
                relay.kill()
                relay.wait(timeout=3)
            relay.stdout.close()
    wait_for(lambda: thread_count(pid) == 2)
    invoke(binary, env, "boot", "0")
    invoke(binary, env, "rules", "CW\n")
    invoke(binary, env, "resume")


def check_control_during_rebuild(binary: str, env: dict, root: str):
    folder = os.path.join(root, "load")
    os.mkdir(folder)
    for index in range(5000):
        write_file(os.path.join(folder, f"{index}.bin"))
    payload = "CW\n" + "".join(
        config(folder, pattern="*.never", recursive=False, rule_id=f"load-{index}").split("\n", 1)[1]
        for index in range(128)
    )
    invoke(binary, env, "pause")
    invoke(binary, env, "rules", payload)
    invoke(binary, env, "resume")
    waiting = []
    try:
        for _ in range(4):
            client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            waiting.append(client)
            client.connect(os.path.join(env["CW_TEST_HOME"], "control.sock"))
            client.sendall(b"settled\n")
            client.shutdown(socket.SHUT_WR)
        time.sleep(0.05)
        assert invoke(binary, env, "status")["checking"], "The load fixture did not keep rebuild active"
        invoke(binary, env, "pause")
    finally:
        for client in waiting:
            client.close()
    invoke(binary, env, "rules", "CW\n")


def check_failed_scan_recovers(binary: str, env: dict, root: str):
    scope = os.path.join(root, "recovery")
    junk = os.path.join(scope, "Junk")
    os.makedirs(junk)
    sentinel = os.path.join(root, "precious.bin")
    write_file(sentinel, "sentinel")
    link = os.path.join(junk, "link")
    os.symlink(sentinel, link)
    invoke(binary, env, "pause")
    invoke(binary, env, "rules", config(scope, 2, "Junk", False, rule_id="recovery"))
    invoke(binary, env, "resume")
    assert invoke(binary, env, "settled")["health"]["recovery"] == "symlink"
    assert os.path.exists(junk) and os.path.exists(sentinel)
    os.unlink(link)
    wait_for(lambda: not os.path.exists(junk))
    assert os.path.exists(sentinel), "Recovery crossed the rule scope"
    incoming = os.path.join(root, "incoming")
    os.mkdir(incoming)
    os.symlink(sentinel, os.path.join(incoming, "link"))
    os.rename(incoming, junk)
    wait_for(lambda: invoke(binary, env, "status")["health"]["recovery"] == "symlink")
    time.sleep(2)
    os.unlink(link)
    wait_for(lambda: not os.path.exists(junk))
    assert os.path.exists(sentinel), "Incoming directory recovery crossed the rule scope"
    missing = os.path.join(scope, "Later", "sub")
    rules = config(scope, recursive=False, rule_id="existing")
    rules += config(missing, rule_id="absent").split("\n", 1)[1]
    invoke(binary, env, "rules", rules)
    assert invoke(binary, env, "settled")["health"]["absent"].startswith("storage")
    active_file = os.path.join(scope, "active.log")
    write_file(active_file)
    wait_for(lambda: not os.path.exists(active_file))
    os.makedirs(missing)
    appeared = os.path.join(missing, "appeared.log")
    write_file(appeared)
    wait_for(lambda: not os.path.exists(appeared))
    assert invoke(binary, env, "status")["health"]["absent"] == ""
    invoke(binary, env, "rules", "CW\n")


def check_concurrent_pause(binary: str, env: dict):
    for _ in range(4):
        clients = []
        try:
            for _ in range(4):
                client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                clients.append(client)
                client.settimeout(5)
                client.connect(os.path.join(env["CW_TEST_HOME"], "control.sock"))
                client.sendall(b"pause")
            for client in clients:
                client.shutdown(socket.SHUT_WR)
            for client in clients:
                with client.makefile("r", encoding="utf-8") as stream:
                    response = json.load(stream)
                assert not response.get("error") and response["paused"], response
        finally:
            for client in clients:
                client.close()
    invoke(binary, env, "resume")


def check_empty_children(binary: str, env: dict, root: str):
    scope = os.path.join(root, "children")
    tree = os.path.join(scope, "Tree")
    deep = os.path.join(tree, "Deep")
    os.makedirs(deep)
    hidden = os.path.join(scope, "Hidden")
    os.mkdir(hidden)
    write_file(os.path.join(hidden, ".nomedia"), "")
    occupied = os.path.join(scope, "Occupied")
    os.mkdir(occupied)
    content = os.path.join(occupied, "keep.bin")
    write_file(content)
    empty = os.path.join(scope, "Empty")
    os.mkdir(empty)
    invoke(binary, env, "rules", config(scope, 3, "*", False))
    invoke(binary, env, "settled")
    wait_for(lambda: not os.path.exists(empty))
    wait_for(lambda: invoke(binary, env, "status")["watches"] == 4)
    assert os.path.exists(deep) and os.path.exists(os.path.join(hidden, ".nomedia"))
    os.remove(content)
    wait_for(lambda: not os.path.exists(occupied))
    nested = os.path.join(deep, "Nested")
    os.mkdir(nested)
    time.sleep(2)
    assert os.path.exists(nested) and invoke(binary, env, "status")["watches"] == 3
    os.rmdir(nested)
    os.rmdir(deep)
    wait_for(lambda: not os.path.exists(tree))
    incoming = os.path.join(root, "incoming-children")
    os.mkdir(incoming)
    write_file(os.path.join(incoming, "keep.bin"))
    moved = os.path.join(scope, "Moved")
    os.rename(incoming, moved)
    wait_for(lambda: invoke(binary, env, "status")["watches"] == 3)
    os.remove(os.path.join(moved, "keep.bin"))
    wait_for(lambda: not os.path.exists(moved))
    os.mkdir(moved)
    write_file(os.path.join(moved, "keep.bin"))
    wait_for(lambda: invoke(binary, env, "status")["watches"] == 3)
    os.rename(moved, incoming)
    wait_for(lambda: invoke(binary, env, "status")["watches"] == 2)
    os.remove(os.path.join(incoming, "keep.bin"))
    time.sleep(2)
    assert os.path.exists(incoming) and os.path.exists(scope)
    write_file(os.path.join(scope, "keep.log"))
    assert invoke(binary, env, "preview", "test")["paths"] == []
    records = [entry for entry in invoke(binary, env, "status")["records"] if entry["root"] == scope]
    assert records and all(entry["name"] == "*" and entry["id"] == "test" for entry in records)
    assert any("Empty" in entry["paths"] for entry in records)
    invoke(binary, env, "rules", "CW\n")


def check_unrelated_moves(binary: str, env: dict, root: str):
    scope = os.path.join(root, "unrelated")
    noise = os.path.join(scope, "noise")
    renamed = os.path.join(scope, "renamed")
    os.makedirs(noise)
    for action, name in ((0, "target.log"), (2, "Junk")):
        invoke(binary, env, "rules", config(scope, action, name, False))
        invoke(binary, env, "settled")
        target = os.path.join(scope, name)
        if action == 0:
            write_file(target)
        else:
            os.mkdir(target)
            write_file(os.path.join(target, "child.bin"))
        deadline = time.monotonic() + 5
        while os.path.exists(target) and time.monotonic() < deadline:
            os.rename(noise, renamed)
            os.rename(renamed, noise)
            time.sleep(0.05)
        assert not os.path.exists(target), "Unrelated directory moves postponed cleanup"
    invoke(binary, env, "rules", "CW\n")


def check_idle_directories(binary: str, env: dict, root: str):
    scope = os.path.join(root, "idle-directories")
    child = os.path.join(scope, "child")
    os.makedirs(child)
    os.symlink(root, os.path.join(child, "link"))
    libc = ctypes.CDLL(None, use_errno=True)
    notifications = libc.inotify_init1(os.O_NONBLOCK | os.O_CLOEXEC)
    assert notifications >= 0, ctypes.get_errno()
    try:
        in_open = 0x20
        assert libc.inotify_add_watch(notifications, os.fsencode(child), in_open) >= 0, ctypes.get_errno()
        for action, pattern, error in ((0, "*.log", ""), (2, "child", "symlink")):
            invoke(binary, env, "rules", config(scope, action, pattern))
            invoke(binary, env, "resume")
            assert invoke(binary, env, "settled")["health"]["test"] == error
            time.sleep(2)
            while select.select([notifications], [], [], 0)[0]:
                os.read(notifications, 65536)
            assert not select.select([notifications], [], [], 3)[0], "Directory reads triggered repeated cleanup scans"
    finally:
        os.close(notifications)
        invoke(binary, env, "rules", "CW\n")


if __name__ == "__main__":
    assert os.geteuid() == 0, "Run in the isolated Linux test environment as root"
    binary = os.path.abspath("build/native-tests/test-daemon")
    root = tempfile.mkdtemp(prefix="cleanupwatcher-test-")
    env = dict(os.environ, CW_TEST_HOME=os.path.join(root, "backend"))
    folder = os.path.join(root, "files")
    os.mkdir(folder)
    process = subprocess.Popen([binary, "serve"], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        wait_for(lambda: os.path.exists(os.path.join(env["CW_TEST_HOME"], "control.sock")))
        check_status_subscription(binary, env, root, process.pid)
        check_slow_subscriber(binary, env, root, process.pid)
        check_control_during_rebuild(binary, env, root)
        check_concurrent_pause(binary, env)
        check_idle_directories(binary, env, root)
        check_failed_scan_recovers(binary, env, root)
        check_unrelated_moves(binary, env, root)
        check_empty_children(binary, env, root)
        invoke(binary, env, "resume")
        maximum = config(folder)
        maximum = maximum[:-1] + " " * (128 * 1024 - len(maximum)) + "\n"
        invoke(binary, env, "rules", maximum)
        invoke(binary, env, "rules", config(folder))
        assert not invoke(binary, env, "settled")["checking"]
        path = os.path.join(folder, "event.log")
        write_file(path)
        wait_for(lambda: not os.path.exists(path))
        os.close(os.open(path, os.O_CREAT | os.O_RDONLY, 0o600))
        wait_for(lambda: not os.path.exists(path))
        invoke(binary, env, "pause")
        write_file(path)
        time.sleep(2)
        assert os.path.exists(path), "A paused backend deleted a file"
        preview = invoke(binary, env, "preview", "test")
        assert preview["paths"] == ["event.log"], preview
        result = invoke(binary, env, "clean", "test")
        assert result["deleted"] == 1, result
        assert invoke(binary, env, "status")["paused"]
        snapshot = invoke(binary, env, "status")
        assert 0 < snapshot["records"][-1]["time"] <= int(time.time() * 1000) + 1000
        assert snapshot["records"][-1]["name"] == "*.log"
        assert snapshot["records"][-1]["id"] == "test"
        assert snapshot["records"][-1]["root"] == folder
        assert snapshot["records"][-1]["paths"] == ["event.log"]
        cleared = invoke(binary, env, "clear-records")
        assert cleared["records"] == [] and cleared["paused"] and cleared["rules"] == snapshot["rules"]
        invoke(binary, env, "resume")
        assert not invoke(binary, env, "settled")["checking"]
        time.sleep(2)
        temporary = os.path.join(folder, "pending.bin")
        write_file(temporary)
        os.rename(temporary, path)
        wait_for(lambda: not os.path.exists(path))
        nested = os.path.join(folder, "@ancestor")
        os.mkdir(nested)
        path = os.path.join(nested, "nested.log")
        write_file(path)
        wait_for(lambda: not os.path.exists(path))
        burst = []
        for index in range(30):
            directory = os.path.join(folder, f"burst-{index}")
            os.mkdir(directory)
            log = os.path.join(directory, "event.log")
            write_file(log)
            burst.append(log)
        wait_for(lambda: all(not os.path.exists(log) for log in burst))
        wait_for(lambda: sum(record["deleted"] for record in invoke(binary, env, "status")["records"]) == 32)
        records = invoke(binary, env, "status")["records"]
        assert 0 < len(records) <= 99 and all(record["time"] > 0 for record in records)
        assert sum(record["deleted"] for record in records) == 32
        for record in records:
            assert len(record["paths"]) == record["deleted"]
            assert all(not os.path.exists(os.path.join(record["root"], path)) for path in record["paths"])
        invoke(binary, env, "rules", config(folder, 2, "Junk", False))
        junk = os.path.join(folder, "Junk")
        os.mkdir(junk)
        write_file(os.path.join(junk, "generated.bin"))
        wait_for(lambda: not os.path.exists(junk))
        os.mkdir(junk)
        write_file(os.path.join(junk, "manual.bin"))
        wait_for(lambda: invoke(binary, env, "status")["watches"] >= 2)
        result = invoke(binary, env, "clean", "test")
        assert result["error"] == "", result
        assert not os.path.exists(junk)
        invoke(binary, env, "rules", config(folder, 1, "Empty", False))
        empty = os.path.join(folder, "Empty")
        os.mkdir(empty)
        occupied = os.path.join(empty, "keep.bin")
        write_file(occupied)
        time.sleep(2)
        assert os.path.exists(occupied)
        os.remove(occupied)
        wait_for(lambda: not os.path.exists(empty))
        os.mkdir(empty)
        child = os.path.join(empty, "child")
        os.mkdir(child)
        deep = os.path.join(child, "deep")
        os.mkdir(deep)
        occupied = os.path.join(deep, "keep.bin")
        write_file(occupied, "")
        time.sleep(2)
        assert os.path.exists(occupied)
        os.remove(occupied)
        wait_for(lambda: not os.path.exists(empty))
        os.makedirs(deep)
        wait_for(lambda: not os.path.exists(empty))
        duplicate = config(folder, pattern="overlap-*.log", recursive=False, rule_id="one")
        duplicate += config(folder, pattern="overlap-*.log", recursive=False, rule_id="two").split("\n", 1)[1]
        invoke(binary, env, "rules", duplicate)
        assert invoke(binary, env, "settled")["watches"] == 1
        invoke(binary, env, "clear-records")
        overlap_file = os.path.join(folder, "overlap-once.log")
        write_file(overlap_file)
        wait_for(lambda: not os.path.exists(overlap_file))
        wait_for(lambda: sum(record["deleted"] for record in invoke(binary, env, "status")["records"]) >= 1)
        assert sum(record["deleted"] for record in invoke(binary, env, "status")["records"]) == 1
        overlap = os.path.join(folder, "Overlap")
        os.makedirs(os.path.join(overlap, "child"))
        write_file(os.path.join(overlap, "last.tmp"))
        combined = config(folder, 1, "Overlap", False, rule_id="parent")
        combined += config(overlap, pattern="*.tmp", recursive=False, rule_id="child").split("\n", 1)[1]
        invoke(binary, env, "rules", combined)
        assert invoke(binary, env, "settled")["watches"] == 3
        wait_for(lambda: not os.path.exists(overlap))
        invoke(binary, env, "rules", config(folder, pattern="literal*.log", recursive=False, literal=True))
        exact = os.path.join(folder, "literal*.log")
        unmatched = os.path.join(folder, "literal-other.log")
        write_file(unmatched, "sentinel")
        write_file(exact)
        wait_for(lambda: not os.path.exists(exact))
        assert os.path.exists(unmatched), "A literal rule matched a wildcard expansion"
        invoke(binary, env, "pause")
        limited = [os.path.join(folder, f"rate-{index}.log") for index in range(512)]
        for log in limited:
            write_file(log)
        invoke(binary, env, "rules", config(folder, pattern="rate-*.log", recursive=False, rule_id="rate"))
        invoke(binary, env, "resume")
        wait_for(lambda: all(not os.path.exists(log) for log in limited), timeout=20)
        deferred = os.path.join(folder, "rate-deferred.log")
        write_file(deferred)
        wait_for(lambda: invoke(binary, env, "status")["health"].get("rate") == "rate")
        assert os.path.exists(deferred)
        vanished = os.path.join(folder, "rate-vanished.log")
        write_file(vanished)
        os.unlink(vanished)
        time.sleep(2)
        assert invoke(binary, env, "status")["health"]["rate"] == "rate"
        wait_for(lambda: not os.path.exists(deferred), timeout=65)
        invoke(binary, env, "rules", "CW\n")
        wait_for(lambda: not invoke(binary, env, "status")["checking"])
        with open(f"/proc/{process.pid}/stat", encoding="utf-8") as stream:
            before = sum(map(int, stream.read().split()[13:15]))
        time.sleep(2)
        with open(f"/proc/{process.pid}/stat", encoding="utf-8") as stream:
            after = sum(map(int, stream.read().split()[13:15]))
        assert after - before <= 3, f"Unexpected idle CPU use: {after - before} ticks"
        with subscribe(env) as client, client.makefile("r", encoding="utf-8") as stream:
            assert json.loads(stream.readline())["running"]
            invoke(binary, env, "stop")
            assert stream.readline() == "", "Backend exit left a subscription open"
        process.wait(timeout=5)
        assert process.returncode == 0
        failed = subprocess.run(
            [binary, "serve"],
            env=env,
            capture_output=True,
            text=True,
            timeout=5,
            preexec_fn=restrict_descriptors,
        )
        assert failed.returncode == 1 and json.loads(failed.stdout)["error"] == "watch", failed.stdout
        print("Backend IPC, event delivery, pause, preview, manual cleanup, recursive watches and idle checks passed")
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)
        assert os.path.realpath(root).startswith("/tmp/cleanupwatcher-test-")
        shutil.rmtree(root)
