# Host Agent and Collector Engineering Verification

## Scope and Status

Task 6 follows the Owner instruction to execute the next step, using the accepted single-device Smoke design, TDR-024/025 and Task 5 machine contracts. Final independent review approved the engineering implementation; bilingual implementation commits are pushed and all four exact implementation CI runs succeeded. This record does not replace Owner acceptance.

Baseline Chinese 3fd2622d129ea70c08c24f4c81cd6e3dfcbbb484, English 2e9f6293cbe5ec632520db0c3438af76f7a256c3. The fixed Task 5 implementation Subjects and evidence remain in the [Event and result engineering record](attempt-result-verification.md) and are not replaced.

Final implementation Subjects: Chinese 84db30893d5c1f47c75128f9c89b25b828c833cb; English cd195191d49f744b4787e9b90b0322359f9dbc28. Initial implementations are 832d62f / cebae27, with fixes retained as separate commits. Remote HEADs match the final Subjects exactly. This engineering record uses subsequent separate paired documentation commits and does not replace implementation Subjects.

## Implementation and Technical Choices

The independent [Agent project](../../agent/README.md) contains a strict CLI, mTLS same-origin client, fixed ADB operations, execution journal, LOG/SCREENSHOT Plugins, shared JCS Result digest and recovery loop. Source, tests and instructions reside in agent/, without changes to Backend, migrations, Schema, Quality or Verified.

The project reuses the Backend Gradle 8.14.4 Wrapper, Kotlin catalog and Spring Boot BOM; the Agent does not depend on Spring runtime. All four Wrapper files match byte for byte; gradlew is 100755, and the JAR SHA-256 is 7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172. Schema/OpenAPI/golden JSON are imported directly as resources, without a second wire definition.

Key choices are recorded in [TDR-024](../v0.2/tdr/TDR-024-single-device-smoke-execution.md): existing aapt and direct JVM invocation of apksigner.jar; persistent action intent; identical saved Result replay to confirm a lost terminal receipt; and cross-process device locks under one controlled account and canonical serial. APK and PNG/log inputs have resource limits; insufficient capacity stops new work without adding quality thresholds or Company services.

Engineering rulings and the cost if wrong:

| Ruling | Reason | Cost If Wrong |
|---|---|---|
| Execute only Task 6; keep Task 7 real-device integration separate | Preserve staged implementation scope without premature device claims | Revise task boundaries and handoff records |
| Reuse existing Build Tools and direct JVM apksigner invocation | Reuse available tools while avoiding a batch shell | Revise tool invocation/output parsing |
| Replay an identical durable Result to confirm its old receipt | Follow accepted terminal idempotency without restoring new write authority | Redesign terminal receipt recovery |
| Require one controlled account and canonical serial for the device lock | Meet the single-host demonstration scope without claiming cross-account exclusion | Change locking/configuration before supporting multiple accounts or aliases |
| Explicitly defer nonblocking P3 diagnosis overwrite | It cannot create false PASS, replay actions or rewrite history | Add cause retention and a failure test first if the demonstration requires the primary cause |

## Tests and Reviews

The actual environment is Windows with Temurin 21.0.7+6. These Gradle commands run from the repository root, equivalent to entering agent/ and running its Wrapper:

- Initial full verification: agent/gradlew.bat -p agent clean test build --console=plain, exit 0, 41 tests / 0 failed / 0 errors / 0 skipped.
- Full verification after the first fix round: the same command, exit 0, 68 tests / 0 failed / 0 errors / 0 skipped.
- Full verification after the final fix: the same command, exit 0, 81 tests / 0 failed / 0 errors / 0 skipped; all 10 Gradle tasks executed, without compilation warnings.
- installDist exited 0; the generated CLI was run with an unknown argument and exited 1 with only CLI_INVALID, as expected for the negative check.
- The controller parsed each final XML set directly, confirming single-run counts without adding overlapping rounds. Compilation and Gradle check provide this task's checks; no separate Agent lint configuration exists.

| Suite | Tests After Fixes | Scope |
|---|---:|---|
| AdbExecutorTest | 7 | Real bounded child JVMs, dual streams, timeout, limits, invalid lease |
| AgentClientIntegrationTest | 3 | Real localhost HTTPS/mTLS, redirects, retries, total deadline |
| AgentConfigTest | 1 | CLI/configuration rejection |
| AgentLoopIntegrationTest | 3 | Terminal receipt replay and expired rejection |
| AgentRecoveryTest | 2 | Phase recovery rules |
| AndroidSmokeDeviceTest | 13 | Explicit device-port doubles, SDK 26 boundary and environment checks |
| ApkInspectionTest | 2 | APK tool output parsing |
| JournalTest | 6 | Persistence, corruption rejection, links/cross-process locks |
| PackagePreflightTest | 13 | Real child-process exits through actual preflight; first install/existing package/error rejection |
| ResultDigestContractTest | 2 | Shared canonical golden and digest |
| SmokeAssertionsTest | 5 | Current UI marker and XML/input boundaries |
| SmokeFlowTest | 24 | Complete flow and failure recovery using real HTTPS and explicit device doubles |

Initial behavioral RED evidence covers unimplemented Recovery, duplicate STARTED after ACKED restart/Windows junctions, and invalid receipts/fractional journal values/unknown Event responses. Gradle DSL, missing API and generated-source compilation errors are disclosed separately and are not behavioral RED. New Jackson fields() deprecation warnings were removed without suppressing warnings.

The first task review found three Important issues: missing post-execution environment checks, API >= 26 preflight, and ERROR/partial Evidence completion after permanent upload rejection. Focused repair first produced 16 failures among 21 tests, with 5 protective branches passing; all 21 then passed. Environment is checked after observation, collection and upload before freezing the Result; unsupported SDK is rejected before installation. A permanent rejection's stable Problem code is saved only after status/path validation, and a valid lease remains necessary to submit ERROR with confirmed IDs. A 503, unknown response, temporary I/O, permission denial or STALE_LEASE retains the spool without becoming a writable result. A fixture compilation error in direct device-wrapper tests is retained separately; the full build ran only after those tests passed.

The first scoped re-review closed all three Important findings without new issues. The final whole-slice review found F1: AOSP pm path returns 1 with empty output for an absent package, while the previous process exception discarded that meaning and blocked first installation on a clean device. The final fix keeps strict nonzero exceptions in the shared process layer; only the fixed existing-package query recognizes exit=1 with completely empty stdout/stderr. Success requires one path and no stderr; other failures remain rejected. The 13 combination regressions first produced 3 behavioral failures, followed by 33 passing related boundary tests; the final 81 tests come from one full build. APK inspection gains only a single-method port, retaining one production implementation. See the AOSP [package lookup implementation](https://android.googlesource.com/platform/frameworks/base/%2B/d18c61ae8e7ec024352e71e405a040b829376f50/services/core/java/com/android/server/pm/PackageManagerShellCommand.java).

The final scoped re-review approved engineering implementation at 84db308, closing F1 without new issues. Final review retains P3 / D1 (later Collector failures overwrite the primary diagnosis) as OPEN / NON-BLOCKING / EXPLICITLY DEFERRED. It reduces diagnostic quality without creating false PASS, replaying actions or rewriting history; it must not be claimed fixed or covered.

## Actual Local APK Check

The controller separately ran the built Agent ApkInspector/BoundedProcess with actual Build Tools 34.0.0 against the existing Task 1 APK, exiting 0: versionCode=1, APK SHA-256 282187c056abe33b6f2b7629896d32a27d6244318990d6ec909e7136a4377ea6, signing-certificate SHA-256 6a52389eda39ba559e04c54549ba325db01258ea0eb18fe0668c4589f6ec43c1. All match the original record. This verifies host tools and parsing, not installed APK readback or real-device behavior, and is not included in JUnit counts.

## Bilingual and Remote Checks

Baseline contracts passed with schemas=5, positive=13, negative=6, operations=36; acceptance records passed. The initial implementation Pair Gate passed. After the final fix, 451 non-Markdown blobs/modes match; final Pair Gate passed, and the exact implementation CI runs below all completed with SUCCESS.

| Language / Subject | M1 Backend | M2 Backend |
|---|---|---|
| Chinese / 84db308 | [34457197484](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34457197484) | [34457197531](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34457197531) |
| English / cd19519 | [34457197131](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34457197131) | [34457197140](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34457197140) |

Existing M1/M2 workflows do not execute Agent tests. Agent evidence is the local tests/build above; remote M1/M2 runs cover existing regressions and cannot replace Agent verification. The new M3 CI entry belongs to Task 7.

## Known Limitations and Retained Materials

- No real Android device operations were performed. The complete Smoke flow uses real local HTTPS and explicit device doubles. Actual ADB/installed APK readback, real Backend database integration and Task 7 failure exercises remain unverified.
- Device exclusion requires one controlled account and unique canonical serial, without covering multiple accounts or selector aliases. Windows file force/atomic replacement is not directory fsync or absolute persistence during power loss.
- Multiple Collector failures may overwrite the primary preflight reasonCode. This P3 diagnostic defect remains explicitly unfixed and does not change the contract requiring ERROR on collection failure.
- All spool files are retained; insufficient capacity stops work without automatic cleanup. Script/file behavior on other operating systems remains unverified.
- This task does not establish full Crash/ANR, device power-loss, M3, Release Gate or Company acceptance; Verified retains its existing semantics. Historical M2.5 P95 above the 1000 ms reference, canonical coverage limits for non-primary fields and the earliest Artifact expiry of 2026-10-07 remain.
- Raw per-round logs, XML, command sources and summaries reside in this task's ignored SDD Evidence directory; versioned records retain findings and fixed Subjects. Real Payloads, device serials, private keys and passwords are not committed to GitHub.

## Next Execution Plan

Current result: Task 6 implementation, 81 tests/build, independent review and exact implementation CI verification are complete; P3 remains explicitly deferred and nonblocking. Git status: the bilingual implementation Subjects above are pushed. This record uses separate paired documentation commits without replacing implementation Subjects; exact documentation CI is checked after committing.

Sole next action: Task 7 integration, CI and real-device verification. Prerequisites: instruction to implement Task 7; before real-device operations, explicit device/configuration selection, API >= 26, ADB authorization and the permitted test APK installation/launch scope. CI work can proceed independently; unavailable device verification is recorded UNKNOWN without selecting the first device automatically.

Acceptance target: distinguish CI_FIXTURE from REAL_DEVICE; verify normal and deterministic FAIL Run → Result → LOG/PNG associations and recomputed download SHA values, plus disconnection/Agent restart recovery within the permitted scope. Create a PENDING acceptance record bound to new fixed Subjects, without claiming full M3 or Owner acceptance.
