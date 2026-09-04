# M2.5 Traceability Verification, Gap, and Immutable Snapshot Design

- Spec ID: `M2-KD-2026-09-04-01`
- Owner Design Direction: `APPROVED 2026-09-04` (Approach A: asynchronous Verification Run plus immutable Snapshot)
- Written Spec Review: `APPROVE`
- Architecture Baseline: V0.1 `0.1.0` (FROZEN) and V0.2 `0.2.0`
- Parent Authority: M2.3 `M2-3-OWNER-GATE-001` and M2.4 `M2-4-OWNER-GATE-001`
- Scope: implementation architecture for M2.5 Traceability Verification, Gap, Snapshot, and queries only; implementation is not authorized

## 1. Purpose and Frozen Boundary

M2.3 materialized the Issue set for a target Release, and M2.4 saved three append-only typed Edge Revision types: `ISSUE_COMMIT`, `COMMIT_BUILD`, and `BUILD_ARTIFACT`. M2.5 combines those historical facts with `ARTIFACT_RELEASE` derived from the Locked Release Manifest to produce auditable and replayable Fixed, Included, Verified, and exact Gap results.

This design does not change the V0.1 Core Contract, Release-centric architecture, Manifest authority, first-class Evidence, Traceability chain, Deterministic Quality Engine, Adapter/Plugin architecture, or ADR governance. The complete chain remains `Issue → Commit → Build → Artifact → Release → Test Run → Test Result → Evidence`. M2.5 has no real Test Result/Evidence and therefore cannot produce `Verified=true`.

## 2. Non-Negotiable Boundaries

- Release definition comes only from the Locked Manifest; `ARTIFACT_RELEASE` is read only from `artifact_release_edge_v`.
- Issue scope comes only from one pinned M2.3 `release_issue_snapshot`.
- The three writable Edge types use only M2.4 Revisions pinned when the Run is created; retry and replay cannot read latest Revisions.
- Fixed, Included, and Verified are independent facts and cannot substitute for one another.
- A completed Snapshot and its Issue Results, Edges, and Gaps cannot be updated or deleted.
- Jira, GitHub, CI, Device, and Test Agent are outside the verification execution path.
- A missing Edge is a business Gap. Missing, cross-Project, unlocked, or untrusted authority input is a Run error. They cannot be conflated.
- Company, real Jira, real CI, M3 Test/Evidence, merge, Tag, release, and production deployment remain blocked.

## 3. Compared Approaches and Decision

Select an asynchronous Verification Run with a pinned input ledger and atomic PostgreSQL materialization of an immutable Snapshot. `POST` pins inputs and creates a Job in a short transaction. A Worker evaluates graph reachability from pinned inputs only, then writes Snapshot, Issue Results, Edges, Gaps, Audit, and Outbox in one transaction.

Synchronous request-time verification was rejected because it enlarges HTTP timeout, retry, and lock risks and contradicts the approved `202` contract. Dynamic query-time calculation was rejected because latest Revisions would change historical results and prevent audited replay. Kafka, RabbitMQ, a graph database, and an independent service were rejected because the current maximum of 20 Issues and bounded Edge count provide no real scale requirement for that operational cost.

## 4. Logical Architecture and Data Flow

```text
POST traceability:verify
        ↓
Auth / Project / Idempotency Boundary
        ↓
pin Release + Locked Manifest + M2.3 Snapshot + M2.4 Edge Revisions
        ↓ one short PostgreSQL transaction
Verification Run + Input Ledger + Audit + Outbox + Background Job
        ↓
PostgreSQL Worker claims Job with SKIP LOCKED
        ↓
deterministic reachability and Gap calculation, no external calls
        ↓ one result transaction
Snapshot + Issue Results + Edges + Gaps + Audit + Outbox
        ↓
GET immutable Snapshot / GET Verification Run status
```

The Traceability Application obtains Release, Manifest, Issue Snapshot, and typed Revision facts through read-only Ports. Controller DTOs remain at the Adapter boundary; Domain expresses only pinned inputs, path status, Gap, and deterministic digests. Traceability cannot write Issue, Manifest, Release, or M2.4 Revision tables.

## 5. Data Authority and Pinned Inputs

Run creation pins `release_id`, `release_issue_snapshot_id`, `locked_manifest_revision_id`, Manifest digest, type/edge ID/revision/fact digest for every visible M2.4 Edge, policy version, validator version, and canonical `input_digest`.

The existing request field `IdentifierInput.sourceId` identifies the Issue Source. In the same transaction, the server selects the latest immutable Issue Snapshot for that Release and Source and saves its concrete Snapshot ID. A caller cannot select a Manifest Revision, Edge Revision, status, or conclusion.

If a logical Edge does not exist when inputs are pinned, it has no Input Ledger row, and a later Revision cannot enter this Run. If the current authoritative Revision exists but is `INVALID`, `CONFLICT`, or `ERROR`, the request fails for untrusted input rather than converting it to a missing Gap.

## 6. Fixed, Included, and Verified

Evaluate every Issue in the Snapshot independently:

- `Fixed=true`: at least one `VALID ISSUE_COMMIT` Edge satisfies the current policy.
- `Included=true`: at least one continuous `VALID` path exists through `ISSUE_COMMIT → COMMIT_BUILD → BUILD_ARTIFACT → ARTIFACT_RELEASE`, and the final edge is derived from the target Locked Manifest.
- `Verified=true`: Included is true and a PASS Test Result with required Evidence satisfies verification criteria on the target Release.

M2.5 always outputs `Verified=false`. A Commit object, Issue text, similar Artifact name or version, or manual note cannot replace an Edge. One complete candidate path is enough for Included. The Snapshot stores all Edges used in evaluation but stores only one primary proof path selected by stable ordering.

## 7. Gap Model and Exact Diagnostics

Stable Gap Codes are:

| Code | Break Position |
|---|---|
| `ISSUE_COMMIT_MISSING` | The Issue has no valid fix Commit |
| `COMMIT_BUILD_MISSING` | A Commit exists but no Build contains it |
| `BUILD_ARTIFACT_MISSING` | A Build exists but no corresponding Artifact exists |
| `ARTIFACT_RELEASE_MISSING` | An Artifact exists but is absent from the target Locked Manifest |
| `TEST_RESULT_EVIDENCE_MISSING` | No qualifying Test Result/Evidence exists on the target Release |

Each Gap stores the Issue ID, actual source node at the break, expected Edge Type, stable Code, used Revision reference, redacted reason, and gap digest. Report only the actual frontier break and do not invent cascading downstream nodes. A complete Included path still produces `TEST_RESULT_EVIDENCE_MISSING`; M2.5 exposes no administrator switch or manual parameter to change Verified to true.

## 8. PostgreSQL Model Extension

Reuse the V4 `traceability_verification_run`, `traceability_gap`, `traceability_snapshot`, `traceability_snapshot_edge`, `traceability_snapshot_gap`, `background_job`, and `idempotency_record` structures and make a minimal extension through a new forward-only Migration.

Extend `traceability_verification_run` with pinned Manifest/Issue Snapshot references, validator/input digest, final `result_snapshot_id`, and required execution diagnostics. Add append-only `traceability_verification_run_edge_input` with primary key `(verification_run_id, ordinal)`, a unique run/type/edge/revision constraint, and fact digest. It is immutable after creation.

Add `traceability_snapshot_issue_result` with primary key `(snapshot_id, issue_id)`. It stores Fixed, Included, Verified, primary path digest, minimum path Confidence, reason, and result digest. Add `traceability_snapshot_issue_path_edge` with primary key `(snapshot_id, issue_id, path_ordinal)` and a reference to `traceability_snapshot_edge.ordinal` in the same Snapshot. This materializes the exact Edge order of the primary proof path instead of recalculating it during a query.

Extend `traceability_gap` and `traceability_snapshot_gap` with nullable break-source entity type/ID and preceding source edge ID/revision. A first-segment Gap uses the Issue as the break source and has no preceding Edge. A downstream Gap must retain the actual preceding reference, with CHECK/FK constraints enforcing consistency. Issue Results, Path Edges, Snapshot Edges, and Gaps are created in the same transaction as the Snapshot Header and use equivalent creation-transaction and immutable triggers.

`traceability_gap` is a Run-level diagnostic record. Historical APIs use only Snapshot-family tables as authority and do not treat Run Gaps as a second historical source. `traceability_snapshot.content_digest` covers canonical pinned input, Issue Results, primary-path Edge order, every Edge, and every Gap.

## 9. Creation, Execution, and State Transactions

The creation transaction follows a fixed order: permission and Project validation, Release lock, Locked Manifest validation, Issue Snapshot selection, Edge Revision pinning, input digest calculation, and atomic writes for Idempotency, Run, Input Ledger, Audit, Outbox, and Background Job. Any failure rolls back all writes.

The Worker uses the existing PostgreSQL Job mechanism with `FOR UPDATE SKIP LOCKED`. Calculation reads the Input Ledger only. The result transaction locks Run and Release, allocates the next per-Release Snapshot Version, writes Header, Issue Results, primary-path Edges, all Edges, Gaps, Audit, and Outbox, recomputes the digest, and marks Run and Job `SUCCEEDED` together. Any write failure leaves no partial Snapshot.

Run transitions are limited to `QUEUED → RUNNING → SUCCEEDED|FAILED`. A transient failure leaves the Run RUNNING during bounded Job retries; exhausting retries marks it FAILED. A terminal state cannot be reopened, and re-verification creates a new Run.

## 10. Idempotency, Concurrency, and Determinism

The same Principal/Scope/Idempotency-Key and request hash returns the original Run; a different hash returns `409 IDEMPOTENCY_KEY_REUSED`. A Job key derives from the Run ID. Different Keys with an identical input digest may create separate audit Runs while reusing the existing Snapshot with the same content digest through `result_snapshot_id`; changed input creates a new Snapshot Version.

Input pinning and version allocation for one Release lock `release_record`. Database UNIQUE/FK/Trigger constraints are the final protection; no JVM-local lock is used. Concurrent identical inputs materialize at most one Snapshot. Sorting uses Issue, Edge Type, from ID, to ID, edge ID, and revision. Identical input must produce identical path, Gap, Confidence, and digest.

Graph calculation uses reachable sets and does not enumerate a Cartesian product of paths. The Snapshot stores a deduplicated complete participating Edge set, and selects the primary path by the stable ordering above.

## 11. API, Permissions, and Errors

Retain:

- `POST /api/v1/releases/{releaseId}/traceability:verify` with `traceability:verify`, Idempotency-Key, and `202`.
- `GET /api/v1/releases/{releaseId}/traceability` with `traceability:read` and optional `snapshotId`; omission reads the latest completed Snapshot.

Add backward-compatible `GET /api/v1/traceability-verification-runs/{verificationRunId}` with `traceability:read`, returning QUEUED/RUNNING/SUCCEEDED/FAILED. The POST `Location` points to this resource. Success returns a Snapshot locator; failure returns only a stable diagnostic code and redacted summary.

The typed POST response includes at least verificationRunId, releaseId, issueSnapshotId, status, inputDigest, and statusUrl. The Snapshot response includes authority versions/digests, three states for each Issue, primary path, participating Edges, Gaps, and Confidence. It excludes Jira Description/Comment/Attachment, raw Payload, credentials, local paths, SQL, and stack traces.

Missing Scope returns 403. A resource not visible in the current Project returns 404. Unlocked Manifest, state conflict, or idempotency conflict returns 409. An existing but untrusted authoritative input returns 422. Unavailable required infrastructure returns 503. Errors use RFC 9457 Problem Details and stable code/requestId. Jira/GitHub/CI availability cannot cause 503 because verification does not call them.

## 12. Capacity and Performance Boundary

Retain the maximum of 20 Issues per Issue Snapshot and M2.4 limits of 20 Issues, 20 Artifacts, and 100 Facts per Envelope. One Verification reads at most 2,000 Edge Revisions by default. Exceeding the limit fails closed with `TRACEABILITY_INPUT_LIMIT_EXCEEDED`; it cannot truncate and produce a Snapshot.

Pilot reference objectives are Run creation P95 at or below one second, asynchronous verification of 20 Issues/2,000 Edges within ten seconds, and complete Snapshot query P95 at or below one second. Queries cannot introduce per-Issue or per-Edge N+1 behavior. Shared CI uses a generous timeout to detect algorithmic regression; a fixed reference environment report verifies precise performance objectives.

## 13. Test and Evidence Matrix

- Domain: complete chain, every exact break, Commit only, Issue-to-Commit only, multiple paths, Manifest membership, and M2.5 Verified=false.
- Determinism: three identical runs match; a new Revision does not alter an old Snapshot; primary path, Gap, Confidence, and digest remain stable.
- PostgreSQL: FK, cross-Project, immutable/creation-transaction triggers, primary-path Edge references, Gap predecessor consistency, state transitions, version uniqueness, same-digest reuse, and Migration upgrade.
- Transaction: failure at Input, Snapshot child, Audit, Outbox, or Idempotency boundaries rolls back completely.
- Concurrency: duplicate claim, same/different Key, concurrent identical input, Release version allocation, and Worker crash/retry.
- API/Contract: typed schemas, Location/status polling, snapshotId, permission matrix, enumeration-safe 404, RFC 9457, and compatibility baseline.
- Security: parameterized SQL, oversize/control characters, and sensitive-data scans of logs, Problem, Outbox, and Evidence.
- Recovery: DB restart, poison/dead-letter, commit boundary, and Snapshot digest after backup/restore.

Owner Gate Evidence includes exact-head CI, real PostgreSQL tests, known-chain/gap/replay reports, transaction/concurrency/recovery reports, Contract/Security tests, bilingual Pair Gate, and fixed Git locators. No failure can be masked by another PASS.

## 14. Migration, Deployment, and Recovery

Use a forward-only Expand Migration for columns, Input Ledger, Issue Result, Path Edge, Gap predecessor fields, composite FKs, indexes, and triggers. Deployment order is PostgreSQL backup, Migration Constraint Test, the same Backend image, Pilot Fixture known-chain/gap Smoke, digest replay, then explicit endpoint enablement.

Add no Broker, Redis, graph database, object store, microservice, public Backend, or Company dependency. Application failure can roll back to the previous image while retaining backward-compatible database extensions. Do not run a down migration; repair schema problems through a new forward migration.

Pinned input and bounded Job retry recover from Worker crash. A poison job enters Dead Letter and remains visible. A FAILED Run is not rewritten; retry creates a new Run after repair. After database restore, verify the Flyway version and recompute digest from Snapshot data. A mismatch stops use, does not overwrite the old digest, and cannot fall back to JSON/file/cache.

## 15. Technology Decision Delegation and V0.3

`TDR-018` records PostgreSQL-backed asynchronous Verification, the pinned input ledger, and immutable Snapshot materialization. It answers selection, problem, alternatives, V0.2/V0.3 impact, migration, testing, deployment, and recovery. The Project Owner approved the Written Spec Review, and its status is now `Accepted`.

V0.3 may add Test Run, Test Result, and Evidence through new schema/policy/validator versions to produce real Verified conclusions. Historical M2.5 Snapshots remain Verified=false. Worker extraction, a Broker, or graph queries are evaluated only when measurements prove that 20 Issues/2,000 Edges, PostgreSQL Jobs, or the monolith Worker are a bottleneck. Pinned input, database authority, digest, and historical immutability remain unchanged.

## 16. Cut Line, Stop Conditions, and Written Spec Gate

If delivery slips, remove formatted reports, extra filters, non-critical metrics, and administration UI in that order. Do not remove separation of Fixed/Included/Verified, Manifest-only Artifact-to-Release, pinned inputs, exact Gaps, immutable Snapshot, Project isolation, Idempotency, Audit/Outbox, or transaction/replay/recovery tests.

Stop and submit a Finding, TDR revision, or ADR Proposal if implementation requires changing the three-state semantics, adding a second Artifact-to-Release authority, reading latest Revisions to replay history, putting external systems in Gate execution, accepting caller conclusions, turning missing/UNKNOWN/error into success, overwriting an old Snapshot, or retaining credentials/raw company data.

The Project Owner approved this Written Spec. That approval permits only creation of a separate Implementation Plan and does not authorize production code, Migration, real Jira/CI, Company, M3, merge, Tag, release, or production deployment. The Implementation Plan and later Subagent-Driven execution require separate explicit authorization.
