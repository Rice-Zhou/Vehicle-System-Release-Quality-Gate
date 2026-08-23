# TDR-009 — OIDC for Users and Separate Service Identities

- Status: Proposed for V0.2 Review
- Scope: authentication for people, CI, Adapters, and Agents

## Problem and Requirements

A company of about 300 people needs unified human identities, offboarding/revocation, roles, and Audit. CI, Adapters, and Agents must not share human credentials. The project has neither need nor capacity to build a secure identity system.

## Decision and Rationale

Users sign in through company OIDC/OAuth 2.1. CI/Adapters use separate service accounts and short-lived credentials. Agents use mTLS or short-lived client identities. The application maps external subjects to local RBAC/project scopes. Secrets exist only in Secret Manager.

## Alternatives Not Selected

- Custom username/password: high security, compliance, lifecycle, and operational cost.
- Shared API key: cannot distinguish principals, revoke selectively, or audit.
- Network allowlist only: not an identity and cannot enforce action permissions.
- Tokens in Manifest/configuration repository: violates security boundaries.

## V0.2 / V0.3 Impact

V0.2 depends on the company IdP but gains unified governance. V0.3 may add group sync, fine-grained project policy, and stronger workload identity without changing the principal/permission model.

## Migration and Rollback

Preserve stable mapping through issuer+subject. An IdP change uses controlled identity mapping and a dual-issuer transition. Roll back the application if authentication integration fails; never fall back to anonymous or default Admin.

## Testing, Deployment, and Recovery

Test issuer, audience, expiry, signature, revocation, permission matrices, and cross-project access. Inject client configuration from Secret Manager at deployment. Fail closed during IdP failure. Company process must govern and strongly audit break-glass access.

## Re-evaluation Triggers

The company has no OIDC capability, compliance requirements change, or device-scale certificate lifecycle infrastructure becomes necessary.
