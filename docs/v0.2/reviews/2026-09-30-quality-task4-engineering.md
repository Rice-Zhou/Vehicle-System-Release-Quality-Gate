# Minimal Quality Evaluation Task 4: Rule Version and Publication API Engineering Record

Date: 2026-09-30. Authorities: [implementation plan](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md), [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md), and [rule specification](../11-quality-rule-specification.md). This record reports engineering verification; it is neither Owner acceptance nor permission to publish real rules.

## Delivery and boundary

V16 adds Rule Set version and rule-content tables retaining the request definition, catalog/engine versions, requiredIssueRefs, selectedCaseRefs, content digest, author/reviewer, reason, Git provenance, original YAML, restricted AST, and golden fixture digest. The database rejects UPDATE/DELETE of published versions and retains history; a new version can be drafted after its predecessor is published. `createRuleSet` and `publishRuleSet` reuse project authorization, Idempotency, Audit, and `If-Match`; an author cannot review their own version.

The current publication gate accepts only the two fixed demonstration YAML files in the repository. It checks packaged resource and source digests, AST and Fact Catalog v2 bindings, and actually runs versioned golden cases. Other structurally valid rules may be stored as Draft without YAML provenance, but publication rejects them. This limit respects the existing JSON-only rule request contract and does not mislabel client JSON as Git YAML. No rule was actually published; no Company resource or deployment was used; Task 5 was not started.

## Review and verification

Independent specification and code-quality reviews found and closed fractional catalog-version truncation, trailing JSON acceptance, rule versions outside the database range, incorrect reporting of concurrent Draft uniqueness conflicts, and an application-to-adapter dependency. Rejection paths were added, and the application now uses a parser port. Early CI runs also exposed a PostgreSQL `Instant` binding in the test fixture and legacy migration assertions affected by V16; the isolated restore drill now removes V16 objects before replaying V15→V16. Idempotency replay compares JSON content rather than field order.

Local reruns of `ArchitectureTest`, `ApplicationContextTest`, `RulePublicationGateTest`, and the existing `QualityRuleGoldenTest` passed. Node quality contracts passed 7/7; contract verification passed schemas=7 / positive=22 / negative=9 / operations=36. There is no local Docker installation, so the final GitHub M1 full test runs provide the PostgreSQL evidence.

Final implementation commits: Chinese `74ec9213cabbe7564e1483d4874ff10370e781dc`, English `82c54816830d0003990db50136288613708b4adf`. Pair Gate and EnglishOnly passed, and non-Markdown content matches across the two commits. CI for both fixed commits concluded success:

| Branch | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| Chinese | [36685730380](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/36685730380) | [36685730421](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/36685730421) | [36685730391](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/36685730391) |
| English | [36685750264](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/36685750264) | [36685750130](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/36685750130) | [36685750135](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/36685750135) |

## Remaining boundary and next-step plan

TDR-026 remains Proposed / REVIEW_REQUIRED; this implementation does not change its governance status. This phase verifies the demonstration publication mechanism only. Real publication requires separate authorization; Task 5 formal fact binding, asynchronous evaluation, and historical replay remain unimplemented.

Current result: Task 4 implementation and bilingual fixed-commit CI passed engineering checks; no Owner acceptance was performed. Git status: implementation subjects were pushed; this record will be committed separately on both branches. Next action: read-only preflight for Task 5 source ports, Job/fencing, V17 availability, and test environment. Preconditions: pair this record and verify its fixed-commit CI; Task 5 implementation requires later explicit authorization. Acceptance target: give the Owner reviewable Task 4 scope and evidence without claiming that quality evaluation is complete.
