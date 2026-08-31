---
acceptanceId: M2-2-SYNC-TRANSACTION-001
subject: M2.2 Sync Transactions, Successful Cursor Authority, and Business API
subjectCommit: 0fed69f8a8199d2ff738aeea05981717b03d6738
pairedSubjectCommit: f863e80a73caed56ed653730e059dedcdfd95c9a
branch: docs/m2-issue-traceability-design-en
status: PENDING
submittedAt: 2026-08-31T13:04:00Z
owner: PENDING
decisionAt: null
---

# M2.2 Sync Transactions, Successful Cursor Authority, and Business API Acceptance Candidate

## Scope

**Included**

- Fixed Chinese Subject Commit `0fed69f8a8199d2ff738aeea05981717b03d6738` and paired English Subject Commit `f863e80a73caed56ed653730e059dedcdfd95c9a`.
- Single-transaction creation of authorization, idempotency, Sync Run, Audit, Outbox, and Background Job by `StartIssueSync`.
- Atomic page writes, revision deduplication, fixed failure diagnostics, and successful Cursor advancement only after total success by `RunIssueSync`.
- Bounded Job claim and persisted state transitions by `IssueSyncJobWorker`; scheduling is disabled by default and enabled only by explicit configuration.
- `POST /api/v1/issue-sources/{sourceId}/sync` with a `202 Accepted` operation response, and project-authorized retrieval through `GET /api/v1/issue-sync-runs/{syncRunId}`.
- PostgreSQL integration evidence for total rollback when Audit, Outbox, or Job writes fail, plus retry through a new Sync Run without duplicate revisions.

**Excluded**

- Real Jira end-to-end Sync Smoke, a real `Sync Run ID`, expanded Jira query scope, or any Jira write operation.
- Automatic lease/reaper recovery of a stale `RUNNING` Job after process failure; this Task 4 scope proves handled failures and retry through a new Sync Run only.
- Task 5 Immutable Release Issue Snapshot, Traceability, Quality Engine decisions, or Release Gate integration.
- Company Profile, merging `main`/`release`, creating a Tag, release, or production deployment.
- Any change to the V0.1 Core Contract, Release-centric architecture, Manifest authority, Evidence, Traceability, Deterministic Quality Engine, Adapter, Plugin, or ADR governance.

## Evidence

- **TDD RED**: the first `IssueSyncIntegrationTest` execution failed at compilation because `StartIssueSync`, `RunIssueSync`, and command types did not yet exist; the minimum production path was implemented only afterward.
- **Chinese Full Gate**: GitHub Actions `M1 Backend` Run [#139](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33393922681); Subject Commit `0fed69f8a8199d2ff738aeea05981717b03d6738`; created `2026-08-31T12:52:15Z`; completed `2026-08-31T12:56:41Z`; conclusion `success`.
- **Chinese Artifact**: `m1-evidence-0fed69f8a8199d2ff738aeea05981717b03d6738`; Artifact ID [`9758685069`](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/9758685069); size `90613 bytes`; digest `sha256:5679c696c6916a1cf89a7b07f1db32ae6d1f980a307af30b71b060b8209c92f1`; expiresAt `2026-09-30T12:56:38Z`.
- **English Full Gate**: GitHub Actions `M1 Backend` Run [#140](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33394459142); Paired Subject Commit `f863e80a73caed56ed653730e059dedcdfd95c9a`; created `2026-08-31T12:58:30Z`; completed `2026-08-31T13:02:56Z`; conclusion `success`.
- **English Artifact**: `m1-evidence-f863e80a73caed56ed653730e059dedcdfd95c9a`; Artifact ID [`9758886094`](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/9758886094); size `90602 bytes`; digest `sha256:a2cce28c661b9143446d8dfd5991300966a7ffe21a48f6bdb1b6c7dade1c7212`; expiresAt `2026-09-30T13:02:52Z`.
- **Historical Failure Evidence**: Chinese Run [#138](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33393393139) failed when test cleanup attempted to delete append-only `audit_event`; the root-cause correction did not disable the database protection and instead created a unique authority fixture for every test. The historical failure and its Artifact remain visible and are not overwritten by Run #139.
- **Local Regression**: `IssueSourceContractTest`, `JiraCliPilotAdapterTest`, `ApplicationContextTest`, `ArchitectureTest`, `PermissionMatrixTest`, and `M2ApiContractTest` passed on both branches; Docker is unavailable on the local host, so the PostgreSQL/Testcontainers results are fixed by the two full CI Gates.
- **Pair Gate**: `scripts/verify-language-branches.ps1` returned `PASS mode=Pair chinese=0fed69f8a8199d2ff738aeea05981717b03d6738 english=f863e80`; every non-Markdown file is byte-identical.

## Acceptance Checks

| Check | Result | Evidence | Notes |
|---|---|---|---|
| Start transaction atomicity | `PASS` | PostgreSQL failure injection and Run #139/#140 | Run and idempotency both roll back when any Audit, Outbox, or Job write fails |
| State machine and failure diagnostics | `PASS` | `IssueSyncIntegrationTest` | Covers `QUEUED` → `RUNNING` → `SUCCEEDED`/`FAILED`; failures are not returned as success |
| successful Cursor authority | `PASS` | Multi-page success, second-page failure, and retry tests | Only the final success transaction advances the authority Cursor |
| Revision deduplication and page atomicity | `PASS` | Duplicate and checkpoint-failure tests | The same source/version/mapping is not duplicated; a page failure rolls back the entire page |
| Authorization, idempotency, and API | `PASS` | Security, Permission, API, and integration tests | Unauthorized requests create neither idempotency nor Sync Run; POST returns `202` |
| Append-only governance retained | `PASS` | Run #138 root cause and Run #139 correction | The immutable trigger was neither removed nor weakened; tests use unique fixture isolation |
| Chinese-English candidate pairing | `PASS` | Fixed-SHA Pair Gate | Every non-Markdown file is byte-identical |
| Real Jira end-to-end Sync | `UNKNOWN` | Scope exclusion | No real `Sync Run ID` exists, so end-to-end PASS must not be claimed |
| Automatic stale Job recovery after process failure | `UNKNOWN` | Residual risk | The MVP has no lease/reaper; crash recovery must not be claimed as PASS |
| Owner decision | `PENDING` | This candidate record | Awaiting an independent Project Owner acceptance instruction |

## Residual Risks

| Risk | Impact | Owner | Mitigation / Review Condition |
|---|---|---|---|
| Real Jira end-to-end Sync has not run | Fixtures prove contracts and transactions, not the complete path from a particular Jira instance to PostgreSQL | Implementation Owner / Project Owner | After Owner approval of this candidate, separately authorize a read-only Smoke for one project and at most 20 records, with an independent acceptance record |
| A stale `RUNNING` Job has no lease/reaper | A Worker process crash after claim leaves a visible Job that is not automatically reclaimed | Implementation Owner | Handle manually in the Pilot through run records and fixed diagnostics; add lease/reaper only through a separate design when a real operational need appears, without pre-installing distributed scheduling in the MVP |
| Scheduler is disabled by default | A Job remains `QUEUED` without explicit configuration | Operator | Explicitly enable and record configuration for a Pilot deployment; tests may call the Worker/Use Case directly |
| Docker is unavailable locally | PostgreSQL/Testcontainers integration tests cannot run on the local host | Implementation Owner | Target non-container regressions passed locally; two full GitHub Gates succeeded and fixed their Artifacts |
| Artifacts expire on `2026-09-30` | Online Artifact review may no longer be possible after expiry | Release Engineer | Owner reviews within retention; a later M2 Gate produces new fixed Evidence |

## Decision Reason

The current status is `PENDING`. The candidate is supported by Task 4 TDD Red→Green, transaction and failure-injection tests, two successful fixed-Subject CI Runs, Artifact digests, and the bilingual Pair Gate. It does not rewrite real Jira end-to-end Sync, automatic process-crash recovery, the Company environment, or complete M2 as `PASS`.

The Project Owner must decide whether to accept the sync transactions, successful Cursor authority, business API, and residual risks implemented by the fixed Subject Commits. Any decision applies only to this record's Scope and does not automatically authorize the next task.

## Follow-up Actions

| Action | Owner | Due / Trigger | Closure Condition | Completion Evidence |
|---|---|---|---|---|
| Review and decide `M2-2-SYNC-TRANSACTION-001` | Project Owner | After this candidate is committed | Give an explicit `APPROVE`, `CONDITIONAL`, or `REJECT` instruction | Reviewable original Owner instruction and a later independent decision commit |
| After approval, decide whether to run a real Jira read-only end-to-end Smoke | Project Owner | After this candidate is approved | Separately authorize one project, at most 20 records, and redacted output boundaries | New independent Smoke Evidence and acceptance record |
| Obtain independent authorization before Task 5 | Project Owner | After Task 4 is approved | Explicitly authorize the Immutable Release Issue Snapshot scope | Reviewable Owner instruction |
| Keep Company, merge, and release operations blocked | Release Engineer / Project Owner | Until separately authorized | Do not enable Company or perform merge, Tag, release, or production deployment | Git and release audit records |

## Decision History

| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| 2026-08-31T13:04:00Z | PENDING | PENDING | Task 4 bilingual fixed candidate, transaction/Cursor/API, and full CI Evidence submitted for Owner review | PENDING |
