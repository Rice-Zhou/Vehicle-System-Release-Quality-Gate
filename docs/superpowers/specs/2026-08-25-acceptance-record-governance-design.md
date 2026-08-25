# 验收记录治理设计

## 1. 目标

为 M1 及后续所有架构、实施、发布和里程碑验收建立仓库内可复核记录。记录必须把验收对象、机器证据、人工判断、残余风险和后续动作连接起来，并通过 Git 历史保留责任链。

## 2. 边界

本设计只定义验收记录的结构、生命周期和 Git 治理，不改变 V0.1 冻结架构、Core Contract、ADR 机制或质量引擎，也不授权自动批准、合并、发布或创建 Tag。

机器生成的 `evidence.json` 和 CI Artifact 仍是 Gate 事实来源；验收记录引用它们，但不得复制后改写其结果。Project Owner 仍是最终决定责任人。

## 3. 方案

采用“每个验收对象一份独立记录”的方式：

- `docs/governance/acceptance/README.md` 定义长期治理规则和记录目录。
- `docs/governance/acceptance/template.md` 定义所有验收记录的必填结构。
- `docs/governance/acceptance/records/<date>-<acceptance-id>.md` 保存具体记录。

不采用单一总账，以避免长期合并冲突；不把 GitHub Issue、Release 或聊天记录作为唯一权威来源，以避免平台状态和权限变化削弱可复核性。

## 4. 记录模型

每份记录必须包含以下字段：

| 字段 | 含义 |
|---|---|
| Acceptance ID | 仓库内唯一、稳定的验收标识 |
| Subject | 被验收的版本、里程碑、架构或发布对象 |
| Subject Commit | 被验收的候选提交；不得指向承载本记录的提交 |
| Paired Subject Commit | 双语对应分支的候选提交；不适用时写 `N/A` 并说明原因 |
| Branch | 候选所在分支 |
| Status | `PENDING`、`APPROVE`、`REJECT` 或 `CONDITIONAL` |
| Submitted At | 候选提交验收的 UTC 时间 |
| Owner | 最终决定责任人；待决定时写 `PENDING` |
| Decision At | 最终决定的 UTC 时间；待决定时写 `PENDING` |
| Scope | 本次验收覆盖和明确不覆盖的范围 |
| Evidence | CI、Artifact、报告和 SHA-256 |
| Acceptance Checks | 每项检查的结果与证据定位 |
| Residual Risks | 已知限制、生产前置条件和责任人 |
| Decision Reason | Owner 决定及其依据；待决定时写 `PENDING` |
| Follow-up Actions | 决定后的动作、负责人和停止条件 |
| Decision History | 只追加的状态变化历史 |

`Subject Commit` 与记录提交分离，避免记录提交改变 HEAD 后产生自引用。记录提交只承载治理资料；验收对象始终固定为已完成机器 Gate 的候选 SHA。

## 5. 生命周期

1. 候选完成机器 Gate 后创建记录，状态为 `PENDING`。
2. 提交记录时引用固定的 Subject Commit、CI Run 和 Artifact，不预填批准结论。
3. Owner 复核后，通过新的有意义 Git commit 把状态更新为 `APPROVE`、`REJECT` 或 `CONDITIONAL`，并追加 Decision History。
4. `CONDITIONAL` 必须列出条件、负责人、完成证据和截止或复核触发条件。
5. 后续纠错只能追加更正记录和新的历史行；禁止删除历史、改写 Artifact、压平相关提交或 force-push。
6. 只有 `APPROVE` 且所有强制前置条件满足后，才能进入合并、Tag 或发布动作；这些动作仍需独立授权。

允许的状态转换为：

- `PENDING → APPROVE`
- `PENDING → REJECT`
- `PENDING → CONDITIONAL`
- `CONDITIONAL → APPROVE`
- `CONDITIONAL → REJECT`

已终结的 `APPROVE` 或 `REJECT` 不原地改写；如需推翻，创建新的 Acceptance ID，并引用被替代记录。

## 6. 双语与 Git 治理

- 中文分支的 Markdown 使用中文，英文分支对应 Markdown 使用纯英文。
- 两个分支使用相同路径、Acceptance ID、状态语义和证据结构。
- 分支专属的 Commit、CI Run 和 Artifact 分别记录，不要求 Markdown 字节一致。
- 非 Markdown 文件继续逐字节一致。
- 每次创建记录、作出决定或追加更正都使用独立、有意义的 commit，并推送到对应远端分支。
- 未经 Owner 明确批准，不合并 `main`/`release`，不创建 Tag，不清理候选 worktree。

## 7. M1 首份记录

首份记录使用 Acceptance ID `M1-OWNER-GATE-001`，初始状态为 `PENDING`，验收对象固定为：

- 中文 Subject Commit：`f567e3e366e7cd454d8ccd128dd6a56645b66997`
- 英文 Subject Commit：`586a89932baa9489d8ac946f0a01f2d0dd332b53`
- 中文 CI Run：`32824436148`
- 英文 CI Run：`32824447703`
- 中文 Artifact：`m1-evidence-f567e3e366e7cd454d8ccd128dd6a56645b66997`
- 英文 Artifact：`m1-evidence-586a89932baa9489d8ac946f0a01f2d0dd332b53`

记录必须明确 `m1-acceptance-validator/1` 仅为受控验收 fixture；生产 validator、OIDC、备份保留和运行责任仍属于生产落地前置条件。

## 8. 验证与失败处理

实施时必须验证：

- 所有必填字段存在，状态值和转换合法。
- Subject Commit 与 CI/Artifact 名称一致。
- URL、Artifact SHA-256 和验收项证据可定位。
- `PENDING` 记录不包含伪造的 Owner、决定时间或批准理由。
- 英文文档不包含汉字，双分支非 Markdown 文件逐字节一致。
- 两个分支工作树干净、远端 SHA 与本地一致、CI 成功。

证据缺失时对应检查必须记为 `UNKNOWN`，不能视为通过。发现 Subject Commit、Artifact 或 SHA-256 不一致时停止验收并创建 Finding；不得通过修改目标值消除差异。

## 9. 安全与隐私

验收记录不得包含密码、Token、私钥、数据库凭据、个人敏感信息或未脱敏日志。记录只保存稳定链接、公开标识、摘要、责任角色和必要判断。敏感运行证据保留在受控系统中，并在记录中注明访问责任人和定位方式。
