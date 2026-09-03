# M2.4 Build Provenance Envelope Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现一个 Build Attempt 对应一个 `BuildProvenanceEnvelope schemaVersion: 2` 的 M2.4 Pilot，在单个 PostgreSQL transaction 中形成可审计、可重放、append-only 的 Issue→Commit→Build→Artifact provenance facts。

**Architecture:** 保持现有 Kotlin/Spring Boot Modular Monolith，由 Traceability Adapter 接收 provider DTO，由 Application 处理 normalized Envelope，由 PostgreSQL 保存 Project-scoped Commit、Build Attempt、Edge Header/typed Revision、Receipt、Audit、Outbox 与 Idempotency。Issue 只能从 M2.3 immutable Snapshot 解析，Artifact 只能按既有 SHA-256 authority 解析；GitHub Actions 是第一个 Adapter，不能进入 Core，也不能产生 Fixed/Included/Verified 或 `ARTIFACT_RELEASE`。

**Tech Stack:** Kotlin/JVM 21、Spring Boot、Spring JDBC、PostgreSQL 17、Flyway、Jackson、RFC 8785 JCS、JUnit 5、AssertJ、MockMvc、Testcontainers、PowerShell、GitHub Actions。

**Spec:** `docs/superpowers/specs/2026-09-03-m2-build-provenance-envelope-design.md`

## Global Constraints

- V0.1 `0.1.0` Core Contract、Release-centric architecture、Manifest authority、Evidence、Traceability、Deterministic Quality Engine、Adapter、Plugin 与 ADR governance 保持冻结。
- Endpoint 固定为 `POST /api/v1/traceability/facts:ingest`，scope 固定为 `traceability:ingest`，且只允许 Service Identity。
- API body 明确使用 `schemaVersion: 2` supersede 未实现且无 consumer 的 v1 pre-release 草案；Path、Method、Permission 与 `Idempotency-Key` 不变。
- 一个 Envelope 只描述一个 Project、Provider 和 Build Attempt；`sourceIssueIds` 与 `artifactSha256s` 各最多 20，derived facts 最多 100，请求体最多 256 KiB。
- M2.4 只写 `ISSUE_COMMIT`、`COMMIT_BUILD`、`BUILD_ARTIFACT`；不得新增可写 `ARTIFACT_RELEASE` 路径或 Artifact `build_id`。
- Snapshot、Commit、Build、Artifact、Edge、Receipt、Idempotency、Audit 与 Outbox 的成功路径必须位于一个 PostgreSQL transaction；任一写入失败整体回滚。
- 历史 Edge Revision、Receipt、rejected receipt、Commit 与 Build 禁止 UPDATE/DELETE；新 observation 只能追加 Revision。
- GitHub Actions Pilot 的成功 Confidence 最高为 `MEDIUM`；无签名 proof 不得产生 `HIGH`。
- Pilot Feature 默认关闭；不新增 Broker、Redis、图数据库、独立 Service、Raw Event Store、对象存储或 UI。
- 不访问真实 Jira、公司 CI 或 Company 环境；不 merge、Tag、release 或 production deploy。

---

## 文件结构与提交顺序

本计划锁定六个独立提交边界：

1. Contract v2 与 consumer inventory。
2. PostgreSQL authority expansion。
3. normalized Envelope、canonical digest 与 GitHub validator policy。
4. JDBC authority resolution、Edge Header/Revision 与 Receipt repository。
5. 单事务 Use Case、Service Identity、HTTP、安全与冲突恢复。
6. exact-head GitHub Smoke、完整 Gate、运维文档与 PENDING Owner Gate。

每个 Task 必须先看到指定失败，再写最小实现，再运行目标测试并提交。Task 1～Task 6 的所有非 Markdown 文件在中英文分支保持 byte-identical；Markdown 只做等义翻译。

### Task 1: Contract v2 and Consumer Inventory

**Files:**

- Modify: `contracts/openapi/v0.2/openapi.json`
- Modify: `contracts/openapi/v0.2/compatibility-baseline.json`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/M2ApiContractTest.kt`
- Create: `docs/m2/traceability-v1-consumer-inventory.md`

**Interfaces:**

- Consumes: 已冻结的 Path、Method、`traceability:ingest`、`Idempotency-Key` 与 `serviceOauth`。
- Produces: `BuildProvenanceEnvelope` request schema、`BuildProvenanceResult` response schema，以及 Task 5 Controller 必须实现的 exact Contract。

- [ ] **Step 1: 写失败的 v2 Contract tests**

在 `M2ApiContractTest` 删除 v1 `TraceabilityFactInput` 断言，新增 strict v2 schema 断言：

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

同时断言 response 为 `BuildProvenanceResult`，Provider enum 仅包含 `GITHUB_ACTIONS`，`sourceRevision` 是完整 40 位小写 Git SHA，Artifact/proof digest 为小写 SHA-256，两个数组 `minItems: 1`，`buildAttempt >= 1`，所有 object 均 `additionalProperties: false`。

- [ ] **Step 2: 运行测试并确认旧 v1 schema 失败**

```powershell
./backend/gradlew -p backend test --tests '*M2ApiContractTest'
```

Expected: FAIL，指出 request body 仍引用 `TraceabilityFactBatch`、`schemaVersion` 仍为 1、缺少 Envelope 字段或 response schema。

- [ ] **Step 3: 替换为 exact Envelope v2 schema**

`BuildProvenanceEnvelope` 必需字段固定为：

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

`BuildProvenanceResult` 必需字段固定为 `receiptId`、`releaseIssueSnapshotId`、`sourceCommitId`、`buildRecordId`、`envelopeDigest`、`validatorVersion`、`verificationStatus`、`confidence` 和 `edgeRevisions`。每个 `EdgeRevisionResult` 固定包含 `edgeId`、`edgeType`、`revisionId`、`revision`、`verificationStatus`、`confidence`、`factDigest`。

Compatibility baseline 只把 request body ref 从 `#/components/requestBodies/TraceabilityFactBatch` 改为 `#/components/requestBodies/BuildProvenanceEnvelope`，保留 operation 数量、Path、Method、Permission 与 Idempotency 要求。

- [ ] **Step 4: 固定 repository consumer inventory**

`docs/m2/traceability-v1-consumer-inventory.md` 必须记录可重放命令与结论：

```powershell
rg -n "TraceabilityFactBatch|TraceabilityFactInput|facts:ingest" . --glob '!docs/superpowers/**'
```

只允许命中 OpenAPI、compatibility baseline、Contract Test 与书面设计，不得命中 Controller、Application、Adapter、fixture、script 或 workflow。若命中任何真实 consumer，立即停止 Task 1，保持 v1，不继续实施并重新打开 TDR-017 compatibility review。

- [ ] **Step 5: 验证并提交 Contract**

```powershell
./backend/gradlew -p backend test --tests '*M2ApiContractTest' --tests '*PermissionMatrixTest'
npm run test:contracts
git diff --check
git add contracts/openapi/v0.2/openapi.json contracts/openapi/v0.2/compatibility-baseline.json backend/src/test/kotlin/com/ricezhou/vsrqg/M2ApiContractTest.kt docs/m2/traceability-v1-consumer-inventory.md
git commit -m "feat(m2): define build provenance envelope v2"
```

Expected: PASS；operation 数仍为 33，用户 RBAC 仍不包含 `traceability:ingest`。

### Task 2: PostgreSQL Build and Edge Authority

**Files:**

- Create: `backend/src/main/resources/db/migration/V9__build_provenance_authority.sql`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/BuildProvenanceMigrationTest.kt`

**Interfaces:**

- Consumes: V4 的 `source_commit`、`build_record` 与三类 typed Revision，V6 的 `release_issue_snapshot`/items，V1 的 Artifact checksum、Audit、Outbox 与 principal/project authority。
- Produces: Task 4 使用的 `traceability_edge_identity`、`build_provenance_receipt`、`build_provenance_rejected_receipt`、Build Attempt authority 与 Revision proof columns。

- [ ] **Step 1: 写失败的 Migration tests**

创建 `BuildProvenanceMigrationTest : PostgresIntegrationTest()`，测试以下真实 PostgreSQL 行为：

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

另测：同一 Build Attempt 不同 row 被 UNIQUE 拒绝、同 Edge endpoints 并发收敛、历史 nullable Build 可读、v2 Build 必须同时具有 repository/attempt、三个 Revision/两种 Receipt UPDATE/DELETE 被拒绝、cross-Project typed FK 被拒绝。

- [ ] **Step 2: 运行并确认 V9 objects 缺失**

```powershell
./backend/gradlew -p backend test --tests '*BuildProvenanceMigrationTest'
```

Expected: FAIL，明确缺少 V9 table/column/index/constraint，而不是 Docker 或 fixture 静默跳过。

- [ ] **Step 3: 编写 forward-only V9 Migration**

Migration 必须先运行脱敏 precondition，发现既有 `(project_id, provider, pipeline, build_id)` 无法安全扩展时用固定 diagnostic `BUILD_AUTHORITY_PRECONDITION_FAILED` 中止，不删除或猜测数据。核心结构为：

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

`build_provenance_receipt` 对 `(project_id, provider, pipeline, provider_build_id, build_attempt)` 唯一，保存 Envelope digest、Snapshot/Commit/Build FK、validator version、status/confidence、response JSON 与 counts。`build_provenance_rejected_receipt` 保存 accepted receipt、rejected digest、固定 diagnostic、actor 与时间；不得保存 raw payload 或 Provider response：

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

- [ ] **Step 4: 增加 typed Revision authority**

三张 Revision 表增加 nullable historical columns `proof_reference`、`proof_digest`、`reason_code`；新 validator version `github-actions-provenance/v1` 的行由约束要求三者非空。每张表增加 `(edge_id, project_id)` FK 到 Header，并用 `DEFERRABLE INITIALLY DEFERRED` constraint trigger 校验 Header 的 `edge_type`、`from_entity_id`、`to_entity_id` 与 typed endpoints 完全一致。

替换 V4 的 `enforce_*_edge_identity` 函数，使其只保护 Project 与 endpoints；`source_type`、`source_reference`、proof、status、confidence、validator 和 reason 可以通过新 Revision 变化。保留现有 previous revision composite FK 和所有 immutable triggers。

- [ ] **Step 5: 验证空库、V8 upgrade、repeat 与并发**

```powershell
./backend/gradlew -p backend test --tests '*BuildProvenanceMigrationTest' --tests '*M2MigrationConstraintTest' --tests '*MigrationConstraintTest'
```

Expected: PASS；Flyway clean migration、V8→V9 upgrade、第二次 migrate、constraint、trigger 与两个并发 transaction 均有显式断言。

- [ ] **Step 6: 提交 PostgreSQL Authority**

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

- Consumes: Task 1 exact Envelope fields and V0.1 Traceability edge types。
- Produces: `BuildProvenanceEnvelope`、`CanonicalBuildProvenance`、`BuildProvenanceValidatorPort.validate()` 和 Task 4/5 使用的 immutable validation observation。

- [ ] **Step 1: 写失败的 canonicalization tests**

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

增加 duplicate、control character、Unicode NFC、完整 Git SHA、小写 Artifact digest、Attempt、20/20、100 facts、repository/workflow/proof allowlist 和三次 byte stability 测试。

- [ ] **Step 2: 写失败的 GitHub validator tests**

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

- [ ] **Step 3: 定义 provider-neutral immutable models**

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

不得在 model 中加入 Fixed、Included、Verified、Quality Result、GitHub event、credential、author email 或 runner path。`GITHUB_ACTIONS` 只存在于 HTTP DTO 与 GitHub Adapter；Adapter 将其映射为 `ProvenanceProviderId("github-actions")`，Domain/Application 不包含 GitHub enum。

- [ ] **Step 4: 实现 JCS digests 与 hard limits**

`JcsBuildProvenanceCanonicalizer` 使用既有 `org.erdtman.jcs.JsonCanonicalizer`。Envelope digest 覆盖所有 normalized request fields；proof digest 的服务端重算输入固定为 provider、repository、sourceRevision、pipeline、buildId、buildAttempt、workflowReference、proofReference。两个 digest 都使用 `sha256:` + 64 位小写 hex。

排序使用 Unicode code point order；duplicate 不做静默去重，直接抛出 allowlisted `BuildProvenanceInvalid`。`derivedFactCount = sourceIssueIds.size + 1 + artifactSha256s.size`，大于 100 时返回 `FACT_LIMIT_EXCEEDED`。

- [ ] **Step 5: 实现 GitHub Actions Pilot validator**

`BuildProvenanceValidatorPort` 固定为：

```kotlin
fun interface BuildProvenanceValidatorPort {
    fun validate(provenance: CanonicalBuildProvenance): ProvenanceValidation
}
```

Adapter 只接受：repository 与 workflow owner/repo 相同、proof host 为 `github.com`、path 精确为 `/{repository}/actions/runs/{buildId}/attempts/{buildAttempt}`、proof digest 与服务端重算一致。匹配时 `VALID/MEDIUM/PROOF_MATCHED`；digest 不同为 `INVALID/LOW/PROOF_DIGEST_MISMATCH`；无法确定为 `ERROR/UNKNOWN/PROOF_UNAVAILABLE`。任何分支都不得返回 `HIGH`。

- [ ] **Step 6: 验证并提交 canonical policy**

```powershell
./backend/gradlew -p backend test --tests '*BuildProvenanceCanonicalizerTest' --tests '*GithubActionsBuildProvenanceValidatorTest' --tests '*ArchitectureTest'
git add backend/src/main/kotlin/com/ricezhou/vsrqg/traceability backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/BuildProvenanceCanonicalizerTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/GithubActionsBuildProvenanceValidatorTest.kt
git commit -m "feat(m2): validate canonical build provenance"
```

Expected: PASS；GitHub 类型只存在于 Adapter，Domain/Application 不依赖 GitHub DTO 或 SDK。

### Task 4: JDBC Authority Resolution and Revision Repository

**Files:**

- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/BuildProvenanceRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/application/BuildProvenanceRecords.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/JdbcBuildProvenanceRepository.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/BuildProvenanceRepositoryIntegrationTest.kt`

**Interfaces:**

- Consumes: Task 2 schema、Task 3 canonical models，以及既有 M2.3 Snapshot、Artifact checksum 和 ID generator。
- Produces: Task 5 用例使用的 typed repository，不向 Controller 暴露 JDBC 或内部 query。

- [ ] **Step 1: 写失败的 authority resolution tests**

覆盖：Snapshot project/immutable digest、仅 Snapshot Items 内 Issue、Artifact checksum、Commit/Build reuse、同 Build Attempt receipt replay、Edge Header stable identity、相同 fact digest reuse Revision、新 proof 追加 revision、cross-Project/no-resource、稳定排序。

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

- [ ] **Step 2: 运行并确认 repository 缺失**

```powershell
./backend/gradlew -p backend test --tests '*BuildProvenanceRepositoryIntegrationTest'
```

Expected: FAIL at compile，指出 `BuildProvenanceRepository` 与 JDBC Adapter 尚不存在。

- [ ] **Step 3: 定义 exact repository contract**

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

`BuildAttemptKey` 精确包含 projectId、provider、pipeline、buildId、buildAttempt。`BuildProvenanceContext` 固化 projectId/projectKey、snapshotId/releaseId、snapshot content digest；Issue endpoint 固化 issueId/sourceIssueId，Artifact endpoint 固化 artifactId/checksum。

Task 4 同时定义下列跨任务 records；所有 `List` 在构造时 defensive copy 并以 stable order 暴露：

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

- [ ] **Step 4: 实现参数化 resolution 与 identity checks**

`lockContext` 按 Project、Snapshot 顺序 `FOR UPDATE`，并读取 Snapshot header/items count 与 digest；不得查询当前 latest Issue。Artifact 查询必须使用 `checksum_algorithm = 'SHA-256' AND checksum_value IN (...)`，结果数量与请求完全相等，否则 fail-closed。

`resolveCommit` 使用 `(project_id, repository, commit_id)`；`resolveBuild` 使用 Task 2 的五列 Build Attempt authority，并验证 repository/sourceRevision。所有 `INSERT ... ON CONFLICT DO NOTHING` 后必须 read-back 并逐字段比较，禁止把冲突当成功。

- [ ] **Step 5: 实现 Header lock 与 append-only Revision**

Edge candidates 按 `edgeType.name`、fromId、toId 排序。每个 candidate 先 upsert Header，再 `SELECT ... FOR UPDATE`；读取 latest Revision，canonical fact digest 相同则返回已有行，否则插入 `revision + 1` 并设置 previous identity。

Canonical fact digest 必须包含 edge type/endpoints、`sourceType`、`sourceReference`、proof reference/digest、verification status、confidence、validator version 与 reason code；不得包含 Revision ID、revision number、request ID、Idempotency Key、`verifiedAt` 或 `createdAt`。latest 为 `VALID` 且新 observation 对同一 Edge 为 `INVALID` 时，插入 `CONFLICT/LOW/PROOF_CONTRADICTS_ACCEPTED`；新 `VALID` proof 与旧 proof 不同但没有反证时插入新的 `VALID` Revision，不把“不同”误判为“矛盾”；`ERROR/UNKNOWN` 永远不能复用旧 `VALID`。

- [ ] **Step 6: 验证 concurrency、replay 与 immutability**

```powershell
./backend/gradlew -p backend test --tests '*BuildProvenanceRepositoryIntegrationTest' --tests '*BuildProvenanceMigrationTest'
```

Expected: PASS；两个 transaction 对同 Header/fact 收敛到一个 Revision；新 proof 产生 revision 2；旧 Revision bytes/digest 不变；数据库是最终并发保护。

- [ ] **Step 7: 提交 repository authority**

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

- Consumes: Tasks 1–4 Contract、canonicalization、validator 和 repository，以及既有 `IdempotentExecutor`、`GovernanceStore`、JWT principal mapper。
- Produces: 可经 HTTP 验证的 M2.4 Pilot ingestion；Task 6 只能通过该入口生成 live Evidence。

- [ ] **Step 1: 写失败的 end-to-end Application/API tests**

测试必须覆盖：Feature disabled 404、Service scope、USER+scope 403、缺 project claim 403、claim/body mismatch 403、disabled/unassigned SERVICE 403、Snapshot/Issue/Artifact 404、invalid input 422、same key replay、same key different digest 409、same Build Attempt different key replay、same Build Attempt different Envelope conflict/rejected receipt、proof contradiction revision、Audit/Outbox 和无 `ARTIFACT_RELEASE` 写入。

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

- [ ] **Step 2: 写失败的 transaction injection tests**

依次在 Commit、Build、第二个 Edge、Receipt、Audit、Outbox、Idempotency response 前注入 SQLException，断言所有 Domain/Receipt/Idempotency/Audit/Outbox count 回到 baseline。冲突路径单独断言 accepted facts 不变、outer idempotency row 回滚、`REQUIRES_NEW` rejected receipt 与脱敏 Audit 各保留一条。

- [ ] **Step 3: 实现专用 Service Identity Project authority**

不要向 `Permission` 增加 `traceability:ingest`。新增 contract：

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

JDBC 实现同时要求：JWT `principal_type=SERVICE`、token project claim 与 body project 完全相同、数据库 principal 为 `SERVICE` 且未 disabled、Project 未 archived、存在 Project assignment。`@PreAuthorize("hasAuthority('SCOPE_traceability:ingest')")` 继续做 scope boundary；普通 USER 即使有该 scope 和 ADMINISTRATOR assignment 仍被拒绝。

- [ ] **Step 4: 实现单事务 `IngestBuildProvenance`**

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

`IngestBuildProvenance.ingest()` 是非事务 façade，先执行 Feature flag、canonicalize/limits 和 Service authorization，再调用独立 Bean `BuildProvenanceTransaction.execute()`。transaction 内固定顺序为：`IdempotentExecutor`→lock Project/Snapshot→validate Snapshot project/digest→find Build Attempt Receipt→resolve Snapshot Issues→resolve Commit/Build/Artifacts→validate proof→stable Edge candidates→append/reuse revisions→insert Receipt→Audit→Outbox→read-back counts/digests→返回 response。

同 Build Attempt + same Envelope digest 读取已有 Receipt，并让当前 Idempotency Key 保存同一 response；different Envelope digest 抛出 `BuildProvenanceConflict`。`BuildProvenanceTransaction` 的 Spring proxy 完成回滚并把异常交还 façade 后，façade 才调用独立 `BuildProvenanceConflictRecorder.record()`，避免在持有 Project/Receipt lock 时启动冲突事务。Recorder 使用 `REQUIRES_NEW` 写脱敏 rejected receipt 与 Audit，再返回 409；它不更新 accepted Receipt、Edge 或 Revision。

冲突记录 Port 固定为：

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

`JdbcBuildProvenanceConflictRecorder.record()` 标记 `@Transactional(propagation = Propagation.REQUIRES_NEW)`；对 `(acceptedReceiptId, rejectedEnvelopeDigest)` 重放时返回既有 rejected receipt，不追加重复 Audit。

- [ ] **Step 5: 固定 HTTP、payload limit 与 error mapping**

Controller DTO 只负责 Bean Validation 和映射到 normalized Envelope。`BuildProvenancePayloadLimitFilter` 只拦截 exact POST path，流式读取最多 262144 bytes；超限立即输出固定 413 Problem，不把 body 写入日志。

新增 allowlisted validation diagnostic。固定 code 为 `RESOURCE_NOT_FOUND`、`PROJECT_SCOPE_MISMATCH`、`SNAPSHOT_ISSUE_NOT_FOUND`、`ARTIFACT_NOT_FOUND`、`ARTIFACT_DIGEST_MISMATCH`、`PROOF_VALIDATION_FAILED`、`IDEMPOTENCY_CONFLICT`、`BUILD_PROVENANCE_CONFLICT`、`FACT_LIMIT_EXCEEDED` 与 `PERSISTENCE_UNAVAILABLE`。resource hidden 为 404；两个 conflict 为 409；Domain validation、fact limit 与 proof failure 为 422；database unavailable 为 503。Problem、日志、Audit、Outbox 不含 raw body、Token、Cookie、Authorization、provider response、stack trace、author email 或 runner path。

配置固定为：

```yaml
vsrqg:
  traceability:
    ingestion:
      enabled: ${VSRQG_TRACEABILITY_INGESTION_ENABLED:false}
      max-payload-bytes: ${VSRQG_TRACEABILITY_MAX_PAYLOAD_BYTES:262144}
```

Properties 启动时要求 max bytes 精确位于 1..262144；Company 不继承 Pilot test identity。

- [ ] **Step 6: 验证事务、安全与回归**

```powershell
./backend/gradlew -p backend test --tests '*BuildProvenanceIntegrationTest' --tests '*BuildProvenanceTransactionFailureTest' --tests '*SecurityAcceptanceTest' --tests '*PermissionMatrixTest'
```

Expected: PASS；每个失败注入点都无部分成功，冲突只保留脱敏 rejected receipt，USER 永远不能 ingest。

- [ ] **Step 7: 提交 transactional ingestion**

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

- Consumes: Task 5 real HTTP Endpoint、GitHub allowlisted context 和现有 Linux/Docker GitHub Actions。
- Produces: exact-commit Smoke report、fail-closed Gate、双语 implementation Subject 与 `M2-4-OWNER-GATE-001` PENDING acceptance candidate。

- [ ] **Step 1: 写失败的 Gate orchestration tests**

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

- [ ] **Step 2: 实现真实 GitHub exact-head Smoke**

`BuildProvenanceGithubSmokeTest` 使用 `@SpringBootTest(webEnvironment = RANDOM_PORT)` 与真实 Testcontainers PostgreSQL。仅在 `GITHUB_ACTIONS=true` 且六个 allowlisted variables 全部存在时执行：`GITHUB_REPOSITORY`、`GITHUB_SHA`、`GITHUB_WORKFLOW_REF`、`GITHUB_RUN_ID`、`GITHUB_RUN_ATTEMPT`、`GITHUB_JOB`；缺失任何一项必须 FAIL，不得 skip。

测试通过正常 fixture API/JDBC 创建合成 Project、SERVICE assignment、Locked Manifest、M2.3 Snapshot/Item 与 Artifact，再通过真实 HTTP 提交 Envelope。它重放 same key、different key，并执行 conflict、USER、wrong project negatives；最后查询 PostgreSQL 证明 Receipt/Edge/Revision/Audit/Outbox 完整且没有 `ARTIFACT_RELEASE` 写表。

输出 `backend/build/m2/build-provenance-smoke.json` 只包含 exact commit、Run/Attempt、schema/validator version、Envelope/Artifact digest、Edge/Revision IDs、replay results、fixed diagnostics 与 test counts。

- [ ] **Step 3: 实现固定顺序 M2.4 Gate**

`scripts/m2/verify-build-provenance.ps1` 固定执行：

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

脚本必须保持真实 child exit code，输出 `CHECK <name> PASS|FAILED` 和最终固定 summary。不得输出环境变量值、Envelope body、绝对路径或 raw exception。

- [ ] **Step 4: 接入同一 GitHub workflow 并上传脱敏 Evidence**

在 `.github/workflows/m1-backend.yml` 的 M1 candidate gate 成功后运行：

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

不得新增 PAT、Jira secret、公司 credential、write permission 或公网 Backend。Workflow permission 保持 read-only 最小值。

- [ ] **Step 5: 创建运维说明并运行完整本地验证**

`docs/m2/build-provenance.md` 记录 Endpoint、Service Identity claims、Feature flag、Envelope/proof digest、固定 errors、replay、conflict receipt、关闭入口与 roll-forward recovery。运行：

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

Expected: 全部可执行检查 PASS、敏感扫描无真实 secret/path/principal 匹配。本机无 Docker 时 PostgreSQL/live Smoke 必须明确报告未执行；只有绑定 exact commit 的 GitHub Linux/Docker CI 可以补足，不得伪造本地 PASS。

- [ ] **Step 6: 提交双语 implementation Subject 并取得远端 Evidence**

```powershell
git add backend contracts scripts .github/workflows/m1-backend.yml docs/m2/build-provenance.md
git commit -m "test(m2): gate GitHub build provenance smoke"
git push
```

将所有非 Markdown files 以 identical bytes 同步到英文分支，翻译 Markdown 后提交和推送。运行 exact refs Pair Gate，并等待两个 GitHub Actions Runs 对各自 exact HEAD 返回 `success`；记录 Run ID、Artifact ID、digest、createdAt 和 expired 状态。不得 merge、Tag、release 或 deploy。

- [ ] **Step 7: 创建 record-only PENDING Owner Gate**

使用 `git rev-parse HEAD` 获取真实中英文 Subject SHAs，不手写预测 commit。验收记录 `M2-4-OWNER-GATE-001` 必须固定：两条 Subject commits、两个 exact-head CI Runs、PostgreSQL/transaction/concurrency/GitHub Smoke/security/Pair Gate reports、Artifact digests、所有残余风险；metadata 初始为 `status: PENDING`、`owner: PENDING`、`decisionAt: PENDING`。

分别提交 record-only bilingual commits，推送并等待 record-head CI 成功：

```powershell
git add docs/governance/acceptance/records/2026-09-03-m2-4-owner-gate-001.md
git commit -m "docs(acceptance): submit M2.4 owner gate"
git push
```

Owner 未给出独立 APPROVE/REJECT/CONDITIONAL 前不得改变状态，也不得开始 M2.5。

## Plan 自审清单

- Spec coverage：Task 1 覆盖 v2/API consumer boundary；Task 2 覆盖 Schema/FK/Trigger/immutability；Task 3 覆盖 canonical/limits/validator；Task 4 覆盖 Snapshot/Artifact authority、typed Revision 与 concurrency；Task 5 覆盖 transaction、两层 Idempotency、Service Identity、conflict/recovery/security；Task 6 覆盖 GitHub Smoke、Evidence、deployment/recovery docs 与 Owner Gate。
- Type consistency：全计划统一使用 `ProvenanceProviderId`、`BuildProvenanceEnvelope`、`CanonicalBuildProvenance`、`ProvenanceValidation`、`BuildAttemptKey`、`EdgeCandidate`、`EdgeRevisionRecord`、`BuildProvenanceReceipt` 和 `BuildProvenanceResult`。
- Authority consistency：Issue 只来自 immutable Snapshot；Artifact 只来自 checksum authority；Header 只表达 Edge identity；Revision 是 proof/status history；Receipt 是 Build Attempt replay authority；不存在第二事实来源。
- Security consistency：用户 RBAC 不获得 `traceability:ingest`；Service JWT、DB principal、Project assignment 和 token/body project 四重一致；无 raw event/credential/provider error 持久化。
- Scope consistency：无 Fixed/Included/Verified、`ARTIFACT_RELEASE` write、M2.5、Company、真实 Jira、公司 CI、Broker、merge、Tag、release 或 deploy。
- Placeholder scan：不得存在未定义占位步骤、泛化“补充测试/错误处理”或未定义类型；未来 Git/CI locator 必须在执行时由命令解析并以真实值写入验收记录。

## 实施授权 Gate

本计划的创建由已批准的 `M2-KD-2026-09-03-01` Written Spec Review 授权；计划本身不授权 production code、Migration 或 GitHub Smoke 执行。开始 Task 1 前必须取得 Project Owner 独立指令：

```text
批准采用 Subagent-Driven 执行 M2.4 Build Provenance Envelope
```

授权范围仅为本计划 Task 1～Task 6 的 Pilot implementation、synthetic/live GitHub Actions tests、双语配对提交和 CI；不含真实 Jira、公司 CI、Company、M2.5、`main`/`release` merge、Tag、release 或 production deployment。
