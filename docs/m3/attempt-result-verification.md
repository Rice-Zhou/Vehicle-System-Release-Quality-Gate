# Events and Results — Task 5 Engineering Verification

## Scope and Implementation Instruction

On 2026-09-10, after Task 5 was explicitly identified as the next action, the Owner instructed execution of that next step. This slice implements Event, Result and Run completion contracts under the accepted design and plan. Tasks 6–7, device operations and Company are outside this slice. This engineering record does not replace Owner acceptance, add a component acceptance gate, or authorize merge, Tag, release or deployment.

Basis: [implementation plan](../superpowers/plans/2026-09-09-single-device-smoke-implementation.md), [design](../superpowers/specs/2026-09-09-single-device-smoke-design.md), [TDR-024](../v0.2/tdr/TDR-024-single-device-smoke-execution.md) and [Task 4](local-evidence-verification.md).

## Implementation Subjects and Behavior

Final implementation Subjects: Chinese 19cd72d610773d6884b05e1ae484d5fc11b1f786; English 56240e45e5d0b39085ff4e416fca15153cb5e718. Subsequent verification-record commits do not replace these implementation identities. Paired commits are pushed, all 420 non-Markdown file blobs/modes match, and Pair Gate passed. Independent final review returned APPROVE_FINAL with no remaining findings; exact CI and Artifact verification are complete.

Event reception is bound to Command/Attempt and starts continuously at sequence 1. Same-digest replay returns the original acknowledgement; gaps, changed content, and new writes from old generations return explicit conflicts. Result retains the strict Schema with lossless numbers and timestamp-format validation, applying the existing JCS digest algorithm to a copy. Canonical deduplication does not make duplicate Evidence IDs acceptable at the API. Terminal replay rechecks the original Agent's current permission without changing the historical acknowledgement after later file observations.

Normal results, cancellation, and deadline closure share one lifecycle: seal unfinished Sessions for the original binding before changing fencing, then commit Result, Attempt, Run snapshot, and Audit/Outbox atomically. PASS requires real required Evidence belonging to the current Attempt; ERROR may retain partial Evidence with explicit failed requirements. The single completion predicate covers every Case Resolution and created Attempt, including optional ones, without extending the V13 single-Case demo scheduler.

Result queries return execution identity, frozen Plan/Environment, each Attempt's Test Result and Evidence requirements, and inputDigest, without generating a Quality Result. V15 stores new terminal snapshots and reads old terminal Runs through the same frozen-fact projection. Missing Runs, legitimate legacy NULL snapshots, and missing snapshots on new terminal Runs remain distinct. Existing file-storage defaults and Company boundaries remain.

## Executed Checks

Baselines are Chinese c5b6f9332bc5088a7d3e1bafe5fedc421a9562bc and English a458003030e072be4df93c844becb3b6648b5bb8. Both worktrees were clean, upstream ahead/behind was 0/0 and exact remote commits matched. Fresh queries confirmed all four baseline workflows SUCCESS: Chinese M1 34436318143 / M2 34436318212; English M1 34436316736 / M2 34436316654. Fresh local contract validation passed with schemas=5, positive=13, negative=6, operations=36, as did acceptance-record validation. These establish only the state before Task 5.

The controller independently aggregated the local final-verified JUnit evidence: 52 tests / 0 failures / 0 errors / 0 skipped, including architecture 6, Evidence HTTP 10, metadata port 2, Agent HTTP security 7, identity transactions 3, Result application 6, canonical 3, generic completion 3, deadline application 10, and default context 2. Application repository and identity doubles do not establish real PostgreSQL behavior; database scenarios were not run locally; final exact-commit PostgreSQL results appear below.

## Fixes and Review Record

The initial implementation was Chinese 8be9a15b668e54d31c20b918bd3e9a32044ab24b / English 648487e1629dd41c49d1c2d0a49f2e4a07c73b50. Task review identified I1: default DoubleNode parsing rounded numbers before losslessness validation. Raw-request regressions first recorded 12 tests / 4 failures, with a separate safe-integer boundary run of 2 / 2. Chinese 7436026184e3888ec44ddc3a4b2ed67f9f6ed733 / English 45bd4939349f416692e4a5f3c705ab15d7764525 preserve precise decimals on first parsing and retain the same Schema/JCS boundary checks. The controller independently verified 44 / 0 / 0 / 0 after the fix, without adding these to the initial 52 tests. Raw-request tests use MockMvc, certificate request attributes, and Mockito application doubles; the 10 EvidenceHttpTest cases separately exercise real localhost TLS while retaining identity and database doubles.

Initial M1 Chinese 34444190275 / English 34444190249 each recorded 1103 tests / 2 failures / 0 errors / 3 skipped. Failure Artifacts were downloaded and verified against size and SHA-256 metadata, preserving raw JUnit: CI1 had identical complete ACK content but unequal Jackson LongNode/IntNode representations; CI2 had deferred trigger events from V15 backfill blocking later ALTER TABLE with SQLSTATE 55006. Initial M2 Chinese 34444190317 / English 34444190238 both returned SUCCESS, which does not replace exact-commit validation after the fixes.

Chinese 7a0b9437551fe562ca37aaf79576267957d81372 / English b7c3b07dc7a5bfe13b5eda1a1e4acd5862fa8342 address both CI findings: complete JCS ACK comparison and unique Audit/Outbox assertions without a production conversion, and execution of the original run_closed_attempts constraint followed by restoring DEFERRED before later DDL. Triggers are not disabled, and both original and new constraints are checked as enabled. Scoped re-review marked I1, CI1, and CI2 ADDRESSED with no new issues.

The subsequent whole-Task-5 final review identified I2: Schema format assertions were disabled, allowing an invalid Event timestamp into accepted facts. M1 Chinese 34445595281 / English 34445595216 each recorded 1110 tests / 1 failure / 0 errors / 3 skipped. V15 passed the previous DDL failure, but the real repository's JdbcClient.single rejected legitimate SQL NULL for a legacy terminal snapshot (CI3). Both failure ZIPs were verified against metadata and retained. M2 Chinese 34445595223 / English 34445595171 both returned SUCCESS.

The single final fix wave contains Chinese e83f2d751011fbdec7839d2861a5574e1010629c / English a7783ffd13ad06989938060cef307122f5e96dde for unified format assertions, and Chinese 19cd72d610773d6884b05e1ae484d5fc11b1f786 / English 56240e45e5d0b39085ff4e416fca15153cb5e718 for nullable snapshot reads. I2 raw requests recorded 6 / 6 RED, then targeted 50 / 0 / 0 / 0; CI3 diagnostics recorded 3 / 2 RED, then targeted 15 / 0 / 0 / 0. The controller independently aggregated each JUnit set without adding overlapping suites. TerminalSnapshotJdbcTest uses real Spring JdbcClient with controlled JDBC doubles to distinguish legacy terminal NULL, a missing Run, and a new terminal Run missing its snapshot; it does not establish PostgreSQL behavior. Scoped final re-review marked I2 and CI3 ADDRESSED and returned APPROVE_FINAL with no new or remaining issues; real migration scenarios are verified by final exact-commit CI below.

## Technical Rulings and Costs

1. Execute only Task 5 of the existing plan under the accepted design and current implementation instruction; an incorrect scope interpretation requires redefining the task boundary.
2. Keep metadata sealing available with file storage disabled, using one implementation and repository and explicit failure without Payload; an incorrect dependency choice requires changes to default wiring or terminal-history handling.
3. Store new terminal snapshots and read old terminal facts through the same frozen projection; an incorrect compatibility strategy requires migration and historical-read changes.
4. The consumer owns the single AttemptEvidence / EvidenceResolution port to preserve one-way module dependencies; incorrect ownership requires changes to port placement and exception-mapping scope.
5. Compare complete protocol content for ACK replay rather than treating Jackson numeric node types as an external contract; any such internal JVM compatibility requirement would need explicit definition and targeted handling.

TDR-024 records the implementation boundaries of the first four rulings. The fifth corrects how the test expresses the protocol without adding production serialization rules. None redefines the frozen architecture or substitutes for Owner acceptance.

## Exact-Commit CI

All four final implementation workflows returned SUCCESS, bound to the final implementation Subjects at the start of this record:

| Language | M1 | M2.5 |
|---|---|---|
| Chinese | [34446819598](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34446819598) | [34446819620](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34446819620) |
| English | [34446819246](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34446819246) | [34446819270](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34446819270) |

Both M1 Artifacts are CANDIDATE with all 8 gates reporting exitCode 0. Each contains 1119 tests / 0 failures / 0 errors / 3 skipped. The skips are Windows ACL cases inapplicable on Linux: one ControlledPayloadStoreTest and two EvidenceArchiveDirectoryAccessReaderTest cases; they are not counted as executed passes. Task 4's 18 PostgreSQL regressions, all 20 AgentExecutionSecurityTest cases, and all 6 ArchitectureTest cases passed.

The new Task 5 test classes have no failures or skips in either language's Artifact:

| Test class | Count | Verification boundary |
|---|---:|---|
| AttemptResultIntegrationTest | 4 | Real PostgreSQL, Event/Result/Evidence and authorized replay |
| TestRunCompletionIntegrationTest | 3 | Real PostgreSQL, cancellation/deadlines, fault rollback and races |
| ResultSnapshotMigrationIntegrationTest | 1 | Real PostgreSQL, V14 historical restore and V15 migration, legacy terminals and new constraints |
| ResolveAttemptEvidenceTest | 2 | Metadata port and storage disabled by default |
| AttemptResultApplicationTest | 6 | Application logic and Spring transaction proxy with repository/identity doubles |
| ResultCanonicalizerTest | 3 | JCS golden vectors, digest and input immutability |
| RunCompletionTest | 3 | Generic completion including optional cases |
| TerminalSnapshotJdbcTest | 3 | Real Spring JdbcClient with JDBC doubles for NULL/missing-row semantics |

Both M2.5 Evidence files report 12/12 PASS with 20 Issues / 2000 Edges and 3 samples. Historical replay, backupRestore, dbRestartReclaim, deadLetter, and manualRetry all passed. Run creation P95 is 1468 ms Chinese / 1414 ms English, above the 1000 ms reference and within only the 30000 ms shared-CI hard limit; worker P95 is 3986/4095 ms and query P95 is 25/22 ms. The Evidence migrationVersion=V11 is the existing fixed M2.5 identifier. The PostgreSQL cases above verify the actual V15 migration without rewriting that historical identifier.

The controller downloaded all four Artifacts and checked expiration, file size, and GitHub SHA-256 metadata. Each M1 Artifact's 131 retained reports passed individual size and digest checks. Two build JARs are outside that Artifact, so their bytes are not claimed as independently reverified here. M2.5 sidecar digests, performance/recovery subreports, replayDigest, and exact commits match.

| Artifact | ID | ZIP SHA-256 | Expires at UTC |
|---|---:|---|---|
| Chinese M1 | 10140194863 | 9384d4b3b3119b202e1ce92c7746f4df180b5ec2c846ff20d2c8f90ac437b899 | 2026-10-10T06:56:16Z |
| Chinese M2.5 | 10140197766 | 27242a06bc16401f0b4aaa0ca3f4f5c159d06a785d26e4b6b4a33ab1b4cd03b3 | 2026-10-10T06:56:23Z |
| English M1 | 10140273713 | 71f20ba3e83f7ad187456a3af931abadb1d46493c02c8c08469f79f6ff730d11 | 2026-10-10T06:59:02Z |
| English M2.5 | 10140170119 | 25b0bcdb3b510135861ff111d5020d508f90fc51e38cacd2df1d5c0c8cb97c73 | 2026-10-10T06:55:25Z |

Existing JVM CDS, Mockito, Schema components, TLS PHA, and Gradle deprecation warnings are retained without global suppression. Per-round raw logs/XML, failed and final ZIPs, metadata, and verification summaries remain in this slice's SDD Evidence directory; the GitHub Runs above provide remote evidence for the fixed commits.

## Known Limitations

This slice executes no Agent/ADB or real-device behavior and does not establish full M3/Release or Company acceptance. Performance evidence applies only to the existing shared-CI hard limit; 1000 ms remains a reference target and does not establish Company performance readiness. Existing canonical digests do not cover every non-primary-path field, so arbitrary-field tamper detection is not claimed. Historical M2.5 Artifacts begin expiring on 2026-10-07; subsequent evidence retention follows the existing Evidence Archive governance. Only the existing Backend/PostgreSQL/GitHub are used, with no additional Company resources.

## Next Execution Plan

Current result: Task 5 implementation, independent reviews, exact-commit CI, and Artifact verification are complete. Git status: paired implementation commits are pushed. This record is delivered in separate paired documentation commits whose exact CI is checked after submission, without replacing the implementation Subjects.

Sole next action: Task 6 host Agent, ADB, and LOG/SCREENSHOT Collector implementation. Prerequisites: use this slice's final implementation Subjects, shared Schema/golden JSON, and accepted TDR-024/025 under an explicit Task 6 implementation instruction. Acceptance target: Agent client/recovery journal, real bounded subprocess, same-origin HTTPS, shared digest, Collector tests and build pass, with interruption/corruption/replay evidence retained. Controlled protocol or process tests must not be labeled as real-device passes. Task 7 integration and real-device verification follow their separate boundaries.
