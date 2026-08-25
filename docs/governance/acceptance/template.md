---
acceptanceId: REPLACE_ACCEPTANCE_ID # 复制模板时必须替换
subject: "REPLACE_SUBJECT" # 复制模板时必须替换
subjectCommit: REPLACE_SUBJECT_COMMIT # 复制模板时必须替换
pairedSubjectCommit: REPLACE_PAIRED_SUBJECT_COMMIT # 复制模板时必须替换
branch: "REPLACE_BRANCH" # 复制模板时必须替换
status: PENDING # 初始记录固定值，Owner 决定时必须替换
submittedAt: REPLACE_SUBMITTED_AT_UTC # 复制模板时必须替换
owner: PENDING # 初始记录固定值，Owner 决定时必须替换
decisionAt: PENDING # 初始记录固定值，Owner 决定时必须替换
---

# 验收记录模板

> 本文件位于 `template.md`，不在 `records/` 目录中，因此 validator 不会将它当作具体验收记录。上述内容全部是模板示例，不表示任何真实验收结果。
>
> 复制模板时必须替换 `acceptanceId`、`subject`、`subjectCommit`、`pairedSubjectCommit`、`branch` 和 `submittedAt` 的所有示例值，并删除本说明。`subjectCommit` 通常必须是真实固定 SHA；仅当 subject 不是 Git 对象时可写 `N/A`，此时 Evidence 必须提供不可变 locator、version 和 digest。`pairedSubjectCommit` 仅在不适用双语配对时可写 `N/A`。未知 commit 不得写 `N/A`。
>
> 创建具体记录时，`status`、`owner` 和 `decisionAt` 必须保持初始语义 `PENDING`，它们不是预填的真实决定。仅在 Owner 实际作出决定后，才通过新 commit 更新这些字段。

## Scope

**Included**

- 复制时必须替换：列出本次验收包含的版本、里程碑、组件、文档或发布边界。
- 复制时必须替换：说明 Subject Commit 与本次验收的关系。

**Excluded**

- 复制时必须替换：列出本次明确不覆盖的范围，以及使用 `N/A` 的原因（如有）。

## Evidence

复制时必须替换每个条目，不得把本示例视为真实 Evidence。每项至少记录：

- **Type**：CI Run、Artifact、报告、日志或人工复核记录。
- **Locator**：稳定 URL、Run ID、Artifact 名称或仓库路径。
- **Generated At**：`YYYY-MM-DDTHH:mm:ssZ` 形式且 calendar 有效的真实 UTC 时间点。
- **Subject Commit**：证据对应的固定候选 SHA。
- **Digest / Summary**：SHA-256 或可复核摘要。
- **Availability**：可访问性和保留期；缺失、不可访问或过期时必须写 `UNKNOWN`。
- **Owner Authorization**：非 `PENDING` 决定的不可变授权 locator；无可验证 locator 时必须写 `UNKNOWN`。

## Acceptance Checks

| Check | Result | Evidence | Notes |
|---|---|---|---|
| 复制时必须替换：验收项 | `UNKNOWN` | 复制时必须替换：证据定位 | 模板示例，不是真实结果 |
| Owner 决定 | `PENDING` | `N/A` | 等待 Owner 复核 |

Result 只能使用 `PASS`、`FAIL`、`UNKNOWN`、`N/A` 或 `PENDING`：`PASS` 需可复核证据，`FAIL` 表示不满足，`UNKNOWN` 表示证据缺失、不可访问或过期，`N/A` 需范围证明，`PENDING` 仅用于尚未发生的 Owner decision。普通机器检查未完成时按事实写 `UNKNOWN` 或 `FAIL`。

## Residual Risks

| Risk | Impact | Owner | Mitigation / Review Condition |
|---|---|---|---|
| 复制时必须替换：已知限制或生产前置条件 | 复制时必须替换：影响 | 复制时必须替换：责任人 | 复制时必须替换：缓解措施、证据和复核触发条件 |

没有已知风险时，复制后使用明确句子说明复核范围内未发现残余风险；不得留下示例行。

## Decision Reason

`PENDING`

初始记录必须保持 `PENDING`；Owner 决定后，复制时必须替换为实际决定理由、所依据 Evidence 和对残余风险的处置。有 `UNKNOWN` 仍作出 `APPROVE` 时，Owner 必须在此明确接受该风险。终结态事实纠错只能在原文末尾追加带 UTC 时间与 Owner 的 correction，不得删改原理由。

## Follow-up Actions

| Action | Owner | Due / Trigger | Closure Condition | Completion Evidence |
|---|---|---|---|---|
| 等待 Owner 复核 | Owner | 复核完成时 | Owner 已作出决定 | 新 commit 中更新决定字段并追加 Decision History |
| 复制时必须替换：其他后续动作 | 复制时必须替换：责任人 | 复制时必须替换：截止时间或触发条件 | 复制时必须替换：可验证的关闭条件 | 复制时必须替换：完成证据 |

`submittedAt`、非初始 `decisionAt` 和每个 Decision History `At` 都必须是 `YYYY-MM-DDTHH:mm:ssZ` 形式且 calendar 有效的真实 UTC 时间点。

`CONDITIONAL` 的每个动作都必须填写 Owner、Due / Trigger、Closure Condition 和 Completion Evidence。Decision History 初始行的 `At` 必须替换为与 `submittedAt` 一致的时间，`Status`、`Owner` 和 `Commit` 必须保持 `PENDING`，`Reason` 必须替换为非空且不是 `PENDING` 的实际提交说明。后续行的 `Commit` 表示本次变更所基于的前一版 acceptance record commit，必须是 40 位小写 Git SHA，不是 Subject Commit、承载当前行的 commit、`PENDING` 或 `N/A`；`PENDING` 行的 `Owner` 保持 `PENDING`，非 `PENDING` 行填写实际决定责任人。所有 `At` 必须严格递增。非 `PENDING` metadata 的 `decisionAt` 和 `owner` 必须与 Decision History 第一次到达当前 metadata `status` 的行的 `At` 和 `Owner` 一致，后续同状态 correction 不改写首次决定时间或责任人。

Decision History section 只能包含下方连续表格行，表内不得有空行或额外文字。单元格内的 `|` 必须写为 `\|`，详细理由写入 Decision Reason。

## Decision History

| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| REPLACE_SUBMITTED_AT_UTC | PENDING | PENDING | 复制时必须替换：候选已提交 Owner 复核 | PENDING |
