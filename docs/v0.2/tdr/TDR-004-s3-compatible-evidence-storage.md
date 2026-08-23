# TDR-004 — S3-Compatible Evidence Payload Storage

- Status: Proposed for V0.2 Review
- Scope: large objects such as logs, screenshots, traces, and dumps

## Problem and Requirements

Evidence Payloads are large, diverse, and retained for long periods. They need streaming upload, integrity, lifecycle management, access control, and low-cost scaling, while Metadata still needs relational queries. Storing Payloads in PostgreSQL amplifies backup, WAL, and query costs.

## Decision and Rationale

Use company S3 or S3-compatible object storage such as MinIO for Payloads. PostgreSQL stores Evidence Metadata, object key, size, and SHA-256. The Agent uploads directly with a restricted presigned URL, and the Server validates at Complete. The S3 API has a mature ecosystem, supports versioning/lifecycle, and eases migration from development MinIO to company storage.

## Alternatives Not Selected

- PostgreSQL bytea: transactionally convenient, but large objects harm database operations and recovery.
- Shared filesystem: weaker permissions, cross-host behavior, lifecycle controls, and API consistency.
- Arbitrary local paths outside the database: not portable and easily lost.

## V0.2 / V0.3 Impact

V0.2 adds an upload state machine because the database and object storage are not atomic, but avoids making the Backend a transfer bottleneck. V0.3 can adopt tiered storage, cross-region replication, or a dedicated Evidence service without changing the Metadata contract.

## Migration and Rollback

Copy objects to a new bucket/provider using content checksum and inventory. After validation, update controlled URI references in batches and retain the mapping. Application rollback continues reading the compatible S3 API; failed copies never delete source objects.

## Testing, Deployment, and Recovery

Test interrupted uploads, expired URLs, invalid checksums, orphaned/missing objects, and permissions. Deploy private buckets with encryption, versioning, and lifecycle policies. Recovery reconciles bucket inventory with DB metadata.

## Re-evaluation Triggers

The company prohibits the S3 API, Evidence types require specialized indexing/stream processing, or measured cost/performance cannot satisfy retention policy.
