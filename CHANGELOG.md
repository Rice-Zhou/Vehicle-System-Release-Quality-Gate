# 变更记录

本文件记录 VSRQG 可评审、可追溯的架构版本。只有目标明确且可独立审查的变更才形成提交或版本标签。

## 0.2.0-draft.1 — Implementation Architecture 评审草案 — 2026-08-21

- 新增 14 份 V0.2 实施架构与技术决策专题文档及总索引。
- 细化 Domain、Database、API、Manifest、Adapter、Traceability、Test、Agent、Evidence、Quality、Authentication、Deployment 和 MVP 验收计划。
- 新增 10 项 TDR，对 Modular Monolith、Kotlin/Spring Boot、PostgreSQL、S3、REST/OpenAPI、Agent Pull、PostgreSQL Outbox、YAML Rule、OIDC 和容器化 VM 部署进行论证。
- 明确 Technology Decision Delegation、三条架构红线、六个月业余开发边界及 GitHub 版本治理。
- 建立配对的中文 `main` 与英文 `release` 文档治理、自动校验和语义评审流程。
- 本版本为评审草案，未执行 V0.2 Design Freeze。

## 0.1.0 — 架构基线 — 2026-08-21

- 冻结 Release-centric 核心架构与 Core Contract。
- 确立 Release Manifest 的权威地位。
- 确立 Evidence、Traceability、Deterministic Quality Engine、Adapter、Plugin 与 ADR 治理机制。
- 提供初始 Release Manifest JSON Schema 和 V0.2 演进边界。

## 版本治理

- V0.1 冻结架构只允许通过获批 ADR 修改。
- V0.2 设计在评审通过前使用 Draft 标识，不得标记为 Design Freeze。
- 每次提交只包含一个可说明、可审查的逻辑变更。
- 发布标签只指向已完成对应评审的提交。
