# TDR-002 — Kotlin/JVM and Spring Boot

- Status: Accepted
- Approved Review: `V0.2-AR-2026-08-23-01`
- Approval Date: 2026-08-24
- Accepted Residual Risks: Owner final acceptance checklist Section 5, items 1-5
- Scope: Backend implementation stack

## Problem and Requirements

The system needs strongly typed domain models, transactions, REST/OIDC, PostgreSQL, background jobs, validation, observability, and a mature test ecosystem. The Agent operates in an Android/JVM environment. One primary developer needs high productivity and company-level maintainability.

## Decision and Rationale

Recommend Kotlin/JVM + Spring Boot 3 for the Backend, running on a currently supported LTS JDK. Kotlin null safety, sealed types, and immutable models suit state and contract expression. Spring provides transactions, Web, Security, Validation, Actuator, and broad integrations, reducing custom infrastructure. The Agent uses Kotlin, but Server and Agent share only protocol schemas, not internal domain classes.

## Alternatives Not Selected

- Java: equally viable with a mature ecosystem, but more verbose for immutable domain models and protocol types; it may replace Kotlin if the team's Java capability is materially stronger.
- Go: lightweight deployment, but complex transactions, rule models, and enterprise authentication need more custom constraints, and it separates the language from the Android Agent.
- Node.js/TypeScript: fast for APIs, but weaker advantages for long-lived enterprise transactions and JVM/Android alignment.

## V0.2 / V0.3 Impact

V0.2 gains delivery speed at the cost of higher JVM memory than Go. V0.3 can keep the Backend or extract services in other languages behind stable OpenAPI/events without changing contracts.

## Migration and Rollback

OpenAPI, DB migrations, and Agent protocol fix all external contracts. A replacement language implements the same contracts and passes contract/replay tests. A failed release rolls back to the previous image; irreversible migrations are never rolled back.

## Testing, Deployment, and Recovery

Use unit, property, integration, containerized PostgreSQL, contract, and end-to-end tests. Build an immutable JVM container. Restart failed processes and recover state from PostgreSQL/Object Storage.

## Re-evaluation Triggers

The company explicitly does not support JVM, measured resource usage cannot meet deployment limits, or the maintenance team's technology stack changes materially.
