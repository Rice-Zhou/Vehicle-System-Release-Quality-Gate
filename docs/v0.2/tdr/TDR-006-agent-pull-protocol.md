# TDR-006 — Agent-Initiated Pull Protocol

- 状态：Proposed for V0.2 Review
- 范围：Test Orchestrator 与 Test Agent 通信

## 问题与需求

Agent 位于车机/台架网络，常在 NAT/防火墙后且可能断网、断电；系统需要注册、心跳、任务、ACK、重试、Timeout、重连、Evidence 上传和幂等。MVP 仅少量 Agent，不需要大规模实时推送。

## 决策与理由

Agent 主动通过 HTTPS 注册、心跳和长轮询领取 Command；commandId、租约与 fencing token 保证恢复和防陈旧写入；Evidence 用预签名 URL 直传。仅需出站连接，网络策略简单，断连状态可持久化，服务端无需保持复杂双向会话。

## 未选方案

- Server 主动连接 Agent：防火墙/NAT 与设备地址管理困难。
- WebSocket：实时性更高但重连、代理和连接状态复杂，MVP 无必要。
- MQTT/Kafka：需要额外 Broker、权限和运维，当前 Agent 数量不构成需求。
- ADB 作为协议：可作为执行机制，但不是可靠、版本化的 Agent 控制协议。

## V0.2 / V0.3 影响

V0.2 协议简单但任务领取有最长轮询延迟。V0.3 可在同一 Command/ACK 语义下替换为 WebSocket/MQTT，不改变 Run/Attempt。

## 迁移与回滚

Agent/Server 协商 protocol version；升级期同时支持相邻版本。回滚 Server 时只向兼容 Agent 下发任务，不兼容者 DRAINING。

## 测试、部署与恢复

协议契约、唯一 Versioned Path、重复/乱序/迟到消息、RECOVERY_PENDING、断连、重启、断电和过期租约测试。Agent 独立部署并持久化本地 command/spool；Server 重启后从 DB 恢复租约与状态。

## 重新评估条件

Agent 数量或任务延迟实测超出长轮询能力，或公司设备平台已提供可靠且可复用的双向消息基础设施。
