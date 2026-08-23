# 双语文档与分支策略

## 分支职责

- `main` 是中文协作入口，说明性正文使用中文。
- `release` 是纯英文兜底与语义复核分支。
- 两条分支表达同一套架构，不允许独立演进。

## 保留的技术内容

VSRQG、Release、Manifest、Evidence、Traceability、Quality Engine、Adapter、Plugin、ADR、TDR、API path、字段、表列、枚举、状态、Rule ID、代码、命令、文件名、版本和产品名称保持原始技术形式。

## 同步规则

1. 先在 `main` 完成一个目的明确的中文修改。
2. 在 `release` 创建语义等价的英文修改，并在提交信息引用中文来源 SHA。
3. 自动检查路径、非 Markdown blob、语言、链接、code fence、标题结构和 inline technical token。
4. 人工检查强制级别、否定条件、权威关系、异常语义、权限、状态机和验收标准。
5. 两种检查均通过后才允许发布或冻结。

## 差异处理

发现语义冲突时标记 `TRANSLATION_DISCREPANCY`，阻止合并、标签和 Design Freeze。依据 V0.1 冻结文件、已批准 ADR/TDR 与 Owner 意图确定正确语义，同时修正两条分支并重新验证。

## 版本治理

Draft 通过提交和 Pull Request 记录来源关系。冻结版本使用成对 annotated tag，例如 `v0.2.0-design-zh` 与 `v0.2.0-design-en`。V0.2 当前仍为 `0.2.0-draft.1`。

不在被配对提交的文件中记录其自身 SHA，避免自引用；commit 配对由标签 message、Pull Request 或 GitHub Release notes 记录。

## 禁止事项

- 不得自动机器翻译后直接发布。
- 不得借翻译修改 V0.1 Core Contract。
- 不得只更新一个语言分支。
- 不得用 force push 消除差异。
- 不得把翻译完成等同于 V0.2 Design Freeze。
