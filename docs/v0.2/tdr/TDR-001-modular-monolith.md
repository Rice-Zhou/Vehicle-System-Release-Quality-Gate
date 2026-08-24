# TDR-001 — Modular Monolith Backend

- 状态：Accepted
- 批准评审：`V0.2-AR-2026-08-23-01`
- 批准日期：2026-08-24
- 已接受残余风险：Owner 最终验收清单第 5 节第 1～5 项
- 范围：VSRQG Backend 部署与模块边界

## 问题与需求

V0.2 需同时实现 Release、Manifest、Issue、Traceability、Test、Evidence 和 Quality，跨模块存在强事务与一致性要求；主要开发资源有限，六个月内必须形成真实闭环，当前没有独立扩缩容或大规模吞吐证据。

## 决策与理由

采用一个可部署 Backend，内部以模块、应用 Port 和依赖测试形成边界；Test Agent 保持独立部署。单事务可保护 Manifest Lock、状态历史、Audit 和 Outbox，降低本地开发、调试、部署和恢复成本，同时保留未来抽离的稳定 API/Port。

## 未选方案

- 微服务：增加网络一致性、部署、观测、版本协调和团队协作成本，无当前需求驱动。
- 无模块单体：短期简单但会形成跨表/跨层耦合，无法安全演进。
- Serverless functions：长任务、事务编排和 Agent 状态管理更复杂。

## V0.2 / V0.3 影响

V0.2 获得最低运维复杂度和明确事务。V0.3 可在有指标证明时抽离 Evidence worker、Adapter 或 Orchestrator；Core Contract 和 API 不变。

## 迁移与回滚

抽离使用 Strangler：先稳定 Port/API → 独立读模型/worker → 双写禁止、使用 Outbox → 切换单一所有者。V0.2 回滚为上一 Backend 镜像和兼容数据库版本。

## 测试、部署与恢复

使用模块依赖测试、跨模块契约测试和端到端事务测试。部署一个 Backend 镜像；故障时重启无状态进程，任务由 DB 租约恢复，数据库按备份/PITR 恢复。

## 重新评估条件

某模块有量化的独立扩缩容、法规隔离、故障域、发布频率或资源冲突，且单体优化无法满足已定义 SLO。
