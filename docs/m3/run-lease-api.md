# 单设备 Run 与租约 API

Task 3 实现 Create/Cancel Run、Result 查询、Agent heartbeat/poll/ACK 与 Context GET。Event、Agent Result 提交与运行时 Evidence 上传分别属于后续任务。数据库中不预写成功结果；Run 完成不代表 Release PASS 或 Traceability Verified。

## 演示配置与发布输入

普通 Backend 默认关闭创建 Smoke Run。演示启用以下服务端配置：

| 配置 | 约束 |
|---|---|
| `vsrqg.demo.smoke.enabled` | 默认 `false`；显式开启才允许创建 Run、启动期限 Worker。 |
| `vsrqg.demo.smoke.agent-id` | 显式预登记 Agent ID，绑定现有 SERVICE principal、项目及 Device。 |
| `vsrqg.demo.smoke.device-id` | 显式预登记 Device ID，必须与 Agent 绑定一致；不自动选择设备。 |
| `vsrqg.demo.smoke.environment-config-base64` | 对实际 CONFIG 原始字节进行标准 Base64 编码；编码文本最多 87384 字符、解码最多 65536 字节。Base64 只是传输编码，不是加密。 |
| `vsrqg.test.deadline-worker.enabled` | 演示开启时默认 `true`，测试可显式关闭自动扫描。 |
| `vsrqg.test.deadline-worker.interval-ms` | 默认 1000 毫秒；业务期限使用 Server TimeProvider，与扫描周期分离。 |

CONFIG 是严格 UTF-8 JSON，只接受三个必填字段 `bootSessionId`、`buildId`、`buildFingerprint`，每个长度为 1–128。未知字段、重复字段、无效 UTF-8、缺失或超限内容明确失败。服务端对**原始 CONFIG 字节**计算 SHA-256，与 Locked Manifest 中 required CONFIG 的 checksum 比较；不把规范化后的 JSON 摘要当作文件摘要。该配置由受控演示初始化提供，不接受客户端路径或“环境已匹配”布尔值。

预登记 Device 的 `vehicle`、`platform` 必须与 Release 匹配；原 V12 Device 保留，未补充匹配属性时不能参与 Smoke Run。严格 CreateTestRunRequest 保持原字段：`releaseId`、`testPlan`、`deviceSelector`。selector.vehicle 和能力需求仍参与校验。

Create 只读取既有 PUBLISHED Plan/Case Version；不会发布或修改测试定义。后续演示初始化负责发布 `single-device-smoke` / `apk-launch-smoke` v1(normal) 与 v2(assertion-failure)，每个 Plan 一个 required Case、最大 Attempt 为 1。缺失、DRAFT、错误版本、能力不足或其他 required Artifact 类型会被拒绝。Manifest Lock 继续使用 M1 的实际文件校验与信任策略；普通默认 INCOMPLETE 不变。

## 行为与事务

Create 在事务中固定 Locked Manifest identity/digest、Environment bytes/digest、Plan/Case Version、Run、一个 QUEUED Attempt、Schema 验证后的 Context 及其 JCS SHA-256 摘要，同时写 Audit/Outbox。Context 内提前固定 commandId；Command 行仅在首次调度时创建。

Attempt 仅使用标准小写 UUID。UUID 由现有 IdGenerator 的一次 `nextId("att_")` 结果转换，不保留 att_ 别名；APK 标记、API 和全部 FK 使用同一个值。

全体写路径统一先锁 Agent 身份，再按 Run → Attempt → 后续 Evidence 的顺序加锁。Device 仅共享读取，不通过更新设备状态升级锁；活动 Run 的部分唯一索引保证同 Device 最多一个独占 Run。注册、续租、取消与 Worker 不反向获取执行锁。

Poll 最多返回一个 Command，空结果为 JSON null；等待最长 20 秒，不持有数据库事务。重复请求按既有幂等记录返回原响应；新的 poll 在 ACK 后不重复下发已接受 Command。ACK 接受时返回同一 leaseId、fencingToken、leaseExpiresAt；拒绝必须有 reasonCode，形成明确 ERROR 终态。

租约 90 秒，过期边界为 `now < expiresAt`。Heartbeat 通过认证 Agent、精确 currentCommandId 与 bootSessionId 绑定当前代；协议没有新增客户端 fencing 字段。相同心跳 key 重放不再次延长租约。旧命令、过期租约及终态不能恢复有效写入。AttemptAccess 要求调用方已有事务，保持行锁至依赖写完成；Evidence/Result 调用方仍须将请求 leaseId/fencingToken 与返回 binding 精确比较。

分配期限 60 秒；Command/Case 从派发开始最多 300 秒；Run 自创建最多 600 秒。断连进入 RECOVERY_PENDING，恢复窗口最多 120 秒且受 Case/Run 期限限制；过期租约不能恢复业务写入。Worker 用持久化租约/期限重建状态，Server 停机跨越整个恢复窗口时，首次扫描即写 TIMEOUT。无法恢复的 Agent 身份/能力或已报告 bootSession 变化写 ERROR，不重放安装动作。

Heartbeat、Worker 与 AttemptAccess 复用当前执行资格判定：已注册且能力满足固定 Case，车型/平台仍匹配 Locked Manifest。资格失效后不能续租或取得写权限，由 Heartbeat/Worker 原子写 ERROR 并释放预约；AttemptAccess 保持调用者事务内的拒绝语义。Worker 每轮按 Run ID 游标遍历活动 Run，每页最多 100 条，不读取终态历史，避免持续续租掩盖资格变化或后续页饥饿。恢复续租依据恢复前的 ACK 状态；未 ACK 的 DISPATCHED 恢复仅清理恢复标记，租约到期时间保持不变。

取消或期限终止在同一事务中递增 fencing token、封闭 Attempt、写唯一不可变 SERVER Result、Audit/Outbox，最后封闭 Run 并释放设备占用。取消映射 BLOCKED / CANCELLED_BY_OPERATOR。未收到执行开始事实时 Result.startedAt、durationMs 保留 null；ACK 仅说明接受 Command，不虚构实际 Case 开始。当前 Task 3 尚无 Evidence 运行数据，required Evidence 以明确 FAILED 记录，不能生成 PASS。

## 查询与范围

用户结果查询沿用 `GET /api/v1/test-runs/{id}/results` 的 `items` / `nextCursor` 契约。运行中无已提交 Result 时返回空 items；已有结果包含来源、状态、reasonCode、时间、Agent/Device、Evidence requirements 和 resultDigest。SERVER resultDigest 为不含派生 resultDigest 字段的固定 Result JSON 的 JCS SHA-256。Result、Run/Attempt 状态历史不被后续请求覆盖。

Context GET 仅对实际已派发给当前 Agent 的 Attempt 开放，返回固定 Schema 内容，不返回凭据、本机路径或原始设备序列号。Agent mTLS 与用户 JWT 保持独立；项目权限复用现有 ProjectAuthorizer、AgentAccess 与 Permission。

本地可运行 Schema、状态边界和 HTTP 身份/请求体测试。行锁、部分唯一索引、迁移及延迟约束必须由真实 PostgreSQL 集成测试验证；没有 Docker 的本机初始化失败不能标作数据库验证通过。
