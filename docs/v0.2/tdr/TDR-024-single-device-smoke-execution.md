# TDR-024 — 单设备 Smoke 执行与最小演示 APK

- 日期：2026-09-09；状态：Accepted，限本演示设计/规划及后续 Task 1 APK 实施；其余任务未授权。
- 依据：[Owner 设计批准](../../governance/acceptance/records/2026-09-09-m3-smoke-design-review-001.md)；原文保存在[receipt](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/7271be84cf73fd4172c4072c807772b98aa68522)。
- 范围：M3 首个演示切片；设计见[单设备设计](../../superpowers/specs/2026-09-09-single-device-smoke-design.md)。
- Owner 已确认有 Android 设备、允许安装和运行测试应用，并选择由项目新增最小演示 APK。连接方式、系统版本及具体设备未实测。

- Task 1：后续 Owner 实施指令与构建检查见[构建验证](../../m3/minimal-apk-build-verification.md)，不等于完整 M3 验收。

## 选择与替代方案

建议以 Kotlin/JVM 21 主机 Agent 调用现有 ADB，复用已接受的 Agent HTTPS pull/ACK/Event/Result 协议。Agent 使用独立命令行进程，不能直接访问 Backend 数据库或写最终 Quality Result。现有 Backend 保留测试编排、身份、租约及权威记录；不增加 Broker 或设备池。

| 方案 | 取舍 |
|---|---|
| 主机 Agent + ADB + 最小测试 APK | 推荐；执行与设备分离，利用现有 JVM/ADB，设备断开时仍能保留本地主机记录；须验证 ADB 能力与中断路径。 |
| 设备内常驻 Agent | 多出后台存活、权限、升级及断电恢复问题，首个安装/启动演示不需要。 |
| 独立 ADB 脚本直接输出成功 JSON | 容易演示，但绕过 Run/Attempt、租约与正式 Evidence 关联，不作为产品实现。 |

## APK 与构建

新增独立 `demo/android-smoke/` Android 工程。使用 Java 单 Activity 和原生 TextView，仅显示固定说明及当前 Attempt 标记，不增加 Compose、依赖注入、网络、账号或后台服务。包名 `com.ricezhou.vsrqg.smoke`，唯一入口 `.SmokeActivity`；只接受 UUID 格式的 `attemptId` 和 `normal` / `assertion-failure` 两种固定 mode，后者仅为演示失败夹具。

构建基线：AGP 8.7.3、Gradle 8.9、JDK 17、compileSdk/targetSdk 35、minSdk 26、Build Tools 34.0.0。这是隔离的 APK 构建，不变更 Backend Kotlin 2.2.21 / Gradle 8.14.4 / JVM 21。Android 官方[兼容性表](https://developer.android.com/build/releases/agp-8-7-0-release-notes?hl=en)列出 AGP 8.7 的 Gradle 8.9、JDK 17 和最高 API 35；不声称这是最新版本。原设计时仅确认 PATH 存在 ADB；后续 Task 1 已验证 SDK 构建能力，设备 API Level 仍未验证。实施时固定 Wrapper 摘要、记录实际 SDK/签名证书摘要；密钥不入库。

## 执行与信任

ADB 仅为白名单设备执行机制，参照[官方 ADB 文档](https://developer.android.com/tools/adb)。只允许明确选定设备上的测试包安装、启动、定向日志/UI 状态读取及截图。参数使用进程参数列表，包名/Activity 固定；不接受任意 shell、自动选首台设备、卸载、清数据、刷机或设备重启。已有不同签名的同名包明确阻止，不能自动卸载解决。

Agent 使用既有独立 mTLS 身份契约；证书与项目、Device、Agent 绑定由服务端控制。用户 JWT 不代替 Agent 身份，Agent 无权发布 Plan、改变 Manifest 或创建 Quality Result。APK 是不可信测试对象，只提供观察标记，最终 Test Result 由 Agent 对本次启动、前台组件及 UI 标记进行断言后上报；是否满足 Release Gate 留给未来 Quality Engine。

采集只面向该测试应用与本次 Attempt，日志不读取整机无关缓冲；截图在确认测试 Activity 位于前台后采集，个人通知等风险仍需展示设备保持干净。真实 Payload 默认本地受控保存，不自动推送 GitHub。

## 验证、回退与边界

验证构建/lint、输入拒绝、安装与签名冲突、启动/UI 断言、旧标记、ADB 断连、进程超时、Agent 重启和同次 Result/Evidence 对应。CI 夹具与真实设备分别记证据。停止 Agent 即停止新任务；保留数据库历史与 spool，不自动卸载应用。

本决定不批准完整 M3、真实车辆 Release 或 Issue Verified。Test Run 仍固定 Release/Locked Manifest/Plan/Environment；合成演示 Release 必须标明其测试范围，不能将测试 APK 等同完整车辆 Release。Evidence 保存由 TDR-025 单独说明。扩展设备池、其他 APK、刷机或设备内 Agent 时重新评估本 TDR。
