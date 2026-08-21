# 12 — Authentication, Authorization and Audit

## 1. 边界

人员身份复用公司 OIDC/OAuth 2.1；服务、Adapter 和 Agent 使用独立 workload identity/service account。VSRQG 不自建密码系统，不在 Git、源码、Manifest、日志或业务表中保存 Secret 明文。

实现理由见 [TDR-009](tdr/TDR-009-oidc-and-service-identities.md)。

## 2. 身份类型

| 身份 | 认证 | 用途 | 禁止 |
|---|---|---|---|
| User | OIDC Authorization Code + PKCE | UI/API 操作 | 共享账号 |
| CI Service Account | OAuth client credential/短期 token | 注册 Release/Manifest/Build | 人员登录 |
| Adapter Service Account | Secret Manager 注入凭证 | 外部 API 同步 | 将外部 token 写入 DB |
| Agent/Device Identity | mTLS 或短期 client credential | Agent Protocol | 使用用户 token |
| Internal Worker | 进程内身份/受控 service identity | 后台任务 | 绕过应用授权写库 |

数据库仅保存 principal ID、issuer、subject、状态与 credential reference；Secret 保存在公司 Secret Manager/部署平台。

## 3. 固定 RBAC

| 能力 | Viewer | Engineer | Release Manager | Quality Owner | Administrator |
|---|:---:|:---:|:---:|:---:|:---:|
| 查看 Release/Trace/Report | ✓ | ✓ | ✓ | ✓ | ✓ |
| 查看一般 Evidence | ✓ | ✓ | ✓ | ✓ | ✓ |
| 查看高敏 Evidence |  | 按授权 | 按授权 | ✓ | ✓ |
| 创建 Release/Manifest |  | ✓ | ✓ |  | ✓ |
| Lock Manifest |  |  | ✓ |  | ✓ |
| 执行/取消 Test |  | ✓ | ✓ |  | ✓ |
| 创建 Rule Draft |  |  |  | ✓ | ✓ |
| 发布 Rule Set |  |  |  | ✓ | ✓ |
| Override Quality Result |  |  |  | ✓ | ✓ |
| 批准 Release |  |  | ✓ | ✓（按治理） | ✓ |
| 管理身份/系统配置 |  |  |  |  | ✓ |

权限为细粒度 permission，角色只是稳定集合。MVP 不实现 ABAC/策略语言；项目范围通过 principal-project assignment 约束。

## 4. 高风险操作

Manifest Lock、Rule Publish、Quality Override、Release Approval 强制重新校验权限和资源版本，记录 actor、reason、request ID、前后状态。生产 Rule Publish 和 BLOCK Override 推荐双人原则；若 MVP 初期无法技术强制，流程与审计仍必须存在并在报告中标记单人批准。

Override 不改写算法结果；批准 PASS/WARNING/BLOCK 的治理语义由 Owner 策略决定。

## 5. Evidence 授权

Metadata 与 Payload 分开授权。下载前检查项目范围、Evidence sensitivity、purpose 和保留状态，返回分钟级预签名 URL。下载操作写审计；URL 不进入日志。高敏 dump/log 可要求额外 permission 和水印/审批。

## 6. Audit Event

追加事件包含：eventId、occurredAt、actor type/id、action、resource type/id、project、requestId、result、reason、before/after digest、source IP/agent ID 和 application version。敏感字段只保存摘要或引用。

至少审计：Release create、Manifest register/lock、Snapshot、Test execute/cancel、Evidence access/delete、Rule publish、Evaluation、Override、Approval、身份/角色变更、credential reference 轮换。

## 7. 失败与恢复

- OIDC 不可用：现有短期 token 在有效期内按策略工作；新登录失败并明确提示，不开放匿名回退。
- 权限服务/映射失败：fail closed，返回 503/403，不默认 Admin。
- Agent credential 泄露：撤销 identity、Agent REVOKED、隔离相关命令并轮换。
- Secret Manager 不可用：依赖 Adapter/worker DEGRADED，不从日志或配置 fallback 明文凭证。
- 审计写入失败：高风险写操作整体失败；不能先执行后丢审计。

## 8. 验收

- 权限矩阵逐格自动测试，跨项目访问失败。
- Secret 扫描、日志检查和数据库检查无明文凭证。
- 过期/撤销 token、错误 issuer/audience、重放 token 被拒绝。
- 所有高风险操作均可由 Audit Event 重建时间线。
- 高敏 Evidence 下载 URL 短期有效且不可跨用户复用（按存储能力约束）。

证据：RBAC 测试报告、OIDC 集成测试、Secret scan、审计导出、credential revoke 演练。
