#pragma once
#include "core.h"

namespace cw {
inline constexpr int BACKEND_VERSION_CODE = 3;
inline constexpr int MODULE_VERSION_CODE = 1;
inline constexpr char MODULE_VERSION_NAME[] = "v1.0";
extern const char *HOME;
extern const char *SOCKET;
extern const char *BINARY;
void writeFull(int fd, const std::string &value);
std::string readFull(int fd, size_t limit = 512 * 1024);
Fd stateDir(bool create = true);
std::string load(const char *name, const std::string &fallback);
void save(const char *name, const std::string &value, mode_t mode = 0600);
Fd connectServer();
Fd lockFile(int dir, const char *name);
void installBackend();
void removeBackend();
void checkReleasedScopes(const std::vector<Rule> &previous, const std::vector<Rule> &next = {});
std::string configureBackend(const std::string &payload);
void installModule();
void removeModule();
std::string inspect(bool magisk);
} // namespace cw
