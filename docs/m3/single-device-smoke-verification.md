# Single-Device Smoke Integration Engineering Verification

## Scope and Current Status

Task 7 integration engineering, independent review, exact bilingual CI and Artifact verification are complete. Real-device normal/expected FAIL, disconnection/Agent restart and local database/Payload recovery were not executed; Task 7 as a whole is not marked complete. New acceptance record [M3-SMOKE-IMPLEMENTATION-OWNER-GATE-001](../governance/acceptance/records/2026-09-10-m3-smoke-implementation-owner-gate-001.md) is PENDING and does not act for the Owner.

Final implementation Subjects: Chinese dbd59a48ba9c7dc9279588e046182dbf97ab22ef; English bc1f62637ac9f9357912abf85961a65cb0035852. Implementation baselines are Chinese 47c29e96a864d64d15620046e96dfb65bc1cf753 and English a387bcf436327cd7a4ec9b26d9e80ec96e582488. Commits carrying this record remain separate from implementation Subjects; Git history identifies record versions.

## Implementation and Verification

The demo entry creates a Release, registers/validates/Locks an APK+CONFIG Manifest and creates a Run through formal APIs. The formal AgentLoop submits Result and Evidence; the coordinator downloads LOG/PNG, recomputes SHA-256 and waits for the current target's durable ACK and natural zero exit. START reuses only exactly matching synthetic definitions; EXISTING never initializes or stops an existing service. See the [runbook](single-device-smoke-runbook.md) and [TDR-024](../v0.2/tdr/TDR-024-single-device-smoke-execution.md). There are no direct Result, AVAILABLE or Verified writes; source Case FAIL and scenario PASS remain distinct.

Local rounds are recorded separately: the initial 20 Backend, 81 Agent, 1 APK and 14 wrapper checks passed. The fix round's 18 Backend and 32 targeted Agent tests and related builds passed, all with 0 failures/errors/skips. The local APK used an incremental JDK 21 build; CI uses JDK 17. Rounds are not accumulated as one execution, and not every incremental task is claimed to have rerun.

Local explicit m3IntegrationTest executed 2 tests; both failed during PostgreSQL startup because Docker was unavailable, without skips or completed integration. Those failures remain; later CI success does not rewrite the local result. Pester/missing-class compilation errors are tool or compilation failures, not behavioral RED. Unknown configuration, overlapping credential paths and diagnostic-overflow cleanup have separate valid RED→GREEN evidence.

Three Important task-review findings are closed: writes through derived artifacts links, the race between Server terminal state and durable Agent confirmation, and invalid database configuration classification. Real junction regression confirms no new external files or output. Child-JVM regression withholds the server Result response, proving old-journal replay cannot complete a new target and natural exit occurs only after validating/persisting its receipt. First-round scoped review and final engineering review passed; Task 6 P3 diagnostic limitations remain explicitly deferred.

Initial M3 CI (Chinese 34467757847, English 34467757917) failed at Linux wrapper checks: Get-Command returned multiple pwsh paths combined into one command. A subsequent reproduction showed 14 passing assertions but Exit=1 inherited from an expected negative case. The final fix only selects one path and explicitly succeeds after all assertions/cleanup, without weakening assertions. Actual three-PATH regression produced 14 PASS/Exit=0. The single final CI fix-wave scoped review passed with no new Critical/Important findings. The four APK files uploaded by the initial CI are not treated as integration Evidence.

## Exact-Commit CI and Artifacts

All six runs below bind the final implementation Subjects above and completed SUCCESS. Full Pair Gate and parity for 465 non-Markdown blobs/modes passed; atomic push and exact remote HEADs were verified.

| Branch | Workflow | Run | Status |
|---|---|---|---|
| ZH | M1 | [34468641193](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641193) | SUCCESS |
| ZH | M2 | [34468641319](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641319) | SUCCESS |
| ZH | M3 | [34468641285](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641285) | SUCCESS |
| EN | M1 | [34468641281](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641281) | SUCCESS |
| EN | M2 | [34468641201](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641201) | SUCCESS |
| EN | M3 | [34468641234](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641234) | SUCCESS |

The following six selected Artifacts were downloaded and independently checked for metadata size/SHA-256, non-expiry, ZIP CRC/paths, exact Subject and internal reports. Other Artifacts from the same workflows were not automatically counted as verified.

| Branch/type | Artifact | ZIP bytes | ZIP SHA-256 | Expiry UTC |
|---|---|---|---|---|
| ZH M1 | [10148963678](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641193/artifacts/10148963678) | 274748 | 0e29da074d45e66d2dc818734c309864b52f7e71741308a793e7cf72cafead7a | 2026-10-10T11:07:44Z |
| ZH M2 | [10148854955](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641319/artifacts/10148854955) | 1753 | 13f0bf166d3204b3166e02bb0481f2ce74a9324cd065829a5c18b5b447955a4b | 2026-10-10T11:04:30Z |
| ZH M3 | [10148776017](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641285/artifacts/10148776017) | 27847 | bad87adf9791a755bbf491df7993eaf4d6ab65fa68bfc9f54dd6e7d0176b781f | 2026-10-10T11:02:12Z |
| EN M1 | [10148982391](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641281/artifacts/10148982391) | 274538 | 73f2a65bfdf07a835f6339e81a1a6824a74575f5e662d41184e7b7e944ea4419 | 2026-10-10T11:08:15Z |
| EN M2 | [10148934960](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641201/artifacts/10148934960) | 1750 | 4373979fc5078f8fad446e55eeea38247d01704a2f2d66d6592a255d98b9c771 | 2026-10-10T11:06:53Z |
| EN M3 | [10148783792](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34468641234/artifacts/10148783792) | 27851 | 8099a0e48ef8742560b47e99c4fd7dd1b3aa35153fcfaf71fb60fd66397cef1f | 2026-10-10T11:02:26Z |

Each M3 Artifact contains XML for 1 APK, 83 Agent, 25 Backend and 2 PostgreSQL integration tests: 111 tests, 0 failures/errors and 1 skip. The sole skip is the Windows junction configuration/output-path check, which is inapplicable on Linux. Both integration tests passed without skips. The wrapper has 14 additional actual Linux checks. Lint retains three existing Warnings without Error/Fatal findings.

Each M3 Artifact contains one invocation and three distinct Runs: normal source Case PASS, existing-service v2 source Case FAIL, and repeated START v2 source Case FAIL. All three retain generation SUCCEEDED, scenario PASS and Run COMPLETED. Each Run's two Evidence IDs, sizes and payloadChecksums match actual download SHA-256 values; all six LOG/PNG payloads were recomputed from their bytes. Results are referenced by Attempt ID and formal resultDigest, with stable historical queries. Artifact summaries contain Result digest references only; they do not establish offline recomputation of full Result JCS. All scenarios remain CI_FIXTURE, NOT_EVALUATED and verified=false, explicitly excluding actual ADB/UI, disconnection, Agent restart and backup recovery.

Both downloaded CI APKs are 7645 bytes. Independent aapt/apksigner checks confirm package com.ricezhou.vsrqg.smoke, versionCode 1, minSdk 26, targetSdk 35 and their corresponding signing digests. Chinese APK SHA-256 is e46f91f80c1f03c1c975a7606e577208fc41b7d51d874c4a36fe23a3270bb5f5; English is d629116d60f431430e1774dae7f4547c6144977f3e63d9b7c2676f61de6afc6d. The CI invocations use different ephemeral debug signers. Neither is the private 7677-byte local JDK 21 APK; real-device execution must fix the identity and digest of the actual file used.

Each M1 Evidence is CANDIDATE with 8/8 gates, 1137 tests, 0 failures/errors and 3 Windows ACL skips inapplicable on Linux. It includes all 25 current Backend checks passing. Bytes/hashes of 134 retained reports were verified; two build JARs are outside that ZIP and their bytes were not independently verified. Both M2.5 Evidence files are 12/12 PASS with 20 Issues/2000 Edges, historical replay and backupRestore/dbRestartReclaim/deadLetter/manualRetry passing. These existing regressions do not prove M3 recovery.

## Real Device and Runtime Prerequisites

The Owner specified an Android head unit through ADB and allowed local configuration on drive D. Read-only preflight on 2026-09-10 found exactly one authorized device with API 34. Boot/session, build and fingerprint are stored outside the repository in an account-controlled directory; raw values and the serial are not committed. Device configuration and a SHA-256-verified local demo APK copy are prepared, but database/TLS runtime configuration is not. Preflight proves only the connection at that time and must be rechecked before execution.

The Owner explicitly confirmed no local database; no Docker, Podman or PostgreSQL tools, services or processes were found. This turn installed no database environment or APK, launched no APK, captured no real logs/screenshots, and performed no real Run→Result→Evidence, disconnection/Agent restart or database/Payload recovery. These real-device and local recovery checks are UNKNOWN and are not marked complete because CI passed.

## Engineering Rulings and Costs

| Ruling | Reason | Cost If Wrong |
|---|---|---|
| Continue independent engineering while retaining UNKNOWN real-device delivery | The accepted plan permits CI progress with missing runtime prerequisites | Revise real-device delivery scheduling and handoff |
| Use a strict origin/lifecycle server object and file references for identity/device details | Preserve top-level fields while distinguishing owned/existing services | Migrate configuration, runbook and rejection tests together |
| START owns only its local demo process; EXISTING never initializes or stops existing services | Bound data and process operations | Revise lifecycle integration and configuration compatibility |
| Reuse native PowerShell assertions from existing M1 tests | Available Pester cannot run the plan example; avoid another compatibility layer | Migrate test entry and plan examples if a framework is required |
| Isolate M3 integration in its own test task, keeping default Backend tests independent | Existing M1 has no implicit Agent/APK/SDK dependency; M3 explicitly checks inputs | Revise Gradle source set/classpath and workflow wiring |
| START reuses only exactly matching synthetic identities and published definitions | One controlled identity supports normal/failure and repeat demos without changing history | Revise bootstrap matching/transactions and consecutive-start regression |
| Agent may wait for the current Attempt's durable receipt before natural exit | Remove the shutdown race between Server terminal state and host ACK using the existing completion path | Coordinate changes to CLI, completion loop, coordinator lifecycle and runbook |

## Retained Limitations

Task 6 P3 primary-diagnosis overwrite remains OPEN/NON-BLOCKING/EXPLICITLY DEFERRED: later Collector failure can overwrite the primary cause, but the result is forced to ERROR and cannot count as scenario success. Single-account/canonical-serial device locking, Windows persistence and spool-retention limits remain in the [host record](host-agent-verification.md). Existing APK OldTargetApi, MissingApplicationIcon and SetTextI18n warnings, plus local JDK 21 Java 8 source/target warnings, remain without changing the fixed SDK.

Current M2.5 Run creation P95 is 1340 ms in Chinese and 1650 ms in English, above the 1000 ms reference target and only within the shared CI 30000 ms hard limit. This does not establish Company performance. Existing canonical digests do not cover every non-primary field, so arbitrary tampering detection is not claimed. These six selected Artifacts expire earliest at 2026-10-10T11:02:12Z. Historical implementation Artifacts expiring as early as 2026-10-07 remain subject to existing Evidence Archive governance. Expired or inaccessible evidence makes the relevant checks UNKNOWN.

This turn does not establish full Crash/ANR, device power loss, M3, Release Quality Gate or Company acceptance, and does not change Verified. No merge, Tag, release, deployment or real Provider was performed. Raw round logs/XML, verification scripts and downloaded copies remain in this plan's ignored SDD directory; versioned records retain fixed locators/digests without committing real Payloads, serials or credentials.

## Next Execution Plan

This record retains historical engineering evidence for the fixed implementation Subjects. The Owner subsequently authorized and completed local runtime preparation; current environment results and the sole next action are in [local runtime verification](local-runtime-verification.md). The original absence of a database and unexecuted real-device/recovery checks are not rewritten; the Owner Gate remains PENDING.
