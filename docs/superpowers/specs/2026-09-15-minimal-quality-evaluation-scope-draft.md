# Minimal quality evaluation work package: scope and acceptance draft

- Status: DRAFT / OWNER_SCOPE_PENDING.
- Date: 2026-09-15. Source: Owner authorization to prepare scope and acceptance proposals; not implementation, rule publication or milestone acceptance authorization.
- Inspection baseline: ZH `93d0e5b` / EN `61e846f`. Smoke product Subjects remain ZH `9c9f97d` / EN `b5ed45d`.

## 1. Goal and authorities

Let reviewers follow one Release from stored facts to versioned rule decisions, explanations and Evidence, then replay the same decision. The target is a minimal demonstrable vertical flow, not full M4 or vehicle release permission.

Retain the [frozen architecture](../../00-architecture-freeze.md), [Engine design](../../v0.2/10-quality-engine-design.md), [rule specification](../../v0.2/11-quality-rule-specification.md), [TDR-008](../../v0.2/tdr/TDR-008-versioned-yaml-quality-rules.md) and [MVP plan](../../v0.2/14-mvp-implementation-plan.md). This draft proposes WHAT / BOUNDARY / ACCEPTANCE for the Owner to decide without redefining those authorities.

## 2. Current state and first dependency

M1/M2 demonstrations, offline reports and bounded Smoke already exist; the [Smoke final review](../../m3/single-device-smoke-final-review.md) retains acceptance and risks. The current report generator accepts only M1/M2; main source has no Quality Evaluation/Result runtime module. OpenAPI declares quality request and query operations, but declarations are not implementation.

The Smoke integration entry creates a Release, Manifest and Run without corresponding Issue/Traceability Snapshots. Historical M2 and Smoke outputs cannot be inferred to belong to one Release from filenames, identical APKs or manual concatenation. The first deliverable must establish and validate formal input references for one Release; old runs and failures remain unchanged. Demo input stays labeled SYNTHETIC_DEMO, with real Smoke Evidence and synthetic Issue/Build provenance distinguishable.

The Fact Catalog requires Issue, Traceability and Test Result facts. A complete, explicit empty Issue Snapshot differs from a missing Snapshot; never insert empty arrays, default confidence or Verified=true to obtain PASS. Generic Smoke does not prove an Issue's verification criteria were met.

## 3. Alternatives and recommendation

| Approach | Cost and trade-off |
|---|---|
| Recommended: a minimal quality flow inside the existing Backend | Reuse the database, identity, formal Result/Evidence and versioned rules; establish same-Release input binding first. Retain one decision authority. |
| Complete all M3 collection and physical recovery first | Follow the original plan more fully, but no quality decision is visible in this work package; Crash/ANR and device power loss need additional device permission and verification. |
| Decide directly in the offline report | Easy presentation, but introduces another decision implementation and bypasses input snapshots; rejected. |

The recommendation is only a proposed sequencing decision for the Owner: allow design, then separately authorized implementation of a bounded quality slice before full M3 acceptance. It neither closes M3 gaps nor changes the original MVP completion definition.

## 4. Proposed scope

1. Select a Locked Manifest, Issue/Traceability Snapshots, terminal Test Results and complete Evidence for one Release from formal storage, producing a replayable Quality Input Snapshot. Run/Attempt selection must be explicit and traceable, without automatically picking a successful retry to obscure failures.
2. Retain the versioned Fact Catalog, strict YAML and restricted AST; rule publication preserves source, version, digest, review and permissions. Initial rules are proposed for selected Smoke Case outcomes and blocking required Issues that are not Verified; concrete YAML, applicability and action tables need Owner confirmation before publication.
3. A selected Smoke Case FAIL is proposed to map to BLOCK; PASS only means that rule does not block. Other rules can still produce BLOCK/WARNING/ERROR; Case PASS cannot obscure incomplete, missing or corrupt inputs. Distinguish Case ERROR from Evaluation ERROR and specify the complete status mapping in technical design.
4. Persist Rule Results, Quality Results, explanation parameters, fact/Evidence references and versions; query through existing quality API contracts, with reports projecting formal results instead of executing rules.
5. Replay identical Input/Rule/Engine/Canonicalization versions at least three times and compare canonical result digests; different identities and timestamps must not affect decisions.
6. Verify project isolation, evaluation/read/publication permissions, duplicate requests, transaction failures and preservation of history. Use existing local runtime and CI without adding services.

Rule semantics remain `BLOCK > WARNING > PASS`. Missing required input, rule exceptions, no applicable Gate rules or Evidence integrity errors produce Evaluation ERROR; the Release remains NOT_EVALUATED, with older results visible but not reused as current results.

## 5. Exclusions and uncollected data

This work package does not implement Crash/ANR Collectors, physical disconnection/power-loss exercises, device pools, Memory, an online rule editor, a full Dashboard, Override or Company/real Providers. Those existing full M3/M4/M5 requirements remain open and are not replaced by acceptance of this slice.

Uncollected Crash/ANR stays Missing/UNKNOWN rather than zero events. If bounded rules do not consume these optional facts, reports still expose the uncovered scope; rules requiring that Evidence must produce ERROR when it is absent. Do not use appliesWhen to hide missing required Evidence. Any PASS states only that the published rule set passed for that input, not the absence of Crash/ANR or vehicle quality compliance.

## 6. Acceptance matrix (proposed, not executed)

| ID | Scenario | Required outcome and evidence |
|---|---|---|
| A1 | Same-Release binding | Locate every input reference, version and digest; reject inputs from another Release/project without changing existing data. |
| A2 | Normal and deterministic FAIL | With complete input, normal rule outcome PASS and failure rule outcome BLOCK; aggregate all rules and navigate to Case, Attempt, logs and screenshots. |
| A3 | Issue state | A required Issue that is not Verified produces BLOCK; Smoke PASS does not change historical Verified automatically. |
| A4 | Missing and corrupt inputs | Missing Snapshot/Result/required Evidence, digest conflicts and no applicable rules cannot produce PASS; retain ERROR and reasons. |
| A5 | Uncollected facts | Distinguish uncollected Crash/ANR from explicit empty collections; ERROR when required, otherwise report uncovered scope without claiming zero events. |
| A6 | Rules and replay | Full specification Operator Matrix, operand permutations, golden cases, invalid YAML rejection and three identical result digests; testing only operators used by initial rules cannot establish Engine acceptance. |
| A7 | History and permissions | Published versions are immutable; new evaluations preserve history; reject cross-project and unauthorized calls, with duplicate-request/failure recovery records. |
| A8 | Presentation and scope | Read-only formal results expose provenance, uncovered scope and original failures; synthetic CI material does not replace device evidence, and the Owner separately accepts this slice. |

Controlled device runs require a separate check of device authorization and configuration. CI first proves protocol, rules and failure paths; when new device input is needed, use new runs and output directories while retaining historical evidence. Each acceptance record binds actual implementation Subjects, input/rule versions and accessible evidence; this matrix currently has no PASS conclusion.

## 7. Required design and Owner decisions before implementation

The Owner must confirm the recommended sequencing, the two initial rule intents and actions, presentation scope and A1–A8 acceptance boundaries. Confirmation is not rule publication, implementation acceptance, merge, Tag, release or deployment authorization.

Technical design must provide a TDR covering execution within the existing JVM/database setup, same-Release input construction, fact binding, persistence/API, determinism and resource limits. Specifically reconcile binding for Fact Catalog `testResults[].status`, Issue fields and evaluator-local paths; do not use unregistered paths before updating the specification. Any contract completion needs explicit versioning and compatibility; changes to frozen semantics require ADR. This draft selects no new dependencies and adds no parallel digest or permission mechanism.

Current result: scope, dependencies and acceptance draft prepared, pending Owner scope confirmation. Git status: the draft is committed in both languages and identified through Git history. Next action: prepare technical design and a TDR based on Owner confirmation of this draft. Prerequisites: explicit Owner acceptance or adjustment of sequencing, rule intents and acceptance boundaries. Acceptance target: technical design maps to A1–A8 and resolves same-Release and fact binding; product implementation is not yet executed.
