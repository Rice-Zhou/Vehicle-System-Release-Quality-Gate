# Pilot / Company Dual-Mode Configuration Design

## 1. Goal

Define a deployable maturity configuration for V0.2. The project can continue development and validation without obtaining corporate S3, OIDC, or operational resources in advance. When entering the corporate environment, the same implementation must fail closed if required security, retention, or immutability capabilities are absent.

This design accepts that target-capability switches are enabled by default, but configuration intent must never be treated as an external fact. A default boolean value of `true` means that the system requires the control. It does not prove that archival is complete, Object Lock is active, or corporate resources exist.

## 2. Architecture and Governance Boundary

- Do not modify the V0.1 Core Contract, Release-centric architecture, Manifest authority, Evidence, Traceability, Quality Engine, Adapter, Plugin, or ADR governance.
- Preserve the S3-compatible Evidence Payload Storage decision in `TDR-004` and the corporate deployment direction in `TDR-010`.
- `LOCAL_PILOT` means only local staging and demonstration capability. It is not a long-term Evidence archive provider and cannot produce an archival `PASS`.
- A Profile affects deployment readiness, archival operations, and acceptance-evidence interpretation. It does not change the business semantics of a deterministic Quality Result.
- Owner decisions, merges, Tags, and releases continue to require separate authorization.

## 3. Selected Approach

### 3.1 Adopted: Two Profiles with Truthful Capability State

Use the `PILOT` and `COMPANY` deployment Profiles. Every target-control boolean defaults to `true`; Provider and verified capability use enums and cannot be replaced by booleans. `PILOT` permits operation without external resources but cannot fabricate success. `COMPANY` fails closed when mandatory controls are unmet.

This approach supports a six-month project boundary and company-grade requirements without maintaining two business implementations.

### 3.2 Rejected: Default Every External State to True

Defaulting actual states such as archived, encrypted, or Object Lock enabled to `true` would create success claims that Evidence cannot reproduce. That violates Evidence-first, auditability, and determinism.

### 3.3 Rejected: Default Everything to False During the Project

Disabling every control would separate the development path from the future corporate path and defer integration gaps until rollout. Target controls must be enabled from the first day while unavailable external capability remains explicitly unverified.

## 4. Configuration Contract

Input configuration uses one namespace, and configuration files must not contain credentials:

| Property | Type | Default | Rule |
|---|---|---|---|
| `vsrqg.deployment.mode` | enum | `PILOT` | Only `PILOT` and `COMPANY` are allowed |
| `vsrqg.evidence.archive.enabled` | boolean | `true` | Enables the archival workflow; it does not mean archival is complete |
| `vsrqg.evidence.archive.checksum-verification-enabled` | boolean | `true` | Recomputes SHA-256 before and after upload |
| `vsrqg.evidence.archive.encryption-required` | boolean | `true` | Requires encryption at rest and in transit |
| `vsrqg.evidence.archive.private-access-required` | boolean | `true` | Prohibits anonymous and public reads |
| `vsrqg.evidence.archive.retention-policy-required` | boolean | `true` | Requires an explicit retention policy |
| `vsrqg.evidence.archive.immutability-required` | boolean | `true` | Requires Object Lock/WORM or an approved equivalent control |
| `vsrqg.evidence.archive.provider` | enum | `NONE` | Only `NONE`, `FILESYSTEM_STAGING`, and `S3_COMPATIBLE` are allowed |
| `vsrqg.evidence.archive.staging-root` | string | empty | Required for `FILESYSTEM_STAGING` and must be an explicit absolute path |
| `vsrqg.evidence.archive.endpoint` | string | empty | Validated by Profile when using `S3_COMPATIBLE` |
| `vsrqg.evidence.archive.region` | string | empty | Required when the Provider requires it |
| `vsrqg.evidence.archive.bucket` | string | empty | Required when using `S3_COMPATIBLE` |
| `vsrqg.evidence.archive.object-prefix` | string | `acceptance/` | Allows only a normalized relative object key prefix |
| `vsrqg.evidence.archive.access-owner` | string | empty | Required for corporate archival |
| `vsrqg.evidence.archive.retention-period` | duration | empty | Required for corporate archival and must be positive |

Credentials may come only from environment variables, Secret Manager, Workload Identity, or a credential profile. Configuration responses, logs, Audit, and Git must never contain secret values.

## 5. Derived State

The system produces a read-only Capability Report from configuration and active verification. A caller cannot directly supply the result:

| State | Determination |
|---|---|
| `UNCONFIGURED` | Provider is `NONE`, or mandatory connection properties are absent |
| `LOCAL_PILOT` | Provider is `FILESYSTEM_STAGING`, the staging path is writable, and SHA-256 can be recomputed |
| `EXTERNAL_UNVERIFIED` | `S3_COMPATIBLE` is configured, but connection, permission, or control verification is incomplete |
| `EXTERNAL_VERIFIED` | Endpoint, Bucket, write, read-back, digest, private access, retention, and immutability checks all pass |

The Capability Report contains at least the Profile, Provider, state, check time, individual check results, and secret-free failure reasons. It is deployment evidence, not a Core Evidence Entity or Quality Result.

## 6. Behavior Matrix

| Profile / State | System Operation | Archival Behavior | Acceptance Interpretation |
|---|---|---|---|
| `PILOT` / `UNCONFIGURED` | Allowed | Explicitly unavailable | Evidence retention is `UNKNOWN` |
| `PILOT` / `LOCAL_PILOT` | Allowed | Can stage, create a manifest, and recompute digests | Cannot count as long-term archival `PASS` |
| `PILOT` / `EXTERNAL_UNVERIFIED` | Allowed and reported as degraded | Refuses to declare archival complete | Evidence retention is `UNKNOWN` |
| `PILOT` / `EXTERNAL_VERIFIED` | Allowed | Can create a reviewable archive receipt | May be `PASS` only with complete Evidence |
| `COMPANY` / not `EXTERNAL_VERIFIED` | Management endpoints can start; readiness is NOT_READY | Archival and archive-dependent approval paths fail closed | Cannot enter the corporate release Gate |
| `COMPANY` / `EXTERNAL_VERIFIED` | READY | Can archive and perform read-back verification | Determined from the real receipt |

`COMPANY` never silently falls back to `LOCAL_PILOT`. Changing the Profile invalidates old Capability state and requires a fresh verification.

## 7. Data Flow and Archive Receipt

```text
Configuration
    -> Configuration Validator
    -> Provider Probe
    -> Capability Report
    -> Archive Command
    -> Upload
    -> Read-back SHA-256 Verification
    -> Archive Receipt
    -> Acceptance Record Reference
```

An Archive Receipt records the acceptance ID, source Artifact ID/Run/commit, source digest, destination locator, destination digest, size, access owner, retention policy, immutability control, archivedAt, and verifier. A failed path never produces a successful receipt.

## 8. Error Handling

- Invalid configuration: startup configuration validation fails with the exact property and reason.
- Unreachable or unauthorized Provider: state becomes `EXTERNAL_UNVERIFIED`; preserve the real error and do not switch Provider.
- Upload failure: do not create a receipt and do not delete the staging source.
- Read-back digest mismatch: fail archival and retain expected/actual digests; never rewrite the expected value.
- Retention or immutability cannot be proven: uploading a file is insufficient for `EXTERNAL_VERIFIED`.
- Expired Capability Report or changed Profile: verify again before archival.

## 9. Security Constraints

- Configuration objects and diagnostics reject or redact credentials, tokens, and presigned URLs.
- Object prefixes prohibit absolute paths, `..`, and non-normalized separators to prevent out-of-scope writes.
- `FILESYSTEM_STAGING` uses only an explicit staging root and cannot claim WORM or corporate retention.
- The S3 Bucket prohibits public access and limits permissions to the designated prefix; production prefers short-lived identity.
- An Archive Receipt records only stable locators and digests, never secrets or temporary Bearer URLs.

## 10. Test Strategy

At minimum, cover:

1. Default configuration loads as `PILOT`, all target-control booleans are `true`, and Provider is `NONE`.
2. `PILOT` plus `NONE` starts with Capability `UNCONFIGURED`, and archival produces no receipt.
3. `FILESYSTEM_STAGING` can write and recompute digests but cannot produce long-term archival `PASS`.
4. `COMPANY` without Endpoint, Bucket, owner, retention, or immutability has readiness NOT_READY.
5. S3 upload requires read-back SHA-256 recomputation and fails closed on mismatch.
6. Only successful verification of every control produces `EXTERNAL_VERIFIED`.
7. A Profile change invalidates old Capability state.
8. Logs, errors, and receipts contain no credentials, tokens, or presigned URLs.
9. An acceptance record can mark Evidence retention `PASS` only from a successful Archive Receipt.

## 11. Migration and Rollback

The initial default is `PILOT` plus `NONE`. During the project, `FILESYSTEM_STAGING` may demonstrate transfer and digest flow but does not change acceptance facts. After corporate resources are available, configure `S3_COMPATIBLE`, verify it, and then switch to `COMPANY`.

Rollback changes the Profile from `COMPANY` to `PILOT` only to restore non-production development. It does not delete external objects, overwrite receipts, or rewrite a failed corporate check as successful. Provider migration continues to follow the inventory, digest verification, and source-preserving rules in `TDR-004`.

## 12. Current M1 Decision

This design does not automatically modify `M1-OWNER-GATE-001`. The current local ZIP files and transfer manifest are staging only, and the existing `CONDITIONAL` decision remains effective. If the Owner moves corporate immutable archival to a production-rollout residual risk, that change requires a new explicit Owner instruction and acceptance-record transition; it cannot be derived from Profile configuration.

## 13. Implementation and Technology Decision

The later implementation plan must first add `TDR-011` to record the Profile, derived enforcement, Capability Report, failure recovery, testing, deployment, and corporate migration decisions. The implementation reuses one Archive Port: `FILESYSTEM_STAGING` and `S3_COMPATIBLE` are Adapters only and must not create a second acceptance or Quality Engine.

## 14. Design Acceptance Criteria

- Default project configuration runs without corporate resources.
- Every target-control boolean defaults to `true`.
- Actual external capability always comes from verification and cannot be fabricated by boolean configuration.
- Pilot staging and corporate long-term archival have distinct states and acceptance semantics.
- Company mode fails closed when mandatory capability is absent.
- The design does not change the V0.1 frozen architecture or the long-term storage direction in `TDR-004`.
- Chinese and English specifications have paired semantics, and every non-Markdown file remains byte-identical.
