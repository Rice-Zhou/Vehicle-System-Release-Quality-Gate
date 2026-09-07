# TDR-019 — Versioned Evidence Archive Work-Package Identity

- Status: Accepted; the Owner confirmed the written proposal; not implemented.
- Date: 2026-09-07
- Scope: support an independent M2.5 work package through the same narrow JVM operation defined by TDR-012.
- Basis: [TDR-012](TDR-012-evidence-archive-acceptance-operations.md), [TDR-013](TDR-013-controlled-local-file-identity.md), and the [committed preparation package](../../../ops/evidence-archive/m2-5-preparation/README.md).
- Current authorization: record approval and write the detailed plan under the [written review record](../../governance/acceptance/records/2026-09-07-tdr-019-written-review-001.md); this is not production implementation or Company archive authorization.

## Problem and Verifiable Current State

Original M2.5 implementation has APPROVE under `M2-5-OWNER-GATE-001`. Both original ZIPs are locally preserved; the preparation manifest remains local material, not an executable descriptor. Earliest online Artifact expiry is `2026-10-07T02:45:31Z`.

Code inspection found a fixed M1 ID in all three JSON Schemas, the single Kotlin descriptor parser, recovery reports and provisional/failure output, operation safety summaries, and the Node offline verifier. Runner already propagates receipt acceptanceId from the work package, but report schemaVersion remains fixed at 1. Merely changing the descriptor const into an unrestricted string would cause downstream rejection or mislabel M2.5 as M1.

SourceVerifier already checks ZIP size/SHA-256, ZIP32 structure, raw manifest digest, and two Pilot classification fields against the descriptor. It does not interpret business summaries inside ZIPs based on M1 file names. This extension reuses those checks without adding another ZIP reader, business Evidence parser, or quality acceptance authority. Preparation summary/sidecar checks remain source evidence for the fixed inputs.

## Alternatives and Recommendation

| Approach | Benefit | Cost and conclusion |
|---|---|---|
| Reuse the M1 ID or duplicate an M2.5 executor | Superficially fewer changes | Confuses historical packages or duplicates security decisions; rejected |
| Generic framework with arbitrary IDs and Artifact counts | Supports unknown future packages | Expands input, configuration, and test scope; unnecessary for two packages |
| Two explicit version/ID pairs throughout the existing execution chain | One execution path, bounded test matrix, historical compatibility | Requires coordinated parser, report, Schema, and offline-verifier changes; recommended |

Add only the known M2.5 profile, with no registry service, database, dynamic plugin, Provider implementation, or new management API. This is an operations-layer technology decision; it changes neither frozen Core Contract nor Archive facade/Port/Receipt, Capability, Manifest authority, or ADR governance.

## Input and Version Decision

| Purpose | schemaVersion | workPackageId |
|---|---:|---|
| Existing M1 descriptor and bound reports | 1 | `V0-2-EVIDENCE-ARCHIVE-001` |
| New M2.5 descriptor and bound reports | 2 | `M2-5-EVIDENCE-ARCHIVE-001` |

Accept only these exact pairs; reject unknown IDs, unknown versions, and swapped version/ID pairs. Both profiles retain two Artifacts, existing fields, bounds, prohibited-field checks, `LOCAL_PILOT_NOT_IMMUTABLE`, and `conditionBClosed=false`. Do not relax size, digest, file-name, path, duplicate-key, or identity checks for the new ID.

The planned new descriptor location is `ops/evidence-archive/m2-5-evidence-archive-001.json`, created during implementation, not now. subjectCommit and pairedSubjectCommit bind original implementation commits `3b010726941c26f0b4096cea34ea4b4dd80c5283` and `de49b2af6ddf1e5f529453e2714366064c873e15`. Copy each Artifact ID/run/commit/fileName/size/SHA-256 from the fixed preparation manifest fields, never replacing them with latest CI. Retain manifest file name `pilot-preservation-manifest.json` and raw-byte SHA-256 `c5f3b1e7ffa11a1627de70cf9b9f4853d50af5e6ad3aa40608113327fdc87300`. companyArchiveCompleted in the preparation manifest stays false.

The descriptor subjectCommit is the implementation Subject referenced by this package; artifacts.sourceCommit identifies each source commit. Preserve existing field responsibilities without adding a new equality constraint between them for M1. The raw descriptor digest still binds all fixed fields. Approved inputs remain specified by versioned files and an authorization locator; an allowlisted ID is not execution approval. Do not reformat or repack the manifest or ZIPs.

## Single Identity Chain and Failure Handling

Kotlin defines one internal version/ID profile validator near the existing parser and carries the version through Parsed/Verified work packages after parsing. Runner, RecoveryVerifier, and OperationMain consume the validated profile instead of copying allowlists into each class. Schemas constrain all three documents through a shared identity definition in the work-package Schema, with dependencies registered first by the offline loader. Node uses Schema validation and cross-document equality checks without a separate fixed-ID constant.

Both archive and recovery reports must match the descriptor version, ID, raw-byte digest, and manifest digest. Preserve existing executionId, Artifact source tuple, exact payload/receipt version, digest, protection, retention, and dual-identity checks. Runner uses the work-package ID for receipt acceptanceId, which recovery continues to verify. The offline success summary returns the verified ID, never a fixed M1 value.

Preserve the recovery entry point's ordering: write a provisional diagnostic before reading untrusted inputs. No reliable identity exists then. The new provisional uses version 2, null workPackageId, and IN_PROGRESS status; it is not a completion report and cannot pass offline acceptance. Final failure reports created before complete descriptor-parser validation use version 2 and null ID, with null executionId, descriptorSha256, pilotManifestSha256, and identity fields, empty artifacts, FAIL status, and actual errorCode and cleanup results. Never infer an ID from unvalidated JSON, file names, or command arguments, and never default to M1.

After full descriptor validation, bound reports use its profile. A subsequent archive-report mismatch still produces FAIL without borrowing the untrusted report identity. The existing safe-summary null-ID failure path remains applicable; PASS requires a verified pair and both complete results. The final recovery Schema allows null ID only for the unbound FAIL shape above, never for PASS. Provisional output remains governed by its separate stage rules, outside the success-report Schema.

## Compatibility, Migration, and Recovery

Preserve existing M1 descriptor, Pilot manifest, original ZIP, and published report bytes. New readers accept valid M1 v1 and M2.5 v2; old readers continue processing M1 v1 and reject M2.5 v2 as expected fail-closed behavior. Valid M1 bound reports generated under fixed clocks and dependencies retain existing canonical bytes. The sole explicit adjustment is that unidentifiable input no longer claims M1 and instead produces an unbound v2 failure diagnostic.

Do not bulk-upgrade historical reports, rewrite v2 as v1, or overwrite old Evidence. Complete local regressions before an operator with explicit Company authorization uses the new reader/operation for the new package. When reverting code, retain v2 Evidence and verify it with matching tools; rollback must not delete sources, overwrite through re-archiving, or downgrade failure into Pilot success.

## Affected Files and Validation Targets

| File or existing test | Proposed change and required behavioral proof |
|---|---|
| `EvidenceArchiveSourceVerifier.kt`, `EvidenceArchiveModels.kt` | Single profile validation and version propagation; accept both correct pairs, reject unknown or crossed pairs, preserve source checks |
| `EvidenceArchiveRunner.kt`, `EvidenceArchiveRunnerTest.kt` | Bind version and receipt acceptanceId; preserve M1 canonical baseline and propagate M2.5 correctly |
| `EvidenceArchiveRecoveryVerifier.kt`, `EvidenceArchiveRecoveryVerifierTest.kt` | Bound reports, unbound provisional/FAIL, exact read-back, and completion marker; reject cross-package/version/digest mismatches without mislabeling failure as M1 |
| `EvidenceArchiveOperationMain.kt`, `EvidenceArchiveOperationMainTest.kt` | Safe summaries accept validated profiles; unknown input emits safe failure with nonzero exit and no input leakage |
| `ops/evidence-archive/schemas/`, `scripts/evidence-archive/verify-evidence.mjs` | Shared identity pairs, v2 unbound FAIL restrictions, and cross-document binding; preserve prohibited-field and canonical checks |
| `scripts/tests/evidence-archive-evidence.test.mjs`, `EvidenceArchiveSourceVerifierTest.kt` | Valid flows for both profiles, cross-ID/version/digest mutations, original M1 fixture regressions, duplicate-field and malicious-source rejection |
| New descriptor, preparation package, and existing runbook | Fix original inputs and state tool versions and authorization; create no actual archive reports or new Owner decisions |

Kotlin files reside in the existing shared/adapter/archive/operations directory; tests reside in shared/archive/operations. The implementation plan must list full paths and commands based on actual test files without expanding into archive-core refactoring.

Minimum validation matrix: archive→independent verify→Node offline cross-check for M1 v1 and M2.5 v2; reject an ID or version swap in any document, a one-byte descriptor digest change with the same ID, or mismatched receipt acceptanceId. Pre-parse failures must produce neither invented identity nor a success marker; post-binding failures retain the correct identity. Use existing test Providers/fixtures without enabling Company. Retain the existing 47 offline-verifier regressions and add negative cases against implementation behavior. Run targeted backend tests with a 60-second timeout, then the affected build, existing CI, and bilingual Pair Gate.

These are future implementation validation requirements. This task inspects existing code and prepares the proposal; it does not claim v2 has passed tests. Actual archiving still requires real Provider capabilities, retention/accessOwner, independent identities, ACLs, and separate execution authorization; technical tests cannot close those conditions.

## Review and Next Execution Plan

The Owner confirmed the two explicit profiles, unbound failure diagnostics, M1 compatibility boundary, and validation matrix. The written review record locates the decision and original confirmation. This acceptance authorizes only recording the decision and writing the detailed Implementation Plan.

Current result: the TDR is accepted; production code and Schemas are unchanged. Git state: determined by the bilingual commits containing this TDR and their remote branches. Next action: write the detailed Implementation Plan. Prerequisite: planning is authorized by this confirmation; implementation, Company writes, and independent recovery each follow existing authorization boundaries. Acceptance target: concrete files, tests, commit ordering, and scope boundaries; accepting this TDR does not mean M2.5 Evidence has completed long-term archiving.

## Reassessment Conditions

Reassess if a third work package, variable Artifact counts, new report fields, Receipt/Capability/Core Contract changes, or existing consumers unable to accept this version migration are required. Frozen-semantic changes require an ADR Proposal. M2.5 creation P95 `1467/1477 ms` misses the `1000 ms` reference target, and canonical coverage excludes some non-primary-path fields; both limits remain. This proposal authorizes no merge, Tag, release, deployment, or next milestone.
