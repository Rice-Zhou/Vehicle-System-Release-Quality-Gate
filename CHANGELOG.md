# 变更记录

本文件记录 VSRQG 可评审、可追溯的架构版本。只有目标明确且可独立审查的变更才形成提交或版本标签。

## 0.2.0-draft.2 — Architecture Review 修订草案 — 2026-08-24

- 记录 Owner 对 OD-01～OD-04 的批准，冻结 Memory Stretch、投入/Cut Line、Pilot RPO/RTO 和双人审批边界。
- 将 Traceability Edge 改为 append-only Revision，并在 Snapshot 中物化完整 Edge Fact，设计关闭 AR-02。
- 移除 Build→Artifact 平行来源，采用 Locked Manifest 派生关系、Composite FK 和不透明 `source_version`，设计关闭 AR-03。
- 补充 Core ER Overview、三个 Domain ER 与 Complete Table Catalog，设计关闭 AR-04。
- 冻结 Rule Missing/Empty/Null/type-error Matrix 与 ERROR 传播，设计关闭 AR-05。
- 补全 RECOVERY_PENDING、Run Completion、迟到 Event/Result 和完整 Agent Versioned Path，设计关闭 AR-06/AR-07。
- 指定 V0.2 Manifest RFC 8785 JCS、`required` 必填与跨实现 digest 规则，设计关闭 AR-08。
- 将 HIGH Evidence 下载改为逐请求鉴权 Proxy/Gateway，设计关闭 AR-09。
- 所有实现验收仍需在 M1～M4 按对应 Gate 执行；本版本保持 Draft，不创建 Design Freeze 标签。

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
