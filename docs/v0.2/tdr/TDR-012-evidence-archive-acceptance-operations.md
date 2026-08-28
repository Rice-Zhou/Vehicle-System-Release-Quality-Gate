# TDR-012 — Evidence Archive Acceptance Operations

- Status: Accepted
- Date: 2026-08-27
- Decision basis: approved Evidence Archive acceptance work package design and implementation plan
- Scope: controlled operation entry point, input descriptor, and machine Evidence for `V0-2-EVIDENCE-ARCHIVE-001`
- Related decisions: [TDR-002](TDR-002-kotlin-spring-boot.md), [TDR-004](TDR-004-s3-compatible-evidence-storage.md), [TDR-009](TDR-009-oidc-and-service-identities.md), [TDR-011](TDR-011-pilot-company-deployment-profiles.md)

## Why this technology was selected

V0.2 selects `narrow JVM operation + canonical JSON Evidence + two invocations`. The archive entry point reuses the existing Kotlin/JVM Archive facade, Capability probe, Provider-attested identity, and Adapter, and wires only the narrow dependencies required for archiving. It does not start the Web management plane or establish another persistent coordination layer. The JVM path directly reuses the tested Archive contract and avoids duplicating digest, receipt, immutability, or identity decisions in another runtime.

Each work package requires two explicit and separate invocations. The first `archive` invocation uses the archive identity to verify fixed sources, perform create-only archival, and emit canonical JSON archive Evidence. The second `verify` invocation uses an independent verifier identity to recover payload and receipt by exact version and emit canonical JSON recovery Evidence. Canonical JSON makes fields, ordering, and digests deterministically recomputable and lets a later machine Gate cross-check both invocations without relying on process logs or a human retelling.

The fixed work package descriptor records only Artifact ID, run ID, commit, plain file name, size, and SHA-256. Source directory, recovery directory, and output location exist only as runtime invocation parameters. Credentials come only from the repository-external identity chain; the work package, Git, canonical JSON Evidence, and logs must not store credentials, tokens, raw principals, or temporary signed URLs.

## What problem it solves

This decision fixes the two Pilot ZIP files and preservation manifest as a secret-free, machine-verifiable input contract, and defines a reproducible operational boundary for a future real Company Provider archive. Before execution, it can reject inputs whose digest, size, commit, classification, or file name does not match the contract. After execution, an independent invocation can prove exact object versions, read-back digests, protection controls, and recovery results. A failure cannot be converted into long-term archive success.

This is an operations-layer decision. It does not change the Archive core, Archive Port, Capability semantics, Provider Adapter, Archive Receipt, or first-class Evidence, and it does not change the Release-centric architecture, Manifest authority, Traceability, or Deterministic Quality Engine. The work package is not an acceptance record, does not close any condition of `V0-2-PILOT-COMPANY-002`, and does not alter `M1-OWNER-GATE-001`.

## Why alternatives were not selected

- REST management endpoint: it would expand the remote attack surface and introduce new authorization, idempotency, lifecycle, and long-term operations contracts; a one-time controlled operation does not need a resident management API.
- AWS CLI script: it would bypass the existing Archive facade and Provider-neutral contract, duplicate digest, receipt, and control decisions, and bind the implementation to one Provider.
- Database queue table: the current scope has only two fixed inputs and a manually authorized controlled execution; it does not need a new scheduling state, migration, cleanup, or recovery coordination data source.
- New microservice: it would add deployment, identity, network, observability, and failure surfaces without providing an independent scaling benefit required at the current volume.

## Impact on V0.2

V0.2 adds a narrow JVM operations entry point with no Web or database dependency, a versioned work package schema, a fixed descriptor, and canonical JSON Evidence contracts for archive and recovery. The implementation still calls the sole Archive facade. The work package contains no local paths, Provider configuration, or credentials. Real external writes, independent recovery verification, and subsequent acceptance-record creation each still require separate explicit authorization. This decision does not itself authorize any Company write, merge, Tag, release, or production deployment.

## Impact on V0.3

If measured operational volume or an SLO proves the need, V0.3 can attach the same narrow operation to a controlled job platform or Evidence Gateway. Migration must preserve the work package version, canonical JSON, exact-version references, two invocations, independent identities, and fail-closed semantics. A queue, database, or remote API must not become a new Archive authority. A change to Archive Receipt, Capability, or the Core Contract requires separate TDR/ADR governance.

## Migration

The initial state commits only the schema and fixed work package and performs no Provider operation. After obtaining a Company Provider, approved retention policy, `accessOwner`, archive identity, independent verifier identity, and execution authorization, recompute size and SHA-256 from the current sources. Then use the `archive` invocation to write create-only and read back by exact version. Next switch to the independent verifier identity and run the `verify` invocation, recomputing the original digest and protection state in an independent recovery location.

Migration always preserves the Pilot sources and existing object versions. A retry must produce a new execution ID and Evidence. An exact object ref that was successfully written and passes strict conflict verification may be reused; only a real new write or a Provider switch produces a new locator/version. This retry behavior does not change the existing Archive key or sole Archive facade. A retry must never overwrite old Evidence, modify fixed digests, delete the only copy, or interpret `FILESYSTEM_STAGING` as Company long-term immutable archival.

## Testing

Work package schema tests use Node.js and the existing AJV dependency to compile JSON Schema 2020-12. They verify that the valid fixed descriptor passes and reject unknown fields; any undeclared path, root, or credential field; local absolute paths; malformed SHA-256; non-positive size; duplicate Artifact ID; incorrect Pilot classification; closure condition B; a count other than two Artifacts; unsafe plain file names; and commits that are not 40-character lowercase SHAs.

Later operation tests cover source size/digest mismatch, fresh Capability bound to the actual identity, create-only conflict, exact-version read-back of payload and receipt, canonical JSON stability, dual-identity separation, immutability and retention controls, independent recovery, forbidden-field scanning, and failure exit codes. Tests must target the real implementation contract and must not substitute mock call counts for behavioral proof.

## Deployment

The operation is delivered with the existing JVM application build artifact and runs as a one-shot process in a controlled operations environment. It exposes no listening port and deploys no new service, database table, or queue. The work package is loaded from read-only repository content. The controlled execution environment explicitly injects the source root, Evidence output, and independent recovery location. Provider, region, bucket, prefix, and identity policy follow the existing external configuration, and all credentials come only from the external identity chain.

Before each deployment, verify the work package schema, build version, current Provider Capability, actual runtime identity, and least privilege. Archive and verify invocations use different controlled identities and outputs. Logs record only secret-free fingerprints, stable object references, digests, and explicit failures; they do not record local absolute paths, raw principals, or temporary URLs.

## Failure recovery

If schema, fixed size, digest, commit, or Pilot manifest validation fails, stop before any external write and expose the specific error. If Capability, identity attestation, encryption, private access, versioning, retention, or immutability cannot be proven, fail closed, produce no successful Archive Receipt, and do not downgrade to Pilot success. Network and timeout errors retain failure or uncertainty semantics and cannot be treated as an expected rejection or success.

If an upload partially succeeds, preserve committed exact-version references, source files, and the actual error, correct the same Provider, and retry with a new execution ID. Do not overwrite or delete the source. If payload or receipt read-back, digest, protection state, or independent recovery fails, preserve the corresponding canonical JSON failure Evidence and prohibit acceptance-record creation. If credential exposure is suspected, stop immediately and use the external security process to revoke and replace it; the repository retains only secret-free remediation proof.

## Re-evaluation triggers

Re-evaluate only if controlled operation volume requires a scheduling platform, Company policy prohibits the JVM operation or external identity chain, the Provider cannot supply exact versions and immutable controls, or V0.3 must change existing Archive/Core Contract semantics. Successful work package execution still cannot automatically change any Owner decision; every status transition requires separate authorization.
