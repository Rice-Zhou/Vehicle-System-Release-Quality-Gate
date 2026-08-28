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

## Status Transition Rules

On 2026-08-24, the Project Owner approved Architecture Review `V0.2-AR-2026-08-23-01` and the five residual risks in Section 5 of the Owner acceptance checklist. TDR-001 through TDR-010 transition to `Accepted` in the same governance change; the acceptance state of one language or one TDR must not change alone.

Open a new TDR when a document's Re-evaluation Triggers occur; never change a decision silently.
