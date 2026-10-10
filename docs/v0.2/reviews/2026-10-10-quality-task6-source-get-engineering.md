# Task 6 Formal Source GET Binding Engineering Record

- Date: 2026-10-10; scope: engineering verification with an isolated synthetic fixture, not Owner or milestone acceptance.
- Basis: [Task 6 export record](2026-10-10-quality-task6-readonly-report-engineering.md) and the proposed read-only report addendum to [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md).

## Result

After a fixed `COMPLETED/BLOCK` Evaluation, the existing PostgreSQL isolation test calls the formal `GET /api/v1/releases/{releaseId}/traceability` and `GET /api/v1/test-runs/{id}/results` routes using the exact `snapshotId` and `runId` in its `inputSnapshot`. The fixture retains the parsed JSON objects from both HTTP response bodies. The Traceability and Test repositories remain controlled mocks, and the terminal Test Run response now includes the fields required by the formal contract. No real Provider, device, or actual rule publication was used.

The report step in the existing M1 workflow now requires both source responses and passes them to the fixed Evaluation exporter and its existing schema and binding validator. The `COMPLETED` report retains the source responses. Conflicts in Release, Snapshot, Issue Snapshot, Manifest, Run, Attempt, Case, or Result digest fail export. A pre-pin `ERROR` queries neither source and does not invent a Quality Result. Node tests cover bound sources, a binding conflict, and missing source response rejection.

Locally, `node scripts/contract-validator.mjs`, `node --test scripts/tests/demo-report.test.mjs`, the targeted Node tests, `backend/gradlew.bat compileTestKotlin --no-daemon`, and `git diff --check` passed. Docker is unavailable locally; the M1 CI runs the persistent HTTP integration test below.

## Fixed-Commit CI

Chinese implementation `a2a870eb57a6474ac3858403e158f667d98b7215`: [M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38034078318), [M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38034078243), and [M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38034078163) all succeeded. English implementation `f5037bfec26b39ae910d14949bd8447163bb65e6`: [M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38034069992), [M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38034069956), and [M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38034069967) all succeeded. Both M1 runs completed `Render controlled quality fixture reports` and the existing demo Artifact upload. The `m1-demo` Artifact IDs are `11663299506` and `11663114897`; API metadata shows both unexpired. ZIP members have not been inspected independently.

These results establish formal GET and export binding for the isolated synthetic fixture. TDR-026 and the Task 6 addendum to TDR-023 remain under review; real Release and device Evidence, actual rule publication, A1–A8, and Owner acceptance remain open. Browser visual inspection of local `file://` reports is still blocked by the existing security policy.
