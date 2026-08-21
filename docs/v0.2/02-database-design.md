# 02 — Database Architecture and ER Model

## 1. 决策摘要

结构化数据使用单一 PostgreSQL 实例；Evidence Payload 使用 S3 兼容对象存储。原因和替代方案见 [TDR-003](tdr/TDR-003-postgresql.md) 与 [TDR-004](tdr/TDR-004-s3-compatible-evidence-storage.md)。

模块化单体使用一个数据库和同一事务管理器，但表按领域前缀或 PostgreSQL schema 分组。MVP 推荐单 schema + 清晰表名前缀，避免迁移和权限配置复杂度。数据库不成为绕过领域边界的集成总线。

## 2. 通用约定

- 主键：`uuid`，由应用生成 UUIDv7；对外业务标识另设唯一键。
- 时间：`timestamptz` UTC；所有实体有 `created_at`，可变记录另有 `updated_at` 和乐观锁 `row_version`。
- 枚举：MVP 使用受 CHECK 约束的 `varchar`，避免 PostgreSQL enum 的迁移摩擦。
- JSONB：只保存版本化原始快照、规则文档、扩展 Metadata；关键查询字段必须结构化列化。
- 删除：历史核心数据不物理删除；使用状态/保留策略。凭证引用可撤销，临时上传会话可清理。
- 金额之外的数值指标使用明确单位列；时间长度统一毫秒。
- 所有 FK 列建立索引；常用 Release 范围查询建立复合索引。

## 3. ER 总图

```mermaid
erDiagram
  RELEASE {
    uuid id PK
    string release_id UK
    uuid locked_manifest_id FK
    string status
    timestamptz created_at
  }
  MANIFEST_REVISION {
    uuid id PK
    uuid release_id FK
    int revision
    string schema_version
    string state
    string content_digest UK
  }
  ARTIFACT {
    uuid id PK
    string artifact_id UK
    string type
    string checksum_algorithm
    string checksum_value
  }
  MANIFEST_ARTIFACT {
    uuid manifest_id PK,FK
    uuid artifact_id PK,FK
    bool required
    int ordinal
  }
  ISSUE_SOURCE {
    uuid id PK
    string source_key UK
    string adapter_type
  }
  NORMALIZED_ISSUE {
    uuid id PK
    uuid source_id FK
    string source_issue_id
    bigint source_version
  }
  RELEASE_ISSUE_SNAPSHOT {
    uuid id PK
    uuid release_id FK
    uuid issue_id FK
    int snapshot_version
    string content_digest
  }
  SOURCE_COMMIT {
    uuid id PK
    string repository
    string commit_id
  }
  BUILD_RECORD {
    uuid id PK
    string provider
    string build_id
  }
  ISSUE_COMMIT_EDGE {
    uuid id PK
    uuid issue_id FK
    uuid commit_id FK
    string confidence
    string verification_status
  }
  COMMIT_BUILD_EDGE {
    uuid id PK
    uuid commit_id FK
    uuid build_id FK
    string confidence
  }
  BUILD_ARTIFACT_EDGE {
    uuid id PK
    uuid build_id FK
    uuid artifact_id FK
    string confidence
  }
  TRACEABILITY_SNAPSHOT {
    uuid id PK
    uuid release_id FK
    int version
    string content_digest
  }
  TEST_PLAN_VERSION {
    uuid id PK
    string plan_id
    int version
  }
  TEST_CASE_VERSION {
    uuid id PK
    string case_id
    int version
  }
  TEST_RUN {
    uuid id PK
    string test_run_id UK
    uuid release_id FK
    uuid plan_version_id FK
    uuid environment_snapshot_id FK
  }
  TEST_ATTEMPT {
    uuid id PK
    uuid test_run_id FK
    uuid case_version_id FK
    int attempt_no
    uuid agent_id FK
    uuid device_id FK
  }
  TEST_RESULT {
    uuid id PK
    uuid attempt_id UK,FK
    string status
    bigint duration_ms
  }
  EVIDENCE {
    uuid id PK
    string evidence_id UK
    uuid release_id FK
    uuid test_run_id FK
    uuid test_result_id FK
    string payload_checksum
    string upload_state
  }
  RULE_SET_VERSION {
    uuid id PK
    string rule_set_id
    int version
    string content_digest UK
  }
  QUALITY_EVALUATION {
    uuid id PK
    string evaluation_id UK
    uuid release_id FK
    uuid rule_set_version_id FK
    string input_digest
  }
  QUALITY_RESULT {
    uuid id PK
    uuid evaluation_id UK,FK
    string final_status
    string result_digest
  }

  RELEASE ||--o{ MANIFEST_REVISION : owns
  RELEASE o|--|| MANIFEST_REVISION : locks
  MANIFEST_REVISION ||--|{ MANIFEST_ARTIFACT : contains
  ARTIFACT ||--o{ MANIFEST_ARTIFACT : belongs_to
  ISSUE_SOURCE ||--o{ NORMALIZED_ISSUE : provides
  RELEASE ||--o{ RELEASE_ISSUE_SNAPSHOT : freezes
  NORMALIZED_ISSUE ||--o{ RELEASE_ISSUE_SNAPSHOT : snapshotted_as
  NORMALIZED_ISSUE ||--o{ ISSUE_COMMIT_EDGE : linked
  SOURCE_COMMIT ||--o{ ISSUE_COMMIT_EDGE : linked
  SOURCE_COMMIT ||--o{ COMMIT_BUILD_EDGE : linked
  BUILD_RECORD ||--o{ COMMIT_BUILD_EDGE : linked
  BUILD_RECORD ||--o{ BUILD_ARTIFACT_EDGE : produces
  ARTIFACT ||--o{ BUILD_ARTIFACT_EDGE : produced_as
  RELEASE ||--o{ TRACEABILITY_SNAPSHOT : freezes
  RELEASE ||--o{ TEST_RUN : tested_by
  TEST_PLAN_VERSION ||--o{ TEST_RUN : selected_for
  TEST_RUN ||--|{ TEST_ATTEMPT : contains
  TEST_CASE_VERSION ||--o{ TEST_ATTEMPT : executes
  TEST_ATTEMPT ||--o| TEST_RESULT : ends_with
  TEST_RUN ||--o{ EVIDENCE : produces
  TEST_RESULT o|--o{ EVIDENCE : supports
  RELEASE ||--o{ QUALITY_EVALUATION : evaluated_by
  RULE_SET_VERSION ||--o{ QUALITY_EVALUATION : uses
  QUALITY_EVALUATION ||--|| QUALITY_RESULT : yields
```

## 4. 表设计与键

### 4.1 Release / Manifest / Artifact

| 表 | PK | 关键 FK | 唯一约束 | 生命周期 |
|---|---|---|---|---|
| `release` | `id` | `locked_manifest_id → manifest_revision.id`（延迟绑定） | `release_id` | 永久保留，状态追加记录 |
| `release_status_history` | `id` | `release_id → release.id`, `actor_id` | `(release_id, sequence_no)` | 追加 |
| `manifest_revision` | `id` | `release_id → release.id` | `(release_id, revision)`, `content_digest` | REGISTERED 后不可变 |
| `artifact` | `id` | 可选 `build_id → build_record.id` | `(checksum_algorithm, checksum_value)`；`artifact_id` | 内容寻址、可跨 Release 复用 |
| `manifest_artifact` | 复合 `(manifest_id, artifact_id)` | 两侧 FK | `(manifest_id, ordinal)` | 随 Manifest 锁定 |

为解除 Release 与 Manifest 的创建顺序循环，`release.locked_manifest_id` 初始为空；Lock 在一个事务内校验该 Manifest 属于该 Release、更新 Manifest 状态、写入引用和状态历史。部分唯一索引保证每个 Release 至多一个 `LOCKED` Manifest。

### 4.2 Issue 与 Snapshot

| 表 | PK | 关键 FK | 唯一约束 |
|---|---|---|---|
| `issue_source` | `id` | service credential reference | `source_key` |
| `normalized_issue` | `id` | `source_id` | `(source_id, source_issue_id, source_version)` |
| `issue_sync_run` | `id` | `source_id` | `sync_run_id` |
| `issue_sync_cursor` | `source_id` | `source_id` | 一源一游标 |
| `release_issue_snapshot` | `id` | `release_id`, `issue_id`, `sync_run_id` | `(release_id, snapshot_version, issue_id)` |

`normalized_issue.raw_snapshot` 保存去敏后的外部原始响应和 schema version；Core 查询只使用结构化归一字段。Snapshot 创建完成后不可更新，外部状态变化产生新 source version，不改变历史 Release。

### 4.3 Traceability

四张 Edge 表分别是 `issue_commit_edge`、`commit_build_edge`、`build_artifact_edge`、`artifact_release_edge`。共同列：

- `id`, 两端 FK
- `source_type`：BUILD_METADATA、SCM_REFERENCE、MANIFEST、MANUAL_ASSERTION、NAME_INFERENCE
- `source_reference`：证明来源的稳定引用
- `evidence_id`（可空，仅当有 Evidence 实体时）
- `confidence`：HIGH/MEDIUM/LOW/UNKNOWN
- `verification_status`：UNVERIFIED/VALID/INVALID/MISSING
- `verified_at`, `verified_by`, `reason`

每对端点和来源唯一，避免重复同步产生重复边。`traceability_snapshot` 与 `traceability_snapshot_edge` 固化 Quality Evaluation 所见的边集合。

### 4.4 Test / Device / Agent

| 表 | 关系与约束 |
|---|---|
| `test_plan_version` | `(plan_id, version)` 唯一；PUBLISHED 后不可变 |
| `test_case_version` | `(case_id, version)` 唯一；PUBLISHED 后不可变 |
| `test_plan_case` | M:N 关联，保存顺序、是否必须和参数 |
| `device` | `device_id` 唯一；敏感序列号仅保存受控引用或散列 |
| `agent` | `agent_id` 唯一；绑定 service identity 与协议版本 |
| `agent_capability` | `(agent_id, capability, version)` 唯一 |
| `environment_snapshot` | 固化 Device/系统/台架/Agent/Capability 快照和摘要 |
| `test_run` | 绑定 Release、Locked Manifest、Plan Version、Environment Snapshot |
| `test_attempt` | `(test_run_id, case_version_id, attempt_no)` 唯一 |
| `test_result` | `attempt_id` 唯一，确保一个 Attempt 至多一个终态 Result |
| `agent_command` | `command_id` 唯一，含租约、ACK、幂等键和状态 |

### 4.5 Evidence

`evidence` 的必填 FK 为 `release_id`、`test_run_id`；可选 FK 为 `test_result_id`、`device_id`、`artifact_id`。数据库 CHECK 保证 Test Result 若存在必须属于同一 Test Run；该跨表约束由事务服务和约束触发器/延迟校验共同保证。

`evidence_upload_session` 记录对象 key、预期大小/摘要、过期时间与状态。只有 Complete 操作从对象存储读取实际 metadata 并校验成功后，`evidence.upload_state` 才能变为 AVAILABLE。

### 4.6 Quality

| 表 | 关键约束 |
|---|---|
| `quality_rule` | `(rule_id, version)` 唯一，发布后不可变 |
| `rule_set_version` | `(rule_set_id, version)` 与 `content_digest` 唯一 |
| `rule_set_member` | `(rule_set_version_id, rule_id, rule_version)` 唯一 |
| `quality_input_snapshot` | `input_digest`、Manifest/Issue/Trace/Test/Evidence 摘要不可变 |
| `quality_evaluation` | `(release_id, input_digest, rule_set_version_id, engine_version)` 可用作幂等键 |
| `rule_evaluation_result` | `(evaluation_id, rule_id, rule_version)` 唯一 |
| `quality_result` | `evaluation_id` 唯一；只追加，不覆盖旧评估 |

## 5. 关键完整性约束

1. Release 进入 `READY_FOR_TEST` 前，`locked_manifest_id IS NOT NULL`。
2. Locked Manifest 的 `release_id` 必须与 Release 一致，且 Artifact 集合非空。
3. Artifact checksum 仅允许受支持算法，V0.2 为 SHA-256；值满足长度/字符格式。
4. Test Run 的 Manifest 摘要必须等于 Release 当前 Locked Manifest 摘要。
5. Attempt number 从 1 递增；终态 Attempt 不得回到运行态。
6. AVAILABLE Evidence 必须有对象 URI、大小、checksum 和 collector version。
7. Quality Evaluation 仅接受 AVAILABLE Evidence 和已完成的输入 Snapshot。
8. `ERROR`、缺失或不一致输入不得通过默认值变为 PASS。
9. 所有手工 Traceability Edge 和 Quality Override 必须有 actor、reason 和 Audit Event。

数据库约束负责防止非法结构；业务状态机和跨聚合一致性由应用事务负责。不得用宽泛异常捕获隐藏约束失败。

## 6. 事务边界

- Create Release：Release + 首条状态历史 + Audit + Outbox 同事务。
- Register Manifest：Revision + Artifact links + validation result 同事务。
- Lock Manifest：完整性复检 + Lock + Release 引用 + 状态历史 + Audit + Outbox 同事务。
- Complete Test Result：Attempt 终态 + Result + Outbox 同事务；Evidence 上传独立完成。
- Publish Rule Set：规则验证 + 版本发布 + Audit 同事务。
- Complete Evaluation：Input Snapshot + Rule Results + Quality Result + Release 状态历史同事务。

对象存储不能参与数据库事务，使用上传状态机和 checksum 实现可恢复一致性，不宣称分布式原子事务。

## 7. 索引与查询

MVP 必需索引：

- Release：`(project, created_at DESC)`、`(status, created_at)`。
- Snapshot：`(release_id, snapshot_version DESC)`。
- Trace Edge：每个 from/to FK 索引及 `(verification_status, confidence)`。
- Run/Attempt：`(release_id, created_at DESC)`、`(agent_id, state, lease_expires_at)`。
- Evidence：`(release_id, type, captured_at)`、`(test_run_id, upload_state)`、checksum。
- Quality：`(release_id, created_at DESC)`、幂等复合键。
- Audit：`(resource_type, resource_id, occurred_at)`、`(actor_id, occurred_at)`。

V0.2 不预建大量分析索引。慢查询通过 `pg_stat_statements` 和真实数据证明后新增。

## 8. 数据生命周期与保留

| 数据 | 活跃期 | 历史策略 | 清理条件 |
|---|---|---|---|
| Release/Manifest/Trace/Quality | 全生命周期 | 不物理删除 | 公司治理策略批准后归档 |
| Issue Snapshot | 与 Release 同期 | 不跟随外部删除 | 与 Release 一起归档 |
| Test Result/Evidence Metadata | 与 Release 同期 | 永久可解释 | 与 Payload 保留策略一致 |
| Evidence Payload | 默认至少覆盖项目质量审计周期 | 分层存储可选 | 到期、无 legal hold、审计记录 |
| Upload Session | 小时级 | 保留失败摘要 | 到期且无引用后清理 |
| Command/Heartbeat | 运行期 | 摘要保留 | 按运维周期分区/清理 |
| Audit | 公司审计周期 | 追加、不可篡改 | 仅按正式策略归档 |

具体天数是部署策略，不硬编码；必须配置、审计且保证 Quality Result 在保留期内可解释。

## 9. 数据库版本与迁移

- Flyway 迁移只前进，文件命名 `V<sequence>__<meaning>.sql`；已应用脚本不可修改。
- 每次发布先在生产备份副本和上一版本数据上演练迁移。
- 采用 Expand → Migrate → Contract；破坏性 Contract 至少跨一个兼容发布。
- Schema version、应用版本和迁移清单写入部署记录。
- 历史 JSON Snapshot 通过自身 `schema_version` 解释，不批量改写业务含义。

## 10. 故障与恢复

- 数据库不可用：写入失败并显式返回；不得内存暂存后宣称成功。
- 事务冲突：基于乐观锁返回 409；客户端读取最新状态后决定重试。
- 迁移失败：停止部署，恢复旧应用；若已执行不可逆迁移，按演练恢复备份。
- 主库损坏：从备份 + WAL/PITR 恢复，随后对 Evidence Object inventory 与 metadata 做 reconciliation。
- 数据不一致：隔离受影响 Release，Quality Evaluation 拒绝运行，输出可审计诊断。

## 11. 验收标准与证据

- ER 中所有 PK/FK/基数与本文一致，数据库实际约束可导出验证。
- 并发 Lock 只有一个成功，失败方收到 409，Release 无部分状态。
- 重复 Adapter/Agent/API 请求不产生重复实体。
- 任意 Quality Result 能查询到固定的 Manifest、Snapshot、Result、Evidence 和 Rule Set。
- 在上一版本备份上成功完成迁移与回滚/恢复演练。

证据：迁移测试报告、约束测试、并发测试、Explain Plan 样本、备份恢复记录、数据一致性巡检报告。
