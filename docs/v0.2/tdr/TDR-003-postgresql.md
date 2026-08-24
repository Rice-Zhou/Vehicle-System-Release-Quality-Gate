# TDR-003 — PostgreSQL for Structured Data

- 状态：Accepted
- 批准评审：`V0.2-AR-2026-08-23-01`
- 批准日期：2026-08-24
- 已接受残余风险：Owner 最终验收清单第 5 节第 1～5 项
- 范围：领域、关系、事务和历史查询数据

## 问题与需求

Release、Manifest、Issue、Commit、Build、Artifact、Test、Evidence Metadata 和 Quality 存在大量结构化关系；Manifest Lock/状态转换需要事务；Traceability 和审计需要历史查询；Quality Snapshot 需要一致性；MVP 数据规模可控。

## 决策与理由

使用 PostgreSQL 作为唯一结构化数据存储。它提供 ACID、FK/UNIQUE/CHECK、MVCC、一致性查询、JSONB 扩展、成熟迁移/备份/PITR，并能用强类型关联表满足固定追溯链。一个数据库降低跨存储一致性和运维成本。

## 未选方案

- MongoDB：文档灵活，但本项目的强关系、多实体事务和完整性约束是核心需求。
- Neo4j/图数据库：追溯链固定、规模可控，SQL 关联足够；引入第二数据库增加一致性问题。
- MySQL：可行，但 PostgreSQL 在约束、JSONB、并发任务和查询能力上更匹配。
- 多数据库：没有独立规模/隔离证据。

## V0.2 / V0.3 影响

V0.2 获得单一事务事实源。V0.3 若分析规模证明需要，可从不可变事件/快照构建只读搜索或图投影，但 PostgreSQL 仍是权威记录。

## 迁移与回滚

Flyway forward-only、Expand/Migrate/Contract、上线前副本演练。故障时回滚应用；数据库通过备份/PITR 恢复。迁移到其他数据库需导出带 digest 的领域快照并完成双读比对，不改变 ID/语义。

## 测试、部署与恢复

使用真实 PostgreSQL 集成测试约束、事务、锁和迁移；部署独立/受管实例，启用加密、备份和监控。定期恢复并重放历史 Quality Result。

## 重新评估条件

实测单库容量/吞吐/SLO 不满足且索引、分区、读副本等优化已被证明不足，或公司平台强制变更。
