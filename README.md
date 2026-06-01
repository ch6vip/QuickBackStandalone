# QuickBackStandalone

独立的 LSPosed / Xposed 模块骨架，用来单独承载“切换上一个应用”功能。

## 目录结构

- `app/src/main/java/com/sevtinge/quickback/MainActivity.java`：简单设置页
- `app/src/main/java/com/sevtinge/quickback/hook/QuickBackHook.java`：桌面 Hook 入口
- `app/src/main/resources/META-INF/xposed/java_init.list`：现代 libxposed 入口声明
- `app/src/main/resources/META-INF/xposed/module.prop`：现代 libxposed 模块配置
- `app/src/main/resources/META-INF/xposed/scope.list`：模块作用域声明
- `app/src/main/res`：界面文案与布局

## 当前目标

- 提供一个独立 APK
- 只作用于 `com.miui.home`
- 一个开关控制是否启用

## 发布状态

- 当前版本：`1.1.1`
- 变更摘要：现代 libxposed 迁移、Android 16 适配、调试日志进一步收敛
- 详细记录见 [`CHANGELOG.md`](./CHANGELOG.md)

## 使用方式

1. 安装生成的 APK
2. 在 LSPosed 中勾选 `com.miui.home`
3. 打开应用，开启开关

## 实现总结

- 这是一个独立的 LSPosed / libxposed 模块，只对 `com.miui.home` 生效。
- 主体逻辑在 `QuickBackHook`，负责拦截桌面手势并切换到上一个任务。
- Android 16 的桌面方法和旧版不一致，不能再直接依赖旧的 `isDisableQuickSwitch()` 和 `loadRecentTaskIcon()`。
- 新版适配改成监听 `GestureStubView$3.onSwipeStart()` 和 `onSwipeStop()`，通过手势持续时间和位移判断是否属于 QuickBack。
- 任务切换时优先从 `RecentsModel.getTaskList()` 找当前任务和下一个任务，找不到再回退到旧的 `getSmartRecentsTaskLoadPlan()` 路径。

## 开关实现

- 设置页只负责写入本地偏好。
- Hook 端不再使用 `XSharedPreferences` 直接读文件，因为 Android 16 上会遇到不可读问题。
- 改为通过 `ContentProvider` 读取设置状态，桌面进程可以直接调用模块内的配置入口。
- 这样开关状态可以真正控制是否接管手势，不会再依赖 `fileCanRead` 之类的兜底逻辑。

## 工作原理

1. 用户在设置页打开开关，应用把状态写入自己的私有偏好文件。
2. `com.miui.home` 被 LSPosed 注入后，`QuickBackHook` 会挂到桌面手势相关类上。
3. 当用户从屏幕边缘发起并停顿到足够时间时，模块把这次滑动识别为 QuickBack。
4. 模块先通过桌面 `Context` 读取设置状态，只有 `provider=true` 才继续接管。
5. 接管时会先取消当前手势，再从最近任务列表里找“当前任务的下一个任务”。
6. 找到目标任务后，调用桌面的任务启动接口把它切到前台，并补上对应动画。
7. 如果没有找到可切换任务，就走失败反馈流程，避免误触后静默卡住。
8. 这样做的核心好处是：设置开关、Android 16 适配、任务查找和任务启动各自分层，互相不绑死。

## libxposed 迁移

- 迁移前已在本地创建备份分支：`backup/pre-libxposed-migration`。
- 模块入口从 legacy `xposed_init` 切换为现代 `META-INF/xposed/java_init.list`。
- 模块配置改为 `META-INF/xposed/module.prop` 和 `META-INF/xposed/scope.list`。
- Hook 入口从 `IXposedHookLoadPackage` 改为继承 `XposedModule`，在 `onPackageReady()` 里注册 Hook。
- Hook 注册从 `XposedHelpers.findAndHookMethod()` 改为 `hook(method).intercept(...)`。
- 公开稳定依赖使用 `io.github.libxposed:api:101.0.1`。目前公开文档和仓库显示稳定 API 是 101，未直接使用不可确认的 `102.0.0` 坐标。

## 验证日志

正常情况下，日志里会看到这些关键信息：

- `handleLoadPackage: hooks installed`
- `handleRecentSwipeStop: task started`

如果开关关闭，不会输出每次读取开关的调试日志，也不会执行任务切换。正式版只保留 Hook 安装、Hook 失败、任务切换成功和失败反馈相关日志。

## 运行说明

- Android 16 上旧版桌面实现里的 `isDisableQuickSwitch()` 和 `loadRecentTaskIcon()` 可能已经不存在，日志里出现 `method missing` 属于兼容分支跳过。
- 这不会影响当前主链路，只要能看到 `handleLoadPackage: hooks installed` 和 `handleRecentSwipeStop: task started`，说明模块已经接管到新的手势路径。
- 若 `provider=true`，说明设置页和 Hook 侧已经连通，开关状态能正常控制模块行为。

## 致谢

- 感谢 [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler) 项目在 HyperOS / MIUI 系统功能适配和 LSPosed Hook 实现上的参考价值。

## 与 HyperCeiler 的细节差异

- HyperCeiler 的 QuickBack 更偏向桌面原生流程，主要围绕 `GestureStubView` 的旧式状态机和 `getNextTask()` 进行接管。
- 这个独立版额外加入了 Android 16 的现代兜底：当旧状态机不可用时，改用 `onSwipeStart()` 记录按住时间，再用 `onSwipeStop()` 的持续时间和偏移判断是否为 QuickBack。
- HyperCeiler 侧更像是把“下一个任务”塞回桌面的原流程，我们这里是在 `onSwipeStop()` 里直接完成任务启动、收尾和失败反馈。
- 独立版优先从 `RecentsModel.getTaskList()` 和 `ActivityManagerWrapper` 找任务与启动入口，再回退旧的 `getSmartRecentsTaskLoadPlan()` 路径。
- 独立版把设置页和 Hook 侧解耦成 `ContentProvider` 读取开关，避免 Android 16 上 `XSharedPreferences` 直接读文件的问题。
- 所以两者都能做 QuickBack，但独立版更偏“针对当前桌面版本的单功能适配”，HyperCeiler 更偏“系统增强套件里的一个功能分支”。
