<p align="center">
  <img src="docs/app-icon.svg" width="144" alt="Cleanup Watcher icon">
</p>

<h1 align="center">Cleanup Watcher</h1>

[中文文档](README.zh-CN.md)

An Android root cleaner built for reliable, continuous background monitoring. An independent C++ backend handles monitoring and automatic cleanup, while the app handles configuration and management. Monitoring and cleanup continue after the app process ends.

## ✨ Features

- Stable and reliable independent backend
- Efficient, low-overhead monitoring using inotify kernel events, without periodic polling
- Clean files or folders by name or pattern
- Clean empty child folders or entire empty folder trees, with whitelisted paths protected
- Import and export rules
- Optional Magisk module for reliable startup at boot

## 📥 Installation

Download the APK from [GitHub Releases](https://github.com/jasongzy/CleanupWatcher/releases).

Requires Android 11 or newer and root access.

Open the app, grant root access, and install the backend. Add a rule, review its matches, then enable it. The optional Magisk module is managed separately in the app.

## 🔒 Safety

Cleanup is limited to primary-user shared storage and app cache folders. Enabling a rule permanently deletes all existing and future matches, so review the preview carefully.

Before uninstalling the app, remove the backend and Magisk module from within the app. Otherwise, the backend will continue monitoring and cleaning files.
