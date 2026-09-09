# Android Smoke 演示 APK

这是 VSRQG 单设备 Smoke 流程使用的最小 Java APK。它使用一个原生 `Activity` 和 `TextView` 显示合成演示标记，不包含网络权限、服务、后台任务、依赖注入或 Compose。

## 工具链

- JDK 17
- Android Gradle Plugin 8.7.3
- Gradle Wrapper 8.9
- compileSdk / targetSdk 35
- minSdk 26
- Android Build Tools 34.0.0
- JUnit 4.13.2

设置 `JAVA_HOME`、`ANDROID_HOME` 和 `ANDROID_SDK_ROOT` 后，在本目录运行：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`。

## 输入与输出

入口为 `com.ricezhou.vsrqg.smoke/.SmokeActivity`，只接受两个 Intent extra：

- `attemptId`：完整的小写标准 UUID 字符串。
- `mode`：`normal` 或 `assertion-failure`。

`normal` 显示 `SYNTHETIC_DEMO` 和 `VSRQG_SMOKE_READY:<attemptId>`；`assertion-failure` 显示 `SYNTHETIC_DEMO` 和 `VSRQG_SMOKE_NOT_READY:<attemptId>`。无效输入只显示 `SMOKE_INPUT_INVALID` 并结束 Activity。

## 验证边界

本目录的单元测试、lint、构建和签名检查仅验证 APK 构建产物及纯函数输入契约。这里不执行 ADB 或设备命令；安装、启动、方向切换、`onNewIntent` 和真实 UI 行为由后续真机任务验证，不能由单元测试替代。
