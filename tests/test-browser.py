import json
import os
import shutil
import subprocess
import tempfile

from test_backend import encoded, write_file


def browse(binary: str, env: dict, path: str, cursor: str = "-", success: bool = True) -> dict:
    result = subprocess.run(
        [binary, "browse"], input=f"{encoded(path)} {cursor}", text=True, capture_output=True, env=env, timeout=10
    )
    assert (result.returncode == 0) == success, result.stdout + result.stderr
    return json.loads(result.stdout)


if __name__ == "__main__":
    assert os.geteuid() == 0, "Run in the isolated Linux test environment as root"
    binary = os.path.abspath("build/native-tests/test-daemon")
    root = tempfile.mkdtemp(prefix="cleanupwatcher-test-")
    home = os.path.join(root, "backend")
    env = dict(os.environ, CW_TEST_HOME=home)
    folder = os.path.join(root, "files")
    os.mkdir(folder)
    try:
        names = [f"file-{index:03}.log" for index in range(300)] + [
            ".hidden",
            ".A",
            ".a",
            "!first",
            "A.log",
            "a.log",
            "B.log",
            "alpha.log",
            "z.log",
            "Ä.log",
            "ä.log",
            "Ω.log",
            "ω.log",
            "Я.log",
            "я.log",
            "空 格.log",
            "a*?[].log",
            "';$(echo test)",
        ]
        for name in names:
            write_file(os.path.join(folder, name), "sentinel")
        os.utime(os.path.join(folder, ".hidden"), (1_700_000_123, 1_700_000_123))
        os.mkdir(os.path.join(folder, "directory"))
        directories = [f"{'Dir' if index % 2 else 'dir'}-{index:03}" for index in range(260)] + [
            ".Hidden-dir",
            ".hidden-dir",
            "!dir",
            "Alpha-dir",
            "z-dir",
            "Ä-dir",
            "ä-dir",
        ]
        for name in directories:
            os.mkdir(os.path.join(folder, name))
        os.symlink(os.path.join(folder, "directory"), os.path.join(folder, "dir-link"))
        os.symlink(os.path.join(folder, ".hidden"), os.path.join(folder, "file-link"))
        os.mkfifo(os.path.join(folder, "pipe"))
        raw = os.fsencode(folder) + b"/\xff"
        with open(raw, "wb") as stream:
            stream.write(b"sentinel")
        first = browse(binary, env, folder)
        assert bytes.fromhex(first["path"]).decode() == folder
        assert len(first["entries"]) == 256 and first["cursor"] != "-"
        entries = first["entries"]
        cursor = first["cursor"]
        cursors = set()
        while cursor != "-":
            assert cursor not in cursors
            cursors.add(cursor)
            page = browse(binary, env, folder, cursor)
            assert 0 < len(page["entries"]) <= 256
            entries += page["entries"]
            cursor = page["cursor"]
        actual = {bytes.fromhex(entry["name"]): entry for entry in entries}
        expected = {
            os.fsencode(name) for name in names + directories + ["directory", "dir-link", "file-link", "pipe"]
        } | {b"\xff"}
        assert set(actual) == expected and len(entries) == len(expected)
        directory_names = {os.fsencode(name) for name in directories + ["directory", "dir-link"]}
        ordered = sorted(
            expected - {b"\xff"},
            key=lambda name: (name not in directory_names, not name.startswith(b"."), name.decode().lower(), name),
        )
        assert [bytes.fromhex(entry["name"]) for entry in entries if bytes.fromhex(entry["name"]) != b"\xff"] == ordered
        assert actual[b"dir-link"]["type"] == "link" and actual[b"dir-link"]["directory"]
        assert actual[b"file-link"]["type"] == "link" and not actual[b"file-link"]["directory"]
        assert actual[b"pipe"]["type"] == "other"
        assert actual[b"file-000.log"]["size"] == 8 and actual[b"directory"]["size"] == -1
        assert actual[b".hidden"]["modified"] == 1_700_000_123_000
        for name, entry in actual.items():
            assert entry["modified"] == os.lstat(os.fsencode(folder) + b"/" + name).st_mtime_ns // 1_000_000
        assert bytes.fromhex(browse(binary, env, os.path.join(folder, "dir-link"))["path"]).decode() == os.path.join(
            folder, "directory"
        )
        assert browse(binary, env, "relative", success=False)["error"] == "path"
        assert browse(binary, env, folder + "\0", success=False)["error"] == "config"
        for cursor in ["d", "xname", "dfoo/bar", "d.", "f..", "f" + "x" * 256]:
            assert browse(binary, env, folder, encoded(cursor), success=False)["error"] == "path"
        boundary = os.path.join(root, "boundary")
        os.mkdir(boundary)
        for index in range(255):
            os.mkdir(os.path.join(boundary, f"dir-{index:03}"))
        last_dir = "Z" * 255
        next_dir = "z" * 255
        os.mkdir(os.path.join(boundary, last_dir))
        os.mkdir(os.path.join(boundary, next_dir))
        write_file(os.path.join(boundary, ".file"), "sentinel")
        page = browse(binary, env, boundary)
        assert len(page["entries"]) == 256 and all(entry["directory"] for entry in page["entries"])
        assert bytes.fromhex(page["entries"][-1]["name"]).decode() == last_dir
        os.rmdir(os.path.join(boundary, last_dir))
        page = browse(binary, env, boundary, page["cursor"])
        assert [bytes.fromhex(entry["name"]).decode() for entry in page["entries"]] == [next_dir, ".file"]
        assert page["cursor"] == "-"
        assert not os.path.exists(home), "Browsing created backend state"
        for name in names:
            with open(os.path.join(folder, name), encoding="utf-8") as stream:
                assert stream.read() == "sentinel"
        print("Read-only root browsing, pagination, links, special files and filename transport checks passed")
    finally:
        assert os.path.abspath(root).startswith("/tmp/cleanupwatcher-test-")
        shutil.rmtree(root)
