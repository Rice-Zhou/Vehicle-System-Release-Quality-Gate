# TDR-001 — Modular Monolith Backend

- Status: Proposed for V0.2 Review
- Scope: VSRQG Backend deployment and module boundaries

## Problem and Requirements

V0.2 must implement Release, Manifest, Issue, Traceability, Test, Evidence, and Quality together. Strong transaction and consistency requirements cross these modules. Primary development capacity is limited, and a real closed loop must be delivered within six months. There is currently no evidence requiring independent scaling or high throughput.

## Decision and Rationale

Use one deployable Backend, with internal boundaries enforced through modules, application Ports, and dependency tests; keep the Test Agent independently deployable. A single transaction can protect Manifest Lock, state history, Audit, and Outbox. This lowers local development, debugging, deployment, and recovery costs while preserving stable APIs/Ports for future extraction.

## Alternatives Not Selected

- Microservices: add network consistency, deployment, observability, version coordination, and team collaboration costs without a current requirement.
- Unmodularized monolith: simple initially but creates cross-table and cross-layer coupling that cannot evolve safely.
- Serverless functions: make long-running jobs, transaction orchestration, and Agent state management more complex.

## V0.2 / V0.3 Impact

V0.2 receives minimal operational complexity and explicit transactions. V0.3 may extract an Evidence worker, Adapter, or Orchestrator when metrics justify it; Core Contract and APIs remain unchanged.

## Migration and Rollback

Extraction uses the Strangler approach: stabilize Port/API → create an independent read model/worker → prohibit dual writes and use Outbox → switch to a single owner. V0.2 rolls back to the previous Backend image and a compatible database version.

## Testing, Deployment, and Recovery

Use module dependency tests, cross-module contract tests, and end-to-end transaction tests. Deploy one Backend image. On failure, restart stateless processes, recover jobs through DB leases, and restore the database through backup/PITR.

## Re-evaluation Triggers

A module has quantified independent scaling, regulatory isolation, failure-domain, release-frequency, or resource-conflict requirements, and monolith optimization cannot satisfy a defined SLO.
