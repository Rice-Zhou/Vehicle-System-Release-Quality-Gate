# M2.4 CI/Build Facts 与 Edge Revision 设计

- Spec ID：`M2-KD-2026-09-03-01`
- Owner Design Direction：`APPROVED 2026-09-03`（方案 A：单次 Build Provenance Envelope）
- Written Spec Review：`APPROVE`
- Architecture Baseline：V0.1 `0.1.0`（FROZEN）与 V0.2 `0.2.0`
- Parent Governance：中文 `81b1aa12da8ffcb060df9c257e8277e883221fe0` / 英文 `c6496ae5147afb85ea4224f390b0df30d7d96324`
- 范围：只定义 M2.4 CI/Build Fact ingestion 与 typed Edge Revision 的实施架构；不授权实现

## 1. 目的与证据缺口

M2.3 已把某个 Release 的精确 Issue 集合固化为不可变 `release_issue_snapshot`。当前数据库虽预建 `source_commit`、`build_record` 和三类 Edge Revision 表，但尚无可执行的 ingestion authority、稳定逻辑 Edge identity、并发收敛、proof validation、Build Attempt 幂等或真实 CI 端到端 Evidence。

M2.4 使用一次 Build Attempt 对应一个版本化 Build Provenance Envelope。服务端只接收来源事实，在一个 PostgreSQL 事务中解析精确 Issue Snapshot Item、创建或复用 Commit/Build、验证 Artifact checksum，并写入三类 typed Edge 的 append-only Revision、Idempotency、Audit 与 Outbox。它不得接受 Fixed、Included、Verified、PASS、WARNING 或 BLOCK 等调用方结论。

本设计不改变 V0.1 Core Contract、Release-centric architecture、Manifest authority、Evidence 一级实体、Traceability 语义、Deterministic Quality Engine、Adapter/Plugin 或 ADR 治理。M2.4 只建立 M2.5 可以验证的 provenance facts，不生成 Release Traceability Snapshot 或 Quality Result。

## 2. 不可协商边界

- 一个 Envelope 只描述一个 Project 中一个 CI Provider 的一个 Build Attempt。
- Envelope 必须绑定一个不可变 Release Issue Snapshot；Issue 只能在该 Snapshot Items 内按 source identity 精确解析，禁止查询当前最新 Issue Revision。
- `ISSUE_COMMIT`、`COMMIT_BUILD`、`BUILD_ARTIFACT` 是唯一可写 Edge 类型。
- `ARTIFACT_RELEASE` 只能由 `release.locked_manifest_id → manifest_artifact` 派生；不得出现写 API、表、缓存或旁路映射。
- Source Commit authority 为 Project、Repository Identity 与完整 Source Revision；Build authority 为 Project、Provider、Pipeline、Build ID 与 Attempt。
- Artifact 必须已存在，并按完整小写 SHA-256 精确解析；M2.4 不上传、注册或替换 Artifact。
- 调用方不得提交 `edgeId`、Revision、Verification Status、Confidence、Validator Version 或 Fixed/Included/Verified。
- 历史 Edge Revision 禁止 UPDATE/DELETE；重验或 proof 变化只能插入新 Revision。
- GitHub Actions 只作为首个 Pilot Provider Adapter，不进入 Core 或数据库通用语义。
- Company、真实公司 CI、生产凭据、M2.5、merge、Tag、release 与 production deployment 保持阻断。

## 3. 方案比较与决定

采用“单次 Build Provenance Envelope + 服务端派生 typed Edge + PostgreSQL 原子提交”。Envelope 提供结构化 Build authority、Snapshot reference、Issue source IDs、Artifact digests 与 proof；服务端派生完整 typed facts，调用方不直接拼装内部 Entity ID 或结论。

未选择“通用独立 Edge Batch”，因为 Producer 必须预先知道数据库内部 ID，容易产生半条链、顺序依赖和并发漂移。未选择“原始 Attestation/Event 先落库再异步归一化”，因为当前没有签名供应链、Broker、独立 Raw Store 或高吞吐需求，其恢复和运维成本超出六个月业余 MVP。未选择“CI 直接写 Edge 表”，因为它绕过验证、事务、Audit 和 Project Scope。

## 4. 逻辑架构与数据流

```text
GitHub Actions / future CI Adapter
                ↓
      Build Provenance Envelope v2
                ↓
Service Identity + Project Scope Boundary
                ↓
      BuildProvenanceValidatorPort
                ↓
Release Issue Snapshot / Commit / Build / Artifact resolution
                ↓
       one PostgreSQL transaction
  ┌─────────────┼───────────────────┐
Commit/Build  Edge Identity/Revision  Idempotency/Audit/Outbox
                ↓
       immutable ingestion result
                ↓
       M2.5 verification input
```

Controller DTO 和 GitHub environment parsing 位于 Adapter boundary。Application 只接收规范化 `BuildProvenanceEnvelope`；Domain 只表达 provider-neutral identity、proof observation 和 typed edge candidate。Traceability Adapter 可以通过只读 Port 查询 M2.3 Snapshot、Manifest/Artifact facts，不得依赖 Issue、Manifest 或 Release Adapter 实现。

## 5. Build Provenance Envelope v2

保留 Endpoint `POST /api/v1/traceability/facts:ingest`、`traceability:ingest` service scope 与 `Idempotency-Key`。请求 body 使用 `schemaVersion: 2`，必需字段如下：

```json
{
  "schemaVersion": 2,
  "project": "project-reference",
  "releaseIssueSnapshotId": "ris_...",
  "provider": "GITHUB_ACTIONS",
  "repository": "owner/repository",
  "sourceRevision": "0123456789abcdef0123456789abcdef01234567",
  "pipeline": "m1-backend",
  "buildId": "33705417856",
  "buildAttempt": 1,
  "workflowReference": "owner/repository/.github/workflows/m1-backend.yml@refs/heads/main",
  "proofReference": "https://github.com/owner/repository/actions/runs/33705417856/attempts/1",
  "proofDigest": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "sourceIssueIds": ["ISSUE-1"],
  "artifactSha256s": ["0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"]
}
```

`sourceIssueIds` 最多 20 项，`artifactSha256s` 最多 20 项；重复值直接拒绝，通过校验后按 Unicode code point 排序。派生 fact 数为 Issue 数量的 `ISSUE_COMMIT`、一个 `COMMIT_BUILD` 和 Artifact 数量的 `BUILD_ARTIFACT`，总数硬上限 100；请求体硬上限 256 KiB。空 Issue 或 Artifact 数组、重复值、控制字符、未知 Provider、非完整 Git SHA、非小写 SHA-256、Attempt 小于 1 或非 allowlisted proof scheme 均返回固定边界错误，不截断处理。

M2.0 的 `TraceabilityFactBatch schemaVersion: 1` 是未实现且无外部消费者的 pre-release 草案。M2.4 不静默复用其含义；`TDR-017` 明确以 v2 supersede v1，并在同一 Subject 中更新 OpenAPI 与 compatibility baseline 的计划约束。平台 `/api/v1` major path、Method、Permission 和 Idempotency 不变；body schema 的有意变化必须由 Contract Test 和治理记录显式证明。

## 6. 数据权威与关系模型

新增轻量 `traceability_edge_identity`，作为逻辑 Edge Header：

| 字段 | 约束 | 说明 |
|---|---|---|
| `edge_id` | PK | 服务端 UUIDv7，不由调用方提交 |
| `project_id` | FK、非空 | Project Scope |
| `edge_type` | CHECK、非空 | 三类可写 Edge 之一 |
| `from_entity_id` | 非空 | 稳定起点 identity |
| `to_entity_id` | 非空 | 稳定终点 identity |
| `created_at` | 非空 | Authority 写入时间，不进入事实结论 |

唯一约束为 `(project_id, edge_type, from_entity_id, to_entity_id)`。三张 typed Revision 表继续持有其具体端点 Composite FK；新增 Edge Header FK 与 deferred constraint trigger，保证 Header discriminator/端点和 Revision typed endpoints 一致。Header 只表达逻辑身份，不保存 proof、状态或 Confidence，因此不是第二个事实来源。

`build_record` 增加 `build_attempt >= 1` 与 immutable `repository`，authority uniqueness 调整为 `(project_id, provider, pipeline, build_id, build_attempt)`。已有表当前没有生产数据；Migration 仍使用 expand/validate 顺序，不假设空表并提供 precondition check。`source_commit` 继续使用 `(project_id, repository, commit_id)`；Artifact 继续使用已有 content identity，不新增 `build_id`。

## 7. 原子 ingestion 事务

正常路径固定为：

1. 校验 Service Identity、Project claim、payload size、schema 和 request digest。
2. 获取或创建 Idempotency Record；同 Key 不同摘要立即 `409`。
3. 获取 Build Attempt Receipt，并验证 authority tuple 与 Envelope digest。
4. 锁定 Release Issue Snapshot，验证 Project Scope、immutable status 与 digest。
5. 只在 Snapshot Items 中解析全部 source issue IDs；任一缺失整体失败。
6. 创建或复用 Source Commit 和 Build Record，验证 identity 不冲突。
7. 按 checksum 稳定排序解析全部 Artifact；任一缺失或不一致整体失败。
8. 按 Edge Type 与 endpoints 排序，创建或锁定 Edge Header。
9. 运行 provider-neutral validator，生成服务端 status、confidence、reason 和 fact digest。
10. 相同 digest 复用已有 Revision；变化时插入严格 `revision+1`。
11. 写 Build Attempt Receipt、Audit、Outbox 与 Idempotency response。
12. 对待写模型重新计算 counts/digests，全部成功后一次提交。

任一 Domain、Audit、Outbox 或 Idempotency 写入失败时事务整体回滚。Endpoint 不存在、跨 Project、Snapshot 不含 Issue 或 Artifact checksum 不匹配时不得留下 Commit、Build、Edge 或成功 Receipt。

## 8. Revision、幂等与冲突

Edge Revision 行保存 `verified_at` 作为验证发生时间；canonical fact 只固化 edge identity/type、typed endpoints、source type/reference、proof reference/digest、verification status、confidence、validator version 和 reason code。`previous_revision_id` 与 `previous_revision` 继续作为 append-only chain metadata 保存，但与 `verified_at`、`created_at`、request ID、Idempotency Key、Revision ID 和 revision number 一样不进入 fact digest。这样同一语义事实恢复出现时可以复用相同 digest，同时仍通过独立链字段保留完整历史位置。

- 相同 Edge canonical digest 返回现有 latest Revision，不插入重复行。
- proof、status、confidence、validator version 或 reason 改变时，在 Edge Header row lock 下插入下一 Revision。
- 调用方改变 endpoints 会解析为另一个 Edge identity；不能借旧 `edgeId` 篡改端点。
- 相同 Idempotency Key + 相同 request digest 返回原响应；不同 digest 返回 `IDEMPOTENCY_CONFLICT`。
- 相同 Build Attempt authority + 相同 Envelope digest 即使使用不同 Key，也返回原 Receipt 和 Revision 集合。
- 相同 Build Attempt authority + 不同 Envelope digest 不覆盖旧事实；独立短事务保存脱敏 rejected receipt 与 Audit，返回 `BUILD_PROVENANCE_CONFLICT`。
- 新 proof 与同一 Edge 的既有权威 proof 矛盾时，插入 `CONFLICT` Revision 并保留旧 Revision。
- 一个 Build 合法产出多个 Artifact 时形成多个 `BUILD_ARTIFACT` Edge，不自动视为冲突。

固定锁顺序为 Project、Build Attempt Receipt、Edge Header `(edge_type, edge_id)`、Revision、Audit/Outbox。数据库 UNIQUE/FK/Trigger 是最终并发保护；应用层不得只依赖“先查后插”。

## 9. Proof Validation 与 Confidence

验证分六层：Schema、Service Identity、Project Scope、Endpoint、Integrity、Provider Provenance。`BuildProvenanceValidatorPort` 只返回规范化 observation；Domain policy 根据固定 validator version 产生 `VALID`、`INVALID`、`CONFLICT` 或 `ERROR`，以及 `HIGH`、`MEDIUM`、`LOW` 或 `UNKNOWN`。

GitHub Actions Pilot 使用真实 repository、commit SHA、workflow reference、run ID/attempt 和构建 Artifact checksum，但没有独立签名 attestation，因此成功 proof 的最高 Confidence 为 `MEDIUM`。环境不可用或无法复核产生 `ERROR/UNKNOWN` 新 Revision，禁止沿用旧 `VALID`。只有未来经单独评审的 GitHub OIDC Attestation、SLSA Provenance 或公司签名 metadata 才能由新 Validator Version 产生 `HIGH`。

M2.4 不计算 Fixed/Included/Verified。`VALID/MEDIUM` 只说明当前 validator 对一条 provenance Edge 的判断；M2.5 才能结合 Locked Manifest 派生的 `ARTIFACT_RELEASE`、policy 和 Gap 判断连续路径。

## 10. GitHub Actions 有界真实 Smoke

CI Gate 继续只依赖合成、版本化 fixtures；真实 Smoke 是同一 workflow 内的附加 acceptance path：

1. checkout exact commit 并构建受控测试 Artifact；
2. 使用 GitHub 真实 context 形成 Envelope；
3. 启动临时 PostgreSQL 与 Backend；
4. 通过正常 application/test fixture 创建合成 Project、Release、Locked Manifest、Issue Sync/Snapshot 与 Artifact；
5. 使用 Runner 内临时测试 Service Identity，经真实 HTTP Endpoint ingest；
6. 重放相同/不同 Idempotency Key，执行 conflict 和 unauthorized negatives；
7. 查询 PostgreSQL 证明 authority、Revision、Audit/Outbox 与无 `ARTIFACT_RELEASE` 写路径；
8. 上传只含摘要的 Evidence Artifact。

真实字段限于 repository identity、commit SHA、workflow reference、run ID/attempt、Artifact checksum 和 proof locator。Issue/Release 使用合成 fixture；不访问真实 Jira、公司 CI 或生产系统。Backend 不暴露公网。Smoke Evidence 只记录 exact commit、Run/Attempt、schema/validator version、Envelope digest、Artifact digest、Edge/Revision IDs、重放结果、固定 diagnostics 和测试汇总。

## 11. API、错误与安全

Endpoint 只允许 `traceability:ingest` Service Identity；普通用户 JWT 即使属于 Project Admin 也返回 403。Token project claim 必须与 body project 解析到同一 Project。SQL 全部参数化；Provider、Repository、Pipeline、Build ID、proof reference 和 source issue ID 均有字符/长度 allowlist。

固定错误包括：`RESOURCE_NOT_FOUND`、`PROJECT_SCOPE_MISMATCH`、`SNAPSHOT_ISSUE_NOT_FOUND`、`ARTIFACT_NOT_FOUND`、`ARTIFACT_DIGEST_MISMATCH`、`PROOF_VALIDATION_FAILED`、`IDEMPOTENCY_CONFLICT`、`BUILD_PROVENANCE_CONFLICT`、`FACT_LIMIT_EXCEEDED` 与 `PERSISTENCE_UNAVAILABLE`。404 对不可见资源不泄露存在性；Domain input 为 422；identity/authority 冲突为 409；短暂数据库不可用为 503。

禁止保存或输出完整 GitHub event payload、environment dump、Token、Cookie、Authorization header、Commit author email、PR body/comment、Runner path、原始 Provider error/response 或 stack trace。日志、Problem Details、Audit、Outbox 与 CI Artifact 只使用稳定 ID、版本、counts、digest 和固定 diagnostic。

## 12. 测试与 Evidence 矩阵

- Contract：v2 required/additional fields、长度、数组/payload 上限、v1 superseded、Service Identity、普通 JWT 拒绝、禁止结论/`ARTIFACT_RELEASE` 字段。
- Unit：canonical Envelope/fact digest、Unicode code point 排序、duplicate removal、proof normalization、derived fact count 与 validator policy。
- PostgreSQL：Edge Header 唯一性、typed endpoint FK、Revision chain、immutable trigger、Build Attempt uniqueness、cross-Project、并发相同 Envelope 和 Migration repeat/upgrade。
- Application：Snapshot-only Issue resolution、Commit/Build reuse、Artifact checksum、两层幂等、同 Build Attempt conflict、proof contradiction、新 Validator Revision。
- Transaction：Commit、Build、任一 Edge、Receipt、Audit、Outbox 或 Idempotency failure 均不产生部分成功。
- Security：用户 JWT、错误 Project、未知 Provider、control character、oversized payload、log/Problem/Artifact sensitive scan。
- Authority：Schema/代码中不存在可写 `ARTIFACT_RELEASE`；Artifact 无 `build_id`；Locked Manifest view 仍是唯一 Artifact→Release 来源。
- Replay：重验只插入 Revision；旧 Revision 和 M2.3 Issue Snapshot bytes/digest 不变。
- Live Smoke：GitHub exact-head Build/Artifact metadata 经 HTTP 入库，重复提交收敛，并上传脱敏 Evidence。

Owner Gate Evidence 至少包含 PostgreSQL Integration Test、transaction failure report、concurrency/idempotency report、GitHub Actions Smoke、sensitive scan、Pair Gate 和 exact Git/CI locator。Fixture PASS 与 live Smoke 分别报告，任何一项失败不得被另一项覆盖。

## 13. Migration、部署与恢复

使用 forward-only Expand Migration 增加 Edge Header、Build Attempt/Receipt、rejected receipt、复合 FK/UNIQUE/Trigger 与必要索引。部署前运行 precondition query；若发现与新 authority tuple 冲突的数据，Migration 必须失败并生成脱敏诊断，不自动合并或删除。

继续使用 Modular Monolith、Kotlin/Spring Boot 和 PostgreSQL，不增加 Broker、Redis、图数据库、新服务、对象存储、管理 UI 或公网 Callback。M2.4 Pilot Feature 默认关闭；部署顺序为 Migration、兼容应用、Gate、显式启用。Company Profile 不继承 Pilot 默认凭据或 Confidence。

应用回滚时关闭 ingestion 入口并保留新表、Receipt 和 Revision；不得 reverse Migration 或删除历史。暂时数据库故障由调用方使用原 Idempotency Key 有界重试，不使用 JSON/file/cache fallback。数据库恢复后对账 Edge Header/Revision chain、Envelope/fact digest、Receipt、Audit、Outbox 和 Idempotency response，发现不一致时保持 fail-closed 并 roll-forward。

## 14. V0.2、V0.3 与 Cut Line

V0.2 只实现 GitHub Actions Pilot Provider、一个同步 ingestion Endpoint、三类 typed Edge、最高 `MEDIUM` Confidence 和 PostgreSQL authority。若进度延误，依次删除格式化 Smoke 报告、额外指标和非关键查询；不得删除 Snapshot-bound Issue resolution、Artifact checksum、atomic transaction、typed Edge Header/Revision、两层幂等、冲突保留、Manifest-only Artifact→Release、Audit/Outbox 或安全负向测试。

V0.3 可以新增 Jenkins/GitLab/公司 CI Adapter、签名 Attestation、批量异步 ingestion 或更高容量，但必须复用 normalized Envelope/Validator Port，并通过新 schema/validator version 新增 Revision。只有真实吞吐、签名供应链或部署约束证明当前同步 PostgreSQL 方案不足时，才评估 Queue、独立 ingestion service 或 Raw Attestation Store。

## 15. Technology Decision Delegation

本设计的关键技术决定记录在 `TDR-017`，逐项回答选择原因、问题、替代方案、V0.2/V0.3 影响、迁移、测试、部署与失败恢复。Project Owner 已批准 `M2-KD-2026-09-03-01` Written Spec Review，当前状态为 `Accepted`。

## 16. 停止条件与 Written Spec Review Gate

出现以下情况立即停止并提交 Finding、TDR 修订或 ADR Proposal：需要改变 Fixed/Included/Verified；需要允许 CI 写 `ARTIFACT_RELEASE`；需要以当前最新 Issue 替代 Snapshot；需要把 GitHub DTO 放入 Core；需要调用方提交状态/Confidence；需要第二结构化数据源或静默 fallback；需要保存 credential/原始公司数据；或当前容量迫使删除不可削弱项。

本规范提交后先由 Project Owner 书面评审。批准 Written Spec 只授权创建独立 Implementation Plan，不授权生产代码、Migration、真实 Jira、真实公司 CI、Company、M2.5、merge、Tag、release 或 production deployment。Implementation Plan 的执行继续使用独立授权。
