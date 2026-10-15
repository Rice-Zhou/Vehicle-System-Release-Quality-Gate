# Report wording confirmation and input-sealing evidence review

- Date: 2026-10-15; nature: user-feedback recording and read-only engineering review, not Owner acceptance.
- Basis: the [notice fix record](2026-10-15-quality-task6-unpinned-presentation-engineering.md), [Task 5/6 plan](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md) and [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md). This work changes no production code, tests, rules, demo entry points or acceptance states.

## User confirmation and provenance

The user confirmed that the page displays unpinned/undetermined content, retains ERROR and requested the next step. This feedback is recorded as `USER_REPORTED_CONTENT_CONFIRMATION`. The immediately preceding delivery context associates it with the following rerendered historical synthetic reports. The user supplied no separate URL, language, viewport or new screenshots; the feedback cannot establish full bilingual-page visual acceptance or agent browser inspection.

| Locator | Fixed value |
| --- | --- |
| Chinese fix commit | `7be5d55fcfd40a343be67e8c1181a2c26fe159e8` |
| English fix commit | `b8e2c5a78f07d4b4b3f4a409cc6de8b907f6c235` |
| Source fixture commit / Artifact | `de578230bd8bbef124ce090697c6e76fda124873` / `11867684721` |
| Evaluation | `qev_01a1386ede8e703a9915c8ad9dd595bf` |
| Export SHA-256 | `658dd2b55813c112abcc96455258aac42571f649a52bd393792a1c215c97615d` |
| Chinese HTML SHA-256 | `19777c0b36a0bce858bb1ac2849432ca5c5865434c60cd735ab6e0f76e525b81` |
| English HTML SHA-256 | `3cb40864604f1b42d14d6a7270e251d670f8c84a4739c60ba73a779cc05f91ff` |
| User-feedback receipt SHA-256 | `9c54a5a4924952959ab3d5cf7e3ce8599e36f4272808e035c90f630f0a1e6eff` |

Files remain in the local evidence directory `task6-unpinned-7be5d55`; the receipt is `user-confirmation-20261015.json`. This review recomputed the three report-file digests above and verified that both remote branches still pointed to the fix commits. The original export retains ERROR, NOT_EVALUATED and QUALITY_REQUIRED_EVIDENCE_MISSING. No new Evaluation ran; no Input Snapshot or Quality Result exists. Original provenance and old screenshots remain preserved; historical pending records are not rewritten as having passed at that time.

The preceding delivery receipt records a passing Pair Gate and six successful CI runs for the exact fix commits: Chinese [M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38697359009), [M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38697359080), [M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38697359019); English [M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38697372090), [M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38697372117), [M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38697372100). The initial online reread in this review timed out. This explicitly cites previously verified delivery evidence and does not claim six freshly retrieved responses. Commits and CI for this record require separate verification.

## Task 5 transaction-boundary mapping

Code and test Subjects are the trees at the fix commits above. These tests belong to the default Backend test source set. The [M1 workflow](../../../.github/workflows/m1-backend.yml) invokes the [candidate gate](../../../scripts/m1/verify.ps1), running clean test bootJar and archiving test results. Previous M1 success is aggregate evidence for that batch. This review neither reran PostgreSQL nor independently downloaded and inspected individual test XML; it cannot claim fresh per-test execution counts.

| Clause | Locatable implementation and existing tests | Evidence limits / gaps |
| --- | --- | --- |
| Pin sources in a short transaction | [FormalQualitySourceReader.read](../../../backend/src/main/kotlin/com/ricezhou/vsrqg/quality/application/QualitySourceReader.kt) lines 40–41 declare read-only REPEATABLE_READ; Worker lines 36–38 read, verify bytes, then seal | Annotations and call order are inspectable; no dedicated test directly asserts an inactive transaction during byte verification or measures lock duration. |
| Verify Evidence bytes outside the transaction | [ReadQualityEvidence.verify](../../../backend/src/main/kotlin/com/ricezhou/vsrqg/evidence/application/ReadQualityEvidence.kt) lines 51–68 check session, binding, state and metadataDigest before calling PayloadStore; Worker has no transaction annotation | The [formal Evidence integration test](../../../backend/src/test/kotlin/com/ricezhou/vsrqg/quality/QualityEvaluationEvidenceIntegrationTest.kt) deletes a Payload and queries an integrity ERROR through Worker; this does not prove continuous integrity. |
| Recheck sources and metadata at sealing | [JdbcQualityEvaluationRepository.seal](../../../backend/src/main/kotlin/com/ricezhou/vsrqg/quality/adapter/JdbcQualityEvaluationRepository.kt) lines 123–156 lock the claim, reread sources, compare inputDigest and the complete evidence objects, then insert a Snapshot | The [repository integration test](../../../backend/src/test/kotlin/com/ricezhou/vsrqg/quality/QualityEvaluationIntegrationTest.kt) at line 140 changes the digest through a mocked reader, proving sealing rejection and queryable ERROR; it does not directly cover unchanged digests with only an Evidence metadataDigest change. |
| Old workers cannot seal or complete | Repository lockClaim lines 312–328 check RUNNING, attempt_count, started_at and the 300-second lease, locking Job/Evaluation; seal, complete, fail and retry share this check | Repository integration tests at lines 252, 272 and 291 cover rejected fail, seal and complete after reclaim; they do not directly cover exact expiry before another worker reclaims the job. |
| Atomic completion transaction | Repository complete lines 158–224 write Rule Results, Quality Result, Evaluation/Job terminal states, Audit and Outbox in one transaction | The repository integration test at line 169 causes a result constraint failure; line 193 causes an Outbox failure and asserts rollback of results, rules, Audit and Outbox, followed by exactly one reclaimed completion. Its source reader is mocked. |
| Queryable failure without source substitution | Repository fail, terminalFailure and row; Worker records the source error against the same Evaluation rather than replacing it with another request | Repository integration tests at lines 126, 140 and 252 cover source failure, source change and retry exhaustion; the Worker unit test only proves the fail call and cannot independently prove database queryability. |
| External file changes after verification | Formal Evidence integration test lines 440–462 change a file after byte verification; a second check fails but seal succeeds, and the fixture actively calls fail after restoring the file | This matches the TDR-026 window. That ERROR is not automatic Worker diagnosis of the window change; successful sealing does not imply another byte check at sealing. |

## Task 6 remaining scope

Fields, same-Evaluation reference consistency, four isolated outcomes, bilingual read-only projection and the notice fix have engineering evidence. The planned independent orchestration-entry files remain absent. Current CI reuses the existing M1 workflow to generate reports; a planned workflow filename does not justify adding parallel infrastructure. A new entry point is outside this authorization.

Actual rule publication, real-device input, complete-page visual coverage and Owner judgment of A1–A8 remain separate. Unknown unpinned content must not be represented as an empty collection; existing tests still verify actual pinned empty collections. Composite Task 5/6 clauses remain unchecked and TDR-026 remains Proposed / REVIEW_REQUIRED.

## Executable sequence and evidence plan

1. First add targeted sealing-boundary tests: unchanged input digests with a changed Evidence metadataDigest must reject sealing; exactly at the 300-second lease deadline, before reclaim, the old claim must fail seal/complete. Reuse the repository integration tests and assert that the old claim does not overwrite the original Snapshot, results, Job attempt or terminal state. This fills evidence gaps; the implementation may already pass, so expected passing additions must not be called pre-fix red tests.
2. If behavior actually fails, preserve failure output and repair only the shared source comparison or lockClaim, without another gate. If nothing fails, commit tests only. Target unit tests default to a 60-second timeout. Without Docker, report compilation only; isolated CI must establish database behavior.
3. Retain exact bilingual Subjects, M1 test XML, zero failures and zero skipped targeted cases, Pair Gate and all six CI runs. Keep the missing direct transaction-state evidence for byte verification explicitly open. Run targeted tests before the affected build; do not operate real rules or devices.

Current result: user-confirmed wording is recorded with provenance, and Task 5 sealing implementations and existing tests are mapped individually; no new behavior was implemented. Git status: determined by the paired commits containing this record and remote verification. Next action: add targeted sealing tests for Evidence metadata changes and lease expiry before reclaim. Prerequisites: isolated PostgreSQL or CI capable of executing these tests; no real Provider or device is needed. Acceptance target: exact-commit targeted tests prove rejection of changed sources and expired claims without additional Snapshot/result writes; engineering evidence remains distinct from Owner acceptance.
