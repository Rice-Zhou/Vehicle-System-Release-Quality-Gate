# Evidence Archive Acceptance Work Package Design

## 1. Objective

Establish an independent `V0-2-EVIDENCE-ARCHIVE-001` acceptance work package for reviewing the corporate Evidence archival facts required by conditions A and B of `V0-2-PILOT-COMPANY-002`. The work package may start only as `PENDING`. It collects, verifies, and presents evidence but cannot change an existing acceptance state by itself.

The design remains feasible for a six-month spare-time project: it freezes executable boundaries and an acceptance matrix now, then reuses the existing Archive implementation for real acceptance after corporate resources become available, without introducing premature services or fabricating external capability.

## 2. Governance Boundary

- Do not modify the V0.1 Core Contract, Release-centric architecture, Manifest authority, Evidence, Traceability, Quality Engine, Adapter, Plugin, or ADR governance.
- Add no archive implementation or second Capability data source. Execution must follow `TDR-004`, `TDR-011`, and the Pilot / Company profile design.
- Do not rewrite `V0-2-PILOT-COMPANY-002` or `M1-OWNER-GATE-001`. A later explicit Owner decision applies any closure condition.
- Do not authorize merge, Tag, release, or production deployment.
- This design commit is neither an acceptance record nor proof that archival completed.

## 3. Acceptance Subject

The future record pins the original Subject Artifacts and their Pilot preservation manifest, not the commit carrying the acceptance record:

| Object | Pinned Identifier | Size | SHA-256 |
|---|---|---:|---|
| Chinese Subject Artifact | `m1-evidence-892fb23ce75e7f74a05c1b5e304fccace70ee8d3` / Artifact ID `9631253528` | `55065` bytes | `1f087ef27cfabbb2152d06fc002eb0772c2efbbb63964d6b13ec5f0d7a73ed7a` |
| English Subject Artifact | `m1-evidence-8687d49c9566030bb0829752dbe5dda45af02f4b` / Artifact ID `9631250285` | `55099` bytes | `e7602924fe67fd6eff75ebfe5d48122240639d883edc58dc164c419893d979ca` |
| Pilot preservation manifest | `pilot-preservation-manifest.json` | Reverified from the manifest during execution | `7bcb4d9df5ce0e28fe6150e0593c9824ea2533a2f7885f17d61d3ae813aa4a32` |

The future Chinese record pins `subjectCommit` to `e3576582b08c154189eb9e7f2796f39280cdb8a5` and `pairedSubjectCommit` to `6ef2cd2fb234737fad78e96cff4172ef8f92fc45`; the English record swaps them. These commits pin the Pilot preservation facts and original digests. They do not mean that Company archival completed.

The local directory is a transfer source only and never a published Evidence locator. Manifest classification `LOCAL_PILOT_NOT_IMMUTABLE` and `conditionBClosed=false` remain unchanged until new corporate archive evidence forms independently.

## 4. Record Lifecycle

```text
Approved Design
    -> Implementation Plan
    -> Corporate Resource Readiness
    -> Controlled Archive Execution
    -> Independent Verification
    -> PENDING Acceptance Record
    -> Owner Review
    -> APPROVE | REJECT | CONDITIONAL
```

1. After written-design approval, write an implementation plan first. Design approval is not execution authorization.
2. Execute archival only when a real corporate Provider, runtime identity, access owner, and retention policy are available.
3. After archival and independent verification complete, create `V0-2-EVIDENCE-ARCHIVE-001` with initial metadata exactly `status: PENDING`, `owner: PENDING`, and `decisionAt: PENDING`.
4. The Owner reviews the committed record separately. Only an explicit Owner instruction may produce a terminal or `CONDITIONAL` state commit.
5. Even an approved archive record cannot automatically transition `V0-2-PILOT-COMPANY-002` or `M1-OWNER-GATE-001`. Every transition requires independent authorization and a Git record.

## 5. Execution Data Flow

```text
Pinned Pilot ZIPs + Manifest
    -> Source Digest Verification
    -> Fresh Provider Capability Probe
    -> Provider-attested Runtime Identity
    -> Create-only Payload Archive
    -> Exact-version Read-back
    -> Immutability and Retention Verification
    -> Create-only Archive Receipt
    -> Exact-version Receipt Read-back
    -> ArchiveReceiptReference
    -> Independent Recovery Test
    -> Acceptance Evidence
```

Stop immediately on a source-digest mismatch and never update the expected digest. Execution uses the single trusted facade and a fresh authorization. Both payload and receipt are verified at the exact `versionId`. Only a successful `ArchiveReceiptReference`, independent recovery result, and actual-control proof may support a long-term archival conclusion.

## 6. Acceptance Matrix

| Check | Requirement Before Creating the `PENDING` Record | Acceptable Evidence | When Missing or Failed |
|---|---|---|---|
| Pinned input integrity | Both ZIP files match the pinned sizes and digests, and the manifest digest matches | Local recomputation report and source-manifest digest | `FAIL`; stop transfer |
| Provider configuration | Provider, endpoint, bucket, region, and prefix are verifiable and configuration contains no credentials | Secret-free configuration fingerprint and check report | `UNKNOWN` or `FAIL` |
| Runtime identity | Provider attests the actual principal and binds `RuntimeIdentityRef` | Attestation summary and `principalFingerprint` | `UNKNOWN`; fail closed |
| Private access and transport encryption | No public read; transport and at-rest encryption meet policy | Capability Report and control checks | `FAIL` |
| Exact-version archive | Both payload and receipt have stable locator, `versionId`, size, and SHA-256 | Both `StoredObjectRef` values and read-back report | `FAIL` |
| Immutability and retention | Actual object mode, retain-until, and runtime-identity restrictions meet policy | Head-style control proof and negative authorization-test result | `UNKNOWN` or `FAIL` |
| Access ownership | Access responsibility and review path are explicit | `accessOwner` and controlled access record | `UNKNOWN` |
| Recoverability | Download the exact version to an independent temporary location and recompute the original digest | Recovery log, digest, and UTC time | `FAIL` |
| Archive Receipt | Receipt content matches fresh Capability and object references for this execution | `ArchiveReceiptReference` | `UNKNOWN`; never claim completion |
| Bilingual and repository governance | Paired semantics, byte-identical non-Markdown files, and passing validator | Pair Gate, validator, and CI Run | `FAIL` |
| Owner decision | Independent Owner authorization has not happened | `N/A` | Remain `PENDING` |

An ordinary technical check cannot use `PENDING` to hide a missing fact. Record it as `PASS`, `FAIL`, or `UNKNOWN` according to evidence. Creating the record does not require every check to be `PASS`, but every non-`PASS` item must explicitly prevent any statement that the condition is closed.

## 7. Minimum Evidence Fields

Every external Evidence item contains at least:

- acceptance ID, original Artifact ID, source commit, and paired commit;
- stable locator, bucket, key, exact `versionId`, size, and SHA-256;
- Provider, `policyFingerprint`, `capabilityCheckedAt`, archivedAt, and verifiedAt;
- Provider and `principalFingerprint` from `RuntimeIdentityRef`, never a raw principal identifier;
- actual immutability mode, retain-until, retention policy, and `accessOwner`;
- verifier, recovery-test result, Availability, and retention horizon;
- Owner authorization locator, fixed as `UNKNOWN` before a decision.

Never record credentials, tokens, presigned URLs, endpoint user-info/query, raw ARN/account/subject/user ID/session name, or a local absolute path. A temporary Bearer URL is not a stable locator.

## 8. Failure and Recovery

- Missing or unreadable source ZIP, or digest mismatch: stop, preserve the original file and error evidence, and do not upload.
- Unproven Capability, identity, permission, encryption, retention, or immutability: result is `UNKNOWN` or `FAIL`; never degrade to Pilot success.
- Partial upload success: retain committed object references, clean only safely identifiable partials, never overwrite or delete the source, and never fabricate a receipt.
- Read-back or digest failure: record failure at the exact version and never continue by reading latest.
- Receipt upload or read-back failure: archival is incomplete; do not create a successful `ArchiveReceiptReference`.
- Recovery-test failure: preserve the failure log and object references and keep the record `PENDING`; a retry produces a new execution ID and Evidence.
- Network or timeout error: never classify it as expected denial or success; retain `INDETERMINATE` semantics and fail closed.
- Suspected credential exposure: stop immediately and revoke and replace the credential through the security process; the repository records only secret-free remediation proof.

No failure may be closed by changing a pinned SHA-256, weakening policy, shortening retention, or switching to `FILESYSTEM_STAGING`.

## 9. Roles and Prerequisites

| Role | Responsibility |
|---|---|
| Project Owner | Defines Acceptance and makes the independent final decision |
| Release Engineer | Pins source inputs, runs archival, and creates the candidate record |
| Platform | Supplies the controlled Provider, network, versioning, and Object Lock / equivalent capability |
| Security | Reviews runtime identity, least privilege, private access, encryption, and retention control |
| Independent Verifier | Performs exact-version read-back and recovery verification without substituting the uploader's conclusion |

Execution prerequisites are: controlled Provider configuration, repository-external credentials or Workload Identity, explicit `accessOwner`, an approved retention policy, an available recovery-test location, and an environment that will not write secrets to logs. If any prerequisite is absent, keep the work package awaiting execution.

## 10. Tests and Verification

The written-design commit runs at least:

1. Markdown bilingual Pair Gate and non-Markdown byte-equality checks.
2. Han-character check on the English branch.
3. High-confidence credential / token / presigned URL pattern scan.
4. `git diff --check` and design-placeholder scan.
5. Affected Acceptance validator and complete M1 documentation Gate.

Future execution and record verification cover at least:

1. Actual size and SHA-256 for both source ZIP files.
2. Fresh Capability, Provider-attested identity, and policy-fingerprint binding.
3. Create-only behavior, exact-version read-back, digest, and protection state for payload / receipt.
4. Actual retention not shorter than policy and inability of runtime identity to overwrite/delete/bypass.
5. Independent recovery result matches the original digest.
6. Acceptance record metadata, status enum, Decision History, and Evidence locators are reviewable.
7. Chinese and English pinned-subject cross-references are correct, with passing CI and Pair Gate.

## 11. Deployment, Migration, and Cost Boundary

This work package deploys no new database, message queue, Kubernetes, or standalone archival service. It reuses the current application, Archive Adapter, corporate object storage, and GitHub Actions. Additions are limited to controlled execution parameters, Evidence output, and the acceptance record.

Pilot-to-Company migration is a source-preserving copy: verify source digest, create-only upload, read back and verify the exact version, then create the receipt. Successful archival does not automatically delete the Pilot source. Cleanup requires an independent retention policy and authorization.

On Provider failure, retain the source and committed versions and retry after repairing the same Provider. A Provider change produces a new locator, version, digest, and receipt. Never overwrite old Evidence or rewrite a prior failure as success.

## 12. State Transition and Closure Rules

`V0-2-EVIDENCE-ARCHIVE-001` may transition from `PENDING` only to `APPROVE`, `REJECT`, or `CONDITIONAL` as allowed by acceptance governance. Machine checks have no authority to change metadata status.

The record may recommend `APPROVE` to the Owner only when all of the following exist:

- both original Artifacts have successful and reviewable `ArchiveReceiptReference` values;
- exact versions, digests, private access, encryption, immutability, and retention for payload and receipt are all `PASS`;
- Provider-attested identity, `accessOwner`, and retention policy are reviewable;
- independent recovery test is `PASS`;
- bilingual Pair Gate, Acceptance validator, CI, and security scan are `PASS`;
- no untreated `FAIL` or `UNKNOWN` remains.

An `APPROVE` decision on this record proves only that this work package passed archival acceptance. Closing conditions A/B of `V0-2-PILOT-COMPANY-002` or updating `M1-OWNER-GATE-001` requires a separate explicit Owner decision in each corresponding record.

## 13. Design Acceptance Criteria

- The work package cross-references existing conditional records while keeping every status independent.
- Pinned inputs, digests, owners, deadline risk, and Evidence fields are explicit.
- Pilot local preservation cannot be interpreted as long-term immutable archival.
- Missing external resources remain truthful `UNKNOWN`, allowing project design and implementation to continue without fabricating Company readiness.
- Failure paths preserve source, preserve real errors, and fail closed.
- Create the acceptance record only after real execution and independent verification; initial state can only be `PENDING`.
- Every Owner state transition, merge, Tag, release, and production deployment requires independent authorization.
- Implementation reuses the existing Archive architecture, remaining feasible in six months of spare time while providing a corporate-grade audit boundary.
