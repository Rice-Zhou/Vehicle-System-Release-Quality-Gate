# Evidence Archive 验收工作包实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变 V0.1 和既有 Archive 架构的前提下，提供可受控执行、独立恢复验证和机器复核的 `V0-2-EVIDENCE-ARCHIVE-001` 工作包，并在真实 Company Evidence 完成后创建初始 `PENDING` 验收记录。

**Architecture:** 复用 `ArchiveEvidence.archive(ArchiveCommand)`、`S3ArchiveAdapter`、`S3Gateway` 和现有 Capability 链，只增加一个无 Web/数据库依赖的 JVM 运维入口。固定工作包描述符不含本地路径或凭据；归档执行与独立恢复分别产生 canonical JSON Evidence，再由 Node verifier 交叉核验。真实 Provider 写入和验收记录创建属于独立的最终检查点。

**Tech Stack:** Kotlin 2 / Java 21、Spring Framework narrow context、AWS SDK S3/STS、Jackson + JCS、Node.js + AJV、PowerShell、Gradle、GitHub Actions、Markdown Acceptance Governance。

---

## 文件职责图

| 文件 | 职责 |
|---|---|
| `ops/evidence-archive/v0-2-evidence-archive-001.json` | 固定 Artifact、commit、size、digest 和 Pilot manifest 事实；不包含路径和凭据 |
| `ops/evidence-archive/schemas/work-package.schema.json` | 工作包描述符结构 |
| `ops/evidence-archive/schemas/archive-execution.schema.json` | 归档执行 Evidence 结构 |
| `ops/evidence-archive/schemas/recovery-verification.schema.json` | 独立恢复 Evidence 结构 |
| `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveModels.kt` | 运维输入、执行报告和恢复报告类型 |
| `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveSourceVerifier.kt` | 固定输入、路径边界、size、SHA-256 和 Pilot manifest 验证 |
| `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveRunner.kt` | 调用唯一 Archive facade 并原子写入执行报告 |
| `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveRecoveryVerifier.kt` | 以独立身份按精确版本回读 payload/receipt 并验证控制 |
| `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveOperationMain.kt` | `archive` / `verify` 命令入口和进程退出码 |
| `scripts/evidence-archive/verify-evidence.mjs` | 不访问 Provider，交叉校验三份 JSON 和禁止字段 |
| `scripts/tests/evidence-archive-evidence.test.mjs` | Evidence verifier 正反例测试 |
| `docs/m1/evidence-archive-runbook.md` | 双身份执行、失败恢复和验收交接运行手册 |
| `docs/v0.2/tdr/TDR-012-evidence-archive-acceptance-operations.md` | 运维入口与 Evidence 格式技术决策 |

### Task 1: 固化 TDR 与无秘密工作包描述符

**Files:**
- Create: `docs/v0.2/tdr/TDR-012-evidence-archive-acceptance-operations.md`
- Create: `ops/evidence-archive/v0-2-evidence-archive-001.json`
- Create: `ops/evidence-archive/schemas/work-package.schema.json`
- Test: `scripts/tests/evidence-archive-evidence.test.mjs`

- [ ] **Step 1: 写描述符 schema 失败测试**

在测试中加载 schema，并证明未知字段、本地绝对路径、非 64 位小写 SHA-256、错误 size、重复 Artifact ID 和非 `LOCAL_PILOT_NOT_IMMUTABLE` 分类会失败：

```javascript
test("rejects mutable or path-bearing work package input", () => {
  const candidate = structuredClone(validWorkPackage);
  candidate.sourceRoot = "C:\\staging";
  assert.equal(validateWorkPackage(candidate), false);
});
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
node --test scripts/tests/evidence-archive-evidence.test.mjs
```

Expected: FAIL，缺少 schema 或 `validateWorkPackage`。

- [ ] **Step 3: 实现 schema 并创建固定描述符**

`work-package.schema.json` 固定 `additionalProperties=false`、恰好两个唯一 Artifact、正整数 size、64 位小写 SHA-256、安全文件名、两个 40 位小写 commit，并禁止任何 path/root/credential 字段。描述符必须使用以下固定事实，不增加路径：

```json
{
  "schemaVersion": 1,
  "workPackageId": "V0-2-EVIDENCE-ARCHIVE-001",
  "subjectCommit": "e3576582b08c154189eb9e7f2796f39280cdb8a5",
  "pairedSubjectCommit": "6ef2cd2fb234737fad78e96cff4172ef8f92fc45",
  "pilotManifest": {
    "fileName": "pilot-preservation-manifest.json",
    "sha256": "7bcb4d9df5ce0e28fe6150e0593c9824ea2533a2f7885f17d61d3ae813aa4a32",
    "classification": "LOCAL_PILOT_NOT_IMMUTABLE",
    "conditionBClosed": false
  },
  "artifacts": [
    {
      "artifactId": "9631253528",
      "artifactName": "m1-evidence-892fb23ce75e7f74a05c1b5e304fccace70ee8d3",
      "fileName": "m1-evidence-892fb23ce75e7f74a05c1b5e304fccace70ee8d3.zip",
      "sourceRunId": "33033752846",
      "sourceCommit": "892fb23ce75e7f74a05c1b5e304fccace70ee8d3",
      "sizeBytes": 55065,
      "sha256": "1f087ef27cfabbb2152d06fc002eb0772c2efbbb63964d6b13ec5f0d7a73ed7a"
    },
    {
      "artifactId": "9631250285",
      "artifactName": "m1-evidence-8687d49c9566030bb0829752dbe5dda45af02f4b",
      "fileName": "m1-evidence-8687d49c9566030bb0829752dbe5dda45af02f4b.zip",
      "sourceRunId": "33033740162",
      "sourceCommit": "8687d49c9566030bb0829752dbe5dda45af02f4b",
      "sizeBytes": 55099,
      "sha256": "e7602924fe67fd6eff75ebfe5d48122240639d883edc58dc164c419893d979ca"
    }
  ]
}
```

- [ ] **Step 4: 写 `TDR-012`**

TDR 必须回答九项 Technology Decision Record 问题，并选择“narrow JVM operation + canonical JSON Evidence + two invocations”。明确未采用 REST 管理端点、AWS CLI 脚本、数据库队列表和新微服务；说明 V0.2/V0.3 影响、迁移、测试、部署、失败恢复和凭据仅从外部 identity chain 注入。

- [ ] **Step 5: 测试并提交**

```powershell
node --test scripts/tests/evidence-archive-evidence.test.mjs
git diff --check
git add docs/v0.2/tdr/TDR-012-evidence-archive-acceptance-operations.md ops/evidence-archive scripts/tests/evidence-archive-evidence.test.mjs
git commit -m "docs(archive): define evidence work package operations"
```

Expected: tests PASS；commit 不包含本地路径、credential 或验收决定。

### Task 2: 补齐可恢复的精确回执引用

**Files:**
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveModels.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3ArchiveAdapter.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/FilesystemStagingArchiveAdapter.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveContractTest.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/S3ArchiveAdapterTest.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/FilesystemStagingArchiveTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
assertThat(result.receiptReference.sizeBytes).isPositive()
assertThat(result.runtimeIdentity).isEqualTo(expectedRuntimeIdentity)
assertThat(localResult.runtimeIdentity).isNull()
```

同时断言 S3 completion identity 与执行 control identity 相同，identity 变化时不返回结果。

- [ ] **Step 2: 运行目标测试确认失败**

```powershell
./backend/gradlew.bat -p backend test --tests "com.ricezhou.vsrqg.shared.archive.ArchiveContractTest" --tests "com.ricezhou.vsrqg.shared.archive.S3ArchiveAdapterTest" --tests "com.ricezhou.vsrqg.shared.archive.FilesystemStagingArchiveTest"
```

Expected: FAIL，缺少 `sizeBytes` 或 `runtimeIdentity`。

- [ ] **Step 3: 最小扩展模型**

```kotlin
data class ArchiveReceiptReference(
    val locator: String,
    val versionId: String?,
    val sha256: String,
    val sizeBytes: Long,
)

data class ArchiveResult(
    val receipt: ArchiveReceipt,
    val receiptReference: ArchiveReceiptReference,
    val runtimeIdentity: RuntimeIdentityRef?,
)
```

S3 使用已完成 control 与 completion identity 一致的 `archiveControl.identity`；filesystem 固定为 `null`。不得把原始 principal 写入模型。

- [ ] **Step 4: 运行测试并提交**

Run 同 Step 2。Expected: PASS。

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveModels.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3ArchiveAdapter.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/FilesystemStagingArchiveAdapter.kt backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive
git commit -m "feat(archive): expose exact recovery evidence"
```

### Task 3: 验证固定源输入

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveModels.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveSourceVerifier.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveSourceVerifierTest.kt`

- [ ] **Step 1: 写源验证失败测试**

覆盖源根目录非绝对路径、symlink、文件名越界、ZIP 缺失、size/digest 不一致、manifest 摘要或分类不一致、重复 ID，以及两个有效 ZIP：

```kotlin
assertThatThrownBy { verifier.verify(descriptor, sourceRoot) }
    .isInstanceOf(EvidenceArchiveInputFailure::class.java)
assertThat(verifier.verify(validDescriptor, validSourceRoot).artifacts).hasSize(2)
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
./backend/gradlew.bat -p backend test --tests "com.ricezhou.vsrqg.shared.archive.operations.EvidenceArchiveSourceVerifierTest"
```

Expected: FAIL，类型不存在。

- [ ] **Step 3: 实现不可变输入模型与验证器**

```kotlin
enum class OperationStatus {
    PASS,
    FAIL,
}

class EvidenceArchiveInputFailure(
    val code: String,
) : IllegalArgumentException(code)

class EvidenceArchiveVerificationFailure(
    val code: String,
) : IllegalStateException(code)

data class VerifiedArchiveSource(
    val artifactId: String,
    val sourceRunId: String,
    val sourceCommit: String,
    val path: Path,
    val sizeBytes: Long,
    val sha256: String,
)

data class VerifiedEvidenceArchiveWorkPackage(
    val workPackageId: String,
    val descriptorSha256: String,
    val pilotManifestSha256: String,
    val artifacts: List<VerifiedArchiveSource>,
)
```

验证器先对 descriptor bytes 计算摘要，再用 no-follow real-path 检查确保每个 source 是 source root 下的普通文件；逐项计算 size/SHA-256。错误只输出字段名和稳定错误码，不输出绝对路径。

- [ ] **Step 4: 运行测试并提交**

Run 同 Step 2。Expected: PASS。

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveSourceVerifierTest.kt
git commit -m "feat(archive): verify evidence work package sources"
```

### Task 4: 实现受控归档操作与执行 Evidence

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveRunner.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveOperationMain.kt`
- Modify: `backend/build.gradle.kts`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveRunnerTest.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveOperationMainTest.kt`

- [ ] **Step 1: 写执行失败与成功测试**

```kotlin
assertThat(success.status).isEqualTo(OperationStatus.PASS)
assertThat(success.artifacts).hasSize(2)
assertThat(success.artifacts).allMatch { it.receiptReference.versionId?.isNotBlank() == true }
assertThat(failure.status).isEqualTo(OperationStatus.FAIL)
assertThat(failure.errorCode).isEqualTo("ARCHIVE_UNAVAILABLE")
```

覆盖第二个 Artifact 失败时保留第一个成功引用、report 原子写入、未知异常映射为 `UNEXPECTED_FAILURE` 且进程非零、输出不包含 exception message/path/secret。

- [ ] **Step 2: 运行测试确认失败**

```powershell
./backend/gradlew.bat -p backend test --tests "com.ricezhou.vsrqg.shared.archive.operations.EvidenceArchiveRunnerTest" --tests "com.ricezhou.vsrqg.shared.archive.operations.EvidenceArchiveOperationMainTest"
```

Expected: FAIL，runner 和 main 不存在。

- [ ] **Step 3: 实现 runner**

每个输入只通过以下映射调用既有 facade：

```kotlin
ArchiveCommand(
    acceptanceId = workPackage.workPackageId,
    sourceArtifactId = source.artifactId,
    sourceRunId = source.sourceRunId,
    sourceCommit = source.sourceCommit,
    source = source.path,
    expectedSha256 = source.sha256,
)
```

执行报告包含 `schemaVersion`、`workPackageId`、随机 `executionId`、descriptor/manifest digest、startedAt/completedAt、`policyFingerprint`、`capabilityCheckedAt`、`RuntimeIdentityRef`、两个 payload exact ref、两个 receipt exact ref、`accessOwner`、retention 和状态。先写同目录 `.partial`，flush 后 create-only rename；目标已存在则拒绝覆盖。

- [ ] **Step 4: 实现 narrow operation context 与 Gradle task**

`EvidenceArchiveOperationMain` 只注册 ObjectMapper、TimeProvider、Archive configuration、Adapter、runner 和 verifier，不扫描 Web、JDBC、Flyway 或 Security。命令只允许：

```text
archive --work-package=ops/evidence-archive/v0-2-evidence-archive-001.json --source-root=$env:VSRQG_EVIDENCE_SOURCE_ROOT --output=$env:VSRQG_ARCHIVE_REPORT
verify --work-package=ops/evidence-archive/v0-2-evidence-archive-001.json --archive-report=$env:VSRQG_ARCHIVE_REPORT --recovery-root=$env:VSRQG_RECOVERY_ROOT --output=$env:VSRQG_RECOVERY_REPORT
```

`backend/build.gradle.kts` 增加 `evidenceArchiveOperation` JavaExec task；未提供参数或 mode 非法时 exit `2`，已知操作失败 exit `1`，成功 exit `0`。

- [ ] **Step 5: 运行测试并提交**

Run 同 Step 2。Expected: PASS。

```powershell
git add backend/build.gradle.kts backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations
git commit -m "feat(archive): add controlled work package operation"
```

### Task 5: 实现独立精确版本恢复验证

**Files:**
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3Gateway.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveRecoveryVerifier.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveRecoveryVerifierTest.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/S3ConfigurationTest.kt`

- [ ] **Step 1: 写独立恢复失败测试**

覆盖相同运行身份、latest-only 引用、version shadow、payload/receipt digest 或 size 不一致、receipt 内容不引用 payload、保护模式或 retain-until 不足、恢复目录非空、partial 清理失败和完整成功：

```kotlin
assertThatThrownBy { verifier.verify(workPackage, archiveReport, recoveryRoot) }
    .isInstanceOf(EvidenceArchiveVerificationFailure::class.java)
assertThat(success.verifierIdentity).isNotEqualTo(archiveReport.runtimeIdentity)
assertThat(success.status).isEqualTo(OperationStatus.PASS)
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
./backend/gradlew.bat -p backend test --tests "com.ricezhou.vsrqg.shared.archive.operations.EvidenceArchiveRecoveryVerifierTest" --tests "com.ricezhou.vsrqg.shared.archive.S3ConfigurationTest"
```

Expected: FAIL，恢复 verifier 不存在。

- [ ] **Step 3: 实现精确恢复**

为 `S3Gateway` 增加仅按完整 `StoredObjectRef` 下载并返回响应 metadata 的窄方法；请求始终包含 `versionId`，禁止无版本调用。Verifier 先 attestation 当前身份并要求与 archive identity 不同，再下载 receipt exact version，复算 size/SHA-256、解析 canonical `ArchiveReceipt`、验证 work package 和 payload ref，之后下载 payload exact version并复算原摘要，最后对 payload/receipt 调用 `headProtection`。

恢复文件只写到新的显式 recovery root，以 `.partial` 开始；成功报告写完后删除恢复内容，删除失败使结果为 `FAIL`。报告不得保存本地路径、原始主体或临时 URL。

- [ ] **Step 4: 运行测试并提交**

Run 同 Step 2。Expected: PASS。

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3Gateway.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveRecoveryVerifier.kt backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive
git commit -m "feat(archive): verify independent exact-version recovery"
```

### Task 6: 建立 Evidence schema 与离线交叉校验

**Files:**
- Create: `ops/evidence-archive/schemas/archive-execution.schema.json`
- Create: `ops/evidence-archive/schemas/recovery-verification.schema.json`
- Create: `scripts/evidence-archive/verify-evidence.mjs`
- Modify: `scripts/tests/evidence-archive-evidence.test.mjs`
- Modify: `package.json`

- [ ] **Step 1: 写离线 verifier 失败测试**

覆盖 descriptor/report ID 不一致、少一个 Artifact、digest/version/locator 交叉不一致、相同 identity、`UNKNOWN`/`FAIL`、presigned/query/user-info、本地路径和成功 fixture：

```javascript
assert.throws(() => verifyEvidence(descriptor, archiveReport, recoveryReport), /IDENTITY_NOT_INDEPENDENT/);
assert.deepEqual(verifyEvidence(descriptor, validArchiveReport, validRecoveryReport), {
  workPackageId: "V0-2-EVIDENCE-ARCHIVE-001",
  result: "PASS",
  artifactCount: 2
});
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
node --test scripts/tests/evidence-archive-evidence.test.mjs
```

Expected: FAIL，缺少 schemas/verifier。

- [ ] **Step 3: 实现 schema 和 verifier**

使用现有 AJV/JCS 依赖；先验证三个 schema，再按 `artifactId` 排序交叉比较 source、payload、receipt 和 recovery。locator 只允许无 user-info/query/fragment 的 S3 URI，且必须由非空 bucket 与规范化 key 组成；所有 SHA-256 必须为 64 位小写；所有 exact version 必须非空且不等于 `null`；最终只在两份报告均为 `PASS`、两个 identity 不同、两个 Artifact 完整且无 `FAIL`/`UNKNOWN` 时输出：

```json
{"artifactCount":2,"result":"PASS","workPackageId":"V0-2-EVIDENCE-ARCHIVE-001"}
```

- [ ] **Step 4: 添加命令并提交**

```json
"verify:evidence-archive": "node scripts/evidence-archive/verify-evidence.mjs"
```

```powershell
pnpm run test:acceptance
node --test scripts/tests/evidence-archive-evidence.test.mjs
git add package.json ops/evidence-archive/schemas scripts/evidence-archive scripts/tests/evidence-archive-evidence.test.mjs
git commit -m "test(archive): validate work package evidence"
```

Expected: 全部 PASS。

### Task 7: 集成 Gate、运行手册与双语同步

**Files:**
- Modify: `scripts/m1/verify.ps1`
- Create: `docs/m1/evidence-archive-runbook.md`
- Modify: `docs/governance/acceptance/README.md`
- Modify: `docs/governance/acceptance/template.md`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveBoundaryTest.kt`

- [ ] **Step 1: 写架构和 Gate 失败测试**

架构测试断言 operation runner 只调用 `ArchiveEvidence`，恢复 verifier 只调用 `S3Gateway` read/head/identity，任何 controller/repository/Quality Engine 都不依赖 operations package。M1 增加离线 fixture Gate，但不得要求真实 S3 credential。

```kotlin
assertThat(operationDependencies).doesNotContain("release", "manifest", "quality")
assertThat(publicArchiveMethods).containsExactly("archive")
```

- [ ] **Step 2: 运行目标测试确认失败**

```powershell
./backend/gradlew.bat -p backend test --tests "com.ricezhou.vsrqg.shared.archive.ArchiveBoundaryTest"
```

Expected: FAIL，尚未加入 operations 依赖规则。

- [ ] **Step 3: 更新中文运行手册和治理说明**

手册给出三阶段命令：Release Engineer `archive`、Independent Verifier `verify`、离线 `pnpm --silent run verify:evidence-archive -- ...`。明确两次调用使用不同 repository-external identity，输出目录 create-only，失败不删除源/已提交版本，不把本地路径写入记录，不执行 merge/Tag/release/prod。Acceptance template 增加本类 Evidence 的 locator/version/digest/access owner/retention/verifier 要求，但不改变状态枚举。

- [ ] **Step 4: 同步共享文件并独立维护英文 Markdown**

Task 1～7 的所有非 Markdown commit 按顺序 cherry-pick 到 `feat/m1-release-manifest-en`。中文 Markdown 只在中文分支提交；英文分支创建语义配对的纯英文版本。不得 cherry-pick Markdown。

- [ ] **Step 5: 运行完整验证**

```powershell
pnpm install --frozen-lockfile
pnpm run test:contracts
pnpm run test:acceptance
pnpm run verify:acceptance
node --test scripts/tests/evidence-archive-evidence.test.mjs
./backend/gradlew.bat -p backend test --tests "com.ricezhou.vsrqg.shared.archive.*"
git diff --check
```

Expected: 全部 PASS；secret scan 为 0；英文 Han 为 0。

- [ ] **Step 6: 分支提交与 Pair Gate**

```powershell
git add scripts/m1/verify.ps1 backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveBoundaryTest.kt
git commit -m "test(archive): gate evidence archive operations"
git add docs/m1/evidence-archive-runbook.md docs/governance/acceptance
git commit -m "docs(archive): document evidence archive acceptance"
./scripts/verify-language-branches.ps1 -Mode Pair -ChineseRef feat/m1-release-manifest -EnglishRef feat/m1-release-manifest-en
```

Expected: `PASS mode=Pair`，所有非 Markdown blob 相同。

- [ ] **Step 7: clean HEAD、完整 M1、普通 push 和 CI**

在两个分支分别确认 clean，执行 `./scripts/m1/verify.ps1`。本地无 Docker 时保留失败并由两个精确 HEAD 的 GitHub Actions 完成正式 Gate。只在远端为祖先时普通 push，禁止 force；记录两个 run/job/artifact/digest。

### Task 8: 真实 Company 执行与初始验收记录

**Files:**
- Create after successful execution: `docs/governance/acceptance/records/$utcDate-v0-2-evidence-archive-001.md`
- Create after successful execution: `docs/governance/acceptance/evidence/$executionId/archive-report.json`
- Create after successful execution: `docs/governance/acceptance/evidence/$executionId/recovery-report.json`
- Create after successful execution: `docs/governance/acceptance/evidence/$executionId/recovery-report.json.complete.$recoveryReportSha256`

**Hard checkpoint:** 本 Task 不与 Task 1～7 自动连续执行。必须先取得真实 Provider、两个外部身份、`accessOwner`、retention policy、recovery root 和 Project Owner 对 Company 外部写入的明确执行授权。内部目标截止时间仍为 `2026-09-23T02:30:00Z`，不得晚于最早 Artifact 到期时间 `2026-09-26T02:37:56Z`。任何 credential 不得进入命令历史、日志、Git 或聊天。

- [ ] **Step 1: 只读前置检查**

确认 `VSRQG_DEPLOYMENT_MODE=COMPANY`、Provider `S3_COMPATIBLE`、archive enabled、HTTPS/native endpoint、bucket、region、prefix、正 retention、私有访问、versioning、Object Lock 和两个身份的最小权限。缺一项即停止，不创建记录。

本地输入、恢复和报告目录必须由单一受信写者控制。Linux/POSIX 缺失 `fileKey` 时 fail closed；Windows 等非 POSIX 文件系统只有在 Operator-controlled ACL 成立时才允许 real path 与稳定 metadata 身份回退，且不声称抵御受信写者 A-B-A 替换。此本地边界不替代 Company S3 Object Lock、精确 `versionId` 或 Provider protection 证明。

- [ ] **Step 2: Release Engineer 执行归档**

```powershell
$workPackage = (Resolve-Path 'ops/evidence-archive/v0-2-evidence-archive-001.json').Path
$sourceRoot = (Resolve-Path $env:VSRQG_EVIDENCE_SOURCE_ROOT).Path
$archiveReportRoot = (Resolve-Path $env:VSRQG_EVIDENCE_REPORT_ROOT).Path
$archiveReport = Join-Path $archiveReportRoot 'archive-report.json'
if (Test-Path -LiteralPath $archiveReport) {
    throw 'archive-report.json already exists; use a new trusted output directory'
}

try {
    $env:VSRQG_EVIDENCE_OPERATION_COMMAND = 'archive'
    $env:VSRQG_EVIDENCE_OPERATION_WORK_PACKAGE = $workPackage
    $env:VSRQG_EVIDENCE_OPERATION_SOURCE_ROOT = $sourceRoot
    $env:VSRQG_EVIDENCE_OPERATION_OUTPUT = $archiveReport
    ./backend/gradlew.bat -q -p backend evidenceArchiveOperation --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "archive failed with exit code $LASTEXITCODE" }
} finally {
    Remove-Item Env:VSRQG_EVIDENCE_OPERATION_COMMAND -ErrorAction SilentlyContinue
    Remove-Item Env:VSRQG_EVIDENCE_OPERATION_WORK_PACKAGE -ErrorAction SilentlyContinue
    Remove-Item Env:VSRQG_EVIDENCE_OPERATION_SOURCE_ROOT -ErrorAction SilentlyContinue
    Remove-Item Env:VSRQG_EVIDENCE_OPERATION_OUTPUT -ErrorAction SilentlyContinue
}
```

Expected: exit `0`，两个 payload/receipt exact ref，执行报告 `PASS`。工作包与源目录使用 `Resolve-Path` 固定为绝对规范路径；尚不存在的 create-only 输出由已解析的合法父目录和固定叶名称构造，因此不能对输出文件本身调用 `Resolve-Path`。命令不打印路径或 credential，路径变量只存在于 Owner 控制的当前会话且不写入 Git。

- [ ] **Step 3: 切换独立身份并执行恢复**

```powershell
$workPackage = (Resolve-Path 'ops/evidence-archive/v0-2-evidence-archive-001.json').Path
$archiveReport = (Resolve-Path $env:VSRQG_ARCHIVE_REPORT).Path
$recoveryRoot = (Resolve-Path $env:VSRQG_RECOVERY_ROOT).Path
$recoveryReportRoot = (Resolve-Path $env:VSRQG_RECOVERY_REPORT_ROOT).Path
$recoveryReport = Join-Path $recoveryReportRoot 'recovery-report.json'
if (Test-Path -LiteralPath $recoveryReport) {
    throw 'recovery-report.json already exists; use a new trusted output directory'
}

try {
    $env:VSRQG_EVIDENCE_OPERATION_COMMAND = 'verify'
    $env:VSRQG_EVIDENCE_OPERATION_WORK_PACKAGE = $workPackage
    $env:VSRQG_EVIDENCE_OPERATION_ARCHIVE_REPORT = $archiveReport
    $env:VSRQG_EVIDENCE_OPERATION_RECOVERY_ROOT = $recoveryRoot
    $env:VSRQG_EVIDENCE_OPERATION_OUTPUT = $recoveryReport
    ./backend/gradlew.bat -q -p backend evidenceArchiveOperation --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "recovery verification failed with exit code $LASTEXITCODE" }
} finally {
    Remove-Item Env:VSRQG_EVIDENCE_OPERATION_COMMAND -ErrorAction SilentlyContinue
    Remove-Item Env:VSRQG_EVIDENCE_OPERATION_WORK_PACKAGE -ErrorAction SilentlyContinue
    Remove-Item Env:VSRQG_EVIDENCE_OPERATION_ARCHIVE_REPORT -ErrorAction SilentlyContinue
    Remove-Item Env:VSRQG_EVIDENCE_OPERATION_RECOVERY_ROOT -ErrorAction SilentlyContinue
    Remove-Item Env:VSRQG_EVIDENCE_OPERATION_OUTPUT -ErrorAction SilentlyContinue
}
```

Expected: exit `0`，verifier identity 与 archive identity 不同，两个 exact-version 恢复摘要一致，报告 `PASS`。已有输入均由 `Resolve-Path` 固定；尚不存在的 create-only recovery report 由已解析的合法父目录和固定叶名称构造。Evidence Archive 专用环境变量桥把每个含空格绝对路径作为独立 JVM argv token；组合不完整、未知或空白时固定失败且不打印值。`--no-daemon` 防止复用旧环境，`finally` 只清理本任务变量，不得打印路径或 credential。

- [ ] **Step 4: 离线复核并固化 canonical 报告**

```powershell
$offlineWorkPackage = (Resolve-Path 'ops/evidence-archive/v0-2-evidence-archive-001.json').Path
$offlineArchiveReport = (Resolve-Path $env:VSRQG_ARCHIVE_REPORT).Path
$offlineRecoveryReport = (Resolve-Path $env:VSRQG_RECOVERY_REPORT).Path
pnpm --silent run verify:evidence-archive -- --work-package $offlineWorkPackage --archive-report $offlineArchiveReport --recovery-report $offlineRecoveryReport
```

Expected: `{"artifactCount":2,"result":"PASS","workPackageId":"V0-2-EVIDENCE-ARCHIVE-001"}`。禁止字段扫描为 0 后，把两份 canonical report 和由 recovery report 原始字节 SHA-256 派生的零字节 completion marker 原样放入同一 `$executionId` Evidence 目录；中英文分支共享文件必须字节一致。Git commit+path+SHA-256 是报告与 marker locator，报告中的 payload/receipt locator 仍指向 Company Object Lock 精确版本。报告不得包含本地路径、原始 principal、credential 或临时 URL。

- [ ] **Step 5: 创建双语 `PENDING` 记录**

metadata 必须使用固定 subject commits，状态只能为：

```yaml
status: PENDING
owner: PENDING
decisionAt: PENDING
```

Acceptance Checks 逐项引用稳定 locator/version/digest。只在两份外部 report、离线 verifier、Pair Gate 和 CI 都为 `PASS` 时建议 Owner 复核；记录创建本身不关闭 002 或 M1。

- [ ] **Step 6: 验证、提交、推送并等待 Owner**

```powershell
pnpm run test:acceptance
pnpm run verify:acceptance
$zhRecordCommit = git rev-parse feat/m1-release-manifest
$enRecordCommit = git rev-parse feat/m1-release-manifest-en
./scripts/verify-language-branches.ps1 -Mode Pair -ChineseRef $zhRecordCommit -EnglishRef $enRecordCommit
```

分别提交中文/英文记录，普通 push 并等待精确 HEAD CI。不得 merge、Tag、release 或 production deployment。最终向 Owner 提交 `V0-2-EVIDENCE-ARCHIVE-001` 复核请求；机器不得自动转换状态。

## 计划完成判定

- Task 1～7 在没有 Company 资源时可全部测试并交付，不制造外部成功事实。
- 唯一写入路径仍为 `ArchiveEvidence.archive(ArchiveCommand)`；operation 层不成为第二个 Capability 或 Quality 数据源。
- 固定描述符没有路径、secret 或可变期望值，两个 Artifact 和 manifest 事实准确。
- payload、receipt 和恢复均绑定 exact `versionId`、size 和 SHA-256；禁止 latest fallback。
- 归档和恢复使用不同 Provider-attested identity，报告不含原始 principal。
- 任一 partial、timeout、网络、identity、digest、retention 或 protection 失败都保留真实错误并 fail closed。
- Task 8 只有在外部资源和明确写入授权齐备后执行；成功后才创建初始 `PENDING` 记录。
- `V0-2-PILOT-COMPANY-002`、`M1-OWNER-GATE-001`、merge、Tag、release 和 production deployment 均保持独立授权。
- 中英文 Markdown 语义配对，所有非 Markdown 文件字节一致，Acceptance validator、M1 Gate、Pair Gate 和 CI 全部通过。
