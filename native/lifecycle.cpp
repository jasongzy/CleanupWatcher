#include "lifecycle.h"
#include <algorithm>
#include <charconv>
#include <chrono>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <linux/openat2.h>
#include <sstream>
#include <stdexcept>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <thread>
#include <unistd.h>

namespace cw {
#ifdef CLEANUPWATCHER_TEST
static const std::string home = getenv("CW_TEST_HOME") ? getenv("CW_TEST_HOME") : "/tmp/cleanupwatcher-test-backend";
static const std::string modules = home.substr(0, home.rfind('/')) + "/modules";
#else
static const std::string home = "/data/adb/cleanupwatcher", modules = "/data/adb/modules";
#endif
static const std::string socketPath = home + "/control.sock", binaryPath = home + "/cleanupwatcherd";
static const std::string modulePath = modules + "/cleanupwatcher";
const char *HOME = home.c_str();
const char *SOCKET = socketPath.c_str();
const char *BINARY = binaryPath.c_str();
static const std::vector<std::string> backendFiles{"cleanupwatcherd",
                                                   "rules",
                                                   "paused",
                                                   "boot",
                                                   "lock",
                                                   "guardian.lock",
                                                   "removing",
                                                   "control.sock",
                                                   "cleanupwatcherd.new",
                                                   "rules.new",
                                                   "paused.new",
                                                   "boot.new",
                                                   "removing.new"};
static const std::vector<std::string> moduleFiles{"module.prop",     "skip_mount",     "service.sh",    "action.sh",
                                                  "action.sh.new",   "disable",        "remove",        "update",
                                                  "module.prop.new", "skip_mount.new", "service.sh.new"};

void writeFull(int fd, const std::string &value) {
    size_t done = 0;
    while (done < value.size()) {
        auto size = write(fd, value.data() + done, value.size() - done);
        if (size < 0 && errno == EINTR)
            continue;
        if (size <= 0)
            throw std::runtime_error("write");
        done += static_cast<size_t>(size);
    }
}
std::string readFull(int fd, size_t limit) {
    std::string out;
    char buffer[4096];
    while (true) {
        auto size = read(fd, buffer, sizeof(buffer));
        if (size < 0 && errno == EINTR)
            continue;
        if (size < 0)
            throw std::runtime_error("read");
        if (!size)
            return out;
        if (out.size() + size > limit)
            throw std::runtime_error("limit");
        out.append(buffer, size);
    }
}
static Fd folder(const std::string &path) {
    struct open_how how{};
    how.flags = O_RDONLY | O_DIRECTORY | O_CLOEXEC;
    how.resolve = RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS;
    Fd fd(static_cast<int>(syscall(SYS_openat2, AT_FDCWD, path.c_str(), &how, sizeof(how))));
    if (fd.get() < 0) {
        if (errno == ENOENT)
            return fd;
        throw std::runtime_error(errno == ENOSYS ? "unsupported" : "management");
    }
    struct stat st{};
    if (fstat(fd.get(), &st) || st.st_uid != 0 || (st.st_mode & 0022))
        throw std::runtime_error("management");
    return fd;
}
Fd stateDir(bool create) {
    if (create) {
#ifndef CLEANUPWATCHER_TEST
        if (mkdir("/data/adb", 0700) && errno != EEXIST)
            throw std::runtime_error("install");
        auto base = folder("/data/adb");
#endif
        if (mkdir(HOME, 0700) && errno != EEXIST)
            throw std::runtime_error("install");
    }
    auto dir = folder(home);
    struct stat st{};
    if (dir.get() >= 0 && (fstat(dir.get(), &st) || (st.st_mode & 0777) != 0700))
        throw std::runtime_error("management");
    return dir;
}
static std::string readAt(int dir, const char *name, const std::string &fallback) {
    Fd fd(openat(dir, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK));
    if (fd.get() < 0 && errno == ENOENT)
        return fallback;
    struct stat st{};
    if (fd.get() < 0 || fstat(fd.get(), &st) || !S_ISREG(st.st_mode) || st.st_uid != 0 || st.st_nlink != 1)
        throw std::runtime_error("management");
    return readFull(fd.get());
}
std::string load(const char *name, const std::string &fallback) {
    auto dir = stateDir();
    return readAt(dir.get(), name, fallback);
}
static void saveAt(int dir, const char *name, const std::string &value, mode_t mode) {
    auto temp = std::string(name) + ".new";
    if (unlinkat(dir, temp.c_str(), 0) && errno != ENOENT)
        throw std::runtime_error("write");
    Fd fd(openat(dir, temp.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, mode));
    if (fd.get() < 0)
        throw std::runtime_error("write");
    writeFull(fd.get(), value);
    if (fsync(fd.get()) || renameat(dir, temp.c_str(), dir, name) || fsync(dir))
        throw std::runtime_error("write");
}
void save(const char *name, const std::string &value, mode_t mode) {
    auto dir = stateDir();
    saveAt(dir.get(), name, value, mode);
}
Fd connectServer() {
    Fd fd(socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0));
    struct sockaddr_un address{};
    address.sun_family = AF_UNIX;
    if (socketPath.size() >= sizeof(address.sun_path))
        throw std::runtime_error("management");
    std::strcpy(address.sun_path, SOCKET);
    if (fd.get() < 0 || connect(fd.get(), reinterpret_cast<sockaddr *>(&address), sizeof(address)))
        throw std::runtime_error("offline");
    struct ucred peer{};
    socklen_t size = sizeof(peer);
    if (getsockopt(fd.get(), SOL_SOCKET, SO_PEERCRED, &peer, &size) || peer.uid != 0)
        throw std::runtime_error("root");
    struct timeval timeout{120, 0};
    setsockopt(fd.get(), SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    return fd;
}
Fd lockFile(int dir, const char *name) {
    Fd fd(openat(dir, name, O_CREAT | O_RDWR | O_NOFOLLOW | O_CLOEXEC, 0600));
    struct stat st{};
    if (fd.get() < 0 || fstat(fd.get(), &st) || !S_ISREG(st.st_mode) || st.st_uid != 0 || st.st_nlink != 1)
        throw std::runtime_error("management");
    return fd;
}
static void waitLock(int fd) {
    for (unsigned attempt = 0; attempt < 350; ++attempt) {
        if (flock(fd, LOCK_EX | LOCK_NB) == 0)
            return;
        if (errno != EWOULDBLOCK && errno != EINTR)
            throw std::runtime_error("management");
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    throw std::runtime_error("timeout");
}
static void stopBackend() {
    try {
        auto fd = connectServer();
        writeFull(fd.get(), "stop");
        shutdown(fd.get(), SHUT_WR);
        if (readFull(fd.get()).find("\"stopped\":true") == std::string::npos)
            throw std::runtime_error("management");
    } catch (const std::exception &e) {
        if (std::string(e.what()) != "offline")
            throw;
    }
}
void installBackend() {
    auto dir = stateDir();
    saveAt(dir.get(), "removing", "1", 0600);
    try {
        stopBackend();
        auto guardian = lockFile(dir.get(), "guardian.lock");
        waitLock(guardian.get());
        auto lock = lockFile(dir.get(), "lock");
        waitLock(lock.get());
        Fd self(open("/proc/self/exe", O_RDONLY | O_CLOEXEC));
        save("cleanupwatcherd", readFull(self.get(), 16 * 1024 * 1024), 0700);
        if (unlinkat(dir.get(), "removing", 0) && errno != ENOENT)
            throw std::runtime_error("management");
    } catch (...) {
        unlinkat(dir.get(), "removing", 0);
        throw;
    }
}
static std::vector<std::string> entries(int dir) {
    int copy = openat(dir, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (copy < 0)
        throw std::runtime_error("management");
    DIR *stream = fdopendir(copy);
    if (!stream) {
        close(copy);
        throw std::runtime_error("management");
    }
    std::vector<std::string> names;
    errno = 0;
    while (auto *entry = readdir(stream)) {
        std::string name = entry->d_name;
        if (name != "." && name != "..")
            names.push_back(name);
    }
    int error = errno;
    closedir(stream);
    if (error)
        throw std::runtime_error("management");
    return names;
}
static void validateFiles(int dir, const std::vector<std::string> &allowed) {
    for (const auto &name : entries(dir)) {
        struct stat st{};
        if (std::find(allowed.begin(), allowed.end(), name) == allowed.end() ||
            fstatat(dir, name.c_str(), &st, AT_SYMLINK_NOFOLLOW) || st.st_uid != 0 ||
            (name == "control.sock" ? !S_ISSOCK(st.st_mode) : !S_ISREG(st.st_mode) || st.st_nlink != 1))
            throw std::runtime_error("uninstall_unknown: " + name);
    }
}
static void removeFlat(const std::string &path, int dir, const std::vector<std::string> &allowed) {
    validateFiles(dir, allowed);
    auto parent = folder(path.substr(0, path.rfind('/')));
    auto name = path.substr(path.rfind('/') + 1);
    struct stat held{}, live{};
    if (parent.get() < 0 || fstat(dir, &held) || fstatat(parent.get(), name.c_str(), &live, AT_SYMLINK_NOFOLLOW) ||
        held.st_dev != live.st_dev || held.st_ino != live.st_ino)
        throw std::runtime_error("changed");
    for (const auto &file : allowed)
        if (unlinkat(dir, file.c_str(), 0) && errno != ENOENT)
            throw std::runtime_error("management");
    if (fsync(dir) || unlinkat(parent.get(), name.c_str(), AT_REMOVEDIR) || fsync(parent.get()))
        throw std::runtime_error("management");
}
void checkReleasedScopes(const std::vector<Rule> &previous, const std::vector<Rule> &next) {
    for (const auto &rule : previous) {
        if (std::any_of(next.begin(), next.end(),
                        [&](const Rule &r) { return backingPath(r.root) == backingPath(rule.root); }))
            continue;
        auto root = openRoot(rule.root, true);
        if (root.get() < 0)
            continue;
        struct stat st{};
        if (fstatat(root.get(), ".cleanupwatcher", &st, AT_SYMLINK_NOFOLLOW)) {
            if (errno == ENOENT)
                continue;
            throw std::runtime_error("staging");
        }
        Fd stage(openat(root.get(), ".cleanupwatcher", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
        struct stat held{};
        if (!S_ISDIR(st.st_mode) || st.st_uid != 0 || (st.st_mode & 0777) != 0700 || stage.get() < 0 ||
            fstat(stage.get(), &held) || held.st_dev != st.st_dev || held.st_ino != st.st_ino)
            throw std::runtime_error("staging");
        if (!entries(stage.get()).empty())
            throw std::runtime_error("retained");
        if (unlinkat(root.get(), ".cleanupwatcher", AT_REMOVEDIR))
            throw std::runtime_error("staging");
        refreshSharedPath(rule.root, ".cleanupwatcher");
    }
}
void removeBackend() {
    auto dir = stateDir(false);
    if (dir.get() < 0)
        return;
    saveAt(dir.get(), "removing", "1", 0600);
    try {
        saveAt(dir.get(), "boot", "0", 0600);
        stopBackend();
        auto guardian = lockFile(dir.get(), "guardian.lock");
        waitLock(guardian.get());
        auto lock = lockFile(dir.get(), "lock");
        waitLock(lock.get());
        validateFiles(dir.get(), backendFiles);
        checkReleasedScopes(decodeRules(readAt(dir.get(), "rules", "CW\n")));
        removeFlat(home, dir.get(), backendFiles);
    } catch (...) {
        unlinkat(dir.get(), "removing", 0);
        throw;
    }
}
static bool installedAt(int dir) {
    struct stat st{};
    return fstatat(dir, "cleanupwatcherd", &st, AT_SYMLINK_NOFOLLOW) == 0 && S_ISREG(st.st_mode) && st.st_uid == 0 &&
           st.st_nlink == 1 && (st.st_mode & 0111);
}
std::string configureBackend(const std::string &payload) {
    auto next = decodeRules(payload);
    auto dir = stateDir(false);
    if (dir.get() < 0 || !installedAt(dir.get()) || faccessat(dir.get(), "removing", F_OK, 0) == 0)
        throw std::runtime_error("offline");
    try {
        auto client = connectServer();
        writeFull(client.get(), "rules\n" + encodeRules(next));
        shutdown(client.get(), SHUT_WR);
        return readFull(client.get());
    } catch (const std::exception &e) {
        if (std::string(e.what()) != "offline")
            throw;
    }
    auto lock = lockFile(dir.get(), "lock");
    if (flock(lock.get(), LOCK_EX | LOCK_NB) || faccessat(dir.get(), "removing", F_OK, 0) == 0)
        throw std::runtime_error("offline");
    checkReleasedScopes(decodeRules(readAt(dir.get(), "rules", "CW\n")), next);
    saveAt(dir.get(), "rules", encodeRules(next), 0600);
    return "{\"configured\":true}";
}
static Fd moduleDir() {
    auto parent = folder(modules);
    if (parent.get() < 0)
        return Fd();
    auto dir = folder(modulePath);
    if (dir.get() >= 0 && readAt(dir.get(), "module.prop", "").find("id=cleanupwatcher\n") != 0)
        throw std::runtime_error("module");
    return dir;
}
void installModule() {
    auto parent = folder(modules);
    if (parent.get() < 0)
        throw std::runtime_error("magisk");
    auto existing = moduleDir();
    if (existing.get() >= 0)
        validateFiles(existing.get(), moduleFiles);
    if (mkdirat(parent.get(), "cleanupwatcher", 0700) && errno != EEXIST)
        throw std::runtime_error("module");
    auto dir = folder(modulePath);
    saveAt(dir.get(), "module.prop",
           "id=cleanupwatcher\nname=Cleanup Watcher\nversion=" + std::string(MODULE_VERSION_NAME) +
               "\nversionCode=" + std::to_string(MODULE_VERSION_CODE) +
               "\nauthor=jasongzy\n"
               "description=Start the Cleanup Watcher backend at boot\n",
           0600);
    saveAt(dir.get(), "skip_mount", "", 0600);
    saveAt(dir.get(), "service.sh",
           "#!/system/bin/sh\n[ -x /data/adb/cleanupwatcher/cleanupwatcherd ] && "
           "/data/adb/cleanupwatcher/cleanupwatcherd start\n",
           0700);
    for (const auto *name : {"disable", "remove", "update"})
        if (unlinkat(dir.get(), name, 0) && errno != ENOENT)
            throw std::runtime_error("module");
    saveAt(dir.get(), "action.sh",
           "#!/system/bin/sh\n/system/bin/am start --user 0 -n com.jasongzy.cleanupwatcher/.MainActivity\n", 0700);
    if (fsync(dir.get()))
        throw std::runtime_error("module");
}
void removeModule() {
    auto dir = moduleDir();
    if (dir.get() >= 0)
        removeFlat(modulePath, dir.get(), moduleFiles);
}
static std::string moduleProperty(const std::string &properties, const std::string &key) {
    std::istringstream stream(properties);
    std::string line, value, prefix = key + "=";
    bool found = false;
    while (std::getline(stream, line)) {
        if (!line.empty() && line.back() == '\r')
            line.pop_back();
        if (!line.starts_with(prefix))
            continue;
        if (found)
            return "";
        found = true;
        value = line.substr(prefix.size());
    }
    return value;
}
static int moduleVersion(const std::string &properties) {
    auto value = moduleProperty(properties, "versionCode");
    int version = 0;
    auto result = std::from_chars(value.data(), value.data() + value.size(), version);
    return result.ec == std::errc() && result.ptr == value.data() + value.size() && version > 0 ? version : 0;
}
std::string inspect(bool magisk) {
    auto dir = stateDir(false);
    bool installed = false, boot = false;
    if (dir.get() >= 0) {
        installed = installedAt(dir.get());
        boot = readAt(dir.get(), "boot", "0") == "1";
    }
    std::string module = "absent";
    int moduleCode = 0;
    std::string moduleName;
    try {
        auto folder = moduleDir();
        if (folder.get() >= 0) {
            validateFiles(folder.get(), moduleFiles);
            auto properties = readAt(folder.get(), "module.prop", "");
            moduleCode = moduleVersion(properties);
            moduleName = moduleProperty(properties, "version");
            module = faccessat(folder.get(), "remove", F_OK, 0) == 0    ? "removing"
                     : faccessat(folder.get(), "disable", F_OK, 0) == 0 ? "disabled"
                                                                        : "enabled";
        }
    } catch (const std::exception &) {
        module = "invalid";
    }
    return "{\"present\":" + std::string(dir.get() >= 0 ? "true" : "false") +
           ",\"installed\":" + (installed ? "true" : "false") + ",\"boot\":" + (boot ? "true" : "false") +
           ",\"magisk\":" + (magisk ? "true" : "false") + ",\"module\":" + json(module) +
           ",\"moduleVersionName\":" + json(moduleName) + ",\"moduleVersionCode\":" + std::to_string(moduleCode) +
           ",\"requiredModuleVersionCode\":" + std::to_string(MODULE_VERSION_CODE) +
           ",\"requiredBackendVersionCode\":" + std::to_string(BACKEND_VERSION_CODE) + "}";
}
} // namespace cw
