#include "core.h"
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <fnmatch.h>
#include <linux/fs.h>
#include <linux/fscrypt.h>
#include <linux/magic.h>
#include <linux/openat2.h>
#include <sstream>
#include <stdexcept>
#include <strings.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <sys/vfs.h>
#include <unistd.h>

namespace cw {
#ifdef CLEANUPWATCHER_TEST
std::function<void()> beforeCapture;
#endif
static constexpr size_t MAX_ITEMS = 512;
static constexpr size_t MAX_VISITED = 20000;
static constexpr const char *STAGING = ".cleanupwatcher";
static std::atomic<unsigned long> serial{0};
static void fail(const char *code) {
    throw std::runtime_error(std::string(code) + ": " + strerror(errno));
}
Fd::~Fd() {
    if (value_ >= 0)
        close(value_);
}
Fd::Fd(Fd &&other) noexcept : value_(other.value_) {
    other.value_ = -1;
}
Fd &Fd::operator=(Fd &&other) noexcept {
    if (this != &other) {
        if (value_ >= 0)
            close(value_);
        value_ = other.value_;
        other.value_ = -1;
    }
    return *this;
}
static struct stat statFd(int fd) {
    struct stat st{};
    if (fstat(fd, &st))
        fail("stat");
    return st;
}
static bool same(const struct stat &a, const struct stat &b) {
    return a.st_dev == b.st_dev && a.st_ino == b.st_ino && (a.st_mode & S_IFMT) == (b.st_mode & S_IFMT);
}
static bool under(const std::string &path, const std::string &parent) {
    return path == parent || path.starts_with(parent + "/");
}
static bool statAt(int fd, const std::string &path, struct stat &st) {
    int result = fstatat(fd, path.c_str(), &st, AT_SYMLINK_NOFOLLOW);
    if (result && errno != ENOENT && errno != ENOTDIR)
        fail("stat");
    return result == 0;
}
void requireKey(int fd) {
    struct fscrypt_get_policy_ex_arg policy{};
    policy.policy_size = sizeof(policy.policy);
    if (ioctl(fd, FS_IOC_GET_ENCRYPTION_POLICY_EX, &policy)) {
        if (errno == ENODATA || errno == ENOTTY || errno == EOPNOTSUPP)
            return;
        fail("storage");
    }
    struct fscrypt_get_key_status_arg status{};
    if (policy.policy.version == FSCRYPT_POLICY_V2) {
        status.key_spec.type = FSCRYPT_KEY_SPEC_TYPE_IDENTIFIER;
        memcpy(status.key_spec.u.identifier, policy.policy.v2.master_key_identifier, FSCRYPT_KEY_IDENTIFIER_SIZE);
    } else if (policy.policy.version == FSCRYPT_POLICY_V1) {
        status.key_spec.type = FSCRYPT_KEY_SPEC_TYPE_DESCRIPTOR;
        memcpy(status.key_spec.u.descriptor, policy.policy.v1.master_key_descriptor, FSCRYPT_KEY_DESCRIPTOR_SIZE);
    } else
        throw std::runtime_error("unsupported");
    if (ioctl(fd, FS_IOC_GET_ENCRYPTION_KEY_STATUS, &status) || status.status != FSCRYPT_KEY_STATUS_PRESENT)
        throw std::runtime_error("storage");
}
std::string hex(const std::string &value) {
    static constexpr char digits[] = "0123456789abcdef";
    std::string out;
    for (unsigned char ch : value) {
        out += digits[ch >> 4];
        out += digits[ch & 15];
    }
    return out.empty() ? "-" : out;
}
std::string unhex(const std::string &value, size_t maxBytes) {
    if (value == "-")
        return "";
    if (value.size() % 2 || value.size() / 2 > maxBytes)
        throw std::runtime_error("config");
    auto digit = [](char ch) -> int {
        if (ch >= '0' && ch <= '9')
            return ch - '0';
        if (ch >= 'a' && ch <= 'f')
            return ch - 'a' + 10;
        throw std::runtime_error("config");
    };
    std::string out;
    for (size_t i = 0; i < value.size(); i += 2)
        out += static_cast<char>((digit(value[i]) << 4) | digit(value[i + 1]));
    if (out.find('\0') != std::string::npos)
        throw std::runtime_error("config");
    return out;
}
std::string json(const std::string &value) {
    std::string out = "\"";
    static constexpr char digits[] = "0123456789abcdef";
    for (unsigned char ch : value) {
        if (ch == '"' || ch == '\\') {
            out += '\\';
            out += ch;
        } else if (ch < 32) {
            out += "\\u00";
            out += digits[ch >> 4];
            out += digits[ch & 15];
        } else
            out += ch;
    }
    return out + "\"";
}
bool validRelative(const std::string &value) {
    if (value.empty() || value.size() > 4096 || value.front() == '/' || value.back() == '/' ||
        value.find('\0') != std::string::npos)
        return false;
    std::istringstream input(value);
    std::string part;
    while (std::getline(input, part, '/'))
        if (part.empty() || part == "." || part == ".." || part.size() > 255)
            return false;
    return true;
}
std::string backingPath(const std::string &logical) {
#ifdef CLEANUPWATCHER_TEST
    if (logical.starts_with("/tmp/cleanupwatcher-test-") && validRelative(logical.substr(1)))
        return logical;
#endif
    std::string path = logical;
    for (const auto &alias : {std::string("/sdcard"), std::string("/storage/emulated/0")}) {
        if (under(path, alias)) {
            path = "/data/media/0" + path.substr(alias.size());
            break;
        }
    }
    if (path.empty() || path.front() != '/' || !validRelative(path.substr(1)))
        throw std::runtime_error("scope");
    std::istringstream components(path);
    std::string component;
    while (std::getline(components, component, '/'))
        if (strcasecmp(component.c_str(), STAGING) == 0)
            throw std::runtime_error("scope");
    if (under(path, "/data/media/0"))
        return path;
    const std::string privateRoot = "/data/user/0/";
    if (path.starts_with(privateRoot)) {
        auto suffix = path.substr(privateRoot.size());
        auto slash = suffix.find('/');
        auto package = suffix.substr(0, slash);
        if (slash != std::string::npos && package.find('.') != std::string::npos && package.front() != '.' &&
            package.back() != '.' && package.find("..") == std::string::npos &&
            package.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._") ==
                std::string::npos &&
            under(suffix.substr(slash + 1), "cache"))
            return path;
    }
    throw std::runtime_error("scope");
}
Fd openWithin(int root, const std::string &path, bool directory) {
    if (!validRelative(path))
        throw std::runtime_error("path");
    struct open_how how{};
    how.flags = O_CLOEXEC | (directory ? O_RDONLY | O_DIRECTORY : O_PATH) | O_NOFOLLOW;
    how.resolve = RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS | RESOLVE_NO_XDEV;
    int fd = static_cast<int>(syscall(SYS_openat2, root, path.c_str(), &how, sizeof(how)));
    if (fd < 0)
        fail(errno == ENOSYS || errno == EINVAL ? "unsupported" : "path");
    auto result = Fd(fd);
    if (S_ISLNK(statFd(fd).st_mode))
        throw std::runtime_error("symlink");
    if (directory)
        requireKey(fd);
    return result;
}
Fd openRoot(const std::string &logical, bool allowMissing) {
    std::string path = backingPath(logical);
    struct open_how how{};
    how.flags = O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW;
    how.resolve = RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS;
    int fd = static_cast<int>(syscall(SYS_openat2, AT_FDCWD, path.c_str(), &how, sizeof(how)));
    if (fd < 0 && errno == ENOENT && allowMissing)
        return Fd();
    if (fd < 0)
        fail(errno == ENOSYS ? "unsupported" : "storage");
    auto result = Fd(fd);
    requireKey(fd);
    return result;
}
std::vector<Rule> decodeRules(const std::string &value) {
    if (value.size() > 128 * 1024)
        throw std::runtime_error("config");
    std::istringstream input(value);
    std::string header, line;
    std::getline(input, header);
    if (header != "CW")
        throw std::runtime_error("config");
    std::vector<Rule> rules;
    while (std::getline(input, line)) {
        std::istringstream row(line);
        Rule rule;
        std::string id, root, pattern, whitelist, extra;
        int recursive, enabled, literal;
        if (!(row >> id >> root >> pattern >> rule.action >> recursive >> enabled >> literal >> whitelist) ||
            row >> extra)
            throw std::runtime_error("config");
        rule.id = unhex(id);
        rule.root = unhex(root);
        rule.pattern = unhex(pattern);
        rule.recursive = recursive == 1;
        rule.enabled = enabled == 1;
        rule.literal = literal == 1;
        if (rule.id.empty() || rule.id.size() > 64 || rule.pattern.empty() || rule.pattern.size() > 255 ||
            rule.pattern.find('/') != std::string::npos || rule.action < 0 || rule.action > 2 || recursive < 0 ||
            recursive > 1 || enabled < 0 || enabled > 1 || literal < 0 || literal > 1 || rules.size() >= 128)
            throw std::runtime_error("config");
        backingPath(rule.root);
        if (rule.action == 2 &&
            (rule.pattern == "." || rule.pattern == ".." || strcasecmp(rule.pattern.c_str(), STAGING) == 0))
            throw std::runtime_error("config");
        std::istringstream whitelistLines(unhex(whitelist, 64 * 1024));
        std::string entry;
        while (std::getline(whitelistLines, entry)) {
            if (!validRelative(entry) || rule.whitelist.size() >= 128)
                throw std::runtime_error("config");
            rule.whitelist.push_back(entry);
        }
        if (std::any_of(rules.begin(), rules.end(), [&](const Rule &r) { return r.id == rule.id; }))
            throw std::runtime_error("config");
        rules.push_back(std::move(rule));
    }
    return rules;
}
std::string encodeRules(const std::vector<Rule> &rules) {
    std::string out = "CW\n";
    for (const auto &r : rules) {
        std::string whitelist;
        for (const auto &x : r.whitelist)
            whitelist += (whitelist.empty() ? "" : "\n") + x;
        out += hex(r.id) + " " + hex(r.root) + " " + hex(r.pattern) + " " + std::to_string(r.action) + " " +
               std::to_string(r.recursive) + " " + std::to_string(r.enabled) + " " + std::to_string(r.literal) + " " +
               hex(whitelist) + "\n";
    }
    return out;
}
bool matchesName(const Rule &rule, const std::string &name) {
    return rule.literal || rule.action == 2 ? name == rule.pattern
                                            : fnmatch(rule.pattern.c_str(), name.c_str(), 0) == 0;
}
static std::string prefixAtDepth(const std::string &path, size_t depth) {
    size_t seen = 0;
    for (size_t i = 0; i < path.size(); ++i)
        if (path[i] == '/' && seen++ == depth)
            return path.substr(0, i);
    return path;
}
bool protectedPath(const Rule &rule, int root, const std::string &path, bool subtree) {
    std::istringstream input(path);
    std::string part;
    while (std::getline(input, part, '/'))
        if (strcasecmp(part.c_str(), STAGING) == 0)
            return true;
    auto depth = std::count(path.begin(), path.end(), '/');
    for (const auto &x : rule.whitelist) {
        if (under(path, x) || (subtree && under(x, path)))
            return true;
        auto whitelistDepth = std::count(x.begin(), x.end(), '/');
        if (whitelistDepth > depth && !subtree)
            continue;
        auto sharedDepth = static_cast<size_t>(std::min(depth, whitelistDepth));
        struct stat candidate{}, protectedItem{};
        if (statAt(root, prefixAtDepth(path, sharedDepth), candidate) &&
            statAt(root, prefixAtDepth(x, sharedDepth), protectedItem) && same(candidate, protectedItem))
            return true;
    }
    return false;
}
static std::vector<std::string> list(int fd, size_t limit = MAX_VISITED) {
    int copy = openat(fd, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (copy < 0)
        fail("list");
    DIR *dir = fdopendir(copy);
    if (!dir) {
        int error = errno;
        close(copy);
        errno = error;
        fail("list");
    }
    std::vector<std::string> names;
    errno = 0;
    while (auto *entry = readdir(dir)) {
        std::string name(entry->d_name);
        if (name != "." && name != "..")
            names.push_back(name);
        if (names.size() > limit) {
            closedir(dir);
            throw std::runtime_error("limit");
        }
        errno = 0;
    }
    int error = errno;
    closedir(dir);
    if (error) {
        errno = error;
        fail("list");
    }
    return names;
}
static bool exactName(int parent, const std::string &name) {
    struct stat st{};
    if (!statAt(parent, name, st))
        return false;
    struct statfs fs{};
    if (fstatfs(parent, &fs))
        fail("stat");
    auto type = static_cast<unsigned long>(fs.f_type);
    int flags = 0;
    if ((type == EXT4_SUPER_MAGIC || type == F2FS_SUPER_MAGIC) && ioctl(parent, FS_IOC_GETFLAGS, &flags) == 0 &&
        !(flags & FS_CASEFOLD_FL))
        return true;
    auto names = list(parent);
    return std::find(names.begin(), names.end(), name) != names.end();
}
static bool exactPath(int root, const std::string &path) {
    Fd parent(dup(root));
    std::istringstream input(path);
    std::string part;
    while (std::getline(input, part, '/')) {
        if (!exactName(parent.get(), part))
            return false;
        if (input.peek() != std::char_traits<char>::eof())
            parent = openWithin(parent.get(), part, true);
    }
    return true;
}
Plan plan(const Rule &rule, std::string focus) {
    Plan out;
    out.rule = rule;
    try {
        out.root = openRoot(rule.root);
        struct stat stage{};
        if (fstatat(out.root.get(), STAGING, &stage, AT_SYMLINK_NOFOLLOW) == 0) {
            auto staging = openWithin(out.root.get(), STAGING, true);
            auto identity = statFd(staging.get());
            if (identity.st_uid != geteuid() || (identity.st_mode & 0777) != 0700)
                throw std::runtime_error("staging");
            if (!list(staging.get()).empty())
                throw std::runtime_error("retained");
        } else if (errno != ENOENT)
            fail("staging");
        size_t visited = 0, listed = 0, pathBytes = 2;
        auto enumerate = [&](int fd) {
            auto names = list(fd, MAX_VISITED - listed);
            listed += names.size();
            return names;
        };
        std::function<bool(const std::string &, bool, int)> walk;
        walk = [&](const std::string &relative, bool tree, int depth) -> bool {
            if (++visited > MAX_VISITED || depth > 64)
                throw std::runtime_error("limit");
            if (protectedPath(rule, out.root.get(), relative, tree)) {
                if (tree && rule.action != 1)
                    throw std::runtime_error("protected");
                return false;
            }
            Fd node;
            try {
                node = openWithin(out.root.get(), relative);
            } catch (const std::exception &e) {
                if ((!tree || rule.action == 1) && std::string(e.what()) == "symlink")
                    return false;
                throw;
            }
            auto st = statFd(node.get());
            bool directory = S_ISDIR(st.st_mode);
            bool matches = matchesName(rule, relative.substr(relative.find_last_of('/') + 1));
            bool empty = directory;
            if (directory) {
                bool subtree = tree || (matches && rule.action != 0);
                if (!subtree && !rule.recursive)
                    return false;
                auto dir = openWithin(out.root.get(), relative, true);
                out.directories.push_back(relative);
                auto names = enumerate(dir.get());
                if (subtree && rule.action == 2 && protectedPath(rule, out.root.get(), relative, true))
                    throw std::runtime_error("protected");
                if (subtree || rule.recursive)
                    for (const auto &name : names)
                        if (!walk(relative + "/" + name, subtree, depth + 1))
                            empty = false;
                empty = empty && !protectedPath(rule, out.root.get(), relative, true);
                if (subtree && (rule.action != 1 || empty))
                    out.items.push_back({relative, st, std::move(node)});
            } else if (S_ISREG(st.st_mode)) {
                if ((tree && rule.action != 1) || (matches && rule.action == 0))
                    out.items.push_back({relative, st, std::move(node)});
            } else if (tree && rule.action != 1)
                throw std::runtime_error("type");
            if (!out.items.empty() && out.items.back().path == relative)
                pathBytes += json(relative).size() + 1;
            if (out.items.size() > MAX_ITEMS || pathBytes > 128 * 1024)
                throw std::runtime_error("limit");
            return empty;
        };
        if (focus.empty()) {
            if ((rule.literal || rule.action == 2) && !rule.recursive) {
                if (exactName(out.root.get(), rule.pattern))
                    walk(rule.pattern, false, 1);
            } else
                for (const auto &name : enumerate(out.root.get()))
                    walk(name, false, 1);
        } else {
            if (!validRelative(focus))
                throw std::runtime_error("path");
            bool insideTree = false;
            if (rule.action != 0) {
                std::istringstream parts(focus);
                std::string part, prefix;
                while (std::getline(parts, part, '/')) {
                    prefix += (prefix.empty() ? "" : "/") + part;
                    if (matchesName(rule, part) && (rule.recursive || prefix == part)) {
                        if (rule.action == 2 && protectedPath(rule, out.root.get(), prefix, true))
                            throw std::runtime_error("protected");
                        insideTree = true;
                        focus = prefix;
                        break;
                    }
                }
            }
            if ((rule.recursive || focus.find('/') == std::string::npos || insideTree) &&
                exactPath(out.root.get(), focus))
                walk(focus, false, 1 + static_cast<int>(std::count(focus.begin(), focus.end(), '/')));
        }
        if (rule.action == 1) {
            std::vector<std::string> targets;
            for (const auto &item : out.items)
                if ((rule.recursive || item.path.find('/') == std::string::npos) &&
                    matchesName(rule, item.path.substr(item.path.find_last_of('/') + 1)))
                    targets.push_back(item.path);
            std::erase_if(out.items, [&](const Item &item) {
                return std::none_of(targets.begin(), targets.end(),
                                    [&](const std::string &target) { return under(item.path, target); });
            });
        }
    } catch (const std::exception &e) {
        out.error = e.what();
        out.items.clear();
    }
    return out;
}
static void writeAll(int fd, const std::string &value) {
    size_t done = 0;
    while (done < value.size()) {
        auto count = write(fd, value.data() + done, value.size() - done);
        if (count < 0 && errno == EINTR)
            continue;
        if (count <= 0)
            fail("write");
        done += static_cast<size_t>(count);
    }
}
Result execute(Plan &job, const std::function<bool()> &active) {
    Result result;
    Fd staging;
    struct stat stageIdentity{};
    bool ownedStaging = false;
    result.error = job.error;
    if (!result.error.empty() || job.items.empty())
        return result;
    try {
        auto expectedRoot = statFd(job.root.get());
        if (mkdirat(job.root.get(), STAGING, 0700) && errno != EEXIST)
            fail("staging");
        staging = openWithin(job.root.get(), STAGING, true);
        auto stageStat = statFd(staging.get());
        if (stageStat.st_uid != geteuid() || (stageStat.st_mode & 0777) != 0700)
            throw std::runtime_error("staging");
        stageIdentity = stageStat;
        ownedStaging = true;
        for (auto &item : job.items) {
            if (!active())
                throw std::runtime_error("cancelled");
            auto currentRoot = openRoot(job.rule.root);
            if (!same(expectedRoot, statFd(currentRoot.get())) || !exactPath(currentRoot.get(), item.path))
                throw std::runtime_error("changed");
            if (protectedPath(job.rule, currentRoot.get(), item.path, S_ISDIR(item.identity.st_mode)))
                throw std::runtime_error("protected");
            auto split = item.path.find_last_of('/');
            auto name = split == std::string::npos ? item.path : item.path.substr(split + 1);
            auto parent = split == std::string::npos ? Fd(dup(job.root.get()))
                                                     : openWithin(job.root.get(), item.path.substr(0, split), true);
            auto current = openWithin(parent.get(), name);
            if (!same(item.identity, statFd(current.get())))
                throw std::runtime_error("changed");
#ifdef CLEANUPWATCHER_TEST
            if (beforeCapture)
                beforeCapture();
#endif
            if (!exactName(parent.get(), name))
                throw std::runtime_error("changed");
            auto token = std::to_string(getpid()) + "-" + std::to_string(++serial);
            auto metaName = token + ".meta";
            auto itemName = token + ".item";
            Fd meta(
                openat(staging.get(), metaName.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
            if (meta.get() < 0)
                fail("staging");
            writeAll(meta.get(), hex(job.rule.root) + "\n" + hex(item.path) + "\n" +
                                     std::to_string(item.identity.st_dev) + " " + std::to_string(item.identity.st_ino) +
                                     "\n");
            if (fsync(meta.get()) || fsync(staging.get()))
                fail("staging");
            if (syscall(SYS_renameat2, parent.get(), name.c_str(), staging.get(), itemName.c_str(), RENAME_NOREPLACE)) {
                unlinkat(staging.get(), metaName.c_str(), 0);
                fail("capture");
            }
            struct stat captured{};
            if (fstatat(staging.get(), itemName.c_str(), &captured, AT_SYMLINK_NOFOLLOW))
                fail("staging");
            bool verified = same(item.identity, captured);
            if (!verified) {
                if (ftruncate(meta.get(), 0) || lseek(meta.get(), 0, SEEK_SET) < 0)
                    fail("staging");
                writeAll(meta.get(), hex(job.rule.root) + "\n" + hex(item.path) + "\n" +
                                         std::to_string(captured.st_dev) + " " + std::to_string(captured.st_ino) +
                                         "\n");
                if (fsync(meta.get()))
                    fail("staging");
            }
            if (fsync(parent.get()) || fsync(staging.get()))
                fail("staging");
            bool allowed = verified && active();
            if (allowed) {
                try {
                    auto liveRoot = openRoot(job.rule.root);
                    auto liveParent = split == std::string::npos
                                          ? Fd(dup(liveRoot.get()))
                                          : openWithin(liveRoot.get(), item.path.substr(0, split), true);
                    allowed = same(expectedRoot, statFd(liveRoot.get())) &&
                              same(statFd(parent.get()), statFd(liveParent.get())) &&
                              (split == std::string::npos || exactPath(liveRoot.get(), item.path.substr(0, split)));
                } catch (const std::exception &) {
                    allowed = false;
                }
            }
            int flags = S_ISDIR(item.identity.st_mode) ? AT_REMOVEDIR : 0;
            if (!allowed || unlinkat(staging.get(), itemName.c_str(), flags)) {
                int saved = errno;
                if (syscall(SYS_renameat2, staging.get(), itemName.c_str(), parent.get(), name.c_str(),
                            RENAME_NOREPLACE))
                    throw std::runtime_error("retained");
                unlinkat(staging.get(), metaName.c_str(), 0);
                if (!allowed)
                    throw std::runtime_error(verified && !active() ? "cancelled" : "changed");
                errno = saved;
                fail("delete");
            }
            unlinkat(staging.get(), metaName.c_str(), 0);
            ++result.deleted;
        }
    } catch (const std::exception &e) {
        result.error = e.what();
    }
    if (ownedStaging) {
        struct stat current{};
        if (fstatat(job.root.get(), STAGING, &current, AT_SYMLINK_NOFOLLOW) == 0 && same(stageIdentity, current))
            unlinkat(job.root.get(), STAGING, AT_REMOVEDIR);
    }
    return result;
}
std::string resultJson(const Plan &job, const Result &result, bool includePaths) {
    std::string out = "{\"id\":" + json(job.rule.id) + ",\"root\":" + json(job.rule.root) +
                      ",\"deleted\":" + std::to_string(result.deleted) +
                      ",\"error\":" + json(result.error.empty() ? job.error : result.error) + ",\"paths\":[";
    bool first = true;
    for (const auto &item : job.items) {
        if (!includePaths)
            break;
        if (!first)
            out += ',';
        first = false;
        out += json(item.path);
    }
    return out + "]}";
}
std::string recover(const Rule &rule) {
    int restored = 0;
    std::string error;
    try {
        auto root = openRoot(rule.root);
        auto stage = openWithin(root.get(), STAGING, true);
        auto st = statFd(stage.get());
        if (st.st_uid != geteuid() || (st.st_mode & 0777) != 0700)
            throw std::runtime_error("staging");
        for (const auto &name : list(stage.get())) {
            if (!name.ends_with(".meta"))
                continue;
            Fd meta(openat(stage.get(), name.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC));
            auto identity = statFd(meta.get());
            constexpr int maxRecord = 2 * 4096 * 2 + 128;
            if (!S_ISREG(identity.st_mode) || identity.st_uid != geteuid() || identity.st_size > maxRecord)
                throw std::runtime_error("staging");
            char buffer[maxRecord + 1];
            ssize_t length = 0;
            while (length < identity.st_size) {
                auto count = read(meta.get(), buffer + length, identity.st_size - length);
                if (count < 0 && errno == EINTR)
                    continue;
                if (count <= 0)
                    fail("read");
                length += count;
            }
            std::istringstream record(std::string(buffer, length));
            std::string savedRoot, path, extra;
            unsigned long long device, inode;
            if (!(record >> savedRoot >> path >> device >> inode) || record >> extra ||
                backingPath(unhex(savedRoot)) != backingPath(rule.root))
                throw std::runtime_error("staging");
            path = unhex(path);
            if (!validRelative(path))
                throw std::runtime_error("staging");
            auto itemName = name.substr(0, name.size() - 5) + ".item";
            struct stat item{};
            if (fstatat(stage.get(), itemName.c_str(), &item, AT_SYMLINK_NOFOLLOW)) {
                if (errno != ENOENT)
                    fail("staging");
                if (unlinkat(stage.get(), name.c_str(), 0))
                    fail("staging");
                continue;
            }
            if (static_cast<unsigned long long>(item.st_dev) != device ||
                static_cast<unsigned long long>(item.st_ino) != inode ||
                (!S_ISREG(item.st_mode) && !S_ISDIR(item.st_mode) && !S_ISLNK(item.st_mode) &&
                 !S_ISFIFO(item.st_mode) && !S_ISSOCK(item.st_mode)))
                throw std::runtime_error("staging");
            auto split = path.find_last_of('/');
            auto leaf = split == std::string::npos ? path : path.substr(split + 1);
            auto parent =
                split == std::string::npos ? Fd(dup(root.get())) : openWithin(root.get(), path.substr(0, split), true);
            if (syscall(SYS_renameat2, stage.get(), itemName.c_str(), parent.get(), leaf.c_str(), RENAME_NOREPLACE))
                throw std::runtime_error("retained");
            if (fsync(parent.get()) || fsync(stage.get()))
                fail("staging");
            if (unlinkat(stage.get(), name.c_str(), 0))
                fail("staging");
            ++restored;
        }
    } catch (const std::exception &e) {
        error = e.what();
    }
    if (error.empty()) {
        auto root = openRoot(rule.root);
        unlinkat(root.get(), STAGING, AT_REMOVEDIR);
    }
    return "{\"id\":" + json(rule.id) + ",\"root\":" + json(rule.root) + ",\"restored\":" + std::to_string(restored) +
           ",\"error\":" + json(error) + ",\"paths\":[]}";
}
} // namespace cw
