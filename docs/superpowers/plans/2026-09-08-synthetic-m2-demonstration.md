# 最小 M2 合成串联演示实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 复用 M1，交付两条合成 Issue 的真实输入、Traceability 与历史查询演示。

**Architecture:** 使用既有 demo source set 和 HTTP/事务/Worker；只在隔离启动器注入 Fixture 输入与专用 validator。演示不写业务结果，不新增生产入口。

**Tech Stack:** Kotlin/JDK 21、Spring Boot、PostgreSQL 17.11、现有 PowerShell/Gradle/GitHub CI。

**Spec:** [TDR-022](../../v0.2/tdr/TDR-022-synthetic-m2-demonstration.md)，同时作为本计划设计规范；任务 1 实施范围于 2026-09-09 获授权；任务 2 未启动。

## Global Constraints

- V0.1 冻结语义、Schema、Migration、生产 API、权限规则、canonicalization 和 Quality Engine 均不修改。
- M2 必须先完成同次 M1 场景，再复用其 Release、Locked Manifest 与文件摘要。
- 全部 Verified=false；classification=SYNTHETIC_DEMO、proofKind=SYNTHETIC_FIXTURE。
- 不增服务、软件安装、Company/AWS 资源、归档操作或新前端。
- 后端单测默认 60 秒超时；本机没有可用 Docker 时由 exact-commit CI 补足实际 PostgreSQL/HTTP 证据。
- 每项修改独立双语提交、Pair Gate 后推送；保留已有工作区内容。Owner 验收另行记录，不替 Owner 批准。

## 任务 1：隔离合成输入与身份接入

**Files:**

- 新增 `backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/M2DemoInputs.kt`：Fixture factory、descriptor 与固定 Mapping 定义。
- 新增 `backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/M2DemoProvenanceValidator.kt`：有限合成输入匹配。
- 修改 `backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/M1DemoMain.kt`：显式 M2 选项与 bean 注入。
- 修改 `backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/M1DemoIdentity.kt`、`M1DemoBootstrap.kt`（同目录）：Engineer/Service 与 FIXTURE Source 配置。
- 新增 `backend/src/test/kotlin/com/ricezhou/vsrqg/demo/M2DemoInputsTest.kt`。
- 修改 `backend/src/test/kotlin/com/ricezhou/vsrqg/demo/M1DemoPackagingTest.kt`。

**Interfaces:**

沿用 `IssueSourceRuntimeFactory.open(profile: CompiledIssueMappingProfile): IssueSourcePort` 和 `BuildProvenanceValidatorPort.validate(provenance: CanonicalBuildProvenance): ProvenanceValidation`。新增 `M2DemoProvenanceValidator(payloadSha256: String)`，`M2DemoInputs.mappingDefinition(): JsonNode`，`M2DemoInputs.factory(observedAt: Instant): IssueSourceRuntimeFactory`。为既有 start/environment 增加默认关闭的 `includeM2: Boolean = false`；M2 start 另传实测 payload SHA，未提供则明确失败。Identity token 新增末尾默认参数 scopes、principalType、projectReference，原 M1 调用签名兼容；Bootstrap 新增 M2 初始化方法返回 Engineer/Service subject 和 sourceId（只在内存使用）。

- [x] 编写无 Docker 单测，固定 Mapping 内容如下；factory 应从编译结果使用 mappingVersion，不能自行覆盖版本：

```json
{
  "schemaVersion":"jira-mapping-profile/v1",
  "normalizationVersion":"unicode-nfc-trim-root-lower/v1",
  "unknownStatusPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
  "unknownSeverityPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
  "statusAliases":{"CLOSED":["Closed"]},
  "severityAliases":{"HIGH":["Major"]}
}
```

```kotlin
@Test
@Timeout(60)
fun fixtureUsesCompiledVersion() {
    val codec = JcsIssueMappingProfileCodec(jacksonObjectMapper())
    val profile = codec.compile(M2DemoInputs.mappingDefinition())
    val factory = M2DemoInputs.factory(Instant.parse("2026-09-08T00:00:00Z"))
    assertThat(factory.descriptor.sourceType).isEqualTo("FIXTURE")
    assertThat(factory.descriptor.adapterVersion).isEqualTo("m2-demo-fixture/v1")
    assertThat(factory.open(profile).fetchByIds(setOf("DEMO-1", "DEMO-2")).issues)
        .extracting<String> { it.mappingVersion }.containsOnly(profile.mappingVersion)
}
```

- [x] 执行 `./gradlew test --tests '*M2DemoInputsTest' --tests '*M1DemoPackagingTest'`（backend 目录、JDK 21），记录 RED；新增类型缺失应失败，不能将环境失败当 RED。
- [x] 实现一个 terminal FixturePage，两个 CLOSED/HIGH 的 NormalizedIssue，sourceVersion=1、sourceReference=SYNTHETIC_DEMO，observedAt 和 mappingVersion 来自入参；复用 FixtureIssueSourceAdapter 的 fetch/size 行为。
- [x] 实现 validator；使用下列有限匹配，不复制 canonicalizer。表中 pair 同时绑定 Build、revision 与 Issue，不能交叉组合：

```kotlin
val pairMatches = when (e.buildId) {
    "1" -> e.sourceRevision == "a".repeat(40) && e.sourceIssueIds == listOf("DEMO-1")
    "2" -> e.sourceRevision == "b".repeat(40) && e.sourceIssueIds == listOf("DEMO-2")
    else -> false
}
val matches = pairMatches && e.provider.value == "github-actions" &&
    e.repository == "vsrqg-synthetic/demo" && e.pipeline == "synthetic-m2" &&
    e.buildAttempt == 1 &&
    e.workflowReference == "vsrqg-synthetic/demo/.github/workflows/demo.yml@synthetic" &&
    e.proofReference == "https://github.com/vsrqg-synthetic/demo/actions/runs/${e.buildId}/attempts/1" &&
    e.artifactSha256s == listOf(payloadSha256) &&
    e.proofDigest == provenance.recomputedProofDigest
return ProvenanceValidation(
    if (matches) VerificationStatus.VALID else VerificationStatus.INVALID,
    Confidence.LOW, "m2-demo-fixture-provenance/v1",
    if (matches) "SYNTHETIC_FIXTURE_MATCHED" else "SYNTHETIC_FIXTURE_MISMATCH",
)
```

这里 e=provenance.normalized；为匹配及每项常量不匹配、摘要不符分别断言 VALID/INVALID、LOW 和独立 version。输入的 project/Snapshot/Artifact authority 仍由既有应用检查。BuildProvenanceTransaction 会保留 INVALID 事实，不能误测为 ingestion 422；Traceability 拒绝 INVALID 输入才是既有边界。

- [x] 在 M2 启动器注册专用 primary validator/descriptor bean 和 factory，保持默认 registry 与 canonicalizer；无 component 注解，不能进入 main 扫描或生产包。M2 开启既有 flags/Workers。使用既有参数化 Bootstrap 事务新增 Engineer、Service、assignment 与 Source（credential_reference=NULL）；激活前不提交 Sync。原 Manager/Viewer 不变。
- [x] 目标单测 GREEN、`compileDemoKotlin bootJar` 与 packaging 检查；M1 模式无 M2 factory/validator，生产 JAR 无新增 demo 类，原 JWT 错误签名/issuer/audience/过期负向测试仍通过。
- [x] 审查 diff，提交双语代码与任务记录（`docs/m2/2026-09-08-synthetic-demo-inputs.md`）；非 Markdown 同步，Pair Gate 后推送。记录实际检查结果，不宣称集成闭环已完成。

## 任务 2：真实串联、单入口和结果

**Files:**

- 新增 `backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/M2DemoScenario.kt`、`M2DemoReport.kt`。
- 修改 `backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/M1DemoMain.kt`：调用 M1 后调用 M2，综合退出结果。
- 修改 `scripts/demo/run-m1.ps1`，`scripts/tests/m1-demo.tests.ps1`，`scripts/tests/m1-demo-ci.tests.ps1`，`scripts/tests/fixtures/m1-demo-command.ps1`。
- 新增 `backend/src/test/kotlin/com/ricezhou/vsrqg/demo/M2DemoIntegrationTest.kt`、`M2DemoReportTest.kt`。
- 修改 `.github/workflows/m1-backend.yml`，`docs/m1/demo-runbook.md`；新增 `docs/m2/synthetic-demo-runbook.md`。

**Interfaces:**

新增 `M2DemoScenario.run(baseUri: URI, managerToken: String, engineerToken: String, serviceToken: String, sourceId: String, m1: DemoResult, payloadSha256: String): Unit`；构造注入 M2DemoReport。Report 消费实际 HTTP 投影并写 m2-summary.json；不从期望值构造业务结果。沿用 DemoResult 和同次 M1 的 DemoReport.payloadSha256。单入口使用 `-IncludeM2` → 严格布尔环境值 `VSRQG_DEMO_INCLUDE_M2`，不新增另一套 Gradle/Compose 启动脚本。

- [ ] 编写真实 PostgreSQL 集成测试，调用启动器和两个 scenario；仅基础身份/Source 用 Bootstrap，禁止调用现有 Traceability test seeder。测试固定断言如下（a、aAgain、b 是真实 HTTP bytes，使用既有 Jackson mapper）：

```kotlin
assertThat(aAgain).isEqualTo(a)
val first = mapper.readTree(a).path("issues").associateBy { it.path("sourceIssueId").asText() }
check(first.getValue("DEMO-1").path("fixed").asBoolean())
check(first.getValue("DEMO-1").path("included").asBoolean())
check(!first.getValue("DEMO-2").path("included").asBoolean())
check(first.values.none { it.path("verified").asBoolean() })
check(mapper.readTree(b).path("issues").all { it.path("included").asBoolean() })
check(mapper.readTree(b).path("issues").none { it.path("verified").asBoolean() })
```

追加四边/空路径、精确 Gap code、A/B ID 不同、latest=B、A contentDigest 不变、same-key ingestion/verify bytes 相同断言。USER 携 scope ingestion 应 403；错误事实场景用独立 Project，ingestion 保留 INVALID 后 verify 为 422 TRACEABILITY_INPUT_NOT_VALID，不能污染成功场景。轮询超时、FAILED Run、非法输出字段必须导致总失败。

- [ ] 执行 `./gradlew test --tests '*M2DemoIntegrationTest' --tests '*M2DemoReportTest'`，记录 RED。无 Docker 不执行伪造的集成 RED/GREEN，先跑可执行报告单测，在现有 CI 补足。
- [ ] 按 TDR 流程表逐次调用现有 HTTP。proofDigest 由现有 canonicalizer 计算：先用合法占位 sha256 格式构造 envelope，再 copy(proofDigest=recomputedProofDigest)；HTTP body 使用 project 字段和 GITHUB_ACTIONS 枚举。不调用外网。两次 Build 分别绑定实际 Issue Snapshot 与样例 SHA。轮询使用返回的 statusUrl，校验同源；最多 30 秒，每次 5 秒 timeout，每 250 ms 查询。
- [ ] Report 只提取 TDR allowlist，按实际响应打印两条 Issue 的路径/Gap 和历史比较；请求原文仅在内存使用。M1 后失败必须输出整体 FAILED 和非零码，即使其 summary.json 是 PASS。新增 report 单测拒绝缺字段、未知状态、意外 Verified=true 和未完成场景；输出无 Token/locator/连接信息。
- [ ] 入口增加 IncludeM2 参数和明确说明；默认 M1 fixture 合同维持 20 项回归。CI harness 增加 M2 同 volume 连跑两次（唯一 runId/Project），检查三份输出、历史稳定、服务所有权和 volume 保留。复用已有 PostgreSQL/Compose，不安装软件，不 down/delete/recreate。
- [ ] 执行目标测试、shell tests、编译/bootJar、现有 M1/M2 回归及双语 Pair Gate。现有 workflow 上传 m2-summary.json；验收包绑定 exact Subject、CI Run、实际测试计数、失败/跳过与 Artifact 到期，不把文档提交当实施 Subject。
- [ ] diff 审查后双语提交推送；新增 `docs/m2/2026-09-08-synthetic-demo-walkthrough.md` 记录实际结果和限制。实施完成且证据齐全后再创建 Owner 待验收记录，不预填 APPROVE。

## 自检与交付状态

任务 1 覆盖 Source、Mapping、合成 Build validation、身份与包装；任务 2 覆盖真实 HTTP/Worker、完整链、缺边、后续事实、历史、负向、输出与复用。已核对 ingestion 返回 200、Snapshot selectedCount、Manager 与 Engineer 权限差异、INVALID 事实持久化行为，避免以错误假设写测试。方案自检不代表测试已运行。

当前结果：任务 1 实施、独立评审及双语 CI 验证完成，见[工程记录](../../m2/2026-09-08-synthetic-demo-inputs.md)。Git 状态：双语实施与证据记录已版本化。下一步动作：执行任务 2 的真实 HTTP 串联、单命令入口与结果展示。前置条件：下一步执行指令；完整运行复用现有容器环境/CI。验收目标：完整链、缺边链、补充新事实后的历史稳定性、Verified=false 和失败非零退出均有实际结果；不替 Owner 验收。
