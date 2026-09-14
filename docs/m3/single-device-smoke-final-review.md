# Single-device Smoke final review

## Scope and source

This record consolidates the whole Tasks 1–7 engineering review and candidate evidence reconciliation for Task 7 Step 6. The candidate is [M3-SMOKE-FINAL-OWNER-GATE-001](../governance/acceptance/records/2026-09-14-m3-smoke-final-owner-gate-001.md), with Owner status `APPROVE`. Earlier design approval and historical records do not replace this acceptance; the frozen authority chain, `NOT_EVALUATED` and `verified=false` remain unchanged.

| Source level | ZH | EN |
|---|---|---|
| Whole-review design baseline | `0856fc38cad004c153c9a9c3f8ddd115f9be4af6` | Same design scope, checked against the paired product |
| Fixed product Subject | `9c9f97d9ebe5aadc64089530024912620eb2deb0` | `b5ed45d4cede1bb7f48f815da838febd647f1f80` |
| Actual recovery checkout | `9a569258c3d22bdbe50b7286bdcb8740ae02541d` | `5ac9a2047d8d0ea7bf5ddfcbb75c1822378eaa0d` |
| Record HEAD at this review's start | `d5ad3917b8c3855876c9e0e33fb160d521c99778` | `3cf5ed6cf0a636effb39a2c29d3ad6de74fbcf5f` |

The recovery checkouts and record HEADs differ from the products only in Markdown; the product pair has `467` identical non-Markdown blobs/modes. Git history identifies the commit carrying this record; it is not presented as the product or recovery execution HEAD.

## Tasks 1–7 coverage matrix

| Task | Review scope | Historical engineering and acceptance locator |
|---|---|---|
| 1 | Minimal APK, inputs and build identity | [APK verification](minimal-apk-build-verification.md) |
| 2 | Agent identity, registration and machine contract | [Identity verification](agent-identity-registration-verification.md) |
| 3 | Run/Attempt, scheduling, leases and fixed Context | [Lease verification](run-lease-verification.md) |
| 4 | Bounded Payload, authorization, Complete, download and recovery | [Evidence verification](local-evidence-verification.md) |
| 5 | Event, Result digest, idempotency, terminal state and transactions | [Result verification](attempt-result-verification.md) |
| 6 | Host Agent, ADB, LOG/SCREENSHOT, spool and ACK | [Host verification](host-agent-verification.md) |
| 7 | Formal API integration, CI, real device, recovery and final candidate | [Integration](single-device-smoke-verification.md), [runtime prerequisites](local-runtime-verification.md), [real device](real-device-smoke-verification.md), [recovery](smoke-recovery-verification.md) |

These links retain each round's Subject, acceptance ID, failures, test counts and scope; separate executions are not added into a new test run. The whole review covers the complete change from the design baseline through the fixed product, not just the final screenshot repair.

## Exact CI and Artifact

The following are product execution evidence from `2026-09-10`. Read-only reconciliation on `2026-09-14` confirmed all six runs as `completed/success`, with live metadata size/digest matching the existing record and `expired=false`. This does not claim new tests, new real-device execution or a fresh download. Exact ZIP bytes/SHA-256/expiry tables are in the [real-device record](real-device-smoke-verification.md).

| Subject | Workflow | CI Run | Selected Artifact |
|---|---|---|---|
| ZH product | M1 | [34479550025](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479550025) | [10153442303](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153442303) |
| ZH product | M2 | [34479549785](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549785) | [10153328623](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153328623) |
| ZH product | M3 | [34479549893](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549893) | [10153212746](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153212746) |
| EN product | M1 | [34479549737](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549737) | [10153435188](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153435188) |
| EN product | M2 | [34479549739](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549739) | [10153339613](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153339613) |
| EN product | M3 | [34479549735](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34479549735) | [10153193975](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/10153193975) |

The six CI runs for the previous record HEADs are also `completed/success`: ZH M1/M2/M3 are `34582569454` / `34582569526` / `34582569457`; EN are `34582569515` / `34582569499` / `34582569489`. They bind to the record HEADs above, not this new record, and do not replace product Artifacts.

Each product M1 has `1138` tests, `0` failures/errors, `3` Windows ACL skips and `135` retainedReports; the two named JARs are outside the ZIP and were not independently checked. Each M2 has `12/12` checks PASS. Each M3 has `128` tests, `0` failures/errors, `1` Windows junction skip and `3` retained lint warnings; that junction test executed in the historical local Agent `100/0/0/0`, not a new Windows test run. The CI_FIXTURE raw PASS/FAIL/FAIL is separate from REAL_DEVICE, and the `7645`-byte CI APK is separate from the `7677`-byte real-device APK.

## Real-device and recovery evidence

The normal and deterministic FAIL real-device Runs on `2026-09-10` were both `COMPLETED`, origin `AGENT`, wrapper exit `0`, scenario `PASS`, with Case `PASS` / `FAIL` respectively. The [real-device record](real-device-smoke-verification.md) contains four LOG/PNG IDs, actual sizes/digests, the original three failures and binding repair I1 CLOSED evidence.

The `2026-09-11` `STOPPED_POSTGRESQL_COLD_COPY` contains `1623` files / `71475501` bytes and was restored into an isolated copy; reads and downloaded-byte checks for the original `2` Runs / `4` Evidence passed before and after the exercises. Same-Run A reached `RESULT_ACKED` after restart, with install/launch each `0` and no duplicate Result/audit/outbox. B ended `TIMEOUT/TIMEOUT/SERVER`, `RECOVERY_DEADLINE_EXCEEDED` after Backend stoppage; late replay returned `409:LATE_EVENT_CONFLICT`, Agent exit `1`, no ACK, while the supplement tool returned exit `0` / `PASS`. B's uploaded LOG/PNG can be checked but are outside its SERVER Result's empty Evidence reference set.

Original A/B wrapper `FAIL` remains intact. Same-Run supplement `PASS` does not rewrite A's `generationStatus=FAILED` / `scenarioOutcome=FAILED` or B's original failure. The first A attempt's root cause remains `UNKNOWN`. Exact Run/Attempt, timing, digests and cleanup evidence are in the [recovery record](smoke-recovery-verification.md). This review only reads retained materials and starts no device, Agent, Backend or database service.

## Current independent conclusions and locators

Whole engineering review: `APPROVE_FINAL`, confirmed by Controller after reading the independent report. No new Critical, Important or blocking finding; existing P3 remains deferred. This is an engineering disposition, not an Owner decision; completion of the candidate checks remains separate.

Current independent retained-ZIP and real-Payload recomputation: `PASS`. All six ZIP sizes/SHA-256, CRC/paths, internal reports and CI Payload checks passed; offline normal/deterministic FAIL Result JCS/receipt/journal and four Payload checks passed. Recovery reports, A's six immutable files, retained A/B Payload and each source/backup inventory of `1623` files / `71475501` bytes matched. No recovery or late-write exercise was rerun; A's JCS/runtime behavior is supported by the unchanged historical supplement hashes. CI ZIPs contain Result references only, so no complete CI Result JCS recomputation is claimed.

Runtime Owner / Controller manages the private materials. This review uses `runtime-20260910-85ebd6c0/final-review-20260914/`: `live-ci.json`, `live-artifact-metadata.json` and `source-binding.json`. The engineering report is in the ignored locator `.superpowers/sdd/2026-09-09-single-device-smoke-implementation/final-review-20260914/whole-implementation-review.md`; independent byte reconciliation is `evidence-reconciliation.md` in the same directory. Reports, ZIPs, databases, credentials, serial numbers and raw Payload are not committed. Fixed retention and future availability are `UNKNOWN`.

| Report | Generated At | SHA-256 |
|---|---|---|
| `whole-implementation-review.md` | `2026-09-14T01:39:40Z` | `5e0015cf83cd34132612745c70af849cfb7ac79647a4adb5aa524567e9eb5f3a` |
| `evidence-reconciliation.md` | `2026-09-14T01:43:03Z` | `87bf97d900af883065ca917cc5ad78db6bd69fea541b6ef6ab108b424ef56e23` |

## Residual risks and decision boundary

P3 first-cause diagnostic coverage remains `OPEN/NON-BLOCKING/EXPLICITLY DEFERRED`. Physical USB/ADB disconnection, complete Crash/ANR and power-loss recovery remain `UNKNOWN`; Backend connection interruption does not replace physical disconnection, and cold copy does not prove online or cross-PostgreSQL-version recovery. Single-account/canonical-serial locking, Windows durability and spool capacity limits remain; see the [runtime record](local-runtime-verification.md) for the local PostgreSQL Windows ZIP's `NotSigned` and local-digest verification limits.

M2 start P95 is ZH `1273 ms` / EN `1433 ms`; historical `1340 ms` / `1650 ms` also missed the `1000 ms` reference, satisfying only the shared-CI `30000 ms` hard limit. Canonical coverage of non-primary fields is limited. The current six product Artifacts first expire at `2026-10-10T13:01:50Z`; the historical earliest `2026-10-07` constraint remains. Expired or inaccessible evidence becomes `UNKNOWN`. Existing APK/JDK warnings remain.

The Owner approved this fixed candidate and its residual risks; see the acceptance record and immutable receipt above. Approval does not establish full M3, vehicle Release Quality Gate or Company acceptance and does not authorize merge, Tag, release or deploy. Prior acceptance history remains unchanged.

## Next execution plan

Current result: startup prerequisites, normal/FAIL ordering and evidence locators have been added to the [runbook](single-device-smoke-runbook.md) and reconciled read-only against the existing scripts and report structure; no new device verification conclusion was produced. Git status: these documentation changes are versioned separately and identified through Git history. Next action: present historical evidence once using the runbook. Prerequisites: a next execution instruction and access to controlled outputs. Acceptance target: locate normal and deterministic FAIL outcomes, Run/Attempt associations and both Evidence types, with download hashes matching summaries and presentation explicitly labeled historical; record missing content as UNKNOWN.
