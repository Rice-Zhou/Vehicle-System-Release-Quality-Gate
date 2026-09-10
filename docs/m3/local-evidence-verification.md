# 本地 Evidence — Task 4 工程验证

## 范围与实施指令

2026-09-10，Owner 在明确的 Task 4 下一步后回复以下原文，按上下文解释为“执行下一步”。本轮仅实施本地 Evidence 上传、下载与恢复，沿用已接受的单设备演示设计。Task 5–7、设备操作及 Company 不在范围内；本记录不替代 Owner 验收，不设新组件验收门槛，不授权 merge、Tag、发布或部署。

```json
{"instruction":"\u5fd7\u5174\u4e0b\u4e00\u6b65"}
```

依据：[实施计划](../superpowers/plans/2026-09-09-single-device-smoke-implementation.md)、[设计](../superpowers/specs/2026-09-09-single-device-smoke-design.md)、[TDR-025](../v0.2/tdr/TDR-025-local-demo-evidence-payload.md)、[Task 3](run-lease-verification.md)。

## 实施 Subject 与行为

- 中文 Subject：[ca37cee7fc1626823617f565fd45224cbb73bf32](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/ca37cee7fc1626823617f565fd45224cbb73bf32)。
- 英文 Subject：[97ee9c2be9d9ec4a09f5df9f2b9622e856b8808a](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/97ee9c2be9d9ec4a09f5df9f2b9622e856b8808a)。
- 两个 Subject 包含首轮实现、I1/I2 修复、超大整数检查及 CI 事务/迁移修复；后续文档提交不替换实施 Subject。已通过 Pair Gate、成对推送并核对远端；独立整体复审及最后限定复审为 APPROVE_FINAL，无未解决可操作发现，准确提交 CI 与 Artifact 已核对通过。
- V14 以 Upload Session 保存不可变的 Agent/Device/Run/Attempt/lease/fencing 绑定和 Evidence Metadata，通过既有 AgentAccess/AttemptAccess 获取当前权限；沿用严格 Create/Complete 请求，不新增第二套身份或权限来源。
- Payload 保存于默认关闭的本地演示 Profile，受控根目录位于 Git 与公开静态资源之外，只接受服务器生成的 ID。LOG 限 1 MiB、严格 UTF-8/text/plain；SCREENSHOT 限 8 MiB、image/png 签名。目录与文件要求服务账号权限，拒绝路径越界、链接和非普通文件，64 KiB 分块写入独占候选。
- PUT 采用短事务预检查 → 无业务事务的 Servlet 非阻塞接收 → 短事务重新检查当前权限、租约及原 binding。总接收期限默认/上限 30 秒，允许配置缩短；超时为 408 UPLOAD_TIMEOUT。声明的 size/SHA-256 匹配后才通过同目录 hard-link create-only 发布，不支持时明确失败。EOF/timeout/error 竞争只终结一次，错误候选不占用固定文件或删除已有正确内容。这项 HOW 决定见 TDR-025。
- Complete 在 Attempt/Session 锁内再次校验实际 bytes、类型与摘要，提交 AVAILABLE、Audit/Outbox；文件已写而 DB 回滚时保留正确孤儿，允许同 Session 复验重试，不声称跨文件系统/数据库原子事务。
- 下载申请使用现有 Reason.reason 记录 purpose，60 秒 grant 绑定 actor/project/evidence/purpose。每次 GET 重新检查当前身份、权限、敏感度、expiry、retention/legal hold；Audit 成功后才输出，使用同源受鉴权相对 URI、no-store、安全文件名，拒绝 Range，不返回无鉴权跳转。
- 对账、备份清单与恢复按固定 Evidence ID 集合执行，每批 1–1000 项。实际 Metadata 与文件清单/摘要成对核对；缺失或损坏追加 INTEGRITY_ERROR observation，不改写历史 Result/Metadata，不删除未知文件。AttemptEvidence.resolve/seal 供 Task 5 使用；本轮不实施 Event/Result 上报。

## 已执行检查

基线中文 8b2bfc0e37a9f1f852d2f3c587f257df14f66eb4、英文 e9d8c98ca2e19b25b8e17c47819bf8fb53ee1c0f；两语工作区干净、远端一致。四条基线 M1/M2 CI 均 SUCCESS；契约 schemas=5、positive=13、negative=6、operations=36 及验收记录校验 PASS。这些仅证明 Task 4 开始前状态。

### 首轮实施与复审

首个候选提交为中文 8f12336、英文 a084bd0，尚不能作为最终交付 Subject。本地使用既有 JDK 21.0.7 与 Gradle Wrapper；不同轮次有重叠，不相加为独立覆盖总数。

| 检查 | 实际结果 | 边界 |
|---|---|---|
| 初始 RED | 编译失败 | 实现不存在，ControlledPayloadStore/EvidenceUploadService 未解析；不是业务断言 RED，也不是 Docker 初始化失败。 |
| 广泛非 PostgreSQL 回归 | 760 tests、3 failures、0 errors、10 skipped | 默认关闭时的 JDBC 依赖 2 项、测试动态配置权威 1 项失败，后续定向修复；跳过含既有条件/平台场景。 |
| 兼容性定向修复 | 33 tests、0 failures/errors、1 skipped | 默认上下文、连接池预算、架构、API、真实 HTTPS 和文件存储；保留原共享断言。 |
| 最终文件/HTTPS 首轮 | 11 tests、0 failures/errors、1 skipped | 真实 HTTPS/mTLS/Servlet/文件传输；数据库和身份接口为明确测试替身。Linux 专属链接用例在 Windows 跳过，Windows ACL 正反例实际执行。 |
| 本机 PostgreSQL | 15 项初始化失败 | 无可用 Docker，未执行事务、FK、并发或恢复业务断言；当时转由准确提交 CI 验证。 |
| 独立任务复审 | 需修复 | 首次短流在验证前占用固定文件；缺少总接收期限且网络读取持有业务锁。两项均进入集中修复，不以局部 GREEN 覆盖发现。 |

早期 HTTPS 失败分别来自测试 health 配置、临时目录继承权限、测试 JWT issuer 格式；修复保持生产 ACL 和鉴权不变。测试输出中的 Mockito/JVM、Schema 与 TLS 提示保留，未使用全局抑制掩盖。首次报告误称已经实现总接收预算，经复审核实并更正；最终实现以修复提交和回归证据为准。

### 集中修复与定向复审

| 检查 | 实际结果 | 边界 |
|---|---|---|
| Fix1 RED | 5 tests、2 failures | I1 固定文件占位为真实断言 RED；I2 早期 fixture 未正确构造持续等待正文，不能把该轮解释为完整网络总期限 RED。 |
| Fix1 最终文件/HTTPS | 17 tests、0 failures/errors、1 skipped | Store 10 项、HTTPS 7 项；Linux 专属链接/POSIX 项在 Windows 跳过。实际 TLS 请求仅发送部分正文并保持未 EOF，验证总期限 408、候选清理与正确重传。 |
| Fix1 兼容性 | 22/22 PASS | 默认上下文、架构、连接池预算、API；其他 fixture 漏设 requestId 的失败在最终 17 项中修复，未改生产要求。 |
| I1/I2 定向复审 | ADDRESSED；Approved | 完整核对固定修复 diff 和分轮 XML；声明校验前移，网络阶段不持业务锁，EOF 重查当前权限。 |
| PostgreSQL 最终集合 | 该轮 18 项已编译，尚无 CI 结果 | Upload 5、Recovery 9、Download 3、Sensitive 1；包含 pending receiver 期间 Cancel/Worker 两秒内完成并拒绝 late EOF。 |
| 契约与双语实施 | PASS | schemas=5、positive=13、negative=6、operations=36；400 个非 Markdown blob/mode 一致；Pair Gate 含 EnglishOnly、结构和链接检查通过。 |

最后一轮 compileKotlin、compileTestKotlin、bootJar 实际成功。文件 abort/EOF 及 Controller 三回调竞争用明确的 MockAsyncContext 加真实服务/存储验证，不冒称真实网络调度竞争。HTTPS 的数据库/身份端口仍为测试替身，真实事务/锁/恢复以 PostgreSQL CI 为准。总期限约束网络接收；本地磁盘操作和短事务提交仍依赖操作系统及数据库正常服务。

### 中间提交 CI 失败与处理

中文 030ed39 / 英文 3f3ac5c 的四条 CI 均 FAILED。两语 M1 均为 1076 tests、3 failures、0 errors、3 skipped：[中文 M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34434153793)、[英文 M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34434153741)。实际失败是缺失文件的 checked IOException 未使 Complete 回滚、V12 到最新迁移数量仍期望 1、恢复后最新版本仍期望 V13。对应 M2 的 replay 子命令也失败：[中文 M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34434153842)、[英文 M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34434153743)。它们是中间失败证据，不能作为最终交付通过记录。

英文 M1 Artifact 10135679935 已下载并核对大小 265487 bytes、SHA-256 00b248fe57ac47639fba4e43f25ba576aa5def66af4552ea6804680cf29c21fd；122 个保留报告的清单摘要一致。Task 4 PostgreSQL 当时为 17/18 PASS，Linux 链接测试已实际执行；局部通过不覆盖上述失败。三个 skip 均为 Linux 不适用的 Windows ACL 场景。

整体复审另发现超大 sizeBytes 整数可能被 asLong 截断，导致无效声明创建 Session。实际文件仍有界且 V14 阻止不一致 AVAILABLE；该 Minor 与 CI 失败随后一并修复，最终限定复审确认全部 ADDRESSED。既有 Schema components、TLS 1.3 optional certificate/PHA 与 Mockito/JVM 告警明确保留；已测初始 mTLS 握手不代表 PHA 能力。

最终修复分为两笔逻辑提交：a702724 / dfbdcc7 在唯一声明边界检查无损 Long 转换，实际 HTTPS RED 1/1 → GREEN 8/8；ca37cee / 97ee9c2 为 Complete 局部配置 IOException 回滚，保留 EvidenceRejected 的持久 REJECTED 语义，并精确更新两处 V14 预期。真实 Spring 注解事务代理 RED 2 tests/1 failure → 最终定向 GREEN 10/10；使用实际缺失/损坏文件，JDBC Connection 是明确可观察替身。PG 原失败流程另加入缺失 Complete 后正确 PUT/Complete 恢复至 AVAILABLE，18 场景及迁移测试编译通过；实际 PG 通过证据见下节最终 CI。

## 准确提交 CI

已逐项核对 head SHA 与上述最终实施 Subject 一致，以下四条运行均为 SUCCESS。两语 M1 完整 XML 各为 1079 tests、0 failures/errors、3 skipped。三个跳过均为 Linux 不适用的 Windows ACL 场景；本地 Windows 正反例已执行。Task 4 PostgreSQL 各为 18/18 PASS、无跳过：Upload 5、Recovery 9、Download 3、Sensitive 1。Linux 链接/POSIX 用例实际执行通过；最终 HTTPS 为 10/10。M1 证据状态仍为 CANDIDATE，8 个候选门禁 exitCode=0，不改写为 Owner 验收。

| 分支 | M1 | M2 |
|---|---|---|
| 中文 | [34435145567](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34435145567) | [34435145584](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34435145584) |
| 英文 | [34435145725](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34435145725) | [34435145721](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34435145721) |

两语 M2 均为 12/12 PASS，覆盖 20 Issues / 2000 Edges；exactCommit、摘要 sidecar、性能/恢复文件内容和历史重放摘要已核对。backupRestore、dbRestartReclaim、deadLetter、manualRetry 均 PASS。V12→V14 迁移与当前 V14 恢复断言通过。

以下四个 ZIP 已下载并逐项匹配 GitHub Artifact 元数据中的大小与 SHA-256。两语 M1 各校验 ZIP 内 123 个报告的清单摘要；清单另列的两个构建 JAR 不在此 ZIP 中，未声称从该 Artifact 重新校验其 bytes。原始 XML/M2 JSON 保留于 ZIP，验证摘要在本记录版本化。

| Artifact | bytes | SHA-256 | expiresAt UTC |
|---|---:|---|---|
| 中文 M1 10136116837 | 260211 | c2e137bf87b3b866d8e58b86ed919d564385e6dba331b144d1f5bb8764a7526a | 2026-10-10T04:05:53Z |
| 英文 M1 10136128632 | 260174 | 69155c818d8f790853f2732f72c22083435535bdbbcf873fa8a2bb8631729465 | 2026-10-10T04:06:25Z |
| 中文 M2 10136032718 | 1752 | 9f8dade90bf3cb819ab2767b78abd6eaeda1ba44101b5e35d95d1322e065a2a1 | 2026-10-10T04:02:03Z |
| 英文 M2 10136034722 | 1754 | 516f542bc57d788c4f82bded94dff3140f37c7c43d0f230e76dfe9631b9fcca6 | 2026-10-10T04:02:09Z |

## 已知限制

本轮不执行 Agent Event/Result 上报或设备操作，不代表完整 M3/Release 或 Company 验收。默认演示关闭；不启用真实 Provider，不要求 Company 归档资源。普通目录权限与内容摘要不声称管理员不可修改或 WORM。

已知 Schema、TLS optional certificate/PHA、Mockito/JVM 告警按上述归属保留。网络总期限不代表本地磁盘或数据库具备实时截止保证。既有 canonical 摘要不覆盖非主路径全部字段，不能声称任意字段篡改均可检测。M2 固定 migrationVersion=V11 是历史格式标记，当前 V14 由实际迁移与恢复测试证明。

本轮 M2 创建 Run 的 P95 为中文 1017 ms、英文 1305 ms，均未达到 1000 ms 参考目标，仅通过共享 CI 硬上限，不代表 Company 性能达标。

本记录在现有 GitHub 中版本化保存验证摘要，原始 Actions Artifact 仍有上述期限；不声称永久保留，沿用现有归档治理，不新增云资源。历史 M2.5 Artifact 最早 2026-10-07 到期的限制不因本轮回归关闭。

## 下一步执行计划

当前结果：Task 4 本地 Evidence 实现、独立复审、准确提交 CI 与 Artifact 核对完成，未代做 Owner 验收。Git 状态：上述实施 Subject 已推送并核对远端，记录提交独立。下一步动作：执行 Task 5，实现 Event、Result 与 Run 完成契约。前置条件：Task 5 实施指令；沿用已接受设计，无需 Company 资源。验收目标：实际 Result 摘要与幂等冲突、required Evidence 归属和完整性、终态/取消并发、Run 完成与结果查询通过真实 PostgreSQL/契约测试，双语准确提交及 CI 可核对。
