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

## 状态迁移规则

Project Owner 已于 2026-08-24 批准 Architecture Review `V0.2-AR-2026-08-23-01` 及 Owner 验收清单第 5 节的五项残余风险。TDR-001～TDR-010 在同一治理变更中转为 `Accepted`；不得单独改变某一语言或某一份 TDR 的接受状态。

每项决策在出现文末“重新评估触发条件”时重新开 TDR；不得静默改变。
