# A1 Same-APK Cross-Release Input Binding Engineering Record

- Date: 2026-10-12; scope: isolated PostgreSQL engineering verification, not A1 Owner acceptance.
- Basis: the [A1 scope](../../superpowers/specs/2026-09-15-minimal-quality-evaluation-scope-draft.md), the [Task 5 implementation plan](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md), and the [four-scenario engineering record](2026-10-12-quality-task6-four-scenarios-engineering.md).

## Exact Counterexample

The earlier `QualityFormalSourceTest` only changed the mocked Run's `releaseId`; it did not create two real Releases sharing an APK. The updated `QualityEvaluationEvidenceIntegrationTest` builds Release A and B in the same project, with separate locked Manifests that actually reference the same APK `artifact_id`. Release B independently completes a formal Run and PASS Result with LOG and SCREENSHOT Evidence. Release A retains its Traceability, rule set, and earlier Evaluation history.

A formal `POST /quality-evaluations` for Release A then references Release B's Run. After the Worker reads formal sources, that Evaluation becomes `ERROR` with `QUALITY_TEST_RUN_INVALID` and has no Input Snapshot or Quality Result. The test also checks that both Releases' locked Manifest bindings, digests, and states; the shared APK association; Release B's Test Result; and Release A's earlier quality decision are unchanged by the rejection. The error Evaluation itself remains queryable history.

## Evidence Boundary

Local Kotlin test compilation and `git diff --check` passed. Docker is unavailable locally; fixed-commit M1 CI supplies the PostgreSQL execution evidence. Chinese implementation Subject `f060ec2f8a2b4ebda68639dbbbf503addf8d69d4`; English implementation Subject `802773653042e6f1015ba838f0fcf04c80082301`. CI: [Chinese M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38211141412), [M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38211141471), [M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38211141368); [English M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38211154316), [M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38211154417), [M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38211154317). All six succeeded. Both M1 Backend gates and controlled report rendering steps succeeded.

This test supplies engineering evidence for the “same APK, different Release” counterexample. It does not mark the other A1 input combinations, a real device, actual rule publication, or Owner acceptance as passed. TDR-026 remains Proposed / REVIEW_REQUIRED.
