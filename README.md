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

