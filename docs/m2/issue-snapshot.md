# M2.3 Release Issue Snapshot 运维规范

## 1. 目的与边界

M2.3 把某个 Release 在某次确定的 Issue FULL Sync 中观察到的非 tombstone Issue 物化为不可变 Snapshot。它只消费 PostgreSQL 中已经固定的 Release、Locked Manifest、Issue Source、Sync Run、Observation 与 Normalized Revision；创建路径不读取 Jira，也不改变 V0.1 Core Contract、Release-centric Authority、Evidence 或 Traceability 定义。

Pilot 可以启用该写入口。Company 模式尚未完成独立验收，配置层强制保持关闭，不得用环境变量绕过。

## 2. 接口与权限

- Endpoint：`POST /api/v1/releases/{releaseId}/issue-snapshots`
- 请求权限：OAuth scope `issue:snapshot`，并且调用方在目标 Project 中具有 `ENGINEER`、`RELEASE_MANAGER` 或 `ADMINISTRATOR` 角色。
- 请求头：必填 `Idempotency-Key`，长度 1～128。
- 请求体：只允许 `sourceId`。调用方不能指定 Sync Run、Mapping Version、Adapter Version、filter 或 canonicalization version。
- 成功响应：HTTP 201，返回 Snapshot ID、Release ID、固定 Sync Run、Snapshot Version、SHA-256 content digest、selected count 和创建时间。

不存在、越权或写入口关闭时保持资源隐藏语义；缺少 Locked Manifest 或不存在合格 FULL Run 时返回固定 409 冲突。响应与日志不得输出 Issue title、raw token、source reference、JQL、外部 URL 或凭据。

## 3. Pilot 选择规则

Pilot 的最大 Sync age 固定默认值为 `PT24H`。系统在事务内锁定 Release 与 Source，随后只选择该 Source 最新的 `SUCCEEDED`、`FULL` Run；不合格时不得回退到更早 Run。Run 的完成时间不得位于未来，且从完成到 Snapshot 创建的时间不得超过 `PT24H`。

Snapshot membership 只来自该 Run 的 `issue_sync_run_item`。系统验证 Project、Source、Run metadata、Observation count、Mapping Version 与 revision-local fact digest，然后排除 tombstone，并用 `release-issue-snapshot-jcs/v1` 产生稳定 bytes 与 digest。写入 Header、Items、Audit、Outbox 和幂等响应属于同一事务。

## 4. 固定诊断与处置

422 `ISSUE_SNAPSHOT_INVALID` 只暴露下列固定 violation code：

- `SYNC_RUN_STALE`：最新 FULL Run 位于未来或超过 Pilot age policy。完成新的 FULL Sync 后重试。
- `SYNC_OBSERVATION_INTEGRITY_FAILED`：Run metadata、Observation membership 或 count 不一致。保留数据库与 Gate evidence，禁止手工修改历史行。
- `SNAPSHOT_INTEGRITY_FAILED`：revision-local fact 或 Snapshot read-back digest 不一致。立即关闭写入口并保留证据，按数据完整性事件复核。

Gate 自身只输出 commit、check、status、测试计数与固定 diagnostic。`POSTGRESQL_RUNTIME_UNAVAILABLE` 表示本机缺少可用容器运行时，不等同于测试通过；必须由绑定 exact commit 的 Linux/Docker CI 补足。

## 5. Replay 与验收

在仓库根目录运行：

```powershell
pwsh -NoProfile -File scripts/tests/m2-issue-snapshot-gates.tests.ps1
pwsh -NoProfile -File scripts/m2/verify-issue-snapshot.ps1
```

Gate 固定依次执行 migration、sync-observation、snapshot-canonical、snapshot-integration、snapshot-replay、contracts、acceptance 七项检查。任一检查失败，总状态必须为 `FAILED` 并列出失败 check；不得静默跳过。Replay 测试在真实 PostgreSQL 中保存 baseline canonical bytes/digest，并在新增 Revision、激活新 Mapping、完成新 FULL Sync 后三次复读旧 Snapshot，逐字节与摘要比较。

本机无 Docker 时，保留 `POSTGRESQL_RUNTIME_UNAVAILABLE` 的失败结果，再由 GitHub Actions 的既有 `M1 Backend` workflow 在绑定的 exact commit 上执行；不得添加 Jira secret、外部写权限或伪造本地 PASS。验收结果进入独立 Acceptance Record，Owner 决定前保持 `PENDING`。

## 6. 关闭与恢复

发现完整性异常、重复失败或需要停止 Pilot 写入时：

1. 将 `VSRQG_ISSUE_SNAPSHOT_ENABLED=false` 并重启服务；已物化 Snapshot 仍可保留和复读。
2. 保存失败 commit、固定 Gate summary、CI Run 与测试报告定位；不得包含敏感内容。
3. 排查并通过七项 Gate，在 exact commit 的 Linux/Docker CI 获得成功结果。
4. 经 Project Owner 对恢复范围和 Evidence 独立复核后，才可将 Pilot `VSRQG_ISSUE_SNAPSHOT_ENABLED=true` 并重启。

该恢复步骤只恢复 Pilot Snapshot 写入口。它不授权 Company、真实 Jira 写入、merge、Tag、release 或 production deployment。
