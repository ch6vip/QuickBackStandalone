# QuickBackStandalone

独立的 LSPosed / Xposed 模块骨架，用来单独承载“切换上一个应用”功能。

## 目录结构

- `app/src/main/java/com/sevtinge/quickback/MainActivity.java`：简单设置页
- `app/src/main/java/com/sevtinge/quickback/hook/QuickBackHook.java`：桌面 Hook 入口
- `app/src/main/assets/xposed_init`：Xposed 入口声明
- `app/src/main/res`：界面文案与布局

## 当前目标

- 提供一个独立 APK
- 只作用于 `com.miui.home`
- 一个开关控制是否启用

## 使用方式

1. 安装生成的 APK
2. 在 LSPosed 中勾选 `com.miui.home`
3. 打开应用，开启开关

## 实现总结

- 这是一个独立的 LSPosed 模块，只对 `com.miui.home` 生效。
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

## 验证日志

正常情况下，日志里会看到这些关键信息：

- `hookOnSwipeStart: installed`
- `hookOnSwipeStop: installed`
- `modern quick back gesture: ...`
- `handleRecentSwipeStop: task started`
- `isEnabled: provider=true`

如果开关关闭，日志会显示 `isEnabled: provider=false`，并且不会执行任务切换。
