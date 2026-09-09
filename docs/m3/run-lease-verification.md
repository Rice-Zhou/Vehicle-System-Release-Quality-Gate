# Run and Lease — Task 3 Engineering Verification

## Scope and Implementation Instruction

On 2026-09-09, after Task 3 was identified as the next action, the Owner supplied the exact text below, interpreted in context as “execute the next step.” This slice covers Run, Attempt, scheduling and leases only; Tasks 4–7, device operations and Company are out of scope. This engineering record does not replace Owner acceptance, introduce another component gate, or authorize merge, Tag, release or deployment.

```json
{"instruction":"\u5fd7\u5174\u4e0b\u4e00\u6b65"}
```

Basis: [implementation plan](../superpowers/plans/2026-09-09-single-device-smoke-implementation.md), [design](../superpowers/specs/2026-09-09-single-device-smoke-design.md), [TDR-024](../v0.2/tdr/TDR-024-single-device-smoke-execution.md), and [Task 2](agent-identity-registration-verification.md).

## Implementation Subjects and Behavior

- Chinese Subject: [61f5dddc2c78dc5ee639e36be3ca246b598894e9](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/61f5dddc2c78dc5ee639e36be3ca246b598894e9).
- English Subject: [820cf3522ef1f9ea093afe7974ee4c97a620a76c](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/820cf3522ef1f9ea093afe7974ee4c97a620a76c).
- Subjects include the initial implementation, two behavioral fix rounds and separate CI assertion repairs; subsequent documentation commits do not replace them. Both Subjects have been pushed together and verified against the remote.
- V13 persists immutable Plan/Case Version, Environment, Run, Attempt, Command/Event, Result and terminal history. Create Run reads the actual Locked Manifest and verified digest, limits input to one fixed demo APK plus CONFIG, and requires the exact previously published Plan; it neither publishes definitions automatically nor seeds successful Results.
- The strict Create API is preserved. Explicit server demo configuration binds the registered Agent/Device and bounded raw CONFIG bytes whose SHA-256 must match Locked CONFIG. This HOW choice is recorded in TDR-024; configuration, interfaces and deadlines are documented in the [Run API](run-lease-api.md).
- Attempt uses one standard UUID; Context is fixed under the existing Schema/JCS and readable only by its assigned Agent. Row locks and a partial unique index on active device Runs enforce exclusivity. Poll returns at most one Command; repeated poll/ACK preserves Command/Attempt/lease/fencing, and polling waits hold no transaction.
- Heartbeat, Worker and AttemptAccess share the current execution eligibility check. Heartbeat/Worker atomically ERROR, fence, record one Server Result/Audit/Outbox and release the device when eligibility is lost. AttemptAccess denies writes inside the caller transaction; future callers must compare supplied leaseId/fencingToken and retain the lock through the write.
- DRAINING may renew its current acknowledged valid lease but cannot claim new work. Recovery to unacknowledged DISPATCHED does not renew early. Worker checks at most 100 active Runs per page and traverses all pages; cancellation and deadline closure retain immutable history, leave unstarted startedAt/duration null, and never replay installation automatically.

## Executed Checks

Local validation uses the existing JDK 21.0.7+6 and Backend toolchain. Test rounds overlap and must not be summed as unique coverage.

| Check | Result | Evidence and boundary |
|---|---|---|
| TDD RED | Observed | Failures cover missing lease/UUID targets, an already elapsed recovery window after restart, rollback-only on identity denial, DRAINING/trailing JSON, renewal after eligibility loss, unacknowledged recovery and omission of the 101st active Run. The first pure-test XML was overwritten by a later run; only its original tool output remains. Later RED/GREEN XML was saved separately. Docker initialization failures are not behavioral RED. |
| Initial local regression | 31/31 PASS | Context, architecture, API, security chain, leases, CONFIG and deadlines; zero failure/error/skipped, including compilation and bootJar. |
| First fix regression | 31/31 PASS | Real Spring transaction proxies, deadlines, security, CONFIG, registration and permissions; zero failure/error/skipped. The proxy tests mock the JDBC Connection and do not replace PostgreSQL. |
| Final behavioral fix regression | 23/23 PASS | Deadline 10, Worker pagination 1, transaction proxy 3, execution security 5 and registration 4; zero failure/error/skipped. |
| CI assertion repairs | PASS | Test compilation passed. A dependency probe confirms the LongNode/IntNode representation difference after JSONB replay; full JCS bytes still reject a changed fencingToken. The strict table allowlist adds exactly the 11 V13 tables. |
| Local PostgreSQL | Environment blocked | Docker is unavailable; the final 14 AgentLease cases failed initialization and are not marked passed. The final 22 Task 3 cases require exact-commit CI. |
| Independent task and final engineering review | PASS | Rollback-only, DRAINING, trailing JSON, real concurrency, current eligibility loss and unacknowledged recovery were fixed and received scoped re-review. CI assertion repairs were reviewed separately; no actionable findings remain. |
| Contracts, acceptance records and bilingual checks | PASS | schemas=5, positive=13, negative=6, operations=36; 383 non-Markdown blobs/modes match. Pair Gate on the exact implementation Subjects passed, including EnglishOnly, structure and links. |

## Exact-Commit CI

The head SHA of every run below was checked against its implementation Subject; all four runs concluded SUCCESS. Each language's full M1 XML contains 1041 tests, zero failure/error and 2 skipped: existing Windows ACL tests inapplicable on Linux. Each has 22/22 passing Task 3 PostgreSQL cases with no skips: TestRun 7, AgentLease 14 and Migration 1. M1 evidence retains CANDIDATE status, with exitCode=0 for all 8 candidate gates; it is not rewritten as Owner acceptance.

| Branch | M1 | M2 |
|---|---|---|
| Chinese | [34354214202](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34354214202) | [34354214157](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34354214157) |
| English | [34354214263](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34354214263) | [34354214276](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34354214276) |

Both M2 runs are 12/12 PASS, covering 20 Issues / 2000 Edges. exactCommit, digest sidecar, performance/recovery file contents and historical replay digest were checked. backupRestore, dbRestartReclaim, deadLetter and manualRetry are all PASS.

The ZIP files below were downloaded and their size and SHA-256 matched GitHub Artifact metadata. For each M1 ZIP, 117 contained reports were checked against manifest digests. Two build JARs also listed in the manifest are outside this ZIP; their bytes were not independently checked through this Artifact. Raw XML and M2 JSON remain in the ZIPs, and this record versions the verification summary.

| Artifact | bytes | SHA-256 | expiresAt UTC |
|---|---:|---|---|
| Chinese M1 10105462983 | 245353 | dcc8079eb2719ef45c203461a44cd98a5abb02d8959702ff8ccedabe09a1b341 | 2026-10-09T13:09:53Z |
| English M1 10105441887 | 245237 | de660802de2096ad500e1aa885982866b2fc6e5ae16abc312c58b9cb85e2998d | 2026-10-09T13:09:24Z |
| Chinese M2 10105315742 | 1758 | 32bf97a97c8ac93b0261d4fe592152279187c4d701d7432e9abc15ef183de887 | 2026-10-09T13:06:25Z |
| English M2 10105338636 | 1746 | 3b69a779b2f9c157654ca27924cb7ead28a7e770f584873dd535e51226e5bcde | 2026-10-09T13:06:59Z |

Intermediate Subjects 9eb9f41 / 5bcceef each had M1 totals of 1031 tests, 2 failures, zero errors and 2 skipped ([Chinese](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34352222288), [English](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34352222251)). Failures concerned the V13 table set and ACK replay representation assertions; both intermediate M2 runs succeeded. Repairs neither changed production responses nor relaxed field/value constraints. Intermediate partial passes do not replace final evidence.

## Known Limitations

This slice does not execute device installation/UI, Evidence Payload storage, Agent Event/Result reporting or a complete Release PASS. It does not represent full M3 or Company acceptance. Current Server Results explicitly mark missing required Evidence FAILED; later tasks must associate actual evidence. Demo execution remains disabled by default, and no real Provider is enabled.

Mockito self-attach, dynamic-agent and OpenJDK CDS messages remain visible without suppression settings. Existing canonical digests do not cover every non-primary-path field and cannot prove arbitrary field tampering is detectable. M2's fixed migrationVersion=V11 is an existing evidence format; current V13 must be established by M1 migration and recovery tests.

M2 Run creation P95 is 1460 ms for Chinese and 1430 ms for English. Both exceed the 1000 ms reference target and pass only the shared CI hard limit; neither proves Company performance compliance.

This record versions the verification summary in GitHub. Raw Actions Artifacts still expire; no permanent retention or administrator immutability is claimed. Existing archive governance is retained without new cloud resources. This regression does not close the historical M2.5 Artifact limitation whose earliest expiry is 2026-10-07.

## Next Execution Plan

Current result: Task 3 engineering implementation, independent review and bilingual exact-commit CI are complete without performing Owner acceptance. Git status: the implementation Subjects above were pushed and verified against the remote; record commits are separate. Next action: execute Task 4, implementing local Evidence upload, download and recovery. Prerequisites: a Task 4 implementation instruction; use the accepted design without Company resources. Acceptance target: real tests establish actual byte storage and digest validation, cross-Agent/Run authorization isolation, upload retry/cancellation races, download and database/file failure recovery, with verifiable bilingual commits and CI.
