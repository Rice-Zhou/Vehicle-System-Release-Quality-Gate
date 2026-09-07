# Final Review Fix Report

## Status

This bounded pass corrects the two Important P2 final-review findings: complete Snapshot Edge queries and digest recomputation from restored Snapshot content. The current technical state is `PENDING_CI_AND_REVIEW`, not final acceptance. Earlier CI Evidence applies only to earlier Subjects; the Owner record retains its existing Subject and `PENDING` state without rewriting or substituting an Owner decision.

## Root Causes and Changes

- Queries read Header, Issue Result, main paths, and Gap but omitted persisted non-main-path Edges. One read now loads every `traceability_snapshot_edge` ordered by `ordinal` and returns `edges` through Application, DTO, and Controller while preserving the main path. Public fields contain only identity, exact revisions, endpoints, Confidence, status, authority, and fact digest; source references, raw payload, and credentials are excluded.
- The previous recovery drill reran the graph verifier from another `RUNNING` Run's pinned input and compared stored hash columns and partial IDs. It did not verify restored result content. Recovery now reconstructs the existing canonical projection from the completed Snapshot's Header, Issue Result, all Edges, Path, Gap, and exact producer identity, reading only immutable digest metadata from its pinned Issue Snapshot, and separately recomputes Gap, Issue Result, and overall digests. Recovery does not load an execution ledger, rerun the graph verifier, or read source revisions or the Manifest view. The Run used for reclaim deliberately has different input.
- The single canonical projection factory, JCS renderer, and database Gap token mapping are reused; only the corresponding internal methods are exposed to module tests. Canonical bytes/version, V0.1, Migrations, and production immutability protections are unchanged.
- The constant read budget is now one each for release/header/issues/edges/paths/gaps: six Repository reads plus one membership read, seven authorized database round trips. Performance still uses 20 Issues, 2,000 Edges, and 3 samples and now asserts that 2,000 Edges are returned. Gate and Evidence allowlists are updated together.
- Real dump/restore, restart, fresh connections, process identity, reclaim, Dead Letter, and manual retry remain. Seven corruptions are injected only into rollback transactions in the independent restored database: Fixed/Included, Included, Confidence, Gap reason, Gap break entity, main-path Confidence, and non-main-path numeric revision. Hash columns remain unchanged; verification must reject each mutation and pass again after rollback. Production triggers/constraints are unchanged.

## RED and GREEN

- Query RED: `TraceabilityVerificationReadQueryShapeTest` expected the complete edge read; the original implementation omitted it, producing 1 test failed.
- Recovery RED: `RestoredTraceabilitySnapshotTest` changed content while preserving digests; the implementation that trusted the old digest failed to throw. Layered recomputation made it GREEN.
- Focused GREEN: `RestoredTraceabilitySnapshotTest`, `TraceabilityVerificationReadQueryShapeTest`, `TraceabilityVerificationDtoTest`, `TraceabilityVerificationQueryHttpTest`, `TraceabilityCanonicalizerTest`, and `TraceabilityVerifierTest`: 47 tests passed.
- After adding HTTP edge assertions, strict OpenAPI field assertions, and the non-main-path mutation, `RestoredTraceabilitySnapshotTest`, `TraceabilityVerificationQueryHttpTest`, `M2ApiContractTest`, and `*ArchitectureTest` ran again with exit 0.
- `node scripts/contract-validator.mjs`: `schemas=4 positive=12 negative=5 operations=34`.
- `scripts/tests/m2-5-verify-gates.tests.ps1` verifies the six constant reads and fails closed on an extra Edge read. Public Replay asserts the exact non-main-path revision and compares complete historical response bytes before and after new authority facts are appended.
- Focused PostgreSQL execution: `TraceabilityReplayTest`, `TraceabilityVerificationRecoveryTest`, and `TraceabilityVerificationPerformanceTest` compiled, but execution stopped at `DockerClientProviderStrategy`. 3 tests failed at initialization; database semantic assertions did not execute and are not counted as PASS. The new candidate requires real PostgreSQL CI Evidence.

## Existing Digest Boundary

The technical ruling for this pass preserves the versioned canonical format: non-main-path Edge type, ID, numeric revision, revision ID, and fact digest are included in the overall digest; expanded fields such as from/to and Confidence are absent from the existing overall projection. The M2.4 fact digest additionally needs proof reference/proof digest that Snapshot does not store. That upstream digest cannot be recomputed from Snapshot alone, and arbitrary field corruption cannot be claimed detectable. The non-main-path corruption test changes the covered numeric revision. Broader coverage would require a separate versioned projection and migration design, not a silent change to historical digest semantics.

## Handoff

This pass does not push, merge, tag, deploy, call external Providers/Jira, or modify the Owner record. The sole next action is final review and exact-head CI on the new bilingual commits, obtaining complete PostgreSQL, Replay, Recovery, Performance, and bilingual Pair Gate evidence before updating candidate acceptance materials.
