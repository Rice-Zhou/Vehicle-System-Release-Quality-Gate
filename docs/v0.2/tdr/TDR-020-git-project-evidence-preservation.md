# TDR-020 — 使用 Git 保存当前项目证据

- 日期：2026-09-08。
- 状态：Recorded；落实 Owner 已明确的 GitHub 保存方向，限本次项目材料保全。
- 依据：[当前阶段决定](../reviews/2026-09-08-demonstrable-product-priority.md)。

## 选择与理由

使用现有 Git 仓库保存两份原始 M2.5 Evidence ZIP，并沿用已有保全清单的文件名、来源提交、大小及 SHA-256。两个 ZIP 共 3509 bytes；不需要新服务、Git LFS、自动上传流程或数据库改造。仅保留 Actions 链接无法在 Artifact 到期后提供原内容；仅保留本机副本不便从 GitHub 获取。Company 对象存储不属于当前阶段需求。

## 保存与取回

ZIP 位于 [repository-evidence](../../../ops/evidence-archive/m2-5-preparation/README.md)，按原文件名原样提交到两条语言分支。取回时固定包含文件的提交，从该提交检出文件，再与同一提交中的保全清单核对大小、ZIP SHA-256、成员摘要及 summary sidecar。原实施 Subject 与保存文件的后续提交分别记录，不重新打包或用最新 CI 替代原证据。

已有清单是输入摘要的唯一记录；不更改其历史分类或 Company 完成字段。普通 Git 保存不声称管理员无法删除，也不声称满足历史 Company 不可变归档条件。

## 范围、验证与回退

本决定只涉及项目验收材料，不替代 PostgreSQL、运行时大型 Evidence 存储或冻结的历史 Snapshot 语义，不修改 TDR-004、TDR-012、TDR-019 的既有实现契约。Company 工具保留但不要求启用。不引入部署或数据库迁移。

验证源与 Git 内两份 ZIP、全部成员、sidecar 及双语字节一致性，运行现有语言门禁与验收记录校验。内容错误时通过普通后续提交说明并更正，保留原历史；不 force push。未来材料体量、敏感性或业务保留要求发生变化时再评估具体存储，不能据本决定把真实公司数据提交到公开仓库。
