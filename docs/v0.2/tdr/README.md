# Technology Decision Records

TDRs record replaceable implementation decisions inside the frozen V0.1 boundary. A TDR has no authority to modify the Core Contract. If a decision affects Release, Manifest, Evidence, Traceability, Quality semantics, module responsibility, or an authoritative source, it must become an ADR Proposal.

| TDR | Decision | Status |
|---|---|---|
| [TDR-001](TDR-001-modular-monolith.md) | Use a modular monolith Backend | Proposed for V0.2 Review |
| [TDR-002](TDR-002-kotlin-spring-boot.md) | Use Kotlin/JVM + Spring Boot for the Backend | Proposed for V0.2 Review |
| [TDR-003](TDR-003-postgresql.md) | Use PostgreSQL for structured data | Proposed for V0.2 Review |
| [TDR-004](TDR-004-s3-compatible-evidence-storage.md) | Use S3-compatible storage for Evidence Payloads | Proposed for V0.2 Review |
| [TDR-005](TDR-005-rest-openapi.md) | Use REST + OpenAPI 3.1 for external APIs | Proposed for V0.2 Review |
| [TDR-006](TDR-006-agent-pull-protocol.md) | Agent initiates long polling to claim work | Proposed for V0.2 Review |
| [TDR-007](TDR-007-postgresql-job-outbox.md) | Use PostgreSQL Job/Outbox instead of a Broker | Proposed for V0.2 Review |
| [TDR-008](TDR-008-versioned-yaml-quality-rules.md) | Define Rules with YAML + restricted AST | Proposed for V0.2 Review |
| [TDR-009](TDR-009-oidc-and-service-identities.md) | Use OIDC + separate service identities | Proposed for V0.2 Review |
| [TDR-010](TDR-010-containerized-vm-deployment.md) | Use containerized VM/small-platform deployment | Proposed for V0.2 Review |

## Status Transition Rules

Before final Owner approval, TDR-001 through TDR-010 must remain `Proposed for V0.2 Review`. Only after Architecture Review `V0.2-AR-2026-08-23-01` is approved may all ten TDRs transition to `Accepted` in the same governance change, recording the Review ID, approval date, and accepted residual risks. A single language or individual TDR must not be accepted alone.

Open a new TDR when a document's Re-evaluation Triggers occur; never change a decision silently.
