# Minimal quality evaluation technical design

- Date: 2026-09-15; status: DRAFT / REVIEW_REQUIRED.
- The Owner replied “execute the next step” after the scope draft, authorizing continued design of the recommended slice; no new implementation or milestone APPROVE is registered. Scope baseline: ZH `47478f8` / EN `ea83c31`.
- Authorities: [scope and A1–A8](2026-09-15-minimal-quality-evaluation-scope-draft.md), [Engine](../../v0.2/10-quality-engine-design.md), [rule semantics](../../v0.2/11-quality-rule-specification.md), [technology decision](../../v0.2/tdr/TDR-026-minimal-quality-evaluation.md).

## 1. Structure and sole authority

Add a quality module to the existing Backend: a pure domain evaluator, application input construction/publication/evaluation/query, and JDBC/HTTP/job/YAML boundary adapters. Application read ports provide immutable versions across modules; Quality cannot modify Issues, Traceability or Test Results. Reuse PostgreSQL, authorization, Audit/Outbox, Jobs and Evidence storage without another network service or report-side evaluator.

The flow is formal same-Release references → pinned input → canonical facts → published rules → Rule Results/Quality Result → read-only report. Clients specify references only; local summaries, screenshot text and CI scenario PASS are not authoritative quality input.

## 2. Formal input binding and demo preparation

Reuse ruleSet, testRunIds and traceabilitySnapshotId in the existing request. Resolve exact Issue Snapshot and Manifest references from the Traceability Snapshot, checking Release, project, version, state and stored digests. Test Runs must be terminal and bind the same Manifest; select the Attempt referenced by each published Case's formal final Resolution, recording the selection policy version and historical references to every other Attempt. Never select “latest successful” or accept client digests to bypass failure.

The first slice accepts one explicit Run per request; reject multiple Runs with a capacity reason instead of merging conflicting outcomes for a Case. Evaluating another Run creates another Evaluation and preserves old results. Retain the current single-Case restriction.

A new demo uses formal APIs for one Release: create/lock Manifest, sync synthetic Issues, create Issue Snapshot, ingest Build/Edges tied to the actual APK checksum and labeled synthetic, create Traceability Snapshot, then run Smoke. Extract reusable preparation from existing launch code without duplicating identity, registration or Manifest validation. Normal and deterministic failure use separate new Runs; never concatenate historical M2/M3 files, modify them or fabricate associations. No device operations occur this turn.

The Evidence module provides a read-only verification port reusing its sole Payload resolution/checksum logic; do not query through an execution port that seals Sessions. For every selected Result, verify Evidence project, Run/Attempt, type, status, size and actual byte checksum. Check required rule types before evaluation even when appliesWhen is false. Quality retains verification references and outcomes without copying raw Payloads.

## 3. Fact Catalog integration (versioned proposal)

The current catalog is version 1, implemented Traceability uses HIGH/MEDIUM/LOW/UNKNOWN, Issue Snapshots lack required, and the catalog does not explicitly bind Test/Issue item aliases. Do not assume compatibility. Propose catalog version 2 while preserving version 1 files and interpretation without rewriting old digests.

| Fact | Sole source and mapping |
|---|---|
| Release / artifacts | Exact Locked Manifest version, preserving semantic array order. |
| Issue fixed/included/verified | Exact Traceability Issue Result; retain verified=false. Read severity/source from the referenced Issue Snapshot; missing entries are errors. |
| Issue required | Explicit requiredIssueRefs in published Rule Set content, using source + sourceIssueId rather than title or severity inference. Require an explicit array, which may explicitly be empty; references absent from the selected Snapshot are errors. Scope the Set to its project and publish a new version to change the list. |
| Traceability gaps | Retain original gap codes, Issues and broken-edge references; generic Smoke does not remove the M2.5 Evidence gap. |
| Traceability confidence | Version 2 uses new path traceability.minimumConfidenceLevel as a bounded STRING enum, taking the existing lowest level and preserving UNKNOWN. Never convert it to 0, 0.5 or 1. Version 1's required DECIMAL minimumConfidence is not reused in version 2; no conversion between versions. |
| Test Results | Results selected by formal Resolution, with caseId/version, attemptNo/status and references; stable catalog ordering. |
| Crash/ANR/Memory | Omit uncollected paths and display UNKNOWN, never empty arrays or zero. |

Version 2 adds typed collection-local binding: item.status in testResults where binds its status; item.required/item.verified in issues bind the same item. Reject use in other collections. Validate paths against the current collection environment without untyped reflection. Declare and test source/identity/order fields in the catalog; passing the Schema path regex alone does not authorize an unknown path.

An existing Case error is a usable fact, not an evaluator error. Proposed SMOKE_CASE_OUTCOME rule: PASS does not block; FAIL, BLOCKED, SKIPPED, ERROR and TIMEOUT produce BLOCK, retaining the original Case status in explanations. Missing selected results, nonterminal or unknown statuses produce Evaluation ERROR. REQUIRED_ISSUE_VERIFIED blocks when an Issue has required=true and verified=false, otherwise PASS. Neither rule hides behind appliesWhen; the first requires LOG/SCREENSHOT. These are concrete policy proposals for review, not rules published this turn.

Rule Sets explicitly declare selectedCaseRefs; input must cover the nonempty set, preventing vacuous all truth from producing PASS without execution. This is an input requirement for the selected demo rules, not a change to all semantics. Every selected input Case must belong to Rule Set scope; never silently filter.

Version 2 completes implementation integration without changing Core Entities, Traceability state or aggregation semantics. Review must confirm the catalog type change and ownership of required policy; if judged to affect frozen semantics, submit an ADR before implementation rather than relying on this design. Preserve the old catalog for existing contract tests; the runtime rejects evaluation requests whose catalog lacks supported binding instead of silently converting to version 2.

## 4. Publication, execution and recovery

Retain createRuleSet / publishRuleSet / requestQualityEvaluation / getQualityResults routes, permissions and idempotency. Explicitly extend Rule Set requests with catalogVersion, engineVersion, requiredIssueRefs, selectedCaseRefs and project applicability; complete machine response Schemas for Evaluation, Snapshot, Rule Result and paginated history. Deliver these through separate contract changes and compatibility tests; OpenAPI is unchanged this turn. The same idempotency key/content returns the original resource; different content conflicts.

YAML parsing produces only a bounded node tree. Before object binding, reject duplicate keys, alias/anchor/tag, multiple documents, implicit dates and unsupported scalars. Canonical AST and Fact typing plus all operator golden/matrix tests precede review/publication. Retain TDR-008 author/reviewer permission boundaries without bypassing production two-person review for demos. Implementation-plan preflight must confirm configurable node-level parser restrictions and pin the dependency version; do not write a general YAML parser.

Jobs reuse PostgreSQL claim/lease/fencing. A short request transaction saves Evaluation and Job. The worker pins immutable source references/content in a consistent-read transaction, verifies bytes through Evidence outside the transaction, then writes the input snapshot in a transaction rechecking fencing and source state. A source-state change produces ERROR rather than selecting new input. Pure evaluation runs outside transactions; commit Rule Results, Quality Result, Audit and Job terminal state atomically. A unique constraint permits one result commit per Evaluation; stale workers cannot commit.

Persistence separates rule/Rule Set versions, Evaluation/Job association, Quality Input Snapshot and Rule Results/Quality Result. Store source, canonical AST, versions, digests, review information and source references; apply existing update/delete protection patterns to published rules and sealed inputs/results. Mutable DB Job state is excluded from business result content. Commit failure rolls back and permits reclaim; exhausted retries retain an explicit error instead of a fabricated Quality Result.

Evaluation ERROR is separate from PASS/WARNING/BLOCK and yields no usable final quality action. Queries include failed Evaluations instead of listing only successes; older results remain visible for their original input rather than becoming the current evaluation. Override remains unimplemented without a fake-success endpoint.

## 5. Determinism, digests and limits

Preserve existing module digest algorithms and validate only their declared coverage; do not claim detection of arbitrary tampering in fields those digests omit. The new Quality Snapshot digest covers every canonical fact it consumes, source references/digests, selection policy and catalog/rule/Engine versions. It identifies this module's input without replacing source digest authorities.

Canonical numbers use arbitrary-precision integers and decimal fixed-point without double conversion. Separately version canonical encoding and test large integers, trailing decimal zeros, negative zero and Unicode; do not reuse an ordinary JCS number path that loses precision. Order follows the catalog. Result digests include all rule outcomes, matched facts, explanation parameters, Evidence refs and input/execution versions, excluding Evaluation ID, duration, timestamps and AI text.

Replay reads saved snapshots and exact rule/execution versions, verifies input digests and does not reselect live Issues/Runs or external Providers. A request to prove current Evidence integrity separately verifies storage; historical decision replay does not prove current Payload availability. Missing historical interpreter versions yield ERROR instead of switching to a newer engine.

Initial engineering limits: 64 KiB YAML per rule, depth 32, 4096 AST nodes; 32 rules per Set, 4 MiB canonical input, 100000 total evaluation steps; retain 20 Issues/2000 Edges and one Run/Case for the first slice. Limits are part of the Engine configuration version and violations explicitly fail. These are testable resource budgets, not Company performance promises. Rule errors are never hidden by short-circuiting; aggregation retains existing semantics.

## 6. A1–A8 implementation verification mapping

| Scope | Design deliverable and verification |
|---|---|
| A1 | Input read ports, Snapshot binding, project/Manifest/Issue version conflicts and duplicate Case rejection; include two Releases sharing one APK. |
| A2 | Two versioned YAML status tables, formal normal/FAIL results and navigation; normal Smoke may still aggregate to BLOCK because a required Issue is not Verified. |
| A3 | Reviewed requiredIssueRefs publication and exact provenance; no Verified writes; absent required references yield ERROR. |
| A4 | Missing/corrupt/state-changed input, empty selectedCaseRefs, no rules, no applicable rules and missing historical engines never PASS. |
| A5 | Missing/Empty/Null/UNKNOWN distinction, required Evidence prechecks and uncovered-scope reporting. |
| A6 | Full Operator Matrix, operand permutations, resource bounds, numeric encoding and three cold-start replays; reject misbinding version 1 and out-of-context version 2 item paths. |
| A7 | Job reclaim/late writes/transaction rollback, idempotency conflicts, publication immutability, cross-project permissions and review records; separately record replay after DB+Payload restoration. |
| A8 | Read-only presentation after formal queries, navigation to same-Release Evidence/Traceability, visible real/synthetic provenance, original failures and uncovered scope. |

Implementation order: contract integration → publication/pure evaluation → input snapshots/jobs/API → integration/report → failure/replay acceptance. Write corresponding tests before each implementation segment; backend unit tests default to a 60-second timeout. This design has no runtime acceptance conclusion, and existing Smoke approval does not transfer to this slice.

## 7. Review and next step

Task 1's [source binding record](../../v0.2/reviews/2026-09-17-quality-task1-contracts.md) clarifies existing TIMEOUT results and formal selection boundaries: no fabricated independent Result/Resolution IDs; cross-project ownership and byte verification remain later runtime responsibilities.

Review version 2 confidence paths/local binding, Rule Set ownership of required Issues, the Case action table and asynchronous snapshot commit boundaries. The subsequent [review and technical probe](../../v0.2/reviews/2026-09-15-quality-evaluation-preflight.md) verified SnakeYAML 2.5 event information and basic exact numerics; its section 3 completes this design's event guards, canonical tree and numeric expansion limits. The candidate version is selected; BOM compatibility, production rejection and full encoding golden tests remain implementation checks. Seventeen passing probe checks are not full encoding or Engine acceptance.

Current result: technical design and TDR candidates prepared. Git status: bilingual documentation commits identified through Git history. Next action: review the design and prepare an implementation plan from the review outcome. Prerequisites: acceptance of contract integration and rule policy; any frozen change needs prior ADR approval. Acceptance target: the plan covers A1–A8 and fixes parser/encoding verification and implementation/test order; this turn executes no product code, device operations or rule publication.
