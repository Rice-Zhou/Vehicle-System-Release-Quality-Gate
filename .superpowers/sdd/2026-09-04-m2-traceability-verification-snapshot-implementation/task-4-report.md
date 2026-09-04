# Task 4 report — Verification Run Creation Transaction

## Status

DONE WITH LOCAL POSTGRESQL EXECUTION BLOCKED. Production and test sources compile, the executable non-PostgreSQL verification set is green, and the PostgreSQL suites are compiled but require exact-head CI or a host with Docker/PostgreSQL.

## RED evidence

The focused command was run before production implementation:

`./backend/gradlew -p backend test --tests '*TraceabilityVerificationStartIntegrationTest' --tests '*TraceabilityVerificationStartFailureTest'`

It failed in `compileTestKotlin` on the deliberately absent Task 4 boundary, including `StartTraceabilityVerification`, `StartTraceabilityVerificationCommand`, and `TraceabilityInputRejected`. This was the permitted first missing-interface RED. The tests specify locked-manifest and latest-snapshot selection, issue-scoped exact revision pinning (including revision entity IDs), untrusted-current-authority rejection, 20/2,000 fail-closed limits, later-revision isolation, idempotency semantics, HTTP scope/visibility behavior, and rollback at all six write boundaries.

## Implementation summary

- Added one `@Transactional` start use case. It checks the feature flag, resolves Project visibility without leaking resource existence, enforces `TRACEABILITY_VERIFY`, and places the existing JDBC idempotency executor inside the same transaction.
- Added one parameterized, set-based PostgreSQL authority query. It locks the Release row, verifies the locked Manifest, selects the latest immutable v1 Issue Snapshot for the requested Issue Source, reads only current typed Edge revisions relevant to Snapshot Issues, and obtains Artifact-to-Release authority only from `artifact_release_edge_v`.
- The query returns at most `max + 1`; the application rejects 21 Issues or 2,001 Edge revisions and never persists a truncated Run. Any current relevant Edge whose status is not `VALID` fails closed with `422` rather than becoming a Gap.
- Pinned input includes numeric revision and authoritative revision entity ID: typed revision table IDs for the three stored Edge types and locked Manifest Revision ID for `ARTIFACT_RELEASE`.
- Added atomic Run, ordered Input Ledger, Audit, Outbox, and one `TRACEABILITY_VERIFY` Background Job creation. The Job payload contains only `verificationRunId`; Run and governance payloads contain allowlisted IDs, versions, counts, status, and digests only.
- Added the approved POST controller, dedicated OAuth scope, idempotency/request-ID behavior, `202` plus `Location`, fixed disabled `503`, input `422`, and RFC 9457 responses. Configuration is disabled by default and bounded at 20 Issues/2,000 revisions.
- Added focused integration and parameterized trigger-based rollback tests, plus the minimum context-test mock required by the new application port.

## Verification evidence

### Executable local GREEN

Fresh command after the final production edit:

`./backend/gradlew -p backend cleanTest test --tests '*M2ApiContractTest' --tests '*TraceabilityVerificationDtoTest' --tests '*TraceabilityCanonicalizerTest' --tests '*TraceabilityVerifierTest' --tests '*ArchitectureTest' --tests '*ApplicationContextTest'`

Result: `BUILD SUCCESSFUL` in 47 seconds; `60/60` tests passed, with zero failures, errors, or skips. This run freshly compiled production and test Kotlin sources.

Contract validator:

`npm run test:contracts`

Result: `PASS contracts schemas=4 positive=12 negative=5 operations=34`.

`git diff --check` produced no findings.

### PostgreSQL execution block

Fresh command:

`./backend/gradlew -p backend test --tests '*TraceabilityVerificationStartIntegrationTest' --tests '*TraceabilityVerificationStartFailureTest' --tests '*SecurityAcceptanceTest'`

Result: `BUILD FAILED`; all `30` selected PostgreSQL-tagged tests stopped during `PostgresIntegrationTest` initialization. The first cause was `IllegalStateException` from `DockerClientProviderStrategy`, followed by the same initializer's `NoClassDefFoundError`. The host exposes no Docker, Podman, `psql`, or `pg_isready`, so no Spring context, fixture, SQL, transaction, or semantic assertion executed. This is an environment block, not a passing database result; exact-head CI must run the compiled suites before acceptance.

## Self-review

- Authority: one PostgreSQL source; no Jira, GitHub, CI, Device, JSON/file/cache fallback, second Artifact-to-Release table, or dynamic external query.
- Transaction: idempotency, Run, every ledger row, Audit, Outbox, and Job remain under one Spring transaction; failures are not caught and converted to `202`.
- Query shape: one set-based authority query and one set-based ledger insert; no per-Issue/per-Edge N+1. All request-derived SQL values are bound parameters.
- Payload: Job body is Run ID only. No issue title, source reference, proof URL, credential, repository, raw provider payload, exception, SQL, or stack trace is persisted in Task 4 governance metadata.
- Error behavior: resource invisibility is enumeration-safe; disabled is fixed `503`; unlocked Manifest is `409`; untrusted or oversized fixed authority is `422`; no silent fallback, truncation, or broad catch exists.
- Scope: no Task 5 worker/materialization, Task 6 query/replay, broker, cache, service split, UI, migration, deployment, or ledger modification was introduced.

## Files and scope

Created the seven Task 4 implementation/test files. Modified only `backend/src/main/resources/application.yml` and `backend/src/test/kotlin/com/ricezhou/vsrqg/ApplicationContextTest.kt` as allowed by the brief. This report is the only SDD artifact added. No governance ledger was edited.

## Spec conflict assessment

No conflict with the approved M2.5 design, V0.1 frozen architecture, Task 1 DTO/API contract, Task 2 V11 authority, or Task 3 canonicalizer was found. No ADR/TDR revision is required by this task.

## Commit

Subject: `feat(m2): queue pinned traceability verification`

The immutable commit ID is reported by the implementing agent after the commit is created; a commit cannot contain its own hash.

## Remaining risk / handoff

- Exact-head CI must execute the three PostgreSQL suites above and retain their reports before this Task can be used as acceptance evidence.
- Task 5 must consume only the persisted Run and Input Ledger. It must not reread latest revision authority or copy the authority query into the Worker.
- Task 6 must replay stored Run/Snapshot results and must not recompute from current source tables.

## Review fix round 1

### Finding verdict

1. **Important 1 — ADDRESSED.** The PostgreSQL authority query now selects the absolute latest immutable Issue Snapshot for the Release/Issue Source without pre-filtering its canonicalization version. The selected version is returned as authority metadata and the application rejects anything except `release-issue-snapshot-jcs/v1` with fixed `422 TRACEABILITY_INPUT_NOT_VALID`. It cannot fall back to an older supported snapshot. A PostgreSQL regression creates an older v1 snapshot and a newer unsupported snapshot; a local application-level regression independently proves rejection occurs before canonical digest or persistence.
2. **Important 2 — ADDRESSED.** Resource, transient, timeout, and transaction-creation database failures on the verification POST are routed through the existing shared `ProblemHandler` authority and return redacted `503 PERSISTENCE_UNAVAILABLE`. No controller-local database handler or parallel error taxonomy was added. The endpoint-specific Advice now has explicit precedence only for its fixed traceability business errors; a real default-disabled HTTP regression proves `TRACEABILITY_VERIFICATION_UNAVAILABLE` remains a fixed 503.
3. **Important 3 — ADDRESSED.** The parameterized write-failure matrix now has a seventh boundary, `IDEMPOTENCY_RESPONSE`, which installs a trigger on the final `idempotency_record` success UPDATE. Because that UPDATE occurs only after Run, ledger, Audit, Outbox, and Job writes, the test proves its failure rolls back every artifact including the pending idempotency record.
4. **Minor findings — ADDRESSED.** Outbox rollback counts are scoped to the fixture Release ID in allowlisted event payload rather than the global `trv_%` namespace. Controller `releaseId` validation is now 1..128, matching OpenAPI `OpaqueId` and existing Release/Manifest controllers; an HTTP boundary test accepts 128 and rejects 129. The feature-disabled test uses the real application policy rather than a mocked exception.

### TDD evidence

- Persistence HTTP RED: expected `503`, received `500 INTERNAL_ERROR` from the shared catch-all.
- Release ID RED: a 128-character ID permitted by OpenAPI expected `202`, received `400 INVALID_REQUEST`.
- Disabled feature RED: the real default-disabled application POST expected `503`, received `500 INTERNAL_ERROR` because the global Advice catch-all preceded the controller-specific handler.
- Snapshot authority interface RED: `compileTestKotlin` failed on the deliberately absent `issueSnapshotCanonicalizationVersion` authority field.
- Snapshot authority behavioral RED after adding only the field: the use case reached the canonicalizer and failed with a null-result `NullPointerException` instead of the expected `TraceabilityInputRejected`, proving no version validation existed.
- The PostgreSQL latest-snapshot and seventh rollback-boundary regressions compile but cannot produce local behavioral RED/GREEN because this host has no Docker/PostgreSQL runtime.

### Verification

Fresh non-PostgreSQL command:

`./backend/gradlew -p backend cleanTest test --tests '*TraceabilityVerificationStartHttpTest' --tests '*TraceabilityVerificationAuthorityValidationTest' --tests '*ApplicationContextTest' --tests '*ArchitectureTest' --tests '*M2ApiContractTest' --tests '*TraceabilityVerificationDtoTest' --tests '*TraceabilityCanonicalizerTest' --tests '*TraceabilityVerifierTest' --tests '*BuildProvenanceTransactionStructureTest'`

Result: `BUILD SUCCESSFUL`; `67/67` tests passed, zero failures/errors/skips. This includes the existing Build Provenance persistence taxonomy regression, so extending the shared path classifier did not change its fixed 503 behavior.

Contract validator remains `PASS contracts schemas=4 positive=12 negative=5 operations=34`. `git diff --check` has no findings.

PostgreSQL command:

`./backend/gradlew -p backend test --tests '*TraceabilityVerificationStartIntegrationTest' --tests '*TraceabilityVerificationStartFailureTest'`

Result: all `18` selected tests stopped at the same Testcontainers `DockerClientProviderStrategy` initialization failure. The selected set includes the unsupported-latest regression and all seven write boundaries. No fixture, SQL, transaction, or assertion executed, so exact-head CI remains mandatory.

### Scope and architecture

- The shared change is limited to recognizing the approved verification POST inside the existing persistence-unavailable authority. Traceability business errors remain inside the traceability Adapter; Shared does not depend on the traceability module, and Architecture tests remain green.
- No external source, fallback, new error source, migration, Worker, query/replay implementation, ledger edit, push, merge, tag, release, or deployment was added.

### Fix commit

Subject: `fix(m2): close traceability start review findings`

The immutable commit ID is reported after creation because a commit cannot contain its own hash.

## Exact-head CI pool-budget fix round 2

### Root-cause evidence and single hypothesis

- Exact-head CI JUnit XML shows that both new PostgreSQL test classes received `SQLSTATE 53300 FATAL: too many clients already` during Flyway context initialization. Every later failure was a Spring context failure-threshold cascade, not a migration, transaction, or business assertion failure.
- Bytecode inspection of the local HikariCP 6.3.3 dependency confirms that an unspecified `maximumPoolSize` defaults to 10, while `minimumIdle` starts at -1 and is normalized to `maximumPoolSize` during validation. Each default test pool can therefore retain up to 10 connections.
- `TraceabilityVerificationStartIntegrationTest` uses `@AutoConfigureMockMvc`, while `TraceabilityVerificationStartFailureTest` does not, so they form separate cached Spring Boot contexts. Neither previously declared a local Hikari budget, and their pools pushed the exact-head run beyond the database client limit after earlier PostgreSQL test contexts had opened connections.
- Neither class contains threads or a concurrent executor, and Gradle/JUnit does not enable parallel test execution. Fixture work, MockMvc/use-case calls, failure injection, assertions, and cleanup execute sequentially. The actual path for one context therefore needs one active connection; `maximumPoolSize=2` preserves limited headroom, and `minimumIdle=0` avoids reserving idle connections.

### Fix

- Set `spring.datasource.hikari.maximum-pool-size=2` and `spring.datasource.hikari.minimum-idle=0` only in the `@TestPropertySource` of those two test classes.
- Production `application.yml`, shared `PostgresIntegrationTest`, and every other test context remain unchanged. No PostgreSQL test or assertion was reduced or skipped.
- Add a database-free regression that reads the merged `@TestPropertySource` from both real test classes, binds it to `HikariConfig` through Spring Binder, and asserts max=2 and minIdle=0 for each. Removing either context's local property makes this test fail.

### TDD evidence

- RED command: `./backend/gradlew -p backend test --tests '*TraceabilityVerificationStartPoolBudgetTest'`.
- RED result: the test failed because `TraceabilityVerificationStartIntegrationTest maximum pool size` expected 2 but received the default 10, directly proving that the new context had no local pool budget.
- After adding the four local test properties, the same command returned `BUILD SUCCESSFUL` with `1/1` test passing.

### Verification

Non-PostgreSQL verification command:

`./backend/gradlew -p backend cleanTest test --tests '*TraceabilityVerificationStartPoolBudgetTest' --tests '*TraceabilityVerificationStartHttpTest' --tests '*TraceabilityVerificationAuthorityValidationTest' --tests '*ApplicationContextTest' --tests '*ArchitectureTest' --tests '*M2ApiContractTest' --tests '*TraceabilityVerificationDtoTest' --tests '*TraceabilityCanonicalizerTest' --tests '*TraceabilityVerifierTest' --tests '*BuildProvenanceTransactionStructureTest'`

Result: `BUILD SUCCESSFUL` in 51 seconds; `68/68` tests passed with zero failures, errors, or skips. The contract validator remains `PASS contracts schemas=4 positive=12 negative=5 operations=34`.

PostgreSQL compile/execution command:

`./backend/gradlew -p backend test --tests '*TraceabilityVerificationStartIntegrationTest' --tests '*TraceabilityVerificationStartFailureTest'`

Result: production and test sources compiled, but all `18` selected PostgreSQL tests still stopped at the local host's Testcontainers `DockerClientProviderStrategy` initialization. No fixture, Flyway, SQL, transaction, or assertion ran. The local result therefore cannot prove that exact-head `SQLSTATE 53300` is gone; exact-head CI must still execute both classes after the fix.

### Scope

- Changes are limited to the two new PostgreSQL test contexts, one database-free configuration regression, and this report.
- No production pool, production code, schema/migration, business behavior, test assertion, governance Ledger, CI workflow, push, merge, tag, release, or deployment changed.

### Fix commit

Subject: `test(m2): bound verification test pool budgets`

The immutable commit ID is reported after creation because a commit cannot contain its own hash.

## Exact-head CI connection-resource structural fix (second fix attempt)

### Disproved local hypothesis

- After fix commit `b0b552f6d8a931a744ad07e0b1d43b17252da8a3`, both new test classes in exact-head CI artifact `9948147626` still received the same `SQLSTATE 53300 FATAL: too many clients already` during Flyway context initialization. This directly disproved the local hypothesis that constraining only the two new contexts would restore an available connection.
- This was the first failed implementation fix for the same connection-resource problem, making this section the second fix attempt and still below the systematic-debugging stop threshold of three failures. Because the local solution failed, this round performed a structural architecture review instead of merely reducing the local pool number again.

### Complete context-resource evidence

- The repository has 23 subclasses of `PostgresIntegrationTest`. A temporary database-free Spring TestContext bootstrap probe used the real `BootstrapUtils` to calculate 12 unique `MergedContextConfiguration` values. The probe was removed after investigation and was not committed.
- The local Spring TestContext cache dependency defaults to 32 entries. The repository has no `@DirtiesContext`, and Gradle/JUnit does not enable parallel execution. All 12 contexts may therefore stay cached in one full-suite JVM, retaining their DataSources until cache eviction or JVM shutdown.
- The first 10 existing unique contexts had no test-only Hikari configuration. HikariCP 6.3.3 defaults `maximumPoolSize` to 10, and an unspecified `minimumIdle=-1` is normalized to the maximum during validation, reserving 10 connections per context. The first 10 contexts alone can accumulate 100 connections, so the later two contexts locally limited to max=2/minIdle=0 may still be unable to obtain even their first Flyway connection.

### Concurrency demand and unified budget

- A global search for `runConcurrently`, `Executors`, `CountDownLatch`, `CyclicBarrier`, and parallel test settings found a maximum database-test worker fan-out of two.
- `BuildProvenanceMigrationTest`, `M2MigrationConstraintTest`, and `TraceabilityVerificationMigrationTest` hold two test connections while the main test thread observes PostgreSQL lock state through `JdbcClient`, proving a peak need of three active connections. A shared max of two would break these real concurrency tests.
- Shared `maximumPoolSize=3` exactly covers the proven peak. `minimumIdle=0` prevents cached contexts from reserving idle connections. Across the current 12 unique contexts, the theoretical maximum falls from 120 to 36.

### Option comparison and architecture review

1. **Shared base-class budget—selected.** `PostgresIntegrationTest` is the single common boundary for every shared PostgreSQL context. Defining max=3/minIdle=0 there creates one inheritable, verifiable test-resource invariant while retaining the Spring context cache.
2. **`@DirtiesContext`—not selected.** It could close a context after each class, but would defeat caching and force 23 subclasses to recreate the Spring context and rerun Flyway, increasing runtime and lifecycle noise without expressing a connection budget.
3. **Increase container `max_connections`—not selected.** It only enlarges the leak budget, does not prevent future contexts from reserving default pools, and hides missing test-harness resource ownership.

This fix governs test-harness resources only. It does not change V0.1/V0.2 production architecture, transactions, schema, business behavior, or production container settings, and requires no ADR/TDR.

### TDD and mutation evidence

- RED command: `./backend/gradlew -p backend test --tests '*PostgresIntegrationPoolBudgetTest'`.
- RED result: `1/1` failed because `shared PostgreSQL test pool authority` was null, proving the shared base class had no pool budget.
- The same test became GREEN after adding max=3/minIdle=0 to the shared base class.
- A second test then required every subclass context's merged configuration to retain the shared budget. Temporarily restoring local max=2 on `TraceabilityVerificationStartIntegrationTest` made the mutation run fail precisely: effective maximum pool size expected `"3"` but was `"2"`. Removing the mutation restored `2/2` GREEN.

### Implementation

- Centrally define `spring.datasource.hikari.maximum-pool-size=3` and `spring.datasource.hikari.minimum-idle=0` in `PostgresIntegrationTest` through `@TestPropertySource`.
- Remove the local max=2/minIdle=0 properties from the two Traceability Verification test classes and remove the old regression limited to those classes.
- Add `PostgresIntegrationPoolBudgetTest`: the first test validates shared authority through Spring Binder; the second uses ArchUnit to discover every subclass and Spring `MergedContextConfiguration` to verify that each context effectively retains max=3/minIdle=0, preventing future local override drift.

### Verification

Non-PostgreSQL verification command:

`./backend/gradlew -p backend cleanTest test --tests '*PostgresIntegrationPoolBudgetTest' --tests '*TraceabilityVerificationStartHttpTest' --tests '*TraceabilityVerificationAuthorityValidationTest' --tests '*ApplicationContextTest' --tests '*ArchitectureTest' --tests '*M2ApiContractTest' --tests '*TraceabilityVerificationDtoTest' --tests '*TraceabilityCanonicalizerTest' --tests '*TraceabilityVerifierTest' --tests '*BuildProvenanceTransactionStructureTest'`

Result: `BUILD SUCCESSFUL` in 52 seconds; `69/69` tests passed with zero failures, errors, or skips. The contract validator remains `PASS contracts schemas=4 positive=12 negative=5 operations=34`.

PostgreSQL compile/execution command:

`./backend/gradlew -p backend test --tests '*TraceabilityVerificationStartIntegrationTest' --tests '*TraceabilityVerificationStartFailureTest'`

Result: production and test sources compiled, but all `18` selected tests still stopped at the local host's Testcontainers `DockerClientProviderStrategy` initialization. No fixture, Flyway, SQL, transaction, or assertion ran; only the next exact-head full-suite CI can verify that `SQLSTATE 53300` is gone.

### Scope

- Changes are limited to the shared PostgreSQL test base class, one shared pool-budget regression, removal of the two local configurations and old local regression, and this report.
- No production DataSource, production code, schema/migration, container `max_connections`, CI workflow, or governance Ledger changed. No existing PostgreSQL/business test was removed or skipped and no business assertion was weakened. No push, merge, tag, release, or deployment occurred.

### Fix commit

Subject: `test(m2): enforce shared postgres pool budget`

The immutable commit ID is reported after creation because a commit cannot contain its own hash.
