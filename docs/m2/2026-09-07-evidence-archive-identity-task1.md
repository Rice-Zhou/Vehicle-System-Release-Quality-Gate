# Evidence Archive Identity Extension — Task 1 Verification Record

## Authorization and Scope

The Project Owner explicitly instructed execution of Task 1 of the plan under accepted TDR-019. The original instruction is preserved below as Unicode escapes. This is an implementation verification record, not a new Owner acceptance decision for the tool implementation or Company archive.

```text
\u6267\u884c\u4efb\u52a1\u0031
```

Basis: [TDR-019](../v0.2/tdr/TDR-019-versioned-evidence-archive-work-package-identity.md)、[Implementation Plan](../superpowers/plans/2026-09-07-m2-5-evidence-archive-identity-implementation.md).
Implementation baseline: 2185c8f906f069684c813f778f8ba75f1603395e.

## Changes and Boundaries

- Add one internal profile accepting exactly `1 / V0-2-EVIDENCE-ARCHIVE-001` and `2 / M2-5-EVIDENCE-ARCHIVE-001`。Parsed/Verified models explicitly carry Int schemaVersion without a default; existing M1 construction sites explicitly supply 1.
- Three Schemas share the work-package identity definition. Node strictly registers dependencies, checks version/ID/digest binding across all three documents, and derives its success summary from validated input.
- Unbound v2 FAIL permits only JSON-null ID/identities, empty Artifacts, and valid error/cleanup fields. Bound reports retain the existing nullableIdentity object format. Reject null PASS, final IN_PROGRESS reports, and interchange of these identity formats.
- The production RecoveryVerifier changes by one constructor argument only. Runner/Recovery test classes receive explicit versions and 60-second test timeouts; Task 2 behavior changes are not included.
- Original M1 descriptor, M2.5 preservation manifest, ZIPs, and historical fixtures are unchanged. No real M2.5 descriptor or new producer fixture was created, and no M2.5 JVM/Company archiving ran.

## Validation Evidence

| Stage | Command or evidence | Actual result |
|---|---|---|
| compiler RED | `backend/build/task1-schema-field-red.log` | exit 1; undefined schemaVersion |
| runtime RED | `backend/build/task1-fixed-id-red.xml` | 1 failure, explicitly `DESCRIPTOR_INVALID:workPackageId` |
| Expanded matrix RED | `backend/build/task1-matrix-red.xml` | 62 tests, 3 failures |
| Node RED | `backend/build/task1-node-red.log` | 82 tests, 77 passed, 5 failed |
| Node GREEN | `node --test scripts/tests/evidence-archive-evidence.test.mjs` | exit 0; 82/82, original 47 retained |
| SourceVerifier GREEN | `./backend/gradlew.bat -p backend test --tests '*EvidenceArchiveSourceVerifierTest'` | exit 0; 62/62, no skips |
| Archive regression and build | `./backend/gradlew.bat -p backend test --tests '*EvidenceArchive*Test' assemble` | exit 0; 217 tests, 212 executed passes, 5 environment skips, 0 failures/errors |
| Existing governance and Contract | `node scripts/acceptance-record-validator.mjs`、`node scripts/contract-validator.mjs` | `PASS`；`schemas=4 positive=12 negative=5 operations=34` |
| Independent read-only review | Full diff, TDR/plan, actual RED/GREEN logs and XML | Spec APPROVE; code quality APPROVE; no remaining findings |

RED/GREEN logs reside in ignored local build output; this table retains versioned result summaries. Explicit field migration preceded runtime RED, whose failure came from the old fixed ID. The initially misnamed task1-compiler-red.log was a passing baseline and is not used as RED evidence. A Node local-variable reference error was exposed by 17 positive tests, fixed to parsed descriptorInput.value, and revalidated to 82/82 without weakening assertions or suppressing failure.

Five skips arise from unavailable POSIX permissions, symbolic-link privileges, and local-filesystem parent-identity conditions; all new SourceVerifier tests executed. Remote exact-head M1/M2 CI supplements platform validation; 212 local passes do not imply skipped cases passed. Actual conclusions of the four post-push CI runs are reported in this task's final handoff, never substituted with previous runs.

## Limitations and Next Execution Plan

Task 1 is an intermediate integration state. Input parser and offline v2 support do not imply complete v2 propagation through Runner, recovery reports, or CLI summaries; those remain Task 2, while the real descriptor and JVM→Node end-to-end evidence remain Task 3. No real Provider, Company, merge, Tag, release, deployment, or next milestone was enabled.

Original creation P95 of 1467/1477 ms misses the 1000 ms reference target; canonical coverage excludes some non-primary-path fields. Original Artifacts expire from 2026-10-07, and local preservation is not immutable archiving. This task closes none of these limitations.

Current result: Task 1 implementation and independent review are complete. Git state: determined by the bilingual commits containing this record and their remote branches. Next action: execute Task 2 of the plan. Prerequisite: explicit Owner authorization for Task 2; Company writes remain separately authorized. Acceptance target: actual Runner/Recovery/CLI identity propagation, pre-/post-parse failure distinction, M1 canonical compatibility, and corresponding red/green, bilingual commits, and CI.
