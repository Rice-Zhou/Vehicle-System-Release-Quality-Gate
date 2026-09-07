# M2.5 Owner State Gate Fix Record

## Scope and Root Cause

M2 Run `34087115638` for Chinese state commit `2d62500fa3e5601449c67bd9702541b696945949` and M2 Run `34087115597` for English state commit `ce56d460f830ef6575a65725e4d571f7509555b6` both passed 11/12 checks. Only acceptance failed with `OWNER_DECISION_NOT_PENDING`. Both M1 Runs `34087115618` / `34087115616` succeeded.

After running the shared acceptance validator, the M2 Gate used a second regex rule requiring status, owner, and decisionAt to remain PENDING, rejecting an Owner decision allowed by governance. This fix reuses existing governance authority, introduces no new technology choice, and changes neither TDR-018 nor frozen architecture.

## Changes and Boundaries

Remove the second PENDING state rule. `scripts/acceptance-record-validator.mjs` remains the single validator for state, Owner, timestamps, and history transitions. Retain the requirement that the M2.5 target record exists, returning `ACCEPTANCE_RECORD_MISSING` when absent. Other records cannot replace the target.

Orchestration tests use the real Node validator and installed YAML dependency, isolating only heavyweight external test commands. The Windows wrapper propagates the exit code on a separate line; Linux uses exec. The M2 workflow runs this regression before the candidate Gate.

Machine Gate PASS does not mean Owner APPROVE; REJECT and CONDITIONAL retain their governance effect. Original implementation Subjects, Owner decision, historical Evidence, performance limits, canonical coverage, and archiving obligations remain unchanged. This fix authorizes no Company, real Provider, next milestone, merge, Tag, release, deployment, or Pilot enablement.

## Verification Record

- RED: after the real validator accepted a valid APPROVE record, the original Gate still returned `OWNER_DECISION_NOT_PENDING`, reproducing the CI failure.
- GREEN: the complete `scripts/tests/m2-5-verify-gates.tests.ps1` suite passed, covering all four valid states, invalid state, pending Owner/time, invalid initial history state, and a missing target alongside another valid record.
- During test development, the Windows wrapper swallowed the Node nonzero exit; the invalid-state assertion correctly failed. After correcting exit propagation, the complete rerun passed without relaxing production validation.
- Existing acceptance validator tests passed 37/37; actual acceptance records passed validation; Contract `schemas=4 positive=12 negative=5 operations=34` passed.
- Independent read-only review returned `APPROVE`, with no remaining findings.
- CI acceptance requires successful bilingual exact-head M1/M2 runs after pushing the fix, including execution of the new orchestration regression. This record does not present local stub tests as PostgreSQL or Linux CI Evidence.

## Next Execution Plan

Current result: Gate fix and local regressions complete. Git state: determined by the commit containing this file and the remote branches. Next action: verify bilingual exact-head CI for the fix commits. Prerequisite: push and await completed runs. Acceptance target: four successful Runs, passing new orchestration regression, and M2 12/12 PASS; Evidence archiving still requires separate authorization.
