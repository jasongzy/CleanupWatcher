<p align="center">
  <img src="docs/app-icon.svg" width="144" alt="Cleanup Watcher 图标">
</p>

<h1 align="center">Cleanup Watcher</h1>

[English Doc](README.md)

专注于稳定、持续后台监测的 Android root 清理工具。独立 C++ 后端负责监测与自动清理，App 负责配置与管理；结束 App 后，监测和清理仍会继续。

## ✨ 功能

- 稳定可靠的独立后端程序
- 基于 inotify 内核事件通知的非轮询文件监测，高效、低开销
- 按名称或匹配模式清理文件或文件夹
- 仅空文件夹与路径白名单等清理条件
- 规则导入与导出
- 可选 Magisk 模块，提供可靠的开机启动

## 📥 安装

从 [GitHub Releases](https://github.com/jasongzy/CleanupWatcher/releases) 下载 APK。

需要 Android 11 或更高版本及 root 权限。

打开应用，授予 root 权限并安装后端。添加规则，检查预览后启用。可选的 Magisk 模块在应用中单独管理。

## 🔒 安全

清理范围限于主用户共享存储和应用缓存目录。启用规则后，会清理现有及后续出现的所有匹配项目，不可恢复，请仔细确认预览。

卸载应用前，请先在应用中移除后端和 Magisk 模块，否则后端监测清理仍会持续运行。
