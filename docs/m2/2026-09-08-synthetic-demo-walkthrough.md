# M2 synthetic walkthrough implementation record

- Record date: 2026-09-09; TDR-022 task 2 engineering record, not Owner acceptance.
- Basis: [TDR-022](../v0.2/tdr/TDR-022-synthetic-m2-demonstration.md), [implementation plan task 2](../superpowers/plans/2026-09-08-synthetic-m2-demonstration.md).
- Pre-implementation baseline: Chinese 77b8a841322779d8db48f493d2f88529c8e2165b; English f22243ceac1d20fdf7589851ff2a27f8bf01bf5d. All four baseline CI runs were verified SUCCESS.

## Execution basis and scope

After task 1 completion, with task 2 explicitly identified as the next action, the Owner instructed execution of the next step on 2026-09-09. The original text is preserved as Unicode escapes; authorization covers task 2 implementation only, not Owner acceptance, deployment, or the next milestone.

```json
{"instruction":"\u6267\u884c\u4e0b\u4e00\u6b65"}
```

This work reuses the delivered synthetic inputs and M1 launcher to connect actual HTTP operations, the single-command IncludeM2 option, result reporting, and existing CI. See the [runbook](synthetic-demo-runbook.md) for operations and result interpretation. V0.1 semantics, production APIs, permissions, Schema, and canonicalization remain frozen.

## Implementation and verification status

Actual HTTP operations now connect Mapping activation, FULL Sync, Issue Snapshot, two Build ingestions, asynchronous Traceability, same-key replay, and A/B/history queries. Builds use the same M1 run's Project, Locked Manifest, and measured file digest; the existing canonicalizer computes proofDigest. Invalid facts in a separate Project are first persisted with 200 INVALID, then verification returns 422 TRACEABILITY_INPUT_NOT_VALID; a USER with ingestion scope still receives 403 PROJECT_SCOPE_MISMATCH.

M2DemoReport projects only safe fields from actual responses, strictly validates booleans, digests, path types, and Gap codes, and rejects Verified=true. Polling has a 30-second total deadline, with each request limited to 5 seconds and the remaining deadline; failure or an incomplete report causes a nonzero exit. The default entry remains M1; IncludeM2 adds the walkthrough and readable results within the original lifecycle. The production bootJar excludes demo classes.

Local RED: the missing report type caused 3 unresolved references in compileTestKotlin and a nonzero exit. GREEN: the following commands ran in backend with JDK 21, both exiting 0:

```text
./gradlew test --tests '*M2DemoReportTest' --tests '*M1DemoReportTest' --tests '*M1DemoPackagingTest'
./gradlew compileDemoKotlin compileTestKotlin
```

JUnit XML was checked: M2DemoReportTest 3, M1DemoReportTest 3, and M1DemoPackagingTest 7, totaling 13/13 PASS with no failures, errors, or skips. scripts/tests/m1-demo.tests.ps1 passed 21/21 cases: the original 20 plus 1 M2 opt-in case. AST checks passed for four PowerShell files, and git diff --check exited 0.

The new M2DemoIntegrationTest compiles and directly captures actual A, A_AGAIN, and B response bytes to assert historical equality, paths, Gaps, and Verified=false; it has not run locally. Independent review, exact-commit CI, and two M2 runs on the retained volume remain pending. No pending Owner acceptance record has been created.

## Review and initial integration repair

The task review required the exact four-edge order for B/DEMO-2; the whole-plan review required its explicit Fixed transition from A=false to B=true. Both assertions were added to the scenario and actual HTTP test. Scoped re-reviews yielded engineering approval with no open findings; this is not Owner acceptance.

Initial M1 CI runs [34306619848](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34306619848) and [34306619637](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34306619637) both failed, each with 959 tests, 1 failure, and 2 existing platform skips; implementation Subjects were 040e996 / 7988db1. The failure XML from Chinese Artifact 10086981300 confirmed M2_USER_INGESTION_NOT_REJECTED; English logs confirmed the same exception.

The demo incorrectly expected ACCESS_DENIED for a USER with scope, confusing that path with missing scope. The existing TraceabilityIngestAuthorizer and BuildProvenance integration tests require 403 PROJECT_SCOPE_MISMATCH for this path; the HTTP 403 itself was correct. The repair changes only the demo's exact error-code assertion, without weakening it or changing production permissions. Subsequent HTTP response fields and statuses were checked against existing DTOs, Controllers, and integration tests.

After the repair, compileDemoKotlin and compileTestKotlin exited 0. Actual integration GREEN still requires CI on the repaired fixed Subjects; the failed runs and engineering review cannot substitute for it.

## Residual limitations

This is a synthetic backend workflow with all Verified=false; fixture VALID/LOW does not prove a real GitHub Build. Scenario PASS is not Release PASS/BLOCK, a complete MVP, or Company Ready. Historical comparison covers this run's A response and does not promise administrator immutability or arbitrary-field tamper detection. Local Docker is unavailable; actual PostgreSQL/HTTP and retained-volume rerun evidence must come from existing CI.

## Next execution plan

Current result: task 2 is implemented and locally executable checks pass; independent review and actual integration evidence remain pending. Git status: worktree changes are uncommitted. Next action: complete independent review, paired commits, and exact-commit CI. Prerequisites: existing GitHub CI; a complete local rerun additionally requires existing containers and an out-of-repository demo password. Acceptance target: actual evidence for complete and missing paths, historical stability, replay, permission rejection, Verified=false, and nonzero failure exits; submit to the Owner after completion.
