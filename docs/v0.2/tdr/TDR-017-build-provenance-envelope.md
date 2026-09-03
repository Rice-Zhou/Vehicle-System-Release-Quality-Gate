# TDR-017 — Build Provenance Envelope and PostgreSQL Typed Edge Revision

- Status: Accepted
- Date: 2026-09-03
- Decision Basis: On 2026-09-03, the Project Owner approved the written specification through `APPROVE M2-KD-2026-09-03-01 WRITTEN SPEC REVIEW`
- Scope: M2.4 CI/Build Fact ingestion, Build Attempt authority, typed Edge identity/Revision, proof validation, and GitHub Actions Pilot Smoke
- Related Decisions: [TDR-002](TDR-002-kotlin-spring-boot.md), [TDR-003](TDR-003-postgresql.md), [TDR-005](TDR-005-rest-openapi.md), [TDR-007](TDR-007-postgresql-job-outbox.md), [TDR-009](TDR-009-oidc-and-service-identities.md), [TDR-016](TDR-016-materialized-release-issue-snapshot.md)

## 1. Why This Technology Was Selected

V0.2 uses one `BuildProvenanceEnvelope schemaVersion: 2` for one Build Attempt. A Kotlin/Spring Boot Application resolves Snapshot, Commit, Build, and Artifact data in one PostgreSQL transaction, creates server-owned Edge Headers, and saves three append-only typed Edge Revisions plus Receipt, Idempotency, Audit, and Outbox records.

These entities have strongly structured relationships that require FKs, transactions, concurrent uniqueness, historical queries, and deterministic replay. The MVP has one GitHub Actions Provider and limits each Envelope to 20 Issues and 20 Artifacts. Reusing the Modular Monolith and PostgreSQL meets current scale and company-grade audit needs with the lowest operational cost.

## 2. Problem It Solves

The reserved Edge tables have no stable logical Edge identity, Build Attempt authority, Producer-safe Contract, two-level idempotency, proof validator, or conflict retention. The original `TraceabilityFactBatch schemaVersion: 1` requires callers to submit internal Entity IDs, cannot safely create a complete Commit-to-Build-to-Artifact chain, and does not bind the M2.3 Release Issue Snapshot.

Envelope v2 lets callers submit only source facts. The server resolves authority through Snapshot, repository/source revision, provider/build attempt, and Artifact checksum and rejects caller Fixed/Included/Verified or validation conclusions. CI supplies provenance without becoming Release Gate authority.

## 3. Why Alternatives Were Not Selected

- Generic independent Edge Batch: requires CI to understand internal IDs and creates partial-chain and ordering coupling.
- Raw Attestation/Event Store plus asynchronous Worker: no current signing, throughput, or Broker requirement justifies a second storage representation and its recovery cost.
- Direct CI database writes: bypass Service Identity, Project Scope, Validation, Audit, and Domain transactions.
- Provider-specific Endpoint/Schema: leaks vendor models into Application/Core and duplicates implementation.
- Kafka, graph database, or independent ingestion service: current volume and synchronous transactions show no real bottleneck.

## 4. Impact on V0.2

Retain `POST /api/v1/traceability/facts:ingest`, `traceability:ingest`, and `Idempotency-Key`, while explicitly superseding the unimplemented and unused v1 pre-release draft with `schemaVersion: 2`. OpenAPI and the compatibility baseline require an intentional update in the same implementation Subject; the old body schema is not represented as compatible.

PostgreSQL gains `traceability_edge_identity`, Build Attempt success/rejected receipts, Build Record attempt/repository authority, and composite constraints. Application gains a normalized Envelope, Validator Port, and one synchronous transaction use case. The GitHub Actions Adapter reads only allowlisted CI context, and successful Pilot proof has a maximum `MEDIUM` Confidence. M2.4 creates no `ARTIFACT_RELEASE`, decides no Fixed/Included/Verified, and implements no M2.5 Snapshot.

## 5. Impact on Future V0.3

Jenkins, GitLab, or company CI can add Adapters that map provider fields into the same normalized Envelope. Signed Attestation can add a Validator Version and insert a new Revision without rewriting history. If measured throughput later requires asynchronous processing, Receipt may become a durable inbox while Edge authority, transaction atomicity, digest, and idempotency semantics remain unchanged.

Future schemas extend through new versions. Provider DTOs, raw events, and credentials cannot enter Core. Historical Envelopes, Receipts, and Revisions must remain explainable.

## 6. Migration

Use a forward-only Expand Migration to create Edge Header/Receipt structures, constraints, and indexes, add repository and attempt to Build Record, and run a data precondition. A conflict under the new authority tuple fails closed; the Migration cannot delete, merge, or guess automatically.

API Path, Method, Permission, and Idempotency remain unchanged, while the body uses explicit v2. Because v1 was never implemented or released, this is a governed pre-release supersession rather than a second unsafe implementation. Discovery of any real v1 consumer stops work and triggers compatibility reassessment.

## 7. Testing

Contract Tests cover the strict v2 schema, v1 supersession, limits, and forbidden conclusion fields. PostgreSQL Tests cover Edge Header/typed FKs, Revision chain, Build Attempt uniqueness, cross-Project rejection, concurrency, and immutability. Application and transaction Tests cover Snapshot-only Issue resolution, Artifact checksum, two-level idempotency, conflict, Audit/Outbox failure, and complete rollback.

An exact-head GitHub Actions Smoke submits real CI metadata with synthetic Release/Issue data to temporary Backend/PostgreSQL instances and proves HTTP, Service Identity, transaction, and replay behavior. Security scans prove that event payloads, Tokens, environment values, paths, personal data, and raw errors do not enter logs or Artifacts.

## 8. Deployment

Add no service, Broker, database, object store, graph database, or UI. The Migration and Traceability module deploy with the existing Backend, and the Pilot Feature defaults off. Enablement order is Migration, compatible application, complete Gate, then explicit Pilot configuration.

GitHub Smoke uses an ephemeral test identity inside the Runner, uses no PAT, and exposes no public Backend. Company OIDC, formal CI credentials, retention, and operator responsibility require separate configuration and acceptance.

## 9. Failure Recovery

Any normal ingestion failure in Domain, Receipt, Idempotency, Audit, or Outbox writes rolls back the whole PostgreSQL transaction. The same Build Attempt identity with a different digest uses a separate short transaction to save a redacted rejected receipt without overwriting successful facts. Provider unavailability creates an explicit failure or a new `ERROR/UNKNOWN` Revision and cannot reuse an old VALID state.

Application rollback only disables the endpoint and retains the Migration and historical Revisions. After database restore, reconcile Header/Revision chains, Envelope/fact digests, Receipts, Audit, Outbox, and Idempotency responses. A mismatch stays fail-closed and is repaired by roll-forward without JSON/file/cache fallback.

## Re-evaluation Triggers

Re-evaluate when a real v1 consumer appears, one Build exceeds 20 Issues/20 Artifacts or 100 facts, measured synchronous transaction limits are reached, a Provider requires signed Raw Attestation retention, Company needs an independent ingestion service, the Build authority tuple cannot be normalized, or a proposal requires writable `ARTIFACT_RELEASE`, altered Fixed/Included/Verified semantics, or a second authority. A change to frozen semantics requires an ADR Proposal.
