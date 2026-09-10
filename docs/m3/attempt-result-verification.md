# Event 与结果 — Task 5 工程验证

## 范围与实施指令

2026-09-10，Owner 在明确的 Task 5 下一步后指示“执行下一步”。本轮依据已接受的设计与计划实施 Event、Result 与 Run 完成契约。Task 6–7、设备操作及 Company 不在本轮范围；本记录属于工程验证，不替代 Owner 验收，不新增组件验收门槛，不授权 merge、Tag、发布或部署。

依据：[实施计划](../superpowers/plans/2026-09-09-single-device-smoke-implementation.md)、[设计](../superpowers/specs/2026-09-09-single-device-smoke-design.md)、[TDR-024](../v0.2/tdr/TDR-024-single-device-smoke-execution.md)、[Task 4](local-evidence-verification.md)。

## 实施 Subject 与行为

最终实施 Subject：中文 19cd72d610773d6884b05e1ae484d5fc11b1f786；英文 56240e45e5d0b39085ff4e416fca15153cb5e718。后续验证记录提交不替代这两个实施身份。配对提交已推送，420 个非 Markdown 文件的 blob/模式一致，Pair Gate PASS；独立最终复审 APPROVE_FINAL，无遗留发现，准确 CI 与 Artifact 核验均已完成。

Event 按 Command/Attempt 绑定从序号 1 连续接收；同摘要重放返回原确认，跳号、异内容和旧代新写入明确冲突。Result 使用原严格 Schema、无损数字和日期格式校验，对副本执行既有 JCS 摘要算法；API 不因 canonical 去重而接受重复 Evidence ID。终态重放重新检查原 Agent 的当前权限，不随后来文件观察改变历史确认。

正常结果、取消和期限关闭复用同一生命周期：先封闭原 binding 的未完成 Session，再变更 fencing，Result、Attempt、Run 快照及 Audit/Outbox 原子提交。PASS 要求真实且同属当前 Attempt 的 required Evidence；ERROR 可保留部分 Evidence 并明确失败项。统一完成判定覆盖全部 Case Resolution 与已创建 Attempt，包括 optional，不扩展 V13 单 Case 演示调度。

结果查询返回运行身份、冻结 Plan/Environment、各 Attempt 的 Test Result 和 Evidence requirements 以及 inputDigest，不生成 Quality Result。V15 保存新终态快照，旧终态以相同冻结事实投影读取，缺失 Run、合法旧快照 NULL 与新终态缺快照的行为明确区分。文件存储默认开关及 Company 边界保持原约束。

## 已执行检查

基线中文 c5b6f9332bc5088a7d3e1bafe5fedc421a9562bc、英文 a458003030e072be4df93c844becb3b6648b5bb8。两语工作区干净、upstream 0/0、远端精确提交一致。重新查询的四条基线 CI 均为 SUCCESS：中文 M1 34436318143 / M2 34436318212；英文 M1 34436316736 / M2 34436316654。新运行的契约校验 schemas=5、positive=13、negative=6、operations=36 与验收记录校验 PASS；这些仅证明 Task 5 开始前状态。

本地 final-verified 的 JUnit 已由控制器独立汇总：52 tests / 0 failures / 0 errors / 0 skipped，包括架构 6、Evidence HTTP 10、元数据端口 2、Agent HTTP 安全 7、身份事务 3、Result 应用 6、canonical 3、通用完成 3、期限应用 10、默认上下文 2。应用仓储与身份替身不等于真实 PostgreSQL；本地未执行数据库场景，最终准确提交的实际 PostgreSQL 结果见下文。

## 修复与复审记录

初始实施为中文 8be9a15b668e54d31c20b918bd3e9a32044ab24b / 英文 648487e1629dd41c49d1c2d0a49f2e4a07c73b50。任务审查发现 I1：默认 DoubleNode 解析在无损校验前舍入数字。原始请求回归先记录 12 tests / 4 failures，另记录安全整数边界 2 / 2；中文 7436026184e3888ec44ddc3a4b2ed67f9f6ed733 / 英文 45bd4939349f416692e4a5f3c705ab15d7764525 在首次解析保留精确小数，再沿用同一 Schema/JCS 边界校验。控制器独立核对修复后 44 / 0 / 0 / 0，不与初始 52 项累加。原始请求测试使用 MockMvc、证书 request attribute 和 Mockito 应用替身；其中 EvidenceHttpTest 的 10 项另以真实 localhost TLS 执行，仍使用身份与数据库替身。

初始 M1 中文 34444190275 / 英文 34444190249 均为 1103 tests / 2 failures / 0 errors / 3 skipped。失败 Artifact 已下载并核对大小及 SHA-256，保留原始 JUnit：CI1 是完整 ACK 相同但 Jackson LongNode/IntNode 的 equals 不同；CI2 是 V15 回填产生延迟触发器事件，阻止后续 ALTER TABLE，SQLSTATE 55006。初始 M2 中文 34444190317 / 英文 34444190238 均 SUCCESS，不能代替修复后的准确提交验证。

中文 7a0b9437551fe562ca37aaf79576267957d81372 / 英文 b7c3b07dc7a5bfe13b5eda1a1e4acd5862fa8342 修复两项 CI 发现：完整 JCS ACK 比较加唯一 Audit/Outbox 断言，不增加生产转换；V15 执行原 run_closed_attempts 约束、恢复 DEFERRED 后继续 DDL，不关闭触发器，并检查原、新约束仍启用。限定复审将 I1、CI1、CI2 均判为 ADDRESSED，无新增问题。

整个 Task 5 最终复审随后发现 I2：未启用 Schema 格式断言，无效 Event 时间可进入已接受事实。同轮 M1 中文 34445595281 / 英文 34445595216 均为 1110 tests / 1 failure / 0 errors / 3 skipped；V15 已走过原 DDL 错误，但真实仓储 JdbcClient.single 拒绝旧终态合法的 SQL NULL（CI3）。两份失败 ZIP 已按元数据校验并保留，M2 中文 34445595223 / 英文 34445595171 均 SUCCESS。

唯一最终修复批次包含中文 e83f2d751011fbdec7839d2861a5574e1010629c / 英文 a7783ffd13ad06989938060cef307122f5e96dde 的统一格式断言，以及中文 19cd72d610773d6884b05e1ae484d5fc11b1f786 / 英文 56240e45e5d0b39085ff4e416fca15153cb5e718 的可空快照读取。I2 原始请求 6 / 6 RED 后，定向 50 / 0 / 0 / 0；CI3 诊断 3 / 2 RED 后，定向 15 / 0 / 0 / 0，均由控制器独立汇总 JUnit，不累计重叠套件。TerminalSnapshotJdbcTest 使用真实 Spring JdbcClient、受控 JDBC 替身，区分旧终态 NULL、缺 Run 和新终态缺快照，不能冒充 PostgreSQL。限定最终复审已将 I2、CI3 均判为 ADDRESSED，APPROVE_FINAL，无新增或遗留问题；真实迁移场景已由下文最终准确 CI 验证。

## 本轮技术裁决与代价

1. 仅执行既有计划的 Task 5，沿用已接受设计及本轮实施指令；若范围判断错误，需要重划任务边界。
2. 元数据封闭在文件存储关闭时仍可用，复用唯一实现和仓储，缺 Payload 明确失败；若依赖选择错误，需要调整默认装配或终态历史处理。
3. 新终态保存快照，旧终态以同一冻结事实投影读取；若兼容策略错误，需要调整迁移与历史读取。
4. 消费方持有唯一 AttemptEvidence / EvidenceResolution 端口以保持模块单向依赖；若归属错误，需要调整端口位置及异常映射范围。
5. ACK 重放比较完整协议内容，不以 Jackson 数字节点类型为对外契约；若存在此类 JVM 内部兼容要求，需要先明确并补定向兼容处理。

前四项的实现边界见 TDR-024；第五项修正测试对协议的表达，不增加生产序列化规则。所有裁决均未重定义冻结架构或代替 Owner 验收。

## 准确提交 CI

四条最终实施 CI 均 SUCCESS，均绑定本记录开头的最终实施 Subject：

| 语言 | M1 | M2.5 |
|---|---|---|
| 中文 | [34446819598](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34446819598) | [34446819620](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34446819620) |
| 英文 | [34446819246](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34446819246) | [34446819270](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34446819270) |

两份 M1 Artifact 均为 CANDIDATE，8 个 gate 的 exitCode 全为 0；各有 1119 tests / 0 failures / 0 errors / 3 skipped。3 项跳过为 Linux 上不适用的 Windows ACL 用例：ControlledPayloadStoreTest 1 项、EvidenceArchiveDirectoryAccessReaderTest 2 项；不算已执行通过。Task 4 的 18 项 PostgreSQL 回归、AgentExecutionSecurityTest 20 项及 ArchitectureTest 6 项均通过。

Task 5 新增测试类在两语 Artifact 中均无失败或跳过：

| 测试类 | 数量 | 验证边界 |
|---|---:|---|
| AttemptResultIntegrationTest | 4 | 真实 PostgreSQL，Event/Result/Evidence 与授权重放 |
| TestRunCompletionIntegrationTest | 3 | 真实 PostgreSQL，取消/期限、故障回滚及竞争 |
| ResultSnapshotMigrationIntegrationTest | 1 | 真实 PostgreSQL，V14 历史恢复迁移至 V15、旧终态及新约束 |
| ResolveAttemptEvidenceTest | 2 | 元数据端口与默认关闭存储 |
| AttemptResultApplicationTest | 6 | 应用逻辑及 Spring 事务代理，仓储/身份替身 |
| ResultCanonicalizerTest | 3 | JCS golden、摘要及入参不变 |
| RunCompletionTest | 3 | 包括 optional 的通用完成判定 |
| TerminalSnapshotJdbcTest | 3 | 真实 Spring JdbcClient，JDBC 替身的 NULL/无行语义 |

两份 M2.5 Evidence 均为 12/12 PASS；实际 20 Issues / 2000 Edges，3 次采样，历史重放、backupRestore、dbRestartReclaim、deadLetter、manualRetry 均 PASS。创建 Run P95 中文 1468 ms / 英文 1414 ms，超过 1000 ms 参考值，仅通过 30000 ms 共享 CI 硬上限；worker P95 3986/4095 ms、query P95 25/22 ms。Evidence 中 migrationVersion=V11 是既有 M2.5 固定标识，实际 V15 迁移由上述 PostgreSQL 用例验证，不改写历史标识。

控制器下载四份 Artifact，核对未过期、文件大小和 GitHub SHA-256；每份 M1 的 131 个保留报告逐项验证大小与摘要。两份 build JAR 位于该 Artifact 外，未据此声称独立复核 JAR 字节。M2.5 的 sidecar 摘要、性能/恢复子报告、replayDigest 和准确提交均匹配。

| Artifact | ID | ZIP SHA-256 | 到期时间 UTC |
|---|---:|---|---|
| 中文 M1 | 10140194863 | 9384d4b3b3119b202e1ce92c7746f4df180b5ec2c846ff20d2c8f90ac437b899 | 2026-10-10T06:56:16Z |
| 中文 M2.5 | 10140197766 | 27242a06bc16401f0b4aaa0ca3f4f5c159d06a785d26e4b6b4a33ab1b4cd03b3 | 2026-10-10T06:56:23Z |
| 英文 M1 | 10140273713 | 71f20ba3e83f7ad187456a3af931abadb1d46493c02c8c08469f79f6ff730d11 | 2026-10-10T06:59:02Z |
| 英文 M2.5 | 10140170119 | 25b0bcdb3b510135861ff111d5020d508f90fc51e38cacd2df1d5c0c8cb97c73 | 2026-10-10T06:55:25Z |

既有 JVM CDS、Mockito、Schema components、TLS PHA 及 Gradle 弃用警告保留，未全局屏蔽。原始分轮日志/XML、失败与最终 ZIP、元数据及检查摘要保存在本轮 SDD Evidence 目录；以上 GitHub Run 提供固定提交的远端证据。

## 已知限制

本轮不执行 Agent/ADB 或真实设备行为，不代表完整 M3/Release 或 Company 验收。性能证据仅用于既有共享 CI 硬上限，1000 ms 仍是参考目标，不能据此宣称 Company 性能达标。既有 canonical 摘要不覆盖非主路径全部字段，不能宣称任意字段篡改均可检测。历史 M2.5 Artifact 最早于 2026-10-07 到期，后续证据保留仍按现有 Evidence Archive 治理处理。仅使用现有 Backend/PostgreSQL/GitHub，不新增 Company 资源。

## 下一步执行计划

当前结果：Task 5 工程实现、独立复审、准确提交 CI 和 Artifact 核对完成。Git 状态：双语实施提交已推送；本记录以独立配对文档提交交付，其准确 CI 在提交后单独核对，不替换实施 Subject。

唯一下一步：Task 6 主机 Agent、ADB 与 LOG/SCREENSHOT Collector 实施。前置条件：沿用本轮最终实施 Subject、共用 Schema/golden JSON 及已接受 TDR-024/025，在明确 Task 6 实施指令下推进。验收目标：Agent 客户端/恢复 journal、真实受限子进程、同源 HTTPS、共享摘要和 Collector 测试及 build 通过，保留中断/损坏/重复上报证据；不把受控协议或进程测试标为真机通过。Task 7 串联和真实设备验证另按其边界执行。
