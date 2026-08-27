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

`subjectCommit: N/A` 仅限 subject 本身不是 Git 对象的情形；此时 Evidence 必须提供不可变 locator、version 和 digest。未知、未固定或未核实的 commit 不得用 `N/A` 代替。

## 4. 机器校验契约

每份具体记录必须使用 YAML front matter，且包含下列 metadata 字段：

| 字段 | 要求 |
|---|---|
| `acceptanceId` | 稳定的大写字母、数字和连字号标识 |
| `subject` | 被验收的版本、里程碑、架构或发布对象 |
| `subjectCommit` | 被验收的 40 位小写 Git SHA；仅 subject 不是 Git 对象时可用 `N/A` |
| `pairedSubjectCommit` | 配对分支的 40 位小写 Git SHA；不适用双语配对时可用 `N/A`，并在 Scope 说明 |
| `branch` | 验收对象所在分支 |
| `status` | `PENDING`、`APPROVE`、`REJECT` 或 `CONDITIONAL` |
| `submittedAt` | `YYYY-MM-DDTHH:mm:ssZ` 形式且 calendar 有效的真实 UTC 时间点 |
| `owner` | 决定责任人；初始状态必须为 `PENDING` |
| `decisionAt` | 决定时 calendar 有效的真实 UTC 时间点；初始状态必须为 `PENDING` |

正文必须包含且保持下列七个英文二级标题，拼写与大小写不得改动：`Scope`、`Evidence`、`Acceptance Checks`、`Residual Risks`、`Decision Reason`、`Follow-up Actions`、`Decision History`。这些 metadata 字段和标题是机器校验契约，不得翻译、删除或重命名。

Acceptance Checks 的 Result 只能使用下列枚举：

| Result | 语义 |
|---|---|
| `PASS` | 有可复核 Evidence 证明检查满足要求 |
| `FAIL` | 检查不满足要求 |
| `UNKNOWN` | Evidence 缺失、不可访问或过期；不得改写为 `PASS`，且必须进入 Residual Risks |
| `N/A` | Scope 有证据证明检查不适用；不得用于代替缺失的 Evidence |
| `PENDING` | 仅用于尚未发生的 Owner decision |

普通机器检查未完成时，根据当前事实写 `UNKNOWN` 或 `FAIL`，不得写 `PENDING`。存在 `UNKNOWN` 仍作出 `APPROVE` 时，Owner 必须在 Decision Reason 明确接受该风险。`CONDITIONAL` 必须在 Follow-up Actions 逐项写明责任人、截止时间或触发条件、关闭条件和完成 Evidence。

## 5. 状态机与决定历史

新记录的初始状态必须为 `PENDING`，且 `owner` 与 `decisionAt` 必须同为 `PENDING`。仅允许以下状态转换：

- `PENDING -> APPROVE`
- `PENDING -> REJECT`
- `PENDING -> CONDITIONAL`
- `CONDITIONAL -> APPROVE`
- `CONDITIONAL -> REJECT`

相同状态的追加行只能用于更正或补充记录，Reason 必须说明修正原因与影响；不得用于规避正常状态转换。其他转换均为非法倒退或跳转，禁止执行。

Decision History 的 Commit 固定表示“本次状态变更或同状态修正所基于的前一版 acceptance record commit（parent record commit）”，不是 Subject Commit，也不是承载当前行的 record commit。初次 `PENDING` 因尚无 prior record commit 而填 `PENDING`；承载当前行的 commit 通过 Git history/blame 获得。

Decision History 每个 data row 的 `At`、`Status`、`Owner`、`Reason` 和 `Commit` 均不得为空，`At` 必须是 calendar 有效的真实 UTC 时间点并严格递增。首行的 `At` 必须等于 metadata `submittedAt`，且 `Status`、`Owner`、`Commit` 均为 `PENDING`，`Reason` 必须非空且不得为 `PENDING`。后续行的 `Commit` 必须是其 parent record commit 的 40 位小写 Git SHA，不得使用 `PENDING` 或 `N/A`；`PENDING` 行的 `Owner` 必须为 `PENDING`，非 `PENDING` 行的 `Owner` 必须是实际决定责任人。非 `PENDING` metadata 的 `decisionAt` 和 `owner` 必须分别等于 Decision History 中第一次到达当前 metadata `status` 的行的 `At` 和 `Owner`；后续同状态 correction 不得改写该首次决定时间或责任人。

`APPROVE` 和 `REJECT` 是终结决定，不得原地篡改。`Decision History` 必须只追加：不得删除、排序、覆盖或重写任何历史行。终结态事实纠错时，metadata `status` 不变，`decisionAt` 保留最初终结决定时间，不得删改原 Decision Reason 或 History；必须使用新 commit，在 Decision Reason 末尾追加带 UTC 时间和 Owner 的 correction，再追加同状态 History 行，Commit 填被修正的前一版 record commit。如需推翻终结决定，必须创建新 Acceptance ID 并引用被替代记录，不得状态倒退。

## 6. Evidence、风险与安全

- Evidence 条目应记录类型、稳定定位、生成时间、摘要或 SHA-256，以及对应 Subject Commit。
- Evidence 缺失、不可访问或过期时，对应验收项必须明确写为 `UNKNOWN`，不得伪造 `PASS`。同时应在 Residual Risks 记录原因、责任人和复核条件。
- validator 只校验结构，不认证 Owner 身份或授权真实性。非 `PENDING` 决定必须在 Evidence 给出不可变 Owner authorization locator，优先使用 protected PR approval URL、verified signed commit 或受控审批系统 record ID；非 Owner 代录同样必须提供。无可验证 locator 时，授权检查写 `UNKNOWN`，不得声称身份已被机器验证。
- 密码、私钥、API key、token、数据库凭据、个人数据、未脱敏日志及其他敏感信息禁止入库。受控证据只记录稳定定位、访问责任人和必要摘要。

### 6.1 Evidence Archive 验收扩展

涉及 Company Evidence Archive 的记录，每次验收都必须有独立记录并引用当次不可变 Evidence，不得沿用上次报告推导本次结果。除通用字段外，必须记录：

- 每个 payload 与 receipt 的稳定 locator、精确 `versionId`、SHA-256、size 和访问责任方；不得使用 latest-only 引用。
- `accessOwner`、retention policy、实际保护模式和 retain-until；缺少 Provider 实测结果时不得写 `PASS`。
- Release Engineer 与 Independent Verifier 的 Provider identity fingerprint，以及两者不同的复核结果；只保存 fingerprint，不保存原始 principal、ARN、account、subject、user ID 或 session name。
- `archive-report.json`、`recovery-report.json` 和零字节 completion marker 的仓库 Git locator 与 digest。marker 必须与 recovery report 相邻，文件名包含该报告原始字节的 SHA-256。
- 二次 identity 见证的受控 locator、见证责任人和时间；见证只确认职责分离，不能替代 Provider attestation。

Pilot/CI `TEST_FIXTURE` 只能作为工具链 Evidence，不能作为 Company 验收 Evidence。没有真实 Company 归档、独立 exact-version 恢复和离线校验时，不创建本类实际验收记录，不关闭既有 `CONDITIONAL` 条件。新记录仍从 `PENDING` 开始，本节不增加或修改任何状态枚举。

上述 locator 均使用稳定 URL、Provider object identity 或仓库相对路径；禁止记录本地绝对路径、临时 URL、presigned query、credential 或原始 principal。

## 7. Git 审计治理

- 创建记录、每次状态更新以及每次实质性修正必须使用独立、有意义的 commit，以便定位操作人、时间和原因。
- 禁止使用 force-push、rebase 或压平相关提交的方式改写验收审计历史。
- 中文 main-facing 分支的 Markdown 使用中文，英文 release-facing 分支的 Markdown 必须为纯英文；配对记录的 Acceptance ID 必须对齐。
- 双语分支的 Markdown 按各自语言维护，与分支有关的 Commit、CI Run 和 Artifact 分别记录；所有非 Markdown 文件必须保持字节一致。

## 8. 授权边界

未经 Owner 明确授权，任何人或自动化都不得合并 `main`/`release`、创建 Tag 或启动发布。终结决定进入受保护分支时，应由 Owner、CODEOWNERS 或等价机制审批。验收记录处于 `APPROVE` 只表示 Owner 对其 Subject Commit 的验收判定，本身不等于合并、Tag 或发布授权。

## 9. 校验方式

机器校验实际覆盖：YAML front matter 与必填字段格式、状态、calendar 有效的真实 UTC 时间点、固定 headings、Decision History 表结构、逐行字段约束、严格递增、首次决定行与 metadata 一致性、transitions，以及 `records/` 内重复 Acceptance ID。运行：

```powershell
pnpm run test:acceptance
```

`records/` 中存在具体记录时，对全目录运行：

```powershell
pnpm run verify:acceptance
```

以下项目必须人工或跨分支复核，上述命令不会对它们提供保证：

- 文件名日期，SHA 真实存在且与 `branch` 对应，Evidence 可访问性与 Artifact digest。
- `UNKNOWN`/`N/A` 语义，Decision History 的只追加性（使用 Git diff/history），Owner 身份与授权真实性。
- 中英 Acceptance ID 与 paired SHA，Markdown 语言，以及非 Markdown 文件字节一致。

当前人工/跨分支复核的唯一操作入口是本 README 清单与 Task 6 的语言/字节命令，不宣称存在其他自动工具。英文分支语言检查：

```powershell
rg -n "[\p{Han}]" README.md docs
```

中英 worktree 分别运行下列命令，比较输出中的文件集合与 SHA-256：

```powershell
git ls-files | Where-Object { [IO.Path]::GetExtension($_) -ne '.md' } | Sort-Object | ForEach-Object { "$(Get-FileHash -Algorithm SHA256 -LiteralPath $_ | Select-Object -ExpandProperty Hash) $_" }
```

任一校验失败都必须保留可见错误并修正根因；不得通过删除历史、改写 Evidence 或降低状态来消除失败。
