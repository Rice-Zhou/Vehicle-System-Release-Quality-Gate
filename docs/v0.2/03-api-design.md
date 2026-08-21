# 03 — Core API Design

## 1. API 分层

```text
Transport API (REST/OpenAPI)
  → Application Use Case（认证、授权、幂等、事务）
    → Domain Port（业务不变量）
      → Persistence / Adapter / Object Storage Port
```

Transport 不承载领域判断；Adapter API 不直接暴露给 Core 客户端；Agent API 与用户 API 使用不同身份和权限域。

## 2. 通用契约

- Base path：`/api/v1`；Agent：`/agent-api/v1`。
- Media type：`application/json`；时间 ISO-8601 UTC；ID 为不透明字符串。
- 写操作要求 `Idempotency-Key`；创建成功返回 `201`，异步受理返回 `202`。
- 分页采用不透明 cursor：`?limit=50&cursor=...`，响应 `nextCursor`。
- 每个响应返回 `X-Request-Id`；客户端可提供合法 request ID。
- 并发修改使用 `ETag` / `If-Match` 或显式 `rowVersion`。
- OpenAPI 3.1 文档是外部契约；实现框架可替换。

## 3. 核心 Endpoint

| Method | Endpoint | 职责 | 权限 | 幂等 |
|---|---|---|---|---|
| POST | `/releases` | 创建独立 Release 身份 | `release:create` | 是 |
| GET | `/releases/{releaseId}` | 获取 Release | `release:read` | 天然 |
| POST | `/releases/{releaseId}/manifests` | 注册 Manifest Revision | `manifest:write` | 是 |
| POST | `/releases/{releaseId}/manifests/{manifestId}:validate` | 执行可审计校验 | `manifest:write` | 是 |
| POST | `/releases/{releaseId}/manifests/{manifestId}:lock` | 锁定权威 Manifest | `manifest:lock` | 是 |
| POST | `/releases/{releaseId}/issue-snapshots` | 从指定同步结果创建快照 | `issue:snapshot` | 是 |
| GET | `/releases/{releaseId}/traceability` | 查询追溯链和缺口 | `traceability:read` | 天然 |
| POST | `/releases/{releaseId}/traceability:verify` | 验证并固化 Snapshot | `traceability:verify` | 是 |
| POST | `/test-runs` | 为 Locked Release 创建 Run | `test:execute` | 是 |
| POST | `/test-runs/{id}:cancel` | 请求取消 | `test:execute` | 是 |
| GET | `/test-runs/{id}/results` | 获取 Result/Attempt | `test:read` | 天然 |
| GET | `/evidence/{evidenceId}` | 获取 Metadata/受控下载链接 | `evidence:read` | 天然 |
| POST | `/rule-sets` | 创建 Draft Rule Set | `rule:write` | 是 |
| POST | `/rule-sets/{id}:publish` | 发布不可变版本 | `rule:publish` | 是 |
| POST | `/releases/{releaseId}/quality-evaluations` | 以固定输入触发评估 | `quality:evaluate` | 是 |
| GET | `/releases/{releaseId}/quality-results` | 查询历史结果 | `quality:read` | 天然 |
| POST | `/quality-results/{id}:override` | 记录人工治理决定 | `quality:override` | 是且强审计 |
| POST | `/releases/{releaseId}:approve` | 批准 Release | `release:approve` | 是 |

Override 不改写 Quality Result 的算法结果；它创建独立 Governance Decision，保留原始 PASS/WARNING/BLOCK。

## 4. 代表性模型

### Create Release

```json
{
  "project": "vehicle-x",
  "vehicle": "model-a",
  "platform": "android-automotive",
  "systemVersion": "2026.08-rc1",
  "buildId": "build-1842"
}
```

```json
{
  "releaseId": "rel_01...",
  "status": "DRAFT",
  "manifestId": null,
  "createdAt": "2026-08-21T10:00:00Z",
  "version": 1
}
```

### Lock Manifest

Request body 仅包含审核说明，不能替换 Manifest 内容：

```json
{"reason":"Artifacts and checksums verified for RC1"}
```

Response：

```json
{
  "releaseId":"rel_01...",
  "manifestId":"man_01...",
  "manifestRevision":2,
  "contentDigest":"sha256:...",
  "state":"LOCKED",
  "lockedAt":"2026-08-21T11:00:00Z"
}
```

### Create Test Run

```json
{
  "releaseId":"rel_01...",
  "testPlan":{"planId":"release-smoke","version":1},
  "deviceSelector":{"vehicle":"model-a","requiredCapabilities":["ADB","CRASH","ANR"]}
}
```

### Request Quality Evaluation

```json
{
  "ruleSet":{"ruleSetId":"mvp-gate","version":1},
  "testRunIds":["run_01..."],
  "traceabilitySnapshotId":"trs_01..."
}
```

服务端解析并固化全部实际输入，响应 `202` 与 `evaluationId`；调用方不能提交任意“已通过”事实。

## 5. 错误模型

采用 RFC 9457 Problem Details：

```json
{
  "type":"https://vsrqg.example/problems/manifest-not-locked",
  "title":"Manifest is not locked",
  "status":409,
  "code":"MANIFEST_NOT_LOCKED",
  "detail":"Release rel_01... cannot enter testing",
  "instance":"/api/v1/test-runs",
  "requestId":"req_01...",
  "violations":[]
}
```

| HTTP | 语义 |
|---|---|
| 400 | JSON/参数格式错误 |
| 401/403 | 未认证/无权限 |
| 404 | 资源不存在或不可见 |
| 409 | 状态冲突、幂等摘要冲突、版本冲突 |
| 422 | Schema 正确但领域校验失败，附 violations |
| 429 | 限流，含 Retry-After |
| 503 | 明确的依赖不可用；不得伪装成功 |

未知异常返回稳定通用错误并记录关联 request ID，不泄露堆栈、凭证或外部响应敏感信息。

## 6. API Version 与兼容

- Path major version；向后兼容字段在同 major 增加。
- 客户端必须忽略未知响应字段；服务端默认拒绝未知写入字段，避免拼写被静默吞掉。
- 删除/重命名/语义改变需要新 major、迁移期和 TDR；触及 Core Contract 时需要 ADR。
- OpenAPI diff 在 CI 中阻止未声明的 breaking change。

## 7. 幂等性

服务端存储 `(principal, endpoint, idempotency_key, request_digest, response_status, response_body)`。相同 key+摘要返回原响应；相同 key+不同摘要返回 `409 IDEMPOTENCY_KEY_REUSED`。记录保留时间必须覆盖最大客户端重试窗口。

Agent `commandId`、Adapter `(source, sourceVersion)`、Evidence `(collector, payloadChecksum, run)` 和 Quality Evaluation 复合键形成领域级幂等保护。

## 8. 验收

- OpenAPI lint 与 breaking-change check 通过。
- 所有写 Endpoint 有权限、幂等和并发测试。
- 重复请求只产生一个业务结果。
- 错误路径返回可机器处理 code，不出现假成功或敏感信息。
- Owner 可从 API 完成 Release 全闭环，不需直接访问数据库。

证据：发布的 OpenAPI、契约测试报告、权限矩阵测试、幂等并发测试、API 审计样本。
