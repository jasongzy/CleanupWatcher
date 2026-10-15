import os
import shutil
import subprocess
import tempfile


if __name__ == "__main__":
    assert os.geteuid() == 0, "Run in the isolated Linux test environment as root"
    root = tempfile.mkdtemp(prefix="cleanupwatcher-test-")
    source = None
    mounted = False
    volume = os.path.join(root, "volume")
    os.mkdir(volume)
    try:
        if shutil.which("mount.drvfs"):
            source = tempfile.mkdtemp(prefix="casefold-", dir=os.path.abspath("build/native-tests"))
            windows = subprocess.check_output(["wslpath", "-w", source], text=True).strip()
            subprocess.run(["mount", "-t", "drvfs", windows, volume, "-o", "metadata,uid=0,gid=0"], check=True)
        else:
            image = os.path.join(root, "casefold.img")
            with open(image, "wb") as stream:
                stream.truncate(64 * 1024 * 1024)
            subprocess.run(["mkfs.ext4", "-q", "-F", "-O", "casefold", "-E", "encoding=utf8", image], check=True)
            subprocess.run(["mount", "-o", "loop", image, volume], check=True)
        mounted = True
        folder = os.path.join(volume, "files")
        os.mkdir(folder, 0o700)
        if source is None:
            subprocess.run(["chattr", "+F", folder], check=True)
        subprocess.run([os.path.abspath("build/native-tests/safety"), folder], check=True)
    finally:
        if mounted:
            subprocess.run(["umount", volume], check=True)
        if source:
            assert source.startswith(os.path.abspath("build/native-tests/casefold-"))
            shutil.rmtree(source)
        assert root.startswith("/tmp/cleanupwatcher-test-")
        shutil.rmtree(root)
