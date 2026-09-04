# Technology Decision Records

TDRs record replaceable implementation decisions inside the frozen V0.1 boundary. A TDR has no authority to modify the Core Contract. If a decision affects Release, Manifest, Evidence, Traceability, Quality semantics, module responsibility, or an authoritative source, it must become an ADR Proposal.

| TDR | Decision | Status |
|---|---|---|
| [TDR-001](TDR-001-modular-monolith.md) | Use a modular monolith Backend | Accepted |
| [TDR-002](TDR-002-kotlin-spring-boot.md) | Use Kotlin/JVM + Spring Boot for the Backend | Accepted |
| [TDR-003](TDR-003-postgresql.md) | Use PostgreSQL for structured data | Accepted |
| [TDR-004](TDR-004-s3-compatible-evidence-storage.md) | Use S3-compatible storage for Evidence Payloads | Accepted |
| [TDR-005](TDR-005-rest-openapi.md) | Use REST + OpenAPI 3.1 for external APIs | Accepted |
| [TDR-006](TDR-006-agent-pull-protocol.md) | Agent initiates long polling to claim work | Accepted |
| [TDR-007](TDR-007-postgresql-job-outbox.md) | Use PostgreSQL Job/Outbox instead of a Broker | Accepted |
| [TDR-008](TDR-008-versioned-yaml-quality-rules.md) | Define Rules with YAML + restricted AST | Accepted |
| [TDR-009](TDR-009-oidc-and-service-identities.md) | Use OIDC + separate service identities | Accepted |
| [TDR-010](TDR-010-containerized-vm-deployment.md) | Use containerized VM/small-platform deployment | Accepted |
| [TDR-011](TDR-011-pilot-company-deployment-profiles.md) | Pilot / Company dual-mode deployment profiles | Accepted |
| [TDR-012](TDR-012-evidence-archive-acceptance-operations.md) | Evidence Archive acceptance operations | Accepted |
| [TDR-013](TDR-013-controlled-local-file-identity.md) | Controlled local file identity and Windows argument bridge | Accepted |
| [TDR-014](TDR-014-bounded-jira-cli-pilot-adapter.md) | Bounded Jira CLI Pilot Adapter and Fixture Contract | Accepted |
| [TDR-015](TDR-015-versioned-jira-mapping-and-adapter-authority.md) | Versioned Jira Mapping Profile and Adapter Version Authority | Accepted |
| [TDR-016](TDR-016-materialized-release-issue-snapshot.md) | Materialized Release Issue Snapshot and Sync Observation Ledger | Accepted |
| [TDR-017](TDR-017-build-provenance-envelope.md) | Build Provenance Envelope and PostgreSQL Typed Edge Revision | Accepted |
| [TDR-018](TDR-018-postgresql-async-traceability-snapshot.md) | PostgreSQL Asynchronous Traceability Verification and Immutable Snapshot | Proposed |

## Status Transition Rules

On 2026-08-24, the Project Owner approved Architecture Review `V0.2-AR-2026-08-23-01` and the five residual risks in Section 5 of the Owner acceptance checklist. TDR-001 through TDR-010 transition to `Accepted` in the same governance change; the acceptance state of one language or one TDR must not change alone.

On 2026-08-28, the Project Owner approved the `M2-KD-2026-08-28-01` Written Spec Review, authorizing `TDR-014` to transition to `Accepted` in paired bilingual governance commits. This acceptance authorizes only M2 implementation planning; it does not authorize production code, Jira writes, merge, Tag, release, or production deployment.

On 2026-09-01, the Project Owner approved the `M2-KD-2026-09-01-01` Written Spec Review, authorizing `TDR-015` to transition to `Accepted` in paired bilingual governance commits. This acceptance authorizes only creation of an Implementation Plan; it does not authorize production code, Migration, real Jira queries, Jira writes, Company, merge, Tag, release, or production deployment.

On 2026-09-02, the Project Owner approved the `M2-KD-2026-09-02-01` Written Spec Review, authorizing `TDR-016` to transition to `Accepted` in paired bilingual governance commits. This acceptance authorizes only creation of an Implementation Plan; it does not authorize production code, Migration, real Jira, Company, M2.4, merge, Tag, release, or production deployment.

On 2026-09-03, the Project Owner approved the written specification through `APPROVE M2-KD-2026-09-03-01 WRITTEN SPEC REVIEW`, authorizing `TDR-017` to transition to `Accepted` in paired bilingual governance commits. This acceptance permits only creation of an independent Implementation Plan; it does not authorize production code, Migration, real Jira, real company CI, Company, M2.5, merge, Tag, release, or production deployment.

On 2026-09-04, the Project Owner approved the design direction for `M2-KD-2026-09-04-01`. `TDR-018` remains `Proposed` pending Written Spec Review. Design-direction approval does not authorize an Implementation Plan, production code, Migration, real Jira/CI, Company, M3, merge, Tag, release, or production deployment.

Open a new TDR when a document's Re-evaluation Triggers occur; never change a decision silently.
