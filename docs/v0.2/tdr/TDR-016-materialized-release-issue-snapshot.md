# TDR-016 — Materialized Release Issue Snapshot and Sync Observation Ledger

- Status: Accepted
- Date: 2026-09-02
- Decision Basis: The Project Owner approved the `M2-KD-2026-09-02-01` Written Spec Review on 2026-09-02
- Scope: exact Sync membership, transactional materialization, canonical digest, and historical replay for the M2.3 Release Issue Snapshot
- Related Decisions: [TDR-003](TDR-003-postgresql.md), [TDR-005](TDR-005-rest-openapi.md), [TDR-007](TDR-007-postgresql-job-outbox.md), [TDR-009](TDR-009-oidc-and-service-identities.md), [TDR-015](TDR-015-versioned-jira-mapping-and-adapter-authority.md)

## 1. Why This Technology Was Selected

V0.2 uses a PostgreSQL `issue_sync_run_item` Observation Ledger to record the exact `normalized_issue` Revisions observed by each Sync, then materializes an immutable Release Issue Snapshot in one transaction. The Snapshot uses RFC 8785 canonical JSON and a SHA-256 content digest.

Release, Manifest, Issue, Sync, Snapshot, and later Traceability have strongly structured relationships and require transactions, historical queries, Project isolation, and consistency. MVP data volume is bounded. Extending PostgreSQL is more direct than introducing a Blob-only store, event stream, or second database and fits the six-month spare-time delivery constraint.

## 2. Problem It Solves

The current `normalized_issue` has no Sync membership. A time-based or current-latest Revision query cannot prove that a fact belonged to a specified successful Sync. The Observation Ledger gives every page transaction verifiable membership; the materialized Snapshot prevents later Jira, Mapping, Revision, or Sync changes from altering an old Release Gate input.

This decision specifies only the Issue Adapter and Snapshot implementation. It does not change V0.1 Issue, Release, Manifest, Evidence, Traceability, Quality Result, or Fixed/Included/Verified semantics.

## 3. Why Alternatives Were Not Selected

- Query the historical latest Revision by `observed_at/completed_at`: time boundaries and concurrent runs make membership ambiguous.
- Store only Revision IDs in the Snapshot: historical API bytes still depend on referenced rows and query implementation, so the replay input is not self-contained.
- Store only a canonical JSON Blob: weakens FK, Project Scope, Issue queries, and constraints and creates a second data model for relational facts.
- Query Jira again while creating the Snapshot: external state drifts and would place Jira in the authoritative Release Gate path.
- Kafka/Event Sourcing/CDC: the current single-project, at-most-20-item Pilot has no requirement that justifies the operational cost.

## 4. Impact on V0.2

A forward-only Migration adds `issue_sync_run_item` with composite FKs, unique constraints, and an immutable Trigger; adds a `FULL/DELTA` result mode and versioned filter reference to Sync Runs; and adds replay metadata to Snapshot Headers. Historical M2.2 Runs are not guessed or backfilled and remain explicitly ineligible for M2.3.

`persistPage` writes Normalized Revisions and Observations in the existing page transaction. `POST /api/v1/releases/{releaseId}/issue-snapshots` preserves the approved `IdentifierInput.sourceId`, resolves the latest eligible `SUCCEEDED/FULL` Run under Release/Source locks, and pins the resolution in the Idempotency Record.

The Snapshot materializes non-tombstone Items, records observed/tombstone/selected counts, orders by stable identity, and computes a digest. Header, Items, Audit, Outbox, and Idempotency response commit in one transaction; any failure rolls back everything. M2.3 does not decide Fixed, Included, or Verified.

## 5. Impact on Future V0.3

V0.3 may continue generating an independent Snapshot for each Source and combine multiple sources through a separately reviewed Release Input Snapshot. An incremental Adapter must first create a verifiable full-state materialization before becoming eligible as `FULL`; it cannot present a DELTA as complete Release input.

Future implementations may replace JDBC access or add an archive tier, but they must preserve exact Sync membership, immutable materialization, Project Scope, canonicalization/version, digest replay, and fail-closed semantics.

## 6. Migration

Use an Expand-only Migration to create the Observation table, indexes, constraints, and Triggers and to add nullable-to-validated Sync/Snapshot metadata. The new application writes Observations only for new Runs. After constraint validation, enable the Snapshot Endpoint. Old Runs stay historically visible but return `ELIGIBLE_SYNC_NOT_FOUND`; membership must not be inferred from timestamps.

The API Path, Method, Permission, Idempotency, and `IdentifierInput` remain unchanged. Pilot defaults to `all-relevant-issues/v1`, `release-issue-snapshot-jcs/v1`, and `issue-snapshot-age/v1`. A version change requires a TDR revision or new TDR.

## 7. Testing

Unit Tests cover RFC 8785, SHA-256, UTF-8, UTC time, ordering, empty sets, tombstones, and three-run digest replay. PostgreSQL Tests cover membership FK/uniqueness, page atomicity, terminal sealing, same-transaction Snapshot children, immutable rejection, and cross-Project rejection.

Application Tests cover Locked Manifest, latest eligible Run, FULL/DELTA, age boundaries, idempotency, and concurrency. Fault injection proves full rollback on Audit, Outbox, Item, or digest failure. Replay Tests compare old Snapshot bytes after Mapping/Revision/Sync changes. Security Tests confirm that logs, Git, and CI Evidence do not leak Issue content, JQL, URLs, paths, or Credentials.

## 8. Deployment

No service, Broker, database, container, object storage, or UI is added. The Migration ships with the existing Backend/PostgreSQL deployment, and the M2.3 write path is enabled only in the Pilot Profile by default. Deployment order is Migration, compatible application, Gate verification, then Endpoint enablement.

Before Company Profile enablement, age policy, permissions, retention, and recovery ownership require explicit configuration and independent Company Evidence. This TDR does not authorize real Jira queries, Company writes, or production deployment.

## 9. Failure Recovery

A page or Snapshot transaction failure rolls back in PostgreSQL while existing successful Runs and historical Snapshots remain unchanged. A canonicalization, count, fact-digest, or content-digest mismatch disables new writes, fails closed, and is repaired by roll-forward; it must not use a current-latest Revision or Blob fallback.

Database recovery uses the existing backup/restore mechanism. After restore, reconstruct canonical bytes and reconcile digest, FKs, counts, Audit, and Outbox. If a rolled-back application cannot interpret a new version, it may only block new Snapshots; it must not delete Observations or rewrite historical Migrations.

## Re-evaluation Triggers

Re-evaluate when one Release must atomically combine multiple Issue Sources, an incremental Source needs full-state materialization, one Snapshot exceeds current PostgreSQL transaction/read limits, Company requires independent archive or retention/deletion semantics, RFC 8785/digest algorithms change, or the API must allow callers to select any historical Sync. Re-evaluation must not silently change the frozen V0.1 architecture or historical Snapshots.
