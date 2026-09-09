# Run 与租约 — Task 3 工程验证

## 范围与实施指令

2026-09-09，Owner 在明确的 Task 3 下一步后回复下列原文，按上下文解释为“执行下一步”。本轮仅实施 Run、Attempt、调度与租约；Task 4–7、设备操作和 Company 不在范围内。本记录属于工程验证，不代替 Owner 验收，不设新的组件验收门槛，不授权 merge、Tag、发布或部署。

```json
{"instruction":"\u5fd7\u5174\u4e0b\u4e00\u6b65"}
```

依据：[实施计划](../superpowers/plans/2026-09-09-single-device-smoke-implementation.md)、[设计](../superpowers/specs/2026-09-09-single-device-smoke-design.md)、[TDR-024](../v0.2/tdr/TDR-024-single-device-smoke-execution.md)、[Task 2](agent-identity-registration-verification.md)。

## 实施 Subject 与行为

- 中文 Subject：[61f5dddc2c78dc5ee639e36be3ca246b598894e9](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/61f5dddc2c78dc5ee639e36be3ca246b598894e9)。
- 英文 Subject：[820cf3522ef1f9ea093afe7974ee4c97a620a76c](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/820cf3522ef1f9ea093afe7974ee4c97a620a76c)。
- Subject 包含初始实现、两轮行为修复及独立 CI 断言修复；后续文档提交不替换实施 Subject。上述 Subject 已成对推送并核对远端。
- V13 保存不可变 Plan/Case Version、Environment、Run、Attempt、Command/Event、Result 及终态历史。Create Run 读取真实 Locked Manifest 与已验证摘要，限制一个固定演示 APK 与 CONFIG，要求预先发布的精确 Plan；不自动发布定义或预填成功 Result。
- 保持严格 Create API，由显式服务端演示配置绑定已注册 Agent/Device 及有界 CONFIG 原始 bytes；其 SHA-256 必须匹配 Locked CONFIG。该 HOW 选择记录于 TDR-024；配置、接口和期限见 [Run API](run-lease-api.md)。
- Attempt 使用唯一标准 UUID；Context 按既有 Schema/JCS 固化并仅供已分配 Agent 读取。行锁及设备活动 Run 部分唯一索引保证独占，最多领取一个 Command，重复 poll/ACK 保持相同 Command/Attempt/lease/fencing；等待期间不持有事务。
- Heartbeat、Worker 与 AttemptAccess 复用当前执行资格判定。Heartbeat/Worker 在资格失效时原子 ERROR、fence、记录唯一 Server Result/Audit/Outbox 并释放设备。AttemptAccess 在调用者事务内拒绝写入；未来调用者必须比较请求 leaseId/fencingToken 并持锁到写入结束。
- DRAINING 可续当前已 ACK 的有效租约，但不领取新任务。恢复到未 ACK 的 DISPATCHED 不提前续租。Worker 每页最多检查 100 条活动 Run，并遍历全部页；取消及期限关闭保留不可变历史，未开始的 startedAt/duration 保持 null，不自动重放安装。

## 已执行检查

本地沿用 JDK 21.0.7+6 与既有 Backend 工具链。各轮测试集合有重叠，不相加为独立覆盖总数。

| 检查 | 结果 | 证据与边界 |
|---|---|---|
| TDD RED | 已观察 | 租约/UUID 目标缺失、重启时恢复窗已过、身份拒绝导致 rollback-only、DRAINING/尾随 JSON、资格失效续租、未 ACK 恢复、第 101 条活动 Run 遗漏均有失败证据。首次纯测试 XML 被后续运行覆盖，仅保留当时工具输出；后续 RED/GREEN XML 分开保存。Docker 初始化失败不算行为 RED。 |
| 初始本地回归 | 31/31 PASS | 上下文、架构、API、安全链、租约、CONFIG 与期限；0 failure/error/skipped，包含编译及 bootJar。 |
| 第一轮修复回归 | 31/31 PASS | 真实 Spring 事务代理、期限、安全链、CONFIG、注册、权限；0 failure/error/skipped。事务代理测试的 JDBC Connection 为替身，不能代替 PostgreSQL。 |
| 最终行为修复回归 | 23/23 PASS | 期限 10、Worker 分页 1、事务代理 3、执行安全 5、注册 4；0 failure/error/skipped。 |
| CI 断言修复 | PASS | 测试编译通过；依赖级探针确认 JSONB 重放的 LongNode/IntNode 表示差异，完整 JCS bytes 比较仍拒绝变化的 fencingToken；严格表白名单只增加 V13 的 11 张表。 |
| PostgreSQL 本地 | 环境阻断 | 本机无 Docker，最后 AgentLease 的 14 项初始化失败，未记录为通过；最终 22 个 Task 3 场景由准确提交 CI 验证。 |
| 独立任务与最终工程复审 | PASS | rollback-only、DRAINING、尾随 JSON、真实并发、当前资格失效及未 ACK 恢复均已修复并定向复审；CI 断言修复另行复核，无未解决可操作发现。 |
| 契约、验收记录与双语 | PASS | schemas=5、positive=13、negative=6、operations=36；383 个非 Markdown blob/mode 一致；准确实施 Subject 的 Pair Gate（含 EnglishOnly、结构及链接）通过。 |

## 准确提交 CI

已逐项核对 head SHA 与上述实施 Subject 一致，以下四条运行均为 SUCCESS。两语 M1 完整测试 XML 各为 1041 tests、0 failure/error、2 skipped；跳过的是 Linux 上不适用的既有 Windows ACL 测试。Task 3 PostgreSQL 场景各为 22/22 PASS、无跳过：TestRun 7、AgentLease 14、Migration 1。M1 证据状态保留 CANDIDATE，全部 8 个候选门禁 exitCode=0，不改写为 Owner 验收。

| 分支 | M1 | M2 |
|---|---|---|
| 中文 | [34354214202](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34354214202) | [34354214157](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34354214157) |
| 英文 | [34354214263](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34354214263) | [34354214276](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34354214276) |

两语 M2 均为 12/12 PASS，覆盖 20 Issues / 2000 Edges；exactCommit、摘要 sidecar、性能/恢复文件内容和历史重放摘要已核对。backupRestore、dbRestartReclaim、deadLetter、manualRetry 均 PASS。

以下 ZIP 已下载，大小及 SHA-256 与 GitHub Artifact 元数据匹配。两语 M1 各校验了 ZIP 内 117 个报告的清单摘要；清单另外列出的两个构建 JAR 不在此 ZIP 内，未声称从该 Artifact 重新校验其 bytes。原始 XML 和 M2 JSON 保留于 ZIP，验证摘要在本记录版本化。

| Artifact | bytes | SHA-256 | expiresAt UTC |
|---|---:|---|---|
| 中文 M1 10105462983 | 245353 | dcc8079eb2719ef45c203461a44cd98a5abb02d8959702ff8ccedabe09a1b341 | 2026-10-09T13:09:53Z |
| 英文 M1 10105441887 | 245237 | de660802de2096ad500e1aa885982866b2fc6e5ae16abc312c58b9cb85e2998d | 2026-10-09T13:09:24Z |
| 中文 M2 10105315742 | 1758 | 32bf97a97c8ac93b0261d4fe592152279187c4d701d7432e9abc15ef183de887 | 2026-10-09T13:06:25Z |
| 英文 M2 10105338636 | 1746 | 3b69a779b2f9c157654ca27924cb7ead28a7e770f584873dd535e51226e5bcde | 2026-10-09T13:06:59Z |

中间提交 9eb9f41 / 5bcceef 的 M1 各有 1031 tests、2 failures、0 errors、2 skipped（[中文](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34352222288)、[英文](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34352222251)），失败为 V13 表集合与 ACK 重放表示断言；当时 M2 两语成功。修复没有改变生产响应或放宽字段/值约束；中间局部通过不代替本轮最终证据。

## 已知限制

本轮不执行设备安装/UI、Evidence Payload 保存、Agent Event/Result 上报或完整 Release PASS，不代表完整 M3 或 Company 验收。当前 Server Result 对缺少的 required Evidence 明确记录 FAILED；后续任务必须关联实际证据。默认演示关闭，不启用真实 Provider。

Mockito self-attach、dynamic-agent 与 OpenJDK CDS 提示原样保留，没有增加抑制配置。既有 canonical 摘要不覆盖非主路径全部字段，不能声称任意字段篡改均可检测。M2 固定 migrationVersion=V11 是既有证据格式，当前 V13 须由 M1 迁移及恢复测试证明。

本轮 M2 创建 Run 的 P95 为中文 1460 ms、英文 1430 ms，均未达到 1000 ms 参考目标，仅通过共享 CI 硬上限，不代表 Company 性能达标。

本记录在 GitHub 版本化保存验证摘要，原始 Actions Artifact 仍有期限，不声称永久保留或管理员不可修改；沿用现有归档治理，不新增云资源。历史 M2.5 Artifact 最早 2026-10-07 到期的限制不因本轮回归关闭。

## 下一步执行计划

当前结果：Task 3 工程实现、独立复审及双语准确提交 CI 完成，未代做 Owner 验收。Git 状态：上述实施 Subject 已推送并核对远端，记录提交独立。下一步动作：执行 Task 4，实现本地 Evidence 上传、下载与恢复。前置条件：Task 4 实施指令；沿用已接受设计，无需 Company 资源。验收目标：实际 bytes 保存与摘要校验、跨 Agent/Run 授权隔离、上传重试/取消竞态、下载与数据库/文件故障恢复通过真实测试，双语提交与 CI 可核对。
