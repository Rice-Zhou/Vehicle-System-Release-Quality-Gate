# M2.4 Build Provenance Envelope Operations Specification

## 1. Purpose and boundary

M2.4 uses one `Build Provenance Envelope v2` to atomically persist the source facts for one Project, one CI Provider, and one Build Attempt in PostgreSQL. The endpoint only creates or reuses Source Commit, Build, three typed Edge Header/Revision kinds, Receipt, Audit, Outbox, and the idempotent response. It does not calculate Fixed, Included, Verified, or Quality Result, and it does not write `ARTIFACT_RELEASE`.

GitHub Actions is the first Pilot Adapter. The live Smoke uses only the GitHub repository, exact commit, workflow reference, Run/Attempt, and Job context. Project, Release, Locked Manifest, Issue Snapshot, Artifact, and SERVICE Identity are synthetic fixtures inside the Runner. It does not access Jira, company CI, or a public Backend.

## 2. Endpoint and Service Identity

- Endpoint: `POST /api/v1/traceability/facts:ingest`
- Request header: `Idempotency-Key` is required and has length 1 through 128; `Content-Type` is `application/json`.
- OAuth scope: `traceability:ingest`.
- JWT claims: trusted issuer, subject, `principal_type=SERVICE`, `project`, and scope; deployment configuration also validates the existing audience.
- Database authority: the matching Principal must be a non-disabled `SERVICE`, the target Project must not be archived, and a Project assignment must exist.
- Four-way consistency: the JWT SERVICE type, JWT `project`, body `project`, and database assignment must agree. An ordinary USER must not ingest even if it carries the scope and has the Project Administrator role.

The Pilot Feature is disabled by default. Only after the exact-head Gate completes and the Owner authorizes it may an operator explicitly set `VSRQG_TRACEABILITY_INGESTION_ENABLED=true` and restart the compatible application. `VSRQG_TRACEABILITY_MAX_PAYLOAD_BYTES` must be between 1 and 262144; its default and maximum are 262144. The Company Profile does not inherit the Pilot identity or enabled state.

## 3. Envelope, digest, and authority

The Envelope must use `schemaVersion=2` and bind an immutable `releaseIssueSnapshotId`. Issues resolve only from those Snapshot Items. Artifacts resolve only by a complete lowercase SHA-256 checksum already associated with the Project. A caller cannot submit an internal Entity ID, Edge ID, Revision, Verification Status, Confidence, or business conclusion.

After normalizing fields and array order, the server calculates the Envelope digest with `build-provenance-envelope-jcs/v2`. The Proof digest covers provider, repository, source revision, pipeline, Build ID/Attempt, workflow reference, and proof reference. `github-actions-provenance/v1` produces `VALID/MEDIUM` only when those values agree with the GitHub proof locator. An Artifact checksum is not an Artifact identity digest and cannot replace Manifest authority.

The Build Attempt authority is Project, Provider, Pipeline, Build ID, and Attempt. All Commit/Build, Edge Revision, Receipt, Audit, Outbox, and idempotency writes complete within one transaction. Any write or read-back failure must roll back the whole transaction.

## 4. Replay, conflict, and fixed errors

The same `Idempotency-Key` with the same Envelope digest returns the byte-identical response. The same key with a different digest returns 409 `IDEMPOTENCY_CONFLICT`. A different key for the same Build Attempt and the same Envelope digest reuses the accepted Receipt. A different Envelope digest returns 409 `BUILD_PROVENANCE_CONFLICT`; after the main transaction rolls back, an independent transaction retains one redacted rejected receipt and Audit without changing accepted facts.

The fixed boundary errors are:

- 404: `RESOURCE_NOT_FOUND`, `SNAPSHOT_ISSUE_NOT_FOUND`, `ARTIFACT_NOT_FOUND`
- 403: `PROJECT_SCOPE_MISMATCH`; a missing OAuth scope uses the existing `ACCESS_DENIED`
- 409: `ARTIFACT_DIGEST_MISMATCH`, `IDEMPOTENCY_CONFLICT`, `BUILD_PROVENANCE_CONFLICT`
- 422: `PROOF_VALIDATION_FAILED`, `FACT_LIMIT_EXCEEDED`, and other allowlisted Domain violations
- 413: the request body exceeds the configured limit
- 503: `PERSISTENCE_UNAVAILABLE`

Problem, logs, Audit, Outbox, and Smoke Evidence must not contain a raw Envelope, Token, Cookie, Authorization header, GitHub event, Provider response, author email, PR content, Runner path, absolute path, or stack trace.

## 5. Gate and Evidence

Run from the repository root:

```powershell
pwsh -NoProfile -File scripts/tests/m2-build-provenance-gates.tests.ps1
pwsh -NoProfile -File scripts/m2/verify-build-provenance.ps1
```

The Gate runs contract, migration, canonical, validator, repository, transaction, security, github-smoke, contracts, and acceptance in that fixed order. Each item emits only `CHECK <name> PASS|FAILED`, a test count, and a fixed diagnostic, while the Gate preserves the first real failing child exit code. Missing GitHub context fails `github-smoke` with `GITHUB_CONTEXT_MISSING`; a mismatch between checkout HEAD and `GITHUB_SHA` fails with `EXACT_HEAD_MISMATCH`; and a new Evidence document whose `exactCommit`, `runId`, or `runAttempt` differs from current context fails with `EVIDENCE_CONTEXT_MISMATCH`. Missing local Docker fails PostgreSQL checks with `POSTGRESQL_RUNTIME_UNAVAILABLE`. Those outcomes mean not executed and must never be recorded as PASS; only exact-commit Linux/Docker GitHub Actions can complete them.

The successful live Smoke invokes the real HTTP Endpoint on a random local port for the initial submission, same-key replay, different-key replay, Build Attempt conflict, USER negative path, and wrong-project negative path. It then queries PostgreSQL to verify the Receipt, rejected receipt, Edge/Revision, Audit, Outbox, Locked Manifest, and absence of an `ARTIFACT_RELEASE` write. It produces only `backend/build/m2/build-provenance-smoke.json`, which the same read-only workflow uploads as `m2-build-provenance-${{ github.sha }}`. The file contains only the exact commit, Run/Attempt, schema/validator version, Envelope/Artifact digest, Edge/Revision ID, replay booleans, fixed diagnostics, and counts.

## 6. Shutdown and roll-forward recovery

When an integrity anomaly, repeated conflict, or database outage occurs:

1. Set `VSRQG_TRACEABILITY_INGESTION_ENABLED=false` and restart the application to close new ingestion immediately. Retain the new tables, accepted/rejected Receipt, and every Revision.
2. Preserve the exact commit, fixed Gate summary, CI Run, and redacted Evidence locator. Do not reverse a Migration, delete history, modify an old Revision, or enable a JSON/file/cache fallback.
3. For a temporary database failure, the caller performs bounded retries with the original `Idempotency-Key`. After database recovery, reconcile the Edge Header/Revision chain, Envelope/fact digest, Receipt, Audit, Outbox, and idempotency response.
4. Pass the complete Gate and obtain a successful Smoke Artifact from Linux/Docker CI at the same exact commit. If any inconsistency remains, stay fail-closed and correct it with a new forward-only Migration or compatible application.
5. Re-enable the Pilot endpoint only after the Project Owner reviews the Evidence and independently approves recovery.

These steps do not authorize Company, real Jira, company CI, M2.5, a `main`/`release` merge, Tag, release, or production deployment.
