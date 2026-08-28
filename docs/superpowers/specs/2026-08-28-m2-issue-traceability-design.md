# M2 Issue Snapshot and Traceability Kickoff Design

- Spec ID: `M2-KD-2026-08-28-01`
- Owner Design Direction: `APPROVED 2026-08-28`
- Written Spec Review: `PENDING`
- Architecture Baseline: V0.1 `0.1.0` (FROZEN) and V0.2 `0.2.0`
- Parent Implementation: Chinese `5117668c54970fbab3d830fc88fa983919756c8d` / English `a9af41e185e880f15f2c0a99cff0ea0c11787927`
- Planned Period: Weeks 7-11
- Capacity: one primary developer, 10-12 hours per week, with 20% contingency

## 1. Purpose

M2 extends the locked Release Manifest into an auditable Issue Snapshot and Traceability loop. It must prove that external Issues are normalized through Adapters and frozen in Release Snapshots that do not change with their sources; Issue→Commit→Build→Artifact relations are retained as append-only Revisions; Artifact→Release is derived only from the Locked Manifest; Fixed, Included, and Verified remain distinct; and historical Traceability Snapshots can be replayed deterministically.

M2 does not change the V0.1 Core Contract, Release-centric architecture, Manifest authority, first-class Evidence, Traceability semantics, Deterministic Quality Engine, Adapter/Plugin model, or ADR governance. The Company Evidence Archive remains open under the deferral decision and does not block this M2 Pilot work package.

## 2. Selected Approach

Use a deterministic fixture contract baseline plus a bounded, read-only real Jira CLI smoke test.

- Automated Gates depend only on synthetic, sanitized, versioned fixtures, never on an external network or corporate data.
- `JiraCliPilotAdapter` uses the already configured `jira` CLI to read one configured project, with at most 20 Issues per invocation, and is enabled explicitly only in the `PILOT` Profile.
- The real smoke test proves authentication, actual reads, and mapping, but does not replace deterministic fixture tests for pagination, 429, 5xx, duplicate pages, and interrupted recovery.
- The internal Issue source currently uses a recorded fixture Adapter through the same Port. Without a real API contract, it must not be described as an integrated corporate internal system.
- A future Company integration migrates to a direct HTTPS Jira REST Adapter or an equivalent approved Adapter without changing Core, Snapshot, or Traceability semantics.

Rejected alternatives:

- Fixture-only: cannot prove that the real Jira identity and read path work.
- Live-first CI: network, permission, and changing data would break deterministic Gates and make the corporate system a build dependency.
- Parse Jira CLI credentials/configuration directly: expands secret exposure and turns private external-tool configuration into an application contract.
- Query all Issues now: there is no capacity, throttling, retention, or sensitive-field governance Evidence yet.

In the next independent governance commit after this Written Spec Review is approved, this technology choice must be created and accepted as `TDR-014` (Bounded Jira CLI Pilot Adapter and Fixture Contract). Implementation cannot begin before that commit.

## 3. Scope

### 3.1 Included

- M2 Issue, Traceability, and operations package boundaries.
- `IssueSourcePort`, one Normalization Contract, and one Adapter contract suite.
- Fixture Adapter, Jira CLI Pilot Adapter, and recorded internal-source fixture Adapter.
- `issue_source`, `issue_sync_run`, `issue_sync_cursor`, versioned `normalized_issue`, and immutable `release_issue_snapshot`.
- `source_commit`, `build_record`, and three append-only Edge Revision types.
- Traceability Verification Run, Gap, immutable Snapshot Edge/Gap, and stable digest.
- Controlled Issue Sync, CI/Build Fact ingestion, Snapshot creation, and Traceability verify/query APIs.
- RBAC, Audit, Idempotency, Outbox/Background Job, failure recovery, and PostgreSQL Constraint Tests.
- Manually triggered smoke Evidence over a small set of real Jira Issues, with both a default and hard limit of 20.

### 3.2 Excluded

- Jira or internal-system write-back, transition, comment, assignment, or attachment reads.
- Binding to a real corporate internal Issue API.
- Any cross-project or `project IS NOT EMPTY` query.
- Persisting Jira Description, Comment, Attachment, user email, raw Payload, or credential.
- AI matching, fuzzy attribution, graph databases, or intelligent cross-repository inference.
- Device, Agent, Test Run, real Verified Evidence, or M3 capability. M2 records the missing facts required for Verified and never fabricates Verified.
- Merging `main`/`release`, Tagging, release, or production deployment.

## 4. Architecture and Module Responsibilities

```text
Synthetic Fixture ─ FixtureIssueSourceAdapter ─┐
                                                ├─ IssueSourcePort
Real Jira ─ JiraCliPilotAdapter ───────────────┤
Internal Fixture ─ RecordedInternalAdapter ────┘
                       ↓
             Issue Sync Application
                       ↓
     Normalized Issue Revision + Successful Sync Run
                       ↓
        Immutable Release Issue Snapshot
                       ↓
CI/Build Fact Ingress → Typed Edge Revision Validator
                       ↓
       Verification Run + Traceability Gaps
                       ↓
         Immutable Traceability Snapshot
                       ↓
            Query API / Acceptance Export
```

### Issue Module

Owns Source configuration, Adapter Port, Sync Run, Cursor, mapping version, Normalized Issue Revision, and Release Issue Snapshot. It does not parse Git/Build provenance, decide Fixed/Included/Verified, or expose Jira DTOs to another module.

### Traceability Module

Owns Source Commit, Build Record, typed Edge Revision, proof validation, Confidence, Verification Run, Gap, Snapshot, and path query. It reads Issue Snapshot, Artifact, and Locked Manifest facts through read-only Ports and must not write Issue, Manifest, or Release tables directly.

### Shared Infrastructure

Reuses M1 PostgreSQL, transactions, Idempotency, Audit, Outbox, RBAC, Problem Details, and Time/ID Ports. M2 adds no Kafka, Redis, graph database, or second structured data source.

## 5. Issue Source and Adapter Contract

```text
IssueSourcePort
  capabilities()
  fetchChanges(cursor, filter, pageSize)
  fetchByIds(sourceIssueIds)
  health()
```

M2 does not implement `updateIssue`; an invocation must return `CAPABILITY_NOT_SUPPORTED`. Every `IssuePage` contains Issues, next cursor, source watermark, observedAt, mapping version, and an explicit terminal marker.

Normalized Issue requires source, sourceIssueId, title, severity, status, sourceVersion, sourceReference, observedAt, and mappingVersion. Status permits only `OPEN`, `IN_PROGRESS`, `RESOLVED`, `CLOSED`, and `UNKNOWN`. An unknown value maps to `UNKNOWN` with a Warning and never defaults to `CLOSED`.

Replaying the same `(source, sourceIssueId, sourceVersion, mappingVersion)` must not create a duplicate Revision. A new source version, mapping version, or tombstone creates a new Revision and never changes an old row.

## 6. Jira CLI Pilot Boundary

Jira CLI is only an Adapter transport and is not part of Core or the database contract. Execution must use a `ProcessBuilder` argument array or an equivalent API without shell concatenation. No PowerShell/cmd string may concatenate caller input.

The allowed command shape is fixed to list/search in the configured controlled project and enforced by code:

```text
jira issue list --project <configured-project> --paginate 0:<1..20> --plain --no-headers --no-truncate --columns KEY,SUMMARY,STATUS,PRIORITY,UPDATED --delimiter <U+001F>
```

The project key comes from repository-external configuration and is validated as a stable project identifier at the boundary. JQL, search text, additional flags, and executable path cannot be supplied arbitrarily by an API caller. `--raw`, `--comments`, `--history`, and columns outside the allowlist are prohibited because a host schema probe proved that `--raw` returns out-of-scope fields including Description, Comment, Reporter, and Assignee. The Adapter does not read the Jira CLI configuration file; it invokes the configured CLI, whose credential remains under the CLI's external security mechanism.

Configuration contract:

```text
VSRQG_JIRA_PILOT_ENABLED=false
VSRQG_JIRA_CLI_PATH=<absolute path, required when enabled>
VSRQG_JIRA_PROJECT=<single project key, required when enabled>
VSRQG_JIRA_MAX_ISSUES=20
VSRQG_JIRA_TIMEOUT=PT15S
```

The default and V0.2 hard limit for `VSRQG_JIRA_MAX_ISSUES` are both 20. A value below 1 or above 20, a non-absolute CLI path, a non-file path, a non-`PILOT` mode, or a missing project key causes startup failure. stdout is byte-bounded and parsed only in memory with ASCII Unit Separator (`U+001F`). Every record must have exactly five columns, the line count cannot exceed the configured limit, and fields cannot contain control characters. A column-count, encoding, or bound violation fails the Sync. stderr becomes only a fixed diagnostic code and digest; the original text is not retained. Logs, CI Artifacts, Git, Acceptance Records, and Problem Details must not expose the complete command, stdout, Issue titles, personal data, server URL, local configuration path, or credentials.

The real smoke report records only execution time, Adapter/mapping version, query limit, returned count, success/failure code, sanitized schema digest, and Sync Run ID. Real Issue data remains only in the controlled Pilot PostgreSQL database and is not committed.

## 7. Sync, Transactions, and Recovery

Sync states:

```text
QUEUED → RUNNING → SUCCEEDED
                 ↘ FAILED
```

1. `POST /api/v1/issue-sources/{sourceId}/sync` creates the Sync Run, Audit, and Background Job in one transaction and returns `202`.
2. A worker locks the Job and current successful Cursor, then invokes the Adapter.
3. Each page transaction writes Normalized Issue Revisions and a page checkpoint. A page commits only when complete.
4. After every page succeeds, one transaction marks the Sync Run `SUCCEEDED` and advances the successful Cursor/Watermark.
5. A page failure marks the Sync Run `FAILED` and retains fixed diagnostics and counts, but does not advance the successful Cursor.
6. A retry creates a new Sync Run from the last successful Cursor and relies on source-version idempotency; it never rewrites failed history.

The real Jira CLI Pilot has one bounded page in V0.2 but uses the same state machine. The fixture contract suite covers multiple pages, duplicate pages, interrupted pagination, 429 Retry-After, bounded 5xx retry, 401/403, mapping errors, timeouts, invalid output, and tombstones.

## 8. Release Issue Snapshot

`POST /api/v1/releases/{releaseId}/issue-snapshots` accepts only a `SUCCEEDED` Sync Run and versioned filter reference. The Snapshot transaction:

- verifies Release, Project Scope, Sync Source, and age policy;
- selects exact Normalized Revisions in stable `(source, sourceIssueId)` order;
- materializes fields used for the Release, raw status token, mapping version, source version/reference, and fact digest;
- computes the content digest;
- writes Audit and Outbox; and
- rejects UPDATE/DELETE after commit.

A later Jira change, mapping change, or Sync can only produce a new Snapshot. STALE data carries explicit age. Data older than policy permits is rejected by default as new Gate input and cannot masquerade as a successful current sync.

## 9. Traceability Facts, Revisions, and Snapshots

M2 persists three writable append-only Edge Revision types:

- `ISSUE_COMMIT`
- `COMMIT_BUILD`
- `BUILD_ARTIFACT`

`ARTIFACT_RELEASE` has no writable table and is derived only from `release.locked_manifest_id → manifest_artifact`. Every logical Edge has a stable edgeId. A proof, status, confidence, validator version, or reason change inserts the next revision.

A CI/Build service identity submits versioned fact batches through `POST /api/v1/traceability/facts:ingest`. A request requires Idempotency-Key, project scope, provider reference, source revision, artifact SHA-256, and proof reference. A caller cannot submit Fixed/Included/Verified Boolean conclusions.

A Verification Run validates endpoint existence, proof, Manifest membership, and policy version. It produces `VALID`, `INVALID`, `CONFLICT`, or `ERROR`, plus `HIGH`, `MEDIUM`, `LOW`, or `UNKNOWN` Confidence. A missing required edge creates an exact `TraceabilityGap`.

A Snapshot materializes complete Edge/Gap facts, source revision, proof reference, validator/policy version, reason, and fact digest and computes a content digest in stable ordinal order. Replay reads only the Snapshot, never the latest Edge Revision or an external system.

## 10. Fixed, Included, and Verified

- Fixed: at least one valid Issue→Commit Edge satisfies policy.
- Included: a continuous valid path exists from that Commit through Build and Artifact to the target Locked Release.
- Verified: Included is true and a PASS Test Result on the target Release satisfies the Issue's verification criteria and required Evidence.

M2 does not yet implement M3 Test Result/Evidence. It may prove Fixed, Included, or an exact Verified gap, but cannot create a real-release Verified=true claim from fixtures. Automated inference may only create a LOW/UNKNOWN candidate. Manual linkage requires actor, reason, proof, and Audit and does not automatically become HIGH.

## 11. API and Permissions

Retain approved endpoints:

- `POST /api/v1/releases/{releaseId}/issue-snapshots`
- `GET /api/v1/releases/{releaseId}/traceability`
- `POST /api/v1/releases/{releaseId}/traceability:verify`

M2 adds backward-compatible operational endpoints:

- `POST /api/v1/issue-sources/{sourceId}/sync` — `issue:sync`
- `GET /api/v1/issue-sync-runs/{syncRunId}` — `issue:read`
- `POST /api/v1/traceability/facts:ingest` — `traceability:ingest`, Service Identity only

Every write requires Idempotency-Key, Project Scope, Audit, and stable Problem Details. Sync and verify return `202` plus an operation ID. Missing or invisible resources uniformly return 404; state conflicts return 409; mapping/domain errors return 422; and external unavailability returns 503. The OpenAPI compatibility baseline only adds Operations and does not change an existing Path/Method/Permission/Request semantic.

## 12. Security, Privacy, and Retention

- CLI credentials, PATs, cookies, configuration contents, and environment values never enter Git, database business fields, logs, or test reports.
- The real Jira Adapter does not read attachments, comments, full Description, email, or irrelevant custom fields.
- Allowlisted column text exists only in a bounded process buffer and is discarded after mapping. Requesting or parsing Jira raw JSON is prohibited.
- APIs and logs never return Adapter stderr, stack traces, or raw external responses.
- Fixtures are synthetic or fully sanitized and cannot copy real titles, people, URLs, or project identifiers.
- Normalized Issue Revision, Release Snapshot, Traceability Revision/Snapshot, and Audit are retained for the audit period. A failed Sync retains no raw Payload.
- Expansion to all Issues requires a separate review of pagination, throttling, capacity, sensitive fields, retention, and deletion semantics.

## 13. Implementation Increments

### M2.0 — Contract and Quality Baseline

Create Issue/Traceability package markers, architecture dependency rules, permission matrix, backward-compatible OpenAPI additions, synthetic fixtures, and the M2 Gate shell. Exit when Contract/Architecture/Secret Scan pass and no Jira DTO leaks into Core.

### M2.1 — PostgreSQL Authority Baseline

Add forward-only Migration, PK/FK/UNIQUE/CHECK, append-only triggers, indexes, and real PostgreSQL Constraint Tests. Exit when clean/upgrade/repeat migration, cross-Project/FK rejection, Revision uniqueness, and immutability rejection pass.

### M2.2 — Adapters, Sync, and Real Jira Smoke

Implement the Port, fixture/internal recorded Adapters, Jira CLI Pilot Adapter, bounded worker, mapping, and Sync/Cursor. Exit when the shared contract suite passes and a locally authorized Pilot reads no more than 20 real Issues with a PASS smoke summary; CI remains independent of real Jira.

### M2.3 — Release Issue Snapshot

Implement Snapshot creation, stable digest, age policy, Audit/Outbox, and API. Exit when historical Snapshot bytes and digest remain unchanged after an external Issue or mapping changes.

### M2.4 — CI/Build Facts and Edge Revisions

Implement service ingestion, Source Commit, Build Record, three typed Edge Revisions, proof validation, and conflict retention. Exit when duplicate batches are idempotent, re-verification only inserts a Revision, and Artifact→Release has no second source.

### M2.5 — Verification, Gaps, and Snapshot

Implement path verification, Fixed/Included/Verified separation, Gap, immutable Snapshot, and query API. Exit when any missing required edge yields Included=false with the exact gap and old Snapshot replay never reads a current Revision.

### M2.6 — Failure, Recovery, and Acceptance Package

Create one M2 Gate, backup/restore, pagination/throttling/timeout/transaction failure drills, a real Jira smoke summary, known-chain/gap reports, and an Owner acceptance-record candidate. Failure cannot become PASS automatically, and a new record begins at `PENDING`.

## 14. Test and Acceptance Matrix

| Scenario | Required Result | Evidence |
|---|---|---|
| Same input through two Adapters | Same Normalized Contract | Contract suite + golden digest |
| Jira CLI missing/unauthenticated/timeout | Sync FAILED; Cursor unchanged | failure injection report |
| At most 20 real Jira Issues | Read-only Sync SUCCEEDED; no raw data in Git/log | redacted smoke summary |
| Pagination interruption/429/5xx | bounded retry; final failure does not advance watermark | fixture timeline |
| Unknown status/severity | UNKNOWN + warning, never CLOSED/PASS | mapping report |
| Same source version replayed | no duplicate Revision | PostgreSQL test |
| Jira changes later | old Release Snapshot digest unchanged | replay report |
| Only Issue→Commit exists | Fixed can be evaluated; Included/Verified false | gap report |
| Build→Artifact missing | Included=false with exact gap | known-chain test |
| Edge re-verified | new Revision; old Snapshot unchanged | revision/snapshot test |
| Artifact→Release | derived only from Locked Manifest | schema/query proof |
| Same Snapshot replayed three times | identical path, gap, confidence, and digest | replay report |
| Cross-Project write | 403 or DB FK/constraint rejection | security/constraint test |
| Audit/Outbox failure | complete business transaction rollback | rollback test |

## 15. Scope Control and Cut Line

If M2 is more than one week late, remove in order: management UI, automatic scheduled sync, extra query filters, manual-link UI, nonessential metrics, and formatted reports. Never remove the shared contract for both Adapters, real PostgreSQL, historical Snapshot immutability, typed Edge Revision, Manifest authority for Artifact→Release, Fixed/Included/Verified separation, Gaps, Audit/Idempotency, fail-closed behavior, or recovery tests.

A fixture PASS never hides a real Jira smoke failure. Fixture PASS proves replayability; live Smoke proves the current external read path. Report them separately. An internal-source fixture PASS does not establish real internal-system integration, and a bounded Jira smoke test cannot establish `Company Ready`.

## 16. Migration, Deployment, and Rollback

The M2 branches derive from the paired unmerged M1 HEADs. Independent development and testing may proceed while M1 remains `CONDITIONAL`, but merge, Tag, release, and deployment remain prohibited. M2 Migration is forward-only and starts with Expand. Application rollback does not modify an applied Migration; use a rehearsed backup restore when required.

Pilot deployment adds PostgreSQL tables, a background Job, and an optional Jira CLI Adapter but no Broker or service. The Operator installs and authenticates Jira CLI outside the repository. Disabling `VSRQG_JIRA_PILOT_ENABLED` prevents new live syncs while retaining historical Syncs, Snapshots, and Audit. A retry creates a new Sync/Verification Run and never overwrites history.

Migration to a Jira REST Adapter first runs the shared fixture contract suite and a bounded live comparison to align mapping digests, then switches the source Adapter version. Historical Snapshots are not rewritten; rollback switches back to the former Adapter version.

## 17. Stop Conditions

Stop and submit a Finding, TDR revision, or ADR Proposal if implementation requires:

- changing Fixed/Included/Verified or Artifact→Release semantics;
- placing Jira/CLI DTOs in the Core Contract;
- abandoning append-only Revision or immutable Snapshot replay;
- adding a second Artifact→Release source;
- converting external error, UNKNOWN, or a missing Edge into success;
- reading or committing credentials or raw corporate data; or
- removing a non-negotiable item to fit the five-week capacity.

## 18. Written Spec Review Gate

After commit, the Project Owner reviews this written specification. Only explicit approval of `M2-KD-2026-08-28-01` authorizes creation of the file-by-file, test-by-test, commit-by-commit Implementation Plan. Written-spec approval does not authorize production code, a real Jira write, merge, Tag, release, or production deployment; every later authorization remains independent.
