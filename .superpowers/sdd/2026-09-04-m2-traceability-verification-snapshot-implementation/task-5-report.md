# Task 5 Report — Worker Materialization, Recovery, and Concurrency

## Status

Implementation and local static/non-PostgreSQL verification are complete. After independent-review fix round 1, Task 5 has 25 PostgreSQL behavioral tests and 3 database-free retry tests; all compiled successfully, and the 3 retry tests are locally GREEN. The first Chinese and English exact-head CI runs both failed while starting the first PostgreSQL Context because test OIDC properties were missing; none of the 25 cases executed PostgreSQL semantics. CI fix round 1 has consolidated the fixed test issuer/audience into the shared `PostgresIntegrationTest` single authority and covers every direct/indirect derived Context with a structural regression that does not start Docker. Because this machine has no Docker/Testcontainers runtime, all 25 PostgreSQL tests still stop during container initialization, before any fixture, SQL, transaction, or business assertion runs; PostgreSQL GREEN on the repaired exact-head CI remains a mandatory acceptance condition.

## Goals and Boundaries

- The Worker claims `TRACEABILITY_VERIFY` work from the PostgreSQL `background_job` table and loads input only from the Run, pinned Issue Snapshot, Locked Manifest ID, and exact Revision references in `traceability_verification_run_edge_input`.
- Pure computation reuses the Task 3 `TraceabilityVerifier` and the sole `TraceabilityCanonicalizer`; there is no second Fixed/Included/Gap algorithm or digest implementation.
- One result transaction writes the Snapshot Header, Issue Results, every pinned Snapshot Edge, primary-path Edges, Run/Snapshot Gaps, Audit, Outbox, and terminal Run and Job states.
- The implementation does not read latest Edge Revisions, call Jira, GitHub, CI, Device, or Adapter network interfaces, rebuild authority from JSON/files/cache, or add a Broker, Redis, graph database, service, or JVM lock.

## Plan-Related Changes

The Task 5 file list names only the new Run/Worker and three test files, but its `Consumes` contract explicitly requires `TraceabilityVerificationRepository.claimNext(now)`. The Task 4 Port/Adapter contained only creation-transaction methods. Without extending the existing Port/Adapter, the Worker would have to contain direct JDBC or introduce a second persistence boundary, either of which would violate the existing single Repository authority.

The minimal related change therefore only adds claim, pinned-input loading, atomic result writes, and failure-recovery methods to `TraceabilityVerificationRepository` and `JdbcTraceabilityVerificationRepository`. It does not modify any Migration, Task 3 Domain/Canonicalizer, Controller, configuration file, or frozen contract.

## TDD RED Evidence

The three approved test files were created before the production implementation, then this command was run:

`./backend/gradlew -p backend test --tests '*TraceabilityVerificationWorkerIntegrationTest' --tests '*TraceabilityVerificationWorkerFailureTest' --tests '*TraceabilityVerificationConcurrencyTest'`

Result: `compileTestKotlin` failed explicitly because `TraceabilityVerificationJobWorker`, `runNext()`, Repository `claimNext(now)`, and Claim fields did not exist. The initial test draft also exposed Kotlin test-visibility noise. After correcting test visibility, all remaining failures identified missing Task 5 APIs. This was a feature-absence RED, not an environmental skip.

Independent-review fix round 1 added an executable transaction-retry mutation. Tests for success after 1 or 2 conflicts and mandatory escape on the 3rd conflict were added first, then `MAX_VERSION_ATTEMPTS` was temporarily changed from 3 to 2. Two of the 3 tests failed as expected: the 2-conflict case escaped too early, and the strict-bound case observed only 2 calls. After restoring the constant to 3, the same command was `3/3` GREEN. This proves that the tests protect bounded transaction-retry behavior rather than constant text.

After the first exact-head CI exposed the shared test-configuration gap, `PostgresIntegrationPoolBudgetTest` was extended without starting Docker before the shared base class was modified. The baseline ran 4 cases, with the 2 new cases RED as expected: the shared issuer was `null`, and `IssueSnapshotIntegrationTest` was the first class found to declare the OIDC keys directly. The test uses ArchUnit to scan every direct/indirect class satisfying `isAssignableTo(PostgresIntegrationTest)`, uses Spring `buildMergedContextConfiguration()` to verify the effective issuer/audience, and checks that derived classes do not redeclare either key. After moving the fixed properties to the shared base class and deleting duplicate declarations from 23 derived Contexts, the same test was `4/4` GREEN. The structural test also uses literal expectations to protect the eight retained feature/trusted-validator incremental configurations. Temporarily changing the Worker's `vsrqg.traceability.verification.enabled` from `true` to `false` produced `4 tests completed, 1 failed`; restoring it returned `4/4` GREEN. Deleting either shared property breaks the shared/effective assertions, and redeclaring an OIDC key in any derived class breaks the override assertion.

The Job/Release row-lock, SQLSTATE/constraint translation, and damaged-ledger cases require real PostgreSQL and cannot be replaced by source grep or a test-only production hook. The local Docker blocker prevented those RED/GREEN mutations from running. This report records only the mutations that the tests are designed to detect and reserves their real execution as a mandatory exact-head CI acceptance condition.

## Implementation

### Claim, Attempts, and Recovery

- `claimNext(now)` uses `FOR UPDATE OF job, verification_run SKIP LOCKED` in a separate short transaction, so one Job can be claimed by only one Worker.
- The first claim atomically increments the Job attempt by `+1` and transitions the Run from `QUEUED → RUNNING`. After a crash, reclaim is allowed only when `started_at` reaches the 300-second lease boundary, and each reclaimed execution increments the attempt again.
- Transient database failure handling catches only Spring `DataAccessException` or an exactly identified Snapshot-version unique conflict. Other programming or invariant errors remain visible; there is no broad error swallowing.
- The attempt limit is fixed at 3. The first two failures requeue the Job and persist only `TRACEABILITY_VERIFICATION_RETRY_SCHEDULED`. The third marks the Run `FAILED` and the Job `DEAD_LETTER`, persisting only `TRACEABILITY_VERIFICATION_RETRY_EXHAUSTED`. Run/Job data never stores database exception text, SQL, URLs, stacks, or input content.

### Pinned Input and Pure Computation

- The loader uses the fixed Run ID to load `issue_snapshot_id`, `manifest_revision_id`, and the Input Ledger. Each Edge is joined to append-only authority by the exact `(type, source edge ID, numeric revision, revision entity ID, fact digest)` identity.
- The loader does not use `max(revision)`, a latest CTE, or a runtime Adapter. An Edge Revision appended after Task 4 creation cannot enter this execution.
- The Worker recalculates the input canonical digest and compares it with the Run's `input_digest`. A mismatch fails with `TRACEABILITY_INPUT_NOT_VALID` and creates no Snapshot.
- `VerificationComputation` is produced entirely by the Task 3 verifier/canonicalizer. The Worker persists its `contentDigest`, `resultDigest`, and `gapDigest` without recalculating or rewriting conclusions.

### CI Fix Round 1: Shared PostgreSQL Test OIDC Authority

- The first failure in both the Chinese and English initial exact-head CI runs was the `TraceabilityVerificationConcurrencyTest` ApplicationContext. The deepest root cause was `PlaceholderResolutionException` failing to resolve `${VSRQG_OIDC_ISSUER_URI}`; all 25 subsequent failures were Context failure-threshold cascades rather than PostgreSQL business-assertion failures.
- The root cause was that the shared `PostgresIntegrationTest` held only the pool budget, while 23 existing PostgreSQL test Contexts each duplicated the fixed issuer/audience. The shared Task 5 derived layer declared only `vsrqg.traceability.verification.enabled=true`, so the OIDC placeholder in production `application.yml` had no test value.
- The fix places `https://idp.vsrqg.test` and `vsrqg-api` in the shared `PostgresIntegrationTest` only within test infrastructure and deletes the duplicate OIDC lines from PostgreSQL derived classes. Feature flags, trusted validators, and other incremental properties continue to merge through Spring `@TestPropertySource` inheritance.
- `ApplicationContextTest`, `BuildProvenanceGithubSmokeTest`, and `TraceabilityVerificationStartHttpTest`, which does not inherit the PostgreSQL base class, retain their own test OIDC boundaries. Production `application.yml`, the environment-variable contract, Hikari maximum pool size `3`/minimum idle `0`, and the absence of an environment fallback are unchanged.

### Atomic Materialization and Reuse

- The result transaction first locks the claimed Run/Job and searches by input/result digest for a successfully completed Snapshot with compatible input identity.
- On a miss, it locks `release_record`, checks reuse again, then allocates the next Release-scoped version as `max(version)+1`; no JVM lock is used. Only SQLSTATE `23505` for the exact `uq_trace_snapshot_release_version` constraint retries the complete transaction, up to three attempts. Other SQL errors are not swallowed as version contention.
- Snapshot Edges are projected set-wise from the pinned Input Ledger and retain Ledger ordinals. Path Edges reference only those Snapshot Edges. Domain `TEST_RESULT_EVIDENCE` is mapped to the existing database token `TEST_EVIDENCE` at the single persistence boundary.
- Run Gaps and Snapshot Gaps are written with the same stable fields and digest. Audit is fixed as `TRACEABILITY_VERIFICATION_SUCCEEDED`, Outbox as `traceability.verification.succeeded`, and the Run/Job are updated to `SUCCEEDED` only at the end.
- The system preserves separate Runs for identical inputs submitted under different Idempotency Keys while reusing one content-identical Snapshot. Concurrent identical inputs also converge on one Snapshot after the second reuse check under the Release row lock. Concurrent different inputs receive consecutive versions.

## Test and Failure-Injection Matrix

The three test files contain 25 PostgreSQL cases, plus 3 database-free transaction-retry cases, covering:

1. Complete-chain materialization with Fixed=true, Included=true, Verified=false, a four-segment primary path, and `TEST_RESULT_EVIDENCE_MISSING`.
2. An INVALID Revision appended after the request does not change the pinned Revision result.
3. Sequential identical input under different Runs reuses one Snapshot.
4. Two Workers claim one Job only once.
5. Concurrent identical inputs converge on one Snapshot.
6. Concurrent different inputs under one Release receive consecutive versions 1 and 2.
7. A crashed RUNNING Job cannot be claimed before the lease boundary, can be reclaimed at 300 seconds, and increments its attempt.
8. Three failures produce Run FAILED and Job DEAD_LETTER while keeping diagnostics redacted.
9. The nine parameterized result-write boundaries: Snapshot Header, Issue Result, Snapshot Edge, Path Edge, Gap, Audit, Outbox, Run terminal, and Job terminal. Failure injected at each boundary requires zero Snapshots, no result pointer on the Run, zero successful Audit/Outbox records, and only a safe Job retry.
10. While an independent transaction holds the eligible Job row lock, a second `claimNext` must return `null` within 1 second before the first transaction releases its lock. Removing `SKIP LOCKED` makes this assertion time out.
11. While an independent transaction holds the `release_record` row lock, the Worker must be visibly waiting on a real database Lock in `pg_stat_activity` and finish only after release. Removing the Release `FOR UPDATE` makes the Worker complete early with no observed wait.
12. A real `23505` from `uq_trace_snapshot_release_version` is translated to `TraceabilitySnapshotVersionConflict`. Another `23505` and a version check violation remain Spring `DataIntegrityViolationException` instances and enter the Worker's safe retry path instead of being swallowed by version-conflict retries.
13. Simulated corruption of the pinned Run input digest or ledger fact digest atomically produces Run `FAILED`, Job `DEAD_LETTER`, zero Snapshot/Gap rows, and only `TRACEABILITY_INPUT_NOT_VALID`.
14. If `failInvalidInput` fails while writing Job DEAD_LETTER, the Run FAILED update is rolled back with it, and the Job enters only safe retry without a partial terminal state.
15. A complete transaction succeeds after 1 or 2 consecutive `TraceabilitySnapshotVersionConflict` instances; the 3rd consecutive conflict must escape, with exactly 3 calls and a new Snapshot ID on each attempt.

Mutation evidence is layered. `MAX_VERSION_ATTEMPTS=2` produced a real local RED, and restoring 3 produced GREEN. Removing `SKIP LOCKED` makes the controlled second claim time out before the first transaction releases; removing the Release row lock lets the Worker complete early without a `pg_stat_activity` Lock wait; broadening conflict recognition to any `23505` breaks the OTHER_UNIQUE type assertion; allowing input corruption to create a Snapshot, expose an internal reason, or split the invalid-terminal transaction breaks the corresponding terminal-state and zero-artifact assertions. Because Docker is unavailable, these PostgreSQL mutations can currently be executed only by exact-head CI; test design is not reported as local runtime evidence.

## Local Verification Evidence

Compilation:

`./backend/gradlew -p backend compileKotlin compileTestKotlin`

Result: `BUILD SUCCESSFUL`; production and test Kotlin both compiled.

Fresh non-PostgreSQL gate:

`./backend/gradlew -p backend cleanTest test --tests '*RunTraceabilityVerificationRetryTest' --tests '*TraceabilityVerifierTest' --tests '*TraceabilityCanonicalizerTest' --tests '*ArchitectureTest' --tests '*ApplicationContextTest' --tests '*PostgresIntegrationPoolBudgetTest' --tests '*M2ApiContractTest' --tests '*TraceabilityVerificationDtoTest' compileKotlin compileTestKotlin --rerun-tasks`

Final fresh independent-review fix-round-1 result: `BUILD SUCCESSFUL in 1m 8s`; `66/66` passed with zero failures, errors, or skips, and all 7 Gradle tasks executed.

Final fresh CI-fix-round-1 result after adding the shared OIDC structural regression: `BUILD SUCCESSFUL in 1m 24s`; `68/68` passed with zero failures, errors, or skips, and all 7 Gradle tasks executed. `PostgresIntegrationPoolBudgetTest` was separately `4/4` GREEN, covering the pool budget, shared OIDC authority, effective merged values for every derived Context, prohibition of derived overrides, and retention of eight incremental configurations.

Contract gate:

`npm run test:contracts`

Result: `PASS contracts schemas=4 positive=12 negative=5 operations=34`.

Focused PostgreSQL command:

`./backend/gradlew -p backend cleanTest test --tests '*TraceabilityVerificationWorkerIntegrationTest' --tests '*TraceabilityVerificationWorkerFailureTest' --tests '*TraceabilityVerificationConcurrencyTest' --rerun-tasks`

Local CI-fix-round-1 result: `BUILD FAILED in 36s`; all `25/25` cases stopped during `PostgresIntegrationTest` initialization of `DockerClientProviderStrategy`. The first failure was `IllegalStateException`; all remaining cases reported `NoClassDefFoundError` caused by the same initialization failure. Production and test sources recompiled successfully, and the failure chain was no longer the OIDC placeholder, but no fixture, Flyway, SQL, transaction, or assertion ran. This is a local Docker environmental blocker, not PostgreSQL GREEN and not a business-logic failure.

Initial exact-head CI failure evidence: Chinese Run `33914382941` / Job `101158044699` / Artifact `9952720393`; English Run `33914386537` / Job `101158060276` / Artifact `9952729037`. Both branches had the same failure shape and neither entered PostgreSQL semantics. The repaired bilingual exact-head CI still requires synchronization, push, and re-execution.

Database-free retry gate:

`./backend/gradlew -p backend test --tests '*RunTraceabilityVerificationRetryTest' --rerun-tasks`

Result: after restoring the correct limit, `BUILD SUCCESSFUL in 25s` and `3/3` passed. The temporary limit-2 mutation explicitly returned `3 tests completed, 2 failed`.

`git diff --check`: passed.

Final diff review strengthened the rollback assertion: after any of the nine boundary failures or bounded-retry exhaustion, the Snapshot Header count and the directly Run-linked `traceability_gap` count must both be zero. After this strengthening, `compileTestKotlin --rerun-tasks` returned `BUILD SUCCESSFUL in 24s` with all 4 tasks executed.

The local `backend/.kotlin` cache produced by this Gradle run was removed and is not part of the commit.

## Self-Review

- Write transaction: all nine result boundaries are inside one `REQUIRES_NEW` Repository call. The V11 deferred trigger validates the complete result at commit; any exception rolls back all result data and terminal states.
- Queries: pinned-input loading uses set queries by exact ID; Edge materialization is one set-based `INSERT ... SELECT`; Issue, Path, and Gap data use bounded JSON recordset batch writes; there is no per-Edge authority query.
- Concurrency: database row/job locks are the sole synchronization authority; there is no `synchronized`, JVM collection lock, or local mutex.
- Recovery: attempts and leases live in PostgreSQL, so a RUNNING Job can be reclaimed after application restart. A terminal Run is not reopened; recovery requires a new Run.
- Security: the Job payload still contains only the Run ID; result-governance payloads contain only IDs, versions, status, and digests; failure summaries contain only allowlisted codes.
- Scope: no V0.1/V0.2 frozen semantics, V11 Migration, Task 3 result, OpenAPI, query/replay, Company configuration, deployment, merge, Tag, or release was changed.

## Commit

Initial implementation Commit: `fc36e90df14a8151bf3b381152b67418cee6beef`; subject: `feat(m2): materialize traceability snapshots`.

Independent-review fix-round-1 Commit: `f2ec0cc92d131e463734194d9976bfb6ed230ee2`; subject: `test(m2): harden traceability worker invariants`.

Recommended CI-fix-round-1 subject: `test(m2): centralize postgres test oidc authority`. The implementation agent will report the immutable Commit ID after committing; a Commit cannot contain its own hash.

## Residual Risks / Handoff

- The repaired Chinese and English exact-head CI must produce a real GREEN result for all 25 PostgreSQL cases. It must specifically confirm that the Context starts with merged shared OIDC properties, as well as controlled `SKIP LOCKED` non-blocking behavior, Release row-lock waiting, target/non-target integrity-error translation, fail-closed damaged input, invalid-terminal rollback, V11 deferred-trigger ordering, nine-boundary rollback, identical-input reuse, and consecutive version allocation for different inputs.
- Worker scheduling is explicitly enabled with `vsrqg.traceability.verification.worker-enabled=true` and remains disabled by default. Task 7 operations guidance must document poll/initial-delay environment configuration and Pilot rollout.
- Task 6 may only read completed Snapshot/Run data; it must not invoke this Worker to recompute or query current Edge authority.
