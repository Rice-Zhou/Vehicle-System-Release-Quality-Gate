# VSRQG 双语分支治理设计

- 状态：Approved Design Draft
- 日期：2026-08-21
- 适用仓库：`Rice-Zhou/Vehicle-System-Release-Quality-Gate`
- 中文分支：`main`
- 英文分支：`release`

## 1. 目标

在不改变 V0.1 冻结架构和 V0.2 技术语义的前提下，建立两套可独立阅读、可相互校验、可证明同步的文档：

- `main` 使用中文作为说明性正文语言。
- `release` 使用纯英文。
- 两条分支中的架构概念、约束、字段、状态、关系、版本和验收标准保持语义等价。

双语分支用于降低单一语言理解偏差，不是建立两套独立架构。

## 2. 当前状态与迁移范围

当前远端：

- `main` 包含 V0.1 英文架构基线和 `v0.1.0-architecture` 标签。
- `docs/v0.2-implementation-architecture` 包含 V0.2 中文为主的评审草案。
- `release` 尚不存在。

本次迁移覆盖仓库内所有 Markdown 文档，包括根 README、CHANGELOG、V0.1 冻结文档、ADR、V0.2 文档和 TDR。JSON Schema、脚本、配置、代码及其他非文档文件不翻译，必须在两条分支保持字节一致。

V0.2 在双语迁移后仍为 `0.2.0-draft.1`，不因翻译自动成为 Design Freeze。

## 3. 分支角色

### 3.1 `main`

`main` 是中文协作入口。说明、背景、理由、职责、异常处理和验收描述使用中文。以下内容保持原始技术形式：

- VSRQG、Release、Manifest、Evidence、Traceability、Quality Engine、Adapter、Plugin、ADR、TDR 等稳定术语；
- API path、JSON/YAML 字段、数据库表/列、枚举、状态、规则 ID；
- 代码、命令、文件路径、分支名、标签、checksum、commit SHA；
- 产品或技术专有名称，如 PostgreSQL、Kotlin、Spring Boot、OpenAPI、OIDC、S3。

技术名词第一次出现时可采用“中文解释（English Term）”，之后使用稳定术语。不得为了追求中文比例翻译会破坏契约的标识符。

### 3.2 `release`

`release` 是英文兜底与语义复核分支。所有 Markdown 的自然语言、标题、表格说明、图注、注释和示例说明必须为英文。代码、API、标识符和专有名称保持与 `main` 相同。

`release` 不得自行引入新架构决定。发现中文原文歧义时必须回到 `main` 澄清，再同步英文。

### 3.3 权威与冲突

中文用于日常协作，英文用于独立理解与交叉验证。两者在已发布版本中共同构成验收文档集。

若两者语义冲突：

1. 标记 `TRANSLATION_DISCREPANCY`；
2. 阻止合并、版本标签和 Design Freeze；
3. 根据 V0.1 冻结文件、已批准 ADR/TDR 和 Owner 意图确定正确语义；
4. 同时修正两条分支；
5. 重新执行结构、术语和人工语义评审。

不得规定“冲突时永远以某一种语言覆盖另一种语言”，因为这会削弱兜底价值。

## 4. 目录与结构等价

除本地/平台生成文件外，两条分支必须具有相同仓库路径集合。Markdown 文件一一对应，同一路径只允许语言不同；非 Markdown 文件必须字节一致。

```text
main:    docs/v0.2/02-database-design.md  (中文说明)
release: docs/v0.2/02-database-design.md  (English prose)
```

以下内容属于结构锚点，必须一致：

- 标题层级数量和章节编号；
- 表格行列和实体清单；
- Mermaid 节点 ID、边、基数和状态迁移；
- API method/path、Request/Response 字段；
- PK/FK、表名、列名、约束和枚举；
- Rule ID、状态、版本号和示例数据；
- 本地相对链接目标；
- 验收条目数量和稳定 ID。

自然语言段落不要求逐句直译，但 SHALL/MUST/MUST NOT、默认行为、异常结果、权限、边界和验收含义必须等价。

## 5. 同步工作流

```text
1. 在 main 提交一个有明确目的的中文变更
2. 记录该 main commit SHA
3. 从 release 创建对应翻译工作分支
4. 同步所有非 Markdown 变更，翻译 Markdown
5. 执行自动结构/语言/非文档一致性校验
6. 人工进行术语、否定条件、边界和验收语义评审
7. 合并到 release，并在提交信息引用 main SHA
8. 只有双分支均通过时才创建成对版本标签
```

提交信息建议：

```text
docs(zh): clarify manifest lock acceptance
docs(en): mirror manifest lock acceptance from main@<sha>
```

不得把多个无关架构修改合并成一次翻译提交。不得只在 `release` 修正文档而不回写 `main`。

## 6. 版本与配对记录

发布或冻结版本使用成对 annotated tag：

```text
v0.2.0-design-zh → main commit
v0.2.0-design-en → release commit
```

两条标签的 message 互相记录对方 tag、文档版本、语义评审状态和对应 commit SHA。Draft 阶段使用 Pull Request/提交信息记录来源 SHA，不为每次翻译修改创建标签。

不在被配对提交本身的文件中保存“当前 commit SHA”，因为 commit 内容引用自身 SHA 会形成不可解的自引用。Git tag、GitHub Release notes 和 PR metadata 是配对证据来源。

`CHANGELOG.md` 在两个分支保持条目结构和版本一致，但分别使用对应语言。

## 7. 自动校验

仓库提供同一份 `scripts/verify-language-branches.ps1`，至少验证：

1. `main` 与 `release` 的受控路径集合一致；
2. 非 Markdown 文件 SHA-256 一致（允许列表仅包含分支治理元数据）；
3. `release` Markdown 正文不存在 CJK 字符；
4. `main` 的说明性 Markdown 包含中文，纯 schema/代码文件除外；
5. Markdown 本地链接在各自分支可解析；
6. code fence 数量闭合；
7. API path、枚举、规则 ID、版本号等结构锚点没有丢失；
8. V0.1 冻结概念清单在两种语言中均存在。

语言扫描只作为机械门禁，不声称能够证明语义等价。语义等价必须有人工作为最终验收步骤。

## 8. 人工语义评审清单

逐文件重点比较：

- 否定词和强制级别是否一致；
- Release、Manifest 和 Evidence 的权威关系是否一致；
- Fixed/Included/Verified 是否仍分离；
- 错误、缺失和 UNKNOWN 是否被错误翻译为 PASS/false/0；
- 主键、外键、基数和状态机是否一致；
- 权限允许/禁止项是否一致；
- Timeout、Retry、断连、断电和恢复语义是否一致；
- Quality Rule 条件、阈值、单位和优先级是否一致；
- MVP 与 V0.3 延期边界是否一致；
- ADR/TDR 的选择与被否决方案是否一致。

评审结果为 PASSED、FAILED 或 BLOCKED。只有 PASSED 可创建成对发布标签。

## 9. 初次迁移顺序

1. 以远端 `main` 和 V0.2 Draft 为输入，形成完整中文候选内容。
2. 将 V0.1 说明性文档翻译为中文，保持所有冻结概念和技术标识不变。
3. 将 V0.2 Draft 合并到中文候选，运行 V0.1 冻结差异检查。
4. 经评审后快进/PR 合并到 `main`。
5. 从对应中文内容创建 `release`，将所有 Markdown 翻译为英文。
6. 运行双分支自动校验和人工语义评审。
7. 推送 `release`；V0.2 继续保持 Draft，等待原架构评审。

不能从旧 V0.1 英文 `main` 直接声明其为新的 `release` 完成态，因为它缺少 V0.2 文档且未经过双分支结构校验。

## 10. 失败与恢复

- 翻译遗漏：校验失败，不推送对应发布标签。
- 分支漂移：以最近已通过的标签对为共同基线，分别审计之后的提交，不直接 force reset。
- 错误翻译已合并：使用新修正提交，不重写公开历史。
- 非文档文件不一致：停止翻译流程，先确定是否为遗漏同步或未授权实现差异。
- GitHub 推送部分成功：保留已推送提交，重试另一分支；在两边完成前不创建标签。
- V0.1 语义被翻译改变：立即停止并按架构冲突处理；只有真正修改冻结概念时才提交 ADR Proposal。

## 11. 验收标准

初次双语迁移完成必须满足：

1. 远端存在 `main` 和 `release`；
2. 两条分支均包含 V0.1 与完整 V0.2 Draft 文档集；
3. `main` 说明性正文为中文，技术标识保持稳定；
4. `release` Markdown CJK 字符数为 0；
5. 非 Markdown 文件内容一致；
6. 所有本地 Markdown 链接有效，code fence 闭合；
7. V0.1 冻结概念与 V0.2 状态未改变；
8. 自动结构检查通过；
9. 人工语义评审清单全部 PASSED；
10. GitHub 提交历史保留现有 `main`、V0.1 标签和 V0.2 Draft 来源，不使用 force push；
11. 两条分支的 CHANGELOG 清楚记录双语迁移；
12. V0.2 仍标记为 Draft，直到单独 Architecture Review 批准。

## 12. 修改完成后的下一步执行计划

任何与本项目有关的修改完成后，无论修改是否已提交或推送到 GitHub，完成报告都必须包含“下一步执行计划”。适用范围包括文档、Schema、配置、脚本、代码、测试、分支、标签和其他项目治理内容。

完成报告至少说明：

```text
当前结果：本次完成了什么
Git 状态：未提交 / 已提交未推送 / 已推送（包含分支与 commit）
下一步动作：唯一明确的首要动作
前置条件：需要的评审、权限、输入或依赖；没有则写“无”
验收目标：下一步完成时使用什么证据判断成功
```

规则：

1. 下一步计划是信息和顺序承诺，不自动扩大当前授权范围；需要 Owner 决策或外部权限时必须明确等待。
2. 如果存在多个后续动作，按依赖顺序列出，首项必须可执行且具体。
3. 如果当前修改未提交，必须首先说明是否建议提交以及建议提交边界。
4. 如果已提交但未推送，必须把远端同步和 SHA 验证列为下一步或明确说明为何暂不推送。
5. 如果当前阶段已完成且下一阶段尚未获批，下一步写为“等待 Owner 评审/批准”，不得擅自进入实施。
6. 如果任务受阻，下一步必须是解除阻塞所需的最小动作，并写明阻塞证据。
7. 不允许使用“继续优化”“后续完善”等不可验收表述。

该规则必须在初次双语迁移时写入两条分支各自语言版本的仓库级 `AGENTS.md`，使后续 AI 或人工协作者都能看到。自动检查可验证 `AGENTS.md` 存在并包含固定标题；计划内容的真实性由评审者核对。

## 13. 非目标

- 不翻译代码标识和协议字段；
- 不建立自动机器翻译后直接发布的流水线；
- 不借翻译重写 V0.1 Core Contract；
- 不在本任务中执行 V0.2 Design Freeze；
- 不为双语维护引入独立文档平台、数据库或复杂 CI 基础设施。
