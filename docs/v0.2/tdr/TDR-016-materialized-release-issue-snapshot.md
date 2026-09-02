# TDR-016 — 物化 Release Issue Snapshot 与 Sync Observation Ledger

- 状态：Accepted
- 日期：2026-09-02
- 决策依据：Project Owner 已于 2026-09-02 批准 `M2-KD-2026-09-02-01` Written Spec Review
- 范围：M2.3 Release Issue Snapshot 的精确 Sync membership、事务物化、canonical digest 与历史重放
- 相关决定：[TDR-003](TDR-003-postgresql.md)、[TDR-005](TDR-005-rest-openapi.md)、[TDR-007](TDR-007-postgresql-job-outbox.md)、[TDR-009](TDR-009-oidc-and-service-identities.md)、[TDR-015](TDR-015-versioned-jira-mapping-and-adapter-authority.md)

## 1. 为什么选择该技术

V0.2 使用 PostgreSQL `issue_sync_run_item` Observation Ledger 记录每次 Sync 实际观察到的精确 `normalized_issue` Revision，并在单事务中物化不可变 Release Issue Snapshot。Snapshot 采用 RFC 8785 canonical JSON 与 SHA-256 content digest。

Release、Manifest、Issue、Sync、Snapshot 与后续 Traceability 存在强结构化关系，需要事务、历史查询、Project 隔离和数据一致性；MVP 数据规模可控。沿用 PostgreSQL 比引入 Blob-only store、事件流或第二数据库更直接，也符合六个月业余时间可落地约束。

## 2. 解决什么问题

现有 `normalized_issue` 没有 Sync membership，按时间或“当前最新 Revision”无法证明某项事实属于指定成功 Sync。Observation Ledger 让每个页面事务保存可验证 membership；物化 Snapshot 则让外部 Jira、新 Mapping、新 Revision 或新 Sync 无法改变旧 Release Gate 输入。

本决定只规定 Issue Adapter 和 Snapshot 的实施方式，不修改 V0.1 Issue、Release、Manifest、Evidence、Traceability、Quality Result 或 Fixed/Included/Verified 语义。

## 3. 为什么没有选择其他方案

- 按 `observed_at/completed_at` 查询历史最新 Revision：时间边界和并发运行存在歧义，无法证明 membership。
- Snapshot 只保存 Revision ID：历史 API bytes 仍依赖被引用行和查询实现，缺少自包含的重放输入。
- 只保存 canonical JSON Blob：弱化 FK、Project Scope、Issue 查询和约束，形成关系事实的第二数据模型。
- 在创建 Snapshot 时重新查询 Jira：外部状态会漂移，且把 Jira 放入 Release Gate 权威路径。
- Kafka/Event Sourcing/CDC：当前单项目、最多 20 条 Pilot 不存在迫使引入其运维成本的需求。

## 4. 对 V0.2 的影响

Forward-only Migration 新增 `issue_sync_run_item` 及复合 FK/唯一约束/immutable Trigger，为 Sync Run 增加 `FULL/DELTA` result mode 与版本化 filter reference，并补充 Snapshot Header 的 replay metadata。历史 M2.2 Run 不猜测回填，明确不可用于 M2.3。

`persistPage` 在原有页事务中同时写 Normalized Revision 与 Observation。`POST /api/v1/releases/{releaseId}/issue-snapshots` 保留已批准的 `IdentifierInput.sourceId`，在 Release/Source 锁内解析最新合格 `SUCCEEDED/FULL` Run，并把解析结果固定进 Idempotency Record。

Snapshot 物化非 tombstone Items，保存 observed/tombstone/selected counts，按稳定 identity 排序并计算 digest。Header、Items、Audit、Outbox 与 Idempotency response 在一个事务中提交；任一失败整体回滚。M2.3 不判断 Fixed、Included 或 Verified。

## 5. 对未来 V0.3 的影响

V0.3 可为每个 Source 继续生成独立 Snapshot，并通过单独评审的 Release Input Snapshot 组合多个来源。增量 Adapter 必须先形成可验证 full-state materialization，再取得 `FULL` 资格；不能把 DELTA 当完整 Release 输入。

未来可以替换 JDBC 实现或增加归档层，但必须保留精确 Sync membership、不可变 materialization、Project Scope、canonicalization/version、digest 重放和 fail-closed 语义。

## 6. 如何迁移

使用 Expand-only Migration 创建 Observation 表、索引、约束和 Trigger，并增加 nullable-to-validated 的 Sync/Snapshot metadata。新应用只为新 Run 写 Observation；经过约束验证后再启用 Snapshot Endpoint。旧 Run 保持历史可见但返回 `ELIGIBLE_SYNC_NOT_FOUND`，不得根据 timestamp 猜测回填。

API Path、Method、Permission、Idempotency 和 `IdentifierInput` 不变。Pilot 默认使用 `all-relevant-issues/v1`、`release-issue-snapshot-jcs/v1` 与 `issue-snapshot-age/v1`；版本变化必须创建新 TDR revision 或新 TDR。

## 7. 如何测试

Unit Test 覆盖 RFC 8785、SHA-256、UTF-8、UTC 时间、排序、空集合、tombstone 和三次 digest 重放。PostgreSQL Test 覆盖 membership FK/唯一性、页原子性、终态封存、Snapshot child 同事务、不可变拒绝和跨 Project 拒绝。

Application Test 覆盖 Locked Manifest、最新合格 Run、FULL/DELTA、age boundary、幂等与并发。故障注入证明 Audit、Outbox、Item 或 digest 失败整体回滚。Replay Test 在后续 Mapping/Revision/Sync 改变后逐字节比较旧 Snapshot；Security Test 确认日志、Git 和 CI Evidence 不泄露 Issue 内容、JQL、URL、路径或 Credential。

## 8. 如何部署

不新增服务、Broker、数据库、容器、对象存储或 UI。Migration 随现有 Backend/PostgreSQL 部署；M2.3 写入口默认只在 Pilot Profile 启用。部署顺序为 Migration、兼容应用、Gate 验证和 Endpoint 启用。

Company Profile 启用前必须显式配置 age policy、权限、保留和恢复责任，并形成独立 Company Evidence。本 TDR 不授权真实 Jira 查询、Company 写入或生产部署。

## 9. 失败时如何恢复

页面或 Snapshot 事务失败时 PostgreSQL 整体回滚；已有成功 Run 和历史 Snapshot 不变。出现 canonicalization、count、fact digest 或 content digest 不一致时关闭新写入口、保持 fail-closed 并优先 roll-forward，不使用当前最新 Revision 或 Blob fallback。

数据库恢复沿用既有 backup/restore，恢复后重建 canonical bytes，对账 digest、FK、counts、Audit 与 Outbox。应用回滚不能解释新版本时只禁止新 Snapshot，不删除 Observation 或改写历史 Migration。

## 重新评估条件

当一个 Release 必须原子组合多个 Issue Source、增量 Source 需要 full-state materialization、单 Snapshot 数量超过当前 PostgreSQL 事务/读取边界、Company 要求独立归档或保留删除语义、RFC 8785/摘要算法变化，或现有 API 必须允许调用方选择任意历史 Sync 时重新评估。重新评估不得静默改变 V0.1 冻结架构或历史 Snapshot。
