# TDR-022 — Minimal synthetic M2 demonstration

- Date: 2026-09-08; status: Accepted (task 1/2 implementation scope). On 2026-09-09, after delivery of the proposal and task 1 next action, the Owner instructed the agent to execute the next step, authorizing task 1. After verified task 1 delivery, the Owner again instructed execution of the next step, authorizing task 2 implementation, not Owner acceptance.
- Proposal commits referenced by authorization: Chinese b859e4981270fbfdd27eb1c3e67fec2389b54604; English 7d5d50f04c941ae42ca4b0fb5e82a104ffc2df2b.
- Handoff commits bound to task 2 authorization: Chinese 77b8a841322779d8db48f493d2f88529c8e2165b; English f22243ceac1d20fdf7589851ff2a27f8bf01bf5d.
- Baseline: Chinese b7dd99a61cdb7e6434e0b5e0def9968e18a11fc0; English 8e1b4321285cd183b9fb3499af68952869db870a.
- Basis: [stage priority](../reviews/2026-09-08-demonstrable-product-priority.md), [P1 gaps](../reviews/2026-09-08-demonstrable-product-gap-inventory.md), [accepted M1](../../governance/acceptance/records/2026-09-08-tdr-021-m1-demo-review-001.md).

## Goal and choice

Extend the accepted M1 demonstration with FULL Sync for two synthetic Issues, an Issue Snapshot, Build Facts, asynchronous Traceability and historical queries. Explain why one Issue is Included and the other has a missing edge; all results remain Verified=false. This demonstrates the existing mechanical flow, not Quality Engine PASS/BLOCK or real build/vehicle testing.

| Option | Trade-off |
|---|---|
| Extend the isolated demo; synthetic inputs traverse real HTTP, application transactions and Workers | Selected; reuse database, identity, payload verification and existing endpoints, leaving business results to existing modules |
| Use real Jira/GitHub Build context | Requires external input and authorization and affects local replay; unnecessary now |
| Write Snapshots/Edges directly or present test-seeder results | Bypasses the flow this demonstration must show; rejected |

Do not modify frozen V0.1 semantics, Schema, Migrations, production APIs, permission rules, canonicalization or the Quality Engine. Add no service, software installation, Company/AWS resource, archive operation or frontend. Keep project materials in Git; do not promise administrator-proof storage or detection of arbitrary field tampering.

## Inputs and authority

Use the existing FIXTURE source type, FixtureIssueSourceAdapter, DefaultIssueSourceRuntimeRegistry and Mapping Profile codec. Add a factory in the demo source set with adapterId=m2-demo-fixture, adapterVersion=m2-demo-fixture/v1, schema=jira-mapping-profile/v1, FULL and filterReference=all-relevant-issues/v1. Explicitly inject its descriptor registry only in the demo process; leave the production registry unchanged. The factory uses the actual compiled mappingVersion and generates one terminal page containing DEMO-1 and DEMO-2; observedAt comes from the current run, not an expired static timestamp.

Bootstrap only the current Project, Principals, assignments and FIXTURE issue_source configuration. Unactivated adapter/mapping markers must not become eligible sync input; activate actual versions through mapping-profiles:activate before Sync. Do not prewrite Issue Revisions, Observations, Sync state, Snapshots, Commits, Builds, Edges or conclusions.

Build HTTP requests retain v2 and provider=GITHUB_ACTIONS. The existing canonicalizer also restricts workflow/proof fields to GitHub-shaped values. Preserve that contract using explicitly synthetic repository=vsrqg-synthetic/demo, pipeline=synthetic-m2, buildId=1/2, attempt=1 and 40-character synthetic revisions of repeated a/b respectively. workflowReference is vsrqg-synthetic/demo/.github/workflows/demo.yml@synthetic; proofReference is https://github.com/vsrqg-synthetic/demo/actions/runs/1/attempts/1 or its build 2 counterpart. These are format-compatible sample strings, not valid build locators. Never access them or publish them as clickable evidence, and do not claim the repository does not exist.

Explicitly inject M2DemoProvenanceValidator through BuildProvenanceValidatorPort only in the demo launcher. Match the two constant tuples above, the corresponding single sourceIssueId and current sample's measured Artifact SHA-256, and compare submitted/recomputed proofDigest. Return VALID/LOW, validatorVersion=m2-demo-fixture-provenance/v1, reasonCode=SYNTHETIC_FIXTURE_MATCHED only for matching synthetic input, not external proof validity. Otherwise return INVALID/LOW and SYNTHETIC_FIXTURE_MISMATCH. Do not use an always-successful validator; the ordinary Backend retains GithubActionsBuildProvenanceValidator. Preserve application checks for Snapshot membership, Artifacts, SERVICE assignments, transactions and conflicts.

## Isolation and reuse

Retain TDR-021's demo source set, loopback binding, dedicated vsrqg_demo database, existing Compose project/volume, in-memory RSA JWT, actual JwtDecoder/RBAC and explicit external demo password. Select M2 explicitly with run-m1.ps1 -IncludeM2; preserve the default command and six M1 scenarios without duplicating Compose lifecycle logic. M2 first completes M1 in the same run and reuses its Release, Locked Manifest and payload digest.

Add ENGINEER and SERVICE demo identities. Manager configures Mapping/Sync/Snapshot, Engineer starts Traceability, and Service carries principal_type=SERVICE, project and traceability:ingest with a matching project assignment. Manager does not gain TRACEABILITY_VERIFY merely from a scope; Permission.allowedRoles stays unchanged. Never output Tokens or raw identities.

Explicitly enable Issue Sync Worker, Issue Snapshot, Build ingestion and Traceability verification/Worker in the M2 process. Ordinary M1 keeps Workers disabled and production defaults stay unchanged. Use the existing two Workers' scheduling, not another job-processing loop. Poll HTTP every 250 ms for at most 30 seconds, with a 5-second per-request timeout. Failed states, timeouts and exceptions cause nonzero exit. Existing Workers may consume residual jobs in a retained volume; explain the dedicated demo database's purpose beforehand. Acceptance binds only the current runId and never counts other jobs as success.

## Flow and expectations

| Order | Actual entry point | Assertion |
|---|---|---|
| 1 | Same-run M1 HTTP flow | Six M1 scenarios pass; obtain Locked Manifest and measured payload digest |
| 2 | POST /api/v1/issue-sources/{sourceId}/mapping-profiles:activate | 201; codec/server determine versions |
| 3 | POST /api/v1/issue-sources/{sourceId}/sync; GET /api/v1/issue-sync-runs/{syncRunId} | 202 then SUCCEEDED, FULL, two Issues |
| 4 | POST /api/v1/releases/{releaseId}/issue-snapshots, body only sourceId | 201, selectedCount=2 |
| 5 | POST /api/v1/traceability/facts:ingest; Build 1 includes only DEMO-1 | 200; ingestion creates three Edge types; ARTIFACT_RELEASE comes only from Locked Manifest |
| 6 | POST /api/v1/releases/{releaseId}/traceability:verify, body sourceId; poll returned statusUrl | 202 then SUCCEEDED, obtain Snapshot A |
| 7 | GET /api/v1/releases/{releaseId}/traceability?snapshotId={A} | DEMO-1: Fixed=true, Included=true, Verified=false, four-edge path, TEST_RESULT_EVIDENCE_MISSING; DEMO-2: false/false/false, empty path, ISSUE_COMMIT_MISSING |
| 8 | Ingest Build 2 (DEMO-2) with a new key; create another Run for Snapshot B | Both Issues Included=true, both Verified=false, still missing test Evidence |
| 9 | GET Snapshot A again and query latest | A's entire response bytes and contentDigest are unchanged; latest points to B |

Build 2 uses a different Build Attempt authority; never modify Build 1 or “repair” history by changing an existing request. Replay the first ingestion and verify with the same keys and require identical responses. Reject USER ingestion even with the scope. Reads do not change state. Fixed, Included and Gap come entirely from HTTP responses; the demo only asserts and presents them without recomputing the graph.

## Output and validation

Retain M1 summary.json and manifest.json; add m2-summary.json in the same directory. Allow only classification=SYNTHETIC_DEMO, proofKind=SYNTHETIC_FIXTURE, codeCommit, workingTreeDirty, runId, observed HTTP statuses, scenario statuses, Release/Manifest/Issue Snapshot/Run/Traceability Snapshot IDs, digests and the two Issues' Fixed/Included/Verified, typed paths and Gap codes. Exclude locators, Tokens, connection information, raw requests/responses and exception text. Final console status combines M1 and M2; either failure means FAILED. Synthetic PASS means only that scenario assertions passed.

Acceptance evidence must cover actual PostgreSQL 17.11/HTTP flow, A/B historical comparison, same-key replay, USER ingestion rejection, INVALID for wrong fixtures/digests and rejection of invalid verification input, permission/identity isolation, ordinary M1 regression and production bootJar exclusion of new demo classes. Reuse GitHub CI and Artifact upload; add no environment or separate governance Gate. Backend unit tests default to 60-second timeouts. Without local Docker, report integration tests as unexecuted and supplement with exact-commit CI. Keep non-Markdown files identical across languages; pass Pair Gate and existing contract/acceptance-record validators.

## Implementation and next step

The [implementation plan](../../superpowers/plans/2026-09-08-synthetic-m2-demonstration.md) has two tasks: isolated synthetic input integration; HTTP composition, entry point and result delivery. Self-review covers both P1 input and presentation gaps without transferring Source/Build persistence authority to the demo. Self-review is not implementation success or Owner acceptance.

Current result: the Owner approved the TDR-022 synthetic demonstration; see the [acceptance record](../../governance/acceptance/records/2026-09-09-tdr-022-m2-demo-review-001.md). Implementation Subjects remain 8d5354d / db98f89. Git status: the approval receipt and acceptance decision are independently versioned and pushed as a bilingual pair. Next action: review and update the minimum demonstration gap inventory to identify the remaining delivery scope after M1/M2 acceptance. Prerequisites: the next execution instruction; this acceptance does not authorize implementing a new milestone, frontend, or Company environment. Acceptance target: an inventory citing accepted evidence, distinguishing completed and remaining gaps, and proposing one next work package with explicit boundaries and completion criteria.
