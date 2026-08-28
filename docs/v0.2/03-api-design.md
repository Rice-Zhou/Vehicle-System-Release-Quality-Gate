# 03 — Core API Design

## 1. API Layers

```text
Transport API (REST/OpenAPI)
  → Application Use Case (authentication, authorization, idempotency, transaction)
    → Domain Port (business invariants)
      → Persistence / Adapter / Object Storage Port
```

Transport does not contain domain decisions. Adapter APIs are not directly exposed to Core clients. Agent APIs and user APIs use separate identity and permission domains.

## 2. General Contract

- Base path: `/api/v1`; Agent: `/agent-api/v1`.
- Media type: `application/json`; time uses ISO-8601 UTC; IDs are opaque strings.
- Write operations require `Idempotency-Key`; successful creation returns `201`, asynchronous acceptance returns `202`.
- Pagination uses an opaque cursor: `?limit=50&cursor=...`; the response contains `nextCursor`.
- Every response includes `X-Request-Id`; clients may provide a valid request ID.
- Concurrent modification uses `ETag` / `If-Match` or explicit `rowVersion`.
- OpenAPI 3.1 is the external contract; the implementation framework is replaceable.

### 2.1 Machine-Executable Contract

- OpenAPI 3.1 Draft: [`contracts/openapi/v0.2/openapi.json`](../../contracts/openapi/v0.2/openapi.json).
- Compatibility baseline: [`contracts/openapi/v0.2/compatibility-baseline.json`](../../contracts/openapi/v0.2/compatibility-baseline.json).
- OpenAPI covers every Method/Path in this document and the Agent Protocol table. `x-permission` and `x-idempotency-required` freeze permission and idempotency requirements.
- Run `pnpm install --frozen-lockfile`, then `scripts/verify-contracts.ps1` locally. Validation covers OpenAPI reference resolution, document/API Endpoint-set equality, permission/idempotency attributes, and the compatibility baseline.
- Changing an existing Operation's Path, Method, Permission, Idempotency, or Request Contract is incompatible and requires an explicit compatibility-baseline update and Review. A change to Core Contract semantics still requires an ADR.

## 3. Core Endpoints

| Method | Endpoint | Responsibility | Permission | Idempotent |
|---|---|---|---|---|
| POST | `/releases` | Create independent Release identity | `release:create` | Yes |
| GET | `/releases/{releaseId}` | Get Release | `release:read` | Intrinsic |
| POST | `/releases/{releaseId}/manifests` | Register Manifest Revision | `manifest:write` | Yes |
| GET | `/releases/{releaseId}/manifests/{manifestId}` | Export the locked authoritative Manifest, digest, and validation report | `release:read` | Intrinsic |
| POST | `/releases/{releaseId}/manifests/{manifestId}:validate` | Perform auditable validation | `manifest:write` | Yes |
| POST | `/releases/{releaseId}/manifests/{manifestId}:lock` | Lock authoritative Manifest | `manifest:lock` | Yes |
| POST | `/issue-sources/{sourceId}/sync` | Start Issue Source synchronization | `issue:sync` | Yes |
| GET | `/issue-sync-runs/{syncRunId}` | Get Issue synchronization run status | `issue:read` | Intrinsic |
| POST | `/releases/{releaseId}/issue-snapshots` | Create Snapshot from specified sync result | `issue:snapshot` | Yes |
| GET | `/releases/{releaseId}/traceability` | Query trace chain and gaps | `traceability:read` | Intrinsic |
| POST | `/releases/{releaseId}/traceability:verify` | Verify and freeze Snapshot | `traceability:verify` | Yes |
| POST | `/traceability/facts:ingest` | Ingest Traceability Facts through a Service Identity | `traceability:ingest` | Yes |
| POST | `/test-runs` | Create Run for Locked Release | `test:execute` | Yes |
| POST | `/test-runs/{id}:cancel` | Request cancellation | `test:execute` | Yes |
| GET | `/test-runs/{id}/results` | Get Results/Attempts | `test:read` | Intrinsic |
| GET | `/evidence/{evidenceId}` | Get Metadata without a permanent object URL | `evidence:read` | Intrinsic |
| POST | `/evidence/{evidenceId}:download` | GENERAL/RESTRICTED download request returning a ≤60-second Presigned URL | `evidence:read` | Yes and audited |
| GET | `/evidence/{evidenceId}/payload` | Authenticate every HIGH Payload request and stream through Backend/Gateway | `evidence:read:sensitive` | Intrinsic and audited |
| POST | `/rule-sets` | Create Draft Rule Set | `rule:write` | Yes |
| POST | `/rule-sets/{id}:publish` | Publish immutable version | `rule:publish` | Yes |
| POST | `/releases/{releaseId}/quality-evaluations` | Trigger evaluation with fixed inputs | `quality:evaluate` | Yes |
| GET | `/releases/{releaseId}/quality-results` | Query historical Results | `quality:read` | Intrinsic |
| POST | `/quality-results/{id}:override` | Record manual governance decision | `quality:override` | Yes, strongly audited |
| POST | `/releases/{releaseId}:approve` | Approve Release | `release:approve` | Yes |

The Idempotency Record TTL for an ordinary Evidence download request equals the Presigned URL lifetime. The same key returns the same grant within TTL; after expiry, the caller must use a new key and reauthorize. HIGH Payload GET creates no reusable grant and authenticates every request.

Override does not rewrite the algorithmic Quality Result. It creates a separate Governance Decision and preserves the original PASS/WARNING/BLOCK.

## 4. Representative Models

### Create Release

```json
{
  "project": "vehicle-x",
  "vehicle": "model-a",
  "platform": "android-automotive",
  "systemVersion": "2026.08-rc1",
  "buildId": "build-1842"
}
```

```json
{
  "releaseId": "rel_01...",
  "status": "DRAFT",
  "manifestId": null,
  "createdAt": "2026-08-21T10:00:00Z",
  "version": 1
}
```

### Lock Manifest

The request body contains only review rationale and cannot replace Manifest content:

```json
{"reason":"Artifacts and checksums verified for RC1"}
```

Response:

```json
{
  "releaseId":"rel_01...",
  "manifestId":"man_01...",
  "manifestRevision":2,
  "contentDigest":"sha256:...",
  "state":"LOCKED",
  "lockedAt":"2026-08-21T11:00:00Z"
}
```

### Create Test Run

```json
{
  "releaseId":"rel_01...",
  "testPlan":{"planId":"release-smoke","version":1},
  "deviceSelector":{"vehicle":"model-a","requiredCapabilities":["ADB","CRASH","ANR"]}
}
```

### Request Quality Evaluation

```json
{
  "ruleSet":{"ruleSetId":"mvp-gate","version":1},
  "testRunIds":["run_01..."],
  "traceabilitySnapshotId":"trs_01..."
}
```

The server resolves and freezes all actual inputs and returns `202` with `evaluationId`. A caller cannot submit an arbitrary "passed" fact.

## 5. Error Model

Use RFC 9457 Problem Details:

```json
{
  "type":"https://vsrqg.example/problems/manifest-not-locked",
  "title":"Manifest is not locked",
  "status":409,
  "code":"MANIFEST_NOT_LOCKED",
  "detail":"Release rel_01... cannot enter testing",
  "instance":"/api/v1/test-runs",
  "requestId":"req_01...",
  "violations":[]
}
```

| HTTP | Semantics |
|---|---|
| 400 | Malformed JSON or parameter format |
| 401/403 | Unauthenticated / unauthorized |
| 404 | Resource absent or not visible |
| 409 | State conflict, idempotency-digest conflict, or version conflict |
| 422 | Schema is valid but domain validation failed; includes violations |
| 429 | Rate limited; includes Retry-After |
| 503 | Explicit dependency unavailability; must not masquerade as success |

An unknown exception returns a stable generic error and logs a correlated request ID without exposing stack traces, credentials, or sensitive external response data.

## 6. API Version and Compatibility

- Use a path major version; add backward-compatible fields within the same major.
- Clients must ignore unknown response fields. The server rejects unknown write fields by default so misspellings are not silently swallowed.
- Removal, rename, or semantic change requires a new major version, migration period, and TDR; a Core Contract impact requires ADR.
- OpenAPI diff in CI blocks undeclared breaking changes.

## 7. Idempotency

The server stores `(principal, endpoint, idempotency_key, request_digest, response_status, response_body)`. The same key and digest returns the original response; the same key with a different digest returns `409 IDEMPOTENCY_KEY_REUSED`. Retention must cover the maximum client retry window.

Agent `commandId`, Adapter `(source, sourceVersion)`, Evidence `(collector, payloadChecksum, run)`, and the Quality Evaluation composite key provide domain-level idempotency.

## 8. Acceptance

- OpenAPI lint and breaking-change check pass.
- Every write Endpoint has permission, idempotency, and concurrency tests.
- Repeated requests produce one business result.
- Error paths return machine-readable codes with no false success or sensitive information.
- The Owner can complete the entire Release loop through APIs without direct database access.

Evidence: published OpenAPI, contract-test report, permission-matrix tests, idempotency concurrency tests, and API Audit samples.
