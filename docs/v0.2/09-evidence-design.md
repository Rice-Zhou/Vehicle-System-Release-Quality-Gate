# 09 — Evidence and Collector Design

## 1. First-Class Entity Principle

The single-device demonstration Profile exception is defined in [TDR-025](tdr/TDR-025-local-demo-evidence-payload.md): PostgreSQL stores Metadata and a controlled Backend directory outside the repository stores Payload. New `PUT /agent-api/v1/evidence/uploads/{id}/payload` uses independent Agent mTLS and `agent:evidence:write`, authorizing and replaying through Agent/project/Attempt Session, valid lease, expiry and bytes digest without Idempotency-Key. Task 4 implements streaming storage and Complete verification for this endpoint. Metadata creation or successful byte upload must not be interpreted as AVAILABLE.

Evidence is not a field attached to Test Result. Metadata is stored in PostgreSQL and Payload in S3-compatible object storage. Immutable evidenceId, object key, size, and checksum connect them.

```text
Collector → local spool → Upload Session → Object Storage
                                  ↓ complete + verify
                         PostgreSQL Evidence Metadata
```

## 2. Metadata

Required: evidenceId, type, schemaVersion, releaseId, testRunId, capturedAt, collectorName/version, source, checksum algorithm/value, payload size, object key/URI, media type, upload state, sensitivity, and createdAt.

Optional: testResultId, attemptId, deviceId, artifactId, process/package, time range, fingerprint, severity, and structured summary. sensitivity is GENERAL, RESTRICTED, or HIGH and can change only through an audited reclassification process. URI is a controlled internal reference; Metadata API does not expose a permanent object-storage address.

Types: LOG, SCREENSHOT, CRASH, ANR, MEMORY, PERFETTO, DUMP, TEST_REPORT. An extension type requires a schema/version and compatible read strategy. Collector is an Agent Plugin and does not enter the Core Contract.

## 3. Upload and Integrity

States: PENDING_UPLOAD → UPLOADING → VERIFYING → AVAILABLE. Failure enters REJECTED and an expired session is EXPIRED. After AVAILABLE, checksum, URI, size, collector version, and associations are immutable.

A presigned URL permits only the specified key, size range, content type, and short expiration. After Complete, Server verifies object Metadata. High-value Evidence may have checksum recomputed asynchronously. Object keys do not contain raw sensitive device identifiers.

An upload presigned URL and user download authorization are separate boundaries. Agent single-object restricted upload may use a presigned URL. User downloads follow the sensitivity policy in Section 8.

Duplicate Payloads may use content-deduplicated storage, but every collection still creates separate Evidence Metadata to preserve Release/Test Run context.

## 4. Collector Plugin Contract

```text
CollectorPlugin
  descriptor() → type, version, capabilities, schemaVersions
  start(context, config) → session
  mark(testCaseContext)
  collect(trigger, timeWindow) → EvidenceCandidate[]
  stop() → summary
  health() → health
```

Collector input contains only execution context and collection configuration. Its output contains objective data and diagnostics, never BLOCK/WARNING/PASS. A Plugin failure is isolated to that Plugin and its Evidence requirement and does not corrupt Release or historical Evidence.

## 5. Crash Collector

### Sources and Detection

- Android logcat crash buffer, ActivityManager process death, tombstone where permitted, DropBox/system_server events, and target-app crash markers.
- Correlate using Agent/Device monotonic-clock windows and Test Case markers; preserve raw time and correction information.

### Collection and Association

Collect package/process, redacted UID when necessary, artifact/package version, signal/exception, top frames, timestamp, Device, Release, Run, Attempt, and raw log/tombstone. Resolve Artifact association through package/signature/version/checksum in the Locked Manifest. If no unique match exists, remain unassociated and record the reason.

### Fingerprint and Deduplication

Fingerprint is versioned: normalized exception/signal + process/package + first N non-noise stack frames, hashed as `crashFingerprint`. The same fingerprint in one Run/Device/Artifact/window may aggregate occurrence count, while every raw Payload remains traceable. Cross-Release matching is for queries only and never merges Evidence.

Collector does not decide whether severity is BLOCK. It may output objective classification and fingerprint; Quality Rules decide severity/policy.

## 6. ANR Collector

Detection sources include ActivityManager ANR events, `traces`/DropBox, target-process unresponsive signals, and test-framework timeout diagnostics. Record process/package, timestamp, reason, provable duration, Device, Release, Run, Attempt, Artifact, and traces Evidence.

ANR fingerprint is versioned: package/process + normalized reason + key main-thread frames + blocked-resource clue. When duration is unavailable, leave it null rather than zero. Deduplication follows Crash.

Collector may output raw system classification. Gate severity such as `CRITICAL` is calculated by a Rule from package criticality, count, and verification scope, avoiding hard-coding.

## 7. Memory Collector

Sampling supports PSS, RSS, Java Heap, Native Heap, Process Memory, and System Memory. Each sample contains metric, value, unit, process/package, capturedAt, source command, sampling quality, and Device/Run/Attempt.

Time series may use compressed JSON/CSV/Parquet Payload. Metadata stores window, sampling interval, sample count, and min/max summary for retrieval. Missing samples, process restarts, and collection overhead must be explicit.

Collector reports only objective values such as `PSS=420 MiB`. "BLOCK after three consecutive samples above 400 MiB" can exist only in a versioned Quality Rule.

## 8. Lifecycle, Retention, and Privacy

- Evidence follows the Release Audit period. Metadata and decision references must not be deleted before Payload.
- Tiering/expiration is policy-configured and checks legal hold and Quality Result references before execution.
- Cleanup writes an Audit Event and deletion inventory. Object deletion failure enters retryable reconciliation.
- Before upload, logs redact tokens, accounts, and personal data according to company policy. Raw high-sensitivity Evidence receives stricter permission.

### 8.1 Download Paths

The TDR-025 demonstration Profile streams GENERAL/RESTRICTED/HIGH through the existing Payload GET. GENERAL/RESTRICTED use `evidence:read`, available to all project roles; HIGH uses `evidence:read:sensitive`, restricted to QUALITY_OWNER/ADMINISTRATOR. OpenAPI `x-demo-permission-by-sensitivity` declares these conditional Profile permissions while preserving the default HIGH permission baseline. Every request revalidates user JWT, project and purpose and records Audit, returning no unauthenticated URL and exposing no local paths, credentials or raw device serial numbers in responses or logs. Task 4 provides local runtime downloads; database validation status is recorded in engineering verification. The default object-storage Profile semantics below continue to apply.

- GENERAL/RESTRICTED: Backend validates principal, project scope, permission, purpose, and retention/legal-hold state for each request, then may return a single-object Presigned Download URL valid for at most 60 seconds. It is a Bearer capability and may be reused by its holder before expiry. Controls are short TTL, least object permission, TLS, prohibition on logging it, and an Audit of the download request; the design does not claim user binding.
- HIGH: never return an object-storage Presigned URL to the client. The client calls GET `/api/v1/evidence/{evidenceId}/payload`; Backend/controlled Gateway revalidates user token, project scope, `evidence:read:sensitive`, purpose, and optional approval for every HTTP request, then streams from object storage with server-side credentials.
- HIGH response sets `Cache-Control: no-store`, safe Content-Disposition, a media-type allowlist, and rate/Range limits. It must not 3xx redirect to object storage or put token, object key, or internal URL in Log/Audit Payload.
- Before streaming, Audit records actor, Evidence ID, purpose, decision, request ID, and authorization basis. Transfer failure appends a result Event. Audit failure is fail closed.

A copied HIGH payload path carries no authorization. User B accessing a path previously used by User A is authorized under User B's identity and receives 403 without permission. A verifiably user-bound download Gateway may replace Backend Proxy only after a new TDR proves equivalent controls.

## 9. Failure Handling

- Local disk insufficient: Agent becomes DEGRADED, stops new work, and protects required Evidence.
- Upload failed: retain spool and session state and use exponential backoff; do not mark AVAILABLE.
- Checksum mismatch: REJECTED with diagnostics; re-upload creates a new session.
- Object exists but DB transaction failed: inventory reconciliation marks an orphan and safely deletes it or restores association.
- DB Metadata exists but object is missing: mark INTEGRITY_ERROR and prohibit new Evaluation for the related Release.
- Collector crashed: corresponding requirement FAILED while other Collectors continue; Run explicitly reports the absence.

## 10. Acceptance

- Every type has a Metadata schema, Payload example, and checksum revalidation.
- Duplicate Crash/ANR events may aggregate while raw Evidence remains traceable.
- A Memory threshold appears in neither Collector configuration nor code contract.
- Interrupted upload, checksum error, orphaned object, and missing object have recovery rehearsals.
- Unauthorized roles cannot obtain Evidence Payload. HIGH returns no Presigned URL.
- User B accessing User A's HIGH payload path is reauthorized and receives 403; responses and logs contain no object URL/token.

Evidence: Collector contract tests, real Crash/ANR/Memory samples, object-inventory reconciliation, ordinary-Evidence Presigned URL TTL Tests, HIGH Backend Proxy cross-user tests, log-leak scans, and upload-failure report.

## 11. Task 4 Local Runtime Interfaces and Recovery

This Profile is disabled by default. Explicitly configure `vsrqg.demo.evidence.enabled=true` and a pre-created absolute `vsrqg.demo.evidence.root` directory outside the repository, `static`, `public` and `wwwroot`. Startup and every file access check the root, ancestor links, regular files and service-account write permissions. POSIX rejects access permissions for other accounts/groups; Windows ACL trusts only the service account, SYSTEM and Administrators. This Profile sets Tomcat connection/upload idle timeouts to 30 seconds and disables swallowing the remaining request body after rejection. These controls provide neither WORM nor tamper resistance against administrators.

`vsrqg.demo.evidence.sensitivity` defaults to `RESTRICTED`; the operator can fix GENERAL/RESTRICTED/HIGH at startup. Agents cannot lower sensitivity themselves, and existing Sessions do not change with configuration. No retention deadline is set by default. Once `retention_until` is configured, expiry prevents download; legal hold preserves content while requiring the same current-principal permissions.

Create strictly reuses Agent Schema and returns `{uploadId,evidenceId,uploadUrl,expiresAt}`. `uploadUrl` is a same-Backend relative URI that the Agent resolves against its configured mTLS Backend origin. Existing Create/Complete have no lease/fencing fields: Create captures both under the Attempt lock, and PUT/Complete compare the current binding each time. Lock order is Agent → Run → Attempt → Session. PUT checks Server time again after streaming; Complete retains locks through Metadata, Audit and Outbox transaction commit.

LOG is at most 1 MiB, strict UTF-8 and declared `text/plain`; SCREENSHOT is at most 8 MiB, declared `image/png` with the fixed PNG signature. The file layer writes an exclusive temporary file using a 64 KiB buffer, then publishes received candidate bytes through same-directory hard-link create-only publication. Unsupported file systems fail explicitly without switching implementations. This file is not AVAILABLE. Complete verifies the same Session's actual size, SHA-256 and type against Create/Complete declarations before fixing Metadata. Empty, truncated, oversized, interrupted or conflicting retransmissions never overwrite an existing file. Complete with an incorrect type/hash records REJECTED. Failed transactions do not pretend that the file system and PostgreSQL commit atomically; an existing file survives transaction rollback for the same Session's retry and reconciliation.

The existing `:download` `reason` is purpose. `evidence_download_grant` records a request, not role permissions; it binds actor/project/evidence/purpose and returns `DOWNLOAD_GRANT_EXPIRED_NEW_KEY_REQUIRED` for the same key after 60 seconds. `DownloadGrant.url` is a URI reference in this Profile: `/api/v1/evidence/{evidenceId}/payload?grantId=…`. Each GET revalidates JWT, current project role, corresponding scope, grant owner/purpose/expiry and retention/legal hold. HIGH additionally requires `evidence:read:sensitive`. Audit commits before bytes are emitted. Range returns 416, no redirects are issued and responses use no-store.

`EvidenceReconciler` provides directly callable application operations: `reconcile(Set<evidenceId>)`, `backupInventory(Set<evidenceId>)` and `verifyRestored(List<EvidenceInventoryItem>)`, each bounded to 1–1000 fixed IDs. Inventories contain Evidence ID, Upload ID, size/SHA-256 and Metadata JCS digest without disk paths. Operations neither traverse arbitrary directories nor delete unknown files. Stop new writes before backing up PostgreSQL and the inventory's Payload files as a pair; restore both and call `verifyRestored` for each item. Tests exercise this with real `pg_dump`/`pg_restore` and a separate file directory.

Metadata queries, reconciliation and recovery append `evidence_integrity_observation`, exposing `INTEGRITY_ERROR` for missing/corrupt content. A closed Run's Result, Evidence Metadata and digests remain unchanged. `AttemptEvidence.resolve` accepts only Evidence for the same binding; `seal` closes unfinished Sessions inside the caller's Attempt transaction for subsequent Task 5 consumption.
