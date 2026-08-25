# 验收记录治理规范

本目录为里程碑、架构、实施和发布验收保留可复核的仓库内记录。具体记录只陈述已固定验收对象的事实、证据、风险和 Owner 决定。

## 1. 适用范围与架构边界

- 本机制适用于仓库内所有需要 Owner 复核的验收对象。
- 本机制只记录验收事实与 Owner 决定，不修改 Core Contract 或 ADR，也不改变已冻结的 Release-centric、Manifest、Evidence、Traceability 和确定性引擎架构。
- 记录引用机器产生的 Evidence 与 CI Artifact，不复制后改写其结果，不替代任何 Gate、合并或发布机制。

## 2. 目录、命名与标识

目录结构：

- `docs/governance/acceptance/template.md`：标准记录模板，不是具体验收记录。
- `docs/governance/acceptance/records/`：具体记录目录；M1 首份记录由后续独立提交写入。

具体记录必须位于 `records/`，文件名为 `YYYY-MM-DD-<acceptance-id-lowercase>.md`，例如 `2026-08-25-m1-owner-gate-001.md`。文件名日期使用记录首次提交日期，Acceptance ID 在单个分支的 `records/` 目录内必须唯一。

中英配对记录允许使用相同 Acceptance ID；它们依靠 `branch`、`subjectCommit` 和 `pairedSubjectCommit` 交叉标识。配对时，一方的 `pairedSubjectCommit` 应等于另一方的 `subjectCommit`。

## 3. Subject Commit 与记录提交

`Subject Commit` 是被验收的固定候选提交，即 metadata 中的 `subjectCommit`。它必须与承载记录的 record commit 分离，不得指向 record commit 本身，以避免 self-reference。record commit 只对已固定的 Subject Commit 记录判定，不应混入被验收的产品变更。

## 4. 机器校验契约

每份具体记录必须使用 YAML front matter，且包含下列 metadata 字段：

| 字段 | 要求 |
|---|---|
| `acceptanceId` | 稳定的大写字母、数字和连字号标识 |
| `subject` | 被验收的版本、里程碑、架构或发布对象 |
| `subjectCommit` | 被验收的 40 位小写 Git SHA；确实无 Git 对象时可用 `N/A`，并在 Scope 说明 |
| `pairedSubjectCommit` | 配对分支的 40 位小写 Git SHA；不适用双语配对时可用 `N/A`，并在 Scope 说明 |
| `branch` | 验收对象所在分支 |
| `status` | `PENDING`、`APPROVE`、`REJECT` 或 `CONDITIONAL` |
| `submittedAt` | `YYYY-MM-DDTHH:mm:ssZ` 形式的 UTC 时间 |
| `owner` | 决定责任人；初始状态必须为 `PENDING` |
| `decisionAt` | 决定时的 UTC 时间；初始状态必须为 `PENDING` |

正文必须包含且保持下列七个英文二级标题，拼写与大小写不得改动：`Scope`、`Evidence`、`Acceptance Checks`、`Residual Risks`、`Decision Reason`、`Follow-up Actions`、`Decision History`。这些 metadata 字段和标题是机器校验契约，不得翻译、删除或重命名。

## 5. 状态机与决定历史

新记录的初始状态必须为 `PENDING`，且 `owner` 与 `decisionAt` 必须同为 `PENDING`。仅允许以下状态转换：

- `PENDING -> APPROVE`
- `PENDING -> REJECT`
- `PENDING -> CONDITIONAL`
- `CONDITIONAL -> APPROVE`
- `CONDITIONAL -> REJECT`

相同状态的追加行只能用于更正或补充记录，Reason 必须说明修正原因与影响；不得用于规避正常状态转换。其他转换均为非法倒退或跳转，禁止执行。

`APPROVE` 和 `REJECT` 是终结决定，不得原地篡改。`Decision History` 必须只追加：不得删除、排序、覆盖或重写任何历史行。如果只是纠正终结记录中的事实性错误，必须使用新 commit 追加同状态行并说明原因，不得改变原决定。如需推翻终结决定，必须创建新 Acceptance ID 并引用被替代记录。

## 6. Evidence、风险与安全

- Evidence 条目应记录类型、稳定定位、生成时间、摘要或 SHA-256，以及对应 Subject Commit。
- Evidence 缺失、不可访问或过期时，对应验收项必须明确写为 `UNKNOWN`，不得伪造 `PASS`。同时应在 Residual Risks 记录原因、责任人和复核条件。
- 密码、私钥、API key、token、数据库凭据、个人数据、未脱敏日志及其他敏感信息禁止入库。受控证据只记录稳定定位、访问责任人和必要摘要。

## 7. Git 审计治理

- 创建记录、每次状态更新以及每次实质性修正必须使用独立、有意义的 commit，以便定位操作人、时间和原因。
- 禁止使用 force-push、rebase 或压平相关提交的方式改写验收审计历史。
- 中文 main-facing 分支的 Markdown 使用中文，英文 release-facing 分支的 Markdown 必须为纯英文；配对记录的 Acceptance ID 必须对齐。
- 双语分支的 Markdown 按各自语言维护，与分支有关的 Commit、CI Run 和 Artifact 分别记录；所有非 Markdown 文件必须保持字节一致。

## 8. 授权边界

未经 Owner 明确授权，任何人或自动化都不得合并 `main`/`release`、创建 Tag 或启动发布。验收记录处于 `APPROVE` 只表示 Owner 对其 Subject Commit 的验收判定，本身不等于合并、Tag 或发布授权。

## 9. 校验方式

修改 validator 或治理契约后运行：

```powershell
pnpm run test:acceptance
```

`records/` 中存在具体记录时，对全目录运行：

```powershell
pnpm run verify:acceptance
```

任一校验失败都必须保留可见错误并修正根因；不得通过删除历史、改写 Evidence 或降低状态来消除失败。
