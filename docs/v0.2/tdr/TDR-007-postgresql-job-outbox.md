# TDR-007 — PostgreSQL Jobs and Transactional Outbox

- 状态：Proposed for V0.2 Review
- 范围：后台任务与事务后异步处理

## 问题与需求

Adapter sync、Trace verify、Evidence reconcile 和 Quality Evaluation 需要异步、重试与恢复；Manifest Lock 等事件必须与业务事务一致。MVP 规模可控，不能承担 Broker 运维和跨系统一致性成本。

## 决策与理由

业务事务同时写 Outbox；worker 使用 PostgreSQL 行锁/租约、有界重试和 dead-letter 处理任务。数据库是已有依赖，可原子保证“状态变化与事件记录同时成功”，并支持多实例安全领取。

## 未选方案

- Kafka：吞吐和回放强，但部署、Schema、消费者和运维复杂，无当前需求。
- RabbitMQ：成熟但仍增加一套状态与发布确认问题。
- 内存队列：重启丢失，不能满足审计/恢复。
- 同步执行全部任务：外部调用和长任务会扩大事务与 API 延迟。

## V0.2 / V0.3 影响

V0.2 降低运维成本，代价是 DB 承担任务扫描。V0.3 可让 Outbox publisher 投递 Broker，业务事务和事件契约保持不变。

## 迁移与回滚

先为事件定义稳定 event ID/schema；引入 Broker 时从 Outbox 双轨发布但仅一个消费者拥有副作用，切换后停止 DB worker。回滚可恢复 DB worker 并依据幂等键续跑。

## 测试、部署与恢复

测试重复领取、worker crash、租约过期、poison job、DB restart 和幂等。worker 与 Backend 同镜像部署；状态从 job/outbox 表恢复，dead-letter 必须告警和人工可见。

## 重新评估条件

实测 job lag/DB 负载不满足 SLO、需要跨系统大量订阅或独立吞吐扩展，且索引/批处理优化不足。
