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
