# M2.5 Traceability Verification Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Asynchronously compute replayable Fixed, Included, Verified, and exact Gaps from the Locked Manifest, M2.3 Issue Snapshot, and M2.4 Edge Revisions pinned when the request is accepted, then atomically persist an immutable Traceability Snapshot.

**Architecture:** Keep the Kotlin/Spring Boot modular monolith and PostgreSQL as the sole run and result authority. The HTTP creation transaction pins every input and writes the existing Job/Outbox records. A worker reads only that ledger and atomically materializes the Snapshot, Issue Results, primary path edges, and Gaps in a second transaction. Queries read completed Snapshots only and never call Jira, GitHub, CI, a device, or the latest edge state.

**Tech Stack:** Kotlin/JVM 21, Spring Boot, Spring JDBC, PostgreSQL 17, Flyway, Jackson, RFC 8785 JCS, JUnit 5, AssertJ, MockMvc, Testcontainers, PowerShell, and GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-04-m2-traceability-verification-snapshot-design.md`

## Global Constraints

- Preserve the frozen V0.1 `0.1.0` Core Contract, release-centric architecture, Manifest authority, Evidence, Traceability, Deterministic Quality Engine, Adapter, Plugin, and ADR governance.
- Release authority is the Locked Manifest. Issue scope is the M2.3 `release_issue_snapshot` pinned at request time. M2.4 edges use exact pinned revisions. `ARTIFACT_RELEASE` comes only from `artifact_release_edge_v`.
- M2.5 computes Fixed and Included only. Verified is always `false` and must produce `TEST_RESULT_EVIDENCE_MISSING`.
- A pinned current authority revision with `INVALID`, `CONFLICT`, or `ERROR` is an input failure, not a Gap, and cannot produce a successful Snapshot.
- The creation transaction atomically writes Idempotency, Verification Run, Input Ledger, Audit, Outbox, and Background Job. The result transaction atomically writes Snapshot, Issue Result, Path Edge, Gap, Audit, Outbox, and terminal state.
- State transitions are only `QUEUED → RUNNING → SUCCEEDED|FAILED`; terminal state cannot be overwritten. Different keys with identical inputs may reuse a content-identical Snapshot while retaining separate Runs.
- A run accepts at most 20 Issues and, by default, 2,000 Edge Revisions. Truncation is forbidden. Pilot targets are creation P95 at most 1 second, verification at most 10 seconds, and query P95 at most 1 second.
- Preserve `POST /api/v1/releases/{releaseId}/traceability:verify` and `GET /api/v1/releases/{releaseId}/traceability`; add only `GET /api/v1/traceability-verification-runs/{verificationRunId}`.
- Do not add a broker, Redis, graph database, separate service, external call, UI, or Company dependency. Do not merge, tag, release, or deploy to production.

---

## File Structure and Commit Order

This plan fixes seven independently reviewable commit boundaries:

1. OpenAPI, permissions, and strict DTOs.
2. PostgreSQL V11 pinned-input and immutable-result extensions.
3. Provider-neutral deterministic path computation and canonical digests.
4. Verification Run creation transaction and pinned input ledger.
5. Worker claim, result materialization, recovery, and concurrency convergence.
6. Snapshot/Run queries, authorization, and replay.
7. Performance, recovery, gates, operations, and the Owner acceptance candidate.

Non-Markdown files from Tasks 1 through 7 must be byte-identical on both language branches. Markdown files are equivalent translations. Every task starts with the specified failure, adds the smallest implementation, reruns the target tests, and ends with a commit.

### Task 1: OpenAPI Contract, Permission, and Strict DTO

**Files:**

- Modify: `contracts/openapi/v0.2/openapi.json`
- Modify: `contracts/openapi/v0.2/compatibility-baseline.json`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/M2ApiContractTest.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/access/SecurityAcceptanceTest.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/TraceabilityVerificationDtos.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationDtoTest.kt`

**Interfaces:**

- Consumes: existing `traceability:verify`, `traceability:read`, OIDC, `Idempotency-Key`, Release path, and `IdentifierInput.sourceId`.
- Produces: `TraceabilityVerificationAccepted`, `TraceabilityVerificationRunResponse`, and `TraceabilitySnapshotResponse` for the controllers in Tasks 4 and 6.

- [ ] **Step 1: Write failing contract and strict DTO tests**

```kotlin
@Test
fun `verification contract keeps paths and adds run polling`() {
    assertOperation("post", "/api/v1/releases/{releaseId}/traceability:verify", "traceability:verify", 202)
    assertOperation("get", "/api/v1/releases/{releaseId}/traceability", "traceability:read", 200)
    assertOperation("get", "/api/v1/traceability-verification-runs/{verificationRunId}", "traceability:read", 200)
}

@Test
fun `identifier sourceId means issue source and unknown fields fail`() {
    val request = mapper.readValue("""{"sourceId":"jira-main"}""", TraceabilityVerifyRequest::class.java)
    assertThat(request.issueSourceId).isEqualTo("jira-main")
    assertThatThrownBy {
        mapper.readValue("""{"sourceId":"jira-main","edgeSource":"latest"}""", TraceabilityVerifyRequest::class.java)
    }.hasMessageContaining("INVALID_TRACEABILITY_VERIFY_REQUEST")
}
```

- [ ] **Step 2: Run tests and observe the missing polling operation and DTO**

```powershell
./backend/gradlew -p backend test --tests '*M2ApiContractTest' --tests '*TraceabilityVerificationDtoTest'
```

Expected: FAIL specifically because the status path/schema or DTO is absent, not because the environment skipped the test.

- [ ] **Step 3: Fix the exact schemas and error semantics**

The `POST` body accepts only `sourceId`. The required `202` response fields are `verificationRunId`, `status=QUEUED`, `releaseId`, `issueSnapshotId`, and `statusUrl`. The Run GET response contains `verificationRunId`, `releaseId`, `status`, `policyVersion`, `validatorVersion`, `inputDigest`, nullable `resultSnapshotId`, nullable `diagnosticCode`, and timestamps. The Snapshot GET response contains the header, Issue Results sorted by source issue identity, each Issue's ordered primary path, and Gaps. Every object uses `additionalProperties:false`.

Errors are fixed as invisible resource `404`, state conflict `409`, invalid pinned facts or limit breach `422`, and explicit infrastructure unavailability `503`. Responses never expose titles, proof URLs, credentials, raw payloads, SQL, or stack traces.

- [ ] **Step 4: Update the compatibility baseline and security matrix tests**

Add exactly one GET operation while preserving existing paths, methods, permissions, and idempotency. Test that Owner and Engineer can verify, Viewer can only read, and cross-project or unauthorized access is uniformly hidden as 404.

- [ ] **Step 5: Verify and commit the contract**

```powershell
./backend/gradlew -p backend test --tests '*M2ApiContractTest' --tests '*TraceabilityVerificationDtoTest' --tests '*SecurityAcceptanceTest'
npm run test:contracts
git diff --check
git add contracts/openapi/v0.2/openapi.json contracts/openapi/v0.2/compatibility-baseline.json backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/TraceabilityVerificationDtos.kt backend/src/test/kotlin/com/ricezhou/vsrqg/M2ApiContractTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/access/SecurityAcceptanceTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationDtoTest.kt
git commit -m "feat(m2): define traceability verification contract"
```

Expected: contract tests PASS and the operation count increases exactly from 33 to 34.

### Task 2: PostgreSQL V11 Fixed Input and Immutable Result Authority

**Files:**

- Create: `backend/src/main/resources/db/migration/V11__traceability_verification_snapshot.sql`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationMigrationTest.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/M2MigrationConstraintTest.kt`

**Interfaces:**

- Consumes: V4 Verification/Snapshot/Gap/Job tables, the V6 Issue Snapshot, V9/V10 typed edge authority, and `artifact_release_edge_v`.
- Produces: Run input identity, `traceability_verification_run_edge_input`, `traceability_snapshot_issue_result`, `traceability_snapshot_issue_path_edge`, and exact Gap break references for Tasks 4 and 5.

- [ ] **Step 1: Write failing clean, upgrade, and constraint tests**

```kotlin
@Test
fun `v11 adds fixed input and issue result authority`() {
    assertThat(columnNames("traceability_verification_run")).contains(
        "issue_snapshot_id", "manifest_revision_id", "validator_version", "input_digest", "result_snapshot_id",
    )
    assertThat(tableNames()).contains(
        "traceability_verification_run_edge_input",
        "traceability_snapshot_issue_result",
        "traceability_snapshot_issue_path_edge",
    )
}

@Test
fun `snapshot result and fixed inputs reject update and delete`() {
    seedCompletedVerification()
    assertImmutable("traceability_verification_run_edge_input")
    assertImmutable("traceability_snapshot_issue_result")
    assertImmutable("traceability_snapshot_issue_path_edge")
}
```

Also test cross-project and cross-release foreign keys, repeated ordinals, non-VALID input, illegal state transitions, terminal overwrite, unsupported Gap codes, path edges absent from input, Snapshot/Run mismatch, concurrent per-Release version allocation, and V10-to-V11 upgrade.

- [ ] **Step 2: Run tests and observe missing V11 objects**

```powershell
./backend/gradlew -p backend test --tests '*TraceabilityVerificationMigrationTest'
```

Expected: FAIL specifically for missing V11 columns, tables, or constraints.

- [ ] **Step 3: Write the forward-only V11 migration**

Extend `traceability_verification_run` with `issue_snapshot_id`, `manifest_revision_id`, `validator_version`, `input_digest`, `result_snapshot_id`, `requested_by`, and `request_id`. Historical rows may remain nullable; CHECK constraints or triggers require complete fields for a new M2.5 policy row. Add the ledger:

```sql
CREATE TABLE traceability_verification_run_edge_input (
    verification_run_id varchar(40) NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    project_id varchar(40) NOT NULL,
    edge_type varchar(40) NOT NULL CHECK (edge_type IN ('ISSUE_COMMIT','COMMIT_BUILD','BUILD_ARTIFACT','ARTIFACT_RELEASE')),
    source_edge_id varchar(40) NOT NULL,
    source_edge_revision integer NOT NULL CHECK (source_edge_revision > 0),
    source_edge_revision_id varchar(40) NOT NULL,
    fact_digest varchar(71) NOT NULL CHECK (fact_digest ~ '^sha256:[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (verification_run_id, ordinal),
    UNIQUE (verification_run_id, edge_type, source_edge_id, source_edge_revision)
);
```

Both `traceability_snapshot_edge` and the input ledger store `source_edge_revision_id`: the first three typed Edge kinds use the corresponding `*_edge_revision.id`, while `ARTIFACT_RELEASE` uses the Locked Manifest Revision ID. The database rejects an ID forged across Edge types or inconsistent with the Manifest. `traceability_snapshot_issue_result` stores `issue_id`, `source_issue_id`, `fixed`, `included`, `verified=false`, `result_digest`, and ordinal. `traceability_snapshot_issue_path_edge` uses `(snapshot_id, issue_ordinal, path_ordinal)` to reference the Issue Result and existing Snapshot Edge. Extend both Gap tables with `break_entity_type`, `break_entity_id`, and nullable `predecessor_edge_type/id/revision`; allow only the five approved diagnostics.

- [ ] **Step 4: Install triggers and indexes**

Use the existing `reject_immutable_write()` on every new result table and the input ledger. A state trigger permits only `QUEUED→RUNNING→SUCCEEDED|FAILED`; `SUCCEEDED` requires a result snapshot and `FAILED` requires a fixed diagnostic. Index `(release_id, created_at desc)`, `input_digest`, `result_snapshot_id`, Issue Result lookup, and worker dispatch. Do not create a second Artifact-to-Release table.

- [ ] **Step 5: Verify clean, V10 upgrade, repeat, and concurrency**

```powershell
./backend/gradlew -p backend test --tests '*TraceabilityVerificationMigrationTest' --tests '*M2MigrationConstraintTest' --tests '*MigrationConstraintTest'
```

Expected: PASS. A second Flyway start has no pending migration. Concurrent version allocation permits one transaction to succeed and the other to retry.

- [ ] **Step 6: Commit database authority**

```powershell
git add backend/src/main/resources/db/migration/V11__traceability_verification_snapshot.sql backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationMigrationTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/shared/M2MigrationConstraintTest.kt
git commit -m "feat(m2): add traceability snapshot authority"
```

### Task 3: Deterministic Verification Domain and Canonical Digest

**Files:**

- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/domain/TraceabilityVerificationModels.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/TraceabilityVerifier.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/TraceabilityCanonicalizer.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/JcsTraceabilityCanonicalizer.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerifierTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityCanonicalizerTest.kt`

**Interfaces:**

- Consumes: immutable `VerificationInput(issueSnapshot, manifest, edgeRevisions)`.
- Produces: `VerificationComputation(issueResults, pathEdges, gaps, contentDigest)` for Task 5 to persist unchanged.

- [ ] **Step 1: Write failing complete-chain and per-segment Gap tests**

```kotlin
@Test
fun `complete path is fixed and included but never verified in m25`() {
    val result = verifier.verify(knownChain())
    assertThat(result.issueResults.single()).extracting("fixed", "included", "verified")
        .containsExactly(true, true, false)
    assertThat(result.gaps.map(TraceabilityGap::diagnosticCode))
        .containsExactly(TraceabilityGapCode.TEST_RESULT_EVIDENCE_MISSING)
}

@ParameterizedTest
@EnumSource(value = TraceabilityGapCode::class, names = [
    "ISSUE_COMMIT_MISSING", "COMMIT_BUILD_MISSING", "BUILD_ARTIFACT_MISSING", "ARTIFACT_RELEASE_MISSING",
])
fun `first broken segment produces one exact structural gap`(code: TraceabilityGapCode) {
    assertThat(verifier.verify(chainMissing(code)).gaps.single().diagnosticCode).isEqualTo(code)
}
```

Add stable multi-path choice, Issue isolation, the absence of any N+1 repository assumption, 20/2,000 boundaries, 2,001 fail-closed, and rejection with `TRACEABILITY_INPUT_NOT_VALID` for every pinned `INVALID|CONFLICT|ERROR` input.

- [ ] **Step 2: Define provider-neutral immutable types**

```kotlin
enum class TraceabilityGapCode {
    ISSUE_COMMIT_MISSING, COMMIT_BUILD_MISSING, BUILD_ARTIFACT_MISSING,
    ARTIFACT_RELEASE_MISSING, TEST_RESULT_EVIDENCE_MISSING,
}

data class TraceabilityIssueResult(
    val issueId: String,
    val sourceIssueId: String,
    val fixed: Boolean,
    val included: Boolean,
    val verified: Boolean = false,
    val path: List<PinnedTraceabilityEdge>,
    val gaps: List<TraceabilityGap>,
)
```

The constructor rejects `verified=true`. Fixed requires at least one policy-valid `ISSUE_COMMIT`; Included requires one continuous primary path to the Locked Manifest Release. Sort candidate paths by edge type sequence, endpoint IDs, edge ID, numeric revision, and revision ID, then select the first. A Gap names the first break and its actual predecessor.

- [ ] **Step 3: Implement canonical payloads and digests**

The input digest contains only schema, policy, validator, Release, Manifest revision/digest, Issue Snapshot ID/digest, and pinned fact digests sorted by `(edgeType, sourceEdgeId, numericRevision, revisionId)`. The result digest contains only pinned input identity, including revision ID, Issue Results sorted by `sourceIssueId, issueId`, path edge facts including revision ID, and Gaps. Timestamps, run ID, snapshot ID, request ID, and actor are excluded.

- [ ] **Step 4: Run determinism and three-pass byte stability tests**

```powershell
./backend/gradlew -p backend test --tests '*TraceabilityVerifierTest' --tests '*TraceabilityCanonicalizerTest'
```

Expected: PASS. Randomizing input order 100 times leaves canonical bytes and digests unchanged.

- [ ] **Step 5: Commit the domain algorithm**

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/domain/TraceabilityVerificationModels.kt backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/TraceabilityVerifier.kt backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/TraceabilityCanonicalizer.kt backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/JcsTraceabilityCanonicalizer.kt backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerifierTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityCanonicalizerTest.kt
git commit -m "feat(m2): compute deterministic traceability results"
```

### Task 4: Verification Run Creation Transaction

**Files:**

- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/TraceabilityVerificationRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/StartTraceabilityVerification.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/JdbcTraceabilityVerificationRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/TraceabilityVerificationController.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/TraceabilityVerificationConfiguration.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationStartIntegrationTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationStartFailureTest.kt`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**

- Consumes: Task 1 request/accepted DTOs, the Task 3 input canonicalizer, existing principal/project authority, Locked Manifest, latest immutable Issue Snapshot for the Release/source, exact M2.4 Edge Revisions, and the Manifest view.
- Produces: `StartTraceabilityVerification.start(command): TraceabilityVerificationAccepted` and one `TRACEABILITY_VERIFY` Background Job.

- [ ] **Step 1: Write failing authority pinning and idempotency tests**

Test non-Locked Release as 409, absent source Snapshot as 404, latest Snapshot pinned within the transaction, only Snapshot Issues entering the ledger, edge limit as 422 without truncation, same key/body returning the same Run, same key/different body as 409, different keys/same input creating separate Runs, and later Edge Revision insertion leaving the ledger unchanged.

- [ ] **Step 2: Write failure-injection tests for every persistence boundary**

```kotlin
@ParameterizedTest
@EnumSource(StartWriteBoundary::class)
fun `failure rolls back every creation artifact`(boundary: StartWriteBoundary) {
    repository.failAt(boundary)
    assertThatThrownBy { useCase.start(command()) }.isInstanceOf(RuntimeException::class.java)
    assertThat(countRunArtifacts()).containsOnly(0)
}
```

Boundaries are idempotency, run, input ledger, audit, outbox, and job. Never catch a failure and return `202`.

- [ ] **Step 3: Implement one `@Transactional` creation use case**

Use one set-based repository query to load the Issue Snapshot, latest eligible revisions for the three typed edges, and `artifact_release_edge_v`. Validate Project, Release, and Manifest consistency, then compute the input digest. The Run payload stores IDs, versions, and digests only. The Audit event is `TRACEABILITY_VERIFICATION_QUEUED`, the Outbox event is `traceability.verification.queued`, and the Job payload contains only the run ID. Configuration is:

```yaml
vsrqg:
  traceability:
    verification:
      enabled: ${VSRQG_TRACEABILITY_VERIFICATION_ENABLED:false}
      policy-version: m2.5-traceability-policy/v1
      validator-version: m2.5-path-validator/v1
      max-issues: 20
      max-edge-revisions: 2000
```

- [ ] **Step 4: Implement the POST controller and permission**

The controller uses `@PreAuthorize("hasAuthority('SCOPE_traceability:verify')")`, the existing principal resolver, and Request ID. Disabled mode returns a fixed 503 and never falls back to synchronous computation or dynamic query.

- [ ] **Step 5: Verify and commit the creation transaction**

```powershell
./backend/gradlew -p backend test --tests '*TraceabilityVerificationStartIntegrationTest' --tests '*TraceabilityVerificationStartFailureTest' --tests '*SecurityAcceptanceTest' --tests '*ApplicationContextTest'
git diff --check
git add backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/TraceabilityVerificationRepository.kt backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/StartTraceabilityVerification.kt backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/JdbcTraceabilityVerificationRepository.kt backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/TraceabilityVerificationController.kt backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/TraceabilityVerificationConfiguration.kt backend/src/main/resources/application.yml backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationStartIntegrationTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationStartFailureTest.kt
git commit -m "feat(m2): queue pinned traceability verification"
```

### Task 5: Worker Materialization, Recovery, and Concurrency

**Files:**

- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/RunTraceabilityVerification.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/TraceabilityVerificationJobWorker.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationWorkerIntegrationTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationWorkerFailureTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationConcurrencyTest.kt`

**Interfaces:**

- Consumes: `TraceabilityVerificationRepository.claimNext(now)`, the Task 3 verifier/canonicalizer, and the fixed input ledger.
- Produces: an immutable Snapshot graph and terminal Run; Task 6 only reads these results.

- [ ] **Step 1: Write failing claim, crash, and atomic-result tests**

Cover one-time `FOR UPDATE SKIP LOCKED` claim, attempt increment, no duplicate execution by two workers, RUNNING crash reclaim, and bounded attempts leading to Run FAILED and Job DEAD_LETTER. Inject failures at Snapshot header, Issue Result, Snapshot Edge, Path Edge, Gap, Audit, Outbox, Run terminal, and Job terminal; assert that no partial Snapshot remains.

- [ ] **Step 2: Write content reuse and Release version concurrency tests**

```kotlin
@Test
fun `same input different runs reuse one content identical snapshot`() {
    val first = execute(start(idempotencyKey = "verify-1"))
    val second = execute(start(idempotencyKey = "verify-2"))
    assertThat(second.resultSnapshotId).isEqualTo(first.resultSnapshotId)
    assertThat(runCount()).isEqualTo(2)
    assertThat(snapshotCount()).isEqualTo(1)
}
```

For two different concurrent inputs, lock the database Release row and allocate consecutive versions. JVM locks are forbidden. Retry only a bounded unique conflict and never swallow other SQL failures.

- [ ] **Step 3: Implement worker claim and pure computation**

Reuse the Issue Worker claim pattern with job type `TRACEABILITY_VERIFY`. The claim transaction changes Job and Run to RUNNING. The worker then loads facts only from the ledger and calls the Task 3 verifier. It must not call external adapters, read latest revisions, or reconstruct authority from JSON or a cache.

- [ ] **Step 4: Implement the atomic result transaction**

First find a reusable Snapshot by input/result digest. If none exists, lock the Release row, allocate a version, and write Snapshot header, stably ordered Issue Results, deduplicated Snapshot Edges, Path Edges, Gaps, Audit `TRACEABILITY_VERIFICATION_SUCCEEDED`, Outbox `traceability.verification.succeeded`, then Run and Job terminal state. Invalid input writes no Snapshot and updates FAILED/DEAD_LETTER with a fixed code. Unexpected infrastructure failures retain a safe code and retryable Job without persisting exception text.

- [ ] **Step 5: Verify worker and recovery behavior**

```powershell
./backend/gradlew -p backend test --tests '*TraceabilityVerificationWorkerIntegrationTest' --tests '*TraceabilityVerificationWorkerFailureTest' --tests '*TraceabilityVerificationConcurrencyTest'
```

Expected: PASS. Every successful Run points to one complete Snapshot and every failed Run points to none.

- [ ] **Step 6: Commit the worker**

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/RunTraceabilityVerification.kt backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/TraceabilityVerificationJobWorker.kt backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationWorkerIntegrationTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationWorkerFailureTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationConcurrencyTest.kt
git commit -m "feat(m2): materialize traceability snapshots"
```

### Task 6: Read APIs, Security, and Replay

**Files:**

- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/GetTraceabilityVerification.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/TraceabilityVerificationController.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationQueryIntegrationTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityReplayTest.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/access/SecurityAcceptanceTest.kt`

**Interfaces:**

- Consumes: completed immutable tables from Task 5.
- Produces: Task 1 Run polling and Snapshot query responses without recomputation.

- [ ] **Step 1: Write failing query ordering, visibility, and replay tests**

Test default latest SUCCEEDED Snapshot, exact historical `snapshotId`, QUEUED/RUNNING/FAILED never impersonating a Snapshot, stable Issue/path/Gap ordering, and uniform 404 for cross-project, unauthorized, or absent resources. After completion, insert M2.4 revision+1 and a new Issue Snapshot; assert unchanged historical response bytes and digest.

- [ ] **Step 2: Implement set-based read repository methods**

Run GET queries by public verification ID and principal Project membership. Snapshot GET performs one query each for header, Issue Results, Path Edges, and Gaps, then the Application assembles persisted ordinals. Do not query per Issue or read source revision tables, `artifact_release_edge_v`, or external systems.

- [ ] **Step 3: Implement GET endpoints and Problem Details mapping**

```kotlin
@GetMapping("/api/v1/traceability-verification-runs/{verificationRunId}")
@PreAuthorize("hasAuthority('SCOPE_traceability:read')")
fun getRun(...): TraceabilityVerificationRunResponse

@GetMapping("/api/v1/releases/{releaseId}/traceability")
@PreAuthorize("hasAuthority('SCOPE_traceability:read')")
fun getSnapshot(..., @RequestParam(required = false) snapshotId: String?): TraceabilitySnapshotResponse
```

Expose only allowlisted diagnostics and identifiers. Responses contain no raw reason, SQL, proof URL, source title, or token-like string.

- [ ] **Step 4: Verify read, security, and replay behavior, then commit**

```powershell
./backend/gradlew -p backend test --tests '*TraceabilityVerificationQueryIntegrationTest' --tests '*TraceabilityReplayTest' --tests '*SecurityAcceptanceTest' --tests '*M2ApiContractTest'
git diff --check
git add backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/GetTraceabilityVerification.kt backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/TraceabilityVerificationController.kt backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationQueryIntegrationTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityReplayTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/access/SecurityAcceptanceTest.kt
git commit -m "feat(m2): expose immutable traceability results"
```

### Task 7: Performance, Recovery, Candidate Gate, and Owner Record

**Files:**

- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationPerformanceTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationRecoveryTest.kt`
- Create: `scripts/m2/verify-m25.ps1`
- Create: `scripts/tests/m2-5-verify-gates.tests.ps1`
- Modify: `.github/workflows/m2-backend.yml`
- Create: `docs/m2/traceability-verification-operations.md`
- Create: `docs/governance/acceptance/records/2026-09-04-m2-5-owner-gate-001.md`

**Interfaces:**

- Consumes: exact behavior from Tasks 1 through 6 and existing M2 Evidence/Gate conventions.
- Produces: a fixed-commit M2.5 evidence bundle and a `PENDING` Owner acceptance record; it does not self-approve.

- [ ] **Step 1: Write failing gate orchestration tests**

Fix gate order as clean tree, fixed commit, contract, migration, domain, transaction failure, concurrency, replay, performance, secret scan, acceptance validator, and evidence digest. Any failure makes the total result FAILED while still producing a sanitized evidence summary. The Owner decision starts only as `PENDING`.

- [ ] **Step 2: Add reference Pilot performance and query-count tests**

Build a deterministic 20-Issue/2,000-fixed-Edge fixture. Measure start, worker, and query with sample count, p50/p95/max, and hardware/runtime metadata. Assert constant-order repository query count and no per-Issue query. The hard gate uses a reproducible generous ceiling while the report also records the targets of P95 at most 1s/10s/1s. Passing by skip, truncation, or a smaller fixture is forbidden.

- [ ] **Step 3: Add backup/restore and dead-letter recovery tests**

Export and restore a completed Snapshot and recompute its canonical digest. Simulate reclaiming a RUNNING job after database restart. A poison job reaches Run FAILED and Job DEAD_LETTER; a manual retry creates a new Run and never mutates the old terminal record.

- [ ] **Step 4: Implement the M2.5 gate and CI**

`verify-m25.ps1` invokes target Gradle suites, `npm run test:contracts`, governance/language/secret checks, and emits JSON containing commit, migration version, test counts, performance, replay digest, and recovery result. The workflow has only `contents: read`, configures no Jira or GitHub provider credential, never accesses a Company environment, and uploads `m2-5-evidence-${{ github.sha }}`.

- [ ] **Step 5: Write operations guidance and the PENDING acceptance record**

The operations document covers only the Pilot feature flag, Migration-to-deploy-to-known-chain/gap-smoke sequence, image rollback with database roll-forward, Worker backlog/dead-letter inspection, fixed diagnostics, and the prohibition on reconstructing history. Commit the candidate gate first, then create the WHY/WHAT/BOUNDARY/ACCEPTANCE record using that commit's actual SHA. Decision remains `PENDING`; do not claim Company Ready.

- [ ] **Step 6: Run full verification, self-review, and bilingual synchronization**

```powershell
pwsh -NoProfile -File scripts/tests/m2-5-verify-gates.tests.ps1
pwsh -NoProfile -File scripts/m2/verify-m25.ps1
git diff --check
rg -n 'T[B]D|T[O]DO|implement[ ]later|fill[ ]in' docs/superpowers/plans/2026-09-04-m2-traceability-verification-snapshot-implementation.md
pwsh -NoProfile -File scripts/verify-language-branches.ps1 -Mode Pair -ChineseRef docs/m2-issue-traceability-design -EnglishRef docs/m2-issue-traceability-design-en
```

Expected: all checks PASS; English Markdown contains no Han characters; non-Markdown files are byte-identical; both worktrees are clean; exact-head CI succeeds.

- [ ] **Step 7: Commit the candidate gate and acceptance record**

```powershell
git add backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationPerformanceTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationRecoveryTest.kt scripts/m2/verify-m25.ps1 scripts/tests/m2-5-verify-gates.tests.ps1 .github/workflows/m2-backend.yml docs/m2/traceability-verification-operations.md
git commit -m "test(m2): add traceability verification candidate gate"
git add docs/governance/acceptance/records/2026-09-04-m2-5-owner-gate-001.md
git commit -m "docs(m2): add traceability owner gate candidate"
```

## Final Acceptance and Stop Conditions

Completion requires fixed bilingual Subject commits, two exact-head successful CI runs, two M2.5 Evidence artifacts, Pair Gate, V11 clean/upgrade/repeat report, complete-chain and five-Gap report, transaction failure matrix, concurrency/recovery/replay report, 20-Issue/2,000-Edge performance report, and a `PENDING` Owner Gate. After review, only the Project Owner may record `ACCEPTED`, `CONDITIONAL`, or `REJECTED`; Codex does not replace the Owner decision.

Stop immediately and submit a Finding, TDR revision, or ADR Proposal if implementation would change Fixed/Included/Verified semantics, create a second Artifact-to-Release authority, dynamically read latest revisions, convert invalid/conflict/error into a Gap, produce Verified true, call Jira/GitHub/CI/device systems, overwrite an old Run/Snapshot, or cross a frozen V0.1 boundary.
