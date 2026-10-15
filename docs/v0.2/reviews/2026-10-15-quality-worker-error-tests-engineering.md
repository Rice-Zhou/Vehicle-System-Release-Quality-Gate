# G1 Worker Automatic ERROR Persistence and Payload Corruption Engineering Record

- Date: 2026-10-15; scope: G1 test evidence and a minimal repair of a real failure, not Owner or milestone acceptance.
- Basis: [evidence matrix G1](2026-10-15-quality-task5-task6-evidence-matrix-review.md), [Task 5/6 plan](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md), and [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md). Starting Subjects: Chinese `471f91a7a1ed7902a140d8c325b0d200a09c3c93`, English `8514ce5577c352d49824045553f99127a36fb3e2`; these are pre-supplement baselines, not execution Subjects for the new tests.

## Tests and actual boundaries

`QualityEvaluationIntegrationTest` adds two named parameterized scenarios: input digest and Evidence metadata changes. The reader port returns initial and changed input; the real Worker, sealing repository and PostgreSQL reject and automatically persist ERROR without the test directly calling `fail`. Assertions cover original `QUALITY_SOURCE_CHANGED`, `NOT_EVALUATED`, no Input Snapshot/Rule Result/Quality Result, Job FAILED/attempt=1/completion times, and exactly one corresponding Audit/Outbox error. Requested source references remain unchanged; both reads and the bytes-verification call are checked.

This remains explicit reader fault injection, not proof that normal APIs can rewrite terminal Evidence; no immutable trigger is disabled and no formal source history is rewritten. Shared assertions in test-only `QualityEvaluationAssertions.kt` query persisted results rather than simulate error persistence.

`QualityEvaluationEvidenceIntegrationTest` reuses the formal upload/Result/Traceability/Quality HTTP fixture and changes one SCREENSHOT file byte before the Worker's first verification. It queries ERROR through the real PayloadStore, Evidence reader and Worker, checks the same database invariants and an unchanged prior decision, and restores original bytes in finally. The existing characterization of an external change after verification with unchanged metadata and successful seal remains afterward; no report entry is expanded or new error scenario exported as a demonstration.

## Real RED and root-cause repair

New `QualityEvidenceReaderTest` cases use the real `ControlledPayloadStore`, a controlled temporary directory and the known hello SHA-256; only the Evidence repository/provider are port fixtures. Same-length HELLO replacement, truncated bad and extended hello! each verify a valid file before changing bytes. Before the production repair, all three fail assertions: expected `QUALITY_EVIDENCE_INTEGRITY_ERROR`, actual `PAYLOAD_INTEGRITY_ERROR`; these are not Docker initialization errors.

Root cause: real Store.verify throws EvidenceConflict for corrupt bytes; original `ReadQualityEvidence.verify` converts only IOException or a mismatched return value. The repair wraps only `store.verify`, converts its EvidenceConflict and IOException into the existing Quality integrity error while preserving the original cause, and still rejects mismatched return values. Metadata/binding checks and disabled-storage checks remain outside conversion; other Evidence routes retain their own error semantics. Tests also assert the cause's original storage code to protect diagnostics.

Worker, seal, rule evaluation, API Schema, database migrations and storage verification algorithms are unchanged; no extra bytes read is introduced and post-verification file changes are not promoted to automatic seal detection. Evaluation remains ERROR; only the corruption diagnostic at the Quality boundary is normalized.

## Verification and evidence

Local `compileTestKotlin` passes. All three database target scenarios stop at Testcontainers initialization because Docker is unavailable; this is neither business RED nor a runtime pass. Post-repair local target XML: Reader 5/5, Worker 1/1 and Store 9/10 pass; the remaining Store test is Linux-only and skipped on Windows. Acceptance-record and contract validation pass, with schemas=7, positive=22, negative=9 and operations=36. Independent read-only review checks real Store exception behavior and conversion scope, with no unresolved findings.

Local RED/GREEN XML is retained under `D:/development/Vehicle-System-Release-Quality-Gate-evidence/task5-worker-error-20261015`. New database cases, the whole Backend suite/build and existing report regressions still require verification at the new M1 commits; M2/M3 are additional regressions. Commit/push both languages separately, check Pair Gate, remote exact heads, six CI runs and M1 full-test XML; final Subjects, runs/artifacts and conclusions are fixed in this delivery receipt, without treating baselines or pending CI as successful.

TDR-026 remains Proposed / REVIEW_REQUIRED. Task 5/6 checkboxes, A1–A8 and acceptance status are unchanged; no actual rule publication, device execution, real Provider, merge, Tag or deployment occurs.

## Next execution plan

Current result: G1 tests are added, real-file RED is reproduced and repaired at the boundary, and local targets pass; verify the complete database path at fixed-commit CI. Git status: paired commits, pushes and CI are verified in the delivery receipt. Next action: follow matrix G2 with separate unknown-catalog/encoding rejection regressions. Prerequisites: authorization for follow-up test implementation and an available verification environment. Acceptance target: otherwise valid input/rules return exactly `QUALITY_VERSION_UNSUPPORTED`, excluding false positives from empty rules or digest failures, and paired fixed-commit CI passes; this is not Owner acceptance.
