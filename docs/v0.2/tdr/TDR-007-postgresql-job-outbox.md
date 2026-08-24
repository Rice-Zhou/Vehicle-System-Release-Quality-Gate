# TDR-007 — PostgreSQL Jobs and Transactional Outbox

- Status: Accepted
- Approved Review: `V0.2-AR-2026-08-23-01`
- Approval Date: 2026-08-24
- Accepted Residual Risks: Owner final acceptance checklist Section 5, items 1-5
- Scope: background work and post-transaction asynchronous processing

## Problem and Requirements

Adapter sync, Trace verify, Evidence reconcile, and Quality Evaluation require asynchrony, retries, and recovery. Events such as Manifest Lock must remain consistent with business transactions. MVP scale is manageable and cannot justify Broker operations or cross-system consistency costs.

## Decision and Rationale

The business transaction also writes an Outbox record. Workers use PostgreSQL row locks/leases, bounded retries, and dead-letter handling. The database is already required and can atomically ensure that state changes and event records succeed together, while supporting safe multi-instance claiming.

## Alternatives Not Selected

- Kafka: strong throughput and replay, but complex deployment, Schema, consumers, and operations without a current need.
- RabbitMQ: mature, but still introduces another state system and publisher-confirmation issues.
- In-memory queue: loses work on restart and cannot support audit/recovery.
- Fully synchronous execution: external calls and long work enlarge transactions and API latency.

## V0.2 / V0.3 Impact

V0.2 lowers operational cost while the DB carries job scanning. V0.3 may add a Broker publisher behind the Outbox; business transactions and event contracts remain unchanged.

## Migration and Rollback

First define stable event IDs/schemas. When introducing a Broker, publish on both paths from Outbox but allow only one consumer to own side effects; stop the DB worker after cutover. Rollback restores the DB worker and resumes with idempotency keys.

## Testing, Deployment, and Recovery

Test duplicate claims, worker crash, lease expiry, poison jobs, DB restart, and idempotency. Deploy workers in the Backend image. Recover from job/outbox tables; dead letters must alert and remain visible to operators.

## Re-evaluation Triggers

Measured job lag or DB load misses SLO, large cross-system subscriptions or independent throughput scaling is needed, and index/batch optimization is inadequate.
