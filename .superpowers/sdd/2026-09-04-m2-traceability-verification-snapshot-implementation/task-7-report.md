# Task 7 Report — Performance, Recovery, Candidate Gate, and Owner Record

## Status

The candidate Gate, performance/recovery tests, read-only CI, and Pilot operations guidance are implemented. The Owner acceptance record must be created after the candidate Gate commit, reference its real SHA, and remain `PENDING`. Review fix round 1 replaced the recovery drill with a real PostgreSQL dump, independent restore, and container restart, and tightened the Evidence boundary to a recursive exact-property allowlist. Round 2 additionally uses PostgreSQL's own postmaster start time to prove that the database process actually changed and adds nested cleanup that preserves the primary failure. This machine has no usable Docker/Testcontainers runtime, so the PostgreSQL performance and recovery cases have only compiled; the real fixture, SQL, transaction, performance samples, and recovery assertions have not run. Exact-head Linux/Docker CI is mandatory acceptance evidence.

## Boundaries and related files

- Production code, the V11 Migration, Core Contract, Fixed/Included/Verified semantics, Manifest authority, Issue Snapshot authority, and M2.4 Edge Revision authority are unchanged.
- `.github/workflows/m2-backend.yml` did not exist when Task 7 began. Although the plan listed it as Modify, it was created following the read-only convention in `m1-backend.yml`. The workflow has only `contents: read`; it has no Jira/GitHub Provider credential, Company call, or external write permission.
- The Gate's fixed `replay` stage runs historical replay and the recovery suite in sequence. This preserves the approved names and order of the 12 top-level checks without omitting the independent recovery test.
- The plan requires the first candidate commit to exclude the Owner record. Therefore, the first commit verifies only Gate orchestration, compilation, Contract, and existing Acceptance; the second commit creates the PENDING record and provides the input required by the complete M2.5 Gate.

## TDD RED / GREEN

1. `scripts/tests/m2-5-verify-gates.tests.ps1` was created first. Its initial exact failure was `Missing M2.5 verification gate`, exit `1`.
2. After the minimum `verify-m25.ps1` implementation, the first orchestration test exposed that the recovery suite was not called by the top-level replay stage, leaving `recovery=null` in Evidence. Binding recovery as the replay stage's second command made the same test GREEN.
3. After adding the performance/recovery tests, the first `compileTestKotlin` failed because the query-count wrapper had not retained its delegate as a property. Changing it to `private val delegate` made compilation GREEN. This was a test-wiring failure, not a production-behavior RED.
4. Focused PostgreSQL execution failed exactly at `DockerClientProviderStrategy`. Neither test entered the Spring/PostgreSQL fixture, SQL, or business assertions, so the environment block was not reported as PASS.
5. Review fix round 1 first made the recovery test reference the not-yet-existing `restoreSnapshotAndRestartDatabase`; `compileTestKotlin` was RED as expected on the unresolved reference. The same compilation became GREEN after implementing the real dump/restore/restart boundary.
6. Adding an extra synthetic token field to the performance child report showed the original Gate RED because it did not produce the fixed `EVIDENCE_INVALID` diagnostic. The Gate test became GREEN after adding raw JSON scanning, an exact-property allowlist at every level, explicit reconstruction, and a final total-JSON scan. Windows and Unix absolute-path mutations must also fail closed and delete both potentially uploadable child reports.
7. `VSRQG_M25_STUB_FAIL_PATTERN=TraceabilityVerificationConcurrencyTest` makes the real Gradle stub child exit `23`. The Gate preserves that first exit code, continues through acceptance, and emits all 12 checks, total `FAILED`, Evidence, and the digest.
8. Review fix round 2 first made the restart test reference not-yet-existing PostgreSQL process-identity helpers, producing a `compileTestKotlin` RED on the unresolved references. Compilation became GREEN after implementation. The assertion compares `pg_postmaster_start_time()` before and after restart and requires the latter to be strictly later, so merely creating a new Worker, DataSource, or connection without restarting the database fails.

Gate orchestration uses mutations and fixtures to verify that later checks still run after an injected transaction failure and the total status remains `FAILED`; a dirty tree yields `WORKTREE_DIRTY`; a mismatch between the CI SHA and HEAD yields `EXACT_HEAD_MISMATCH`; child output is not echoed; extra secrets and Windows/Unix absolute paths in child reports fail deterministically and are removed from the upload set; both failed and successful executions generate `m2-5-evidence.json` and its SHA-256 sidecar; and the explicit allowlist protects the 20/2,000 fixture, recovery/replay, and Owner `PENDING` assertions.

## Performance design

- The real PostgreSQL fixture is fixed at 20 Issues and exactly 2,000 fixed Edges. The test directly requires an Input Ledger count of 2,000 after Start and a Snapshot Edge count of 2,000 after Worker execution; truncating or shrinking the fixture is prohibited.
- Three independent Idempotency-Keys measure start, worker, and query against the same fixed input. Evidence records sample count, p50/p95/max, processor count, JVM maximum memory, and Java/OS runtime metadata.
- The reference P95 targets for start/worker/query are `≤1s/≤10s/≤1s`. The reproducible shared-CI hard limits are `30s/60s/30s`; they only detect algorithmic regression and do not claim Company performance acceptance.
- Query uses a counting decorator around the real Repository and fixes release/header/issues/paths/gaps at one call each for 20 Issues. Any per-Issue or per-Edge query changes the count and fails.

## Recovery design

- The shared test PostgreSQL performs only a custom-format `pg_dump --no-owner --no-privileges`. The dump is restored into an independent `postgres:17.11` container with `pg_restore --exit-on-error --no-owner --no-privileges`. From the restored database, fresh `DataSource`, `JdbcClient`, and `JdbcTraceabilityVerificationRepository` instances load the fixed input and Snapshot relational facts. The test calls only the single `TraceabilityVerifier`/canonicalizer to recompute the digest and compares the producer, issue result, path, gap, and `content_digest` facts individually. It does not read the latest Revision or any external system.
- The restart scenario retains the RUNNING Job persisted before the crash in that independent restored container. New connections query `pg_postmaster_start_time()`, `pg_backend_pid()`, and `SELECT 1` both before and after restart, and the postmaster start time must be strictly later to prove a real database-process boundary. The test then rebuilds the DataSource/repository/transaction manager and reclaims after the lease; attempt must increase from 1 to 2. The shared Testcontainer is never restarted or stopped.
- The source-container dump, independent restore container, and host dump use nested `finally` cleanup semantics. A nonzero `pg_dump` still triggers deletion of a possible partial container dump, and a container-stop failure still permits host-dump deletion. A cleanup failure is attached as suppressed, neither replacing the first test failure nor being swallowed as a false success.
- A poison trigger fails three consecutive attempts, leaving the Run `FAILED` and the Job `DEAD_LETTER`. After removing the cause, a new Idempotency-Key creates and completes a new Run; rereading confirms that the old Run and Job terminal states remain unchanged.

## Gate and Evidence

Fixed top-level order:

1. clean-tree
2. fixed-commit
3. contract
4. migration
5. domain
6. transaction
7. concurrency
8. replay (including recovery)
9. performance
10. secret
11. acceptance
12. evidence-digest

A failure does not stop later top-level checks. The Gate does not echo child stdout/stderr; it outputs only the commit, fixed check/status/tests/diagnostic fields, summary, and failed checks. Performance and recovery reports are scanned as raw JSON, rebuilt and overwritten through an exact-property allowlist at every level, and then scanned again as part of the final total JSON. Any secret or absolute path sets the fixed `EVIDENCE_INVALID` failure and deletes the affected child reports. Evidence outputs are:

- `backend/build/m2/m2-5-evidence.json`
- `backend/build/m2/m2-5-evidence.json.sha256`
- `backend/build/m2/traceability-performance.json`
- `backend/build/m2/traceability-recovery.json`

## Local validation

- `pwsh -NoProfile -File scripts/tests/m2-5-verify-gates.tests.ps1`: `PASS m2-5-verify-gates`.
- `./backend/gradlew.bat -p backend compileTestKotlin --no-daemon`: Review fix round 2 source `BUILD SUCCESSFUL`.
- `npm run test:contracts`: `PASS contracts schemas=4 positive=12 negative=5 operations=34`.
- `npm run verify:acceptance`: `PASS acceptance-records`.
- `./backend/gradlew.bat -p backend cleanTest test --tests '*TraceabilityVerificationPerformanceTest' --tests '*TraceabilityVerificationRecoveryTest' --rerun-tasks`: `BUILD FAILED`; both cases failed while initializing Docker/Testcontainers and did not execute PostgreSQL semantics.

## Commit

- Candidate Gate Subject: `test(m2): add traceability verification candidate gate`. This report is in that same commit, so it does not embed its own commit SHA. External handoff must use the real value from `git rev-parse HEAD`.
- The Owner record uses the candidate Gate's real SHA and is committed separately with the fixed Subject `docs(m2): add traceability owner gate candidate`.

## Residual risks

- Exact-head Linux/Docker CI must generate the real 20/2,000 performance and recovery Evidence and verify hard limits, query count, digest restore, RUNNING reclaim, poison dead-letter behavior, and the new-Run retry.
- The shared-CI hard limits are not a Company performance commitment. No fixed reference environment exists yet, and the Owner record must remain `PENDING`.
- Evidence Artifacts are retained for 30 days. A later Owner review or archive must bind the exact commit, Run, Artifact ID, and digest.
