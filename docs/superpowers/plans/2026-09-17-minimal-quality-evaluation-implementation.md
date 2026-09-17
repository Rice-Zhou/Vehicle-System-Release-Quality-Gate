# 最小质量判定实施计划

> **执行 Agent：** REQUIRED SUB-SKILL：逐任务使用 superpowers:executing-plans；若明确选择子 Agent 实施，使用 superpowers:subagent-driven-development。复选框记录实际完成，不提前勾选。

**目标：** 以同 Release 的正式输入产生可解释、可重放的限定质量结果。
**架构：** 现有 Backend 模块化单体中增加 quality；正式源数据经应用读取端口绑定，PostgreSQL Job 固定输入，纯求值器产生结果，报告只读投影。
**技术栈：** Kotlin 2.2.21、Spring Boot 3.5.16、JDK 21、PostgreSQL、SnakeYAML 2.5 候选、现有 Node 契约工具。
**规范：** [技术设计](../specs/2026-09-15-minimal-quality-evaluation-design.md)、[范围 A1–A8](../specs/2026-09-15-minimal-quality-evaluation-scope-draft.md)、[探针修订](../../v0.2/reviews/2026-09-15-quality-evaluation-preflight.md)、[TDR-026](../../v0.2/tdr/TDR-026-minimal-quality-evaluation.md)。

## 全局约束与执行状态

计划编制后的“执行下一步”授权 Task 1 契约实施，不代录 TDR Accepted 或产品验收。Task 1 的实际证据见[来源绑定与兼容性记录](../../v0.2/reviews/2026-09-17-quality-task1-contracts.md)；后续“执行下一步”授权 Task 2 解析与编码实施，见[工程记录](../../v0.2/reviews/2026-09-17-quality-task2-parsing-encoding.md)。Task 3–6 尚未执行。产品实施前确认 TDR-026 的目录衔接、required 政策与 Case 动作，若触及冻结边界先 ADR。原 Smoke 批准不扩展为本切片批准。

- 不修改 Core Contract 或原 Snapshot；v1 目录和摘要算法保留。运行时不接受未支持目录，不静默转换版本。
- 一个 Run/Case，20 Issues / 2000 Edges；每规则 64 KiB、深度 32、4096 节点；每 Set 32 规则、输入 4 MiB、求值 100000 步。
- 数字 token/precision/整数位数上限 4096，绝对 scale 4096，展开长度 8192；超限明确 ERROR。
- 未采集 Crash/ANR 保留 Missing/UNKNOWN；任何 required 输入缺失、无适用规则或完整性错误均不 PASS。
- 不新增服务、Company 资源或真实 Provider；不自动执行设备、发布规则、merge、Tag、发布或部署。
- 复用现有双语 worktree；每段单一提交并推送，Markdown 配对、非 Markdown blob 一致。保留用户未提交修改。
- 后端单测设置 JUnit 默认 timeout 为 60 秒，DB 集成测试使用既有受控测试配置；缺数据库环境记录未运行，不以跳过算通过。

路径约定：下文 P = backend/src/main/kotlin/com/ricezhou/vsrqg/quality，T = backend/src/test/kotlin/com/ricezhou/vsrqg/quality。这是精确目录前缀，不是现有模块声明；所有新文件在对应 Task 创建。命令中的 Gradle 工作目录为 backend，Node 命令工作目录为仓库根。

## Task 1：机器契约与源事实绑定契约

**文件：** 新增 contracts/facts/v0.2/fact-catalog-v2.json、schemas/v0.2/fact-catalog-v2.schema.json、schemas/v0.2/quality-evaluation.schema.json、scripts/tests/quality-contract.test.mjs；修改 contracts/openapi/v0.2/openapi.json、scripts/contract-validator.mjs。保留原 fact-catalog.json 与其 Schema。
**接口：** v2 明确 minimumConfidenceLevel 枚举、source/Case/排序字段及 item 局部绑定；新增 itemBindings 与 enumValues 的 Schema 定义，旧 Schema 不接受这些新字段。Rule Set 明确 catalogVersion/engineVersion/requiredIssueRefs/selectedCaseRefs/project；请求仍只接收正式引用，响应区分 Evaluation ERROR 与质量 action。
**绑定表：** Issue required 只来自已发布 Rule Set；severity/source 来自精确 Issue Snapshot；fixed/included/verified 来自同版本 Traceability；Case 来自正式 Resolution；Evidence 来自唯一模块核验端口。逐项核对当前模块的可读字段，缺字段只能补应用读取端口，不能填默认值。

- [x] 写失败契约测试：v1 字节保持、v2 item.status 只允许 Test 集合、item.required 只允许 Issue 集合、UNKNOWN 等级合法但数字拒绝、selectedCaseRefs 空集合拒绝、跨 project 引用拒绝属于运行测试而非伪装 Schema 能检查。
- [x] 用 Node 运行测试，记录因新契约缺失而失败。
- [x] 补齐契约和明确正反 fixture；全量验证器同时读取 v1/v2。兼容报告说明现有未实现质量路由的请求扩展；不能静默更新 compatibility-baseline。
- [x] 测试通过后提交契约段，证据记录 A1/A3/A4/A5 的结构检查与剩余运行检查。

```js
// scripts/tests/quality-contract.test.mjs: preserve the v1 identity.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
test('catalog versions stay distinct', async () => {
  const read = async p => JSON.parse(await readFile(p, 'utf8'));
  assert.equal((await read('contracts/facts/v0.2/fact-catalog.json')).version, 1);
  assert.equal((await read('contracts/facts/v0.2/fact-catalog-v2.json')).version, 2);
});
```

```powershell
node --test scripts/tests/quality-contract.test.mjs
node scripts/contract-validator.mjs
```

## Task 2：严格解析、目录绑定与精确规范编码

**文件：** 修改 backend/build.gradle.kts；新增 P/domain/QualityFailure.kt、P/domain/QualityValue.kt、P/domain/QualityCanonicalEncoder.kt、P/adapter/StrictRuleYaml.kt、T/StrictRuleYamlTest.kt、T/QualityCanonicalEncoderTest.kt。
**接口：** QualityFailure(code: String) 为明确领域异常；QualityValue 为 Null/Bool/Text/Integer/Decimal/ArrayValue/ObjectValue 的 sealed 值树。StrictRuleYaml.parse(bytes: ByteArray): QualityValue 只做严格语法；目录/AST 类型由 Task 3 验证。QualityCanonicalEncoder.encode(value: QualityValue): ByteArray 产生复核规定的有类型 UTF-8 格式。

- [x] 首先运行 dependencyInsight 核对当前 BOM，显式锁定 SnakeYAML 2.5；冲突则停在本段报告，不擅自升级 Spring Boot。
- [x] 写拒绝样例：重复解码 key、alias/anchor/tag/merge、多文档、复合 key、plain 日期/yes/on、非法 UTF-8、未配对 surrogate、字节/深度/节点上限；quoted 文本保留。正好上限与上限加一分别验证。
- [x] 写编码 golden bytes：全部类型、控制字符、Unicode scalar key 排序、无 normalization、负零/尾零、大整数相邻值及 exponent 展开上限。
- [x] 实施事件栈与逐 mapping key 集合，在通用对象构造前拒绝；LoaderOptions 仅辅助。编码拒绝超限后才构造/展开大数，禁止 double。
- [x] 运行目标单测与 build，提交解析/编码段。17 项探针不能代替本段拒绝器与全类型测试。

```kotlin
@Test
fun rejectsDuplicateKeys() {
    val failure = assertThrows<QualityFailure> {
        StrictRuleYaml().parse("a: 1\na: 2\n".toByteArray())
    }
    assertEquals("RULE_DUPLICATE_KEY", failure.code)
}
```

```powershell
./gradlew.bat dependencyInsight --dependency snakeyaml --configuration runtimeClasspath
./gradlew.bat test --tests '*StrictRuleYamlTest' --tests '*QualityCanonicalEncoderTest'
./gradlew.bat assemble
```

## Task 3：完整受限规则求值与两条演示规则

**文件：** 新增 P/domain/RuleAst.kt、P/domain/FactBindings.kt、P/domain/RuleEvaluator.kt、P/domain/QualityAggregator.kt、T/RuleOperatorMatrixTest.kt、T/QualityAggregatorTest.kt；新增 contracts/examples/v0.2/quality-rule/smoke-case-outcome.yaml、required-issue-verified.yaml，登记版本化 golden fixtures。
**接口：** RuleAst 为规则规范所有操作符的 sealed AST；FactBindings 绑定 QualityValue 与目录 v2。RuleEvaluator.evaluate(ast: RuleAst, facts: FactBindings): RuleOutcome；RuleOutcome 保存 status、matchedFacts、evidenceRefs、explanationCode/parameters；RuleStatus 包含 PASS/WARNING/BLOCK/ERROR/NOT_APPLICABLE。QualityAggregator.aggregate(statuses: List<RuleStatus>): String 返回 ERROR 或质量 action；ERROR 时不得制造 Quality Result。

- [ ] 按规则规范第 5 节逐操作符生成 value/empty/missing/null/type-error 矩阵，and/or 操作数顺序置换均测试，缺失与显式 null 不合并。
- [ ] 为目录局部字段、错误传播、全规则无适用、求值步数限制写失败测试。
- [ ] 实施不访问网络/文件/当前时间的纯求值器与聚合器；BigDecimal/BigInteger 类型比较沿目录规范。
- [ ] 两条 YAML 覆盖 Case PASS/FAIL/BLOCKED/SKIPPED/ERROR/TIMEOUT 与 required Issue false/true；Case PASS 仍可因 Issue 未 Verified 聚合 BLOCK。published selectedCaseRefs 为空在输入校验拒绝，不改 all 的空集合语义。
- [ ] 跑矩阵与 golden tests，提交纯引擎段；此段无规则发布或设备运行。

```kotlin
@Test
fun errorsDominateQualityActions() {
    assertEquals("ERROR", QualityAggregator.aggregate(
        listOf(RuleStatus.BLOCK, RuleStatus.ERROR)))
    assertEquals("ERROR", QualityAggregator.aggregate(emptyList()))
}
```

```powershell
./gradlew.bat test --tests '*RuleOperatorMatrixTest' --tests '*QualityAggregatorTest'
```

## Task 4：规则版本存储、审核与发布 API

**文件：** 新增 P/application/RulePublication.kt、P/adapter/JdbcQualityRepository.kt、P/adapter/RuleSetController.kt、T/RulePublicationIntegrationTest.kt；新增 backend/src/main/resources/db/migration/V16__quality_rules.sql。执行前核对版本未被占用；若已使用则分配下一个版本并同步计划。
**接口：** RulePublication.create(projectId: String, body: JsonNode, idempotencyKey: String): JsonNode；publish(projectId: String, id: String, version: Long, reason: String, idempotencyKey: String): JsonNode。JsonNode 仅为契约 DTO 边界；内部使用 Task 2/3 的类型。actor 从既有鉴权上下文读取，禁止请求伪造。

- [ ] 在现有 PostgreSQL 测试方式下验证权限、project 隔离、作者/审核者、同 key 同内容复用与冲突、If-Match 冲突、发布后 UPDATE/DELETE 拒绝；写数据库失败回滚测试。
- [ ] 增量表保存 YAML、AST、目录/引擎/规则集版本、requiredIssueRefs/selectedCaseRefs、Git 来源、digest 与审核信息，复用唯一 Audit/幂等机制。
- [ ] 接通既有 createRuleSet/publishRuleSet 路由。无权限或未通过全部 golden/类型验证不得发布；测试 fixture 中模拟发布不代表真实发布许可。
- [ ] 跑契约、迁移和集成测试，提交本段。回滚应用保留已发布历史。

```sql
-- Integration test must assert rejection after publishing the fixture.
UPDATE quality_rule_set_versions SET content_digest = 'changed'
WHERE state = 'PUBLISHED';
```

```powershell
./gradlew.bat test --tests '*RulePublicationIntegrationTest'
```

## Task 5：正式输入、异步评估、结果查询与重放

**文件：** 新增 P/application/QualitySourceReader.kt、QualityEvaluationService.kt、P/adapter/QualityEvaluationWorker.kt、QualityController.kt、T/QualityInputBindingTest.kt、QualityEvaluationIntegrationTest.kt、QualityReplayTest.kt；扩展 Task 4 仓储；新增 V17__quality_evaluations.sql。Issue/Traceability/Test Management/Evidence 各自在所属 application 包提供读取实现；禁止 quality SQL 复制源模块业务校验。
**接口：** QualitySourceReader.read(projectId: String, releaseId: String, request: JsonNode): JsonNode 返回受类型契约约束的固定源内容；QualityEvaluationService.request(projectId: String, releaseId: String, body: JsonNode, idempotencyKey: String): JsonNode；list(projectId: String, releaseId: String, cursor: String?): JsonNode。Worker 仅按既有 Job claim/fencing 协议调用；不接受客户端提交 facts。

- [ ] 写同 APK 不同 Release、混项目/Manifest、缺源字段、空 selectedCaseRefs、多 Run、缺 Result、required 引用不存在、Evidence 损坏与 appliesWhen=false 的反例。Fake reader 只在单测使用，不在生产失败时 fallback。
- [ ] 短事务固定源引用/内容；事务外 Evidence 模块核验 bytes；复核源状态/fencing 后封闭 Input Snapshot。失败记录 Evaluation ERROR，不换成新来源。
- [ ] 纯求值后原子写全部 Rule Results、Quality Result、Audit 与 Job 终态。测试事务失败、重领、旧 lease 晚写、重复提交及唯一约束；失败 Evaluation 必须可查询。
- [ ] 保存每个 Case 的正式 Resolution、所选 Attempt 与其他 Attempt 历史，精确规则/目录/编码/Engine 版本。缺解释器拒绝重放，不用新版替代。
- [ ] 用固定完整输入在三个新 JVM 中重放；比较规范结果 digest，改变时间/请求 ID 仍一致。缺 Payload 的当前核验与历史决定重放分开报告。
- [ ] 跑单元/DB集成/权限/重放，提交后验证恢复 DB+Payload 的独立副本；不动原演示库。

```sql
-- Integration query: every committed evaluation has at most one final result.
SELECT evaluation_id FROM quality_results
GROUP BY evaluation_id HAVING COUNT(*) > 1;
-- Expected: zero rows, enforced by a database unique constraint.
```

```powershell
./gradlew.bat test --tests '*QualityInputBindingTest' --tests '*QualityEvaluationIntegrationTest' --tests '*QualityReplayTest'
```

## Task 6：同 Release 演示、只读报告与验收材料

**文件：** 新增 backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/QualityDemoScenario.kt、scripts/demo/run-quality.ps1、docs/demo/quality-evaluation-runbook.md；修改 scripts/demo/demo-report.mjs、render-report.mjs、scripts/tests/demo-report.test.mjs；新增 .github/workflows/quality-evaluation.yml。复用既有 M2/M3 准备代码与正式 API，不复制权限或 Evidence 上传实现。
**接口：** 演示入口只接收受控配置路径，正式 API 生成新 Release/Snapshot/Run；报告读取质量查询导出与绑定引用，不从原 M1/M2 文件推断质量。现有 m1/m2 报告参数行为保持；quality scope 需新增严格输入 Schema 和新输出文件，禁止覆盖。
**证据：** 一份正常 Smoke、requiredIssueRefs 显式空集合的限定 PASS 示例；一份确定 FAIL 的 BLOCK；一份 required Issue 未 Verified 的 BLOCK；缺必需 Evidence 的 ERROR。合成 Issue/Build 与真机 Evidence 分开标明，不宣称真实 Issue 已 Verified。

- [ ] 先用 CI fixture 跑四种结果及非法报告输入、HTML 转义、跨 Release 报告绑定；旧报告回归必须通过。
- [ ] 实施新的正式串联入口与只读报告。新 Run 选择与现有历史无覆盖关系，输出保存输入/规则/执行版本、结果、未覆盖项和 Evidence 定位。
- [ ] CI 运行契约、全算子矩阵、目标测试/build、故障与三次重放；共享 CI 性能只作共享环境记录。
- [ ] 需要真机阶段时先确认当前设备授权、配置和环境，使用新输出/spool；未执行则明确 UNKNOWN，不重用历史截图作为新运行。
- [ ] 自查 diff 并完成工程复审；A1–A8 逐项记录 Subject/证据与未关闭风险，Owner 单独验收。不得自行把矩阵全部改为 PASS。

```powershell
node --test scripts/tests/demo-report.test.mjs
node scripts/contract-validator.mjs
./scripts/demo/run-quality.ps1 -Config D:/controlled/quality/config.json
```

最后一条仅为后续授权真机执行命令，文件路径为占位示例，不在编制计划时运行。run-quality.ps1 需将非零退出与错误摘要原样暴露，不能静默兜底。

## 覆盖、检查与下一步

| 验收 | 主要任务 |
|---|---|
| A1 | 1、5、6：同 Release 正式绑定与反例 |
| A2 | 3、5、6：规则状态、结果、导航 |
| A3 | 1、3、4、5：required 来源和 Verified 保留 |
| A4 | 2、3、5：拒绝错误输入、缺失版本及无适用规则 |
| A5 | 1、3、5、6：Missing/Empty/Null/UNKNOWN 与未覆盖 |
| A6 | 2、3、5：守卫、精确编码、完整矩阵、三次重放 |
| A7 | 4、5：权限、不可变、幂等、晚写、回滚、恢复 |
| A8 | 6：只读展示、来源与证据验收 |

每段使用对应目标测试；全部通过后才扩大至受影响 build/最小 smoke。不得把工具不可用的错误当作预期红灯。提交前运行 Markdown 配对、验收/契约校验与 git diff --check；推送后核对准确远端提交与 CI，CI 未结束如实记录。

当前结果：六段计划及 A1–A8 映射已形成；未实施任何任务。Git 状态：双语计划版本化提交，版本由 Git history 定位。下一步动作：确认 TDR-026 与计划后执行 Task 1。前置条件：Owner 接受目录衔接、required 政策和 Case 动作；若冻结语义改变先 ADR。验收目标：Task 1 的 v1 保留、v2 正反例与 API 契约检查通过，并有独立提交及记录。
