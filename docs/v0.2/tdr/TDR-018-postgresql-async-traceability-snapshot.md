# TDR-018 — PostgreSQL 异步 Traceability Verification 与不可变 Snapshot

- 状态：Accepted
- 日期：2026-09-04
- 决策依据：Project Owner 于 2026-09-04 通过 `APPROVE M2-KD-2026-09-04-01 WRITTEN SPEC REVIEW` 批准书面规范
- 范围：M2.5 固定输入、异步 Verification Run、Fixed/Included/Verified、Gap、不可变 Snapshot、查询与恢复
- 相关决定：[TDR-001](TDR-001-modular-monolith.md)、[TDR-003](TDR-003-postgresql.md)、[TDR-005](TDR-005-rest-openapi.md)、[TDR-007](TDR-007-postgresql-job-outbox.md)、[TDR-016](TDR-016-materialized-release-issue-snapshot.md)、[TDR-017](TDR-017-build-provenance-envelope.md)

## 1. 为什么选择该技术

选择现有模块化单体中的 PostgreSQL-backed asynchronous Worker、固定输入账本和不可变物化 Snapshot。Release/Manifest/Issue/Commit/Build/Artifact 具有强结构化关系，需要事务、FK、历史查询、并发唯一性和一致重放；当前 Pilot 最多 20 个 Issue、默认 2,000 条 Edge，现有 PostgreSQL Job/Outbox 足以承担异步执行。

该方案复用已经接受的 PostgreSQL、REST/OpenAPI、Job/Outbox 和单体部署，不增加 Broker、图数据库或服务。它在六个月业余实施范围内提供公司级审计和恢复边界。

## 2. 解决什么问题

动态读取最新 Edge 无法回答历史 Release 当时基于哪些事实得出结论；同步 HTTP 验证又会扩大超时、重试和并发锁风险。本决定在请求时固定 Locked Manifest、M2.3 Issue Snapshot、M2.4 Edge Revision、policy 和 validator，并异步计算 Fixed、Included、Verified 与精确 Gap。

结果以一个原子、不可变 Snapshot 保存，使 API、Quality Engine、审计和备份恢复读取同一权威结果。M2.5 没有真实测试 Evidence，因此 Verified 固定为 false，不伪造 Release verification。

## 3. 为什么没有选择其他方案

- 同步请求事务：实现表面简单，但与已批准 `202` 契约冲突，并放大超时和客户端重复请求风险。
- 查询时动态计算：最新 Revision 会改变历史结果，无法确定重放。
- Kafka/RabbitMQ：当前没有吞吐或隔离需求，增加发布确认、监控和恢复状态。
- 图数据库：当前关系类型固定、规模可控，PostgreSQL 可达性查询与物化结果已满足需求。
- 独立 Traceability service：增加部署、网络、认证和跨服务事务，而无真实边界收益。

## 4. 对 V0.2 的影响

增加一个 forward-only Migration，扩展 Verification Run，新增 immutable Edge Input Ledger、Snapshot Issue Result 与主路径 Edge 关联，并扩展 Gap 前驱引用；复用现有 Snapshot/Edge/Gap、Background Job、Idempotency、Audit 和 Outbox。增加 Traceability Application/Domain/Adapter 实现和一个只读 Verification Run status Endpoint。

M2.5 只在 PostgreSQL 内执行，不调用 Jira、GitHub、CI、Device 或 Agent。`ARTIFACT_RELEASE` 继续只由 Locked Manifest view 派生。单次验证最多 20 个 Issue和默认 2,000 条 Edge，超限失败关闭。

## 5. 对未来 V0.3 的影响

V0.3 可以用新 schema、policy 和 validator version 增加 Test Run、Test Result 与 Evidence snapshot facts，并生成真实 Verified 结论；旧 M2.5 Snapshot 保持 Verified=false。若测量证明 Worker 或 PostgreSQL Job 到达容量边界，可以在不改变 API、固定输入、Snapshot digest 和数据库 authority 的前提下抽离 Worker或引入 Broker。

图查询只有在真实关系类型、深度或规模证明 PostgreSQL 不足时重新评估；不得为未来可能性提前增加第二权威数据源。

## 6. 如何迁移

Flyway 使用 expand-only Migration 添加 nullable/有默认策略的列、Input/Issue Result/Path Edge 表、Gap 前驱字段、FK、索引、creation transaction 与 immutable trigger。先运行数据 precondition 和 Migration Constraint Test，再部署兼容应用。旧应用可忽略新增结构；失败时回滚镜像并通过新 forward migration 修正数据库，不执行 down migration或删除历史。

API 保留既有 Path、Method、Permission 与 Idempotency，使用 typed response 和可选查询参数做向后兼容扩展。发现真实 consumer 与新增 schema 不兼容时停止并重新评审。

## 7. 如何测试

Domain 测试覆盖完整链、每段缺口、多路径和三个状态分离；Replay 测试证明新 Revision 不改变旧 Snapshot。PostgreSQL 测试覆盖固定输入、不可变性、跨 Project、版本分配、digest 复用和并发。Transaction 测试在每个持久化边界注入失败，证明没有半成品。

Contract/Security 测试覆盖 `202`、status polling、snapshot query、权限、404 防枚举、Problem Details 和敏感信息扫描。Recovery 测试覆盖 Worker crash、DB restart、dead-letter、backup/restore 和 digest 校验。性能报告覆盖 20 Issue/2,000 Edge，无 N+1 和组合路径爆炸。

## 8. 如何部署

随现有 Backend 镜像和 PostgreSQL 部署，不新增服务、Broker、Redis、图数据库、对象存储或公网 Endpoint。顺序为备份、Migration、完整 Gate、Pilot known-chain/gap Smoke、replay 校验和显式启用。Company、真实 Jira/CI 和 M3 不作为 M2.5 部署前提。

配置外置且不含 credential。Worker 使用现有 Background Job 配置、有界重试和 Dead Letter；liveness 不依赖外部 Jira/GitHub/CI。

## 9. 失败时如何恢复

创建事务失败不产生 Run/Job，结果事务失败不产生 Snapshot。Worker crash 后使用固定 Input Ledger 重试；相同输入和 digest 收敛到同一 Snapshot。poison job 达到次数后进入 Dead Letter，Run 明确 FAILED，修复后创建新 Run，不覆盖终态。

应用可回滚上一镜像并保留扩展表。数据库恢复后校验 Flyway 版本、Run/Input/Snapshot 外键、Audit/Outbox 和 canonical digest；任何不一致保持 fail-closed 并 roll-forward，不从 JSON、文件、缓存或外部系统重建权威历史。

## 重新评估条件

当单 Release 稳定超过 20 Issue 或 2,000 Edge、参考验证持续超过 10 秒、Job backlog/锁竞争达到测量阈值、需要真实 Test/Evidence Verified、需要跨服务隔离，或 PostgreSQL 图查询被真实数据证明不足时重新评估。若提案改变 Fixed/Included/Verified、Manifest authority、Snapshot 不可变性或引入第二权威来源，必须转为 ADR Proposal。
