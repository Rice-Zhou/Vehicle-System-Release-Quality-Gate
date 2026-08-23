# AI 开发指南

本仓库面向多个 AI Coding Agent 协作设计。

## 必读顺序

修改代码之前必须依次阅读：

1. `docs/00-architecture-freeze.md`
2. `docs/project-constitution.md`
3. `docs/core-contract.md`
4. `docs/system-architecture.md`
5. `docs/roadmap.md`
6. 相关 ADR
7. 相关实现代码

## 规则

1. 未经 ADR 不得修改 Core Contract。
2. 不得让 AI 成为确定性 Quality Gate 的权威决策者。
3. 不得将 Core Domain 与 Jira 或其他外部系统耦合。
4. 新增外部集成必须使用 Adapter。
5. 新增运行时 Collector 必须使用 Test Agent Plugin 模型。
6. Quality Rule 必须版本化，并由数据/配置驱动。
7. 必须保留对现有 Release Result 的历史解释能力。
8. 不得静默修改 Schema；必须提供 Migration。
9. 新增 Domain 行为必须增加 Test。
10. 优先采用小型、可逆的修改。
11. 功能开发期间不得重构无关代码。
12. 如果请求与冻结架构冲突，必须停止并改为提出 ADR。

## Commit 指南

每个 Commit 应说明一个逻辑变更。

示例：

- `docs: freeze release quality gate architecture`
- `feat(manifest): add artifact integrity validation`
- `feat(traceability): link issue to build`
- `feat(agent): collect ANR evidence`
- `test(quality): add critical ANR blocking rule`

## Agent 完成检查表

声明任务完成前检查：

- 已检查架构
- 已保留 Core Contract
- 已新增/更新 Test
- 必要时已新增 Migration
- 已更新文档
- 架构变更已新增 ADR
- 无无关修改
