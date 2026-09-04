# M2.5 Traceability Verification Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于请求时固定的 Locked Manifest、M2.3 Issue Snapshot 与 M2.4 Edge Revision，异步计算可重放的 Fixed/Included/Verified 和精确 Gap，并原子保存不可变 Traceability Snapshot。

**Architecture:** 保持 Kotlin/Spring Boot Modular Monolith，以 PostgreSQL 为唯一运行与结果权威。HTTP 创建事务固定全部输入并写入现有 Job/Outbox；Worker 只读取固定账本，在第二个事务中原子物化 Snapshot、Issue Result、主路径 Edge 与 Gap；查询只读已完成 Snapshot，不访问 Jira、GitHub、CI、Device 或最新 Edge。

**Tech Stack:** Kotlin/JVM 21、Spring Boot、Spring JDBC、PostgreSQL 17、Flyway、Jackson、RFC 8785 JCS、JUnit 5、AssertJ、MockMvc、Testcontainers、PowerShell、GitHub Actions。

**Spec:** `docs/superpowers/specs/2026-09-04-m2-traceability-verification-snapshot-design.md`

## Global Constraints

- V0.1 `0.1.0` Core Contract、Release-centric architecture、Manifest authority、Evidence、Traceability、Deterministic Quality Engine、Adapter、Plugin 与 ADR governance 保持冻结。
- Release authority 只能是 Locked Manifest；Issue scope 只能是请求时固定的 M2.3 `release_issue_snapshot`；M2.4 三类 Edge 只能读取固定 Revision；`ARTIFACT_RELEASE` 只能来自 `artifact_release_edge_v`。
- M2.5 只计算 Fixed 与 Included；Verified 必须始终为 `false`，并产生 `TEST_RESULT_EVIDENCE_MISSING`。
- 当前权威 Revision 的 `INVALID`、`CONFLICT` 或 `ERROR` 是输入失败，不得降级成 Gap，也不得产生成功 Snapshot。
- 创建事务必须原子写 Idempotency、Verification Run、Input Ledger、Audit、Outbox 与 Background Job；结果事务必须原子写 Snapshot、Issue Result、Path Edge、Gap、Audit、Outbox 与终态。
- 状态只允许 `QUEUED → RUNNING → SUCCEEDED|FAILED`；终态不可覆盖。相同输入不同 key 可以复用 content-identical Snapshot，但每次请求仍保留独立 Run。
- 单次最多 20 个 Issue、默认最多 2,000 个 Edge Revision；禁止截断。参考 Pilot 目标：创建 P95 ≤ 1 秒、验证 ≤ 10 秒、查询 P95 ≤ 1 秒。
- API 保留 `POST /api/v1/releases/{releaseId}/traceability:verify` 与 `GET /api/v1/releases/{releaseId}/traceability`，仅 additive 增加 `GET /api/v1/traceability-verification-runs/{verificationRunId}`。
- 不新增 Broker、Redis、图数据库、独立 Service、外部调用、UI 或 Company 依赖；不 merge、Tag、release 或 production deploy。

---

## 文件结构与提交顺序

本计划锁定七个独立提交边界：

1. OpenAPI、权限与严格 DTO。
2. PostgreSQL V11 固定输入和不可变结果扩展。
3. Provider-neutral 确定性路径算法与 canonical digest。
4. Verification Run 创建事务与固定输入账本。
5. Worker claim、结果物化、恢复与并发收敛。
6. Snapshot/Run 查询、授权与 replay。
7. 性能、恢复、Gate、运维说明与 PENDING Owner Gate。

Task 1～7 的非 Markdown 文件在中英文分支必须 byte-identical；Markdown 只做等义翻译。每个 Task 必须先看到指定失败，再写最小实现，再运行目标测试并提交。

### Task 1: OpenAPI Contract、Permission 与 Strict DTO

**Files:**

- Modify: `contracts/openapi/v0.2/openapi.json`
- Modify: `contracts/openapi/v0.2/compatibility-baseline.json`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/M2ApiContractTest.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/access/SecurityAcceptanceTest.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/TraceabilityVerificationDtos.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationDtoTest.kt`

**Interfaces:**

- Consumes: 既有 `traceability:verify`、`traceability:read`、OIDC、`Idempotency-Key`、Release path 与 `IdentifierInput.sourceId`。
- Produces: `TraceabilityVerificationAccepted`、`TraceabilityVerificationRunResponse`、`TraceabilitySnapshotResponse`，供 Task 4/6 Controller 使用。

- [ ] **Step 1: 写失败的 Contract 与 strict DTO tests**

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

- [ ] **Step 2: 运行并确认 polling operation 与 DTO 缺失**

```powershell
./backend/gradlew -p backend test --tests '*M2ApiContractTest' --tests '*TraceabilityVerificationDtoTest'
```

Expected: FAIL，明确缺少 status path/schema 或 DTO，而不是环境跳过。

- [ ] **Step 3: 固定 exact schema 与错误语义**

`POST` body 只允许 `sourceId`；返回 `202` 的必需字段为 `verificationRunId`、`status=QUEUED`、`releaseId`、`issueSnapshotId`、`statusUrl`。Run GET 返回 `verificationRunId`、`releaseId`、`status`、`policyVersion`、`validatorVersion`、`inputDigest`、nullable `resultSnapshotId`、nullable `diagnosticCode` 与 timestamps。Snapshot GET 返回 header、按 source issue identity 稳定排序的 Issue Result、每个 Issue 的有序主路径和 Gap；所有 object 使用 `additionalProperties:false`。

错误固定为：不可见资源 `404`、状态冲突 `409`、无效固定事实/超限 `422`、明确基础设施不可用 `503`。API 不返回 title、proof URL、credential、raw payload、SQL 或 stack trace。

- [ ] **Step 4: 更新 compatibility baseline 与安全矩阵测试**

只增加一个 GET operation；保留既有 Path/Method/Permission/Idempotency。增加 Owner/Engineer 可 verify、Viewer 只 read、跨 Project 和无权限统一 404 的测试。

- [ ] **Step 5: 验证并提交 Contract**

```powershell
./backend/gradlew -p backend test --tests '*M2ApiContractTest' --tests '*TraceabilityVerificationDtoTest' --tests '*SecurityAcceptanceTest'
npm run test:contracts
git diff --check
git add contracts/openapi/v0.2/openapi.json contracts/openapi/v0.2/compatibility-baseline.json backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/TraceabilityVerificationDtos.kt backend/src/test/kotlin/com/ricezhou/vsrqg/M2ApiContractTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/access/SecurityAcceptanceTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationDtoTest.kt
git commit -m "feat(m2): define traceability verification contract"
```

Expected: contract tests PASS，operation count 由 33 精确增加为 34。

### Task 2: PostgreSQL V11 Fixed Input and Immutable Result Authority

**Files:**

- Create: `backend/src/main/resources/db/migration/V11__traceability_verification_snapshot.sql`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationMigrationTest.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/M2MigrationConstraintTest.kt`

**Interfaces:**

- Consumes: V4 Verification/Snapshot/Gap/Job tables、V6 Issue Snapshot、V9/V10 typed Edge authority 与 `artifact_release_edge_v`。
- Produces: Task 4/5 使用的 Run input identity、`traceability_verification_run_edge_input`、`traceability_snapshot_issue_result`、`traceability_snapshot_issue_path_edge` 与精确 Gap break references。

- [ ] **Step 1: 写失败的 clean/upgrade/constraint tests**

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

另测：跨 Project/Release FK、重复 ordinal、非 VALID input、非法状态跳转、终态覆盖、错误 Gap code、path edge 不属于 input、Snapshot/Run 不一致、同 Release version 并发与 V10→V11 upgrade。

- [ ] **Step 2: 运行并确认 V11 objects 缺失**

```powershell
./backend/gradlew -p backend test --tests '*TraceabilityVerificationMigrationTest'
```

Expected: FAIL，明确缺少 V11 columns/tables/constraints。

- [ ] **Step 3: 编写 forward-only V11 Migration**

扩展 `traceability_verification_run`：`issue_snapshot_id`、`manifest_revision_id`、`validator_version`、`input_digest`、`result_snapshot_id`、`requested_by`、`request_id`；旧行允许 nullable，新 M2.5 policy 行由 CHECK/trigger 要求完整。新增账本：

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

`traceability_snapshot_edge` 和 input ledger 均保存 `source_edge_revision_id`：前三类 typed Edge 使用对应 `*_edge_revision.id`，`ARTIFACT_RELEASE` 使用 Locked Manifest Revision ID；数据库拒绝跨类型或与 Manifest 不一致的伪造 ID。`traceability_snapshot_issue_result` 固定保存 `issue_id`、`source_issue_id`、`fixed`、`included`、`verified=false`、`result_digest` 与 ordinal。`traceability_snapshot_issue_path_edge` 通过 `(snapshot_id, issue_ordinal, path_ordinal)` 引用 Issue Result 和既有 Snapshot Edge。扩展两张 Gap 表，加入 `break_entity_type`、`break_entity_id`、nullable `predecessor_edge_type/id/revision`，diagnostic 仅允许五个批准 code。

- [ ] **Step 4: 安装触发器与索引**

所有新结果表与 input ledger 使用现有 `reject_immutable_write()`；状态 trigger 只允许 `QUEUED→RUNNING→SUCCEEDED|FAILED`，且 `SUCCEEDED` 必须具有 result snapshot、`FAILED` 必须具有固定 diagnostic。索引覆盖 `(release_id, created_at desc)`、`input_digest`、`result_snapshot_id`、Issue Result lookup 与 Worker dispatch；禁止创建第二个 Artifact→Release 表。

- [ ] **Step 5: 验证 clean、V10 upgrade、repeat 和并发**

```powershell
./backend/gradlew -p backend test --tests '*TraceabilityVerificationMigrationTest' --tests '*M2MigrationConstraintTest' --tests '*MigrationConstraintTest'
```

Expected: PASS；Flyway 第二次启动无 pending migration，并发 version 分配只有一个事务成功后另一事务可重试。

- [ ] **Step 6: 提交数据库权威**

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

- Consumes: immutable `VerificationInput(issueSnapshot, manifest, edgeRevisions)`。
- Produces: `VerificationComputation(issueResults, pathEdges, gaps, contentDigest)`，供 Task 5 原样持久化。

- [ ] **Step 1: 写完整链与每段 Gap 的失败测试**

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

增加多路径稳定选择、多个 Issue 隔离、无 N+1 repository 假设、20/2,000 boundary、2,001 fail-closed，以及任何 pinned `INVALID|CONFLICT|ERROR` 抛 `TRACEABILITY_INPUT_NOT_VALID` 的测试。

- [ ] **Step 2: 定义 provider-neutral immutable types**

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

构造器拒绝 `verified=true`。Fixed 只要求至少一条 policy-valid `ISSUE_COMMIT`；Included 要求同一主路径连续到 Locked Manifest Release。候选路径按 Edge type sequence、endpoint IDs、edge ID、numeric revision、revision ID 排序后取第一条；Gap 指向首个断点与实际 predecessor。

- [ ] **Step 3: 实现 canonical payload 和 digest**

Input digest 只含 schema/policy/validator、Release、Manifest revision/digest、Issue Snapshot ID/digest 和按 `(edgeType, sourceEdgeId, numericRevision, revisionId)` 排序的固定 fact digest。Result digest 只含固定输入 identity（包括 revision ID）、按 `sourceIssueId, issueId` 排序的 Issue Result、path edge facts（包括 revision ID）与 gaps；timestamps、run ID、snapshot ID、request ID、actor 不进入 digest。

- [ ] **Step 4: 运行确定性和三次 byte stability tests**

```powershell
./backend/gradlew -p backend test --tests '*TraceabilityVerifierTest' --tests '*TraceabilityCanonicalizerTest'
```

Expected: PASS；输入顺序随机化 100 次仍得到相同 canonical bytes 和 digest。

- [ ] **Step 5: 提交领域算法**

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

- Consumes: Task 1 request/accepted DTO、Task 3 input canonicalizer、现有 principal/project authority、Locked Manifest、latest immutable Issue Snapshot for Release/source、M2.4 exact Edge Revisions 与 Manifest view。
- Produces: `StartTraceabilityVerification.start(command): TraceabilityVerificationAccepted` 和一个 `TRACEABILITY_VERIFY` Background Job。

- [ ] **Step 1: 写失败的 authority pinning 与 idempotency tests**

测试：非 Locked Release 409；source 无 Snapshot 404；latest Snapshot 在 transaction 内固定；只有 Snapshot Issue 可进入 ledger；Edge 超限 422 且不截断；相同 key+相同 body 返回同 Run；相同 key+不同 body 409；不同 key+相同 input 创建独立 Run；请求完成后新增 Edge Revision 不改变 ledger。

- [ ] **Step 2: 写每个持久化边界的失败注入测试**

```kotlin
@ParameterizedTest
@EnumSource(StartWriteBoundary::class)
fun `failure rolls back every creation artifact`(boundary: StartWriteBoundary) {
    repository.failAt(boundary)
    assertThatThrownBy { useCase.start(command()) }.isInstanceOf(RuntimeException::class.java)
    assertThat(countRunArtifacts()).containsOnly(0)
}
```

边界固定为 idempotency、run、input ledger、audit、outbox、job；不得 catch 后返回 `202`。

- [ ] **Step 3: 实现一个 `@Transactional` 创建用例**

Repository 用一次 set-based 查询加载 Issue Snapshot、三类 latest eligible revision 和 `artifact_release_edge_v`，验证 Project/Release/Manifest 一致，再计算 input digest。Run payload 只保存 IDs、versions 和 digests。Audit event 为 `TRACEABILITY_VERIFICATION_QUEUED`，Outbox 为 `traceability.verification.queued`，Job payload 只含 run ID；配置：

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

- [ ] **Step 4: 实现 POST Controller 与权限**

Controller 使用 `@PreAuthorize("hasAuthority('SCOPE_traceability:verify')")`、现有 principal resolver 与 Request ID；disabled 时返回固定 503，绝不退回同步计算或动态 query。

- [ ] **Step 5: 验证并提交创建事务**

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

- Consumes: `TraceabilityVerificationRepository.claimNext(now)`、Task 3 verifier/canonicalizer 与 fixed input ledger。
- Produces: immutable Snapshot graph and terminal Run；Task 6 只读这些结果。

- [ ] **Step 1: 写 claim、crash 与 atomic result 失败测试**

覆盖 `FOR UPDATE SKIP LOCKED` 单次 claim、attempt increment、两个 Worker 不重复执行、RUNNING crash reclaim、超过有界 attempts 后 Run FAILED/Job DEAD_LETTER。分别在 Snapshot header、Issue Result、Snapshot Edge、Path Edge、Gap、Audit、Outbox、Run terminal、Job terminal 注入失败，断言不存在半成品 Snapshot。

- [ ] **Step 2: 写 content reuse 与 release version concurrency tests**

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

两个不同 input 并发时以数据库 release row lock 分配连续 version；禁止 JVM lock。唯一冲突只重试有界 transaction，不吞掉其他 SQL 错误。

- [ ] **Step 3: 实现 Worker claim 与纯计算**

沿用 Issue Worker 的 claim 模式，job type 固定 `TRACEABILITY_VERIFY`。claim transaction 把 Job/Run 变为 RUNNING；随后只从 ledger 加载 facts 并调用 Task 3 verifier。不得调用外部 Adapter，不得读取 latest Revision，不得从 JSON/cache 重建 authority。

- [ ] **Step 4: 实现原子结果 transaction**

先按 input/result digest 查找可复用 Snapshot；无匹配时锁 Release row，分配 version，依次写 Snapshot header、稳定排序 Issue Result、去重 Snapshot Edge、Path Edge、Gap、Audit `TRACEABILITY_VERIFICATION_SUCCEEDED`、Outbox `traceability.verification.succeeded`，最后更新 Run/Job。输入非法时不写 Snapshot，以固定 code 更新 FAILED/DEAD_LETTER；不可预测基础设施失败保留安全 code 和可重试 Job，不保存异常文本。

- [ ] **Step 5: 验证 Worker 与恢复**

```powershell
./backend/gradlew -p backend test --tests '*TraceabilityVerificationWorkerIntegrationTest' --tests '*TraceabilityVerificationWorkerFailureTest' --tests '*TraceabilityVerificationConcurrencyTest'
```

Expected: PASS；每个成功 Run 指向一个完整 Snapshot，每个失败 Run 指向零个 Snapshot。

- [ ] **Step 6: 提交 Worker**

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

- Consumes: completed immutable tables from Task 5。
- Produces: Run polling and Snapshot query responses from Task 1, without recomputation。

- [ ] **Step 1: 写 query ordering、visibility 与 replay 失败测试**

测试默认读取最新 SUCCEEDED Snapshot、`snapshotId` 精确读取历史、QUEUED/RUNNING/FAILED 不冒充 Snapshot、Issue/path/gap order 稳定、跨 Project/无权限/不存在统一 404。完成 Snapshot 后插入 M2.4 revision+1 和新 Issue Snapshot，断言历史 response bytes 与 digest 不变。

- [ ] **Step 2: 实现 set-based read repository methods**

Run GET 按 verification public ID 与 principal Project membership 查询。Snapshot GET 分别一次读取 header、Issue Result、Path Edge、Gap，再在 Application 按 persisted ordinal 组装；禁止逐 Issue 查询、禁止读 source revision table、`artifact_release_edge_v` 或外部系统。

- [ ] **Step 3: 实现 GET endpoints 与 Problem Details mapping**

```kotlin
@GetMapping("/api/v1/traceability-verification-runs/{verificationRunId}")
@PreAuthorize("hasAuthority('SCOPE_traceability:read')")
fun getRun(...): TraceabilityVerificationRunResponse

@GetMapping("/api/v1/releases/{releaseId}/traceability")
@PreAuthorize("hasAuthority('SCOPE_traceability:read')")
fun getSnapshot(..., @RequestParam(required = false) snapshotId: String?): TraceabilitySnapshotResponse
```

只暴露 allowlisted diagnostics 与 identifiers；response 不包含 raw reason、SQL、proof URL、source title 或 token-like 字符串。

- [ ] **Step 4: 验证 read/security/replay 并提交**

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

- Consumes: Tasks 1～6 的 exact behavior 与现有 M2 Evidence/Gate conventions。
- Produces: fixed-commit M2.5 evidence bundle and a `PENDING` Owner acceptance record；不自行批准。

- [ ] **Step 1: 写失败的 Gate orchestration tests**

验证 Gate 固定顺序：clean tree、fixed commit、contract、migration、domain、transaction failure、concurrency、replay、performance、secret scan、acceptance validator、evidence digest。任一失败则总状态 FAILED，但仍生成不含敏感数据的 evidence summary；Owner decision 初始只能为 `PENDING`。

- [ ] **Step 2: 增加参考 Pilot 性能与 query-count tests**

构造 20 Issue/2,000 fixed Edge 的 deterministic fixture，测量 start、worker、query 并记录样本数、p50/p95/max、hardware/runtime metadata；断言 repository query count 为常数级且无 per-Issue query。硬 Gate 使用宽松可复现上限，报告同时记录目标 P95 ≤ 1s/10s/1s；不得通过跳过、截断或缩小 fixture 获得 PASS。

- [ ] **Step 3: 增加 backup/restore 与 dead-letter recovery tests**

导出并恢复完成 Snapshot，重算 canonical digest；模拟 DB restart 后 reclaim RUNNING job；poison job 达到上限后 Run FAILED/Job DEAD_LETTER，人工重试创建新 Run 而不修改旧终态。

- [ ] **Step 4: 编写 M2.5 Gate 与 CI**

`verify-m25.ps1` 调用目标 Gradle suites、`npm run test:contracts`、治理/语言/secret checks，生成包含 commit、migration version、test counts、performance、replay digest、recovery result 的 JSON。Workflow 只使用 `contents: read`，不配置 Jira/GitHub provider credential，不访问 Company 环境，上传 `m2-5-evidence-${{ github.sha }}`。

- [ ] **Step 5: 编写运维说明与 PENDING 验收记录**

运维文档只说明 Pilot feature flag、Migration→deploy→known-chain/gap smoke→rollback image/roll-forward DB、Worker backlog/dead-letter inspection、固定 diagnostic 和禁止重建历史。先提交候选 Gate，再用该提交的实际 SHA 创建列出 WHY/WHAT/BOUNDARY/ACCEPTANCE 的验收记录；decision 保持 `PENDING`，不得声称 Company Ready。

- [ ] **Step 6: 运行完整验证、自检与双语同步**

```powershell
pwsh -NoProfile -File scripts/tests/m2-5-verify-gates.tests.ps1
pwsh -NoProfile -File scripts/m2/verify-m25.ps1
git diff --check
rg -n 'T[B]D|T[O]DO|implement[ ]later|fill[ ]in' docs/superpowers/plans/2026-09-04-m2-traceability-verification-snapshot-implementation.md
pwsh -NoProfile -File scripts/verify-language-branches.ps1 -Mode Pair -ChineseRef docs/m2-issue-traceability-design -EnglishRef docs/m2-issue-traceability-design-en
```

Expected: 全部 PASS；英文 Markdown 零汉字；非 Markdown byte-identical；两个工作树 clean；exact-head CI Success。

- [ ] **Step 7: 提交候选 Gate 与验收记录**

```powershell
git add backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationPerformanceTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationRecoveryTest.kt scripts/m2/verify-m25.ps1 scripts/tests/m2-5-verify-gates.tests.ps1 .github/workflows/m2-backend.yml docs/m2/traceability-verification-operations.md
git commit -m "test(m2): add traceability verification candidate gate"
git add docs/governance/acceptance/records/2026-09-04-m2-5-owner-gate-001.md
git commit -m "docs(m2): add traceability owner gate candidate"
```

## 最终验收与停止条件

实施完成必须提供：固定双语 Subject commits、两个 exact-head Success CI runs、两份 M2.5 Evidence Artifacts、Pair Gate、V11 clean/upgrade/repeat report、完整链/五类 Gap report、transaction failure matrix、并发/recovery/replay report、20 Issue/2,000 Edge 性能报告，以及 `PENDING` Owner Gate。Project Owner 只可在复核后作出 `ACCEPTED`、`CONDITIONAL` 或 `REJECTED`；Codex 不代替 Owner 决策。

如实施需要修改 Fixed/Included/Verified 语义、创建第二个 Artifact→Release authority、动态读取最新 Revision、把 invalid/conflict/error 转成 Gap、产生 Verified=true、调用 Jira/GitHub/CI/Device、覆盖旧 Run/Snapshot，或突破 V0.1 冻结项，立即停止并提交 Finding、TDR revision 或 ADR Proposal。
