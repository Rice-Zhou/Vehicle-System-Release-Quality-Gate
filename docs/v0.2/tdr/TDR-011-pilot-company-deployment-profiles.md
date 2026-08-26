# TDR-011 — Pilot / Company Deployment Profiles and Archive Capability

- Status: Accepted
- Date: 2026-08-26
- Approval: Project Owner approval of the written Pilot/Company design
- Approval Date: 2026-08-26
- Authorization Receipt Locators: Chinese plan commit `7cb0adcc20491f3d18bbc53144f2166101942dd4` and paired English plan commit `7f28cb4f50aed94c4c320b4a83022c4591550610`
- Authorization Receipt Statement: these Git commits are immutable locators recording work performed under the direct Owner instruction; they are unsigned and do not authorize merge, tag, or release
- Accepted Residual Risk: `PILOT` has no verified company archive and cannot claim long-term `PASS`
- Scope: V0.2 deployment readiness, archive operations, and acceptance-evidence interpretation
- Related Decisions: [TDR-004](TDR-004-s3-compatible-evidence-storage.md), [TDR-010](TDR-010-containerized-vm-deployment.md)

## Problem and Requirements

The project phase cannot assume that company object storage, identity, or operational resources already exist, while a company deployment must prove that encryption, private access, versioning, retention, and immutability controls are active. A single boolean configuration cannot distinguish target intent from external fact and can misreport an unconfigured or local-only staging state as successful long-term archival.

This decision defines how one business implementation supports `PILOT` and `COMPANY` while preserving truthful capability reporting, deterministic acceptance semantics, and fail-closed behavior in the company environment. A Profile affects only deployment readiness, archive operations, and acceptance-evidence interpretation. It does not change the V0.1 Core Contract, frozen architecture, Release-centric information flow, or Quality Result business semantics.

## Decision and Rationale

Adopt the `PILOT` and `COMPANY` deployment Profiles and connect `NONE`, `FILESYSTEM_STAGING`, and `S3_COMPATIBLE` Adapters through one Archive Port. Both Profiles share the configuration contract, capability evaluation, archive command, receipt, and acceptance path; there is no parallel business implementation.

The six target-control booleans for the archive workflow, digest verification, encryption, private access, retention policy, and immutability default to `true`. These values express requirements only and cannot express actual external state. A read-only Capability can come only from an active probe; callers and configuration files cannot directly supply `UNCONFIGURED`, `LOCAL_PILOT`, `EXTERNAL_UNVERIFIED`, or `EXTERNAL_VERIFIED`.

This MVP uses single-use, fresh-probe semantics. Every readiness evaluation probes again, and every archive command probes again immediately before execution. A report is bound to the current Profile, Provider, policy and configuration snapshot, and `checkedAt`; it answers only that evaluation and cannot be cached or reused as later authorization. Any configuration or Profile change, and any probe, upload, read-back, or receipt failure, invalidates the report. Every Provider call uses bounded connection and read timeouts; a timeout is a probe or operation failure and never extends an old report's lifetime.

Every S3 probe must also obtain Provider-attested `RuntimeIdentityRef(provider, principalFingerprint)`. `principalFingerprint` is a 64-character lowercase SHA-256 of the normalized actual principal and cannot be supplied by configuration or a caller. AWS uses STS `GetCallerIdentity` with the same `DefaultCredentialsProvider`. A custom S3-compatible endpoint requires an approved equivalent attestor from trusted wiring. If identity cannot be proven, Capability remains `EXTERNAL_UNVERIFIED`, and `COMPANY` readiness plus archival fail closed. Raw ARN, account, subject, user ID, or session name participates in normalization and hashing only in attestor memory and is never persisted, logged, or placed in health, receipt, or Evidence.

```text
Profile + Policy
    -> Provider Probe
    -> RuntimeIdentityRef
    -> Capability Report
    -> Readiness and Archive Operation
    -> Archive Receipt
```

`FILESYSTEM_STAGING` proves only that an explicit local root is writable and digests can be recomputed, so it can produce only `LOCAL_PILOT` and a non-long-term receipt. It can never produce long-term archival `PASS`. Capability becomes `EXTERNAL_VERIFIED` only when `S3_COMPATIBLE` Provider-attested runtime identity, connection, write, read-back digest, encryption, private access, versioning, retention, and immutability controls all pass verification. A daily control result binds policy fingerprint, identity fingerprint, and UTC date. An identity change requires a new control winner and cannot reuse another identity's result.

Immutability `PASS` uses Provider-neutral criteria: both the payload and Archive Receipt are covered by immutable controls; effective retention is at least the policy requirement; the runtime identity cannot overwrite, delete, or bypass retention; and receipt field `immutabilityControl` records the actual lock mode or an approved equivalent control. Proving only that a bucket has Object Lock enabled is insufficient; object-level coverage, effective retention, and runtime-identity restrictions must all be verified.

`PILOT` may start when external capability is absent and truthfully report an unconfigured or degraded state, but an archive failure cannot be disguised as success. The `COMPANY` READY invariant is `archive.enabled=true` and Capability state `EXTERNAL_VERIFIED`; readiness is NOT_READY if either condition is false. In particular, with `archive.enabled=false`, readiness remains NOT_READY even if the Provider can be verified, and archive operations plus archive-dependent approval paths still fail closed. Liveness remains independent of external object storage, so an external failure does not fail the liveness probe or cause a process restart loop.

AWS SDK for Java v2 versions are managed by `software.amazon.awssdk:bom:2.54.4`, and only `software.amazon.awssdk:s3`, `software.amazon.awssdk:sts`, and `software.amazon.awssdk:url-connection-client` are selected. STS calls only `GetCallerIdentity` to produce Provider-attested runtime identity and has no storage, object-transfer, or coordination responsibility. S3 remains the sole storage service client. The complete SDK, Transfer Manager, a second object-storage client, identity registry, database, and distributed lock are excluded. Credentials use `DefaultCredentialsProvider` and the default credential chain and may come only from controlled environment injection, workload identity, or a credential profile. Git and YAML never store an access key, secret key, token, raw principal, or temporary signed address.

## Governance Relationship

This decision does not replace [TDR-004](TDR-004-s3-compatible-evidence-storage.md). That decision continues to govern S3-compatible long-term Evidence Payload Storage, content digests, inventory, and source-preserving migration. This decision governs only when that capability can be truthfully claimed and used for deployment readiness.

This decision also does not replace [TDR-010](TDR-010-containerized-vm-deployment.md). That decision continues to govern immutable containers, controlled VMs or small platforms, and externalized state. This decision only adds Profile, probe, and archive fail-closed constraints to those deployments.

This decision does not automatically change `M1-OWNER-GATE-001`. Local ZIP files, the transfer manifest, and filesystem staging are still not company-grade long-term immutable archival, and the existing `CONDITIONAL` status remains effective. Any Owner acceptance-status transition requires separate explicit authorization and an acceptance record; it cannot be derived automatically from a Profile or Capability.

## Alternatives Not Selected

- Default actual external states to `true`: this fabricates success before probing connection, permissions, and controls, violating Evidence-first, auditability, and determinism.
- Disable every control for Pilot: this separates the project path from the company path, defers security and archival integration risk until cutover, and prevents continuous validation of the target contract.
- Use separate Pilot and Company business implementations: this creates two archival, acceptance, and Quality Engine semantics and increases drift, duplicate testing, and cutover risk; Profiles and Adapters already express environmental differences.
- Self-declare runtime identity in configuration or reuse a control result across identities: this cannot prove that the current actual principal performed the negative permission test and can misrepresent an old identity's denial as a current fact. Provider attestation is required, and identity fingerprint binds the control key and result.

## V0.2 / V0.3 Impact

V0.2 adds deployment Profiles, archive policy, an active Capability Report, readiness integration, filesystem staging, an S3-compatible Adapter, Provider-attested `RuntimeIdentityRef`, and a reviewable Archive Receipt. The AWS path adds minimal STS identity attestation, while a custom S3-compatible path requires an approved equivalent attestor. Both are Adapter and non-core implementation details. They add no Core Evidence Entity, storage provider, or coordination infrastructure and do not modify Manifest authority, Traceability, or the Quality Engine.

V0.3 may use measured SLO, object volume, archive cost, or company-platform requirements to extract a controlled Evidence Gateway, add tiered or cross-region storage, expand approved identity attestors, or change the orchestration platform. The irreversible fingerprint and no-cross-identity-reuse semantics of `RuntimeIdentityRef` remain compatible. Profile, Archive Port, Capability, and receipt semantics remain compatible unless a new TDR or ADR explicitly changes them.

## Migration and Rollback

Start with `PILOT` plus `NONE`. Use `FILESYSTEM_STAGING` when transfer and digest flow must be demonstrated, but acceptance still cannot record long-term archival success. After company resources are available, configure `S3_COMPATIBLE` and Provider identity attestation, generate a source-object inventory, copy each object, compare count, size, and SHA-256, and complete read-back, control, and receipt verification under the current identity fingerprint before switching to `COMPANY`. A runtime-identity change forces a new control winner but never deletes old-identity control objects.

Migration never deletes source objects before verified cutover. After cutover, retain source-to-destination mappings, inventory, and digest evidence, and handle old objects only under an approved retention policy. Rollback can restore the prior application image and configuration. Only non-production development may switch from `COMPANY` back to `PILOT`, and rollback never deletes external objects, overwrites successful receipts, rewrites failed checks, or promotes staging to long-term archival.

## Test Matrix

| Scenario | Expected Capability / Health | Archive and Acceptance Result |
|---|---|---|
| `PILOT` + `NONE` | `UNCONFIGURED`; liveness healthy | Operation fails explicitly; no receipt and no long-term `PASS` |
| `PILOT` + `FILESYSTEM_STAGING` with a successful probe | `LOCAL_PILOT`; liveness healthy | May create a non-long-term receipt; cannot produce long-term `PASS` |
| `PILOT` + `S3_COMPATIBLE` with any failed control | `EXTERNAL_UNVERIFIED`; reported degraded | Fail closed; no successful receipt |
| `PILOT` + `S3_COMPATIBLE` with all controls successful | `EXTERNAL_VERIFIED` | Long-term archival may be claimed only after a matching read-back digest and complete receipt |
| `COMPANY` + `archive.enabled=false` | readiness NOT_READY; liveness healthy | Archive and archive-dependent approval paths fail closed even if the Provider can be verified |
| `COMPANY` + `archive.enabled=true` + not `EXTERNAL_VERIFIED` | readiness NOT_READY; liveness healthy | Archive and archive-dependent approval paths fail closed |
| `COMPANY` + `archive.enabled=true` + `EXTERNAL_VERIFIED` | readiness READY; liveness healthy | Interpret acceptance only from a real, reviewable receipt |
| `S3_COMPATIBLE` without Provider-attested identity | `EXTERNAL_UNVERIFIED`; `COMPANY` readiness NOT_READY | Do not reuse a control result; archival fails closed |
| Two runtime identities under the same policy and UTC date | Probe and create a winner separately for each identity fingerprint | Prohibit cross-identity result reuse; raw identity values enter no output or Evidence |
| Every readiness evaluation or archive command | Probe again within bounded timeouts; report is bound to the current snapshot and `checkedAt` | An old report cannot be reused as authorization |
| Configuration or Profile change | Current report is invalid immediately | Probe again with the new snapshot |
| Probe, upload, read-back digest, or receipt-write failure | Current report is invalidated and Capability is not promoted | Preserve source and uploaded objects; do not create a successful receipt |
| Payload or receipt is not fully protected, effective retention is insufficient, or runtime identity can bypass it | `EXTERNAL_UNVERIFIED` | Immutability cannot be `PASS`; the receipt cannot claim successful long-term archival |

Tests also verify the six target-control defaults, path normalization, digest mismatch, replay, existing-target content conflict, logs and errors free of credentials and raw principals, the S3 control matrix, Provider timeouts, fail-closed STS/equivalent-attestor behavior, `principalFingerprint` format, distinct winners with no cross-reuse for two identities under the same policy and day, non-reuse of a single-use report, recording of the actual lock mode or equivalent control, and that archive Capability contributes to readiness only and does not affect liveness.

## Deployment

Development and project environments start with `PILOT` and explicitly select `NONE` or `FILESYSTEM_STAGING`. A filesystem root must be a controlled absolute path and labeled as staging only. The company environment uses `S3_COMPATIBLE`. An AWS deployment enables `GetCallerIdentity` through the same default credential chain, while trusted wiring injects an approved equivalent attestor for a custom endpoint. Before switching to `COMPANY`, verify the identity claim, bucket reachability, least privilege, encryption, private access, versioning, immutable coverage of payload and receipt, effective retention, runtime-identity restrictions, write, read-back digest, and Archive Receipt. Do not proceed when identity cannot be proven.

Containers continue to follow TDR-010 externalized-configuration and no-local-persistent-state principles. Credentials and raw identity do not enter images, Git, YAML, logs, or Evidence. Archive Capability contributes to readiness only, not liveness; all other readiness checks remain. After deployment, run a fresh probe with the current configuration and current attested identity and retain the Capability Report without secret or principal values as deployment evidence, but do not reuse that report as archive authorization.

## Failure Recovery

Invalid configuration exposes the exact property and reason. When a Provider is unreachable, times out, is unauthorized, `GetCallerIdentity`/equivalent attestor fails, or a control cannot be proven, preserve the real failed checks without raw principal, remain `EXTERNAL_UNVERIFIED`, and invalidate the current report. Never switch Provider, reuse a result across identities, extend a timeout and retain old state, or silently degrade. An upload failure writes no successful receipt. A read-back digest mismatch preserves expected/actual digest and fails closed. A receipt-write failure preserves the source object and uploaded payload for reconciliation and retry.

If the payload and receipt are not both protected, effective retention is insufficient, the actual lock mode is unknown, or runtime identity can overwrite, delete, or bypass retention, immutability verification fails and the current report becomes invalid. Recovery does not reduce retention, use a bypass identity, or rewrite a bucket-level switch as object-level success; preserve the objects, repair the controls, and then verify the actual payload and receipt state.

Recovery first repairs configuration, Provider identity attestation, networking, or storage controls, then obtains a new identity fingerprint, runs a new probe with bounded timeouts, and replays an idempotent archive command. An identity change uses a new control key and winner; never reuse the old identity result. Reconcile with bucket inventory, stable locators, Archive Receipts, and SHA-256. No recovery step deletes the only copy or overwrites conflicting content.

## Re-evaluation Triggers

Re-evaluate this decision if the company prohibits the S3 API or default credential chain; a mandatory platform cannot provide the required probes or an Object Lock/WORM equivalent; measured availability, performance, capacity, or cost cannot satisfy retention policy; filesystem staging is asked to serve as long-term archival; Profile count or environmental differences can no longer be expressed safely by one Archive Port and Adapters; or V0.3 must change Capability, receipt, or Core Contract semantics. A change to the Core Contract or frozen architecture requires a separate ADR.
