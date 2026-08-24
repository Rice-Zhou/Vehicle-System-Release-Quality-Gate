# 14 — Six-Month MVP Implementation and Acceptance Plan

## 1. Goal

This plan is organized by acceptable outcomes and does not prescribe classes, functions, or internal coding methods. The capacity baseline is one primary developer investing 10–12 hours per week for 24 weeks with 20% Contingency. If any critical milestone slips by more than two weeks, remove UI, trend analytics, Memory Stretch, automated external Issue write-back, and non-essential reports in that order. Release, Manifest, Evidence, Traceability, Deterministic Quality, Auth/Audit, and recovery invariants must not be weakened.

## 2. Global Definition of Done

A milestone is complete only when its contract is implemented, target tests pass, failure paths are visible, operating documentation exists, acceptance Evidence is archived, and Git commits are single-purpose and traceable. "Code is written" alone is not completion.

## 3. Milestones

### M0 — Design Freeze (Weeks 1–2)

Deliverables: this V0.2 document set, TDRs, OpenAPI/Schema drafts, acceptance matrix, and no unresolved architecture conflict.

Exit: Owner completes Architecture Review, conflicting items have approved ADRs, and the `v0.2.0-design-zh` and `v0.2.0-design-en` Annotated Tags are created for the semantically paired Chinese `main` and English `release` commits. Tag Messages cross-reference each other. Status remains Draft before review.

### M1 — Release Identity and Manifest Authority (Weeks 3–6)

Deliverables: project skeleton, database migrations, Release, Manifest Revision/Validation/Lock, basic OIDC/RBAC/Audit.

Exit: concurrent Lock, checksum mismatch, duplicate request, and permission-denial tests pass. External APK/Branch/Build changes cannot modify Release. The API exports Locked Manifest and Validation Report.

### M2 — Issue Snapshot and Traceability (Weeks 7–11)

Deliverables: Jira Adapter, internal-system Adapter, CI/Build entry, Normalized Issue, immutable Snapshot, four Trace Edge types, Confidence, and gap report.

Exit: both Adapters pass the same contract suite; pagination/429/unavailability rehearsals pass; a real Issue-to-Release path is queryable; missing Edges are not disguised; historical Snapshot does not follow external changes.

### M3 — Real Device Test and Evidence (Weeks 12–18)

Deliverables: Device/Agent registration, heartbeat, capabilities, Plan/Case/Run/Attempt, pull protocol, Crash/ANR/Log/Screenshot, and direct object upload. Retain the Memory Interface, Fact Contract, and Rule Example; the real Memory Collector is a Stretch Goal only after M1/M2 are on schedule and the bench is stable.

Exit: one real Device executes Smoke Plan. Network loss, Agent restart, and Device power loss recover deterministically. Required Evidence checksum can be revalidated. Collector contains no Gate threshold.

### M4 — Deterministic Gate and Report (Weeks 19–22)

Deliverables: Fact Catalog, YAML Rule, Rule Set publication, Quality Input Snapshot, Engine, Rule Result, Quality Result, report, and independent Override.

Exit: replay the same input/Rules three times with identical digest. Missing/corrupt input does not PASS. Every BLOCK/WARNING navigates to Evidence and Traceability. AI is outside the decision path.

### M5 — Operational Acceptance (Weeks 23–24)

Deliverables: deployment/upgrade/rollback runbooks, monitoring/alerts, backup recovery, capacity benchmark, security checks, and a real Release acceptance package.

Exit: deploy from an empty environment, restore backup and replay, and complete one real Release end-to-end. The Owner signs against the acceptance matrix or records explicit gaps.

## 4. Critical Dependency Order

```text
Release/Manifest
  → Issue/Build Facts → Traceability Snapshot
  → Device/Agent → Test Result/Evidence
  → Rule/Fact Catalog → Quality Evaluation
  → Report/Operations Acceptance
```

The UI may be implemented as a thin layer after the API stabilizes and must not precede the core loop. Advanced reporting must not block real-device execution or deterministic replay.

## 5. Acceptance Matrix

| Frozen Principle | Acceptance Scenario | Expected | Required Evidence |
|---|---|---|---|
| Release-centric | Same APK in two Releases | Releases remain independent; Artifact is reusable | ER/API query |
| Manifest authoritative | External version changes after Lock | Original Release/Manifest unchanged | Digest + Audit |
| Evidence first-class | Payload deleted/corrupted | Evidence integrity error; evaluation rejected | Reconciliation report |
| Traceability mandatory | Issue has only Commit | Fixed/Included/Verified remain separate | Gap report |
| Deterministic Engine | Replay same Snapshot/Rule | Decision digest identical | Replay report |
| Adapter isolation | Jira field changes | Mapping failure visible; Core free of DTO contamination | Contract test |
| Plugin collectors | Memory threshold changes | Only Rule changes; Collector unchanged | Git diff + test |
| AI advisory | AI unavailable/conclusion differs | Gate Result unchanged | Dependency/flow proof |
| Real device | Execute on real bench | Run, Device, Environment, Evidence all associated | E2E report |
| Auditability | Override BLOCK | Original Result retained; governance decision has actor/reason | Audit export |

## 6. Fault Acceptance Suite

Rehearse duplicate Create/Lock/Result, concurrent Lock, Jira 429/5xx/interrupted pagination, internal-system unavailability, Agent disconnect/restart, Device power loss, interrupted Evidence upload/checksum mismatch, DB transaction failure, duplicate Job claim, Rule exception, inconsistent Snapshot, and backup recovery.

Every rehearsal records precondition, injection method, observed signals, terminal system state, data reconciliation, recovery steps, and residual risk.

## 7. GitHub Version Governance

- `main`: stores only accepted stable baselines.
- Design/features use clearly scoped branches and commits; do not mix unrelated formatting or incomplete changes.
- V0.1 baseline tag: `v0.1.0-architecture`.
- V0.2 review draft: branch `docs/v0.2-implementation-architecture`, version `0.2.0-draft.N`.
- After Architecture Review, merge and create the cross-referenced `v0.2.0-design-zh` / `v0.2.0-design-en` Annotated Tags. Implementation milestones use paired language-suffixed tags or release notes that reference both Chinese and English commits.
- Every commit states WHY/WHAT, affected documents/modules, verification, and residual risk. Submit ADR before proceeding on an architecture conflict.

## 8. Risks and Scope Control

| Risk | Control |
|---|---|
| Insufficient spare time | Prioritize a complete vertical slice and defer UI/analytics/scale capabilities |
| Unstable external API | Adapter contract + fixture + Snapshot |
| Uncontrolled device environment | Environment Snapshot, preflight, lease, recovery window |
| Evidence volume growth | Object storage, retention policy, measured capacity |
| Uncontrolled Rules | Restricted DSL, versions, golden tests, publication review |
| Technical over-design | TDR requires current requirement evidence and alternatives |

## 9. Final Acceptance Package

1. Design Freeze documents and TDR index.
2. Deployment version/database/protocol/Rule/Manifest schema inventory.
3. Real Release, Locked Manifest, and Validation Report.
4. Issue/Traceability Snapshot and gap report.
5. Real Device Run, Results, and Evidence inventory/checksum.
6. Quality Input, Rule Results, Quality Result, and three replays.
7. Permission, Audit, fault, backup-recovery, and capacity reports.
8. Known limitations, V0.3 candidates, and open risks.

V0.2 MVP may be declared complete only when this acceptance package is complete and the Owner approves.
