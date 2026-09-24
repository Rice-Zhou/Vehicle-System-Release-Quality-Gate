# Minimal quality evaluation Task 3: pure rule evaluation engineering record

Date: 2026-09-24. References: [implementation plan](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md), [rule specification](../11-quality-rule-specification.md), [policy confirmation](2026-09-24-quality-task3-policy-review.md).

## Delivery and boundary

Added a restricted AST, Fact Catalog v2 local binding, a pure evaluator, and an aggregator. Rules validate catalog paths, types, and local scope; evaluation uses a cumulative step limit without network, files, or current time. Two versioned YAML rules fix the Smoke Case status actions and required Issue Verified action, with golden fixtures for normal, error, and empty-value boundaries. Both examples are registered under the rule authoring Schema; catalog v1, the Core Contract, original module digests, and published results were not changed.

Restricted evaluation keeps Missing, Empty, Null, and Type Error distinct. Null in a required nonnullable field yields ERROR; collections cannot be compared as scalars; invalid Boolean predicates cannot be hidden by empty collections or appliesWhen being false. Numeric ordering compares only catalog-matched INTEGER/DECIMAL types exactly. Catalog v2 declares no general order for STRING or TIMESTAMP, so ordered operators reject those types; consecutive has a dedicated stable order by capturedAt Instant and evidenceId. Any future general ordering needs versioned catalog and Engine semantics.

## Review and verification

Implementation followed RED→GREEN. Independent specification review found two P1 and two P2 issues: nonnullable Null producing PASS, scalar comparison of a collection with Null, uncounted prescans, and incomplete rule golden categories. Each was fixed and re-reviewed. Independent code quality review found one P1: an empty collection could hide a non-Boolean predicate and yield PASS. Static result-type checking and empty/nonempty comparison tests fixed it, and re-review closed it. Neither review substitutes for Owner acceptance.

The Chinese worktree forcibly reran the Task 3 target tests and assemble: RuleOperatorMatrixTest 17, QualityRuleGoldenTest 3, and QualityAggregatorTest 2; 22/22 PASS with zero failures, errors, or skips. Gradle executed nine tasks and reported BUILD SUCCESSFUL. Node contract tests passed 7/7; validation reported schemas=7, positive=22, negative=9, operations=36. The Memory example in the paired rule specification was also corrected from integer threshold 400 to decimal 400.0 for DECIMAL pssMiB, matching same-type comparison. Final bilingual Pair Gate, remote commits, and CI require fixed-delivery verification and are not claimed here.

## Formal boundaries still pending

The PASS produced by an empty testResults collection in the golden fixture only proves pure any(empty)=false. A formal Rule Set requires nonempty selectedCaseRefs; Task 5 must also establish that each selected Result exists and is terminal, otherwise Evaluation is ERROR. Evidence type declarations do not prove byte integrity; Task 5 must use the Evidence module read-only verification port. RuleOutcome is a domain object; Task 5 must explicitly project it into the Rule Result Schema, including explanation shape and typed INTEGER/DECIMAL diagnostic values, rather than directly serializing it with Jackson. This segment did not publish rules, migrate the database, bind real input, operate a device, or accept the quality milestone.

## Next execution plan

Current result: Task 3 pure evaluation and two demonstration rules passed local target verification; remote status depends on delivery checks. Git status: paired bilingual commit and push status will be confirmed by delivery commands. Next action: check the Task 4 migration number, existing PostgreSQL integration-test environment, and rule-publication permission boundary. Prerequisites: verify Task 3 paired commits and fixed-commit CI; Task 4 product implementation requires separate authorization under the existing plan. Acceptance target: a verifiable Task 4 preflight conclusion without publishing rules or changing production data.
