# V0.2 实施架构与技术决策规范

- 设计版本：`0.2.0-draft.1`
- 状态：Architecture Review Draft
- 基线：V0.1 Architecture `0.1.0`（FROZEN）
- 日期：2026-08-21

## 1. 定位

V0.1 回答“VSRQG 是什么”，V0.2 回答“如何在不改变 V0.1 的前提下工程化落地”。本目录不是对 Core Contract 的修订，而是从冻结概念到可开发、可测试、可部署、可验收实现的映射。

```text
V0.1 Concept (FROZEN)
        ↓ implementation mapping only
V0.2 Boundary / Contract / Technology Decision
        ↓ architecture review and design freeze
Implementation → Test → Acceptance
```

## 2. 角色分工

项目 Owner 负责：WHY、WHAT、BOUNDARY、ACCEPTANCE。

实施方/Codex 负责：HOW、TECHNOLOGY、IMPLEMENTATION、TEST、OPTIMIZATION。

实施自由不得突破冻结架构与本规范中的外部可见契约。验收关注业务不变量、行为、证据和恢复能力，不规定类名、ORM、线程模型等代码细节。

## 3. 六个月产品工程边界

目标是一个主要开发者在约六个月业余时间内，交付可在约 300 人公司真实使用的 MVP。公司级可信度体现为身份、权限、审计、一致性、重放和恢复能力，而非基础设施数量。

MVP 必须闭环：

```text
真实 Release → Manifest Lock → Issue Snapshot → Traceability
→ 真实设备 Test Run → Evidence → Rule Set Evaluation
→ 可解释 Quality Result → 相同快照重放
```

MVP 不引入 Kafka、Redis、Kubernetes、通用工作流引擎、图数据库、规则脚本平台或微服务拆分。高级设备池、趋势分析、AI 辅助和跨项目治理延期到 V0.3。

## 4. Technology Decision Delegation

V0.2 阶段，具体技术栈由实施方根据需求、冻结架构、六个月边界、可维护性、可测试性和部署条件自主选择。项目 Owner 不要求预先指定具体技术栈。

所有关键技术选择必须有 Technology Decision Record（TDR），说明：问题、决策、替代方案、V0.2/V0.3 影响、迁移、测试、部署、恢复和重新评估条件。TDR 只能决定冻结边界内的实现；触及 Core Contract、责任归属、权威来源或质量语义时，必须停止并提交 ADR Proposal。

## 5. 三条红线

1. **不能改变目标**：不得把 VSRQG 变成 AI QA、测试框架、Jira Dashboard、Crash Dashboard 或 CI Dashboard。
2. **不能擅自改变架构**：不得合并或删除冻结实体，不得绕过 Manifest、Evidence、Traceability、Quality Engine、Adapter、Plugin 或 ADR。
3. **不能为了技术而技术**：新增基础设施必须由当前可测需求驱动；“未来可能需要”不构成 MVP 引入理由。

## 6. 文档导航

| 文档 | 回答的问题 | 主要验收物 |
|---|---|---|
| [01-domain-model.md](01-domain-model.md) | 核心概念如何映射为实施边界 | 领域关系与不变量 |
| [02-database-design.md](02-database-design.md) | 数据如何持久化且保持一致 | ER、键、约束、生命周期 |
| [03-api-design.md](03-api-design.md) | 系统如何对外提供稳定能力 | OpenAPI、错误、幂等、权限 |
| [04-release-manifest-design.md](04-release-manifest-design.md) | 权威 Manifest 如何完成生命周期 | 状态机、Lock 与并发验收 |
| [05-issue-adapter-design.md](05-issue-adapter-design.md) | 多问题源如何隔离和归一化 | Port、映射、同步与快照 |
| [06-traceability-design.md](06-traceability-design.md) | Fixed、Included、Verified 如何被证明 | 强类型追溯边与置信度 |
| [07-test-architecture.md](07-test-architecture.md) | 测试如何调度和恢复 | Run/Attempt 状态机 |
| [08-test-agent-protocol.md](08-test-agent-protocol.md) | Server 与 Agent 如何可靠通信 | 注册、租约、ACK、重连 |
| [09-evidence-design.md](09-evidence-design.md) | Evidence 如何存储、校验与保留 | Metadata/Payload 完整性 |
| [10-quality-engine-design.md](10-quality-engine-design.md) | Release 如何得出确定结果 | 输入快照、求值与重放 |
| [11-quality-rule-specification.md](11-quality-rule-specification.md) | 规则如何定义、版本化和测试 | 受限 YAML 规范 |
| [12-authentication-design.md](12-authentication-design.md) | 谁能做什么 | OIDC、RBAC、服务身份与审计 |
| [13-deployment-design.md](13-deployment-design.md) | MVP 如何部署、监控和恢复 | 拓扑、备份、恢复与 SLO |
| [14-mvp-implementation-plan.md](14-mvp-implementation-plan.md) | 六个月如何按成果推进 | 里程碑、出口条件、验收矩阵 |
| [tdr/README.md](tdr/README.md) | 为什么选择这些技术 | 可复核的技术决策记录 |
| [reviews/2026-08-23-architecture-review.md](reviews/2026-08-23-architecture-review.md) | V0.2 是否具备 Design Freeze 条件 | Review Gate、Blocker 与 Owner 决策项 |

## 7. 版本与变更治理

- V0.2 评审前使用 `0.2.0-draft.N`，不得标记为冻结。
- 每次 Git 提交只承载一个可独立说明和审查的逻辑变更。
- V0.1 基线、V0.2 Draft、评审修订和 Design Freeze 使用不同提交或标签。
- Design Freeze 只能在完成本目录验收矩阵、关闭冲突 ADR 并获得 Owner 批准后创建。
- 冻结后发现架构问题：停止修改 → ADR Proposal → Architecture Review → 批准 → 修订 → 重新 Freeze。

## 8. 全局完成标准

所有专题文档必须同时给出：责任与非责任、数据/接口契约、异常语义、版本策略、MVP 范围、验收条件和验收证据。任何错误、缺失、不一致或外部不可用都不得静默转为成功或 PASS。

## 9. V0.1 一致性与 ADR 检查

| V0.1 冻结项 | V0.2 实施映射 | 结论 |
|---|---|---|
| Release-centric | 所有 Snapshot、Run、Evidence、Evaluation 均显式关联 Release | 保持不变 |
| Manifest authoritative | Lock 后成为 Release 唯一权威内容定义，外部变化不回写 | 保持不变 |
| Evidence first-class | 独立 Metadata/Payload、ID、生命周期和 API | 保持不变 |
| Traceability | 四类强类型 Edge + Test/Evidence 验证链 | 保持不变 |
| Deterministic Quality | 冻结输入 + 版本 Rule Set + 可重放求值 | 保持不变 |
| Adapter | Jira/内部系统仅通过统一 Port 输出 Normalized Issue | 保持不变 |
| Plugin | Collector 通过 Agent Plugin Contract 扩展 | 保持不变 |
| AI advisory | AI 不进入 final status 的权威计算 | 保持不变 |
| ADR governance | TDR 不得修改冻结边界，冲突必须转 ADR | 保持不变 |

本次 V0.2 设计评审未发现必须修改 V0.1 才能落地的冲突，因此没有创建 ADR Proposal。若实施验证推翻这一结论，应立即停止相关修改并按 ADR 流程处理。
