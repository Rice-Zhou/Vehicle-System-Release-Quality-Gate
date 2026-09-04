# Technology Decision Records

TDR 记录 V0.1 冻结边界内的可替换实施决策。TDR 不具有修改 Core Contract 的权力；若决策触及 Release、Manifest、Evidence、Traceability、Quality 语义、模块责任或权威来源，必须转为 ADR Proposal。

| TDR | 决策 | 状态 |
|---|---|---|
| [TDR-001](TDR-001-modular-monolith.md) | Backend 采用模块化单体 | Accepted |
| [TDR-002](TDR-002-kotlin-spring-boot.md) | Backend 采用 Kotlin/JVM + Spring Boot | Accepted |
| [TDR-003](TDR-003-postgresql.md) | 结构化数据使用 PostgreSQL | Accepted |
| [TDR-004](TDR-004-s3-compatible-evidence-storage.md) | Evidence Payload 使用 S3 兼容存储 | Accepted |
| [TDR-005](TDR-005-rest-openapi.md) | 外部 API 使用 REST + OpenAPI 3.1 | Accepted |
| [TDR-006](TDR-006-agent-pull-protocol.md) | Agent 主动长轮询领取任务 | Accepted |
| [TDR-007](TDR-007-postgresql-job-outbox.md) | PostgreSQL Job/Outbox 代替 Broker | Accepted |
| [TDR-008](TDR-008-versioned-yaml-quality-rules.md) | YAML + 受限 AST 定义规则 | Accepted |
| [TDR-009](TDR-009-oidc-and-service-identities.md) | OIDC + 独立服务身份 | Accepted |
| [TDR-010](TDR-010-containerized-vm-deployment.md) | 容器化 VM/小型平台部署 | Accepted |
| [TDR-011](TDR-011-pilot-company-deployment-profiles.md) | Pilot / Company 双模式部署配置 | Accepted |
| [TDR-012](TDR-012-evidence-archive-acceptance-operations.md) | Evidence Archive 验收运维操作 | Accepted |
| [TDR-013](TDR-013-controlled-local-file-identity.md) | 受控本地文件身份与 Windows 参数桥 | Accepted |
| [TDR-014](TDR-014-bounded-jira-cli-pilot-adapter.md) | 有界 Jira CLI Pilot Adapter 与 Fixture Contract | Accepted |
| [TDR-015](TDR-015-versioned-jira-mapping-and-adapter-authority.md) | 版本化 Jira Mapping Profile 与 Adapter Version Authority | Accepted |
| [TDR-016](TDR-016-materialized-release-issue-snapshot.md) | 物化 Release Issue Snapshot 与 Sync Observation Ledger | Accepted |
| [TDR-017](TDR-017-build-provenance-envelope.md) | Build Provenance Envelope 与 PostgreSQL Typed Edge Revision | Accepted |
| [TDR-018](TDR-018-postgresql-async-traceability-snapshot.md) | PostgreSQL 异步 Traceability Verification 与不可变 Snapshot | Proposed |

## 状态迁移规则

Project Owner 已于 2026-08-24 批准 Architecture Review `V0.2-AR-2026-08-23-01` 及 Owner 验收清单第 5 节的五项残余风险。TDR-001～TDR-010 在同一治理变更中转为 `Accepted`；不得单独改变某一语言或某一份 TDR 的接受状态。

Project Owner 已于 2026-08-28 批准 `M2-KD-2026-08-28-01` Written Spec Review，授权 `TDR-014` 在双语治理提交中转为 `Accepted`。该接受只授权 M2 实施规划，不授权生产代码、Jira 写操作、merge、Tag、release 或生产部署。

Project Owner 已于 2026-09-01 批准 `M2-KD-2026-09-01-01` Written Spec Review，授权 `TDR-015` 在双语治理提交中转为 `Accepted`。该接受只授权创建 Implementation Plan，不授权生产代码、Migration、真实 Jira 查询、Jira 写操作、Company、merge、Tag、release 或 production deployment。

Project Owner 已于 2026-09-02 批准 `M2-KD-2026-09-02-01` Written Spec Review，授权 `TDR-016` 在双语治理提交中转为 `Accepted`。该接受只授权创建 Implementation Plan，不授权生产代码、Migration、真实 Jira、Company、M2.4、merge、Tag、release 或 production deployment。

Project Owner 已于 2026-09-03 通过 `APPROVE M2-KD-2026-09-03-01 WRITTEN SPEC REVIEW` 批准书面规范，授权 `TDR-017` 在双语治理提交中转为 `Accepted`。该接受只允许创建独立 Implementation Plan，不授权生产代码、Migration、真实 Jira、真实公司 CI、Company、M2.5、merge、Tag、release 或 production deployment。

Project Owner 已于 2026-09-04 批准 `M2-KD-2026-09-04-01` 的设计方向；`TDR-018` 保持 `Proposed`，等待 Written Spec Review。设计方向批准不授权 Implementation Plan、生产代码、Migration、真实 Jira/CI、Company、M3、merge、Tag、release 或 production deployment。

每项决策在出现文末“重新评估触发条件”时重新开 TDR；不得静默改变。
