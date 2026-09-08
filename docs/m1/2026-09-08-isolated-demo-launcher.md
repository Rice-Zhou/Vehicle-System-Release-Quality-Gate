# Isolated Demonstration Launcher Implementation Record

- Record date: 2026-09-08; Task 2 engineering record, not Owner acceptance.
- Authority: [TDR-021](../v0.2/tdr/TDR-021-local-m1-demonstration.md), [implementation-plan Task 2](../superpowers/plans/2026-09-08-local-m1-demonstration.md).
- Pre-implementation baseline: Chinese 9c7dc5e54ed4cd517a4c65b7a2bb0066d3e6a6f7; English 759f058d6cf381429cc8c6fe3dbf2a587856e7fe. All four baseline M1/M2 CI runs were verified SUCCESS.

## Execution Authority and Scope

Following Task 1 delivery and its explicit Task 2 next-step plan, the Owner again issued the instruction below, authorizing the isolated demonstration launcher and real authentication. The original Chinese is preserved using Unicode escapes; this does not imply complete demonstration acceptance, Task 3 implementation, or Company infrastructure authorization.

```json
{"instruction":"\u6267\u884c\u4e0b\u4e00\u6b65"}
```

This task adds only a separate demo source set, launcher, temporary identity, minimal identity-table initialization, real HTTP scenarios, and tests. Task 3 delivers the single-command script, fixed sample, and persisted result format. Reuse existing dependencies and the PostgreSQL test environment, without installing software or provisioning Company resources.

## Implementation Constraints

The launcher reuses VsrqgApplication, SecurityConfig, JwtPrincipalMapper, JdbcProjectAuthorizer, and Permission. Demonstration Beans are explicitly injected only; ordinary application scanning must not automatically load demonstration configuration. Production bootJar excludes demo classes.

Bind only to a random loopback port, forcing PILOT, NONE, the local verifier, and the current-process trusted version; disable Issue/Traceability Workers. Environment-supplied Company/real-Provider configuration cannot override these fixed boundaries. The database is restricted to loopback vsrqg_demo; identity initialization uses a parameterized transaction and fresh per-run identifiers, never prewriting Release/Manifest/Validation/Lock states.

Temporary JWTs are signed in memory; a real decoder checks signatures, issuer, audience, and time constraints. Existing RBAC must reject VIEWER even with write scopes. Never output keys, tokens, passwords, or connection information. Corrupt a separate working copy before registration; never rewrite historical reports or exports.

The existing registration API returns no failed-version Manifest ID in its 422 response, and no revision-list API exists. For the corrupted-file scenario only, allow a parameterized read-only lookup of the unique REJECTED revision using the Release ID returned by this run’s HTTP request and the synthetic Project ID, solely to obtain the ID required for the Lock request; zero or multiple rows fail. All business-state changes still use real HTTP; never correct state after lookup, guess IDs, or add a production API. The launcher injects this lookup into the scenario; it does not become a business authority.

## Verification Record

RED: with M1DemoIntegrationTest and M1DemoPackagingTest added before the new entry points existed, compileTestKotlin failed on unresolved new classes and Gradle explicitly reported BUILD FAILED. The original PowerShell wrapper returned 0 after printing the log; this is not a passing test result. Subsequent commands explicitly propagate the exit code. Original log: backend/build/m1-demo-red.log.

Local GREEN: the command below used the existing JDK 21 and exited 0. M1DemoPackagingTest passed 4/4, ArchitectureTest 6/6, and ArtifactPayloadVerifierTest had 10 PASS/3 SKIPPED: 23 cases, 20 PASS, 3 SKIPPED, 0 failures. The skipped cases are the two existing Windows symbolic-link capability tests and one POSIX-permission test. compileTestKotlin (including real HTTP integration tests) and bootJar succeeded; the packaging test enumerates the production JAR to exclude demo classes and verifies that ordinary component scanning does not load demonstration configuration.

```text
./backend/gradlew.bat -p backend test --tests '*M1DemoPackagingTest' --tests '*ArtifactPayloadVerifierTest' --tests '*ArchitectureTest' compileTestKotlin bootJar
```

GREEN log: backend/build/m1-demo-local.log; JUnit XML: backend/build/test-results/test. Running m1Demo without demonstration database inputs actually exited 1 with the fixed DEMO_INPUT_INVALID output; log: backend/build/m1-demo-input-failure.log. The launcher reads only VSRQG_DEMO_DATABASE_URL, VSRQG_DEMO_DATABASE_USERNAME, and VSRQG_DEMO_DATABASE_PASSWORD as explicit inputs, accepting no command-line arguments; the Task 3 single-command script is not yet present.

No usable container command exists locally; real PostgreSQL/HTTP tests must run in the existing CI container environment. New tests do not automatically skip when containers are unavailable. Unexecuted checks are not PASS.

Independent read-only review: APPROVE, with no blocking findings; checked source set/bootJar isolation, absence of demonstration component scanning, real JWT, local verifier, configuration isolation, initialization, and HTTP/read-only lookup paths. The reviewer did not rerun the build; real HTTP/database verification still awaits CI. Contract and acceptance-record validation passed.

## First CI Attempt Diagnosis

Initial implementation Subjects: Chinese ee9260d676a104d440f62d0cd67e63329e6fc67b and English e4bbcfa48d8a83ca03525beac8f4bd93ff804fab. Chinese M1 [34191581247](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34191581247) and English M1 [34191581034](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34191581034) both FAILED; M2 runs 34191581281/34191581026 both succeeded. M1 reported 944 cases, 1 failure, and 2 skips; M1DemoIntegrationTest failed with DEMO_DATABASE_INVALID while constructing DemoDatabase, before reaching HTTP scenarios.

Root cause verified from the existing Testcontainers PostgreSQL 1.21.4 bytecode: configure automatically adds loggerLevel=OFF and getJdbcUrl appends it to the URL. The demonstration entry intentionally rejects all JDBC parameters. Correct the test fixture to construct a parameter-free URL from the container’s actual host, mapped port, and database name, and add a loggerLevel rejection case; do not relax the entry, discard unknown parameters, or change business implementation. Rerun paired CI after the fix; the failed first attempt is not a final passing result.

Local fix verification: M1DemoPackagingTest passed 4/4 and compileTestKotlin passed, exit code 0; log: backend/build/m1-demo-ci-fixture-fix.log. Scoped independent re-review APPROVE confirmed actual container endpoints and unchanged entry restrictions. Real HTTP integration awaits CI on the new commits.

## Next Execution Plan

Current result: Task 2 is implemented; local checks and independent review passed, with real HTTP/database verification awaiting CI. Git state: this record accompanies the paired implementation commits. Next action: execute Task 3 single-command entry and result output after Task 2 verification. Prerequisite: next-step execution instruction; complete execution requires an existing container environment. Acceptance target: one command executes actual synthetic scenarios, emits sanitized results, exits nonzero on failure, and retains data; this does not substitute for Owner acceptance.
