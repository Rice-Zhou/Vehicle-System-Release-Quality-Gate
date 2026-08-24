# TDR-004 — S3-Compatible Evidence Payload Storage

- Status: Accepted
- Approved Review: `V0.2-AR-2026-08-23-01`
- Approval Date: 2026-08-24
- Accepted Residual Risks: Owner final acceptance checklist Section 5, items 1-5
- Scope: large objects such as logs, screenshots, traces, and dumps

## Problem and Requirements

Evidence Payloads are large, diverse, and retained for long periods. They need streaming upload, integrity, lifecycle management, access control, and low-cost scaling, while Metadata still needs relational queries. Storing Payloads in PostgreSQL amplifies backup, WAL, and query costs.

## Decision and Rationale

Use company S3 or S3-compatible object storage such as MinIO for Payloads. PostgreSQL stores Evidence Metadata, object key, size, and SHA-256. The Agent uploads directly with a restricted presigned URL, and the Server validates at Complete. GENERAL/RESTRICTED downloads may use a short-lived Presigned URL; HIGH downloads stream through a per-request authenticated Backend Proxy/controlled Gateway and never disguise a Bearer URL as user-bound. The S3 API has a mature ecosystem, supports versioning/lifecycle, and eases migration from development MinIO to company storage.

## Alternatives Not Selected

- PostgreSQL bytea: transactionally convenient, but large objects harm database operations and recovery.
- Shared filesystem: weaker permissions, cross-host behavior, lifecycle controls, and API consistency.
- Arbitrary local paths outside the database: not portable and easily lost.

## V0.2 / V0.3 Impact

V0.2 adds an upload state machine because database and object storage are not atomic. Agent upload and ordinary download do not proxy Payload through Backend. Low-volume HIGH download accepts Backend/Gateway traffic to authenticate every request and must measure bandwidth, concurrency, and timeout. If HIGH traffic becomes a bottleneck, V0.3 can extract a controlled Evidence Gateway or add tiered storage/cross-region replication without changing Metadata or authorization contracts.

## Migration and Rollback

Copy objects to a new bucket/provider using content checksum and inventory. After validation, update controlled URI references in batches and retain the mapping. Application rollback continues reading the compatible S3 API; failed copies never delete source objects.

## Testing, Deployment, and Recovery

Test interrupted uploads, expired URLs, invalid checksums, orphaned/missing objects, ordinary-download TTL, HIGH cross-user access, and log leakage. Deploy private buckets with encryption, versioning, and lifecycle policies. Recovery reconciles bucket inventory with DB metadata.

## Re-evaluation Triggers

The company prohibits the S3 API, Evidence types require specialized indexing/stream processing, or measured cost/performance cannot satisfy retention policy.
