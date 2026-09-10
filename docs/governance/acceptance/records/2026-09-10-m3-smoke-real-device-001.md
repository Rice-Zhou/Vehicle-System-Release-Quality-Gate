---
acceptanceId: M3-SMOKE-REAL-DEVICE-001
subject: Single-device normal and deterministic FAIL real Smoke candidate
subjectCommit: b5ed45d4cede1bb7f48f815da838febd647f1f80
pairedSubjectCommit: 9c9f97d9ebe5aadc64089530024912620eb2deb0
branch: docs/m2-issue-traceability-design-en
status: PENDING
submittedAt: 2026-09-10T13:00:46Z
owner: PENDING
decisionAt: PENDING
---

# Single-Device Real Smoke Candidate Acceptance

## Scope

This record submits only the fixed product Subjects' normal and deterministic FAIL real-device chains, formal API Run→Result→LOG/PNG, durable RESULT_ACKED, independent byte/digest verification and three compatibility fixes. Actual device execution used the paired ZH Subject; the English candidate has identical committed non-Markdown blobs.

Connection interruption, Agent restart and paired database+Payload restoration remain UNKNOWN. This is not complete Task 7, M3, Release Quality Gate or Company acceptance. Existing M3-SMOKE-IMPLEMENTATION-OWNER-GATE-001 retains its original Subjects and PENDING status; historical facts and Owner decisions are not replaced. This record is separate from product commits and authorizes no merge, Tag, release or deployment.

## Evidence

- Fixed [Subject](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/b5ed45d4cede1bb7f48f815da838febd647f1f80) / [paired Subject](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/9c9f97d9ebe5aadc64089530024912620eb2deb0).
- [Real-device verification record](../../../m3/real-device-smoke-verification.md) contains both Run/Attempt identities, four Evidence IDs/sizes/SHA values, summary SHA values, historical failures, separate RED/GREEN rounds, independent methods and controlled locators. Materials are dated 2026-09-10 and accessed by Runtime Owner / Controller. Raw logs, screenshots, configuration and credentials are not committed.
- [Implementation plan](../../../superpowers/plans/2026-09-09-single-device-smoke-implementation.md), [TDR-024](../../../v0.2/tdr/TDR-024-single-device-smoke-execution.md), [TDR-025](../../../v0.2/tdr/TDR-025-local-demo-evidence-payload.md).
- All six exact final paired-Subject M1/M2/M3 CI runs and six selected Artifacts independently PASS. See the real-device record for exact locators, sizes/SHA/expiry and verification boundaries.

## Acceptance Checks

| Check | Result | Evidence | Notes |
|---|---|---|---|
| Normal and deterministic FAIL real runs | PASS | Two distinct Runs in the real-device record | REAL_DEVICE, clean Subject, COMPLETED, Case PASS/FAIL, wrapper exit 0 |
| Receipts and actual Evidence | PASS | VerifyRealEvidence / Four Evidence SHA values | JCS, receipt, journal RESULT_ACKED, LOG/PNG bytes, PNG decode |
| Compatibility fixes and I1 closure | PASS | Separate test rounds and independent scoped review | Final Agent suites each 100 tests with no failures; I1 CLOSED, quality APPROVED |
| Exact paired M3 CI / Artifacts | PASS | Real-device record CI table | Each 128 tests, 0 failures/errors, 1 Windows-only test skipped on Linux |
| Exact paired M2 CI / Artifacts | PASS | CI table and controlled verification summaries | Each 12/12 checks PASS; start P95 misses reference target, M2 restoration is not M3 |
| Exact paired M1 CI / Artifacts | PASS | CI table and controlled verification summaries | Each 1138 tests, 0 failures/errors, 3 Windows ACL skips; 135 retainedReports bytes/hashes match, two JARs outside Artifact not independently rehashed |
| Controlled process shutdown | PASS | final-runtime-stopped.txt | PostgreSQL / Backend stopped; 55432/58443 not listening; all data retained |
| Connection interruption / Agent restart | UNKNOWN | Not injected | Unit tests do not replace real timing/lease evidence |
| Paired database+Payload restoration | UNKNOWN | Not executed | Ordinary stop/start is not restoration |
| Owner decision | PENDING | This record | No acceptance decision for these new fixed Subjects |

## Residual Risks

Selected Artifacts are verified; three M1 Windows ACL tests were skipped on Linux without a claim of local execution this turn, and two JARs outside the Artifact were not independently rehashed. Missing, expired or inaccessible Evidence remains UNKNOWN. Runtime Owner provides controlled access to original Evidence. Implementation Owner must perform outstanding connection interruption, Agent restart and paired restoration under explicit authorization and recovery prerequisites; ordinary database restart or CI_FIXTURE cannot substitute.

P3 OPEN/NON-BLOCKING/EXPLICITLY DEFERRED, existing APK warnings, single-account locks and Windows durability/spool limits remain. M2.5 P95 missed its reference target, canonical coverage outside primary paths is limited and earliest historical expiry 2026-10-07 remains applicable; actual current Artifact expiry is recorded separately. Case PASS/FAIL does not produce Release Quality; NOT_EVALUATED and verified=false remain.

## Decision Reason

PENDING

## Follow-up Actions

| Action | Owner | Due / Trigger | Closure Condition | Completion Evidence |
|---|---|---|---|---|
| Sole next execution: Task 7 Step 5 | Implementation Owner | Explicit injection/device authorization and controlled backups | Connection interruption, Agent restart, DB+Payload restoration, no duplicate install/late writes/false PASS | Timing, leases/terminal states, restored bytes/SHA-256 |
| Fixed candidate Owner decision | Project Owner | After reviewing Subjects, Evidence and risks | Explicit decision and immutable authorization locator | Separate decision record commit |

## Decision History

| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| 2026-09-10T13:00:46Z | PENDING | PENDING | Submit normal/deterministic FAIL real-device candidate, preserving outstanding recovery checks, awaiting a decision for fixed Subjects. | PENDING |
