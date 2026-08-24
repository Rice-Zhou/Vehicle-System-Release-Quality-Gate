# 09 — Evidence and Collector Design

## 1. First-Class Entity Principle

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
