# M2.4 Build Provenance Envelope Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the M2.4 Pilot in which one Build Attempt maps to one `BuildProvenanceEnvelope schemaVersion: 2`, producing auditable, replayable, append-only Issue-to-Commit-to-Build-to-Artifact provenance facts in one PostgreSQL transaction.

**Architecture:** Keep the existing Kotlin/Spring Boot Modular Monolith. A Traceability Adapter accepts the provider DTO, the Application handles a normalized Envelope, and PostgreSQL stores Project-scoped Commits, Build Attempts, Edge Headers, typed Revisions, Receipts, Audit, Outbox, and Idempotency. Issues resolve only from an M2.3 immutable Snapshot and Artifacts resolve only through existing SHA-256 authority; GitHub Actions is the first Adapter and cannot enter Core or produce Fixed, Included, Verified, or `ARTIFACT_RELEASE`.

**Tech Stack:** Kotlin/JVM 21, Spring Boot, Spring JDBC, PostgreSQL 17, Flyway, Jackson, RFC 8785 JCS, JUnit 5, AssertJ, MockMvc, Testcontainers, PowerShell, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-03-m2-build-provenance-envelope-design.md`

## Global Constraints

- The V0.1 `0.1.0` Core Contract, Release-centric architecture, Manifest authority, Evidence, Traceability, Deterministic Quality Engine, Adapter, Plugin, and ADR governance remain frozen.
- The Endpoint remains `POST /api/v1/traceability/facts:ingest`, the scope remains `traceability:ingest`, and only a Service Identity may call it.
- The API body explicitly uses `schemaVersion: 2` to supersede the unimplemented v1 pre-release draft with no consumer; Path, Method, Permission, and `Idempotency-Key` remain unchanged.
- One Envelope describes one Project, Provider, and Build Attempt; `sourceIssueIds` and `artifactSha256s` each allow at most 20 items, derived facts allow at most 100, and the request body allows at most 256 KiB.
- M2.4 writes only `ISSUE_COMMIT`, `COMMIT_BUILD`, and `BUILD_ARTIFACT`; it must not add a writable `ARTIFACT_RELEASE` path or an Artifact `build_id`.
- The successful path for Snapshot, Commit, Build, Artifact, Edge, Receipt, Idempotency, Audit, and Outbox must use one PostgreSQL transaction; any write failure rolls back the whole transaction.
- Historical Edge Revisions, Receipts, rejected receipts, Commits, and Builds reject UPDATE and DELETE; a new observation can only append a Revision.
- A successful GitHub Actions Pilot result is capped at `MEDIUM`; unsigned proof must never produce `HIGH`.
- The Pilot Feature is disabled by default; do not add a Broker, Redis, graph database, independent Service, Raw Event Store, object storage, or UI.
- Do not access real Jira, company CI, or a Company environment; do not merge, Tag, release, or production deploy.

---

## File Structure and Commit Order

This plan fixes six independently reviewable commit boundaries:

1. Contract v2 and consumer inventory.
2. PostgreSQL authority expansion.
3. Normalized Envelope, canonical digest, and GitHub validator policy.
4. JDBC authority resolution, Edge Header and Revision, and Receipt repository.
5. Single-transaction Use Case, Service Identity, HTTP, security, and conflict recovery.
6. Exact-head GitHub Smoke, complete Gate, operations documentation, and PENDING Owner Gate.

Every Task must first observe the specified failure, then add the minimal implementation, run the targeted tests, and commit. All non-Markdown files from Task 1 through Task 6 must be byte-identical on the Chinese and English branches; translate Markdown prose only.

### Task 1: Contract v2 and Consumer Inventory

**Files:**

- Modify: `contracts/openapi/v0.2/openapi.json`
- Modify: `contracts/openapi/v0.2/compatibility-baseline.json`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/M2ApiContractTest.kt`
- Create: `docs/m2/traceability-v1-consumer-inventory.md`

**Interfaces:**

- Consumes: The frozen Path, Method, `traceability:ingest`, `Idempotency-Key`, and `serviceOauth`.
- Produces: The `BuildProvenanceEnvelope` request schema, `BuildProvenanceResult` response schema, and exact Contract that the Task 5 Controller must implement.

- [ ] **Step 1: Write the failing v2 Contract tests**

Remove the v1 `TraceabilityFactInput` assertions from `M2ApiContractTest` and add strict v2 schema assertions:

```kotlin
@Test
fun `traceability ingestion contract is one strict build provenance envelope v2`() {
    val operation = openApi.at("/paths/~1api~1v1~1traceability~1facts:ingest/post")
    assertThat(operation.path("x-service-identity-only").booleanValue()).isTrue()
    assertThat(operation.at("/requestBody/${'$'}ref").textValue())
        .isEqualTo("#/components/requestBodies/BuildProvenanceEnvelope")

    val schema = openApi.at("/components/schemas/BuildProvenanceEnvelope")
    assertStrictObject(schema, BUILD_PROVENANCE_FIELDS)
    assertThat(schema.at("/properties/schemaVersion/const").intValue()).isEqualTo(2)
    assertThat(schema.at("/properties/sourceIssueIds/maxItems").intValue()).isEqualTo(20)
    assertThat(schema.at("/properties/sourceIssueIds/uniqueItems").booleanValue()).isTrue()
    assertThat(schema.at("/properties/artifactSha256s/maxItems").intValue()).isEqualTo(20)
    assertThat(schema.at("/properties/artifactSha256s/uniqueItems").booleanValue()).isTrue()
    assertThat(openApi.at("/components/schemas/TraceabilityFactInput").isMissingNode).isTrue()
}
```

Also assert that the response is `BuildProvenanceResult`, the Provider enum contains only `GITHUB_ACTIONS`, `sourceRevision` is a complete 40-character lowercase Git SHA, Artifact and proof digests are lowercase SHA-256, both arrays have `minItems: 1`, `buildAttempt >= 1`, and every object has `additionalProperties: false`.

- [ ] **Step 2: Run the test and observe the old v1 schema failure**

```powershell
./backend/gradlew -p backend test --tests '*M2ApiContractTest'
```

Expected: FAIL because the request body still references `TraceabilityFactBatch`, `schemaVersion` is still 1, or the Envelope fields or response schema are missing.

- [ ] **Step 3: Replace it with the exact Envelope v2 schema**

The required `BuildProvenanceEnvelope` fields are fixed as follows:

```json
{
  "schemaVersion": 2,
  "project": "project-reference",
  "releaseIssueSnapshotId": "isnap_123",
  "provider": "GITHUB_ACTIONS",
  "repository": "owner/repository",
  "sourceRevision": "0123456789abcdef0123456789abcdef01234567",
  "pipeline": "m1-backend",
  "buildId": "33705417856",
  "buildAttempt": 1,
  "workflowReference": "owner/repository/.github/workflows/m1-backend.yml@refs/heads/main",
  "proofReference": "https://github.com/owner/repository/actions/runs/33705417856/attempts/1",
  "proofDigest": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "sourceIssueIds": ["ISSUE-1"],
  "artifactSha256s": ["0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"]
}
```

The required `BuildProvenanceResult` fields are `receiptId`, `releaseIssueSnapshotId`, `sourceCommitId`, `buildRecordId`, `envelopeDigest`, `validatorVersion`, `verificationStatus`, `confidence`, and `edgeRevisions`. Each `EdgeRevisionResult` contains exactly `edgeId`, `edgeType`, `revisionId`, `revision`, `verificationStatus`, `confidence`, and `factDigest`.

In the Compatibility baseline, change only the request body ref from `#/components/requestBodies/TraceabilityFactBatch` to `#/components/requestBodies/BuildProvenanceEnvelope`, preserving the operation count, Path, Method, Permission, and Idempotency requirement.

- [ ] **Step 4: Fix the repository consumer inventory**

`docs/m2/traceability-v1-consumer-inventory.md` must record the replayable command and conclusion:

```powershell
rg -n "TraceabilityFactBatch|TraceabilityFactInput|facts:ingest" . --glob '!docs/superpowers/**'
```

Matches are allowed only in OpenAPI, the compatibility baseline, Contract Tests, and written design. There must be no match in a Controller, Application, Adapter, fixture, script, or workflow. If any real consumer is found, stop Task 1 immediately, preserve v1, stop implementation, and reopen the TDR-017 compatibility review.

- [ ] **Step 5: Verify and commit the Contract**

```powershell
./backend/gradlew -p backend test --tests '*M2ApiContractTest' --tests '*PermissionMatrixTest'
npm run test:contracts
git diff --check
git add contracts/openapi/v0.2/openapi.json contracts/openapi/v0.2/compatibility-baseline.json backend/src/test/kotlin/com/ricezhou/vsrqg/M2ApiContractTest.kt docs/m2/traceability-v1-consumer-inventory.md
git commit -m "feat(m2): define build provenance envelope v2"
```

Expected: PASS; the operation count remains 33 and user RBAC still excludes `traceability:ingest`.

### Task 2: PostgreSQL Build and Edge Authority

**Files:**

- Create: `backend/src/main/resources/db/migration/V9__build_provenance_authority.sql`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/BuildProvenanceMigrationTest.kt`

**Interfaces:**

- Consumes: V4 `source_commit`, `build_record`, and three typed Revisions; V6 `release_issue_snapshot` and items; V1 Artifact checksum, Audit, Outbox, and principal and project authority.
- Produces: `traceability_edge_identity`, `build_provenance_receipt`, `build_provenance_rejected_receipt`, Build Attempt authority, and Revision proof columns used by Task 4.

- [ ] **Step 1: Write the failing Migration tests**

Create `BuildProvenanceMigrationTest : PostgresIntegrationTest()` and test these real PostgreSQL behaviors:

```kotlin
@Test
fun `v9 creates build attempt receipts and typed edge headers`() {
    assertThat(tableNames()).contains(
        "traceability_edge_identity",
        "build_provenance_receipt",
        "build_provenance_rejected_receipt",
    )
    assertThat(columnNames("build_record")).contains("repository", "build_attempt")
    assertThat(columnNames("issue_commit_edge_revision"))
        .contains("proof_reference", "proof_digest", "reason_code")
    assertThat(uniqueIndexExists(
        "build_record",
        listOf("project_id", "provider", "pipeline", "build_id", "build_attempt"),
    )).isTrue()
}

@Test
fun `typed revisions reject mismatched headers mutation and broken chains`() {
    seedBuildProvenanceAuthority()
    assertThatThrownBy { insertRevisionWithWrongTypeOrEndpoints() }
        .hasRootCauseInstanceOf(java.sql.SQLException::class.java)
    assertThatThrownBy { updateEdgeHeader() }
        .hasRootCauseInstanceOf(java.sql.SQLException::class.java)
    assertThatThrownBy { insertRevisionSkippingPrevious() }
        .hasRootCauseInstanceOf(java.sql.SQLException::class.java)
}
```

Also test that UNIQUE rejects a second row for the same Build Attempt, concurrent inserts converge on one Edge endpoint tuple, historical nullable Builds remain readable, v2 Builds require repository and attempt together, UPDATE and DELETE fail for three Revisions and both Receipt tables, and cross-Project typed FKs fail.

- [ ] **Step 2: Run and observe the missing V9 objects**

```powershell
./backend/gradlew -p backend test --tests '*BuildProvenanceMigrationTest'
```

Expected: FAIL with explicit missing V9 table, column, index, or constraint failures, not a silent Docker or fixture skip.

- [ ] **Step 3: Write the forward-only V9 Migration**

The Migration first runs a redacted precondition. If existing `(project_id, provider, pipeline, build_id)` data cannot be safely expanded, stop with fixed diagnostic `BUILD_AUTHORITY_PRECONDITION_FAILED`; never delete or infer data. The core structure is:

```sql
CREATE TABLE traceability_edge_identity (
    edge_id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    edge_type varchar(40) NOT NULL CHECK (edge_type IN ('ISSUE_COMMIT', 'COMMIT_BUILD', 'BUILD_ARTIFACT')),
    from_entity_id varchar(40) NOT NULL,
    to_entity_id varchar(40) NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (edge_id, project_id),
    UNIQUE (project_id, edge_type, from_entity_id, to_entity_id)
);

ALTER TABLE build_record
    ADD COLUMN repository varchar(512),
    ADD COLUMN build_attempt integer,
    ADD CONSTRAINT ck_build_record_v2_authority CHECK (
        (repository IS NULL AND build_attempt IS NULL)
        OR (repository IS NOT NULL AND build_attempt >= 1)
    );
ALTER TABLE build_record DROP CONSTRAINT uq_build_record_identity;
CREATE UNIQUE INDEX uq_build_record_attempt_authority
    ON build_record(project_id, provider, pipeline, build_id, build_attempt)
    WHERE repository IS NOT NULL AND build_attempt IS NOT NULL;
```

`build_provenance_receipt` is unique on `(project_id, provider, pipeline, provider_build_id, build_attempt)` and stores the Envelope digest, Snapshot, Commit and Build FKs, validator version, status and confidence, response JSON, and counts. `build_provenance_rejected_receipt` stores the accepted receipt, rejected digest, fixed diagnostic, actor, and time; it stores no raw payload or Provider response:

```sql
CREATE TABLE build_provenance_receipt (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    provider varchar(40) NOT NULL,
    pipeline varchar(255) NOT NULL,
    provider_build_id varchar(255) NOT NULL,
    build_attempt integer NOT NULL CHECK (build_attempt >= 1),
    envelope_digest varchar(71) NOT NULL CHECK (envelope_digest ~ '^sha256:[0-9a-f]{64}$'),
    release_issue_snapshot_id varchar(40) NOT NULL,
    source_commit_id varchar(40) NOT NULL,
    build_record_id varchar(40) NOT NULL,
    validator_version varchar(80) NOT NULL,
    verification_status varchar(20) NOT NULL,
    confidence varchar(20) NOT NULL,
    issue_count integer NOT NULL CHECK (issue_count BETWEEN 1 AND 20),
    artifact_count integer NOT NULL CHECK (artifact_count BETWEEN 1 AND 20),
    edge_count integer NOT NULL CHECK (edge_count BETWEEN 3 AND 100),
    response_body jsonb NOT NULL,
    actor_id varchar(40) NOT NULL REFERENCES principal(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    UNIQUE (project_id, provider, pipeline, provider_build_id, build_attempt),
    FOREIGN KEY (release_issue_snapshot_id, project_id)
        REFERENCES release_issue_snapshot(id, project_id) ON DELETE RESTRICT,
    FOREIGN KEY (source_commit_id, project_id)
        REFERENCES source_commit(id, project_id) ON DELETE RESTRICT,
    FOREIGN KEY (build_record_id, project_id)
        REFERENCES build_record(id, project_id) ON DELETE RESTRICT
);

CREATE TABLE build_provenance_rejected_receipt (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    accepted_receipt_id varchar(40) NOT NULL REFERENCES build_provenance_receipt(id) ON DELETE RESTRICT,
    rejected_envelope_digest varchar(71) NOT NULL CHECK (rejected_envelope_digest ~ '^sha256:[0-9a-f]{64}$'),
    diagnostic_code varchar(80) NOT NULL CHECK (diagnostic_code = 'BUILD_PROVENANCE_CONFLICT'),
    actor_id varchar(40) NOT NULL REFERENCES principal(id) ON DELETE RESTRICT,
    attempted_at timestamptz NOT NULL,
    UNIQUE (accepted_receipt_id, rejected_envelope_digest)
);
```

- [ ] **Step 4: Add typed Revision authority**

Add nullable historical columns `proof_reference`, `proof_digest`, and `reason_code` to all three Revision tables; constraints require all three for rows with new validator version `github-actions-provenance/v1`. Add an `(edge_id, project_id)` FK from every table to the Header and use a `DEFERRABLE INITIALLY DEFERRED` constraint trigger to ensure the Header `edge_type`, `from_entity_id`, and `to_entity_id` exactly match typed endpoints.

Replace the V4 `enforce_*_edge_identity` functions so that they protect only Project and endpoints; `source_type`, `source_reference`, proof, status, confidence, validator, and reason may change through a new Revision. Preserve existing previous-revision composite FKs and every immutable trigger.

- [ ] **Step 5: Verify clean database, V8 upgrade, repeat, and concurrency**

```powershell
./backend/gradlew -p backend test --tests '*BuildProvenanceMigrationTest' --tests '*M2MigrationConstraintTest' --tests '*MigrationConstraintTest'
```

Expected: PASS with explicit assertions for a clean Flyway migration, V8-to-V9 upgrade, second migrate, constraints, triggers, and two concurrent transactions.

- [ ] **Step 6: Commit PostgreSQL Authority**

```powershell
git add backend/src/main/resources/db/migration/V9__build_provenance_authority.sql backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/BuildProvenanceMigrationTest.kt
git commit -m "feat(m2): add build provenance authority"
```

### Task 3: Canonical Envelope and GitHub Validation Policy

**Files:**

- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/domain/BuildProvenanceModels.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/BuildProvenanceCanonicalizer.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/BuildProvenanceValidatorPort.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/JcsBuildProvenanceCanonicalizer.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/GithubActionsBuildProvenanceValidator.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/BuildProvenanceCanonicalizerTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/GithubActionsBuildProvenanceValidatorTest.kt`

**Interfaces:**

- Consumes: The exact Task 1 Envelope fields and V0.1 Traceability edge types.
- Produces: `BuildProvenanceEnvelope`, `CanonicalBuildProvenance`, `BuildProvenanceValidatorPort.validate()`, and the immutable validation observation consumed by Tasks 4 and 5.

- [ ] **Step 1: Write the failing canonicalization tests**

```kotlin
@Test
fun `canonical envelope sorts sets and excludes request metadata`() {
    val first = canonicalizer.canonicalize(envelope(
        sourceIssueIds = listOf("ISSUE-2", "ISSUE-1"),
        artifactSha256s = listOf(DIGEST_B, DIGEST_A),
    ))
    val second = canonicalizer.canonicalize(envelope(
        sourceIssueIds = listOf("ISSUE-1", "ISSUE-2"),
        artifactSha256s = listOf(DIGEST_A, DIGEST_B),
    ))
    assertThat(first.envelopeDigest).isEqualTo(second.envelopeDigest)
    assertThat(first.normalized.sourceIssueIds).containsExactly("ISSUE-1", "ISSUE-2")
    assertThat(first.normalized.artifactSha256s).containsExactly(DIGEST_A, DIGEST_B)
}
```

Add tests for duplicates, control characters, Unicode NFC, complete Git SHA, lowercase Artifact digest, Attempt, 20/20 limits, 100 facts, repository, workflow and proof allowlists, and three-run byte stability.

- [ ] **Step 2: Write the failing GitHub validator tests**

```kotlin
@Test
fun `matching GitHub proof is valid medium and never high`() {
    val observation = validator.validate(canonicalizer.canonicalize(githubEnvelope()))
    assertThat(observation.verificationStatus).isEqualTo(VerificationStatus.VALID)
    assertThat(observation.confidence).isEqualTo(Confidence.MEDIUM)
    assertThat(observation.validatorVersion).isEqualTo("github-actions-provenance/v1")
}

@Test
fun `proof digest mismatch is an invalid low observation`() {
    val observation = validator.validate(canonicalizer.canonicalize(githubEnvelope(proofDigest = OTHER_DIGEST)))
    assertThat(observation.verificationStatus).isEqualTo(VerificationStatus.INVALID)
    assertThat(observation.confidence).isEqualTo(Confidence.LOW)
    assertThat(observation.reasonCode).isEqualTo("PROOF_DIGEST_MISMATCH")
}
```

- [ ] **Step 3: Define provider-neutral immutable models**

```kotlin
@JvmInline
value class ProvenanceProviderId(val value: String)
enum class TraceabilityEdgeType { ISSUE_COMMIT, COMMIT_BUILD, BUILD_ARTIFACT }
enum class VerificationStatus { VALID, INVALID, CONFLICT, ERROR }
enum class Confidence { HIGH, MEDIUM, LOW, UNKNOWN }

data class BuildProvenanceEnvelope(
    val schemaVersion: Int,
    val projectReference: String,
    val releaseIssueSnapshotId: String,
    val provider: ProvenanceProviderId,
    val repository: String,
    val sourceRevision: String,
    val pipeline: String,
    val buildId: String,
    val buildAttempt: Int,
    val workflowReference: String,
    val proofReference: String,
    val proofDigest: String,
    val sourceIssueIds: List<String>,
    val artifactSha256s: List<String>,
)

data class ProvenanceValidation(
    val verificationStatus: VerificationStatus,
    val confidence: Confidence,
    val validatorVersion: String,
    val reasonCode: String,
)

data class CanonicalBuildProvenance(
    val normalized: BuildProvenanceEnvelope,
    val canonicalBytes: ByteArray,
    val envelopeDigest: String,
    val recomputedProofDigest: String,
    val derivedFactCount: Int,
)
```

Do not add Fixed, Included, Verified, Quality Result, GitHub event, credential, author email, or runner path to the model. `GITHUB_ACTIONS` exists only in the HTTP DTO and GitHub Adapter. The Adapter maps it to `ProvenanceProviderId("github-actions")`; Domain and Application contain no GitHub enum.

- [ ] **Step 4: Implement JCS digests and hard limits**

`JcsBuildProvenanceCanonicalizer` uses the existing `org.erdtman.jcs.JsonCanonicalizer`. The Envelope digest covers all normalized request fields. The server recomputes the proof digest from provider, repository, sourceRevision, pipeline, buildId, buildAttempt, workflowReference, and proofReference. Both use `sha256:` followed by 64 lowercase hex characters.

Sorting uses Unicode code point order. Do not silently deduplicate duplicates; throw an allowlisted `BuildProvenanceInvalid`. `derivedFactCount = sourceIssueIds.size + 1 + artifactSha256s.size`, and a value over 100 returns `FACT_LIMIT_EXCEEDED`.

- [ ] **Step 5: Implement the GitHub Actions Pilot validator**

`BuildProvenanceValidatorPort` is fixed as:

```kotlin
fun interface BuildProvenanceValidatorPort {
    fun validate(provenance: CanonicalBuildProvenance): ProvenanceValidation
}
```

The Adapter accepts only a matching repository and workflow owner/repo, proof host `github.com`, exact path `/{repository}/actions/runs/{buildId}/attempts/{buildAttempt}`, and proof digest equal to the server recomputation. A match returns `VALID/MEDIUM/PROOF_MATCHED`; a digest difference returns `INVALID/LOW/PROOF_DIGEST_MISMATCH`; an indeterminate result returns `ERROR/UNKNOWN/PROOF_UNAVAILABLE`. No branch may return `HIGH`.

- [ ] **Step 6: Verify and commit the canonical policy**

```powershell
./backend/gradlew -p backend test --tests '*BuildProvenanceCanonicalizerTest' --tests '*GithubActionsBuildProvenanceValidatorTest' --tests '*ArchitectureTest'
git add backend/src/main/kotlin/com/ricezhou/vsrqg/traceability backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/BuildProvenanceCanonicalizerTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/GithubActionsBuildProvenanceValidatorTest.kt
git commit -m "feat(m2): validate canonical build provenance"
```

Expected: PASS. GitHub types exist only in the Adapter; Domain and Application do not depend on a GitHub DTO or SDK.

### Task 4: JDBC Authority Resolution and Revision Repository

**Files:**

- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/BuildProvenanceRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/BuildProvenanceRecords.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/JdbcBuildProvenanceRepository.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/BuildProvenanceRepositoryIntegrationTest.kt`

**Interfaces:**

- Consumes: Task 2 schema, Task 3 canonical models, and existing M2.3 Snapshot, Artifact checksum, and ID generator.
- Produces: The typed repository used by the Task 5 Use Case; JDBC and internal queries are not exposed to the Controller.

- [ ] **Step 1: Write the failing authority resolution tests**

Cover Snapshot project and immutable digest, Issues only within Snapshot Items, Artifact checksum, Commit and Build reuse, same Build Attempt receipt replay, stable Edge Header identity, same fact digest Revision reuse, new proof appending a Revision, cross-Project or missing resources, and stable ordering.

```kotlin
@Test
fun `repository resolves only snapshot issues and appends typed revisions`() {
    val context = repository.lockContext(PROJECT_KEY, SNAPSHOT_ID)!!
    val issues = repository.resolveSnapshotIssues(context, listOf("ISSUE-2", "ISSUE-1"))
    val artifacts = repository.resolveArtifacts(context.projectId, listOf(DIGEST_B, DIGEST_A))
    val commit = repository.resolveCommit(context.projectId, REPOSITORY, SOURCE_REVISION, NOW)
    val build = repository.resolveBuild(context.projectId, attemptKey(), REPOSITORY, SOURCE_REVISION, NOW)
    val revisions = repository.appendRevisions(edgeCandidates(issues, commit, build, artifacts), validation(), NOW)

    assertThat(revisions.map { it.edgeType }).containsExactly(
        TraceabilityEdgeType.ISSUE_COMMIT,
        TraceabilityEdgeType.ISSUE_COMMIT,
        TraceabilityEdgeType.COMMIT_BUILD,
        TraceabilityEdgeType.BUILD_ARTIFACT,
        TraceabilityEdgeType.BUILD_ARTIFACT,
    )
    assertThat(revisions).allMatch { it.revision == 1 }
}
```

- [ ] **Step 2: Run and observe the missing repository**

```powershell
./backend/gradlew -p backend test --tests '*BuildProvenanceRepositoryIntegrationTest'
```

Expected: compilation FAIL because `BuildProvenanceRepository` and its JDBC Adapter do not exist.

- [ ] **Step 3: Define the exact repository contract**

```kotlin
interface BuildProvenanceRepository {
    fun lockContext(projectReference: String, snapshotId: String): BuildProvenanceContext?
    fun findReceipt(key: BuildAttemptKey): BuildProvenanceReceipt?
    fun resolveSnapshotIssues(context: BuildProvenanceContext, sourceIssueIds: List<String>): List<IssueEndpoint>
    fun resolveArtifacts(projectId: String, artifactSha256s: List<String>): List<ArtifactEndpoint>
    fun resolveCommit(projectId: String, repository: String, sourceRevision: String, now: Instant): CommitEndpoint
    fun resolveBuild(projectId: String, key: BuildAttemptKey, repository: String, sourceRevision: String, now: Instant): BuildEndpoint
    fun appendRevisions(candidates: List<EdgeCandidate>, validation: ProvenanceValidation, now: Instant): List<EdgeRevisionRecord>
    fun insertReceipt(receipt: BuildProvenanceReceipt)
    fun readReceipt(receiptId: String): BuildProvenanceReceipt?
}
```

`BuildAttemptKey` contains exactly projectId, provider, pipeline, buildId, and buildAttempt. `BuildProvenanceContext` fixes projectId and projectKey, snapshotId and releaseId, and Snapshot content digest. An Issue endpoint fixes issueId and sourceIssueId; an Artifact endpoint fixes artifactId and checksum.

Task 4 also defines these cross-task records. Every `List` is defensively copied at construction and exposed in stable order:

```kotlin
data class BuildAttemptKey(
    val projectId: String,
    val provider: ProvenanceProviderId,
    val pipeline: String,
    val buildId: String,
    val buildAttempt: Int,
)

data class BuildProvenanceContext(
    val projectId: String,
    val projectReference: String,
    val snapshotId: String,
    val releaseId: String,
    val snapshotDigest: String,
)

data class IssueEndpoint(val issueId: String, val sourceIssueId: String)
data class ArtifactEndpoint(val artifactId: String, val checksumSha256: String)
data class CommitEndpoint(val commitId: String)
data class BuildEndpoint(val buildRecordId: String)

data class EdgeCandidate(
    val projectId: String,
    val edgeType: TraceabilityEdgeType,
    val fromEntityId: String,
    val toEntityId: String,
    val sourceType: String,
    val sourceReference: String,
    val proofReference: String,
    val proofDigest: String,
)

data class EdgeRevisionRecord(
    val edgeId: String,
    val edgeType: TraceabilityEdgeType,
    val revisionId: String,
    val revision: Int,
    val verificationStatus: VerificationStatus,
    val confidence: Confidence,
    val factDigest: String,
)

data class BuildProvenanceResult(
    val receiptId: String,
    val releaseIssueSnapshotId: String,
    val sourceCommitId: String,
    val buildRecordId: String,
    val envelopeDigest: String,
    val validatorVersion: String,
    val verificationStatus: VerificationStatus,
    val confidence: Confidence,
    val edgeRevisions: List<EdgeRevisionRecord>,
)

data class BuildProvenanceReceipt(
    val receiptId: String,
    val key: BuildAttemptKey,
    val envelopeDigest: String,
    val result: BuildProvenanceResult,
    val issueCount: Int,
    val artifactCount: Int,
    val actorId: String,
    val createdAt: Instant,
)
```

- [ ] **Step 4: Implement parameterized resolution and identity checks**

`lockContext` performs `FOR UPDATE` in Project then Snapshot order and reads the Snapshot header, item count, and digest; it must not query the current latest Issue. The Artifact query uses `checksum_algorithm = 'SHA-256' AND checksum_value IN (...)`; its result count must equal the request exactly or fail closed.

`resolveCommit` uses `(project_id, repository, commit_id)`. `resolveBuild` uses the five-column Build Attempt authority from Task 2 and validates repository and sourceRevision. Every `INSERT ... ON CONFLICT DO NOTHING` must be followed by read-back and field-by-field comparison; never treat a conflict as success.

- [ ] **Step 5: Implement Header locking and append-only Revision**

Sort Edge candidates by `edgeType.name`, fromId, and toId. For each candidate, upsert the Header and then use `SELECT ... FOR UPDATE`. Read the latest Revision, return it when the canonical fact digest is identical, or insert `revision + 1` with the previous identity.

The canonical fact digest contains edge type and endpoints, `sourceType`, `sourceReference`, proof reference and digest, verification status, confidence, validator version, and reason code. It excludes Revision ID, revision number, request ID, Idempotency Key, `verifiedAt`, and `createdAt`. When the latest Revision is `VALID` and a new observation for the same Edge is `INVALID`, insert `CONFLICT/LOW/PROOF_CONTRADICTS_ACCEPTED`. A different new `VALID` proof with no counter-evidence appends another `VALID` Revision without treating difference as contradiction. `ERROR/UNKNOWN` must never reuse an old `VALID`.

- [ ] **Step 6: Verify concurrency, replay, and immutability**

```powershell
./backend/gradlew -p backend test --tests '*BuildProvenanceRepositoryIntegrationTest' --tests '*BuildProvenanceMigrationTest'
```

Expected: PASS. Two transactions for the same Header and fact converge on one Revision, new proof produces revision 2, old Revision bytes and digest remain unchanged, and the database is the final concurrency guard.

- [ ] **Step 7: Commit repository authority**

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/BuildProvenanceRepository.kt backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/BuildProvenanceRecords.kt backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/JdbcBuildProvenanceRepository.kt backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/BuildProvenanceRepositoryIntegrationTest.kt
git commit -m "feat(m2): persist typed provenance revisions"
```

### Task 5: Transactional Ingestion, Service Identity, and Conflict Recovery

**Files:**

- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/TraceabilityIngestAuthorizer.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/BuildProvenanceConflictRecorder.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/IngestBuildProvenance.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/BuildProvenanceTransaction.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/JdbcTraceabilityIngestAuthorizer.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/JdbcBuildProvenanceConflictRecorder.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/BuildProvenanceController.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/BuildProvenancePayloadLimitFilter.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/BuildProvenanceConfiguration.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/SafeValidationFailure.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/problem/ProblemHandler.kt`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/BuildProvenanceIntegrationTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/BuildProvenanceTransactionFailureTest.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/access/SecurityAcceptanceTest.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/access/PermissionMatrixTest.kt`

**Interfaces:**

- Consumes: Tasks 1 through 4 Contract, canonicalization, validator, and repository, plus existing `IdempotentExecutor`, `GovernanceStore`, and JWT principal mapper.
- Produces: An HTTP-verifiable M2.4 Pilot ingestion path; Task 6 may create live Evidence only through this path.

- [ ] **Step 1: Write the failing end-to-end Application and API tests**

Cover Feature disabled 404, Service scope, USER plus scope 403, missing project claim 403, claim and body mismatch 403, disabled or unassigned SERVICE 403, Snapshot, Issue, or Artifact 404, invalid input 422, same-key replay, same key with different digest 409, same Build Attempt with different-key replay, same Build Attempt with different Envelope conflict and rejected receipt, proof contradiction Revision, Audit and Outbox, and absence of an `ARTIFACT_RELEASE` write.

```kotlin
@Test
fun `service ingestion creates one atomic replayable provenance chain`() {
    val first = ingest(validEnvelope(), "provenance-key", serviceToken()).andExpect {
        status { isOk() }
        jsonPath("$.verificationStatus") { value("VALID") }
        jsonPath("$.confidence") { value("MEDIUM") }
        jsonPath("$.edgeRevisions.length()") { value(3) }
    }.andReturn().response.contentAsString
    val replay = ingest(validEnvelope(), "provenance-key", serviceToken())
        .andReturn().response.contentAsString
    assertThat(replay).isEqualTo(first)
    assertThat(count("traceability_edge_identity")).isEqualTo(3)
    assertThat(countArtifactReleaseWriteTables()).isZero()
}
```

- [ ] **Step 2: Write the failing transaction injection tests**

Inject SQLException before Commit, Build, the second Edge, Receipt, Audit, Outbox, and Idempotency response in turn. Assert that every Domain, Receipt, Idempotency, Audit, and Outbox count returns to baseline. For the conflict path, separately assert that accepted facts are unchanged, the outer idempotency row rolls back, and exactly one redacted `REQUIRES_NEW` rejected receipt and Audit entry remain.

- [ ] **Step 3: Implement dedicated Service Identity Project authority**

Do not add `traceability:ingest` to `Permission`. Add this contract:

```kotlin
fun interface TraceabilityIngestAuthorizer {
    fun require(
        principal: Principal,
        tokenProjectReference: String?,
        requestProjectReference: String,
    ): TraceabilityIngestAuthorization
}

data class TraceabilityIngestAuthorization(
    val principalId: String,
    val projectId: String,
    val projectReference: String,
)
```

The JDBC implementation simultaneously requires JWT `principal_type=SERVICE`, exact equality between the token project claim and body project, database principal type `SERVICE` and not disabled, a non-archived Project, and a Project assignment. `@PreAuthorize("hasAuthority('SCOPE_traceability:ingest')")` remains the scope boundary. A normal USER is rejected even when it has that scope and an ADMINISTRATOR assignment.

- [ ] **Step 4: Implement the single-transaction `IngestBuildProvenance`**

```kotlin
data class IngestBuildProvenanceCommand(
    val principal: Principal,
    val tokenProjectReference: String?,
    val envelope: BuildProvenanceEnvelope,
    val idempotencyKey: String,
    val requestId: String,
)

data class PreparedBuildProvenance(
    val authorization: TraceabilityIngestAuthorization,
    val provenance: CanonicalBuildProvenance,
    val idempotencyKey: String,
    val requestId: String,
)

class BuildProvenanceConflict(
    val acceptedReceiptId: String,
    val key: BuildAttemptKey,
    val rejectedEnvelopeDigest: String,
) : RuntimeException("BUILD_PROVENANCE_CONFLICT")

fun ingest(command: IngestBuildProvenanceCommand): BuildProvenanceResult

@Transactional
fun execute(prepared: PreparedBuildProvenance): BuildProvenanceResult
```

`IngestBuildProvenance.ingest()` is a non-transactional facade. It first applies the Feature flag, canonicalization and limits, and Service authorization, then calls the independent Bean `BuildProvenanceTransaction.execute()`. The fixed transaction sequence is `IdempotentExecutor`, Project and Snapshot locks, Snapshot project and digest validation, Build Attempt Receipt lookup, Snapshot Issue resolution, Commit, Build, and Artifact resolution, proof validation, stable Edge candidates, Revision append or reuse, Receipt insertion, Audit, Outbox, read-back counts and digests, and response.

For the same Build Attempt and same Envelope digest, read the existing Receipt and let the current Idempotency Key save the same response. A different Envelope digest throws `BuildProvenanceConflict`. Only after the Spring proxy for `BuildProvenanceTransaction` has completed rollback and returned the exception to the facade does the facade call the independent `BuildProvenanceConflictRecorder.record()`. This avoids starting a conflict transaction while Project or Receipt locks remain held. The Recorder uses `REQUIRES_NEW` to write a redacted rejected receipt and Audit entry before returning 409, and never updates an accepted Receipt, Edge, or Revision.

The conflict recording Port is fixed as:

```kotlin
fun interface BuildProvenanceConflictRecorder {
    fun record(
        acceptedReceiptId: String,
        projectId: String,
        actorId: String,
        rejectedEnvelopeDigest: String,
        requestId: String,
        attemptedAt: Instant,
    )
}
```

`JdbcBuildProvenanceConflictRecorder.record()` is marked `@Transactional(propagation = Propagation.REQUIRES_NEW)`. A replay for `(acceptedReceiptId, rejectedEnvelopeDigest)` returns the existing rejected receipt without appending duplicate Audit.

- [ ] **Step 5: Fix HTTP, payload limit, and error mapping**

The Controller DTO performs only Bean Validation and mapping into the normalized Envelope. `BuildProvenancePayloadLimitFilter` intercepts only the exact POST path and streams at most 262144 bytes. On overflow it immediately emits a fixed 413 Problem and never logs the body.

Add an allowlisted validation diagnostic. Fixed codes are `RESOURCE_NOT_FOUND`, `PROJECT_SCOPE_MISMATCH`, `SNAPSHOT_ISSUE_NOT_FOUND`, `ARTIFACT_NOT_FOUND`, `ARTIFACT_DIGEST_MISMATCH`, `PROOF_VALIDATION_FAILED`, `IDEMPOTENCY_CONFLICT`, `BUILD_PROVENANCE_CONFLICT`, `FACT_LIMIT_EXCEEDED`, and `PERSISTENCE_UNAVAILABLE`. A hidden resource is 404, both conflicts are 409, Domain validation, the fact limit, and proof failure are 422, and database unavailability is 503. Problems, logs, Audit, and Outbox contain no raw body, Token, Cookie, Authorization, provider response, stack trace, author email, or runner path.

The configuration is fixed as:

```yaml
vsrqg:
  traceability:
    ingestion:
      enabled: ${VSRQG_TRACEABILITY_INGESTION_ENABLED:false}
      max-payload-bytes: ${VSRQG_TRACEABILITY_MAX_PAYLOAD_BYTES:262144}
```

Properties require max bytes to be exactly within 1 through 262144 at startup. Company never inherits a Pilot test identity.

- [ ] **Step 6: Verify transaction, security, and regression**

```powershell
./backend/gradlew -p backend test --tests '*BuildProvenanceIntegrationTest' --tests '*BuildProvenanceTransactionFailureTest' --tests '*SecurityAcceptanceTest' --tests '*PermissionMatrixTest'
```

Expected: PASS. Every injected failure leaves no partial success, a conflict preserves only a redacted rejected receipt, and a USER can never ingest.

- [ ] **Step 7: Commit transactional ingestion**

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/traceability backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/SafeValidationFailure.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/problem/ProblemHandler.kt backend/src/main/resources/application.yml backend/src/test/kotlin/com/ricezhou/vsrqg/traceability backend/src/test/kotlin/com/ricezhou/vsrqg/access/SecurityAcceptanceTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/access/PermissionMatrixTest.kt
git commit -m "feat(m2): ingest build provenance atomically"
```

### Task 6: GitHub Actions Smoke, Regression Gate, and Owner Candidate

**Files:**

- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/BuildProvenanceGithubSmokeTest.kt`
- Create: `scripts/m2/verify-build-provenance.ps1`
- Create: `scripts/tests/m2-build-provenance-gates.tests.ps1`
- Modify: `.github/workflows/m1-backend.yml`
- Create: `docs/m2/build-provenance.md`
- Create after Subject CI: `docs/governance/acceptance/records/2026-09-03-m2-4-owner-gate-001.md`

**Interfaces:**

- Consumes: The Task 5 real HTTP Endpoint, allowlisted GitHub context, and existing Linux and Docker GitHub Actions.
- Produces: An exact-commit Smoke report, fail-closed Gate, bilingual implementation Subject, and `M2-4-OWNER-GATE-001` PENDING acceptance candidate.

- [ ] **Step 1: Write the failing Gate orchestration tests**

```powershell
Describe 'M2.4 Build Provenance Gate' {
    It 'fails closed and names the failed check' {
        $output = & $scriptUnderTest -InjectFailure 'transaction' 2>&1
        $LASTEXITCODE | Should -Not -Be 0
        ($output -join "`n") | Should -Match 'CHECK transaction FAILED'
    }

    It 'never converts a missing live smoke to pass' {
        $output = & $scriptUnderTest -RequireGithubSmoke -InjectFailure 'github-smoke' 2>&1
        $LASTEXITCODE | Should -Not -Be 0
        ($output -join "`n") | Should -Match 'CHECK github-smoke FAILED'
    }
}
```

- [ ] **Step 2: Implement the real GitHub exact-head Smoke**

`BuildProvenanceGithubSmokeTest` uses `@SpringBootTest(webEnvironment = RANDOM_PORT)` with a real Testcontainers PostgreSQL. It runs only when `GITHUB_ACTIONS=true` and all six allowlisted variables exist: `GITHUB_REPOSITORY`, `GITHUB_SHA`, `GITHUB_WORKFLOW_REF`, `GITHUB_RUN_ID`, `GITHUB_RUN_ATTEMPT`, and `GITHUB_JOB`. When any is missing under required mode, it must FAIL rather than skip.

The test creates a synthetic Project, SERVICE assignment, Locked Manifest, M2.3 Snapshot and Item, and Artifact through normal fixture APIs or JDBC, then submits the Envelope through real HTTP. It replays with the same key and a different key, and runs conflict, USER, and wrong-project negatives. Finally it queries PostgreSQL to prove complete Receipt, Edge, Revision, Audit, and Outbox data and no writable `ARTIFACT_RELEASE` table.

Write `backend/build/m2/build-provenance-smoke.json` containing only exact commit, Run and Attempt, schema and validator version, Envelope and Artifact digest, Edge and Revision IDs, replay results, fixed diagnostics, and test counts.

- [ ] **Step 3: Implement the fixed-order M2.4 Gate**

`scripts/m2/verify-build-provenance.ps1` runs this fixed sequence:

```powershell
$checks = @(
    @{ Name = 'contract'; Tests = '*M2ApiContractTest' },
    @{ Name = 'migration'; Tests = '*BuildProvenanceMigrationTest' },
    @{ Name = 'canonical'; Tests = '*BuildProvenanceCanonicalizerTest' },
    @{ Name = 'validator'; Tests = '*GithubActionsBuildProvenanceValidatorTest' },
    @{ Name = 'repository'; Tests = '*BuildProvenanceRepositoryIntegrationTest' },
    @{ Name = 'transaction'; Tests = '*BuildProvenanceIntegrationTest|*BuildProvenanceTransactionFailureTest' },
    @{ Name = 'security'; Tests = '*SecurityAcceptanceTest|*PermissionMatrixTest' },
    @{ Name = 'github-smoke'; Tests = '*BuildProvenanceGithubSmokeTest' },
    @{ Name = 'contracts'; Command = @('npm', 'run', 'test:contracts') },
    @{ Name = 'acceptance'; Command = @('npm', 'run', 'verify:acceptance') }
)
```

The script preserves real child exit codes, prints `CHECK <name> PASS|FAILED` and a fixed final summary, and never emits environment values, the Envelope body, an absolute path, or a raw exception.

- [ ] **Step 4: Attach it to the same GitHub workflow and upload redacted Evidence**

After the M1 candidate gate succeeds in `.github/workflows/m1-backend.yml`, run:

```yaml
- name: Run M2.4 build provenance gate
  shell: pwsh
  run: ./scripts/m2/verify-build-provenance.ps1 -RequireGithubSmoke

- name: Upload M2.4 build provenance evidence
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: m2-build-provenance-${{ github.sha }}
    path: backend/build/m2/build-provenance-smoke.json
    if-no-files-found: error
```

Do not add a PAT, Jira secret, company credential, write permission, or public Backend. Keep workflow permissions at the current read-only minimum.

- [ ] **Step 5: Create operations documentation and run complete local verification**

`docs/m2/build-provenance.md` records the Endpoint, Service Identity claims, Feature flag, Envelope and proof digests, fixed errors, replay, conflict receipt, entry-point shutdown, and roll-forward recovery. Run:

```powershell
./backend/gradlew -p backend test
npm run test:contracts
npm run test:acceptance
npm run verify:acceptance
pwsh -NoProfile -File scripts/tests/m2-build-provenance-gates.tests.ps1
pwsh -NoProfile -File scripts/m2/verify-build-provenance.ps1
rg -n -i 'github_pat_|ghp_|Bearer\s+[A-Za-z0-9._-]+|C:\\Users\\|S-1-5-|Authorization:|Cookie:' backend scripts docs/m2
git diff --check
```

Expected: every executable check passes and the sensitive scan finds no real secret, path, or principal. If Docker is unavailable locally, PostgreSQL and live Smoke must explicitly report not executed; only GitHub Linux and Docker CI bound to the exact commit may fill that gap, never a fabricated local PASS.

- [ ] **Step 6: Commit the bilingual implementation Subject and obtain remote Evidence**

```powershell
git add backend contracts scripts .github/workflows/m1-backend.yml docs/m2/build-provenance.md
git commit -m "test(m2): gate GitHub build provenance smoke"
git push
```

Synchronize every non-Markdown file to the English branch with identical bytes, translate Markdown, commit, and push. Run the Pair Gate on exact refs and wait for both GitHub Actions Runs to return `success` for their own exact HEAD. Record Run ID, Artifact ID, digest, createdAt, and expired state. Do not merge, Tag, release, or deploy.

- [ ] **Step 7: Create the record-only PENDING Owner Gate**

Use `git rev-parse HEAD` to obtain the real Chinese and English Subject SHAs; never handwrite a predicted commit. Acceptance record `M2-4-OWNER-GATE-001` fixes both Subject commits, both exact-head CI Runs, PostgreSQL, transaction, concurrency, GitHub Smoke, security, and Pair Gate reports, Artifact digests, and every residual risk. Initial metadata is `status: PENDING`, `owner: PENDING`, and `decisionAt: PENDING`.

Commit record-only bilingual commits, push them, and wait for record-head CI success:

```powershell
git add docs/governance/acceptance/records/2026-09-03-m2-4-owner-gate-001.md
git commit -m "docs(acceptance): submit M2.4 owner gate"
git push
```

Do not change status or begin M2.5 until the Owner provides an independent APPROVE, REJECT, or CONDITIONAL decision.

## Plan Self-Review Checklist

- Spec coverage: Task 1 covers v2 API and consumer boundaries; Task 2 covers Schema, FK, Trigger, and immutability; Task 3 covers canonicalization, limits, and validator; Task 4 covers Snapshot and Artifact authority, typed Revision, and concurrency; Task 5 covers transaction, two-layer Idempotency, Service Identity, conflict, recovery, and security; Task 6 covers GitHub Smoke, Evidence, deployment and recovery documentation, and Owner Gate.
- Type consistency: The whole plan consistently uses `ProvenanceProviderId`, `BuildProvenanceEnvelope`, `CanonicalBuildProvenance`, `ProvenanceValidation`, `BuildAttemptKey`, `EdgeCandidate`, `EdgeRevisionRecord`, `BuildProvenanceReceipt`, and `BuildProvenanceResult`.
- Authority consistency: Issues come only from an immutable Snapshot, Artifacts only from checksum authority, the Header expresses only Edge identity, Revision is proof and status history, Receipt is Build Attempt replay authority, and there is no second fact source.
- Security consistency: User RBAC does not gain `traceability:ingest`; Service JWT, database principal, Project assignment, and token and body project all agree; no raw event, credential, or provider error is persisted.
- Scope consistency: No Fixed, Included, Verified, writable `ARTIFACT_RELEASE`, M2.5, Company, real Jira, company CI, Broker, merge, Tag, release, or deployment.
- Placeholder scan: Do not leave undefined placeholder steps, generic instructions to add tests or error handling, or undefined types. Resolve future Git and CI locators from commands at execution time and write only real values into the acceptance record.

## Implementation Authorization Gate

Creation of this plan is authorized by the approved `M2-KD-2026-09-03-01` Written Spec Review. The plan itself does not authorize production code, Migration, or GitHub Smoke execution. Before Task 1 starts, obtain the independent Project Owner instruction:

```text
APPROVE SUBAGENT-DRIVEN EXECUTION OF M2.4 BUILD PROVENANCE ENVELOPE
```

Authorization covers only the Task 1 through Task 6 Pilot implementation, synthetic and live GitHub Actions tests, bilingual paired commits, and CI. It excludes real Jira, company CI, Company, M2.5, `main` or `release` merge, Tag, release, and production deployment.
