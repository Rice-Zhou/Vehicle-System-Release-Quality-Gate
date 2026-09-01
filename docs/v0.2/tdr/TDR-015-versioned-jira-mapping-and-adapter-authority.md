# TDR-015 — Versioned Jira Mapping Profile and Adapter Version Authority

- Status: Accepted
- Date: 2026-09-01
- Decision basis: Project Owner approval of the `M2-KD-2026-09-01-01` Written Spec Review on 2026-09-01
- Scope: M2.2 Jira status/severity Mapping Profile, Adapter Version Authority, and Sync version pinning
- Related decisions: [TDR-003](TDR-003-postgresql.md), [TDR-005](TDR-005-rest-openapi.md), [TDR-007](TDR-007-postgresql-job-outbox.md), [TDR-009](TDR-009-oidc-and-service-identities.md), [TDR-011](TDR-011-pilot-company-deployment-profiles.md), [TDR-014](TDR-014-bounded-jira-cli-pilot-adapter.md)

## 1. Why this technology was selected

V0.2 uses an immutable PostgreSQL Mapping Profile to store Jira status/severity mapping content and its digest, while an `IssueSourceRuntimeDescriptor` in code declares the sole Adapter Version. A Source stores the currently active Mapping Version, and every Sync Run pins both Adapter Version and Mapping Version.

Release, Manifest, Issue, Test, Evidence, and Traceability have structured relationships, transaction, historical-query, and consistency requirements, while MVP data volume is controlled. PostgreSQL is already the V0.2 authority for structured data, so adding one Project/Source-scoped, INSERT-only `issue_mapping_profile` fits current constraints better than introducing a second configuration system.

## 2. What problem it solves

All statuses in the real Jira Smoke normalized to `UNKNOWN`, showing that a synthetic default Map cannot explain the real project's workflow. The same run also exposed a mismatch between a manually entered Adapter Version and the existing acceptance record. This decision gives every Jira Source independent, auditable, replayable mapping history and removes multiple Adapter Version authorities across APIs, Operators, Seeds, and runtime code.

A Mapping Profile is Adapter implementation configuration, not a Core Entity. This decision does not change the V0.1 Core Contract, Issue/Traceability semantics, or release-centric architecture.

## 3. Why alternatives were not selected

- Repository-external YAML as the sole authority: historical Syncs cannot be replayed after an old file is lost, and transactional activation and database Audit are absent.
- Environment variables or a Spring Map: no content digest, Project Scope, immutable history, or Idempotency.
- Store one editable current Map in the database: UPDATE would let the historical meaning of the same Mapping Version drift.
- Accept Adapter Version through an API: the caller could claim a version that runtime code does not implement, creating a second authority.
- Configuration center or administration UI: the current single-project Pilot of at most 20 records does not justify the added platform cost.
- Regex, fuzzy, or contains matching: the same input could produce different results as rule order or implementation changes, breaking determinism.

## 4. Impact on V0.2

Add the immutable `issue_mapping_profile` table. `(source_id, mapping_version)` uniquely identifies a Profile, and a composite foreign key enforces Project isolation. `mapping_version` is `sha256:<64 lowercase hex>` over the RFC 8785 canonical definition. A database Trigger rejects UPDATE/DELETE, and reads verify the digest again.

Add the minimal authenticated activation operation `POST /api/v1/issue-sources/{sourceId}/mapping-profiles:activate`, requiring `issue:configure` and `Idempotency-Key`. The request submits only Schema and definition and cannot accept Mapping Version or Adapter Version. Profile insertion, Source activation, Audit, and Outbox complete in one transaction.

`StartIssueSync` pins both versions under the Source lock. The Worker permits the Jira Process to start only after validating Descriptor, Run, Profile, Project, Source, Schema, and digest. If the Profile is missing, integrity fails, Schema is unsupported, or versions mismatch, Jira call count is zero, the Run fails, and successful Cursor does not advance. Unknown status/severity still produces a Warning and maps to `UNKNOWN`.

## 5. Impact on future V0.3

V0.3 can add a Jira REST Adapter or internal Issue Adapter under the same Profile Authority, with each Source type declaring its supported Mapping Schemas. Expansion to multiple projects, all Issues, automatic scheduling, or Company requires separate governance for capacity, retention, data classification, service identity, and operational responsibility.

Historical Profiles, Sync Runs, Revisions, and Snapshots remain immutable. Future technology can replace the PostgreSQL access implementation or Adapter transport, but it must preserve version pinning, digest verification, Source/Project isolation, and fail-closed semantics.

## 6. How to migrate

Use a forward-only Expand Migration to create the table, constraints, indexes, and immutable Trigger. Do not seed real corporate Tokens or alter historical data. First complete synthetic Profile activation, Fixture Contract, and integrity tests; then obtain separate Owner authorization for a bounded real Jira retest.

When an Alias or mapping result changes, insert and activate a new Profile while old Runs continue with the old Profile. If Profile Schema, normalization algorithm, CLI argv, parsing protocol, or Adapter behavior changes incompatibly, upgrade Mapping Schema or Adapter Version at the relevant compatibility boundary and retain the old Runtime while historical compatibility is required.

## 7. How to test

Unit Tests cover RFC 8785, SHA-256, Unicode NFC, `Locale.ROOT`, Alias conflicts, invalid enums, unknown Schema, boundary limits, and unknown Tokens. PostgreSQL/Application Tests cover immutability, Project/Source isolation, authorization, idempotency, Audit, Outbox, transaction rollback, and rejection of caller-supplied versions.

Runtime Tests cover Profile A/B activation races, Run version pinning, five fixed failure diagnostics, zero Jira Process Runner calls for every version/integrity failure, no Cursor advancement after failure, and unchanged historical Snapshot digests. Security tests verify that logs, Problem Details, Git, and CI Artifacts do not expose definitions, real workflow Tokens, Issue content, paths, or Credentials.

## 8. How to deploy

No service, Broker, database, container, or UI is added. The Migration deploys with the existing Backend and PostgreSQL. The Jira CLI Pilot Adapter remains disabled by default and unavailable by default in Company Profile. Activating a real Profile is a separate authenticated and audited operation, not a Seed, environment variable, or Git configuration.

Empty-database, upgraded-database, repeat-Migration, Fixture Contract, and authorization tests must pass before deployment. Startup fails when the application and database Schema are incompatible; a hard-coded Map cannot be used silently.

## 9. How to recover from failure

A failed Profile activation transaction rolls back completely and leaves the old Profile active. If a new Profile produces unknown statuses, retain the `UNKNOWN_STATUS` Warning, then insert and activate another version with corrected Aliases. Do not modify the old Profile or historical Runs.

If the application cannot interpret an active Mapping Schema, remain fail-closed and prefer roll-forward. If the application must roll back, use an audited operation to reselect an older compatible Profile/version; do not delete the new Profile or overwrite historical Syncs, Revisions, or Snapshots. If sensitive Tokens or Credentials leak, stop real Sync, isolate Artifacts, follow the external security process, and retest before recovery.

## Re-evaluation triggers

Re-evaluate when an organization-wide dictionary must be shared across Sources, Profile count or read load exceeds the current PostgreSQL boundary, non-Jira Sources require a different mapping language, Company needs a self-service configuration UI, Mapping Schema changes incompatibly, or Adapter Runtime can no longer ship in the same version as the Backend. Re-evaluation must not silently change the frozen V0.1 architecture, Core Entities, or Traceability semantics.
