#include "browser.h"
#include "core.h"
#include "lifecycle.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <clocale>
#include <condition_variable>
#include <csignal>
#include <cstring>
#include <fcntl.h>
#include <iostream>
#include <map>
#include <mutex>
#include <poll.h>
#include <sstream>
#include <stdexcept>
#include <sys/eventfd.h>
#include <sys/file.h>
#include <sys/inotify.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#ifdef __ANDROID__
#include <sys/system_properties.h>
#endif

using namespace cw;
static std::mutex mutex;
static std::mutex deletion;
static std::condition_variable watchReady;
static std::vector<Rule> rules;
static std::vector<std::string> records;
static std::map<std::string, std::string> health;
static std::atomic<bool> stopping{false}, paused{false}, watcherFailed{false};
static std::atomic<unsigned> revision{1}, watchCount{0}, watchRevision{0};
static std::atomic<unsigned> policyRevision{1};
static int wake = -1;
static int listener = -1;
static std::mutex clientMutex;
static std::condition_variable clientsDone;
static constexpr int MAX_CLIENTS = 4;
static int clients = 0, waiters = 0;
struct WaitSlot {
    WaitSlot(const WaitSlot &) = delete;
    WaitSlot &operator=(const WaitSlot &) = delete;
    WaitSlot() {
        std::lock_guard lock(clientMutex);
        if (waiters >= MAX_CLIENTS)
            throw std::runtime_error("busy");
        ++waiters;
    }
    ~WaitSlot() {
        std::lock_guard lock(clientMutex);
        --waiters;
    }
};
#ifdef __ANDROID__
struct StorageProperty {
    uint32_t serial = 0;
    bool ready = false;
};
static StorageProperty storageProperty(const prop_info *info) {
    StorageProperty result;
    if (info)
        __system_property_read_callback(
            info,
            [](void *cookie, const char *, const char *value, uint32_t serial) {
                auto *result = static_cast<StorageProperty *>(cookie);
                result->serial = serial;
                result->ready = strcmp(value, "true") == 0;
            },
            &result);
    return result;
}
static bool awaitStorage() {
    while (true) {
        uint32_t serial = __system_property_area_serial(), next = 0;
        auto *info = __system_property_find("sys.user.0.ce_available");
        auto state = storageProperty(info);
        if (state.ready)
            return true;
        if (!__system_property_wait(info, info ? state.serial : serial, &next, nullptr))
            return false;
    }
}
#endif
static long long millis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch())
        .count();
}
static void notify() {
    uint64_t one = 1;
    if (wake >= 0) {
        auto size = write(wake, &one, sizeof(one));
        if (size < 0 && errno != EAGAIN)
            stopping = true;
    }
}
static void record(const Plan &job, const Result &result) {
    std::lock_guard lock(mutex);
    auto entry = resultJson(job, result, false);
    entry.pop_back();
    auto time =
        std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch())
            .count();
    records.push_back(entry + ",\"time\":" + std::to_string(time) +
                      ",\"name\":" + json(job.rule.action == EmptyChildren ? job.rule.root : job.rule.pattern) + "}");
    if (records.size() > 12)
        records.erase(records.begin());
}
static std::string status() {
    std::lock_guard lock(mutex);
    std::string out =
        "{\"versionName\":" + json(BACKEND_VERSION_NAME) + ",\"versionCode\":" + std::to_string(BACKEND_VERSION_CODE) +
        ",\"running\":true,\"paused\":" + std::string(paused ? "true" : "false") +
        ",\"boot\":" + (load("boot", "0") == "1" ? "true" : "false") + ",\"watches\":" + std::to_string(watchCount) +
        ",\"rules\":" + json(encodeRules(rules)) + ",\"records\":[";
    for (size_t i = 0; i < records.size(); ++i) {
        if (i)
            out += ',';
        out += records[i];
    }
    out += "],\"checking\":" + std::string(watchRevision != revision ? "true" : "false") + ",\"health\":{";
    bool first = true;
    for (const auto &[id, error] : health) {
        if (!first)
            out += ',';
        first = false;
        out += json(id) + ":" + json(error);
    }
    return out + "}}";
}
struct Watch {
    std::string id, path;
    bool ancestor;
};
struct Pending {
    std::string id, path;
    long long due;
};
static void worker() {
    Fd events(inotify_init1(IN_NONBLOCK | IN_CLOEXEC));
    Fd mounts(open("/proc/self/mounts", O_RDONLY | O_CLOEXEC));
    if (events.get() < 0 || mounts.get() < 0)
        throw std::runtime_error("watch");
    unsigned version = 0;
    std::map<int, std::vector<Watch>> watches;
    std::vector<std::string> activeRules;
    std::vector<Pending> pending;
    long long rebuildDue = -1;
    std::map<std::string, std::pair<long long, int>> budgets;
    auto attach = [&](const std::string &id, int fd, const std::string &path, bool ancestor = false) {
        auto proc = "/proc/self/fd/" + std::to_string(fd);
        int wd = inotify_add_watch(events.get(), proc.c_str(),
                                   IN_CREATE | IN_CLOSE_WRITE | IN_DELETE | IN_MOVED_TO | IN_MOVED_FROM |
                                       IN_DELETE_SELF | IN_MOVE_SELF | IN_ONLYDIR);
        if (wd < 0)
            throw std::runtime_error("watch");
        auto &contexts = watches[wd];
        if (std::none_of(contexts.begin(), contexts.end(),
                         [&](const Watch &w) { return w.id == id && w.path == path && w.ancestor == ancestor; }))
            contexts.push_back({id, path, ancestor});
        watchCount = static_cast<unsigned>(watches.size());
    };
    while (!stopping) {
        if (version != revision) {
            version = revision;
            events = Fd(inotify_init1(IN_NONBLOCK | IN_CLOEXEC));
            if (events.get() < 0)
                throw std::runtime_error("watch");
            watches.clear();
            pending.clear();
            rebuildDue = -1;
            activeRules.clear();
            std::vector<Rule> snapshot;
            {
                std::lock_guard lock(mutex);
                snapshot = rules;
                health.clear();
            }
            std::erase_if(budgets, [&](const auto &entry) {
                return std::none_of(snapshot.begin(), snapshot.end(),
                                    [&](const Rule &rule) { return rule.id == entry.first; });
            });
            if (!paused)
                for (const auto &rule : snapshot)
                    if (rule.enabled) {
                        if (stopping || paused || revision != version)
                            break;
                        std::lock_guard planningLock(deletion);
                        auto job = plan(rule);
                        try {
                            if (job.root.get() >= 0) {
                                attach(rule.id, job.root.get(), "");
                                if (rule.recursive || rule.action != File)
                                    for (const auto &path : job.directories) {
                                        if (!rule.recursive && !matchesName(rule, path.substr(0, path.find('/'))))
                                            continue;
                                        auto dir = openWithin(job.root.get(), path, true);
                                        attach(rule.id, dir.get(), path);
                                    }
                            } else {
                                auto ancestor = backingPath(rule.root);
                                while (ancestor.size() > 1) {
                                    ancestor = ancestor.substr(0, ancestor.find_last_of('/'));
                                    if (ancestor.empty())
                                        break;
                                    Fd dir(open(ancestor.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
                                    if (dir.get() >= 0) {
                                        attach(rule.id, dir.get(), "", true);
                                        break;
                                    }
                                }
                            }
                            {
                                std::lock_guard lock(mutex);
                                health[rule.id] = job.error;
                            }
                            if (!job.error.empty())
                                record(job, {0, job.error});
                            else {
                                activeRules.push_back(rule.id);
                                pending.push_back({rule.id, "", millis() + 1500});
                            }
                        } catch (const std::exception &e) {
                            {
                                std::lock_guard lock(mutex);
                                health[rule.id] = e.what();
                            }
                            record(job, {0, e.what()});
                        }
                    }
            watchCount = static_cast<unsigned>(watches.size());
            {
                std::lock_guard lock(mutex);
                watchRevision = version;
            }
            watchReady.notify_all();
        }
        long long due = pending.empty()
                            ? -1
                            : std::min_element(pending.begin(), pending.end(), [](const Pending &a, const Pending &b) {
                                  return a.due < b.due;
                              })->due;
        if (rebuildDue >= 0 && (due < 0 || rebuildDue < due))
            due = rebuildDue;
        int timeout = due < 0 ? -1 : static_cast<int>(std::max(0LL, due - millis()));
        struct pollfd fds[]{{events.get(), POLLIN, 0}, {wake, POLLIN, 0}, {mounts.get(), POLLPRI, 0}};
        if (poll(fds, 3, timeout) < 0) {
            if (errno == EINTR)
                continue;
            throw std::runtime_error("watch");
        }
        if (fds[1].revents || fds[2].revents) {
            uint64_t value;
            auto size = read(wake, &value, sizeof(value));
            if (size < 0 && errno != EAGAIN)
                throw std::runtime_error("watch");
            if (fds[2].revents) {
                lseek(mounts.get(), 0, SEEK_SET);
                char data[4096];
                while (read(mounts.get(), data, sizeof(data)) > 0) {
                }
                ++revision;
            }
            continue;
        }
        if (fds[0].revents & POLLIN) {
            alignas(inotify_event) char buffer[16384];
            auto count = read(events.get(), buffer, sizeof(buffer));
            if (count < 0 && errno != EAGAIN && errno != EINTR)
                throw std::runtime_error("watch");
            for (ssize_t pos = 0; pos < count;) {
                auto *event = reinterpret_cast<inotify_event *>(buffer + pos);
                pos += sizeof(inotify_event) + event->len;
                if (event->mask & (IN_Q_OVERFLOW | IN_IGNORED | IN_DELETE_SELF | IN_MOVE_SELF)) {
                    ++revision;
                    break;
                }
                auto found = watches.find(event->wd);
                if (found == watches.end())
                    continue;
                for (const auto &watch : found->second) {
                    std::string name = event->len ? std::string(event->name) : "";
                    if (name.empty() || name == ".cleanupwatcher")
                        continue;
                    bool directory = event->mask & IN_ISDIR;
                    if (watch.ancestor) {
                        if (directory && (event->mask & (IN_CREATE | IN_MOVED_TO)))
                            rebuildDue = millis() + 1500;
                        continue;
                    }
                    if (std::find(activeRules.begin(), activeRules.end(), watch.id) == activeRules.end()) {
                        rebuildDue = millis() + 1500;
                        continue;
                    }
                    std::string path = watch.path.empty() ? name : watch.path + "/" + name;
                    bool removed = event->mask & (IN_DELETE | IN_MOVED_FROM);
                    Rule rule;
                    {
                        std::lock_guard lock(mutex);
                        for (const auto &r : rules)
                            if (r.id == watch.id) {
                                rule = r;
                                break;
                            }
                    }
                    if (directory && !rule.recursive &&
                        (rule.action == File || (watch.path.empty() && !matchesName(rule, name))))
                        continue;
                    if (rule.action == EmptyTree && (event->mask & IN_CLOSE_WRITE))
                        continue;
                    if (rule.action == EmptyChildren &&
                        ((watch.path.empty() && !directory) ||
                         (!watch.path.empty() && !(event->mask & (IN_DELETE | IN_MOVED_FROM)))))
                        continue;
                    if (directory && (event->mask & IN_MOVED_FROM) &&
                        (rule.action != EmptyChildren || watch.path.empty())) {
                        ++revision;
                        continue;
                    }
                    if (directory && (event->mask & (IN_CREATE | IN_MOVED_TO))) {
                        bool targetTree =
                            rule.action != File && matchesName(rule, watch.path.substr(0, watch.path.find('/')));
                        if (!rule.recursive && !targetTree && (rule.action == File || !matchesName(rule, name)))
                            continue;
                        std::lock_guard planningLock(deletion);
                        auto incoming = plan(rule, path);
                        try {
                            if (!incoming.error.empty())
                                throw std::runtime_error(incoming.error);
                            for (const auto &relative : incoming.directories) {
                                auto dir = openWithin(incoming.root.get(), relative, true);
                                attach(rule.id, dir.get(), relative);
                            }
                        } catch (const std::exception &e) {
                            {
                                std::lock_guard lock(mutex);
                                health[rule.id] = e.what();
                            }
                            record(incoming, {0, e.what()});
                            rebuildDue = millis() + 1500;
                            continue;
                        }
                    } else if (!(event->mask & (IN_CLOSE_WRITE | IN_MOVED_TO | IN_DELETE | IN_MOVED_FROM)))
                        continue;
                    if ((rule.action == EmptyTree || rule.action == EmptyChildren) && (!directory || removed))
                        path = watch.path;
                    else if (removed)
                        continue;
                    if (!directory && rule.action == File && !matchesName(rule, name))
                        continue;
                    if (rule.action != File) {
                        std::istringstream components(path);
                        std::string part, prefix;
                        bool matched = false;
                        while (std::getline(components, part, '/')) {
                            prefix += (prefix.empty() ? "" : "/") + part;
                            if (matchesName(rule, part) && (rule.recursive || prefix == part)) {
                                path = prefix;
                                matched = true;
                                break;
                            }
                        }
                        if (!matched && !directory)
                            continue;
                    }
                    auto existing = std::find_if(pending.begin(), pending.end(),
                                                 [&](const Pending &p) { return p.id == watch.id && p.path == path; });
                    if (existing != pending.end())
                        existing->due = millis() + 1500;
                    else if (pending.size() < 2048)
                        pending.push_back({watch.id, path, millis() + 1500});
                    else
                        ++revision;
                }
            }
        }
        if (rebuildDue >= 0 && millis() >= rebuildDue) {
            ++revision;
            continue;
        }
        for (size_t i = 0; i < pending.size();) {
            if (version != revision || paused)
                break;
            if (pending[i].due > millis()) {
                ++i;
                continue;
            }
            auto task = pending[i];
            pending.erase(pending.begin() + i);
            Rule rule;
            bool found = false;
            {
                std::lock_guard lock(mutex);
                for (const auto &r : rules)
                    if (r.id == task.id && r.enabled) {
                        rule = r;
                        found = true;
                        break;
                    }
            }
            if (!found)
                continue;
            std::lock_guard serialLock(deletion);
            auto job = plan(rule, task.path);
            auto &budget = budgets[rule.id];
            if (millis() - budget.first >= 60000)
                budget = {millis(), 0};
            if (!job.items.empty() && budget.second + static_cast<int>(job.items.size()) > 512) {
                if (budget.second <= 512)
                    record(job, {0, "rate"});
                {
                    std::lock_guard lock(mutex);
                    health[rule.id] = "rate";
                }
                budget.second = 513;
                pending.push_back({task.id, task.path, budget.first + 60000});
                continue;
            }
            auto result = execute(job, [&] { return !stopping && !paused && revision == version; });
            budget.second += result.deleted;
            {
                std::lock_guard lock(mutex);
                health[rule.id] = result.error.empty() && budget.second > 512 ? "rate" : result.error;
            }
            if (result.deleted || !result.error.empty())
                record(job, result);
        }
    }
    watchCount = 0;
}
static std::string command(const std::string &request) {
    auto newline = request.find('\n');
    auto verb = request.substr(0, newline);
    auto payload = newline == std::string::npos ? "" : request.substr(newline + 1);
    if (verb == "status")
        return status();
    if (verb == "settled") {
        std::unique_lock lock(mutex);
        if (!stopping && watchRevision != revision) {
            WaitSlot waiting;
            watchReady.wait_for(lock, std::chrono::seconds(20), [] { return stopping || watchRevision == revision; });
        }
        lock.unlock();
        return status();
    }
    if (verb == "clear-records") {
        {
            std::lock_guard lock(mutex);
            records.clear();
        }
        return status();
    }
    if (verb == "rules") {
        auto next = decodeRules(payload);
        std::lock_guard serialLock(deletion);
        {
            std::lock_guard lock(mutex);
            checkReleasedScopes(rules, next);
            save("rules", encodeRules(next));
            rules = std::move(next);
            ++policyRevision;
            ++revision;
        }
        notify();
        return status();
    }
    if (verb == "preview" || verb == "clean" || verb == "recover") {
        Rule rule;
        unsigned version;
        bool found = false;
        {
            std::lock_guard lock(mutex);
            version = policyRevision;
            for (const auto &r : rules)
                if (r.id == payload) {
                    rule = r;
                    found = true;
                    break;
                }
        }
        if (!found)
            throw std::runtime_error("rule");
        if (verb == "recover") {
            {
                std::lock_guard lock(mutex);
                paused = true;
                ++policyRevision;
                save("paused", "1");
                ++revision;
            }
            notify();
            std::lock_guard lock(deletion);
            return recover(rule);
        }
        std::lock_guard serialLock(deletion);
        auto job = plan(rule);
        Result result;
        if (version != policyRevision) {
            job.error = "changed";
            job.items.clear();
        }
        if (verb == "clean") {
            result = execute(job, [&] { return !stopping && version == policyRevision; });
            record(job, result);
        }
        return resultJson(job, result);
    }
    if (verb == "pause" || verb == "resume" || verb == "refresh") {
        {
            std::lock_guard lock(mutex);
            if (verb != "refresh") {
                bool pause = verb == "pause";
                if (pause)
                    paused = true;
                ++policyRevision;
                save("paused", pause ? "1" : "0");
                paused = pause;
            }
            ++revision;
        }
        notify();
        {
            std::lock_guard barrier(deletion);
        }
        return status();
    }
    if (verb == "boot") {
        if (payload != "0" && payload != "1")
            throw std::runtime_error("config");
        {
            std::lock_guard lock(mutex);
            save("boot", payload);
        }
        return status();
    }
    if (verb == "stop") {
        {
            std::lock_guard lock(mutex);
            stopping = true;
        }
        watchReady.notify_all();
        ++policyRevision;
        ++revision;
        notify();
        {
            std::lock_guard barrier(deletion);
        }
        shutdown(listener, SHUT_RDWR);
        return "{\"stopped\":true}";
    }
    throw std::runtime_error("command");
}
static void serve() {
    auto dir = stateDir();
    auto lock = lockFile(dir.get(), "lock");
    if (flock(lock.get(), LOCK_EX | LOCK_NB))
        return;
    rules = decodeRules(load("rules", "CW\n"));
    paused = load("paused", "0") == "1";
    wake = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    Fd wakeFd(wake);
    if (wake < 0)
        throw std::runtime_error("watch");
    Fd server(socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0));
    listener = server.get();
    struct sockaddr_un address{};
    address.sun_family = AF_UNIX;
    std::strcpy(address.sun_path, SOCKET);
    unlink(SOCKET);
    if (server.get() < 0 || bind(server.get(), reinterpret_cast<sockaddr *>(&address), sizeof(address)) ||
        chmod(SOCKET, 0600) || listen(server.get(), 4))
        throw std::runtime_error("socket");
    Fd storageSignal;
    pid_t storageChild = -1;
#ifdef __ANDROID__
    if (!storageProperty(__system_property_find("sys.user.0.ce_available")).ready) {
        int signals[2];
        if (pipe2(signals, O_CLOEXEC | O_NONBLOCK))
            throw std::runtime_error("storage");
        storageSignal = Fd(signals[0]);
        Fd sender(signals[1]);
        pid_t parent = getpid();
        storageChild = fork();
        if (storageChild < 0)
            throw std::runtime_error("storage");
        if (storageChild == 0) {
            prctl(PR_SET_PDEATHSIG, SIGTERM);
            if (getppid() != parent)
                _exit(0);
            close(lock.get());
            close(dir.get());
            close(server.get());
            close(wakeFd.get());
            close(storageSignal.get());
            if (!awaitStorage())
                _exit(1);
            char ready = 1;
            auto written = write(sender.get(), &ready, 1);
            _exit(written == 1 ? 0 : 1);
        }
    }
#endif
    std::thread watcher([] {
        try {
            worker();
        } catch (...) {
            watcherFailed = true;
            stopping = true;
            shutdown(listener, SHUT_RDWR);
            notify();
        }
    });
    while (!stopping) {
        struct pollfd ready[]{{server.get(), POLLIN, 0}, {storageSignal.get(), POLLIN, 0}};
        if (poll(ready, 2, -1) < 0) {
            if (errno == EINTR)
                continue;
            watcherFailed = true;
            break;
        }
        if (ready[1].revents) {
            char value;
            auto count = read(storageSignal.get(), &value, 1);
            if (count == 1) {
                ++revision;
                notify();
            }
            if (storageChild > 0)
                waitpid(storageChild, nullptr, 0);
            storageChild = -1;
            storageSignal = Fd();
        }
        if (!ready[0].revents)
            continue;
        Fd client(accept4(server.get(), nullptr, nullptr, SOCK_CLOEXEC));
        if (client.get() < 0)
            continue;
        struct ucred peer{};
        socklen_t size = sizeof(peer);
        if (getsockopt(client.get(), SOL_SOCKET, SO_PEERCRED, &peer, &size) || peer.uid != 0)
            continue;
        struct timeval timeout{3, 0};
        setsockopt(client.get(), SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
        setsockopt(client.get(), SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
        {
            std::lock_guard lock(clientMutex);
            if (clients - waiters >= MAX_CLIENTS || clients >= 2 * MAX_CLIENTS)
                continue;
            ++clients;
        }
        std::thread([client = std::move(client)] {
            try {
                auto request = readFull(client.get(), 128 * 1024 + 32);
                writeFull(client.get(), command(request) + "\n");
            } catch (const std::exception &e) {
                try {
                    writeFull(client.get(), "{\"error\":" + json(e.what()) + "}\n");
                } catch (...) {
                }
            }
            {
                std::lock_guard lock(clientMutex);
                --clients;
            }
            clientsDone.notify_all();
        }).detach();
    }
    {
        std::lock_guard lock(mutex);
        stopping = true;
    }
    watchReady.notify_all();
    if (storageChild > 0) {
        kill(storageChild, SIGTERM);
        waitpid(storageChild, nullptr, 0);
    }
    notify();
    watcher.join();
    unlink(SOCKET);
    std::unique_lock clientLock(clientMutex);
    clientsDone.wait(clientLock, [] { return clients == 0; });
    if (watcherFailed)
        throw std::runtime_error("watch");
}
int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);
    umask(0077);
    try {
        if (geteuid() != 0 || argc < 2)
            throw std::runtime_error("root");
        std::string verb = argv[1];
        if (verb == "version") {
            std::cout << "{\"versionName\":" << json(BACKEND_VERSION_NAME)
                      << ",\"versionCode\":" << BACKEND_VERSION_CODE << "}\n";
        } else if (verb == "browse") {
            if (!std::setlocale(LC_CTYPE, "C.UTF-8"))
                throw std::runtime_error("list");
            std::cout << browse(readFull(STDIN_FILENO, 16 * 1024)) << '\n';
        } else if (verb == "install") {
            installBackend();
            std::cout << "{\"installed\":true}\n";
        } else if (verb == "configure") {
            std::cout << configureBackend(readFull(STDIN_FILENO, 128 * 1024)) << '\n';
        } else if (verb == "uninstall") {
            removeBackend();
            std::cout << "{\"removed\":true}\n";
        } else if (verb == "inspect" && argc == 3 && (std::string(argv[2]) == "0" || std::string(argv[2]) == "1")) {
            std::cout << inspect(std::string(argv[2]) == "1") << '\n';
        } else if (verb == "module-install" || verb == "module-uninstall") {
            if (verb == "module-install")
                installModule();
            else
                removeModule();
            std::cout << "{\"changed\":true}\n";
        } else if (verb == "serve")
            serve();
        else if (verb == "start" || verb == "boot-start") {
            auto dir = stateDir();
            if (faccessat(dir.get(), "removing", F_OK, 0) == 0)
                throw std::runtime_error("management");
            if (verb == "boot-start" && load("boot", "0") != "1") {
                std::cout << "{\"starting\":false}\n";
                return 0;
            }
            try {
                auto fd = connectServer();
                writeFull(fd.get(), "refresh");
                shutdown(fd.get(), SHUT_WR);
                std::cout << readFull(fd.get());
            } catch (const std::exception &e) {
                if (std::string(e.what()) != "offline")
                    throw;
                auto guardian = lockFile(dir.get(), "guardian.lock");
                if (flock(guardian.get(), LOCK_EX | LOCK_NB)) {
                    if (errno != EWOULDBLOCK)
                        throw std::runtime_error("management");
                    std::cout << "{\"starting\":true}\n";
                    return 0;
                }
                pid_t pid = fork();
                if (pid < 0)
                    throw std::runtime_error("start");
                if (pid == 0) {
                    setsid();
                    Fd null(open("/dev/null", O_RDWR));
                    for (int fd = 0; fd < 3; ++fd)
                        dup2(null.get(), fd);
                    for (unsigned attempt = 0; attempt < 5; ++attempt) {
                        if (faccessat(dir.get(), "removing", F_OK, 0) == 0)
                            _exit(0);
                        pid_t child = fork();
                        if (child < 0)
                            _exit(1);
                        if (child == 0) {
                            execl(BINARY, BINARY, "serve", nullptr);
                            _exit(1);
                        }
                        int status = 0;
                        while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
                        }
                        if (WIFEXITED(status) && WEXITSTATUS(status) == 0)
                            _exit(0);
                        sleep(1U << attempt);
                    }
                    _exit(1);
                }
                std::cout << "{\"starting\":true}\n";
            }
        } else if (verb == "ctl" && argc == 3) {
            auto fd = connectServer();
            writeFull(fd.get(), std::string(argv[2]) + "\n" + readFull(STDIN_FILENO, 128 * 1024));
            shutdown(fd.get(), SHUT_WR);
            std::cout << readFull(fd.get());
        } else
            throw std::runtime_error("command");
        return 0;
    } catch (const std::exception &e) {
        std::cout << "{\"error\":" << json(e.what()) << "}\n";
        return 1;
    }
}
