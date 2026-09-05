# Task 7 报告——Performance、Recovery、Candidate Gate 与 Owner Record

## 状态

候选 Gate、性能/恢复测试、只读 CI 和 Pilot 运维说明已实现。最终中文 Subject `2652c7f442b84a6ed04865e0104bf01a6c45e69d` 与英文 Subject `a1a86715d244965061e5d333ca04e26f92a5dc79` 的 exact-head M1/M2 CI 全部成功，20/2,000 性能、常数查询、真实 restore/restart/reclaim、Dead Letter、manual retry、Evidence digest 和安全扫描均已运行通过，Task 7 实现状态为 `COMPLETE`。Owner Authorization 仍为 `UNKNOWN`；验收记录必须保持 `PENDING`，Codex 不代替 Project Owner 作出决定。

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
9. Exact-head CI fix 复审先新增无 Docker 依赖的运行诊断契约测试，首次编译按预期 RED 于 `evaluateTraceabilityPerformanceAwait`、`TraceabilityPerformanceAwaitDecision` 与 `postgresRestartTimeoutMessage` 尚不存在。实现后 4 个诊断测试 GREEN；随后分别临时恢复旧的 Performance FAILED 短消息和 Recovery 单一超时消息，两次负向变异均使对应测试准确失败，还原后再次 GREEN。
10. Exact-head CI fix round 3 新增 fixture authority ordering、Docker dual-stack port binding 和冲突端口测试，首次编译按预期 RED 于两个 resolver 尚不存在。实现后无 Docker 测试 GREEN；临时移除 fixture 排序时 ordering test 准确失败，临时恢复旧 `singleOrNull()` 端口逻辑时 dual-stack test 准确失败，还原后再次 GREEN。另新增真实 20/2,000 direct-runner PostgreSQL 测试：它绕过 Worker 的异常脱敏边界，使未来 materialization SQL 异常直接进入测试报告，但不改变生产重试语义。
11. Round 3 复审新增 null/missing binding、UDP-only、非数字、`0` 与 `65536` 端口测试，总计 12 个无 Docker test cases GREEN。移除 TCP protocol 过滤会使 UDP-only case 准确失败；把合法范围错误放宽为 `0..65536` 会使两个边界 case 准确失败。20/2,000 direct-runner 进一步从该 Run 的 pinned input 经唯一 `TraceabilityVerifier`/canonicalizer 重算 digest，并要求 Snapshot header digest 精确相等、Run diagnostic 为空、Job `SUCCEEDED` 且 attempt 为 1。
12. Round 4 先在 Gate fixture 中放置固定 exit `86` 的 `npm` shim，并新增只接受 `node|scripts/contract-validator.mjs` 与 `node|scripts/acceptance-record-validator.mjs` 的命令绑定断言。旧 Gate 可重复 RED 于 `Successful checks must pass`，与 CI 中仅两个 npm-backed check 失败的边界一致；改为已解析 Node executable 直接执行两个固定仓库脚本后，同一测试 GREEN，且 trace 明确拒绝任何 `npm-shim|` 调用。
13. Round 5 先新增 workflow 顺序契约，旧 workflow 准确 RED 于 `M2.5 workflow must install the frozen Node dependency graph`；加入 Gate 前的 `pnpm install --frozen-lockfile` 后 GREEN。随后直接测试 `Invoke-SafeChild`：旧实现对不存在的 executable 抛出原始 `Get-Command` 异常；实现固定 `RESOLUTION_FAILED`、`START_FAILED`、`EXIT_NONZERO` 后 GREEN。失败 child 同时输出 synthetic secret 与绝对路径，结果对象和 Gate 输出均只保留 basename、category、exit code，负向内容未逸出；临时把 Evidence diagnostic 退回 `CHECK_FAILED` 后，测试准确 RED 于 `Real child failure lost its fixed Evidence diagnostic`，还原固定 category 后再次 GREEN。

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
- `./backend/gradlew.bat -p backend test --tests '*TraceabilityVerificationRuntimeDiagnosticTest' --no-daemon`：4 个无 Docker 诊断契约测试 `BUILD SUCCESSFUL`；旧 Performance FAILED 短消息与旧 Recovery 单一超时消息的负向变异均被测试击穿。
- `./backend/gradlew.bat -p backend test --tests '*TraceabilityVerificationRuntimeDiagnosticTest' --tests '*TraceabilityVerificationFixtureOrderingTest' --no-daemon`：round 3 的 12 个无 Docker 诊断/顺序/端口 test cases `BUILD SUCCESSFUL`；移除 fixture 排序、恢复 `singleOrNull()`、移除 TCP 过滤和放宽端口范围的负向变异均被测试击穿。
- `npm run test:contracts`：`PASS contracts schemas=4 positive=12 negative=5 operations=34`。
- `npm run verify:acceptance`：`PASS acceptance-records`。
- `./backend/gradlew.bat -p backend cleanTest test --tests '*TraceabilityVerificationPerformanceTest' --tests '*TraceabilityVerificationRecoveryTest' --rerun-tasks`：`BUILD FAILED`；2/2 均因本机 Docker/Testcontainers 初始化失败，未执行 PostgreSQL 语义。

## Commit

- 候选 Gate Subject：`test(m2): add traceability verification candidate gate`。本报告位于同一 commit 中，因此不在自身内容内嵌该 commit 的 SHA；外部交接必须使用 `git rev-parse HEAD` 的真实值。
- Owner record 使用候选 Gate 的真实 SHA 后单独提交，Subject 固定为 `docs(m2): add traceability owner gate candidate`。

## Exact-head CI Fix Round 1

- 首次 ledger-only exact-head CI 在 ZH Run `33930594922` / Job `101208217133` / Artifact `9958498571` 与 EN Run `33930594894` / Job `101208217181` / Artifact `9958499937` 同样失败。真实 XML 显示 Performance 在 `worker.runNext()` 返回后直接 `requireNotNull(result_snapshot_id)`，Recovery 则在 raw Docker restart 后持续连接 Testcontainers 启动时缓存的 `localhost:32772`。
- Performance 测试不再把 `runNext() == true`（仅表示领取过一个 Job）等同于目标 Run 成功。它在同一个 worker 计时样本内有界轮询目标 Run，只有 `SUCCEEDED` 且 Snapshot ID 非空才继续；`FAILED` 或超时会公开固定的 Run status、diagnostic 与 Job lifecycle，不再以无上下文的空值异常遮蔽根因。20 Issue、2,000 fixed Edge、3 samples、常数查询计数和原硬上限均未缩减。
- Performance 的 `FAILED`、timeout 与 `SUCCEEDED` 但 Snapshot ID 为空三条失败路径统一输出同一固定字段集合：reason、runId、status、diagnostic，以及 Job status/attemptCount/resultSummary lifecycle。纯状态机测试逐分支断言完整字面输出，因此任一分支重新退回短消息都会失败；正常 pending 路径不额外查询 Job，不改变性能样本查询边界。
- Recovery 测试保留同一独立 restore 容器上的真实 Docker restart；每次重连都重新 `inspect` 容器，要求 `State.Running=true`，读取 PostgreSQL 5432 的当前 host binding，再创建 fresh DataSource。等待条件同时要求连接成功且 `pg_postmaster_start_time()` 严格晚于 restart 前，因此端口刷新不会弱化进程边界。
- Recovery restart 超时不再一律误报“未接受连接”：固定诊断以 `NO_FRESH_CONNECTION` 区分从未取得 fresh connection，以 `POSTMASTER_START_TIME_NOT_ADVANCED` 区分已连接但 start time 未严格变更，并始终报告 before/last-observed postmaster time 与最后连接异常类型；最后连接异常仍作为 cause 保留。
- 本轮修改在无 Docker 主机上完成 Kotlin 编译和非 Docker Gate 验证；PostgreSQL 运行语义仍必须由修复提交的 exact-head Linux/Docker CI 证明。Owner decision 继续保持 `PENDING`，progress ledger 本轮不改。

## Exact-head CI Fix Round 3

- Round 2 exact-head ZH Run `33933549761` / Job `101216929632` / Artifact `9959464252` 显示 Performance 已不再空值失败，而是目标 Run 经 3 次基础设施重试后以 `TRACEABILITY_VERIFICATION_RETRY_EXHAUSTED` / `DEAD_LETTER` 终止；Recovery 则在独立 PostgreSQL 已成功启动并发布 `localhost:32772` 后、restart 之前失败于“无当前端口绑定”。
- Performance 的底层失败来自测试 fixture authority ordinal 与冻结 Worker 排序不一致：fixture 以 `ISSUE-1, ISSUE-2, …` 的生成顺序写 `release_issue_snapshot_item.ordinal`，Verifier 以固定 Unicode identity order 生成结果（`ISSUE-1, ISSUE-10, …`），而 V11 `validate_snapshot_issue_result` 要求结果 ordinal 与权威快照 ordinal 相同。fixture 现在复用 `TraceabilityOrdering.unicodeCodePointOrder`，仅修正测试权威数据，不修改生产排序、V11 约束或 retry/dead-letter 语义。
- 新增的 exact 20 Issue/2,000 Edge direct-runner 测试直接执行 `RunTraceabilityVerification`，从该 Run 的 pinned input 调用唯一 `TraceabilityVerifier`/canonicalizer 重算预期 digest，并要求 Snapshot header `content_digest` 精确相等；同时断言 20 条 Issue Result、2,000 条 Snapshot Edge、Run `SUCCEEDED` 且 diagnostic 为空、Job `SUCCEEDED` 且 attempt 为 1。该观察路径会让底层 `DataAccessException` 原样成为测试失败，而正式 Performance Gate 仍通过 Worker 执行三次有界重试和固定脱敏诊断。
- Recovery 的端口误判来自对 `Ports.Binding[]` 使用 `singleOrNull()`：Docker 可为同一 `5432/tcp` host port 同时返回 IPv4/IPv6 binding。resolver 现在从 fresh inspect 中匹配 `5432/tcp`，严格校验每个 `hostPortSpec`，对端口值去重；相同端口的 dual-stack binding 被接受，null/missing、UDP-only、非数字、`0`、`65536`、多个不同 published port 均固定 fail-closed。restart 后仍再次 inspect 并重建 DataSource，真实数据库进程边界不变。
- Round 3 最终提交不是已公开 Round 2 commit 的 sibling：中文以远端 `c2bbc4df446104a3a2f28d59797d232cc1d189bc` 为直接父提交，英文以远端 `0bf1e43d4219696afba8bae2932dea124d67d089` 为直接父提交，均为正常单提交快进后继；未 rebase、force 或删除远端历史。
- 本轮在无 Docker 主机上完成纯测试、Kotlin 编译和非 Docker Gate 验证；20/2,000 direct materialization、restore/restart/reclaim 仍必须由修复 commit 的 exact-head Linux/Docker CI 证明。Owner decision 保持 `PENDING`，progress ledger 不改。

## Exact-head CI Fix Round 4

- Round 3 exact-head M1 在 ZH Run `33935861445` 与 EN Run `33935861206` 均为 `SUCCESS`，已实际证明 20/2,000 direct digest、performance、restore/restart/reclaim 等 PostgreSQL 路径 GREEN。专用 M2 Gate 则在 ZH Run `33935861476` / Artifact `9960231524` 与 EN Run `33935861193` / Artifact `9960233754` 同样仅有 contract 和 acceptance 失败；Artifact 中 performance、全部 recovery 项与 evidence digest 均为 `PASS`，contract 保留首个 Gradle 阶段的 10 tests，而 npm 阶段失败，acceptance 为 `UNKNOWN`。
- 两个失败检查是 Gate 中仅有的 npm-backed child；对应 Node 校验脚本在本机已有依赖的工作树中直接执行均通过。Round 4 因此把故障假设收敛到 npm shim，并移除该不必要的跨平台边界；后续 Round 4 exact-head 结果证明该假设不足以解释根因。
- Gate 现在仍通过 `Resolve-FixedExecutable` 固定解析 `node`，并通过 `ArgumentList` 分别传递固定的 `scripts/contract-validator.mjs` 与 `scripts/acceptance-record-validator.mjs`；没有字符串命令拼接、shell fallback 或 contract/acceptance 放宽。纯 fixture 以失败 npm shim 复现旧边界，验证两个检查只走 Node，且仍保持原 12 项顺序、真实 child exit 传播、失败后继续执行和 Evidence 生成语义。
- 本机 orchestration、contract 与 acceptance 验证通过。Owner decision 继续保持 `PENDING`，progress ledger 不改；专用 M2 Gate 的 Round 4 exact-head Linux CI 用于验证该假设。

## Exact-head CI Fix Round 5

- Round 4 exact-head M1 在 ZH Run 与 EN Run 再次均为 `SUCCESS`。专用 M2 Gate 在 ZH Run `33936939161` / Artifact `9960589963` 与 EN Run `33936939172` / Artifact `9960569868` 仍同样只有 contract `FAILED tests=10` 与 acceptance `FAILED UNKNOWN`，performance、全部 recovery 项和 digest 均为 `PASS`。固定 Node 直启未改变失败形态，因此 npm shim 不是根因。
- 根因是专用 `.github/workflows/m2-backend.yml` 把 pnpm 配置为 `run_install: false`，且 Gate 前没有任何 dependency install。两个失败脚本分别 import `@apidevtools/swagger-parser`、`ajv`、`ajv-formats`、`yaml`；从同一 HEAD 用 `git archive` 创建不含 `node_modules` 的干净 checkout 后，两个固定 Node 命令都稳定 exit `1` 且命中 `ERR_MODULE_NOT_FOUND`。这精确解释 contract 的 Gradle 10 tests 成功后 JS child 失败，以及 acceptance 没有 test count。
- Workflow 现在在 Gate 前执行 `pnpm install --frozen-lockfile`，只安装 lockfile 固定的校验依赖，不增加权限、Provider credential、Company 调用或外部写操作。Gate 仍以已解析 Node executable 和固定脚本参数执行，不恢复 npm shim。
- `Invoke-SafeChild` 现在把失败分为 `RESOLUTION_FAILED`、`START_FAILED` 与 `EXIT_NONZERO`，仅返回/输出 executable basename、固定 category 和 exit code；stdout/stderr 仍只在内存流中消费，不回显、不进入 Evidence。Evidence schema 未增加字段，既有 check `diagnostic` 仅使用固定 category；Docker 不可用仍保留专用固定诊断。
- 本机 workflow 顺序契约、三类 child 失败诊断、secret/path 不泄漏、12 项编排、contract 与 acceptance 均已验证。Owner decision 保持 `PENDING`，progress ledger 不改；Round 5 专用 M2 exact-head Linux CI 仍是最终证明。

## Final Exact-head Evidence Receipt

- 中文 Subject `2652c7f442b84a6ed04865e0104bf01a6c45e69d`：M1 Run `33938298619` / Job `101230468818` 和 M2 Run `33938298641` / Job `101230468866` 均为 `success`；Artifact `9960984362` 名称为 `m2-5-evidence-2652c7f442b84a6ed04865e0104bf01a6c45e69d`，于 `2026-10-05T02:15:08Z` 到期。
- 英文 Subject `a1a86715d244965061e5d333ca04e26f92a5dc79`：M1 Run `33938298612` / Job `101230468647` 和 M2 Run `33938298611` / Job `101230469189` 均为 `success`；Artifact `9960984734` 名称为 `m2-5-evidence-a1a86715d244965061e5d333ca04e26f92a5dc79`，于 `2026-10-05T02:15:09Z` 到期。
- 两侧 Artifact 均为 `status=PASS`、12/12 checks、digest self-check `true`、unsafe finding `0`、20 Issues/2,000 Edges/3 samples，release/header/issues/paths/gaps query count 各 `1`；`backupRestore`、`dbRestartReclaim`、`deadLetter`、`manualRetry` 均为 `PASS`。
- 中文 start/worker/query P95 为 `852/2302/12 ms`，英文为 `1020/2877/12 ms`，全部低于共享 CI 硬上限。英文 start P95 比 `1000 ms` 参考目标高 `20 ms`；该目标不是 Gate 硬限，也不构成 Company 性能承诺。
- 初始候选、各失败 Run、Round 1 至 Round 5 修复 commits 和被取代 Artifacts 均保留在本报告与 Git 历史中。最终 Evidence 取代它们成为当前候选依据，但不删除或改写其审计事实。
- Task 7 的技术实现与 CI closure 已完成；`M2-5-OWNER-GATE-001` 仍等待 Project Owner 独立决定，状态、owner 与 decisionAt 均保持 `PENDING`。

## 剩余风险

- 共享 CI 的宽松硬上限不是 Company 性能承诺；固定参考环境尚未建立，英文 start P95 也未达到 `1000 ms` 参考目标。
- 两个 Evidence Artifacts 最早于 `2026-10-05T02:15:08Z` 到期；Owner 应在到期前复核，或按 Evidence Archive 治理形成独立受控归档。
- Git commit 与 CI locator 不提供密码学 Owner 身份认证；在收到可复核的 Owner 原始指令和独立双语 receipt 前，Owner Gate 必须保持 `PENDING`。
