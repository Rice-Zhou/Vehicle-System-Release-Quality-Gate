# Evidence Archive Acceptance Work Package Runbook

## 1. Purpose and Current Boundary

This runbook executes Company long-term archival, independent exact-version recovery, and offline cross-verification for `V0-2-EVIDENCE-ARCHIVE-001`. It reuses frozen first-class Evidence and the existing `ArchiveEvidence.archive(ArchiveCommand)` facade. It does not change Release, Manifest, Traceability, or the Deterministic Quality Engine.

Repository content under `ops/evidence-archive/fixtures/offline-test/` is only mechanical gate data marked `TEST_FIXTURE`. It does not access S3 or use real identities, cannot prove that a Company Provider, Object Lock, retention, or recovery has been accepted, and cannot create an acceptance record, close `V0-2-PILOT-COMPANY-002`, or change `M1-OWNER-GATE-001`. A real Company operation must be performed separately after explicit Owner authorization for external writes.

This procedure performs no merge, Tag, release, production deployment, or object deletion. Each of those actions requires separate authorization.

## 2. Roles and Trust Boundary

| Stage | Role | Identity Requirement | Output |
|---|---|---|---|
| 1 | Release Engineer | repository-external Company archive identity | `archive-report.json` |
| 2 | Independent Verifier | repository-external identity different from Stage 1 | `recovery-report.json` and a zero-byte completion marker |
| 3 | Reviewer / CI | no Provider credential | offline `PASS`/failure code |

A repository-external identity comes from Provider attestation and cannot be self-reported by Git configuration or the work package. The `principalFingerprint` values for Stages 1 and 2 must differ. An acceptance record stores only fingerprints, not an ARN, account, subject, user ID, session name, access key, secret, or token. The Independent Verifier supplies a secondary identity witness confirming that an independent session/workload identity was used and the two fingerprints differ, and records a controlled approval locator, responsible witness, and witness time. This witness cannot replace Provider attestation.

One trusted Owner must pre-create and enforce single-writer access on every source, report-output, and recovery directory. Do not execute in a shared, uncontrolled, or otherwise writable directory. Reports and markers are create-only. Overwriting one and interpreting the result as the same acceptance execution is prohibited. An internal marker-publication retry counts as idempotently complete only if an existing marker with the same name is revalidated as a trusted-ACL zero-byte file whose name binds exactly the same recovery-report digest.

## 3. Common Preparation

Start PowerShell at the repository root. These variables exist only in the current controlled session. Do not write local paths or credentials to Git, Manifest, command-log attachments, or acceptance records.

```powershell
$repoRoot = (Resolve-Path '.').Path
$gradleWrapper = Join-Path $repoRoot $(if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { 'backend/gradlew.bat' } else { 'backend/gradlew' })
$workPackage = (Resolve-Path 'ops/evidence-archive/v0-2-evidence-archive-001.json').Path
$sourceRoot = (Resolve-Path $env:VSRQG_EVIDENCE_SOURCE_ROOT).Path
$reportRoot = (Resolve-Path $env:VSRQG_EVIDENCE_REPORT_ROOT).Path
$archiveReport = Join-Path $reportRoot 'archive-report.json'

if (Test-Path -LiteralPath $archiveReport) {
    throw 'archive-report.json already exists; use a new trusted output directory'
}
```

Before a real execution, inject `VSRQG_EVIDENCE_ARCHIVE_*` for Company Profile according to the [M1 Run and Recovery Runbook](runbook.md), and confirm that `COMPANY`, `S3_COMPATIBLE`, HTTPS/AWS native transport, versioning, private access, actual `COMPLIANCE` Object Lock, positive retention, and Provider-attested identity are all verifiable. Credentials may come only from Secret Manager, a workload identity, or an equivalent external identity chain.

Because Windows `gradlew.bat` reinterprets `--args`, this runbook consistently uses the dedicated Evidence Archive environment-variable bridge. Gradle passes each value as a separate JVM argv token only when the variable set is complete and exactly matches `archive` or `verify`. An unknown, blank, or incomplete combination fails with fixed `EVIDENCE_OPERATION_ENV_INVALID` without printing values. `--no-daemon` prevents a Gradle daemon from reusing a previous environment, and `-q` avoids printing command parameters. At task completion, clear only `VSRQG_EVIDENCE_OPERATION_*`; do not clear other `VSRQG_*` configuration required by the application.

On Linux/POSIX file systems, trusted directories and files must have a non-empty `fileKey`; absence fails closed. On Windows and other non-POSIX file systems, the program actually reads `AclFileAttributeView`, Owner, and the ACL. Only Owner, and SYSTEM and `BUILTIN\Administrators` resolved by host principal lookup and confirmed equal as objects, may have permission to write data, append, create, change attributes/ACL/Owner, or delete. An `ALLOW` for Everyone, Users, Authenticated Users, an unknown principal, or a principal whose lookup fails causes fail-closed behavior; `DENY` does not offset an unknown `ALLOW`. Only after verification may local identity fall back to real path, creation time, last-modified time, size, and type. This still depends on a single trusted writer and does not claim to prevent a trusted writer from performing an A-B-A replacement. Company S3 Object Lock, exact `versionId`, and Provider protection verification remain unchanged.

ACL or POSIX permissions must be verified separately for the parent directory and the file object itself. A trusted parent does not imply a trusted file. Source files, work package, archive/recovery reports, staging files, publication targets, and completion marker each provide their own access proof at the relevant operation boundary. A staging file refreshes its proof after writing and is checked again before publication or cleanup. An access-proof change or read failure fails closed.

The Source Verifier establishes a trusted-directory identity for the source root. For the manifest and each ZIP, it rechecks parent identity, exact-file access proof, file identity, size, and timestamps before open, after open and before first read, after reading, and after channel close. Shared write permission, failure to read ACL view/Owner/ACL, or any staged change stops with `SOURCE_ROOT_INVALID` / `SOURCE_FILE_INVALID`; existing ZIP structure and extraction limits remain active.

`$sourceRoot` must contain the two ZIP files and `pilot-preservation-manifest.json` listed in the fixed work package, with size/SHA-256 matching the descriptor. A Stage 1 failure must not be addressed by editing the descriptor to match local files.

## 4. Stage 1: Release Engineer Archive

```powershell
try {
    $env:VSRQG_EVIDENCE_OPERATION_COMMAND = 'archive'
    $env:VSRQG_EVIDENCE_OPERATION_WORK_PACKAGE = $workPackage
    $env:VSRQG_EVIDENCE_OPERATION_SOURCE_ROOT = $sourceRoot
    $env:VSRQG_EVIDENCE_OPERATION_OUTPUT = $archiveReport
    & $gradleWrapper -q -p backend evidenceArchiveOperation --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "archive failed with exit code $LASTEXITCODE" }
} finally {
    Remove-Item Env:VSRQG_EVIDENCE_OPERATION_COMMAND -ErrorAction SilentlyContinue
    Remove-Item Env:VSRQG_EVIDENCE_OPERATION_WORK_PACKAGE -ErrorAction SilentlyContinue
    Remove-Item Env:VSRQG_EVIDENCE_OPERATION_SOURCE_ROOT -ErrorAction SilentlyContinue
    Remove-Item Env:VSRQG_EVIDENCE_OPERATION_OUTPUT -ErrorAction SilentlyContinue
}
```

A successful output must be `PASS` and contain two Artifacts. The Release Engineer then preserves read-only:

- `executionId`, descriptor/manifest digest, and times;
- archive identity fingerprint, `policyFingerprint`, `accessOwner`, retention, and `COMPLIANCE`;
- provider, bucket/key, locator, non-empty `versionId`, SHA-256, and size for two payloads and two receipts.

All four exact object identities (provider, bucket, key, `versionId`) must be globally unique. On failure, preserve both source ZIP files, manifest, committed payload/receipt versions, and control objects for reconciliation. Clean up only a partial owned by this execution, and never delete or overwrite a committed version.

## 5. Stage 2: Independent Verifier Recovery

End the Stage 1 identity session. The Independent Verifier uses a different controlled identity with Provider attestation, opens a new shell, resets the common variables, and prepares a new empty recovery directory and new trusted report directory. Both directories must be absolute canonical paths, non-symlinks, and single-writer controlled. `recovery-report.json` must not already exist.

```powershell
$repoRoot = (Resolve-Path '.').Path
$gradleWrapper = Join-Path $repoRoot $(if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { 'backend/gradlew.bat' } else { 'backend/gradlew' })
$workPackage = (Resolve-Path 'ops/evidence-archive/v0-2-evidence-archive-001.json').Path
$archiveReport = (Resolve-Path $env:VSRQG_ARCHIVE_REPORT).Path
$recoveryRoot = (Resolve-Path $env:VSRQG_RECOVERY_ROOT).Path
$recoveryReportRoot = (Resolve-Path $env:VSRQG_RECOVERY_REPORT_ROOT).Path
$recoveryReport = Join-Path $recoveryReportRoot 'recovery-report.json'

if ((Get-ChildItem -LiteralPath $recoveryRoot -Force | Measure-Object).Count -ne 0) {
    throw 'recovery root must be empty'
}
if (Test-Path -LiteralPath $recoveryReport) {
    throw 'recovery-report.json already exists; use a new trusted output directory'
}

try {
    $env:VSRQG_EVIDENCE_OPERATION_COMMAND = 'verify'
    $env:VSRQG_EVIDENCE_OPERATION_WORK_PACKAGE = $workPackage
    $env:VSRQG_EVIDENCE_OPERATION_ARCHIVE_REPORT = $archiveReport
    $env:VSRQG_EVIDENCE_OPERATION_RECOVERY_ROOT = $recoveryRoot
    $env:VSRQG_EVIDENCE_OPERATION_OUTPUT = $recoveryReport
    & $gradleWrapper -q -p backend evidenceArchiveOperation --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "recovery verification failed with exit code $LASTEXITCODE" }
} finally {
    Remove-Item Env:VSRQG_EVIDENCE_OPERATION_COMMAND -ErrorAction SilentlyContinue
    Remove-Item Env:VSRQG_EVIDENCE_OPERATION_WORK_PACKAGE -ErrorAction SilentlyContinue
    Remove-Item Env:VSRQG_EVIDENCE_OPERATION_ARCHIVE_REPORT -ErrorAction SilentlyContinue
    Remove-Item Env:VSRQG_EVIDENCE_OPERATION_RECOVERY_ROOT -ErrorAction SilentlyContinue
    Remove-Item Env:VSRQG_EVIDENCE_OPERATION_OUTPUT -ErrorAction SilentlyContinue
}
```

The Verifier reads each receipt/payload only by the exact `versionId` in the report, then verifies digest, size, receipt binding, actual protection, and retain-until. Reading latest is prohibited. After success, the recovery directory should be cleaned to empty, and the report directory must contain:

```text
recovery-report.json.complete.<sha256(raw recovery-report.json bytes)>
```

The marker must be a zero-byte regular file. The program first creates a marker partial create-only under an unpredictable name in the same trusted directory, forces its empty content, verifies that partial's own ACL, identity, and zero-byte state, and closes the channel successfully. Only then does it publish the final marker through a create-only hard link. The hard link is the irreversible commit point: the final path and the completely verified, closed partial are the same file object, and the ACL is not inferred again from the destination path. A verification, close, or hard-link failure before the commit point must not leave this execution's final marker. After hard-link success, the final marker must not be deleted and the operation must not change to `FAIL`. Directory force and partial cleanup after that point are housekeeping only: a failure emits a fixed redacted warning code, may leave a random partial, and does not change the valid zero-byte final marker or `PASS`. The Operator should isolate and clean a remaining partial only after confirming ownership. On a concurrent collision, the program must first verify that the existing final is an exact trusted zero-byte marker. A valid final is treated as an existing commit and current partial-cleanup failure is a warning only. An invalid final remains a conflict failure; a cleanup exception may only be suppressed/warned and must not replace the root cause or delete the existing final. A PASS report may remain for diagnosis, but without the final marker the operation is incomplete. The marker name binds the raw report bytes; a missing, nonzero, symlinked, digest-mismatched, or renamed marker fails offline acceptance.

## 6. Stage 3: Offline Cross-verification

Offline verification does not access the Provider or require S3 credentials. The Node CLI accepts exactly three absolute canonical paths. PowerShell must first pin them with `Resolve-Path`. On Windows, `pnpm --silent run` arguments are strictly decoded by the CLI and then checked again as absolute canonical paths, while preventing the package manager from echoing a command line that contains local paths.

```powershell
$offlineWorkPackage = (Resolve-Path 'ops/evidence-archive/v0-2-evidence-archive-001.json').Path
$offlineArchiveReport = (Resolve-Path $env:VSRQG_ARCHIVE_REPORT).Path
$offlineRecoveryReport = (Resolve-Path $env:VSRQG_RECOVERY_REPORT).Path

pnpm --silent run verify:evidence-archive -- `
    --work-package $offlineWorkPackage `
    --archive-report $offlineArchiveReport `
    --recovery-report $offlineRecoveryReport
if ($LASTEXITCODE -ne 0) { throw "offline verification failed with exit code $LASTEXITCODE" }
```

Manual review may begin only for output `{"artifactCount":2,"result":"PASS","workPackageId":"V0-2-EVIDENCE-ARCHIVE-001"}`. This `PASS` proves internal consistency among the three files. It does not itself authenticate execution authorization, Company-environment provenance, or a Git locator.

The M1 no-Provider fixture gate uses the same command and `ops/evidence-archive/fixtures/offline-test/`. All critical references are marked `TEST_FIXTURE`; the fixture proves only that the toolchain is replayable and cannot be copied into a Company acceptance record.

On every host, M1 runs `scripts/tests/evidence-archive-gradle-args.tests.ps1` as the independent `evidence-archive-operation-args` gate and automatically selects `gradlew.bat` or `gradlew`. The probe creates only a canonical invalid `{}` work package, isolates `VSRQG_*`/`AWS_*` Provider environment and disables EC2 metadata, and fails deterministically before Provider construction. It also verifies complete, partial, unknown, and blank bridge combinations and the legacy `--args` compatibility entry point without accessing a Provider or creating a report/marker. Only the Windows probe can prove the Windows path-with-spaces argv regression; a Unix PASS must not be extrapolated as proof of `gradlew.bat`.

## 7. Committing Evidence and Acceptance Handoff

Before creating an actual acceptance record, first pin the candidate commit. Copy the following three files under their original names into the same repository Evidence directory. Stop if a target exists; do not overwrite it:

1. `archive-report.json`;
2. `recovery-report.json`;
3. the adjacent zero-byte `recovery-report.json.complete.<digest>` marker from item 2.

The marker-name digest must equal the SHA-256 of the repository `recovery-report.json` raw bytes. Recompute all three digests after copying. The marker's own SHA-256 is the standard digest of an empty file, while the digest in its file name binds the recovery report. The acceptance record uses a repository-relative locator, Git commit, and blob/report digest; it does not include a local source path, absolute report path, or recovery root.

The Evidence handoff must include at least: four exact object locators/versionIds/digests/sizes; `accessOwner`; retention; actual protection mode and retain-until; archive and verifier identity fingerprints; Git locators/digests of both reports and the marker; and the secondary identity-witness locator, responsible witness, and witness time. If any item is missing or inaccessible, write `UNKNOWN` for the corresponding Acceptance Check.

Without a real Company archive, independent recovery, offline `PASS`, and controlled Evidence locator, do not create a `V0-2-EVIDENCE-ARCHIVE-001` record and do not close `V0-2-PILOT-COMPANY-002` or `M1-OWNER-GATE-001`. The initial status of a new record must remain `PENDING`; an Owner decision is recorded in a later independent commit.

## 8. Failure Recovery

- Input size/digest/manifest mismatch: stop and preserve sources; reacquire Evidence from the authoritative CI Artifact and do not modify fixed work-package facts.
- Capability, identity, transport, private access, versioning, Object Lock, or retention failure: stop the Company flow and rerun in a new output directory only after correcting Provider configuration. Do not downgrade to filesystem and claim long-term success.
- Second Artifact failure: preserve the first committed exact version for inventory reconciliation. A retry may reuse content-addressed objects but must not delete an old version.
- Same identity, or version/digest/size/receipt/protection inconsistency: stop publication and investigate independently. Do not switch to latest, overwrite a report, or shorten retention.
- Existing report target, or marker content/ACL/name that does not exactly match the current recovery report: treat it as a create-only conflict and use a new trusted directory. Do not delete existing Evidence to reuse a name. Only an internal marker-publication retry encountering the exact trusted zero-byte marker of the same name may return idempotently.
- Recovery cleanup failure: the report remains `FAIL`; isolate the recovery directory and record the failure. Do not manually add a zero-byte marker to rewrite failure as success.
- `MARKER_DIRECTORY_FORCE_FAILED` or `MARKER_PARTIAL_CLEANUP_FAILED` after marker commit: the final marker is already the completion signal and the operation remains `PASS`. Record the fixed warning code and clean a random partial only after confirming ownership; never delete or recreate the final marker.
- Offline verification failure: preserve all three inputs and the marker for review. Correct the root cause and repeat the Provider stage; do not edit a canonical report.

## 9. Docker, CI, and Production Boundary

The Evidence Archive offline gate requires neither Docker nor real S3 and can run locally and in CI. Complete M1 still includes PostgreSQL/Testcontainers, smoke, and restore. If the local host has no Docker-compatible runtime, it must fail explicitly after all pre-Docker gates pass, and the approved GitHub Actions or Company-compatible Runner must execute the complete gate.

CI fixture `PASS`, complete M1 `PASS`, and real Company Provider acceptance are three different kinds of Evidence. The first two cannot prove that the Company bucket, identity, Object Lock, or production readiness is complete. Any `merge`, Tag, release, or production deployment still requires separate Owner authorization.
