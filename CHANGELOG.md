# Changelog

## Unreleased

- 收紧现代 QuickBack 兜底触发条件，从 `350ms + 180f` 调整为 `700ms-1800ms + 300f`，降低和系统普通返回手势混淆的概率。
- 修复设置页未启用 QuickBack 时仍可能吞掉系统返回手势的问题；开关关闭时会完整放行桌面原始返回逻辑。
- `isDisableQuickSwitch()` 只在 QuickBack 开关启用时强制放开，关闭时走桌面原实现。
- Provider 读取入口限制为模块自身和 `com.miui.home`，减少无关应用读取开关状态的暴露面。
- 升级 Android Gradle Plugin 到 `8.9.1` 并补充 Gradle Wrapper，匹配 `compileSdk 36`。

## 1.1.1

- 进一步收敛运行日志，移除现代手势识别的逐次调试输出。
- 补充 Android 16 上旧桌面方法缺失时的兼容说明。

## 1.1

- 迁移到现代 libxposed API 入口。
- 适配 Android 16 / 新版系统桌面手势路径。
- 设置页开关改为通过 `ContentProvider` 控制 Hook 生效状态。
- 清理调试日志，正式版只保留关键安装、失败和任务切换日志。
- 兼容旧桌面方法缺失时自动跳过，不再输出无意义的逐帧调试信息。

## 1.0

- 初始独立 LSPosed 模块。
- 提供简单设置页。
- 实现侧边停顿手势切换上一个任务。
