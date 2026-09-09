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

The new M2DemoIntegrationTest directly captures actual A, A_AGAIN, and B response bytes to assert historical equality, paths, Gaps, and Verified=false. Docker integration was not run locally; CI on the fixed commits below supplies actual PostgreSQL/HTTP and two retained-volume M2 runs.

## Review and initial integration repair

The task review required the exact four-edge order for B/DEMO-2; the whole-plan review required its explicit Fixed transition from A=false to B=true. Both assertions were added to the scenario and actual HTTP test. Scoped re-reviews yielded engineering approval with no open findings; this is not Owner acceptance.

Initial M1 CI runs [34306619848](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34306619848) and [34306619637](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34306619637) both failed, each with 959 tests, 1 failure, and 2 existing platform skips; implementation Subjects were 040e996 / 7988db1. The failure XML from Chinese Artifact 10086981300 confirmed M2_USER_INGESTION_NOT_REJECTED; English logs confirmed the same exception.

The demo incorrectly expected ACCESS_DENIED for a USER with scope, confusing that path with missing scope. The existing TraceabilityIngestAuthorizer and BuildProvenance integration tests require 403 PROJECT_SCOPE_MISMATCH for this path; the HTTP 403 itself was correct. The repair changes only the demo's exact error-code assertion, without weakening it or changing production permissions. Subsequent HTTP response fields and statuses were checked against existing DTOs, Controllers, and integration tests.

After the repair, compileDemoKotlin and compileTestKotlin exited 0. CI on the repaired fixed Subjects below supplies actual integration GREEN; the failed runs and engineering review do not replace runtime evidence.

## Final implementation and CI Evidence

| Branch | Final implementation Subject Commit | M1 CI | M2 CI |
|---|---|---|---|
| Chinese | 8d5354dcf21ae7b506b27f56eae4d044b9beb895 | [34307583566](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34307583566) SUCCESS | [34307583503](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34307583503) SUCCESS |
| English | db98f89ab07e427beda63ac2e9616422f6f38f34 | [34307583091](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34307583091) SUCCESS | [34307583088](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34307583088) SUCCESS |

GitHub API checks confirmed completed/success and the full head_sha for all four runs. Chinese runs were created at 2026-09-09T03:33:30Z and English runs at 2026-09-09T03:33:29Z. Subsequent record commits are not these implementation Subjects.

Both test Artifacts were downloaded and read: each contains 95 full-test-results XML files and 959 tests, with 957 PASS, 2 SKIPPED, and no failures or errors. Only the two existing Windows ACL tests in EvidenceArchiveDirectoryAccessReaderTest were skipped. M2DemoIntegrationTest 1/1, M2DemoReportTest 3/3, M2DemoInputsTest 4/4, M1DemoIntegrationTest 2/2, M1DemoPackagingTest 7/7, and M1DemoReportTest 3/3 all passed.

Both CLI lifecycle and retained-volume rerun steps succeeded. Each demo ZIP was inspected: each branch has three M1 PASS runs and one expected FAILED run caused by the wrong password; two runs use IncludeM2. All four m2-summary.json files have 10/10 PASS scenarios and workingTreeDirty=false, bind the corresponding Subject, and use distinct runIds. A/DEMO-1 is true/true/false with four edges; A/DEMO-2 is false/false/false with an empty path and ISSUE_COMMIT_MISSING. Both B Issues are true/true/false with four edges and TEST_RESULT_EVIDENCE_MISSING. A/B IDs differ, historical response bytes remain stable, and latest=B; USER ingestion returns 403 and invalid-fact verification returns 422. Report field allowlists and absence of sensitive text were checked. The actual HTTP integration test above executed the byte-equality assertion; report booleans were not treated as independent replay proof.

| Artifact | ID | Generated UTC | Expires UTC | ZIP SHA-256 |
|---|---|---|---|---|
| Chinese tests | 10087410214 | 2026-09-09T03:43:32Z | 2026-10-09T03:43:31Z | 765555212f61083c9a7208703be7536989951a56d507c39d4ef2bf81fc731933 |
| English tests | 10087413513 | 2026-09-09T03:43:42Z | 2026-10-09T03:43:41Z | d6eef04df93be767fc5e52d3c4d3edc58e858c05e657836c1f2aea85bd66ff78 |
| Chinese demo | 10087409439 | 2026-09-09T03:43:30Z | 2026-10-09T03:43:30Z | 1490d7979dcac9815bdbaadeaed832be1a3c401ff905f5a385e63dfceb1d65b4 |
| English demo | 10087412935 | 2026-09-09T03:43:40Z | 2026-10-09T03:43:40Z | 4dd2767c82eb954cbd5bc7ef9a7effee9abe1fa8b434fbce9cd9211991be6cc8 |

Task review, whole-plan review, and scoped review of the CI error-code repair have no open findings. The final implementation Pair Gate and atomic paired push are complete; record commits continue through bilingual and acceptance-record validation. These digests and locators do not mean that raw Artifacts have been permanently preserved.

## Residual limitations

This is a synthetic backend workflow with all Verified=false; fixture VALID/LOW does not prove a real GitHub Build. Scenario PASS is not Release PASS/BLOCK, a complete MVP, or Company Ready. Historical comparison covers this run's A response and does not promise administrator immutability or arbitrary-field tamper detection. Local Docker is unavailable, so actual runtime evidence comes from CI. Worker FAILED and polling-timeout propagation were code-reviewed; these two faults were not separately injected to verify the demo process exit, and are not reported as runtime PASS. Platform skips are not counted as passes, and Artifacts expire. Existing M2.5 performance-reference gaps and canonical-digest coverage limitations remain.

## Next execution plan

Current result: both TDR-022 tasks have completed implementation, independent review, and bilingual CI verification; the [Owner review record](../governance/acceptance/records/2026-09-09-tdr-022-m2-demo-review-001.md) is PENDING. Git status: implementation Subjects 8d5354d / db98f89 were pushed as a pair; current records are versioned under bilingual governance. Next action: the Owner reviews TDR-022-M2-DEMO-REVIEW-001 and decides on the fixed Subjects. Prerequisites: an explicit Owner decision; report review requires no new environment. Acceptance target: confirm the synthetic walkthrough meets the current demonstration goal, or state specific conditions/adjustments, and record the decision under existing governance.
