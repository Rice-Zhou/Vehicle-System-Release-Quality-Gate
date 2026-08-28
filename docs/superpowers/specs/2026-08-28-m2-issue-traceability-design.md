# M2 Issue Snapshot and Traceability 启动设计

- Spec ID：`M2-KD-2026-08-28-01`
- Owner Design Direction：`APPROVED 2026-08-28`
- Written Spec Review：`PENDING`
- Architecture Baseline：V0.1 `0.1.0`（FROZEN）与 V0.2 `0.2.0`
- Parent Implementation：中文 `5117668c54970fbab3d830fc88fa983919756c8d` / 英文 `a9af41e185e880f15f2c0a99cff0ea0c11787927`
- 计划周期：第 7～11 周
- 容量：一名主要开发者，每周 10～12 小时，预留 20% Contingency

## 1. 目的

M2 把已锁定 Release Manifest 扩展为可审计的 Issue Snapshot 与 Traceability 闭环。它必须证明：外部 Issue 可以经 Adapter 归一化并形成不随来源变化的 Release Snapshot；Issue→Commit→Build→Artifact 关系以 append-only Revision 保存；Artifact→Release 只由 Locked Manifest 派生；Fixed、Included、Verified 保持独立；历史 Traceability Snapshot 可以确定重放。

M2 不改变 V0.1 Core Contract、Release-centric architecture、Manifest authority、Evidence 一级实体、Traceability 语义、Deterministic Quality Engine、Adapter/Plugin 或 ADR 治理。Company Evidence Archive 仍按延期决定保持开放，不阻止本 M2 Pilot 工作包。

## 2. 已选择方案

采用“确定性 fixture 契约基线 + 有界真实 Jira CLI 只读 Smoke”。

- 自动化 Gate 只依赖合成、脱敏、版本化 fixture，不依赖外部网络或公司数据。
- `JiraCliPilotAdapter` 通过既有 `jira` CLI 读取当前配置的单一项目，单次最多 20 条 Issue，只在 `PILOT` Profile 显式启用。
- 真实 Smoke 证明认证、实际读取和映射链路可用，但不替代分页、429、5xx、重复页和中断恢复的确定性 fixture 测试。
- 内部 Issue 系统在当前阶段提供同一 Port 的 recorded fixture Adapter；没有真实 API 契约时不得声称公司内部系统已经接入。
- 未来 Company 接入迁移为直接 HTTPS Jira REST Adapter 或公司批准的等价 Adapter，不改变 Core、Snapshot 或 Traceability 语义。

未选择：

- fixture-only：无法证明真实 Jira 身份和读取路径可用。
- live-first CI：网络、权限和不断变化的数据会破坏确定性 Gate，并把公司系统变成构建依赖。
- 直接解析 Jira CLI credential/config：会扩大 secret 暴露面并把外部工具私有配置变成应用契约。
- 当前即查询全部 Issue：尚无容量、限流、保留和敏感字段治理 Evidence。

该技术选择必须在本 Written Spec Review 获批后的下一独立治理提交中创建并接受为 `TDR-014`（有界 Jira CLI Pilot Adapter 与 Fixture Contract）；在此之前不得开始实现。

## 3. 范围

### 3.1 包含

- M2 Issue、Traceability 和 operations package boundary。
- `IssueSourcePort`、统一 Normalization Contract 与 Adapter contract suite。
- Fixture Adapter、Jira CLI Pilot Adapter 与 recorded internal-source fixture Adapter。
- `issue_source`、`issue_sync_run`、`issue_sync_cursor`、版本化 `normalized_issue` 和不可变 `release_issue_snapshot`。
- `source_commit`、`build_record` 与三类 append-only Edge Revision。
- Traceability Verification Run、Gap、不可变 Snapshot Edge/Gap 与稳定 digest。
- 受控 Issue Sync、CI/Build Fact ingestion、Snapshot 创建、Traceability verify/query API。
- RBAC、Audit、Idempotency、Outbox/Background Job、失败恢复和 PostgreSQL Constraint Test。
- 少量真实 Jira Issue 的手工触发 Smoke Evidence，默认和硬上限均为 20。

### 3.2 不包含

- Jira 或内部系统写回、状态流转、评论、分配或附件读取。
- 真实公司内部 Issue API 绑定。
- 任意跨项目或 `project IS NOT EMPTY` 查询。
- Jira Description、Comment、Attachment、用户邮箱、原始 Payload 或 credential 的持久化。
- AI 匹配、模糊归因、图数据库或跨仓库智能推断。
- Device、Agent、Test Run、真实 Verified Evidence 和 M3 功能；M2 只保存 Verified 所需缺口，不伪造 Verified。
- merge `main`/`release`、Tag、release 或 production deployment。

## 4. 架构与模块责任

```text
Synthetic Fixture ─ FixtureIssueSourceAdapter ─┐
                                                ├─ IssueSourcePort
Real Jira ─ JiraCliPilotAdapter ───────────────┤
Internal Fixture ─ RecordedInternalAdapter ────┘
                       ↓
             Issue Sync Application
                       ↓
     Normalized Issue Revision + Successful Sync Run
                       ↓
        Immutable Release Issue Snapshot
                       ↓
CI/Build Fact Ingress → Typed Edge Revision Validator
                       ↓
       Verification Run + Traceability Gaps
                       ↓
         Immutable Traceability Snapshot
                       ↓
            Query API / Acceptance Export
```

### Issue 模块

负责 Source 配置、Adapter Port、Sync Run、Cursor、映射版本、Normalized Issue Revision 和 Release Issue Snapshot。它不解析 Git/Build provenance，不决定 Fixed/Included/Verified，也不把 Jira DTO 暴露给其他模块。

### Traceability 模块

负责 Source Commit、Build Record、typed Edge Revision、proof validation、Confidence、Verification Run、Gap、Snapshot 和路径查询。它通过只读 Port 获取 Issue Snapshot、Artifact 与 Locked Manifest facts；不得直接写 Issue、Manifest 或 Release 表。

### Shared Infrastructure

复用 M1 PostgreSQL、事务、Idempotency、Audit、Outbox、RBAC、Problem Details 和 Time/ID Port。不得引入 Kafka、Redis、图数据库或第二个结构化数据源。

## 5. Issue Source 与 Adapter Contract

```text
IssueSourcePort
  capabilities()
  fetchChanges(cursor, filter, pageSize)
  fetchByIds(sourceIssueIds)
  health()
```

M2 不实现 `updateIssue`；调用时必须返回 `CAPABILITY_NOT_SUPPORTED`。每个 `IssuePage` 必须包含 issues、next cursor、source watermark、observedAt、mapping version 和明确的 terminal 标志。

Normalized Issue 必填：source、sourceIssueId、title、severity、status、sourceVersion、sourceReference、observedAt、mappingVersion。状态仅允许 `OPEN`、`IN_PROGRESS`、`RESOLVED`、`CLOSED`、`UNKNOWN`；未知值必须映射为 `UNKNOWN` 并产生 Warning，不能默认 `CLOSED`。

同一 `(source, sourceIssueId, sourceVersion, mappingVersion)` 重放不得创建重复 Revision。新 source version、mapping version 或 tombstone 创建新 Revision，不修改旧行。

## 6. Jira CLI Pilot Boundary

Jira CLI 只作为 Adapter transport，不进入 Core 或数据库契约。执行必须使用 `ProcessBuilder` 参数数组或等价无 shell 拼接 API；禁止 PowerShell/cmd 字符串拼接用户输入。

允许的命令形态固定为读取当前受控项目的 list/search，并由代码强制：

```text
jira issue list --project <configured-project> --paginate 0:<1..20> --plain --no-headers --no-truncate --columns KEY,SUMMARY,STATUS,PRIORITY,UPDATED --delimiter <U+001F>
```

项目键来自 repository-external 配置并在边界校验为稳定项目标识；JQL、搜索文本、额外 flags 和可执行路径不能由 API 调用者任意传入。`--raw`、`--comments`、`--history` 和未列入白名单的 column 均禁止，因为实机 schema 探针证明 `--raw` 会返回 Description、Comment、Reporter、Assignee 等超范围字段。Adapter 不读取 Jira CLI 配置文件，只调用已配置 CLI；credential 继续由 CLI 自身的外部安全机制管理。

配置契约：

```text
VSRQG_JIRA_PILOT_ENABLED=false
VSRQG_JIRA_CLI_PATH=<absolute path, required when enabled>
VSRQG_JIRA_PROJECT=<single project key, required when enabled>
VSRQG_JIRA_MAX_ISSUES=20
VSRQG_JIRA_TIMEOUT=PT15S
```

`VSRQG_JIRA_MAX_ISSUES` 的默认值和 V0.2 硬上限均为 20；小于 1、大于 20、非绝对 CLI 路径、非文件、非 `PILOT` 模式或缺失项目键时启动失败。stdout 有 byte bound，仅在内存按 ASCII Unit Separator (`U+001F`) 解析；每个 record 必须恰好 5 列，行数不得超过配置上限，字段不得包含控制字符。任何列数、编码或边界错误使 Sync 失败。stderr 只转为固定诊断 code 和摘要，不写入原文。不得在日志、CI Artifact、Git、Acceptance Record 或 Problem Details 中输出完整命令、stdout、Issue 标题、人员信息、服务器 URL、本地配置路径或 credential。

真实 Smoke 只记录：执行时间、Adapter/mapping version、查询上限、返回数量、成功/失败 code、脱敏 schema digest 和 Sync Run ID。真实 Issue 数据只保存在受控 Pilot PostgreSQL 中，不提交到仓库。

## 7. 同步、事务与恢复

Sync 状态：

```text
QUEUED → RUNNING → SUCCEEDED
                 ↘ FAILED
```

1. `POST /api/v1/issue-sources/{sourceId}/sync` 在单事务内创建 Sync Run、Audit 和 Background Job，返回 `202`。
2. Worker 锁定 Job 和当前成功 Cursor，调用 Adapter。
3. 每页在事务中写入 Normalized Issue Revisions 与 page checkpoint；只有完整 page 成功才提交该页。
4. 所有页面成功后，单事务把 Sync Run 置为 `SUCCEEDED` 并推进成功 Cursor/Watermark。
5. 任一页失败时 Sync Run 为 `FAILED`，保留固定诊断与计数，但不推进成功 Cursor。
6. 重试创建新 Sync Run；它从最后成功 Cursor 开始，依靠 source version 幂等去重，不改写失败历史。

真实 Jira CLI Pilot 在 V0.2 只有一个 bounded page，但仍走相同状态机。Fixture contract suite 必须覆盖多页、重复页、分页中断、429 Retry-After、5xx bounded retry、401/403、mapping error、timeout、invalid output 和 tombstone。

## 8. Release Issue Snapshot

`POST /api/v1/releases/{releaseId}/issue-snapshots` 只接受 `SUCCEEDED` Sync Run 和版本化 filter reference。Snapshot 创建事务：

- 验证 Release、Project Scope、Sync Source 与年龄策略；
- 按稳定 `(source, sourceIssueId)` 顺序选择精确 Normalized Revision；
- 物化用于 Release 的字段、原始状态 token、mapping version、source version/reference 与 fact digest；
- 计算 content digest；
- 写 Audit 和 Outbox；
- 提交后禁止 UPDATE/DELETE。

外部 Jira 后续变化、mapping 修改或新 Sync 只能产生新 Snapshot。STALE 数据必须显式标记年龄；超过策略上限时默认拒绝创建新的 Gate 输入，不能伪装为本次同步成功。

## 9. Traceability Facts、Revision 与 Snapshot

M2 保存三类可写 append-only Edge Revision：

- `ISSUE_COMMIT`
- `COMMIT_BUILD`
- `BUILD_ARTIFACT`

`ARTIFACT_RELEASE` 不建可写表，只由 `release.locked_manifest_id → manifest_artifact` 派生。每个逻辑 Edge 有稳定 edgeId；proof、status、confidence、validator version 或 reason 变化时插入下一 revision。

CI/Build service identity 通过 `POST /api/v1/traceability/facts:ingest` 提交版本化 fact batch。请求必须有 Idempotency-Key、project scope、provider reference、source revision、artifact SHA-256 和 proof reference；不得接受调用方提交 Fixed/Included/Verified 布尔结论。

Verification Run 验证端点存在性、proof、Manifest membership 和策略版本，输出 `VALID`、`INVALID`、`CONFLICT` 或 `ERROR` 以及 `HIGH`、`MEDIUM`、`LOW`、`UNKNOWN` Confidence。缺失 required edge 创建精确 `TraceabilityGap`。

Snapshot 物化完整 Edge/Gap facts、source revision、proof reference、validator/policy version、reason 和 fact digest，按稳定 ordinal 计算 content digest。重放只读取 Snapshot，不查询最新 Edge Revision 或外部系统。

## 10. Fixed、Included 与 Verified

- Fixed：至少一个有效 Issue→Commit Edge，并满足 policy。
- Included：从该 Commit 经 Build、Artifact 到目标 Locked Release 存在连续有效路径。
- Verified：Included 成立，并存在目标 Release 上满足验证标准的 PASS Test Result 与 required Evidence。

M2 尚未实现 M3 Test Result/Evidence，因此 M2 可以证明 Fixed、Included 或精确的 Verified 缺口，但不得生成 Verified=true 的假 fixture 作为真实 Release 结论。自动推断只能创建 LOW/UNKNOWN candidate；人工补链必须有 actor、reason、proof 和 Audit，且不会自动升级为 HIGH。

## 11. API 与权限

保留已批准 Endpoint：

- `POST /api/v1/releases/{releaseId}/issue-snapshots`
- `GET /api/v1/releases/{releaseId}/traceability`
- `POST /api/v1/releases/{releaseId}/traceability:verify`

M2 增加向后兼容的运维 Endpoint：

- `POST /api/v1/issue-sources/{sourceId}/sync` — `issue:sync`
- `GET /api/v1/issue-sync-runs/{syncRunId}` — `issue:read`
- `POST /api/v1/traceability/facts:ingest` — `traceability:ingest`，仅 Service Identity

所有写操作要求 Idempotency-Key、Project Scope、Audit 和稳定 Problem Details。同步/验证返回 `202` 与 operation ID；不存在或不可见统一为 404；状态冲突为 409；映射/领域错误为 422；外部不可用为 503。OpenAPI 兼容性基线只追加 Operation，不修改既有 Path/Method/Permission/Request 语义。

## 12. 安全、隐私与数据保留

- CLI credential、PAT、cookie、config 内容和环境变量值不得进入 Git、数据库业务字段、日志或测试报告。
- 真实 Jira Adapter 不读取附件、评论、完整 Description、邮箱或无关 custom field。
- 白名单列文本只存在于 bounded process buffer，映射后释放，不持久化；禁止请求或解析 Jira raw JSON。
- API 与日志不返回 Adapter stderr、stack trace 或原始外部响应。
- Fixture 必须人工合成或彻底脱敏，禁止复制真实标题、人员、URL 或项目标识。
- Normalized Issue Revision、Release Snapshot、Traceability Revision/Snapshot 和 Audit 按审计期保留；失败 Sync 的原始 Payload 不保留。
- 扩大到全部 Issue 前必须单独评审分页、限流、容量、敏感字段、保留和删除语义。

## 13. 实施批次

### M2.0 — Contract 与质量基线

建立 Issue/Traceability package marker、架构依赖规则、权限矩阵、OpenAPI compatible additions、synthetic fixtures 和 M2 Gate shell。出口是 Contract/Architecture/Secret Scan 通过，且没有 Jira DTO 泄漏到 Core。

### M2.1 — PostgreSQL 权威基线

新增 forward-only Migration、PK/FK/UNIQUE/CHECK、append-only Trigger、索引和真实 PostgreSQL Constraint Test。出口是空库/升级迁移、重复迁移、跨 Project/FK、Revision 唯一性和不可变拒绝通过。

### M2.2 — Adapter、Sync 与真实 Jira Smoke

实现 Port、fixture/internal recorded Adapter、Jira CLI Pilot Adapter、bounded worker、mapping 和 Sync/Cursor。出口是统一 contract suite 通过，以及在显式授权的本地 Pilot 上最多 20 条真实 Issue 的只读 Smoke PASS；CI 不依赖真实 Jira。

### M2.3 — Release Issue Snapshot

实现 Snapshot 创建、稳定 digest、年龄策略、Audit/Outbox 和 API。出口是历史 Snapshot 在外部 Issue/mapping 改变后字节与 digest 不变。

### M2.4 — CI/Build Facts 与 Edge Revision

实现 service ingestion、Source Commit、Build Record、三类 typed Edge Revision、proof validation 和冲突保留。出口是重复 batch 幂等、重新验证只插入 Revision、Artifact→Release 无第二数据源。

### M2.5 — Verification、Gap 与 Snapshot

实现路径验证、Fixed/Included/Verified 分离、Gap、不可变 Snapshot 与查询 API。出口是缺失任一 required edge 时 Included=false 且报告精确缺口；旧 Snapshot 重放不读最新 Revision。

### M2.6 — 故障、恢复与验收包

建立单一 M2 Gate、备份恢复、分页/限流/超时/事务故障演练、真实 Jira Smoke 摘要、known-chain/gap reports 和 Owner acceptance record candidate。失败不得自动改为 PASS；新记录从 `PENDING` 开始。

## 14. 测试与验收矩阵

| 场景 | 必需结果 | Evidence |
|---|---|---|
| 两个 Adapter 相同输入 | 相同 Normalized Contract | Contract suite + golden digest |
| Jira CLI 未安装/未认证/超时 | Sync FAILED，Cursor 不推进 | failure injection report |
| 真实 Jira ≤20 条 | 只读 Sync SUCCEEDED，无原始数据入 Git/log | redacted smoke summary |
| 分页中断/429/5xx | bounded retry；最终失败不推进 watermark | fixture timeline |
| 未知状态/严重度 | UNKNOWN + warning，不映射为 CLOSED/PASS | mapping report |
| 相同 source version 重放 | 无重复 Revision | PostgreSQL test |
| Jira 后续变化 | 旧 Release Snapshot digest 不变 | replay report |
| 只有 Issue→Commit | Fixed 可判断，Included/Verified 不成立 | gap report |
| 缺少 Build→Artifact | Included=false，精确 gap | known-chain test |
| Edge 重新验证 | 新 Revision；旧 Snapshot 不变 | revision/snapshot test |
| Artifact→Release | 仅 Locked Manifest 派生 | schema/query proof |
| 相同 Snapshot 三次重放 | path、gap、confidence、digest 一致 | replay report |
| 跨 Project 写入 | 403 或 DB FK/constraint 拒绝 | security/constraint test |
| Audit/Outbox 失败 | 业务事务整体回滚 | rollback test |

## 15. 范围控制与 Cut Line

若 M2 延误超过一周，依次删除：管理 UI、自动定时同步、额外查询筛选、人工补链 UI、非关键指标和格式化报表。不得删除：两个 Adapter 的同一契约、真实 PostgreSQL、历史 Snapshot 不变性、typed Edge Revision、Artifact→Release Manifest authority、Fixed/Included/Verified 分离、Gap、Audit/Idempotency、失败关闭和恢复测试。

真实 Jira Smoke 失败不会被 fixture PASS 覆盖；fixture PASS 证明实现可重放，live Smoke 证明当前外部读取链路，二者分别报告。内部系统 fixture PASS 不等于真实内部系统接入，bounded Jira Smoke 也不得形成 `Company Ready` 声明。

## 16. 迁移、部署与回滚

M2 分支基于未合并的 M1 配对 HEAD；在 M1 保持 `CONDITIONAL` 时允许独立开发和测试，但不得合并、Tag、release 或部署。M2 Migration 只前进，先 Expand；应用回滚不得修改已应用 Migration，必要时使用经演练备份恢复。

Pilot 部署只新增 PostgreSQL 表、后台 Job 和可选 Jira CLI Adapter，不新增 Broker 或服务。Jira CLI 必须由 Operator 在仓库外安装和认证。禁用 `VSRQG_JIRA_PILOT_ENABLED` 即停止新的 live sync；历史 Sync、Snapshot 和 Audit 保留。失败重试创建新 Sync/Verification Run，不能覆盖历史。

迁移到 Jira REST Adapter 时，先用同一 fixture contract suite 和 bounded live comparison 对齐 mapping digest，再切换 source Adapter version；历史 Snapshot 不重写，回滚只切回旧 Adapter version。

## 17. 停止条件

出现以下情况立即停止并提交 Finding、TDR 修订或 ADR Proposal：

- 需要改变 Fixed/Included/Verified 或 Artifact→Release 语义；
- 需要把 Jira/CLI DTO 放入 Core Contract；
- 无法通过 append-only Revision 和不可变 Snapshot重放历史；
- 需要第二个 Artifact→Release 来源；
- 需要把外部错误、UNKNOWN 或缺失 Edge 改成成功；
- 需要读取或提交 credential/原始公司数据；
- 五周容量要求删除任何不可削弱项。

## 18. Written Spec Review Gate

本规范提交后先由 Project Owner 书面评审。只有 Owner 明确批准 `M2-KD-2026-08-28-01`，才编写逐文件、逐测试、逐提交 Implementation Plan。批准书面规范不授权生产代码、真实 Jira 写操作、merge、Tag、release 或 production deployment；每一后续授权保持独立。
