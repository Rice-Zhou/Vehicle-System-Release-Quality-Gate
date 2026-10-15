# G2 Unknown Catalog/Encoding Version Rejection Regression Record

- Date: 2026-10-15; scope: independent version-rejection regression evidence, not Owner or milestone acceptance.
- Basis: [evidence matrix G2](2026-10-15-quality-task5-task6-evidence-matrix-review.md), [Task 5 plan](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md), and [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md). Starting Subjects: Chinese `52c0b801d7b6422807f2e6154aff49e1399ecc4c`, English `c3d2863dcf9baacfa50f7d411f55293192bfcb20`; these are pre-change baselines, not execution Subjects for the new tests.

## Independent scenarios and valid preconditions

Only `QualityReplayTest` gains two named parameterized scenarios:

| Field | Unknown value | Expected |
| --- | --- | --- |
| `catalogVersion` | `999` | `QualityInputFailure.code = QUALITY_VERSION_UNSUPPORTED` |
| `canonicalizationVersion` | `UNAVAILABLE-ENCODING` | `QualityInputFailure.code = QUALITY_VERSION_UNSUPPORTED` |

Each scenario first runs the real `QualityDecisionRunner` with a supported-version Input Snapshot and a nonempty rule list, asserting no errorCode, a BLOCK Quality Result and one BLOCK Rule Result. The existing structural fixture contains an Issue and its rule tests exists issues[], so BLOCK is a valid evaluation outcome, not actual publication or real Issue verification.

It then deep-copies that input, changes only the scenario's version field and retains all other versions, facts, references and rules. It excludes snapshotId/inputDigest from digest material, recomputes inputDigest with existing canonical encoding, and asserts the exact exception code with the same nonempty valid rule list. Errors from empty rules, an old Engine, bad AST or stale digest cannot falsely pass. Digest recomputation only isolates the version check; it implements no unknown encoder or historical interpreter.

If the current interpreter ignores an unknown version and continues evaluation, assertThrows fails; if the diagnostic is an input-digest or rule error, the exact-code assertion fails. Existing unknown-Engine, no-applicable-rule and three-fresh-JVM replay scenarios are unchanged; their evidence claims are not expanded here.

## Verification and delivery boundary

The new scenarios pass directly, with no observed business failure, so production code is unchanged. Chinese local targets: QualityReplayTest 6, QualityRuleGoldenTest 3 and RuleOperatorMatrixTest 17, totaling 26/26 with no failure/error/skipped; assemble, acceptance-record and contract checks pass. Contract totals are schemas=7, positive=22, negative=9 and operations=36. The English worktree reuses identical non-Markdown test content and runs the same target checks.

Local XML/logs are retained under `D:/development/Vehicle-System-Release-Quality-Gate-evidence/task5-version-rejection-20261015`. After separate bilingual commits/pushes, check Pair Gate, remote commits and exact-head M1/M2/M3; M1 full-test XML must include both named rejection cases, rather than relying solely on workflow status. Final Subjects, runs/artifacts, XML digests and CI conclusions are fixed in the delivery receipt without preclaiming pending CI as successful.

Rules, Catalog/encoding definitions, production interpreters, API, database, report entry, plan checkboxes and acceptance state are unchanged. TDR-026 remains Proposed / REVIEW_REQUIRED; no actual rule publication, device execution, real Provider, merge, Tag or deployment occurs.

## Next execution plan

Current result: G2 independent rejection regressions are added and local targets pass, with no production repair. Git status: paired commits/pushes and fixed-commit CI are verified in the delivery receipt. Next action: follow matrix G3 to connect one formal-source Evaluation with independent DB+Payload restoration evidence. Prerequisites: authorization for follow-up implementation and an available PostgreSQL/CI environment. Acceptance target: read that Evaluation's pinned Snapshot and rule AST from an independently restored database and compare its saved result digest in three fresh JVMs; verify current Payload integrity separately from historical decision replay and leave the original demo database untouched; this is not Owner acceptance.
