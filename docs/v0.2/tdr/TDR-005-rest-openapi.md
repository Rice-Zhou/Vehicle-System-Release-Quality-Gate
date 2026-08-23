# TDR-005 — REST/JSON and OpenAPI 3.1

- Status: Proposed for V0.2 Review
- Scope: user, CI, and system integration APIs

## Problem and Requirements

Core resource boundaries are clear. The API must be easy to integrate inside the company, browsable, client-generatable, versionable, and contract-testable. MVP request volume is manageable; interactions are mainly resource CRUD, state commands, and asynchronous job queries.

## Decision and Rationale

Use REST/JSON, OpenAPI 3.1, and `/api/v1`. Resource modeling is straightforward and company tooling broadly supports it. OpenAPI enables change checks, contract tests, and acceptance. Long-running work returns 202 plus a resource ID and does not hold a long transaction connection.

## Alternatives Not Selected

- GraphQL: flexible queries, but more complex authorization, caching, error, and schema governance; MVP has no dynamic aggregation need.
- gRPC: suits high-throughput internal calls, but raises browser/manual-debugging and company-integration barriers; the Agent has no high-frequency streaming need.
- Messaging API: reliable asynchronous behavior, but adds a Broker and eventual consistency and is unsuitable as the primary operational interface.

## V0.2 / V0.3 Impact

V0.2 gains low-barrier integration. V0.3 may add gRPC/events for high-frequency internal paths, while REST remains the governance API and Core semantics stay unchanged.

## Migration and Rollback

Within one major version, make only backward-compatible extensions. Breaking changes use a new major version and a dual-version migration window. Application rollback must preserve DB/API compatibility; version the OpenAPI artifact together with the image.

## Testing, Deployment, and Recovery

Use OpenAPI lint, breaking-diff, consumer-contract, idempotency, authorization, and error tests. Deploy with the Backend. Roll back API failure to the prior image and recover asynchronous resource state from the DB.

## Re-evaluation Triggers

A quantified real-time streaming, high-throughput low-latency, or client query-composition requirement appears and REST pagination/asynchronous resources cannot meet it.
