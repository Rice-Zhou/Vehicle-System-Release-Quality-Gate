# M2.5 Evidence Archive Identity Extension Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans task-by-task, with review checkpoints and checkbox tracking.

**Goal:** Support M1 v1 and M2.5 v2 in the same archive toolchain with identity binding, safe failure diagnostics, and legacy compatibility.

**Architecture:** One Kotlin profile validator and existing parser/Archive facade, a shared Schema identity definition, and the existing Node offline cross-check. No new service, database, or Provider.

**Tech Stack:** Kotlin/JVM 21, JUnit 5, Jackson, JSON Schema 2020-12, AJV 8.17.1, Node.js, PowerShell, Gradle.

**Spec:** [TDR-019](../../v0.2/tdr/TDR-019-versioned-evidence-archive-work-package-identity.md). **Authorization record:** [TDR-019-WRITTEN-REVIEW-001](../../governance/acceptance/records/2026-09-07-tdr-019-written-review-001.md).

**Status:** Tasks 1 and 2 are complete; Task 3 implementation and local verification are complete, with independent review and remote CI passed. Owner acceptance remains pending.

## Global Constraints and Preflight

- Support only `1 / V0-2-EVIDENCE-ARCHIVE-001` and `2 / M2-5-EVIDENCE-ARCHIVE-001`。
- Exactly two Artifacts per package; preserve `LOCAL_PILOT_NOT_IMMUTABLE`、`conditionBClosed=false`、`companyArchiveCompleted=false`。
- Do not change frozen semantics, Archive Receipt/Port/Capability, create-only, exact-version, dual identities, canonical algorithms, or completion-marker semantics.
- Preserve M1 descriptor, manifest, original ZIP, and historical report bytes; retain TDR-019's fixed M2.5 Subjects, two Artifacts, and preparation-manifest digest.
- No real Provider/Company, merge, Tag, release, deployment, or next milestone. Use existing test doubles and local temporary directories only; TEST_FIXTURE cannot serve Company acceptance.
- Start implementation only after explicit authorization. Read AGENTS.md, frozen architecture authorities, TDR-012/013/019, this plan, and the preparation package. Locate existing bilingual worktrees with git worktree list and check uncommitted work and remote differences.
- Record red/green commands, actual exits, findings, and commits after every task. Task 1 is an intermediate integration state; do not claim the M2.5 tool is deliverable before Task 3 completes.
- Code blocks define plan requirements; task checkboxes and verification records identify what has actually been implemented.

## Task 1: Version Contract and Single Validation

**Files:**
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveSourceVerifier.kt`
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveModels.kt`
- `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveSourceVerifierTest.kt`
- `ops/evidence-archive/schemas/work-package.schema.json`
- `ops/evidence-archive/schemas/archive-execution.schema.json`
- `ops/evidence-archive/schemas/recovery-verification.schema.json`
- `scripts/evidence-archive/verify-evidence.mjs`
- `scripts/tests/evidence-archive-evidence.test.mjs`

**Interfaces:** Add this internal profile in the SourceVerifier file. Append an explicit Int schemaVersion to ParsedEvidenceArchiveWorkPackage and VerifiedEvidenceArchiveWorkPackage without an implicit M1 default. Update every constructor with 1 or the parsed version; retain other fields and the existing parser entry point.

```kotlin
internal enum class EvidenceArchiveWorkPackageProfile(
    val schemaVersion: Int,
    val workPackageId: String,
) {
    M1(1, "V0-2-EVIDENCE-ARCHIVE-001"),
    M25(2, "M2-5-EVIDENCE-ARCHIVE-001");

    companion object {
        fun resolve(version: Int, id: String): EvidenceArchiveWorkPackageProfile? =
            entries.singleOrNull { it.schemaVersion == version && it.workPackageId == id }
    }
}
```

- [x] First add this test to SourceVerifierTest using its existing descriptorBytes, objectMapper, and imports. It initially fails compilation without schemaVersion; after adding the property, v2 must fail on the existing fixed ID. Record both stages.

```kotlin
@Test
fun `accepts M25 pair and rejects crossed pairs`() {
    val root = objectMapper.readTree(descriptorBytes()) as ObjectNode
    root.put("schemaVersion", 2)
    root.put("workPackageId", "M2-5-EVIDENCE-ARCHIVE-001")
    val parsed = EvidenceArchiveWorkPackageParser().parse(objectMapper.writeValueAsBytes(root))
    assertThat(parsed.schemaVersion).isEqualTo(2)
    assertThat(parsed.workPackageId).isEqualTo("M2-5-EVIDENCE-ARCHIVE-001")
    root.put("schemaVersion", 1)
    assertThatThrownBy {
        EvidenceArchiveWorkPackageParser().parse(objectMapper.writeValueAsBytes(root))
    }.isInstanceOf(EvidenceArchiveInputFailure::class.java)
}
```

- [x] Add negative cases for versions 0/3, unknown ID, 2/M1, oversized non-Int numbers, and missing fields; retain source digest, size, ZIP32, ACL, and duplicate-field tests.
- [x] In the single parser, strictly read an Int-representable schemaVersion, then call resolve. A null result uses existing DESCRIPTOR_INVALID; never infer version from ID. SourceVerifier propagates the version into its Verified result.
- [x] Place this definition in work-package Schema at $defs.identity. Its root references it through allOf, replacing the old single-value const. The archive Schema references the same definition. Do not put additionalProperties=false inside this identity fragment; existing root unknown-field rejection stays active.

```json
"identity": {
  "oneOf": [
    {
      "type": "object",
      "required": ["schemaVersion", "workPackageId"],
      "properties": {
        "schemaVersion": { "const": 1 },
        "workPackageId": { "const": "V0-2-EVIDENCE-ARCHIVE-001" }
      }
    },
    {
      "type": "object",
      "required": ["schemaVersion", "workPackageId"],
      "properties": {
        "schemaVersion": { "const": 2 },
        "workPackageId": { "const": "M2-5-EVIDENCE-ARCHIVE-001" }
      }
    }
  ]
}
```

- [x] The recovery Schema uses two alternatives: the bound identity above, or an unbound version-2/null-ID FAIL. The latter requires null executionId, descriptorSha256, pilotManifestSha256, archiveIdentity, and verifierIdentity, and empty artifacts. Preserve required safe errorCode, cleanup constraints, and FAIL/PASS rules; IN_PROGRESS is never a final report.
- [x] Offline loader and test AJV instances register work-package Schema before compiling referring reports; preserve explicit initialization errors. Remove Node WORK_PACKAGE_ID checks and fixed success output. Require equal versions and IDs across all three documents and return the validated descriptor ID on success.
- [x] Add this real offline test using existing fixtures/helpers; also cover independent ID/version mutations in each document and unbound FAIL/null PASS.

```javascript
test("accepts M25 identity and rejects mixed report versions", () => {
  const f = evidenceFixture();
  f.descriptor.schemaVersion = 2;
  f.descriptor.workPackageId = "M2-5-EVIDENCE-ARCHIVE-001";
  f.descriptorBytes = Buffer.from(JSON.stringify(f.descriptor));
  for (const report of [f.archiveReport, f.recoveryReport]) {
    report.schemaVersion = 2;
    report.workPackageId = f.descriptor.workPackageId;
    report.descriptorSha256 = sha256(f.descriptorBytes);
  }
  f.archiveReportBytes = canonicalBytes(f.archiveReport);
  f.recoveryReportBytes = canonicalBytes(f.recoveryReport);
  assert.equal(verifyFixture(f).workPackageId, f.descriptor.workPackageId);
  f.recoveryReport.schemaVersion = 1;
  f.recoveryReportBytes = canonicalBytes(f.recoveryReport);
  assert.throws(() => verifyFixture(f));
});
```

- [x] Run targeted tests, fix to green, review the diff, and commit. Kotlin constructor changes only propagate explicit versions; avoid incidental refactoring and keep existing M1 tests passing.

```powershell
node --test scripts/tests/evidence-archive-evidence.test.mjs
# Run from backend
./gradlew test --tests "*EvidenceArchiveSourceVerifierTest"
```

Add JUnit @Timeout(60), in its default seconds, to touched backend test classes so individual tests cannot hang indefinitely. Report compilation and dependency-download time separately from test timeouts. Suggested commit message: `feat(archive): validate versioned work package identities`.

## Task 2: Execution, Recovery, and Safe Summaries

**Files:**
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveRunner.kt`
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveRecoveryVerifier.kt`
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveOperationMain.kt`
- `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveRunnerTest.kt`
- `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveRecoveryVerifierTest.kt`
- `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveOperationMainTest.kt`

**Interfaces:** Consume Task 1's resolve and explicit schemaVersion. Make RecoveryReport.workPackageId a String?; safeFailureReport receives this invocation's fully parsed work package or null. Append an internally used Int? schemaVersion to OperationSummary with explicit values at every constructor; its JSON output retains the existing field shape. Add no public management entry point.

- [x] First add the Runner v2 behavior test using existing resultFor, ScriptedArchiveAdapter, and WORK_PACKAGE. Also reject a v2 ID/v1 version and mismatched receipt acceptanceId; do not rely solely on mock call counts.

```kotlin
@Test
fun `propagates M25 identity through facade and report`() {
    val id = "M2-5-EVIDENCE-ARCHIVE-001"
    fun result(source: VerifiedArchiveSource): ArchiveResult {
        val original = resultFor(source)
        return original.copy(receipt = original.receipt.copy(acceptanceId = id))
    }
    val adapter = ScriptedArchiveAdapter(result(FIRST_SOURCE), result(SECOND_SOURCE))
    val report = runner(adapter).run(WORK_PACKAGE.copy(workPackageId = id, schemaVersion = 2))
    assertThat(report.status).isEqualTo(OperationStatus.PASS)
    assertThat(report.schemaVersion).isEqualTo(2)
    assertThat(report.workPackageId).isEqualTo(id)
    assertThat(adapter.commands).allSatisfy { assertThat(it.acceptanceId).isEqualTo(id) }
}
```

- [x] Add the pre-parse recovery failure test. Then use a valid v2 descriptor and malformed archive JSON to verify post-parse failure remains v2/M2.5. Cover both recover and recoverFiles, including an unreadable archive file with a valid descriptor.

```kotlin
@Test
fun `malformed descriptor has no inferred work package identity`() {
    val output = reportOutput("unbound.json")
    val result = fixture.verifier().recover(
        "{".toByteArray(),
        fixture.archiveReportBytes(),
        emptyRoot("unbound-recovery"),
        output,
    )
    assertThat(result.status).isEqualTo(OperationStatus.FAIL)
    assertThat(result.schemaVersion).isEqualTo(2)
    assertThat(result.workPackageId).isNull()
    assertThat(result.descriptorSha256).isNull()
    assertThat(result.artifacts).isEmpty()
}
```

- [x] Runner validates the work-package pair through resolve before facade/provider work. Report version comes from the package; receipt acceptanceId continues through the existing propagation and verification path. Fixed M1 canonical bytes remain unchanged.
- [x] Make staged recovery input loading explicitly sequential: beginOutput → read and fully parse descriptor → retain this invocation's validated context → read/parse archive report → execute. Do not eagerly read both files into a Pair, which loses an identifiable package when reading the second file fails. Context stays local to the invocation, never global.
- [x] Before full descriptor validation, provisional output is v2/null/IN_PROGRESS and final failure is v2/null/FAIL, with other unbound fields cleared as in Task 1. Post-parse failures use the context version/ID; never copy untrusted archive identity into failed reports. Preserve existing exception classifications, cleanup outcomes, Error propagation, and safe diagnostics without new swallowed failures.
- [x] Recovery validateWorkPackage, validateArchive, and exactSchemaVersion paths use the same profile/equality constraints. Preserve digest, executionId, exact-reference, actual-protection, and receipt acceptanceId checks. The canonical writer emits JSON null ID, never the string null. Provisional is not a final report. Retain existing completion-marker byte binding and publication semantics; FAIL cannot pass offline acceptance even if a completion marker exists.
- [x] OperationMain summary PASS requires successful resolve, two Artifacts, and no error. FAIL permits safe null identity. Test both valid pairs, mismatches, unknown IDs, null PASS, original M1 JSON bytes, stderr, and nonzero exits. Summary versions come from validated reports, never reconstruction from ID.
- [x] Extend the existing Fixture so explicit test version/ID parameters populate descriptor, report, and receipt together. Compute the raw descriptor digest rather than hardcoding a digest to simulate binding. Save red output, run these commands to green, review, and commit.

```powershell
# Run from backend
./gradlew test --tests "*EvidenceArchiveRunnerTest" --tests "*EvidenceArchiveRecoveryVerifierTest" --tests "*EvidenceArchiveOperationMainTest"
./gradlew assemble
```

Suggested commit message: `feat(archive): propagate verified identity through recovery`.

## Task 3: Fixed Inputs and End-to-End Regression

**Files:**
- `ops/evidence-archive/m2-5-evidence-archive-001.json`
- `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveIdentityIntegrationTest.kt`
- `backend/src/test/resources/evidence-archive/identity-m25/descriptor.json`
- `backend/src/test/resources/evidence-archive/identity-m25/archive-report.json`
- `backend/src/test/resources/evidence-archive/identity-m25/recovery-report.json`
- `backend/src/test/resources/evidence-archive/identity-m25/recovery-report.json.complete.<sha256>`
- `scripts/tests/evidence-archive-evidence.test.mjs`
- `ops/evidence-archive/m2-5-preparation/README.md`
- `docs/m1/evidence-archive-runbook.md`

**Interfaces:** The new identity-m25 directory contains only deterministic canonical test fixtures. The marker's final component is the actual raw recovery digest; <sha256> is not a literal file name. JVM integration calls real SourceVerifier, Runner, and RecoveryVerifier using test S3Gateway/ArchiveAdapter doubles, and Node consumes their actual report bytes. Add no production Provider.

- [x] During authorized implementation, execute this Node module content from the repository root to create the real descriptor. Use create-only; if it exists, inspect it rather than overwrite. Compare original Subjects, runs, Artifacts, and digests field by field with the preparation manifest.

```javascript
import fs from "node:fs";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
const bytes = fs.readFileSync("ops/evidence-archive/m2-5-preparation/pilot-preservation-manifest.json");
assert.equal(createHash("sha256").update(bytes).digest("hex"),
  "c5f3b1e7ffa11a1627de70cf9b9f4853d50af5e6ad3aa40608113327fdc87300");
const manifest = JSON.parse(bytes);
assert.equal(manifest.artifacts.length, 2);
const descriptor = {
  schemaVersion: 2,
  workPackageId: "M2-5-EVIDENCE-ARCHIVE-001",
  subjectCommit: manifest.implementationSubjectCommit,
  pairedSubjectCommit: manifest.pairedImplementationSubjectCommit,
  pilotManifest: {
    fileName: "pilot-preservation-manifest.json",
    sha256: createHash("sha256").update(bytes).digest("hex"),
    classification: "LOCAL_PILOT_NOT_IMMUTABLE",
    conditionBClosed: false
  },
  artifacts: manifest.artifacts.map(a => ({
    artifactId: a.artifactId, artifactName: a.artifactName, fileName: a.fileName,
    sourceRunId: a.sourceRunId, sourceCommit: a.sourceCommit,
    sizeBytes: a.sizeBytes, sha256: a.sha256
  }))
};
fs.writeFileSync("ops/evidence-archive/m2-5-evidence-archive-001.json",
  JSON.stringify(descriptor, null, 2) + "\n", { flag: "wx" });
```

- [x] Add descriptor Schema acceptance and fixed-input manifest equality tests; retain digest comparisons for original M1 files. Never pass the preparation manifest directly to the operation.
- [x] The integration test fixes clock, executionId, test identities, and local controlled directories, then executes source→archive→verify with test ZIPs and manifest. Export fixtures from this actual JVM flow and read them with Node; do not hand-author PASS reports as producer proof. Reuse existing test gateway semantics for actual receipt bodies, versionId, and protection.
- [x] JVM tests compare generated bytes to committed fixtures byte for byte. Node adds the consumption assertion below. Reuse existing M1 JVM fixtures without rewriting them. Generate under temporary directories; never present test output as Company Evidence.

```javascript
test("consumes actual JVM M25 archive and recovery output", () => {
  const root = path.join(repositoryRoot, "backend/src/test/resources/evidence-archive/identity-m25");
  const result = evidenceVerifier.verifyEvidenceFiles({
    workPackagePath: path.join(root, "descriptor.json"),
    archiveReportPath: path.join(root, "archive-report.json"),
    recoveryReportPath: path.join(root, "recovery-report.json")
  });
  assert.equal(result.workPackageId, "M2-5-EVIDENCE-ARCHIVE-001");
});
```

- [x] Extend integration to the M1/v1 control and cover the failure matrix below. Record actual expected error classes/codes and evidence of no Provider write or false success. Preserve all existing regressions; never delete failing cases to satisfy a test count.

| Scenario | Required proof |
|---|---|
| Single-document ID/version mutation | Schema or cross-document rejection; cannot pass through another package |
| Changed raw descriptor bytes with same ID | Reject digest mismatch; never canonicalize descriptor to conceal change |
| receipt acceptanceId | Reject cross-package value after recovering actual receipt body |
| Pre-/post-parse failure | Null before parsing, correct pair after parsing; both FAIL with nonzero exit |
| Malicious ZIP, ACL/identity, cleanup/publication failure | Preserve fail-closed and foreign-file protection; never archive success |
| Valid M1 and M2.5 | JVM→Node PASS for each; retain original M1 canonical bytes |

- [x] Update the runbook with v1/v2 tool compatibility, null diagnostics, separate Company authorization, and preserving original v2 reports on rollback. The preparation package records only technical support validation, never turning resource UNKNOWN into PASS.
- [x] Run all affected archive tests, build, and existing gates. Check non-Markdown byte parity, Pair Gate, and each bilingual commit's exact-head CI. Diagnose and fix failures rather than substitute previous CI.

```powershell
# Run from repository root
node --test scripts/tests/evidence-archive-evidence.test.mjs
node scripts/acceptance-record-validator.mjs
node scripts/contract-validator.mjs
# Run from backend
./gradlew test --tests "*EvidenceArchive*Test"
./gradlew assemble
# Run from repository root after paired commits
pwsh -NoProfile -File scripts/verify-language-branches.ps1 -Mode Pair -ChineseRef docs/m2-issue-traceability-design -EnglishRef docs/m2-issue-traceability-design-en
```

- [x] Review the full diff for duplicated authority, broad swallowed errors, implicit M1 defaults, excessive input generalization, secrets, or false Company Evidence. Produce an implementation verification record containing task commits, tests, failure fixes, final CI, and original limitations; keep Owner judgment pending for separate acceptance.
Suggested commit message: `feat(archive): verify fixed M2.5 package end to end`.

## Completion Evidence and Next Execution Plan

This plan covers TDR-019 inputs, identity chain, failure handling, compatibility, migration, and validation matrix. Task 3 fixes formal inputs and locally verifies actual JVM-to-Node v1/v2 flows; independent review, paired commits, and remote CI are complete. Plan self-review covers file locations, interface consistency, bilingual technical tokens, constraint coverage, and placeholder checks.

Current result: Task 3 implementation and local verification complete; see the [Task 3 record](../../m2/2026-09-07-evidence-archive-identity-task3.md). Git state: paired implementation commits pushed; implementation Subjects and CI are pinned in the Task 3 record, while this document's commit only supplements the delivery record. Next action: Owner review and decision on TDR-019 tool implementation acceptance. Prerequisite: an explicit Owner decision for the fixed implementation Subjects; Company still requires separate resources and execution authorization. Acceptance target: retain the Owner decision against the three task commits, APPROVE_FINAL, fixed-input/failure matrix, Pair Gate, and corresponding CI; this does not mean Company archiving is complete.
