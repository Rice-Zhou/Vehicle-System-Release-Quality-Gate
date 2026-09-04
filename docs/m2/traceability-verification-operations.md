# M2.5 Traceability Verification Snapshot 运维规范

## 1. 运行边界

M2.5 只在 Pilot Profile 中启用异步 Traceability Verification。它消费已经固定在 PostgreSQL 中的 Locked Manifest、M2.3 Issue Snapshot 和 M2.4 Edge Revision，生成不可变 Snapshot；执行期间不访问 Jira、GitHub、CI、Device 或 Company 环境，也不产生 `Verified=true`。

默认保持入口与 Worker 关闭：

```text
VSRQG_TRACEABILITY_VERIFICATION_ENABLED=false
VSRQG_TRACEABILITY_VERIFICATION_WORKER_ENABLED=false
```

只有候选 commit 的 M2.5 Gate、真实 PostgreSQL 测试、Evidence digest 与 Owner Gate 均通过后，Pilot 才可显式设置两个变量为 `true`。Worker 的 `VSRQG_TRACEABILITY_VERIFICATION_POLL_INTERVAL` 和 `VSRQG_TRACEABILITY_VERIFICATION_INITIAL_DELAY` 使用 ISO-8601 Duration；Pilot 建议保持默认 `PT1S`，不得用高频轮询代替容量验证。Company Profile 保持关闭，且不得继承 Pilot 的启用状态或身份。

## 2. 部署顺序

固定部署顺序如下：

1. 对 PostgreSQL 做可恢复备份，并记录备份 locator、当前 Flyway version 和应用镜像 digest。
2. 在目标数据库执行 V11 Migration Constraint Test；Migration 只允许 forward-only，不执行 down migration。
3. 部署与候选 commit 对应的同一 Backend 镜像，入口和 Worker 继续关闭。
4. 使用合成 Pilot fixture 执行一条完整 known chain 和每种固定 Gap 的 Smoke；确认 `Fixed`、`Included` 与固定 `Verified=false`，并复读 Snapshot digest。
5. 确认历史 Snapshot response bytes 与 digest 未变化、Worker backlog 可见、无异常 Dead Letter 后，才显式启用 Pilot 入口和 Worker。

known-chain/gap Smoke 只使用本地合成 Project、Release、Manifest、Issue Snapshot 与 Edge Revision。它不授权或连接真实 Company、Jira、GitHub、CI 或 Device。

## 3. 固定诊断与检查

对外和运维 Evidence 只允许使用固定诊断：

- `TRACEABILITY_INPUT_NOT_VALID`：固定输入身份、digest、authority 或状态不可信；关闭入口并保留 Run、Input Ledger 与 Migration/Gate Evidence。
- `TRACEABILITY_INPUT_LIMIT_EXCEEDED`：固定 Edge 超过 2,000；不得截断、缩小后冒充成功或修改旧 Run。
- `TRACEABILITY_VERIFICATION_RETRY_SCHEDULED`：基础设施失败仍处于有界重试内；检查 PostgreSQL 可用性和 Worker backlog。
- `TRACEABILITY_VERIFICATION_RETRY_EXHAUSTED`：Run 已 `FAILED` 且 Job 已 `DEAD_LETTER`；旧终态不得恢复。
- `PERSISTENCE_UNAVAILABLE`：数据库不可用；入口和读取保持 fail-closed，不回退到 JSON、文件或缓存。

固定诊断不得附带 SQL、JDBC URL、credential、token、原始 payload、外部 Issue 内容、本机路径或 stack trace。

## 4. Backlog 与 Dead Letter

仅在授权的只读数据库会话中按 `job_type='TRACEABILITY_VERIFY'` 检查以下聚合指标：

- `QUEUED` 数量、最早 `available_at` 与 backlog age；
- `RUNNING` 数量、最早 `started_at` 与超过 300 秒 lease 的数量；
- `DEAD_LETTER` 数量、固定 `diagnosticCode` 与对应 Verification Run ID；
- Run 的 `QUEUED/RUNNING/SUCCEEDED/FAILED` 数量，以及成功 Run 是否都指向完整 Snapshot。

不得导出 Job payload、请求原文或数据库异常文本。单个 poison job 达到三次 attempt 后必须保持 `DEAD_LETTER`；修复根因后，以新的 `Idempotency-Key` 创建新的 Verification Run。人工 retry 不更新旧 Run、旧 Job、旧 Snapshot 或旧 digest。

## 5. 故障恢复

应用故障时先把两个 Pilot flag 设置为 `false` 并重启上一兼容镜像。V11 扩展保留在数据库中，数据库只允许 roll-forward：Schema 缺陷通过新的 forward Migration 修正，禁止回滚 Migration、删除历史或绕过 immutable trigger。

数据库恢复后的固定动作是：

1. 校验 Flyway version 与预期 Migration chain。
2. 使用 PostgreSQL custom-format dump 把已完成 Snapshot 的 relational closure 恢复到独立 PostgreSQL，并从该恢复实例的固定关系事实重算 canonical digest。
3. 在独立恢复实例 restart 前后以 fresh connection 比较 `pg_postmaster_start_time()`，后值必须严格更新；随后以 fresh repository 验证 crash 前持久化的 `RUNNING` Job 在 300 秒 lease 后可 reclaim，attempt 单调递增。演练不得 restart 共享测试或生产数据库。
4. 执行 known-chain/gap/replay、transaction、concurrency、recovery 与 security Gate。
5. digest 不一致、Snapshot 不完整或固定输入不可加载时立即停止使用，保留 Evidence，并继续关闭入口。

禁止从最新 Edge Revision、最新 Issue Snapshot、外部系统、JSON、文件或缓存重建历史 Snapshot。历史结论只能从其固定输入和不可变 Snapshot 解释；任何新验证都必须创建新 Run。

## 6. 候选 Gate 与 Evidence

在干净且固定的候选 commit 上运行：

```powershell
pwsh -NoProfile -File scripts/tests/m2-5-verify-gates.tests.ps1
pwsh -NoProfile -File scripts/m2/verify-m25.ps1
```

Gate 固定依次执行 clean-tree、fixed-commit、contract、migration、domain、transaction、concurrency、replay/recovery、performance、secret、acceptance 和 evidence-digest。任一检查失败，总状态均为 `FAILED`，但仍生成脱敏的 `backend/build/m2/m2-5-evidence.json` 和 SHA-256 sidecar；失败不得被其他 PASS 覆盖。

上传前，性能/恢复子报告必须通过递归 exact-property allowlist，并以显式字段重建；总 Evidence 和两个子报告都必须拒绝 secret 与 Windows/Unix 绝对路径。任何异常字段或泄漏使 Gate 固定失败为 `EVIDENCE_INVALID`，相关子报告不得进入 Artifact。

性能 Evidence 固定使用 20 个 Issue、2,000 条 Edge、至少 3 个样本，记录 start/worker/query 的 p50、p95、max、硬上限、参考目标与 hardware/runtime metadata。参考目标为 P95 `≤1s/≤10s/≤1s`；共享 CI 的宽松硬上限只防算法退化，不等于 Company 性能验收。不得跳过、截断或缩小 fixture。

GitHub Actions workflow 只有 `contents: read`，不配置 Provider credential，不调用 Company 环境，并上传 `m2-5-evidence-${{ github.sha }}`。Evidence、双语 Pair Gate 与 exact-head CI success 只形成候选材料；Owner Decision 在独立复核前必须保持 `PENDING`。
