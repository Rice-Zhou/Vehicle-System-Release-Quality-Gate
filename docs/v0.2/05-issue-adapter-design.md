# 05 — Issue Adapter Design

## 1. 边界

Core Domain 只认识 Normalized Issue 和 Release Issue Snapshot，不认识 Jira field ID、JQL、内部系统状态码、分页或认证方式。

```text
Jira API ── Jira Adapter ──┐
                           ├─ IssueSourcePort → Normalized Issue → Snapshot
Internal API ─ Adapter ────┘
```

## 2. 统一 Port

概念接口：

```text
IssueSourcePort
  capabilities() → SourceCapabilities
  fetchChanges(cursor, filter, pageSize) → IssuePage
  fetchByIds(sourceIssueIds) → IssueBatch
  updateIssue(command, expectedSourceVersion) → ExternalUpdateResult
  health() → SourceHealth
```

`IssuePage` 必须含 normalized issues、next cursor、source watermark、同步时间和 mapping version。`updateIssue` 是受控可选能力；不支持时明确返回 CAPABILITY_NOT_SUPPORTED。

## 3. Normalized Issue

必填：source、sourceIssueId、title、severity、status、snapshot timestamp、source version/reference。可选：description summary、fixVersion、component、assignee reference、labels、required、verification criteria。

标准状态仅用于跨源查询：OPEN、IN_PROGRESS、RESOLVED、CLOSED、UNKNOWN。原始状态和映射版本保留在 Snapshot 中；UNKNOWN 不得默认映射为 CLOSED。

映射配置版本化并纳入同步报告。字段缺失产生 Mapping Warning 或 Mapping Error；required 字段错误会使本次同步失败。

## 4. 同步机制

1. 后台任务读取上次成功 cursor/watermark。
2. Adapter 分页获取变化，校验并映射。
3. 每页以 source version 幂等 upsert 为新的 Normalized Issue version。
4. 全部页面成功后原子推进 cursor；部分失败不推进最终 watermark。
5. Release Snapshot 只能引用一个已成功完成的 sync run。

首次全量同步与增量同步使用同一数据模型。外部删除用 tombstone version 表达，不物理删除历史 Snapshot。

## 5. Jira Adapter

### 接入与认证

- 使用公司 Jira REST API 的受支持版本和 HTTPS。
- 优先 OAuth 2.0/service credential；必要时 PAT 由 Secret Manager 注入。
- Secret 不进入 Git、Manifest、数据库明文字段或日志。

### 查询与分页

- 使用配置化 JQL 按 `updated` + stable tie-breaker 增量查询。
- 显式指定字段集合，避免请求全部字段。
- 遵循 Jira 返回的分页 token/startAt/maxResults，直到服务端表明结束；不自行假设总数稳定。
- 保存 query、watermark、最后 issue key 和 Jira API/mapping version 以支持恢复。

### 限流与重试

- 429 遵循 `Retry-After`；5xx/连接错误使用有上限的指数退避和抖动。
- 401/403、JQL 错误、字段映射错误不自动无限重试，进入人工可见失败。
- 达到重试上限后 Sync Run FAILED，保留已获取诊断但不推进成功 cursor。

### Jira → Normalized 映射

| Jira | Normalized | 规则 |
|---|---|---|
| `key` | `sourceIssueId` | 原样稳定引用 |
| `summary` | `title` | 必填，去除不可见控制字符 |
| `priority`/severity custom field | `severity` | 版本化映射，未知为 UNKNOWN |
| `status` | `status` | 配置映射；未映射为 UNKNOWN |
| `fixVersions` | `fixVersion` | 仅事实字段，不定义 Release |
| `components` | `component` | 规范化列表 |
| `updated` + changelog/version | `sourceVersion` | 构造稳定单调版本/ETag |

Jira 状态变化生成新 Normalized version；已存在 Release Snapshot 保持不变。

### Issue 更新

更新使用显式命令白名单和预期 source version，记录前后状态、操作者、外部响应引用。V0.2 默认读取与快照优先；自动改变 Jira 状态不是 Release Gate 必需路径。

## 6. 内部问题系统 Adapter

实现相同 Port、Normalized 模型、Sync Run 和错误语义。其私有字段只存在于 adapter mapping 层。若不支持增量游标，则使用更新时间窗口 + ID 去重，并在 Sync Report 标记一致性能力。

上线前必须提供：认证方式、分页终止条件、限流规则、状态/严重度映射、版本标识来源、删除语义和测试沙箱或录制 fixture。

## 7. 外部不可用

- 保留最近成功 Snapshot，但本次请求标记 STALE，显示年龄和来源。
- 创建新的 Release Issue Snapshot 时，策略必须显式决定是否允许使用 STALE 数据；默认 Gate 输入拒绝超过配置上限的数据。
- 不把旧数据伪装为本次同步成功，不把外部错误吞掉。
- 已完成历史 Quality Result 不受外部当前状态影响。

## 8. 版本与观察

每个 Sync Run 保存 adapter version、API version、mapping version、filter version、cursor、数量、警告、错误与耗时。指标包括成功率、延迟、429、mapping error、snapshot age；日志按 sourceIssueId/requestId 关联并去敏。

## 9. 验收

- Jira 与内部系统通过同一契约测试套件。
- 分页中断、429、5xx、401、未知状态和重复页均有确定结果。
- 同一 source version 重放不产生重复 Issue。
- Jira 后续变化不改变历史 Release Snapshot/Quality Result。
- Core 模块依赖扫描中不存在 Jira SDK/DTO。

证据：Adapter contract tests、映射 golden files、失败注入报告、同步报告样本、历史快照不变性证明。
