---
acceptanceId: M3-SMOKE-IMPLEMENTATION-OWNER-GATE-001
subject: Single-device Smoke integration engineering candidate
subjectCommit: bc1f62637ac9f9357912abf85961a65cb0035852
pairedSubjectCommit: dbd59a48ba9c7dc9279588e046182dbf97ab22ef
branch: docs/m2-issue-traceability-design-en
status: PENDING
submittedAt: 2026-09-10T11:00:12Z
owner: PENDING
decisionAt: PENDING
---

# Single-Device Smoke Integration Candidate Acceptance Record

## Scope

This record submits the Task 7 single-device demonstration engineering candidate: formal API integration, strict local configuration, Agent completion confirmation, independent CI_FIXTURE and the runbook. Real-device normal/expected FAIL, disconnection/Agent restart and local database/Payload recovery remain undelivered and explicitly UNKNOWN. This does not establish all of Task 7, M3, Release Quality Gate or Company completion.

Original design approval does not replace an Owner decision on this fixed implementation Subject. subjectCommit identifies implementation; the later commit carrying this record is separate. No merge, Tag, release, deployment or real Provider is authorized.

## Evidence

- Fixed [implementation Subject](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/bc1f62637ac9f9357912abf85961a65cb0035852) and [paired implementation Subject](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/dbd59a48ba9c7dc9279588e046182dbf97ab22ef).
- The [integration engineering record](../../../m3/single-device-smoke-verification.md) maintains actual tests, reviews, exact CI/Artifacts, digests, exclusions and the next action. The [runbook](../../../m3/single-device-smoke-runbook.md) describes controlled operations.
- The [implementation plan](../../../superpowers/plans/2026-09-09-single-device-smoke-implementation.md), [TDR-024](../../../v0.2/tdr/TDR-024-single-device-smoke-execution.md) and [TDR-025](../../../v0.2/tdr/TDR-025-local-demo-evidence-payload.md) retain existing authority boundaries.
- Read-only preflight on 2026-09-10: the Owner-designated Android head unit was authorized through ADB with API 34; private device configuration and an APK copy are prepared. The Owner explicitly confirmed no local database. Raw serials, environment values, configuration and credentials are not committed.

## Acceptance Checks

| Check | Result | Evidence | Notes |
|---|---|---|---|
| Local component and wrapper verification | PASS | Commands, XML and logs in the engineering record | Rounds are recorded separately, not accumulated as one execution; local database integration failures are listed separately. |
| Final independent review and CI-fix scoped review | PASS | Engineering record | Final engineering review and the last CI-fix scoped review passed, with no new Critical/Important findings. |
| Exact bilingual M1/M2/M3 CI and Artifacts | PASS | Engineering record | All six exact implementation CI runs succeeded and six selected Artifacts were independently verified; initial failures remain recorded. |
| Local database runtime prerequisites | PASS | [Local runtime verification](../../../m3/local-runtime-verification.md) | Subsequently authorized native PostgreSQL preparation, two Backend health rounds and normal restart passed. Historical Docker integration failures remain; this does not prove real-device or backup recovery success. |
| Real-device normal and expected FAIL integration | UNKNOWN | Not executed | API34 preflight is not installation or Run→Result→Evidence proof. |
| Disconnection and Agent restart field recovery | UNKNOWN | Not executed | Unit tests do not replace actual injection, leases, terminal state and recovered bytes. |
| Paired local database and Payload recovery | UNKNOWN | Not executed | M2.5 recovery reports do not prove M3; actual restoration and recomputation are required. |
| Owner decision | PENDING | This record | No acceptance decision for this fixed implementation candidate has been received. |

## Residual Risks

Real-device and recovery Evidence does not yet exist. The Implementation Owner must provide it once explicit device authorization and local runtime prerequisites are available; open checks must not become PASS. CI_FIXTURE does not prove real ADB installation, UI or head-unit behavior. Initial CI failures and missing local Docker remain recorded; later success belongs only to its exact Subject.

Task 6 primary-diagnosis overwrite P3 remains OPEN/NON-BLOCKING/EXPLICITLY DEFERRED. Existing APK lint/build warnings and single-account locking, Windows persistence and spool-retention limits are documented in the engineering record. Historical M2.5 Run creation P95 above the 1000 ms reference, canonical non-primary-field coverage limits and earliest Artifact expiry of 2026-10-07 remain. Current Artifact expiry follows actual metadata in the engineering record. Expired or inaccessible Evidence becomes UNKNOWN.

## Decision Reason

PENDING

## Follow-up Actions

| Action | Owner | Due / Trigger | Closure Condition | Completion Evidence |
|---|---|---|---|---|
| Prepare local demo runtime prerequisites | Owner / Implementation Owner | Authorized by subsequent instruction and completed | Local database connectivity, Backend health and normal stop/start passed, without Company or real Providers. | [Local runtime verification](../../../m3/local-runtime-verification.md). |
| Complete real-device and recovery checks | Implementation Owner | Device authorization and runtime/recovery prerequisites are available | Exercise normal/FAIL bindings, recovery injection timing, leases, terminal states, bytes and absence of repeated installation under the accepted plan; unexecuted items remain UNKNOWN. | Formal APIs, downloaded bytes/SHA-256 and actual recovery records. |
| Owner decision on the fixed candidate | Project Owner | After reviewing this Subject, evidence and residual risks | Preserve an explicit decision and authorization locator; no automatic merge or release. | Separate decision-record commit. |

## Decision History

| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| 2026-09-10T11:00:12Z | PENDING | PENDING | Submit the single-device integration candidate with real-device and recovery work incomplete, awaiting an independent decision on the fixed Subject. | PENDING |
