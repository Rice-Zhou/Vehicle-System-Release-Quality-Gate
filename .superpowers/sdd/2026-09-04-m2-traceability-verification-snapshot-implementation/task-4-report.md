# Task 4 报告——Verification Run Creation Transaction

## 状态

已完成，本地 PostgreSQL 执行受阻。生产代码与测试源代码均可编译，可在非 PostgreSQL 环境执行的验证集已全部通过；PostgreSQL 测试套件已成功编译，但需要由 exact-head CI 或配备 Docker/PostgreSQL 的主机执行。

## RED 证据

生产实现开始前执行了以下聚焦命令：

`./backend/gradlew -p backend test --tests '*TraceabilityVerificationStartIntegrationTest' --tests '*TraceabilityVerificationStartFailureTest'`

该命令在 `compileTestKotlin` 阶段因刻意缺失的 Task 4 边界而失败，其中包括 `StartTraceabilityVerification`、`StartTraceabilityVerificationCommand` 和 `TraceabilityInputRejected`。这是计划允许的首次缺失接口 RED。测试明确规定了：已锁定 Manifest 与最新 Snapshot 的选择、Issue 范围内的精确 Revision 固定（包括 Revision 实体 ID）、不可信当前权威数据的拒绝、20/2,000 的 fail-closed 上限、后续 Revision 隔离、幂等语义、HTTP scope/可见性行为，以及全部六个写入边界的回滚。

## 实施摘要

- 新增一个带 `@Transactional` 的启动用例。它检查功能开关，在不泄露资源存在性的前提下解析 Project 可见性，强制要求 `TRACEABILITY_VERIFY`，并将现有 JDBC 幂等执行器置于同一事务内。
- 新增一个参数化、基于集合的 PostgreSQL 权威查询。它锁定 Release 行、验证已锁定的 Manifest、为所请求的 Issue Source 选择最新的不可变 v1 Issue Snapshot、仅读取与 Snapshot Issues 相关的当前类型化 Edge Revisions，并仅从 `artifact_release_edge_v` 获取 Artifact-to-Release 权威数据。
- 查询最多返回 `max + 1` 条记录；应用会拒绝 21 个 Issues 或 2,001 个 Edge Revisions，且绝不会持久化被截断的 Run。任何状态不是 `VALID` 的当前相关 Edge 都会以 `422` fail closed，而不会被视为 Gap。
- 固定输入包含数字 Revision 和权威 Revision 实体 ID：三个已存储 Edge 类型对应的类型化 Revision 表 ID，以及 `ARTIFACT_RELEASE` 对应的已锁定 Manifest Revision ID。
- 新增原子化的 Run、有序 Input Ledger、Audit、Outbox 和一个 `TRACEABILITY_VERIFY` Background Job 创建过程。Job payload 仅包含 `verificationRunId`；Run 与治理 payload 仅包含白名单允许的 ID、版本、数量、状态和摘要。
- 新增已批准的 POST controller、专用 OAuth scope、幂等/request-ID 行为、`202` 与 `Location`、功能禁用时固定返回的 `503`、输入错误 `422`，以及 RFC 9457 响应。配置默认禁用，并限制为 20 个 Issues/2,000 个 Revisions。
- 新增聚焦的集成测试和参数化的触发器回滚测试，以及新应用端口所需的最小 context-test mock。

## 验证证据

### 本地可执行 GREEN

最终生产代码编辑完成后重新执行：

`./backend/gradlew -p backend cleanTest test --tests '*M2ApiContractTest' --tests '*TraceabilityVerificationDtoTest' --tests '*TraceabilityCanonicalizerTest' --tests '*TraceabilityVerifierTest' --tests '*ArchitectureTest' --tests '*ApplicationContextTest'`

结果：`BUILD SUCCESSFUL`，耗时 47 秒；`60/60` 个测试通过，失败、错误和跳过均为零。本次运行重新编译了生产 Kotlin 源代码和测试 Kotlin 源代码。

契约校验器：

`npm run test:contracts`

结果：`PASS contracts schemas=4 positive=12 negative=5 operations=34`。

`git diff --check` 未发现问题。

### PostgreSQL 执行阻塞

重新执行：

`./backend/gradlew -p backend test --tests '*TraceabilityVerificationStartIntegrationTest' --tests '*TraceabilityVerificationStartFailureTest' --tests '*SecurityAcceptanceTest'`

结果：`BUILD FAILED`；所选择的全部 `30` 个带 PostgreSQL 标签的测试均在 `PostgresIntegrationTest` 初始化期间停止。首个原因是 `DockerClientProviderStrategy` 抛出的 `IllegalStateException`，随后同一初始化器出现 `NoClassDefFoundError`。该主机未提供 Docker、Podman、`psql` 或 `pg_isready`，因此没有执行 Spring context、fixture、SQL、事务或语义断言。这是环境阻塞，不代表数据库测试通过；验收前必须由 exact-head CI 执行已编译的测试套件。

## 自检

- 权威数据：仅使用一个 PostgreSQL 数据源；没有 Jira、GitHub、CI、Device、JSON/file/cache fallback、第二张 Artifact-to-Release 表或动态外部查询。
- 事务：幂等记录、Run、每条 Ledger 记录、Audit、Outbox 和 Job 均处于同一个 Spring 事务中；失败不会被捕获并转换成 `202`。
- 查询形态：一个基于集合的权威查询和一个基于集合的 Ledger 插入；不存在逐 Issue/逐 Edge 的 N+1。所有源自请求的 SQL 值均使用绑定参数。
- Payload：Job body 仅包含 Run ID。Task 4 治理元数据中不会持久化 Issue 标题、源引用、证明 URL、凭据、仓库、原始 provider payload、异常、SQL 或 stack trace。
- 错误行为：资源不可见行为可防止枚举；禁用时固定返回 `503`；Manifest 未锁定时返回 `409`；不可信或超出上限的固定权威数据返回 `422`；不存在静默 fallback、截断或宽泛异常捕获。
- 范围：未引入 Task 5 worker/materialization、Task 6 query/replay、broker、cache、服务拆分、UI、migration、deployment 或 Ledger 修改。

## 文件与范围

创建了七个 Task 4 实现/测试文件。按照 brief 的允许范围，仅修改了 `backend/src/main/resources/application.yml` 和 `backend/src/test/kotlin/com/ricezhou/vsrqg/ApplicationContextTest.kt`。本报告是唯一新增的 SDD 工件。未编辑任何治理 Ledger。

## 规范冲突评估

未发现与已批准的 M2.5 设计、V0.1 冻结架构、Task 1 DTO/API contract、Task 2 V11 权威数据或 Task 3 canonicalizer 存在冲突。本任务无需修订 ADR/TDR。

## Commit

Subject：`feat(m2): queue pinned traceability verification`

实现代理将在创建 Commit 后报告其不可变 Commit ID；Commit 无法包含自身的 hash。

## 剩余风险 / 交接

- 在本 Task 可用作验收证据之前，exact-head CI 必须执行上述三个 PostgreSQL 测试套件并保留测试报告。
- Task 5 必须仅使用已持久化的 Run 和 Input Ledger。不得重新读取最新 Revision 权威数据，也不得将权威查询复制到 Worker 中。
- Task 6 必须重放已存储的 Run/Snapshot 结果，不得根据当前源表重新计算。

## Review 修复第 1 轮

### Finding 结论

1. **Important 1 — 已处理。** PostgreSQL 权威查询现在会为 Release/Issue Source 选择绝对最新的不可变 Issue Snapshot，而不会预先按 canonicalization version 过滤。所选版本会作为权威元数据返回；除 `release-issue-snapshot-jcs/v1` 以外的任何版本，应用均以固定的 `422 TRACEABILITY_INPUT_NOT_VALID` 拒绝。该过程无法 fallback 到较旧的受支持 Snapshot。一个 PostgreSQL 回归测试会创建较旧的 v1 Snapshot 和较新的不受支持 Snapshot；另一个本地应用层回归测试独立证明，拒绝发生在计算 canonical digest 或持久化之前。
2. **Important 2 — 已处理。** Verification POST 遇到资源、瞬态、超时和事务创建类数据库故障时，会通过现有共享 `ProblemHandler` 权威路径处理，并返回脱敏的 `503 PERSISTENCE_UNAVAILABLE`。未新增 controller 本地数据库 handler 或平行错误分类。端点专用 Advice 现在仅对其固定的 Traceability 业务错误具有明确优先级；使用真实默认禁用配置的 HTTP 回归测试证明，`TRACEABILITY_VERIFICATION_UNAVAILABLE` 仍固定返回 503。
3. **Important 3 — 已处理。** 参数化写入失败矩阵现已增加第七个边界 `IDEMPOTENCY_RESPONSE`，该边界在 `idempotency_record` 最终成功 UPDATE 上安装触发器。由于该 UPDATE 仅在 Run、Ledger、Audit、Outbox 和 Job 写入之后发生，测试证明其失败会回滚包括 pending 幂等记录在内的所有工件。
4. **Minor findings — 已处理。** Outbox 回滚计数根据白名单 event payload 中的 fixture Release ID 限定范围，不再使用全局 `trv_%` 命名空间。Controller 的 `releaseId` 校验现为 1..128，与 OpenAPI `OpaqueId` 以及现有 Release/Manifest controllers 保持一致；HTTP 边界测试接受 128 并拒绝 129。功能禁用测试使用真实应用策略，而不是 mocked exception。

### TDD 证据

- Persistence HTTP RED：预期 `503`，却从共享 catch-all 收到 `500 INTERNAL_ERROR`。
- Release ID RED：OpenAPI 允许的 128 字符 ID 预期返回 `202`，实际收到 `400 INVALID_REQUEST`。
- Disabled feature RED：使用真实默认禁用配置的应用 POST 预期返回 `503`，但由于全局 Advice catch-all 的优先级高于 controller 专用 handler，实际收到 `500 INTERNAL_ERROR`。
- Snapshot authority interface RED：`compileTestKotlin` 因刻意缺失的 `issueSnapshotCanonicalizationVersion` 权威字段而失败。
- 仅添加该字段后的 Snapshot authority behavioral RED：用例执行到 canonicalizer，并因 null result 触发 `NullPointerException`，而不是预期的 `TraceabilityInputRejected`，证明当时不存在版本校验。
- PostgreSQL 最新 Snapshot 回归测试和第七个回滚边界回归测试均可编译，但由于该主机没有 Docker/PostgreSQL 运行环境，无法在本地生成行为层 RED/GREEN。

### 验证

重新执行非 PostgreSQL 命令：

`./backend/gradlew -p backend cleanTest test --tests '*TraceabilityVerificationStartHttpTest' --tests '*TraceabilityVerificationAuthorityValidationTest' --tests '*ApplicationContextTest' --tests '*ArchitectureTest' --tests '*M2ApiContractTest' --tests '*TraceabilityVerificationDtoTest' --tests '*TraceabilityCanonicalizerTest' --tests '*TraceabilityVerifierTest' --tests '*BuildProvenanceTransactionStructureTest'`

结果：`BUILD SUCCESSFUL`；`67/67` 个测试通过，失败/错误/跳过均为零。其中包括现有 Build Provenance persistence taxonomy 回归测试，因此扩展共享 path classifier 并未改变其固定的 503 行为。

契约校验器仍为 `PASS contracts schemas=4 positive=12 negative=5 operations=34`。`git diff --check` 未发现问题。

PostgreSQL 命令：

`./backend/gradlew -p backend test --tests '*TraceabilityVerificationStartIntegrationTest' --tests '*TraceabilityVerificationStartFailureTest'`

结果：所选择的全部 `18` 个测试均在同一个 Testcontainers `DockerClientProviderStrategy` 初始化故障处停止。所选测试集包含“不受支持的绝对最新 Snapshot”回归测试和全部七个写入边界。由于没有执行 fixture、SQL、事务或断言，exact-head CI 仍是强制要求。

### 范围与架构

- 共享改动仅限于在现有 persistence-unavailable 权威处理逻辑中识别已批准的 Verification POST。Traceability 业务错误仍保留在 Traceability Adapter 内；Shared 不依赖 Traceability 模块，Architecture tests 继续保持 GREEN。
- 未新增外部源、fallback、新错误源、migration、Worker、query/replay 实现、Ledger 编辑、push、merge、tag、release 或 deployment。

### 修复 Commit

Subject：`fix(m2): close traceability start review findings`

不可变 Commit ID 将在创建后报告，因为 Commit 无法包含自身的 hash。

## Exact-head CI 连接池修复第 2 轮

### 根因证据与单一假设

- Exact-head CI 的 JUnit XML 显示，两个新增 PostgreSQL 测试类均在 Flyway context 初始化期间收到 `SQLSTATE 53300 FATAL: too many clients already`；后续失败均为 Spring context failure threshold 的连锁结果，而不是 migration、事务或业务断言失败。
- 本地依赖中的 HikariCP 6.3.3 字节码确认，未配置时 `maximumPoolSize` 为 10，`minimumIdle` 初始为 -1，并在校验时归一为 `maximumPoolSize`。因此每个默认测试连接池最多保留 10 个连接。
- `TraceabilityVerificationStartIntegrationTest` 使用 `@AutoConfigureMockMvc`，而 `TraceabilityVerificationStartFailureTest` 不使用；两者因此形成不同的缓存 Spring Boot context。它们此前均未声明局部 Hikari budget，在已有 PostgreSQL 测试 context 之后新增的连接池使 exact-head 运行跨过数据库 client 上限。
- 两个测试类内部均没有线程或并发执行器，Gradle/JUnit 配置也未启用并行测试。测试的 fixture、MockMvc/use-case 调用、失败注入、断言和清理均按顺序执行。因此单个 context 的实际测试路径只要求一个活动连接；`maximumPoolSize=2` 保留一个有限的并发余量，`minimumIdle=0` 则不预留空闲连接。

### 修复

- 仅在上述两个测试类各自的 `@TestPropertySource` 中设置 `spring.datasource.hikari.maximum-pool-size=2` 和 `spring.datasource.hikari.minimum-idle=0`。
- 未修改生产 `application.yml`、共享 `PostgresIntegrationTest` 或其他测试 context，也未降低 PostgreSQL 测试数量或跳过任何断言。
- 新增一个无需数据库的回归测试：它读取两个真实测试类的合并 `@TestPropertySource`，通过 Spring Binder 绑定至 `HikariConfig`，并分别断言 max=2、minIdle=0。删除任一 context 的局部配置都会使该测试失败。

### TDD 证据

- RED 命令：`./backend/gradlew -p backend test --tests '*TraceabilityVerificationStartPoolBudgetTest'`。
- RED 结果：测试失败，`TraceabilityVerificationStartIntegrationTest maximum pool size` 期望 2、实际为默认 10；这直接证明新增 context 没有局部 pool budget。
- 加入四条局部测试属性后重新执行同一命令，结果为 `BUILD SUCCESSFUL`，`1/1` 个测试通过。

### 验证

非 PostgreSQL 验证命令：

`./backend/gradlew -p backend cleanTest test --tests '*TraceabilityVerificationStartPoolBudgetTest' --tests '*TraceabilityVerificationStartHttpTest' --tests '*TraceabilityVerificationAuthorityValidationTest' --tests '*ApplicationContextTest' --tests '*ArchitectureTest' --tests '*M2ApiContractTest' --tests '*TraceabilityVerificationDtoTest' --tests '*TraceabilityCanonicalizerTest' --tests '*TraceabilityVerifierTest' --tests '*BuildProvenanceTransactionStructureTest'`

结果：`BUILD SUCCESSFUL`，耗时 51 秒；`68/68` 个测试通过，失败、错误和跳过均为零。契约校验器仍为 `PASS contracts schemas=4 positive=12 negative=5 operations=34`。

PostgreSQL 编译/执行命令：

`./backend/gradlew -p backend test --tests '*TraceabilityVerificationStartIntegrationTest' --tests '*TraceabilityVerificationStartFailureTest'`

结果：生产代码与测试代码均已编译，但所选择的全部 `18` 个 PostgreSQL 测试仍在本地主机的 Testcontainers `DockerClientProviderStrategy` 初始化处停止。没有执行 fixture、Flyway、SQL、事务或断言；因此本地结果不能证明 exact-head 的 `SQLSTATE 53300` 已消除，修复后仍必须由 exact-head CI 执行这两个类。

### 范围

- 改动仅涉及两个新增 PostgreSQL 测试 context、一个非数据库配置回归测试和本报告。
- 未修改生产连接池、生产代码、schema/migration、业务行为、测试断言、治理 Ledger、CI workflow，也未执行 push、merge、tag、release 或 deployment。

### 修复 Commit

Subject：`test(m2): bound verification test pool budgets`

不可变 Commit ID 将在创建后报告，因为 Commit 无法包含自身的 hash。

## Exact-head CI 连接资源结构修复（第 2 次修复尝试）

### 被证伪的局部假设

- 修复 Commit `b0b552f6d8a931a744ad07e0b1d43b17252da8a3` 之后，exact-head CI artifact `9948147626` 中的两个新增测试类仍在 Flyway context 初始化期间收到相同的 `SQLSTATE 53300 FATAL: too many clients already`。这直接证伪了“仅约束两个新增 context 即可恢复可用连接”的局部假设。
- 同一连接资源问题此前只有一次实现修复，本节是第 2 次修复尝试，尚未达到 systematic debugging 的三次失败停止阈值。由于局部方案已经失败，本轮仍先执行了结构性架构审视，而不是继续缩小局部 pool 数值。

### 完整 context 资源证据

- 全库共有 23 个 `PostgresIntegrationTest` 派生类。一个临时、非数据库的 Spring TestContext bootstrap 探针使用真实 `BootstrapUtils` 计算出 12 个唯一 `MergedContextConfiguration`；探针仅用于调查，运行后已删除，未进入提交。
- Spring TestContext cache 的本地依赖默认上限为 32；全库没有 `@DirtiesContext`，Gradle/JUnit 也没有启用并行执行。因此这 12 个 context 均可在同一个 full-suite JVM 内保持缓存，其 DataSource 直到 cache eviction 或 JVM 关闭才会关闭。
- 前 10 个既有唯一 context 没有 Hikari test-only 配置。HikariCP 6.3.3 默认 `maximumPoolSize=10`，且未指定的 `minimumIdle=-1` 会在校验时归一为 maximum，也就是每个 context 预留 10 个连接。仅前 10 个 context 即可累计 100 个连接；因此后续两个已经局部限制为 max=2/minIdle=0 的新增 context 仍可能连 Flyway 所需的首个连接都无法获得。

### 并发需求与统一预算

- 全局搜索 `runConcurrently`、`Executors`、`CountDownLatch`、`CyclicBarrier` 和并行测试配置后，数据库测试的 worker fan-out 最大为 2。
- `BuildProvenanceMigrationTest`、`M2MigrationConstraintTest` 和 `TraceabilityVerificationMigrationTest` 都会同时持有两个测试连接，并由主测试线程通过 `JdbcClient` 查询 PostgreSQL lock 状态，因此可证明的峰值需求为 3 个活动连接。max=2 会破坏这些真实并发测试，不能作为共享预算。
- 共享 `maximumPoolSize=3` 精确覆盖已证明的峰值；`minimumIdle=0` 禁止每个缓存 context 预留空闲连接。对当前 12 个唯一 context，即使每个都同时达到 maximum，总上限也从默认的 120 降为 36。

### 方案比较与架构审视

1. **共享基类预算——采用。** `PostgresIntegrationTest` 是所有共享 PostgreSQL context 的唯一公共边界，在这里定义 max=3/minIdle=0 可形成单一、可继承、可验证的测试资源不变量，同时保留 Spring context cache。
2. **`@DirtiesContext`——不采用。** 它可以在每个类后关闭 context，但会破坏既有缓存，使 23 个派生类反复创建 Spring context 并执行 Flyway；这增加运行时间和新的生命周期噪声，却没有表达连接预算。
3. **提高容器 `max_connections`——不采用。** 它只扩大泄漏空间，无法阻止未来新增 context 继续按默认值预留连接，并会掩盖测试 harness 的资源所有权缺失。

该修复仅治理测试 harness 资源，不改变 V0.1/V0.2 生产架构、事务、schema、业务行为或容器生产参数，不需要 ADR/TDR。

### TDD 与 mutation 证据

- RED 命令：`./backend/gradlew -p backend test --tests '*PostgresIntegrationPoolBudgetTest'`。
- RED 结果：`1/1` 失败，`shared PostgreSQL test pool authority` 实际为 null，证明共享基类尚未声明 pool budget。
- 在共享基类加入 max=3/minIdle=0 后，同一测试 GREEN。
- 随后增加“每个派生 context 的合并配置均不得 override 共享预算”测试。临时将 `TraceabilityVerificationStartIntegrationTest` 恢复为局部 max=2 后，mutation run 精确失败：effective maximum pool size 期望 `"3"`、实际为 `"2"`。撤销 mutation 后，`2/2` 测试恢复 GREEN。

### 实施

- 在 `PostgresIntegrationTest` 的 `@TestPropertySource` 中集中定义 `spring.datasource.hikari.maximum-pool-size=3` 和 `spring.datasource.hikari.minimum-idle=0`。
- 删除两个 Traceability Verification 测试类中的局部 max=2/minIdle=0，以及只验证这两个类的旧回归测试。
- 新增 `PostgresIntegrationPoolBudgetTest`：第一项通过 Spring Binder 验证共享权威值；第二项使用 ArchUnit 自动发现所有派生测试类，并通过 Spring `MergedContextConfiguration` 验证每个 context 最终生效的 max=3/minIdle=0，能够阻止未来局部 override 漂移。

### 验证

非 PostgreSQL 验证命令：

`./backend/gradlew -p backend cleanTest test --tests '*PostgresIntegrationPoolBudgetTest' --tests '*TraceabilityVerificationStartHttpTest' --tests '*TraceabilityVerificationAuthorityValidationTest' --tests '*ApplicationContextTest' --tests '*ArchitectureTest' --tests '*M2ApiContractTest' --tests '*TraceabilityVerificationDtoTest' --tests '*TraceabilityCanonicalizerTest' --tests '*TraceabilityVerifierTest' --tests '*BuildProvenanceTransactionStructureTest'`

结果：`BUILD SUCCESSFUL`，耗时 52 秒；`69/69` 个测试通过，失败、错误和跳过均为零。契约校验器仍为 `PASS contracts schemas=4 positive=12 negative=5 operations=34`。

PostgreSQL 编译/执行命令：

`./backend/gradlew -p backend test --tests '*TraceabilityVerificationStartIntegrationTest' --tests '*TraceabilityVerificationStartFailureTest'`

结果：生产代码与测试代码均已编译，但所选择的全部 `18` 个测试仍在本地主机的 Testcontainers `DockerClientProviderStrategy` 初始化处停止。没有执行 fixture、Flyway、SQL、事务或断言；因此只有下一次 exact-head full-suite CI 可以验证 `SQLSTATE 53300` 是否已消除。

### 范围

- 改动仅涉及共享 PostgreSQL 测试基类、一个共享 pool budget 回归测试、删除两个局部配置/旧局部回归测试，以及本报告。
- 未修改生产 DataSource、生产代码、schema/migration、容器 `max_connections`、CI workflow 或治理 Ledger；未删除/跳过既有 PostgreSQL/业务测试，也未放宽业务断言；未执行 push、merge、tag、release 或 deployment。

### 修复 Commit

Subject：`test(m2): enforce shared postgres pool budget`

不可变 Commit ID 将在创建后报告，因为 Commit 无法包含自身的 hash。
