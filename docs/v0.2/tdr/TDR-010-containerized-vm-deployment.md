# TDR-010 — Containerized VM / Small Platform Deployment

- Status: Accepted
- Approved Review: `V0.2-AR-2026-08-23-01`
- Approval Date: 2026-08-24
- Accepted Residual Risks: Owner final acceptance checklist Section 5, items 1-5
- Scope: MVP runtime topology

## Problem and Requirements

The system needs repeatable deployment, upgrade, rollback, monitoring, and backup. MVP has one Backend, one DB, one object store, and a few Agents. One primary developer cannot operate a complex cluster.

## Decision and Rationale

Build immutable application containers. Use Docker Compose for development and a controlled VM or existing small container platform in the company environment. Prefer managed company PostgreSQL/Object Storage; otherwise deploy them as dedicated controlled services. This is repeatable while retaining low operational complexity.

## Alternatives Not Selected

- Kubernetes: no current elasticity, scale, or multi-team isolation requirement; learning and operational costs are unjustified.
- Manual bare-metal installation: not repeatable and weak for rollback and version traceability.
- Fully managed public cloud: may conflict with company network/data policy; S3/OIDC interfaces still preserve portability.

## V0.2 / V0.3 Impact

V0.2 single/few instances may have short maintenance downtime but satisfy MVP. V0.3 can deploy the same containers to Kubernetes/company platform when measured SLO/scale requires it.

## Migration and Rollback

Externalize configuration and keep state out of local container storage. To migrate, restore DB/Object copies on the new platform, validate smoke/replay, then switch traffic. Roll back to the previous application image; database migrations remain backward-compatible.

## Testing, Deployment, and Recovery

Deploy from an empty environment automatically or from documentation, then run smoke, backup restoration, service restart, and capacity benchmarks. On failure, restore the prior image, recover the database with PITR, and reconcile object inventory. Agent local spool preserves Evidence not yet uploaded.

## Re-evaluation Triggers

The company mandates a platform, availability cannot tolerate a single instance, device/task volume requires elasticity, or an operations team can bear quantified cluster cost and benefit.
