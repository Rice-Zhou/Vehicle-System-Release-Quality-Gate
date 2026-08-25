---
acceptanceId: REPLACE_ACCEPTANCE_ID # Must be replaced when copying the template
subject: "REPLACE_SUBJECT" # Must be replaced when copying the template
subjectCommit: REPLACE_SUBJECT_COMMIT # Must be replaced when copying the template
pairedSubjectCommit: REPLACE_PAIRED_SUBJECT_COMMIT # Must be replaced when copying the template
branch: "REPLACE_BRANCH" # Must be replaced when copying the template
status: PENDING # Fixed initial value; must be replaced when the Owner decides
submittedAt: REPLACE_SUBMITTED_AT_UTC # Must be replaced when copying the template
owner: PENDING # Fixed initial value; must be replaced when the Owner decides
decisionAt: PENDING # Fixed initial value; must be replaced when the Owner decides
---

# Acceptance Record Template

> This file is `template.md`, outside the `records/` directory, so the validator does not treat it as a concrete acceptance record. Everything above is template example data and represents no actual acceptance result.
>
> When copying the template, replace every example value for `acceptanceId`, `subject`, `subjectCommit`, `pairedSubjectCommit`, `branch`, and `submittedAt`, then remove this note. `subjectCommit` normally must be a real pinned SHA. It may be `N/A` only when the subject is not a Git object, in which case Evidence must provide an immutable locator, version, and digest. `pairedSubjectCommit` may be `N/A` only when bilingual pairing does not apply. An unknown commit must not be written as `N/A`.
>
> When creating a concrete record, `status`, `owner`, and `decisionAt` must retain the initial meaning `PENDING`; they are not prefilled actual decisions. Update these fields in a new commit only after the Owner actually decides.

## Scope

**Included**

- Must be replaced when copied: list the version, milestone, component, document, or release boundary included in this acceptance.
- Must be replaced when copied: explain how the Subject Commit relates to this acceptance.

**Excluded**

- Must be replaced when copied: list the explicitly excluded scope and the reason for any use of `N/A`.

## Evidence

Replace every entry when copying; do not treat this example as real Evidence. Each item must record at least:

- **Type**: CI Run, Artifact, report, log, or manual review record.
- **Locator**: Stable URL, Run ID, Artifact name, or repository path.
- **Generated At**: A real calendar-valid UTC instant in `YYYY-MM-DDTHH:mm:ssZ` format.
- **Subject Commit**: Pinned candidate SHA corresponding to the evidence.
- **Digest / Summary**: SHA-256 or a reviewable summary.
- **Availability**: Accessibility and retention period; write `UNKNOWN` when missing, inaccessible, or expired.
- **Owner Authorization**: Immutable authorization locator for a non-`PENDING` decision; write `UNKNOWN` when no verifiable locator exists.

## Acceptance Checks

| Check | Result | Evidence | Notes |
|---|---|---|---|
| Must be replaced when copied: acceptance check | `UNKNOWN` | Must be replaced when copied: evidence location | Template example, not a real result |
| Owner decision | `PENDING` | `N/A` | Awaiting Owner review |

Result is restricted to `PASS`, `FAIL`, `UNKNOWN`, `N/A`, or `PENDING`: `PASS` requires reviewable evidence, `FAIL` means requirements are not satisfied, `UNKNOWN` means evidence is missing, inaccessible, or expired, `N/A` requires proof from Scope, and `PENDING` is reserved for an Owner decision that has not yet occurred. Record an incomplete ordinary machine check as `UNKNOWN` or `FAIL` according to the facts.

## Residual Risks

| Risk | Impact | Owner | Mitigation / Review Condition |
|---|---|---|---|
| Must be replaced when copied: known limitation or production prerequisite | Must be replaced when copied: impact | Must be replaced when copied: responsible person | Must be replaced when copied: mitigation, evidence, and review trigger |

If no known risk exists, replace the example row with an explicit sentence stating that review found no residual risk within scope.

## Decision Reason

`PENDING`

The initial record must retain `PENDING`. After the Owner decides, replace it with the actual decision rationale, supporting Evidence, and treatment of residual risks. If the Owner gives `APPROVE` while an `UNKNOWN` remains, the Owner must explicitly accept that risk here. A terminal-state factual correction may only be appended to the original text with its UTC time and Owner; the original rationale must not be deleted or altered.

## Follow-up Actions

| Action | Owner | Due / Trigger | Closure Condition | Completion Evidence |
|---|---|---|---|---|
| Await Owner review | Owner | When review is complete | Owner has made a decision | Update decision fields and append Decision History in a new commit |
| Must be replaced when copied: other follow-up action | Must be replaced when copied: responsible person | Must be replaced when copied: deadline or trigger | Must be replaced when copied: verifiable closure condition | Must be replaced when copied: completion evidence |

`submittedAt`, every non-initial `decisionAt`, and every Decision History `At` must be a real calendar-valid UTC instant in `YYYY-MM-DDTHH:mm:ssZ` format.

Every `CONDITIONAL` action must fill Owner, Due / Trigger, Closure Condition, and Completion Evidence. Replace `At` in the initial Decision History row with the same instant as `submittedAt`; keep `Status`, `Owner`, and `Commit` as `PENDING`; and replace `Reason` with an actual submission explanation that is non-empty and not `PENDING`. Every later `Commit` means the previous acceptance record commit on which the change is based and must be a 40-character lowercase Git SHA, not the Subject Commit, the commit carrying the current row, `PENDING`, or `N/A`. A `PENDING` row keeps `PENDING` as its `Owner`; a non-`PENDING` row names the actual decision owner. All `At` values must be strictly increasing. For non-`PENDING` metadata, `decisionAt` and `owner` must match the `At` and `Owner` on the first Decision History row that reaches the current metadata `status`; later same-state corrections do not rewrite the first decision time or owner.

The Decision History section may contain only the contiguous table rows below, with no blank line or extra text inside the table. Write `|` inside a cell as `\|`; put detailed reasoning in Decision Reason.

## Decision History

| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| REPLACE_SUBMITTED_AT_UTC | PENDING | PENDING | Must be replaced when copied: candidate submitted for Owner review | PENDING |
