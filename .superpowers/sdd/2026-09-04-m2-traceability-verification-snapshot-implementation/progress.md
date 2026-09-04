# SDD ledger — plan: docs/superpowers/plans/2026-09-04-m2-traceability-verification-snapshot-implementation.md

Started: 2026-09-04
Merge base / implementation authorization head: 6a8b5cfe20b32f9ee7d723ad0b6459584c343ed6

## Goal and success criteria

- Goal: implement the approved M2.5 pinned-input asynchronous traceability verification and immutable snapshot without changing V0.1 semantics.
- Success: Tasks 1-7 complete with RED/GREEN evidence, task review approval, final whole-branch review, full gates, bilingual synchronization, exact-head CI, and a PENDING Owner Gate.
- Assumptions verified locally: Kotlin/Spring Boot modular monolith; PostgreSQL V10 is current; V4 already owns verification/snapshot/gap/job bases; V6 owns issue snapshots; V9/V10 own typed edge authority; the current branch is an isolated linked worktree.

## Pre-flight task/interface scan

| Tasks | Shared file or interface | Producer / consumer check | Finding |
| --- | --- | --- | --- |
| 1 / 4 | verification DTOs and POST contract | Task 1 fixes strict DTOs; Task 4 binds the POST controller to them | Consistent |
| 1 / 6 | read DTOs, contract, security tests | Task 1 fixes response/security contract; Task 6 implements GETs | Consistent |
| 2 / 4 | Run/input ledger schema | Task 2 creates V11 authority; Task 4 writes it transactionally | Consistent |
| 2 / 5 | Snapshot/result/path/gap schema | Task 2 creates constraints; Task 5 materializes atomically | Consistent |
| 2 / 6 | persisted read model | Task 2 fixes immutable tables; Task 6 reads persisted ordinals only | Consistent |
| 3 / 4 | input canonicalizer | Task 3 defines deterministic input digest; Task 4 computes it after pinning | Consistent |
| 3 / 5 | verifier and result canonicalizer | Task 3 produces pure computation; Task 5 persists it unchanged | Consistent |
| 4 / 5 | repository, job type, Run lifecycle | Task 4 queues TRACEABILITY_VERIFY; Task 5 claims and terminates it | Consistent |
| 4 / 6 | controller and repository | Task 4 creates POST/controller base; Task 6 adds read operations | Consistent |
| 5 / 6 | immutable Snapshot graph | Task 5 writes the complete graph; Task 6 performs set-based reads | Consistent |
| 1-6 / 7 | tests, contracts, migrations, operations | Task 7 consumes exact behaviors and emits candidate evidence | Consistent |
| 1 | Internal consistency | Tests expect one additive operation and strict DTOs; listed files provide both | Consistent |
| 2 | Internal consistency | Tests cover V11 clean/upgrade/constraints; migration files provide the authority | Consistent |
| 3 | Internal consistency | RED cases match Fixed/Included/Verified and five approved Gap codes | Consistent |
| 4 | Internal consistency | Start tests match one creation transaction and disabled fail-closed behavior | Consistent |
| 5 | Internal consistency | Worker tests match SKIP LOCKED, atomic result, reuse, and database locking | Consistent |
| 6 | Internal consistency | Query tests match persisted-only reads, visibility, ordering, and replay | Consistent |
| 7 | Internal consistency | Gate tests, performance/recovery, operations, and PENDING record are independently reviewable | Consistent |

Pre-flight conflicts found: none.

## Baseline evidence and rulings

- Full backend baseline attempted before Task 1: FAILED because Testcontainers could not find a Docker runtime (`DockerClientProviderStrategy`), causing PostgreSQL integration tests to fail before exercising repository code.
- Environment diagnosis: no `docker` or `podman` executable, Docker Desktop installation, Docker service, or WSL distribution is present; only `DOCKER_CONFIG=D:\Docker\.docker` exists.
- Non-container baseline: PASS — focused Kotlin architecture/contract/domain tests completed successfully; `npm run test:contracts` PASS with schemas=4, positive=12, negative=5, operations=33.
- Ruling: proceed with local compile/unit/contract RED-GREEN and use exact-head GitHub Actions as the PostgreSQL/Testcontainers authority for integration tasks — the current host cannot provide the already-designed container boundary, and installing a privileged runtime would expand scope; cost if wrong: integration feedback arrives after push rather than before commit, so each task must stop on CI failure and enter its normal fix/re-review loop.

## Task progress

- Task 1: complete (commits `6a8b5cf..2723880`, review clean after fix round 1; exact-head CI jobs ZH `100970395986` and EN `100970322818` success)
- Task 2: complete (ZH head `c96a57e4fde2768199cdf34b5fce4eab56f08605`, EN head `5df826d72941508417ef4d4d58283b2f13da1161`; Pair Gate PASS; ZH Run `33870650065` Job `101015552031` success; EN Run `33870650234` attempt 2 Job `101017683264` success)
- Task 3: complete (ZH head `4e285d35406f45eba5ce770eb3c2617c3e7f2c37`, EN head `dcede67c17f8f0c83d8dadf0e4fa2fb6bee87322`; final review `APPROVE_TASK`; Pair Gate and backend byte identity PASS; exact-head CI jobs ZH `101058811743` and EN `101058811131` success)
- Task 4: complete (ZH head `9e56d0b1d845fd0814fe684d0c57c25d5cdb23a6`, EN head `1df788cdbd0cb165e9fe7771920816e16ee5e9c8`; final task review and both CI-fix reviews approved; Pair Gate PASS; exact-head CI success)
- Task 5: pending
- Task 6: pending
- Task 7: pending

## Review history

- Task 1 initial implementation: commits `6a8b5cf..3888761`; reviewer found one Important deterministic DTO ordering gap and one CI-only security verification item.
- Task 1: fix round 1/5 (1 addressed, 0 open — deterministic Issue/path/gap DTO ordering; commits `3888761..2723880`).
- Task 1 scoped re-review: original Important finding ADDRESSED; no new Critical/Important breakage; SecurityAcceptanceTest exact-head CI verification pending.
- Task 2 RED: test-only commits ZH `f021c46` / EN `3eb69ee`; EN Run `33858632911` Job `100977661235` failed on the absent V11 columns/tables and V10-to-current migration, establishing semantic RED. One unrelated duplicate-edge fixture failure was rejected as RED and corrected before implementation.
- Task 2 implementation: V11 authority plus five review/fix rounds (`5a0cb17`, `998186c`, `36a5917`, `55a1a34`, `9edb510`, `ccf2f49`). Reviews closed input/result transaction sealing, exact latest-revision input, path/Gap semantics, legacy compatibility, same-input reuse, multi-path reachability, and exact Run/Snapshot Gap-set authority. Final task review verdict: `APPROVE_TASK`, no Critical/Important.
- Task 2 first post-review CI: ZH/EN heads `ccf2f49` / `343c951`; EN Run `33869620112` Job `101012248841` exposed two stale M2.4 current-migration counts. Integration fix ZH `c96a57e` / EN `5df826d` was independently reviewed `APPROVE_CI_FIX`.
- Task 2 final CI: ZH exact-head Run `33870650065` Job `101015552031` success. EN exact-head Run `33870650234` attempt 1 Job `101015552554` was cancelled by runner infrastructure with no test annotation; the same SHA was rerun without code changes, and attempt 2 Job `101017683264` succeeded. Pair Gate and backend byte identity passed before both pushes.
- Task 3 implementation and four review/fix rounds: ZH commits `05fd08c`, `0448576`, `9f9367e`, cross-task confidence authority correction `af76713`, `80d60f5`, and `4e285d3`; final reviewer verdict `APPROVE_TASK` with no Critical/Important/Minor findings. Fresh focused tests passed 37/37, architecture and compile passed, contracts passed (`schemas=4 positive=12 negative=5 operations=34`), and the 20-Issue/2,000-Edge reference computation completed in about 42 ms.
- Task 3 bilingual synchronization: EN equivalents `a8dc0ff`, `2edf39f`, `b338ee4`, `c4f88ed`, `914316e`, and `dcede67`; Pair Gate and backend byte identity passed. Exact-head CI succeeded for ZH Job `101058811743` at `4e285d35406f45eba5ce770eb3c2617c3e7f2c37` and EN Job `101058811131` at `dcede67c17f8f0c83d8dadf0e4fa2fb6bee87322`.

## Cross-task authority audit before Task 4

- Finding: the additive public `TraceabilityPathEdge` contract requires both opaque `revisionId` and numeric `revision`, while the V11 pinned input/snapshot authority and Task 3 `PinnedTraceabilityEdge` currently retain only logical `source_edge_id` plus numeric `source_edge_revision`.
- Risk: Task 5/6 would otherwise have to invent `revisionId` during materialization/query or lose the existing typed Edge Revision entity identity, violating deterministic replay and the already-published API shape.
- Ruling: stop Task 4 dispatch until a focused RED/GREEN correction preserves the authoritative revision entity ID end-to-end. For M2.4 typed edges this is the immutable `*_edge_revision.id`; for manifest-derived `ARTIFACT_RELEASE`, the Locked Manifest Revision ID is the authoritative revision ID. This is an implementation identity completion, not a change to Release/Manifest/Evidence/Traceability semantics.
- Correction implementation: ZH `b114644e7f51c2deb29b3da9c36eb49c0fe13163` plus review fix `1f88b000e5fe5d1b7c218c230124652eeefc014f`; EN equivalents `1e554b1` and `ee22723`. The final independent review verdict is `APPROVE_CORRECTION`, with no remaining Critical/Important/Minor findings.
- Review fix evidence: the V10→V11 fixture now contains a non-empty historical Snapshot with all four Edge types and proves exact revision-ID backfill, unchanged historical content identity, restored immutable trigger, and rejected mutation. Local non-PostgreSQL focused tests passed 59/59; PostgreSQL execution remains assigned to exact-head CI because this host has no Docker provider.
- Bilingual correction Pair Gate: PASS for ZH `1f88b000e5fe5d1b7c218c230124652eeefc014f` and EN `ee2272347ae4e51730ae5f1295de63dbf2cbc6a7`; every non-Markdown file is byte-identical and English Markdown satisfies the language boundary.
- First exact-head correction CI: ZH Job `101075988911` and EN Job `101075989535` both failed semantically in nine PostgreSQL migration tests because the shared test fixture wrote an empty `ARTIFACT_RELEASE` revision ID. Systematic data-flow tracing proved the V11 trigger correctly required the Locked Manifest Revision ID; no product authority was weakened.
- CI fix: ZH `dd564c59fa42cb87010b5e6cb9674acbea43967e`, EN `eae524b721abf1eee9578715d4bfe98e696c454a`; final review verdict `APPROVE_CI_FIX`, no Critical/Important/Minor. The fixture now reads `artifact_release_edge_v.manifest_revision_id` and asserts it equals the parent authority before inserting the ledger. Pair Gate PASS for these exact heads.
- Second exact-head correction CI: ZH Job `101081133185` and EN Job `101081135794` both failed on two fixture-layer assumptions: the V10 custom-schema connection lacked the target schema in its transaction-local search path for the historical V1 ownership trigger, and a forged post-commit child expected the fixed-input SQLSTATE even though the earlier atomic-creation trigger correctly returned `55000`.
- Trigger-layer CI fix: ZH `f16a92879900f1a6a282d6a429c2486127369ac2`, EN `13d5976dcb4c1f91fe42185c5d0dfc8f6062280c`; review verdict `APPROVE_CI_FIX`, no Critical/Important/Minor. The test now proves the custom Manifest row is the same-Release `LOCKED` authority before applying a transaction-local test search path, and separately proves post-commit append `55000` versus same-transaction forged fixed input `23514`. No product SQL or trigger changed. Pair Gate PASS for these exact heads.
- Final correction CI: ZH exact-head Job `101086244538` at `f16a92879900f1a6a282d6a429c2486127369ac2` success; EN exact-head Job `101086241113` at `13d5976dcb4c1f91fe42185c5d0dfc8f6062280c` success. The pre-Task-4 revision identity authority audit is closed.
- Task 4 implementation: ZH `c54cba982ac7c68edeff030268255f540048375d`, review fix `c58532ae0ae9ee101ee8369a9c454f11ddc3a023`, and Chinese report governance fix `8fde50448cdbc83a1dff0d7ea9289594959e5af4`; EN code equivalents `1eb6bdc` and `d4eadd5` retain the English report. The independent final review verdict is `APPROVE_TASK`, no Critical/Important/Minor.
- Task 4 review fixes closed: absolute-latest Issue Snapshot selection followed by fail-closed version validation; redacted verification persistence `503`; final idempotency response UPDATE rollback boundary; scoped Outbox assertion; 128/129 Release ID contract boundary; direct disabled `503` regression. Local non-PostgreSQL tests passed 67/67 and contracts passed 34 operations.
- Task 4 bilingual Pair Gate initially failed because the ZH SDD report was English; the report-only correction retained every inline-code and numeric token and the final Pair Gate passed for ZH `8fde50448cdbc83a1dff0d7ea9289594959e5af4` and EN `d4eadd534b6398acc0ab0ead3eb7dac539cac9a7`.
- First Task 4 exact-head CI: ZH Job `101109931634` and EN Job `101109930957` failed during Flyway context initialization with `SQLSTATE 53300 FATAL: too many clients already`; artifact JUnit XML proved all later failures were context-threshold cascades and that no Task 4 SQL/business assertion executed.
- Task 4 CI pool-budget fix: ZH `b0b552f6d8a931a744ad07e0b1d43b17252da8a3`, EN `d288ea0b301a209d25e41183f7d3350357d09395`; independent verdict `APPROVE_CI_FIX`, no Critical/Important/Minor. Only the two new test contexts use max pool 2/min idle 0, with a database-free Spring Binder regression; production and shared test infrastructure remain unchanged. Pair Gate PASS for these exact heads.
- Second Task 4 exact-head CI: ZH Job `101118092212` and EN Job `101118089797` failed with the same `SQLSTATE 53300` during Flyway context initialization. Artifact `9948147626` again showed that no Task 4 SQL/business assertion executed, disproving the local two-context pool-budget hypothesis.
- Structural connection-resource fix: ZH `0601b5bfc32e348ae29733b38a24934dcec0097e` plus independent-review fix `afa4456303be0bdff1d203b504ad6ef98af5c2ed`; EN equivalents `92183fa` and `21b8cfb8d647b76935986452f9b5a07739947da2`. The shared `PostgresIntegrationTest` now owns max pool 3/min idle 0 for all 12 cached contexts, reducing the actual total maximum from 104 to 36 and the idle reservation ceiling from 100 to 0 while retaining the proven three-connection concurrency requirement.
- The structural regression uses real Spring merged-context metadata and ArchUnit discovery across all 23 subclasses. Independent review added exact `DynamicPropertiesContextCustomizer` equality so higher-priority `@DynamicPropertySource` cannot bypass the shared authority. Mutation evidence proved both local property and dynamic property overrides fail; local non-PostgreSQL verification passed 69/69 and both CI-fix reviews returned `APPROVE_CI_FIX` with no findings. Pair Gate PASS for ZH `afa4456303be0bdff1d203b504ad6ef98af5c2ed` and EN `21b8cfb8d647b76935986452f9b5a07739947da2` before the ledger update.
- Final Task 4 exact-head CI: ZH Run `33907619072` / Job `101136196594` succeeded at `9e56d0b1d845fd0814fe684d0c57c25d5cdb23a6`; EN Run `33907618906` / Job `101136196368` succeeded at `1df788cdbd0cb165e9fe7771920816e16ee5e9c8`. After the complete gate actually executed, the original `SQLSTATE 53300` did not recur, closing the Task 4-to-Task 5 persistence handoff boundary.
