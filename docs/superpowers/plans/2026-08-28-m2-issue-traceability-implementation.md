# M2 Issue Snapshot and Traceability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement repeatable Issue synchronization and Release Issue Snapshots, then prove Fixed, Included, and the Verified gap through strongly typed, snapshot-based, replayable Traceability without changing the frozen V0.1 architecture.

**Architecture:** Add `issue` and `traceability` modules to the existing Kotlin/Spring Boot modular monolith. Normalize every external Issue through `IssueSourcePort`, and retain historical facts as append-only PostgreSQL Revisions and Snapshots. CI uses synthetic Fixtures; real Jira runs only as a read-only Smoke over at most 20 records under explicit `PILOT` configuration. Artifact-to-Release derives only from the Locked Manifest.

**Tech Stack:** Kotlin 2.3, Java 21, Spring Boot 4, Spring JDBC/Transaction, PostgreSQL 17, Flyway, Testcontainers, JUnit 5, AssertJ, ArchUnit, OpenAPI 3.1, PowerShell, and Jira CLI.

---

## Delivery Boundary and Order

This plan is based on `M2-KD-2026-08-28-01` and Accepted `TDR-014`. Production-code execution still requires separate authorization. Jira writes, Company Profile enablement, merge, Tag, release, and production deployment are excluded. Execute M2.0 through M2.6 in order; each Task must pass its target tests and be committed before the next starts.

### Task 1: M2.0 Modules, Permissions, and API Contract

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/PackageMarker.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/PackageMarker.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/ArchitectureTest.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/access/domain/Permission.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/access/PermissionMatrixTest.kt`
- Modify: `contracts/openapi/v0.2/openapi.json`
- Modify: `contracts/openapi/v0.2/compatibility-baseline.json`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/M2ApiContractTest.kt`

- [ ] **Step 1: Write failing architecture and permission tests**

In `ArchitectureTest`, add `issue` and `traceability` to required modules and adapter isolation. Assert exactly that `issue:sync`/`issue:snapshot` permit ENGINEER, RELEASE_MANAGER, ADMINISTRATOR; `issue:read`/`traceability:read` permit every role; `traceability:verify` permits ENGINEER, QUALITY_OWNER, ADMINISTRATOR; and `traceability:ingest` is a dedicated service-identity scope.

- [ ] **Step 2: Run tests and verify failure**

Run: `./backend/gradlew -p backend test --tests '*ArchitectureTest' --tests '*PermissionMatrixTest'`

Expected: FAIL for two missing PackageMarkers and M2 Permissions.

- [ ] **Step 3: Add the minimal modules and permissions**

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

Keep `traceability:ingest` as a JWT service scope and do not map it to a user ProjectRole.

- [ ] **Step 4: Add backward-compatible OpenAPI Operations**

Add exactly: `POST /api/v1/issue-sources/{sourceId}/sync`, `GET /api/v1/issue-sync-runs/{syncRunId}`, `POST /api/v1/releases/{releaseId}/issue-snapshots`, `POST /api/v1/traceability/facts:ingest`, `POST /api/v1/releases/{releaseId}/traceability:verify`, and `GET /api/v1/releases/{releaseId}/traceability`. Set `x-idempotency-required: true` on writes, the approved `x-permission` on every Operation, and `202` on asynchronous responses.

- [ ] **Step 5: Verify and commit**

Run: `pnpm run test:contracts; ./backend/gradlew -p backend test --tests '*ArchitectureTest' --tests '*PermissionMatrixTest' --tests '*M2ApiContractTest'`

Expected: contract `operations=32` or higher with all six additions present; Gradle tests PASS.

Commit: `feat(m2): establish issue traceability contracts`

### Task 2: M2.1 PostgreSQL Authority Baseline

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__m2_issue_traceability.sql`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/MigrationConstraintTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/M2MigrationConstraintTest.kt`

- [ ] **Step 1: Write failing Migration Constraint Tests**

Assert 17 M2 tables, `artifact_release_edge_v`, every FK/index/UNIQUE/CHECK, stable Revision endpoints, rejected Snapshot/Edge/Gap UPDATE/DELETE, rejected cross-Project references, that `artifact` still has no `build_id`, and that no writable Artifact-to-Release table exists.

- [ ] **Step 2: Run and prove V4 is missing**

Run: `./backend/gradlew -p backend test --tests '*M2MigrationConstraintTest'`

Expected: FAIL first on missing `issue_source`.

- [ ] **Step 3: Create the forward-only V4 Migration**

Create, in dependency order: `background_job`; `issue_source`, `issue_sync_run`, `issue_sync_cursor`, `normalized_issue`, `release_issue_snapshot`, `release_issue_snapshot_item`; `source_commit`, `build_record`; three `*_edge_revision` tables; `traceability_verification_run`, `traceability_gap`, `traceability_snapshot`, `traceability_snapshot_edge`, `traceability_snapshot_gap`; then read-only `artifact_release_edge_v`. Use existing varchar(40) UUIDv7 representation; digest CHECK `^sha256:[0-9a-f]{64}$`; CHECK-constrained varchar states; and `ON DELETE RESTRICT` FKs.

Every Revision table includes:

```sql
UNIQUE (edge_id, revision),
UNIQUE (id, edge_id, revision),
CHECK ((revision = 1 AND previous_revision_id IS NULL AND previous_revision IS NULL)
    OR (revision > 1 AND previous_revision_id IS NOT NULL AND previous_revision = revision - 1)),
FOREIGN KEY (previous_revision_id, edge_id, previous_revision)
    REFERENCES issue_commit_edge_revision(id, edge_id, revision) DEFERRABLE
```

Install the existing `reject_immutable_write()` Trigger on Revision, Snapshot, Snapshot Item/Edge/Gap. A Constraint Trigger rejects endpoint or source-identity changes within one `edge_id`.

- [ ] **Step 4: Verify clean/upgrade/repeat and constraints**

Run: `./backend/gradlew -p backend test --tests '*MigrationConstraintTest' --tests '*M2MigrationConstraintTest'`

Expected: PASS; a second Flyway startup reports no pending Migration.

Commit: `feat(m2): add issue traceability database authority`

### Task 3: M2.2 IssueSourcePort, Fixture Contract, and Jira CLI Pilot

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

- [ ] **Step 1: Define the Port with a Parameterized Contract Test**

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

Run the same digest assertions over fixture, recorded internal fixture, and Jira process fixture; cover duplicate page, 429, 5xx, 401/403, timeout, invalid output, tombstone, and UNKNOWN mapping.

- [ ] **Step 2: Run and verify the Port is absent**

Run: `./backend/gradlew -p backend test --tests '*IssueSourceContractTest'`

Expected: compile FAIL.

- [ ] **Step 3: Implement Fixture and strict Jira process boundary**

Build only fixed argv. Validate absolute regular file, Project Key `^[A-Z][A-Z0-9_]{1,19}$`, limit 1 through 20, timeout, stdout byte bound, exactly five columns, and no control characters. Prohibit `--raw` and caller-supplied flags. Reduce stderr to a fixed code and SHA-256 digest.

- [ ] **Step 4: Add disabled-by-default configuration and startup rejection tests**

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

- [ ] **Step 5: Verify and commit**

Run: `./backend/gradlew -p backend test --tests '*IssueSourceContractTest' --tests '*JiraCliPilotAdapterTest' --tests '*ApplicationContextTest'`

Expected: PASS with no fixture title, argv, path, or stderr in test output.

Commit: `feat(m2): add bounded issue source adapters`

### Task 4: M2.2 Sync Transactions, Cursor, and API

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueSyncRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/StartIssueSync.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/RunIssueSync.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JdbcIssueSyncRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSyncController.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSyncJobWorker.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSyncIntegrationTest.kt`

- [ ] **Step 1: Write failing transaction and recovery tests**

Test `QUEUED → RUNNING → SUCCEEDED/FAILED`, atomic page commits, no duplicate `(source, sourceIssueId, sourceVersion, mappingVersion)`, no successful-Cursor advance on failure, a new Sync Run on retry, and full rollback if Audit/Outbox/Job fails.

- [ ] **Step 2: Run and verify missing Use Cases**

Run: `./backend/gradlew -p backend test --tests '*IssueSyncIntegrationTest'`

Expected: compile FAIL.

- [ ] **Step 3: Implement sync and Controller**

In one `@Transactional` block, `StartIssueSync` performs authorization, idempotency, Sync Run, Audit, Outbox, and Background Job, then returns a `202` operation ID. The Worker writes each Revision/checkpoint in a page transaction and advances Cursor only in the final transaction after all pages succeed. Map failures to fixed diagnostics; never catch broadly and return success.

- [ ] **Step 4: Verify and commit**

Run: `./backend/gradlew -p backend test --tests '*IssueSyncIntegrationTest' --tests '*SecurityAcceptanceTest'`

Expected: PASS with exact Cursor and idempotency state after failure injection.

Commit: `feat(m2): persist recoverable issue synchronization`

### Task 5: M2.3 Immutable Release Issue Snapshot

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueSnapshotRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/CreateIssueSnapshot.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JdbcIssueSnapshotRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSnapshotController.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSnapshotIntegrationTest.kt`

- [ ] **Step 1: Write failing Snapshot immutability tests**

Test that only a `SUCCEEDED` Sync Run is usable; mismatched Project/source/age returns 404/409/422; items sort by `(source, sourceIssueId)`; later Jira version, mapping, or sync changes leave old Snapshot bytes/digest unchanged; and the database rejects UPDATE/DELETE.

- [ ] **Step 2: Implement stable digest and single-transaction write**

The canonical payload contains only snapshot schema/version, Release/Sync/filter identity, age, raw status token, mapping/source version/reference, and each `factDigest`. One transaction writes header/items, Audit, and Outbox. Idempotency replay returns the same Snapshot.

- [ ] **Step 3: Verify and commit**

Run: `./backend/gradlew -p backend test --tests '*IssueSnapshotIntegrationTest'`

Expected: PASS and three replay digests are identical.

Commit: `feat(m2): freeze release issue snapshots`

### Task 6: M2.4 CI/Build Fact Ingestion and Edge Revision

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/domain/TraceabilityModels.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/TraceabilityRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/IngestTraceabilityFacts.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/JdbcTraceabilityRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/TraceabilityFactController.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/FactIngestionIntegrationTest.kt`

- [ ] **Step 1: Write failing typed-edge tests**

Cover Issue-to-Commit, Commit-to-Build, Build-to-Artifact, many-to-many, idempotent same batch, revision+1 on proof/status/confidence change, rejected endpoint change, rejected cross-Project write, rejected Artifact-to-Release request field, and user-JWT rejection on the service-only endpoint.

- [ ] **Step 2: Implement domain types and ingest transaction**

```kotlin
enum class EdgeType { ISSUE_COMMIT, COMMIT_BUILD, BUILD_ARTIFACT, ARTIFACT_RELEASE }
enum class VerificationStatus { VALID, INVALID, CONFLICT, ERROR }
enum class Confidence { HIGH, MEDIUM, LOW, UNKNOWN }
```

Accept only the first three writable types, provider reference, source revision, artifact SHA-256, and proof reference. Reject Fixed/Included/Verified Booleans. Return the current Revision for the same fact digest; otherwise lock the logical edge and append the next Revision, Audit, and Outbox.

- [ ] **Step 3: Verify and commit**

Run: `./backend/gradlew -p backend test --tests '*FactIngestionIntegrationTest' --tests '*SecurityAcceptanceTest'`

Expected: PASS; a database query proves append-only history for every logical edge.

Commit: `feat(m2): ingest typed traceability revisions`

### Task 7: M2.5 Verification, Gap, Snapshot, and Query

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/VerifyTraceability.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/GetTraceability.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/TraceabilityController.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationIntegrationTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityReplayTest.kt`

- [ ] **Step 1: Write failing known-chain and gap tests**

Build Issue-to-Commit-to-Build-to-Artifact-to-Locked Manifest-to-Release. Assert Fixed=true, Included=true, Verified=false, and exact `TEST_RESULT_EVIDENCE_MISSING` gap. Removing each required edge yields Included=false and the matching gap. Commit-only must not yield Fixed/Verified; CONFLICT/ERROR cannot be a valid path.

- [ ] **Step 2: Implement deterministic path verification**

Sort by stable Issue/Edge ID and read an explicit Revision for every logical edge. Read Artifact-to-Release only through `artifact_release_edge_v`. Persist policy/validator version, status, and diagnostics on the Verification Run; on completion materialize complete Edge/Gap facts and content digest. M2 must never create Verified=true.

- [ ] **Step 3: Implement asynchronous verify and read-only query API**

`POST :verify` creates operation, Audit, and Job and returns `202`; `GET traceability` reads only a specified or latest completed Snapshot and never the newest Revision. Use 404 for hidden resources, 409 for state conflict, 422 for invalid facts, and 503 for explicit external unavailability.

- [ ] **Step 4: Verify replay and commit**

Run: `./backend/gradlew -p backend test --tests '*TraceabilityVerificationIntegrationTest' --tests '*TraceabilityReplayTest'`

Expected: PASS; after edge revision+1, old Snapshot bytes/path/confidence/digest remain unchanged.

Commit: `feat(m2): verify and snapshot traceability paths`

### Task 8: M2.6 Gate, Real Jira Smoke, and Acceptance Package

**Files:**
- Create: `scripts/m2/verify.ps1`
- Create: `scripts/m2/jira-pilot-smoke.ps1`
- Create: `scripts/tests/m2-verify-gates.tests.ps1`
- Create: `.github/workflows/m2-backend.yml`
- Create: `docs/m2/README.md`
- Create: `docs/governance/acceptance/records/2026-08-28-m2-owner-gate-001.md`

- [ ] **Step 1: Write failing Gate orchestration tests**

Verify clean worktree, fixed commit, contract/governance/secret scan/Gradle/PostgreSQL/replay/recovery order, whole-run failure on any Gate failure, Evidence JSON still emitted with status=`FAILED`, and initial Owner decision=`PENDING`. Real Jira Smoke is not a required CI Gate.

- [ ] **Step 2: Implement M2 Gate and CI**

Reuse the M1 Evidence format with milestone `M2`. Run design governance, OpenAPI, all Backend tests, Migration, known-chain/gap/replay, backup/restore drill, and secret scan. The Workflow grants only `contents: read`, configures no Jira secret, and uploads `m2-evidence-${{ github.sha }}`.

- [ ] **Step 3: Implement manual Jira Pilot Smoke**

Require `PILOT`, explicit enablement, one Project, and limit 1 through 20. Invoke the Backend operation rather than composing a Jira command in the script. Emit only execution time, versions, limit, count, schema digest, Sync Run ID, and fixed result code. Fail and refuse report publication if title, URL, path, or credential-like token is detected.

- [ ] **Step 4: Create PENDING acceptance record and run the full Gate**

Run: `pwsh -NoProfile -File scripts/tests/m2-verify-gates.tests.ps1; pwsh -NoProfile -File scripts/m2/verify.ps1`

Expected: `PASS M2 gates=...`, fixed-commit Evidence, and acceptance-record validator PASS; Owner decision remains `PENDING`.

- [ ] **Step 5: Run real Smoke separately on the authorized Pilot host**

Run: `pwsh -NoProfile -File scripts/m2/jira-pilot-smoke.ps1`

Expected: at most 20 read-only Issues and a redacted PASS summary. Jira unavailability is explicit FAIL, does not alter historical fixture CI, and cannot create a Company Ready claim.

- [ ] **Step 6: Self-review, bilingual sync, and commit**

Run: `git diff --check; rg -n 'T[B]D|T[O]DO|implement[ ]later' docs/superpowers/plans; pwsh -NoProfile -File scripts/verify-language-branches.ps1 -Mode Pair -ChineseRef docs/m2-issue-traceability-design -EnglishRef docs/m2-issue-traceability-design-en`

Expected: no placeholder, Pair Gate PASS, both worktrees clean, and both remote CI runs Success.

Commits: `test(m2): add deterministic candidate gate`, followed by independent `docs(m2): add owner acceptance candidate`.

## Final Acceptance and Stop Conditions

Completion requires fixed bilingual commits, two successful CI runs, two M2 Evidence Artifacts, Pair Gate, PostgreSQL schema/constraint report, fixture-contract report, known-chain/gap/replay report, and a separate redacted real Jira Smoke result. The Owner records `ACCEPTED`, `CONDITIONAL`, or `REJECTED`; Codex does not substitute for that decision.

Stop immediately and submit a Finding, TDR revision, or ADR Proposal if implementation requires Jira DTOs in Core, a second Artifact-to-Release source, rewriting old Revisions/Snapshots, converting UNKNOWN/error/gap into PASS, reading real fields outside the allowlist, or changing Fixed/Included/Verified semantics.
