# M2.3 Release Issue Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement an M2.3 Release Issue Snapshot with provable exact Sync membership, transaction atomicity, historical immutability, and a stable digest.

**Architecture:** Add an `issue_sync_run_item` Observation Ledger inside the existing Kotlin/Spring Boot modular monolith and PostgreSQL Authority. Each Sync page atomically stores Normalized Revisions and Observations. After locking Release/Source, the Snapshot use case pins the latest eligible `SUCCEEDED/FULL` Run, materializes non-tombstone Items, and produces a replayable digest with RFC 8785 + SHA-256. Jira, current-latest Revisions, and secondary storage do not enter the Snapshot creation path.

**Tech Stack:** Kotlin/JVM, Spring Boot, Spring JDBC, PostgreSQL 17, Flyway, Jackson, RFC 8785 JCS, JUnit 5, AssertJ, MockMvc, Testcontainers, PowerShell, and GitHub Actions.

---

## Implementation Boundary and Commit Order

Only the following responsibilities may change:

- `V6__release_issue_snapshot_authority.sql`: M2.3 Expand-only Schema, constraints, and Triggers.
- Issue Sync application/adapter: pin result mode/filter and persist exact Observations.
- Issue Snapshot application/adapter: canonical model, Repository, transactional use case, and REST Controller.
- shared Problem mapping/configuration: fixed 409/422 failure semantics and Pilot age policy.
- tests/governance: PostgreSQL, canonicalization, API, replay, security tests, and an independent PENDING acceptance candidate.

Do not modify the V0.1 Core Contract, Artifact-to-Release Authority, Fixed/Included/Verified semantics, Jira write permission, or Company configuration, and do not begin M2.4. Commit each Task independently after it passes. Synchronize identical non-Markdown changes and semantically equivalent documentation to the English branch.

### Task 1: M2.3 PostgreSQL Authority Expansion

**Files:**

- Create: `backend/src/main/resources/db/migration/V6__release_issue_snapshot_authority.sql`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/M2MigrationConstraintTest.kt`

- [ ] **Step 1: Write failing Schema and constraint tests**

Add these assertions to `M2MigrationConstraintTest`:

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

- [ ] **Step 2: Run the target test and confirm failure**

Run:

```powershell
./backend/gradlew -p backend test --tests '*M2MigrationConstraintTest'
```

Expected: FAIL because `issue_sync_run_item`, the new columns, and the Triggers do not exist.

- [ ] **Step 3: Write the forward-only V6 Migration**

The Migration must contain this concrete structure. Historical Run/Snapshot columns remain nullable and must not be inferred from timestamps:

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

It must also implement these three fixed Triggers:

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

- [ ] **Step 4: Verify clean, upgrade, repeat, and constraint Migration behavior**

Run:

```powershell
./backend/gradlew -p backend test --tests '*M2MigrationConstraintTest' --tests '*MigrationTest'
```

Expected: PASS. Historical nullable rows remain readable, while PostgreSQL rejects missing metadata on new v1 Snapshots, cross-Project Observations, terminal Run mutation, and Observation UPDATE/DELETE.

- [ ] **Step 5: Commit database Authority**

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

- [ ] **Step 1: Write failing Observation and result-mode tests**

Add tests proving that a reused Revision is still associated with every Run, a page failure leaves no Observation, ordinals remain stable across pages, and the Descriptor is the only source of mode/filter:

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

- [ ] **Step 2: Run tests and confirm the new contract is missing**

```powershell
./backend/gradlew -p backend test --tests '*IssueSyncIntegrationTest' --tests '*IssueSourceRuntimeRegistryTest'
```

Expected: FAIL at compile or because the Observation table is empty.

- [ ] **Step 3: Pin the Runtime Descriptor and Run model**

Add an explicit enum and fixed Descriptor fields:

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

`JIRA_CLI_PILOT_DESCRIPTOR` uses `IssueSyncResultSetMode.FULL` and `all-relevant-issues/v1`. Under the Source lock, `StartIssueSync` obtains both from `IssueSourceDescriptorRegistry.require(source.sourceType)` and stores them in `IssueSyncRunRecord`. Controllers, environment variables, and database Seeds must not accept either version value.

- [ ] **Step 4: Persist Revision and Observation atomically**

Change `insertIssue` to return the authoritative Revision and read and compare every field after a conflict:

```kotlin
private fun resolveIssue(run: IssueSyncRunRecord, issue: NormalizedIssue): PersistedIssueRevision {
    insertIssueIfAbsent(run, issue)
    val stored = findIssue(run.sourceId, issue.sourceIssueId, issue.sourceVersion, issue.mappingVersion)
        ?: error("NORMALIZED_ISSUE_RESOLUTION_FAILED")
    check(stored.factDigest == issueDigest(issue)) { "NORMALIZED_ISSUE_INTEGRITY_FAILED" }
    return stored
}
```

Inside the same locked-Run `REQUIRES_NEW` page transaction, allocate ordinals as `run.issueCount + pageIndex`:

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

Update page counts/watermark only after all Observations succeed. After a terminal state, Repository methods must not allow `markSucceeded`, `markFailed`, or page updates to mutate the Run.

- [ ] **Step 5: Verify and commit Observation membership**

```powershell
./backend/gradlew -p backend test --tests '*IssueSyncIntegrationTest' --tests '*IssueSourceRuntimeRegistryTest' --tests '*M2MigrationConstraintTest'
git add backend/src/main/kotlin/com/ricezhou/vsrqg/issue backend/src/test/kotlin/com/ricezhou/vsrqg/issue
git commit -m "feat(m2): record exact issue sync observations"
```

Expected: PASS. Two Runs may share one Normalized Revision, while each owns a complete, immutable, stably ordered Observation membership.

### Task 3: Canonical Snapshot Model and JDBC Repository

**Files:**

- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueSnapshotModels.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueSnapshotRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueSnapshotCanonicalizer.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JcsIssueSnapshotCanonicalizer.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JdbcIssueSnapshotRepository.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSnapshotCanonicalizerTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSnapshotRepositoryIntegrationTest.kt`

- [ ] **Step 1: Write failing canonicalization and Repository tests**

Use a fixed Instant and synthetic Issues to prove input order independence, stable UTC microsecond formatting, tombstone exclusion, and three identical bytes/digests:

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

The Repository Integration Test must prove `lockContext`, latest successful FULL Run selection, same logical Snapshot lookup, stable next version, ordered Observations, atomic Header/Items, and read-back digest.

- [ ] **Step 2: Run and confirm missing types/Repository**

```powershell
./backend/gradlew -p backend test --tests '*IssueSnapshotCanonicalizerTest' --tests '*IssueSnapshotRepositoryIntegrationTest'
```

Expected: FAIL at compile because the Snapshot model, canonicalizer, and Repository do not exist.

- [ ] **Step 3: Implement the dedicated application contract**

Keep the core types inside the Issue module implementation; do not add them to the V0.1 Core Contract:

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

`IssueSnapshotRepository` exposes only `findContext`, `lockContext`, `findLatestSuccessfulFullRun`, `findExisting`, `nextSnapshotVersion`, `loadObservations`, `insert`, and `read`. The Controller does not access JDBC.

- [ ] **Step 4: Implement RFC 8785 canonical bytes**

Reuse `org.erdtman.jcs.JsonCanonicalizer`, pin constants, and reject unknown formats:

```kotlin
internal const val SNAPSHOT_SCHEMA_VERSION = "release-issue-snapshot/v1"
internal const val SNAPSHOT_CANONICALIZATION_VERSION = "release-issue-snapshot-jcs/v1"
internal const val SNAPSHOT_AGE_POLICY_VERSION = "issue-snapshot-age/v1"

private fun digest(canonicalBytes: ByteArray): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(canonicalBytes)
    return "sha256:" + hash.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
```

The canonicalizer sorts by `(sourceId, sourceIssueId, issueId)`, filters tombstones, then creates ordinals. Use UTC RFC 3339 with fixed microsecond precision. JSON excludes Snapshot ID, actor, request time, Idempotency Key, transaction ID, and `created_at`.

- [ ] **Step 5: Implement the parameterized JDBC Repository**

`lockContext` runs `SELECT ... FROM release_record ... FOR UPDATE` followed by `SELECT ... FROM issue_source ... FOR UPDATE`. `findLatestSuccessfulFullRun` uses:

```sql
SELECT id, project_id, source_id, source_watermark, adapter_version, mapping_version,
       result_set_mode, filter_reference, issue_count, completed_at
FROM issue_sync_run
WHERE source_id = :sourceId AND project_id = :projectId
  AND status = 'SUCCEEDED' AND result_set_mode = 'FULL'
ORDER BY completed_at DESC, id DESC
LIMIT 1
```

Do not fall back after selection. `loadObservations` reads only through `issue_sync_run_item JOIN normalized_issue` and validates source/project/count. `insert` writes the Header and all Items in the current transaction. Parameterize all SQL; no dynamic column/table name may come from a request.

- [ ] **Step 6: Verify and commit canonical authority**

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

- [ ] **Step 1: Write failing Use Case/API tests**

Cover authorization, hidden 404, Locked Manifest, latest FULL Run, DELTA/FAILED, `PT24H` boundary, future time, empty Snapshot, preserved unknown status, idempotent replay, Key conflict, convergence of different concurrent Keys, and rollback on Audit/Outbox/Item failure:

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

- [ ] **Step 2: Run and confirm the Endpoint/Use Case is missing**

```powershell
./backend/gradlew -p backend test --tests '*IssueSnapshotIntegrationTest' --tests '*M2ApiContractTest' --tests '*SecurityAcceptanceTest'
```

Expected: FAIL at compile or with 404 because `CreateIssueSnapshot` and the Controller do not exist.

- [ ] **Step 3: Implement configuration and fixed failure types**

Add configuration:

```yaml
vsrqg:
  issue:
    snapshot:
      enabled: ${VSRQG_ISSUE_SNAPSHOT_ENABLED:true}
      max-sync-age: ${VSRQG_ISSUE_SNAPSHOT_MAX_SYNC_AGE:PT24H}
```

`IssueSnapshotProperties` requires `maxSyncAge > Duration.ZERO` at startup. Add `SafeValidationDiagnostic.ISSUE_SNAPSHOT_INVALID` allowing only `SYNC_RUN_STALE`, `SYNC_OBSERVATION_INTEGRITY_FAILED`, and `SNAPSHOT_INTEGRITY_FAILED`. `ProblemHandler` maps it to 422 without returning title, URL, JQL, path, stack trace, or raw payload. Missing Locked Manifest and eligible Run use fixed 409 `ResourceConflict` values.

- [ ] **Step 4: Implement the single-transaction CreateIssueSnapshot**

Pin the command and result types:

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

The `@Transactional create()` order is: obtain Project through read-only context and require `Permission.ISSUE_SNAPSHOT`; enter `IdempotentExecutor`; lock Release/Source; revalidate Project; require `lockedManifestId != null`; pin latest successful FULL Run; require `completedAt <= now` and age within policy; fail without fallback if ineligible; load and validate Observation/count/fact digest; find same logical Snapshot; allocate version; canonicalize; write Header/Items, Audit, and Outbox; recompute digest after read-back; return the result.

Audit/Outbox payload permits only:

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

- [ ] **Step 5: Implement a Controller compatible with the current OpenAPI**

The request body must use the existing `IdentifierInput.sourceId` exactly. Do not add caller-supplied Sync/Mapping/Adapter/filter versions:

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

- [ ] **Step 6: Verify and commit the transactional API**

```powershell
./backend/gradlew -p backend test --tests '*IssueSnapshotIntegrationTest' --tests '*M2ApiContractTest' --tests '*SecurityAcceptanceTest'
git add backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/CreateIssueSnapshot.kt backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSnapshotController.kt backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSnapshotConfiguration.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/SafeValidationFailure.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/problem/ProblemHandler.kt backend/src/main/resources/application.yml backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSnapshotIntegrationTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/M2ApiContractTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/SecurityAcceptanceTest.kt
git commit -m "feat(m2): create immutable release issue snapshots"
```

Expected: PASS. No failure leaves a partial Header, Item, Audit, Outbox, or Idempotency response.

### Task 5: Replay Evidence, Regression Gate, and Acceptance Candidate

**Files:**

- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSnapshotReplayTest.kt`
- Create: `scripts/m2/verify-issue-snapshot.ps1`
- Create: `scripts/tests/m2-issue-snapshot-gates.tests.ps1`
- Create: `docs/m2/issue-snapshot.md`
- Create: `docs/governance/acceptance/records/2026-09-02-m2-3-owner-gate-001.md`

- [ ] **Step 1: Write failing replay and Gate orchestration tests**

`IssueSnapshotReplayTest` stores baseline bytes/digest in real PostgreSQL, then inserts a new Normalized Revision, activates a new Mapping, completes a new FULL Sync, and reads the old Snapshot three times for byte-for-byte comparison. The Gate test proves any failed command makes the total status fail, the Evidence summary still emits `FAILED`, and the acceptance record initially permits only `PENDING`.

```powershell
Describe 'M2.3 Issue Snapshot Gate' {
    It 'fails closed and preserves the failing check name' {
        $result = & $scriptUnderTest -InjectFailure 'snapshot-replay'
        $LASTEXITCODE | Should -Not -Be 0
        ($result -join "`n") | Should -Match 'FAILED snapshot-replay'
    }
}
```

- [ ] **Step 2: Implement the minimal M2.3 Gate**

`scripts/m2/verify-issue-snapshot.ps1` runs this fixed order:

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

Output includes only commit, check name, status, test counts, and fixed diagnostics. It must not include Issue titles, raw tokens, source references, JQL, URLs, environment-variable values, absolute paths, or Credentials. CI continues using the existing `M1 Backend` Workflow without a Jira secret or external write permission.

- [ ] **Step 3: Create operations guidance and a PENDING acceptance candidate**

`docs/m2/issue-snapshot.md` records the Endpoint, permission, `PT24H` Pilot policy, `FULL` restriction, fixed diagnostics, replay procedure, and write-disable recovery step. Acceptance record `M2-3-OWNER-GATE-001` fixes implementation/paired commits, two CI Runs, and PostgreSQL/replay/security reports. Unknown Evidence is `UNKNOWN`, and Owner/decisionAt/status initially remain `PENDING`.

- [ ] **Step 4: Run full regression and security scans**

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

Expected: all PASS and no sensitive scan match. If local Docker is unavailable, PostgreSQL checks explicitly report not executed and exact-commit GitHub Linux/Docker CI supplies the missing Evidence; never fabricate a local PASS.

- [ ] **Step 5: Commit the Gate and acceptance candidate**

```powershell
git add backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSnapshotReplayTest.kt scripts/m2/verify-issue-snapshot.ps1 scripts/tests/m2-issue-snapshot-gates.tests.ps1 docs/m2/issue-snapshot.md docs/governance/acceptance/records/2026-09-02-m2-3-owner-gate-001.md
git commit -m "test(m2): gate release issue snapshot replay"
```

- [ ] **Step 6: Synchronize bilingual branches, run Pair Gate, and bind remote Evidence**

Copy every non-Markdown file to the English branch as identical bytes and translate only Markdown prose. After separate commits, run:

```powershell
pwsh -NoProfile -File scripts/verify-language-branches.ps1 -Mode Pair -ChineseRef docs/m2-issue-traceability-design -EnglishRef docs/m2-issue-traceability-design-en
git status --short --branch
```

Expected: Pair Gate PASS and both worktrees clean. Push both branches and wait for GitHub Actions bound to each exact HEAD to conclude `success`. Do not merge, Tag, release, or deploy.

## Plan Self-review Checklist

- Spec coverage: Task 1 covers Schema/immutability, Task 2 exact membership, Task 3 canonical materialization, Task 4 transaction/API/error/security, and Task 5 replay, Gate, and acceptance record.
- Type consistency: consistently use `IssueSyncResultSetMode`, `IssueSnapshotCandidate`, `SnapshotObservation`, `CanonicalIssueSnapshot`, `CreateIssueSnapshotCommand`, and `CreateIssueSnapshotResult`.
- Authority consistency: Jira is absent from the Snapshot path; Artifact-to-Release remains Locked Manifest-derived; Observation/Materialized Snapshot use only PostgreSQL Authority.
- Scope consistency: no M2.4 Edge, Fixed/Included/Verified, Company, real Jira, merge/Tag/release/deploy work.
- Placeholder scan: the plan contains no undefined placeholder step; failure expectations, commands, files, and commits are explicit.

## Implementation Authorization Gate

Creation of this plan is authorized by the approved `M2-KD-2026-09-02-01` Written Spec Review. The plan does not itself authorize production code or Migration. Before Task 1 starts, obtain this independent Project Owner instruction:

```text
批准采用 Subagent-Driven 执行 M2.3 Release Issue Snapshot
```

Authorization covers only Pilot implementation, testing, paired bilingual commits, and CI for Tasks 1 through 5. It excludes real Jira queries/writes, Company, M2.4, merge, Tag, release, and production deployment.
