# Task 6 Controlled Four-Scenario Quality Decision Engineering Record

- Date: 2026-10-12; scope: engineering verification in an isolated environment, not Owner or milestone acceptance.
- Basis: the [Task 6 implementation plan](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md), the proposed [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md), and the [controlled-upload linkage record](2026-10-12-quality-task6-upload-report-engineering.md).

## Fixed Scenarios and Sources

`QualityEvaluationEvidenceIntegrationTest` uses separate formal Test Runs under the same synthetic Release. The test Agent uploads controlled LOG/SCREENSHOT data through the existing HTTP routes. Test Results and the Traceability Job pin formal sources; the Quality Job returns exact Evaluation IDs, and formal query responses feed separate report exports. Both demonstration rules exist only as synthetic `PUBLISHED` fixtures in the isolated test database. They are not actual rules published through the publication gate.

| Scenario | Pinned input | Expected formal result |
| --- | --- | --- |
| Normal Smoke | Explicitly empty `requiredIssueRefs`, PASS Result, LOG and SCREENSHOT | `COMPLETED`, limited `PASS` |
| Definite FAIL | Another Run under the same Release, FAIL Result, LOG and SCREENSHOT | `COMPLETED`, `BLOCK` |
| Required Issue not Verified | Formal Issue/Traceability Snapshot, PASS Result, LOG and SCREENSHOT | `COMPLETED`, `BLOCK`; the pinned Issue remains unverified |
| Missing required Evidence | ERROR Result with only LOG; the SCREENSHOT requirement is FAILED | `ERROR`, `QUALITY_REQUIRED_EVIDENCE_MISSING`, with no Quality Result or pinned input |

CI captures these Evaluations, their Traceability/Test responses, and Evidence metadata responses for completed scenarios. It verifies source IDs, Run/Attempt binding, digests, and integrity before generating separate Chinese and English read-only reports for each scenario. The pre-pin missing-Evidence ERROR retains its original code and does not query nonexistent pinned sources. The previous missing-Payload `QUALITY_EVIDENCE_INTEGRITY_ERROR` path remains. Node tests also reject a mismatched scenario decision and check both language outputs.

## Verification and Limits

Local `compileTestKotlin`, `QualityRuleGoldenTest`, `verify-contracts.tests.ps1`, targeted report tests, and `git diff --check` passed. Docker is unavailable locally; fixed-commit M1 CI is the PostgreSQL integration and report artifact evidence. Chinese implementation Subject `89c642bb668c730c7a0a71038c0aa46da0cbe52b`; English implementation Subject `2604ba6c6b77535ebe2a58b89be9f61f7e0eb4cb`. CI: [Chinese M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38208396168), [M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38208396126), [M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38208396266); [English M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38208404011), [M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38208403896), [M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38208403885). All six succeeded. Both M1 Backend gates, controlled report rendering steps, and artifact uploads succeeded.

This record supplies the plan's four controlled examples; it does not close A1–A8. A1 still needs an explicit same-APK, cross-Release binding counterexample; A5 still needs explicit treatment of uncollected Crash/ANR facts; A2/A4/A8 still need device and complete navigation evidence. Synthetic rule sets, controlled uploads, and static reports do not substitute for actual rule publication, a real Provider/device, or Owner acceptance. TDR-026 remains Proposed / REVIEW_REQUIRED.
