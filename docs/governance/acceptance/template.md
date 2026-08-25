---
acceptanceId: EXAMPLE-REPLACE-001 # 复制模板时必须替换
subject: "复制时必须替换：验收对象名称"
subjectCommit: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa # 复制模板时必须替换
pairedSubjectCommit: bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb # 复制模板时必须替换
branch: "复制时必须替换：feat/example-branch"
status: PENDING # 初始记录固定值，Owner 决定时必须替换
submittedAt: 2000-01-01T00:00:00Z # 复制模板时必须替换
owner: PENDING # 初始记录固定值，Owner 决定时必须替换
decisionAt: PENDING # 初始记录固定值，Owner 决定时必须替换
---

# 验收记录模板

> 本文件位于 `template.md`，不在 `records/` 目录中，因此 validator 不会将它当作具体验收记录。上述内容全部是模板示例，不表示任何真实验收结果。
>
> 复制模板时必须替换 `acceptanceId`、`subject`、`subjectCommit`、`pairedSubjectCommit`、`branch` 和 `submittedAt` 的所有示例值，并删除本说明。`subjectCommit` 与 `pairedSubjectCommit` 中的合成 40 位 SHA 必须替换为真实固定 SHA；仅当确实没有 Git 对象或不适用双语配对时，对应字段才允许写 `N/A`，并必须在 Scope 说明原因。
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
- **Generated At**：`YYYY-MM-DDTHH:mm:ssZ` 形式的 UTC 时间。
- **Subject Commit**：证据对应的固定候选 SHA。
- **Digest / Summary**：SHA-256 或可复核摘要。
- **Availability**：可访问性和保留期；缺失、不可访问或过期时必须写 `UNKNOWN`。

## Acceptance Checks

| Check | Result | Evidence | Notes |
|---|---|---|---|
| 复制时必须替换：验收项 | `UNKNOWN` | 复制时必须替换：证据定位 | 模板示例，不是真实结果 |
| Owner 决定 | `PENDING` | 不适用 | 等待 Owner 复核 |

Result 仅使用已定义的检查结果语义。Evidence 缺失、不可访问或过期时必须写 `UNKNOWN`，不得写为 `PASS`。

## Residual Risks

| Risk | Impact | Owner | Mitigation / Review Condition |
|---|---|---|---|
| 复制时必须替换：已知限制或生产前置条件 | 复制时必须替换：影响 | 复制时必须替换：责任人 | 复制时必须替换：缓解措施、证据和复核触发条件 |

没有已知风险时，复制后使用明确句子说明复核范围内未发现残余风险；不得留下示例行。

## Decision Reason

`PENDING`

初始记录必须保持 `PENDING`；Owner 决定后，复制时必须替换为实际决定理由、所依据 Evidence 和对残余风险的处置。

## Follow-up Actions

| Action | Owner | Due / Trigger | Completion Evidence |
|---|---|---|---|
| 等待 Owner 复核 | Owner | 复核完成时 | 新 commit 中更新决定字段并追加 Decision History |
| 复制时必须替换：其他后续动作 | 复制时必须替换：责任人 | 复制时必须替换：截止时间或触发条件 | 复制时必须替换：完成证据 |

Decision History 初始行的 At 必须替换为与 `submittedAt` 一致的真实 UTC 时间，Reason 必须替换为实际提交说明。初始 Status、Owner 和 Commit 保持 `PENDING`。每次决定、状态更新或实质性修正都必须通过新 commit 只追加一行，不得删除或重写旧行。

## Decision History

| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| 2000-01-01T00:00:00Z | PENDING | PENDING | 复制时必须替换：候选已提交 Owner 复核 | PENDING |
