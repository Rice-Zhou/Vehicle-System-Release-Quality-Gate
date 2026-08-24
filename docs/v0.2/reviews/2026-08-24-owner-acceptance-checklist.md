# V0.2 Implementation Architecture Owner 最终验收清单

- Package Status：`APPROVED_FOR_DESIGN_FREEZE`
- Architecture Review ID：`V0.2-AR-2026-08-23-01`
- Design Version：`0.2.0`
- Chinese Candidate：`docs/v0.2-architecture-review`
- English Candidate：`docs/v0.2-architecture-review-en`
- Decision Owner：Project Owner
- Prepared Date：2026-08-24

## 1. 验收目的与决策边界

本清单供项目 Owner 验收 V0.2 Implementation Architecture 是否充分回答 HOW、TECHNOLOGY、IMPLEMENTATION、TEST 和 OPTIMIZATION，并允许进入 M1 实施。它不修改 V0.1，不代表生产代码、真实设备、部署环境或 M1～M4 实现验收通过。

Owner 继续负责 WHY、WHAT、BOUNDARY 和 ACCEPTANCE。技术评审只证明方案可实施、可测试、可恢复和可审计，不替代 Owner 的最终业务决定。

## 2. 冻结架构一致性

| 验收项 | 证据 | 技术结论 |
|---|---|---|
| Release-centric architecture | Release、Manifest、Test Run、Evidence、Quality Result 全链路设计 | PASS |
| Manifest authority | 独立 V0.2 Schema、Lock 和不可变 Revision | PASS |
| Evidence first-class | 独立 Metadata/Payload、访问控制和生命周期 | PASS |
| Traceability | append-only Edge Revision 与物化 Snapshot | PASS |
| Deterministic Quality Engine | Versioned Fact、Rule、Input Snapshot 和 Replay | PASS |
| Adapter / Plugin boundary | 外部 Issue 使用 Adapter，Collector 使用 Plugin | PASS |
| AI advisory only | AI 不进入权威 Quality Result 计算 | PASS |
| ADR governance | 未发现必须修改 V0.1 的冲突 | PASS；V0.1 ADR Required=`NO` |

## 3. Architecture Review Finding

| Finding | 技术状态 | 后续实现验收 |
|---|---|---|
| AR-01 Machine-executable Contract | DESIGN_RESOLVED | M1～M4 Producer/Consumer Contract Test |
| AR-02 Traceability Snapshot Immutability | DESIGN_RESOLVED | M2 PostgreSQL Replay Test |
| AR-03 Database Source of Truth / Constraint | DESIGN_RESOLVED | M1/M2 PostgreSQL Constraint Test |
| AR-04 Complete ER / Table Catalog | DESIGN_RESOLVED | M1 Migration/Schema Export Review |
| AR-05 Rule Missing/Null/Error Semantics | DESIGN_RESOLVED | M4 Operator Matrix / Replay Test |
| AR-06 Test/Attempt State Consistency | DESIGN_RESOLVED | M3 Failure/Recovery State Test |
| AR-07 Agent Versioned Endpoint | DESIGN_RESOLVED | M3 Protocol Contract Test |
| AR-08 Manifest Canonicalization | DESIGN_RESOLVED | M1 Cross-implementation Digest Test |
| AR-09 HIGH Evidence Access | DESIGN_RESOLVED | M3 Cross-user Security Test |
| AR-10 Bilingual Tag / Review Governance | GOVERNANCE_READY | Owner 批准后的状态迁移、合并与配对 Tag 验证 |

## 4. Technology Decision 建议

TDR-001～TDR-010 的技术评审结论均为 `RECOMMEND_ACCEPT`。在 Owner 批准前，所有 TDR 必须保持 `Proposed for V0.2 Review`；批准后才可改为 `Accepted`，并记录 Architecture Review ID、Owner 决定日期和接受的残余风险。

关键选择保持六个月边界：Modular Monolith、Kotlin/Spring Boot、PostgreSQL、S3-compatible Object Storage、REST/OpenAPI、Agent Pull、PostgreSQL Outbox、Restricted YAML AST、OIDC/Service Identity 和 Containerized VM。未引入 Kafka、Kubernetes、Redis、图数据库、通用 Workflow 或 Microservice 拆分。

## 5. Owner 需要接受或退回的残余风险

1. Memory 保持 Interface/Fact/Rule Example，真实 Collector 是 Stretch Goal。
2. Pilot RPO 24 小时、RTO 4 小时仍需公司 IT 环境验证；不满足时必须记录替代目标和风险。
3. 公司 IdP、Secret Manager、Object Storage 和目标 VM/平台的产品选择在实施期验证。
4. 当前关闭的是 Design Finding，不是 M1～M4 的生产实现验收。
5. 一个主要开发者、24 周、每周 10～12 小时的容量基线要求严格执行 Cut Line。

## 6. Owner 决策选项

### APPROVE

批准 `V0.2-AR-2026-08-23-01` 和上述残余风险，授权按第 7 节完成状态迁移、双语分支合并、配对 Design Freeze Tag，并进入 M1。批准不授权改变 V0.1 Core Contract。

### RETURN_WITH_FINDINGS

退回并列出具体 Finding、涉及的 WHY/WHAT/BOUNDARY/ACCEPTANCE 以及重新验收条件。退回期间 V0.2 保持 Draft，不合并、不接受 TDR、不创建 Design Freeze Tag。

## 7. Owner 批准后的唯一执行顺序

1. 准备配对治理提交：将 Design Version 改为 `0.2.0`，将 Review Decision 改为 `APPROVED_FOR_DESIGN_FREEZE`，记录 Owner、日期和 Accepted Residual Risks。
2. 在同一对治理提交中将 TDR-001～TDR-010 改为 `Accepted`，每份记录 Review ID 和批准日期。
3. 再次运行 Contract、`verify-design-governance.tests.ps1 -Stage ApprovedPreTag` 和双语 Pair Verification。
4. 将中文候选合并到 `main`，英文候选合并到 `release`；禁止把一个语言分支合并到另一个语言分支。
5. 基于合并后的精确 commit 再次运行远端 Contract、Pair 和 `ApprovedPreTag` 治理验证。
6. 创建 annotated `v0.2.0-design-zh` 和 `v0.2.0-design-en`；Tag Message 记录 Review ID、对应 commit、配对 Tag 名称和另一语言 commit。
7. 运行 `verify-design-governance.tests.ps1 -Stage Frozen`，通过后发布 V0.2 Design Freeze 说明并开始 M1；任何后续冻结架构变更继续执行 ADR。

任何一步失败都停止后续步骤，不得留下单边 Tag、单边 TDR Accepted 或中英文状态不一致。

## 8. 验收证据命令

```powershell
pnpm install --frozen-lockfile
./scripts/tests/verify-contracts.tests.ps1
./scripts/tests/verify-design-governance.tests.ps1
./scripts/tests/verify-language-branches.tests.ps1
./scripts/verify-language-branches.ps1 `
  -ChineseRef origin/docs/v0.2-architecture-review `
  -EnglishRef origin/docs/v0.2-architecture-review-en `
  -Mode Pair
```

预批准状态下，`git tag --list "v0.2.0-design*"` 必须为空。

## 9. Owner 签署

```text
Decision: APPROVE
Approved Review ID: V0.2-AR-2026-08-23-01
Accepted Residual Risks: Section 5, items 1-5
Owner: Project Owner
Date: 2026-08-24
Return Findings (only for RETURN_WITH_FINDINGS):
```
