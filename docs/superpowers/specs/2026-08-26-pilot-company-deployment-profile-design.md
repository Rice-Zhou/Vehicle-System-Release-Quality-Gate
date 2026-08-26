# Pilot / Company Dual-Mode Configuration Design

## 1. Goal

Define a deployable maturity configuration for V0.2. The project can continue development and validation without obtaining corporate S3, OIDC, or operational resources in advance. When entering the corporate environment, the same implementation must fail closed if required security, retention, or immutability capabilities are absent.

This design accepts that target-capability switches are enabled by default, but configuration intent must never be treated as an external fact. A default boolean value of `true` means that the system requires the control. It does not prove that archival is complete, Object Lock is active, or corporate resources exist.

## 2. Architecture and Governance Boundary

- Do not modify the V0.1 Core Contract, Release-centric architecture, Manifest authority, Evidence, Traceability, Quality Engine, Adapter, Plugin, or ADR governance.
- Preserve the S3-compatible Evidence Payload Storage decision in `TDR-004` and the corporate deployment direction in `TDR-010`.
- `LOCAL_PILOT` means only local staging and demonstration capability. It is not a long-term Evidence archive provider and cannot produce an archival `PASS`.
- A Profile affects deployment readiness, archival operations, and acceptance-evidence interpretation. It does not change the business semantics of a deterministic Quality Result.
- The only public application boundary is `ArchiveEvidence`; `ArchiveAdapter` and `ArchiveAuthorization` are Kotlin module-internal types. A caller can submit only an `ArchiveCommand`, never a Capability Report or authorization. `EvaluateArchiveCapability` and `ArchiveEvidence` form the only trusted call chain and do not add a second Capability data source.
- Owner decisions, merges, Tags, and releases continue to require separate authorization.

## 3. Selected Approach

### 3.1 Adopted: Two Profiles with Truthful Capability State

Use the `PILOT` and `COMPANY` deployment Profiles. Every target-control boolean defaults to `true`; Provider and verified capability use enums and cannot be replaced by booleans. `PILOT` permits operation without external resources but cannot fabricate success. `COMPANY` is READY only when `archive.enabled=true` and Capability is `EXTERNAL_VERIFIED`; readiness and archive operations fail closed if either condition is false.

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
| `vsrqg.evidence.archive.endpoint` | string | empty | When non-empty, must be an absolute `http`/`https` URI with a non-empty host and no user-info, query, or fragment |
| `vsrqg.evidence.archive.region` | string | empty | Required when the Provider requires it |
| `vsrqg.evidence.archive.bucket` | string | empty | Required when using `S3_COMPATIBLE` |
| `vsrqg.evidence.archive.object-prefix` | string | `acceptance/` | Allows only a normalized relative object key prefix |
| `vsrqg.evidence.archive.access-owner` | string | empty | Required for corporate archival |
| `vsrqg.evidence.archive.retention-period` | duration | empty | Required for corporate archival and must be positive |
| `vsrqg.evidence.archive.probe-timeout` | duration | `PT5S` | Must be positive; bounds external Provider Capability requests |
| `vsrqg.evidence.archive.operation-timeout` | duration | `PT30S` | Must be positive and at least `probe-timeout`; bounds external Provider upload, download, read-back, Head, and receipt requests |

Credentials may come only from environment variables, Secret Manager, Workload Identity, or a credential profile. Configuration responses, logs, Audit, and Git must never contain secret values.

## 5. Derived State

The system produces a read-only Capability Report from configuration and active verification. A caller cannot directly supply the result:

| State | Determination |
|---|---|
| `UNCONFIGURED` | Provider is `NONE`, or mandatory connection properties are absent |
| `LOCAL_PILOT` | Provider is `FILESYSTEM_STAGING`, the staging path is writable, and SHA-256 can be recomputed |
| `EXTERNAL_UNVERIFIED` | `S3_COMPATIBLE` is configured, but connection, permission, or control verification is incomplete |
| `EXTERNAL_VERIFIED` | Endpoint, Bucket, dedicated control-object write/read-back/digest, private access, retention, actual mode, and runtime-identity restrictions all pass |

The Capability Report contains at least the Profile, Provider, state, `policyFingerprint`, `checkedAt`, individual check results, and secret-free failure reasons. The policy fingerprint is a deterministic SHA-256 of the normalized, secret-free Profile, Provider, policy, and configuration snapshot; any related field change changes the fingerprint.

Capability uses single-use, fresh-probe semantics. Every readiness evaluation probes again, and every archive command probes again immediately before execution. A report is bound only to the snapshot identified by its fingerprint and `checkedAt`; it cannot be cached or reused as authorization. A configuration or Profile change, and any probe, upload, read-back, or receipt failure, invalidates the current report. External Provider probes are bounded by `probe-timeout`, external archive requests are bounded by `operation-timeout`, and a timeout is a failure.

A public boundary cannot accept a report or authorization. Internal `EvaluateArchiveCapability` uses one private evaluate path to produce either a readiness report or module-internal opaque `ArchiveAuthorization`; `ArchiveEvidence` obtains it and immediately invokes the internal `ArchiveAdapter`. The authorization constructor is not open to another module, and an Adapter accepts authorization rather than a caller-constructible report. A Capability Report is deployment evidence, not authority, a Core Evidence Entity, or a Quality Result.

## 6. Behavior Matrix

| Profile / State | System Operation | Archival Behavior | Acceptance Interpretation |
|---|---|---|---|
| `PILOT` / `UNCONFIGURED` | Allowed | Explicitly unavailable | Evidence retention is `UNKNOWN` |
| `PILOT` / `LOCAL_PILOT` | Allowed | Can stage, create a manifest, and recompute digests | Cannot count as long-term archival `PASS` |
| `PILOT` / `EXTERNAL_UNVERIFIED` | Allowed and reported as degraded | Refuses to declare archival complete | Evidence retention is `UNKNOWN` |
| `PILOT` / `EXTERNAL_VERIFIED` | Allowed | Can create a reviewable archive receipt | May be `PASS` only with complete Evidence |
| `COMPANY` / `archive.enabled=false` | Management endpoints can start; readiness is NOT_READY | Archival and archive-dependent approval paths fail closed even if the Provider can be verified | Cannot enter the corporate release Gate |
| `COMPANY` / `archive.enabled=true` / not `EXTERNAL_VERIFIED` | Management endpoints can start; readiness is NOT_READY | Archival and archive-dependent approval paths fail closed | Cannot enter the corporate release Gate |
| `COMPANY` / `archive.enabled=true` / `EXTERNAL_VERIFIED` | READY | Can archive and perform read-back verification | Determined from the real receipt |

`COMPANY` never silently falls back to `LOCAL_PILOT`. `enabled=false` is an independent readiness and operation Gate and must not be fabricated as a Provider Capability state. Archive Capability contributes to readiness only, not liveness; all other readiness checks remain.

## 7. Data Flow and Archive Receipt

```text
Configuration
    -> Configuration Validator
    -> Provider Probe
    -> Capability Report
    -> Internal Archive Authorization
    -> Archive Command
    -> Upload
    -> Read-back SHA-256 Verification
    -> StoredObjectRef (Payload Exact Version)
    -> Archive Receipt
    -> StoredObjectRef (Receipt Exact Version)
    -> ArchiveReceiptReference
    -> Acceptance Record Reference
```

Every create-only Put returns `StoredObjectRef` containing at least Provider, locator, bucket, key, exact `versionId`, SHA-256, and size. Every read, Head, and protection check uses that exact version and never falls back to the key's latest version. An Archive Receipt records the payload `StoredObjectRef`, acceptance ID, source Artifact ID/Run/commit, access owner, retention policy, actual lock mode or approved equivalent immutability control, `policyFingerprint`, `capabilityCheckedAt`, archivedAt, and verifier.

Receipt content does not contain its own locator, version, or digest, avoiding a self-hash cycle. Only after Receipt Put completes does its `StoredObjectRef` produce an independent `ArchiveReceiptReference` with locator, `versionId`, and SHA-256; Acceptance Evidence stores that reference. The receipt fingerprint and check time must equal the fresh authorization used immediately before that command; a failed path never produces a successful reference.

Immutability `PASS` uses Provider-neutral criteria: the actual payload and Archive Receipt objects are both protected; effective retention is at least the policy requirement; runtime identity cannot overwrite, delete, or bypass retention; and the receipt records the actual mode or an approved equivalent control. A bucket Object Lock flag alone is insufficient.

Capability controls use one consistent UTC-day model. Each `policyFingerprint` has deterministic target and result keys per day. The retention lower bound is fixed at `nextUtcMidnight(checkedAt) + retentionPeriod`, so probes on the same day do not create a rolling threshold. The atomic create-only winner for the target is the only instance allowed to run overwrite/delete/bypass negative mutation tests that day; concurrent losers and later probes read only the recorded result. Each outcome is exactly `DENIED_AS_EXPECTED`, `ALLOWED`, or `INDETERMINATE`. Only an explicit authorization denial is the first state; network failure, timeout, unknown error, or a winner that never completes the result is `INDETERMINATE` and fails closed. A result is valid only for the same policy fingerprint and UTC date and expires at the next UTC midnight. Target and result are retained under policy and lifecycle can remove them only after retain-until; there are at most two small objects per fingerprint per day.

## 8. Error Handling

- Invalid configuration: startup configuration validation fails with the exact property and reason.
- Invalid Endpoint: reject a relative URI, non-HTTP(S), empty host, user-info, query, or fragment. The error contains only the property and rule, never the URI.
- Unreachable, timed-out, or unauthorized Provider: state becomes `EXTERNAL_UNVERIFIED`; invalidate the current report, preserve the real error, and do not switch Provider.
- A mutation test records `DENIED_AS_EXPECTED` only for an explicit denial; success records `ALLOWED`, and network, timeout, or unclassified failure records `INDETERMINATE`. The latter two fail closed.
- Upload failure: do not create a receipt and do not delete the staging source.
- Read-back digest mismatch: fail archival and retain expected/actual digests; never rewrite the expected value.
- Missing exact version, version shadow, delete marker, or concurrent replacement: never continue by reading latest; preserve the acquired reference and fail closed.
- Payload or receipt object protection, effective retention, actual mode, or runtime-identity restrictions cannot be proven: uploading a file is insufficient for `EXTERNAL_VERIFIED` or a successful receipt.
- Configuration or Profile change, or probe, upload, read-back, or receipt failure: discard the current report immediately; the next readiness evaluation or archive command verifies again.

## 9. Security Constraints

- Configuration objects and diagnostics reject or redact credentials, tokens, and presigned URLs.
- Object prefixes prohibit absolute paths, `..`, and non-normalized separators to prevent out-of-scope writes.
- `FILESYSTEM_STAGING` uses only an explicit staging root and cannot claim WORM or corporate retention.
- The S3 Bucket prohibits public access and limits permissions to the designated prefix; production prefers short-lived identity.
- Runtime identity has no effective permission to overwrite, delete, or bypass retention. Negative authorization checks use only a dedicated small control object under the capability-probe prefix and never run destructive tests against Evidence objects.
- Public APIs accept no Capability Report, `ArchiveAuthorization`, or Adapter. Architecture tests enforce module-internal construction and dependency direction, prohibiting a bypass call chain or second state source.
- Every S3 read, Head, protection check, and receipt reference is bound to `versionId`; evidence never relies only on the current bucket/key version.
- An Archive Receipt records only stable locators, digests, policy fingerprint, check time, and actual immutability control, never secrets or temporary Bearer URLs.

## 10. Test Strategy

At minimum, cover:

1. Default configuration loads as `PILOT`, all target-control booleans are `true`, and Provider is `NONE`.
2. `PILOT` plus `NONE` starts with Capability `UNCONFIGURED`, and archival produces no receipt.
3. `FILESYSTEM_STAGING` can write and recompute digests but cannot produce long-term archival `PASS`.
4. `COMPANY` with `archive.enabled=false` is always NOT_READY and rejects archival, while Provider state remains truthfully probe-derived.
5. `COMPANY` archive readiness is READY only with `archive.enabled=true` and Capability `EXTERNAL_VERIFIED`; liveness and all other readiness checks remain independent.
6. Endpoint accepts only absolute HTTP(S) without user-info/query/fragment; an invalid-input error never echoes the original URI.
7. `probe-timeout` defaults to `PT5S` and must be positive; `operation-timeout` defaults to `PT30S`, must be positive, and must be at least the former; every external-request timeout fails closed.
8. Filesystem staging promises no cancellable I/O timeout; copy/digest/receipt failure cleans partial files, preserves source and committed objects, and permits safe retry.
9. Consecutive readiness evaluations and archive commands each create a new probe. A report fingerprint is stable for the same normalized snapshot, changes with any field, and an old report cannot be reused as authorization.
10. The public boundary accepts only a command. A forged report/authorization has no callable entry point, only the trusted facade can call the internal Adapter, and architecture tests block cross-package bypass dependencies.
11. S3 upload reads back by exact `versionId` and recomputes SHA-256. Version shadow, delete marker, concurrent replacement, or any operation failure never degrades to reading latest.
12. Concurrent probes for one fingerprint/UTC day have one create-only winner that runs the mutation test once; other probes read the result. Only `DENIED_AS_EXPECTED` passes; `ALLOWED` and `INDETERMINATE` fail closed, and the next day forces new daily objects.
13. Payload and receipt both pass HeadObject-style actual mode and retain-until checks by exact version, effective retention meets policy, the receipt records the payload ref, and independent `ArchiveReceiptReference` records receipt version/digest.
14. Logs, errors, and receipts contain no credentials, tokens, or presigned URLs.
15. An acceptance record can mark Evidence retention `PASS` only from a successful `ArchiveReceiptReference`.

## 11. Migration and Rollback

The initial default is `PILOT` plus `NONE`. During the project, `FILESYSTEM_STAGING` may demonstrate transfer and digest flow but does not change acceptance facts. After corporate resources are available, configure `S3_COMPATIBLE`, verify exact versions and actual protection for daily target/result, payload, and receipt under the current policy fingerprint, and then switch to `COMPANY`. Inventory records key, versionId, and digest together; cutover never validates only the latest key.

Rollback changes the Profile from `COMPANY` to `PILOT` only to restore non-production development. It does not delete external or control objects, overwrite receipts, reduce retention, use a bypass identity, or rewrite a failed corporate check as successful. Any configuration rollback creates a new policy fingerprint and forces a new probe. Lifecycle can clean daily control objects only after their retain-until; a failed winner without a result remains `INDETERMINATE` until the next day. Provider migration continues to follow the version-aware inventory, digest verification, and source-preserving rules in `TDR-004`.

## 12. Current M1 Decision

This design does not automatically modify `M1-OWNER-GATE-001`. The current local ZIP files and transfer manifest are staging only, and the existing `CONDITIONAL` decision remains effective. If the Owner moves corporate immutable archival to a production-rollout residual risk, that change requires a new explicit Owner instruction and acceptance-record transition; it cannot be derived from Profile configuration.

## 13. Implementation and Technology Decision

The later implementation plan must first add `TDR-011` to record the Profile, derived enforcement, Capability Report, failure recovery, testing, deployment, and corporate migration decisions. The implementation reuses one internal Archive Port: `FILESYSTEM_STAGING` and `S3_COMPATIBLE` are Adapters only. Public calls go only through `ArchiveEvidence` and the same evaluator and must not create a second Capability, acceptance path, or Quality Engine. Version-aware object refs and the independent receipt reference are deployment-evidence implementation details and do not change the Core Evidence Entity.

## 14. Design Acceptance Criteria

- Default project configuration runs without corporate resources.
- Every target-control boolean defaults to `true`.
- Actual external capability always comes from verification and cannot be fabricated by boolean configuration.
- Pilot staging and corporate long-term archival have distinct states and acceptance semantics.
- Company is READY only when archival is enabled with fresh `EXTERNAL_VERIFIED` Capability; otherwise readiness and archive operations fail closed while liveness and all other readiness checks remain independent.
- A Capability Report has a deterministic policy fingerprint and single-use semantics, and every external Provider call uses a valid bounded timeout.
- A public caller cannot construct or submit archive authorization. The internal Adapter is reachable only through one trusted facade, with no second Capability state source.
- Daily control-object concurrency, result states, validity, and lifecycle are deterministic and fail closed; garbage is bounded to two small objects per policy fingerprint per day.
- Exact payload and receipt versions, actual object protection, effective retention, runtime-identity restrictions, and actual mode are reviewable. An independent receipt reference avoids a self-hash cycle, and a bucket flag alone cannot produce immutability `PASS`.
- Filesystem staging uses atomic partial cleanup and retry recovery without fabricating cancellable local-I/O timeouts.
- The design does not change the V0.1 frozen architecture or the long-term storage direction in `TDR-004`.
- Chinese and English specifications have paired semantics, and every non-Markdown file remains byte-identical.
