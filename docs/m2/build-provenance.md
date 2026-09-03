# M2.4 Build Provenance Envelope 运维规范

## 1. 目的与边界

M2.4 通过一次 `Build Provenance Envelope v2`，把一个 Project、一个 CI Provider 和一个 Build Attempt 的来源事实原子写入 PostgreSQL。入口只创建或复用 Source Commit、Build、三类 typed Edge Header/Revision、Receipt、Audit、Outbox 与幂等响应；它不计算 Fixed、Included、Verified 或 Quality Result，也不写入 `ARTIFACT_RELEASE`。

GitHub Actions 是首个 Pilot Adapter。真实 Smoke 只使用 GitHub 的 repository、exact commit、workflow reference、Run/Attempt 与 Job context；Project、Release、Locked Manifest、Issue Snapshot、Artifact 和 SERVICE Identity 均为 Runner 内合成 fixture。它不访问 Jira、公司 CI 或公网 Backend。

## 2. Endpoint 与 Service Identity

- Endpoint：`POST /api/v1/traceability/facts:ingest`
- 请求头：`Idempotency-Key` 必填，长度 1～128；`Content-Type` 为 `application/json`。
- OAuth scope：`traceability:ingest`。
- JWT claims：可信 issuer、subject、`principal_type=SERVICE`、`project` 和 scope；部署配置还校验既有 audience。
- 数据库 authority：对应 Principal 必须是未 disabled 的 `SERVICE`，目标 Project 未 archived，并且存在 Project assignment。
- 四重一致：JWT SERVICE 类型、JWT `project`、body `project` 与数据库 assignment 必须一致。普通 USER 即使携带 scope 且拥有 Project Administrator 角色也不得写入。

Pilot Feature 默认关闭。只有完成 exact-head Gate 与 Owner 授权后，才可显式设置 `VSRQG_TRACEABILITY_INGESTION_ENABLED=true` 并重启兼容应用；`VSRQG_TRACEABILITY_MAX_PAYLOAD_BYTES` 必须位于 1～262144，默认且最大为 262144。Company Profile 不继承 Pilot 身份或启用状态。

## 3. Envelope、digest 与 authority

Envelope 必须使用 `schemaVersion=2`，并绑定不可变 `releaseIssueSnapshotId`。Issue 只从该 Snapshot Items 解析；Artifact 只按已存在、Project 关联的完整小写 SHA-256 checksum 解析。调用方不能提交内部 Entity ID、Edge ID、Revision、Verification Status、Confidence 或业务结论。

服务端规范化字段和数组顺序后，以 `build-provenance-envelope-jcs/v2` 计算 Envelope digest。Proof digest 覆盖 provider、repository、source revision、pipeline、Build ID/Attempt、workflow reference 与 proof reference；`github-actions-provenance/v1` 只在这些值与 GitHub proof locator 一致时产生 `VALID/MEDIUM`。Artifact checksum 不是 Artifact identity digest，也不得代替 Manifest authority。

同一 Build Attempt authority 为 Project、Provider、Pipeline、Build ID 与 Attempt。所有 Commit/Build、Edge Revision、Receipt、Audit、Outbox 和幂等写入在一个事务内完成；任一写入或 read-back 失败都必须整体回滚。

## 4. Replay、冲突与固定错误

相同 `Idempotency-Key` 和相同 Envelope digest 返回逐字节相同响应；相同 key 配不同 digest 返回 409 `IDEMPOTENCY_CONFLICT`。同一 Build Attempt 使用不同 key 但相同 Envelope digest 复用 accepted Receipt；不同 Envelope digest 返回 409 `BUILD_PROVENANCE_CONFLICT`，并在主事务回滚后用独立事务保留一条脱敏 rejected receipt 与 Audit，不改变已接受事实。

固定边界错误如下：

- 404：`RESOURCE_NOT_FOUND`、`SNAPSHOT_ISSUE_NOT_FOUND`、`ARTIFACT_NOT_FOUND`
- 403：`PROJECT_SCOPE_MISMATCH`；缺少 OAuth scope 使用既有 `ACCESS_DENIED`
- 409：`ARTIFACT_DIGEST_MISMATCH`、`IDEMPOTENCY_CONFLICT`、`BUILD_PROVENANCE_CONFLICT`
- 422：`PROOF_VALIDATION_FAILED`、`FACT_LIMIT_EXCEEDED` 与其他 allowlisted Domain violation
- 413：请求体超过配置上限
- 503：`PERSISTENCE_UNAVAILABLE`

Problem、日志、Audit、Outbox 和 Smoke Evidence 不得包含 raw Envelope、Token、Cookie、Authorization header、GitHub event、Provider response、author email、PR 内容、Runner 路径、绝对路径或 stack trace。

## 5. Gate 与 Evidence

在仓库根目录运行：

```powershell
pwsh -NoProfile -File scripts/tests/m2-build-provenance-gates.tests.ps1
pwsh -NoProfile -File scripts/m2/verify-build-provenance.ps1
```

Gate 固定依次执行 contract、migration、canonical、validator、repository、transaction、security、github-smoke、contracts 和 acceptance。每项只输出 `CHECK <name> PASS|FAILED`、测试计数与固定 diagnostic，并保留首个真实失败 child exit code。缺少 GitHub context 时 `github-smoke` 必须以 `GITHUB_CONTEXT_MISSING` 失败；本机缺少 Docker 时 PostgreSQL 检查以 `POSTGRESQL_RUNTIME_UNAVAILABLE` 失败。这些结果表示未执行，不得记录为 PASS，只能由绑定 exact commit 的 Linux/Docker GitHub Actions 补足。

成功的真实 Smoke 通过随机本地端口的实际 HTTP Endpoint 执行首次提交、same-key replay、different-key replay、Build Attempt conflict、USER 与 wrong-project 负向路径，再从 PostgreSQL 核对 Receipt、rejected receipt、Edge/Revision、Audit、Outbox、Locked Manifest 以及不存在 `ARTIFACT_RELEASE` 写入。它只生成 `backend/build/m2/build-provenance-smoke.json`，并由同一只读 workflow 上传为 `m2-build-provenance-${{ github.sha }}`；文件只含 exact commit、Run/Attempt、schema/validator version、Envelope/Artifact digest、Edge/Revision ID、replay boolean、固定 diagnostics 和 counts。

## 6. 关闭与 roll-forward 恢复

发现完整性异常、连续冲突或数据库不可用时：

1. 设置 `VSRQG_TRACEABILITY_INGESTION_ENABLED=false` 并重启应用，立即关闭新 ingestion；保留新表、accepted/rejected Receipt 和全部 Revision。
2. 保存 exact commit、固定 Gate summary、CI Run 和脱敏 Evidence locator；不得 reverse Migration、删除历史、修改旧 Revision 或启用 JSON/file/cache fallback。
3. 临时数据库故障由调用方使用原 `Idempotency-Key` 做有界重试。数据库恢复后对账 Edge Header/Revision chain、Envelope/fact digest、Receipt、Audit、Outbox 和幂等 response。
4. 通过完整 Gate，并在同一 exact commit 的 Linux/Docker CI 取得成功 Smoke Artifact。发现任何不一致时继续 fail-closed，以新的 forward-only Migration 或兼容应用修复。
5. 由 Project Owner 复核 Evidence 并单独批准恢复后，才可重新启用 Pilot 入口。

这些步骤不授权 Company、真实 Jira、公司 CI、M2.5、`main`/`release` merge、Tag、release 或 production deployment。
