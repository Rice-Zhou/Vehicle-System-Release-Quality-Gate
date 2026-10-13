# Task 5 Published Plan Case Binding Engineering Record

- Date: 2026-10-13; scope: engineering verification, not Owner or milestone acceptance.
- Basis: the [Task 5 implementation plan](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md), [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md), and the [Task 1 source binding record](2026-09-17-quality-task1-contracts.md).

## Implementation and Boundary

The formal source reader takes the Plan ID/version from the terminal Test Run projection and calls the existing Test Management `TestRunRepository.plan()` port to read that version's Published Case. The Case ID/version in the rule set's `selectedCaseRefs`, the Published Case definition, and the selected Result must agree. An unavailable Plan, missing fields, or a mismatch returns `QUALITY_CASE_SELECTION_MISMATCH`. This port retains Test Management's publication checks and immutable versions; it does not copy SQL or create another Case authority.

The reader trusts the terminal projection returned by Test Management for the Run ID and the Plan reference it contains. This change does not rewrite the Test Run Snapshot or introduce a separate Resolution entity. Current V0.2 still permits one Run, one Case, and one Attempt; `otherAttempts=[]` does not establish multi-Attempt history support.

## Verification

The new Case ID/version mismatch test first failed as expected. After the check was added, the matching path, both mismatch variants, and an unavailable Published Plan passed in `QualityFormalSourceTest` on both branches (8/8 each). The Test Management mock in the isolated database flow now returns a Published Plan; the state of real rule publication was not changed. Docker is unavailable locally, so the fixed-commit CI is the evidence for database integration.

| Implementation commit | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| Chinese `652280f` | [38334709073](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38334709073) | [38334708978](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38334708978) | [38334708975](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38334708975) |
| English `260180e` | [38334914408](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38334914408) | [38334914391](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38334914391) | [38334914406](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38334914406) |

All six fixed-commit CI runs above succeeded; the M1 `Run M1 candidate gate` step covers backend `clean test bootJar`. TDR-026 remains Proposed / REVIEW_REQUIRED. No actual rule was published; no real Provider or device was run; no merge, Tag, or acceptance status change was made.
