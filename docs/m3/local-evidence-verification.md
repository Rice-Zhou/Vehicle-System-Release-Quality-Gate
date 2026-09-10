# Local Evidence — Task 4 Engineering Verification

## Scope and Implementation Instruction

On 2026-09-10, after Task 4 was identified as the next action, the Owner supplied the exact text below, interpreted in context as “execute the next step.” This slice implements local Evidence upload, download and recovery under the accepted single-device demonstration design. Tasks 5–7, device operations and Company are outside scope. This record does not replace Owner acceptance, introduce a component gate, or authorize merge, Tag, release or deployment.

```json
{"instruction":"\u5fd7\u5174\u4e0b\u4e00\u6b65"}
```

Basis: [implementation plan](../superpowers/plans/2026-09-09-single-device-smoke-implementation.md), [design](../superpowers/specs/2026-09-09-single-device-smoke-design.md), [TDR-025](../v0.2/tdr/TDR-025-local-demo-evidence-payload.md), and [Task 3](run-lease-verification.md).

## Implementation Subjects and Behavior

- Chinese Subject: [ca37cee7fc1626823617f565fd45224cbb73bf32](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/ca37cee7fc1626823617f565fd45224cbb73bf32).
- English Subject: [97ee9c2be9d9ec4a09f5df9f2b9622e856b8808a](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/97ee9c2be9d9ec4a09f5df9f2b9622e856b8808a).
- Both Subjects contain the initial implementation, I1/I2 repair, oversized-integer check and CI transaction/migration repairs. Later documentation commits do not replace the implementation Subjects. Pair Gate passed, both branches were pushed together and the remote was verified. Independent whole-slice review and final scoped re-review concluded APPROVE_FINAL with no unresolved actionable findings; exact-commit CI and Artifacts have been verified successfully.
- V14 stores immutable Agent/Device/Run/Attempt/lease/fencing bindings and Evidence Metadata in Upload Sessions. Existing AgentAccess/AttemptAccess supplies current authorization; strict Create/Complete requests remain unchanged, with no second identity or permission authority.
- Payload storage belongs to the disabled-by-default local demonstration Profile. The controlled root is outside Git and public static resources and accepts only server-generated IDs. LOG is limited to 1 MiB and strict UTF-8/text/plain; SCREENSHOT to 8 MiB and an image/png signature. Directories and files require service-account permissions, reject path escape, links and nonregular files, and use 64 KiB blocks with exclusive candidates.
- PUT uses a short preflight transaction, Servlet nonblocking reception without a business transaction, then a short transaction rechecking current permissions, lease and the original binding. The total receive deadline defaults to and is capped at 30 seconds, with shorter configuration allowed; timeout returns 408 UPLOAD_TIMEOUT. Declared size/SHA-256 must match before same-directory hard-link create-only publication, with explicit failure on unsupported filesystems. EOF/timeout/error races terminate once; invalid candidates neither occupy the fixed file nor delete correct existing bytes. TDR-025 records this HOW decision.
- Complete retains Attempt/Session locks while rechecking actual bytes, type and digest and committing AVAILABLE with Audit/Outbox. A correct file surviving DB rollback is retained as an orphan for same-Session verification and retry; no cross-filesystem/database atomic transaction is claimed.
- Download requests record purpose through existing Reason.reason. A 60-second grant binds actor/project/evidence/purpose. Every GET rechecks current identity, permission, sensitivity, expiry and retention/legal hold; output starts only after successful Audit. It uses an authenticated same-origin relative URI, no-store and a safe filename, rejects Range and does not redirect to an unauthenticated URL.
- Reconciliation, backup inventory and restoration operate on fixed Evidence ID sets of 1–1000 items per batch. Actual Metadata and payload inventories/digests are checked together. Missing or corrupt files append INTEGRITY_ERROR observations without rewriting historical Result/Metadata or deleting unknown files. AttemptEvidence.resolve/seal is provided for Task 5; Event/Result reporting is not implemented here.

## Executed Checks

Baselines: Chinese 8b2bfc0e37a9f1f852d2f3c587f257df14f66eb4 and English e9d8c98ca2e19b25b8e17c47819bf8fb53ee1c0f. Both worktrees were clean and matched the remote. All four baseline M1/M2 runs concluded SUCCESS. Contract schemas=5, positive=13, negative=6, operations=36 and acceptance-record validation passed. These establish only the state before Task 4.

### Initial Implementation and Review

Initial candidates are Chinese 8f12336 and English a084bd0; they are not final delivery Subjects. Local checks use the existing JDK 21.0.7 and Gradle Wrapper. Rounds overlap and are not summed as unique coverage.

| Check | Actual result | Boundary |
|---|---|---|
| Initial RED | Compilation failure | ControlledPayloadStore/EvidenceUploadService were unresolved because implementation did not exist. This was neither behavioral assertion RED nor Docker initialization failure. |
| Broad non-PostgreSQL regression | 760 tests, 3 failures, zero errors, 10 skipped | Two disabled-default JDBC dependency failures and one test dynamic-configuration authority failure were subsequently fixed with targeted coverage. Skips include existing conditional/platform cases. |
| Targeted compatibility fix | 33 tests, zero failures/errors, 1 skipped | Default context, pool budget, architecture, API, real HTTPS and file storage; original shared assertions remain intact. |
| Final first-round file/HTTPS checks | 11 tests, zero failures/errors, 1 skipped | Real HTTPS/mTLS/Servlet/file transfer with explicit database and identity test doubles. The Linux-only link case is skipped on Windows; Windows ACL positive/negative cases actually execute. |
| Local PostgreSQL | 15 initialization failures | Docker is unavailable; transaction, FK, concurrency and recovery business assertions did not run locally and were assigned to exact-commit CI. |
| Independent task review | Fixes required | A first short stream occupied the fixed file before validation; reception lacked a total deadline while network reading held business locks. Both enter the combined fix round; partial GREEN does not override findings. |

Early HTTPS failures came from test health configuration, inherited temporary-directory permissions and test JWT issuer formatting. Repairs retain production ACL and authorization checks. Mockito/JVM, Schema and TLS messages remain visible without global suppression. The initial report incorrectly claimed that a total receive budget already existed; review established and corrected this error. Final behavior must be supported by the fix commits and regression evidence.

### Combined Repair and Scoped Re-review

| Check | Actual result | Boundary |
|---|---|---|
| Fix1 RED | 5 tests, 2 failures | I1 fixed-file poisoning is behavioral assertion RED. The early I2 fixture did not construct a genuinely pending body, so that round is not claimed as complete network total-deadline RED. |
| Fix1 final file/HTTPS | 17 tests, zero failures/errors, 1 skipped | Store 10 and HTTPS 7; the Linux-only link/POSIX case is skipped on Windows. An actual TLS request sends only part of the body and remains before EOF, verifying total-deadline 408, candidate cleanup and correct retry. |
| Fix1 compatibility | 22/22 PASS | Default context, architecture, pool budget and API. A separate fixture missing requestId was corrected within the final 17 cases without changing the production requirement. |
| I1/I2 scoped re-review | ADDRESSED; Approved | The fixed diff and round-specific XML were checked in full: declaration validation precedes publication, network reception holds no business locks, and EOF rechecks current authorization. |
| Final PostgreSQL set | 18 compiled at that round, before CI results | Upload 5, Recovery 9, Download 3, Sensitive 1; includes Cancel/Worker completing within two seconds during pending reception and rejecting late EOF. |
| Contract and bilingual implementation | PASS | schemas=5, positive=13, negative=6, operations=36; all 400 non-Markdown blobs/modes match. Pair Gate, including EnglishOnly, structure and link checks, passed. |

The last round actually passed compileKotlin, compileTestKotlin and bootJar. File abort/EOF and Controller three-callback races use explicit MockAsyncContext with real services/storage; they are not presented as real network scheduling races. HTTPS still uses database/identity port doubles; real transaction, lock and restore behavior requires PostgreSQL CI. The total deadline bounds network reception; local disk operations and short transaction commits still rely on operating-system/database service availability.

### Intermediate CI Failures and Treatment

All four workflows for Chinese 030ed39 / English 3f3ac5c FAILED. Each M1 reported 1076 tests, 3 failures, zero errors and 3 skipped: [Chinese M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34434153793), [English M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34434153741). Actual failures were a checked IOException for a missing file not rolling back Complete, the V12-to-latest migration count still expecting 1, and the restored latest version still expecting V13. The corresponding M2 replay child command also failed: [Chinese M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34434153842), [English M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34434153743). These are intermediate failure evidence, not final delivery passes.

English M1 Artifact 10135679935 was downloaded and verified at 265487 bytes with SHA-256 00b248fe57ac47639fba4e43f25ba576aa5def66af4552ea6804680cf29c21fd; all 122 retained report manifest digests matched. Task 4 PostgreSQL was then 17/18 PASS and the Linux link test actually ran. Partial success does not override the failures. All three skips were Windows ACL cases inapplicable to Linux.

Whole-slice review additionally found that asLong could truncate an oversized sizeBytes integer and create a Session with an invalid declaration. Actual files remained bounded and V14 prevented inconsistent AVAILABLE metadata. This Minor was subsequently fixed alongside CI failures, and final scoped re-review confirmed every finding ADDRESSED. Existing Schema components, TLS 1.3 optional certificate/PHA and Mockito/JVM messages remain explicitly disclosed; tested initial mTLS handshakes do not establish PHA support.

Final repairs form two logical commits: a702724 / dfbdcc7 checks lossless Long conversion at the single declaration boundary, with actual HTTPS RED 1/1 followed by GREEN 8/8. ca37cee / 97ee9c2 configures local IOException rollback for Complete, preserves durable REJECTED behavior for EvidenceRejected, and precisely updates two V14 expectations. Real Spring annotation transaction-proxy RED was 2 tests/1 failure, followed by final targeted GREEN 10/10. Files are actually missing/corrupt; JDBC Connection is an explicit observable double. The original failing PG flow additionally verifies correct PUT/Complete recovery to AVAILABLE after missing-file Complete. All 18 scenarios and migration tests compiled; the final CI section below records actual PG success.

## Exact-Commit CI

Each head SHA was verified against the final implementation Subject above, and all four runs below concluded SUCCESS. Each language's complete M1 XML contains 1079 tests, zero failures/errors and 3 skipped. All three skips are Windows ACL cases inapplicable to Linux; local Windows positive/negative cases actually ran. Task 4 PostgreSQL is 18/18 PASS with no skips in each language: Upload 5, Recovery 9, Download 3, Sensitive 1. The Linux link/POSIX case actually passed; final HTTPS coverage is 10/10. M1 evidence retains CANDIDATE status with all 8 candidate gates at exitCode=0, not Owner acceptance.

| Branch | M1 | M2 |
|---|---|---|
| Chinese | [34435145567](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34435145567) | [34435145584](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34435145584) |
| English | [34435145725](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34435145725) | [34435145721](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34435145721) |

Both M2 runs are 12/12 PASS, covering 20 Issues / 2000 Edges. exactCommit, digest sidecar, performance/recovery file contents and historical replay digests were checked. backupRestore, dbRestartReclaim, deadLetter and manualRetry all PASS. V12→V14 migration and current V14 restoration assertions passed.

All four ZIPs below were downloaded and individually matched against GitHub Artifact size and SHA-256 metadata. Each M1 ZIP has 123 retained report manifest digests verified. The two additional build JARs listed in the manifest are outside this ZIP; no independent byte verification of those JARs from this Artifact is claimed. Original XML/M2 JSON remains in the ZIPs, with the verification summary versioned in this record.

| Artifact | bytes | SHA-256 | expiresAt UTC |
|---|---:|---|---|
| Chinese M1 10136116837 | 260211 | c2e137bf87b3b866d8e58b86ed919d564385e6dba331b144d1f5bb8764a7526a | 2026-10-10T04:05:53Z |
| English M1 10136128632 | 260174 | 69155c818d8f790853f2732f72c22083435535bdbbcf873fa8a2bb8631729465 | 2026-10-10T04:06:25Z |
| Chinese M2 10136032718 | 1752 | 9f8dade90bf3cb819ab2767b78abd6eaeda1ba44101b5e35d95d1322e065a2a1 | 2026-10-10T04:02:03Z |
| English M2 10136034722 | 1754 | 516f542bc57d788c4f82bded94dff3140f37c7c43d0f230e76dfe9631b9fcca6 | 2026-10-10T04:02:09Z |

## Known Limitations

This slice does not execute Agent Event/Result reporting or device operations and does not establish full M3/Release or Company acceptance. The demonstration remains disabled by default; no real Provider is enabled or Company archive resource required. Ordinary directory permissions and content digests do not establish administrator immutability or WORM.

Known Schema, TLS optional certificate/PHA and Mockito/JVM messages retain the attribution above. A total network deadline does not establish real-time guarantees for local disk or database operations. Existing canonical digests do not cover every field outside the main paths; arbitrary field-tamper detection is not claimed. The fixed M2 migrationVersion=V11 is a historical format marker; actual migration and restoration tests establish current V14 behavior.

M2 Run creation P95 is 1017 ms in Chinese and 1305 ms in English. Both exceed the 1000 ms reference target and pass only the shared-CI hard limit, not Company performance acceptance.

This record versions verification summaries in the existing GitHub repository. Raw Actions Artifacts retain the expiry dates above; permanent retention is not claimed. Existing archive governance applies without additional cloud resources. The earliest historical M2.5 Artifact expiry of 2026-10-07 is not closed by this regression run.

## Next Execution Plan

Current result: Task 4 local Evidence implementation, independent reviews, exact-commit CI and Artifact verification are complete, without acting for the Owner's acceptance. Git status: the implementation Subjects above are pushed and verified against the remote; documentation commits are separate. Next action: execute Task 5, implementing Event, Result and Run completion contracts. Prerequisites: a Task 5 implementation instruction under the accepted design; no Company resources are needed. Acceptance target: actual Result digests/idempotent conflicts, required Evidence ownership/integrity, terminal/cancellation concurrency, Run completion and result queries pass real PostgreSQL/contract tests, with verifiable bilingual exact commits and CI.
