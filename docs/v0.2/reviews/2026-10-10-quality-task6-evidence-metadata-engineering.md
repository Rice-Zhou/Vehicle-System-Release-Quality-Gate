# Task 6 Evidence Metadata Locator Engineering Record

- Date: 2026-10-10; scope: engineering verification with an isolated fixture, not Owner or milestone acceptance.
- Basis: the proposed [TDR-026 Task 6 addendum](../tdr/TDR-026-minimal-quality-evaluation.md) and the [formal source GET record](2026-10-10-quality-task6-source-get-engineering.md).

## Verification Scope and Result

The existing `EvidenceFixture` supplies PostgreSQL, controlled Payload storage, and a test context with `vsrqg.demo.evidence.enabled=true`. For a fixed Evidence ID, the test calls the formal `GET /api/v1/evidence/{evidenceId}` route. With `evidence:read` scope and project membership, HTTP 200 returns that ID and `integrity=VERIFIED`, while `evidence_integrity_observation` gains a `VERIFIED` row. Missing scope or revoked project membership returns 403 without adding an observation. After corrupting that same test-owned Payload file, GET returns `integrity=INTEGRITY_ERROR` and persists the corresponding observation. The test requests neither a download grant nor Payload.

The bilingual static quality report test uses a nonempty `evidenceRefs` list. It confirms that the page retains Evidence ID, type, Run/Attempt, digest, and size without generating a download link, `grantId`, or `/payload` path. The current isolated formal Quality Evaluation fixture still has no Evidence references. This segment therefore does not prove that a report ID and the Evidence GET belong to the same quality input, nor does it accept navigation to real device Evidence.

The targeted Node test, contract validator, `backend/gradlew.bat compileTestKotlin --no-daemon`, and `git diff --check` passed locally. Docker is unavailable locally. The M1 `Run M1 candidate gate` below runs `clean test bootJar`, including the new PostgreSQL HTTP test. Production Evidence routes, permissions, download behavior, and the report contract were not changed.

## Fixed-Commit CI

Chinese implementation `d3efcda7478be66289d97dedae0643f2bc5c1a51`: [M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38038955641), [M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38038955660), and [M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38038955639) all succeeded. English implementation `c286994d506aca41bffd9e0cc8736048f9b8e4ab`: [M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38038962558), [M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38038962569), and [M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38038962573) all succeeded. Both M1 Backend gates succeeded.

TDR-026 and the Task 6 addendum to TDR-023 remain under review. Four actual quality decisions, navigation from the same quality input to Evidence, actual rule publication, device Evidence, A1–A8, and Owner acceptance remain open.
