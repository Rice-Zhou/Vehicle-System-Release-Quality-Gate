# TDR-025 — Local Demonstration Evidence Payload Storage

- Date: 2026-09-09; status: Accepted for this demonstration design/planning, Task 2 machine-contract declarations and subsequent Task 4 local Payload upload, download and recovery implementation.
- Authority: [Owner design acceptance](../../governance/acceptance/records/2026-09-09-m3-smoke-design-review-001.md); original instructions are preserved in the [receipt](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/bbfda03a08af7dffe4bca88a38ac558f2965e4ea).
- Scope: LOG/SCREENSHOT for the TDR-024 single-device demonstration, excluding Company and large Evidence.
- Task 2 instructions and checks are in [identity and registration engineering verification](../../m3/agent-identity-registration-verification.md). Task 2 declares the upload endpoint and sensitivity-based download permissions. Task 4 instructions, completed runtime verification and exact-commit evidence are in the [local Evidence engineering record](../../m3/local-evidence-verification.md); Company remains disabled.

## Need and Choice

The Owner prioritizes a demonstrable product without cloud resources or archival infrastructure. The first Case produces bounded logs and screenshots; the existing Backend host already has a filesystem and PostgreSQL. Store Evidence Metadata in PostgreSQL and Payloads in a dedicated controlled Backend directory. The Agent still uploads over HTTP without sharing server paths. GitHub stores code, suitable sanitized materials and provenance, not the runtime business database.

| Option | Trade-off |
|---|---|
| Existing Backend + controlled directory | Recommended for this slice: no new service, server-side streaming validation; requires handling filesystem/database non-atomicity and single-host backups. |
| Add S3/MinIO per TDR-004 | Preserves the existing direct-upload choice but adds service configuration and maintenance unnecessary for this first demonstration. |
| Commit every capture directly to GitHub | Conflates runtime permissions/transactions with project-material publication and risks uploading real device content; rejected. |

## Explicit Adjustment to Existing Technical Contracts

The accepted decision for this demonstration Profile alone replaces S3 presigned direct upload described by TDR-004, TDR-006 and Agent/Evidence documents with Backend streaming upload. Add the fully versioned `PUT /agent-api/v1/evidence/uploads/{id}/payload`, using Agent mTLS identity and server-side Upload Session authorization, without unauthenticated URLs. Create/Complete request fields, Evidence entities, states, checksums, associations and authority remain unchanged. Update OpenAPI, the Agent endpoint table and matching contract tests together during implementation. Do not claim this endpoint already exists or implements S3 compatibility.

GENERAL/RESTRICTED/HIGH all stream through the existing authenticated Payload GET path in this slice, without weakening HIGH controls. API documentation must explicitly identify the demonstration Profile behavior; existing Company paths are not silently changed. Download authorization, project scope, purpose, Audit and prevention of token/path leakage remain required. Add any necessary permission through the existing permission catalog rather than bypassing identity checks.

This is an accepted scoped storage/transport implementation decision, preserving Core Contract and associated, verifiable Evidence semantics. If implementation needs changes to core authority or history semantics, stop and use ADR governance.

## Filesystem and Transaction Boundaries

The server constructs paths exclusively from server-generated Upload/Evidence IDs, rejects symlinks/non-regular files and never accepts client paths. LOG limit: 1 MiB; PNG limit: 8 MiB; two required Evidence items per Attempt. Stream into an exclusively created temporary file. Complete recomputes actual size/SHA-256, fixes the Payload after a match, then writes AVAILABLE and Audit/Outbox transactionally. Conflicting retransmission cannot overwrite existing files.

Identical-byte upload/Complete retries are idempotent; differing digests conflict. Oversize, integrity failure, wrong associations or stale leases cannot produce AVAILABLE. If the file succeeds but DB commit fails, retain a locatable orphan and recover through the same Session retry or reconciliation. Missing/corrupt files referenced by Metadata produce explicit INTEGRITY_ERROR, not success. Do not pretend filesystem and database commits are atomic.

The directory is outside Git and public static resources, isolated by service-account permissions. Backup/restore must verify Metadata and Payload inventories/digests together. Ordinary directory permissions and content digests do not claim administrator-proof immutability or WORM. Agent spool retains required content until the server acknowledges Result/Evidence reception.

## Task 4 Receive Deadline and Transaction Implementation

Task 4 uses Servlet AsyncContext/ReadListener for a total receive deadline with a 30-second default and maximum, using the existing container without another asynchronous framework or service. Socket idle timeouts alone cannot bound a continuous slow stream. PUT therefore authorizes and captures the Session/Attempt binding in a short transaction, releases Agent/Run/Attempt locks while receiving a temporary candidate, then revalidates current identity, permissions, lease/fencing, Session and terminal state in another short transaction after EOF. Only matching declared size/SHA-256 permits same-directory hard-link create-only candidate publication and an UPLOADING commit; unsupported file operations fail explicitly. Complete retains Attempt/Session locks through Metadata/Audit/Outbox commit.

The existing synchronous file-write interface and HTTP reception share one controlled candidate implementation without exposing paths. Timeout, read error or verification failure cleans up only the current unpublished candidate, never deleting or overwriting a previously fixed correct file. EOF, timeout and error races terminate exactly once. Publication followed by database rollback still preserves a verifiable orphan for the same Session's retry. This choice adds limited Servlet asynchronous lifecycle handling to enforce a total deadline and keep network reads from locking out heartbeat/cancellation. Slow-stream timeout, cancellation concurrency and retransmission regressions establish correctness; no file-system/database atomic transaction is claimed.

## Verification, Rollback and Reevaluation

Verify interrupted uploads, retries, conflicts, size limits, path/link rejection, cross-project/Agent/Run access, missing/corrupt files, retries after DB failure and backup/restore. Stop new Runs while retaining the directory and database; application rollback must not delete historical Payloads.

Reevaluate TDR-004 for multiple hosts, concurrent capacity, additional types, actual company data or long retention. Migration requires fixed-inventory verification; switching Providers does not prove history was migrated. This design enables no Company service, object lock or external Provider.
