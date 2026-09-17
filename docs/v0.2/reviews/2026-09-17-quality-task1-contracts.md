# Minimal quality evaluation Task 1: source binding and compatibility record

- Date: 2026-09-17; scope: machine contracts and contract tests, excluding quality runtime services, rule publication, migrations and device execution.
- Authorities: [implementation Task 1](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md), [design](../../superpowers/specs/2026-09-15-minimal-quality-evaluation-design.md), [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md).
- The Owner replied “execute the next step” after the explicit Task 1 handoff. This authorizes that implementation segment, not recording Owner acceptance APPROVE; preserve formal TDR status and prior acceptance records.

## Source binding inspection

| Target fact | Actual source | Current boundary and remaining work |
|---|---|---|
| sourceRef.version | Manifest revision, Issue snapshotVersion, Traceability version | Pins source revisions, not the Release optimistic-lock version; digests retain the sha256: prefix. |
| Issue source/severity | IssueSnapshotCandidate.sourceId and SnapshotObservation.sourceIssueId/severity | Source comes from the Snapshot header, not titles or raw external DTO inference. Exclude tombstones from selected Issues and bind the exact Snapshot. |
| fixed/included/verified | TraceabilitySnapshotIssueView | Preserve historical verified=false instead of changing it from Smoke PASS. |
| required | Published Rule Set requiredIssueRefs | Existing Issue Snapshots lack this field; explicit policy references do not introduce a second Issue authority. Publication and ownership checks belong to subsequent tasks. |
| confidence | Traceability HIGH/MEDIUM/LOW/UNKNOWN levels | v2 retains levels without replacing UNKNOWN with zero or inventing probabilities. |
| gaps | TraceabilitySnapshotGapView diagnosticCode, breakEntityType/breakEntityId and other source fields | Do not fabricate v1 conceptual gapType/sourceRef/targetRef content; v2 uses bindable fields and stable order, retaining original gaps. |
| Case / Attempt / Result | TestRunRepository terminal snapshot, attempts/results, Published Case association and RunCompletion | No separate Result ID or persisted final Resolution ID exists. Identify results by attemptId + resultDigest; Task 5 explicitly selects within current single-Case/Attempt constraints without fabricated identifiers or “latest successful” selection. |
| Evidence | EvidenceSession binding, metadata, state, expected and PayloadStore.verify | ResolveAttemptEvidence is a transactional execution port returning availableIds/failedRequiredTypes, not a full read-only quality interface. Add a later read port inside Evidence, reusing sole byte verification without querying through seal. |
| Crash/ANR | No Collector data in this slice | Missing/UNKNOWN cannot become empty arrays or zero; Schema cannot prove absence of incidents. |

Inspected Backend sources: IssueSnapshotModels.kt, TraceabilityVerificationRepository.kt, TestRunRepository.kt, JdbcTestRunRepository.kt, TestRunLifecycle.kt, SubmitAttemptResult.kt, ResolveAttemptEvidence.kt and Evidence AttemptEvidence.kt, retaining module directories and responsibilities. This segment delivers binding contracts/documentation only, not runtime read ports.

## Design integration corrections

Existing agent-protocol permits TIMEOUT and the server produces TIMEOUT Test Results; cancelling a Run produces Case BLOCKED, not CANCELLED. Retain TIMEOUT in the fact catalog, distinct from Run/Attempt state enums. It is an existing non-PASS terminal outcome covered by the subsequent Smoke rule's blocking intent; missing required Evidence takes Evaluation ERROR precedence without bypassing integrity checks to force BLOCK.

“Formal Resolution” in the design means selection from pinned Published Case and terminal Attempt/Result, not an existing independent Resolution entity or ID. Task 5 must record selection policy and real references without fabricating historical records. These corrections align with delivered sources without changing Core Contract or Issue Verified semantics.

Diagnostic numbers reuse the typed arrays from TDR-026 and its probe amendment: integers use `["INTEGER","1"]` and decimals use `["DECIMAL","1.23"]`, without silently turning numbers into plain strings or double values. matchedFact.value and explanation.parameters use this representation; full canonical tree encoding and resource limits remain Task 2 checks. Evaluation ERROR may retain a sealed inputSnapshot and independent ruleResults (including ERROR), but never qualityResult; failures before source inputs are pinned may contain only the failure reason.

## API and catalog compatibility

Preserve v1 Fact Catalog, its Schema and compatibility-baseline.json byte-for-byte. v2 uses separate files/version with enums and collection-local bindings; no automatic upgrade, and old rules explicitly select matching catalogs.

Quality routes previously existed only as machine declarations without Backend runtime implementation. This segment completes strict requests/responses; new required fields, a single-Run bound and concrete responses intentionally tighten those draft quality contracts, without claiming full old-request compatibility. Existing implemented Release/Manifest/Issue/Traceability/Agent/Evidence behavior remains unchanged. Baseline tooling checks only operations, permissions, idempotency and requestBody references; its PASS cannot prove all structural compatibility. Do not rewrite the baseline to hide differences.

Cross-project/Release ownership, actual byte checksums, transactional snapshots and publication review permissions remain later runtime checks. JSON Schema and static semantics establish structure and declared reference consistency, not database/device truth; they cannot mark all A1–A8 passed.

## Verification and delivery record

- RED: all initial 4 tests failed due to missing new contracts/old references; review regressions for ERROR diagnostics and exact numerics also failed before fixes.
- GREEN: both language worktrees ran node --test scripts/tests/quality-contract.test.mjs (also rerun by the full validator), 7/7 PASS; node scripts/contract-validator.mjs reported schemas=7, positive=20, negative=9, operations=36.
- Backend regression: default JUnit timeout 60 seconds; M2ApiContractTest 10 and ManifestContractTest 9 all passed, 0 skipped; Gradle BUILD SUCCESSFUL. Database integration, quality engine and device acceptance were not run.
- Git diff against the pre-implementation commit is empty for v1 Catalog/Schema and compatibility-baseline; new tests also pin v1 content hashes allowing only checkout EOL differences.
- Structural checks cover A1 references, A3 required/verified booleans, A4 ERROR without qualityResult and A5 explicit uncovered declarations; cross-project rejection, rule actions, actual Evidence corruption and runtime uncollected-data behavior remain unaccepted.
- Independent review found two P2 items (missing per-rule ERROR diagnostics and unrepresentable numeric diagnostics); fixes and positive/negative cases are versioned here, independent follow-up review closed both items with no other Task 1 blockers.
- This record is not Owner APPROVE. Final bilingual commits, Pair Gate, push and remote CI are established by delivery verification and the matching GitHub commits, without claiming remote success in advance.

## Next execution plan

Current result: Task 1 binding and compatibility boundaries are explicit; engineering results are established by subsequent verification entries and fixed commits. Git status: paired bilingual commits identified through Git history. Next action: implement Task 2 strict parsing/exact encoding after Task 1 engineering review passes. Prerequisites: passing Task 1 verification and a next implementation instruction, without changing TDR/Owner acceptance status on their behalf. Acceptance target: real rejection guards and all-type golden tests pass while catalog/rule/history interpretation remains consistent.
