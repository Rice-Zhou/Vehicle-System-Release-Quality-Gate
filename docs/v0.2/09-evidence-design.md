# 09 — Evidence and Collector Design

## 1. 一级实体原则

Evidence 不是 Test Result 的附属字段。Metadata 存 PostgreSQL，Payload 存 S3 兼容对象存储；两者通过不可变 evidenceId、object key、size 和 checksum 关联。

```text
Collector → local spool → Upload Session → Object Storage
                                  ↓ complete + verify
                         PostgreSQL Evidence Metadata
```

## 2. Metadata

必填：evidenceId、type、schemaVersion、releaseId、testRunId、capturedAt、collectorName/version、source、checksum algorithm/value、payload size、object key/URI、media type、upload state、createdAt。

可选：testResultId、attemptId、deviceId、artifactId、process/package、time range、fingerprint、severity、structured summary。URI 是受控内部引用；API 返回短期授权下载地址，不公开永久 URL。

类型：LOG、SCREENSHOT、CRASH、ANR、MEMORY、PERFETTO、DUMP、TEST_REPORT。扩展类型需 schema/version 和兼容读取策略；Collector 是 Agent Plugin，不进入 Core Contract。

## 3. 上传与完整性

状态：PENDING_UPLOAD → UPLOADING → VERIFYING → AVAILABLE；失败进入 REJECTED，过期会话为 EXPIRED。AVAILABLE 后 checksum、URI、size、collector version 和关联不可修改。

预签名 URL 仅允许指定 key、大小范围、content type 和短期有效期。Complete 后 Server 复核对象 metadata；高价值 Evidence 可异步重新计算 checksum。对象 key 不使用原始敏感设备标识。

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
- 未授权角色无法获取敏感 Evidence 下载链接。

证据：Collector contract tests、真实 Crash/ANR/Memory 样本、对象清单对账、权限测试、上传故障报告。
