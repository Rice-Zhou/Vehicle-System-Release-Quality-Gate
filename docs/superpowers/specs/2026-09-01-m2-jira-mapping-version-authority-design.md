# M2.2 Jira Status Mapping and Adapter Version Authority Design

- Spec ID: `M2-KD-2026-09-01-01`
- Owner Design Direction: `APPROVED 2026-09-01`
- Written Spec Review: `APPROVED 2026-09-01`
- Architecture Baseline: V0.1 `0.1.0` (FROZEN) and V0.2 `0.2.0`
- Parent Governance: Chinese `e43d89d398165cb550cc7d3f6775a5d26383a407` / English `74f0508764b57e25dc081167ab3213852a03c38b`
- Scope: defines only the M2.2 Jira Mapping Profile and Adapter Version Authority; does not authorize implementation

## 1. Purpose and Trigger Evidence

The real Jira end-to-end read-only Smoke proved that the `Jira → Backend API/Worker → PostgreSQL → successful Cursor` path works, but all 20 normalized statuses were `UNKNOWN`. The same run exposed a mismatch between the manually recorded Source value `jira-cli-pilot-v1` and `jira-cli-pilot-adapter-v1` in the existing acceptance record.

This design closes two implementation-design gaps: it gives each Issue Source / Jira project an independent, immutable, auditable, versioned Mapping Profile; and it makes the runtime code Adapter Descriptor the sole authority for Adapter Version while the database stores only immutable snapshots.

This design does not change V0.1 Issue, Traceability, Release, Manifest, Evidence, Quality Result, or Fixed/Included/Verified semantics. A Mapping Profile is Source Adapter implementation configuration, not a new Core Entity.

## 2. Non-negotiable Boundaries

- A Mapping Profile belongs to exactly one Issue Source in one Project; activation relationships cannot be reused across Sources or Projects.
- One Profile governs both status and severity so that two independent Mapping Versions are not introduced.
- An unknown status remains mapped to `UNKNOWN` and produces `UNKNOWN_STATUS`; an unknown severity remains mapped to `UNKNOWN` and produces `UNKNOWN_SEVERITY`.
- An unknown value cannot default to `CLOSED`, `RESOLVED`, `PASS`, or any success semantics.
- Profiles are INSERT-only; UPDATE and DELETE are prohibited. Activating a new version cannot rewrite old Syncs, Revisions, or Snapshots.
- Adapter Version cannot be freely named by an API, environment variable, Operator, or Seed.
- Profile definitions cannot contain Issue title, person, URL, Description, Comment, Credential, or raw Issue Payload.
- Jira remains read-only, single-project, and bounded to at most 20 records. Company, Jira write-back, Task 5, merge, Tag, release, and production deployment remain blocked.

## 3. Options and Decision

Select “immutable PostgreSQL Profile + code Descriptor.” PostgreSQL stores immutable Profile content and its digest, while the Source stores the currently active Mapping Version. The code Descriptor declares the sole Adapter Version. Every Sync pins and verifies both versions. This option is auditable, replayable, and project-isolated.

A repository-external YAML file was not selected as the sole Authority because history cannot be explained after an old file is lost. Environment variables or a Spring Map were not selected because they lack versioning, Audit, and Project Scope. A configuration center or UI was not selected because no real MVP requirement justifies its cost.

The design adds only one small PostgreSQL Authority and one Adapter Runtime Descriptor. It does not add a UI, Broker, second database, configuration center, or microservice.

## 4. Logical Architecture

```text
Approved Mapping Definition
            ↓
Mapping Profile Validator + Canonicalizer
            ↓
Immutable issue_mapping_profile
            ↓ activate in one transaction
issue_source.mapping_version + Audit + Outbox
            ↓ pin at StartIssueSync
Issue Sync Run(adapterVersion, mappingVersion)
            ↓ open exact runtime
IssueSourceRuntimeRegistry
   ├─ verify Adapter Descriptor
   ├─ load exact Mapping Profile
   └─ verify profile digest/source/project
            ↓
JiraCliPilotAdapter + JiraIssueMapper
            ↓
Normalized Issue Revision + Warning
```

`IssueSourceRuntimeRegistry` belongs to Adapter infrastructure. It returns a version-pinned Runtime for one Sync Run. `RunIssueSync` continues to depend only on `IssueSourcePort`; Core and Snapshot remain unaware of Jira Profiles.

## 5. Data Model

Add the immutable implementation table `issue_mapping_profile`:

| Field | Constraint | Meaning |
|---|---|---|
| `id` | PK, `varchar(40)` | Internal identifier |
| `project_id` | FK, not null | Project Scope |
| `source_id` | composite FK, not null | Issue Source must belong to the same Project |
| `schema_version` | not null | Profile Schema |
| `mapping_version` | not null, `varchar(80)` | SHA-256 of the canonical definition |
| `definition` | `jsonb`, not null | Validated status/severity definition |
| `created_by` | FK, not null | Activation operator |
| `created_at` | not null | Authority write time |

Required constraints are `UNIQUE(source_id, mapping_version)`; `mapping_version` must match `^sha256:[0-9a-f]{64}$`; the `(source_id, project_id)` composite FK points to the same `issue_source`; a database Trigger rejects UPDATE and DELETE; the application computes the content digest at the boundary and verifies it again on read.

Existing `issue_source.mapping_version` remains the selector for the currently active version, not a second copy of Profile content. `issue_sync_run.mapping_version` and `normalized_issue.mapping_version` continue to store execution-time snapshots. Historical values cannot be bulk rewritten.

## 6. Profile Definition and Deterministic Normalization

Conceptual Schema:

```json
{
  "schemaVersion": "jira-mapping-profile/v1",
  "normalizationVersion": "unicode-nfc-trim-root-lower/v1",
  "unknownStatusPolicy": "MAP_TO_UNKNOWN_WITH_WARNING",
  "unknownSeverityPolicy": "MAP_TO_UNKNOWN_WITH_WARNING",
  "statusAliases": {
    "OPEN": ["open", "to do"],
    "IN_PROGRESS": ["in progress"],
    "RESOLVED": ["resolved"],
    "CLOSED": ["closed", "done"]
  },
  "severityAliases": {
    "CRITICAL": ["highest", "critical"],
    "HIGH": ["high"],
    "MEDIUM": ["medium"],
    "LOW": ["low", "lowest"]
  }
}
```

The example uses only synthetic generic Tokens and does not represent an actual corporate workflow. A real Profile enters PostgreSQL through the controlled API and is not committed to Git or a CI Artifact.

Token normalization has a fixed sequence: validate nonblank, no control characters, and within the length bound; Unicode NFC; trim leading and trailing Unicode whitespace; lowercase with `Locale.ROOT`; exact string match.

Regex, wildcard, contains, prefix, and fuzzy matching are prohibited. If two Aliases normalize to the same Token but target different values, reject the entire Profile. Target enums can only be existing non-`UNKNOWN` members of `IssueStatus` and `IssueSeverity`; `UNKNOWN` is produced only by the unmatched policy.

The Definition uses RFC 8785 canonical JSON to compute its digest:

```text
mappingVersion = "sha256:" + lowercaseHex(SHA-256(canonicalDefinitionBytes))
```

Project ID, Source ID, operator, and time are excluded from the digest. Semantically identical content can have the same digest, while activation relationships remain isolated by Source.

## 7. Single Authority for Adapter Version

Every runtime Adapter provides an `IssueSourceRuntimeDescriptor` containing at least source type, adapter ID, adapter version, supported Mapping Schemas, and Transport Version range.

The sole Adapter Version of the Jira CLI Pilot Adapter is `jira-cli-pilot-adapter-v1`. Database fields `issue_source.adapter_version` and `issue_sync_run.adapter_version` store only snapshots of that value. Configuration entry points cannot accept an Adapter Version.

An Alias or mapping-result change changes only Mapping Version. An incompatible Profile Schema or Token-normalization algorithm upgrades Mapping Schema and may upgrade Adapter Version when compatibility requires it. An incompatible CLI argv, field boundary, parsing protocol, or Adapter behavior upgrades Adapter Version. The Jira CLI executable version is recorded separately as Transport Version and never replaces Adapter Version.

## 8. Controlled Profile Activation Operation

MVP provides no administration UI. Add one minimal authenticated Backend operation:

```text
POST /api/v1/issue-sources/{sourceId}/mapping-profiles:activate
Scope: issue:configure
Headers: Idempotency-Key
Body: schemaVersion + mapping definition
Response: profileId + sourceId + schemaVersion + mappingVersion + activatedAt
```

The request cannot contain `mappingVersion` or `adapterVersion`. In one transaction, the Application performs Project authorization, Idempotency, Source lock, Profile validation and insertion, Source activation, Audit, and Outbox. Any failed write rolls back the whole transaction and leaves the old Profile active.

Responses, Audit, Outbox, logs, and Problem Details store only Profile ID, Schema Version, Mapping Version, and fixed diagnostics; they do not copy complete workflow Tokens. The Profile `definition` exists only in the controlled PostgreSQL Authority.

## 9. Sync Version Pinning and Race Behavior

`StartIssueSync` locks the Source in one transaction and copies current `adapter_version` and `mapping_version` into the Sync Run. Profile activation uses the same Source lock, so a partially activated Run cannot exist.

The Worker must open Runtime using the Sync Run's pinned values: select the sole Descriptor by Source Type; compare Descriptor Adapter Version with Run Adapter Version; load the exact Profile by `(source_id, mapping_version)`; recompute the canonical digest; verify Project, Source, Schema, and digest; create a `JiraIssueMapper` pinned to that Profile; and only then start the Jira CLI Process.

If Profile B is activated after Run A is queued, Run A continues with Profile A and new Run B uses Profile B. A Mapper cannot hot-switch within one Run.

## 10. Failure Semantics

| Scenario | Fixed diagnostic | Behavior |
|---|---|---|
| No active Profile | `MAPPING_PROFILE_NOT_CONFIGURED` | zero Jira calls; Run FAILED |
| Content and digest differ | `MAPPING_PROFILE_INTEGRITY_FAILED` | zero Jira calls; Run FAILED |
| Unsupported Schema | `MAPPING_SCHEMA_UNSUPPORTED` | zero Jira calls; Run FAILED |
| Adapter Version mismatch | `ADAPTER_VERSION_MISMATCH` | zero Jira calls; Run FAILED |
| Mapping Version mismatch | `MAPPING_VERSION_MISMATCH` | zero Jira calls; Run FAILED |
| Unknown status | `UNKNOWN_STATUS` Warning | Issue is `UNKNOWN`; Sync may succeed |
| Unknown severity | `UNKNOWN_SEVERITY` Warning | Severity is `UNKNOWN`; Sync may succeed |
| Invalid Profile input | 422 Problem Details | activation fails; old Profile remains active |

Every failure remains visible. There is no fallback to a hard-coded Map, no successful Cursor advancement, and no rewrite of failure into PASS.

## 11. Security and Privacy

- `issue:configure` is granted only to controlled configuration roles within a Project; `issue:sync` cannot implicitly configure a Profile.
- Validate external requests at the boundary; use parameterized SQL; enforce fixed Profile-size, Alias-count, and Token-length bounds.
- Git, Fixtures, CI Artifacts, and acceptance reports cannot contain real corporate workflow Tokens.
- The Profile API, logs, and Problem Details do not return the complete definition.
- Credential, Jira CLI config, Issue Payload, and Mapping Profile remain separate.
- Company Profile does not enable this Pilot Adapter by default; this design is not Company Ready Evidence.

## 12. Migration, Deployment, and Rollback

Implementation uses forward-only Expand: create the table, constraints, indexes, immutable Trigger, and new Application path. Migration does not guess corporate statuses, seed real Tokens, or alter historical Syncs, Revisions, or Snapshots.

Before enabling the new version, pass synthetic Profile activation and the Fixture Contract. A Jira Source without a controlled Profile causes a new Sync to fail explicitly. If a rolled-back application cannot interpret a new Mapping Version, it remains fail-closed and roll-forward is preferred. If the old application must be restored, a reviewed operation reselects an older compatible Profile/version; it does not delete the new Profile or overwrite historical Runs.

No service, Broker, database, container, or UI is added. Pilot continues to use the existing Backend and PostgreSQL.

## 13. Test Matrix

- Profile/Mapper: RFC 8785 canonicalization, SHA-256, Unicode NFC, `Locale.ROOT`, Alias conflicts, invalid enums, unknown Schema, oversized input, unknown Token Warning, and three digest replays.
- PostgreSQL/Application: Profile immutability, rejection across Project/Source, authorization, idempotency, Audit, Outbox, complete rollback on failure, rejection of caller-supplied versions, and empty/upgrade/repeat Migration.
- Sync/Runtime: Run A/Profile A and Run B/Profile B race; zero Jira Process Runner calls for all five version/integrity failures; failure does not advance Cursor; old Snapshot digest remains unchanged.
- Security/real retest: Secret/log scans contain no definition, Token, Issue content, URL, path, or Credential; Fixtures use only synthetic Aliases; a real retest requires renewed Owner authorization and remains read-only, single-project, at most 20 records, with redacted output.

## 14. Conditional Closure and Non-goals

Closing the two implementation conditions in `M2-2-JIRA-E2E-SMOKE-001` requires: Mapping Profile, digest, activation, version pinning, and Fixture regression all pass; Adapter Version comes only from the Descriptor; bilingual implementation/TDR/tests/Gates are paired; separate authorization for a real retest is obtained; and the real read-only retest has zero status Mapping Warnings. A newly encountered unknown status keeps the result `CONDITIONAL`.

This design does not close Company, Stale Job recovery, full Jira, REST Adapter, internal Issue API, Issue write-back, Release Issue Snapshot, Traceability, or M3 conditions, and it does not authorize production code.

## 15. Written Spec Review Gate

On 2026-09-01, the Project Owner explicitly approved the `M2-KD-2026-09-01-01` Written Spec Review, allowing the next stage to create a file-by-file, test-by-test, commit-by-commit Implementation Plan.

Approval of this written specification does not authorize production code, Migration, real Jira queries, Jira writes, Company, merge, Tag, release, or production deployment. The Implementation Plan and implementation execution continue to require separate authorization.
