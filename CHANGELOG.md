# Changelog

## 1.1

- 迁移到现代 libxposed API 入口。
- 适配 Android 16 / 新版系统桌面手势路径。
- 设置页开关改为通过 `ContentProvider` 控制 Hook 生效状态。
- 清理调试日志，正式版只保留关键安装、失败和任务切换日志。

## 1.0

- 初始独立 LSPosed 模块。
- 提供简单设置页。
- 实现侧边停顿手势切换上一个任务。
