# Task 6 Controlled Upload to Quality Report Engineering Record

- Date: 2026-10-12; scope: engineering verification in an isolated environment, not Owner or milestone acceptance.
- Basis: the proposed [TDR-026 Task 6 addendum](../tdr/TDR-026-minimal-quality-evaluation.md) and the [same-Evaluation Evidence locator record](2026-10-12-quality-task6-evidence-linkage-engineering.md).

## Verified Path

The existing PostgreSQL `QualityEvaluationEvidenceIntegrationTest` is reused. A test Agent actually uploads and completes controlled LOG and SCREENSHOT Payloads. The formal Test Result pins both Evidence records, and Traceability and Quality Jobs create formal snapshots and a `COMPLETED` Evaluation. New checks use the same project identity to call the fixed-ID Traceability, Test Result, and both Evidence metadata GET routes. They compare Evidence ID, Run/Attempt, digest, and `integrity=VERIFIED` with the Quality input and check two integrity observations. The test then deletes only its LOG Payload and retains the existing assertion that a second Evaluation fails with `QUALITY_EVIDENCE_INTEGRITY_ERROR`.

M1 CI saves a controlled HTTP response bundle from that same path. The existing report script applies the same identity, source, and conflict checks to the bundle and generates Chinese and English static reports and JSON exports for `COMPLETED` and `ERROR`. Tests cover digest conflict rejection and verify that the report retains Evidence locators without including metadata responses, `grantId`, or `/payload` links. No download authorization or Payload is requested for the report, and no real Provider or device is enabled.

The targeted Node test, full contract validator, Kotlin test compilation, and `git diff --check` passed locally. Docker is unavailable locally; fixed-commit M1 CI supplies PostgreSQL execution. This segment uses the actual controlled upload path and file storage, but remains a synthetic test environment with a synthetic rule set. It does not replace device Evidence, actual rule publication, or Owner acceptance.

## Fixed-Commit CI

Chinese implementation `c04596edd43bdd747e356ee76e97f4d2aa2e8c94`: [M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38198677996), [M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38198677773), and [M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38198678202) all succeeded. English implementation `ca00dda62e6f82178c8c75867dee62d9809c72fa`: [M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38198677591), [M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38198677615), and [M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38198677598) all succeeded. Both M1 Backend gates, controlled report rendering steps, and demo artifact uploads succeeded.

TDR-026 and the Task 6 addendum to TDR-023 remain proposals under review. Four actual Quality Decisions, device Evidence, A1–A8, actual rule publication, and Owner acceptance remain open.
