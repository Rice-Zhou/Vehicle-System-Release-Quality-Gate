# 单设备 Smoke 串联工程验证

## 范围与当前状态

Task 7 的串联工程、独立复审、准确双语 CI 与 Artifact 核对已完成；真实设备正常/确定 FAIL、断连/Agent 重启及本地数据库/Payload 恢复未执行，整个 Task 7 不标记完成。新验收记录 [M3-SMOKE-IMPLEMENTATION-OWNER-GATE-001](../governance/acceptance/records/2026-09-10-m3-smoke-implementation-owner-gate-001.md) 为 PENDING，不代替 Owner 决定。

最终实施 Subject：中文 dbd59a48ba9c7dc9279588e046182dbf97ab22ef；英文 bc1f62637ac9f9357912abf85961a65cb0035852。实施基线为中文 47c29e96a864d64d15620046e96dfb65bc1cf753、英文 a387bcf436327cd7a4ec9b26d9e80ec96e582488。承载本记录的提交与实施 Subject 分离，记录版本通过 Git history 定位。

## 实现与验证

演示入口经正式 API 创建 Release、注册/验证/Lock APK+CONFIG Manifest、创建 Run，再由正式 AgentLoop 提交 Result 与 Evidence；协调器下载 LOG/PNG 复算 SHA-256，并等待本次目标的持久化 ACK 和自然零退出。START 只复用完全匹配的合成定义，EXISTING 不初始化或停止既有服务。操作方法见[运行手册](single-device-smoke-runbook.md)，技术选择见 [TDR-024](../v0.2/tdr/TDR-024-single-device-smoke-execution.md)。不直接写 Result、AVAILABLE 或 Verified；来源 Case FAIL 与场景 PASS 分开。

本地各轮证据分别记录：初版 Backend 20 项、Agent 81 项、APK 1 项与包装脚本 14 项通过；修复轮 Backend 18 项、Agent 定向 32 项及相关构建通过，均 0 failure/error/skip。APK 本地使用 JDK 21 的增量构建，CI 使用 JDK 17；不把各轮累加为一次测试，也不声称所有增量任务重新执行。

本地显式 m3IntegrationTest 执行 2 项，均在 PostgreSQL 启动阶段因 Docker 不可用失败，未跳过，未完成整链。保留该失败；后续 CI 成功不改写本机结果。Pester/缺类编译错误仅为工具或编译失败，不计为行为 RED；未知配置、凭据路径重叠、诊断超限清理均另有有效 RED→GREEN。

独立任务复审的三项 Important 已关闭：派生 artifacts 链接写入越界、Server 终态与 Agent 持久化确认之间的竞态、非法数据库配置错误分类。实际 junction 回归验证外部目录和 output 均无新增文件；子 JVM 回归阻塞服务端 Result 响应，确认旧 journal 重放不完成新目标，只有本次 receipt 校验/持久化后才自然退出。首轮范围复审及最终工程复审通过，Task 6 P3 诊断限制继续明确延期。

初次 M3 CI（中文 34467757847、英文 34467757917）在 Linux 包装脚本处失败：Get-Command 返回多个 pwsh 路径，被合并为一个命令；后续又复现 14 项断言通过但预期负例遗留 Exit=1。最终仅修改单个路径选择和全部断言/清理后的成功退出，原断言未削弱。实际三 PATH 命中回归为 14 PASS/Exit=0；本次唯一最终 CI 修复波次的范围复审通过，无新增 Critical/Important。初次 CI 上传的四份 APK 文件不被当作整链 Evidence。

## 准确提交 CI 与 Artifact

以下六条运行均绑定上述最终实施 Subject，全部 SUCCESS。完整 Pair Gate 与 465 个非 Markdown blob/mode 一致性通过；原子推送和准确远端 HEAD 已核对。

| 分支 | 工作流 | Run | 状态 |
|---|---|---|---|
| ZH | M1 | [34468641193](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641193) | SUCCESS |
| ZH | M2 | [34468641319](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641319) | SUCCESS |
| ZH | M3 | [34468641285](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641285) | SUCCESS |
| EN | M1 | [34468641281](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641281) | SUCCESS |
| EN | M2 | [34468641201](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641201) | SUCCESS |
| EN | M3 | [34468641234](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641234) | SUCCESS |

独立下载并核对以下六份选定 Artifact：metadata 大小/SHA-256、未过期、ZIP CRC/路径、准确 Subject 和内部报告。没有将同一工作流的其他 Artifact 自动计入验证。

| 分支/类型 | Artifact | ZIP bytes | ZIP SHA-256 | 到期 UTC |
|---|---|---|---|---|
| ZH M1 | [10148963678](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641193/artifacts/10148963678) | 274748 | 0e29da074d45e66d2dc818734c309864b52f7e71741308a793e7cf72cafead7a | 2026-10-10T11:07:44Z |
| ZH M2 | [10148854955](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641319/artifacts/10148854955) | 1753 | 13f0bf166d3204b3166e02bb0481f2ce74a9324cd065829a5c18b5b447955a4b | 2026-10-10T11:04:30Z |
| ZH M3 | [10148776017](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641285/artifacts/10148776017) | 27847 | bad87adf9791a755bbf491df7993eaf4d6ab65fa68bfc9f54dd6e7d0176b781f | 2026-10-10T11:02:12Z |
| EN M1 | [10148982391](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641281/artifacts/10148982391) | 274538 | 73f2a65bfdf07a835f6339e81a1a6824a74575f5e662d41184e7b7e944ea4419 | 2026-10-10T11:08:15Z |
| EN M2 | [10148934960](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641201/artifacts/10148934960) | 1750 | 4373979fc5078f8fad446e55eeea38247d01704a2f2d66d6592a255d98b9c771 | 2026-10-10T11:06:53Z |
| EN M3 | [10148783792](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641234/artifacts/10148783792) | 27851 | 8099a0e48ef8742560b47e99c4fd7dd1b3aa35153fcfaf71fb60fd66397cef1f | 2026-10-10T11:02:26Z |

每份 M3 Artifact 的 XML 为 APK 1、Agent 83、Backend 25、PostgreSQL 整链 2，共 111 项，0 failure/error、1 skip。唯一 skip 是 Linux 不适用的 Windows junction 配置/输出路径检查；整链 2 项均通过且未跳过。包装层另有 14 项实际 Linux 检查。lint 保留三条既有 Warning，无 Error/Fatal。

每份 M3 Artifact 包含唯一 invocation 和三个不同 Run：normal 原始 Case PASS、已有服务 v2 原始 Case FAIL、重复 START v2 原始 Case FAIL；三者 generation SUCCEEDED、scenario PASS、Run COMPLETED。每个 Run 的两份 Evidence ID、size、payloadChecksum 与实际下载 SHA-256 一致，六份 LOG/PNG 均已逐字节复算；Results 引用由 Attempt ID 与正式 resultDigest 表示，历史查询稳定。Artifact 的 summary 只含 Result 摘要引用，未据此声称离线重算了完整 Result JCS。所有场景仍为 CI_FIXTURE、NOT_EVALUATED、verified=false，明确未覆盖实际 ADB/UI、断连、Agent 重启与备份恢复。

下载的两份 CI APK 均为 7645 bytes；独立 aapt/apksigner 再次核对包名 com.ricezhou.vsrqg.smoke、versionCode 1、minSdk 26、targetSdk 35 与对应签名摘要。中文 APK SHA-256 为 e46f91f80c1f03c1c975a7606e577208fc41b7d51d874c4a36fe23a3270bb5f5，英文为 d629116d60f431430e1774dae7f4547c6144977f3e63d9b7c2676f61de6afc6d。两次 CI 的临时 debug 签名不同；它们也不是私有目录中本地 JDK 21 生成的 7677-byte APK，真机执行时必须固定实际使用文件的身份与摘要。

每份 M1 Evidence 为 CANDIDATE、8/8 gate、1137 项测试、0 failure/error、3 个 Linux 不适用的 Windows ACL skip；包含本轮 Backend 25 项且均通过。134 份保留报告的 bytes/hash 已核对；两份 build JAR 不在该 ZIP，未声称独立核对其 bytes。两份 M2.5 Evidence 均 12/12 PASS，20 Issues/2000 Edges，历史重放及 backupRestore/dbRestartReclaim/deadLetter/manualRetry 通过；这些是既有回归，不证明 M3 恢复。

## 真实设备与运行前提

Owner 指定 Android 车机、ADB 连接，并允许在 D 盘创建本地配置。2026-09-10 只读预检发现唯一已授权设备、API 34；boot/session、build 和 fingerprint 保存于仓库外当前账户受控目录，原始值与序列号不入库。设备配置和经 SHA-256 核对的本地演示 APK 副本已准备，数据库/TLS 运行配置未生成。预检仅证明当时的连接，执行前必须复核。

Owner 明确确认尚无本地数据库；本机未发现 Docker、Podman 或 PostgreSQL 工具、服务和进程。本轮没有安装新的数据库环境，没有安装/启动 APK、收集真机日志/截图、执行真实 Run→Result→Evidence、断连/Agent 重启或数据库/Payload 恢复。这些真实设备与本地恢复检查为 UNKNOWN，不因 CI 通过勾选完成。

## 工程判断与代价

| 判断 | 理由 | 错误时的代价 |
|---|---|---|
| 独立工程继续，真机交付保留 UNKNOWN | 已接受计划允许运行前提缺失时继续 CI | 调整真机交付安排和交接记录 |
| server 使用 origin/lifecycle 严格对象，其他身份与设备信息使用文件引用 | 保留顶层字段并落实已有/自有服务区分 | 配置、手册与拒绝测试同步迁移 |
| START 只拥有本地演示进程，EXISTING 不初始化或停止既有服务 | 限定数据和进程操作权限 | 调整生命周期集成和配置兼容性 |
| 原生 PowerShell 断言复用现有 M1 测试方式 | 现有 Pester 版本不支持计划示例，不另建兼容层 | 需要框架时迁移测试入口和计划示例 |
| M3 整链使用独立测试任务，普通 Backend 测试保持独立 | 既有 M1 不隐式依赖 Agent/APK/SDK，M3 明确检查输入 | 调整 Gradle source set/classpath 与工作流 |
| START 只复用完全匹配的合成身份与发布定义 | 同一受控身份支持正常/失败和重复演示，历史事实不变 | 调整初始化匹配/事务和连续启动回归 |
| Agent 可选等待本次 Attempt 的持久化回执后自然退出 | 消除 Server 终态与主机 ACK 之间的收尾竞态，复用原完成路径 | CLI、完成循环、协调器生命周期与运行手册协同修改 |

## 保留限制

Task 6 P3 首因诊断覆盖仍 OPEN/NON-BLOCKING/EXPLICITLY DEFERRED：后续 Collector 失败可能覆盖首因，但结果强制 ERROR，不能被新协调器计为场景成功。单账户/唯一规范 serial 设备锁、Windows 持久化与 spool 保留限制见[主机记录](host-agent-verification.md)。既有 APK 的 OldTargetApi、MissingApplicationIcon、SetTextI18n，以及本地 JDK 21 编译 Java 8 source/target 警告继续保留，不擅改固定 SDK。

本次 M2.5 创建 Run P95 为中文 1340 ms、英文 1650 ms，超过 1000 ms 参考目标，仅满足共享 CI 30000 ms 硬上限，不代表 Company 性能达标。既有 canonical 摘要不覆盖非主路径全部字段，不能声称任意篡改均可检测。本次六份选定 Artifact 最早于 2026-10-10T11:02:12Z 到期；历史实施 Artifact 最早 2026-10-07 到期的限制保留，按既有 Evidence Archive 治理处理。过期或不可访问时相应检查转为 UNKNOWN。

本轮不代表完整 Crash/ANR、设备断电、M3、Release Quality Gate 或 Company 验收，不改变 Verified；未执行 merge、Tag、发布、部署或真实 Provider。原始各轮日志/XML、检查脚本及下载副本保留在本计划忽略的 SDD 目录，版本化记录保留固定定位与摘要，不提交真实 Payload、序列号或凭据。

## 下一步执行计划

当前结果：Task 7 独立工程与 CI Evidence 完成，真机/恢复未交付，Owner Gate PENDING。Git 状态：上述双语实施 Subject 已推送；本记录和验收记录采用独立双语文档提交，记录版本由 Git history 定位，提交后核对其准确 CI。

唯一下一步：在 D 盘准备并验证一个隔离的本地 PostgreSQL/Backend 演示运行条件。前置条件：Owner 允许新增这一项本地演示依赖；使用本次固定实现和仓库外受控配置，不启用 Company/真实 Provider。验收目标：数据库连接与 Backend 健康检查可重复通过，配置引用及运行/停止方式可核对；真机 normal/FAIL 和恢复仍须另行实测，不从环境准备推导通过。
