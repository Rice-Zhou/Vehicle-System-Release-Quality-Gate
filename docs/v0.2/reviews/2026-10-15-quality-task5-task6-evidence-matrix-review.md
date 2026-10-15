# Task 5/6 Compound-Clause and A1–A8 Evidence Reconciliation

Date: 2026-10-15. Read-only inspection of product code, tests and CI; only this bilingual engineering record is added. Coverage below means bounded engineering evidence, not checklist completion, A1–A8 PASS, Owner or milestone acceptance.

Basis: [scope and A1–A8](../../superpowers/specs/2026-09-15-minimal-quality-evaluation-scope-draft.md), [technical design](../../superpowers/specs/2026-09-15-minimal-quality-evaluation-design.md), [implementation plan](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md), and [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md). Historical conclusions in authoritative documents are retained; this record reconciles evidence at the current Subjects without changing their status.

## 1. Fixed Subjects and CI provenance

The existing `m2-issue-traceability-design` and `m2-issue-traceability-design-en` from `git worktree list` are reused; both were clean at audit start. These are product/test Subjects; subsequent documentation commits are not the Subjects of these historical CI runs. This audit rereads GitHub Actions head_sha, conclusions and artifact ownership rather than relying only on prior records.

| Subject | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| ZH `5969533c858db3ae5e69672986e67e7cfd3a67f4` | [38708682189](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38708682189) | [38708682048](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38708682048) | [38708682058](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38708682058) |
| EN `7cb4864478594a248a6887eef17d1cd0564e28cc` | [38708524659](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38708524659) | [38708524630](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38708524630) | [38708524697](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38708524697) |

All six are `completed/success`. Implementation inspection uses those trees; provenance supplements are in the [Task 5 formal-source record](2026-10-08-quality-task5-engineering.md), [recovery record](2026-10-12-quality-task5-persisted-replay-engineering.md), [same-APK record](2026-10-12-quality-a1-same-apk-release-binding-engineering.md), [four-scenario record](2026-10-12-quality-task6-four-scenarios-engineering.md), [field record](2026-10-14-quality-task6-report-fields-engineering.md), [seal boundaries](2026-10-15-quality-sealing-boundary-tests-engineering.md) and [direct transaction test](2026-10-15-quality-transaction-window-tests-engineering.md). Reconcile historical open items against this matrix; do not combine different fixtures into one execution claim.

| Artifact | Local ZIP SHA-256 |
| --- | --- |
| ZH M1 `11960212098` | `c6bdc702e63a1229a06cb1692850da648f72c549f52715137d4331bcdf18297c` |
| EN M1 `11959708429` | `f04d09fef8df22c0a748e6e742705bc067c308e12b3c2fee204e77a8b6646c97` |

GitHub artifact workflow_run.head_sha matches its Subject; neither is expired. Downloaded ZIPs are at `D:/development/Vehicle-System-Release-Quality-Gate-evidence/task5-transaction-window-20261015/tx-zh-evidence.zip` and `tx-en-evidence.zip` in that directory; this audit rehashes them and reads `evidence/<Subject>/full-test-results/TEST-com.ricezhou.vsrqg.quality.*.xml`. `scripts/m1/verify.ps1` archives full results after `clean test bootJar`; later smoke does not overwrite that copy. Both languages have the same counts below: 17 classes and 92 tests each, all failures=0/errors=0/skipped=0.

| Test class | Tests per Subject |
| --- | --- |
| `QualityAggregatorTest` | 2 |
| `QualityCanonicalEncoderTest` | 8 |
| `QualityEvaluationDecisionIntegrationTest` | 1 |
| `QualityEvaluationEvidenceIntegrationTest` | 1 |
| `QualityEvaluationIntegrationTest` | 18 |
| `QualityEvaluationRecoveryIntegrationTest` | 1 |
| `QualityEvaluationWorkerTest` | 1 |
| `QualityEvidenceReaderTest` | 2 |
| `QualityFormalSourceTest` | 8 |
| `QualityInputBindingTest` | 1 |
| `QualityPermissionTest` | 2 |
| `QualityReplayTest` | 4 |
| `QualityRuleGoldenTest` | 3 |
| `RuleOperatorMatrixTest` | 17 |
| `RulePublicationGateTest` | 2 |
| `RulePublicationIntegrationTest` | 10 |
| `StrictRuleYamlTest` | 11 |

Test classes are under `backend/src/test/kotlin/com/ricezhou/vsrqg/quality/`. Code names below default to `backend/src/main/kotlin/com/ricezhou/vsrqg/quality/`; Evidence uses `evidence/application/ReadQualityEvidence.kt` and `evidence/adapter/ControlledPayloadStore.kt`. Full XML supports the specific test scopes below; class-level success does not accept every clause.

## 2. Task 5 compound clauses

| ID | Clause | Code and evidence at fixed Subjects | Limit or gap |
| --- | --- | --- | --- |
| 5.1 | Negative source binding | `FormalQualitySourceReader.read`; `QualityFormalSourceTest`, `QualityInputBindingTest`, `QualityEvaluationEvidenceIntegrationTest`. Two real Releases share an APK; referencing the other Release's Run yields ERROR while existing sources and results remain unchanged. | Only this plan item is checked; project, Manifest digest, missing Result/Case/required refs also have mocked-port evidence, not a complete set of formal database counterexamples. |
| 5.2a | Initial short transaction and external bytes | `QualityEvaluationWorker.runNext` → `FormalQualitySourceReader.read` → `ReadQualityEvidence.verify`. The formal-upload integration test observes real JDBC: the initial read-only REPEATABLE_READ commits before two Payload verifications; bytes run without transaction, synchronization or ConnectionHolder. | Direct execution-boundary evidence; no duration SLO for “short,” and no performance guarantee inferred from shared CI. |
| 5.2b | Sealing transaction and window | `JdbcQualityEvaluationRepository.seal` rereads sources, compares inputDigest and complete Evidence items, and calls `lockClaim`. Formal integration observes recheck and actual Snapshot INSERT sharing a writable REPEATABLE_READ and a ConnectionHolder distinct from the first read, with no remaining transaction afterward. | Source/metadata rejection uses a mocked reader and explicit test `fail`; there is no direct database test of that seal rejection being recorded automatically by the full Worker. |
| 5.2c | ERROR and Payload window | A formal missing Payload produces queryable ERROR through the Worker; direct verification rejection of corrupt bytes has evidence. The window characterization verifies bytes, changes the external file without changing metadata, and still seals successfully. | The final `TEST_WINDOW_PROBE` is explicit fixture termination, not automatic integrity diagnosis; equivalent database evidence for automatic ERROR through the full Worker after corrupt bytes is still missing. Retain TDR-026: seal does not reread bytes; later integrity checks diagnose current files. |
| 5.3a | Pure evaluation and atomic persistence | `QualityDecisionRunner.evaluate`; `complete` atomically saves Rule Results, Quality Result, Evaluation/Job terminal states and Audit/Outbox. `QualityEvaluationIntegrationTest` proves rollback using an invalid result and a real outbox trigger fault; retry creates exactly one of each record. | Real repository/DB transaction evidence with mocked sources; not every Worker fault-injection combination. |
| 5.3b | Reclaim, expiry, duplicates and uniqueness | The same class covers stale-claim seal/complete rejection, exhausted reclaim and idempotency conflicts; writes at 299s succeed while seal/complete at 300s/301s fail without reclaim. V17 unique/immutable constraints retain history. | Expiry tests pass time directly and confirm no reclaim; they do not claim an expired Worker bypasses fencing to write ERROR. |
| 5.4 | Selection and exact versions | Formal Published Plan Case, one Run/Case/Attempt, resultDigest, selectionPolicyVersion, ruleSetDigest and catalog/encoding/Engine enter the Snapshot; `QualityFormalSourceTest` rejects multiple Attempts. `QualityDecisionRunner` checks supported versions. | `otherAttempts=[]` satisfies the current V0.2 limit, not multi-Attempt history support. The historical-version test directly covers unknown Engine; dedicated unknown-catalog/encoding regressions remain absent. |
| 5.5a | Three fresh JVMs from formal sources | `QualityEvaluationEvidenceIntegrationTest` reads the persisted Input Snapshot generated from formal sources and stored rule AST, replays in three fresh JVMs and compares against the saved Quality Result digest. | Only one isolated fixture rule is replayed; this is not cold replay of all four scenarios, the full rule set, or an actual published rule. |
| 5.5b | Time/request isolation and current integrity | `QualityEvaluationRecoveryIntegrationTest` uses identical fixed input with distinct requestId/createdAt/Snapshot IDs and equal inputDigest/resultDigest. Deleting the restored Payload causes current integrity failure while historical replay remains equal. | The recovery fixture mocks the source reader and constructs a synthetic Snapshot; it cannot be combined with the previous row into a claim that one formal-source Evaluation was independently restored. |
| 5.6 | Tests and independent restoration | Fixed M1 full-test XML covers unit/DB/permissions/replay. Recovery actually uses `pg_dump`/`pg_restore` into a new DB and copies an independent Payload directory, then reads Snapshot/AST from the restored DB for three fresh-JVM comparisons. | The original demo DB is untouched; real demo-data restoration and the formal-source-to-restoration chain still lack evidence. Local Docker is unavailable; PostgreSQL is not rerun in this audit, which uses verified fixed CI. |

Normal Evidence APIs and V14 `guard_local_evidence` prohibit metadata/state changes after terminal AVAILABLE. Mocked differences above test defensive rereading, not a legal API race; future tests must not disable triggers or alter production history to invent a normally mutable source. TDR-026 remains Proposed / REVIEW_REQUIRED; it is not strengthened into WORM or filesystem/database atomicity.

## 3. Task 6 compound clauses

| ID | Clause | Code and evidence at fixed Subjects | Limit or gap |
| --- | --- | --- | --- |
| 6.1 | Four scenarios and invalid input | Formal HTTP/Job tests and `quality-fixture-ci.mjs` cover normalSmoke→PASS, deterministicFail→BLOCK, requiredIssueBlock→BLOCK and missingEvidence→ERROR; Node tests cover escaping, cross-Release conflicts and old m1/m2 regressions. | All four are SYNTHETIC_FIXTURE; rules are isolated database fixtures, not actual publication or device input. |
| 6.2a | Fixed result and field projection | `export-quality-report.mjs` follows pagination and selects only the exact evaluationId; `quality-report-export.mjs` validates source associations, unique Evidence, Case/Attempt/Result and rule-reference sets; `quality-report.mjs` displays result ID, pinned sources/versions, typed diagnostics and original ERROR. | Traceability GET has no Issue digest; the Input value is retained without independent reverification. ERROR without input skips source GET and shows unpinned/undetermined, distinct from pinned empty. |
| 6.2b | Locators and permissions | The formal fixture compares Traceability/Test/Evidence metadata GET locators for the same Evaluation; static reports show only Evidence ID/type/runId/attemptId/digest/sizeBytes, without Payload prefetch, Token storage or authorization claims. | Metadata GET writes an integrity observation; Payload download needs separate authorization, purpose and audit. Full device navigation/download evidence for this report is absent. Rule Evidence references are the pinned input set, not per-rule causal proof. |
| 6.2c | Formal demo orchestration | Isolated tests create new Runs and sources through existing formal APIs while retaining history; the separate exporter, Schema and pure renderer are reusable. | The planned `QualityDemoScenario.kt`, `scripts/demo/run-quality.ps1` and `docs/demo/quality-evaluation-runbook.md` are absent. A general controlled-configuration entry and runbook are not delivered; test fixtures are not a user-operated demo entry. |
| 6.3 | CI capability | Existing `.github/workflows/m1-backend.yml` runs contracts, `clean test bootJar` (full Operator Matrix, faults and three replays), bilingual fixture reports and artifacts; M2/M3 are additional regressions at the same Subjects. | Absence of `quality-evaluation.yml` does not remove those CI capabilities or require a duplicate workflow. Shared-CI performance is not an independent benchmark, and M3 workflow success does not prove a device run in this task. |
| 6.4 | Device prerequisites | Historical device records are retained; this audit runs no device, real Provider, Company resources or actual rule publication. | A new device phase remains UNKNOWN and needs current authorization, configuration, device state and new Run/output/spool. Old screenshots cannot substitute for new execution. |
| 6.5 | Engineering review and separate acceptance | This record reconciles A1–A8, fixed Subjects, CI/XML, implementation limits and remaining order; the user confirmed error-page wording, without extending that into broader visual conclusions. | Complete bilingual full-page/narrow-screen visual evidence remains incomplete. Task 5/6 compounds are not checked wholesale, A1–A8 are not self-assigned PASS, and the Owner accepts separately. |

Report tests are `scripts/tests/quality-report.test.mjs`, `quality-report-contract.test.mjs`, `quality-report-export.test.mjs` and `quality-fixture-ci.test.mjs`; the strict Schema is `schemas/v0.2/quality-report-export.schema.json`, with a 16 MiB quality-export limit while old m1/m2 retain 1 MiB per file. The separate export function uses formal queries through injected fetchPage/fetchTraceability/fetchTestRun; this is distinct from the still-missing complete controlled-configuration CLI/demo orchestration.

## 4. A1–A8 evidence matrix

| ID | Required outcome from scope | Engineering evidence | Remaining limitation; no acceptance conclusion |
| --- | --- | --- | --- |
| A1 | Same-Release refs/versions/digests; reject cross-project/Release without altering history | 5.1, 5.2, 6.2; real same-APK counterexample plus source-port matrix. | Not every formal-source conflict has end-to-end evidence; one counterexample does not establish all of A1. |
| A2 | Normal rule PASS, failure BLOCK, aggregation and Case/Attempt/log/screenshot navigation | 6.1 four examples; `QualityRuleGoldenTest`/`QualityAggregatorTest` action tables and precedence; formal GET locators. | normalSmoke has explicit empty requiredIssueRefs; Case PASS does not override requiredIssue BLOCK. Complete Payload/device navigation is unverified. |
| A3 | Required Issue not Verified yields BLOCK; Smoke does not rewrite historical Verified | Formal requiredIssueBlock fixture plus two versioned YAML golden sets; required policy is projected from Rule Set references. | Isolated fixtures do not establish real Issue verification or actual publication; retain policy and publication review boundaries. |
| A4 | Missing/corrupt/digest conflicts/no applicable rules never PASS; retain ERROR/reasons | 5.1–5.3, `QualityReplayTest`/`QualityAggregatorTest`, formal missingEvidence/missingPayload; 6.2 ERROR without input fabricates no result. | Automatic error persistence after seal source change and separate catalog/encoding rejection tests remain; the TDR bytes window is not automatic seal detection. |
| A5 | Missing/Empty/Null/UNKNOWN; required ERROR, optional uncovered | `RuleOperatorMatrixTest`, `QualityRuleGoldenTest`; formal Snapshot retains Crash/ANR/Memory as uncovered; bilingual reports distinguish unpinned from empty. | These Collectors are not implemented; there is no event-count or real collection-completeness proof. Uncovered does not mean zero events. |
| A6 | Full Operator Matrix, permutations, golden, invalid YAML and three equal digests | 17 operator + 11 YAML + 8 encoding + 3 golden tests; fixed-input/fresh-JVM evidence in 5.4–5.6. | Counts are not specification acceptance; formal-source replay covers one rule, restoration uses a different fixture, and some missing-version branches need evidence. |
| A7 | Publication immutability, history, project/permissions, duplicate/failure recovery | `RulePublicationIntegrationTest`/`QualityPermissionTest` plus V17 and 5.3/5.6; real rollback, reclaim, expiry without reclaim and independent DB+Payload restoration. | Actual publication is not executed; multi-Attempt is outside current capability; the same-Evaluation formal-source-to-restoration evidence gap remains. |
| A8 | Read-only formal results, provenance/uncovered/original failures, synthetic/device distinction and separate Owner acceptance | 6.1–6.5; fixed-ID export/pure HTML/no Token; user wording confirmation on 2026-10-15. | Only partial screenshots and user text confirmation; no new complete bilingual visual evidence, controlled demo entry or device Evidence from this task. No Owner acceptance. |

A1–A8 are the scope draft's acceptance requirements; design-level checks map through the Task 5/6 rows. This record adds no second acceptance standard. Historical “not executed” wording and plan progress are not rewritten; Subject-bound engineering evidence supplements current facts.

## 5. Remaining order and evidence plan

| Order | Action and verifiable evidence |
| --- | --- |
| G1 | First add automatic error-persistence tests when `QualityEvaluationWorker` encounters a source-recheck conflict at seal: reuse the real repository/DB, explicitly identify the injected-reader seam, and never call `fail` from the test. Assert original code, no Snapshot/Rule Result/Quality Result, actual Job/Audit/Outbox terminal effects and no source switching. First prove the test reaches seal rejection; also use the formal-upload fixture to establish automatic Worker ERROR for bytes corrupted before first verification. Repair only a real behavioral failure, never tool unavailability. |
| G2 | Add separate unknown-catalog/encoding rejection regressions using otherwise valid fixed Snapshot/rules and assert `QUALITY_VERSION_UNSUPPORTED` exactly, avoiding false positives from empty rules or digest failures. Retain missing-interpreter rejection without adding historical interpreters. |
| G3 | Where required for acceptance, connect one formal-source Evaluation to independent DB+Payload restoration: reuse existing formal-upload fixtures and recovery tools, read that Evaluation's Snapshot/all selected rule ASTs from the restored DB and compare its saved Result in three fresh JVMs; record current integrity separately from historical digest. The two existing independent proofs remain valid; this strengthens evidence rather than repairing a known product bug. |
| G4 | After the Task 5 evidence supplements, implement the controlled-configuration demo entry/runbook under separate authorization, reusing APIs, exporter, renderer and M1 without duplicate services, permissions or workflow. Retain new Runs, fixed Evaluation IDs, four scenarios and field projections. Then collect complete bilingual full-page/narrow-screen and authorized locator-access evidence. |
| G5 | Actual rule publication, device execution and Owner acceptance each await their authorization/review; review TDR-026 and rule-policy boundaries through governance first. Bind device work to new Subjects, Manifest/Snapshot/rule/Engine versions and new Run/output/spool; missing evidence remains UNKNOWN. Multi-Attempt expansion first needs separate TDR-024/TDR-026 review and is outside these supplements. |

G1–G4 are proposed follow-up implementation order, not executed here. New tests may all pass; then submit evidence only without manufacturing a production fix. Continue paired commits/pushes, Pair Gate and exact-head M1/M2/M3 for meaningful changes; fixture publication states are not actual rule publication.

## 6. Audit verification and delivery boundary

This audit rechecks six baseline CI runs, artifact ownership and local ZIP digests, reads complete XML per class, and inspects code/tests and missing entry files. Before documentation commits, run acceptance/contract validation and `git diff --check`; after committing, run bilingual Pair Gate, then push and verify six CI runs for the new documentation commits. Delivery results are reported in this session's receipt, not preclaimed as this record's own CI conclusion. Product code, plan checkboxes, TDR status and acceptance records remain unchanged.

Current result: Task 5/6 compounds and A1–A8 are reconciled at fixed Subjects with coverage, fixture limits and G1–G5 order, without acceptance. Git status: bilingual records added; final commits/push/CI are verified in the delivery receipt. Next action: under subsequent authorization, execute G1 evidence for automatic Worker error persistence after a seal conflict. Prerequisites: authorization for follow-up test implementation and available PostgreSQL/CI; real device work and actual rule publication need separate authorization. Acceptance target: tests reach real seal rejection, the Worker automatically persists queryable original ERROR without fabricated results or source substitution, and bilingual Pair Gate/exact-commit CI pass; this is not Owner acceptance.
