# Acceptance Record Governance Design

## 1. Goal

Establish repository-native, reviewable records for M1 and every future architecture, implementation, release, and milestone acceptance. Each record must connect the accepted subject, machine evidence, human judgment, residual risks, and follow-up actions while preserving accountability through Git history.

## 2. Boundary

This design defines only the structure, lifecycle, and Git governance of acceptance records. It does not change the frozen V0.1 architecture, Core Contract, ADR mechanism, or Quality Engine, and it does not authorize automatic approval, merge, release, or tag creation.

Machine-generated `evidence.json` files and CI artifacts remain the factual sources for gates. Acceptance records reference them but must never copy and rewrite their outcomes. The Project Owner remains accountable for the final decision.

## 3. Approach

Use one independent record for each accepted subject:

- `docs/governance/acceptance/README.md` defines the long-term governance rules and record catalog.
- `docs/governance/acceptance/template.md` defines the mandatory structure for every record.
- `docs/governance/acceptance/records/<date>-<acceptance-id>.md` stores each concrete record.

A single shared ledger is rejected because it creates long-term merge conflicts. GitHub Issues, Releases, and chat history are not the sole authority because platform state and permission changes would weaken reviewability.

## 4. Record Model

Every record must contain these fields:

| Field | Meaning |
|---|---|
| Acceptance ID | Unique, stable acceptance identifier within the repository |
| Subject | Version, milestone, architecture, or release under acceptance |
| Subject Commit | Candidate commit under acceptance; never the commit that carries this record |
| Paired Subject Commit | Candidate commit on the paired language branch; use `N/A` with a reason when not applicable |
| Branch | Candidate branch |
| Status | `PENDING`, `APPROVE`, `REJECT`, or `CONDITIONAL` |
| Submitted At | UTC time when the candidate entered acceptance |
| Owner | Final decision owner; use `PENDING` before the decision |
| Decision At | UTC decision time; use `PENDING` before the decision |
| Scope | Explicitly included and excluded acceptance scope |
| Evidence | CI, artifacts, reports, and SHA-256 values |
| Acceptance Checks | Result and evidence location for each check |
| Residual Risks | Known limitations, production prerequisites, and owners |
| Decision Reason | Owner decision and rationale; use `PENDING` before the decision |
| Follow-up Actions | Actions, owners, and stop conditions after the decision |
| Decision History | Append-only status-change history |

Separating `Subject Commit` from the record commit prevents self-reference when the record itself moves branch HEAD. The record commit carries governance material only; the accepted subject remains the fixed candidate SHA that completed machine gates.

## 5. Lifecycle

1. Create a record with `PENDING` status after the candidate completes machine gates.
2. Reference the fixed Subject Commit, CI run, and artifact without prefilling an approval result.
3. After review, the Owner changes the status to `APPROVE`, `REJECT`, or `CONDITIONAL` through a new meaningful Git commit and appends Decision History.
4. `CONDITIONAL` must list conditions, owners, completion evidence, and a deadline or review trigger.
5. Corrections append a correction entry and new history row. Never delete history, rewrite artifacts, squash the relevant audit commits, or force-push.
6. Merge, tag, or release actions are allowed only after `APPROVE` and satisfaction of all mandatory prerequisites; those actions still require separate authorization.

Allowed status transitions are:

- `PENDING → APPROVE`
- `PENDING → REJECT`
- `PENDING → CONDITIONAL`
- `CONDITIONAL → APPROVE`
- `CONDITIONAL → REJECT`

Do not rewrite terminal `APPROVE` or `REJECT` decisions in place. A reversal requires a new Acceptance ID that references the superseded record.

## 6. Bilingual and Git Governance

- Markdown on the Chinese branch uses Chinese; corresponding Markdown on the English branch uses English only.
- Both branches use the same paths, Acceptance IDs, status semantics, and evidence structure.
- Branch-specific commits, CI runs, and artifacts are recorded separately; Markdown bytes need not match.
- Non-Markdown files remain byte-identical.
- Creating a record, making a decision, or adding a correction each requires a separate meaningful commit pushed to the corresponding remote branch.
- Without explicit Owner approval, do not merge `main`/`release`, create a tag, or remove candidate worktrees.

## 7. First M1 Record

The first record uses Acceptance ID `M1-OWNER-GATE-001` and initial status `PENDING`. Its accepted subjects are fixed as:

- Chinese Subject Commit: `f567e3e366e7cd454d8ccd128dd6a56645b66997`
- English Subject Commit: `586a89932baa9489d8ac946f0a01f2d0dd332b53`
- Chinese CI Run: `32824436148`
- English CI Run: `32824447703`
- Chinese Artifact: `m1-evidence-f567e3e366e7cd454d8ccd128dd6a56645b66997`
- English Artifact: `m1-evidence-586a89932baa9489d8ac946f0a01f2d0dd332b53`

The record must state that `m1-acceptance-validator/1` is a controlled acceptance fixture only. The production validator, OIDC, backup retention, and operational ownership remain prerequisites for production adoption.

## 8. Verification and Failure Handling

Implementation must verify:

- Every mandatory field exists and all statuses and transitions are valid.
- Subject Commit matches the CI run and artifact name.
- URLs, artifact SHA-256 values, and acceptance evidence are locatable.
- A `PENDING` record contains no fabricated Owner, decision time, or approval rationale.
- English documentation contains no Han characters and non-Markdown files remain byte-identical across branches.
- Both worktrees are clean, local and remote SHAs match, and CI succeeds.

When evidence is missing, the corresponding check is `UNKNOWN`, never a pass. If Subject Commit, artifact, or SHA-256 differs, stop acceptance and create a Finding. Never erase the discrepancy by editing the expected value.

## 9. Security and Privacy

Acceptance records must not contain passwords, tokens, private keys, database credentials, personal sensitive information, or unredacted logs. Store only stable links, public identifiers, hashes, accountable roles, and necessary judgments. Sensitive operational evidence remains in controlled systems; the record identifies its responsible owner and location without copying sensitive content.
