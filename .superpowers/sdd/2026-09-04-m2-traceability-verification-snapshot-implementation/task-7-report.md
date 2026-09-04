# Task 7 报告——Performance、Recovery、Candidate Gate 与 Owner Record

## 状态

候选 Gate、性能/恢复测试、只读 CI 和 Pilot 运维说明已实现。Owner 验收记录必须在候选 Gate commit 后以其真实 SHA 创建，且保持 `PENDING`。Review fix round 1 已把恢复演练改为真实 PostgreSQL dump/独立 restore/容器 restart，并把 Evidence 边界收紧为递归 exact-property allowlist；round 2 进一步用 PostgreSQL 自身的 postmaster start time 证明数据库进程确实更新，并补齐不掩盖主失败的嵌套清理。本机没有可用 Docker/Testcontainers runtime，因此 PostgreSQL 性能与恢复用例只完成编译，真实 fixture、SQL、事务、性能样本与恢复断言尚未执行；exact-head Linux/Docker CI 是强制验收证据。

## 边界与关联文件

- 未修改生产代码、V11 Migration、Core Contract、Fixed/Included/Verified、Manifest authority、Issue Snapshot authority 或 M2.4 Edge Revision authority。
- `.github/workflows/m2-backend.yml` 在 Task 7 开始时不存在；计划把它列为 Modify，实际按既有 `m1-backend.yml` 的只读惯例新建。Workflow 只有 `contents: read`，没有 Jira/GitHub Provider credential、Company 调用或外部写权限。
- Gate 的固定 `replay` 阶段内部依次执行历史 replay 和 recovery suite，以保持批准的 12 个顶层 check 名称/顺序，同时不遗漏独立恢复测试。
- 计划要求候选首提交不含 Owner record，因此首提交只验证 Gate orchestration、编译、Contract 与既有 Acceptance；第二提交创建 PENDING record 后，才具备运行完整 M2.5 Gate 的输入。

## TDD RED / GREEN

1. 先创建 `scripts/tests/m2-5-verify-gates.tests.ps1`，首次执行准确失败为 `Missing M2.5 verification gate`，exit `1`。
2. 实现最小 `verify-m25.ps1` 后，orchestration test 首次失败暴露 recovery suite 没有被顶层 replay 阶段调用，Evidence 中 `recovery=null`。把 recovery 固定为 replay 阶段的第二个命令后，同一测试 GREEN。
3. 新增性能/恢复测试后首次 `compileTestKotlin` 失败，原因是 query-count wrapper 的 delegate 没有保留为 property；改为 `private val delegate` 后编译 GREEN。该失败属于测试代码接线，并非生产行为 RED。
4. 聚焦 PostgreSQL 执行准确失败于 `DockerClientProviderStrategy`：2 个测试均未进入 Spring/PostgreSQL fixture、SQL 或业务断言，未将环境阻塞记为 PASS。
5. Review fix round 1 先让恢复测试引用尚不存在的 `restoreSnapshotAndRestartDatabase`，`compileTestKotlin` 按预期 RED 于 unresolved reference；完成真实 dump/restore/restart 边界后同一编译 GREEN。
6. 在 performance 子报告加入额外 synthetic token 字段后，原 Gate RED 为没有固定 `EVIDENCE_INVALID`；加入 raw JSON 扫描、每层 exact-property allowlist、显式重建和总 JSON 复扫后 Gate test GREEN。Windows 与 Unix 绝对路径 mutation 也必须 fail-closed，并删除可能上传的两个子报告。
7. `VSRQG_M25_STUB_FAIL_PATTERN=TraceabilityVerificationConcurrencyTest` 让真实 Gradle stub child exit `23`；Gate 保留该首个 exit code，继续执行到 acceptance，输出全部 12 项、总 `FAILED`、Evidence 与 digest。
8. Review fix round 2 先让 restart 测试引用尚不存在的 PostgreSQL process identity helpers，`compileTestKotlin` RED 于 unresolved reference；实现后编译 GREEN。断言比较 restart 前后 `pg_postmaster_start_time()` 且要求严格变晚，所以仅创建新 Worker、DataSource 或连接而不 restart 数据库会失败。

Gate orchestration 通过 mutation/fixture 验证：transaction 注入失败时后续检查仍执行，总状态 `FAILED`；dirty tree 为 `WORKTREE_DIRTY`；CI SHA 与 HEAD 不一致为 `EXACT_HEAD_MISMATCH`；child 输出不回显；子报告中的额外 secret、Windows/Unix 绝对路径会固定失败并从上传集合删除；失败和成功均生成 `m2-5-evidence.json` 与 SHA-256 sidecar；20/2,000 fixture、recovery/replay 和 Owner `PENDING` 均由显式 allowlist Evidence 断言保护。

## 性能设计

- 真实 PostgreSQL fixture 固定为 20 个 Issue 和恰好 2,000 条 fixed Edge；Start 后直接断言 Input Ledger 2,000，Worker 后直接断言 Snapshot Edge 2,000，禁止截断或缩小。
- 在同一固定输入上使用 3 个独立 Idempotency-Key 测量 start、worker、query；记录 sample count、p50/p95/max、CPU 数、JVM max memory、Java/OS runtime metadata。
- 参考目标为 start/worker/query P95 `≤1s/≤10s/≤1s`；共享 CI 的可复现硬上限为 `30s/60s/30s`，只防止算法退化，不声称 Company 性能达标。
- Query 使用真实 Repository 的 counting decorator，20 个 Issue 时固定为 release/header/issues/paths/gaps 各 1 次；任何逐 Issue 或逐 Edge查询都会改变计数并失败。

## 恢复设计

- 共享测试 PostgreSQL 只执行 custom-format `pg_dump --no-owner --no-privileges`；dump 被恢复到独立 `postgres:17.11` 容器，恢复命令固定为 `pg_restore --exit-on-error --no-owner --no-privileges`。测试从恢复数据库的 fresh `DataSource`、`JdbcClient` 和 `JdbcTraceabilityVerificationRepository` 读取固定输入与 Snapshot relational facts，只调用唯一 `TraceabilityVerifier`/canonicalizer 重算 digest，并逐项比较 producer、issue result、path、gap 与 `content_digest`；不读取 latest Revision 或外部系统。
- restart 场景在同一独立恢复容器保留 crash 前已持久化的 RUNNING Job；restart 前后都通过新连接查询 `pg_postmaster_start_time()`、`pg_backend_pid()` 与 `SELECT 1`，并要求 postmaster start time 严格变晚，以证明真实数据库进程边界。之后重建 DataSource/repository/transaction manager，lease 后 reclaim，attempt 必须从 1 增至 2。共享 Testcontainer 不被 restart 或 stop。
- source container dump、独立 restore container 和 host dump 使用嵌套 `finally` 语义清理：`pg_dump` 非零后仍尝试删除可能的 partial container dump，container stop 失败后仍删除 host dump；cleanup failure 附加为 suppressed，不覆盖首个测试失败，也不被吞掉形成假成功。
- poison trigger 连续三次使 Run `FAILED`、Job `DEAD_LETTER`；清除根因后以新 Idempotency-Key 创建新 Run 并成功，旧 Run/Job 终态再次复读仍不变。

## Gate 与 Evidence

固定顶层顺序：

1. clean-tree
2. fixed-commit
3. contract
4. migration
5. domain
6. transaction
7. concurrency
8. replay（含 recovery）
9. performance
10. secret
11. acceptance
12. evidence-digest

任一失败不会中止后续顶层检查。Gate 不回显 child stdout/stderr，只输出 commit、固定 check/status/tests/diagnostic、summary 和失败 check；性能和恢复报告均先扫描 raw JSON，再按每层 exact-property allowlist 重建并覆写，最终总 JSON 再扫描；任何 secret 或绝对路径使检查固定为 `EVIDENCE_INVALID`，相关子报告被删除。Evidence 输出：

- `backend/build/m2/m2-5-evidence.json`
- `backend/build/m2/m2-5-evidence.json.sha256`
- `backend/build/m2/traceability-performance.json`
- `backend/build/m2/traceability-recovery.json`

## 本地验证

- `pwsh -NoProfile -File scripts/tests/m2-5-verify-gates.tests.ps1`：`PASS m2-5-verify-gates`。
- `./backend/gradlew.bat -p backend compileTestKotlin --no-daemon`：Review fix round 2 源码 `BUILD SUCCESSFUL`。
- `npm run test:contracts`：`PASS contracts schemas=4 positive=12 negative=5 operations=34`。
- `npm run verify:acceptance`：`PASS acceptance-records`。
- `./backend/gradlew.bat -p backend cleanTest test --tests '*TraceabilityVerificationPerformanceTest' --tests '*TraceabilityVerificationRecoveryTest' --rerun-tasks`：`BUILD FAILED`；2/2 均因本机 Docker/Testcontainers 初始化失败，未执行 PostgreSQL 语义。

## Commit

- 候选 Gate Subject：`test(m2): add traceability verification candidate gate`。本报告位于同一 commit 中，因此不在自身内容内嵌该 commit 的 SHA；外部交接必须使用 `git rev-parse HEAD` 的真实值。
- Owner record 使用候选 Gate 的真实 SHA 后单独提交，Subject 固定为 `docs(m2): add traceability owner gate candidate`。

## 剩余风险

- exact-head Linux/Docker CI 必须实际生成 20/2,000 performance 和 recovery Evidence，并验证 hard limits、query count、digest restore、RUNNING reclaim、poison dead-letter 与新 Run retry。
- 共享 CI 的宽松硬上限不是 Company 性能承诺；固定参考环境尚未建立，Owner record 必须保持 `PENDING`。
- Evidence Artifact 有 30 天保留期；后续 Owner 复核或归档应绑定 exact commit、Run、Artifact ID 与 digest。
