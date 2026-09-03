# M2.4 CI/Build Facts and Edge Revision Design

- Spec ID: `M2-KD-2026-09-03-01`
- Owner Design Direction: `APPROVED 2026-09-03` (Option A: One Build Provenance Envelope)
- Written Spec Review: `PENDING`
- Architecture Baseline: V0.1 `0.1.0` (FROZEN) and V0.2 `0.2.0`
- Parent Governance: Chinese `81b1aa12da8ffcb060df9c257e8277e883221fe0` / English `c6496ae5147afb85ea4224f390b0df30d7d96324`
- Scope: defines only the M2.4 implementation architecture for CI/Build Fact ingestion and typed Edge Revision; does not authorize implementation

## 1. Purpose and Evidence Gap

M2.3 has frozen the exact Issue set for a Release as an immutable `release_issue_snapshot`. The database already reserves `source_commit`, `build_record`, and three Edge Revision tables, but no executable ingestion authority, stable logical Edge identity, concurrency convergence, proof validation, Build Attempt idempotency, or real CI end-to-end Evidence exists yet.

M2.4 uses one versioned Build Provenance Envelope for one Build Attempt. The server accepts only source facts. In one PostgreSQL transaction, it resolves exact Issue Snapshot Items, creates or reuses Commit/Build records, validates Artifact checksums, and writes append-only Revisions for three typed Edges plus Idempotency, Audit, and Outbox records. It cannot accept caller conclusions such as Fixed, Included, Verified, PASS, WARNING, or BLOCK.

This design does not change the V0.1 Core Contract, Release-centric architecture, Manifest authority, first-class Evidence, Traceability semantics, Deterministic Quality Engine, Adapter/Plugin model, or ADR governance. M2.4 only establishes provenance facts for M2.5 verification; it does not generate a Release Traceability Snapshot or Quality Result.

## 2. Non-negotiable Boundaries

- One Envelope describes one Build Attempt from one CI Provider in one Project.
- The Envelope must bind an immutable Release Issue Snapshot. Issues resolve exactly by source identity within its Items; a current-latest Issue Revision query is forbidden.
- `ISSUE_COMMIT`, `COMMIT_BUILD`, and `BUILD_ARTIFACT` are the only writable Edge types.
- `ARTIFACT_RELEASE` derives only from `release.locked_manifest_id → manifest_artifact`; no write API, table, cache, or side mapping may exist.
- Source Commit authority is Project, Repository Identity, and full Source Revision. Build authority is Project, Provider, Pipeline, Build ID, and Attempt.
- An Artifact must already exist and resolve by its full lowercase SHA-256. M2.4 does not upload, register, or replace an Artifact.
- A caller cannot submit `edgeId`, Revision, Verification Status, Confidence, Validator Version, or Fixed/Included/Verified.
- Historical Edge Revisions reject UPDATE/DELETE. Revalidation or proof change can only insert a new Revision.
- GitHub Actions is only the first Pilot Provider Adapter; it does not enter Core or generic database semantics.
- Company, real company CI, production credentials, M2.5, merge, Tag, release, and production deployment remain blocked.

## 3. Options and Decision

Use one Build Provenance Envelope, derive typed Edges on the server, and commit atomically in PostgreSQL. The Envelope supplies structured Build authority, Snapshot reference, Issue source IDs, Artifact digests, and proof. The caller does not assemble internal Entity IDs or conclusions.

A generic independent Edge Batch was not selected because a Producer would need database-internal IDs and could create partial chains, ordering dependencies, and concurrency drift. Raw Attestation/Event storage followed by asynchronous normalization was not selected because no current requirement demands a signed supply chain, Broker, independent Raw Store, or high throughput; its recovery and operational cost exceeds the six-month spare-time MVP. Direct CI writes to Edge tables were rejected because they bypass validation, transactions, Audit, and Project Scope.

## 4. Logical Architecture and Data Flow

```text
GitHub Actions / future CI Adapter
                ↓
      Build Provenance Envelope v2
                ↓
Service Identity + Project Scope Boundary
                ↓
      BuildProvenanceValidatorPort
                ↓
Release Issue Snapshot / Commit / Build / Artifact resolution
                ↓
       one PostgreSQL transaction
  ┌─────────────┼───────────────────┐
Commit/Build  Edge Identity/Revision  Idempotency/Audit/Outbox
                ↓
       immutable ingestion result
                ↓
       M2.5 verification input
```

Controller DTOs and GitHub environment parsing stay in the Adapter boundary. Application receives only a normalized `BuildProvenanceEnvelope`; Domain expresses only provider-neutral identity, proof observation, and typed edge candidates. The Traceability Adapter may query M2.3 Snapshot and Manifest/Artifact facts through read-only Ports and cannot depend on Issue, Manifest, or Release Adapter implementations.

## 5. Build Provenance Envelope v2

Retain `POST /api/v1/traceability/facts:ingest`, the `traceability:ingest` service scope, and `Idempotency-Key`. The request body uses `schemaVersion: 2` with these required fields:

```json
{
  "schemaVersion": 2,
  "project": "project-reference",
  "releaseIssueSnapshotId": "ris_...",
  "provider": "GITHUB_ACTIONS",
  "repository": "owner/repository",
  "sourceRevision": "0123456789abcdef0123456789abcdef01234567",
  "pipeline": "m1-backend",
  "buildId": "33705417856",
  "buildAttempt": 1,
  "workflowReference": "owner/repository/.github/workflows/m1-backend.yml@refs/heads/main",
  "proofReference": "https://github.com/owner/repository/actions/runs/33705417856/attempts/1",
  "proofDigest": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "sourceIssueIds": ["ISSUE-1"],
  "artifactSha256s": ["0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"]
}
```

`sourceIssueIds` and `artifactSha256s` each have a maximum of 20 items. Duplicates are rejected, and validated values are then ordered by Unicode code point. Derived facts consist of one `ISSUE_COMMIT` per Issue, one `COMMIT_BUILD`, and one `BUILD_ARTIFACT` per Artifact, with a hard total limit of 100 and a hard request-body limit of 256 KiB. Empty Issue or Artifact arrays, duplicates, control characters, an unknown Provider, a non-full Git SHA, a non-lowercase SHA-256, Attempt below 1, or a non-allowlisted proof scheme returns a fixed boundary error without truncation.

The M2.0 `TraceabilityFactBatch schemaVersion: 1` is an unimplemented pre-release draft with no external consumer. M2.4 does not silently reinterpret it. `TDR-017` explicitly supersedes v1 with v2, and the same Subject plans updates to OpenAPI and the compatibility baseline. The platform `/api/v1` major path, Method, Permission, and Idempotency remain unchanged. Contract Tests and governance records must make the intentional body-schema change explicit.

## 6. Data Authority and Relationship Model

Add a small `traceability_edge_identity` logical Edge Header:

| Field | Constraint | Meaning |
|---|---|---|
| `edge_id` | PK | Server UUIDv7; never supplied by a caller |
| `project_id` | FK, required | Project Scope |
| `edge_type` | CHECK, required | One of the three writable Edge types |
| `from_entity_id` | required | Stable source endpoint identity |
| `to_entity_id` | required | Stable target endpoint identity |
| `created_at` | required | Authority write time; not a fact conclusion |

The unique constraint is `(project_id, edge_type, from_entity_id, to_entity_id)`. The three typed Revision tables continue carrying composite FKs for concrete endpoints. An Edge Header FK and deferred constraint trigger ensure that the Header discriminator/endpoints match the typed Revision endpoints. The Header contains no proof, status, or Confidence, so it is not a second fact source.

`build_record` gains `build_attempt >= 1` and immutable `repository`; authority uniqueness becomes `(project_id, provider, pipeline, build_id, build_attempt)`. Although the current tables contain no production data, the Migration still uses expand/validate ordering, does not assume emptiness, and includes a precondition check. `source_commit` keeps `(project_id, repository, commit_id)`. Artifact keeps its existing content identity and gains no `build_id`.

## 7. Atomic Ingestion Transaction

The normal path is fixed:

1. Validate Service Identity, Project claim, payload size, schema, and request digest.
2. Get or create the Idempotency Record; the same Key with a different digest immediately returns `409`.
3. Get the Build Attempt Receipt and validate its authority tuple and Envelope digest.
4. Lock the Release Issue Snapshot and validate Project Scope, immutable state, and digest.
5. Resolve every source issue ID only within Snapshot Items; any missing item fails the whole request.
6. Create or reuse Source Commit and Build Record and reject identity conflict.
7. Resolve every Artifact in checksum order; any missing or inconsistent Artifact fails the whole request.
8. Create or lock Edge Headers in Edge Type and endpoint order.
9. Run the provider-neutral validator and generate server status, confidence, reason, and fact digest.
10. Reuse an existing Revision with the same digest; otherwise insert exactly `revision+1`.
11. Write Build Attempt Receipt, Audit, Outbox, and Idempotency response.
12. Recompute counts/digests from the pending model and commit once when all checks pass.

Any Domain, Audit, Outbox, or Idempotency write failure rolls back the transaction. A missing endpoint, cross-Project reference, Issue absent from the Snapshot, or Artifact checksum mismatch leaves no Commit, Build, Edge, or successful Receipt.

## 8. Revision, Idempotency, and Conflict

An Edge Revision row stores `verified_at` as the observation time. Its canonical fact fixes edge identity/type, typed endpoints, source type/reference, proof reference/digest, verification status, confidence, validator version, reason code, and previous revision identity. `verified_at`, `created_at`, request ID, Idempotency Key, and random Revision ID do not enter the fact digest, preventing replay time alone from creating a new Revision.

- The same Edge canonical digest returns the existing latest Revision and inserts no duplicate row.
- A proof, status, confidence, validator version, or reason change inserts the next Revision under an Edge Header row lock.
- Changed endpoints resolve to another Edge identity; a caller cannot mutate endpoints through an old `edgeId`.
- The same Idempotency Key and request digest returns the original response; a different digest returns `IDEMPOTENCY_CONFLICT`.
- The same Build Attempt authority and Envelope digest returns the original Receipt and Revision set even with a different Key.
- The same Build Attempt authority and a different Envelope digest cannot overwrite old facts. A separate short transaction saves a redacted rejected receipt and Audit, then returns `BUILD_PROVENANCE_CONFLICT`.
- New proof that contradicts an existing authoritative proof for an Edge inserts a `CONFLICT` Revision and retains the old Revision.
- Multiple Artifacts legitimately produced by one Build form multiple `BUILD_ARTIFACT` Edges and are not automatically conflicts.

Lock order is Project, Build Attempt Receipt, Edge Header `(edge_type, edge_id)`, Revision, then Audit/Outbox. Database UNIQUE/FK/Trigger constraints are the final concurrency protection; application code cannot rely only on query-before-insert.

## 9. Proof Validation and Confidence

Validation has six layers: Schema, Service Identity, Project Scope, Endpoint, Integrity, and Provider Provenance. `BuildProvenanceValidatorPort` returns only a normalized observation. Domain policy under a fixed validator version produces `VALID`, `INVALID`, `CONFLICT`, or `ERROR`, and `HIGH`, `MEDIUM`, `LOW`, or `UNKNOWN`.

The GitHub Actions Pilot uses real repository, commit SHA, workflow reference, run ID/attempt, and built Artifact checksum, but has no independently signed attestation. Successful proof therefore has a maximum Confidence of `MEDIUM`. Provider unavailability or unverifiable proof creates a new `ERROR/UNKNOWN` Revision and cannot reuse an old `VALID` state. Only a separately reviewed GitHub OIDC Attestation, SLSA Provenance, or signed company metadata can produce `HIGH` through a new Validator Version.

M2.4 does not calculate Fixed/Included/Verified. `VALID/MEDIUM` describes only the current validator judgment for one provenance Edge. M2.5 must combine it with `ARTIFACT_RELEASE` derived from the Locked Manifest, policy, and Gaps before deciding whether a continuous path exists.

## 10. Bounded Real GitHub Actions Smoke

The CI Gate continues to depend only on synthetic versioned fixtures. A real Smoke is an additional acceptance path in the same workflow:

1. Check out the exact commit and build a controlled test Artifact.
2. Form the Envelope from real GitHub context.
3. Start temporary PostgreSQL and Backend instances.
4. Create a synthetic Project, Release, Locked Manifest, Issue Sync/Snapshot, and Artifact through normal application/test fixtures.
5. Use an ephemeral test Service Identity inside the Runner and ingest through the real HTTP Endpoint.
6. Replay with the same and different Idempotency Keys and execute conflict and unauthorized negatives.
7. Query PostgreSQL to prove authority, Revision, Audit/Outbox, and absence of an `ARTIFACT_RELEASE` write path.
8. Upload a summary-only Evidence Artifact.

Real fields are limited to repository identity, commit SHA, workflow reference, run ID/attempt, Artifact checksum, and proof locator. Issue and Release data use synthetic fixtures; no real Jira, company CI, or production system is accessed. Backend is not exposed publicly. Smoke Evidence records only exact commit, Run/Attempt, schema/validator version, Envelope digest, Artifact digest, Edge/Revision IDs, replay outcome, fixed diagnostics, and test summary.

## 11. API, Errors, and Security

The Endpoint allows only a `traceability:ingest` Service Identity. An ordinary user JWT returns 403 even for a Project Admin. The token project claim and body project must resolve to the same Project. All SQL is parameterized. Provider, Repository, Pipeline, Build ID, proof reference, and source issue ID have character and length allowlists.

Fixed errors include `RESOURCE_NOT_FOUND`, `PROJECT_SCOPE_MISMATCH`, `SNAPSHOT_ISSUE_NOT_FOUND`, `ARTIFACT_NOT_FOUND`, `ARTIFACT_DIGEST_MISMATCH`, `PROOF_VALIDATION_FAILED`, `IDEMPOTENCY_CONFLICT`, `BUILD_PROVENANCE_CONFLICT`, `FACT_LIMIT_EXCEEDED`, and `PERSISTENCE_UNAVAILABLE`. A 404 hides the existence of an invisible resource, Domain input uses 422, identity/authority conflict uses 409, and temporary database unavailability uses 503.

Full GitHub event payloads, environment dumps, Tokens, Cookies, Authorization headers, Commit author emails, PR bodies/comments, Runner paths, raw Provider errors/responses, and stack traces cannot be saved or emitted. Logs, Problem Details, Audit, Outbox, and CI Artifacts use only stable IDs, versions, counts, digests, and fixed diagnostics.

## 12. Test and Evidence Matrix

- Contract: v2 required/additional fields, lengths, array/payload limits, v1 superseded, Service Identity, ordinary JWT rejection, and forbidden conclusion/`ARTIFACT_RELEASE` fields.
- Unit: canonical Envelope/fact digest, Unicode code-point order, duplicate removal, proof normalization, derived fact count, and validator policy.
- PostgreSQL: Edge Header uniqueness, typed endpoint FKs, Revision chain, immutable trigger, Build Attempt uniqueness, cross-Project, concurrent identical Envelope, and repeat/upgrade Migration.
- Application: Snapshot-only Issue resolution, Commit/Build reuse, Artifact checksum, two-level idempotency, same-Build conflict, proof contradiction, and new Validator Revision.
- Transaction: Commit, Build, any Edge, Receipt, Audit, Outbox, or Idempotency failure cannot leave partial success.
- Security: user JWT, wrong Project, unknown Provider, control characters, oversized payload, and sensitive scans of logs/Problems/Artifacts.
- Authority: Schema and code contain no writable `ARTIFACT_RELEASE`; Artifact has no `build_id`; the Locked Manifest view remains the only Artifact-to-Release source.
- Replay: revalidation only inserts a Revision; old Revisions and M2.3 Issue Snapshot bytes/digest stay unchanged.
- Live Smoke: GitHub exact-head Build/Artifact metadata is ingested over HTTP, duplicate submissions converge, and a redacted Evidence Artifact is uploaded.

Owner Gate Evidence must include PostgreSQL Integration Tests, a transaction failure report, concurrency/idempotency report, GitHub Actions Smoke, sensitive scan, Pair Gate, and exact Git/CI locators. Fixture PASS and live Smoke are reported separately; neither can hide a failure in the other.

## 13. Migration, Deployment, and Recovery

Use a forward-only Expand Migration for Edge Header, Build Attempt/Receipt, rejected receipt, composite FK/UNIQUE/Trigger constraints, and required indexes. Run a precondition query before deployment. Conflicts with the new authority tuple must stop the Migration and generate a redacted diagnostic; they cannot be merged or deleted automatically.

Continue using the Modular Monolith, Kotlin/Spring Boot, and PostgreSQL. Add no Broker, Redis, graph database, service, object storage, management UI, or public Callback. The M2.4 Pilot Feature defaults off. Deployment order is Migration, compatible application, Gate, then explicit enablement. Company Profile cannot inherit Pilot credentials or Confidence defaults.

Application rollback disables ingestion and retains new tables, Receipts, and Revisions; it cannot reverse a Migration or delete history. A caller retries temporary database failures with the original Idempotency Key and a bound, with no JSON/file/cache fallback. After database restore, reconcile Edge Header/Revision chains, Envelope/fact digests, Receipts, Audit, Outbox, and Idempotency responses. Any mismatch remains fail-closed and is repaired by roll-forward.

## 14. V0.2, V0.3, and Cut Line

V0.2 implements only the GitHub Actions Pilot Provider, one synchronous ingestion Endpoint, three typed Edges, maximum `MEDIUM` Confidence, and PostgreSQL authority. If delivery slips, remove formatted Smoke reports, extra metrics, and noncritical queries in that order. Do not remove Snapshot-bound Issue resolution, Artifact checksum, atomic transaction, typed Edge Header/Revision, two-level idempotency, conflict retention, Manifest-only Artifact-to-Release, Audit/Outbox, or security negative tests.

V0.3 may add Jenkins/GitLab/company CI Adapters, signed Attestation, asynchronous batches, or higher capacity while reusing the normalized Envelope/Validator Port and creating Revisions under new schema/validator versions. Consider a Queue, independent ingestion service, or Raw Attestation Store only when measured throughput, signed supply-chain needs, or deployment constraints prove the synchronous PostgreSQL design insufficient.

## 15. Technology Decision Delegation

`TDR-017` records the key technology decision and answers selection, problem, alternatives, V0.2/V0.3 impact, migration, testing, deployment, and recovery. It remains `Proposed` and can become `Accepted` only after the Project Owner approves the `M2-KD-2026-09-03-01` Written Spec Review.

## 16. Stop Conditions and Written Spec Review Gate

Stop and submit a Finding, TDR revision, or ADR Proposal if implementation requires changing Fixed/Included/Verified, allowing CI to write `ARTIFACT_RELEASE`, substituting current-latest Issue data for a Snapshot, leaking GitHub DTOs into Core, accepting caller status/Confidence, adding a second structured authority or silent fallback, saving credentials/raw company data, or dropping a non-negotiable item for capacity.

The Project Owner reviews this specification before planning. Written Spec approval authorizes only an independent Implementation Plan. It does not authorize production code, Migration, real Jira, real company CI, Company, M2.5, merge, Tag, release, or production deployment. The Implementation Plan and its execution require separate authorization.
