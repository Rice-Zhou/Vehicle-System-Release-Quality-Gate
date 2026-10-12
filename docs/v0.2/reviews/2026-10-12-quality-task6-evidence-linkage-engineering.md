# Task 6 Same-Evaluation Evidence Locator Engineering Record

- Date: 2026-10-12; scope: engineering verification with an isolated fixture, not Owner or milestone acceptance.
- Basis: the proposed [TDR-026 Task 6 addendum](../tdr/TDR-026-minimal-quality-evaluation.md) and the [Evidence metadata locator record](2026-10-10-quality-task6-evidence-metadata-engineering.md).

## Implementation and Evidence

The fixed Quality Decision PostgreSQL integration test now uses one nonempty Evidence reference. A controlled `EvidenceSession` is pinned into the same Evaluation's `inputSnapshot.evidenceRefs` by the formal `ReadQualityEvidence` service. The test then calls the formal `GET /api/v1/evidence/{evidenceId}` route with the same project identity and checks Evidence ID, Run/Attempt, Payload digest, and `integrity=VERIFIED`. The repository and Payload verification are supplied by this test's synthetic fixtures; this does not represent actual upload or device collection.

The CI fixture bundle keeps that GET metadata response separately. Before generating reports, the fixture script checks the Quality input, Test Result, and Evidence metadata for matching ID, Run/Attempt, type, digest, size, available state, and integrity; missing or conflicting data fails. Metadata is excluded from `quality-report-export.json`. The bilingual static report shows only fixed Evidence locator data and neither requests download authorization or Payload nor generates `grantId` or `/payload` links. Node tests cover four identity/digest conflicts, missing metadata, and bilingual output.

The targeted Node test, contract validator, `backend/gradlew.bat compileTestKotlin --no-daemon`, and `git diff --check` passed locally. Docker is unavailable locally. The fixed-commit M1 Backend gate runs the PostgreSQL integration test in CI, followed by controlled report rendering. The earlier [Evidence metadata locator record](2026-10-10-quality-task6-evidence-metadata-engineering.md) separately verifies integrity observations, permission, and corruption for an actual controlled Payload GET. This segment verifies the locator from the same Evaluation; it has not connected an actual upload to that Evaluation.

## Fixed-Commit CI

Chinese implementation `bea0b7abcaf892f1e105840657990f31409df27c`: [M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38196858830), [M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38196858806), and [M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38196858807) all succeeded. English implementation `6bc57b7fb3f64f7ced1e3078e2015b69bb780134`: [M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38196859090), [M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38196859076), and [M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38196859181) all succeeded. Both M1 Backend gates and controlled report rendering steps succeeded.

TDR-026 and the Task 6 addendum to TDR-023 remain under review. Actual upload into the same Evaluation, four Quality Decisions, actual rule publication, device Evidence, A1–A8, and Owner acceptance remain open.
