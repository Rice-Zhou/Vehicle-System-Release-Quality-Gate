# 08 — Test Agent Protocol

## 1. 选择与边界

Agent 使用由 Agent 主动发起的 HTTPS 注册、心跳和长轮询任务领取；Evidence 通过预签名 URL 直传对象存储。理由见 [TDR-006](tdr/TDR-006-agent-pull-protocol.md)。

协议定义可观察行为，不规定具体 HTTP 库、线程或进程结构。Agent 负责执行和采集，不拥有 Release/Manifest/Quality 决策。

## 2. 身份与版本协商

Agent 首次配置包含非明文 bootstrap identity reference；注册后使用短期 client credential 或 mTLS 身份。注册请求包含 agentVersion、protocolVersions、device reference、capabilities 和 collector versions。

Server 返回选定 protocolVersion、agentId、heartbeat interval、lease policy 和最低受支持版本。无交集返回 `426 AGENT_PROTOCOL_UNSUPPORTED`，不下发任务。

## 3. Endpoint

| Method | Endpoint | 行为 |
|---|---|---|
| POST | `/agent-api/v1/agents:register` | 注册/幂等恢复 Agent |
| POST | `/agent-api/v1/agents/{id}:heartbeat` | 状态、Device、Capability、运行 Command 摘要 |
| POST | `/agent-api/v1/agents/{id}/commands:poll` | 长轮询领取一个或小批 Command |
| POST | `/agent-api/v1/commands/{commandId}:ack` | 接受/拒绝并取得 fencing token |
| POST | `/agent-api/v1/commands/{commandId}/events` | 幂等上报进度与阶段状态 |
| POST | `/agent-api/v1/evidence/uploads` | 创建上传会话和预签名 URL |
| POST | `/agent-api/v1/evidence/uploads/{id}:complete` | 请求服务端校验并固化 Metadata |
| PUT | `/agent-api/v1/attempts/{attemptId}/result` | 幂等提交终态 Test Result |
| GET | `/agent-api/v1/attempts/{attemptId}/context` | 当前分配 Agent 读取固定执行上下文；Task 2 仅声明契约 |
| PUT | `/agent-api/v1/evidence/uploads/{id}/payload` | TDR-025 演示 Profile 流式上传；Task 2 仅声明契约 |

表中的 Endpoint 均为完整 Versioned Path，不允许客户端再次拼接 `/agent-api/v1`，也不允许实现暴露无版本别名。

机器可执行 Payload Contract 为 [`schemas/v0.2/agent-protocol.schema.json`](../../schemas/v0.2/agent-protocol.schema.json)，示例索引位于 [`contracts/examples/v0.2/validation-cases.json`](../../contracts/examples/v0.2/validation-cases.json)，所有 Endpoint 同时登记在 [`contracts/openapi/v0.2/openapi.json`](../../contracts/openapi/v0.2/openapi.json)。Contract Test 会比较本表与 OpenAPI 的精确 Method/Path 集合。

## 4. Command Envelope

### 单设备演示身份与上下文

Task 2 实现注册，运行上下文 GET 由 Task 3 实现，Payload PUT 由 Task 4 实现。新增端点的契约声明不代表运行端点已可用。上下文使用独立 [Schema](../../schemas/v0.2/agent-execution-context.schema.json)，所有字段必填、拒绝未知字段；既有协议 1.0 Command Payload 保持不变。上下文不包含凭据、本机路径或原始设备序列号。

注册默认关闭，显式设置 `vsrqg.demo.agent-registration.enabled=true` 才启用。关闭注册仍保留独立 `/agent-api/**` 证书 SecurityFilterChain，不回退用户 JWT。演示必须由 Backend 终止 TLS，配置 `server.ssl.enabled=true`、`server.ssl.client-auth=want`、`server.ssl.key-store`、`server.ssl.trust-store` 及相应 password/type；`want` 仅使用户路由允许不提供客户端证书。开发 CA、证书及私钥存储在仓库外，用环境变量或受控外部配置提供路径和口令，禁止提交或打印。此实现不接受代理证书 Header，不支持由未声明代理终止 TLS。

预登记 Agent 固定 SERVICE principal、项目、Device 和唯一证书 DER SHA-256 指纹。Device reference 使用已登记 Device ID。注册不会创建新身份、自动选择设备或改变绑定。撤销 Agent、禁用 principal/Device、归档项目或移除/改变项目 assignment 后拒绝后续注册，包括成功响应 replay。Agent scopes 为 `agent:register`、`agent:heartbeat`、`agent:poll`、`agent:execute`、`agent:evidence:write`；共享 Permission 目录中的 ENGINEER role 仍须同时满足证书与 SERVICE 绑定检查，不能由用户 JWT 获得 Agent 权限。

注册请求沿用严格 registrationRequest 与必需 `Idempotency-Key`，按 JCS 请求摘要复用既有幂等存储；同 key 不同内容返回 409，同证书再次注册返回相同 agentId。每次新幂等操作在注册事务内写 Audit，replay 不重复写入。无共同版本返回 `426 AGENT_PROTOCOL_UNSUPPORTED`；成功精确返回 `{protocolVersion:"1.0",agentId,heartbeatIntervalSeconds:20,leaseDurationSeconds:90}`。

注册只接受 UTF-8 的 `application/json`，请求体上限 64 KiB，在输入流边界限制读取；已知长度及 chunked 超限均返回 `413`，媒体类型不支持返回 `415`，空体或非法 UTF-8/JSON 返回 `400`。超限请求不进入注册应用逻辑。

Context GET 无 Idempotency-Key。Payload PUT 复用 `agent:evidence:write`，无 Idempotency-Key，以受当前租约约束的 Agent/project/Attempt Upload Session 与 bytes digest 定义重传；不同 bytes 返回冲突。TDR-025 的上传、Complete 校验及下载运行行为均属于 Task 4。

```json
{
  "protocolVersion":"1.0",
  "commandId":"cmd_01...",
  "attemptId":"att_01...",
  "commandType":"EXECUTE_TEST_CASE",
  "issuedAt":"2026-08-21T12:00:00Z",
  "deadline":"2026-08-21T12:10:00Z",
  "leaseDurationSeconds":90,
  "idempotencyKey":"att_01...:execute",
  "payloadSchemaVersion":"1.0",
  "payload":{
    "caseId":"boot-smoke",
    "caseVersion":1,
    "timeoutMs":300000,
    "requiredEvidence":["LOG","SCREENSHOT"]
  }
}
```

Agent 必须持久化 commandId、attemptId、最后 sequence 和本地执行状态，再 ACK。Command payload 不包含 Secret；必要访问凭证使用短期受限引用。

## 5. ACK、事件与幂等

- ACK 状态 ACCEPTED/REJECTED；拒绝必须有稳定 reason code。
- Server 为 ACCEPTED 返回 `leaseId` 与单调 `fencingToken`。
- 每个 Event 含 `(commandId, sequenceNo)`；重复 sequence 返回已接受，不重复副作用。
- Result 使用 attemptId PUT；相同摘要返回原结果，不同摘要返回 409 并隔离诊断。
- 过期 fencing token 的写入返回 409 STALE_LEASE，防止旧 Agent 污染新 Attempt。
- Attempt/Run 终态后的相同 digest 重复 Event/Result 返回原确认；不同 digest 或非法 sequence 返回 409 LATE_EVENT_CONFLICT，事件进入隔离诊断，不修改终态事实。

## 6. 心跳、断连与重连

心跳包含 monotonic agent uptime、当前 command、last sequence、Device power/connectivity、临时磁盘容量和 clock offset。Server 不依赖 Agent 墙钟判断租约。

```text
Disconnect
→ lease remains valid for grace window
→ Agent reconnects and reports persisted command state
   ├─ same active lease: resume/report
   ├─ result already accepted: acknowledge and clean local spool
   └─ lease expired/reassigned: stop side effects, upload diagnostics only
→ grace expired: Server keeps RECOVERY_PENDING until recovery deadline, then writes ERROR/TIMEOUT Result
```

## 7. Device 突然断电

Agent 与 Device 分离部署时，Agent 报告 DEVICE_UNREACHABLE；Agent 同在 Device 上时由心跳丢失推断。Server 保留 Attempt 和已上传 Evidence，等待恢复窗口。恢复后 Agent 上报 boot/session identity，防止把重启后的新环境误认为原连续执行。

非幂等设备动作不自动重放。超期后 Attempt 明确 ERROR/TIMEOUT；Retry 创建新 Attempt 和新 commandId。

## 8. Evidence 上传

Agent 先计算 SHA-256，创建 Upload Session 获得短期、单对象、限大小的预签名 URL。上传后 Complete 请求含实际 size/checksum/contentType/capturedAt/collectorVersion。Server 查询对象 metadata 并复核；失败保持 REJECTED/PENDING，不生成 AVAILABLE Evidence。

本地 spool 以 attempt/evidence ID 索引，达到容量阈值时停止领取新任务并报告 DEGRADED，不能静默删除 required Evidence。

## 9. Agent 生命周期与升级

状态：REGISTERING、ONLINE、BUSY、DEGRADED、DRAINING、OFFLINE、REVOKED。升级前进入 DRAINING，不接新 Command，完成/中止当前任务后升级。Server 定义 min/recommended version；强制升级只能在无运行任务时进行，失败回滚到上一已签名版本。

V0.2 不设计 Server 任意远程执行 shell。Command type 和 payload schema 必须白名单、版本化、签名来源可信。

## 10. 验收

- 重复 poll/ACK/event/result 不产生重复执行结果。
- 网络断开、Server 重启、Agent 重启、Device 断电均有演练。
- 过期 lease/fencing token 无法写入有效 Result。
- Evidence 上传中断可续传/重试且最终 checksum 一致。
- 不兼容 Agent 明确拒绝而非降级运行。
- Contract Test 断言所有 Agent Endpoint 使用唯一 `/agent-api/v1` 前缀，且无未版本化别名。
- RECOVERY_PENDING、迟到 Event/Result 与过期 fencing token 不改变终态 Run 输入。

证据：协议契约测试、故障注入日志、command timeline、重连/断电报告、Agent 升级回滚记录。
