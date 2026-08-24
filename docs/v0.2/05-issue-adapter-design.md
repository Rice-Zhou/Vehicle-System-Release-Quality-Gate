# 05 — Issue Adapter Design

## 1. Boundary

Core Domain knows only Normalized Issue and Release Issue Snapshot. It does not know Jira field IDs, JQL, internal-system status codes, pagination, or authentication methods.

```text
Jira API ── Jira Adapter ──┐
                           ├─ IssueSourcePort → Normalized Issue → Snapshot
Internal API ─ Adapter ────┘
```

## 2. Unified Port

Conceptual interface:

```text
IssueSourcePort
  capabilities() → SourceCapabilities
  fetchChanges(cursor, filter, pageSize) → IssuePage
  fetchByIds(sourceIssueIds) → IssueBatch
  updateIssue(command, expectedSourceVersion) → ExternalUpdateResult
  health() → SourceHealth
```

`IssuePage` must contain normalized issues, next cursor, source watermark, synchronization time, and mapping version. `updateIssue` is a controlled optional capability; when unsupported it explicitly returns CAPABILITY_NOT_SUPPORTED.

## 3. Normalized Issue

Required: source, sourceIssueId, title, severity, status, snapshot timestamp, and source version/reference. Optional: description summary, fixVersion, component, assignee reference, labels, required, and verification criteria.

Standard states exist only for cross-source queries: OPEN, IN_PROGRESS, RESOLVED, CLOSED, UNKNOWN. Preserve original state and mapping version in the Snapshot. UNKNOWN must not default to CLOSED.

Mapping configuration is versioned and included in the synchronization report. A missing field produces a Mapping Warning or Mapping Error; an error in a required field fails the synchronization run.

## 4. Synchronization Mechanism

1. A background job reads the last successful cursor/watermark.
2. The Adapter retrieves changes page by page, validates, and maps them.
3. Each page idempotently upserts a new Normalized Issue version by source version.
4. Advance the cursor atomically only after all pages succeed; a partial failure does not advance the final watermark.
5. A Release Snapshot may reference only a successfully completed sync run.

Initial full synchronization and incremental synchronization use the same data model. External deletion is represented by a tombstone version and never physically deletes historical Snapshots.

## 5. Jira Adapter

### Integration and Authentication

- Use a supported version of the company Jira REST API over HTTPS.
- Prefer OAuth 2.0/service credentials. If necessary, inject PAT through Secret Manager.
- Secrets do not enter Git, Manifest, plaintext database fields, or logs.

### Query and Pagination

- Use configurable JQL for incremental queries ordered by `updated` plus a stable tie-breaker.
- Request an explicit field set instead of all fields.
- Follow Jira pagination token/startAt/maxResults until the server indicates completion; do not assume a stable total.
- Store query, watermark, last issue key, and Jira API/mapping version for recovery.

### Rate Limits and Retry

- Honor `Retry-After` on 429. Use bounded exponential backoff with jitter for 5xx/connection errors.
- Do not infinitely retry 401/403, JQL errors, or field-mapping errors; expose them as operator-visible failures.
- After the retry limit, mark Sync Run FAILED, retain acquired diagnostics, and do not advance the successful cursor.

### Jira → Normalized Mapping

| Jira | Normalized | Rule |
|---|---|---|
| `key` | `sourceIssueId` | Stable reference preserved exactly |
| `summary` | `title` | Required; strip invisible control characters |
| `priority`/severity custom field | `severity` | Versioned mapping; unknown becomes UNKNOWN |
| `status` | `status` | Configured mapping; unmapped becomes UNKNOWN |
| `fixVersions` | `fixVersion` | Factual field only; does not define Release |
| `components` | `component` | Normalized list |
| `updated` + changelog/version | `sourceVersion` | Construct a stable opaque token; preserve a number, timestamp, or ETag as a lossless string |

A Jira state change creates a new Normalized version. Existing Release Snapshots remain unchanged.

### Issue Update

Updates use an explicit command allowlist and expected source version and record before/after state, actor, and external response reference. `sourceVersion` is used only for equality/concurrency within one Source and is never ordered across Sources; ordering uses synchronization observation time and a local sequence. V0.2 prioritizes reads and snapshots. Automatically changing Jira state is not required for the Release Gate.

## 6. Internal Issue System Adapter

Implement the same Port, Normalized model, Sync Run, and error semantics. Private fields remain only in the adapter mapping layer. If incremental cursors are unsupported, use an update-time window plus ID deduplication and mark the consistency capability in Sync Report.

Before production, provide authentication method, pagination termination, rate-limit rules, state/severity mappings, version-identifier source, deletion semantics, and a test sandbox or recorded fixture.

## 7. External Unavailability

- Retain the latest successful Snapshot but mark the current request STALE and show its age and source.
- When creating a new Release Issue Snapshot, policy explicitly decides whether STALE data is allowed. By default, Gate input rejects data older than the configured limit.
- Do not present old data as a successful current synchronization and do not swallow external errors.
- Completed historical Quality Results are unaffected by current external state.

## 8. Versioning and Observation

Each Sync Run stores adapter version, API version, mapping version, filter version, cursor, counts, warnings, errors, and duration. Metrics include success rate, latency, 429, mapping error, and snapshot age. Logs correlate by sourceIssueId/requestId and are redacted.

## 9. Acceptance

- Jira and internal systems pass the same contract-test suite.
- Interrupted pagination, 429, 5xx, 401, unknown state, and duplicate pages each have deterministic outcomes.
- Replaying the same source version creates no duplicate Issue.
- Later Jira changes do not alter historical Release Snapshot/Quality Result.
- Core module dependency scans contain no Jira SDK/DTO.

Evidence: Adapter contract tests, mapping golden files, fault-injection report, sample synchronization report, and proof of historical Snapshot immutability.
