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

## Next-step execution plan

Current result: Task 3 implementation and verification are underway. Git status: this record is versioned with the implementation changes. Next action: finish independent review and bilingual CI, then record verifiable results. Preconditions: the existing CI container environment. Acceptance target: real synthetic scenarios, repeated execution, and failure reports bound to implementation commits; engineering verification does not replace Owner acceptance.
