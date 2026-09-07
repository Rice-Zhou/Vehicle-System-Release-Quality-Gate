# Evidence Archive Identity Extension Task 2 Verification Record

## Authorization and Scope

The Project Owner's original instruction in this turn authorizes the next step, referring to Task 2 as the sole next action in the Task 1 delivery. The original Unicode escape is preserved below. This record documents implementation verification, not Owner acceptance of the complete tool or Company archiving.

```text
\u6388\u6743\u6267\u884c\u4e0b\u4e00\u6b65
```

Basis: [TDR-019](../v0.2/tdr/TDR-019-versioned-evidence-archive-work-package-identity.md), the [implementation plan](../superpowers/plans/2026-09-07-m2-5-evidence-archive-identity-implementation.md), and the [Task 1 record](2026-09-07-evidence-archive-identity-task1.md).
Implementation baseline: 4d71742de325fcebfb416b07c135939dd0774afb; paired baseline: 71bac31f5c1ab4ccc4e52779abe4f699bd689b4a.

## Changes and Boundaries

- Runner calls the single profile resolver before facade/provider work, propagates the work-package version, and continues checking receipt acceptanceId.
- Recovery follows beginOutput → complete descriptor parsing → save invocation-local context → read archive report. Pre-parse diagnostics use v2/null; post-parse failures retain the verified version/ID without copying untrusted archive identities.
- Unbound report ID and identity fields use literal JSON null; bound reports retain the existing nullableIdentity object format. The canonical algorithm, completion marker, exception classification, cleanup, and Error propagation remain unchanged.
- OperationMain receives explicit schemaVersion from reports; PASS requires a valid profile, two Artifacts, and no error, while safe summary JSON fields remain unchanged. All three affected JUnit classes use @Timeout(60).
- The existing Recovery Fixture parameterizes version/ID across descriptor, reports, and receipts, computing the digest from actual descriptor bytes. Tests cover both recovery entry points for v2 success, version/digest mismatches, pre-parse failure, and post-parse malformed/unreadable archives.
- Original M1 descriptor, historical samples, M2.5 manifest, and ZIPs remain unchanged. Task 3's formal descriptor and new JVM-to-Node samples were not created; no real Provider/Company archive operation was run.

## Verification Evidence

| Stage | Actual result |
|---|---|
| Runner RED | exit 1; expected version 2, actual 1 |
| Recovery RED | exit 1; unbound diagnostic expected version 2, actual 1 |
| Summary RED | exit 1; compilation exposed the missing explicit schemaVersion field |
| Final fix coverage | Recovery/OperationMain tests exit 0, 42s |
| Final archive regression and build | 224 tests, 219 passed, 5 environment skips, 0 failure/error; assemble succeeded, 52s |
| Final assertion fix | Covering test 1/1 passed, 19s; no production changes |
| Node offline regression | 82/82 passed, no skips |
| Independent review | Initial NEEDS_FIXES; final Spec APPROVE / Quality APPROVE, no unresolved findings |

Commands: `./backend/gradlew.bat -p backend test --tests '*EvidenceArchive*Test' assemble --no-daemon`; `node --test scripts/tests/evidence-archive-evidence.test.mjs`.

Local evidence resides in the ignored build directory: `backend/build/task2-red.log`, `backend/build/task2-recovery-red.log`, `backend/build/task2-summary-red.log`, `backend/build/task2-review-fix-full-green.log`, and `backend/build/task2-node-regression.log`; final XML is `backend/build/test-results/test/TEST-*EvidenceArchive*Test.xml`. This table preserves a versioned summary; the four post-commit exact-head CI conclusions are listed in the final delivery.

Initial review found that v2 tests only copied Verified objects and file-failure tests did not cover v2; the parent also confirmed the missing OperationMainTest timeout. Tests now use actual descriptor digests and both entry points, with the timeout added. The earlier `task2-partial-green.log` actually records failure caused by null identities still serialized as objects; this was fixed. Fix coverage log `task2-review-fix-red.log` actually passed; its historical name is not RED evidence.

Second re-review found the no-download assertion inspecting an unused Fixture. It now checks the actual m25.gateway and passed the covering test and re-review; evidence: `backend/build/task2-review-round2-green.log`.

Five skips reflect POSIX permission, symbolic-link privilege, and local filesystem identity conditions; their success cannot be inferred. Existing Gradle deprecation warnings remain; no build-tool upgrade was made.

## Limitations and Next Execution Plan

Task 2 is an intermediate delivery; formal fixed inputs and complete JVM-to-Node evidence remain Task 3. Original creation P95 of 1467/1477 ms misses the 1000 ms reference target; canonical coverage excludes some non-primary-path fields; original Artifacts expire no earlier than 2026-10-07, and local preservation is not immutable archiving. These limitations remain open.

Current result: Tasks 1 and 2 are complete, implementation fixes and independent review approved. Git state: determined by this document's bilingual commits and remote branches. Next action: execute Task 3. Prerequisite: explicit Owner authorization for Task 3; Company writes and independent recovery still require separate authorization. Acceptance target: formal fixed descriptor, actual JVM-to-Node end-to-end evidence, M1 compatibility, and paired CI; this is not completed archiving.
