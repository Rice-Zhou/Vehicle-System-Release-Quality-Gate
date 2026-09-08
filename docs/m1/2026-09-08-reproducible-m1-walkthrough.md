# Reproducible M1 demonstration implementation record

- Date: 2026-09-08; Task 3 engineering record, not Owner acceptance.
- Basis: [TDR-021](../v0.2/tdr/TDR-021-local-m1-demonstration.md), [implementation plan](../superpowers/plans/2026-09-08-local-m1-demonstration.md).
- Baseline: Chinese 634073ed629a15fbff60d46e4342021f1c0358bf; English 4ab3336636f3528bc5ed3c203818ec40809551f4.

## Execution basis and scope

After delivery of Task 2 and the next-step plan for Task 3, the Owner instructed execution of the next step. The original Chinese is recorded as Unicode escapes; this authorizes Task 3 implementation, not demonstration acceptance or Company resource construction.

```json
{"instruction":"\u6267\u884c\u4e0b\u4e00\u6b65"}
```

This task delivers a fixed synthetic file, a single-command PowerShell entry point, safe result reports, script lifecycle tests, real execution in existing CI, and the [runbook](demo-runbook.md). It reuses the Task 1 file verifier, Task 2 isolated launcher, and existing Compose configuration without adding services, dependencies, or production APIs.

## Implementation boundaries

Both initial execution and retained-volume reuse explicitly require VSRQG_DEMO_DATABASE_PASSWORD; the script does not generate, save, or print the user's password. Only a local Docker endpoint, the dedicated vsrqg-m1-demo project, and the vsrqg_demo database are used. Only containers started by this invocation are stopped; volumes and previous reports are retained, and externally running services remain running.

Results identify the current runId, code commit, and workingTreeDirty. Real HTTP scenarios produce business IDs, digests, statuses, and error codes. Expected rejection can mean scenario PASS, while any unexpected failure or stop failure must produce overall FAILED and a nonzero exit. Reports contain no connection information, Tokens, keys, raw identities, or full exceptions.

## Verification record

Script and report tests first confirmed RED from missing entry points/types. The final restricted-inspect script matrix passed 19/19 with exit code 0, covering dependencies, remote endpoint rejection, project conflicts, password/database/child/start/stop failures, missing/mismatched reports, and three successful lifecycle states. Tool output records the execution; no separate complete log file was saved.

Local JDK 21 target tests exited 0 with BUILD SUCCESSFUL: M1DemoReportTest 3/3, M1DemoPackagingTest 4/4, and ArtifactPayloadVerifierTest 10 PASS/3 SKIPPED; 20 tests total, 17 PASS, 3 SKIPPED, and zero failures/errors. Skips are limited to existing Windows symbolic-link/POSIX permission capability tests. A real local HTTP 503 report test proves unexpected responses cannot PASS and response bodies or Tokens are not written to reports. compileTestKotlin and bootJar also succeeded.

```text
./backend/gradlew.bat -p backend test --tests '*M1DemoReportTest' --tests '*M1DemoPackagingTest' --tests '*ArtifactPayloadVerifierTest' compileTestKotlin bootJar
```

The actual local entry exited 1 with DEMO_DOCKER_UNAVAILABLE. Its summary is FAILED, with runId 93f5c322-06da-4622-8bf9-0cd225eec1d0, the pre-implementation HEAD, and workingTreeDirty=true; this is not a successful demonstration. Contract and acceptance-record validators passed. Remote results remain pending; unexecuted checks are not PASS.

Two final probes check exact scenario names: successful execution with an existing service and rejection after replacing one scenario name. Both passed, exit code 0; the actual log is backend/build/m1-report-key-probe.log. The full script matrix includes this negative case, totaling 20 cases for CI. The coordinator reran the Gradle command above with exit code 0 (targets UP-TO-DATE), logged at backend/build/m1-demo-task3-local.log, and read the JUnit XML to confirm the counts above.

Independent read-only review of all three TDR-021 tasks returned APPROVE with no Critical/Important/Minor findings, covering production isolation, real authentication and business paths, safe output, failure exits, and container ownership. The review did not execute Docker or inspect this iteration's remote Artifacts; it does not replace subsequent real CI execution or Owner acceptance.

This machine lacks a container runtime; no additional environment is installed. Real PostgreSQL/HTTP and four lifecycle executions use existing GitHub CI: initial execution, retained-volume reuse, preserving an already running service, and wrong-password failure. CI also checks unchanged source samples, consistency between reports and real exports, distinct run identifiers, and retained volumes.

## Initial CI diagnosis

Initial Subjects: Chinese 6af7ce17f6dd8c265add3bb948d56e9bc7c3de58; English 17800383f824a3d0ed0804acec93f736650af92e. Pair Gate and non-Markdown parity passed, and both branches were pushed. Chinese M1 [34195927596](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34195927596) and English M1 [34195927631](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34195927631) both FAILED, each with 947 tests, one failure, and two skips. M2 runs 34195927570/34195927617 both returned SUCCESS.

Failure XML in Chinese Artifact 10044021277 points to registration replay at M1DemoScenario.kt:82; English logs also confirm DemoHttpFailure in the same integration case. Task 3 moved registration replay after Lock to group reporting, while RegisterManifest requires DRAFT/REGISTERED before entering the idempotent executor. The original Task 2 order was verified to replay registration before Lock. The fix only restores demonstration order and marks the scenario PASS after all three replays complete; production business rules are not relaxed. The real integration test caught this regression, and CI must rerun against the fix commit. Initial failures are not counted as passes.

The fix passed local compileDemoKotlin and M1DemoReportTest 3/3, exit code 0, with actual log backend/build/task3-replay-order-local.log; diff checks passed. The real integration test remains the CI regression check; no simulated protocol manufactures a business PASS.

## CI container compatibility diagnosis

Replay-fix Subjects: Chinese 3e0947381b8bbb763a4495f0e567480fd2a9f389; English d768a64e2f732c991c3b627e3a16798d053e0d51. Scoped review returned APPROVE and Pair Gate passed. Chinese M1 [34196927967](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34196927967) and English M1 [34196927800](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34196927800) passed the original M1 candidate gate, M2.4, and 20/20 script tests, but the real demonstration returned DEMO_COMPOSE_START_FAILED before JVM startup; overall M1 CI remained FAILED. M2 runs 34196927973/34196927796 both returned SUCCESS. Failed demonstration Artifacts: Chinese 10044498291, English 10044500316; the Chinese Artifact was read and contains only a minimal FAILED report.

The actual CI image's [software inventory](https://github.com/actions/runner-images/blob/ubuntu24/20260831.293/images/ubuntu/Ubuntu2404-Readme.md) confirms Docker Compose 2.38.2. That version's [start source](https://github.com/docker/compose/blob/v2.38.2/cmd/compose/start.go) has no --wait option; its [up source](https://github.com/docker/compose/blob/v2.38.2/cmd/compose/up.go) supports --wait and --no-recreate. The fix runs up --wait --no-recreate against the already created and checked container, preserving invocation ownership and the already-running-service branch. It does not upgrade Compose, duplicate health checks, or recreate volumes. The original script fixture did not model this version limitation, so the earlier 20/20 result did not prove real startup compatibility.

The compatibility regression probe first made the old entry exit 2 on initial startup for the unsupported option, causing probe exit 1; actual log backend/build/m1-compose-compatibility-red.log. After switching to the supported command, all four cases passed: initial execution, reuse, an existing service, and startup failure; actual log backend/build/m1-compose-compatibility-green.log. The fixture now rejects start --wait, requires both --wait/--no-recreate for up, and checks that an already running service receives no create/start/up command.

## Final implementation commits and evidence checks

Scoped compatibility-fix review returned APPROVE; Pair Gate and non-Markdown parity passed. All four final CI runs completed with SUCCESS:

| Branch | Implementation Subject Commit | M1 | M2 |
|---|---|---|---|
| Chinese | 917f0c74b297cfb74e2e6dc73714de81057309cd | [34198426316](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34198426316) SUCCESS | [34198426303](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34198426303) SUCCESS |
| English | 4a05ce5b3f88df1db233610d486d4619730267ed | [34198426318](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34198426318) SUCCESS | [34198426370](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34198426370) SUCCESS |

Downloaded and read full-test-results XML from Chinese M1 Artifact 10045129918 and English Artifact 10045102918: each contains 947 tests, 945 PASS, 2 SKIPPED, and zero failures/errors. Only two existing Windows ACL cases in EvidenceArchiveDirectoryAccessReaderTest are skipped. M1DemoIntegrationTest 1/1, M1DemoPackagingTest 4/4, and M1DemoReportTest 3/3 all passed without skips. Both script matrices passed 20/20.

Read demonstration Artifacts: Chinese 10045128467 and English 10045102163. Each contains three PASS runs and one expected FAILED run, all bound to the corresponding final implementation Subject with workingTreeDirty=false. Every successful report has six PASS scenarios, observed HTTP statuses covering 200/201/401/403/409/422, and replay statuses [201,201,200]. The exported Manifest matches the report's Release ID and payloadSha256, and reports contain only the agreed fields. Wrong-password reports contain DEMO_STARTUP_FAILED/DEMO_PROCESS_FAILED, all NOT_RUN scenarios, and empty HTTP observations rather than fabricated business success.

| Case | Chinese runId | English runId |
|---|---|---|
| Fresh PASS | 945c3d3f-b781-4660-87a5-fa44f154579c | ff1f2261-ee08-4308-93b6-8868ced1df1d |
| Reuse PASS | af764fe7-6ed8-4b33-92e4-3271638810f0 | 2dd3c2f5-355d-4ffd-8653-44885f644eb4 |
| Existing service PASS | 8aec51d4-bb05-406f-aa64-1ac0bd6d5107 | a1536676-b45f-4e04-a6e1-5a1920965b80 |
| Wrong password FAILED | 66189ba5-2fba-433b-adc8-9f8a6c8ae4b3 | 2e193f41-5574-46be-b1f8-2c854232ddf4 |

Both CI logs end with lifecycle PASS: initial and stopped-volume reuse leave no running project container; a previously running service remains running after both normal and wrong-password execution; volume creation time and source-sample digest remain unchanged. These checks prove retention within the same CI job. The local runbook explains persistent reuse; temporary runners are not treated as long-term storage.

These demonstration and M1 Artifacts expire on 2026-10-08 UTC. Result records and implementation code are versioned on GitHub. Artifacts are time-limited execution evidence; no Company archive resources or immutable-storage prerequisites are added. Later documentation commits are not new implementation Subjects. All six Task 3 steps are closed, completing the three TDR-021 implementation tasks. This remains a synthetic M1 demonstration, not a complete MVP, real-vehicle validation, or Owner acceptance.

## Next-step execution plan

Current result: M1 synthetic demonstration implementation, verification, and Owner acceptance are complete; see the [Owner record](../governance/acceptance/records/2026-09-08-tdr-021-m1-demo-review-001.md). Git status: bilingual acceptance and navigation records are versioned and pushed. Next action: prepare the smallest proposal for connecting the existing M2 Issue/Build/Traceability capabilities into the synthetic demonstration. Preconditions: a next-step execution instruction; no Company resources. Acceptance target: a proposal mapped to the existing P1 gaps, with a complete chain and a missing-edge chain, explicit Verified=false, and scope confirmation before implementation.
