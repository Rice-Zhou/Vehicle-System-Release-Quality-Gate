# TDR-006 — Agent-Initiated Pull Protocol

- Status: Proposed for V0.2 Review
- Scope: communication between Test Orchestrator and Test Agent

## Problem and Requirements

Agents operate in head-unit or bench networks, often behind NAT/firewalls and subject to network or power loss. The system needs registration, heartbeat, tasks, ACK, retry, Timeout, reconnection, Evidence upload, and idempotency. MVP has few Agents and does not require large-scale real-time push.

## Decision and Rationale

The Agent initiates HTTPS registration, heartbeat, and long polling for Commands. commandId, lease, and fencing token provide recovery and reject stale writes. Evidence uploads directly through presigned URLs. Only outbound connections are needed, simplifying network policy. Disconnected state can be persisted and the server need not maintain complex bidirectional sessions.

## Alternatives Not Selected

- Server-initiated Agent connection: difficult across firewalls/NAT and device address management.
- WebSocket: more real-time but more complex reconnection, proxy, and connection state; unnecessary for MVP.
- MQTT/Kafka: adds Broker, authorization, and operations without a requirement from current Agent count.
- ADB as the protocol: may be an execution mechanism, but is not a reliable, versioned Agent control protocol.

## V0.2 / V0.3 Impact

V0.2 keeps the protocol simple at the cost of maximum polling latency. V0.3 can replace transport with WebSocket/MQTT under the same Command/ACK semantics without changing Run/Attempt.

## Migration and Rollback

Agent and Server negotiate protocol version and support adjacent versions during upgrade. A rolled-back Server dispatches work only to compatible Agents; incompatible Agents enter DRAINING.

## Testing, Deployment, and Recovery

Test protocol contracts, duplicate messages, reordering, disconnects, restarts, power loss, and expired leases. Deploy the Agent independently and persist local command/spool state. After Server restart, restore leases and state from the DB.

## Re-evaluation Triggers

Measured Agent volume or task latency exceeds long-poll capacity, or the company device platform provides reliable reusable bidirectional messaging infrastructure.
