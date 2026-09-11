---
acceptanceId: M3-SMOKE-RECOVERY-001
subject: Single-device Smoke controlled recovery candidate
subjectCommit: b5ed45d4cede1bb7f48f815da838febd647f1f80
pairedSubjectCommit: 9c9f97d9ebe5aadc64089530024912620eb2deb0
branch: docs/m2-issue-traceability-design-en
status: PENDING
submittedAt: 2026-09-11T08:55:28Z
owner: PENDING
decisionAt: PENDING
---

# Single-Device Smoke Controlled Recovery Candidate Acceptance

## Scope

This candidate includes only Task 7 Step 5 for the fixed product Subjects: stopped PostgreSQL database and Payload cold backup, restoration to a new isolated copy, real Agent restart after Server acceptance but before durable ACK, lease timeout and late-write rejection after owned Backend shutdown, independent same-Run verification and cleanup. Execution used clean ZH checkout `9a569258c3d22bdbe50b7286bdcb8740ae02541d`, paired with EN `5ac9a2047d8d0ea7bf5ddfcbb75c1822378eaa0d`; only Markdown differs from the product Subjects.

Physical USB/ADB disconnection, device reboot, online backup, cross-version database restore, Company Evidence Archive, complete M3 and release acceptance are excluded. Task 7 Step 6 final review remains outstanding. This record does not modify prior acceptance records, Subjects or Owner decisions, and authorizes no merge, Tag, release or deployment.

## Evidence

All entries below are controlled real-execution reports. Locators are relative to private root `recovery-20260911/`; exact SHA-256 values and process evidence are in the [recovery verification record](../../../m3/smoke-recovery-verification.md). This execution's provenance maps to actual checkout `9a569258c3d22bdbe50b7286bdcb8740ae02541d`, bound to this record's fixed product candidate. Original report fields are unchanged; this does not imply that every report embeds a Subject field. Availability: currently accessible to Runtime Owner / Controller; retention and future availability are `UNKNOWN`. Owner Authorization: `UNKNOWN`; no acceptance decision authorization exists yet. Raw device configuration, credentials, Payload and logs are not committed.

| Locator | Generated At | Digest / Summary |
|---|---|---|
| `cold-copy-manifest.json` | `2026-09-11T07:57:55Z` | Stopped cold backup, 1623 files / 71475501 bytes |
| `restore-report.json` | `2026-09-11T08:02:51Z` | Formal reads and download verification passed for original 2 Runs / 4 Evidence objects |
| `a-supplement/supplement-result.json` | `2026-09-11T08:45:18Z` | Same-Run ACK replay supplement exit 0 / PASS; original wrapper FAIL retained |
| `b-supplement/supplement-result.json` | `2026-09-11T08:46:31Z` | Same-Run timeout and late-write supplement exit 0 / PASS; original wrapper FAIL retained |
| `restore-post-exercises-report.json` | `2026-09-11T08:52:00Z` | Restore recheck passed, with the same SHA-256 as the first report |
| `final-cleanup-check.json` | `2026-09-11T08:52:55Z` | All source and backup file hashes unchanged; owned services and processes stopped |

## Acceptance Checks

| Check | Result | Evidence | Notes |
|---|---|---|---|
| Cold backup and isolated restore | `PASS` | Both restore reports and cold-copy manifest | Original 2 Runs / 4 Evidence objects intact; source not overwritten |
| A original wrapper | `FAIL` | Original exercise and summary | evidenceIds Set-order false failure; original exit and summary unchanged |
| A same-Run recovery supplement | `PASS` | A supplement | Restart after Server acceptance; durable ACK; restarted install/launch each 0; no duplicate Result/audit/outbox |
| B original wrapper | `FAIL` | Original exercise failure report | Initial null to terminal input_digest generation falsely rejected |
| B same-Run timeout and late-write supplement | `PASS` | B supplement | SERVER TIMEOUT; 409 late-write rejection; replay install/launch each 0; terminal state unchanged |
| Retained uploaded Evidence | `PASS` | Download checks in both supplements | B's retained LOG/PNG are outside the SERVER TIMEOUT Result's empty Evidence reference set |
| Historical data and cleanup | `PASS` | Final restore and cleanup reports | 1623 file hashes unchanged; owned services stopped |
| Physical ADB disconnection | `UNKNOWN` | Not executed this round | Backend connection interruption cannot substitute for physical disconnection evidence |
| Task 7 Step 6 final review | `UNKNOWN` | Subsequent independent overall review | This record does not predeclare documentation CI or final review success |
| Company Evidence Archive | `N/A` | Scope | Local controlled exercise only |
| Owner decision | `PENDING` | `N/A` | Awaiting Owner review |

## Residual Risks

| Risk | Impact | Owner | Mitigation / Review Condition |
|---|---|---|---|
| Single device and bounded injection | Cannot generalize to a full failure matrix or physical ADB recovery | Runtime Owner | Obtain separate scope authorization and evidence before expansion |
| Original private wrapper false failures and A first-round root cause UNKNOWN | Original execution history cannot be relabeled PASS | Controller | Retain original FAIL, supplements and review; keep A first-round cause unresolved |
| Private evidence has no fixed retention | Future inaccessible evidence becomes UNKNOWN | Runtime Owner | Confirm access and retention arrangements during Owner review |

## Decision Reason

`PENDING`

## Follow-up Actions

| Action | Owner | Due / Trigger | Closure Condition | Completion Evidence |
|---|---|---|---|---|
| Task 7 Step 6 final overall review | Controller / Independent Reviewer | After this submission | Fixed provenance and failure history complete; bilingual documentation checks and overall review finished | Subsequent fixed commit and review report |
| Candidate acceptance decision | Owner | After final review and evidence access | Explicit decision on this record | New commit updates decision fields and appends history |

## Decision History

| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| 2026-09-11T08:55:28Z | PENDING | PENDING | Submitted controlled recovery candidate with original failures and independent same-Run supplements preserved, awaiting final review and Owner decision | PENDING |
