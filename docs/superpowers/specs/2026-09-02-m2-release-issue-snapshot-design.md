# M2.3 Release Issue Snapshot Design

- Spec ID: `M2-KD-2026-09-02-01`
- Owner Design Direction: `APPROVED 2026-09-02` (Option A)
- Written Spec Review: `PENDING`
- Architecture Baseline: V0.1 `0.1.0` (FROZEN) and V0.2 `0.2.0`
- Parent Governance: Chinese `5b6d04e87456a9d28e5dedd9ec776d7d644365bb` / English `f2c81f7083cec0c35b3dfc43db23103bf8803d54`
- Scope: defines only the M2.3 Release Issue Snapshot implementation architecture; it does not authorize implementation

## 1. Purpose and Evidence Gap

M2.2 can persist external Issues as append-only `normalized_issue` Revisions through versioned Adapters and Mapping Profiles, but the current data model cannot prove which Sync actually observed a Revision. If M2.3 creates a Snapshot using only `observed_at` or the current latest Revision, concurrent Syncs, reused Revisions, or later Mapping changes make the historical selection unprovable.

This design adopts Owner-approved Option A: a PostgreSQL transactional materialized Snapshot with a minimal Sync Observation Ledger. A Snapshot pins the exact Revisions observed by one successful complete-result Sync, materializes the fields required by the Release Gate, and computes a stable digest. Later changes to Jira, Mapping Profiles, Syncs, or source data must not change existing Snapshot bytes, ordinals, or digests.

This design does not change V0.1 Issue, Release, Manifest, Evidence, Traceability, Quality Result, or Fixed/Included/Verified semantics. `issue_sync_run_item` is a Source Adapter implementation ledger, not a new Core Entity.

## 2. Non-negotiable Boundaries

- A Snapshot belongs to one Project, one Release, one Issue Source, and one `SUCCEEDED` `FULL` Sync Run.
- Only a Release with a Locked Manifest may create an Issue Snapshot used as Gate input; Artifact-to-Release Authority remains derived only from the Locked Manifest.
- Snapshot creation must not call Jira, a CLI, an internal Issue API, or any other external system.
- Snapshot Headers, Items, and content digests are insert-only; UPDATE and DELETE are forbidden.
- Later Issue Revisions, Mapping Profiles, Adapter Versions, Syncs, or Release state changes must not rewrite an old Snapshot.
- `UNKNOWN` status/severity stays visible and must not become CLOSED, RESOLVED, PASS, or any other success semantic.
- A Snapshot does not decide Fixed, Included, or Verified; M2.4/M2.5 consume it and produce Traceability Facts/Gaps.
- No new service, Broker, database, object storage, management UI, or scheduled job is introduced.
- Company, real Jira queries, Jira writes, M2.4, merge, Tag, release, and production deployment remain blocked.

## 3. Option Comparison and Decision

Use a “Sync Observation Ledger + PostgreSQL Materialized Snapshot.” `persistPage` associates each exact `normalized_issue` Revision with its Sync Run in the existing page transaction; Snapshot creation reads only that immutable association and materializes stable content. This adds one small table and a few columns, while allowing FK, transaction, and digest checks to prove provenance, completeness, and historical immutability together.

“Query the latest Revision as of Sync completion time” was rejected because time boundaries cannot prove that a Revision belonged to the run. “Store only Revision ID references in the Snapshot” was rejected because output bytes would still depend on long-term availability of referenced rows and query behavior. “Store only a JSON Blob” was rejected because it weakens relational constraints, Project isolation, and queries, and creates a second representation of structured facts.

## 4. Logical Architecture and Data Flow

```text
IssueSourcePort page
        ↓ one page transaction
normalized_issue Revision + issue_sync_run_item observation
        ↓ terminal success
sealed SUCCEEDED/FULL issue_sync_run
        ↓ POST /releases/{releaseId}/issue-snapshots
authorization + idempotency + Release/Source locks
        ↓
resolve latest eligible Sync + age/integrity checks
        ↓
stable selection and RFC 8785 canonical bytes
        ↓ one transaction
release_issue_snapshot + items + Audit + Outbox
        ↓
immutable replay input for M2.4/M2.5
```

The API input continues to follow the approved `IdentifierInput`: `sourceId` in the body identifies the Issue Source. In the same transaction, the Application pins the latest eligible successful full-result Sync for that Source. The Idempotency Record stores the resolved `syncRunId` and response, so a replay cannot drift when a newer Sync appears.

## 5. Data Authority and Relationship Model

### 5.1 Sync Observation Ledger

Add append-only `issue_sync_run_item`:

| Field | Constraint | Meaning |
|---|---|---|
| `sync_run_id` | PK part, composite FK | Sync Run that actually observed the Revision |
| `ordinal` | PK part, `>= 0` | Stable cross-page ordinal |
| `project_id` | required, composite FK | Project Scope |
| `source_id` | required, composite FK | Must match the Source of the Sync and Issue |
| `issue_id` | required FK | Exact `normalized_issue` Revision |
| `source_issue_id` | required | Validates identity uniqueness within the Run |
| `observed_at` | required | Adapter Observation Time |
| `created_at` | required | Authority write time; excluded from fact digest |

Required constraints are `PRIMARY KEY(sync_run_id, ordinal)`, `UNIQUE(sync_run_id, issue_id)`, and `UNIQUE(sync_run_id, source_issue_id)`. Composite FKs keep Run, Source, Issue, and Project aligned. A Trigger rejects UPDATE/DELETE.

In one transaction, `persistPage` resolves or inserts the `normalized_issue` Revision and inserts its Observation. If the same Revision already exists, its exact ID must still be associated with the current Run. A page replay may reuse only identical ordinal/identity pairs; any conflict rolls back that page and makes the Sync fail explicitly.

### 5.2 Complete Sync Result Semantics

Add `result_set_mode` and `filter_reference` to `issue_sync_run`:

- `result_set_mode` permits only `FULL` or `DELTA`. It is pinned by the runtime code Descriptor and cannot be supplied by the caller.
- M2.3 accepts only `FULL`. The current non-incremental Jira CLI Pilot query is `FULL`. A future incremental Adapter must first create a provable complete Materialized View and must not present a single DELTA as a complete Snapshot.
- The MVP `filter_reference` is the server-owned versioned value `all-relevant-issues/v1`. It represents all relevant Issues returned under the current Source configuration and includes tombstone observations; it is not Jira JQL, a URL, or user input.
- Versions, mode, filter, watermark, counts, completion time, and diagnostics of a terminal Run must be sealed by database constraints/Triggers and must not change after `SUCCEEDED`/`FAILED`.

### 5.3 Materialized Snapshot

Continue using `release_issue_snapshot` and `release_issue_snapshot_item`. A forward-only Migration adds replay Header fields such as `source_id`, `source_watermark`, `adapter_version`, `mapping_version`, `canonicalization_version`, `age_policy_version`, `observed_count`, `tombstone_count`, and `selected_count`, plus `UNIQUE(release_id, sync_run_id, filter_reference)`.

Items continue to materialize `source_issue_id`, title, severity, status, raw status token, source/mapping version/reference, observation time, and `fact_digest`. `all-relevant-issues/v1` selects only observations where `tombstone=false`; the Header retains observed/tombstone/selected counts so the exclusion is auditable. An empty complete result set is a valid fact. `selected_count=0` is not automatically interpreted as Release PASS.

## 6. Snapshot Creation Transaction and Concurrency

`POST /api/v1/releases/{releaseId}/issue-snapshots` requires `issue:snapshot` and `Idempotency-Key`. The single-transaction order is fixed:

1. Validate identity, Project Scope, and request digest; invisible Release/Source resources uniformly return 404.
2. Get or create the Idempotency Record; a completed request returns its original Snapshot.
3. Lock the Release and Issue Source in stable order and verify that the Release has a Locked Manifest.
4. Select and immediately pin the latest `SUCCEEDED`, `FULL`, same-Project Sync Run for that Source.
5. Validate terminal sealing, Mapping/Adapter/Filter/Watermark, Observation counts, and age policy.
6. Lock the Release Snapshot Version sequence; return an existing Snapshot for the same `(release, sync, filter)`.
7. Sort by `(source_id, source_issue_id, issue_id)`, filter tombstones, validate every `fact_digest`, and assign ordinals.
8. Generate canonical bytes and content digest, then write Header, all Items, Audit, Outbox, and Idempotency response.
9. Recompute counts/digest from the pending write model; any write or validation failure rolls back everything.

“Latest” is first selected by terminal success, complete result set, and Project/Source Scope, then validated for age and integrity. If the selected latest Run is stale or corrupt, the operation must fail; it must not skip that Run and silently fall back to an older one.

Concurrent requests with different Idempotency Keys converge on one logical Snapshot through Release/Source locks and the unique constraint. Snapshot Version starts at `1` and increases monotonically per Release. It must not be allocated using an unlocked `MAX(version)+1` query.

## 7. Canonicalization and Digest

The fixed version is `release-issue-snapshot-jcs/v1`, using RFC 8785 canonical JSON and SHA-256:

```text
contentDigest = "sha256:" + lowercaseHex(SHA-256(canonicalSnapshotBytes))
```

The canonical Header contains schema/canonicalization version, Project/Release/Snapshot version, Sync/Source identity, source watermark, Adapter/Mapping/Filter/Age Policy version, and the three counts. Items contain the stable ordinal and every materialized fact field. `created_at`, request time, actor, Idempotency Key, database transaction ID, and random Snapshot ID are excluded from the digest.

Stored UTF-8 strings participate in canonicalization without rerunning Jira Mapping. Time uses UTC RFC 3339 at fixed microsecond precision. Enums use existing uppercase Tokens. RFC 8785 orders JSON object keys, and items may only follow canonical ordinal order. The Repository must be able to reconstruct canonical bytes from a stored Snapshot and revalidate the digest.

## 8. Age Policy and Failure Semantics

Pilot defaults to `maxSyncAge=PT24H` with Policy Version `issue-snapshot-age/v1`. The Company Profile must configure and independently accept this value before enablement. Age is measured from trusted Backend Clock `sync_run.completed_at` to transaction start; `age <= maxSyncAge` is eligible. Unparseable, missing, future, or over-limit times fail closed.

| Scenario | Fixed diagnostic | HTTP / behavior |
|---|---|---|
| Release/Source missing or invisible | `RESOURCE_NOT_FOUND` | 404 without existence disclosure |
| Release has no Locked Manifest | `RELEASE_MANIFEST_NOT_LOCKED` | 409; no Snapshot |
| No successful full Sync | `ELIGIBLE_SYNC_NOT_FOUND` | 409; no DELTA/FAILED fallback |
| Sync is stale or time is invalid | `SYNC_RUN_STALE` | 422; stale data is not current input |
| Observation count/identity mismatch | `SYNC_OBSERVATION_INTEGRITY_FAILED` | 422; full rollback |
| Fact or Snapshot digest mismatch | `SNAPSHOT_INTEGRITY_FAILED` | 422; full rollback and alert |
| Idempotency Key reused for another request | `IDEMPOTENCY_CONFLICT` | 409 |
| Audit/Outbox/database failure | fixed persistence diagnostic | visible failure and full rollback |

This operation has no external dependency. It must not invent a Jira 503 or hide an integrity failure with the current latest Revision, a cache, or a JSON fallback.

## 9. Security, Privacy, and Audit

- `issue:snapshot` is restricted to controlled roles within a Project. Project Scope is checked at Release, Source, Sync, and Issue boundaries.
- SQL is parameterized. Source ID, Release ID, Idempotency Key, and configuration lengths are validated at the boundary.
- Logs, Problem Details, Audit, Outbox, and CI Evidence exclude title, raw status token, source reference, Jira URL, JQL, Credential, and complete Snapshot payload.
- Audit/Outbox store only Snapshot/Release/Source/Sync IDs, versions, counts, digest, and fixed event type.
- Real Issue content remains only in controlled PostgreSQL Domain tables; Fixtures use synthetic content.
- Snapshot creation does not broaden Jira permissions, read Jira CLI configuration, or create a Company Ready claim.

## 10. Migration, Deployment, and Recovery

Use a forward-only Expand Migration to add the Observation Ledger, constraints, indexes, immutable/terminal-seal Triggers, and required Snapshot/Sync columns. Historical M2.2 Runs have no Observation Ledger and must be marked ineligible for M2.3; the Migration does not infer or backfill membership from time.

Deployment order is Migration, compatible application, then M2.3 Endpoint. No service, Broker, container, or external configuration center is added. The feature is enabled only in the Pilot Profile by default. Application startup validates canonicalization and age-policy versions; unknown versions fail closed.

A failed transaction rolls back without a partial Snapshot. If an application defect affects creation, disable new M2.3 writes and preserve all existing Snapshots; prefer roll-forward. Database recovery uses the existing PostgreSQL backup/restore mechanism and proves recovery by reconciling reconstructed Snapshot digests, FKs, counts, Audit, and Outbox without changing historical Migrations.

## 11. Test and Evidence Matrix

- Unit: RFC 8785, SHA-256, UTF-8, UTC precision, stable ordering, empty set, tombstone exclusion, counts, and three-run digest replay.
- PostgreSQL: Observation composite FK/uniqueness, page transaction, immutable Trigger, terminal Run sealing, atomic Snapshot children, cross-Project rejection, UPDATE/DELETE rejection, and clean/upgrade/repeat Migration.
- Application: authorization, hidden 404, Locked Manifest, latest eligible Run pinning, FULL/DELTA, age boundaries, idempotency conflict, concurrent version allocation, and full rollback on Audit/Outbox failure.
- Replay: after creating a Snapshot, insert a new Issue Revision, activate a new Mapping, and complete a new Sync; old Snapshot bytes, items, ordinals, counts, and digest remain identical.
- Integrity: missing Observation, duplicate identity, bad count, or tampered fact/content digest fails explicitly without Jira access or a current-latest Revision read.
- Security: logs, Problem Details, Audit, Outbox, Git, and CI Artifact scans contain no Issue content, JQL, URL, path, or Credential.
- Gate Evidence: real PostgreSQL Integration Test, canonical replay report, transaction failure report, security scan, bilingual Pair Gate, and immutable Git/CI locators.

## 12. V0.2, V0.3, and Non-goals

For V0.2, this design adds one implementation ledger and a minimal Expand Migration, providing replayable Issue input to M2.4/M2.5 without changing the Core Contract. It does not ingest Traceability Edges, infer Fixed/Included/Verified, or produce a Quality Result.

For future V0.3, each Issue Source may produce its own Snapshot and another separately reviewed Release Input Snapshot may compose multiple sources. Incremental Adapters may add provable full-state materialization. The current MVP does not prebuild an aggregation service, CDC, Event Broker, Snapshot UI, cross-Release queries, or full Jira ingestion.

## 13. Technology Decision Delegation

`TDR-016` records this design's key technology decision and answers selection rationale, problem, alternatives, V0.2/V0.3 impact, migration, testing, deployment, and failure recovery. It may transition to `Accepted` only after Owner approval of this Written Spec Review.

## 14. Written Spec Review Gate

The Project Owner approved the direction of Option A on 2026-09-02, but this written specification remains `PENDING`. Only a separate `APPROVE M2-KD-2026-09-02-01 WRITTEN SPEC REVIEW` allows creation of a file-by-file, test-by-test, commit-by-commit Implementation Plan.

Approval of this written specification does not authorize production code, Migration, real Jira queries, Jira writes, Company, M2.4, merge, Tag, release, or production deployment. The Implementation Plan and execution continue to require separate authorization.
