# 主机 Agent 与 Collector 工程验证

## 范围与状态

Task 6 按 Owner 本轮“执行下一步”指令实施，沿用已接受的单设备 Smoke 设计、TDR-024/025 和 Task 5 机器契约。最终独立复审已批准工程实现，双语实施已推送，四条准确实施提交 CI 均成功。本记录不代替 Owner 验收。

基线中文 3fd2622d129ea70c08c24f4c81cd6e3dfcbbb484、英文 2e9f6293cbe5ec632520db0c3438af76f7a256c3。Task 5 固定实施 Subject 与证据仍见[Event 与结果工程记录](attempt-result-verification.md)，不被本轮替换。

最终实施 Subject：中文 84db30893d5c1f47c75128f9c89b25b828c833cb；英文 cd195191d49f744b4787e9b90b0322359f9dbc28。初始实现分别为 832d62f / cebae27，修复作为独立提交保留。远端 HEAD 已与上述最终 Subject 精确核对。工程记录使用后续独立配对文档提交，不替换实施 Subject。

## 实现与技术选择

独立 [Agent 工程](../../agent/README.md)包含严格 CLI、mTLS 同源客户端、固定 ADB 操作、执行 journal、LOG/SCREENSHOT Plugin、共享 JCS Result 摘要及恢复主循环。源码、测试和说明均在 agent/，不修改 Backend、数据库迁移、Schema、Quality 或 Verified。

复用 Backend Gradle 8.14.4 Wrapper、Kotlin catalog 和 Spring Boot BOM；Agent 不依赖 Spring runtime。四份 Wrapper 字节匹配，gradlew 为 100755，JAR SHA-256 为 7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172。Schema/OpenAPI/golden JSON 直接通过 resources 纳入原件，不维护第二份 wire 定义。

关键选择已记入 [TDR-024](../v0.2/tdr/TDR-024-single-device-smoke-execution.md)：使用已有 aapt 与 JVM 直接执行 apksigner.jar；持久化动作意图；原样重放已保存 Result 确认丢失的终态回执；同受控账户、同规范 serial 的跨进程设备锁。APK 和 PNG/日志均有资源上限，容量不足停止新工作，不引入质量阈值或 Company 服务。

本轮工程判断及判断错误时的代价：

| 判断 | 理由 | 错误时的代价 |
|---|---|---|
| 只执行 Task 6，Task 7 真机串联单列 | 沿用逐项实施范围，不提前作真机结论 | 调整任务边界和交接记录 |
| 使用已有 Build Tools 与 JVM 直接调用 apksigner | 复用现有工具并避免 batch shell | 调整工具调用/输出解析 |
| 可重放完全相同的 durable Result 确认旧回执 | 沿用已接受的终态幂等契约，不恢复新写权限 | 重做终态回执恢复设计 |
| 单受控账户、唯一规范 serial 是设备锁前提 | 满足单机演示，明确不声称跨账户互斥 | 需要跨账户/别名时先调整锁与配置 |
| P3 诊断覆盖明确延期、非阻断 | 不导致假 PASS、重放动作或改写历史 | 若演示必须保留首因，先补诊断保存和失败测试 |

## 测试与复审

实际环境为 Windows、Temurin 21.0.7+6。以下 Gradle 命令从仓库根运行，等价于进入 agent/ 后运行其 Wrapper：

- 初始完整验证：agent/gradlew.bat -p agent clean test build --console=plain，退出 0，41 tests / 0 failed / 0 errors / 0 skipped。
- 第一轮修复后完整验证：同一命令，退出 0，68 tests / 0 failed / 0 errors / 0 skipped。
- 最终修复后完整验证：同一命令，退出 0，81 tests / 0 failed / 0 errors / 0 skipped；10 个 Gradle task 全部实际执行，无编译警告。
- installDist 退出 0；生成的 CLI 使用未知参数实跑退出 1，仅输出 CLI_INVALID，符合负例预期。
- 控制器直接解析每轮最终 XML，确认单次统计，不把重叠轮次相加。编译与 Gradle check 为本轮检查；没有额外 Agent lint 配置。

| 套件 | 修复后 Tests | 范围 |
|---|---:|---|
| AdbExecutorTest | 7 | 真实有界子 JVM、双流、超时、超限、失效 lease |
| AgentClientIntegrationTest | 3 | 真实 localhost HTTPS/mTLS、重定向、重传、总期限 |
| AgentConfigTest | 1 | CLI/配置拒绝 |
| AgentLoopIntegrationTest | 3 | 终态回执重放及失效拒绝 |
| AgentRecoveryTest | 2 | 阶段恢复规则 |
| AndroidSmokeDeviceTest | 13 | 显式设备端口替身、SDK 26 边界与环境复核 |
| ApkInspectionTest | 2 | APK 工具输出解析 |
| JournalTest | 6 | 持久化、损坏拒绝、链接/跨进程锁 |
| PackagePreflightTest | 13 | 真实子进程出口经实际预检，首次安装/已有包/异常拒绝 |
| ResultDigestContractTest | 2 | 共享 canonical golden 与摘要 |
| SmokeAssertionsTest | 5 | 本次 UI 标记、XML/输入边界 |
| SmokeFlowTest | 24 | 真实 HTTPS + 显式设备替身的完整流程与失败恢复 |

初始行为 RED 分别记录 Recovery 未实现、ACKED 重启重复 STARTED/Windows junction、错误回执/小数 journal/未知事件响应。Gradle DSL、缺 API 和生成源码编译错误单独披露，没有当作行为 RED；最终消除了新增 Jackson fields() 弃用警告，没有屏蔽警告。

第一轮任务评审发现三项 Important：缺少执行后环境复核、API ≥ 26 预检、永久上传拒绝后的 ERROR/部分 Evidence 收尾。聚焦修复先出现 21 项中的 16 项失败，5 项保护分支通过，随后 21 项全部通过。修复确认观察后、采集后及上传后结果固化前复核环境；SDK 不支持在安装前拒绝；永久拒绝的稳定 Problem code 经过 status/path 校验后保存，仍须有效租约才能提交 ERROR 与已确认 IDs。503、未知响应、临时 I/O、权限拒绝和 STALE_LEASE 保留 spool，不转成可写结果。直接设备封装测试中的夹具编译问题另行保留，最终通过后才运行完整 build。

第一轮聚焦复审确认三项 Important 全部关闭，没有新问题。最终全切片复审新增 F1：AOSP pm path 对未安装包返回 1/空输出，旧进程异常丢失返回语义，阻止干净设备首次安装。最终修复保持统一进程层严格非零异常，只在固定已有包查询识别 exit=1 且 stdout/stderr 完全为空；成功必须有唯一路径且无 stderr，其他失败继续拒绝。13 项组合回归先出现 3 项行为失败，随后覆盖相关边界的 33 项通过；最终 81 项来自一次完整 build。APK 检查仅抽取单方法端口，生产实现仍唯一。AOSP 依据见[包查询实现](https://android.googlesource.com/platform/frameworks/base/%2B/d18c61ae8e7ec024352e71e405a040b829376f50/services/core/java/com/android/server/pm/PackageManagerShellCommand.java)。

最终聚焦复审在 84db308 上批准工程实现，F1 已关闭，没有新增问题。P3 / D1（多个 Collector 失败覆盖首要诊断）经最终评审维持 OPEN / NON-BLOCKING / EXPLICITLY DEFERRED；它降低诊断能力，但不产生错误 PASS、重放动作或改写历史，不能宣称已修复或已有覆盖。

## 实际本地 APK 核查

控制器另用已构建 Agent 的 ApkInspector/BoundedProcess 调用实际 Build Tools 34.0.0，对 Task 1 已有 APK 检查，退出 0：versionCode=1，APK SHA-256 为 282187c056abe33b6f2b7629896d32a27d6244318990d6ec909e7136a4377ea6，签名证书 SHA-256 为 6a52389eda39ba559e04c54549ba325db01258ea0eb18fe0668c4589f6ec43c1，均与原记录匹配。它验证主机工具与解析器，不代表安装后读回或真机行为；不计入 JUnit 数量。

## 双语与远端检查

基线契约检查 schemas=5、positive=13、negative=6、operations=36，验收记录校验通过。初始 implementation Pair Gate 通过；最终修复后 451 项非 Markdown blob/模式一致，最终 Pair Gate 通过；以下准确实施提交 CI 均为 SUCCESS。

| 语言 / Subject | M1 Backend | M2 Backend |
|---|---|---|
| 中文 / 84db308 | [34457197484](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34457197484) | [34457197531](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34457197531) |
| 英文 / cd19519 | [34457197131](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34457197131) | [34457197140](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34457197140) |

现有 M1/M2 工作流不执行 Agent tests。本轮 Agent 证据是上述本地测试/build；远端 M1/M2 只用于既有回归，不能替代 Agent 验证。新的 M3 CI 入口属于 Task 7。

## 已知限制与保留材料

- 未执行任何真实 Android 设备操作。完整 Smoke 流程使用真实本地 HTTPS 和显式设备替身；实际 ADB/安装后 APK 读回、真实 Backend 数据库串联及 Task 7 故障演练仍未验证。
- 同设备排他依赖单受控账户和唯一规范 serial，不覆盖跨账户或 selector 别名。Windows 文件 force/原子替换不等于目录 fsync 或掉电绝对持久。
- 多个 Collector 失败可能覆盖预检的首要 reasonCode；该 P3 诊断缺陷明确未修复，不改变采集失败应为 ERROR 的契约。
- spool 全部保留；空间不足停止，没有自动清理。其他 OS 的脚本/文件行为尚未验证。
- 本轮不代表完整 Crash/ANR、设备断电、M3、Release Gate 或 Company 验收，Verified 保持原语义。历史 M2.5 P95 超过 1000 ms 参考目标、canonical 非主路径覆盖及最早 2026-10-07 Artifact 到期限制不因此消失。
- 原始分轮日志、XML、命令源和摘要保存在本轮忽略的 SDD Evidence 目录；版本化记录保留结论与固定 Subject。真实 Payload、设备 serial、私钥和口令不提交 GitHub。

## 下一步执行计划

当前结果：Task 6 工程实现、81 项测试/build、独立复审及准确实施提交 CI 核对完成；P3 明确非阻断延期。Git 状态：上述双语实施 Subject 已推送；本记录使用独立配对文档提交，不替换实施 Subject，文档提交的准确 CI 在提交后核对。

唯一下一步：Task 7 串联、CI 与真实设备验证。前置条件：Task 7 实施指令；真机操作前明确选定设备/配置、API ≥ 26、ADB 授权及允许的测试 APK 安装/启动范围。CI 工作可独立推进；设备不可用时真机结果记录 UNKNOWN，不自动选择首台设备。

验收目标：区分 CI_FIXTURE 与 REAL_DEVICE，验证正常与确定 FAIL 的 Run → Result → LOG/PNG 关联及下载 SHA 复算，并在允许范围内验证断连/Agent 重启恢复；以新固定 Subject 形成 PENDING 验收记录，不代表完整 M3 或 Owner 验收通过。
