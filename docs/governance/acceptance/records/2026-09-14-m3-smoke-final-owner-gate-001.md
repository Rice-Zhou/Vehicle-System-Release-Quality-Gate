---
acceptanceId: M3-SMOKE-FINAL-OWNER-GATE-001
subject: Single-device Smoke final engineering candidate
subjectCommit: b5ed45d4cede1bb7f48f815da838febd647f1f80
pairedSubjectCommit: 9c9f97d9ebe5aadc64089530024912620eb2deb0
branch: docs/m2-issue-traceability-design-en
status: APPROVE
submittedAt: 2026-09-14T01:47:54Z
owner: Project Owner
decisionAt: 2026-09-14T02:41:13Z
---

# Single-device Smoke final candidate acceptance

## Scope

This new candidate covers the implemented single-device Smoke Tasks 1–7: APK, identity, Run/lease, local Evidence, Event/Result, host Agent, integration, real normal/deterministic FAIL runs, controlled recovery and final engineering/evidence review. The fixed product pair is specified above; design baseline, actual recovery checkout and previous record HEADs are separately bound in the [final review](../../../m3/single-device-smoke-final-review.md). The record commit is separate from product code.

Full M3, physical USB/ADB disconnection, complete Crash/ANR, power loss, vehicle Release Quality Gate and Company Evidence Archive are excluded. Quality remains `NOT_EVALUATED`, `verified=false`. Prior design approval and historical acceptance records remain unchanged. This acceptance approval does not authorize merge, Tag, release or deploy.

## Evidence

Type: fixed-Subject CI/Artifact, retained real-device/recovery evidence and independent review reports. Exact six product Run/Artifact mappings, byte digests and historical record links are in the [final review](../../../m3/single-device-smoke-final-review.md). Product CI execution occurred on `2026-09-10`; recovery occurred on `2026-09-11`; `2026-09-14` reconciliation only reread retained bytes and live metadata, without a fresh download or new product/device execution.

Availability: selected product Artifacts were accessible and unexpired in live metadata checked at `2026-09-14T01:36:32Z`; earliest expiry is `2026-10-10T13:01:50Z`. Runtime Owner / Controller currently controls access to private materials; fixed private retention and future availability are `UNKNOWN`. Owner Authorization: explicit conversational approval below has been received; machine identity verification remains `UNKNOWN`. No raw Payload, environment identity or credential is committed.

| Type / Locator | Generated At | Subject Commit / Digest or summary |
|---|---|---|
| Product CI / six exact Runs and Artifacts in final review | Exact creation times retained in linked product metadata; live check `2026-09-14T01:36:32Z` | Fixed product pair; six `completed/success`; retained ZIP bytes/CRC/paths and live size/digest match |
| Real-device / [real-device record](../../../m3/real-device-smoke-verification.md) | Historical execution `2026-09-10`; exact report locators retained there | ZH product; normal PASS / deterministic FAIL; four Payload and Result JCS/receipt/journal checks |
| Recovery / [recovery record](../../../m3/smoke-recovery-verification.md) | `2026-09-11T07:57:55Z`–`2026-09-11T08:52:55Z` | Actual checkout separately bound; original wrapper FAIL plus same-Run supplement PASS |
| Review / `whole-implementation-review.md` | File UTC timestamp and fixed digest in final review | `APPROVE_FINAL`; whole Tasks 1–7, no new blocker, P3 deferred |
| Reconciliation / `evidence-reconciliation.md` | File UTC timestamp and fixed digest in final review | `PASS`; six retained ZIPs, original real/recovery materials and cold-copy bytes |

Private report locators and fixed SHA-256 are recorded in the final review. Their product binding describes the reviewed scope; it does not assert that every original report embeds a Subject field.

### Owner Authorization Receipt

Owner reply in the current project conversation, recorded at 2026-09-14T02:39:19Z. The preceding question explicitly requested approval of this candidate and its recorded residual risks. Source text (verbatim):

> APPROVE M3-SMOKE-FINAL-OWNER-GATE-001，Subject 9c9f97d / b5ed45d

The full Subjects are the fixed product pair in this record. This receipt preserves the explicit conversational decision; it does not claim a verified signature or machine-authenticated Owner identity. A subsequent decision commit will cite this immutable receipt version. Merge, Tag, release, deploy and expanded scope are not authorized.

The explicit Owner reply is preserved in the [immutable receipt](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/blob/4b2d8cccf755581b5be23f11681628c37aee2af9/docs/governance/acceptance/records/2026-09-14-m3-smoke-final-owner-gate-001.md). The recorder transcribes this conversational decision; machine verification of Owner identity remains `UNKNOWN`. Approval is limited to the fixed product pair and retained residual risks.

## Acceptance Checks

| Check | Result | Evidence | Notes |
|---|---|---|---|
| Whole Tasks 1–7 engineering review | `PASS` | Independent `APPROVE_FINAL` report | Engineering only; Owner separate |
| Exact product CI and selected Artifact reconciliation | `PASS` | Six-Run mapping and independent retained-byte report | `2026-09-10` execution, `2026-09-14` reconciliation; skips and ZIP-external JAR limits retained |
| Product pair and record source binding | `PASS` | `source-binding.json` | `467` non-Markdown blobs/modes identical; record-only Markdown changes |
| Real normal / deterministic FAIL | `PASS` | Historical real-device record and offline reconciliation | Raw Case PASS / FAIL retained; scenario PASS is not Release PASS |
| Isolated cold-copy recovery | `PASS` | Recovery record and current byte recomputation | Source and backup each `1623` files / `71475501` bytes; no new restore |
| Original A/B wrappers | `FAIL` | Preserved original reports | Never rewritten by independent supplement PASS |
| Same-Run A/B supplements | `PASS` | Recovery record and retained-material reconciliation | A durable ACK; B SERVER TIMEOUT and late `409` rejection, no false ACK |
| Physical ADB / full Crash/ANR / power loss | `UNKNOWN` | Scope and residual risks | No qualifying execution evidence |
| Company Evidence Archive | `N/A` | Excluded scope | Local controlled evidence only |
| Owner decision | `PASS` | Immutable receipt above | `APPROVE` for `M3-SMOKE-FINAL-OWNER-GATE-001`; machine identity verification remains UNKNOWN |

## Residual Risks

| Risk | Impact | Owner | Mitigation / Review Condition |
|---|---|---|---|
| P3 `OPEN/NON-BLOCKING/EXPLICITLY DEFERRED` | Later Collector failure may obscure first diagnostic; outcome remains ERROR | Controller | Retain accepted deferral; no claim of repair |
| Bounded device/recovery scope, Windows skips/durability and `NotSigned` ZIP | Cannot establish full fault coverage, universal durability or publisher signature | Runtime Owner | Apply final-review boundaries when deciding this limited candidate |
| M2 performance, canonical coverage and Artifact expiry | Reference target unmet; incomplete non-primary digest coverage; evidence may expire | Owner / Controller | Retain exact measurements/expiry and historical limits in final review |
| Private evidence retention `UNKNOWN` | Future independent verification may become unavailable | Runtime Owner | Confirm controlled access and retention when Owner reviews |
| Original wrapper failures and first A root cause `UNKNOWN` | Original execution cannot be renamed PASS | Controller | Preserve original FAIL and separate same-Run supplement facts |

## Decision Reason

The Project Owner explicitly approved this exact candidate and product pair in response to the preceding question covering its recorded residual risks. Accept the bounded Smoke implementation with P3 deferred, original wrapper FAIL retained, physical ADB/full Crash/ANR/power-loss coverage UNKNOWN, performance/canonical limitations and uncertain future private-evidence retention. The conversational receipt records the decision; it does not claim machine-authenticated identity or eliminate any UNKNOWN/FAIL. This is not full M3/Company acceptance or merge, Tag, release or deployment authorization.

## Follow-up Actions

| Action | Owner | Due / Trigger | Closure Condition | Completion Evidence |
|---|---|---|---|---|
| Reconcile the demonstration handoff using the existing runbook | Implementation Owner | Next execution instruction; preserve accepted scope | Identify startup prerequisites, normal/FAIL demonstration steps and result/evidence locators without new infrastructure | Reviewed handoff checklist referencing the existing runbook |

## Decision History

| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| 2026-09-14T01:47:54Z | PENDING | PENDING | Submit the final fixed-product engineering candidate with independent evidence reconciliation, original failures and residual risks retained | PENDING |
| 2026-09-14T02:41:13Z | APPROVE | Project Owner | Approve fixed-product bounded Smoke; explicitly retain recorded residual risks and original failures. | 4b2d8cccf755581b5be23f11681628c37aee2af9 |
