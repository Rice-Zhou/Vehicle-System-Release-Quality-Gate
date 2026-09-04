# M2.5 Traceability Verification、Gap 与不可变 Snapshot 设计

- Spec ID：`M2-KD-2026-09-04-01`
- Owner Design Direction：`APPROVED 2026-09-04`（方案 A：异步 Verification Run + 不可变 Snapshot）
- Written Spec Review：`APPROVE`
- Architecture Baseline：V0.1 `0.1.0`（FROZEN）与 V0.2 `0.2.0`
- Parent Authority：M2.3 `M2-3-OWNER-GATE-001` 与 M2.4 `M2-4-OWNER-GATE-001`
- 范围：只定义 M2.5 Traceability Verification、Gap、Snapshot 与查询的实施架构；不授权实现

## 1. 目的与冻结边界

M2.3 已固化目标 Release 的 Issue 集合，M2.4 已保存 `ISSUE_COMMIT`、`COMMIT_BUILD`、`BUILD_ARTIFACT` 三类 append-only typed Edge Revision。M2.5 将这些历史事实与 Locked Release Manifest 派生的 `ARTIFACT_RELEASE` 组合，生成可审计、可重放的 Fixed、Included、Verified 结论和精确 Gap。

本设计不改变 V0.1 Core Contract、Release-centric architecture、Manifest authority、Evidence 一级实体、Traceability 链、Deterministic Quality Engine、Adapter/Plugin 或 ADR 治理。完整链继续为 `Issue → Commit → Build → Artifact → Release → Test Run → Test Result → Evidence`。M2.5 不具备真实 Test Result/Evidence，因此不得生成 `Verified=true`。

## 2. 不可协商边界

- Release 定义只来自 Locked Manifest；`ARTIFACT_RELEASE` 只读 `artifact_release_edge_v`。
- Issue 范围只来自一个固定的 M2.3 `release_issue_snapshot`。
- 三类可写 Edge 只读取创建 Run 时固定的 M2.4 Revision；重试和重放禁止读取最新 Revision。
- Fixed、Included、Verified 是三个独立事实，不允许相互推导替代。
- 已完成 Snapshot 及其 Issue Result、Edge、Gap 禁止更新或删除。
- Jira、GitHub、CI、Device 和 Test Agent 不进入验证执行路径。
- 缺失 Edge 是业务 Gap；不存在、跨 Project、未锁定或不可信的权威输入是 Run 错误，二者不得混淆。
- Company、真实 Jira、真实 CI、M3 Test/Evidence、merge、Tag、release 与 production deployment 保持阻断。

## 3. 方案比较与决定

采用“异步 Verification Run + 固定输入账本 + PostgreSQL 原子物化不可变 Snapshot”。`POST` 在短事务内固定输入并创建 Job，Worker 只读固定输入完成图可达性判定，随后一次事务写入 Snapshot、Issue Result、Edge、Gap、Audit 与 Outbox。

未选择同步请求内验证，因为它会扩大 HTTP 超时、重试和并发锁风险，并违背已批准的 `202` 契约。未选择查询时动态计算，因为最新 Revision 会改变历史结果，无法审计重放。未选择 Kafka、RabbitMQ、图数据库或独立服务，因为当前最多 20 个 Issue、受控 Edge 数量和单 PostgreSQL authority 没有真实规模需求支撑额外运维复杂度。

## 4. 逻辑架构与数据流

```text
POST traceability:verify
        ↓
Auth / Project / Idempotency Boundary
        ↓
pin Release + Locked Manifest + M2.3 Snapshot + M2.4 Edge Revisions
        ↓ one short PostgreSQL transaction
Verification Run + Input Ledger + Audit + Outbox + Background Job
        ↓
PostgreSQL Worker claims Job with SKIP LOCKED
        ↓
deterministic reachability and Gap calculation, no external calls
        ↓ one result transaction
Snapshot + Issue Results + Edges + Gaps + Audit + Outbox
        ↓
GET immutable Snapshot / GET Verification Run status
```

Traceability Application 通过只读 Port 获取 Release、Manifest、Issue Snapshot 和 typed Revision。Controller DTO 留在 Adapter boundary；Domain 只表达固定输入、路径状态、Gap 和确定性摘要。不得从 Traceability 模块写 Issue、Manifest、Release 或 M2.4 Revision 表。

## 5. 数据权威与固定输入

创建 Run 时必须固定：`release_id`、`release_issue_snapshot_id`、`locked_manifest_revision_id`、Manifest digest、每条可见 M2.4 Edge 的 type/edge ID/revision/fact digest、policy version、validator version 和规范化 `input_digest`。

请求中的现有 `IdentifierInput.sourceId` 表示 Issue Source ID。服务端在同一事务内选择该 Release 与 Source 当前最新的不可变 Issue Snapshot，并保存具体 Snapshot ID；客户端不能指定 Manifest Revision、Edge Revision、状态或结论。

若某个 logical Edge 在固定时不存在，Input Ledger 中没有该 Edge，后续到达的新 Revision 不得进入本次 Run。若当前权威 Revision 存在但为 `INVALID`、`CONFLICT` 或 `ERROR`，本次请求以不可信输入失败，不把它改写为缺失 Gap。

## 6. Fixed、Included 与 Verified

对 Snapshot 中每个 Issue 独立判定：

- `Fixed=true`：至少存在一条满足当前 policy 的 `VALID ISSUE_COMMIT` Edge。
- `Included=true`：至少存在一条连续 `VALID` 路径 `ISSUE_COMMIT → COMMIT_BUILD → BUILD_ARTIFACT → ARTIFACT_RELEASE`，末段必须由目标 Locked Manifest 派生。
- `Verified=true`：Included 成立，并存在目标 Release 上满足验证标准的 PASS Test Result 与 required Evidence。

M2.5 固定输出 `Verified=false`。Commit 对象存在、Issue 文本、Artifact 名称、版本号相似或人工备注均不能替代所需 Edge。多条候选路径中任意一条完整即可 Included；Snapshot 保存全部参与判定的 Edge，但只保存一个按稳定排序选择的主证明路径。

## 7. Gap 模型与精确诊断

固定 Gap Code：

| Code | 中断位置 |
|---|---|
| `ISSUE_COMMIT_MISSING` | Issue 没有有效修复提交 |
| `COMMIT_BUILD_MISSING` | 已有 Commit，但没有包含它的 Build |
| `BUILD_ARTIFACT_MISSING` | 已有 Build，但没有对应 Artifact |
| `ARTIFACT_RELEASE_MISSING` | 已有 Artifact，但不属于目标 Locked Manifest |
| `TEST_RESULT_EVIDENCE_MISSING` | 没有目标 Release 上的合格 Test Result/Evidence |

每个 Gap 保存 Issue ID、实际中断源节点、期望 Edge Type、固定 Code、所用 Revision 引用、脱敏 reason 和 gap digest。只报告实际最前端中断，不级联虚构后续节点。完整 Included 路径仍必须产生 `TEST_RESULT_EVIDENCE_MISSING`；M2.5 不提供管理员开关或手工参数把 Verified 改为 true。

## 8. PostgreSQL 模型扩展

沿用 V4 的 `traceability_verification_run`、`traceability_gap`、`traceability_snapshot`、`traceability_snapshot_edge`、`traceability_snapshot_gap`、`background_job` 和 `idempotency_record`，通过新 forward-only Migration 做最小扩展。

`traceability_verification_run` 增加固定 Manifest/Issue Snapshot、validator/input digest、最终 `result_snapshot_id` 与必要执行诊断。新增 append-only `traceability_verification_run_edge_input`，以 `(verification_run_id, ordinal)` 为主键并唯一约束 run/type/edge/revision，保存 fact digest；创建后不可更新或删除。

新增 `traceability_snapshot_issue_result`，主键为 `(snapshot_id, issue_id)`，保存 Fixed、Included、Verified、主路径摘要、最低路径 Confidence、原因和 result digest。新增 `traceability_snapshot_issue_path_edge`，以 `(snapshot_id, issue_id, path_ordinal)` 为主键并引用同一 Snapshot 的 `traceability_snapshot_edge.ordinal`，从而固化主证明路径的准确 Edge 顺序，而不是在查询时重新计算。

`traceability_gap` 与 `traceability_snapshot_gap` 增加可空的中断源实体类型/ID和前驱 source edge ID/revision；首段缺口以 Issue 为中断源且没有前驱 Edge，后续缺口必须保存实际前驱引用，并由 CHECK/FK 约束一致性。Issue Result、Path Edge、Snapshot Edge 和 Gap 与 Snapshot Header 在同一事务创建，并使用同类 creation transaction 与 immutable trigger。

`traceability_gap` 是 Run 级诊断记录；历史 API 只以 Snapshot 系列表为权威，不把 Run Gap 作为第二历史数据源。`traceability_snapshot.content_digest` 覆盖固定输入、Issue Result、主路径 Edge 顺序、全部 Edge 和 Gap 的 canonical bytes。

## 9. 创建、执行与状态事务

创建事务按固定顺序完成权限/Project 校验、锁定 Release、校验 Locked Manifest、选择 Issue Snapshot、固定 Edge Revision、计算 input digest，并原子写入 Idempotency、Run、Input Ledger、Audit、Outbox 和 Background Job。任一步失败全部回滚。

Worker 使用现有 PostgreSQL Job 与 `FOR UPDATE SKIP LOCKED` 领取任务。计算只读取 Input Ledger。结果事务锁定 Run 和 Release，分配 Release 内 Snapshot Version，写入 Header、Issue Result、主路径 Edge、全部 Edge、Gap、Audit 与 Outbox，复算 digest，然后同时把 Run/Job 改为 `SUCCEEDED`。任一写入失败不产生半成品 Snapshot。

Run 只允许 `QUEUED → RUNNING → SUCCEEDED|FAILED`。临时失败在有界 Job 重试期间保持 RUNNING；超过次数才 FAILED。终态不能恢复，重新验证必须创建新 Run。

## 10. 幂等、并发与确定性

同一 Principal/Scope/Idempotency-Key 与相同 request hash 返回原 Run；不同 hash 返回 `409 IDEMPOTENCY_KEY_REUSED`。Job key 由 Run ID 派生。不同 Key 但相同 input digest 可以创建新的审计 Run并复用已有相同 content digest 的 Snapshot，通过 `result_snapshot_id` 建立结果引用；输入变化才创建新 Snapshot Version。

同一 Release 的输入固定和版本分配锁定 `release_record`；数据库 UNIQUE/FK/Trigger 是最终保护，不使用 JVM 本地锁。并发相同输入最多物化一个 Snapshot。排序依次使用 Issue、Edge Type、from ID、to ID、edge ID 和 revision；相同输入必须得到相同路径、Gap、Confidence 与 digest。

图计算使用可达性集合，不枚举路径笛卡尔积。Snapshot 保存去重后的完整参与 Edge 集合，主路径按上述稳定顺序选取。

## 11. API、权限与错误

保留：

- `POST /api/v1/releases/{releaseId}/traceability:verify` — `traceability:verify`、Idempotency-Key、`202`。
- `GET /api/v1/releases/{releaseId}/traceability` — `traceability:read`，可选 `snapshotId`；省略时读取最新完成 Snapshot。

增加向后兼容的 `GET /api/v1/traceability-verification-runs/{verificationRunId}`，使用 `traceability:read` 返回 QUEUED/RUNNING/SUCCEEDED/FAILED；POST 的 `Location` 指向它。成功时返回 Snapshot locator，失败时只返回固定诊断码和脱敏摘要。

POST 的 typed response 至少包含 verificationRunId、releaseId、issueSnapshotId、status、inputDigest 和 statusUrl。Snapshot response 包含 authority 版本/digest、每个 Issue 的三个状态、主路径、参与 Edge、Gap 和 Confidence，不包含 Jira Description/Comment/Attachment、原始 Payload、credential、本机路径、SQL 或 stack trace。

缺少 Scope 为 403；对当前 Project 不可见的资源统一 404。Manifest 未锁定、状态或幂等冲突为 409；存在但不可信的权威输入为 422；必要基础设施不可用为 503。错误使用 RFC 9457 Problem Details 和稳定 code/requestId。验证过程不访问 Jira/GitHub/CI，因此这些系统不可用不是该接口的 503 原因。

## 12. 容量与性能边界

沿用每个 Issue Snapshot 最多 20 个 Issue，以及 M2.4 每 Envelope 最多 20 个 Issue、20 个 Artifact、100 个 Facts。单次 Verification 默认最多读取 2,000 条 Edge Revision；超限以 `TRACEABILITY_INPUT_LIMIT_EXCEEDED` 失败关闭，不允许截断后生成 Snapshot。

参考 Pilot 目标为：创建 Run P95 ≤1 秒；20 个 Issue/2,000 Edge 的异步验证 ≤10 秒；完整 Snapshot 查询 P95 ≤1 秒；查询不得出现逐 Issue 或逐 Edge N+1。共享 CI 使用宽松超时防止算法退化，精确性能目标由固定参考环境报告验证。

## 13. 测试与 Evidence 矩阵

- Domain：完整链、每段精确缺口、只有 Commit、只有 Issue→Commit、多路径、Manifest membership、M2.5 Verified=false。
- Determinism：同一输入三次结果一致；新 Revision 不改变旧 Snapshot；主路径、Gap、Confidence、digest 稳定。
- PostgreSQL：FK、cross-Project、immutable/creation-transaction trigger、主路径 Edge 引用、Gap 前驱一致性、状态转换、版本唯一性、相同 digest 复用和 Migration upgrade。
- Transaction：Input、Snapshot 子表、Audit、Outbox、Idempotency 任一点失败均整体回滚。
- Concurrency：重复领取、相同/不同 Key、相同输入并发、Release version allocation、Worker crash/retry。
- API/Contract：typed schemas、Location/status polling、snapshotId、权限矩阵、404 防枚举、RFC 9457 与 compatibility baseline。
- Security：SQL 参数化、oversize/control character、日志/Problem/Outbox/Evidence 敏感信息扫描。
- Recovery：DB restart、poison/dead-letter、提交边界、backup/restore 后 Snapshot digest 校验。

Owner Gate Evidence 至少包含 exact-head CI、真实 PostgreSQL tests、known-chain/gap/replay reports、transaction/concurrency/recovery reports、Contract/Security tests、双语 Pair Gate 和固定 Git locator。任一失败不得被其他 PASS 覆盖。

## 14. Migration、部署与恢复

使用 forward-only Expand Migration 增加列、Input Ledger、Issue Result、Path Edge、Gap 前驱字段、复合 FK、索引和触发器。部署顺序为 PostgreSQL 备份、Migration Constraint Test、同一 Backend 镜像、Pilot Fixture known-chain/gap Smoke、digest replay，再显式开放入口。

不新增 Broker、Redis、图数据库、对象存储、微服务、公开 Backend 或 Company 依赖。应用故障时可回滚上一镜像并保留向后兼容数据库扩展；不执行 down migration。Schema 问题以新 forward migration 修正。

Worker crash 由固定输入和有界 Job retry 恢复；poison job 进入 Dead Letter 并保持可见。FAILED Run 不改写，修复后使用新 Run。数据库恢复后先校验 Flyway 版本，再从 Snapshot 自身数据复算 digest；不一致时停止使用，不覆盖旧 digest，不使用 JSON/file/cache fallback。

## 15. Technology Decision Delegation 与 V0.3

`TDR-018` 记录 PostgreSQL-backed asynchronous Verification、pinned input ledger 与 immutable Snapshot materialization，完整回答选择、问题、替代、V0.2/V0.3 影响、迁移、测试、部署和失败恢复。Project Owner 已批准 Written Spec Review，当前状态为 `Accepted`。

V0.3 可以通过新的 schema/policy/validator version 加入 Test Run、Test Result 和 Evidence，从而产生真实 Verified 结论；旧 M2.5 Snapshot 永远保持 Verified=false。只有测量证明 20 Issue/2,000 Edge、PostgreSQL Job 或单体 Worker 成为瓶颈时，才评估抽离 Worker、Broker 或图查询；固定输入、数据库 authority、digest 和历史不变性必须保持。

## 16. Cut Line、停止条件与 Written Spec Gate

若进度延误，依次删除格式化报告、额外筛选、非关键性能指标和管理 UI；不得删除 Fixed/Included/Verified 分离、Manifest-only Artifact→Release、固定输入、精确 Gap、不可变 Snapshot、Project isolation、Idempotency、Audit/Outbox、事务/重放/恢复测试。

若需要改变三个状态语义、引入第二 Artifact→Release authority、动态读取最新 Revision 重放历史、把外部系统放入 Gate 执行路径、允许调用方提交结论、用缺失/UNKNOWN/error 形成成功、覆盖旧 Snapshot 或保存 credential/原始公司数据，立即停止并提交 Finding、TDR revision 或 ADR Proposal。

Project Owner 已批准本 Written Spec；该批准只允许创建独立 Implementation Plan，不授权生产代码、Migration、真实 Jira/CI、Company、M3、merge、Tag、release 或 production deployment。Implementation Plan 和后续 Subagent-Driven 执行均需要独立明确授权。
