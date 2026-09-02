# M2.3 Release Issue Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现可证明精确 Sync membership、事务原子性、历史不可变性和稳定 digest 的 M2.3 Release Issue Snapshot。

**Architecture:** 在现有 Kotlin/Spring Boot 模块化单体与 PostgreSQL Authority 内增加 `issue_sync_run_item` Observation Ledger。每个 Sync page 原子保存 Normalized Revision 与 Observation；Snapshot 用例在锁定 Release/Source 后固定最新合格 `SUCCEEDED/FULL` Run，物化非 tombstone Items，并用 RFC 8785 + SHA-256 生成可重放摘要。Jira、当前最新 Revision 和第二存储均不进入 Snapshot 创建路径。

**Tech Stack:** Kotlin/JVM、Spring Boot、Spring JDBC、PostgreSQL 17、Flyway、Jackson、RFC 8785 JCS、JUnit 5、AssertJ、MockMvc、Testcontainers、PowerShell、GitHub Actions。

---

## 实施边界与提交顺序

只修改下列职责范围：

- `V6__release_issue_snapshot_authority.sql`：M2.3 Expand-only Schema、约束和 Trigger。
- Issue Sync application/adapter：固定 result mode/filter，并保存精确 Observation。
- Issue Snapshot application/adapter：canonical model、Repository、事务用例与 REST Controller。
- shared Problem mapping/configuration：固定 409/422 失败语义与 Pilot age policy。
- tests/governance：PostgreSQL、canonicalization、API、replay、安全测试和独立 PENDING 验收候选。

不得修改 V0.1 Core Contract、Artifact→Release Authority、Fixed/Included/Verified 语义、Jira 写权限或 Company 配置；不得开始 M2.4。每个 Task 通过后独立提交，英文分支同步同一非 Markdown 变更与等义文档。

### Task 1: M2.3 PostgreSQL Authority Expansion

**Files:**

- Create: `backend/src/main/resources/db/migration/V6__release_issue_snapshot_authority.sql`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/M2MigrationConstraintTest.kt`

- [ ] **Step 1: 写失败的 Schema 与约束测试**

在 `M2MigrationConstraintTest` 增加以下断言：

```kotlin
@Test
fun `m2 snapshot authority records exact observations and seals terminal runs`() {
    assertThat(tableNames()).contains("issue_sync_run_item")
    assertThat(columnNames("issue_sync_run"))
        .contains("result_set_mode", "filter_reference")
    assertThat(columnNames("release_issue_snapshot")).contains(
        "source_id", "source_watermark", "adapter_version", "mapping_version",
        "canonicalization_version", "age_policy_version",
        "observed_count", "tombstone_count", "selected_count",
    )
    assertThat(uniqueConstraintExists("issue_sync_run_item", listOf("sync_run_id", "source_issue_id"))).isTrue()
    assertThat(triggerNames("issue_sync_run_item")).contains("immutable_issue_sync_run_item")
    assertThat(triggerNames("issue_sync_run")).contains("seal_terminal_issue_sync_run")
}

@Test
fun `observation scope and snapshot v1 metadata fail closed`() {
    seedSnapshotAuthority("scope")
    assertThatThrownBy { insertCrossProjectObservation("scope") }
        .hasRootCauseInstanceOf(java.sql.SQLException::class.java)
    assertThatThrownBy { insertIncompleteV1Snapshot("scope") }
        .hasRootCauseInstanceOf(java.sql.SQLException::class.java)
    assertThatThrownBy { updateTerminalRun("scope") }
        .hasRootCauseInstanceOf(java.sql.SQLException::class.java)
}
```

- [ ] **Step 2: 运行目标测试并确认失败**

Run:

```powershell
./backend/gradlew -p backend test --tests '*M2MigrationConstraintTest'
```

Expected: FAIL，指出 `issue_sync_run_item`、新增列和 Trigger 尚不存在。

- [ ] **Step 3: 编写 forward-only V6 Migration**

Migration 必须包含以下实际结构；历史 Run/Snapshot 列保持 nullable，不能按 timestamp 猜测回填：

```sql
ALTER TABLE issue_sync_run
    ADD COLUMN result_set_mode varchar(10),
    ADD COLUMN filter_reference varchar(255);
ALTER TABLE issue_sync_run
    ADD CONSTRAINT ck_issue_sync_run_result_set_mode
        CHECK (result_set_mode IS NULL OR result_set_mode IN ('FULL', 'DELTA'));

ALTER TABLE normalized_issue
    ADD CONSTRAINT uq_normalized_issue_id_source_project UNIQUE (id, source_id, project_id);

CREATE TABLE issue_sync_run_item (
    sync_run_id varchar(40) NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    project_id varchar(40) NOT NULL,
    source_id varchar(40) NOT NULL,
    issue_id varchar(40) NOT NULL,
    source_issue_id varchar(255) NOT NULL,
    observed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (sync_run_id, ordinal),
    UNIQUE (sync_run_id, issue_id),
    UNIQUE (sync_run_id, source_issue_id),
    FOREIGN KEY (sync_run_id, source_id, project_id)
        REFERENCES issue_sync_run(id, source_id, project_id) ON DELETE RESTRICT,
    FOREIGN KEY (issue_id, source_id, project_id)
        REFERENCES normalized_issue(id, source_id, project_id) ON DELETE RESTRICT
);
CREATE INDEX ix_issue_sync_run_item_issue ON issue_sync_run_item(issue_id);

ALTER TABLE release_issue_snapshot
    ADD COLUMN source_id varchar(40),
    ADD COLUMN source_watermark text,
    ADD COLUMN adapter_version varchar(80),
    ADD COLUMN mapping_version varchar(80),
    ADD COLUMN canonicalization_version varchar(80),
    ADD COLUMN age_policy_version varchar(80),
    ADD COLUMN observed_count integer,
    ADD COLUMN tombstone_count integer,
    ADD COLUMN selected_count integer,
    ADD CONSTRAINT uq_issue_snapshot_run_filter UNIQUE (release_id, sync_run_id, filter_reference),
    ADD CONSTRAINT ck_issue_snapshot_counts CHECK (
        (observed_count IS NULL AND tombstone_count IS NULL AND selected_count IS NULL)
        OR (observed_count >= 0 AND tombstone_count >= 0 AND selected_count >= 0
            AND observed_count = tombstone_count + selected_count)
    );
```

还必须实现三个固定 Trigger：

```sql
CREATE TRIGGER immutable_issue_sync_run_item
    BEFORE UPDATE OR DELETE ON issue_sync_run_item
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();

CREATE FUNCTION seal_terminal_issue_sync_run() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('SUCCEEDED', 'FAILED') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'terminal issue sync run is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER seal_terminal_issue_sync_run
    BEFORE UPDATE ON issue_sync_run
    FOR EACH ROW EXECUTE FUNCTION seal_terminal_issue_sync_run();

CREATE FUNCTION validate_release_issue_snapshot_v1() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.canonicalization_version = 'release-issue-snapshot-jcs/v1' AND
       (NEW.source_id IS NULL OR NEW.source_watermark IS NULL OR NEW.adapter_version IS NULL OR
        NEW.mapping_version IS NULL OR NEW.age_policy_version IS NULL OR NEW.observed_count IS NULL OR
        NEW.tombstone_count IS NULL OR NEW.selected_count IS NULL) THEN
        RAISE EXCEPTION 'release issue snapshot v1 metadata is incomplete';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER validate_release_issue_snapshot_v1
    BEFORE INSERT ON release_issue_snapshot
    FOR EACH ROW EXECUTE FUNCTION validate_release_issue_snapshot_v1();
```

- [ ] **Step 4: 验证 Migration 的空库、升级、重复与约束行为**

Run:

```powershell
./backend/gradlew -p backend test --tests '*M2MigrationConstraintTest' --tests '*MigrationTest'
```

Expected: PASS；历史 nullable 行可读，新 v1 Snapshot 缺 metadata、跨 Project Observation、终态 Run 改写及 Observation UPDATE/DELETE 均由 PostgreSQL 拒绝。

- [ ] **Step 5: 提交数据库 Authority**

```powershell
git add backend/src/main/resources/db/migration/V6__release_issue_snapshot_authority.sql backend/src/test/kotlin/com/ricezhou/vsrqg/shared/M2MigrationConstraintTest.kt
git commit -m "feat(m2): add release issue snapshot authority"
```

### Task 2: Exact Sync Observation Membership

**Files:**

- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueMappingProfile.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueSyncRepository.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/StartIssueSync.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSourceRuntime.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JdbcIssueSyncRepository.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSyncIntegrationTest.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSourceRuntimeRegistryTest.kt`

- [ ] **Step 1: 写失败的 Observation 与 result-mode tests**

增加测试，证明重复 Revision 仍关联到每个 Run，页失败不留下 Observation，ordinal 跨页稳定，Descriptor 是 mode/filter 唯一来源：

```kotlin
@Test
fun `successful full sync records exact ordered observations`() {
    val first = startIssueSync.start(command("observation-a", 'a', "request-a"))
    runIssueSync.run(first.syncRunId, twoPageAdapter())
    val second = startIssueSync.start(command("observation-b", 'b', "request-b"))
    runIssueSync.run(second.syncRunId, twoPageAdapter())

    assertThat(observationSourceIssueIds(first.syncRunId)).containsExactly("FIX-1", "FIX-2")
    assertThat(observationSourceIssueIds(second.syncRunId)).containsExactly("FIX-1", "FIX-2")
    assertThat(count("normalized_issue", "source_id", sourceId)).isEqualTo(2)
    assertThat(syncRunValue(second.syncRunId, "result_set_mode")).isEqualTo("FULL")
    assertThat(syncRunValue(second.syncRunId, "filter_reference")).isEqualTo("all-relevant-issues/v1")
}

@Test
fun `failed page rolls back revisions observations and checkpoint together`() {
    installObservationFailure()
    val run = startIssueSync.start(command("observation-fail", 'c', "request-fail"))
    val result = runIssueSync.run(run.syncRunId, onePageAdapter())
    assertThat(result.status).isEqualTo(IssueSyncStatus.FAILED)
    assertThat(observationSourceIssueIds(run.syncRunId)).isEmpty()
    assertThat(count("normalized_issue", "source_id", sourceId)).isZero()
}
```

- [ ] **Step 2: 运行测试并确认缺少新契约**

```powershell
./backend/gradlew -p backend test --tests '*IssueSyncIntegrationTest' --tests '*IssueSourceRuntimeRegistryTest'
```

Expected: FAIL at compile 或因 Observation 表为空而失败。

- [ ] **Step 3: 固定 Runtime Descriptor 与 Run model**

新增明确枚举，并由 Descriptor 提供固定值：

```kotlin
enum class IssueSyncResultSetMode { FULL, DELTA }

data class IssueSourceRuntimeDescriptor(
    val sourceType: String,
    val adapterId: String,
    val adapterVersion: String,
    val supportedMappingSchemas: Set<String>,
    val supportedTransportRange: String,
    val resultSetMode: IssueSyncResultSetMode,
    val filterReference: String,
)
```

`JIRA_CLI_PILOT_DESCRIPTOR` 使用 `IssueSyncResultSetMode.FULL` 与 `all-relevant-issues/v1`。`StartIssueSync` 在 Source 锁内从 `IssueSourceDescriptorRegistry.require(source.sourceType)` 读取两者并写入 `IssueSyncRunRecord`；Controller、环境变量和数据库 Seed 不得接受这两个版本值。

- [ ] **Step 4: 原子保存 Revision 与 Observation**

把 `insertIssue` 改为返回权威 Revision，并在冲突后读取及逐字段校验：

```kotlin
private fun resolveIssue(run: IssueSyncRunRecord, issue: NormalizedIssue): PersistedIssueRevision {
    insertIssueIfAbsent(run, issue)
    val stored = findIssue(run.sourceId, issue.sourceIssueId, issue.sourceVersion, issue.mappingVersion)
        ?: error("NORMALIZED_ISSUE_RESOLUTION_FAILED")
    check(stored.factDigest == issueDigest(issue)) { "NORMALIZED_ISSUE_INTEGRITY_FAILED" }
    return stored
}
```

在已锁定 Run 的同一 `REQUIRES_NEW` 页事务内，以 `run.issueCount + pageIndex` 分配 ordinal：

```kotlin
page.issues.forEachIndexed { pageIndex, issue ->
    val revision = resolveIssue(run, issue)
    insertObservation(
        syncRunId = run.id,
        ordinal = run.issueCount + pageIndex,
        projectId = run.projectId,
        sourceId = run.sourceId,
        issueId = revision.id,
        sourceIssueId = issue.sourceIssueId,
        observedAt = page.observedAt,
    )
}
```

只有 Observation 全部成功后才更新 page counts/watermark。终态后 Repository 不再允许 `markSucceeded`、`markFailed` 或 page update 改写 Run。

- [ ] **Step 5: 验证并提交 Observation membership**

```powershell
./backend/gradlew -p backend test --tests '*IssueSyncIntegrationTest' --tests '*IssueSourceRuntimeRegistryTest' --tests '*M2MigrationConstraintTest'
git add backend/src/main/kotlin/com/ricezhou/vsrqg/issue backend/src/test/kotlin/com/ricezhou/vsrqg/issue
git commit -m "feat(m2): record exact issue sync observations"
```

Expected: PASS；两个 Run 可共享同一 Normalized Revision，但各自拥有完整、不可变、稳定排序的 Observation membership。

### Task 3: Canonical Snapshot Model and JDBC Repository

**Files:**

- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueSnapshotModels.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueSnapshotRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueSnapshotCanonicalizer.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JcsIssueSnapshotCanonicalizer.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JdbcIssueSnapshotRepository.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSnapshotCanonicalizerTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSnapshotRepositoryIntegrationTest.kt`

- [ ] **Step 1: 写失败的 canonicalization 与 Repository tests**

使用固定 Instant 和合成 Issue，断言输入顺序不影响输出、UTC 微秒格式稳定、tombstone 不进入 items、三次 bytes/digest 相同：

```kotlin
@Test
fun `canonical snapshot is byte stable and excludes creation metadata`() {
    val first = canonicalizer.canonicalize(candidate(items = listOf(issue("B"), issue("A"))))
    val second = canonicalizer.canonicalize(candidate(items = listOf(issue("A"), issue("B"))))
    val third = canonicalizer.canonicalize(candidate(items = listOf(issue("B"), issue("A"))))
    assertThat(first.bytes).isEqualTo(second.bytes).isEqualTo(third.bytes)
    assertThat(first.digest).isEqualTo(second.digest).isEqualTo(third.digest)
    assertThat(first.digest).matches("sha256:[0-9a-f]{64}")
}
```

Repository Integration Test 必须证明 `lockContext`、最新成功 FULL Run、相同 logical Snapshot、稳定 next version、ordered Observations、原子 Header/Items 和 read-back digest。

- [ ] **Step 2: 运行并确认类型/Repository 缺失**

```powershell
./backend/gradlew -p backend test --tests '*IssueSnapshotCanonicalizerTest' --tests '*IssueSnapshotRepositoryIntegrationTest'
```

Expected: FAIL at compile，因为 Snapshot model、canonicalizer 与 Repository 尚不存在。

- [ ] **Step 3: 实现专用 application contract**

核心类型保持在 Issue module implementation，不加入 V0.1 Core Contract：

```kotlin
data class SnapshotObservation(
    val issueId: String,
    val sourceIssueId: String,
    val title: String,
    val severity: IssueSeverity,
    val status: IssueStatus,
    val rawStatusToken: String?,
    val sourceVersion: String,
    val sourceReference: String,
    val observedAt: Instant,
    val mappingVersion: String,
    val tombstone: Boolean,
    val factDigest: String,
)

data class IssueSnapshotCandidate(
    val projectId: String,
    val releaseId: String,
    val snapshotVersion: Int,
    val syncRunId: String,
    val sourceId: String,
    val sourceWatermark: String,
    val adapterVersion: String,
    val mappingVersion: String,
    val filterReference: String,
    val agePolicyVersion: String,
    val observations: List<SnapshotObservation>,
)

data class CanonicalIssueSnapshot(val bytes: ByteArray, val digest: String)

fun interface IssueSnapshotCanonicalizer {
    fun canonicalize(candidate: IssueSnapshotCandidate): CanonicalIssueSnapshot
}
```

`IssueSnapshotRepository` 只暴露 `findContext`、`lockContext`、`findLatestSuccessfulFullRun`、`findExisting`、`nextSnapshotVersion`、`loadObservations`、`insert` 与 `read`；Controller 不接触 JDBC。

- [ ] **Step 4: 实现 RFC 8785 canonical bytes**

复用 `org.erdtman.jcs.JsonCanonicalizer`，固定常量并拒绝未知格式：

```kotlin
internal const val SNAPSHOT_SCHEMA_VERSION = "release-issue-snapshot/v1"
internal const val SNAPSHOT_CANONICALIZATION_VERSION = "release-issue-snapshot-jcs/v1"
internal const val SNAPSHOT_AGE_POLICY_VERSION = "issue-snapshot-age/v1"

private fun digest(canonicalBytes: ByteArray): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(canonicalBytes)
    return "sha256:" + hash.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
```

Canonicalizer 必须先按 `(sourceId, sourceIssueId, issueId)` 排序，过滤 tombstone，再创建 ordinal；使用 UTC RFC 3339 固定微秒格式。JSON 不包含 Snapshot ID、actor、request time、Idempotency Key、事务 ID 或 `created_at`。

- [ ] **Step 5: 实现参数化 JDBC Repository**

`lockContext` 依次 `SELECT ... FROM release_record ... FOR UPDATE`、`SELECT ... FROM issue_source ... FOR UPDATE`；`findLatestSuccessfulFullRun` 使用：

```sql
SELECT id, project_id, source_id, source_watermark, adapter_version, mapping_version,
       result_set_mode, filter_reference, issue_count, completed_at
FROM issue_sync_run
WHERE source_id = :sourceId AND project_id = :projectId
  AND status = 'SUCCEEDED' AND result_set_mode = 'FULL'
ORDER BY completed_at DESC, id DESC
LIMIT 1
```

选中后不回退旧 Run。`loadObservations` 只能从 `issue_sync_run_item JOIN normalized_issue` 读取并验证 source/project/count；`insert` 在当前事务写 Header 与全部 Items。所有 SQL 参数化，动态 column/table 名不得来自请求。

- [ ] **Step 6: 验证并提交 canonical authority**

```powershell
./backend/gradlew -p backend test --tests '*IssueSnapshotCanonicalizerTest' --tests '*IssueSnapshotRepositoryIntegrationTest'
git add backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueSnapshotModels.kt backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueSnapshotRepository.kt backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueSnapshotCanonicalizer.kt backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JcsIssueSnapshotCanonicalizer.kt backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JdbcIssueSnapshotRepository.kt backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSnapshotCanonicalizerTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSnapshotRepositoryIntegrationTest.kt
git commit -m "feat(m2): materialize canonical issue snapshots"
```

### Task 4: Transactional Use Case, API, and Failure Semantics

**Files:**

- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/CreateIssueSnapshot.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSnapshotController.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSnapshotConfiguration.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/SafeValidationFailure.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/problem/ProblemHandler.kt`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSnapshotIntegrationTest.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/M2ApiContractTest.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/SecurityAcceptanceTest.kt`

- [ ] **Step 1: 写失败的 Use Case/API tests**

覆盖权限、404 隐藏、Locked Manifest、latest FULL Run、DELTA/FAILED、`PT24H` 边界、未来时间、空 Snapshot、未知 status 保留、幂等重放、Key 冲突、不同 Key 并发收敛、Audit/Outbox/Item 失败回滚：

```kotlin
@Test
fun `snapshot API materializes one immutable release input`() {
    val response = postSnapshot(releaseId, sourceId, "snapshot-key").andExpect {
        status { isCreated() }
        jsonPath("$.snapshotId") { isNotEmpty() }
        jsonPath("$.contentDigest") { value(org.hamcrest.Matchers.matchesPattern("sha256:[0-9a-f]{64}")) }
        jsonPath("$.selectedCount") { value(2) }
    }.andReturn().response.contentAsString
    val replay = postSnapshot(releaseId, sourceId, "snapshot-key").andReturn().response.contentAsString
    assertThat(replay).isEqualTo(response)
    assertThat(snapshotCount(releaseId)).isOne()
}

@Test
fun `new mapping revision and sync cannot alter historical snapshot`() {
    val before = createAndReadSnapshot()
    seedLaterMappingRevisionAndSuccessfulSync()
    val after = readSnapshotDirectly(before.snapshotId)
    assertThat(after.canonicalBytes).isEqualTo(before.canonicalBytes)
    assertThat(after.contentDigest).isEqualTo(before.contentDigest)
}
```

- [ ] **Step 2: 运行并确认 Endpoint/Use Case 缺失**

```powershell
./backend/gradlew -p backend test --tests '*IssueSnapshotIntegrationTest' --tests '*M2ApiContractTest' --tests '*SecurityAcceptanceTest'
```

Expected: FAIL at compile 或 404，因为 `CreateIssueSnapshot` 与 Controller 尚不存在。

- [ ] **Step 3: 实现配置与固定失败类型**

新增配置：

```yaml
vsrqg:
  issue:
    snapshot:
      enabled: ${VSRQG_ISSUE_SNAPSHOT_ENABLED:true}
      max-sync-age: ${VSRQG_ISSUE_SNAPSHOT_MAX_SYNC_AGE:PT24H}
```

`IssueSnapshotProperties` 在启动时要求 `maxSyncAge > Duration.ZERO`。新增 `SafeValidationDiagnostic.ISSUE_SNAPSHOT_INVALID`，只允许 `SYNC_RUN_STALE`、`SYNC_OBSERVATION_INTEGRITY_FAILED`、`SNAPSHOT_INTEGRITY_FAILED`；`ProblemHandler` 统一映射为 422，响应不包含 title、URL、JQL、path、stack trace 或原始 payload。Locked Manifest 与 eligible Run 缺失使用固定 409 `ResourceConflict`。

- [ ] **Step 4: 实现单事务 CreateIssueSnapshot**

命令与结果固定为：

```kotlin
data class CreateIssueSnapshotCommand(
    val principal: Principal,
    val releaseId: String,
    val sourceId: String,
    val idempotencyKey: String,
    val requestDigest: String,
    val requestId: String,
)

data class CreateIssueSnapshotResult(
    val snapshotId: String,
    val releaseId: String,
    val syncRunId: String,
    val snapshotVersion: Int,
    val contentDigest: String,
    val selectedCount: Int,
    val createdAt: Instant,
)
```

`@Transactional create()` 顺序必须是：先以只读 context 获取 Project 并执行 `Permission.ISSUE_SNAPSHOT`；进入 `IdempotentExecutor`；锁 Release/Source；再次验证 Project；要求 `lockedManifestId != null`；固定最新成功 FULL Run；校验 `completedAt <= now` 且 age 不超过 policy；不合格即失败且不回退；加载并验证 Observation/count/fact digest；查找同 logical Snapshot；分配 version；canonicalize；写 Header/Items、Audit、Outbox；read-back 复算 digest；返回结果。

Audit/Outbox payload 只允许：

```kotlin
objectMapper.createObjectNode()
    .put("schemaVersion", 1)
    .put("snapshotId", snapshotId)
    .put("releaseId", releaseId)
    .put("sourceId", sourceId)
    .put("syncRunId", syncRun.id)
    .put("snapshotVersion", snapshotVersion)
    .put("selectedCount", selectedCount)
    .put("contentDigest", canonical.digest)
```

- [ ] **Step 5: 实现兼容现有 OpenAPI 的 Controller**

请求体必须精确使用既有 `IdentifierInput.sourceId`，不得新增调用方提交的 Sync/Mapping/Adapter/filter version：

```kotlin
data class IdentifierInput(@field:Size(min = 1, max = 40) val sourceId: String)

@PostMapping("/api/v1/releases/{releaseId}/issue-snapshots")
@PreAuthorize("hasAuthority('SCOPE_issue:snapshot')")
fun create(
    @AuthenticationPrincipal jwt: Jwt,
    @PathVariable @Size(min = 1, max = 40) releaseId: String,
    @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) idempotencyKey: String,
    @Valid @RequestBody body: IdentifierInput,
    request: HttpServletRequest,
): ResponseEntity<CreateIssueSnapshotResult> = ResponseEntity.status(HttpStatus.CREATED).body(
    useCase.create(command(jwt, releaseId, body.sourceId, idempotencyKey, request)),
)
```

- [ ] **Step 6: 验证并提交事务 API**

```powershell
./backend/gradlew -p backend test --tests '*IssueSnapshotIntegrationTest' --tests '*M2ApiContractTest' --tests '*SecurityAcceptanceTest'
git add backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/CreateIssueSnapshot.kt backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSnapshotController.kt backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSnapshotConfiguration.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/SafeValidationFailure.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/problem/ProblemHandler.kt backend/src/main/resources/application.yml backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSnapshotIntegrationTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/M2ApiContractTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/SecurityAcceptanceTest.kt
git commit -m "feat(m2): create immutable release issue snapshots"
```

Expected: PASS；任何失败均不留下 Header、Item、Audit、Outbox 或 Idempotency response 的部分写入。

### Task 5: Replay Evidence, Regression Gate, and Acceptance Candidate

**Files:**

- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSnapshotReplayTest.kt`
- Create: `scripts/m2/verify-issue-snapshot.ps1`
- Create: `scripts/tests/m2-issue-snapshot-gates.tests.ps1`
- Create: `docs/m2/issue-snapshot.md`
- Create: `docs/governance/acceptance/records/2026-09-02-m2-3-owner-gate-001.md`

- [ ] **Step 1: 写失败的 replay 与 Gate orchestration tests**

`IssueSnapshotReplayTest` 必须在真实 PostgreSQL 中保存 baseline bytes/digest，然后依次插入新 Normalized Revision、激活新 Mapping、完成新 FULL Sync，三次读取旧 Snapshot 并逐字节比较。Gate test 断言任一命令失败即总状态失败，Evidence summary 仍输出 `FAILED`，且验收记录初始状态只能是 `PENDING`。

```powershell
Describe 'M2.3 Issue Snapshot Gate' {
    It 'fails closed and preserves the failing check name' {
        $result = & $scriptUnderTest -InjectFailure 'snapshot-replay'
        $LASTEXITCODE | Should -Not -Be 0
        ($result -join "`n") | Should -Match 'FAILED snapshot-replay'
    }
}
```

- [ ] **Step 2: 实现最小 M2.3 Gate**

`scripts/m2/verify-issue-snapshot.ps1` 固定顺序执行：

```powershell
$checks = @(
    @{ Name = 'migration'; Command = @('./backend/gradlew', '-p', 'backend', 'test', '--tests', '*M2MigrationConstraintTest') },
    @{ Name = 'sync-observation'; Command = @('./backend/gradlew', '-p', 'backend', 'test', '--tests', '*IssueSyncIntegrationTest') },
    @{ Name = 'snapshot-canonical'; Command = @('./backend/gradlew', '-p', 'backend', 'test', '--tests', '*IssueSnapshotCanonicalizerTest') },
    @{ Name = 'snapshot-integration'; Command = @('./backend/gradlew', '-p', 'backend', 'test', '--tests', '*IssueSnapshotIntegrationTest') },
    @{ Name = 'snapshot-replay'; Command = @('./backend/gradlew', '-p', 'backend', 'test', '--tests', '*IssueSnapshotReplayTest') },
    @{ Name = 'contracts'; Command = @('pnpm', 'run', 'test:contracts') },
    @{ Name = 'acceptance'; Command = @('pnpm', 'run', 'verify:acceptance') }
)
```

脚本输出只含 commit、check name、status、test counts 与固定 diagnostics；不得输出 Issue title、raw token、source reference、JQL、URL、环境变量值、绝对路径或 Credential。CI 继续使用既有 `M1 Backend` Workflow，不增加 Jira secret 或外部写权限。

- [ ] **Step 3: 创建运维说明与 PENDING 验收候选**

`docs/m2/issue-snapshot.md` 记录 Endpoint、权限、`PT24H` Pilot policy、`FULL` 限制、固定 diagnostics、replay 方法与关闭写入口的恢复步骤。验收记录 `M2-3-OWNER-GATE-001` 必须固定实现 commit/配对 commit、两条 CI Run、PostgreSQL/replay/security reports，未知 Evidence 写 `UNKNOWN`，Owner/decisionAt/status 初始为 `PENDING`。

- [ ] **Step 4: 运行完整回归与安全扫描**

```powershell
./backend/gradlew -p backend test
pnpm run test:contracts
pnpm run test:acceptance
pnpm run verify:acceptance
pwsh -NoProfile -File scripts/tests/m2-issue-snapshot-gates.tests.ps1
pwsh -NoProfile -File scripts/m2/verify-issue-snapshot.ps1
rg -n -i 'github_pat_|ghp_|Bearer\s+[A-Za-z0-9._-]+|C:\\Users\\|S-1-5-' backend scripts docs/m2 docs/governance/acceptance/records/2026-09-02-m2-3-owner-gate-001.md
git diff --check
```

Expected: 全部 PASS，敏感信息扫描无匹配；本机没有 Docker 时 PostgreSQL 检查必须明确报告未执行，并由绑定 exact commit 的 GitHub Linux/Docker CI 补足，不能伪造本地 PASS。

- [ ] **Step 5: 提交 Gate 与验收候选**

```powershell
git add backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSnapshotReplayTest.kt scripts/m2/verify-issue-snapshot.ps1 scripts/tests/m2-issue-snapshot-gates.tests.ps1 docs/m2/issue-snapshot.md docs/governance/acceptance/records/2026-09-02-m2-3-owner-gate-001.md
git commit -m "test(m2): gate release issue snapshot replay"
```

- [ ] **Step 6: 双语同步、Pair Gate 与远端 Evidence**

把所有非 Markdown 文件以相同 bytes 同步至英文分支，只翻译 Markdown prose。分别提交后运行：

```powershell
pwsh -NoProfile -File scripts/verify-language-branches.ps1 -Mode Pair -ChineseRef docs/m2-issue-traceability-design -EnglishRef docs/m2-issue-traceability-design-en
git status --short --branch
```

Expected: Pair Gate PASS、两个 worktree clean；推送两个分支并等待绑定各自 exact HEAD 的 GitHub Actions `success`。不得 merge、Tag、release 或 deploy。

## Plan 自审清单

- Spec coverage：Task 1 覆盖 Schema/immutability，Task 2 覆盖精确 membership，Task 3 覆盖 canonical materialization，Task 4 覆盖事务/API/error/security，Task 5 覆盖 replay、Gate 与验收记录。
- Type consistency：统一使用 `IssueSyncResultSetMode`、`IssueSnapshotCandidate`、`SnapshotObservation`、`CanonicalIssueSnapshot`、`CreateIssueSnapshotCommand` 与 `CreateIssueSnapshotResult`。
- Authority consistency：Jira 不在 Snapshot path；Artifact→Release 仍由 Locked Manifest；Observer/Materialized Snapshot 均只有 PostgreSQL Authority。
- Scope consistency：无 M2.4 Edge、Fixed/Included/Verified、Company、真实 Jira、merge/Tag/release/deploy。
- Placeholder scan：计划不得包含未定义占位步骤；所有失败预期、命令、文件与提交均已明确。

## 实施授权 Gate

本计划的创建由已批准的 `M2-KD-2026-09-02-01` Written Spec Review 授权；它本身不授权生产代码或 Migration。开始 Task 1 前必须取得 Project Owner 独立指令：

```text
批准采用 Subagent-Driven 执行 M2.3 Release Issue Snapshot
```

授权范围仅为本计划 Task 1～Task 5 的 Pilot 实现、测试、双语配对提交与 CI；不含真实 Jira 查询/写入、Company、M2.4、merge、Tag、release 或 production deployment。
