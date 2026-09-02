# M2.3 Release Issue Snapshot 设计

- Spec ID：`M2-KD-2026-09-02-01`
- Owner Design Direction：`APPROVED 2026-09-02`（方案 A）
- Written Spec Review：`PENDING`
- Architecture Baseline：V0.1 `0.1.0`（FROZEN）与 V0.2 `0.2.0`
- Parent Governance：中文 `5b6d04e87456a9d28e5dedd9ec776d7d644365bb` / 英文 `f2c81f7083cec0c35b3dfc43db23103bf8803d54`
- 范围：只定义 M2.3 Release Issue Snapshot 的实施架构；不授权实现

## 1. 目的与证据缺口

M2.2 已能把外部 Issue 经版本化 Adapter 与 Mapping Profile 写成 append-only `normalized_issue` Revision，但当前数据模型不能证明“某个 Revision 由哪一次 Sync 实际观察到”。如果 M2.3 仅按 `observed_at` 或“当前最新 Revision”创建 Snapshot，并发 Sync、重复 Revision 或后续 Mapping 变化会使历史选择不可证明。

本设计采用已获 Owner 批准的方案 A：PostgreSQL 事务化物化 Snapshot，并增加最小的 Sync Observation Ledger。Snapshot 固定某次成功、完整结果集 Sync 所观察到的精确 Revision，物化 Release Gate 所需字段并计算稳定 digest。外部 Jira、Mapping Profile、新 Sync 或源数据后续变化不得改变既有 Snapshot 的 bytes、ordinal 或 digest。

本设计不改变 V0.1 Issue、Release、Manifest、Evidence、Traceability、Quality Result 或 Fixed/Included/Verified 语义。`issue_sync_run_item` 是 Source Adapter 的实施账本，不是新的 Core Entity。

## 2. 不可协商边界

- Snapshot 只属于一个 Project、一个 Release、一个 Issue Source 和一个 `SUCCEEDED` 且 `FULL` 的 Sync Run。
- 只有具有 Locked Manifest 的 Release 可以创建作为 Gate 输入的 Issue Snapshot；Artifact→Release Authority 仍只来自 Locked Manifest。
- Snapshot 创建期间不得调用 Jira、CLI、内部 Issue API 或其他外部系统。
- Snapshot Header、Items 与内容摘要只能插入，禁止 UPDATE/DELETE。
- 后续 Issue Revision、Mapping Profile、Adapter Version、Sync 或 Release 状态变化不得重写旧 Snapshot。
- `UNKNOWN` status/severity 保持可见，不得改写为 CLOSED、RESOLVED、PASS 或其他成功语义。
- Snapshot 不判断 Fixed、Included 或 Verified；M2.4/M2.5 才消费它并生成 Traceability Fact/Gap。
- 不新增服务、Broker、数据库、对象存储、管理 UI 或定时任务。
- Company、真实 Jira 查询、Jira 写入、M2.4、merge、Tag、release 与 production deployment 保持阻断。

## 3. 方案比较与决定

采用“Sync Observation Ledger + PostgreSQL Materialized Snapshot”。`persistPage` 在原有页事务中把精确 `normalized_issue` Revision 关联到 Sync Run；Snapshot 创建只读取该不可变关联并物化稳定内容。该方案增加一张小表和少量列，但能用 FK、事务和 digest 同时证明来源、完整性和历史不变性。

未选择“按 Sync 完成时间查询当时最新 Revision”，因为时间边界不能证明 Revision 确实属于该次运行；未选择“Snapshot 只保存 Revision ID 引用”，因为读取仍依赖外部行的长期可用性，且无法直接证明历史输出 bytes；未选择“只保存 JSON Blob”，因为会削弱关系约束、查询与 Project 隔离，并形成结构化事实的第二表达。

## 4. 逻辑架构与数据流

```text
IssueSourcePort page
        ↓ one page transaction
normalized_issue Revision + issue_sync_run_item observation
        ↓ terminal success
sealed SUCCEEDED/FULL issue_sync_run
        ↓ POST /releases/{releaseId}/issue-snapshots
authorization + idempotency + Release/Source locks
        ↓
resolve latest eligible Sync + age/integrity checks
        ↓
stable selection and RFC 8785 canonical bytes
        ↓ one transaction
release_issue_snapshot + items + Audit + Outbox
        ↓
immutable replay input for M2.4/M2.5
```

API 输入继续遵守已批准的 `IdentifierInput`：Body 中的 `sourceId` 指定 Issue Source。Application 在同一事务内固定选择该 Source 最新的合格成功全量 Sync。Idempotency Record 保存解析后的 `syncRunId` 与响应，因此请求重放不会因出现更新 Sync 而漂移。

## 5. 数据权威与关系模型

### 5.1 Sync Observation Ledger

新增 append-only `issue_sync_run_item`：

| 字段 | 约束 | 说明 |
|---|---|---|
| `sync_run_id` | PK part、复合 FK | 实际观察该 Revision 的 Sync Run |
| `ordinal` | PK part、`>= 0` | 跨页稳定序号 |
| `project_id` | 非空、复合 FK | Project Scope |
| `source_id` | 非空、复合 FK | 必须与 Sync/Issue 属于同一 Source |
| `issue_id` | FK、非空 | 精确 `normalized_issue` Revision |
| `source_issue_id` | 非空 | 用于同一 Run 内身份唯一性校验 |
| `observed_at` | 非空 | Adapter Observation Time |
| `created_at` | 非空 | Authority 写入时间，不进入事实摘要 |

必需约束：`PRIMARY KEY(sync_run_id, ordinal)`、`UNIQUE(sync_run_id, issue_id)`、`UNIQUE(sync_run_id, source_issue_id)`；复合 FK 保证 Run、Source、Issue 和 Project 一致；Trigger 拒绝 UPDATE/DELETE。

`persistPage` 必须在一个事务中解析或插入 `normalized_issue` Revision，并插入对应 Observation。若相同 Revision 已存在，仍必须把该精确 ID 关联到当前 Run。页面重试只能复用完全相同的 ordinal/identity；任何冲突使该页回滚并让 Sync 明确失败。

### 5.2 Sync 完整结果语义

`issue_sync_run` 增加 `result_set_mode` 与 `filter_reference`：

- `result_set_mode` 仅允许 `FULL` 或 `DELTA`，由运行时代码 Descriptor 固定，调用方不得提交。
- M2.3 只接受 `FULL`。当前 Jira CLI Pilot 的非增量查询固定为 `FULL`；未来增量 Adapter 必须先形成可证明的完整 Materialized View，不能把单次 DELTA 伪装成完整 Snapshot。
- MVP `filter_reference` 固定为服务端版本化值 `all-relevant-issues/v1`，对应当前 Source 配置下返回的全部相关 Issue，并包含 tombstone observation；它不是 Jira JQL、URL 或用户输入。
- 终态 Run 的 versions、mode、filter、watermark、counts、completion time 与 diagnostics 必须由数据库约束/Trigger 封存；不得在 `SUCCEEDED`/`FAILED` 后修改。

### 5.3 Materialized Snapshot

沿用 `release_issue_snapshot` 与 `release_issue_snapshot_item`。Forward-only Migration 增加 `source_id`、`source_watermark`、`adapter_version`、`mapping_version`、`canonicalization_version`、`age_policy_version`、`observed_count`、`tombstone_count` 与 `selected_count` 等重放所需 Header Snapshot 字段，并增加 `UNIQUE(release_id, sync_run_id, filter_reference)`。

Item 继续物化 `source_issue_id`、title、severity、status、raw status token、source/mapping version/reference、observed time 与 `fact_digest`。`all-relevant-issues/v1` 只选择 `tombstone=false` 的 Observation；Header 保留 observed/tombstone/selected counts，使排除行为可审计。空的完整结果集是有效事实，`selected_count=0` 不会被自动解释为 Release PASS。

## 6. Snapshot 创建事务与并发

`POST /api/v1/releases/{releaseId}/issue-snapshots` 要求 `issue:snapshot` 与 `Idempotency-Key`。单事务顺序固定为：

1. 校验身份、Project Scope 与请求摘要；不可见 Release/Source 统一 404。
2. 获取或创建 Idempotency Record；已完成请求返回原 Snapshot。
3. 按稳定顺序锁定 Release 与 Issue Source，验证 Release 已有 Locked Manifest。
4. 选择该 Source 最新的 `SUCCEEDED`、`FULL`、同 Project Sync Run，并立即固定其 ID。
5. 校验终态封存、Mapping/Adapter/Filter/Watermark、Observation 数量与 age policy。
6. 锁定 Release 的 Snapshot Version 序列；相同 `(release, sync, filter)` 已存在时返回该 Snapshot。
7. 按 `(source_id, source_issue_id, issue_id)` 排序，过滤 tombstone，验证每项 `fact_digest`，生成 ordinal。
8. 生成 canonical bytes 与 content digest，写 Header、所有 Items、Audit、Outbox 和 Idempotency response。
9. 重新从待写模型计算 count/digest；任一写入或校验失败时整体回滚。

“最新”先按终态成功、完整结果集和 Project/Source Scope 选择，再验证年龄与完整性。如果选中的最新 Run 过期或损坏，操作必须失败；不得跳过它并静默回退到更旧 Run。

不同 Idempotency Key 的并发请求由 Release/Source 锁与唯一约束收敛到同一逻辑 Snapshot。Snapshot Version 从 `1` 开始按 Release 单调增加；不得以 `MAX(version)+1` 的无锁查询分配。

## 7. Canonicalization 与 Digest

版本固定为 `release-issue-snapshot-jcs/v1`，使用 RFC 8785 canonical JSON 与 SHA-256：

```text
contentDigest = "sha256:" + lowercaseHex(SHA-256(canonicalSnapshotBytes))
```

Canonical Header 包含 schema/canonicalization version、Project/Release/Snapshot version、Sync/Source identity、source watermark、Adapter/Mapping/Filter/Age Policy version 与三个 count。Items 包含稳定 ordinal 和全部物化事实字段。`created_at`、请求时间、actor、Idempotency Key、数据库事务 ID 和随机 Snapshot ID 不进入摘要。

字符串以已存储的 UTF-8 值参与 canonicalization，不重新执行 Jira Mapping。时间统一为 UTC RFC 3339、固定微秒精度；枚举使用现有大写 Token；JSON object key 由 RFC 8785 排序，items 只能按规范 ordinal 排列。Repository 读取 Snapshot 时必须能够重建 canonical bytes 并重新验证 digest。

## 8. Age Policy 与失败语义

Pilot 默认 `maxSyncAge=PT24H`，Policy Version 为 `issue-snapshot-age/v1`；Company Profile 启用前必须显式配置并独立验收。年龄从可信 Backend Clock 的 `sync_run.completed_at` 计算到事务开始时间，边界 `age <= maxSyncAge` 合格。不能解析、缺失、未来时间或超过上限均 fail-closed。

| 场景 | 固定诊断 | HTTP / 行为 |
|---|---|---|
| Release/Source 不存在或不可见 | `RESOURCE_NOT_FOUND` | 404，不泄露存在性 |
| Release 未锁定 Manifest | `RELEASE_MANIFEST_NOT_LOCKED` | 409，不创建 Snapshot |
| 没有成功全量 Sync | `ELIGIBLE_SYNC_NOT_FOUND` | 409，不回退 DELTA/FAILED |
| Sync 过期或时间非法 | `SYNC_RUN_STALE` | 422，不把陈旧数据当新输入 |
| Observation count/identity 不一致 | `SYNC_OBSERVATION_INTEGRITY_FAILED` | 422，整体回滚 |
| Fact 或 Snapshot digest 不一致 | `SNAPSHOT_INTEGRITY_FAILED` | 422，整体回滚并告警 |
| 幂等 Key 被不同请求复用 | `IDEMPOTENCY_CONFLICT` | 409 |
| Audit/Outbox/DB 失败 | 固定 persistence diagnostic | 失败可见，整体回滚 |

本操作没有外部依赖，因此不得返回伪造的 Jira 503，也不得用当前最新 Revision、缓存或 JSON fallback 掩盖完整性失败。

## 9. 安全、隐私与审计

- `issue:snapshot` 只允许项目内受控角色；Release、Source、Sync、Issue 每层都校验 Project Scope。
- SQL 使用参数化调用；Source ID、Release ID、Idempotency Key 和配置长度在边界验证。
- 日志、Problem Details、Audit、Outbox 与 CI Evidence 不包含 title、raw status token、source reference、Jira URL、JQL、Credential 或完整 Snapshot payload。
- Audit/Outbox 只保存 Snapshot/Release/Source/Sync ID、versions、counts、digest 与固定事件类型。
- 真实 Issue 内容仅保留在受控 PostgreSQL Domain 表；Fixture 使用合成内容。
- Snapshot 创建不扩大 Jira 权限，不读取 Jira CLI config，也不产生 Company Ready 声明。

## 10. Migration、部署与恢复

使用 forward-only Expand Migration：新增 Observation Ledger、约束、索引、immutable/terminal seal Trigger 和所需 Snapshot/Sync 列。历史 M2.2 Run 没有 Observation Ledger，必须标记为不可用于 M2.3；Migration 不按时间猜测或回填历史 membership。

部署顺序为 Migration、兼容应用、M2.3 Endpoint；不新增服务、Broker、容器或外部配置中心。Feature 默认只在 Pilot Profile 启用。应用启动时验证 canonicalization/age policy 版本，未知版本 fail-closed。

事务失败自动回滚，不产生半个 Snapshot。若新应用缺陷影响创建，关闭 M2.3 新写入口并保留所有既有 Snapshot；优先 roll-forward。数据库恢复使用既有 PostgreSQL backup/restore 机制，并以 Snapshot 重建 digest、FK、counts 与 Audit/Outbox 对账证明恢复，不修改历史 Migration。

## 11. 测试与 Evidence 矩阵

- Unit：RFC 8785、SHA-256、UTF-8、UTC 时间精度、稳定排序、空集合、tombstone 排除、count 与三次 digest 重放。
- PostgreSQL：Observation 复合 FK/唯一约束、页事务、immutable Trigger、终态 Run 封存、Snapshot 原子子项、跨 Project 拒绝、UPDATE/DELETE 拒绝、空库/升级/重复 Migration。
- Application：权限、404 隐藏、Locked Manifest、最新合格 Run 固定、FULL/DELTA、年龄边界、幂等冲突、并发版本、Audit/Outbox 失败整体回滚。
- Replay：创建 Snapshot 后插入新 Issue Revision、激活新 Mapping、完成新 Sync；旧 Snapshot bytes、items、ordinal、counts 与 digest 完全不变。
- Integrity：缺 Observation、重复身份、错误 count、篡改 fact digest/content digest 均明确失败，不访问 Jira，不读取当前最新 Revision。
- Security：日志、Problem Details、Audit、Outbox、Git 与 CI Artifact 扫描不出现 Issue 内容、JQL、URL、路径或 Credential。
- Gate Evidence：真实 PostgreSQL Integration Test、canonical replay report、transaction failure report、security scan、双语 Pair Gate 与固定 Git/CI locator。

## 12. V0.2、V0.3 与非目标

对 V0.2，本设计新增一张实施账本和最小 Expand Migration，提供 M2.4/M2.5 可重放的 Issue 输入，不改变 Core Contract。它不执行 Traceability Edge ingestion，不推断 Fixed/Included/Verified，不产生 Quality Result。

对未来 V0.3，多个 Issue Source 可分别形成 Snapshot，并由另一个经评审的 Release Input Snapshot 组合；增量 Adapter 可增加可证明的 full-state materialization。当前 MVP 不预建聚合服务、CDC、Event Broker、Snapshot UI、跨 Release 查询或全量 Jira。

## 13. Technology Decision Delegation

本设计的关键技术决定记录在 `TDR-016`，逐项回答选择原因、问题、替代方案、V0.2/V0.3 影响、迁移、测试、部署与失败恢复。该 TDR 只能在本 Written Spec Review 获 Owner 批准后转为 `Accepted`。

## 14. Written Spec Review Gate

Project Owner 已于 2026-09-02 批准方案 A 的设计方向，但本书面规范仍为 `PENDING`。Owner 需要独立确认 `APPROVE M2-KD-2026-09-02-01 WRITTEN SPEC REVIEW` 后，才允许创建逐文件、逐测试、逐提交 Implementation Plan。

批准本书面规范不授权生产代码、Migration、真实 Jira 查询、Jira 写操作、Company、M2.4、merge、Tag、release 或 production deployment；Implementation Plan 与实施执行继续使用独立授权。
