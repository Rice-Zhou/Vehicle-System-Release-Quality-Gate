---
acceptanceId: M2-2-JIRA-PILOT-COMPAT-001
subject: M2.2 Jira CLI Pilot 实机兼容性修正与 Adapter 级只读验证
subjectCommit: 8a83ed572ffacd5346a99b03246ef2591c081a77
pairedSubjectCommit: 2d4001abd8208dbea209dbaf216ac3c9c9a12e3d
branch: docs/m2-issue-traceability-design
status: PENDING
submittedAt: 2026-08-31T09:37:07Z
owner: PENDING
decisionAt: PENDING
---

# M2.2 Jira CLI Pilot 实机兼容性验收记录

## Scope

**Included**

- 固定中文 Subject Commit `8a83ed572ffacd5346a99b03246ef2591c081a77` 与英文配对 Subject Commit `2d4001abd8208dbea209dbaf216ac3c9c9a12e3d`。
- Owner 授权的真实 Jira CLI v1.7.0 Windows Pilot Adapter 级只读验证：单项目、查询上限 20、固定五列、无 Jira 写操作。
- Jira CLI delimiter 参数绑定、可打印 `U+241F` 分隔符、`UPDATED` offset 时间到 UTC `Instant` 的边界规范化，以及相应 TDR/书面规范修订。
- CI 暴露的 PID marker 内容写入竞态修正及确定性回归测试。

**Excluded**

- Task 4 Sync worker、真实 `Sync Run ID`、PostgreSQL 持久化、Cursor authority、业务 API、Outbox，以及端到端真实 Jira Sync Smoke。
- Jira create、update、transition、comment、assign、attachment、跨项目或超过 20 条查询。
- Company Profile、Company Ready 结论、合并 `main`/`release`、Tag、发布或生产部署。
- 对 V0.1 Core Contract、Release-centric architecture、Manifest authority、Evidence、Traceability、Deterministic Quality Engine、Adapter、Plugin 或 ADR 治理的修改。

## Evidence

- **真实 Adapter 级只读结果**：生成于 `2026-08-31T09:13:14Z`；Adapter Version `jira-cli-pilot-adapter-v1`；Mapping Version `issue-mapping-v1`；query limit `20`；returned count `20`；schema digest `sha256:e82894e3569222827ef8d8a04675728734308752bbcb6db1b63663a9fd89a23b`；`Sync Run ID=NOT_AVAILABLE`；固定结果码 `ADAPTER_READ_SUCCEEDED_SYNC_NOT_EXECUTED`。原始 Issue 数据、标题、人员、Server URL、CLI 路径、完整命令、stderr 和 credential 均未写入本记录、Git 或测试输出。
- **中文完整 Gate**：GitHub Actions `M1 Backend` Run [#132](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33377862448)；Subject Commit `8a83ed572ffacd5346a99b03246ef2591c081a77`；conclusion `success`；duration `4m 35s`。
- **中文 Artifact**：`m1-evidence-8a83ed572ffacd5346a99b03246ef2591c081a77`；Artifact ID [`9752692635`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33377862448/artifacts/9752692635)；size `87 KB`；digest `sha256:e3a6d980e44c66405292a7f86728847a3a0bf30c906f0311a85d9ed2b1058795`；保留截止时间 `UNKNOWN`。
- **英文完整 Gate**：GitHub Actions `M1 Backend` Run [#131](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33377862328)；Paired Subject Commit `2d4001abd8208dbea209dbaf216ac3c9c9a12e3d`；conclusion `success`；duration `4m 21s`。
- **英文 Artifact**：`m1-evidence-2d4001abd8208dbea209dbaf216ac3c9c9a12e3d`；Artifact ID [`9752686145`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33377862328/artifacts/9752686145)；size `87.1 KB`；digest `sha256:f0d421be4d9c51c01dc76d691fd2a14c30b7e55a0a3d2207b97570269507bd36`；保留截止时间 `UNKNOWN`。
- **Red→Green 根因证据**：首次真实 Adapter 读取以固定 `TIMEOUT` 和 `INVALID_OUTPUT` fail-closed；脱敏诊断确认真实读取约 `23.410s`、Jira CLI 需要单参数 delimiter、Go `tabwriter` 不保留 `U+001F`，且 `UPDATED` 为 `uuuu-MM-dd'T'HH:mm:ss.SSSxx`。修正后同一 Adapter 边界返回上述脱敏成功结果。
- **CI 竞态证据**：英文历史 Run [#130](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33377131316) 在读取尚为空的 PID marker 时失败；新增确定性失败用例后，等待条件改为 marker 内容可解析，修复后的中英文 Run #132/#131 均成功。历史失败保留，不由后续 PASS 覆盖。
- **Owner Authorization**：Project Owner 于当前会话明确指令“授权，执行下一步”；不可变授权 locator 为 `UNKNOWN`。本记录保持 `PENDING`，不代替 Owner 决定。
- **Owner instruction receipt（待应用）**：Project Owner 于 `2026-08-31T12:12:39Z` 直接给出原始指令 `APPROVE M2-2-JIRA-PILOT-COMPAT-001`。本阶段只固化该指令供下一独立提交引用，metadata 仍为 `PENDING`；本条不自引用承载它的 commit，也不表示决定已经应用。该指令不授权 Task 4、Company Profile、合并 `main`/`release`、创建 Tag、发布或生产部署。

## Acceptance Checks

| Check | Result | Evidence | Notes |
|---|---|---|---|
| 单项目且最多 20 条真实只读查询 | `PASS` | 脱敏 Adapter 结果 | 返回 20 条；无写命令、JQL 或额外字段 |
| 固定五列、边界解析与标准化映射 | `PASS` | schema digest、Contract/Jira tests、Run #132/#131 | delimiter 与时间格式在 Adapter 边界处理，未知输入仍 fail-closed |
| 敏感数据最小化 | `PASS` | 脱敏报告校验与 Git diff | 仅保留允许的计数、版本、digest 和固定结果码 |
| 中英文非 Markdown 实现一致 | `PASS` | 固定 Subject Commit 配对检查 | 受影响 Kotlin 文件字节一致 |
| 完整 CI Gate | `PASS` | Run #132 与 Run #131 | 两条最终 Run 均成功并产生 Artifact |
| 端到端 Sync Run 与 PostgreSQL 持久化 | `UNKNOWN` | `Sync Run ID=NOT_AVAILABLE` | Task 4 尚未实施，本记录不得宣称完整真实 Jira Sync Smoke PASS |
| Company Ready | `N/A` | Scope exclusion | 本次仅为 PILOT |
| Owner 决定 | `PENDING` | `N/A` | 等待 Project Owner 复核 |

## Residual Risks

| Risk | Impact | Owner | Mitigation / Review Condition |
|---|---|---|---|
| Task 4 尚未实施 | 无真实 Sync Run、事务持久化或 Cursor 证据 | Implementation Owner / Project Owner | 先单独批准并实施 Task 4，再执行带真实 `Sync Run ID` 的端到端 Smoke |
| 当前 Pilot 读取超过默认 `PT15S` | 未配置时会按设计以 `TIMEOUT` 失败 | Pilot Operator | 当前主机通过仓库外配置使用不超过 `PT60S`；不依据单一主机静默扩大全局默认值 |
| Jira CLI transport 可能随版本变化 | delimiter 或时间格式变化会使 Adapter fail-closed | Implementation Owner / Pilot Operator | 固定并记录 CLI Version；升级前重跑 Contract 与最多 20 条真实 Smoke |
| 本机 Docker 不可用 | 本地完整 PostgreSQL/Testcontainers 回归无法完成 | Implementation Owner | 目标测试本地通过；修复后两条 GitHub 完整 Gate 成功，历史本地失败不记为 PASS |
| GitHub Artifact 保留截止时间未知 | 未来可能无法在线复核 Artifact | Release Engineer | Owner 尽快复核；后续 M2 Gate 生成新的固定 Evidence |

## Decision Reason

`PENDING`

## Follow-up Actions

| Action | Owner | Due / Trigger | Closure Condition | Completion Evidence |
|---|---|---|---|---|
| 复核 `M2-2-JIRA-PILOT-COMPAT-001` | Project Owner | 本候选提交后 | Owner 作出 `APPROVE`、`CONDITIONAL` 或 `REJECT` | 新 commit 中的决定字段与 Decision History |
| 在进入 Task 4 前取得独立授权 | Project Owner | 本记录获批后 | 明确授权 Sync worker、事务与 Cursor 范围 | 可复核 Owner 指令 |
| Task 4 完成后执行完整真实 Jira Sync Smoke | Implementation Owner / Project Owner | Task 4 Gate 通过后 | 生成真实 `Sync Run ID`、SUCCEEDED 状态和脱敏摘要 | 新的独立验收记录与固定 Evidence |
| 保持 Company、合并与发布阻断 | Release Engineer / Project Owner | 取得相应独立授权前 | 不启用 Company，不 merge、Tag、release 或 production deploy | Git 与发布审计记录 |

## Decision History

| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| 2026-08-31T09:37:07Z | PENDING | PENDING | M2.2 Jira CLI Pilot 实机兼容性修正、脱敏 Adapter 级结果与双语 CI Evidence 已提交 Owner 复核 | PENDING |
| 2026-08-31T12:12:39Z | PENDING | PENDING | 固化 Owner APPROVE 指令，等待下一独立提交应用 | 30657d174791317850d24d5b6e621340356ae24e |
