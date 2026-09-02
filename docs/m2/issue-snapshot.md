# M2.3 Release Issue Snapshot Operations Specification

## 1. Purpose and Boundary

M2.3 materializes the non-tombstone Issues observed for a Release by one specific Issue FULL Sync as an immutable Snapshot. It consumes only the Release, Locked Manifest, Issue Source, Sync Run, Observation, and Normalized Revision already fixed in PostgreSQL. The creation path does not read Jira and does not change the V0.1 Core Contract, Release-centric Authority, Evidence, or Traceability definitions.

The write endpoint may be enabled in Pilot. Company mode has not passed independent acceptance and is forced off by the configuration layer; an environment variable cannot bypass that restriction.

## 2. Endpoint and Authorization

- Endpoint: `POST /api/v1/releases/{releaseId}/issue-snapshots`
- Authorization: OAuth scope `issue:snapshot`, with the caller assigned the `ENGINEER`, `RELEASE_MANAGER`, or `ADMINISTRATOR` role in the target Project.
- Request header: required `Idempotency-Key`, from 1 through 128 characters.
- Request body: only `sourceId` is accepted. A caller cannot supply the Sync Run, Mapping Version, Adapter Version, filter, or canonicalization version.
- Success response: HTTP 201 with the Snapshot ID, Release ID, fixed Sync Run, Snapshot Version, SHA-256 content digest, selected count, and creation time.

Missing resources, denied access, and a disabled write endpoint retain resource-hiding semantics. A missing Locked Manifest or eligible FULL Run returns a fixed 409 conflict. Responses and logs must not expose an Issue title, raw token, source reference, JQL, external URL, or credential.

## 3. Pilot Selection Rules

The default maximum Sync age for Pilot is fixed at `PT24H`. The system locks the Release and Source in one transaction and then selects only that Source's latest `SUCCEEDED`, `FULL` Run. It must not fall back to an older Run when the selected Run is ineligible. The completion time cannot be in the future, and the interval from completion to Snapshot creation cannot exceed `PT24H`.

Snapshot membership comes only from that Run's `issue_sync_run_item` rows. The system verifies the Project, Source, Run metadata, Observation count, Mapping Version, and revision-local fact digest. It then excludes tombstones and uses `release-issue-snapshot-jcs/v1` to produce stable bytes and a stable digest. Header, Items, Audit, Outbox, and the idempotency response are written in the same transaction.

## 4. Fixed Diagnostics and Response

The 422 `ISSUE_SNAPSHOT_INVALID` response exposes only these fixed violation codes:

- `SYNC_RUN_STALE`: the latest FULL Run is in the future or exceeds the Pilot age policy. Complete a new FULL Sync before retrying.
- `SYNC_OBSERVATION_INTEGRITY_FAILED`: Run metadata, Observation membership, or counts are inconsistent. Preserve database and Gate evidence; never modify historical rows manually.
- `SNAPSHOT_INTEGRITY_FAILED`: a revision-local fact or Snapshot read-back digest is inconsistent. Disable the write endpoint immediately, preserve evidence, and review the event as a data-integrity incident.

The Gate itself outputs only the commit, check, status, test count, and fixed diagnostic. `POSTGRESQL_RUNTIME_UNAVAILABLE` means the local host has no usable container runtime; it is not a passing test result and must be closed by Linux/Docker CI bound to the exact commit.

## 5. Replay and Acceptance

Run these commands from the repository root:

```powershell
pwsh -NoProfile -File scripts/tests/m2-issue-snapshot-gates.tests.ps1
pwsh -NoProfile -File scripts/m2/verify-issue-snapshot.ps1
```

The Gate always runs seven checks in this order: migration, sync-observation, snapshot-canonical, snapshot-integration, snapshot-replay, contracts, and acceptance. If any check fails, the overall status must be `FAILED` and every failing check must be listed; no check may be skipped silently. Against real PostgreSQL, the Replay test saves baseline canonical bytes and digest, adds a new Revision, activates a new Mapping, completes a new FULL Sync, and rereads the old Snapshot after each change for byte-for-byte and digest equality.

When Docker is unavailable locally, preserve the `POSTGRESQL_RUNTIME_UNAVAILABLE` failure result and use the existing `M1 Backend` GitHub Actions workflow on the bound exact commit. Do not add a Jira secret or external write permission, and do not fabricate a local PASS. Acceptance belongs in a separate Acceptance Record and remains `PENDING` until the Owner decides.

## 6. Disablement and Recovery

When an integrity anomaly, repeated failure, or Pilot write stop is required:

1. Set `VSRQG_ISSUE_SNAPSHOT_ENABLED=false` and restart the service. Existing materialized Snapshots remain available for replay.
2. Preserve the failed commit, fixed Gate summary, CI Run, and test-report locator without sensitive content.
3. Resolve the fault and pass all seven checks, including Linux/Docker CI on the exact commit.
4. Set `VSRQG_ISSUE_SNAPSHOT_ENABLED=true` and restart only after the Project Owner independently reviews the recovery scope and Evidence.

This procedure restores only the Pilot Snapshot write endpoint. It does not authorize Company mode, real Jira writes, merge, Tag, release, or production deployment.
