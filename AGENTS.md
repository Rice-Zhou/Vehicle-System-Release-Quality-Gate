# VSRQG 仓库协作规则

## 必读顺序

修改项目之前必须依次阅读：

1. `docs/00-architecture-freeze.md`
2. `docs/project-constitution.md`
3. `docs/core-contract.md`
4. `docs/system-architecture.md`
5. `docs/roadmap.md`
6. `docs/ai-development-guide.md`
7. 相关 ADR、TDR 与实施设计

## 冻结架构

V0.1 Core Contract、Release-centric architecture、Manifest authority、Evidence、Traceability、Deterministic Quality Engine、Adapter、Plugin 和 ADR governance 已冻结。任何修改这些概念、责任、权威来源或决定语义的方案都必须停止直接实施并提交 ADR Proposal。

## 分支语言

- `main`：说明性正文使用中文；技术术语、代码、API、字段、枚举、状态、文件名和产品名称保持原始形式。
- `release`：所有 Markdown 说明性内容使用英文。
- 两条分支必须保持目录、结构锚点、技术语义与非 Markdown 文件一致。
- 发现语义差异时标记 `TRANSLATION_DISCREPANCY`，在解决前不得发布或冻结。

## 修改与验证

- 每次提交只包含一个有意义且可独立审查的逻辑变更。
- 不得使用 force push 覆盖公开历史。
- 能自动验证的内容必须验证；失败、缺失和 UNKNOWN 不得静默转换为成功。
- V0.2 在 Architecture Review 批准前始终保持 Draft。

## 修改完成后的下一步执行计划

任何项目修改完成后，最终报告必须包含：

```text
当前结果：本次完成了什么
Git 状态：未提交 / 已提交未推送 / 已推送（包含分支与 commit）
下一步动作：唯一明确的首要动作
前置条件：需要的评审、权限、输入或依赖；没有则写“无”
验收目标：下一步完成时使用什么证据判断成功
```

下一步计划不扩大当前授权。需要 Owner 批准或外部权限时必须明确等待；禁止使用“继续优化”“后续完善”等不可验收表述。
