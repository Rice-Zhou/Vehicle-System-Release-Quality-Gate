# Acceptance Record Governance Policy

This directory preserves reviewable in-repository records for milestone, architecture, implementation, and release acceptance. A concrete record states only the facts, evidence, risks, and Owner decision for a pinned acceptance subject.

## 1. Scope and Architecture Boundary

- This mechanism applies to all in-repository acceptance subjects requiring Owner review.
- This mechanism records only acceptance facts and Owner decisions. It does not modify the Core Contract or ADRs, nor does it alter the frozen Release-centric, Manifest, Evidence, Traceability, or deterministic-engine architecture.
- Records reference machine-generated Evidence and CI Artifacts. They do not copy and reinterpret results, and they do not replace any Gate, merge, or release mechanism.

## 2. Directory, Naming, and Identity

Directory layout:

- `docs/governance/acceptance/template.md`: the standard record template, not a concrete acceptance record.
- `docs/governance/acceptance/records/`: the concrete-record directory; the first M1 record is added by a later independent commit.

A concrete record must be stored under `records/` and named `YYYY-MM-DD-<acceptance-id-lowercase>.md`, for example `2026-08-25-m1-owner-gate-001.md`. The filename date is the date of the record's first commit. Acceptance ID must be unique within the `records/` directory of one branch.

Paired Chinese and English records may use the same Acceptance ID. They identify each other through `branch`, `subjectCommit`, and `pairedSubjectCommit`. For a pair, each side's `pairedSubjectCommit` should equal the other side's `subjectCommit`.

## 3. Subject Commit and Record Commit

`Subject Commit` is the pinned candidate under acceptance, represented by `subjectCommit` in metadata. It must be separate from the record commit that carries the record and must not point to the record commit itself, preventing self-reference. A record commit only records a decision about an already pinned Subject Commit and should not mix in product changes being accepted.

`subjectCommit: N/A` is permitted only when the subject itself is not a Git object. In that case, Evidence must provide an immutable locator, version, and digest. An unknown, unpinned, or unverified commit must not be replaced with `N/A`.

## 4. Machine Validation Contract

Every concrete record must use YAML front matter containing these metadata fields:

| Field | Requirement |
|---|---|
| `acceptanceId` | Stable identifier containing uppercase letters, digits, and hyphens |
| `subject` | Version, milestone, architecture, or release subject under acceptance |
| `subjectCommit` | 40-character lowercase Git SHA for the accepted subject; `N/A` only when the subject is not a Git object |
| `pairedSubjectCommit` | 40-character lowercase Git SHA of the paired branch; `N/A` when bilingual pairing does not apply, with an explanation in Scope |
| `branch` | Branch containing the acceptance subject |
| `status` | `PENDING`, `APPROVE`, `REJECT`, or `CONDITIONAL` |
| `submittedAt` | UTC time in `YYYY-MM-DDTHH:mm:ssZ` format |
| `owner` | Decision owner; must initially be `PENDING` |
| `decisionAt` | UTC decision time; must initially be `PENDING` |

The body must contain and preserve these seven English level-two headings with unchanged spelling and capitalization: `Scope`, `Evidence`, `Acceptance Checks`, `Residual Risks`, `Decision Reason`, `Follow-up Actions`, and `Decision History`. These metadata fields and headings form the machine validation contract and must not be translated, removed, or renamed.

Acceptance Checks Result values are restricted to these enums:

| Result | Meaning |
|---|---|
| `PASS` | Reviewable Evidence proves that the check satisfies its requirements |
| `FAIL` | The check does not satisfy its requirements |
| `UNKNOWN` | Evidence is missing, inaccessible, or expired; it must not be rewritten as `PASS`, and the condition must be entered in Residual Risks |
| `N/A` | Scope evidence proves the check does not apply; it must not substitute for missing Evidence |
| `PENDING` | Reserved for an Owner decision that has not yet occurred |

When an ordinary machine check is incomplete, record `UNKNOWN` or `FAIL` according to the facts, never `PENDING`. If an Owner gives `APPROVE` while an `UNKNOWN` remains, Decision Reason must explicitly accept that risk. Every `CONDITIONAL` item must state its responsible person, deadline or trigger, closure condition, and completion Evidence in Follow-up Actions.

## 5. State Machine and Decision History

A new record must begin in `PENDING`, with both `owner` and `decisionAt` also set to `PENDING`. Only the following transitions are allowed:

- `PENDING -> APPROVE`
- `PENDING -> REJECT`
- `PENDING -> CONDITIONAL`
- `CONDITIONAL -> APPROVE`
- `CONDITIONAL -> REJECT`

An appended row with the same state may only correct or supplement the record. Its Reason must explain the correction and impact; it must not bypass a normal state transition. Every other transition is an illegal rollback or jump and is prohibited.

Decision History Commit means the previous acceptance record commit (parent record commit) on which the state change or same-state correction is based. It is not the Subject Commit or the record commit carrying the current row. The first `PENDING` row uses `PENDING` because no prior record commit exists; the commit carrying the current row is discoverable through Git history or blame.

`APPROVE` and `REJECT` are terminal decisions and must not be modified in place. `Decision History` is append-only: no historical row may be deleted, reordered, overwritten, or rewritten. To correct facts in a terminal state, keep metadata `status` unchanged, preserve the original terminal `decisionAt`, and do not delete or alter the original Decision Reason or History. Use a new commit, append a correction with UTC time and Owner to Decision Reason, then append a same-state History row whose Commit is the previous record commit being corrected. To overturn a terminal decision, create a new Acceptance ID and reference the superseded record; never roll back the state.

## 6. Evidence, Risk, and Security

- Evidence entries should record type, stable location, generation time, digest or SHA-256, and the corresponding Subject Commit.
- When Evidence is missing, inaccessible, or expired, the corresponding acceptance check must explicitly be `UNKNOWN` and must not be fabricated as `PASS`. Residual Risks must also record the reason, responsible person, and review condition.
- The validator checks structure only; it does not authenticate Owner identity or authorization. A non-`PENDING` decision must provide an immutable Owner authorization locator in Evidence, preferably a protected PR approval URL, verified signed commit, or controlled approval-system record ID. The same applies when someone records a decision on behalf of the Owner. Without a verifiable locator, the authorization check is `UNKNOWN`; do not claim the identity was machine-verified.
- Passwords, private keys, API keys, tokens, database credentials, personal data, unredacted logs, and other sensitive information must not be committed. Controlled evidence records only a stable location, access owner, and necessary summary.

## 7. Git Audit Governance

- Record creation, every state update, and every substantive correction must use a separate meaningful commit so the actor, time, and reason remain traceable.
- Rewriting acceptance audit history through force-push, rebase, or squashing related commits is prohibited.
- Markdown on the Chinese main-facing branch uses Chinese; Markdown on the English release-facing branch must be English-only. Paired records must align their Acceptance IDs.
- Markdown is maintained in the language of each branch. Branch-specific Commit, CI Run, and Artifact references are recorded separately; all non-Markdown files must remain byte-identical.

## 8. Authorization Boundary

Without explicit Owner authorization, no person or automation may merge `main`/`release`, create a Tag, or start a release. A terminal decision entering a protected branch should be approved by the Owner, CODEOWNERS, or an equivalent mechanism. An acceptance record in `APPROVE` means only that the Owner accepted its Subject Commit; it is not itself authorization to merge, tag, or release.

## 9. Validation

Machine validation covers YAML front matter and required-field formats, state and time formats, fixed headings, Decision History table structure and transitions, and duplicate Acceptance IDs under `records/`. Run:

```powershell
pnpm run test:acceptance
```

When concrete records exist under `records/`, validate the whole directory with:

```powershell
pnpm run verify:acceptance
```

The following items require manual or cross-branch review and are not guaranteed by those commands:

- Filename date, existence of each SHA and correspondence to `branch`, Evidence accessibility, and Artifact digest.
- Semantics of `UNKNOWN`/`N/A`, append-only Decision History using Git diff/history, and authenticity of Owner identity and authorization.
- Chinese/English Acceptance ID and paired SHA alignment, Markdown language, and byte identity of non-Markdown files.

The only current entry point for manual or cross-branch review is this README checklist and the Task 6 language/byte commands; no other automated tool is claimed to exist. English branch language check:

```powershell
rg -n "[\p{Han}]" README.md docs
```

Run the following command in each Chinese and English worktree, then compare the file set and SHA-256 values:

```powershell
git ls-files | Where-Object { [IO.Path]::GetExtension($_) -ne '.md' } | Sort-Object | ForEach-Object { "$(Get-FileHash -Algorithm SHA256 -LiteralPath $_ | Select-Object -ExpandProperty Hash) $_" }
```

Every validation failure must remain visible and be fixed at its root cause; never eliminate a failure by deleting history, rewriting Evidence, or weakening a state.
