# SDD 台账 — 计划：docs/superpowers/plans/2026-09-04-m2-traceability-verification-snapshot-implementation.md

开始日期：2026-09-04
合并基线 / 实施授权头提交：6a8b5cfe20b32f9ee7d723ad0b6459584c343ed6

## 目标与成功标准

- 目标：在不改变 V0.1 语义的前提下，实现已批准的 M2.5 固定输入异步可追溯性验证与不可变快照。
- 成功标准：任务 1-7 全部完成，并具备 RED/GREEN 证据、任务评审批准、最终全分支评审、完整门禁、双语同步、精确头提交 CI，以及一份状态为 PENDING 的 Owner Gate。
- 已在本地验证的假设：采用 Kotlin/Spring Boot 模块化单体；PostgreSQL 当前版本为 V10；V4 已持有验证、快照、缺口和作业的基础权威；V6 持有问题快照；V9/V10 持有类型化边权威；当前分支位于隔离的链接工作树中。

## 执行前任务 / 接口扫描

| 任务 | 共享文件或接口 | 生产方 / 消费方核对 | 结论 |
| --- | --- | --- | --- |
| 1 / 4 | 验证 DTO 与 POST 契约 | 任务 1 固化严格 DTO；任务 4 将 POST 控制器绑定到这些 DTO | 一致 |
| 1 / 6 | 读取 DTO、契约与安全测试 | 任务 1 固化响应 / 安全契约；任务 6 实现 GET 接口 | 一致 |
| 2 / 4 | Run / 输入台账模式 | 任务 2 建立 V11 权威；任务 4 以事务方式写入 | 一致 |
| 2 / 5 | 快照 / 结果 / 路径 / 缺口模式 | 任务 2 建立约束；任务 5 以原子方式物化 | 一致 |
| 2 / 6 | 持久化读取模型 | 任务 2 固化不可变表；任务 6 只读取已持久化的序号 | 一致 |
| 3 / 4 | 输入规范化器 | 任务 3 定义确定性输入摘要；任务 4 在固定输入后计算该摘要 | 一致 |
| 3 / 5 | 验证器与结果规范化器 | 任务 3 生成纯计算结果；任务 5 不作改动地持久化该结果 | 一致 |
| 4 / 5 | 仓储、作业类型与 Run 生命周期 | 任务 4 将 TRACEABILITY_VERIFY 入队；任务 5 领取作业并使其进入终态 | 一致 |
| 4 / 6 | 控制器与仓储 | 任务 4 建立 POST / 控制器基础；任务 6 增加读取操作 | 一致 |
| 5 / 6 | 不可变快照图 | 任务 5 写入完整图；任务 6 执行基于集合的读取 | 一致 |
| 1-6 / 7 | 测试、契约、迁移与运维 | 任务 7 使用精确行为并产出候选证据 | 一致 |
| 1 | 内部一致性 | 测试要求一个增量操作和严格 DTO；所列文件同时提供二者 | 一致 |
| 2 | 内部一致性 | 测试覆盖 V11 全新安装、升级及约束；迁移文件提供相应权威 | 一致 |
| 3 | 内部一致性 | RED 用例与 Fixed/Included/Verified 以及五个已批准的 Gap 代码相符 | 一致 |
| 4 | 内部一致性 | 启动测试与单一创建事务及禁用时失败关闭行为相符 | 一致 |
| 5 | 内部一致性 | Worker 测试与 SKIP LOCKED、原子结果、复用和数据库锁定相符 | 一致 |
| 6 | 内部一致性 | 查询测试与仅持久化读取、可见性、排序及重放相符 | 一致 |
| 7 | 内部一致性 | 门禁测试、性能 / 恢复、运维和 PENDING 记录均可独立评审 | 一致 |

执行前发现的冲突：无。

## 基线证据与裁决

- 在任务 1 前尝试执行完整后端基线：失败，因为 Testcontainers 找不到 Docker 运行时（`DockerClientProviderStrategy`），导致 PostgreSQL 集成测试在执行仓储代码前失败。
- 环境诊断：不存在 `docker` 或 `podman` 可执行文件，也没有 Docker Desktop 安装、Docker 服务或 WSL 发行版；仅存在 `DOCKER_CONFIG=D:\Docker\.docker`。
- 非容器基线：通过——聚焦的 Kotlin 架构 / 契约 / 领域测试成功完成；`npm run test:contracts` 通过，schemas=4、positive=12、negative=5、operations=33。
- 裁决：继续使用本地编译、单元测试和契约测试完成 RED-GREEN，并以精确头提交 GitHub Actions 作为集成任务的 PostgreSQL/Testcontainers 权威——当前主机无法提供已设计的容器边界，安装需要特权的运行时会扩大范围；若判断有误，代价是集成反馈要到推送后才会出现，因此每项任务都必须在 CI 失败时停止，并进入常规的修复 / 重新评审循环。

## 任务进度

- 任务 1：已完成（提交 `6a8b5cf..2723880`，第 1 轮修复后评审无问题；精确头提交 CI 作业 ZH `100970395986` 与 EN `100970322818` 成功）
- 任务 2：已完成（ZH 头提交 `c96a57e4fde2768199cdf34b5fce4eab56f08605`，EN 头提交 `5df826d72941508417ef4d4d58283b2f13da1161`；Pair Gate 通过；ZH Run `33870650065`、Job `101015552031` 成功；EN Run `33870650234` 第 2 次尝试的 Job `101017683264` 成功）
- 任务 3：已完成（ZH 头提交 `4e285d35406f45eba5ce770eb3c2617c3e7f2c37`，EN 头提交 `dcede67c17f8f0c83d8dadf0e4fa2fb6bee87322`；最终评审 `APPROVE_TASK`；Pair Gate 与后端字节一致性检查通过；精确头提交 CI 作业 ZH `101058811743` 与 EN `101058811131` 成功）
- 任务 4：已完成（ZH 头提交 `9e56d0b1d845fd0814fe684d0c57c25d5cdb23a6`，EN 头提交 `1df788cdbd0cb165e9fe7771920816e16ee5e9c8`；最终任务评审与两次 CI 修复评审均已批准；Pair Gate 通过；精确头提交 CI 成功）
- 任务 5：已完成（ZH 头提交 `54a37478128850ce9bfc53832169caa38bb71644`；EN 头提交 `a713d49e8d552cee6be775903d7f9166e6f81571`；最终评审 `APPROVE_TASK`；CI 修复评审 `APPROVE_CI_FIX`；Pair Gate 与修复后精确头提交 CI 均通过）
- 任务 6：已完成（ZH 头提交 `6e8a3f51d428ba72f6d191d5558aeb3effc4fa1d`；EN 头提交 `c36e93f78e77560123853e926b9ac92e001b9382`；最终评审 `APPROVE_TASK`；CI 修复评审 `APPROVE_CI_FIX`；Pair Gate 与修复后精确头提交 CI 均通过）
- 任务 7：已完成（当前 ZH Subject `2652c7f442b84a6ed04865e0104bf01a6c45e69d`，EN Subject `a1a86715d244965061e5d333ca04e26f92a5dc79`；任务评审 `APPROVE_TASK`、翻译评审 `APPROVE_TRANSLATION`，均为 0 Critical / 0 Important / 0 Minor；最终双语 exact-head M1/M2 CI、12/12 Gate、20/2,000 性能、恢复与 Pair Gate 全部通过；Task 实现完成，但 `M2-5-OWNER-GATE-001` 的 Owner Authorization 仍为 `UNKNOWN`，状态严格保持 `PENDING`）

## 评审历史

- 任务 1 初始实现：提交 `6a8b5cf..3888761`；评审者发现一个重要的确定性 DTO 排序缺口，以及一个仅能在 CI 验证的安全项。
- 任务 1：第 1/5 轮修复（已处理 1 项，未解决 0 项——确定性 Issue / 路径 / 缺口 DTO 排序；提交 `3888761..2723880`）。
- 任务 1 范围化复审：原重要发现已处理；没有新的严重 / 重要破坏；SecurityAcceptanceTest 精确头提交 CI 验证待执行。
- 任务 2 RED：仅测试提交 ZH `f021c46` / EN `3eb69ee`；EN Run `33858632911`、Job `100977661235` 因缺少 V11 列 / 表以及 V10 到当前版本的迁移而失败，确立了语义 RED。一个无关的重复边夹具失败被拒绝作为 RED，并在实现前修正。
- 任务 2 实现：V11 权威及五轮评审 / 修复（`5a0cb17`、`998186c`、`36a5917`、`55a1a34`、`9edb510`、`ccf2f49`）。评审关闭了输入 / 结果事务封存、精确最新修订输入、路径 / Gap 语义、旧版兼容、相同输入复用、多路径可达性，以及精确 Run / Snapshot Gap 集合权威等问题。最终任务评审结论：`APPROVE_TASK`，无严重 / 重要问题。
- 任务 2 首次评审后 CI：ZH/EN 头提交 `ccf2f49` / `343c951`；EN Run `33869620112`、Job `101012248841` 暴露了两处过期的 M2.4 当前迁移数量断言。集成修复 ZH `c96a57e` / EN `5df826d` 经独立评审为 `APPROVE_CI_FIX`。
- 任务 2 最终 CI：ZH 精确头提交 Run `33870650065`、Job `101015552031` 成功。EN 精确头提交 Run `33870650234` 第 1 次尝试的 Job `101015552554` 被 Runner 基础设施取消，且没有测试注解；未更改代码即在相同 SHA 上重新运行，第 2 次尝试的 Job `101017683264` 成功。两次推送前 Pair Gate 与后端字节一致性检查均通过。
- 任务 3 实现及四轮评审 / 修复：ZH 提交 `05fd08c`、`0448576`、`9f9367e`、跨任务置信度权威修正 `af76713`、`80d60f5` 及 `4e285d3`；最终评审结论为 `APPROVE_TASK`，无严重 / 重要 / 次要发现。新执行的聚焦测试 37/37 通过，架构检查与编译通过，契约测试通过（`schemas=4 positive=12 negative=5 operations=34`），20 个 Issue / 2,000 条边的参考计算耗时约 42 ms。
- 任务 3 双语同步：EN 对应提交 `a8dc0ff`、`2edf39f`、`b338ee4`、`c4f88ed`、`914316e` 及 `dcede67`；Pair Gate 与后端字节一致性检查通过。ZH Job `101058811743` 在 `4e285d35406f45eba5ce770eb3c2617c3e7f2c37` 上、EN Job `101058811131` 在 `dcede67c17f8f0c83d8dadf0e4fa2fb6bee87322` 上的精确头提交 CI 均成功。

## 任务 4 前的跨任务权威审计

- 发现：增量公开 `TraceabilityPathEdge` 契约同时要求不透明的 `revisionId` 和数字型 `revision`，而 V11 固定输入 / 快照权威及任务 3 的 `PinnedTraceabilityEdge` 当前仅保留逻辑 `source_edge_id` 和数字型 `source_edge_revision`。
- 风险：否则任务 5/6 将不得不在物化 / 查询时虚构 `revisionId`，或丢失现有类型化边修订实体的标识，这会违反确定性重放以及已发布的 API 形态。
- 裁决：暂停分派任务 4，直至一个聚焦的 RED/GREEN 修正确保权威修订实体 ID 端到端保留。对 M2.4 类型化边而言，它是不可变的 `*_edge_revision.id`；对派生自 Manifest 的 `ARTIFACT_RELEASE` 而言，Locked Manifest Revision ID 是权威修订 ID。这是对实现标识完整性的补齐，不是对 Release / Manifest / Evidence / Traceability 语义的更改。
- 修正实现：ZH `b114644e7f51c2deb29b3da9c36eb49c0fe13163` 加评审修复 `1f88b000e5fe5d1b7c218c230124652eeefc014f`；EN 对应提交 `1e554b1` 和 `ee22723`。最终独立评审结论为 `APPROVE_CORRECTION`，不存在剩余的严重 / 重要 / 次要发现。
- 评审修复证据：V10→V11 夹具现在包含一个具有全部四种边类型的非空历史 Snapshot，并证明了精确修订 ID 回填、历史内容标识不变、不可变触发器恢复及变更被拒绝。本地非 PostgreSQL 聚焦测试 59/59 通过；由于本主机没有 Docker 提供程序，PostgreSQL 执行仍由精确头提交 CI 负责。
- 双语修正 Pair Gate：ZH `1f88b000e5fe5d1b7c218c230124652eeefc014f` 与 EN `ee2272347ae4e51730ae5f1295de63dbf2cbc6a7` 检查通过；所有非 Markdown 文件逐字节一致，英文 Markdown 满足语言边界。
- 首次精确头提交修正 CI：ZH Job `101075988911` 与 EN Job `101075989535` 均在九个 PostgreSQL 迁移测试中出现语义失败，因为共享测试夹具写入了空的 `ARTIFACT_RELEASE` 修订 ID。系统化数据流追踪证明 V11 触发器正确要求 Locked Manifest Revision ID；没有弱化任何产品权威。
- CI 修复：ZH `dd564c59fa42cb87010b5e6cb9674acbea43967e`，EN `eae524b721abf1eee9578715d4bfe98e696c454a`；最终评审结论为 `APPROVE_CI_FIX`，无严重 / 重要 / 次要发现。夹具现在读取 `artifact_release_edge_v.manifest_revision_id`，并在插入台账前断言其等于父级权威。对这些精确头提交的 Pair Gate 检查通过。
- 第二次精确头提交修正 CI：ZH Job `101081133185` 与 EN Job `101081135794` 均因两个夹具层假设失败：V10 自定义模式连接的事务局部搜索路径中缺少目标模式，无法满足历史 V1 所有权触发器；一个伪造的提交后子项预期返回固定输入 SQLSTATE，但更早执行的原子创建触发器正确返回了 `55000`。
- 触发器层 CI 修复：ZH `f16a92879900f1a6a282d6a429c2486127369ac2`，EN `13d5976dcb4c1f91fe42185c5d0dfc8f6062280c`；评审结论为 `APPROVE_CI_FIX`，无严重 / 重要 / 次要发现。测试现在先证明自定义 Manifest 行属于同一 Release 的 `LOCKED` 权威，再应用事务局部测试搜索路径；同时分别证明提交后追加返回 `55000`，而同事务伪造的固定输入返回 `23514`。未更改任何产品 SQL 或触发器。对这些精确头提交的 Pair Gate 检查通过。
- 最终修正 CI：ZH 精确头提交 Job `101086244538` 在 `f16a92879900f1a6a282d6a429c2486127369ac2` 上成功；EN 精确头提交 Job `101086241113` 在 `13d5976dcb4c1f91fe42185c5d0dfc8f6062280c` 上成功。任务 4 前的修订标识权威审计已关闭。
- 任务 4 实现：ZH `c54cba982ac7c68edeff030268255f540048375d`、评审修复 `c58532ae0ae9ee101ee8369a9c454f11ddc3a023`，以及中文报告治理修复 `8fde50448cdbc83a1dff0d7ea9289594959e5af4`；EN 代码对应提交 `1eb6bdc` 与 `d4eadd5` 保留英文报告。最终独立评审结论为 `APPROVE_TASK`，无严重 / 重要 / 次要发现。
- 已关闭的任务 4 评审修复：先选择绝对最新 Issue Snapshot，再进行失败关闭的版本验证；脱敏的验证持久化 `503`；最终幂等响应 UPDATE 回滚边界；限定范围的 Outbox 断言；128/129 Release ID 契约边界；直接禁用 `503` 回归。本地非 PostgreSQL 测试 67/67 通过，契约测试以 34 个操作通过。
- 任务 4 双语 Pair Gate 最初因 ZH SDD 报告为英文而失败；仅报告的修正确保每个行内代码与数字 token 不变，最终 Pair Gate 对 ZH `8fde50448cdbc83a1dff0d7ea9289594959e5af4` 和 EN `d4eadd534b6398acc0ab0ead3eb7dac539cac9a7` 检查通过。
- 首次任务 4 精确头提交 CI：ZH Job `101109931634` 与 EN Job `101109930957` 在 Flyway 上下文初始化期间失败，错误为 `SQLSTATE 53300 FATAL: too many clients already`；制品中的 JUnit XML 证明后续所有失败都是上下文阈值引发的级联，且没有执行任何任务 4 SQL / 业务断言。
- 任务 4 CI 连接池预算修复：ZH `b0b552f6d8a931a744ad07e0b1d43b17252da8a3`，EN `d288ea0b301a209d25e41183f7d3350357d09395`；独立评审结论为 `APPROVE_CI_FIX`，无严重 / 重要 / 次要发现。只有两个新测试上下文使用最大连接池 2 / 最小空闲 0，并配有一个不依赖数据库的 Spring Binder 回归；生产环境和共享测试基础设施保持不变。对这些精确头提交的 Pair Gate 检查通过。
- 第二次任务 4 精确头提交 CI：ZH Job `101118092212` 与 EN Job `101118089797` 在 Flyway 上下文初始化期间因相同的 `SQLSTATE 53300` 失败。制品 `9948147626` 再次表明没有执行任何任务 4 SQL / 业务断言，证伪了仅限制两个本地上下文连接池预算的假设。
- 结构化连接资源修复：ZH `0601b5bfc32e348ae29733b38a24934dcec0097e` 加独立评审修复 `afa4456303be0bdff1d203b504ad6ef98af5c2ed`；EN 对应提交 `92183fa` 与 `21b8cfb8d647b76935986452f9b5a07739947da2`。共享 `PostgresIntegrationTest` 现在对全部 12 个缓存上下文统一持有最大连接池 3 / 最小空闲 0 的权威，将实际总最大连接数从 104 降至 36、空闲连接预留上限从 100 降至 0，同时保留经证明所需的三连接并发能力。
- 结构化回归使用真实的 Spring 合并上下文元数据和 ArchUnit 自动发现全部 23 个子类。独立评审增加了严格的 `DynamicPropertiesContextCustomizer` 相等性检查，确保优先级更高的 `@DynamicPropertySource` 无法绕过共享权威。变异证据证明本地属性覆盖和动态属性覆盖都会失败；本地非 PostgreSQL 验证 69/69 通过，且两次 CI 修复评审均返回 `APPROVE_CI_FIX`、无任何发现。在更新本台账前，ZH `afa4456303be0bdff1d203b504ad6ef98af5c2ed` 与 EN `21b8cfb8d647b76935986452f9b5a07739947da2` 的 Pair Gate 检查通过。
- 任务 4 最终精确头提交 CI：ZH Run `33907619072` / Job `101136196594` 在 `9e56d0b1d845fd0814fe684d0c57c25d5cdb23a6` 成功；EN Run `33907618906` / Job `101136196368` 在 `1df788cdbd0cb165e9fe7771920816e16ee5e9c8` 成功。完整门禁实际执行后，原 `SQLSTATE 53300` 未再出现，任务 4 至任务 5 的持久化交接边界现已关闭。
- 任务 4 最终治理记录提交 CI：ZH Run `33908515164` / Job `101139125201` 在 `5ce16c5ff3f57e23f230ffd251dc5f9438e47a6d` 成功；EN Run `33908516530` / Job `101139130546` 在 `741caa2e03765aad76074e720d9864a938b13dc7` 成功。
- 任务 5 实现：ZH `fc36e90df14a8151bf3b381152b67418cee6beef` 加第 1 轮评审修复 `f2ec0cc92d131e463734194d9976bfb6ed230ee2`；EN 对应提交 `4dea8a380b89a808e7fd2ab94706ea867b7a1ade` 与 `9e712971fd843422dbaa166ec57df676a02de985`。实现使用 `FOR UPDATE SKIP LOCKED` 单次领取、固定 Ledger 输入、Task 3 verifier/canonicalizer、Release 数据库行锁、有界版本冲突重试、内容摘要复用，以及九个写入边界内的原子 Snapshot 图物化；没有外部 Adapter、latest Revision、第二数据源或 JVM lock。
- 任务 5 初次评审发现三项重要测试强度缺口：`SKIP LOCKED` 非阻塞性未被可控锁证明；Release 行锁、精确唯一约束翻译与严格三次重试未被锁死；损坏固定输入的失败终态缺少行为测试。第 1 轮修复增加了真实 PostgreSQL 独立事务/latch/锁等待观测、目标与非目标约束分类、最小 Repository fake 重试 mutation，以及 `TRACEABILITY_INPUT_NOT_VALID` 的 FAILED/DEAD_LETTER/零 Snapshot 与失败回滚验证。范围化复审结论为 `APPROVE_TASK`，无严重、重要或次要发现。
- 任务 5 本地可执行门禁为 `66/66` 通过，契约为 `schemas=4 positive=12 negative=5 operations=34`；将最大版本尝试临时改为 `2` 时，三个重试测试中两个按预期失败，恢复为 `3` 后 `3/3` 通过。全部 `25` 个 PostgreSQL 测试已编译，但在本机 `DockerClientProviderStrategy` 初始化处停止，未执行 fixture、SQL 或语义断言，因此精确头提交 CI 仍是任务关闭条件。ZH `f2ec0cc92d131e463734194d9976bfb6ed230ee2` 与 EN `9e712971fd843422dbaa166ec57df676a02de985` 的 Pair Gate 通过，非 Markdown 文件逐字节一致。
- 任务 5 首次精确头提交 CI：ZH Run `33914382941` / Job `101158044699` / Artifact `9952720393` 与 EN Run `33914386537` / Job `101158060276` / Artifact `9952729037` 均在首个 Task 5 Spring 上下文启动时失败。最深根因为缺少 `${VSRQG_OIDC_ISSUER_URI}` 的测试值；后续 Task 5 失败均为上下文阈值级联，`25` 个 PostgreSQL 语义测试未执行。
- 任务 5 CI 修复：ZH `955dc75f3eb0887718ca7f79302bc472c4d24b98`，EN `b4c3e8204c693fbad90df9bcc1badc3482e9ddc1`。固定测试 issuer/audience 现由共享 `PostgresIntegrationTest` 单一持有，并删除 `23` 处派生上下文重复配置；生产 `application.yml`、环境变量契约及连接池 `3/0` 未改变。ArchUnit 与真实 Spring 合并配置覆盖全部直接/间接派生类，验证有效 OIDC、禁止派生覆盖，并锁定八组 feature/trusted-validator 增量属性。结构测试 `4/4`、非 PostgreSQL 门禁 `68/68`、契约 `schemas=4 positive=12 negative=5 operations=34` 均通过；独立复审结论为 `APPROVE_CI_FIX`，无发现。ZH/EN Pair Gate 以 `137/137` 个行内 token 和逐字节一致的非 Markdown 文件通过。
- 任务 5 修复后精确头提交 CI：ZH Run `33917354363` / Job `101167500202` 在 `54a37478128850ce9bfc53832169caa38bb71644` 成功；EN Run `33917354349` / Job `101167500066` 在 `a713d49e8d552cee6be775903d7f9166e6f81571` 成功。完整 PostgreSQL 门禁实际执行通过，任务 5 至任务 6 的不可变 Snapshot 交接边界现已关闭。
- 任务 5 最终治理记录提交 CI：ZH Run `33918101853` / Job `101169867238` 在 `91197c11a3983e436fe7bcc74f1e44de56f48adc` 成功；EN Run `33918101603` / Job `101169866361` 在 `eb14f787016aebb0de9616dd84f94e3a666ca227` 成功。
- 任务 6 实现：ZH `daa36cb1ee135d08b72662d01cbf9970cad7f52a` 加第 1 轮评审修复 `5910cc83c9579107ba909c740799bfab86d8cb8d`；EN 对应提交 `a71b8de8a4e8e0d80fb276e8747686c35ad3b30e` 与 `d5eaeb40613794229cdf60d9c9ca473ebe19b895`。Run GET 使用公开 verification ID、Project membership 和 read scope；Snapshot GET 只从持久化完成表与 producer Run 读取默认最新成功或精确历史结果，不读取 latest/source revision、`artifact_release_edge_v` 或外部系统，也不重新计算。
- 任务 6 初次评审发现：Replay 仅序列化 application result，未证明公开 GET 字节稳定；报告把五次 Snapshot Repository 读取误写为完整数据库预算，遗漏一次 membership SQL。修复后，认证 MockMvc 精确历史 GET 实际经过 Security、Controller、Task 1 DTO、Application、Project membership 与 JDBC，并在写入 revision+1 和新 Issue Snapshot 前后逐字节比较 `response.contentAsByteArray` 与 `contentDigest`；完整授权读取预算明确为五次 Snapshot read 加一次 membership read，共六次常数数据库往返。范围化复审 `APPROVE_TASK`，无剩余发现。
- 任务 6 本地非 PostgreSQL 门禁 `25/25`、Acceptance `37/37`、契约 `schemas=4 positive=12 negative=5 operations=34` 均通过；PostgreSQL Query/Replay/Security 测试已编译，但本机在 `DockerClientProviderStrategy` 初始化处停止，未执行数据库语义断言，精确头提交 CI 仍是关闭条件。ZH `5910cc83c9579107ba909c740799bfab86d8cb8d` 与 EN `d5eaeb40613794229cdf60d9c9ca473ebe19b895` 的 Pair Gate 通过，非 Markdown 文件逐字节一致。
- 任务 6 首次精确头提交 CI：ZH Run `33922684732` / Job `101184297620` / Artifact `9955777800` 与 EN Run `33922684588` / Job `101184297309` / Artifact `9955765741` 均仅在 Replay fixture 追加 Issue Snapshot 时失败。`seed(issueCount=2)` 已创建版本 `2`，而 `appendLatestSnapshot` 再次硬编码版本 `2`，触发 `uq_issue_snapshot_release_version`；公开 Replay 字节断言尚未执行。
- 任务 6 CI 修复：ZH `ced03b5f0c6111a6942e162155ebfa3c421ffdf7`，EN `09173b436f302d7ca9cf4e3c3c832a51b3805d32`。共享 fixture 的 `appendSnapshotIssues`、`appendLatestSnapshot` 与 `appendUnsupportedLatestSnapshot` 统一在各自事务内按 Project/Release 参数化查询 `COALESCE(MAX(snapshot_version),0)+1`，不再维护三个平行硬编码。Replay 回归明确断言同一 Release 版本从 `1,2` 增长到 `1,2,3`；该 helper 不进入并发执行，也不替代生产 Release 行锁。独立复审 `APPROVE_CI_FIX`，无发现；非 PostgreSQL `25/25` 与契约 `schemas=4 positive=12 negative=5 operations=34` 通过，Pair Gate 与非 Markdown 字节一致性通过。
- 任务 6 修复后精确头提交 CI：ZH Run `33924139415` / Job `101188813729` 在 `6e8a3f51d428ba72f6d191d5558aeb3effc4fa1d` 成功；EN Run `33924139927` / Job `101188814772` 在 `c36e93f78e77560123853e926b9ac92e001b9382` 成功。完整 PostgreSQL Query/Replay/Security 门禁实际执行通过，任务 6 至任务 7 的公开只读与历史重放交接边界现已关闭。
- 任务 6 最终 ledger-only 精确头提交 CI：ZH Run `33918101853` / Job `101169867238` 成功；EN Run `33918101603` / Job `101169866361` 成功。该只更新台账的收尾提交未改变生产、测试或契约内容。
- 任务 7 初始候选与评审：ZH candidate `451b0950b279cd819ed7efe8ab76d1b7b49e8ef7` / Owner record `565744bb1d04e4d280e1b7c3a068b8dfe45e739f`，EN candidate `2bb3ad57e28d8b433cdd2ed3a55684eab5e17622` / Owner record `c3923502368b81801beb39f95337d15d11752f5f`。最终任务复审 `APPROVE_TASK` 与翻译复审 `APPROVE_TRANSLATION` 均为 0 Critical / 0 Important / 0 Minor；初始候选、record 和评审历史继续保留，不被最终替代 Evidence 删除。
- 任务 7 exact-head 失败/修复波次：初次 ZH Run `33930594922` / EN Run `33930594894` 暴露 Performance 状态等待与 restart 重连问题；后续修复依次关闭真实 restore/restart process identity、固定诊断、fixture authority ordinal、Docker dual-stack 端口、pinned-input digest、npm/Node child 边界和专用 M2 workflow 缺失 frozen Node dependency install。当前修复链为 ZH `c2bbc4df446104a3a2f28d59797d232cc1d189bc` → `7a7f3f4f8b452a2c5f6843c3685a7e8f1c7014e4` → `6bd58c65b436fc315432e23dfb05143a1b16f80c` → `2652c7f442b84a6ed04865e0104bf01a6c45e69d`，EN `0bf1e43d4219696afba8bae2932dea124d67d089` → `eb117c5fc9ba064c104b5733717f859befb8eb3e` → `48511ed1bcda8ed5dfcfa3cc0b3f81cf223989ae` → `a1a86715d244965061e5d333ca04e26f92a5dc79`；Round 3 专用 M2 Runs `33935861476` / `33935861193` 和 Round 4 Runs `33936939161` / `33936939172` 的失败 Evidence 继续作为被取代审计历史。
- 任务 7 最终 Evidence：ZH Subject `2652c7f442b84a6ed04865e0104bf01a6c45e69d` 的 M1 Run `33938298619` / Job `101230468818` 与 M2 Run `33938298641` / Job `101230468866` 均成功，Artifact `9960984362` 于 `2026-10-05T02:15:08Z` 到期；EN Subject `a1a86715d244965061e5d333ca04e26f92a5dc79` 的 M1 Run `33938298612` / Job `101230468647` 与 M2 Run `33938298611` / Job `101230469189` 均成功，Artifact `9960984734` 于 `2026-10-05T02:15:09Z` 到期。双方均为 12/12 `PASS`、digest self-check `true`、unsafe `0`、20 Issues/2,000 Edges/3 samples、五类 query count 各 `1`，四项 recovery 均 `PASS`；ZH start/worker/query P95 为 `852/2302/12 ms`，EN 为 `1020/2877/12 ms`，均低于硬上限。Task 7 实现状态更新为 `COMPLETE`；Owner decision 未发生，验收记录继续 `PENDING`。
