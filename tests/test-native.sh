#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/native-tests
g++ -std=c++20 -Wall -Wextra -Werror -O1 -g -fsanitize=address,undefined -DCLEANUPWATCHER_TEST native/core.cpp native/tests.cpp -o build/native-tests/safety
build/native-tests/safety
python3 tests/test-casefold.py
g++ -std=c++20 -Wall -Wextra -Werror -O2 native/core.cpp native/daemon.cpp native/lifecycle.cpp native/browser.cpp -pthread -o build/native-tests/cleanupwatcherd
g++ -std=c++20 -Wall -Wextra -Werror -O2 -DCLEANUPWATCHER_TEST native/core.cpp native/daemon.cpp native/lifecycle.cpp native/browser.cpp -pthread -o build/native-tests/test-daemon
python3 tests/test_backend.py
python3 tests/test-lifecycle.py
python3 tests/test-browser.py
