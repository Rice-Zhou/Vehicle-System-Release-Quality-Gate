# M1 Release Identity and Manifest Authority Kickoff Design

- Spec ID: `M1-KD-2026-08-24-01`
- Owner Design Direction: `APPROVED 2026-08-24`
- Written Spec Review: `APPROVED 2026-08-24`
- Architecture Baseline: `V0.2-AR-2026-08-23-01`
- Design Freeze Tags: `v0.2.0-design-zh` / `v0.2.0-design-en`
- Planned Period: Weeks 3–6
- Capacity: one primary developer, 10–12 hours per week, with 20% Contingency

## 1. Purpose

M1 turns the frozen Release Identity and Manifest Authority design into the first runnable, testable, and recoverable implementation increment. It must prove that the system can create an independent Release, register and validate a Manifest Revision, safely Lock the authoritative Manifest under concurrency, and export the immutable Manifest and validation report through the API.

This design governs implementation order and acceptance evidence. It does not prescribe class names, function decomposition, or personal coding style. No implementation may change the V0.1 Core Contract, Release-centric architecture, Manifest Authority, first-class Evidence, Traceability, Deterministic Quality Engine, Adapter/Plugin model, or ADR governance.

## 2. Selected Decomposition

M1 uses vertical increments organized by business invariant. Each batch covers the required contract, persistence, application behavior, API, tests, and operational evidence so that it can be reviewed and rolled back independently.

The following alternatives were rejected:

- Technical-layer decomposition: completing database, Backend, and tests separately delays integration risk until the end of M1.
- Demo-first hardening: this creates temporary identity, weak transactions, missing Audit, or false-success paths that do not meet the company-level project requirement.

## 3. M1 Scope

M1 includes:

- A Kotlin/JVM + Spring Boot Modular Monolith skeleton.
- PostgreSQL Migration and real-database Constraint Tests.
- The M1-required subset of Release, Release State History, Manifest Revision, Manifest Validation, Artifact, Project, Principal, Project Assignment, Audit Event, Idempotency Record, and Outbox Event.
- Basic OIDC boundaries, fixed RBAC, Service Identity, and Audit.
- Release/Manifest Endpoints from the frozen OpenAPI.
- V0.2 Manifest Schema, RFC 8785 JCS, UTF-8, SHA-256, Validation, and concurrent Lock.
- Locked Manifest and Validation Report export.

M1 excludes:

- UI.
- Jira/Internal Issue Adapters and Traceability Snapshot.
- Device, Test Agent, Test Run, or Evidence Payload.
- Quality Engine or Rule execution.
- Kafka, Kubernetes, Redis, Graph Database, or Microservice decomposition.
- Binding to a company-specific IdP product; M1 implements the standard OIDC boundary and verifies it with a controlled Test Issuer, while company-environment integration remains a pre-deployment condition.

## 4. Implementation Batches

### M1.0 — Engineering and Quality Baseline

Deliverables:

- Backend and module skeletons, establishing at least Release, Manifest, Identity/Audit, and shared-infrastructure boundaries.
- Pinned JDK, Kotlin, Spring Boot, Gradle, PostgreSQL, and Testcontainers versions.
- Local PostgreSQL development environment; Object Storage remains deferred until M3 needs Evidence Payloads and is not deployed early in M1.
- CI runs compilation, unit tests, real PostgreSQL Integration Tests, OpenAPI/Schema Contract Tests, architecture-dependency tests, and Secret Scan.

Exit evidence:

- A clean workspace can start by following documentation and pass a Health/Readiness smoke.
- Module-dependency tests prevent cross-module access that bypasses Application Ports.
- Existing Contract Tests continue to pass and the V0.1 Manifest Schema hash remains unchanged.

Rollback:

- This batch contains only the engineering skeleton and development infrastructure. Removing the new application modules returns to the frozen documentation baseline without persistent data.

### M1.1 — PostgreSQL Authority Baseline

Deliverables:

- Flyway forward-only Migrations.
- M1 tables, PKs, FKs, UNIQUE, CHECK, Composite FKs, immutability controls, and required indexes.
- A single data owner for Release, Manifest, Artifact, Identity, Audit, Idempotency, and Outbox.
- Schema Export, Migration Tests, and Constraint Integration Tests.

Exit evidence:

- Migration succeeds on an empty database, and a repeated migration makes no changes.
- A supported prior Schema state can upgrade.
- PostgreSQL rejects invalid FKs, duplicate identities, cross-Project references, and UPDATE/DELETE against immutable records.
- A Migration failure stops startup; the application cannot bypass database constraints.

Rollback:

- Roll back the application to a compatible image without modifying an applied Migration. An irreversible failure uses a rehearsed backup restore, not manual reverse SQL.

### M1.2 — Identity, RBAC, and Audit Boundary

Deliverables:

- Standard OIDC Principal mapping and separate Service Identity.
- A fixed M1 Role/Permission Matrix with Project Scope.
- Write-operation Audit with request ID, actor, reason, resource identity, and outcome.
- Test environments use a controlled Test Issuer; a production Profile fails startup when issuer, audience, or credential is missing.

Exit evidence:

- Unauthenticated access returns 401; unauthorized or cross-Project access returns 403.
- Access is rejected when Token issuer, audience, expiry, or signature validation fails.
- A high-risk write transaction rolls back entirely if Audit persistence fails.
- Logs, database Fixtures, and configuration contain no plaintext Secret.

Rollback:

- Roll back the application while retaining Principal/Audit history. Never fall back to anonymous access, shared personal Tokens, or a default Admin.

### M1.3 — Release Identity

Deliverables:

- Create and query Release; initial state history, Audit, and Outbox commit in the same transaction.
- `POST /api/v1/releases` and `GET /api/v1/releases/{releaseId}`.
- `Idempotency-Key`, request digest, original-response replay, conflict detection, and optimistic concurrency.
- Release ID is a system-generated opaque stable identifier.

Exit evidence:

- The same Principal, Endpoint, Key, and request digest create one Release and replay the original response.
- The same Key with a different digest returns 409 and creates no second Release.
- External APK, Branch, or Build changes do not modify an existing Release Identity.
- If any create-transaction step fails, Release, History, Audit, and Outbox have no partial writes.

Rollback:

- Roll back the application version while retaining created Release and Audit records. Incorrect business data is corrected only through an explicit governance process; Audit history is not physically deleted.

### M1.4 — Manifest Revision, Validation, and Lock

Deliverables:

- `POST /api/v1/releases/{releaseId}/manifests`, Validate, and Lock Endpoints.
- V0.2 Manifest Schema validation, RFC 8785 JCS canonical bytes, UTF-8 without BOM, and SHA-256 content digest.
- Immutable pre-Lock Revisions; Lock revalidates integrity and commits the Release reference, state history, Audit, and Outbox in one transaction.
- Artifact Identity/Checksum Validation Results and a stable Validation Report.

Exit evidence:

- Semantically identical JSON with different property order produces the same digest; changing Artifact array order produces a different digest.
- Missing required, non-NFC strings, noncanonical numbers, incorrect checksums, and unsupported Schemas are rejected.
- The same idempotent request returns the same Manifest Revision.
- Only one of two concurrent operators can Lock; the loser receives 409 and no partial write occurs.
- External APK, Branch, Build, or source Manifest changes cannot modify Locked content.

Rollback:

- A failed pre-Lock Revision may retain a FAILED/REJECTED record; Locked content is never rewritten. Different content requires a new Release.

### M1.5 — Integrated M1 Acceptance

Deliverables:

- One API flow performs Create Release → Register Manifest → Validate → Lock → Export.
- Acceptance package containing the Locked Manifest, Validation Report, Release State History, and Audit Timeline.
- M1 Runbook covering startup, Migration, common failures, data export, application rollback, and database recovery.

Exit evidence:

- Contract, Unit, Integration, Security, Concurrency, and Smoke Tests all pass.
- An independent PostgreSQL instance completes the flow from an empty database and, after backup restore, exports the same Locked Manifest digest.
- The Owner can judge M1 through the API and archived evidence without directly modifying the database.

Rollback:

- Integrated acceptance does not modify authoritative data. On failure, register a Finding and keep M1 incomplete; do not claim completion from partial PASS results.

## 5. Data and Control Flow

```text
Authenticated Principal
  → Release API
  → Application authorization / idempotency
  → Release transaction
  → Release + State History + Audit + Outbox
  → Manifest registration
  → Schema validation + canonicalization + checksum validation
  → immutable Manifest Revision + Validation Report
  → concurrent Lock transaction
  → Locked Manifest authority + Release reference + Audit + Outbox
  → immutable export
```

Transport performs only protocol mapping. Application Use Cases coordinate the authentication result, authorization, idempotency, and transaction. Domain owns state and authority invariants. Persistence Adapters enforce database contracts and do not hide business decisions in ORM callbacks.

## 6. Failure and Recovery Principles

- Input errors return stable RFC 9457 Problem Details and never false success.
- Authorization failures do not reveal whether an invisible resource exists.
- Idempotency-digest conflicts and concurrent state conflicts return 409.
- Schema-valid requests that fail domain validation return 422 with machine-readable violations.
- Writes fail when PostgreSQL is unavailable; no in-process cache may claim deferred success.
- Audit and Outbox belong to the same business transaction; their failure rolls back the whole operation.
- Unknown failures log a request ID without exposing stack traces, Tokens, configuration, or sensitive external responses.
- Data inconsistency isolates the Release and rejects Lock instead of continuing through a permissive fallback.

## 7. Tests and Acceptance Evidence

Every batch defines a failing test before implementing the minimum behavior. Test layers include:

1. Domain Unit Tests: state transitions, Identity, Revision, digest, and Lock invariants.
2. Application Tests: authorization, idempotency, transaction rollback, and error mapping.
3. PostgreSQL Integration Tests: Migration, Constraints, concurrent Lock, and immutability protection, with no H2 substitute.
4. API Contract Tests: implementation matches the frozen OpenAPI requests, responses, permissions, idempotency, and Problem Details.
5. Security Tests: OIDC validation, cross-Project rejection, Secret Scan, and log inspection.
6. Smoke/Recovery Tests: clean-environment startup, flow execution, application rollback, and database restore.

Each evidence record includes command, version, Git commit, start/end time, exit code, failure count, and Artifact path. Screenshots are supplemental; machine-readable reports are the primary evidence.

## 8. Incremental Owner Acceptance

| Gate | Owner Acceptance Focus | Technical Evidence | Failure Behavior |
|---|---|---|---|
| M1.0 | Buildable engineering baseline, clear boundaries, no excess infrastructure | Build/Contract/Module Test | Stop M1.1 |
| M1.1 | PostgreSQL is the only structured source of truth | Migration/Constraint/Schema Export | Prohibit business Endpoints |
| M1.2 | Default deny, permissions, and Audit are proven | Security/RBAC/Audit Test | Do not expose write APIs |
| M1.3 | Release Identity is stable and transactionally complete | Idempotency/Concurrency/Transaction Test | Do not enter Manifest |
| M1.4 | Manifest Authority and Lock invariants hold | Canonicalization/Checksum/Lock Test | Do not declare M1 complete |
| M1.5 | End-to-end acceptance package is complete and recoverable | API E2E/Export/Restore Report | Register a Finding and re-test |

For each batch, the Owner accepts only whether its objective, boundary, and evidence are satisfied; Codex owns code organization and technical implementation. Batch acceptance does not replace integrated M1.5 acceptance.

## 9. Git and Bilingual Governance

- Chinese implementation and evidence documentation use the Chinese feature branch; equivalent English documentation uses the paired English branch.
- Non-Markdown Artifacts must be identical; English Markdown must contain no Han characters.
- Each Commit contains one explainable increment and passes its target tests before commit.
- Each batch pushes candidate branches first; after Pair Verification, merge separately into `main` and `release`.
- Do not modify or move `v0.2.0-design-zh` / `v0.2.0-design-en`; M1 acceptance uses new paired milestone Tags.
- Any conflict with the Core Contract, Manifest Authority, or another frozen semantic stops implementation and requires an ADR proposal.

## 10. Schedule and Cut Line

| Week | Objective |
|---|---|
| Week 3 | M1.0; start M1.1 |
| Week 4 | complete M1.1; complete M1.2 |
| Week 5 | complete M1.3; start M1.4 |
| Week 6 | complete M1.4; execute M1.5 |

If M1.1 or M1.2 slips by more than one week, first remove nonessential local convenience tools, extra report formats, and noncritical observability enrichment. Never remove real PostgreSQL tests, transactions, authorization, Audit, idempotency, Manifest canonicalization, checksum, concurrent Lock, or recovery evidence. M1 does not enable UI or company-specific platform optimization.

## 11. Definition of Done

M1 is complete only after M1.0 through M1.5 all pass, evidence is archived, bilingual documentation is paired, Git commits are traceable, residual risks are registered, and the Owner completes integrated M1 acceptance.

After written review of this specification, the next step is to generate a file-by-file, test-by-test, commit-by-commit M1 Implementation Plan. That plan may decide implementation details but cannot expand this specification's scope.
