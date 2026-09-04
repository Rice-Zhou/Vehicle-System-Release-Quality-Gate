# TDR-018 — PostgreSQL Asynchronous Traceability Verification and Immutable Snapshot

- Status: Proposed
- Date: 2026-09-04
- Decision Basis: the Owner Design Direction for `M2-KD-2026-09-04-01` is approved; Written Spec Review is pending
- Scope: M2.5 pinned input, asynchronous Verification Run, Fixed/Included/Verified, Gap, immutable Snapshot, query, and recovery
- Related Decisions: [TDR-001](TDR-001-modular-monolith.md), [TDR-003](TDR-003-postgresql.md), [TDR-005](TDR-005-rest-openapi.md), [TDR-007](TDR-007-postgresql-job-outbox.md), [TDR-016](TDR-016-materialized-release-issue-snapshot.md), [TDR-017](TDR-017-build-provenance-envelope.md)

## 1. Why This Technology Was Selected

Select a PostgreSQL-backed asynchronous Worker, pinned input ledger, and immutable materialized Snapshot inside the existing Modular Monolith. Release, Manifest, Issue, Commit, Build, and Artifact have strongly structured relationships that require transactions, FKs, historical queries, concurrent uniqueness, and consistent replay. The current Pilot has at most 20 Issues and a default limit of 2,000 Edges, so the existing PostgreSQL Job/Outbox is sufficient for asynchronous execution.

This approach reuses accepted PostgreSQL, REST/OpenAPI, Job/Outbox, and monolith deployment choices without adding a Broker, graph database, or service. It provides company-grade audit and recovery boundaries within a six-month part-time implementation scope.

## 2. Problem It Solves

Dynamically reading latest Edges cannot explain which facts produced a historical Release conclusion. Synchronous HTTP verification increases timeout, retry, and lock risk. This decision pins Locked Manifest, M2.3 Issue Snapshot, M2.4 Edge Revisions, policy, and validator at request time and calculates Fixed, Included, Verified, and exact Gaps asynchronously.

An atomic immutable Snapshot gives API, Quality Engine, Audit, and backup recovery one authoritative result. M2.5 has no real test Evidence, so Verified remains false and cannot fabricate Release verification.

## 3. Why Alternatives Were Not Selected

- Synchronous request transaction: appears simpler but conflicts with the approved `202` contract and magnifies timeout and duplicate-client-request risk.
- Dynamic query-time calculation: latest Revisions change historical results and prevent deterministic replay.
- Kafka/RabbitMQ: no current throughput or isolation requirement justifies another publication, monitoring, and recovery state machine.
- Graph database: relationship types are fixed and volume is controlled; PostgreSQL reachability and materialized results meet the need.
- Independent Traceability service: adds deployment, network, authentication, and cross-service transaction cost without a proven boundary benefit.

## 4. Impact on V0.2

Add one forward-only Migration that extends Verification Run, creates an immutable Edge Input Ledger, Snapshot Issue Result, and primary-path Edge association, and extends Gap predecessor references. Reuse Snapshot/Edge/Gap, Background Job, Idempotency, Audit, and Outbox. Add Traceability Application/Domain/Adapter implementation and one read-only Verification Run status Endpoint.

M2.5 executes only in PostgreSQL and does not call Jira, GitHub, CI, Device, or Agent. `ARTIFACT_RELEASE` remains derived only from the Locked Manifest view. One verification supports at most 20 Issues and 2,000 Edges by default and fails closed beyond the limit.

## 5. Impact on Future V0.3

V0.3 may use new schema, policy, and validator versions to add Test Run, Test Result, and Evidence snapshot facts and produce real Verified conclusions. Historical M2.5 Snapshots remain Verified=false. If measurements prove the Worker or PostgreSQL Job has reached capacity, the Worker can be extracted or a Broker introduced without changing API, pinned input, Snapshot digest, or database authority.

Graph queries are reconsidered only when real relationship type, depth, or scale proves PostgreSQL insufficient. A second authority cannot be introduced for possible future needs.

## 6. Migration

Flyway uses an expand-only Migration to add nullable or policy-defaulted columns, Input/Issue Result/Path Edge tables, Gap predecessor fields, FKs, indexes, and creation-transaction and immutable triggers. Run data preconditions and Migration Constraint Tests before deploying a compatible application. An older application may ignore the new structures. Failure rolls back the image and repairs the database through a new forward migration; no down migration or historical deletion is allowed.

Existing API Path, Method, Permission, and Idempotency stay unchanged. Typed responses and optional query parameters are backward-compatible extensions. Discovery of a real incompatible consumer stops work and reopens design review.

## 7. Testing

Domain Tests cover a complete chain, every break, multiple paths, and separation of the three states. Replay Tests prove that a new Revision does not change an old Snapshot. PostgreSQL Tests cover pinned input, immutability, cross-Project rejection, version allocation, digest reuse, and concurrency. Transaction Tests inject failure at every persistence boundary and prove there is no partial result.

Contract/Security Tests cover `202`, status polling, Snapshot query, permissions, enumeration-safe 404, Problem Details, and sensitive-data scans. Recovery Tests cover Worker crash, DB restart, dead-letter, backup/restore, and digest verification. A performance report covers 20 Issues/2,000 Edges without N+1 or combinatorial path explosion.

## 8. Deployment

Deploy with the existing Backend image and PostgreSQL. Add no service, Broker, Redis, graph database, object store, or public Endpoint. Sequence is backup, Migration, complete Gate, Pilot known-chain/gap Smoke, replay verification, and explicit enablement. Company, real Jira/CI, and M3 are not M2.5 deployment prerequisites.

Configuration remains external and contains no credential. The Worker uses the existing Background Job settings, bounded retry, and Dead Letter. Liveness does not depend on external Jira/GitHub/CI.

## 9. Failure Recovery

A creation-transaction failure creates no Run or Job, and a result-transaction failure creates no Snapshot. After Worker crash, retry uses the pinned Input Ledger. Identical input and digest converge on one Snapshot. A poison job enters Dead Letter after its limit, the Run is explicitly FAILED, and repair uses a new Run without overwriting a terminal state.

The application can roll back to the previous image while retaining expanded tables. After database restore, verify Flyway version, Run/Input/Snapshot FKs, Audit/Outbox, and canonical digest. Any inconsistency remains fail-closed and is repaired by roll-forward without rebuilding authority from JSON, files, caches, or external systems.

## Re-evaluation Triggers

Re-evaluate when one Release consistently exceeds 20 Issues or 2,000 Edges, reference verification consistently exceeds ten seconds, Job backlog or lock contention reaches measured thresholds, real Test/Evidence Verified is needed, cross-service isolation is required, or real data proves PostgreSQL graph queries insufficient. A proposal that changes Fixed/Included/Verified, Manifest authority, Snapshot immutability, or introduces a second authority requires an ADR Proposal.
