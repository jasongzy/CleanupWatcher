#include "browser.h"
#include "core.h"
#include <algorithm>
#include <cstdlib>
#include <cwchar>
#include <cwctype>
#include <dirent.h>
#include <fcntl.h>
#include <memory>
#include <set>
#include <sstream>
#include <stdexcept>
#include <unistd.h>

namespace cw {
static std::wstring foldedName(const std::string &name) {
    std::mbstate_t state{};
    const char *source = name.c_str();
    std::wstring folded(name.size(), L'\0');
    auto size = std::mbsrtowcs(folded.data(), &source, folded.size(), &state);
    if (size == static_cast<size_t>(-1)) {
        folded.clear();
        for (unsigned char byte : name)
            folded.push_back(byte);
    } else {
        folded.resize(size);
    }
    std::transform(folded.begin(), folded.end(), folded.begin(), [](wchar_t value) { return std::towlower(value); });
    return folded;
}
struct BrowseEntry {
    std::string name;
    bool directory;
    std::wstring folded;
    bool operator<(const BrowseEntry &other) const {
        if (directory != other.directory)
            return directory;
        if ((name.front() == '.') != (other.name.front() == '.'))
            return name.front() == '.';
        if (folded != other.folded)
            return folded < other.folded;
        return name < other.name;
    }
};
static bool isDirectory(int dir, const dirent &entry) {
    if (entry.d_type != DT_UNKNOWN && entry.d_type != DT_LNK)
        return entry.d_type == DT_DIR;
    struct stat st{};
    return fstatat(dir, entry.d_name, &st, 0) == 0 && S_ISDIR(st.st_mode);
}
std::string browse(const std::string &request) {
    std::istringstream input(request);
    std::string pathHex, cursorHex, extra;
    if (!(input >> pathHex >> cursorHex) || input >> extra)
        throw std::runtime_error("path");
    auto path = unhex(pathHex), cursor = unhex(cursorHex);
    if (path.empty() || path.front() != '/' || path.size() > 4096 || cursor.size() > 256)
        throw std::runtime_error("path");
    BrowseEntry after{};
    if (!cursor.empty()) {
        if (cursor.size() < 2 || (cursor.front() != 'd' && cursor.front() != 'f') ||
            cursor.find('/') != std::string::npos)
            throw std::runtime_error("path");
        auto name = cursor.substr(1);
        if (name == "." || name == "..")
            throw std::runtime_error("path");
        after = {name, cursor.front() == 'd', foldedName(name)};
    }
    std::unique_ptr<char, decltype(&free)> canonical(realpath(path.c_str(), nullptr), free);
    if (!canonical)
        throw std::runtime_error("path");
    auto closeDir = [](DIR *stream) { closedir(stream); };
    std::unique_ptr<DIR, decltype(closeDir)> dir(opendir(canonical.get()), closeDir);
    if (!dir)
        throw std::runtime_error("list");
    requireKey(dirfd(dir.get()));
    std::set<BrowseEntry> page;
    constexpr size_t pageSize = 256;
    errno = 0;
    while (auto *entry = readdir(dir.get())) {
        std::string name = entry->d_name;
        if (name != "." && name != "..") {
            BrowseEntry item{name, isDirectory(dirfd(dir.get()), *entry), foldedName(name)};
            if (cursor.empty() || after < item) {
                page.insert(std::move(item));
                if (page.size() > pageSize + 1)
                    page.erase(std::prev(page.end()));
            }
        }
        errno = 0;
    }
    if (errno)
        throw std::runtime_error("list");
    bool more = page.size() > pageSize;
    if (more)
        page.erase(std::prev(page.end()));
    std::string out = "{\"path\":" + json(hex(canonical.get())) + ",\"entries\":[";
    bool first = true;
    for (const auto &entry : page) {
        const auto &name = entry.name;
        struct stat st{};
        if (fstatat(dirfd(dir.get()), name.c_str(), &st, AT_SYMLINK_NOFOLLOW))
            continue;
        bool directory = S_ISDIR(st.st_mode);
        std::string type = directory             ? "directory"
                           : S_ISREG(st.st_mode) ? "file"
                           : S_ISLNK(st.st_mode) ? "link"
                                                 : "other";
        if (S_ISLNK(st.st_mode)) {
            struct stat target{};
            directory = fstatat(dirfd(dir.get()), name.c_str(), &target, 0) == 0 && S_ISDIR(target.st_mode);
        }
        if (!first)
            out += ',';
        first = false;
        out += "{\"name\":" + json(hex(name)) + ",\"type\":" + json(type) +
               ",\"directory\":" + (directory ? "true" : "false") + ",\"modified\":" +
               std::to_string(static_cast<long long>(st.st_mtim.tv_sec) * 1000 + st.st_mtim.tv_nsec / 1000000) +
               ",\"size\":" + std::to_string(S_ISREG(st.st_mode) ? st.st_size : -1) + "}";
    }
    std::string next = "-";
    if (more) {
        const auto &last = *page.rbegin();
        next = hex(std::string(last.directory ? "d" : "f") + last.name);
    }
    return out + "],\"cursor\":" + json(next) + "}";
}
} // namespace cw
