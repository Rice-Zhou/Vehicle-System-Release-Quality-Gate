# M2.2 Jira 状态映射与 Adapter 版本权威设计

- Spec ID：`M2-KD-2026-09-01-01`
- Owner Design Direction：`APPROVED 2026-09-01`
- Written Spec Review：`PENDING`
- Architecture Baseline：V0.1 `0.1.0`（FROZEN）与 V0.2 `0.2.0`
- Parent Governance：中文 `e43d89d398165cb550cc7d3f6775a5d26383a407` / 英文 `74f0508764b57e25dc081167ab3213852a03c38b`
- 范围：只定义 M2.2 Jira Mapping Profile 与 Adapter Version Authority；不授权实现

## 1. 目的与触发证据

真实 Jira 端到端只读 Smoke 已证明 `Jira → Backend API/Worker → PostgreSQL → successful Cursor` 链路可运行，但 20 条 normalized status 均为 `UNKNOWN`。同一次运行还暴露出 Source 人工记录的 `jira-cli-pilot-v1` 与既有验收记录中的 `jira-cli-pilot-adapter-v1` 不一致。

本设计关闭两个实施设计缺口：为每个 Issue Source / Jira 项目提供独立、不可变、可审计的版本化 Mapping Profile；让运行代码的 Adapter Descriptor 成为 Adapter Version 的唯一权威，数据库只保存不可变快照。

本设计不改变 V0.1 Issue、Traceability、Release、Manifest、Evidence、Quality Result 或 Fixed/Included/Verified 语义。Mapping Profile 是 Source Adapter 的实施配置，不是新的 Core Entity。

## 2. 不可协商边界

- 一个 Mapping Profile 只属于一个 Project 内的一个 Issue Source；禁止跨 Source 或跨 Project 复用激活关系。
- Profile 同时管理 status 与 severity，避免两个独立 Mapping Version。
- 未知状态继续映射为 `UNKNOWN` 并产生 `UNKNOWN_STATUS`；未知严重度继续映射为 `UNKNOWN` 并产生 `UNKNOWN_SEVERITY`。
- 未知值不得默认映射为 `CLOSED`、`RESOLVED`、`PASS` 或任何成功语义。
- Profile 只能插入，禁止 UPDATE/DELETE；激活新版本不得改写旧 Sync、Revision 或 Snapshot。
- Adapter Version 不能由 API、环境变量、Operator 或 Seed 自由命名。
- Profile 定义不得包含 Issue 标题、人员、URL、Description、Comment、Credential 或原始 Issue Payload。
- Jira 继续只读、单项目、最多 20 条；Company、Jira 写回、Task 5、merge、Tag、release 与 production deployment 保持阻断。

## 3. 方案比较与决定

采用“PostgreSQL 不可变 Profile + 代码 Descriptor”。PostgreSQL 保存不可变 Profile 内容与摘要，Source 保存当前激活 Mapping Version；代码 Descriptor 声明唯一 Adapter Version。每次 Sync 固定并校验两个版本。该方案可审计、可重放且支持项目隔离。

未选择仓库外 YAML 作为唯一 Authority，因为旧文件丢失后无法解释历史；未选择环境变量或 Spring Map，因为缺少版本、Audit 与 Project Scope；未选择配置中心或 UI，因为当前 MVP 没有真实需求支撑其成本。

本方案只增加一个小型 PostgreSQL Authority 和一个 Adapter Runtime Descriptor，不引入 UI、Broker、第二数据库、配置中心或微服务。

## 4. 逻辑架构

```text
Approved Mapping Definition
            ↓
Mapping Profile Validator + Canonicalizer
            ↓
Immutable issue_mapping_profile
            ↓ activate in one transaction
issue_source.mapping_version + Audit + Outbox
            ↓ pin at StartIssueSync
Issue Sync Run(adapterVersion, mappingVersion)
            ↓ open exact runtime
IssueSourceRuntimeRegistry
   ├─ verify Adapter Descriptor
   ├─ load exact Mapping Profile
   └─ verify profile digest/source/project
            ↓
JiraCliPilotAdapter + JiraIssueMapper
            ↓
Normalized Issue Revision + Warning
```

`IssueSourceRuntimeRegistry` 属于 Adapter infrastructure。它返回为一个 Sync Run 固定版本的 Runtime；`RunIssueSync` 继续只依赖 `IssueSourcePort`，Core 与 Snapshot 不感知 Jira Profile。

## 5. 数据模型

新增不可变实施表 `issue_mapping_profile`：

| 字段 | 约束 | 说明 |
|---|---|---|
| `id` | PK，`varchar(40)` | 内部标识 |
| `project_id` | FK，非空 | Project Scope |
| `source_id` | 复合 FK，非空 | 必须属于同一 Project 的 Issue Source |
| `schema_version` | 非空 | Profile Schema |
| `mapping_version` | 非空，`varchar(80)` | canonical definition 的 SHA-256 |
| `definition` | `jsonb`，非空 | 已验证的 status/severity 定义 |
| `created_by` | FK，非空 | 激活操作者 |
| `created_at` | 非空 | Authority 写入时间 |

必需约束：`UNIQUE(source_id, mapping_version)`；`mapping_version` 必须匹配 `^sha256:[0-9a-f]{64}$`；`(source_id, project_id)` 复合 FK 指向同一 `issue_source`；数据库 Trigger 拒绝 UPDATE/DELETE；应用在边界计算内容摘要并在读取时重新验证。

现有 `issue_source.mapping_version` 保留为当前激活版本选择器，不是第二份 Profile 内容。`issue_sync_run.mapping_version` 和 `normalized_issue.mapping_version` 继续保存执行时快照。历史值不得批量重写。

## 6. Profile 定义与确定性规范化

概念 Schema：

```json
{
  "schemaVersion": "jira-mapping-profile/v1",
  "normalizationVersion": "unicode-nfc-trim-root-lower/v1",
  "unknownStatusPolicy": "MAP_TO_UNKNOWN_WITH_WARNING",
  "unknownSeverityPolicy": "MAP_TO_UNKNOWN_WITH_WARNING",
  "statusAliases": {
    "OPEN": ["open", "to do"],
    "IN_PROGRESS": ["in progress"],
    "RESOLVED": ["resolved"],
    "CLOSED": ["closed", "done"]
  },
  "severityAliases": {
    "CRITICAL": ["highest", "critical"],
    "HIGH": ["high"],
    "MEDIUM": ["medium"],
    "LOW": ["low", "lowest"]
  }
}
```

示例只使用合成通用 Token，不代表公司实际工作流。真实 Profile 经受控 API 进入 PostgreSQL，不提交 Git 或 CI Artifact。

Token 规范化顺序固定为：验证非空、无控制字符且在长度上限内；Unicode NFC；去除首尾 Unicode 空白；`Locale.ROOT` 小写；精确字符串匹配。

禁止正则、通配符、包含匹配、前缀匹配和模糊匹配。两个 Alias 若规范化为同一 Token 但指向不同目标，整个 Profile 拒绝。目标枚举只能是现有 `IssueStatus` 与 `IssueSeverity` 非 `UNKNOWN` 成员；`UNKNOWN` 仅由未匹配策略产生。

Definition 使用 RFC 8785 canonical JSON 计算摘要：

```text
mappingVersion = "sha256:" + lowercaseHex(SHA-256(canonicalDefinitionBytes))
```

Project ID、Source ID、操作者和时间不进入摘要；相同语义内容可以有相同 digest，但激活关系仍按 Source 隔离。

## 7. Adapter Version 单一权威

每个运行时 Adapter 提供 `IssueSourceRuntimeDescriptor`，至少包含 source type、adapter ID、adapter version、支持的 Mapping Schema 与 Transport Version 范围。

Jira CLI Pilot Adapter 的唯一 Adapter Version 为 `jira-cli-pilot-adapter-v1`。数据库 `issue_source.adapter_version` 与 `issue_sync_run.adapter_version` 只保存该值的快照。配置入口不得接收 Adapter Version。

Alias 或映射结果变化只改变 Mapping Version；Profile Schema 或 Token 规范化算法不兼容时升级 Mapping Schema，并按兼容性决定是否升级 Adapter Version；CLI argv、字段边界、解析协议或 Adapter 行为不兼容时升级 Adapter Version；Jira CLI 可执行文件版本记录为 Transport Version，不替代 Adapter Version。

## 8. 受控 Profile 激活操作

MVP 不提供管理 UI。新增最小受鉴权 Backend operation：

```text
POST /api/v1/issue-sources/{sourceId}/mapping-profiles:activate
Scope: issue:configure
Headers: Idempotency-Key
Body: schemaVersion + mapping definition
Response: profileId + sourceId + schemaVersion + mappingVersion + activatedAt
```

请求不得包含 `mappingVersion` 或 `adapterVersion`。Application 在一个事务中完成 Project authorization、Idempotency、Source 锁、Profile 验证与插入、Source 激活、Audit 和 Outbox。任一写入失败时整体回滚，旧 Profile 继续生效。

响应、Audit、Outbox、日志和 Problem Details 只保存 Profile ID、Schema Version、Mapping Version 与固定诊断，不复制完整工作流 Token。Profile `definition` 只存在于受控 PostgreSQL Authority。

## 9. Sync 版本固定与竞态

`StartIssueSync` 在同一事务中锁定 Source，并把当前 `adapter_version` 与 `mapping_version` 复制到 Sync Run。Profile 激活使用同一 Source 锁，因此不存在半激活 Run。

Worker 必须使用 Sync Run 固定值打开 Runtime：根据 Source Type 选择唯一 Descriptor；比较 Descriptor Adapter Version 与 Run Adapter Version；按 `(source_id, mapping_version)` 加载精确 Profile；重新计算 canonical digest；验证 Project、Source、Schema 与 digest；创建固定该 Profile 的 `JiraIssueMapper`；全部通过后才启动 Jira CLI Process。

Profile B 在 Run A 入队后激活时，Run A 继续使用 Profile A；新 Run B 使用 Profile B。不得在一个 Run 内热切换 Mapper。

## 10. 失败语义

| 场景 | 固定诊断 | 行为 |
|---|---|---|
| 没有激活 Profile | `MAPPING_PROFILE_NOT_CONFIGURED` | Jira 调用次数为零，Run FAILED |
| 内容与摘要不一致 | `MAPPING_PROFILE_INTEGRITY_FAILED` | Jira 调用次数为零，Run FAILED |
| Schema 不受支持 | `MAPPING_SCHEMA_UNSUPPORTED` | Jira 调用次数为零，Run FAILED |
| Adapter Version 不一致 | `ADAPTER_VERSION_MISMATCH` | Jira 调用次数为零，Run FAILED |
| Mapping Version 不一致 | `MAPPING_VERSION_MISMATCH` | Jira 调用次数为零，Run FAILED |
| 未知状态 | `UNKNOWN_STATUS` Warning | Issue 为 `UNKNOWN`，Sync 可成功 |
| 未知严重度 | `UNKNOWN_SEVERITY` Warning | Severity 为 `UNKNOWN`，Sync 可成功 |
| Profile 输入非法 | 422 Problem Details | 激活失败，旧 Profile 保持 |

所有失败保持可见，不回退到硬编码 Map，不推进 successful Cursor，不将失败改写为 PASS。

## 11. 安全与隐私

- `issue:configure` 只授予项目内受控配置角色；`issue:sync` 不能隐式配置 Profile。
- 外部请求在边界验证；SQL 使用参数化调用；Profile 大小、Alias 数量和 Token 长度有固定上限。
- Git、Fixture、CI Artifact 与验收报告不得包含真实公司工作流 Token。
- Profile API、日志与 Problem Details 不返回完整 definition。
- Credential、Jira CLI config、Issue Payload 与 Mapping Profile 分离。
- Company Profile 默认不启用该 Pilot Adapter；本设计不构成 Company Ready Evidence。

## 12. Migration、部署与回滚

实施采用 forward-only Expand：创建表、约束、索引、immutable Trigger 和新的 Application path。Migration 不猜测公司状态，不用真实 Token seed 数据，也不修改历史 Sync/Revision/Snapshot。

启用新版本前先通过合成 Profile 激活与 Fixture Contract。Jira Source 没有受控 Profile 时新 Sync 明确失败。应用回滚不能解释新 Mapping Version 时保持 fail-closed，优先 roll-forward；必须回到旧应用时，通过受审计操作重新选择旧兼容 Profile/版本，不删除新 Profile、不覆盖历史 Run。

不新增服务、Broker、数据库、容器或 UI。Pilot 仍使用现有 Backend 与 PostgreSQL。

## 13. 测试矩阵

- Profile/Mapper：RFC 8785 canonicalization、SHA-256、Unicode NFC、`Locale.ROOT`、Alias 冲突、非法枚举、未知 Schema、超限输入、未知 Token Warning 与三次 digest 重放。
- PostgreSQL/Application：Profile 不可变、跨 Project/Source 拒绝、权限、幂等、Audit、Outbox、失败整体回滚、调用方版本注入拒绝、空库/升级/重复 Migration。
- Sync/Runtime：Run A/Profile A 与 Run B/Profile B 竞态；五类版本/完整性失败时 Jira Process Runner 调用次数为零；失败不推进 Cursor；旧 Snapshot digest 不变。
- 安全/真实复测：Secret/log scan 不出现 definition、Token、Issue 内容、URL、路径或 Credential；Fixture 只使用合成 Alias；真实复测须重新取得 Owner 授权并保持单项目、最多 20 条、只读与脱敏输出。

## 14. 条件关闭与非目标

关闭 `M2-2-JIRA-E2E-SMOKE-001` 的两个实施条件需要：Mapping Profile、digest、激活、版本固定和 Fixture 回归全部通过；Adapter Version 只来自 Descriptor；双语实现/TDR/tests/Gate 配对；取得独立真实复测授权；真实只读复测 status Mapping Warning 为零。若出现新的未知状态，继续保持 `CONDITIONAL`。

本设计不关闭 Company、Stale Job recovery、全量 Jira、REST Adapter、内部 Issue API、Issue 写回、Release Issue Snapshot、Traceability 或 M3 条件，也不授权生产代码。

## 15. Written Spec Review Gate

本 Spec 与 `TDR-015` 提交后由 Project Owner 书面复核。只有 Owner 明确批准 `M2-KD-2026-09-01-01` Written Spec Review，才创建逐文件、逐测试、逐提交 Implementation Plan。

批准本书面规范不授权生产代码、Migration、真实 Jira 查询、Jira 写操作、Company、merge、Tag、release 或 production deployment；Implementation Plan 和实施执行继续使用独立授权。
