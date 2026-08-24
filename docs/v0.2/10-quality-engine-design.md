# 10 — Deterministic Quality Engine

## 1. Responsibility

Quality Engine receives normalized, frozen, verifiable facts and uses a published Rule Set to create immutable Rule Results and Quality Result. It does not call Jira, collect Evidence, infer missing data as a pass, or accept AI output as an authoritative decision.

```text
Locked Manifest ─┐
Issue Snapshot ──┤
Trace Snapshot ──┼→ Canonical Quality Input → Rule Evaluator
Test Results ────┤                           → Rule Results
Evidence Index ──┤                           → Aggregate
Rule Set ────────┘                           → Quality Result
```

## 2. Input Snapshot

`QualityInputSnapshot` freezes releaseId, Manifest ID/digest, Issue Snapshot ID/digest, Traceability Snapshot ID/digest, selected Test Run/Result ID+digest, AVAILABLE Evidence Metadata ID+digest, Rule Set ID/version/digest, engine version, and canonicalization version.

Before creation, verify that every reference belongs to the same Release, has terminal state, has complete Evidence, and has an interpretable version. Inconsistency gives Evaluation status ERROR and cannot produce PASS.

## 3. Canonical Facts

Rules access only allowlisted Fact paths such as:

- `release.manifest.artifacts[]`
- `issues[].fixed/included/verified/required/severity`
- `traceability.gaps[]/minimumConfidence`
- `testResults[].caseId/status/attemptNo`
- `evidence.crashes[]/anrs[]/memorySeries[]`

Canonicalization fixes sorting, Missing/Empty/Null semantics, units, and fixed-point decimal precision. UNKNOWN/MISSING is not equivalent to 0/false. Section 5 of [11-quality-rule-specification.md](11-quality-rule-specification.md) is the sole operator-semantics specification.

## 4. Evaluation

1. Read the published Rule Set and Input Snapshot.
2. Validate digest and schema/version.
3. Build immutable Canonical Facts.
4. Evaluate all applicable Rules in stable `(priority, ruleId, version)` order.
5. Each Rule outputs PASS/WARNING/BLOCK/ERROR/NOT_APPLICABLE, matched facts, Evidence refs, and explanation-template parameters.
6. Aggregate the final Result and store its digest.

Do not short-circuit by default so the explanation is complete. A Rule exception outputs ERROR and makes the overall Evaluation ERROR; it must not be ignored.

## 5. Aggregation

Quality action priority is `BLOCK > WARNING > PASS`. If any applicable Rule is BLOCK, final status is BLOCK. With no BLOCK and at least one WARNING, it is WARNING. Only when all applicable Rules PASS is it PASS.

Any missing required input, Rule ERROR, Rule Set without an applicable Gate Rule, or Evidence-integrity error produces Evaluation ERROR. Release remains NOT_EVALUATED or shows its previous Result, but that Result is not reused as the current one.

## 6. Representative Facts to Results

- Required Issue not Verified: Rule matches Issue Snapshot + trace path + verification Result → BLOCK.
- ANR in a critical package: Rule matches Artifact criticality + ANR fingerprint occurrence + Evidence → BLOCK.
- PSS above threshold three consecutive times: Rule calculates a fixed window over the normalized MiB series → BLOCK/WARNING.
- Non-required Case SKIPPED: a Rule decides WARNING or PASS; the Orchestrator does not.

## 7. Replay and Audit

Replay reads only the Input Snapshot and exact Rule/Engine/Canonicalization versions and does not access live external systems. Identical input and versions must produce identical per-Rule status, matched facts, explanation parameters, and final status; evaluationId/timestamp may differ.

Result stores Rule outcomes, Evidence/Fact references, execution versions, duration, and digest. AI may generate a summary after the Result, but AI content is separately labeled and excluded from result digest.

## 8. Override

Override creates a separate `GovernanceDecision`: originalQualityResultId, decision, actor, reason, approval, expiresAt where applicable, and Audit Event. It cannot modify or delete the algorithmic Quality Result. UI/report must display both the algorithmic Result and governance decision.

## 9. Version and Rollback

Rule, Rule Set, Engine, and Canonicalization are versioned separately. If a bad Rule is published, retire that version and publish a new one; historical Results still reference the old version. Re-evaluation creates a new Evaluation and cannot overwrite the old Result.

## 10. Performance and Optimization

MVP executes restricted in-memory Rules after one transactionally consistent read and does not introduce a distributed Rule service. The initial target is to evaluate one MVP Release within a reasonable number of minutes; freeze the concrete SLO after benchmarking real data. Optimize in order: query indexes → Fact pre-aggregation → batch reads, without sacrificing replayability.

## 11. Acceptance

- Run the same Snapshot/Rule Set at least three times; Rule Result digests are identical.
- Missing input, Rule exception, and corrupted Evidence cannot produce PASS.
- Every BLOCK/WARNING navigates to its Rule, facts, and Evidence.
- Override does not alter the algorithmic Result.
- Engine has no dependency on Jira SDK, Agent, or AI service.

Evidence: golden tests, property tests, replay report, fault injection, explanatory report, and dependency-boundary scan.
