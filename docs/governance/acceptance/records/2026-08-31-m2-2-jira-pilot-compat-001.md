---
acceptanceId: M2-2-JIRA-PILOT-COMPAT-001
subject: M2.2 Jira CLI Pilot host compatibility correction and Adapter-level read-only verification
subjectCommit: 8a83ed572ffacd5346a99b03246ef2591c081a77
pairedSubjectCommit: 2d4001abd8208dbea209dbaf216ac3c9c9a12e3d
branch: docs/m2-issue-traceability-design-en
status: PENDING
submittedAt: 2026-08-31T09:37:07Z
owner: PENDING
decisionAt: PENDING
---

# M2.2 Jira CLI Pilot Host Compatibility Acceptance Record

## Scope

**Included**

- Fixed Chinese Subject Commit `8a83ed572ffacd5346a99b03246ef2591c081a77` and paired English Subject Commit `2d4001abd8208dbea209dbaf216ac3c9c9a12e3d`.
- Owner-authorized Adapter-level read-only verification against real Jira through Jira CLI v1.7.0 on Windows Pilot: one project, query limit 20, fixed five columns, and no Jira write operation.
- Jira CLI delimiter argument binding, printable `U+241F` separator, boundary normalization of `UPDATED` offset time to UTC `Instant`, and the corresponding TDR and written-spec revision.
- Correction of the CI-exposed PID marker content-write race and a deterministic regression test.

**Excluded**

- Task 4 Sync worker, real `Sync Run ID`, PostgreSQL persistence, Cursor authority, business API, Outbox, and end-to-end real Jira Sync Smoke.
- Jira create, update, transition, comment, assign, attachment, cross-project query, or a query above 20 records.
- Company Profile, Company Ready claims, merging `main`/`release`, Tag creation, release, or production deployment.
- Any change to the V0.1 Core Contract, Release-centric architecture, Manifest authority, Evidence, Traceability, Deterministic Quality Engine, Adapter, Plugin, or ADR governance.

## Evidence

- **Real Adapter-level read result**: generated at `2026-08-31T09:13:14Z`; Adapter Version `jira-cli-pilot-adapter-v1`; Mapping Version `issue-mapping-v1`; query limit `20`; returned count `20`; schema digest `sha256:e82894e3569222827ef8d8a04675728734308752bbcb6db1b63663a9fd89a23b`; `Sync Run ID=NOT_AVAILABLE`; fixed result code `ADAPTER_READ_SUCCEEDED_SYNC_NOT_EXECUTED`. No raw Issue data, title, person, Server URL, CLI path, complete command, stderr, or credential entered this record, Git, or test output.
- **Chinese full Gate**: GitHub Actions `M1 Backend` Run [#132](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33377862448); Subject Commit `8a83ed572ffacd5346a99b03246ef2591c081a77`; conclusion `success`; duration `4m 35s`.
- **Chinese Artifact**: `m1-evidence-8a83ed572ffacd5346a99b03246ef2591c081a77`; Artifact ID [`9752692635`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33377862448/artifacts/9752692635); size `87 KB`; digest `sha256:e3a6d980e44c66405292a7f86728847a3a0bf30c906f0311a85d9ed2b1058795`; retention expiry `UNKNOWN`.
- **English full Gate**: GitHub Actions `M1 Backend` Run [#131](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33377862328); Paired Subject Commit `2d4001abd8208dbea209dbaf216ac3c9c9a12e3d`; conclusion `success`; duration `4m 21s`.
- **English Artifact**: `m1-evidence-2d4001abd8208dbea209dbaf216ac3c9c9a12e3d`; Artifact ID [`9752686145`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33377862328/artifacts/9752686145); size `87.1 KB`; digest `sha256:f0d421be4d9c51c01dc76d691fd2a14c30b7e55a0a3d2207b97570269507bd36`; retention expiry `UNKNOWN`.
- **Red-to-Green root-cause evidence**: the first real Adapter reads failed closed with fixed `TIMEOUT` and `INVALID_OUTPUT` codes. Redacted diagnostics established an approximately `23.410s` real read, the Jira CLI requirement for a single-argument delimiter, Go `tabwriter` removal of `U+001F`, and the `UPDATED` shape `uuuu-MM-dd'T'HH:mm:ss.SSSxx`. After correction, the same Adapter boundary returned the redacted successful result above.
- **CI race evidence**: historical English Run [#130](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33377131316) failed while reading a PID marker that existed but was still empty. After adding a deterministic failing case, the wait condition was changed to require parseable marker content; corrected Chinese and English Runs #132/#131 both succeeded. The historical failure remains visible and is not covered by the later PASS.
- **Owner Authorization**: the Project Owner directly authorized execution of the next step in the current session; immutable authorization locator is `UNKNOWN`. This record remains `PENDING` and does not replace the Owner decision.

## Acceptance Checks

| Check | Result | Evidence | Notes |
|---|---|---|---|
| Real read-only query against one project with a limit of 20 | `PASS` | Redacted Adapter result | 20 records returned; no write command, JQL, or additional field |
| Fixed five columns, boundary parsing, and normalized mapping | `PASS` | schema digest, Contract/Jira tests, Runs #132/#131 | Delimiter and timestamp shapes are handled at the Adapter boundary; unknown input still fails closed |
| Sensitive-data minimization | `PASS` | Redacted-report validation and Git diff | Only allowed counts, versions, digest, and fixed result code are retained |
| Identical non-Markdown implementation on Chinese and English branches | `PASS` | Fixed Subject Commit pair check | Affected Kotlin files are byte-identical |
| Full CI Gate | `PASS` | Run #132 and Run #131 | Both final Runs succeeded and produced Artifacts |
| End-to-end Sync Run and PostgreSQL persistence | `UNKNOWN` | `Sync Run ID=NOT_AVAILABLE` | Task 4 is not implemented; this record cannot claim a full real Jira Sync Smoke PASS |
| Company Ready | `N/A` | Scope exclusion | This execution is PILOT-only |
| Owner decision | `PENDING` | `N/A` | Awaiting Project Owner review |

## Residual Risks

| Risk | Impact | Owner | Mitigation / Review Condition |
|---|---|---|---|
| Task 4 is not implemented | No real Sync Run, transaction persistence, or Cursor Evidence exists | Implementation Owner / Project Owner | Separately approve and implement Task 4, then run an end-to-end Smoke with a real `Sync Run ID` |
| Current Pilot read exceeds the `PT15S` default | Without host configuration, the operation fails with `TIMEOUT` as designed | Pilot Operator | Use repository-external configuration up to `PT60S` on this host; do not silently expand the global default from one host observation |
| Jira CLI transport may change across versions | A delimiter or timestamp-shape change will make the Adapter fail closed | Implementation Owner / Pilot Operator | Pin and record the CLI Version; rerun Contract and a real Smoke of no more than 20 records before upgrade |
| Docker is unavailable on the local host | The complete local PostgreSQL/Testcontainers regression cannot run | Implementation Owner | Local target tests passed; both corrected GitHub full Gates succeeded, and the historical local failure is not recorded as PASS |
| GitHub Artifact retention expiry is unknown | Online Artifact review may become unavailable later | Release Engineer | Owner reviews promptly; a later M2 Gate generates new fixed Evidence |

## Decision Reason

`PENDING`

## Follow-up Actions

| Action | Owner | Due / Trigger | Closure Condition | Completion Evidence |
|---|---|---|---|---|
| Review `M2-2-JIRA-PILOT-COMPAT-001` | Project Owner | After candidate submission | Owner chooses `APPROVE`, `CONDITIONAL`, or `REJECT` | Decision fields and Decision History in a new commit |
| Obtain separate authorization before Task 4 | Project Owner | After this record is approved | Sync worker, transaction, and Cursor scope are explicitly authorized | Reviewable Owner instruction |
| Run the full real Jira Sync Smoke after Task 4 | Implementation Owner / Project Owner | After the Task 4 Gate passes | A real `Sync Run ID`, SUCCEEDED state, and redacted summary exist | New independent acceptance record and fixed Evidence |
| Keep Company, merge, and release operations blocked | Release Engineer / Project Owner | Until the matching separate authorization exists | No Company enablement, merge, Tag, release, or production deploy occurs | Git and release audit records |

## Decision History

| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| 2026-08-31T09:37:07Z | PENDING | PENDING | M2.2 Jira CLI Pilot host compatibility correction, redacted Adapter-level result, and bilingual CI Evidence submitted for Owner review | PENDING |
