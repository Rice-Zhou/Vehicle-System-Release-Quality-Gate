# TDR-024 — 单设备 Smoke 执行与最小演示 APK

- 日期：2026-09-09；状态：Accepted，限本演示设计/规划及后续 Task 1 APK、Task 2 身份/注册/机器契约、Task 3 Run/Attempt/调度/租约实施；Task 4 本地 Evidence 工程验证已完成，见[工程记录](../../m3/local-evidence-verification.md)；Task 5 Event/Result/Run 完成契约的工程实现、复审与准确提交 CI/Artifact 核对已完成，见[结果工程记录](../../m3/attempt-result-verification.md)；Task 6 主机实现、测试/build 和独立复审已完成，准确交付状态见[主机 Agent 验证](../../m3/host-agent-verification.md)；Task 7 实际交付状态见[串联工程记录](../../m3/single-device-smoke-verification.md)。
- 依据：[Owner 设计批准](../../governance/acceptance/records/2026-09-09-m3-smoke-design-review-001.md)；原文保存在[receipt](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/7271be84cf73fd4172c4072c807772b98aa68522)。
- 范围：M3 首个演示切片；设计见[单设备设计](../../superpowers/specs/2026-09-09-single-device-smoke-design.md)。
- Owner 已确认有 Android 设备、允许安装和运行测试应用，并选择由项目新增最小演示 APK。后续 Owner 指定 ADB 连接的 Android 车机，只读预检 API 34；真实安装与串联状态见[串联工程记录](../../m3/single-device-smoke-verification.md)。

- Task 1：后续 Owner 实施指令与构建检查见[构建验证](../../m3/minimal-apk-build-verification.md)，不等于完整 M3 验收。
- Task 2：后续实施指令及证据见[身份与注册工程验证](../../m3/agent-identity-registration-verification.md)；运行 Context/Payload 仍分属 Task 3/4，不等于真机或完整 M3 验收。
- Task 3：后续实施指令与实际验证状态见[Run 与租约工程验证](../../m3/run-lease-verification.md)；不授权 Evidence Payload、Agent Result 上报或设备执行。

## Run 输入与环境绑定

Task 3 保留严格 CreateTestRun 请求中的 releaseId、testPlan 和 deviceSelector，不扩展客户端任意设备路径或环境声明。单设备演示由显式服务端配置选择已登记的 Agent/Device，并提供有界 CONFIG 内容；创建时检查项目、selector 与能力，计算实际 CONFIG 字节摘要，与 Locked Manifest 的 CONFIG checksum 匹配后固化 Environment。配置不是第二个 Release 内容权威；缺失、不匹配或超出执行范围均明确拒绝，普通 Backend 的默认 INCOMPLETE 文件验证策略保持不变。该选择复用现有配置与 PostgreSQL，不增加环境服务。

配置使用默认关闭的 `vsrqg.demo.smoke.enabled`，以及同前缀的 `agent-id`、`device-id`、`environment-config-base64`。后者编码准确 CONFIG 字节，解码后最多 64 KiB，编码输入也必须有界；base64 仅用于传输，不是加密。CONFIG 严格包含 bootSessionId、buildId、buildFingerprint，不含凭据或原始设备序列号。启动来源配置与预登记 Device 的 vehicle/platform 必须符合请求 selector 及 Release 范围。

## Task 5 结果提交与 Evidence 封闭依赖

Task 5 消费既有 AttemptEvidence 应用端口；Session 的元数据封闭不依赖本地 Payload 开关。Evidence 模块保留唯一的真实实现与同一 JdbcEvidenceRepository，取消/期限关闭可在文件存储禁用时封闭原有 Session。需要真实 bytes 的 resolve 在 PayloadStore 不可用时明确 EVIDENCE_STORAGE_DISABLED，不提供返回成功的替代实现；HTTP 上传/下载及文件对账仍沿用原演示开关。

该选择使生命周期只依赖既有应用端口，不由 Test Management 直接读取 Evidence 表或维护另一套封闭规则。原 binding 的 Session 封闭先于 fencing 代次变化，和终态 Result、Attempt、Run、Audit/Outbox 位于相同提交边界。默认无数据库的上下文测试为真实元数据仓储提供明确测试替身，不改变生产依赖缺失语义。代价是元数据端口需要在默认上下文装配，并须验证文件存储关闭、已存在 Session、取消/超时及回滚分支。

V13 单 Case 演示限制保持；Run 完成使用统一判定检查所有 Published Case Resolution 和所有已创建 Attempt，测试 optional Case/Attempt 边界，不为本轮扩展调度器或解除单 Case 数据约束。Result 的幂等、摘要及现有租约/权限语义保持。实际实现与检查见[结果工程记录](../../m3/attempt-result-verification.md)。

Task 5 通过 V15 增量保存终态结果投影与 inputDigest，新终态在原事务内冻结；后续查询读取同一固定投影，不随 Payload 缺失或完整性观察重新生成历史事实。迁移前已终态的 V13/V14 Run 不改写原行，查询时以相同纯投影/JCS 函数读取已冻结输入与 Result；不使用 SQL jsonb 文本代替 JCS，不虚构缺少的结果。快照要求标志仅豁免迁移前已终态行，已有活动 Run 与新 Run 均要求终态快照；标志不可后改，新插入不能以 false 绕过。该选择保留历史查询能力，同时增加增量迁移和新旧数据兼容验证的责任。

Event 按已存 Command/Attempt 绑定从 sequenceNo=1 连续接收；相同序列和摘要返回旧确认，跳号或不同摘要明确冲突。Result 请求仍不新增 Command/sequence 字段，以服务端已存流核对；预检 ERROR/BLOCKED 无需虚构 STARTED，本演示没有发布跳过条件时拒绝 Agent SKIPPED。已鉴权的迟到冲突通过独立短事务写隔离 Audit，只保留固定归属、code 与摘要；不写原始 Payload，不改终态事实，Audit 失败不能静默变成诊断已保存。该事务不得等待外层持有的业务行锁，须以真实数据库回归确认。

模块依赖保持单向：唯一 AttemptEvidence / EvidenceResolution 出站端口由消费方 Test Management 的 application 包拥有，Evidence 提供实现并继续复用同一仓储。统一更新源码引用，不保留无调用者的兼容别名；Evidence 特定异常由已有按异常类型映射的处理器覆盖跨 Controller 调用，Test Management 不直接依赖 Evidence 异常。该最小依赖倒置避免两模块成环，不改变端口语义或冻结 Core Contract；代价是端口位置和异常处理适用范围须经架构、应用及 HTTP 回归确认。

## Task 6 主机执行与恢复实现

Task 6 已按本轮指令完成主机实现、测试/build 和独立复审，准确交付状态见[主机 Agent 验证](../../m3/host-agent-verification.md)。主机配置显式引用现有 Android Build Tools 的 aapt 和 apksigner.jar，使用 JVM 直接执行 JAR，避免 Windows batch shell。以固定参数检查 APK 包名、版本、签名及 checksum；签名输出不明确或无法读取时拒绝执行，不安装额外 SDK。此选择复用现有工具，代价是必须验证工具输出解析与失败路径。

结果请求先完整持久化，再提交。若服务端已终态而响应丢失，恢复时可重放完全相同的 durable Result PUT 来确认原 receipt；不得重新生成时间、Evidence IDs 或 digest。只有匹配原绑定和摘要的响应才能确认本地完成；拒绝或失效后保留诊断，不继续新的 Evidence 写入、申请新租约或重放设备动作。该确认沿用 Task 5 的终态幂等契约，过期租约的新动作仍受原 RecoveryPolicy 禁止。代价是必须分别验证“旧结果已接收”和“旧结果从未接收且已过期”两条路径。

设备排他以单受控执行账户和唯一规范 serial 为部署前提；账户目录中的哈希锁覆盖跨进程、跨 spool，spool 另持独占 journal 锁。该方案不保证跨操作系统账户或 selector 别名互斥；需要这些能力时必须先调整锁范围和部署配置。Windows 使用文件 force 与原子替换，不声称 JDK 不支持的目录 fsync 或掉电绝对持久。实现保留所有 spool 内容，空间不足停止新工作；这些限制和资源上限由 Agent README 说明。

## Task 7 演示配置与测试入口

本轮实施指令启动串联工程。顶层字段沿用已接受计划，server 严格包含 origin 和 lifecycle（START / EXISTING）；具体身份、设备和工具信息通过受控配置文件引用提供。START 仅启动本次拥有的本地演示 Backend，使用显式提供的 loopback PostgreSQL；EXISTING 只经 API 使用已有授权演示，不初始化或停止既有服务。缺失运行条件明确失败，不自动安装数据库或启用 Company。

包装层测试沿用实际 m1-demo.tests.ps1 的原生 PowerShell 断言与命令注入，不依赖计划示例中的新版 Pester 语法。相同测试源在 Windows 与 CI 执行，保留非零退出、CONFIG_INVALID、无报告、场景失败摘要、结果关联和摘要校验。代价是自行维护有界测试进程和失败诊断，必须由实际测试与独立评审核实。

M3 跨模块整链使用独立 m3IntegrationTest source set/task，显式构建并引用 Agent/APK 输入；配置、报告及 HTTP 边界单测留在普通 Backend test。既有 M1/M2 不隐式增加 SDK 或 Agent 构建前提，缺少 M3 输入时该独立任务必须失败。生产 bootJar 不包含 demo/Agent runtime。每次显式整链执行禁用 up-to-date/build-cache，使用新的 invocation 目录保留本次材料，禁止重复 UUID；CI 只上传本次目录，避免旧成功报告或 Payload 混入失败回合。代价是维护独立测试 classpath、证据目录定位与 CI 入口，需要实际构建验证。

START 初始化允许只读核对并复用完全匹配的既有 SYNTHETIC_DEMO 项目、Agent/Device、证书绑定及已发布 Plan/Case 定义；不完整、非合成或冲突状态明确拒绝，不重新绑定、不更新发布内容、不猜测替代 ID。若临时 JWT 生命周期需要，可新建受限演示 USER 会话。运行态心跳等可变观察不作为不可变定义比较项；既有 Run/Result/Evidence 保留。该选择让正常、确定失败及重复演示可使用同一受控身份，代价是初始化匹配/事务与连续启动必须经真实数据库回归验证。

## 选择与替代方案

Task 7 的有限演示可显式传入 Agent 可选参数 `--until-attempt-acked=<UUID>`；原六参数持续运行入口不变。目标 Attempt 由本次 Run 的正式 API 提供，Agent 复用原 AgentLoop、journal 和 receipt 校验，仅在该目标持久化为 RESULT_ACKED 后正常退出。协调器有界等待自然退出且退出码为零，之后才完成成功摘要；Server 终态本身不代替本地回执确认，旧 journal 重放不算本次目标完成。CI 夹具使用同一完成路径并在退出前执行自身断言。此选择修复服务端提交与主机确认之间的收尾竞态，不扩展 Result/Quality 权威；代价是 CLI 兼容、完成循环、进程等待与恢复旧 journal 的回归验证。

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
