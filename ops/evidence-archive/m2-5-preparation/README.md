# M2.5 Evidence Archive Preparation Work Package

- Preparation ID: `M2-5-EVIDENCE-ARCHIVE-PREP-001`
- Status: preparation materials and formal descriptor fixed; local technical verification passed, while Company execution remains blocked.
- Related acceptance: `M2-5-OWNER-GATE-001`, with Owner decision `APPROVE`.
- Scope: prepare local preservation, fixed inputs, execution prerequisites, and acceptance checks for original implementation Evidence only.

## Fixed Inputs and Verification

The [preservation manifest](pilot-preservation-manifest.json) is a preparation data record, not a work-package descriptor directly accepted by the archive executor. Its SHA-256 is `c5f3b1e7ffa11a1627de70cf9b9f4853d50af5e6ad3aa40608113327fdc87300`; encoding is UTF-8 without BOM, with LF. Both branches hold identical bytes.

| Object | Original implementation Subject | Run / Artifact | ZIP size |
|---|---|---|---:|
| Chinese | `3b010726941c26f0b4096cea34ea4b4dd80c5283` | `34076975284` / `10002515016` | 1756 bytes |
| English | `de49b2af6ddf1e5f529453e2714366064c873e15` | `34077129961` / `10002554126` | 1753 bytes |

- Each ZIP SHA-256 matches live GitHub Artifact metadata. The manifest records digests, stable locators, original file names, and expiry times.
- Each ZIP contains exactly four expected files. The raw summary-byte digest matches both its sidecar and the Owner record; exactCommit matches the corresponding implementation Subject.
- Both summaries contain 12/12 PASS, 20 Issues / 2000 Edges / 3 samples, and four PASS recovery results; performance side-report P95 values match the summaries.
- The manifest records raw-byte size and SHA-256 for each ZIP member. Original ZIPs were neither repacked nor modified nor replaced with later CI Artifacts.
- Earliest online expiry is `2026-10-07T02:45:31Z`. Local ZIPs remain outside the repository, with their location provided in this task's handoff. Git contains no ZIPs, temporary download URLs, credentials, or absolute local paths.

The local copies and manifest retain `LOCAL_PILOT_NOT_IMMUTABLE`, `conditionBClosed=false`, and `companyArchiveCompleted=false`. This task did not run restored-database tests, Company Provider attestation, source-directory ACL verification, or independent archive recovery; content checks do not replace those checks.

Preparation validation: existing archive offline-verifier tests passed 47/47, and acceptance-record validation passed. Local and bilingual manifest bytes match, and both local ZIP digests match the manifest. An actual check against the existing Schema accepts the original M1 descriptor but rejects changing only its work-package ID to the proposed M2.5 ID under the const constraint, confirming the execution blocker below. These results are not Company archive acceptance results.

## Execution Blocker and Recommended Treatment

At preparation time, the [work-package Schema](../schemas/work-package.schema.json), Kotlin parser, operation summaries, and offline verifier fixed the ID to `V0-2-EVIDENCE-ARCHIVE-001`. Tasks 1 and 2 extended inputs, offline contracts, and runtime identity propagation; Task 3 created the [formal descriptor](../m2-5-evidence-archive-001.json) and completed local JVM-to-Node integration verification; independent review and CI passed. Never reuse the existing M1 ID for M2.5 or overwrite its fixed inputs.

The formal descriptor is stored separately from this directory's preservation manifest; never pass the manifest directly to `evidenceArchiveOperation` or bypass the Archive facade. New JVM test samples are marked `TEST_FIXTURE`; they are not Company archive/recovery reports for the original ZIPs or an archive acceptance record.

[TDR-019](../../../docs/v0.2/tdr/TDR-019-versioned-evidence-archive-work-package-identity.md) approved two explicit version/ID profiles, and Task 2 completed runtime identity propagation. Task 3's formal descriptor and actual JVM-to-Node local evidence are available; review and CI passed, with tool Owner acceptance pending. Preserve the single parser/validator, Archive facade, Provider attestation, create-only, exact-version, independent identities, and fail-closed behavior; technical support is not Company execution approval.

## Execution Prerequisites

| Condition | Current state | Responsible role | Closure evidence |
|---|---|---|---|
| Both source ZIP identities, sizes, digests, and summary sidecars | PASS | Implementation Owner | This manifest, original Artifacts, Owner record |
| Executor supports an independent M2.5 package without affecting M1 | Local tests/review/CI PASS; Owner pending | Implementation Owner / Project Owner | Approved technical proposal, implementation commit, regression CI |
| Provider and controlled destination, private access, encryption, versioning, Object Lock | UNKNOWN | Platform / Security | Credential-free configuration and actual capability report |
| retention policy and accessOwner | UNKNOWN | Project Owner / Release Engineer | Explicit retention duration, responsible party, approval locator |
| Repository-external uploader and independent verifier identities | UNKNOWN | Security / Independent Verifier | Provider attestation, distinct fingerprints, witness record |
| Single-writer source, report, and recovery directory permissions | UNKNOWN | Release Engineer | Actual ACL/Owner checks under the existing runbook |
| Company external-write and independent-recovery authorization | NOT_AUTHORIZED | Project Owner | Explicit original authorization for fixed objects, destination, and scope |

Provide only secret-free configuration and approval locators; credentials use the existing repository-external identity chain and must not be submitted in chat or Git. Until resources are ready, retain local copies without reducing retention, converting UNKNOWN to PASS, or enabling Company.

## Subsequent Execution and Acceptance

After technical support, resources, and explicit authorization are available, follow the [Evidence Archive runbook](../../../docs/m1/evidence-archive-runbook.md):

1. Recheck bytes, digests, identities, and permissions of both ZIPs and the manifest in a controlled source directory, and verify the fixed formal descriptor. Never replace original implementation Evidence with latest Artifacts.
2. Using the existing facade and archive identity, perform create-only uploads and fix payload/receipt locators, versionId, size, SHA-256, protection mode, and retain-until.
3. Recover exact versions under an independent identity, verify digests, actual protection, and retention, and produce the recovery report with a digest-bound zero-byte completion marker.
4. Validate reports through the existing offline cross-check authority. Only after actual execution and independent recovery create a separate archive acceptance record initially in PENDING, for an Owner decision.

Existing M2.5 APPROVE is not archive approval. Original creation P95 `1467/1477 ms` misses the `1000 ms` reference target, and canonical coverage excludes some non-primary-path fields; these limits remain. This preparation package authorizes no merge, Tag, release, deployment, Company enablement, or next milestone.

## Next Execution Plan

Current result: Task 3 implementation and local verification complete; see the [Task 3 record](../../../docs/m2/2026-09-07-evidence-archive-identity-task3.md). Git state: paired implementation commits pushed; implementation Subjects and CI are pinned in the Task 3 record, while this document's commit only supplements the delivery record. Next action: Owner review and decision on TDR-019 tool implementation acceptance. Prerequisite: an explicit Owner decision for the fixed implementation Subjects; Company still requires separate resources and execution authorization. Acceptance target: retain the Owner decision against the three task commits, APPROVE_FINAL, fixed-input/failure matrix, Pair Gate, and corresponding CI; this does not mean Company archiving is complete.
