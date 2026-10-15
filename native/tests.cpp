#include "core.h"
#include <cassert>
#include <cerrno>
#include <fcntl.h>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sys/mount.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace fs = std::filesystem;
using namespace cw;
static void put(const fs::path &path, const std::string &value = "keep") {
    std::ofstream(path) << value;
}
static bool hasExactEntry(const fs::path &directory, const std::string &name) {
    for (const auto &entry : fs::directory_iterator(directory))
        if (entry.path().filename().string() == name)
            return true;
    return false;
}
static Rule make(const std::string &root, int action = 0, const std::string &pattern = "*.log") {
    return {"test", root, pattern, action, true, true, {}};
}
static void rejects(const std::function<void()> &action) {
    bool rejected = false;
    try {
        action();
    } catch (const std::exception &) {
        rejected = true;
    }
    assert(rejected);
}
static void casefoldChecks(const std::string &scope) {
    assert(scope.starts_with("/tmp/cleanupwatcher-test-") && validRelative(scope.substr(1)));
    auto root = openRoot(scope);
    assert(fs::is_empty(scope));
    put(scope + "/probe-from");
    bool captureSupported = syscall(SYS_renameat2, AT_FDCWD, (scope + "/probe-from").c_str(), AT_FDCWD,
                                    (scope + "/probe-to").c_str(), RENAME_NOREPLACE) == 0;
    assert(captureSupported || errno == EINVAL || errno == EOPNOTSUPP);
    fs::remove(scope + (captureSupported ? "/probe-to" : "/probe-from"));
    put(scope + "/actual.log", "sentinel");
    assert(fs::exists(scope + "/ACTUAL.log"));
    auto literal = make(scope, 0, "ACTUAL.log");
    literal.literal = true;
    literal.recursive = false;
    auto job = plan(literal);
    assert(job.error.empty() && job.items.empty());
    literal.recursive = true;
    job = plan(literal, "ACTUAL.log");
    assert(job.error.empty() && job.items.empty());
    literal.pattern = "actual.log";
    job = plan(literal);
    assert(job.error.empty() && job.items.size() == 1 && job.items[0].path == "actual.log");
    Result result;
    if (captureSupported) {
        result = execute(job);
        assert(result.error.empty() && result.deleted == 1);
    } else
        fs::remove(scope + "/actual.log");
    put(scope + "/keep.log", "sentinel");
    put(scope + "/remove.log");
    auto files = make(scope);
    files.whitelist = {"KEEP.LOG"};
    job = plan(files);
    assert(job.error.empty() && job.items.size() == 1 && job.items[0].path == "remove.log");
    if (captureSupported)
        assert(execute(job).deleted == 1);
    else
        fs::remove(scope + "/remove.log");
    assert(fs::exists(scope + "/keep.log"));
    fs::remove(scope + "/keep.log");
    put(scope + "/ä.log", "sentinel");
    files.whitelist = {"Ä.LOG"};
    job = plan(files);
    assert(job.error.empty() && job.items.empty() && fs::exists(scope + "/ä.log"));
    fs::remove(scope + "/ä.log");
    fs::create_directories(scope + "/Junk/nested");
    auto tree = make(scope, 2, "Junk");
    tree.recursive = false;
    tree.whitelist = {"junk/future.log"};
    job = plan(tree);
    assert(job.error == "protected" && execute(job).deleted == 0);
    tree.action = 1;
    job = plan(tree);
    assert(job.error.empty() && job.items.empty());
    put(scope + "/Junk/nested/keep.log", "sentinel");
    files.whitelist = {"junk/NESTED"};
    job = plan(files, "Junk/nested/keep.log");
    assert(job.error.empty() && job.items.empty());
    tree.action = 2;
    tree.whitelist.clear();
    job = plan(tree);
    assert(job.error.empty() && job.items.size() == 3);
    if (captureSupported)
        assert(execute(job).deleted == 3 && !fs::exists(scope + "/Junk"));
    else
        fs::remove_all(scope + "/Junk");
    put(scope + "/race.log", "sentinel");
    files.whitelist.clear();
    job = plan(files);
    beforeCapture = [&] {
        fs::rename(scope + "/race.log", scope + "/race.tmp");
        fs::rename(scope + "/race.tmp", scope + "/RACE.log");
        assert(hasExactEntry(scope, "RACE.log"));
    };
    result = execute(job);
    beforeCapture = {};
    assert(result.deleted == 0 && result.error == "changed" && fs::exists(scope + "/RACE.log"));
    assert(!fs::exists(scope + "/.cleanupwatcher"));
    fs::remove(scope + "/RACE.log");
    if (captureSupported) {
        fs::create_directory(scope + "/Moving");
        put(scope + "/Moving/child.log", "sentinel");
        job = plan(files);
        int calls = 0;
        result = execute(job, [&] {
            if (++calls == 2) {
                fs::rename(scope + "/Moving", scope + "/moving.tmp");
                fs::rename(scope + "/moving.tmp", scope + "/MOVING");
                assert(hasExactEntry(scope, "MOVING"));
            }
            return true;
        });
        assert(result.deleted == 0 && result.error == "changed" && fs::exists(scope + "/MOVING/child.log"));
    }
    std::cout << "Case-insensitive exact names, whitelist aliases and pre-capture rename checks passed\n";
    if (!captureSupported)
        std::cout << "Casefold fixture lacks RENAME_NOREPLACE; successful captures use the main Linux safety suite\n";
}
int main(int argc, char **argv) {
    if (argc == 2) {
        casefoldChecks(argv[1]);
        return 0;
    }
    assert(argc == 1);
    char name[] = "/tmp/cleanupwatcher-test-XXXXXX";
    auto root = std::string(mkdtemp(name));
    fs::create_directory(root + "/scope");
    fs::create_directory(root + "/outside");
    put(root + "/outside/precious.log", "sentinel");
    auto scope = root + "/scope";
    auto rule = make(scope);
    auto literal = make(scope, 0, "a*.log");
    literal.literal = true;
    literal.recursive = false;
    put(scope + "/a*.log");
    put(scope + "/abc.log", "sentinel");
    auto exact = plan(literal);
    assert(exact.items.size() == 1 && exact.items[0].path == "a*.log");
    assert(execute(exact).deleted == 1 && fs::exists(scope + "/abc.log"));
    auto missing = plan(literal);
    assert(missing.error.empty() && missing.items.empty());
    fs::remove(scope + "/abc.log");
    fs::create_directory(scope + "/Junk[1]");
    fs::create_directory(scope + "/Junk1");
    fs::create_directory(scope + "/Junk[1]/inner");
    fs::create_directories(scope + "/Other/Junk[1]");
    put(scope + "/Junk[1]/a.log");
    put(scope + "/Junk[1]/inner/deep.bin");
    put(scope + "/Junk1/a.log", "sentinel");
    put(scope + "/Other/Junk[1]/a.log", "sentinel");
    auto named = make(scope, 2, "Junk[1]");
    named.recursive = false;
    auto exactTree = plan(named);
    assert(execute(exactTree).deleted == 4 && fs::exists(scope + "/Junk1/a.log"));
    assert(fs::exists(scope + "/Other/Junk[1]/a.log"));
    named.recursive = true;
    exactTree = plan(named);
    assert(execute(exactTree).deleted == 2 && !fs::exists(scope + "/Other/Junk[1]"));
    fs::remove(scope + "/Other");
    fs::remove_all(scope + "/Junk1");
    fs::create_directory(scope + "/FileTarget");
    put(scope + "/FileTarget/contents.bin", "sentinel");
    auto fileTarget = make(scope, 0, "FileTarget");
    fileTarget.literal = true;
    fileTarget.recursive = false;
    auto wrongType = plan(fileTarget);
    assert(wrongType.error.empty() && wrongType.items.empty() && execute(wrongType).deleted == 0);
    assert(fs::exists(scope + "/FileTarget/contents.bin"));
    put(scope + "/FolderTarget", "sentinel");
    for (int action : {1, 2}) {
        auto folderTarget = make(scope, action, "FolderTarget");
        folderTarget.literal = true;
        folderTarget.recursive = false;
        wrongType = plan(folderTarget);
        assert(wrongType.error.empty() && wrongType.items.empty() && execute(wrongType).deleted == 0);
        assert(fs::is_regular_file(scope + "/FolderTarget"));
    }
    fs::remove_all(scope + "/FileTarget");
    fs::remove(scope + "/FolderTarget");
    fs::create_directory(scope + "/Large");
    for (int index = 0; index < 20001; ++index)
        put(scope + "/Large/" + std::to_string(index));
    put(scope + "/root.log");
    auto shallow = make(scope);
    shallow.recursive = false;
    auto shallowJob = plan(shallow);
    assert(shallowJob.error.empty() && execute(shallowJob).deleted == 1);
    shallow.action = 1;
    shallow.pattern = "Large";
    shallowJob = plan(shallow);
    assert(shallowJob.error == "limit" && shallowJob.items.empty() &&
           shallowJob.directories == std::vector<std::string>{"Large"});
    shallow.action = 2;
    shallowJob = plan(shallow);
    assert(shallowJob.error == "limit" && execute(shallowJob).deleted == 0);
    assert(fs::exists(scope + "/Large/20000"));
    fs::create_directory(scope + "/Large/Inner");
    for (int index = 0; index < 6000; ++index)
        fs::rename(scope + "/Large/" + std::to_string(index), scope + "/Large/Inner/" + std::to_string(index));
    shallow = make(scope);
    shallowJob = plan(shallow, "Large");
    assert(shallowJob.error == "limit" && shallowJob.items.empty() && shallowJob.directories.size() == 2);
    fs::remove_all(scope + "/Large");
    fs::create_directory(scope + "/with space ");
    fs::create_directory(scope + "/with space");
    put(scope + "/with space / ");
    put(scope + "/with space/ ", "sentinel");
    auto spaced = make(scope + "/with space ", 0, " ");
    spaced.literal = true;
    spaced.recursive = false;
    auto spacedJob = plan(spaced);
    assert(execute(spacedJob).deleted == 1 && fs::exists(scope + "/with space/ "));
    fs::remove_all(scope + "/with space ");
    fs::remove_all(scope + "/with space");
    assert(decodeRules(encodeRules({rule}))[0].root == scope);
    auto longWhitelist = rule;
    for (int index = 0; index < 128; ++index)
        longWhitelist.whitelist.push_back(std::string(200, 'x') + std::to_string(index));
    assert(decodeRules(encodeRules({longWhitelist}))[0].whitelist == longWhitelist.whitelist);
    rejects([] { decodeRules("CW\n- - - 0 1 1 -\n"); });
    rejects([&] { decodeRules(encodeRules({rule}) + "extra\n"); });
    rejects([] { backingPath(""); });
    rejects([] { backingPath("/data/adb"); });
    rejects([] { backingPath("/sdcard/.cleanupwatcher"); });
    rejects([] { backingPath("/sdcard/.CLEANUPWATCHER/items"); });
    rejects([] { backingPath("/sdcard/../system"); });
    rejects([] { backingPath("/data/user/10/app/cache"); });
    rejects([] { backingPath("/data/user/0/.app/cache"); });
    rejects([] { backingPath("/data/user/0/com..app/cache"); });
    assert(backingPath("/sdcard/Logs") == "/data/media/0/Logs");
    assert(!validRelative("../outside"));
    assert(!validRelative("a//b"));
    assert(!validRelative("a/./b"));
    auto protectedRoot = openRoot(scope);
    assert(protectedPath(rule, protectedRoot.get(), "nested/.CLEANUPWATCHER/target"));
    rule.whitelist = {"keep"};
    assert(protectedPath(rule, protectedRoot.get(), "keep"));
    assert(protectedPath(rule, protectedRoot.get(), "keep/a.log"));
    assert(!protectedPath(rule, protectedRoot.get(), "keeper/a.log"));
    assert(!protectedPath(rule, protectedRoot.get(), "sub/keep/a.log"));
    assert(!protectedPath(rule, protectedRoot.get(), "Keep/a.log"));
    rule.whitelist = {"images/photo.jpg", "*.log"};
    assert(protectedPath(rule, protectedRoot.get(), "images/photo.jpg"));
    assert(!protectedPath(rule, protectedRoot.get(), "sub/images/photo.jpg"));
    assert(!protectedPath(rule, protectedRoot.get(), "images/photo.jpg.bak"));
    assert(protectedPath(rule, protectedRoot.get(), "*.log"));
    assert(!protectedPath(rule, protectedRoot.get(), "a.log"));
    rule.whitelist.clear();
    put(scope + "/remove.log");
    put(scope + "/keep.jpg");
    fs::create_directory_symlink(root + "/outside", scope + "/link");
    auto job = plan(rule);
    assert(job.error.empty() && job.items.size() == 1);
    auto result = execute(job);
    assert(result.error.empty() && result.deleted == 1);
    assert(!fs::exists(scope + "/.cleanupwatcher"));
    assert(fs::exists(scope + "/keep.jpg"));
    assert(fs::exists(root + "/outside/precious.log"));
    assert(fs::is_symlink(scope + "/link"));
    put(scope + "/race.log", "old");
    job = plan(rule);
    fs::rename(scope + "/race.log", scope + "/old.bin");
    put(scope + "/race.log", "replacement");
    result = execute(job);
    assert(result.deleted == 0 && result.error == "changed");
    assert(fs::exists(scope + "/race.log"));
    fs::remove(scope + "/race.log");
    put(scope + "/capture.log", "original");
    job = plan(rule);
    beforeCapture = [&] {
        fs::rename(scope + "/capture.log", scope + "/original.bin");
        fs::create_symlink(root + "/outside/precious.log", scope + "/capture.log");
    };
    result = execute(job);
    beforeCapture = {};
    assert(result.deleted == 0 && result.error == "changed");
    assert(fs::is_symlink(scope + "/capture.log"));
    assert(fs::exists(scope + "/original.bin"));
    fs::remove(scope + "/capture.log");
    fs::create_directory(scope + "/Junk");
    put(scope + "/Junk/a.log");
    auto tree = make(scope, 2, "Junk");
    tree.whitelist = {"Junk/a.log"};
    job = plan(tree);
    assert(job.error == "protected");
    assert(execute(job).deleted == 0);
    tree.whitelist = {"Junk/future.txt"};
    job = plan(tree);
    assert(job.error == "protected" && execute(job).deleted == 0);
    assert(fs::exists(scope + "/Junk/a.log"));
    tree.whitelist.clear();
    fs::create_directory_symlink(root + "/outside", scope + "/Junk/link");
    job = plan(tree);
    assert(!job.error.empty() && job.items.empty());
    assert(fs::exists(scope + "/Junk/a.log"));
    fs::remove(scope + "/Junk/link");
    job = plan(tree);
    result = execute(job);
    assert(result.error.empty() && result.deleted == 2);
    assert(!fs::exists(scope + "/Junk"));
    fs::create_directory(scope + "/Empty");
    auto empty = make(scope, 1, "Empty");
    job = plan(empty);
    put(scope + "/Empty/new.txt");
    result = execute(job);
    assert(result.deleted == 0 && !result.error.empty());
    assert(fs::exists(scope + "/Empty/new.txt"));
    fs::create_directories(scope + "/EmptyTree/other/deep");
    auto emptyTree = make(scope, 1, "EmptyTree");
    emptyTree.recursive = false;
    auto emptyJob = plan(emptyTree);
    assert(emptyJob.error.empty() && emptyJob.items.size() == 3);
    assert(emptyJob.items.front().path == "EmptyTree/other/deep" && emptyJob.items.back().path == "EmptyTree");
    put(scope + "/EmptyTree/other/deep/zero.bin", "");
    assert(plan(emptyTree).items.empty() && execute(emptyJob).deleted == 0);
    assert(fs::is_regular_file(scope + "/EmptyTree/other/deep/zero.bin"));
    fs::remove(scope + "/EmptyTree/other/deep/zero.bin");
    fs::create_symlink(root + "/outside/precious.log", scope + "/EmptyTree/other/link");
    assert(plan(emptyTree).items.empty());
    fs::remove(scope + "/EmptyTree/other/link");
    assert(mkfifo((scope + "/EmptyTree/other/pipe").c_str(), 0600) == 0);
    assert(plan(emptyTree).items.empty());
    fs::remove(scope + "/EmptyTree/other/pipe");
    emptyTree.whitelist = {"EmptyTree/future/item"};
    assert(plan(emptyTree).items.empty());
    emptyTree.whitelist.clear();
    emptyTree.pattern = "EmptyT*";
    emptyJob = plan(emptyTree, "EmptyTree/other/deep");
    assert(emptyJob.items.size() == 3 && execute(emptyJob).deleted == 3);
    fs::create_directory(scope + "/EmptyMany");
    for (int index = 0; index < 513; ++index)
        fs::create_directory(scope + "/EmptyMany/" + std::to_string(index));
    auto manyEmpty = make(scope, 1, "EmptyMany");
    manyEmpty.recursive = false;
    emptyJob = plan(manyEmpty);
    assert(emptyJob.error == "limit" && execute(emptyJob).deleted == 0);
    assert(fs::exists(scope + "/EmptyMany/512"));
    fs::remove_all(scope + "/EmptyMany");
    fs::create_directories(scope + "/Occupied/Empty/other");
    fs::create_directories(scope + "/Occupied/unmatched");
    put(scope + "/Occupied/keep.bin", "sentinel");
    auto nestedEmpty = make(scope, 1, "Occupied");
    nestedEmpty.recursive = false;
    assert(plan(nestedEmpty).items.empty());
    nestedEmpty.pattern = "Empty";
    assert(plan(nestedEmpty).items.empty());
    nestedEmpty.recursive = true;
    emptyJob = plan(nestedEmpty);
    assert(emptyJob.items.size() == 2 && execute(emptyJob).deleted == 2);
    assert(fs::exists(scope + "/Occupied/keep.bin") && fs::exists(scope + "/Occupied/unmatched"));
    fs::remove_all(scope + "/Occupied");
    fs::create_directory(scope + "/Overlap");
    put(scope + "/Overlap/remove.log");
    put(scope + "/Overlap/keep.log");
    auto parentRule = make(scope, 2, "Overlap");
    parentRule.whitelist = {"Overlap/keep.log"};
    auto childRule = make(scope + "/Overlap");
    childRule.whitelist = {"keep.log"};
    assert(plan(parentRule).error == "protected");
    auto childJob = plan(childRule);
    assert(execute(childJob).deleted == 1 && fs::exists(scope + "/Overlap/keep.log"));
    put(scope + "/Overlap/remove.log");
    childJob = plan(childRule);
    parentRule.whitelist.clear();
    auto parentJob = plan(parentRule);
    assert(execute(parentJob).deleted == 3);
    assert(execute(childJob).deleted == 0 && !fs::exists(scope + "/Overlap"));
    put(scope + "/cancel.log");
    job = plan(rule);
    result = execute(job, [] { return false; });
    assert(result.deleted == 0 && fs::exists(scope + "/cancel.log"));
    fs::remove(scope + "/cancel.log");
    put(scope + "/retained.log", "original");
    job = plan(rule);
    int calls = 0;
    result = execute(job, [&] {
        if (++calls == 2) {
            put(scope + "/retained.log", "replacement");
            return false;
        }
        return calls == 1;
    });
    assert(result.error == "retained" && result.deleted == 0);
    assert(plan(rule).error == "retained");
    auto restoration = recover(rule);
    assert(restoration.find("\"error\":\"retained\"") != std::string::npos);
    for (const auto &entry : fs::directory_iterator(scope + "/.cleanupwatcher")) {
        if (entry.path().extension() != ".meta")
            continue;
        std::ifstream metadata(entry.path());
        std::string saved((std::istreambuf_iterator<char>(metadata)), std::istreambuf_iterator<char>());
        put(entry.path(), saved + "unexpected\n");
        assert(recover(rule).find("\"error\":\"staging\"") != std::string::npos);
        assert(fs::exists(entry.path()));
        put(entry.path(), saved);
    }
    fs::rename(scope + "/retained.log", scope + "/replacement.bin");
    restoration = recover(rule);
    assert(restoration.find("\"restored\":1") != std::string::npos);
    std::ifstream restored(scope + "/retained.log");
    std::string original;
    restored >> original;
    assert(original == "original");
    fs::remove(scope + "/retained.log");
    fs::create_directory(scope + "/Moving");
    put(scope + "/Moving/race.log");
    job = plan(rule);
    calls = 0;
    result = execute(job, [&] {
        if (++calls == 2)
            fs::rename(scope + "/Moving", root + "/outside/Moving");
        return true;
    });
    assert(result.deleted == 0 && result.error == "changed");
    assert(fs::exists(root + "/outside/Moving/race.log"));
    for (int index = 0; index < 513; ++index)
        put(scope + "/limit-" + std::to_string(index) + ".log");
    job = plan(rule);
    assert(job.error == "limit" && job.items.empty());
    assert(execute(job).deleted == 0);
    for (int index = 0; index < 513; ++index)
        fs::remove(scope + "/limit-" + std::to_string(index) + ".log");
    fs::create_directory(scope + "/mounted");
    if (geteuid() == 0) {
        assert(mount((root + "/outside").c_str(), (scope + "/mounted").c_str(), nullptr, MS_BIND, nullptr) == 0);
        job = plan(rule);
        assert(!job.error.empty() && job.items.empty());
        assert(umount((scope + "/mounted").c_str()) == 0);
    }
    std::ifstream sentinel(root + "/outside/precious.log");
    std::string content;
    sentinel >> content;
    assert(content == "sentinel");
    fs::remove_all(root);
    std::cout << "CleanupWatcher safety checks passed\n";
}
