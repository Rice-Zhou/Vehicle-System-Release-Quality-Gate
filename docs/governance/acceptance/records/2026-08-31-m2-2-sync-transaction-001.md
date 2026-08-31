---
acceptanceId: M2-2-SYNC-TRANSACTION-001
subject: M2.2 同步事务、Successful Cursor Authority 与业务 API
subjectCommit: 0fed69f8a8199d2ff738aeea05981717b03d6738
pairedSubjectCommit: f863e80a73caed56ed653730e059dedcdfd95c9a
branch: docs/m2-issue-traceability-design
status: PENDING
submittedAt: 2026-08-31T13:04:00Z
owner: PENDING
decisionAt: PENDING
---

# M2.2 同步事务、Successful Cursor Authority 与业务 API 验收候选记录

## Scope

**Included**

- 固定中文 Subject Commit `0fed69f8a8199d2ff738aeea05981717b03d6738` 与英文配对 Subject Commit `f863e80a73caed56ed653730e059dedcdfd95c9a`。
- `StartIssueSync` 的授权、幂等、Sync Run、Audit、Outbox 与 Background Job 单事务创建。
- `RunIssueSync` 的逐页原子写入、revision 去重、固定失败诊断，以及仅在全部成功后推进 successful Cursor。
- `IssueSyncJobWorker` 的有界 Job 领取与状态落库；调度默认关闭，仅在显式配置后启用。
- `POST /api/v1/issue-sources/{sourceId}/sync` 的 `202 Accepted` operation response，以及 `GET /api/v1/issue-sync-runs/{syncRunId}` 的项目授权查询。
- Audit、Outbox 或 Job 写入失败时的整体回滚，以及失败后新 Sync Run 重试且不重复 revision 的 PostgreSQL 集成证据。

**Excluded**

- 真实 Jira 端到端 Sync Smoke、真实 `Sync Run ID`、扩大 Jira 查询范围或任何 Jira 写操作。
- 进程崩溃后 stale `RUNNING` Job 的 lease/reaper 自动恢复；当前 Task 4 仅证明已捕获失败与新 Sync Run 重试。
- Task 5 Immutable Release Issue Snapshot、Traceability、Quality Engine 判定或 Release Gate 接入。
- Company Profile、合并 `main`/`release`、创建 Tag、发布或生产部署。
- 对 V0.1 Core Contract、Release-centric architecture、Manifest authority、Evidence、Traceability、Deterministic Quality Engine、Adapter、Plugin 或 ADR 治理的修改。

## Evidence

- **TDD RED**：首次执行 `IssueSyncIntegrationTest` 在编译阶段因 `StartIssueSync`、`RunIssueSync` 与命令类型尚不存在而失败；随后才实现最小生产路径。
- **中文完整 Gate**：GitHub Actions `M1 Backend` Run [#139](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33393922681)；Subject Commit `0fed69f8a8199d2ff738aeea05981717b03d6738`；created `2026-08-31T12:52:15Z`；completed `2026-08-31T12:56:41Z`；conclusion `success`。
- **中文 Artifact**：`m1-evidence-0fed69f8a8199d2ff738aeea05981717b03d6738`；Artifact ID [`9758685069`](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/9758685069)；size `90613 bytes`；digest `sha256:5679c696c6916a1cf89a7b07f1db32ae6d1f980a307af30b71b060b8209c92f1`；expiresAt `2026-09-30T12:56:38Z`。
- **英文完整 Gate**：GitHub Actions `M1 Backend` Run [#140](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33394459142)；Paired Subject Commit `f863e80a73caed56ed653730e059dedcdfd95c9a`；created `2026-08-31T12:58:30Z`；completed `2026-08-31T13:02:56Z`；conclusion `success`。
- **英文 Artifact**：`m1-evidence-f863e80a73caed56ed653730e059dedcdfd95c9a`；Artifact ID [`9758886094`](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/9758886094)；size `90602 bytes`；digest `sha256:a2cce28c661b9143446d8dfd5991300966a7ffe21a48f6bdb1b6c7dade1c7212`；expiresAt `2026-09-30T13:02:52Z`。
- **历史失败证据**：中文 Run [#138](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33393393139) 在测试清理尝试删除 append-only `audit_event` 时失败；根因修复没有关闭数据库保护，而是为每个测试创建唯一 Authority fixture。历史失败及其 Artifact 保留，不由 Run #139 覆盖。
- **本地回归**：中英文分支的 `IssueSourceContractTest`、`JiraCliPilotAdapterTest`、`ApplicationContextTest`、`ArchitectureTest`、`PermissionMatrixTest` 与 `M2ApiContractTest` 均通过；本机无 Docker，PostgreSQL/Testcontainers 结果由两条完整 CI Gate 固定。
- **Pair Gate**：`scripts/verify-language-branches.ps1` 返回 `PASS mode=Pair chinese=0fed69f8a8199d2ff738aeea05981717b03d6738 english=f863e80`；所有非 Markdown 文件字节一致。

## Acceptance Checks

| Check | Result | Evidence | Notes |
|---|---|---|---|
| 启动事务原子性 | `PASS` | PostgreSQL failure injection、Run #139/#140 | Audit、Outbox 或 Job 任一失败时 Run 与 idempotency 同时回滚 |
| 状态机与失败诊断 | `PASS` | `IssueSyncIntegrationTest` | 覆盖 `QUEUED` → `RUNNING` → `SUCCEEDED`/`FAILED`，失败不返回成功 |
| successful Cursor authority | `PASS` | 多页成功、第二页失败与 retry tests | 只有最终成功事务推进 authority Cursor |
| Revision 去重与分页原子性 | `PASS` | duplicate 与 checkpoint failure tests | 相同 source/version/mapping 不重复；单页失败整页回滚 |
| 授权、幂等与 API | `PASS` | Security、Permission、API 与 integration tests | 未授权请求不创建 idempotency 或 Sync Run；POST 返回 `202` |
| Append-only 治理保持 | `PASS` | Run #138 根因与 Run #139 修复 | 未删除或放宽 immutable trigger，测试通过唯一 fixture 隔离 |
| 中英文候选配对一致 | `PASS` | 固定 SHA Pair Gate | 所有非 Markdown 文件字节一致 |
| 真实 Jira 端到端 Sync | `UNKNOWN` | Scope exclusion | 尚无真实 `Sync Run ID`，不得宣称端到端 PASS |
| 进程崩溃后的 stale Job 自动恢复 | `UNKNOWN` | Residual risk | MVP 当前没有 lease/reaper；不得宣称 crash recovery PASS |
| Owner 决定 | `PENDING` | 本候选记录 | 等待 Project Owner 独立验收指令 |

## Residual Risks

| Risk | Impact | Owner | Mitigation / Review Condition |
|---|---|---|---|
| 尚未执行真实 Jira 端到端 Sync | Fixture 证明契约和事务，不证明特定 Jira 实例到 PostgreSQL 的完整链路 | Implementation Owner / Project Owner | Owner 批准本候选后，单独授权最多 20 条、单项目、只读 Smoke，并生成独立验收记录 |
| stale `RUNNING` Job 没有 lease/reaper | Worker 进程在 claim 后崩溃会留下人工可见但不会自动重领的 Job | Implementation Owner | Pilot 通过运行记录与固定诊断人工处置；出现真实运维需求后再以独立设计增加 lease/reaper，不在 MVP 预装分布式调度 |
| Scheduler 默认关闭 | 未显式配置时 Job 保持 `QUEUED` | Operator | Pilot 部署显式启用并记录配置；测试可直接调用 Worker/Use Case |
| 本机 Docker 不可用 | 本地不能运行 PostgreSQL/Testcontainers 集成集 | Implementation Owner | 目标非容器回归本地通过；两条 GitHub 完整 Gate 成功并固定 Artifact |
| Artifact 于 `2026-09-30` 到期 | 到期后在线 Artifact 可能无法直接复核 | Release Engineer | Owner 在保留期内复核；后续 M2 Gate 生成新的固定 Evidence |

## Decision Reason

PENDING

## Follow-up Actions

| Action | Owner | Due / Trigger | Closure Condition | Completion Evidence |
|---|---|---|---|---|
| 复核并决定 `M2-2-SYNC-TRANSACTION-001` | Project Owner | 本候选提交后 | 给出明确 `APPROVE`、`CONDITIONAL` 或 `REJECT` 指令 | 可复核 Owner 原始指令与后续独立 decision commit |
| 获批后决定是否执行真实 Jira 端到端只读 Smoke | Project Owner | 本候选获批后 | 单独授权单项目、最多 20 条和脱敏输出边界 | 新的独立 Smoke Evidence 与验收记录 |
| 在进入 Task 5 前取得独立授权 | Project Owner | Task 4 获批后 | 明确授权 Immutable Release Issue Snapshot 范围 | 可复核 Owner 指令 |
| 保持 Company、合并与发布操作阻断 | Release Engineer / Project Owner | 取得相应独立授权前 | 不启用 Company，不 merge、Tag、release 或 production deploy | Git 与发布审计记录 |

## Decision History

| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| 2026-08-31T13:04:00Z | PENDING | PENDING | Task 4 双语固定候选、事务/Cursor/API 与完整 CI Evidence 已提交 Owner 复核 | PENDING |
