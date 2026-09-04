# Task 6 报告——Read API、Security 与 Replay

## 状态

Task 6 的生产实现、TDD、非 PostgreSQL 门禁与契约验证已完成。真实 PostgreSQL Query、Replay 与 Security 测试均已编译，但本机 Testcontainers 在 `DockerClientProviderStrategy` 初始化阶段失败，因此没有执行数据库 fixture、SQL 或业务断言；这些用例不得记为 PASS，必须由绑定本提交精确 HEAD 的 Linux/Docker CI 补足。

## 目标与边界

- 提供 `GET /api/v1/traceability-verification-runs/{verificationRunId}`，按公开 `verification_run_id` 返回已持久化 Run 状态。
- 提供 `GET /api/v1/releases/{releaseId}/traceability`，默认返回最新成功 Snapshot，或按 `snapshotId` 精确读取历史 Snapshot。
- 两个入口均要求 `traceability:read` scope，并在 Application 层按 Run/Release 所属 Project 校验 membership；未知、跨 Project 和不可见目标统一为 enumeration-safe `404 RESOURCE_NOT_FOUND`。
- 查询只读取 Task 5 完成的不可变 Snapshot 与 producer Run，不读取 source Revision 表、`artifact_release_edge_v`、latest Revision、Jira、GitHub、CI、Device 或 Adapter，也不重新计算 Fixed/Included/Path/Gap/digest。
- 未修改 V0.1 冻结架构、V11 Schema、OpenAPI、进度台账、Company、部署或发布边界。

## 计划关联改动

计划文件只显式列出 Application query、Controller 和测试文件，但 Task 6 Step 2 明确要求 set-based Repository read methods。Task 5 的既有 Repository 尚无读取接口；若在 Application/Controller 直接使用 JDBC 会建立第二持久化权威。因此对既有 `TraceabilityVerificationRepository` 与 `JdbcTraceabilityVerificationRepository` 做了最小扩展：加入 Run、Release Project、Snapshot Header、Issue Result、Path Edge、Gap 六类读取方法，继续保持单一 Repository authority。

Controller 新增 `GetTraceabilityVerification` 构造参数后，两个既有测试 fake/mock 仅补齐必要接口，不改变原行为。

## TDD RED / GREEN 证据

1. Run query RED：先创建集成测试并调用尚不存在的 `GetTraceabilityVerification`；`compileTestKotlin` 以 unresolved class/method 失败。实现最小 Run DTO、Project authorization 和 Repository 查询后编译 GREEN。
2. Snapshot query RED：先加入 `getSnapshot` 调用；`compileTestKotlin` 因方法缺失失败。实现 header/issues/path/gap 的固定批量读取与内存按 ordinal 组装后 GREEN。
3. HTTP RED：先写两个 GET 的 strict DTO/serialization 测试；Controller 尚无映射时返回 `404`，断言失败。接入 Controller、read scope 和 Task 1 DTO adapter 后测试 GREEN。
4. N+1 mutation：基线用 20 个 Issue 验证固定读取次数；随后临时把 Path 读取改为逐 Issue 调用，测试明确失败为 `paths=20` 而期望 `1`。恢复单次集合读取后 GREEN。
5. Problem Details RED：模拟 `DataAccessResourceFailureException` 时测试最初抛出 `ServletException`。完整 stack/log 证明业务异常已进入 Advice，但测试的 mock `IdGenerator` 返回 `null`，使 `RequestIdFilter` 删除 request attribute，随后 ProblemWriter 因缺少 Request ID 二次失败。只修复测试 fixture、固定返回 `req_traceability_query` 后，同一 HTTP suite `2/2` GREEN；未用 fallback 或吞异常掩盖根因。

## 实现与 SQL 权威

### Run GET

- JDBC 按公开且唯一的 `verification_run_id` 查询 V11 fixed-input Run；差异审查发现初稿错误地投影内部 `id` 作为响应 ID，已改为投影并返回公开 `verification_run_id`。
- Application 先取得 Run 的 `project_id`，再调用唯一 `ProjectAuthorizer` 校验 `TRACEABILITY_READ`；授权失败与不存在均抛出相同 `ResourceNotFound`。
- 状态只允许 `QUEUED/RUNNING/SUCCEEDED/FAILED`；诊断只允许 `TRACEABILITY_INPUT_NOT_VALID`、`TRACEABILITY_VERIFICATION_RETRY_EXHAUSTED`，其他持久化值统一折叠为 `TRACEABILITY_VERIFICATION_FAILED`。响应不包含 raw reason、SQL、URL 或源系统字段。

### Snapshot GET

- Release Project lookup：1 次；权限通过后才读取 Snapshot。
- Header：1 次。精确模式按 `(release_id, snapshot_id)`；默认模式按 `version DESC, id DESC LIMIT 1`。两者都必须 join producer Run，并要求 producer `status='SUCCEEDED'` 且 `result_snapshot_id=snapshot.id`，所以 QUEUED/RUNNING/FAILED Run 不会冒充结果。
- Header 身份取自不可变 Snapshot 和 producer fixed-input Run；`manifestDigest` 只从当前 Snapshot 的已物化 `ARTIFACT_RELEASE` Edge 读取，不回查 `manifest_revision`。
- Issue Result：1 次，按持久化 `ordinal` 排序；Path Edge：1 次，按 `issue_ordinal,path_ordinal` 排序；Gap：1 次，按 `issue_ordinal,gap.ordinal` 排序。Application 仅按持久化 Issue ordinal 分组装配。
- 因而无论 Issue 数量，Snapshot Repository 固定执行 5 次读取（Release、Header、Issues、Paths、Gaps），其中 header/issues/path/gap 各严格 1 次；真实 `JdbcProjectAuthorizer` 另外执行 1 次 membership SQL，完整授权读取路径合计 6 个数据库 round trips。两部分都与 Issue 数无关，不存在 per-Issue query。当前 20 Issue query-shape 测试以 fake authorizer 隔离并严格证明 5 次 Repository 调用，不把它误报为完整数据库查询数。

## Security 与错误语义

- 两个 GET 均使用 `@PreAuthorize("hasAuthority('SCOPE_traceability:read')")`；缺少专用 scope 返回 `403 ACCESS_DENIED`。
- Run membership 由 Run `project_id` 判定；Snapshot membership 先由 Release `project_id` 判定，并再次要求 Snapshot `project_id` 与 Release 一致，失败关闭。
- SecurityAcceptance 增加专用 scope 403，以及未知 Run/跨 Project Release 同形 404 测试。
- Controller Advice 对读取期间四类 retryable persistence failure 返回固定 `503 PERSISTENCE_UNAVAILABLE` 和固定 GET detail；异常中的 JDBC URL/host/database 不进入响应。POST 原有幂等重试语义保持不变。

## Replay 证据设计

`TraceabilityReplayTest` 先完成一个 Snapshot，再以具有 `SCOPE_traceability:read` 的已认证 JWT 通过真实 Security filter、Controller、Task 1 public DTO、Application、`JdbcProjectAuthorizer` 和 Repository 调用精确 `snapshotId` GET，保存 `response.contentAsByteArray` 与公开 JSON 中的 `snapshot.contentDigest`；随后插入 M2.4 `revision+1`（INVALID）和新 Issue Snapshot，再次调用同一公开 GET，要求 response bytes 逐字节一致且 digest 不变。测试同时固定顶层 public JSON 字段顺序、Issue canonical 顺序和四段 Path canonical 顺序；读取路径没有 current/latest authority 查询，因此未来事实只能影响新 Run/Snapshot，不能重写历史响应。

真实 Replay 行为尚待 Docker CI 执行，本报告只确认其测试源码编译成功，不把设计或编译误报为 PostgreSQL PASS。

## 本地验证

最终非 PostgreSQL 门禁：

`./backend/gradlew -p backend test --tests 'com.ricezhou.vsrqg.ApplicationContextTest' --tests 'com.ricezhou.vsrqg.ArchitectureTest' --tests 'com.ricezhou.vsrqg.M2ApiContractTest' --tests 'com.ricezhou.vsrqg.traceability.TraceabilityVerificationDtoTest' --tests 'com.ricezhou.vsrqg.traceability.TraceabilityVerificationQueryHttpTest' --tests 'com.ricezhou.vsrqg.traceability.TraceabilityVerificationReadQueryShapeTest' --tests 'com.ricezhou.vsrqg.traceability.TraceabilityVerificationStartHttpTest' --rerun-tasks`

结果：`BUILD SUCCESSFUL`；7 suites、25 tests、0 failures、0 errors、0 skipped。生产和测试 Kotlin 均在该次执行重新编译。

契约门禁：

`npm run test:contracts`

结果：`PASS contracts schemas=4 positive=12 negative=5 operations=34`。

PostgreSQL 聚焦测试在源码编译完成后停于 Testcontainers `DockerClientProviderStrategy`，本机未执行 Query 5 个数据库用例、Replay 1 个数据库用例及 SecurityAcceptance 中相关数据库用例。这是环境阻塞，不代表业务失败，也不代表 GREEN；没有重复启动或绕过 Docker。

## 自检

- Authority：没有第二数据源、fallback、cache 或重新计算；Repository 仍是唯一 JDBC 边界。
- Ordering：Issue/Path/Gap 全部按数据库持久化 ordinal 排序；Application 不自行重新排序。
- Query shape：20 Issue mutation test 证明 header/issues/path/gap 的调用次数不随 Issue 数增长。
- Immutability：精确历史读取仅引用 Snapshot materialization 与其 producer fixed-input Run；后续 Revision/Issue Snapshot 不在读取 SQL 中。
- Security：scope、Project membership、统一 404、固定 503 和敏感异常脱敏均有测试。
- Compatibility：既有 POST HTTP 回归 `2/2`、ApplicationContext `2/2`、Architecture `6/6`、M2 API contract `10/10` 均 GREEN。

## 文件

- 新增 `GetTraceabilityVerification.kt`。
- 修改 `TraceabilityVerificationRepository.kt`、`JdbcTraceabilityVerificationRepository.kt`、`TraceabilityVerificationController.kt`。
- 新增 `TraceabilityVerificationQueryIntegrationTest.kt`、`TraceabilityReplayTest.kt`。
- 修改 `SecurityAcceptanceTest.kt`、`TraceabilityVerificationStartFailureTest.kt`、`TraceabilityVerificationWorkerFailureTest.kt`。

## Commit

建议 Subject：`feat(m2): expose immutable traceability results`。实现代理提交后报告不可变 Commit ID；报告不能预写自身 Commit hash。

## 剩余风险与下一步

- 强制下一步是把本提交同步到英文分支，并在绑定中英文精确 HEAD 的 Linux/Docker CI 中执行 Query、Replay、Security 与完整 M2.5 Gate；必须核对实际 PostgreSQL test count、0 failure/error/skip、历史 bytes/digest 和安全 403/404。
- 未获得上述 CI Evidence 前，Task 6 只能标记为“实现完成、PostgreSQL 验收待补”，不能创建 APPROVE Owner Gate 事实。
- 本任务没有 push、merge、Tag、release、deploy，也没有修改 progress ledger。

## Fix round 1——Public Replay 与查询计数校正

- 独立评审结果：0 Critical、1 Important、1 Minor。Important 指出初始 Replay 直接序列化 Application `TraceabilitySnapshotResult`，无法证明 Task 1 公开 DTO、Controller 与真实 GET response bytes 的历史稳定性；Minor 指出初始报告把 fake authorizer 下的 5 次 Repository 调用误称为完整数据库查询数。
- RED：在修改测试前运行公开边界结构验收，明确失败为 `RED: replay does not capture bytes from the public GET boundary`；旧源码也不包含 `SCOPE_traceability:read`。这直接证明评审所述覆盖缺口存在，不是生产行为失败。
- GREEN：Replay 现使用 `@AutoConfigureMockMvc`，以 fixture Project member 的 issuer/subject 和 `SCOPE_traceability:read` 发起精确 `snapshotId` GET。请求实际经过 Security、Controller、Task 1 public DTO、Application、`JdbcProjectAuthorizer` 和 JDBC；不再调用 `writeValueAsBytes(applicationResult)`。`compileTestKotlin --rerun-tasks` 为 `BUILD SUCCESSFUL`，结构验收同时证明 public GET/read scope 存在、直接 Application serialization 已消失。
- Public JSON 确定性：测试断言顶层字段严格为 `snapshot,issues`，两个 Issue 按 Task 1 canonical 次序，首个 Issue 的 Path 严格为 `ISSUE_COMMIT,COMMIT_BUILD,BUILD_ARTIFACT,ARTIFACT_RELEASE`；前后从公开 JSON 解析 `snapshot.contentDigest` 比较，并对两次 `response.contentAsByteArray` 做逐字节比较。任何 Controller mapping、Task 1 DTO 字段/顺序或序列化回归都会击穿真实 PostgreSQL Replay 用例。
- 查询计数裁定：20 Issue 非数据库 shape test 只证明 5 次 Snapshot Repository 调用；生产完整 Snapshot GET 还包含 `JdbcProjectAuthorizer` 的 1 次 membership SQL，因此是总计 6 个数据库 round trips。两者均为与 Issue 数无关的常数，no N+1 结论不变。未为了精确计数引入 datasource proxy 或脆弱 instrumentation。
- 最终非 PostgreSQL 回归仍为 7 suites、25 tests、0 failure/error/skip；Contract 仍为 34 operations PASS，acceptance validator 为 37/37 PASS。
- 本机 Docker 限制不变：聚焦 `TraceabilityReplayTest` 重新执行时 1/1 在 `DockerClientProviderStrategy` 初始化失败，生产/测试编译已成功但未进入数据库 fixture 或 public response bytes/contentDigest 断言；必须由 fix commit 的 exact-head Linux/Docker CI 给出 GREEN Evidence。

## CI fix round 1——Issue Snapshot fixture version authority

- 首次 exact-head CI 双侧唯一失败相同：中文 Run `33922684732` / Job `101184297620` / Artifact `9955777800`，英文 Run `33922684588` / Job `101184297309` / Artifact `9955765741`。`TraceabilityReplayTest.later edge revision...` 在追加新 Issue Snapshot 时触发 `DuplicateKeyException` / `uq_issue_snapshot_release_version`；生产查询和 Replay 对比断言没有失败。
- 根因：`seed(issueCount=2)` 先由 `appendSnapshotIssues` 为同一 Release 创建 version 2；`appendLatestSnapshot` 随后仍硬编码 version 2。`appendUnsupportedLatestSnapshot` 也持有相同硬编码，虽然既有 default-seed 路径未碰撞，但对已有 version 2 的 fixture 存在同一结构缺陷。
- RED：首次双侧 exact-head CI 是真实 PostgreSQL RED。回归进一步在 Replay 中以独立 SQL 固定断言已有版本严格为 `1,2`，追加后必须为 `1,2,3`；保留硬编码 2 时会先被唯一约束击穿，不能以改测试期望绕过。
- 修复：只修改共享测试 fixture。`insertSnapshot` 不再接收调用方 version，统一在当前事务内用参数化 SQL 按 `(project_id, release_id)` 查询 `COALESCE(MAX(snapshot_version), 0) + 1`。`appendSnapshotIssues`、`appendLatestSnapshot`、`appendUnsupportedLatestSnapshot` 三条路径全部复用这一 helper，删除平行硬编码；default seed 仍为 v1→v2，多 Issue seed 变为 v1→v2→v3。
- 本机证据：生产与测试源码经 `compileTestKotlin --rerun-tasks` 全部编译成功。本机 Docker 限制仍阻止真实 PostgreSQL GREEN，因此连续版本和完整 public bytes/digest Replay 必须由本 CI-fix 精确提交的 Linux/Docker CI 证明，不把编译结果写成语义 PASS。
- 范围：没有修改生产 Schema、Migration、Repository、Application、Controller 或 DTO；没有新增第二版本权威、fallback 或测试专用生产 hook。
