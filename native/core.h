#pragma once
#include <functional>
#include <string>
#include <sys/stat.h>
#include <vector>

namespace cw {
#ifdef CLEANUPWATCHER_TEST
extern std::function<void()> beforeCapture;
#endif
class Fd {
    int value_ = -1;

  public:
    explicit Fd(int value = -1) : value_(value) {}
    ~Fd();
    Fd(const Fd &) = delete;
    Fd &operator=(const Fd &) = delete;
    Fd(Fd &&other) noexcept;
    Fd &operator=(Fd &&other) noexcept;
    int get() const {
        return value_;
    }
};
enum Action { File = 0, EmptyTree = 1, FolderTree = 2, EmptyChildren = 3 };
struct Rule {
    std::string id, root, pattern;
    Action action = File;
    bool recursive = false, enabled = false;
    std::vector<std::string> whitelist;
    bool literal = false;
};
struct Item {
    std::string path;
    struct stat identity{};
    Fd handle;
};
struct Plan {
    Rule rule;
    Fd root;
    std::vector<Item> items;
    std::vector<std::string> directories;
    std::string error;
};
struct Result {
    int deleted = 0;
    std::string error;
};
std::string hex(const std::string &value);
std::string unhex(const std::string &value, size_t maxBytes = 4096);
std::string json(const std::string &value);
std::vector<Rule> decodeRules(const std::string &value);
std::string encodeRules(const std::vector<Rule> &rules);
bool validRelative(const std::string &value);
bool matchesName(const Rule &rule, const std::string &name);
void requireKey(int fd);
bool protectedPath(const Rule &rule, int root, const std::string &path, bool subtree = false);
std::string backingPath(const std::string &logical);
Fd openRoot(const std::string &logical, bool allowMissing = false);
Fd openWithin(int root, const std::string &path, bool directory = false);
Plan plan(const Rule &rule, std::string focus = "");
Result execute(Plan &plan, const std::function<bool()> &active = [] { return true; });
std::string resultJson(const Plan &plan, const Result &result = {}, bool deletedOnly = false);
std::string recover(const Rule &rule);
} // namespace cw
