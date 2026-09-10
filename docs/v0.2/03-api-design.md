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
- TDR-025 新增的演示 Payload PUT 以已授权 Upload Session + bytes digest 保证重传，无 Idempotency-Key；其余既有写操作要求不变。
- 分页采用不透明 cursor：`?limit=50&cursor=...`，响应 `nextCursor`。
- 每个响应返回 `X-Request-Id`；客户端可提供合法 request ID。
- 并发修改使用 `ETag` / `If-Match` 或显式 `rowVersion`。
- OpenAPI 3.1 文档是外部契约；实现框架可替换。

### 2.1 机器可执行契约

Task 2 新增契约声明：`GET /agent-api/v1/attempts/{attemptId}/context`（`agent:execute`，Task 3 实现）与 `PUT /agent-api/v1/evidence/uploads/{id}/payload`（`agent:evidence:write`，Task 4 已实现本地 Profile）。二者均使用独立 mTLS，无 Idempotency-Key；Context 返回独立严格 Schema，Payload 使用 Session + bytes digest。Task 2 仅实现既有注册端点与证书绑定身份，普通配置默认关闭注册。

Permission 单一目录定义 `test:execute` 为 ENGINEER/RELEASE_MANAGER/ADMINISTRATOR，`test:read` 与 `evidence:read` 为全部现有项目角色，`evidence:read:sensitive` 仅 QUALITY_OWNER/ADMINISTRATOR。Agent scopes 还必须通过证书、SERVICE principal 与项目/Device 绑定校验，用户 JWT 不可替代。

TDR-025 演示下载 Profile 将 GENERAL/RESTRICTED/HIGH 均经既有受鉴权 Payload GET 流式传输；GENERAL/RESTRICTED 使用 `evidence:read`，HIGH 使用 `evidence:read:sensitive`。OpenAPI 保留默认 HIGH 路径的既有 `x-permission` 兼容基线，并以 `x-demo-permission-by-sensitivity` 明确演示 Profile 的条件权限，不改变普通 Payload 读取角色或降低 HIGH 控制。下载仍要求 purpose、项目鉴权与 Audit，不返回未鉴权 URL、token 或文件路径。Task 4 已实现本地运行下载，默认对象存储 Profile 的既有下载申请契约保持原义。

- OpenAPI 3.1 Draft：[`contracts/openapi/v0.2/openapi.json`](../../contracts/openapi/v0.2/openapi.json)。
- 兼容性基线：[`contracts/openapi/v0.2/compatibility-baseline.json`](../../contracts/openapi/v0.2/compatibility-baseline.json)。
- OpenAPI 覆盖本文与 Agent Protocol 表中的全部 Method/Path；`x-permission` 和 `x-idempotency-required` 固化权限与幂等要求。
- 本地执行 `pnpm install --frozen-lockfile` 后运行 `scripts/verify-contracts.ps1`。校验包括 OpenAPI 引用解析、文档/API Endpoint 集合一致性、权限/幂等属性和兼容性基线。
- 修改既有 Operation 的 Path、Method、Permission、Idempotency 或 Request Contract 属于不兼容修改，必须显式更新兼容性基线并经过 Review；触及 Core Contract 语义时仍需 ADR。

## 3. 核心 Endpoint

| Method | Endpoint | 职责 | 权限 | 幂等 |
|---|---|---|---|---|
| POST | `/releases` | 创建独立 Release 身份 | `release:create` | 是 |
| GET | `/releases/{releaseId}` | 获取 Release | `release:read` | 天然 |
| POST | `/releases/{releaseId}/manifests` | 注册 Manifest Revision | `manifest:write` | 是 |
| GET | `/releases/{releaseId}/manifests/{manifestId}` | 导出已锁定的权威 Manifest、摘要与校验报告 | `release:read` | 天然 |
| POST | `/releases/{releaseId}/manifests/{manifestId}:validate` | 执行可审计校验 | `manifest:write` | 是 |
| POST | `/releases/{releaseId}/manifests/{manifestId}:lock` | 锁定权威 Manifest | `manifest:lock` | 是 |
| POST | `/issue-sources/{sourceId}/sync` | 启动 Issue Source 同步 | `issue:sync` | 是 |
| POST | `/issue-sources/{sourceId}/mapping-profiles:activate` | 激活 Issue Mapping Profile | `issue:configure` | 是 |
| GET | `/issue-sync-runs/{syncRunId}` | 查询 Issue 同步运行状态 | `issue:read` | 天然 |
| POST | `/releases/{releaseId}/issue-snapshots` | 从指定同步结果创建快照 | `issue:snapshot` | 是 |
| GET | `/releases/{releaseId}/traceability` | 查询追溯链和缺口 | `traceability:read` | 天然 |
| POST | `/releases/{releaseId}/traceability:verify` | 验证并固化 Snapshot | `traceability:verify` | 是 |
| GET | `/traceability-verification-runs/{verificationRunId}` | 查询异步 Traceability Verification Run 状态 | `traceability:read` | 天然 |
| POST | `/traceability/facts:ingest` | 由 Service Identity 写入 Traceability Fact | `traceability:ingest` | 是 |
| POST | `/test-runs` | 为 Locked Release 创建 Run | `test:execute` | 是 |
| POST | `/test-runs/{id}:cancel` | 请求取消 | `test:execute` | 是 |
| GET | `/test-runs/{id}/results` | 获取 Result/Attempt | `test:read` | 天然 |
| GET | `/evidence/{evidenceId}` | 获取 Metadata，不返回永久对象 URL | `evidence:read` | 天然 |
| POST | `/evidence/{evidenceId}:download` | GENERAL/RESTRICTED 下载申请；返回 ≤60 秒 Presigned URL | `evidence:read` | 是且审计 |
| GET | `/evidence/{evidenceId}/payload` | HIGH Payload 逐请求鉴权并由 Backend/Gateway 流式传输 | `evidence:read:sensitive` | 天然且审计 |
| POST | `/rule-sets` | 创建 Draft Rule Set | `rule:write` | 是 |
| POST | `/rule-sets/{id}:publish` | 发布不可变版本 | `rule:publish` | 是 |
| POST | `/releases/{releaseId}/quality-evaluations` | 以固定输入触发评估 | `quality:evaluate` | 是 |
| GET | `/releases/{releaseId}/quality-results` | 查询历史结果 | `quality:read` | 天然 |
| POST | `/quality-results/{id}:override` | 记录人工治理决定 | `quality:override` | 是且强审计 |
| POST | `/releases/{releaseId}:approve` | 批准 Release | `release:approve` | 是 |

普通 Evidence 下载申请的 Idempotency Record TTL 与 Presigned URL 有效期相同；同 key 在 TTL 内返回同一 grant，过期后必须使用新 key 并重新授权。HIGH Payload GET 不产生可复用 grant，每次请求都重新鉴权。

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

### Task 4 本地 Evidence Profile

上传 Create/Complete 继续使用严格 Agent Schema，无新增客户端 lease 字段；服务端 Session 捕获并逐次复核 Attempt lease/fencing。Create 返回 `{uploadId,evidenceId,uploadUrl,expiresAt}`，上传地址为同 Backend mTLS 相对 URI。Complete 返回固定 Metadata，只有实际 bytes 的 size/SHA-256/type 全部匹配才为 AVAILABLE。启用与恢复操作见 [Evidence 设计第 11 节](09-evidence-design.md#11-task-4-本地运行接口与恢复)。

本地 `POST /evidence/{evidenceId}:download` 对所有 sensitivity 使用既有 `{reason}` 作为 purpose，返回 `{url,expiresAt}`。URL 为带不透明 `grantId` 的同 Backend 受鉴权相对 URI；`DownloadGrant.url` 因而使用 `uri-reference`。申请记录有效期固定 60 秒，同 key 过期明确拒绝，必须新 key 重新鉴权。GET 的本地必需 query 是 `grantId`；每次验证当前 JWT、项目权限、grant 所有者与 purpose、expiry、retention/legal hold。GENERAL/RESTRICTED 要求 `evidence:read`，HIGH 同时要求 `evidence:read:sensitive`，复制 URL 给另一个用户不会转移权限。

Payload GET 的 Audit 在传输前提交，失败不输出 Payload；统一 no-store、无重定向、不暴露磁盘路径，Range 返回 416。这里不改变默认对象存储 Profile 的 HIGH 控制或普通预签名下载语义。

Task 4 PUT 接收使用 Servlet 异步非阻塞读取：preflight 与 EOF postflight 分别使用短事务，网络等待不持 Agent/Run/Attempt 锁。30 秒总期限返回结构化 `408 UPLOAD_TIMEOUT`；无效 size/hash 候选在固定文件发布前被拒绝，同 Session 的正确重传仍可完成。postflight 每次按证书身份重新核对当前权限、lease、Session 与终态。
