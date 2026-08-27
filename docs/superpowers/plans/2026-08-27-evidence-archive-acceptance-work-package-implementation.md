# Evidence Archive Acceptance Work Package Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Without changing V0.1 or the existing Archive architecture, deliver a controlled, independently recoverable, machine-reviewable `V0-2-EVIDENCE-ARCHIVE-001` work package and create its initial `PENDING` acceptance record only after real Company Evidence exists.

**Architecture:** Reuse `ArchiveEvidence.archive(ArchiveCommand)`, `S3ArchiveAdapter`, `S3Gateway`, and the existing Capability chain, adding only a JVM operation entry point with no Web or database dependency. The pinned work package descriptor contains no local path or credential. Archive execution and independent recovery produce separate canonical JSON Evidence, cross-checked by a Node verifier. Real Provider writes and acceptance-record creation remain an independent final checkpoint.

**Tech Stack:** Kotlin 2 / Java 21, Spring Framework narrow context, AWS SDK S3/STS, Jackson + JCS, Node.js + AJV, PowerShell, Gradle, GitHub Actions, Markdown Acceptance Governance.

---

## File Responsibility Map

| File | Responsibility |
|---|---|
| `ops/evidence-archive/v0-2-evidence-archive-001.json` | Pins Artifact, commit, size, digest, and Pilot manifest facts without paths or credentials |
| `ops/evidence-archive/schemas/work-package.schema.json` | Work package descriptor structure |
| `ops/evidence-archive/schemas/archive-execution.schema.json` | Archive execution Evidence structure |
| `ops/evidence-archive/schemas/recovery-verification.schema.json` | Independent recovery Evidence structure |
| `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveModels.kt` | Operation inputs, execution report, and recovery report types |
| `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveSourceVerifier.kt` | Pinned input, path boundary, size, SHA-256, and Pilot manifest verification |
| `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveRunner.kt` | Invokes the sole Archive facade and atomically writes the execution report |
| `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveRecoveryVerifier.kt` | Reads payload/receipt exact versions under an independent identity and verifies controls |
| `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveOperationMain.kt` | `archive` / `verify` command entry point and process exit codes |
| `scripts/evidence-archive/verify-evidence.mjs` | Cross-checks three JSON documents and prohibited fields without Provider access |
| `scripts/tests/evidence-archive-evidence.test.mjs` | Positive and negative Evidence verifier tests |
| `docs/m1/evidence-archive-runbook.md` | Dual-identity execution, failure recovery, and acceptance handoff runbook |
| `docs/v0.2/tdr/TDR-012-evidence-archive-acceptance-operations.md` | Technology decision for the operation entry point and Evidence formats |

### Task 1: Pin the TDR and Secret-Free Work Package Descriptor

**Files:**
- Create: `docs/v0.2/tdr/TDR-012-evidence-archive-acceptance-operations.md`
- Create: `ops/evidence-archive/v0-2-evidence-archive-001.json`
- Create: `ops/evidence-archive/schemas/work-package.schema.json`
- Test: `scripts/tests/evidence-archive-evidence.test.mjs`

- [ ] **Step 1: Write the failing descriptor schema test**

Load the schema and prove that an unknown field, local absolute path, non-lowercase 64-character SHA-256, wrong size, duplicate Artifact ID, and classification other than `LOCAL_PILOT_NOT_IMMUTABLE` fail:

```javascript
test("rejects mutable or path-bearing work package input", () => {
  const candidate = structuredClone(validWorkPackage);
  candidate.sourceRoot = "C:\\staging";
  assert.equal(validateWorkPackage(candidate), false);
});
```

- [ ] **Step 2: Run the test and verify failure**

```powershell
node --test scripts/tests/evidence-archive-evidence.test.mjs
```

Expected: FAIL because the schema or `validateWorkPackage` is absent.

- [ ] **Step 3: Implement the schema and create the pinned descriptor**

`work-package.schema.json` sets `additionalProperties=false`, requires exactly two unique Artifacts, positive integer sizes, 64-character lowercase SHA-256 values, safe file names, and two 40-character lowercase commits, and prohibits every path/root/credential field. The descriptor uses exactly these facts and adds no path:

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

- [ ] **Step 4: Write `TDR-012`**

The TDR answers all nine Technology Decision Record questions and selects “narrow JVM operation + canonical JSON Evidence + two invocations.” Explicitly reject a REST management endpoint, AWS CLI scripts, a database queue table, and a new microservice. Cover V0.2/V0.3 impact, migration, testing, deployment, failure recovery, and credentials injected only through the external identity chain.

- [ ] **Step 5: Test and commit**

```powershell
node --test scripts/tests/evidence-archive-evidence.test.mjs
git diff --check
git add docs/v0.2/tdr/TDR-012-evidence-archive-acceptance-operations.md ops/evidence-archive scripts/tests/evidence-archive-evidence.test.mjs
git commit -m "docs(archive): define evidence work package operations"
```

Expected: tests PASS; the commit contains no local path, credential, or acceptance decision.

### Task 2: Complete the Exact Receipt Reference for Recovery

**Files:**
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveModels.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3ArchiveAdapter.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/FilesystemStagingArchiveAdapter.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveContractTest.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/S3ArchiveAdapterTest.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/FilesystemStagingArchiveTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
assertThat(result.receiptReference.sizeBytes).isPositive()
assertThat(result.runtimeIdentity).isEqualTo(expectedRuntimeIdentity)
assertThat(localResult.runtimeIdentity).isNull()
```

Also assert that the S3 completion identity equals the execution control identity and no result is returned after an identity change.

- [ ] **Step 2: Run target tests and verify failure**

```powershell
./backend/gradlew.bat -p backend test --tests "com.ricezhou.vsrqg.shared.archive.ArchiveContractTest" --tests "com.ricezhou.vsrqg.shared.archive.S3ArchiveAdapterTest" --tests "com.ricezhou.vsrqg.shared.archive.FilesystemStagingArchiveTest"
```

Expected: FAIL because `sizeBytes` or `runtimeIdentity` is absent.

- [ ] **Step 3: Make the minimal model extension**

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

S3 uses `archiveControl.identity`, already proven equal to the completion identity. Filesystem always uses `null`. Never place a raw principal in the model.

- [ ] **Step 4: Run tests and commit**

Run the command from Step 2. Expected: PASS.

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveModels.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3ArchiveAdapter.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/FilesystemStagingArchiveAdapter.kt backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive
git commit -m "feat(archive): expose exact recovery evidence"
```

### Task 3: Verify Pinned Source Inputs

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveModels.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveSourceVerifier.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveSourceVerifierTest.kt`

- [ ] **Step 1: Write failing source-verification tests**

Cover a non-absolute source root, symlink, escaping file name, missing ZIP, size/digest mismatch, manifest digest or classification mismatch, duplicate ID, and two valid ZIP files:

```kotlin
assertThatThrownBy { verifier.verify(descriptor, sourceRoot) }
    .isInstanceOf(EvidenceArchiveInputFailure::class.java)
assertThat(verifier.verify(validDescriptor, validSourceRoot).artifacts).hasSize(2)
```

- [ ] **Step 2: Run the test and verify failure**

```powershell
./backend/gradlew.bat -p backend test --tests "com.ricezhou.vsrqg.shared.archive.operations.EvidenceArchiveSourceVerifierTest"
```

Expected: FAIL because the types do not exist.

- [ ] **Step 3: Implement immutable input models and verifier**

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

Compute the descriptor digest first. Then use a no-follow real-path check to prove every source is a regular file under the source root and compute every size/SHA-256. Errors expose only a field name and stable error code, never an absolute path.

- [ ] **Step 4: Run tests and commit**

Run the command from Step 2. Expected: PASS.

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveSourceVerifierTest.kt
git commit -m "feat(archive): verify evidence work package sources"
```

### Task 4: Implement the Controlled Archive Operation and Execution Evidence

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveRunner.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveOperationMain.kt`
- Modify: `backend/build.gradle.kts`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveRunnerTest.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveOperationMainTest.kt`

- [ ] **Step 1: Write execution failure and success tests**

```kotlin
assertThat(success.status).isEqualTo(OperationStatus.PASS)
assertThat(success.artifacts).hasSize(2)
assertThat(success.artifacts).allMatch { it.receiptReference.versionId?.isNotBlank() == true }
assertThat(failure.status).isEqualTo(OperationStatus.FAIL)
assertThat(failure.errorCode).isEqualTo("ARCHIVE_UNAVAILABLE")
```

Cover preservation of the first successful reference when the second Artifact fails, atomic report writing, unknown errors mapping to `UNEXPECTED_FAILURE` with a nonzero process exit, and no exception message/path/secret in output.

- [ ] **Step 2: Run tests and verify failure**

```powershell
./backend/gradlew.bat -p backend test --tests "com.ricezhou.vsrqg.shared.archive.operations.EvidenceArchiveRunnerTest" --tests "com.ricezhou.vsrqg.shared.archive.operations.EvidenceArchiveOperationMainTest"
```

Expected: FAIL because the runner and main do not exist.

- [ ] **Step 3: Implement the runner**

Map every input through the existing facade only:

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

The execution report contains `schemaVersion`, `workPackageId`, random `executionId`, descriptor/manifest digest, startedAt/completedAt, `policyFingerprint`, `capabilityCheckedAt`, `RuntimeIdentityRef`, two payload exact refs, two receipt exact refs, `accessOwner`, retention, and status. Write a same-directory `.partial`, flush, and then create-only rename. Refuse to overwrite an existing target.

- [ ] **Step 4: Implement the narrow operation context and Gradle task**

`EvidenceArchiveOperationMain` registers only ObjectMapper, TimeProvider, Archive configuration, Adapters, runner, and verifier. It does not scan Web, JDBC, Flyway, or Security. Allow only:

```text
archive --work-package=ops/evidence-archive/v0-2-evidence-archive-001.json --source-root=$env:VSRQG_EVIDENCE_SOURCE_ROOT --output=$env:VSRQG_ARCHIVE_REPORT
verify --work-package=ops/evidence-archive/v0-2-evidence-archive-001.json --archive-report=$env:VSRQG_ARCHIVE_REPORT --recovery-root=$env:VSRQG_RECOVERY_ROOT --output=$env:VSRQG_RECOVERY_REPORT
```

Add an `evidenceArchiveOperation` JavaExec task to `backend/build.gradle.kts`. Missing arguments or an invalid mode exit `2`, a known operation failure exits `1`, and success exits `0`.

- [ ] **Step 5: Run tests and commit**

Run the command from Step 2. Expected: PASS.

```powershell
git add backend/build.gradle.kts backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations
git commit -m "feat(archive): add controlled work package operation"
```

### Task 5: Implement Independent Exact-Version Recovery Verification

**Files:**
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3Gateway.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveRecoveryVerifier.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveRecoveryVerifierTest.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/S3ConfigurationTest.kt`

- [ ] **Step 1: Write failing independent-recovery tests**

Cover the same runtime identity, latest-only reference, version shadow, payload/receipt digest or size mismatch, receipt content not referencing payload, insufficient protection mode or retain-until, nonempty recovery directory, partial-cleanup failure, and complete success:

```kotlin
assertThatThrownBy { verifier.verify(workPackage, archiveReport, recoveryRoot) }
    .isInstanceOf(EvidenceArchiveVerificationFailure::class.java)
assertThat(success.verifierIdentity).isNotEqualTo(archiveReport.runtimeIdentity)
assertThat(success.status).isEqualTo(OperationStatus.PASS)
```

- [ ] **Step 2: Run tests and verify failure**

```powershell
./backend/gradlew.bat -p backend test --tests "com.ricezhou.vsrqg.shared.archive.operations.EvidenceArchiveRecoveryVerifierTest" --tests "com.ricezhou.vsrqg.shared.archive.S3ConfigurationTest"
```

Expected: FAIL because the recovery verifier does not exist.

- [ ] **Step 3: Implement exact recovery**

Add a narrow `S3Gateway` method that downloads only from a complete `StoredObjectRef` and returns response metadata. Every request includes `versionId`; a versionless call is prohibited. The verifier attests the current identity and requires it to differ from the archive identity. It downloads the receipt exact version, recomputes size/SHA-256, parses canonical `ArchiveReceipt`, verifies the work package and payload ref, then downloads the payload exact version and recomputes the original digest. Finally, call `headProtection` for payload and receipt.

Write recovery files only beneath a new explicit recovery root, starting with `.partial`. Delete recovered content after the successful report is written; cleanup failure makes the result `FAIL`. The report contains no local path, raw principal, or temporary URL.

- [ ] **Step 4: Run tests and commit**

Run the command from Step 2. Expected: PASS.

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3Gateway.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveRecoveryVerifier.kt backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive
git commit -m "feat(archive): verify independent exact-version recovery"
```

### Task 6: Establish Evidence Schemas and Offline Cross-Verification

**Files:**
- Create: `ops/evidence-archive/schemas/archive-execution.schema.json`
- Create: `ops/evidence-archive/schemas/recovery-verification.schema.json`
- Create: `scripts/evidence-archive/verify-evidence.mjs`
- Modify: `scripts/tests/evidence-archive-evidence.test.mjs`
- Modify: `package.json`

- [ ] **Step 1: Write failing offline-verifier tests**

Cover descriptor/report ID mismatch, a missing Artifact, cross-document digest/version/locator mismatch, identical identity, `UNKNOWN`/`FAIL`, presigned/query/user-info, local path, and the successful fixture:

```javascript
assert.throws(() => verifyEvidence(descriptor, archiveReport, recoveryReport), /IDENTITY_NOT_INDEPENDENT/);
assert.deepEqual(verifyEvidence(descriptor, validArchiveReport, validRecoveryReport), {
  workPackageId: "V0-2-EVIDENCE-ARCHIVE-001",
  result: "PASS",
  artifactCount: 2
});
```

- [ ] **Step 2: Run tests and verify failure**

```powershell
node --test scripts/tests/evidence-archive-evidence.test.mjs
```

Expected: FAIL because schemas/verifier are absent.

- [ ] **Step 3: Implement schemas and verifier**

Use the existing AJV/JCS dependencies. Validate all three schemas, then sort by `artifactId` and cross-check source, payload, receipt, and recovery. A locator must be an S3 URI without user-info/query/fragment and with a nonempty bucket and normalized key. Every SHA-256 is 64-character lowercase. Every exact version is nonempty and not `null`. Emit the following only when both reports are `PASS`, identities differ, both Artifacts are complete, and no `FAIL`/`UNKNOWN` remains:

```json
{"workPackageId":"V0-2-EVIDENCE-ARCHIVE-001","result":"PASS","artifactCount":2}
```

- [ ] **Step 4: Add the command and commit**

```json
"verify:evidence-archive": "node scripts/evidence-archive/verify-evidence.mjs"
```

```powershell
pnpm run test:acceptance
node --test scripts/tests/evidence-archive-evidence.test.mjs
git add package.json ops/evidence-archive/schemas scripts/evidence-archive scripts/tests/evidence-archive-evidence.test.mjs
git commit -m "test(archive): validate work package evidence"
```

Expected: all PASS.

### Task 7: Integrate Gates, Runbook, and Bilingual Synchronization

**Files:**
- Modify: `scripts/m1/verify.ps1`
- Create: `docs/m1/evidence-archive-runbook.md`
- Modify: `docs/governance/acceptance/README.md`
- Modify: `docs/governance/acceptance/template.md`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveBoundaryTest.kt`

- [ ] **Step 1: Write failing architecture and Gate tests**

The architecture test proves that the operation runner calls only `ArchiveEvidence`, the recovery verifier calls only `S3Gateway` read/head/identity functions, and no controller/repository/Quality Engine depends on the operations package. M1 adds an offline fixture Gate but never requires a real S3 credential.

```kotlin
assertThat(operationDependencies).doesNotContain("release", "manifest", "quality")
assertThat(publicArchiveMethods).containsExactly("archive")
```

- [ ] **Step 2: Run the target test and verify failure**

```powershell
./backend/gradlew.bat -p backend test --tests "com.ricezhou.vsrqg.shared.archive.ArchiveBoundaryTest"
```

Expected: FAIL because operations dependency rules are absent.

- [ ] **Step 3: Update the English runbook and governance instructions**

The runbook supplies three phases: Release Engineer `archive`, Independent Verifier `verify`, and offline `pnpm run verify:evidence-archive -- ...`. Require different repository-external identities, create-only output, source/committed-version preservation on failure, no local path in records, and no merge/Tag/release/prod. Add locator/version/digest/access owner/retention/verifier requirements to the Acceptance template without changing the status enum.

- [ ] **Step 4: Synchronize shared files and maintain Chinese Markdown independently**

Cherry-pick every non-Markdown commit from Task 1 through Task 7 in order into `feat/m1-release-manifest-en`. Commit Chinese Markdown only on the Chinese branch and create semantically paired pure-English files on the English branch. Never cherry-pick Markdown.

- [ ] **Step 5: Run complete verification**

```powershell
pnpm install --frozen-lockfile
pnpm run test:contracts
pnpm run test:acceptance
pnpm run verify:acceptance
node --test scripts/tests/evidence-archive-evidence.test.mjs
./backend/gradlew.bat -p backend test --tests "com.ricezhou.vsrqg.shared.archive.*"
git diff --check
```

Expected: all PASS; secret scan is 0; English Han count is 0.

- [ ] **Step 6: Branch commits and Pair Gate**

```powershell
git add scripts/m1/verify.ps1 backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveBoundaryTest.kt
git commit -m "test(archive): gate evidence archive operations"
git add docs/m1/evidence-archive-runbook.md docs/governance/acceptance
git commit -m "docs(archive): document evidence archive acceptance"
./scripts/verify-language-branches.ps1 -Mode Pair -ChineseRef feat/m1-release-manifest -EnglishRef feat/m1-release-manifest-en
```

Expected: `PASS mode=Pair`; every non-Markdown blob is identical.

- [ ] **Step 7: Clean HEADs, full M1, normal push, and CI**

Confirm both branches are clean and run `./scripts/m1/verify.ps1` on each. If local Docker is unavailable, retain the failure and let GitHub Actions on both exact HEADs provide the formal Gate. Use normal push only when the remote is an ancestor, never force. Record both run/job/artifact/digest values.

### Task 8: Real Company Execution and Initial Acceptance Record

**Files:**
- Create after successful execution: `docs/governance/acceptance/records/$utcDate-v0-2-evidence-archive-001.md`
- Create after successful execution: `docs/governance/acceptance/evidence/$executionId/archive-execution.json`
- Create after successful execution: `docs/governance/acceptance/evidence/$executionId/recovery-verification.json`

**Hard checkpoint:** Task 8 does not run automatically after Tasks 1–7. It requires a real Provider, two external identities, `accessOwner`, retention policy, recovery root, and explicit Project Owner authorization for external Company writes. The internal target deadline remains `2026-09-23T02:30:00Z`, and execution must not pass the earliest Artifact expiry at `2026-09-26T02:37:56Z`. No credential may enter command history, logs, Git, or chat.

- [ ] **Step 1: Perform read-only prerequisite checks**

Confirm `VSRQG_DEPLOYMENT_MODE=COMPANY`, Provider `S3_COMPATIBLE`, archive enabled, HTTPS/native endpoint, bucket, region, prefix, positive retention, private access, versioning, Object Lock, and least privilege for both identities. Stop without creating a record if any item is absent.

- [ ] **Step 2: Run archival as Release Engineer**

```powershell
./backend/gradlew.bat -p backend evidenceArchiveOperation --args="archive --work-package=ops/evidence-archive/v0-2-evidence-archive-001.json --source-root=$env:VSRQG_EVIDENCE_SOURCE_ROOT --output=$env:VSRQG_ARCHIVE_REPORT"
```

Expected: exit `0`, two payload/receipt exact refs, and execution report `PASS`. The Owner-controlled environment supplies path variables at runtime; never commit them.

- [ ] **Step 3: Switch to the independent identity and run recovery**

```powershell
./backend/gradlew.bat -p backend evidenceArchiveOperation --args="verify --work-package=ops/evidence-archive/v0-2-evidence-archive-001.json --archive-report=$env:VSRQG_ARCHIVE_REPORT --recovery-root=$env:VSRQG_RECOVERY_ROOT --output=$env:VSRQG_RECOVERY_REPORT"
```

Expected: exit `0`, verifier identity differs from archive identity, both exact-version recovery digests match, and report is `PASS`.

- [ ] **Step 4: Verify offline and freeze canonical reports**

```powershell
pnpm run verify:evidence-archive -- --work-package ops/evidence-archive/v0-2-evidence-archive-001.json --archive-report $env:VSRQG_ARCHIVE_REPORT --recovery-report $env:VSRQG_RECOVERY_REPORT
```

Expected: `{"workPackageId":"V0-2-EVIDENCE-ARCHIVE-001","result":"PASS","artifactCount":2}`. After a prohibited-field scan returns 0, place both canonical reports unchanged in the same `$executionId` Evidence directory. Shared files on the Chinese and English branches must be byte-identical. Git commit+path+SHA-256 is the report locator, while payload/receipt locators inside the reports still identify Company Object Lock exact versions. Reports contain no local path, raw principal, credential, or temporary URL.

- [ ] **Step 5: Create paired `PENDING` records**

Metadata uses the pinned subject commits and can only be:

```yaml
status: PENDING
owner: PENDING
decisionAt: PENDING
```

Every Acceptance Check references a stable locator/version/digest. Recommend Owner review only when both external reports, offline verifier, Pair Gate, and CI are `PASS`. Record creation itself closes neither 002 nor M1.

- [ ] **Step 6: Verify, commit, push, and await Owner**

```powershell
pnpm run test:acceptance
pnpm run verify:acceptance
$zhRecordCommit = git rev-parse feat/m1-release-manifest
$enRecordCommit = git rev-parse feat/m1-release-manifest-en
./scripts/verify-language-branches.ps1 -Mode Pair -ChineseRef $zhRecordCommit -EnglishRef $enRecordCommit
```

Commit Chinese and English records separately, push normally, and wait for exact-HEAD CI. Do not merge, Tag, release, or deploy to production. Submit `V0-2-EVIDENCE-ARCHIVE-001` to the Owner for review; no machine may transition its state automatically.

## Plan Completion Criteria

- Tasks 1–7 can be fully tested and delivered without Company resources and create no external success fact.
- The sole write path remains `ArchiveEvidence.archive(ArchiveCommand)`; the operation layer is not a second Capability or Quality data source.
- The pinned descriptor has no path, secret, or mutable expected value, and both Artifact and manifest facts are accurate.
- Payload, receipt, and recovery bind exact `versionId`, size, and SHA-256 with no latest fallback.
- Archive and recovery use different Provider-attested identities, and reports contain no raw principal.
- Any partial, timeout, network, identity, digest, retention, or protection failure preserves the real error and fails closed.
- Task 8 runs only after external resources and explicit write authorization exist; only success permits an initial `PENDING` record.
- `V0-2-PILOT-COMPANY-002`, `M1-OWNER-GATE-001`, merge, Tag, release, and production deployment retain independent authorization.
- Chinese and English Markdown have paired semantics, all non-Markdown files are byte-identical, and Acceptance validator, M1 Gate, Pair Gate, and CI all pass.
