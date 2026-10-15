# Quality transaction-window direct test evidence

- Date: 2026-10-15; nature: transaction-boundary tests in the formal-source integration fixture, not Owner acceptance.
- Basis: the [sealing-boundary tests](2026-10-15-quality-sealing-boundary-tests-engineering.md), [Task 5/6 plan](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md) and [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md). Repairs require actual behavioral failure; window semantics and acceptance states do not change.

## Observation points and assertions

The existing formal-source HTTP→Worker→persisted-query case in [QualityEvaluationEvidenceIntegrationTest](../../../backend/src/test/kotlin/com/ricezhou/vsrqg/quality/QualityEvaluationEvidenceIntegrationTest.kt) is extended without duplicating preparation code. During the first normal evaluation, spies observe real Beans and every observation point continues through callRealMethod, replacing neither source content, Payload bytes, SQL nor the transaction manager.

| Observation point | Direct assertions |
| --- | --- |
| Evidence pin during formal-source reading | An active transaction and synchronization exist; the initial read is read-only REPEATABLE_READ. The bound real JDBC Connection disables autoCommit and uses REPEATABLE_READ isolation. Completion of commit must precede byte verification. |
| Two PayloadStore.verify calls | Both uploaded Sessions perform real file-digest verification; no active transaction, synchronization, isolation level or bound ConnectionHolder exists. Exactly two checks run, with no byte reread during sealing. |
| Sealing recheck and Snapshot INSERT update | The recheck pin and actual INSERT execution through StatementSpec.update both run in a writable REPEATABLE_READ transaction with autoCommit disabled. They share one ConnectionHolder, distinct from the initial read resource. The second commit completion must follow INSERT. |

The exact event sequence requires initial read, initial commit, two byte checks, metadata recheck, INSERT and sealing commit. After Worker returns, active transaction and resource cleanup are checked. Existing queries still establish a persisted Snapshot and Quality Result for the same Evaluation. Matching the Snapshot INSERT locates the actual database write; source text or SQL-string presence does not replace execution assertions. Real StatementSpec param chaining remains intact and the update probe executes real SQL.

Observation covers only that normal evaluation and resets spies in finally. Existing cross-Release rejection, three fresh-JVM replays, post-verification external-file characterization and queryable ERROR after Payload deletion continue to run. The later window probe still allows seal when metadata is unchanged and explicitly calls fail after restoring the file. It does not claim that Worker automatically detects external changes after verification.

## Verification and evidence limits

compileTestKotlin passed in both worktrees. The local targeted test failed at Testcontainers initialization with Could not find a valid Docker environment. Its original XML contains one initialization-failure node before behavioral assertions ran. Logs and XML remain in the local evidence directory `task5-transaction-window-20261015`; this is neither a behavioral red test nor a pass. Independent read-only review found no Critical, Important or Minor issues.

Exact-commit CI must execute the extended case and retain M1 full-test-results XML, checking the test name and zero failures, errors and skips, alongside the existing sealing-boundary class and M1/M2/M3. An immediate pass on actual execution establishes existing behavior without an invented red-green repair. Any actual behavioral failure must retain output before diagnosis and a minimal repair. Remote commits, Pair Gate and CI for this record require post-commit verification; success is not predeclared.

This evidence does not measure transaction duration, define a Company SLO or promise WORM, administrator tamper resistance or filesystem/database atomicity. TDR-026 remains Proposed / REVIEW_REQUIRED; composite Task 5/6 clauses, Owner acceptance states and production code are unchanged. No rule publication, real Provider, Company resource or device is used.

Current result: transaction state, resource identity, commit ordering and actual INSERT assertions are added to the existing formal-source integration fixture; runtime conclusions await exact-commit CI. Git status: determined by the paired commits containing this record and remote verification. Next action: read-only consolidation of composite Task 5/6 clauses and A1–A8 evidence coverage and remaining gaps. Prerequisites: completed exact-commit CI and XML verification for this work; existing read access to the repository and CI. Acceptance target: map each item to fixed Subjects, code/tests and CI evidence, explicitly retaining uncovered items without changing plan checkboxes or Owner acceptance states.
