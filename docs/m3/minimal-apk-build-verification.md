# 最小演示 APK — Task 1 构建验证

## 范围与实施指令

2026-09-09，Owner 在“下一步执行 Task 1，构建最小演示 APK”后回复以下原文，按上下文解释为“执行下一步”。仅实施 Task 1；不授权 Task 2–7、设备操作、Company、merge、Tag、发布或部署。本记录是工程验证，不代替 Owner 验收，也不建立额外的组件验收门槛。

```json
{"instruction":"\u5fd7\u5174\u4e0b\u4e00\u6b65"}
```

依据：[实施计划](../superpowers/plans/2026-09-09-single-device-smoke-implementation.md)、[设计](../superpowers/specs/2026-09-09-single-device-smoke-design.md)、[TDR-024](../v0.2/tdr/TDR-024-single-device-smoke-execution.md)。工程入口及使用方法见 [APK README](../../demo/android-smoke/README.md)。

## 固定源码与产物

- 中文实施 Subject：[9a636999beb5bdcc0563e44f4d146794285a6497](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/9a636999beb5bdcc0563e44f4d146794285a6497)。
- 英文配对 Subject：[b27fc82521481893cd83394b2faad039108ced52](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/b27fc82521481893cd83394b2faad039108ced52)。
- 本地实际构建位于中文 worktree；英文非 Markdown 的 Git blob 和文件模式已逐项核对一致，不宣称独立跑过英文构建。
- 本次 APK 为 `demo/android-smoke/app/build/outputs/apk/debug/app-debug.apk`，构建目录不入 Git。下表记录该次产物，不能保证其他主机使用不同 debug 签名重新构建后得到相同摘要。Task 7 创建 Manifest 时必须重新核对实际 APK。

| 项目 | 本次结果 |
|---|---|
| APK SHA-256 | 282187c056abe33b6f2b7629896d32a27d6244318990d6ec909e7136a4377ea6 |
| APK bytes | 7677 |
| packageName | com.ricezhou.vsrqg.smoke |
| versionCode / versionName | 1 / 1.0 |
| minSdk / compileSdk / targetSdk | 26 / 35 / 35 |
| Signing | Android debug / APK Signature Scheme v2 / 1 signer |
| Certificate SHA-256 | 6a52389eda39ba559e04c54549ba325db01258ea0eb18fe0668c4589f6ec43c1 |
| Gradle 8.9 distribution SHA-256 | d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab |
| Gradle 8.9 Wrapper JAR SHA-256 | 498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17 |

官方 Wrapper 分发及 JAR 摘要分别与 [Gradle 分发校验值](https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256)和 [Wrapper 校验值](https://services.gradle.org/distributions/gradle-8.9-wrapper.jar.sha256)比对。未提交 debug 私钥、local.properties、构建缓存或代理配置。

## 已执行检查

实际工具链为 JDK 17.0.12、AGP 8.7.3、Gradle 8.9、Android platform 35 revision 2、Build Tools 34.0.0、JUnit 4.13.2。进程显式指定 JAVA_HOME 和 SDK 路径；不依赖 Android CLI 的另一处默认 SDK。以下命令从 APK 工程目录执行，apksigner/aapt 使用上述 Build Tools：

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
.\gradlew.bat lintDebug --no-daemon
.\gradlew.bat assembleDebug --no-daemon
apksigner verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk
aapt dump badging app/build/outputs/apk/debug/app-debug.apk
```

| 检查 | 结果 | 证据与限制 |
|---|---|---|
| TDD RED | PASS | 先写测试再写生产类；Gradle 退出 1，唯一失败任务 compileDebugUnitTestJavaWithJavac，4 处 SmokeMarker 未定义；不是依赖故障。 |
| TDD GREEN | PASS | 退出 0，JUnit 1 项、0 failure/error/skipped；覆盖两种 mode、缩写 UUID 和未知 mode 拒绝。 |
| 最终 lintDebug | PASS with warnings | 退出 0；3 Warning、0 Error，详见下一节。 |
| 最终 assembleDebug | PASS | 退出 0；本表 APK 文件实际存在，SHA-256/size 重新计算一致。 |
| 签名与元数据 | PASS | apksigner/aapt 退出 0；包名、版本及 SDK 匹配，v2 签名有效。 |
| Manifest 范围 | PASS | 唯一 Activity；无 permission/service/receiver/provider。 |
| Wrapper | PASS | 官方分发与 JAR 摘要一致；双语 gradlew Git mode 均为 100755。 |
| Task 1 独立评审 | PASS | 初审发现 Wrapper 缺少可执行位；修复后规范及质量复审 Approved，无新破坏。 |
| 最终独立工程复审 | PASS | 完整授权 APK 切片 Approved，无 Critical/Important 或新增可操作问题；不推导 Owner/merge 批准。 |
| 设备安装与 UI | NOT RUN | 未执行 ADB、安装、启动、旋转、onNewIntent 或 UI 检查；由 Task 7 验证。 |

首次日志保存路径错误在 Gradle 启动前暴露，修正后重新执行并保留有效 RED。Android CLI 遥测连接曾失败，不作为 SDK 或构建成功证据。文件枚举的 Windows 长路径警告已明确区分；版本化检查使用 core.longpaths=true。

## 已知警告与边界

- OldTargetApi：设计固定 targetSdk 35，不为消除警告擅自升级。
- MissingApplicationIcon：最小演示未配置图标，不影响固定组件的启动入口。
- SetTextI18n：页面拼接固定机器标记与 Attempt UUID，不属于本轮本地化文案。
- 冗余 screenOrientation 属性已删除，DiscouragedApi 警告已消失；没有关闭 lint 或添加抑制规则。

上述三项被任务评审列为非阻断 Minor；完整 M3、Run/Result、Evidence 上传、真实车辆 Release 和 Company 行为未由本次验证。已有性能、canonical 和历史 Artifact 保留限制不因此关闭。

## 下一步执行计划

当前结果：Task 1 APK 已构建，工程验证及任务复审通过；未代做 Owner 验收。Git 状态：实施 Subject 如上，记录提交与实施提交分离；推送以远端核对为准。下一步动作：执行 Task 2，实现 Agent 身份、注册与上下文机器契约。前置条件：Task 2 实施指令；沿用已接受设计，无需 Company 资源。验收目标：mTLS 与 JWT 隔离、注册及上下文契约正负测试、跨项目/撤销拒绝和双语提交可核对。
