# Agent Identity and Registration — Task 2 Engineering Verification

## Scope and Implementation Instruction

On 2026-09-09, after Task 2 was explicitly named as the next action, the Owner replied with the original text below, interpreted in context as an instruction to execute that step. This slice implements only Agent identity, registration and context machine contracts, excluding Tasks 3–7, device operations and Company. This is engineering verification, not Owner acceptance or a new component acceptance gate; it does not authorize merge, Tag, release or deployment.

```json
{"instruction":"\u5fd7\u5174\u4e0b\u4e00\u6b65"}
```

References: [implementation plan](../superpowers/plans/2026-09-09-single-device-smoke-implementation.md), [design](../superpowers/specs/2026-09-09-single-device-smoke-design.md), [TDR-024](../v0.2/tdr/TDR-024-single-device-smoke-execution.md) and [TDR-025](../v0.2/tdr/TDR-025-local-demo-evidence-payload.md).

## Implementation Subjects and Behavior

- Chinese Subject: [729812348756ee20e897136b0a89177f0b0ac22a](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/729812348756ee20e897136b0a89177f0b0ac22a).
- English Subject: [269a6307f7685c035db89cb8e967a3fde6a615c4](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/269a6307f7685c035db89cb8e967a3fde6a615c4).
- These Subjects include the initial implementation, concurrency and body-read fixes, and existing CI test adaptations; subsequent record commits do not replace implementation Subjects.
- V12 fixes the Agent, SERVICE principal, project, Device and certificate fingerprint binding. Registration reuses existing permissions, idempotency storage and transactional Audit, reauthorizing before every replay.
- Independent X509 and user JWT chains cannot exchange credentials. Registration is disabled by default; see [Agent Protocol](../v0.2/08-test-agent-protocol.md) for development certificate configuration. No real Provider or real device identity was enabled or enrolled.
- Context Schema strictly enforces required fields and rejects unknown fields. Context GET and Payload PUT are machine-contract declarations only, with runtime implementation belonging to Tasks 3 and 4 respectively. Ordinary and sensitive Evidence use sensitivity-based authorization, preserving HIGH controls.

## Executed Checks

Local tools were the existing JDK 21.0.7+6, Kotlin 2.2.21, Spring Boot 3.5.16 and Gradle 8.14.4. JDK keytool generated temporary test certificates; passwords and private keys are not committed. Isolated HTTPS tests use real TLS and production security chains, with test doubles for the registration application and user decoder; PostgreSQL integration must independently verify database bindings.

| Check | Result | Evidence and Boundary |
|---|---|---|
| TDD RED | Observed | The missing new permission failed an assertion; the undeclared Context route failed contract validation. Docker initialization failure is not behavioral RED. |
| Affected local tests | 47/47 PASS | Permissions 7, architecture 6, default context 2, API 10, pool budget 4, security chain 9, real HTTPS 5, registration application 4; 0 failure/error/skipped, including compilation and bootJar. After shared SSL initialization changed, focused HTTPS/pool-budget verification additionally passed 9/9. |
| TLS certificates and routes | PASS | Specified certificates are forcibly sent; untrusted/expired certificates must cause SSLException. User Bearer and certificate cannot exchange routes. |
| Contract and acceptance-record validation | PASS | Both languages: schemas=5, positive=13, negative=6, operations=36; Context additionally rejects missing and unknown fields individually. |
| Local PostgreSQL | Environment blocked | 14 tests failed initialization because local Docker is unavailable; not counted as passed, requiring exact-commit CI below. |
| Independent task and final engineering review | PASS | The shared-lock upgrade deadlock and body-size check after full aggregation were fixed and re-reviewed, covering controlled concurrency, bounded bytes read and HTTPS known-length/chunked 413. CI test adaptations received a separate scoped review; no key findings remain open. |
| Bilingual Pair Gate | PASS | Exact implementation commits passed structure, EnglishOnly, links and non-Markdown parity checks. |

## Exact-Commit CI

The head SHA of each run below matches its implementation Subject; all four workflows concluded SUCCESS. Each language's M1 full test XML contains 992 tests, 0 failure/error and 2 skipped: existing Windows ACL tests inapplicable on Linux. New PostgreSQL identity 8, concurrent registration 2 and database/TLS 4 total 14/14 PASS with no skips. Both M2 runs passed 12/12; summary exactCommit, sidecar digest and recovery results were verified.

| Branch | M1 | M2 |
|---|---|---|
| Chinese | [34339324545](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34339324545) | [34339324487](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34339324487) |
| English | [34339324583](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34339324583) | [34339324521](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34339324521) |

The original ZIPs below were downloaded and their sizes/SHA-256 matched GitHub Artifact metadata; test XML remains preserved inside the ZIPs. This versioned record preserves a verification summary. Original Artifacts still expire; no perpetual hosting or administrator immutability is claimed.

| Artifact | bytes | SHA-256 | expiresAt UTC |
|---|---:|---|---|
| Chinese M1 10099430400 | 225150 | 51f06ea7fc92ded5920af5107be410383d50b9f414e66a0c57a37e33f3045318 | 2026-10-09T10:27:22Z |
| English M1 10099425590 | 225154 | 76d2b439ee400008b3c03a70e2c3785c368b04da24707325401a3fc14074287c | 2026-10-09T10:27:13Z |
| Chinese M2 10099285527 | 1756 | 4ff206b0ea63939b354926daa9a99fabbb0f9792b52e83c67b5542552a7fa192 | 2026-10-09T10:23:17Z |
| English M2 10099316363 | 1760 | ad263489eae7dca490892954679ce51d8644d38c0ab5f244b30254d70add76ca | 2026-10-09T10:24:09Z |

The initial intermediate [M1 CI](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34337578605) had 10 failures involving the new adapter double missing from a no-database test, API set expectations, migration/recovery versions and TLS test dynamic configuration. Fixes preserve the 34-operation compatibility baseline and allow only two new Agent paths. Current upgrade/recovery verifies V12, the historical V10-to-V11 test fixes target 11, and shared connection-pool budget checks were not weakened. Partial intermediate results did not substitute for the final CI above.

## Known Limitations

The OpenJDK CDS instrumentation message and the deprecation warning for the ObjectMapper URL overload used in Schema loading are retained without suppression. Test certificate generation failures include a diagnostic log path. This slice provides no device installation/UI, Run, Result, Evidence Payload or Release PASS evidence, and does not constitute full M3 or Company acceptance. Existing performance, canonical digest coverage and historical Artifact retention limitations remain unchanged.

This M2 regression measured creation Run P95 at 1089 ms in Chinese CI and 1323 ms in English CI, still above the 1000 ms reference target and passing only the shared-CI hard limit. M2 evidence retains its existing fixed migrationVersion=V11 field, which must not be interpreted as the latest database version; current V12 is verified by this slice's M1 migration and recovery tests.

## Next Execution Plan

Current result: Task 2 engineering implementation, independent review and bilingual exact-commit CI are complete, without substituting for Owner acceptance. Git status: implementation Subjects above were pushed and checked against remote refs; record commits are separate. Next action: execute Task 3 for Run, Attempt, scheduling and leases. Prerequisites: a Task 3 implementation instruction, using the accepted design with no Company resources required. Acceptance target: passing real PostgreSQL tests for Locked Manifest/Plan binding, device exclusivity, concurrent claim/ACK, lease expiry/cancellation history and context isolation, with verifiable bilingual commits.
