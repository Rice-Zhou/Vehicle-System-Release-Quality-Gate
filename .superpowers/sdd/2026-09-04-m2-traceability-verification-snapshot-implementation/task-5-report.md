# Task 5 Report — Worker Materialization, Recovery, and Concurrency

## Status

Implementation and local static/non-PostgreSQL verification are complete. All 17 Task 5 PostgreSQL behavioral tests compiled successfully, but this machine has no Docker/Testcontainers runtime. Every test stopped during container initialization, before any fixture, SQL, transaction, or business assertion ran. An exact-head CI PostgreSQL GREEN result therefore remains a mandatory acceptance condition.

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

### Atomic Materialization and Reuse

- The result transaction first locks the claimed Run/Job and searches by input/result digest for a successfully completed Snapshot with compatible input identity.
- On a miss, it locks `release_record`, checks reuse again, then allocates the next Release-scoped version as `max(version)+1`; no JVM lock is used. Only SQLSTATE `23505` for the exact `uq_trace_snapshot_release_version` constraint retries the complete transaction, up to three attempts. Other SQL errors are not swallowed as version contention.
- Snapshot Edges are projected set-wise from the pinned Input Ledger and retain Ledger ordinals. Path Edges reference only those Snapshot Edges. Domain `TEST_RESULT_EVIDENCE` is mapped to the existing database token `TEST_EVIDENCE` at the single persistence boundary.
- Run Gaps and Snapshot Gaps are written with the same stable fields and digest. Audit is fixed as `TRACEABILITY_VERIFICATION_SUCCEEDED`, Outbox as `traceability.verification.succeeded`, and the Run/Job are updated to `SUCCEEDED` only at the end.
- The system preserves separate Runs for identical inputs submitted under different Idempotency Keys while reusing one content-identical Snapshot. Concurrent identical inputs also converge on one Snapshot after the second reuse check under the Release row lock. Concurrent different inputs receive consecutive versions.

## Test and Failure-Injection Matrix

The three test files contain 17 PostgreSQL cases covering:

1. Complete-chain materialization with Fixed=true, Included=true, Verified=false, a four-segment primary path, and `TEST_RESULT_EVIDENCE_MISSING`.
2. An INVALID Revision appended after the request does not change the pinned Revision result.
3. Sequential identical input under different Runs reuses one Snapshot.
4. Two Workers claim one Job only once.
5. Concurrent identical inputs converge on one Snapshot.
6. Concurrent different inputs under one Release receive consecutive versions 1 and 2.
7. A crashed RUNNING Job cannot be claimed before the lease boundary, can be reclaimed at 300 seconds, and increments its attempt.
8. Three failures produce Run FAILED and Job DEAD_LETTER while keeping diagnostics redacted.
9. The nine parameterized result-write boundaries: Snapshot Header, Issue Result, Snapshot Edge, Path Edge, Gap, Audit, Outbox, Run terminal, and Job terminal. Failure injected at each boundary requires zero Snapshots, no result pointer on the Run, zero successful Audit/Outbox records, and only a safe Job retry.

Mutation intent: removing `SKIP LOCKED`, removing the lease condition, changing the attempt limit to 4, removing the Release row lock or second reuse query, reading the latest Revision, moving any terminal write outside the result transaction, or allowing any of the nine boundaries to commit a partial result would fail the corresponding behavioral test. Because this machine has no PostgreSQL runtime, these mutations must be executed by exact-head CI; test design is not reported as runtime evidence.

## Local Verification Evidence

Compilation:

`./backend/gradlew -p backend compileKotlin compileTestKotlin`

Result: `BUILD SUCCESSFUL`; production and test Kotlin both compiled.

Fresh non-PostgreSQL gate:

`./backend/gradlew -p backend cleanTest test --tests '*TraceabilityVerifierTest' --tests '*TraceabilityCanonicalizerTest' --tests '*ArchitectureTest' --tests '*ApplicationContextTest' --tests '*PostgresIntegrationPoolBudgetTest' --tests '*M2ApiContractTest' --tests '*TraceabilityVerificationDtoTest' compileKotlin compileTestKotlin --rerun-tasks`

Final fresh result: `BUILD SUCCESSFUL in 1m 14s`; `63/63` passed with zero failures, errors, or skips, and all 7 Gradle tasks executed.

Contract gate:

`npm run test:contracts`

Result: `PASS contracts schemas=4 positive=12 negative=5 operations=34`.

Focused PostgreSQL command:

`./backend/gradlew -p backend test --tests '*TraceabilityVerificationWorkerIntegrationTest' --tests '*TraceabilityVerificationWorkerFailureTest' --tests '*TraceabilityVerificationConcurrencyTest'`

Result: `BUILD FAILED`; all `17/17` cases stopped during `PostgresIntegrationTest` initialization of `DockerClientProviderStrategy`. The first failure was `IllegalStateException`; all remaining cases reported `NoClassDefFoundError` caused by the same initialization failure. Production and test compilation were up-to-date, and no fixture, Flyway, SQL, transaction, or assertion ran. This is an environmental blocker, not PostgreSQL GREEN and not a business-logic failure.

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

Recommended subject: `feat(m2): materialize traceability snapshots`

The implementation agent will report the immutable Commit ID after committing; a Commit cannot contain its own hash.

## Residual Risks / Handoff

- Exact-head CI must produce a real GREEN result for all 17 PostgreSQL cases, especially V11 deferred-trigger ordering, nine-boundary rollback, two-Worker claim behavior, concurrent identical-input reuse, and consecutive version allocation for different inputs.
- Worker scheduling is explicitly enabled with `vsrqg.traceability.verification.worker-enabled=true` and remains disabled by default. Task 7 operations guidance must document poll/initial-delay environment configuration and Pilot rollout.
- Task 6 may only read completed Snapshot/Run data; it must not invoke this Worker to recompute or query current Edge authority.
