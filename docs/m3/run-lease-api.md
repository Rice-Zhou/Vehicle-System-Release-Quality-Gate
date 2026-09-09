# Single-device Run and Lease API

Task 3 implements Create/Cancel Run, Result queries, Agent heartbeat/poll/ACK and Context GET. Event, Agent Result submission and runtime Evidence upload belong to subsequent tasks. No successful results are seeded; Run completion does not imply Release PASS or Traceability Verified.

## Demonstration Configuration and Published Inputs

The ordinary Backend disables Smoke Run creation by default. The demonstration uses the following server configuration:

| Configuration | Constraint |
|---|---|
| `vsrqg.demo.smoke.enabled` | Defaults to `false`; explicit enablement permits Run creation and starts the deadline Worker. |
| `vsrqg.demo.smoke.agent-id` | Explicit preregistered Agent ID, bound to the existing SERVICE principal, project and Device. |
| `vsrqg.demo.smoke.device-id` | Explicit preregistered Device ID matching the Agent binding; no automatic device selection. |
| `vsrqg.demo.smoke.environment-config-base64` | Standard Base64 encoding of actual raw CONFIG bytes; at most 87384 encoded characters and 65536 decoded bytes. Base64 is transport encoding, not encryption. |
| `vsrqg.test.deadline-worker.enabled` | Defaults to `true` when the demonstration is enabled; tests can explicitly disable automatic scans. |
| `vsrqg.test.deadline-worker.interval-ms` | Defaults to 1000 milliseconds; business deadlines use the server TimeProvider independently of scan frequency. |

CONFIG is strict UTF-8 JSON accepting only three required fields, `bootSessionId`, `buildId` and `buildFingerprint`, each 1–128 characters long. Unknown or duplicate fields, invalid UTF-8, missing or oversized content fail explicitly. The server computes SHA-256 over **raw CONFIG bytes** and compares it with the required CONFIG checksum in the Locked Manifest; canonical JSON digests do not replace file digests. Controlled demonstration initialization supplies this configuration; client paths and “environment matched” booleans are not accepted.

The registered Device `vehicle` and `platform` must match the Release. Existing V12 Devices remain intact but cannot participate in Smoke Runs until matching attributes are supplied. Strict CreateTestRunRequest retains its original fields: `releaseId`, `testPlan` and `deviceSelector`. selector.vehicle and capability requirements remain validated.

Create only reads existing PUBLISHED Plan/Case Versions; it never publishes or modifies definitions. Subsequent demonstration initialization publishes `single-device-smoke` / `apk-launch-smoke` v1(normal) and v2(assertion-failure), with one required Case per Plan and at most one Attempt. Missing, DRAFT or incorrect versions, insufficient capabilities and other required Artifact types are rejected. Manifest Lock continues to use M1 actual-file verification and trust policy; the ordinary INCOMPLETE default is unchanged.

## Behavior and Transactions

Create fixes the Locked Manifest identity/digest, Environment bytes/digest, Plan/Case Version, Run, one QUEUED Attempt, Schema-validated Context and its JCS SHA-256 digest in one transaction with Audit/Outbox. Context fixes commandId in advance; the Command row is created only at first dispatch.

Attempts use only standard lowercase UUIDs, converted from one existing IdGenerator `nextId("att_")` result without retaining an att_ alias. APK markers, APIs and all FKs use that same value.

All write paths lock Agent identity first, then Run → Attempt → subsequent Evidence. Device is read under a shared lock without updating its state and upgrading the lock. An active-Run partial unique index ensures at most one exclusive Run per Device. Registration, renewal, cancellation and Worker paths never acquire execution locks in reverse order.

Poll returns at most one Command, or JSON null when empty. It waits at most 20 seconds without holding a database transaction. Existing idempotency records replay original responses; new polls do not redeliver accepted Commands after ACK. Accepted ACK returns the same leaseId, fencingToken and leaseExpiresAt. Rejection requires reasonCode and produces an explicit ERROR terminal state.

Leases last 90 seconds with the exclusive expiry boundary `now < expiresAt`. Heartbeat binds the current generation through the authenticated Agent, exact currentCommandId and bootSessionId; no client fencing field is added to the protocol. Replaying the same heartbeat key does not extend the lease again. Old commands, expired leases and terminal states cannot regain write access. AttemptAccess requires an existing caller transaction and retains row locks through dependent writes. Evidence/Result callers must still compare request leaseId/fencingToken exactly with the returned binding.

Allocation expires after 60 seconds; Command/Case deadlines are at most 300 seconds from dispatch, and Run deadlines are at most 600 seconds from creation. Disconnection enters RECOVERY_PENDING with a recovery window of at most 120 seconds, bounded by Case/Run deadlines. Expired leases cannot resume business writes. The Worker reconstructs state from persisted leases/deadlines: after server downtime spanning the entire recovery window, the first scan records TIMEOUT immediately. Unrecoverable Agent identity/capability changes or reported bootSession changes produce ERROR without replaying installation.

Cancellation or deadline termination increments fencingToken, closes the Attempt, writes one immutable SERVER Result with Audit/Outbox, then closes the Run and releases device occupancy in one transaction. Cancellation maps to BLOCKED / CANCELLED_BY_OPERATOR. Without an execution-start fact, Result.startedAt and durationMs remain null; ACK means Command acceptance and does not invent a Case start. Task 3 has no runtime Evidence data, so required Evidence is explicitly FAILED and cannot produce PASS.

## Queries and Scope

User Result queries preserve the `items` / `nextCursor` contract of `GET /api/v1/test-runs/{id}/results`. Running work without a committed Result returns empty items. Existing results include source, status, reasonCode, timestamps, Agent/Device, Evidence requirements and resultDigest. SERVER resultDigest is JCS SHA-256 of the fixed Result JSON without the derived resultDigest field. Subsequent requests do not overwrite Results or Run/Attempt state histories.

Context GET is available only for an Attempt actually dispatched to the current Agent and returns fixed Schema content without credentials, local paths or raw device serials. Agent mTLS and user JWT remain separate; project authorization reuses ProjectAuthorizer, AgentAccess and Permission.

Schema, state boundaries and HTTP identity/body tests can run locally. Row locks, partial unique indexes, migrations and deferred constraints require real PostgreSQL integration tests. Initialization failure on a host without Docker is not successful database verification.
