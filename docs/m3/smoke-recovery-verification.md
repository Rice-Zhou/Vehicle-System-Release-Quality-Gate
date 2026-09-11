# Single-Device Smoke Controlled Recovery Verification

This record covers the isolated-copy restore, real Agent restart and owned Backend shutdown exercises for Task 7 Step 5 on 2026-09-11. Independent A and B supplements both exited 0 with `PASS`; original exercise wrapper `FAIL` outcomes remain intact. Supplements verify the same Runs without creating new Runs or relabeling the original executions as successful.

## Provenance and Evidence Boundary

Fixed product Subjects: ZH `9c9f97d9ebe5aadc64089530024912620eb2deb0`, EN `b5ed45d4cede1bb7f48f815da838febd647f1f80`. Execution used clean ZH checkout `9a569258c3d22bdbe50b7286bdcb8740ae02541d`, paired with EN `5ac9a2047d8d0ea7bf5ddfcbb75c1822378eaa0d`; changes from the product Subjects were Markdown only. Runtime summaries identify the actual checkout rather than presenting the product Subject as runtime HEAD.

The exercises use formal Backend APIs, the recovery worker, the original Agent and the same spool. Private JDI tools only pause or terminate owned processes and count calls. Counts use exact method-entry breakpoints without changing product protocols, SDK requirements or the source of success facts. All locators below are relative to the private root `recovery-20260911/` managed by Runtime Owner / Controller; raw logs, screenshots, databases, device configuration and identity material are not committed. Generation times use report file UTC timestamps. No fixed retention period is established; future availability is `UNKNOWN`.

| Report locator | UTC | SHA-256 |
|---|---|---|
| `cold-copy-manifest.json` | `2026-09-11T07:57:55Z` | `d0ddafe3fe65101094162e7e6b0b829b0a9d9ed57cebb77bde017b892a29a5c7` |
| `restore-report.json` | `2026-09-11T08:02:51Z` | `1c424a79ae7efdad1b48c275cde61fb426f069761e5bf52de88889c0e794662e` |
| `a-supplement/supplement-result.json` | `2026-09-11T08:45:18Z` | `6481d53637d5fea0fb9759d0a9ac4c3dddc940e0d3ad044910ffbe56cc3853ba` |
| `b-supplement/supplement-result.json` | `2026-09-11T08:46:31Z` | `54888e1288cff4dc56aacaa2d66a8a09939ea74f357749c79538b10dd31c465a` |
| `restore-post-exercises-report.json` | `2026-09-11T08:52:00Z` | `1c424a79ae7efdad1b48c275cde61fb426f069761e5bf52de88889c0e794662e` |
| `final-cleanup-check.json` | `2026-09-11T08:52:55Z` | `4f8321a406d7ca3359615b5c5be8e30b7e8acfb937a3b64e5f53f8f7a7203598` |

## Database and Payload Restoration to an Isolated Copy

After stopping the original PostgreSQL instance, `STOPPED_POSTGRESQL_COLD_COPY` copied the database and Payload together: `1623` files, `71475501` bytes. This is a stopped physical cold backup, not `pg_dump` or an online consistency backup. Restoration used a new isolated copy without overwriting the source; that copy hosted exercises A and B.

Restore verification reread the original 2 Runs and 4 Evidence objects through formal APIs and checked Result digests, metadata and downloaded-byte checksums. The original normal run remains `COMPLETED/PASS/AGENT`, and the deterministic failure remains `COMPLETED/FAIL/AGENT`. Exact identities and four Payload digests are in the [earlier real-device record](real-device-smoke-verification.md). Restore verification before and after the exercises exited 0 with `PASS`, producing identical report SHA-256 values.

## A: Agent Restart After Server Acceptance, Before Durable ACK

Run `run_01a08f99036178f9bd9c7c2b0aaf46f8`; Attempt `01a08f99-0362-707a-9dfb-03230d9fe8f3`; lease `lse_01a08f990364785a9f26a1ff6f748b72`. At `2026-09-11T08:32:56.496442300Z`, execution paused exactly after the successful Result HTTP response and before receipt persistence: the Server was already `COMPLETED`, the journal was `UPLOADED`, and no receipt existed. The owned Agent was then terminated and the original Agent restarted with the same spool.

The first process recorded install/launch counts of `1` each and exited `1` after controlled termination. The restarted process recorded install/launch counts of `0` each, exited naturally with `0`, and reached journal phase `RESULT_ACKED`. The accepted Result uses fencing token `1`; the terminal database uses `2`, with the same lease and no new Attempt. Lease expiry is `2026-09-11T08:34:18.173798Z`, case deadline `2026-09-11T08:37:47.095918Z`, and terminal completion `2026-09-11T08:32:56.378357Z`; this path does not depend on timeout recovery or reassignment.

The supplement compares every journal field except phase, treating only `evidenceIds` as the production Set: identical members and count, with no duplicates. Actual bytes and digests of 6 immutable files remain unchanged. The original Result JCS digest and durable receipt match the formal Server Result; formal Results, input digest and database snapshots remain stable across replay. Result, result audit, result outbox and terminal outbox counts each remain `1`, with no repeated install, Result or terminal event.

Final Run/Case is `COMPLETED/PASS`, origin `AGENT`. Result digest: `sha256:551b2fda7d5c2d277456ffe8c958175583ad044b355e20b179d5edf4148daf80`; input digest: `sha256:2eef6fdde8dc9aad43218c03128d5be909c652d05e8798f3f153482c10a7693c`. The supplement downloads LOG/PNG through formal APIs and verifies bytes, hashes and PNG decoding.

## B: Owned Backend Shutdown, Lease Recovery Timeout and Late-Write Rejection

Run `run_01a08f9b091e7de8a73583a702766acc`; Attempt `01a08f9b-091f-7fd4-bbde-fd083b2ef3bb`; lease `lse_01a08f9b092076cbb040ecd74faeddf2`. At `2026-09-11T08:35:07.665765500Z`, execution paused at `UPLOADED` before Result PUT. The owned Backend context was closed at `2026-09-11T08:35:07.701508Z`, then the Agent resumed and produced an actual `HTTP_IO_ERROR`, exiting `1`. Initial install/launch counts were each `1`. The Backend restarted at `2026-09-11T08:35:10.208018500Z`, and the formal worker finalized against the original deadline.

Lease expiry was `2026-09-11T08:36:29.909406Z`; recovery deadline `2026-09-11T08:38:29.909406Z`; actual completion `2026-09-11T08:38:30.408858Z`, before the case deadline `2026-09-11T08:39:58.907970Z`. The same lease's fencing token changed from `1` to `2`; the terminal state is `TIMEOUT/TIMEOUT/SERVER`, reason `RECOVERY_DEADLINE_EXCEEDED`. This does not claim successful recovery through a replacement Attempt or new lease.

The independent supplement replays the same spool after timeout, recording install/launch counts of `0` each and receiving actual `409:LATE_EVENT_CONFLICT`, exit `1`, without ACK. The supplement tool's own exit `0` and `PASS` mean the rejection matched expectations. Formal Results and terminal database state remain identical across the late write: Result `1`, Agent Result `0`, result audit `0`, result outbox `0`, terminal outbox `1`, timeout audit `1`.

Server Result digest: `sha256:95ace5d6523ca48e4a209493d83dacc143d173fcf7aa9a1604f5f7dbd8b4d921`; terminal input digest: `sha256:2aedffbe355eb4b2c007302e16e63d2721a93d05f5787e5a2ed79187e5203476`. The Server Result has empty `evidenceIds` and both evidence requirements are `FAILED`. Previously uploaded LOG/PNG remain downloadable and pass byte verification, but do not constitute success evidence for the timeout Result.

## Downloaded-Byte Digests

| Path | Evidence ID | Type | bytes | SHA-256 |
|---|---|---|---|---|
| A | `ev_01a08f992db175e99c377b947d546273` | `LOG` | 195 | `0b6af965a0f8c2047803d8ad2c111df9988d1d5dba7c530f2b978b2bd848f0e7` |
| A | `ev_01a08f992e6473b19c7ae9b642736ef9` | `SCREENSHOT` | 49844 | `3d3c684d82e61dc07d70d213c8ec4afa9ba80664b2aa38fd54ad043f8617975a` |
| B | `ev_01a08f9b2e57722889b390509180ba61` | `LOG` | 195 | `b5803c809be9e5490afa4265fff1f18cc763254ab8ba44374f07fcebdcdb3afe` |
| B | `ev_01a08f9b2f0a7d6cbfb415c1a4c9d6fe` | `SCREENSHOT` | 49291 | `1724ac525d03bf586de497e3a125c8f04e72b8eda64d5eb970cb183a1f4de99b` |

## Original Failures and Independent Supplements

A's first round never reached the injection point and its Run timed out at the allocation deadline; the original root cause remains `UNKNOWN`. B's first round experienced severe TLS cryptographic slowdown under global JDI MethodEntry observation. Bounded synthetic PBKDF2 RED/GREEN evidence supports exact breakpoints, but does not establish A's unique original root cause. All original outputs remain retained.

A-v3 completed the actual checkpoint and restart, but its private tool compared the production Set as an ordered array and reported `ACK_JOURNAL_BINDING_CHANGED`. Original wrapper exit `1` and `output-agent-restart-v3/summary.json` values `generationStatus=FAILED` and `scenarioOutcome=FAILED` remain intact, as do formal `runStatus=COMPLETED` and `caseStatus=PASS`. The independent supplement verifies the same Run using strict Set semantics and preserves all four original summary facts.

B-v2 produced a formal timeout Result, but its private tool incorrectly required initial `input_digest=null` to equal the digest generated at termination; the original wrapper `FAIL` remains intact. The independent supplement uses that same Run's existing terminal state as the late-write baseline, without fabricating Results or changing historical failures. Both supplements correct verification boundaries without modifying product implementation.

## Cleanup, Limitations and Next Step

Cleanup at `2026-09-11T08:52:55.781930+00:00` reports `PASS`: all `1623` source and backup files totaling `71475501` bytes retain their SHA-256 values. The restored PostgreSQL instance is stopped, ports `55432` and `58443` have no listeners, original and restored directories have no `postmaster.pid`, and all owned Agent processes have exited.

B interrupts the Agent→Backend connection by stopping the owned Backend; it is not a physical USB/ADB disconnect. Physical ADB disconnection, device reboot, online backup, cross-version PostgreSQL restore, Company Archive and a general failure matrix remain unverified. This round made no global network changes and did not clear device data, uninstall or reboot. Single-device controlled results do not establish complete M3 or release acceptance.

This round does not change Quality Gate `NOT_EVALUATED` or `verified=false`. Full Crash/ANR and power-loss recovery remain unverified. Existing P3 and M2.5 risks carry forward from the [earlier real-device record](real-device-smoke-verification.md) and are not closed by these results.

Next is Task 7 Step 6 final overall review, followed by an Owner decision on the new candidate record [M3-SMOKE-RECOVERY-001](../governance/acceptance/records/2026-09-11-m3-smoke-recovery-001.md), currently `PENDING`. Existing acceptance records, Subjects and decision histories remain unchanged.
