# Task 5 报告——Worker Materialization、Recovery 与 Concurrency

## 状态

实现和本地静态/非 PostgreSQL 验证已完成。独立评审 fix round 1 后，Task 5 有 25 个 PostgreSQL 行为测试和 3 个无数据库重试测试；全部成功编译，3 个重试测试本地 GREEN。首次中英文 exact-head CI 均在第一个 PostgreSQL Context 启动时因缺少测试 OIDC 属性失败，25 个用例没有执行 PostgreSQL 语义；CI fix round 1 已把固定测试 issuer/audience 收敛到共享 `PostgresIntegrationTest` 单一权威，并用不启动 Docker 的结构回归覆盖全部直接/间接派生 Context。由于本机没有 Docker/Testcontainers runtime，25 个 PostgreSQL 测试仍在容器初始化阶段停止，尚未执行 fixture、SQL、事务或业务断言；修复后的 exact-head CI PostgreSQL GREEN 仍是强制验收条件。

## 目标与边界

- Worker 使用 PostgreSQL `background_job` 领取 `TRACEABILITY_VERIFY`，只从 Run、固定 Issue Snapshot、Locked Manifest ID 和 `traceability_verification_run_edge_input` 的精确 Revision 引用加载输入。
- 纯计算复用 Task 3 `TraceabilityVerifier` 与唯一 `TraceabilityCanonicalizer`；没有第二套 Fixed/Included/Gap 算法或 digest 实现。
- 结果事务一次性写入 Snapshot Header、Issue Result、全部固定 Snapshot Edge、主路径 Edge、Run/Snapshot Gap、Audit、Outbox、Run 与 Job 终态。
- 未读取 latest Edge Revision，未调用 Jira、GitHub、CI、Device、Adapter 网络接口，未从 JSON/file/cache 恢复权威数据，未新增 Broker、Redis、图数据库、服务或 JVM lock。

## 计划关联改动

计划的 Task 5 文件清单只列出新 Run/Worker 和三个测试文件，但其 `Consumes` 明确要求 `TraceabilityVerificationRepository.claimNext(now)`，Task 4 的 Port/Adapter 当时只包含创建事务方法。若不扩展既有 Port/Adapter，只能在 Worker 中直接写 JDBC 或建立第二个持久化边界，都会破坏既有单一 Repository authority。

因此做了最小关联改动：只向 `TraceabilityVerificationRepository` 和 `JdbcTraceabilityVerificationRepository` 追加 claim、固定输入加载、原子结果写入和失败恢复方法；未修改 Migration、Task 3 Domain/Canonicalizer、Controller、配置文件或冻结契约。

## TDD RED 证据

生产实现前先创建三个批准测试文件并运行：

`./backend/gradlew -p backend test --tests '*TraceabilityVerificationWorkerIntegrationTest' --tests '*TraceabilityVerificationWorkerFailureTest' --tests '*TraceabilityVerificationConcurrencyTest'`

结果：`compileTestKotlin` 明确失败，缺失 `TraceabilityVerificationJobWorker`、`runNext()`、Repository `claimNext(now)` 和 Claim 字段。首次测试草稿还暴露了 Kotlin test visibility 噪声；修正测试可见性后，剩余失败均指向尚未实现的 Task 5 API。这是功能缺失 RED，不是环境跳过。

独立评审 fix round 1 补充了可执行的 transaction retry mutation。先增加 1/2 次冲突后成功和第 3 次冲突必须逃逸的测试，再临时把 `MAX_VERSION_ATTEMPTS` 从 3 改成 2。结果 3 个测试中 2 个按预期失败：2 次冲突用例过早抛出，严格上限用例只观察到 2 次调用。恢复常量 3 后同一命令 `3/3` GREEN。这证明测试保护的是有界 transaction retry 行为，不是常量文本。

首次 exact-head CI 暴露共享测试配置缺口后，先扩展不启动 Docker 的 `PostgresIntegrationPoolBudgetTest`，再修改共享基类。基线运行共 4 项，其中新增 2 项按预期 RED：共享 issuer 实际为 `null`；`IssueSnapshotIntegrationTest` 首先被发现仍直接声明 OIDC 键。测试通过 ArchUnit 扫描所有 `isAssignableTo(PostgresIntegrationTest)` 的直接/间接派生类，使用 Spring `buildMergedContextConfiguration()` 验证 effective issuer/audience，并检查派生类不得直接重复声明这两个键。把固定属性移入共享基类并删除 23 个派生 Context 的重复声明后，同一测试 `4/4` GREEN。结构测试还用字面期望保护八个保留的 feature/trusted-validator 增量配置；临时把 Worker 的 `vsrqg.traceability.verification.enabled` 从 `true` 改成 `false` 后，实际运行得到 `4 tests completed, 1 failed`，恢复后 `4/4` GREEN。删除任一共享属性会击穿共享/effective 断言；在任一派生类重新声明 OIDC 键会击穿 override 断言。

Job/Release 行锁、SQLSTATE/constraint 翻译和损坏账本用例必须运行真实 PostgreSQL，不能用 source grep 或 test-only production hook 替代。本机 Docker 阻塞使这些 RED/GREEN mutation 尚未实际执行；报告仅记录其可击穿的 mutation，并将真实执行保留为 exact-head CI 强制验收项。

## 实现

### Claim、attempt 与恢复

- `claimNext(now)` 在独立短事务中使用 `FOR UPDATE OF job, verification_run SKIP LOCKED`；同一个 Job 只能被一个 Worker 领取。
- 首次领取原子执行 Job attempt `+1` 与 Run `QUEUED → RUNNING`。崩溃后，只有 `started_at` 达到 300 秒租约边界才可 reclaim，再次领取继续增加 attempt。
- 临时数据库失败仅捕获 Spring `DataAccessException` 或精确识别的 Snapshot version unique conflict；其他编程/不变量错误直接暴露，不使用宽泛吞错。
- attempt 上限固定为 3。前两次失败将 Job 重新排队并只保存固定 `TRACEABILITY_VERIFICATION_RETRY_SCHEDULED`；第三次把 Run 置为 `FAILED`、Job 置为 `DEAD_LETTER`，只保存固定 `TRACEABILITY_VERIFICATION_RETRY_EXHAUSTED`。数据库异常文本、SQL、URL、stack 或输入内容不会进入 Run/Job。

### 固定输入与纯计算

- Loader 通过固定 Run ID 加载 `issue_snapshot_id`、`manifest_revision_id` 和 Input Ledger；Edge 只按 `(type, source edge ID, numeric revision, revision entity ID, fact digest)` 精确 join append-only authority。
- Loader 不使用 `max(revision)`、latest CTE 或运行时 Adapter。Task 4 创建后新增的 Edge Revision 不会进入本次执行。
- Worker 重算 input canonical digest 并与 Run 的 `input_digest` 比较；不一致以 `TRACEABILITY_INPUT_NOT_VALID` 失败关闭且不生成 Snapshot。
- `VerificationComputation` 完全由 Task 3 verifier/canonicalizer 产生，Worker 只持久化其 `contentDigest`、`resultDigest` 和 `gapDigest`，不重算或改写结论。

### CI fix round 1：共享 PostgreSQL 测试 OIDC 权威

- 中英文首次 exact-head CI 的首个失败均为 `TraceabilityVerificationConcurrencyTest` ApplicationContext；最深根因为 `PlaceholderResolutionException` 无法解析 `${VSRQG_OIDC_ISSUER_URI}`，后续 25 个失败均为 Context failure threshold 级联，并非 PostgreSQL 业务断言失败。
- 根因是共享 `PostgresIntegrationTest` 只持有连接池预算，而既有 23 个 PostgreSQL 测试 Context 各自重复声明固定 issuer/audience；Task 5 的共享派生层只声明 `vsrqg.traceability.verification.enabled=true`，因此生产 `application.yml` 的 OIDC 占位符没有测试值。
- 修复只在测试基础设施中把 `https://idp.vsrqg.test` 与 `vsrqg-api` 放入共享 `PostgresIntegrationTest`，并删除 PostgreSQL 派生类的重复 OIDC 行；各类的 feature flag、trusted validator 和其他增量属性继续通过 Spring `@TestPropertySource` 继承合并。
- `ApplicationContextTest`、`BuildProvenanceGithubSmokeTest` 和不继承 PostgreSQL 基类的 `TraceabilityVerificationStartHttpTest` 仍保留自己的测试 OIDC 边界。生产 `application.yml`、环境变量契约、Hikari 最大连接数 `3`/最小空闲 `0` 均未改变，也未增加环境 fallback。

### 原子物化与复用

- 结果事务先锁定 Claim 对应的 Run/Job，并按 input/result digest 查找已成功且输入身份兼容的 Snapshot。
- 未命中时锁定 `release_record`，再次检查复用后以 `max(version)+1` 分配 Release 内 version；没有 JVM lock。仅精确的 `uq_trace_snapshot_release_version` SQLSTATE `23505` 会触发最多三次完整事务重试，其他 SQL 错误不会被当成版本竞争吞掉。
- Snapshot Edge 由固定 Input Ledger set-based 投影，保持 Ledger ordinal；Path Edge 只引用这些 Snapshot Edge。Domain 的 `TEST_RESULT_EVIDENCE` 在唯一 persistence boundary 映射为既有数据库 token `TEST_EVIDENCE`。
- Run Gap 与 Snapshot Gap 使用同一组稳定字段和 digest 写入。Audit 固定为 `TRACEABILITY_VERIFICATION_SUCCEEDED`，Outbox 固定为 `traceability.verification.succeeded`；最后才更新 Run/Job `SUCCEEDED`。
- 相同输入但不同 Idempotency-Key 保留两个 Run，并复用同一个 content-identical Snapshot；相同输入并发也在 Release row lock 后二次检查并收敛为一个 Snapshot。不同输入并发生成连续 version。

## 测试与失败注入矩阵

三个测试文件共 25 个 PostgreSQL 用例，另有 3 个无数据库 transaction retry 用例，覆盖：

1. 完整链物化 Fixed=true、Included=true、Verified=false、四段主路径和 `TEST_RESULT_EVIDENCE_MISSING`。
2. 请求后新增 INVALID Revision 不改变已固定 Revision 的结果。
3. 不同 Run 的相同输入顺序复用一个 Snapshot。
4. 两个 Worker 对一个 Job 只领取一次。
5. 相同输入并发收敛为一个 Snapshot。
6. 不同输入并发在同一 Release 下产生连续 version 1、2。
7. RUNNING crash 在租约前不可领取、租约边界可 reclaim 且 attempt 递增。
8. 三次失败后 Run FAILED、Job DEAD_LETTER，且诊断脱敏。
9. 参数化的九个结果写入边界：Snapshot Header、Issue Result、Snapshot Edge、Path Edge、Gap、Audit、Outbox、Run terminal、Job terminal。每个注入失败后都要求 Snapshot 数为零、Run 不指向结果、成功 Audit/Outbox 数为零，Job 只进入安全 retry。
10. 一个独立事务持有候选 Job 行锁时，第二个 `claimNext` 必须在 1 秒内返回 `null`，且第一事务仍未释放；删除 `SKIP LOCKED` 会使该断言超时。
11. 一个独立事务持有 `release_record` 行锁时，Worker 必须在 `pg_stat_activity` 中表现为等待真实数据库 Lock，释放后才完成；删除 Release `FOR UPDATE` 会使 Worker 提前完成且观察不到等待。
12. 真实 `uq_trace_snapshot_release_version` 约束的 `23505` 翻译为 `TraceabilitySnapshotVersionConflict`；另一个 `23505` 和 version check violation 保留 Spring `DataIntegrityViolationException`，由 Worker 进入安全 retry，不能被版本冲突重试吞掉。
13. 固定 Run input digest 或 ledger fact digest 被模拟损坏时，Worker 原子形成 Run `FAILED`、Job `DEAD_LETTER`、零 Snapshot/Gap，只保存 `TRACEABILITY_INPUT_NOT_VALID`。
14. `failInvalidInput` 在 Job DEAD_LETTER 写入点失败时，Run FAILED 更新一并回滚，随后只进入安全 retry，不留下半终态。
15. `TraceabilitySnapshotVersionConflict` 连续出现 1 或 2 次后完整事务成功；连续第 3 次必须逃逸，调用次数严格为 3，且每次使用新的 Snapshot ID。

变异证据分层：`MAX_VERSION_ATTEMPTS=2` 已在本地真实 RED、恢复 3 后 GREEN。删除 `SKIP LOCKED` 会让受控第二 claim 在第一事务释放前超时；删除 Release row lock 会让 Worker 提前完成且无 `pg_stat_activity` Lock wait；把冲突识别放宽到任意 `23505` 会让 OTHER_UNIQUE 类型断言失败；让 input corruption 生成 Snapshot、泄露内部 reason 或拆分 invalid terminal 事务会使对应终态/零产物断言失败。这些 PostgreSQL mutation 当前因 Docker 缺失只能由 exact-head CI 实际执行，不能把测试设计误报为本机运行证据。

## 本地验证证据

编译：

`./backend/gradlew -p backend compileKotlin compileTestKotlin`

结果：`BUILD SUCCESSFUL`；生产与测试 Kotlin 均完成编译。

新鲜非 PostgreSQL 门禁：

`./backend/gradlew -p backend cleanTest test --tests '*RunTraceabilityVerificationRetryTest' --tests '*TraceabilityVerifierTest' --tests '*TraceabilityCanonicalizerTest' --tests '*ArchitectureTest' --tests '*ApplicationContextTest' --tests '*PostgresIntegrationPoolBudgetTest' --tests '*M2ApiContractTest' --tests '*TraceabilityVerificationDtoTest' compileKotlin compileTestKotlin --rerun-tasks`

独立评审 fix round 1 最终新鲜执行结果：`BUILD SUCCESSFUL in 1m 8s`；`66/66` 通过，失败、错误、跳过均为零，7 个 Gradle task 全部执行。

CI fix round 1 加入共享 OIDC 结构回归后的最终新鲜执行结果：`BUILD SUCCESSFUL in 1m 24s`；`68/68` 通过，失败、错误、跳过均为零，7 个 Gradle task 全部执行。其中 `PostgresIntegrationPoolBudgetTest` 单独运行为 `4/4` GREEN，覆盖连接预算、共享 OIDC 权威、全部派生 Context 的 effective 合并值、禁止派生 override 和八个增量配置不丢失。

契约门禁：

`npm run test:contracts`

结果：`PASS contracts schemas=4 positive=12 negative=5 operations=34`。

聚焦 PostgreSQL 命令：

`./backend/gradlew -p backend cleanTest test --tests '*TraceabilityVerificationWorkerIntegrationTest' --tests '*TraceabilityVerificationWorkerFailureTest' --tests '*TraceabilityVerificationConcurrencyTest' --rerun-tasks`

CI fix round 1 本地结果：`BUILD FAILED in 36s`，`25/25` 均在 `PostgresIntegrationTest` 的 `DockerClientProviderStrategy` 初始化失败后停止；首个为 `IllegalStateException`，其余为相同初始化失败引起的 `NoClassDefFoundError`。生产与测试源码均重新编译成功，失败链不再是 OIDC 占位符，但仍未执行 fixture、Flyway、SQL、事务或断言。这是本机 Docker 环境阻塞，不代表 PostgreSQL GREEN 或业务失败。

首次 exact-head CI 失败证据：中文 Run `33914382941` / Job `101158044699` / Artifact `9952720393`；英文 Run `33914386537` / Job `101158060276` / Artifact `9952729037`。两边失败形态一致，均未进入 PostgreSQL 语义。修复后的双分支 exact-head CI 尚待同步、推送和重新执行。

无数据库重试门禁：

`./backend/gradlew -p backend test --tests '*RunTraceabilityVerificationRetryTest' --rerun-tasks`

结果：恢复正确上限后 `BUILD SUCCESSFUL in 25s`，`3/3` 通过；临时上限 2 的 mutation 运行明确为 `3 tests completed, 2 failed`。

`git diff --check`：通过。

最终差异审查补强了失败断言：九个边界失败和有界重试耗尽后，除 Snapshot Header 为零外，直接关联 Run 的 `traceability_gap` 也必须为零。补强后执行 `compileTestKotlin --rerun-tasks`，结果 `BUILD SUCCESSFUL in 24s`，4 个 task 全部执行。

本次 Gradle 产生的 `backend/.kotlin` 本地缓存已清理，不进入提交。

## 自检

- 写入事务：九个结果边界均位于同一个 `REQUIRES_NEW` Repository 调用；V11 deferred trigger 在提交时验证完整结果，任何异常回滚全部结果和终态。
- 查询：输入加载是按固定 ID 的集合查询；Edge 物化是单个 set-based `INSERT ... SELECT`；Issue、Path 和 Gap 使用 bounded JSON recordset 批量写入；没有逐 Edge authority 查询。
- 并发：数据库 row/job lock 是唯一同步权威；没有 `synchronized`、JVM collection lock 或本地 mutex。
- 恢复：attempt 与 lease 存在 PostgreSQL；应用重启后可 reclaim RUNNING Job。终态 Run 不恢复，需新建 Run。
- 安全：Job payload 仍只含 Run ID；结果治理 payload 只含 ID、版本、状态和 digest；失败摘要只存 allowlisted code。
- 范围：未修改 V0.1/V0.2 冻结语义、V11 Migration、Task 3 结果、OpenAPI、查询/replay、Company、部署、merge、Tag 或 release。

## Commit

初始实现 Commit：`fc36e90df14a8151bf3b381152b67418cee6beef`，Subject：`feat(m2): materialize traceability snapshots`。

独立评审 fix round 1 Commit：`f2ec0cc92d131e463734194d9976bfb6ed230ee2`，Subject：`test(m2): harden traceability worker invariants`。

CI fix round 1 建议 Subject：`test(m2): centralize postgres test oidc authority`。实现代理将在提交后报告不可变 Commit ID；Commit 不能包含自身 hash。

## 剩余风险 / 交接

- 修复后的中英文 exact-head CI 必须对 25 个 PostgreSQL 用例给出真实 GREEN；重点核对共享 OIDC 合并后 Context 能启动，以及受控 `SKIP LOCKED` 非阻塞、Release 行锁等待、目标/非目标完整性异常翻译、损坏输入失败关闭、invalid terminal 回滚、V11 deferred trigger 顺序、九边界回滚、同输入复用与不同输入 version 连续性。
- 当前 Worker 调度通过 `vsrqg.traceability.verification.worker-enabled=true` 显式开启，默认不启用；Task 7 运维说明应记录 poll/initial delay 环境配置和 Pilot rollout。
- Task 6 只能读取已完成 Snapshot/Run，不得调用本 Worker 重新计算或查询当前 Edge authority。
