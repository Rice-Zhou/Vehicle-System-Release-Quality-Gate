# 12 — Authentication, Authorization and Audit

## 1. Boundary

Human identity reuses company OIDC/OAuth 2.1. Services, Adapters, and Agents use separate workload identities/service accounts. VSRQG does not build a password system and does not store plaintext Secrets in Git, source code, Manifest, logs, or business tables.

See [TDR-009](tdr/TDR-009-oidc-and-service-identities.md) for rationale.

## 2. Identity Types

| Identity | Authentication | Purpose | Prohibited |
|---|---|---|---|
| User | OIDC Authorization Code + PKCE | UI/API actions | Shared account |
| CI Service Account | OAuth client credential/short-lived token | Register Release/Manifest/Build | Human login |
| Adapter Service Account | Credentials injected by Secret Manager | External API sync | Writing external token to DB |
| Agent/Device Identity | mTLS or short-lived client credential | Agent Protocol | Using a user token |
| Internal Worker | In-process identity/controlled service identity | Background jobs | Bypassing application authorization to write DB |

The database stores only principal ID, issuer, subject, state, and credential reference. Secrets reside in company Secret Manager/deployment platform.

## 3. Static RBAC

| Capability | Viewer | Engineer | Release Manager | Quality Owner | Administrator |
|---|:---:|:---:|:---:|:---:|:---:|
| View Release/Trace/Report | ✓ | ✓ | ✓ | ✓ | ✓ |
| View general Evidence | ✓ | ✓ | ✓ | ✓ | ✓ |
| View sensitive Evidence |  | As authorized | As authorized | ✓ | ✓ |
| Create Release/Manifest |  | ✓ | ✓ |  | ✓ |
| Lock Manifest |  |  | ✓ |  | ✓ |
| Execute/cancel Test |  | ✓ | ✓ |  | ✓ |
| Create Rule Draft |  |  |  | ✓ | ✓ |
| Publish Rule Set |  |  |  | ✓ | ✓ |
| Override Quality Result |  |  |  | ✓ | ✓ |
| Approve Release |  |  | ✓ | ✓ (per governance) | ✓ |
| Manage identity/system configuration |  |  |  |  | ✓ |

Permissions are fine-grained; roles are stable permission sets. MVP does not implement ABAC/policy language. Principal-project assignments constrain project scope.

## 4. High-Risk Operations

Manifest Lock, Rule Publish, Quality Override, and Release Approval recheck permission and resource version and record actor, reason, request ID, and before/after state. During Pilot, Production Rule Publish and BLOCK Override may reference an external approval record; its ID, approver, and timestamp must enter the Audit Event. Before use in a real company project, these operations must use system-enforced two-person approval or a company-equivalent approval control that proves separation of duties. The requester and approver must not be the same principal.

Override does not rewrite algorithmic Results. Owner policy determines governance semantics for approving PASS/WARNING/BLOCK.

## 5. Evidence Authorization

Authorize Metadata and Payload separately. Before download, check project scope, Evidence sensitivity, purpose, and retention state, then return a minutes-long presigned URL. Audit the download and do not log the URL. Sensitive dumps/logs may require extra permission and watermark/approval.

## 6. Audit Event

An append-only Event contains eventId, occurredAt, actor type/id, action, resource type/id, project, requestId, result, reason, before/after digest, source IP/agent ID, and application version. Sensitive fields store only digests or references.

Audit at least Release create, Manifest register/lock, Snapshot, Test execute/cancel, Evidence access/delete, Rule publish, Evaluation, Override, Approval, identity/role change, and credential-reference rotation.

## 7. Failure and Recovery

- OIDC unavailable: valid existing short-lived tokens operate according to policy. New login fails explicitly; there is no anonymous fallback.
- Permission service/mapping failed: fail closed with 503/403 and never default to Admin.
- Agent credential leaked: revoke identity, mark Agent REVOKED, quarantine related Commands, and rotate.
- Secret Manager unavailable: dependent Adapter/worker becomes DEGRADED and never falls back to plaintext credentials in logs/configuration.
- Audit write failed: the entire high-risk write fails; do not execute first and lose Audit.

## 8. Acceptance

- Automatically test every permission-matrix cell; cross-project access fails.
- Secret scan, log inspection, and database inspection find no plaintext credentials.
- Expired/revoked tokens, wrong issuer/audience, and replayed tokens are rejected.
- Every high-risk action can be reconstructed from Audit Events.
- Sensitive Evidence download URLs are short-lived and cannot be reused across users, subject to storage capability.

Evidence: RBAC test report, OIDC integration tests, Secret scan, Audit export, and credential-revocation rehearsal.
