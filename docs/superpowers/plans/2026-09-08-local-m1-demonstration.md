# 最小本地 M1 演示实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让实际合成文件通过真实 HTTP 完成 Release → Manifest 校验 → Lock → 导出。

**Architecture:** 复用同一 Backend、JWT/RBAC、PostgreSQL 和 Manifest 持久化事务。demo source set 只负责临时身份、初始化和 HTTP 演示；main 只增加可替换的文件校验接口。

**Tech Stack:** 现有 Kotlin/JVM 21、Spring Boot/Security/Nimbus、PostgreSQL 17.11、Gradle、PowerShell、JUnit、Testcontainers；不新增服务或库。

**Spec:** [TDR-021](../../v0.2/tdr/TDR-021-local-m1-demonstration.md)，Accepted（任务 1 范围）；Owner 已在方案交付后指示执行下一步，当前仅实施任务 1。

## 全局约束

- 不改冻结语义、API/Schema、Migration、既有历史或 Owner 状态；不启动 M2 串联、M3/M4 或前端。
- 普通 Backend 保持 INCOMPLETE；demo 才配置 m1-local-payload/1、PILOT、NONE、loopback 和专用 vsrqg_demo。
- 文件上限 1 MiB、每次 16 个；空/非法摘要拒绝。缺失或读取不可用为 INCOMPLETE，摘要不符/越界/超限为 FAILED，全部文件实际匹配才 VALID；FAILED 优先于 INCOMPLETE。保留固定 violation 与精确数组位置。
- 单元测试默认 60 秒；端到端必须真实 TCP HTTP、JWT 签名和 PostgreSQL，不注入测试结论。每项完成后双语提交、Pair Gate、推送并核对 CI；最终验收由 Owner 决定。

## 任务 1：接入实际文件校验

**Files:** 新建 backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/application/ArtifactPayloadVerifier.kt、manifest/adapter/LocalArtifactPayloadVerifier.kt、manifest/adapter/ArtifactPayloadVerificationConfiguration.kt；修改同一根目录下 manifest/application/ValidateManifest.kt；新增 backend/src/test/kotlin/com/ricezhou/vsrqg/manifest/ArtifactPayloadVerifierTest.kt。Local 实现是普通类，不自动绑定为生产 Bean。

**Interfaces:** 本任务定义下面的唯一接口；任务 2 直接构造 LocalArtifactPayloadVerifier(root: Path)。默认配置仅在没有显式 verifier Bean 时提供缺失能力实现，返回原 validator version；Local 结果使用 m1-local-payload/1。

```kotlin
interface ArtifactPayloadVerifier {
    fun verify(sha256Values: List<String>): PayloadVerification
}
data class PayloadVerification(
    val status: ValidationStatus,
    val violations: List<ManifestViolation>,
    val validatorVersion: String,
)
```

- [x] 写 RED：临时目录保存内容为 UTF-8 demo 的摘要命名文件，再改为其他字节，文件名保持不变；断言如下，并覆盖缺失、空列表、非法摘要、符号链接、超限和默认实现。

```kotlin
val bytes = "demo".toByteArray()
val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }
Files.write(root.resolve(sha), bytes)
val verifier = LocalArtifactPayloadVerifier(root)
assertThat(verifier.verify(listOf(sha)).status).isEqualTo(ValidationStatus.VALID)
Files.writeString(root.resolve(sha), "changed")
assertThat(verifier.verify(listOf(sha)).status).isEqualTo(ValidationStatus.FAILED)
```

- [x] 执行目标测试确认缺失接口/行为导致 RED：`./backend/gradlew -p backend test --tests '*ArtifactPayloadVerifierTest'`。Windows 使用 gradlew.bat。
- [x] 用有界流读取文件并重算摘要；禁止路径输入。将 verifier 注入 ValidateManifest，仅在原 failures 为空时调用；将状态、violations 和版本写入现有 ValidationReport。默认配置维持原 INCOMPLETE；不改 LockManifest、RegisterManifest 事务或 validate API 的历史读取语义。
- [ ] 执行目标测试和已有 Manifest/Lock 测试，证明原普通 profile 行为与历史摘要不变；真实 PostgreSQL 测试在具备容器环境时执行，初始化失败不能计为 PASS。
- [ ] 审查 diff、同步双语并提交：`feat(manifest): verify local demonstration payload bytes`。

## 任务 2：独立演示启动器与真实鉴权

**Files:** 修改 backend/build.gradle.kts；新建 backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/M1DemoMain.kt、M1DemoIdentity.kt、M1DemoBootstrap.kt、M1DemoScenario.kt；新增 backend/src/test/kotlin/com/ricezhou/vsrqg/demo/M1DemoIntegrationTest.kt、M1DemoPackagingTest.kt。不复制生产 SecurityConfig 或 Permission。

**Interfaces:** M1DemoMain 提供 m1Demo 入口，显式导入原 VsrqgApplication 和 demo 配置；M1DemoIdentity 提供内存签名器与真实 JwtDecoder Bean；M1DemoBootstrap 只初始化三张身份/项目表。M1DemoScenario.run(baseUri: URI, managerToken: String, viewerToken: String): DemoResult 调用现有 API；DemoResult 包含 runId、releaseId、manifestId、contentDigest、scenarioStatuses，输出格式在任务 3 固定。Token 不进入 DemoResult。

```kotlin
data class DemoResult(
    val runId: String,
    val releaseId: String,
    val manifestId: String,
    val contentDigest: String,
    val scenarioStatuses: Map<String, String>,
)
```

- [ ] 写 RED：使用 Testcontainers 专用 vsrqg_demo，真实端口与 HttpClient；无 Authorization 的 POST 必须 401，带写 scope 的 VIEWER 必须 403，错误签名/过期/issuer/audience 拒绝。不得使用 MockMvc jwt。
- [ ] 执行 `./backend/gradlew -p backend test --tests '*M1DemoIntegrationTest' --tests '*M1DemoPackagingTest'`，确认新入口与隔离行为缺失。
- [ ] 配置 demo source set 复用 main 输出/依赖，测试 classpath 加入 demo output；m1Demo 使用 demo runtimeClasspath。生产 bootJar 不依赖 demo；打包测试枚举 JAR entries，拒绝 com/ricezhou/vsrqg/demo/。使用既有 toolchain 21。
- [ ] 显式注册临时 decoder 和 Local verifier，再启动同一应用。强制 loopback/PILOT/NONE/专用库和 Worker 关闭；JWT 按 TDR 验证全部声明。初始化参数化 INSERT，仅写 project/principal/project_assignment；每次独立 ID，发生冲突直接失败。不得把演示开关加入 production application.yml。
- [ ] 通过真实 HTTP 创建 Release、动态构建 CONFIG Manifest、注册、validate、Lock、export。损坏文件必须在注册前修改工作副本，使用不同 Release；重复 key 检查原响应一致，不修改既有 validation。finally 关闭自身 context，不停止外部数据库。
- [ ] GREEN 后执行 `./backend/gradlew -p backend bootJar` 并检查包内容及默认 profile 回归；双语提交：`feat(demo): run isolated m1 http demonstration`。

## 任务 3：单命令入口、结果与完整验证

**Files:** 新建 scripts/demo/run-m1.ps1、scripts/tests/m1-demo.tests.ps1、demo/m1/sample-config.txt、docs/m1/demo-runbook.md；扩展 M1DemoScenario.kt 的结果输出；修改 .github/workflows/m1-backend.yml，在现有门禁后运行演示目标并上传脱敏结果。不改现有 deploy/dev/compose.yml。

**Interfaces:** run-m1.ps1 使用独立 Compose project、55432 端口和 vsrqg_demo；调用 `./backend/gradlew -p backend m1Demo`。首次新 volume 的口令由子进程环境提供；复用 volume 必须提供匹配口令，失败不删除 volume 或自动换口令。本次启动的服务才允许停止；结果位于 backend/build/demo/m1/<runId>/。

- [ ] 写脚本 RED：缺依赖、数据库连接失败、子进程非零、已有服务不被停止、口令不匹配不删除数据；不安装软件，不输出子进程环境或秘密。
- [ ] 执行 `pwsh -NoProfile -File scripts/tests/m1-demo.tests.ps1`，确认缺入口/行为失败。
- [ ] 保存固定合成文件，按 TDR 复制摘要命名工作副本；正常和损坏流程分离，不修改 Git 样例。summary 使用显式字段构建，禁止完整异常/HTTP headers/Token/原始身份；失败报告仍非零退出。

```json
{
  "classification": "SYNTHETIC_DEMO",
  "status": "PASS",
  "scenarioStatuses": {
    "validFileLockExport": "PASS",
    "corruptFileRejected": "PASS",
    "unauthenticatedRejected": "PASS",
    "viewerWriteRejected": "PASS",
    "idempotentReplay": "PASS"
  }
}
```

- [ ] 上例仅展示状态字段；实际输出必须附 runId、代码提交、真实 Release/Manifest ID、摘要和 HTTP 状态。值由执行生成，不能把样例 PASS 直接复制为结果。任一场景失败总状态 FAILED。
- [ ] 在可用 PostgreSQL/JDK 环境运行 `pwsh -NoProfile -File scripts/demo/run-m1.ps1`；对照 TDR 验证全矩阵，确认受控停止后 volume/报告保留。现有 CI 执行真实场景，不能依赖本机未验证状态。
- [ ] 写清启动/复用口令/结果查看/保留与停止步骤，区分 M1 合成演示与未实现 M2 串联及真实设备能力。执行原 M1/M2 回归、契约、验收记录和 Pair Gate；提交 `feat(demo): package reproducible m1 walkthrough`。

## 自检与执行交接

覆盖关系：文件校验 P0→任务 1；启动/身份 P0→任务 2/3；真实演示验证与可读输出→任务 2/3。接口名、返回值、默认行为和输出边界一致；本计划没有数据库状态旁路或新的 Company 前置条件。任务 1 已实现并完成本地单测与打包，数据库回归待 CI；任务 2/3 保持未勾选。

下一步：完成任务 1 固定提交的 CI 核查。前置条件：远端运行完成。验收目标：M1/M2、Linux 文件校验及 PostgreSQL 回归通过后更新[实施记录](../../m1/2026-09-08-local-payload-verification.md)，再进入任务 2；不代替 Owner 验收。
