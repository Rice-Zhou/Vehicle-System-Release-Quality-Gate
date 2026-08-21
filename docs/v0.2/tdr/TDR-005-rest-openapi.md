# TDR-005 — REST/JSON and OpenAPI 3.1

- 状态：Proposed for V0.2 Review
- 范围：用户、CI 和系统集成 API

## 问题与需求

核心资源边界清晰，需要公司内易接入、可浏览、可生成客户端、可版本化和可做契约测试的 API。MVP 请求规模可控，主要交互是资源 CRUD、状态命令和异步任务查询。

## 决策与理由

使用 REST/JSON、OpenAPI 3.1 和 `/api/v1`。资源建模直观，公司工具链普遍支持；OpenAPI 可做变更检查、契约测试和验收。长任务返回 202 + resource ID，不保持长事务连接。

## 未选方案

- GraphQL：灵活查询但权限、缓存、错误和 schema 治理复杂，MVP 无动态聚合需求。
- gRPC：适合内部高吞吐，但浏览器/人工调试与公司集成门槛更高；Agent 也无高频流需求。
- 消息 API：异步可靠但增加 Broker 和最终一致性，不适合作为主要操作界面。

## V0.2 / V0.3 影响

V0.2 获得低门槛集成。V0.3 可针对高频内部链路增加 gRPC/事件，但 REST 仍作为治理 API，Core 语义不变。

## 迁移与回滚

同 major 只做向后兼容扩展；breaking change 使用新 major 和双版本迁移期。应用回滚需保持 DB/API 兼容，OpenAPI artifact 与镜像一起版本化。

## 测试、部署与恢复

OpenAPI lint、breaking diff、consumer contract、幂等/权限/错误测试。随 Backend 部署；API 故障由上一镜像回滚，异步资源状态从 DB 恢复。

## 重新评估条件

出现被量化的实时流、高吞吐低延迟或客户端查询组合需求，且 REST 分页/异步资源不能满足。
