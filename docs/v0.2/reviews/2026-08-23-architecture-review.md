# V0.2 Implementation Architecture Review Report

- Review ID：`V0.2-AR-2026-08-23-01`
- Review Date：2026-08-23
- Chinese Baseline：`main@65c869b258c444fb3e43784dc3d87e7f18384ede`
- English Baseline：`release@14b59a2909180bd1bbdcead59699258446ba6ce0`
- Technical Review Status：`CHANGES_REQUIRED`
- Design Freeze Eligibility：`BLOCKED`
- V0.1 ADR Required：`NO`
- Owner Approval：`PENDING`

## 1. 评审目标与边界

本次评审判断 V0.2 是否已经充分回答“如何在不改变 V0.1 的前提下完成工程化实现”，并检查该方案能否由一个主要开发者在约六个月业余时间内形成公司级可信的 Pilot MVP。

评审不批准 V0.2 Design Freeze，不代替 Owner 对 WHY、WHAT、BOUNDARY 和 ACCEPTANCE 的最终签署，也不编写生产代码。

## 2. 结论摘要

V0.2 保持了 Release-centric、Manifest authoritative、Evidence first-class、Traceability、Deterministic Quality Engine、Adapter、Plugin、AI advisory 和 ADR governance。未发现必须修改 V0.1 Core Contract 才能解决的问题。

Modular Monolith、Kotlin/Spring Boot、PostgreSQL、S3-compatible storage、REST/OpenAPI、Agent Pull、PostgreSQL Outbox、Restricted YAML AST、OIDC 和 Containerized VM 的选择与当前需求、规模和六个月约束匹配，技术评审结论为 `RECOMMEND_ACCEPT`。

当前仍存在会导致不同实现者产生不同数据库约束、规则结果、协议状态或发布标签的设计缺口。因此整体结论是 `CHANGES_REQUIRED`，不得创建 Design Freeze 标签。

## 3. 已执行的评审证据

- 14 份 V0.2 专题文档和 10 份 TDR 均存在，TDR 必需章节完整。
- V0.1 冻结概念锚点完整，无 Schema 或 Core Contract 修改。
- 双语分支路径、非 Markdown blob、标题结构、Inline Token、本地链接和 code fence 自动校验 PASS。
- 校验器回归场景 6/6 PASS。
- 中英文关键状态、错误语义、Fixed/Included/Verified、PK/FK、Timeout/Retry/Recovery 和 TDR 替代方案完成抽查。
- 仓库仅有一份可执行 Contract Artifact：`schemas/release-manifest.schema.json`；OpenAPI、Agent Protocol Schema、Quality Rule Schema 和 Fact Catalog 尚不存在。

## 4. Review Gate

| Gate | 结果 | 说明 |
|---|---|---|
| V0.1 冻结架构一致性 | PASS | 未重定义 Core Contract 或权威关系 |
| 双语结构与术语一致性 | PASS | 远端 Pair verifier PASS |
| 技术选择合理性 | PASS WITH CONDITIONS | 10 项 TDR 均建议接受，条件见第 6 节 |
| Database/ER 可直接实施性 | BLOCKED | 关系权威、跨表约束和历史 Edge Version 未闭合 |
| Deterministic Rule 可直接实施性 | BLOCKED | Missing/empty/null 的逐操作符语义未定义 |
| Test/Agent Protocol 可直接实施性 | BLOCKED | Attempt 状态与 Endpoint 形式存在矛盾 |
| 外部 Contract 完整性 | BLOCKED | M0 承诺的机器可验证 Contract Artifact 缺失 |
| 六个月 MVP 范围 | OWNER DECISION | Memory 与 V0.1 Roadmap 边界需确认，投入基线未量化 |
| 运行恢复目标 | OWNER / IT DECISION | RPO/RTO 与公司平台前置条件未确认 |
| Design Freeze | BLOCKED | 需关闭所有 Blocker 并由 Owner 批准 |

## 5. 必须关闭的设计问题

### AR-01 — 外部 Contract Artifact 缺失

- Severity：`BLOCKER`
- Evidence：[14-mvp-implementation-plan.md](../14-mvp-implementation-plan.md) 的 M0 要求 OpenAPI/Schema Draft；[03-api-design.md](../03-api-design.md)、[08-test-agent-protocol.md](../08-test-agent-protocol.md) 和 [11-quality-rule-specification.md](../11-quality-rule-specification.md) 的验收证据依赖机器可验证契约，但仓库没有对应文件。
- Risk：Backend、Agent、CI 和 Rule Engine 可分别实现出互不兼容的契约，文档评审无法阻止字段或错误语义漂移。
- Required Resolution：
  1. 增加 OpenAPI 3.1 Draft；
  2. 增加 Agent Protocol Payload Schema；
  3. 增加 Quality Rule JSON Schema；
  4. 增加 Versioned Fact Catalog；
  5. 增加 V0.2 Manifest Schema，保留 V0.1 Schema 不变；
  6. 在 CI/本地验证 Link、Schema 和 Breaking Diff。
- Closure Evidence：所有示例通过 Schema Validation，OpenAPI/Protocol/Rule/Manifest Contract Test PASS。

### AR-02 — Traceability Snapshot 的历史不可变性未落到 Edge Model

- Severity：`BLOCKER`
- Evidence：[06-traceability-design.md](../06-traceability-design.md) 规定 Snapshot 固化 Edge ID+version；[02-database-design.md](../02-database-design.md) 的 Edge 公共列没有 `version`、不可变 Revision 或 Snapshot Materialization 约束。
- Risk：验证状态或 Confidence 原地更新后，历史 Quality Result 可能读取到不同的追溯事实，破坏 Deterministic Replay。
- Required Resolution：采用 append-only Edge Revision，或在 Snapshot 中物化完整 Edge Fact；禁止 Snapshot 只引用可变行。
- Closure Evidence：更新 Edge 后重放旧 Snapshot，Path、Confidence、Verification Status 和 digest 保持不变。

### AR-03 — Database 存在平行关系和不可执行的跨表 CHECK

- Severity：`BLOCKER`
- Evidence：[02-database-design.md](../02-database-design.md) 同时定义 `artifact.build_id` 和 `build_artifact_edge`；又声明使用 Database CHECK 保证 Evidence 的 Test Result 属于同一 Test Run，而 PostgreSQL CHECK 不能查询其他行；`normalized_issue.source_version` 定义为 bigint，但 [05-issue-adapter-design.md](../05-issue-adapter-design.md) 允许 ETag/外部 Version 标识。
- Risk：Build→Artifact 出现两个 Source of Truth；Evidence 可能关联错误 Release/Run/Result，或不同实现采用不同的 Trigger/Application 逻辑。
- Required Resolution：
  1. Build→Artifact 仅保留 `build_artifact_edge`；
  2. Artifact→Release 只能由 Locked Manifest 派生，不成为第二个 Release 内容入口；
  3. 使用 Composite FK 或明确的 Deferred Constraint Trigger 保证 Evidence、Test Result、Test Run 和 Release 一致；
  4. 将 Source Version 定义为不透明字符串，或明确每种 Adapter 到统一可比较类型的无损映射；
  5. 为真实 PostgreSQL 增加 Constraint Integration Test。
- Closure Evidence：非法跨 Run/Release Evidence 写入由数据库拒绝，重复关系不产生歧义。

### AR-04 — “ER 总图”没有覆盖全部持久化实体

- Severity：`BLOCKER`
- Evidence：[02-database-design.md](../02-database-design.md) 的 ER 图未包含 Device、Agent、Environment Snapshot、Audit Event、Outbox/Job、Idempotency Record、Governance Decision 和 Quality Input Snapshot 的完整 PK/FK/Cardinality。
- Risk：实施阶段仍需重新设计关键表，Implementation Architecture 无法作为数据库验收基线。
- Required Resolution：将当前图明确为 Core ER Overview，并补充按 Domain 拆分的完整 ER、Table Catalog、PK/FK、Unique、Delete/Retention 和 Cardinality。
- Closure Evidence：数据库模型中的每个持久化 Entity 都能映射到可审查的表定义和关系。

### AR-05 — Rule Missing/Empty/Null 语义不完整

- Severity：`BLOCKER`
- Evidence：[11-quality-rule-specification.md](../11-quality-rule-specification.md) 只规定 Missing Path 不是 false，但没有逐操作符定义 `eq`、`ne`、比较、`exists`、`count`、`all`、`any`、`consecutive` 和 Boolean 组合的 Missing/Empty/Null 结果。
- Risk：相同 Snapshot 在不同 Engine 实现中可能得到 PASS、false、0 或 ERROR，直接违反确定性原则。
- Required Resolution：定义三值/错误传播表、Empty Collection 规则、Null 比较、单位转换和数值精度；禁止实现自行默认。
- Closure Evidence：每个操作符具有 value/empty/missing/null/type-error Golden Test，重复执行 digest 一致。

### AR-06 — Test/Attempt 状态与 Run 完成条件矛盾

- Severity：`BLOCKER`
- Evidence：[07-test-architecture.md](../07-test-architecture.md) 的 Attempt State List 不含 `RECOVERY_PENDING`，但断电流程使用该状态；Run 在“all required cases terminal”时完成，未说明仍在运行的 optional Attempt 如何终止。
- Risk：断电恢复和 Run Completion 会产生非法转换、迟到 Result 或 Evaluation 输入变化。
- Required Resolution：把 `RECOVERY_PENDING` 纳入 Attempt State Machine；Run Completion 要求所有已调度 Attempt 终态，或显式取消 optional Attempt 并记录 Result；定义迟到 Event/Result 行为。
- Closure Evidence：断电、恢复窗口到期、optional Case、迟到 Result 的 State Contract Test PASS。

### AR-07 — Agent Endpoint 表达不一致

- Severity：`MAJOR`
- Evidence：[08-test-agent-protocol.md](../08-test-agent-protocol.md) 只有注册 Endpoint 带 `/agent-api/v1`，其他 Endpoint 从 `/agents`、`/commands`、`/attempts` 开始。
- Risk：Server 与 Agent 可生成不同 URL，OpenAPI 也无法确定 Base Path 规则。
- Required Resolution：所有表项统一为完整 Versioned Path，或明确声明表内均相对 `/agent-api/v1` 且保持一致。
- Closure Evidence：Agent OpenAPI/Protocol Contract Test 使用唯一 URL 集合。

### AR-08 — Manifest Canonicalization 与 V0.2 Schema 语义未冻结

- Severity：`MAJOR`
- Evidence：[04-release-manifest-design.md](../04-release-manifest-design.md) 只描述“稳定字段排序和编码”；现有 V0.1 Schema 中 Artifact `required` 可缺省，且不含设计要求的全部 Identity Field。
- Risk：不同 JSON Serializer 生成不同 digest；缺省 `required` 可能被解释为 true、false 或 invalid。
- Required Resolution：指定 JSON Canonicalization 标准、UTF-8 和 SHA-256 输入字节；创建新的 V0.2 Manifest Schema 并显式定义 `required` 缺省语义，不修改 V0.1 Schema。
- Closure Evidence：跨实现 Canonicalization Fixture digest 一致，V0.1/V0.2 Schema Compatibility Test PASS。

### AR-09 — 高敏 Evidence 下载验收与 Presigned URL 能力不匹配

- Severity：`MAJOR`
- Evidence：[12-authentication-design.md](../12-authentication-design.md) 要求高敏下载 URL 不可跨用户复用；标准 S3 Presigned URL 在过期前通常是 Bearer URL，不能绑定应用用户。
- Risk：验收条件无法由已选技术保证，URL 泄露后可能绕过应用权限。
- Required Resolution：普通 Evidence 可使用短期 Presigned URL；高敏 Evidence 使用每次请求鉴权的 Backend Proxy/受控 Gateway，或将验收改为技术上可证明的 Bearer URL 风险控制。
- Closure Evidence：跨用户高敏下载测试失败，URL 不进入 Log/Audit Payload。

### AR-10 — Bilingual Tag 与 Review 状态治理冲突

- Severity：`MAJOR`
- Evidence：[14-mvp-implementation-plan.md](../14-mvp-implementation-plan.md) 使用单一 `v0.2.0-design`；[language-policy.md](../../language-policy.md) 要求 `v0.2.0-design-zh` / `v0.2.0-design-en` 配对标签。10 份 TDR 仍为 `Proposed for V0.2 Review`。
- Risk：Design Freeze 无法证明中英文提交配对，TDR 是否已接受也不明确。
- Required Resolution：统一使用配对 Annotated Tag；Architecture Review 批准后再把 TDR 状态改为 Accepted，并记录 Review ID。
- Closure Evidence：Tag Message 互相引用，TDR 状态和 Review Report 一致。

## 6. TDR 技术评审建议

| TDR | 建议 | 条件 |
|---|---|---|
| TDR-001 Modular Monolith | `RECOMMEND_ACCEPT` | 保持模块依赖测试和单一数据所有者 |
| TDR-002 Kotlin/Spring Boot | `RECOMMEND_ACCEPT` | 实施时记录具体 LTS JDK 和支持周期 |
| TDR-003 PostgreSQL | `RECOMMEND_ACCEPT` | 关闭 AR-02、AR-03、AR-04 |
| TDR-004 S3-compatible Storage | `RECOMMEND_ACCEPT` | 关闭 AR-09 并保留 Inventory Reconciliation |
| TDR-005 REST/OpenAPI | `RECOMMEND_ACCEPT` | 交付 AR-01 的 OpenAPI Draft |
| TDR-006 Agent Pull | `RECOMMEND_ACCEPT` | 关闭 AR-06、AR-07 |
| TDR-007 PostgreSQL Outbox | `RECOMMEND_ACCEPT` | 保留有界重试、Dead Letter 和幂等测试 |
| TDR-008 Restricted YAML AST | `RECOMMEND_ACCEPT` | 关闭 AR-01、AR-05 |
| TDR-009 OIDC/Service Identity | `RECOMMEND_ACCEPT` | 确认公司 IdP、Secret Manager 和 Break-glass 流程 |
| TDR-010 Containerized VM | `RECOMMEND_ACCEPT` | Owner/IT 确认 RPO/RTO 和目标平台 |

本次建议不改变 TDR 状态；只有 Owner 批准 Architecture Review 后才能由 Proposed 改为 Accepted。

## 7. Owner 必须确认的 Boundary / Acceptance

### OD-01 — Memory 是否进入六个月 MVP

- Conflict：[roadmap.md](../../roadmap.md) 把 Memory/CPU/FPS 放在 Phase 2；V0.2 在 Domain、Test、Evidence 和 M3 中把基础 Memory 作为 MVP。
- Recommendation：Crash、ANR、Log、Screenshot 保持 MVP Mandatory；保留 Memory Interface、Fact 和 Rule Example，但把真实 Memory Collector 设为 Stretch Goal。只有 M1/M2 按期且真实台架稳定时进入 M3。

### OD-02 — 业余开发投入基线与 Cut Line

- Gap：计划有 24 周，但没有每周可用工时和延期触发规则，无法判断六个月承诺是否可信。
- Recommendation：以每周 10–12 小时、20% Contingency 为规划基线。任一关键里程碑延误超过两周时，先删除 UI、趋势分析、Memory Stretch、自动外部 Issue 写回和非必需报表，不削弱 Manifest、Evidence、Traceability、Deterministic Quality、Auth/Audit 和恢复。

### OD-03 — Pilot RPO/RTO

- Gap：[13-deployment-design.md](../13-deployment-design.md) 要求实测但未给验收目标。
- Recommendation：Pilot 初始目标为 `RPO ≤ 1 hour`、`RTO ≤ 4 hours`；若公司基础设施不能满足，Owner 与 IT 必须记录替代值和风险接受。

### OD-04 — 高风险操作双人原则

- Gap：Rule Publish 和 BLOCK Override 当前允许通过流程补偿单人操作。
- Recommendation：Pilot 可以使用外部审批记录，但进入公司实际项目前，Production Rule Publish 和 BLOCK Override 必须实现双人批准或公司等价审批控制。

## 8. 六个月可落地性判断

在 Modular Monolith、单 PostgreSQL、单 Object Storage、一个真实台架、顺序执行、固定 RBAC、受限 Rule 和无复杂 UI 的边界下，V0.2 可以作为 Pilot MVP 落地。

若同时要求生产级双 Adapter、Memory Collector、完整 UI、自动审批、复杂报表和公司级高可用，则一个主要开发者以业余时间在六个月内完成的风险不可接受。OD-01 和 OD-02 是排期可信度的必要输入。

## 9. 关闭顺序

1. Owner 确认 OD-01 至 OD-04。
2. 修订 Database/ER 与 Traceability 不变量，关闭 AR-02、AR-03、AR-04。
3. 修订 Rule、Manifest、Test/Agent 和 Evidence Security，关闭 AR-05 至 AR-09。
4. 交付并验证机器可执行 Contract Artifact，关闭 AR-01。
5. 统一 Tag/TDR/Review 状态，关闭 AR-10。
6. 重新执行双语 Pair Verification、Contract Test 和 Architecture Review。
7. Owner 明确批准后，才创建配对 Design Freeze Tag。

## 10. Owner 签署区

```text
Review Decision: APPROVED / APPROVED_WITH_CONDITIONS / REJECTED
OD-01 Memory Scope:
OD-02 Capacity Baseline:
OD-03 RPO/RTO:
OD-04 Two-Person Approval:
Accepted Residual Risks:
Owner:
Date:
```

当前签署状态为 `PENDING`。在签署前，V0.2 保持 `0.2.0-draft.1`。
