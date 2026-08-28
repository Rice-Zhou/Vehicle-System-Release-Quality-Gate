# M2 Issue Snapshot 与 Traceability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变 V0.1 冻结架构的前提下，实现可重复的 Issue 同步与 Release Issue Snapshot，并以强类型、可快照、可重放的 Traceability 证明 Fixed、Included 和 Verified gap。

**Architecture:** 在现有 Kotlin/Spring Boot 模块化单体中增加 `issue` 与 `traceability` 模块；所有外部 Issue 数据经 `IssueSourcePort` 归一化，所有历史事实以 PostgreSQL append-only Revision/Snapshot 保存。CI 使用合成 Fixture，真实 Jira 只在显式 `PILOT` 配置下执行最多 20 条只读 Smoke；Artifact→Release 只从 Locked Manifest 派生。

**Tech Stack:** Kotlin 2.3、Java 21、Spring Boot 4、Spring JDBC/Transaction、PostgreSQL 17、Flyway、Testcontainers、JUnit 5、AssertJ、ArchUnit、OpenAPI 3.1、PowerShell、Jira CLI。

---

## 交付边界与顺序

本计划基于 `M2-KD-2026-08-28-01` 和 Accepted `TDR-014`。执行生产代码仍需独立授权；Jira 写入、Company Profile 启用、merge、Tag、release 和生产部署不在本计划内。顺序固定为 M2.0～M2.6；每个 Task 通过目标测试并提交后才能开始下一项。

### Task 1: M2.0 模块、权限与 API Contract

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/PackageMarker.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/PackageMarker.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/ArchitectureTest.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/access/domain/Permission.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/access/PermissionMatrixTest.kt`
- Modify: `contracts/openapi/v0.2/openapi.json`
- Modify: `contracts/openapi/v0.2/compatibility-baseline.json`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/M2ApiContractTest.kt`

- [ ] **Step 1: 写失败的架构与权限测试**

在 `ArchitectureTest` 测试类的 required modules 与 adapter isolation 中加入 `issue`、`traceability`；在权限矩阵中精确断言：`issue:sync`/`issue:snapshot` 允许 ENGINEER、RELEASE_MANAGER、ADMINISTRATOR，`issue:read`/`traceability:read` 允许全部角色，`traceability:verify` 允许 ENGINEER、QUALITY_OWNER、ADMINISTRATOR，`traceability:ingest` 只允许服务身份的专用 scope。

- [ ] **Step 2: 运行测试并确认失败**

Run: `./backend/gradlew -p backend test --tests '*ArchitectureTest' --tests '*PermissionMatrixTest'`

Expected: FAIL，指出缺少两个 PackageMarker 和 M2 Permission。

- [ ] **Step 3: 添加最小模块与权限实现**

```kotlin
enum class Permission(val scope: String, private val allowedRoles: Set<ProjectRole>) {
    RELEASE_CREATE("release:create", setOf(ENGINEER, RELEASE_MANAGER, ADMINISTRATOR)),
    RELEASE_READ("release:read", ProjectRole.entries.toSet()),
    MANIFEST_WRITE("manifest:write", setOf(ENGINEER, RELEASE_MANAGER, ADMINISTRATOR)),
    MANIFEST_LOCK("manifest:lock", setOf(RELEASE_MANAGER, ADMINISTRATOR)),
    ISSUE_SYNC("issue:sync", setOf(ENGINEER, RELEASE_MANAGER, ADMINISTRATOR)),
    ISSUE_READ("issue:read", ProjectRole.entries.toSet()),
    ISSUE_SNAPSHOT("issue:snapshot", setOf(ENGINEER, RELEASE_MANAGER, ADMINISTRATOR)),
    TRACEABILITY_READ("traceability:read", ProjectRole.entries.toSet()),
    TRACEABILITY_VERIFY("traceability:verify", setOf(ENGINEER, QUALITY_OWNER, ADMINISTRATOR));
    fun isAllowedFor(role: ProjectRole) = role in allowedRoles
}
```

`traceability:ingest` 保持为 JWT service scope，不映射为用户 ProjectRole。

- [ ] **Step 4: 追加向后兼容的 OpenAPI Operations**

精确增加：`POST /api/v1/issue-sources/{sourceId}/sync`、`GET /api/v1/issue-sync-runs/{syncRunId}`、`POST /api/v1/releases/{releaseId}/issue-snapshots`、`POST /api/v1/traceability/facts:ingest`、`POST /api/v1/releases/{releaseId}/traceability:verify`、`GET /api/v1/releases/{releaseId}/traceability`。写 Operation 设置 `x-idempotency-required: true`；每项设置批准的 `x-permission`，异步响应为 `202`。

- [ ] **Step 5: 验证并提交**

Run: `pnpm run test:contracts; ./backend/gradlew -p backend test --tests '*ArchitectureTest' --tests '*PermissionMatrixTest' --tests '*M2ApiContractTest'`

Expected: contract `operations=32` 或更高且新增六项全部存在；Gradle tests PASS。

Commit: `feat(m2): establish issue traceability contracts`

### Task 2: M2.1 PostgreSQL Authority Baseline

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__m2_issue_traceability.sql`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/MigrationConstraintTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/M2MigrationConstraintTest.kt`

- [ ] **Step 1: 写失败的 Migration Constraint Tests**

断言 17 张 M2 表、`artifact_release_edge_v`、所有 FK/index/UNIQUE/CHECK、Revision 端点稳定、Snapshot/Edge/Gap UPDATE/DELETE 拒绝、跨 Project 引用拒绝，以及 `artifact` 仍无 `build_id`、不存在可写 Artifact→Release 表。

- [ ] **Step 2: 运行并确认 V4 缺失**

Run: `./backend/gradlew -p backend test --tests '*M2MigrationConstraintTest'`

Expected: FAIL，首个缺失对象为 `issue_source`。

- [ ] **Step 3: 创建 forward-only V4 Migration**

按依赖顺序创建：`background_job`；`issue_source`、`issue_sync_run`、`issue_sync_cursor`、`normalized_issue`、`release_issue_snapshot`、`release_issue_snapshot_item`；`source_commit`、`build_record`；三张 `*_edge_revision`；`traceability_verification_run`、`traceability_gap`、`traceability_snapshot`、`traceability_snapshot_edge`、`traceability_snapshot_gap`；最后创建只读 `artifact_release_edge_v`。所有 ID 使用现有 varchar(40) UUIDv7 表达；digest 使用 `^sha256:[0-9a-f]{64}$` CHECK；状态使用受 CHECK 约束 varchar；外键 `ON DELETE RESTRICT`。

Revision 表必须具备：

```sql
UNIQUE (edge_id, revision),
UNIQUE (id, edge_id, revision),
CHECK ((revision = 1 AND previous_revision_id IS NULL AND previous_revision IS NULL)
    OR (revision > 1 AND previous_revision_id IS NOT NULL AND previous_revision = revision - 1)),
FOREIGN KEY (previous_revision_id, edge_id, previous_revision)
    REFERENCES issue_commit_edge_revision(id, edge_id, revision) DEFERRABLE
```

为 Revision、Snapshot、Snapshot Item/Edge/Gap 安装现有 `reject_immutable_write()` Trigger；Constraint Trigger 拒绝同一 `edge_id` 改变端点或 source identity。

- [ ] **Step 4: 验证 clean/upgrade/repeat 与约束**

Run: `./backend/gradlew -p backend test --tests '*MigrationConstraintTest' --tests '*M2MigrationConstraintTest'`

Expected: PASS；Flyway 第二次启动显示无 pending Migration。

Commit: `feat(m2): add issue traceability database authority`

### Task 3: M2.2 IssueSourcePort、Fixture Contract 与 Jira CLI Pilot

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/domain/IssueModels.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueSourcePort.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/FixtureIssueSourceAdapter.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JiraCliPilotProperties.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JiraCliPilotAdapter.kt`
- Create: `backend/src/test/resources/m2/issues/fixture-pages.json`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSourceContractTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/JiraCliPilotAdapterTest.kt`
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: 用 Parameterized Contract Test 定义 Port**

```kotlin
interface IssueSourcePort {
    fun capabilities(): SourceCapabilities
    fun fetchChanges(cursor: String?, filter: IssueFilter, pageSize: Int): IssuePage
    fun fetchByIds(sourceIssueIds: Set<String>): IssueBatch
    fun health(): SourceHealth
}

data class IssuePage(
    val issues: List<NormalizedIssue>, val nextCursor: String?,
    val sourceWatermark: String, val observedAt: Instant,
    val mappingVersion: String, val terminal: Boolean,
)
```

Contract Test 对 fixture、recorded internal fixture 与 Jira process fixture 验证相同 digest，并覆盖 duplicate page、429、5xx、401/403、timeout、invalid output、tombstone 和 UNKNOWN mapping。

- [ ] **Step 2: 运行并确认 Port 不存在**

Run: `./backend/gradlew -p backend test --tests '*IssueSourceContractTest'`

Expected: FAIL at compile。

- [ ] **Step 3: 实现 Fixture 与严格 Jira process boundary**

Jira Adapter 只构造固定 argv；校验绝对普通文件、Project Key `^[A-Z][A-Z0-9_]{1,19}$`、limit 1～20、timeout、stdout byte limit、每行五列与 control character。禁止 `--raw` 和任意调用方 flags；stderr 只输出固定 code 与 SHA-256 digest。

- [ ] **Step 4: 增加默认关闭配置并测试启动拒绝**

```yaml
vsrqg:
  jira:
    pilot:
      enabled: ${VSRQG_JIRA_PILOT_ENABLED:false}
      cli-path: ${VSRQG_JIRA_CLI_PATH:}
      project: ${VSRQG_JIRA_PROJECT:}
      max-issues: ${VSRQG_JIRA_MAX_ISSUES:20}
      timeout: ${VSRQG_JIRA_TIMEOUT:PT15S}
```

- [ ] **Step 5: 验证并提交**

Run: `./backend/gradlew -p backend test --tests '*IssueSourceContractTest' --tests '*JiraCliPilotAdapterTest' --tests '*ApplicationContextTest'`

Expected: PASS；测试输出不包含 fixture title、argv、path 或 stderr。

Commit: `feat(m2): add bounded issue source adapters`

### Task 4: M2.2 同步事务、Cursor 与 API

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueSyncRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/StartIssueSync.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/RunIssueSync.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JdbcIssueSyncRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSyncController.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSyncJobWorker.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSyncIntegrationTest.kt`

- [ ] **Step 1: 写失败的事务与恢复测试**

测试 `QUEUED → RUNNING → SUCCEEDED/FAILED`、每页原子提交、相同 `(source, sourceIssueId, sourceVersion, mappingVersion)` 不重复、失败不推进 successful Cursor、retry 创建新 Sync Run、Audit/Outbox/Job 任一失败整体回滚。

- [ ] **Step 2: 运行并确认 Use Case 缺失**

Run: `./backend/gradlew -p backend test --tests '*IssueSyncIntegrationTest'`

Expected: FAIL at compile。

- [ ] **Step 3: 实现同步与 Controller**

`StartIssueSync` 在一个 `@Transactional` 中完成 authorization、idempotency、Sync Run、Audit、Outbox、Background Job，返回 `202` operation ID。Worker 逐页事务写 Revision/checkpoint；只有全部成功才在最终事务推进 Cursor。异常必须映射固定 diagnostics，禁止宽泛 catch 后返回成功。

- [ ] **Step 4: 验证并提交**

Run: `./backend/gradlew -p backend test --tests '*IssueSyncIntegrationTest' --tests '*SecurityAcceptanceTest'`

Expected: PASS，失败注入后 Cursor 与 idempotency 状态精确符合测试。

Commit: `feat(m2): persist recoverable issue synchronization`

### Task 5: M2.3 Immutable Release Issue Snapshot

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueSnapshotRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/CreateIssueSnapshot.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JdbcIssueSnapshotRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSnapshotController.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSnapshotIntegrationTest.kt`

- [ ] **Step 1: 写失败的 Snapshot 不变性测试**

测试只有 `SUCCEEDED` Sync Run 可用；Project/source/age 不匹配返回 404/409/422；项目按 `(source, sourceIssueId)` 稳定排序；后续 Jira version、mapping 或 sync 变化后旧 Snapshot bytes/digest 不变；UPDATE/DELETE 由数据库拒绝。

- [ ] **Step 2: 实现稳定 digest 与单事务写入**

Canonical payload 只含 snapshot schema/version、Release/Sync/filter identity、age、原始 status token、mapping/source version/reference 与每项 `factDigest`。在一个事务内写 header/items、Audit、Outbox；Idempotency replay 返回同一 Snapshot。

- [ ] **Step 3: 验证并提交**

Run: `./backend/gradlew -p backend test --tests '*IssueSnapshotIntegrationTest'`

Expected: PASS，三次 replay digest 完全相同。

Commit: `feat(m2): freeze release issue snapshots`

### Task 6: M2.4 CI/Build Fact Ingestion 与 Edge Revision

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/domain/TraceabilityModels.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/TraceabilityRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/IngestTraceabilityFacts.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/JdbcTraceabilityRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/TraceabilityFactController.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/FactIngestionIntegrationTest.kt`

- [ ] **Step 1: 写失败的 typed edge tests**

覆盖 Issue→Commit、Commit→Build、Build→Artifact，多对多、相同 batch 幂等、proof/status/confidence 变化插入 revision+1、端点变化拒绝、cross-project 拒绝、Artifact→Release 请求字段拒绝、用户 JWT 拒绝 service-only endpoint。

- [ ] **Step 2: 实现领域类型与 ingest transaction**

```kotlin
enum class EdgeType { ISSUE_COMMIT, COMMIT_BUILD, BUILD_ARTIFACT, ARTIFACT_RELEASE }
enum class VerificationStatus { VALID, INVALID, CONFLICT, ERROR }
enum class Confidence { HIGH, MEDIUM, LOW, UNKNOWN }
```

请求只接受前三种 writable type、provider reference、source revision、artifact SHA-256 与 proof reference；禁止 Fixed/Included/Verified Boolean。相同 fact digest 返回现有 Revision；变化时锁 logical edge 并插入下一 Revision、Audit、Outbox。

- [ ] **Step 3: 验证并提交**

Run: `./backend/gradlew -p backend test --tests '*FactIngestionIntegrationTest' --tests '*SecurityAcceptanceTest'`

Expected: PASS；数据库查询证明每个 logical edge 只有 append-only history。

Commit: `feat(m2): ingest typed traceability revisions`

### Task 7: M2.5 Verification、Gap、Snapshot 与 Query

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/VerifyTraceability.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/GetTraceability.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/TraceabilityController.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationIntegrationTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityReplayTest.kt`

- [ ] **Step 1: 写已知链和缺口的失败测试**

构造 Issue→Commit→Build→Artifact→Locked Manifest→Release；断言 Fixed=true、Included=true、Verified=false 且 exact `TEST_RESULT_EVIDENCE_MISSING` gap。逐段删除 required edge 时 Included=false 并返回对应 gap；只有 Commit 存在不得 Fixed/Verified；CONFLICT/ERROR 不能成为有效路径。

- [ ] **Step 2: 实现 deterministic path verification**

按稳定 Issue/Edge ID 排序，读取每个 logical edge 的指定 Revision；Artifact→Release 只读 `artifact_release_edge_v`。Verification Run 保存 policy/validator version、状态与 diagnostics；完成时物化完整 Edge/Gap facts 并计算 content digest。不得在 M2 创建 Verified=true。

- [ ] **Step 3: 实现异步 verify 与只读 query API**

`POST :verify` 创建 operation、Audit、Job 并返回 `202`；`GET traceability` 只读指定或最新完成 Snapshot，不回查最新 Revision。404 隐藏不可见资源，409 表示状态冲突，422 表示事实无效，503 表示明确外部不可用。

- [ ] **Step 4: 验证 replay 与提交**

Run: `./backend/gradlew -p backend test --tests '*TraceabilityVerificationIntegrationTest' --tests '*TraceabilityReplayTest'`

Expected: PASS；插入 edge revision+1 后旧 Snapshot 的 bytes/path/confidence/digest 不变。

Commit: `feat(m2): verify and snapshot traceability paths`

### Task 8: M2.6 Gate、真实 Jira Smoke 与验收包

**Files:**
- Create: `scripts/m2/verify.ps1`
- Create: `scripts/m2/jira-pilot-smoke.ps1`
- Create: `scripts/tests/m2-verify-gates.tests.ps1`
- Create: `.github/workflows/m2-backend.yml`
- Create: `docs/m2/README.md`
- Create: `docs/governance/acceptance/records/2026-08-28-m2-owner-gate-001.md`

- [ ] **Step 1: 写失败的 Gate orchestration tests**

验证 clean worktree、固定 commit、contract/governance/secret scan/Gradle/PostgreSQL/replay/recovery 顺序、任一 Gate 失败即整体失败、Evidence JSON 仍生成且 status=`FAILED`、Owner decision 初始为 `PENDING`。真实 Jira Smoke 不得是 CI 必需 Gate。

- [ ] **Step 2: 实现 M2 Gate 与 CI**

沿用 M1 Evidence 格式并将 milestone 设为 `M2`；运行设计治理、OpenAPI、全部 Backend tests、Migration、已知链/gap/replay、backup/restore drill 与 secret scan。Workflow 只赋予 `contents: read`，不配置 Jira secret，上传 `m2-evidence-${{ github.sha }}`。

- [ ] **Step 3: 实现人工 Jira Pilot Smoke**

脚本要求 `PILOT`、显式 enabled、单 Project、limit 1～20；调用 Backend operation 而非自行拼接 Jira 命令。输出只含 execution time、versions、limit、count、schema digest、Sync Run ID、fixed result code；检测 title、URL、path、credential-like token 时失败并拒绝发布报告。

- [ ] **Step 4: 创建 PENDING 验收记录并运行完整 Gate**

Run: `pwsh -NoProfile -File scripts/tests/m2-verify-gates.tests.ps1; pwsh -NoProfile -File scripts/m2/verify.ps1`

Expected: `PASS M2 gates=...`、固定 commit Evidence、验收记录 validator PASS；Owner decision 仍为 `PENDING`。

- [ ] **Step 5: 在已授权 Pilot 主机单独运行真实 Smoke**

Run: `pwsh -NoProfile -File scripts/m2/jira-pilot-smoke.ps1`

Expected: 最多 20 条只读 Issue、脱敏 summary PASS；若 Jira 不可用则明确 FAIL，不影响 fixture CI 的历史结果，也不生成 Company Ready 声明。

- [ ] **Step 6: 自检、双语同步并提交**

Run: `git diff --check; rg -n 'T[B]D|T[O]DO|implement[ ]later' docs/superpowers/plans; pwsh -NoProfile -File scripts/verify-language-branches.ps1 -Mode Pair -ChineseRef docs/m2-issue-traceability-design -EnglishRef docs/m2-issue-traceability-design-en`

Expected: 无 placeholder、Pair Gate PASS、两个工作树 clean、远端 CI Success。

Commits: `test(m2): add deterministic candidate gate`，随后独立提交 `docs(m2): add owner acceptance candidate`。

## 最终验收与停止条件

实现完成必须提供：固定双语 commit、两个 Success CI runs、两份 M2 Evidence Artifacts、Pair Gate、PostgreSQL schema/constraint report、fixture contract report、已知链/gap/replay report，以及独立的真实 Jira 脱敏 Smoke 结果。Owner 在记录中作出 `ACCEPTED`、`CONDITIONAL` 或 `REJECTED`；Codex 不代替 Owner 决策。

如实施需要 Jira DTO 进入 Core、第二个 Artifact→Release 来源、覆盖旧 Revision/Snapshot、把 UNKNOWN/error/gap 转成 PASS、读取白名单外真实字段，或改变 Fixed/Included/Verified 语义，立即停止并提交 Finding、TDR revision 或 ADR Proposal。
