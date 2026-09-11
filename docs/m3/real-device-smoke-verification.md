# Single-Device Real Smoke Verification

## Scope and Fixed Subjects

On 2026-09-10, the Owner authorized the previously stated next step: normal and deterministic FAIL Smoke runs on one designated head unit. Before the first operation, checks confirmed ADB authorization, API 34 and boot/build/fingerprint matching configuration; operations were limited to the authorized test package. [TDR-024](../v0.2/tdr/TDR-024-single-device-smoke-execution.md), [TDR-025](../v0.2/tdr/TDR-025-local-demo-evidence-payload.md), the original wrapper and production Agent remain authoritative. No Result or Evidence was seeded and the frozen authority chain is unchanged.

Final product Subjects: ZH `9c9f97d9ebe5aadc64089530024912620eb2deb0`, EN `b5ed45d4cede1bb7f48f815da838febd647f1f80`. Both actual runs used the exact ZH Subject with a clean worktree. Atomic paired push and remote SHAs were verified; 467 committed non-Markdown blobs match. Seventy pre-existing local CRLF/LF differences do not change Git blobs/semantics and were not rewritten.

This record is separate from the product commits. New [M3-SMOKE-REAL-DEVICE-001](../governance/acceptance/records/2026-09-10-m3-smoke-real-device-001.md) is PENDING. Existing [M3-SMOKE-IMPLEMENTATION-OWNER-GATE-001](../governance/acceptance/records/2026-09-10-m3-smoke-implementation-owner-gate-001.md) retains its original Subjects `dbd59a48ba9c7dc9279588e046182dbf97ab22ef` / `bc1f62637ac9f9357912abf85961a65cb0035852` and PENDING status. Neither design approval nor rewritten historical checks substitute for a decision.

## Actual Results

Both runs are SYNTHETIC_DEMO / REAL_DEVICE, Run COMPLETED, original AGENT Result, wrapper exit 0, generationStatus SUCCEEDED and scenarioOutcome PASS. The second successful scenario preserves its original Case FAIL. Both retain releaseQuality NOT_EVALUATED and verified=false.

| Mode / Plan and Case version | Run | Attempt | Case | Result digest |
|---|---|---|---|---|
| normal / v1 | `run_01a08b64329e7dd28165bd706c00fb18` | `01a08b64-32a0-7bc4-a9d5-d6bd47161898` | PASS | `sha256:61ec5d4f832a2b5664361a21854b68350ab83ded2a25b6144e3164956129862e` |
| assertion-failure / v2 | `run_01a08b65dfdd71f388e0536be8b6e330` | `01a08b65-dfdf-7162-bfde-5dc96bf2f059` | FAIL | `sha256:862950c8b59ae9c898432252bbd4e2e778cc53cda7375cddf098f0e4d194a249` |

All four Evidence files were downloaded through formal APIs and independently checked against actual byte sizes/SHA-256; read-only database checks confirmed AVAILABLE. Each SHA-256 below matches both payloadChecksum and downloadSha256.

| Mode | Type | Evidence ID | bytes | SHA-256 |
|---|---|---|---|---|
| normal | LOG | `ev_01a08b64562e728c8a1181c66bf4f5c9` | 192 | `7dec0d76f6ee4acf165939f57daa6c6dd9115755998ba141618b71235eb3b436` |
| normal | SCREENSHOT | `ev_01a08b6457487df1ac90f59ffb32a620` | 50541 | `00f3fdc2fae93b38b371f66d4c84567a25d02a2a61d03df9c300ec20a1e191bd` |
| assertion-failure | LOG | `ev_01a08b66027772dda3eea8e73286246c` | 191 | `48584b62e7a51d1c883529b5aaadfb2031adb7bf06e27e505c83e17b8ff17d08` |
| assertion-failure | SCREENSHOT | `ev_01a08b66031a7eb8974e2d8d6b8662a9` | 50783 | `e430d4d1f699bc8176614164f0e1bb894e62c378905b5a0fe586d8224fd23170` |

| Summary locator | Original summary.json SHA-256 |
|---|---|
| `output-normal-retry-3/summary.json` | `924995d9b24853f5423d804c94e38689f8419953f5c43b69057d763c0a17ed65` |
| `output-fail/summary.json` | `583124fcc7e822f2ba091259dafa7d1304e0911b5328567233b83c15070250df` |

Both APK SHA-256 values are `282187c056abe33b6f2b7629896d32a27d6244318990d6ec909e7136a4377ea6`; both CONFIG SHA-256 values are `6b3772119d8e2bec6133a07141e0004f040c1a76b1e0e53da1c0c76b4bd137a6`. Fixed summaries retain Manifest/Release associations. The test APK does not represent a complete vehicle Release.

## Historical Failures and Fixes

Original reports, Runs, Results, spools and bytes remain preserved. Locators below are relative to the controlled runtime collection; later success does not overwrite earlier failure.

| Output directory | ZH Subject at execution | Run | Attempt | summary.json SHA-256 |
|---|---|---|---|---|
| `output-normal` | `b67c1fe475d8403abe6079bdd8361411be348568` | `run_01a08b3a93237ef382ab4d135f4965ad` | `01a08b3a-9324-7770-98ba-f421d71379ce` | `ed7b0e8d231481e585e14838eac060d81dd51c42997a76ff3bac83082e5ca05b` |
| `output-normal-retry-1` | `97e8a7ac1c179c86a4b1f113052ba460219a8f3f` | `run_01a08b43c58172328a8960f60a6377c8` | `01a08b43-c583-7369-9635-5b5107fe96a5` | `724e6f613a7e74e1c3cfe320f3543774fe4a4b931dfff826b57f9850d2a17e65` |
| `output-normal-retry-2` | `07994a20f111dc1250d0e11bc4a0d5103e16367e` | `run_01a08b5079d87c7a826a8117f18c9f5b` | `01a08b50-79d9-76d8-ab2d-6f58e3553eb8` | `76de20a8225476081d84d8c10c84c9e71c8ee88d837edba12c5789477b7ecca6` |

1. Initial Windows `libs.resolve("*")` raised InvalidPathException before Agent launch; the report recorded SCENARIO_FAILED. After coordinator cancellation, DB showed Run/Attempt CANCELLED, a SERVER/BLOCKED Result and zero Evidence. Missing Result references in the summary do not mean no server Result existed. JVM classpath string fix: ZH `97e8a7ac1c179c86a4b1f113052ba460219a8f3f` / EN `9bda7b52d3ff801347bc592f6d43ac88b342e024`.
2. retry-1 was COMPLETED / original ERROR, reason SCREENSHOT_FOREGROUND_REQUIRED, LOG only and journal RESULT_ACKED; the summary reported EVIDENCE_COUNT_INVALID. Actual foreground formats `topResumedActivity=` / `ResumedActivity:` were unrecognized. Exact observed formats were added while retaining component/user/task boundaries: ZH `07994a20f111dc1250d0e11bc4a0d5103e16367e` / EN `7413d1a6db6bfdf5fcf2832ff0fdf7d45d43e754`.
3. retry-2 was COMPLETED / original ERROR, reason PNG_INVALID, LOG only and journal RESULT_ACKED; the summary reported EVIDENCE_COUNT_INVALID. Device screencap stdout contained a 347-byte diagnostic prefix. The sole screenshot path became current UUID temporary PNG → binary cat → exact cleanup, retaining PNG validation rather than prefix scanning/stripping or fallback: ZH `aa54839ff44522934d8867f0eda35a7162756c90` / EN `1655c09420dfe64b4f7392ed3b953b2f9baa5c5d`. Probe PNGs were diagnostics, not formal Evidence.
4. Independent quality review found Important I1 in that screenshot change: early SDK failure could leave a missing or previous Attempt binding. No real retry proceeded until the final Subjects fixed the sole binding entry, clearing old state and strictly binding current Context before device commands, with explicit AgentFailure when unbound. Scoped re-review APPROVED, I1 CLOSED, no new Critical/Important/Minor. The original NEEDS FIXES report remains preserved; P3 primary-cause coverage remains deferred.

## Separate Test Rounds and Independent Method

Counts below belong to separate commands and must not be added together. Every GREEN has zero failures/errors/skips. Full commands and XML/HTML remain under ignored SDD directory `.superpowers/sdd/2026-09-09-single-device-smoke-implementation/`.

| Fix round / subdirectory | RED | Targeted GREEN | Full or paired GREEN |
|---|---|---|---|
| Windows / windows-launch-fix | 1 / 1 failure | Backend M3 19 | ZH 19, EN 19; actual JVM loads two JARs from a directory containing spaces |
| Foreground / foreground-format-fix | 9 / 3 failures | Agent 9 | ZH 87, EN 87; test/build/installDist |
| PNG / screenshot-stream-fix | 15 / 7 failures | Agent 15 | ZH 95, EN 95; test/build/installDist |
| I1 / screenshot-stream-fix/binding-fix | 11 / 5 failures | Agent 24 | ZH 100, EN 100; test/build/installDist |

Controller's independent VerifyRealEvidence passed both runs: exact Subject/Case/Run checks; JCS recomputation of the retained Result request against summary, receipt and durable journal; matching receipt binding fields and current Attempt RESULT_ACKED; actual LOG/PNG size/SHA-256 recomputation and PNG decoding. Final read-only DB checks confirmed AGENT PASS/FAIL and four AVAILABLE records without writing or correcting results.

Controlled locator collection: `runtime-20260910-85ebd6c0`, accessed by the local Runtime Owner / Controller; root and access controls are documented in the [local runtime record](local-runtime-verification.md). Supporting files are `output-normal-retry-3/summary.json`, `output-fail/summary.json`, `logs/normal-independent-verification.txt`, `logs/fail-independent-verification.txt`, `logs/failed-attempts-index.json`, `logs/final-real-runs-db.txt` and `logs/final-runtime-stopped.txt`. Raw device configuration, credentials, logs and screenshots are not committed; the access owner provides controlled original-byte review when needed.

Owned PostgreSQL and Backend are stopped with no listeners on 55432/58443. All data, spools, APK and historical outputs remain retained. No uninstall, data clearing, device reboot, disconnection/Agent restart injection or database/Payload restoration occurred.

## Exact CI and Residual Limits

Both exact final paired-Subject M3 CI runs are SUCCESS. Controller independently downloaded selected Artifacts and checked ZIP size/SHA-256, CRC/paths, APK bytes/summary, three distinct CI_FIXTURE Runs preserving original PASS/FAIL/FAIL and each LOG/PNG digest, JUnit and lint. Each contains 128 tests, 0 failures/errors and 1 skipped test: Linux skips the Windows junction test, which ran in the local Agent 100/0/0/0 suites. Each lint report retains 3 existing warnings.

| Branch | M3 CI Run | Artifact | ZIP bytes | ZIP SHA-256 | expiresAt |
|---|---|---|---|---|---|
| ZH | [34479549893](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549893) | [10153212746](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153212746) | 28842 | `352d5f5ac6f8011e288ad47758757e3934ecefdfdf0d9e2bc975b348f8dafa5d` | 2026-10-10T13:02:19Z |
| EN | [34479549735](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549735) | [10153193975](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153193975) | 28789 | `b287cff01bdd00bd1309ae83157ea32ee87e20a995385870b944ce05a4d2530a` | 2026-10-10T13:01:50Z |

Controlled verification summaries/metadata are under the same runtime collection: `ci-real-subject/m3-zh.summary.json` / `m3-en.summary.json` and corresponding `.metadata.json`. CI APKs are each 7645 bytes (ZH SHA-256 `497a6dac984766038d6327d5ed4ed19178bafafb27d5ea6e4a9bf3b46beecad8`; EN `44a9dd88229c0b5d3efeb29573de950594ea5cc32c57c3a93b2d166e9567ba4a`), recorded separately from the 7677-byte real-device APK. CI_FIXTURE is never REAL_DEVICE.

Both exact M1 CI runs are completed/success. Controller independently verified the selected m1-evidence Artifacts' ZIP/digests, exact Subjects and bytes/hashes of 135 retainedReports in each. Each contains 1138 tests, 0 failures/errors and 3 skipped Windows ACL tests on Linux; this turn does not claim those three ran on Windows. Two listed JARs (`vsrqg-backend-0.1.0-SNAPSHOT-plain.jar` and `vsrqg-backend-0.1.0-SNAPSHOT.jar`) are outside the Artifact and were not independently rehashed, so they are excluded from verified bytes. Controlled materials are `ci-real-subject/m1zh.metadata.json` / `m1zh.summary.json` and corresponding `m1en` files.

| Branch | M1 CI Run | Artifact | ZIP bytes | ZIP SHA-256 | expiresAt |
|---|---|---|---|---|---|
| ZH | [34479550025](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479550025) | [10153442303](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153442303) | 275050 | `a31f1473bdaea741726234998eec9f7efeee8cf7c9f11cb2909cc4bee63db336` | 2026-10-10T13:07:54Z |
| EN | [34479549737](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549737) | [10153435188](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153435188) | 275353 | `7ff449f939b2155c55153f4bd2fed423f44ad99150b486d85b3e8f9245fe4e79` | 2026-10-10T13:07:44Z |

All six exact final paired-Subject M1/M2/M3 CI runs and six selected Artifacts passed independent verification. Their scopes, skipped tests and materials outside Artifacts are recorded separately; this is not verification of all deliverables or Company acceptance.


Both exact M2 CI runs and selected Artifacts independently PASS: ZIP/sidecar, exact Subjects, performance files and recovery/replayDigest consistency, each with 12/12 checks PASS (not a sum of their tests fields). Controlled materials are `ci-real-subject/m2zh.metadata.json` / `m2zh.summary.json` and corresponding `m2en` files. Current start P95 is ZH 1273 ms / EN 1433 ms, still above the 1000 ms reference target and meeting only the shared CI 30000 ms hard limit, not Company performance acceptance. M2 restoration does not prove M3 database+Payload restoration.

| Branch | M2 CI Run | Artifact | ZIP bytes | ZIP SHA-256 | expiresAt |
|---|---|---|---|---|---|
| ZH | [34479549785](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549785) | [10153328623](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153328623) | 1755 | `10e35f42085d27baf221e3ef8b1f9c0ed5138bb31529af19416bb8c472587df4` | 2026-10-10T13:05:10Z |
| EN | [34479549739](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549739) | [10153339613](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153339613) | 1758 | `45e024ca457125962df783bc9816ef0697506a05f4bca51eee906427e6bf4922` | 2026-10-10T13:05:26Z |

Real connection interruption, Agent restart and paired database+Payload restoration remain UNKNOWN; ordinary database stop/start does not prove fault recovery. Full Crash/ANR, power loss, M3, vehicle Release Quality Gate, Company and Owner acceptance remain outside delivery. Single-account/canonical-serial locking, Windows durability and retained-spool limits remain in the [host record](host-agent-verification.md).

Existing P3 is OPEN/NON-BLOCKING/EXPLICITLY DEFERRED. APK OldTargetApi, MissingApplicationIcon, SetTextI18n and JDK21/Java8 source-target warnings remain. Historical M2.5 Run P95 ZH 1340 ms / EN 1650 ms missed the 1000 ms reference target; canonical coverage outside primary paths remains limited; earliest historical Artifact expiry 2026-10-07 remains a constraint. Current CI expiry must be checked separately, never inferred from historical dates.

## Sole Next Action

Current result: normal/deterministic FAIL real-device chains and independent bytes/receipt checks are complete, processes stopped and Owner status PENDING. Git status: this record is separate documentation closure, identified by its eventual Git history; fixed product Subjects are listed above.

The results and unexecuted checks above are historical as of 2026-09-10. Task 7 Step 5 evidence is now recorded in [recovery verification](smoke-recovery-verification.md), including retained harness failures and independent supplements. Current sole next action: Task 7 Step 6 independent engineering review and candidate evidence-package reconciliation. Prerequisites: the fixed Subjects and controlled evidence remain accessible. Acceptance target: a review report with findings disposition and exact bilingual CI/Artifact/real-device evidence mapping; Owner decisions remain separate and PENDING.
