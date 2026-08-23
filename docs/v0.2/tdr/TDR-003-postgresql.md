# TDR-003 — PostgreSQL for Structured Data

- Status: Proposed for V0.2 Review
- Scope: domain, relationship, transaction, and historical-query data

## Problem and Requirements

Release, Manifest, Issue, Commit, Build, Artifact, Test, Evidence Metadata, and Quality contain many structured relationships. Manifest Lock and state transitions require transactions. Traceability and Audit require historical queries. Quality Snapshot requires consistency. MVP data volume is manageable.

## Decision and Rationale

Use PostgreSQL as the single structured data store. It provides ACID, FK/UNIQUE/CHECK constraints, MVCC, consistent queries, JSONB extension, mature migration/backup/PITR, and strongly typed association tables for the fixed traceability chain. One database reduces cross-store consistency and operational costs.

## Alternatives Not Selected

- MongoDB: flexible documents, but strong relationships, multi-entity transactions, and integrity constraints are core requirements here.
- Neo4j/graph database: the traceability chain is fixed and its scale manageable; SQL joins suffice, while a second database adds consistency issues.
- MySQL: viable, but PostgreSQL better matches constraints, JSONB, concurrent jobs, and query requirements.
- Multiple databases: no independent scale or isolation evidence exists.

## V0.2 / V0.3 Impact

V0.2 gains a single transactional source of truth. If V0.3 analytics scale justifies it, read-only search or graph projections can be built from immutable events/snapshots, while PostgreSQL remains the authoritative record.

## Migration and Rollback

Use Flyway forward-only migrations and Expand/Migrate/Contract, rehearsed on a copy before production. On failure, roll back the application and restore the database through backup/PITR. Migrating to another database requires exporting domain snapshots with digests and completing dual-read comparison without changing IDs or semantics.

## Testing, Deployment, and Recovery

Use real PostgreSQL integration tests for constraints, transactions, locks, and migrations. Deploy a dedicated or managed instance with encryption, backup, and monitoring. Regularly restore and replay historical Quality Results.

## Re-evaluation Triggers

Measured single-database capacity, throughput, or SLO remains insufficient after indexes, partitioning, read replicas, and other optimizations are proven inadequate, or the company platform mandates a change.
