# 09 — Evidence and Collector Design

## 1. 一级实体原则

单设备演示 Profile 例外见 [TDR-025](tdr/TDR-025-local-demo-evidence-payload.md)：PostgreSQL 保存 Metadata，Backend 仓库外受控目录保存 Payload。新增 `PUT /agent-api/v1/evidence/uploads/{id}/payload` 使用独立 Agent mTLS 与 `agent:evidence:write`，按 Agent/project/Attempt Session、有效租约、失效时间及 bytes digest 授权与重传，不使用 Idempotency-Key。Task 4 实现该端点的流式保存及 Complete 校验；不得把 Metadata 创建或上传字节成功解释为 AVAILABLE。

Evidence 不是 Test Result 的附属字段。Metadata 存 PostgreSQL，Payload 存 S3 兼容对象存储；两者通过不可变 evidenceId、object key、size 和 checksum 关联。

```text
Collector → local spool → Upload Session → Object Storage
                                  ↓ complete + verify
                         PostgreSQL Evidence Metadata
```

## 2. Metadata

必填：evidenceId、type、schemaVersion、releaseId、testRunId、capturedAt、collectorName/version、source、checksum algorithm/value、payload size、object key/URI、media type、upload state、sensitivity、createdAt。

可选：testResultId、attemptId、deviceId、artifactId、process/package、time range、fingerprint、severity、structured summary。sensitivity 为 GENERAL、RESTRICTED 或 HIGH，创建后只能通过受审计的重新分类流程提高/降低。URI 是受控内部引用，不通过 Metadata API 暴露对象存储永久地址。

类型：LOG、SCREENSHOT、CRASH、ANR、MEMORY、PERFETTO、DUMP、TEST_REPORT。扩展类型需 schema/version 和兼容读取策略；Collector 是 Agent Plugin，不进入 Core Contract。

## 3. 上传与完整性

状态：PENDING_UPLOAD → UPLOADING → VERIFYING → AVAILABLE；失败进入 REJECTED，过期会话为 EXPIRED。AVAILABLE 后 checksum、URI、size、collector version 和关联不可修改。

预签名 URL 仅允许指定 key、大小范围、content type 和短期有效期。Complete 后 Server 复核对象 metadata；高价值 Evidence 可异步重新计算 checksum。对象 key 不使用原始敏感设备标识。

上传预签名 URL 与下载授权是不同边界：Agent 的单对象受限上传可以使用预签名 URL；用户下载必须遵循第 8 节的 sensitivity policy。

重复 payload 可内容去重存储，但每次采集仍创建独立 Evidence Metadata，以保留 Release/Test Run 语境。

## 4. Collector Plugin Contract

```text
CollectorPlugin
  descriptor() → type, version, capabilities, schemaVersions
  start(context, config) → session
  mark(testCaseContext)
  collect(trigger, timeWindow) → EvidenceCandidate[]
  stop() → summary
  health() → health
```

Collector 输入仅含执行上下文和采集配置；输出客观数据与诊断，不包含 BLOCK/WARNING/PASS。Plugin 失败隔离到自身和对应 Evidence requirement，不破坏 Release/历史 Evidence。

## 5. Crash Collector

### 来源与检测

- Android logcat crash buffer、ActivityManager process death、tombstone（权限允许）、DropBox/system_server 事件、目标 app crash marker。
- 使用 Agent/Device 单调时钟窗口与 Test Case marker 关联，保存原始时间和校正信息。

### 采集与关联

采集 package/process、UID（必要时脱敏）、artifact/package version、signal/exception、top frames、timestamp、Device、Release、Run、Attempt 和原始日志/tombstone。Artifact 关联通过 Locked Manifest 的 package/signature/version/checksum 解析；无法唯一匹配时保持未关联并记录原因。

### Fingerprint 与去重

Fingerprint versioned：规范化 exception/signal + process/package + 前 N 个非噪声 stack frame，散列为 `crashFingerprint`。同一 Run/Device/Artifact/窗口内相同 fingerprint 可聚合 occurrence count，但每个原始 Payload 可追溯。跨 Release 仅用于查询，不合并 Evidence。

Collector 不判断严重度是否 BLOCK；它可输出客观分类和 fingerprint，严重度/策略由 Quality Rule 决定。

## 6. ANR Collector

检测来源包括 ActivityManager ANR 事件、`traces`/DropBox、目标进程无响应信号和测试框架 timeout 诊断。记录 process/package、timestamp、reason、duration（若可证明）、Device、Release、Run、Attempt、Artifact 与 traces Evidence。

ANR fingerprint versioned：package/process + normalized reason + 主线程关键 frames + blocked resource clue。duration 不可获得时为空，不能填零。去重规则与 Crash 相同。

Collector 可输出原始系统分类；`CRITICAL` 等 Gate 严重度由规则结合 package criticality、次数和验证范围计算，避免硬编码。

## 7. Memory Collector

采样支持：PSS、RSS、Java Heap、Native Heap、Process Memory、System Memory。每个 sample 包含 metric、value、unit、process/package、capturedAt、source command、采样质量和 Device/Run/Attempt。

时间序列可用压缩 JSON/CSV/Parquet Payload，Metadata 保存窗口、采样周期、样本数和 min/max 摘要以便检索。缺失样本、进程重启和采集开销必须显式标记。

Collector 只报告如 `PSS=420 MiB`；“连续三次高于 400 MiB 则 BLOCK”只能存在于版本化 Quality Rule。

## 8. 生命周期、保留与隐私

- Evidence 跟随 Release 审计周期；Metadata 与决定引用不可早于 Payload 清理。
- 分层存储/过期必须由策略配置，执行前检查 legal hold 和 Quality Result 引用。
- 清理写 Audit Event 和删除清单；对象删除失败进入可重试 reconciliation。
- 日志上传前按公司规则屏蔽 token、账号和个人数据；原始高敏 Evidence 使用更严格权限。

### 8.1 下载路径

TDR-025 演示 Profile 的 GENERAL/RESTRICTED/HIGH 均通过既有 Payload GET 流式下载；GENERAL/RESTRICTED 使用全部项目角色可用的 `evidence:read`，HIGH 使用仅 QUALITY_OWNER/ADMINISTRATOR 可用的 `evidence:read:sensitive`。OpenAPI 的 `x-demo-permission-by-sensitivity` 明确该 Profile 条件权限，默认 HIGH 权限基线保留。所有请求重新验证用户 JWT、项目、purpose 并记录 Audit，不返回无鉴权 URL，不在响应或日志泄漏本机路径、凭据和原始设备序列号。Task 4 已提供本地下载运行实现，数据库验证状态见工程验证记录。以下对象存储默认 Profile 的语义继续成立。

- GENERAL/RESTRICTED：Backend 在每次申请时校验 principal、project scope、permission、purpose、retention/legal hold 状态后，可返回不超过 60 秒的单对象 Presigned Download URL。该 URL 是 Bearer capability，可能在过期前被持有者复用；风险由短 TTL、最小对象权限、TLS、禁止日志记录和下载申请 Audit 控制，不宣称绑定用户。
- HIGH：禁止向客户端返回对象存储 Presigned URL。客户端使用 GET `/api/v1/evidence/{evidenceId}/payload`，Backend/受控 Gateway 对每次 HTTP 请求重新验证用户 token、项目范围、`evidence:read:sensitive`、purpose 和可选审批，再以 server-side credential 流式读取对象。
- HIGH 响应设置 `Cache-Control: no-store`、安全 Content-Disposition、类型白名单和速率/Range 限制；不得 3xx 跳转到对象存储，不把 token、object key 或内部 URL 写入 Log/Audit Payload。
- Audit 在开始传输前记录 actor、Evidence ID、purpose、decision、request ID 和授权依据；传输失败追加结果事件。Audit 失败时 fail closed。

复制 HIGH payload path 不携带授权。User B 访问 User A 使用过的 path 时必须以 User B 自身身份重新授权；无权限返回 403。若未来使用可验证的用户绑定下载 Gateway，必须通过新 TDR 证明等价控制后才能替代 Backend Proxy。

## 9. 故障处理

- 本地磁盘不足：Agent DEGRADED，停止新任务，保护 required Evidence。
- 上传失败：保留 spool 与会话状态，指数退避；不得标记 AVAILABLE。
- checksum 不符：REJECTED，保留诊断，重新上传创建新 session。
- 对象存在但 DB 事务失败：inventory reconciliation 标记 orphan 并安全清理/恢复关联。
- DB 有 Metadata 但对象缺失：标记 INTEGRITY_ERROR，关联 Release 禁止新 Evaluation。
- Collector 崩溃：对应 requirement FAILED，其他 Collector 继续；Run 明确呈现缺失。

## 10. 验收

- 所有类型均有 Metadata schema、Payload 示例和 checksum 复验。
- Crash/ANR 重复事件可聚合但原始证据可追溯。
- Memory 阈值不出现在 Collector 配置/代码契约中。
- 上传中断、checksum 错误、孤儿对象和缺失对象有恢复演练。
- 未授权角色无法获取 Evidence Payload；HIGH 不返回 Presigned URL。
- User A 的 HIGH payload path 由 User B 访问时重新鉴权并返回 403；响应与日志中不存在对象 URL/token。

证据：Collector contract tests、真实 Crash/ANR/Memory 样本、对象清单对账、普通 Evidence Presigned URL TTL Test、HIGH Backend Proxy 跨用户测试、日志泄漏扫描和上传故障报告。

## 11. Task 4 本地运行接口与恢复

本 Profile 默认关闭。显式配置 `vsrqg.demo.evidence.enabled=true`，并指定预先创建的 `vsrqg.demo.evidence.root` 绝对目录；该目录必须在仓库、`static`、`public`、`wwwroot` 之外。启动及每次文件访问检查根目录、祖先链接、普通文件和服务账号写权限；POSIX 拒绝其他账号/组的访问权限，Windows ACL 只信任服务账号、SYSTEM 与 Administrators。本 Profile 将 Tomcat connection/upload 空闲超时设为 30 秒，拒绝超限后的剩余请求体吞读。PUT 另以 Servlet AsyncContext 和非阻塞 ReadListener 执行 30 秒总接收期限（`vsrqg.demo.evidence.upload-timeout` 只可配置为 1 ms–30 s）；超时返回带 requestId 的 `408 UPLOAD_TIMEOUT`，清理本次临时候选。读边界与 EOF 同时检查单调时钟，持续慢流不能刷新总期限。该控制不提供 WORM 或对管理员的防篡改保证。

`vsrqg.demo.evidence.sensitivity` 默认 `RESTRICTED`，可由操作员在启动时固定为 GENERAL/RESTRICTED/HIGH；Agent 请求不能自行降低敏感度，已创建 Session 不随配置改变。保留期限默认未设置；设置 `retention_until` 后到期拒绝下载，legal hold 保留内容时仍要求同样的当前主体权限。

Create 严格复用 Agent Schema，返回 `{uploadId,evidenceId,uploadUrl,expiresAt}`。`uploadUrl` 是同 Backend 相对 URI，Agent 应以已配置 mTLS Backend origin 解析。既有 Create/Complete 没有 lease/fencing 字段：Create 在 Attempt 锁内捕获二者到 Session；PUT/Complete 逐次对比当前 binding。锁顺序 Agent → Run → Attempt → Session。PUT 的 preflight 短事务返回后再接收网络字节，等待请求体时不占有业务锁；EOF 后 postflight 短事务重新解析当前 Agent 权限，检查当前 Server 时间、Session/lease/终态并与原 binding 比较。候选 size/hash 与声明一致后才能 create-only 发布固定文件并提交 UPLOADING；短流或错误 hash 只删除本次候选，既有正确文件及 DB rollback 后正确孤儿文件不变。同 Session 可正确重传。EOF、超时、错误回调竞争只有一个终结者。Complete 持锁到 Metadata、Audit 和 Outbox 事务提交。

LOG 最多 1 MiB，必须严格 UTF-8，声明 `text/plain`；SCREENSHOT 最多 8 MiB，声明 `image/png` 且 PNG 固定签名正确。文件层先写独占临时文件，使用 64 KiB buffer 与同目录 hard-link create-only 发布保存已收到的候选 bytes（文件系统不支持时明确失败，不切换实现）；此文件不是 AVAILABLE。Complete 对同一 Session 的实际 size、SHA-256、type 与 Create/Complete 声明复验后才固化 Metadata。空流、截断、超限、断流、不同 bytes 重传不会覆盖已有文件。错误 type/hash 的 Complete 将 Session 记为 REJECTED；失败事务不会伪装成文件系统与 PostgreSQL 的原子事务。文件已存在而事务回滚时，保留原文件供同 Session 重试与对账。

`:download` 的既有 `reason` 即 purpose。`evidence_download_grant` 是申请记录，不是角色权限表；绑定 actor/project/evidence/purpose，60 秒后同 key 明确返回 `DOWNLOAD_GRANT_EXPIRED_NEW_KEY_REQUIRED`。`DownloadGrant.url` 在本 Profile 是 URI reference：`/api/v1/evidence/{evidenceId}/payload?grantId=…`。GET 每次重新校验 JWT、当前项目角色、对应 scope、grant owner/purpose/expiry 与 retention/legal hold。HIGH 额外要求 `evidence:read:sensitive`。Audit 先提交，之后才能开始输出 bytes；Range 返回 416，不重定向，响应 no-store。

`EvidenceReconciler` 提供可直接调用的应用操作：`reconcile(Set<evidenceId>)`、`backupInventory(Set<evidenceId>)`、`verifyRestored(List<EvidenceInventoryItem>)`，每次 1–1000 个固定 ID。清单含 Evidence ID、Upload ID、size/SHA-256 与 Metadata JCS digest，不返回磁盘路径。操作不遍历任意目录、不删除未知文件。备份须在停止新写入后成对保存 PostgreSQL 和清单指定的 Payload；恢复先还原数据库与这些文件，再逐项调用 `verifyRestored`。测试以真实 `pg_dump`/`pg_restore` 和单独文件目录演练该流程。

Metadata 查询、对账与恢复会追加 `evidence_integrity_observation`，对缺失/损坏公开 `INTEGRITY_ERROR`。已封闭 Run 的 Result、Evidence Metadata 与摘要保持原状。`AttemptEvidence.resolve` 仅接受相同 binding 的 Evidence；`seal` 在调用者的 Attempt 事务内封闭未完成 Session，供后续 Task 5 消费。
