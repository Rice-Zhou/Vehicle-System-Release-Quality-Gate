# Task 6 Report — Read API, Security, and Replay

## Status

Task 6 production implementation, TDD, non-PostgreSQL gates, and contract validation are complete. The real PostgreSQL Query, Replay, and Security tests all compile, but local Testcontainers fails while initializing `DockerClientProviderStrategy`; therefore, no database fixture, SQL, or business assertion ran. These cases must not be recorded as PASS and must be completed by Linux/Docker CI bound to the exact HEAD of this commit.

## Goals and boundaries

- Provide `GET /api/v1/traceability-verification-runs/{verificationRunId}` and return the persisted Run status by public `verification_run_id`.
- Provide `GET /api/v1/releases/{releaseId}/traceability`, returning the latest successful Snapshot by default or an exact historical Snapshot selected by `snapshotId`.
- Both endpoints require the `traceability:read` scope. The Application layer validates membership against the Project owning the Run/Release; unknown, cross-Project, and invisible targets uniformly return enumeration-safe `404 RESOURCE_NOT_FOUND`.
- Queries read only the immutable Snapshot and producer Run completed by Task 5. They do not read source Revision tables, `artifact_release_edge_v`, latest Revision, Jira, GitHub, CI, Device, or Adapter, and they do not recompute Fixed/Included/Path/Gap/digest.
- This task does not change the frozen V0.1 architecture, V11 Schema, OpenAPI, progress ledger, Company, deployment, or release boundaries.

## Plan-related changes

The plan explicitly lists only the Application query, Controller, and test files, but Task 6 Step 2 requires set-based Repository read methods. The existing Task 5 Repository had no read interface. Using JDBC directly in the Application or Controller would create a second persistence authority. The existing `TraceabilityVerificationRepository` and `JdbcTraceabilityVerificationRepository` therefore received the smallest necessary extension: six read categories for Run, Release Project, Snapshot Header, Issue Result, Path Edge, and Gap, while preserving a single Repository authority.

Adding `GetTraceabilityVerification` to the Controller constructor required only the necessary interface additions to two existing test fakes/mocks and did not change their behavior.

## TDD RED / GREEN evidence

1. Run query RED: created the integration test first and invoked the missing `GetTraceabilityVerification`; `compileTestKotlin` failed with an unresolved class/method. Compilation became GREEN after implementing the minimum Run DTO, Project authorization, and Repository query.
2. Snapshot query RED: added the `getSnapshot` call first; `compileTestKotlin` failed because the method did not exist. It became GREEN after implementing fixed batch reads for header/issues/path/gap and in-memory assembly by ordinal.
3. HTTP RED: wrote strict DTO/serialization tests for both GET endpoints first; the Controller had no mappings and returned `404`, failing the assertions. The tests became GREEN after wiring the Controller, read scope, and Task 1 DTO adapter.
4. N+1 mutation: the baseline used 20 Issues and verified a fixed read count. The Path read was then temporarily changed to one call per Issue, and the test failed explicitly with `paths=20` instead of the expected `1`. Restoring one set-based read returned the test to GREEN.
5. Problem Details RED: when simulating `DataAccessResourceFailureException`, the test initially threw `ServletException`. The complete stack/log proved that the business exception reached the Advice, but the mocked `IdGenerator` returned `null`, causing `RequestIdFilter` to remove the request attribute; ProblemWriter then failed a second time because the Request ID was absent. Fixing only the test fixture to return `req_traceability_query` made the same HTTP suite `2/2` GREEN; no fallback or swallowed exception concealed the root cause.

## Implementation and SQL authority

### Run GET

- JDBC queries the V11 fixed-input Run by the public, unique `verification_run_id`. Diff review found that the first draft mistakenly projected internal `id` as the response ID; it now projects and returns public `verification_run_id`.
- The Application obtains the Run `project_id` and then calls the single `ProjectAuthorizer` to require `TRACEABILITY_READ`; authorization failure and absence both throw the same `ResourceNotFound`.
- Status is restricted to `QUEUED/RUNNING/SUCCEEDED/FAILED`. Diagnostics are restricted to `TRACEABILITY_INPUT_NOT_VALID` and `TRACEABILITY_VERIFICATION_RETRY_EXHAUSTED`; any other persisted value is folded into `TRACEABILITY_VERIFICATION_FAILED`. Responses do not include a raw reason, SQL, URL, or source-system field.

### Snapshot GET

- Release Project lookup: 1 call; Snapshot reading starts only after authorization succeeds.
- Header: 1 call. Exact mode selects by `(release_id, snapshot_id)`; default mode uses `version DESC, id DESC LIMIT 1`. Both join the producer Run and require producer `status='SUCCEEDED'` plus `result_snapshot_id=snapshot.id`, so QUEUED/RUNNING/FAILED Runs cannot masquerade as results.
- Header identity comes from the immutable Snapshot and producer fixed-input Run. `manifestDigest` is read only from the current Snapshot's materialized `ARTIFACT_RELEASE` Edge and does not query `manifest_revision`.
- Issue Result: 1 call ordered by persisted `ordinal`; Path Edge: 1 call ordered by `issue_ordinal,path_ordinal`; Gap: 1 call ordered by `issue_ordinal,gap.ordinal`. The Application only groups them by persisted Issue ordinal.
- Therefore, regardless of Issue count, a complete Snapshot uses a fixed 5 reads (Release, Header, Issues, Paths, Gaps), with header/issues/path/gap each read exactly once; there is no per-Issue query.

## Security and error semantics

- Both GET endpoints use `@PreAuthorize("hasAuthority('SCOPE_traceability:read')")`; absence of the dedicated scope returns `403 ACCESS_DENIED`.
- Run membership is decided from the Run `project_id`. Snapshot membership is first decided from the Release `project_id`, then the Snapshot `project_id` is also required to match the Release; mismatch fails closed.
- SecurityAcceptance adds dedicated-scope 403 coverage and same-shape 404 coverage for an unknown Run and a cross-Project Release.
- Controller Advice returns fixed `503 PERSISTENCE_UNAVAILABLE` and a fixed GET detail for four retryable persistence failures during reads; JDBC URL/host/database values from the exception never enter the response. Existing POST idempotent retry semantics remain unchanged.

## Replay evidence design

`TraceabilityReplayTest` first completes a Snapshot and saves the serialized bytes and `contentDigest` returned by an exact `snapshotId` query. It then inserts an M2.4 `revision+1` (INVALID) and a new Issue Snapshot, reads the old Snapshot again, and requires byte-for-byte identical output and the same digest. The read path has no current/latest authority query, so future facts can affect only a new Run/Snapshot and cannot rewrite a historical response.

The real Replay behavior still requires Docker CI. This report confirms only that the test source compiles and does not misreport design or compilation as PostgreSQL PASS.

## Local validation

Final non-PostgreSQL gate:

`./backend/gradlew -p backend test --tests 'com.ricezhou.vsrqg.ApplicationContextTest' --tests 'com.ricezhou.vsrqg.ArchitectureTest' --tests 'com.ricezhou.vsrqg.M2ApiContractTest' --tests 'com.ricezhou.vsrqg.traceability.TraceabilityVerificationDtoTest' --tests 'com.ricezhou.vsrqg.traceability.TraceabilityVerificationQueryHttpTest' --tests 'com.ricezhou.vsrqg.traceability.TraceabilityVerificationReadQueryShapeTest' --tests 'com.ricezhou.vsrqg.traceability.TraceabilityVerificationStartHttpTest' --rerun-tasks`

Result: `BUILD SUCCESSFUL`; 7 suites, 25 tests, 0 failures, 0 errors, 0 skipped. Production and test Kotlin were both recompiled during this execution.

Contract gate:

`npm run test:contracts`

Result: `PASS contracts schemas=4 positive=12 negative=5 operations=34`.

Focused PostgreSQL tests stopped at Testcontainers `DockerClientProviderStrategy` after source compilation. This machine did not execute the 5 Query database cases, the 1 Replay database case, or the relevant SecurityAcceptance database cases. This is an environmental block, not a business failure and not GREEN; Docker was neither bypassed nor repeatedly restarted.

## Self-review

- Authority: no second data source, fallback, cache, or recomputation; the Repository remains the only JDBC boundary.
- Ordering: Issue/Path/Gap are all ordered by database-persisted ordinal; the Application does not reorder them.
- Query shape: the 20-Issue mutation test proves that header/issues/path/gap call counts do not grow with Issue count.
- Immutability: exact historical reads reference only Snapshot materialization and its producer fixed-input Run; later Revision/Issue Snapshot data is absent from the read SQL.
- Security: tests cover scope, Project membership, uniform 404, fixed 503, and redaction of sensitive exception text.
- Compatibility: existing POST HTTP regression `2/2`, ApplicationContext `2/2`, Architecture `6/6`, and M2 API contract `10/10` are all GREEN.

## Files

- Added `GetTraceabilityVerification.kt`.
- Modified `TraceabilityVerificationRepository.kt`, `JdbcTraceabilityVerificationRepository.kt`, and `TraceabilityVerificationController.kt`.
- Added `TraceabilityVerificationQueryIntegrationTest.kt` and `TraceabilityReplayTest.kt`.
- Modified `SecurityAcceptanceTest.kt`, `TraceabilityVerificationStartFailureTest.kt`, and `TraceabilityVerificationWorkerFailureTest.kt`.

## Commit

Suggested Subject: `feat(m2): expose immutable traceability results`. The implementation agent will report the immutable Commit ID after committing; the report cannot prewrite its own Commit hash.

## Remaining risks and next step

- The mandatory next step is to synchronize this commit to the English branch and execute Query, Replay, Security, and the complete M2.5 Gate in Linux/Docker CI bound to the exact Chinese and English HEADs; verify the actual PostgreSQL test count, 0 failure/error/skip, historical bytes/digest, and security 403/404.
- Until that CI Evidence exists, Task 6 may only be marked "implementation complete, PostgreSQL acceptance pending" and cannot create an APPROVE Owner Gate fact.
- This task did not push, merge, Tag, release, deploy, or modify the progress ledger.
