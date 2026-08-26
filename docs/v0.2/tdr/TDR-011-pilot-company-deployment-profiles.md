# TDR-011 — Pilot / Company Deployment Profiles and Archive Capability

- Status: Accepted
- Date: 2026-08-26
- Scope: V0.2 deployment readiness, archive operations, and acceptance-evidence interpretation
- Related Decisions: [TDR-004](TDR-004-s3-compatible-evidence-storage.md), [TDR-010](TDR-010-containerized-vm-deployment.md)

## Problem and Requirements

The project phase cannot assume that company object storage, identity, or operational resources already exist, while a company deployment must prove that encryption, private access, versioning, retention, and immutability controls are active. A single boolean configuration cannot distinguish target intent from external fact and can misreport an unconfigured or local-only staging state as successful long-term archival.

This decision defines how one business implementation supports `PILOT` and `COMPANY` while preserving truthful capability reporting, deterministic acceptance semantics, and fail-closed behavior in the company environment. A Profile affects only deployment readiness, archive operations, and acceptance-evidence interpretation. It does not change the V0.1 Core Contract, frozen architecture, Release-centric information flow, or Quality Result business semantics.

## Decision and Rationale

Adopt the `PILOT` and `COMPANY` deployment Profiles and connect `NONE`, `FILESYSTEM_STAGING`, and `S3_COMPATIBLE` Adapters through one Archive Port. Both Profiles share the configuration contract, capability evaluation, archive command, receipt, and acceptance path; there is no parallel business implementation.

The six target-control booleans for the archive workflow, digest verification, encryption, private access, retention policy, and immutability default to `true`. These values express requirements only and cannot express actual external state. A read-only Capability must come from an active probe before every archive operation; callers and configuration files cannot directly supply `UNCONFIGURED`, `LOCAL_PILOT`, `EXTERNAL_UNVERIFIED`, or `EXTERNAL_VERIFIED`.

```text
Profile + Policy
    -> Provider Probe
    -> Capability Report
    -> Readiness and Archive Operation
    -> Archive Receipt
```

`FILESYSTEM_STAGING` proves only that an explicit local root is writable and digests can be recomputed, so it can produce only `LOCAL_PILOT` and a non-long-term receipt. It can never produce long-term archival `PASS`. Capability becomes `EXTERNAL_VERIFIED` only when `S3_COMPATIBLE` connection, write, read-back digest, encryption, private access, versioning, retention, and immutability controls all pass verification.

`PILOT` may start when external capability is absent and truthfully report an unconfigured or degraded state, but an archive failure cannot be disguised as success. When `COMPANY` has not reached `EXTERNAL_VERIFIED`, readiness is NOT_READY and archive operations plus archive-dependent approval paths fail closed. Liveness remains independent of external object storage, so an external failure does not fail the liveness probe or cause a process restart loop. Changing the Profile requires a fresh probe and never reuses old Capability.

AWS SDK for Java v2 versions are managed by `software.amazon.awssdk:bom:2.54.4`, and only `software.amazon.awssdk:s3` and `software.amazon.awssdk:url-connection-client` are selected. The complete SDK, Transfer Manager, and a second object-storage client are excluded. Credentials use `DefaultCredentialsProvider` and the default credential chain and may come only from controlled environment injection, workload identity, or a credential profile. Git and YAML never store an access key, secret key, token, or temporary signed address.

## Governance Relationship

This decision does not replace [TDR-004](TDR-004-s3-compatible-evidence-storage.md). That decision continues to govern S3-compatible long-term Evidence Payload Storage, content digests, inventory, and source-preserving migration. This decision governs only when that capability can be truthfully claimed and used for deployment readiness.

This decision also does not replace [TDR-010](TDR-010-containerized-vm-deployment.md). That decision continues to govern immutable containers, controlled VMs or small platforms, and externalized state. This decision only adds Profile, probe, and archive fail-closed constraints to those deployments.

This decision does not automatically change `M1-OWNER-GATE-001`. Local ZIP files, the transfer manifest, and filesystem staging are still not company-grade long-term immutable archival, and the existing `CONDITIONAL` status remains effective. Any Owner acceptance-status transition requires separate explicit authorization and an acceptance record; it cannot be derived automatically from a Profile or Capability.

## Alternatives Not Selected

- Default actual external states to `true`: this fabricates success before probing connection, permissions, and controls, violating Evidence-first, auditability, and determinism.
- Disable every control for Pilot: this separates the project path from the company path, defers security and archival integration risk until cutover, and prevents continuous validation of the target contract.
- Use separate Pilot and Company business implementations: this creates two archival, acceptance, and Quality Engine semantics and increases drift, duplicate testing, and cutover risk; Profiles and Adapters already express environmental differences.

## V0.2 / V0.3 Impact

V0.2 adds deployment Profiles, archive policy, an active Capability Report, readiness integration, filesystem staging, an S3-compatible Adapter, and a reviewable Archive Receipt. These are Adapters and non-core implementation details; they add no Core Evidence Entity and do not modify Manifest authority, Traceability, or the Quality Engine.

V0.3 may use measured SLO, object volume, archive cost, or company-platform requirements to extract a controlled Evidence Gateway, add tiered or cross-region storage, expand identity integration, or change the orchestration platform. Profile, Archive Port, Capability, and receipt semantics remain compatible unless a new TDR or ADR explicitly changes them.

## Migration and Rollback

Start with `PILOT` plus `NONE`. Use `FILESYSTEM_STAGING` when transfer and digest flow must be demonstrated, but acceptance still cannot record long-term archival success. After company resources are available, configure `S3_COMPATIBLE`, generate a source-object inventory, copy each object, compare count, size, and SHA-256, and complete read-back, control, and receipt verification before switching to `COMPANY`.

Migration never deletes source objects before verified cutover. After cutover, retain source-to-destination mappings, inventory, and digest evidence, and handle old objects only under an approved retention policy. Rollback can restore the prior application image and configuration. Only non-production development may switch from `COMPANY` back to `PILOT`, and rollback never deletes external objects, overwrites successful receipts, rewrites failed checks, or promotes staging to long-term archival.

## Test Matrix

| Scenario | Expected Capability / Health | Archive and Acceptance Result |
|---|---|---|
| `PILOT` + `NONE` | `UNCONFIGURED`; liveness healthy | Operation fails explicitly; no receipt and no long-term `PASS` |
| `PILOT` + `FILESYSTEM_STAGING` with a successful probe | `LOCAL_PILOT`; liveness healthy | May create a non-long-term receipt; cannot produce long-term `PASS` |
| `PILOT` + `S3_COMPATIBLE` with any failed control | `EXTERNAL_UNVERIFIED`; reported degraded | Fail closed; no successful receipt |
| `PILOT` + `S3_COMPATIBLE` with all controls successful | `EXTERNAL_VERIFIED` | Long-term archival may be claimed only after a matching read-back digest and complete receipt |
| `COMPANY` + not `EXTERNAL_VERIFIED` | readiness NOT_READY; liveness healthy | Archive and archive-dependent approval paths fail closed |
| `COMPANY` + `EXTERNAL_VERIFIED` | readiness READY; liveness healthy | Interpret acceptance only from a real, reviewable receipt |
| Profile change or expired Capability | Old report is invalidated and a new probe runs | Reuse of old success is prohibited |
| Upload, read-back digest, or receipt-write failure | Capability is not promoted | Preserve source and uploaded objects; do not create a successful receipt |

Tests also verify the six target-control defaults, path normalization, digest mismatch, replay, existing-target content conflict, credential-free logs and errors, the S3 control matrix, and readiness independence from liveness.

## Deployment

Development and project environments start with `PILOT` and explicitly select `NONE` or `FILESYSTEM_STAGING`. A filesystem root must be a controlled absolute path and labeled as staging only. The company environment uses `S3_COMPATIBLE` and verifies bucket reachability, least privilege, encryption, private access, versioning, Object Lock, retention, write, read-back digest, and Archive Receipt before switching to `COMPANY`.

Containers continue to follow TDR-010 externalized-configuration and no-local-persistent-state principles. Credentials do not enter images, Git, or YAML. Readiness alone includes archive Capability, while liveness proves only that the process is alive. After deployment, run a fresh probe with the current configuration and retain the secret-free Capability Report as deployment evidence.

## Failure Recovery

Invalid configuration exposes the exact property and reason. When a Provider is unreachable, unauthorized, or unable to prove a control, preserve the real failed checks and remain `EXTERNAL_UNVERIFIED`; never switch Provider or silently degrade. An upload failure writes no successful receipt. A read-back digest mismatch preserves expected/actual digest and fails closed. A receipt-write failure preserves the source object and uploaded payload for reconciliation and retry.

Recovery first repairs configuration, identity, networking, or storage controls, then runs a new probe and replays an idempotent archive command. Reconcile with bucket inventory, stable locators, Archive Receipts, and SHA-256. No recovery step deletes the only copy or overwrites conflicting content.

## Re-evaluation Triggers

Re-evaluate this decision if the company prohibits the S3 API or default credential chain; a mandatory platform cannot provide the required probes or an Object Lock/WORM equivalent; measured availability, performance, capacity, or cost cannot satisfy retention policy; filesystem staging is asked to serve as long-term archival; Profile count or environmental differences can no longer be expressed safely by one Archive Port and Adapters; or V0.3 must change Capability, receipt, or Core Contract semantics. A change to the Core Contract or frozen architecture requires a separate ADR.
