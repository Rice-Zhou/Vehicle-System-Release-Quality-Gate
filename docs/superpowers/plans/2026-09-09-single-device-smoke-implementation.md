# 单设备 Smoke 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用一台明确指定的 Android 设备执行新演示 APK 的安装/启动 Case，形成正式 Run/Result 与可复验的 LOG/SCREENSHOT。

**Architecture:** APK、主机 Agent、Backend Test Management 和 Evidence 按既有边界分离。先交付可独立构建的 APK，再按身份→Run→Evidence→Result→Agent→串联的依赖顺序实施；本文是主计划，各 Task 都有独立测试和评审出口，不跨任务预写成功结果。

**Tech Stack:** Backend Kotlin 2.2.21 / Spring Boot 3.5.16 / PostgreSQL / JVM 21；Agent Kotlin/JVM 21、JDK HTTP Client、Jackson/JCS；APK Java、AGP 8.7.3、Gradle 8.9、JDK 17、SDK 35。Agent 通过 version catalog 引用 Backend 的 Kotlin/BOM 版本，不依赖 Spring runtime。

**Spec:** [已确认设计](../specs/2026-09-09-single-device-smoke-design.md)，固定设计 Subject 7a64d710b00c70dde3ff596c293d0fc9f3082c44 / e6f3f59b8705af9a92e6e8098cf6b6adee8863d2；[Owner 记录](../../governance/acceptance/records/2026-09-09-m3-smoke-design-review-001.md)。确认范围为设计与规划，不是本计划已经执行或 M3 验收。

## Global Constraints

- APK 包名 `com.ricezhou.vsrqg.smoke`，Activity `.SmokeActivity`；minSdk 26、compileSdk/targetSdk 35、Build Tools 34.0.0；normal / assertion-failure 两种固定 mode，UUID Attempt 标记。
- 固定 Plan/Case：single-device-smoke / apk-launch-smoke；v1 正常、v2 明示断言失败；一个 required Case、最大 Attempt 数为 1，不自动重试安装。
- 心跳 20 秒、poll 等待 20 秒、lease 90 秒；分配 60 秒、Case/Command 300 秒、Run 600 秒、恢复窗口 120 秒；恢复不能越过 Case/Run deadline，使用 Server 时间。
- LOG、UI XML 输入上限 1 MiB；SCREENSHOT PNG 上限 8 MiB；上传 Session 5 分钟有效且受当前租约/终态约束。所有进程与 HTTP 操作有期限和大小限制。
- Agent mTLS 与用户 JWT 严格分离；复用 principal/project_assignment/Permission，不复制角色权限源。原始序列号、私钥、token、Payload 路径不入日志或 Git。
- 复用 M1 真实文件校验，不改变普通 Backend 默认 INCOMPLETE。Evidence 使用 TDR-025 的演示专用目录，不启用 S3/Company，不把 GitHub 当运行时 Evidence API。
- 保留 Run/Attempt/Result 完成契约、跨 Release/Run FK、终态历史、Verified=false。不得把任务完成、单 Case PASS 或夹具运行当作 Release PASS/完整 M3。
- 后端单测默认 `@Timeout(60)`；真实设备 Case 的 300 秒为业务期限。每项验证检查退出码，失败可见；禁止未跑即标 PASS。
- 复用现有中英文 worktree，Markdown 翻译、非 Markdown 字节相同；每 Task 独立评审、配对提交/推送。不得 merge、Tag、部署、自动选择设备、卸载、清数据、刷机、断电或重启设备。

## 执行规则与接口文件

执行前读取 AGENTS.md、设计、TDR-024/025 与本计划，核对当前 HEAD/工作区；未提交用户修改原样保留。Task 1 可独立运行；2→3→4→5→6→7 有序执行，7 同时消费 1。新增实现默认关闭，通过显式演示配置启用；缺少依赖明确拒绝请求，不安装“返回成功”的临时适配器。每项下述文件集同时包含其测试、配置与文档；无关模块不重构。

跨模块只有以下应用端口；完整定义由指定任务产生，消费者不得自行换名或另造第二份规则。JSON 只用于经 Schema 验证的协议边界和已固定的上下文，领域状态使用显式枚举。

```kotlin
// Task 2: testmanagement/application/AgentAccess.kt
data class AgentActor(val principalId: String, val projectId: String,
    val agentId: String, val deviceId: String)
interface AgentAccess {
    fun requireAgent(certificateSha256: String, scope: String): AgentActor
}
// Task 3: testmanagement/application/AttemptAccess.kt
data class AttemptBinding(val attemptId: String, val runId: String,
    val releaseId: String, val projectId: String, val agentId: String,
    val deviceId: String, val leaseId: String, val fencingToken: Long)
interface AttemptAccess {
    // Caller transaction retains the attempt lock through the dependent write.
    fun lockWritable(actor: AgentActor, attemptId: String, now: Instant): AttemptBinding
    fun context(actor: AgentActor, attemptId: String): JsonNode
}
// Task 4: evidence/application/AttemptEvidence.kt
data class EvidenceResolution(val availableIds: Set<String>,
    val failedRequiredTypes: Set<String>)
interface AttemptEvidence {
    fun resolve(binding: AttemptBinding, evidenceIds: Set<String>): EvidenceResolution
    fun seal(binding: AttemptBinding, now: Instant)
}
```

`Instant` 为 java.time.Instant，`JsonNode` 为 Jackson JsonNode。Agent 不依赖这些 Backend JVM 类型；它只消费同一组机器契约与 golden JSON。单一 `schemas/v0.2/agent-execution-context.schema.json` 定义上下文：schemaVersion=1.0、attemptId、commandId、projectId、releaseId、manifestId、manifestDigest、deviceId、environment{bootSessionId,buildId,buildFingerprint}、apk{checksum,packageName,versionCode,signingCertificateSha256}、plan{planId,version}、case{caseId,version,mode,timeoutMs,requiredEvidence}。均必填、additionalProperties=false，摘要使用 sha256: 前缀，技术 ID 继承既有长度约束，APK 与 Case 常量限于本设计。

## Task 1: 可构建的最小演示 APK

**Files:** Create `demo/android-smoke/settings.gradle.kts`, `build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/ricezhou/vsrqg/smoke/SmokeMarker.java`, `SmokeActivity.java`, `app/src/test/java/com/ricezhou/vsrqg/smoke/SmokeMarkerTest.java`, `README.md`。Task 1 不接触 Backend 或设备。

**Interfaces:** `SmokeMarker.render(String attemptId, String mode): String`；APK 输出 `app/build/outputs/apk/debug/app-debug.apk`。Activity extras 固定为 attemptId、mode，无网络权限、无服务。标准 UUID 字符串必须完整匹配，不接受 UUID.fromString 的缩写形式。

- [ ] **Step 1:** 检查 JDK 17、SDK platform 35/Build Tools 34.0.0；不可用则明确说明缺项，不执行设备命令。创建 AGP 8.7.3/Gradle 8.9 独立构建，Wrapper JAR/分发校验从官方生成流程取得并固定，不复制未知二进制；unit test 使用 JUnit 4.13.2。先写下列测试，不创建生产类。

```java
@Test public void modesAndInvalidInputRemainDistinct() {
    String id = "01990000-0000-7000-8000-000000000001";
    assertEquals("VSRQG_SMOKE_READY:" + id, SmokeMarker.render(id, "normal"));
    assertEquals("VSRQG_SMOKE_NOT_READY:" + id,
        SmokeMarker.render(id, "assertion-failure"));
    assertThrows(IllegalArgumentException.class,
        () -> SmokeMarker.render("1-1-1-1-1", "normal"));
    assertThrows(IllegalArgumentException.class,
        () -> SmokeMarker.render(id, "anything"));
}
```

- [ ] **Step 2:** 从 `demo/android-smoke` 运行 `./gradlew testDebugUnitTest`（Windows 用 gradlew.bat）；应因 SmokeMarker 未定义而 RED。工具链失败单独记录，不能当作该回归的 RED。
- [ ] **Step 3:** 实现单一输入函数，再让 Activity 调用它；Activity 无效输入显示固定 `SMOKE_INPUT_INVALID` 并结束，不出现 READY。使用平台 TextView 显示完整标记和 SYNTHETIC_DEMO，onNewIntent 重新验证，屏幕方向/重建恢复同次合法参数。

```java
public static String render(String id, String mode) {
    if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
        throw new IllegalArgumentException("SMOKE_INPUT_INVALID");
    if ("normal".equals(mode)) return "VSRQG_SMOKE_READY:" + id;
    if ("assertion-failure".equals(mode)) return "VSRQG_SMOKE_NOT_READY:" + id;
    throw new IllegalArgumentException("SMOKE_INPUT_INVALID");
}
```

- [ ] **Step 4:** 运行 testDebugUnitTest、lintDebug、assembleDebug，检查 Manifest 只有目标 Activity、没有权限/服务/额外组件；用 Build Tools apksigner 验证签名并保存证书摘要、APK SHA-256/size/版本。私钥和 local.properties 不入 Git；实际 UI 行为由 Task 7 真机验证，不能以 unit test 代替。
- [ ] **Step 5:** 补齐该目录双语 README（工具链、构建、输入/输出、未运行设备的边界），检查 diff、Pair Gate，配对提交 `feat(demo): add minimal Android smoke APK` 并推送。

## Task 2: Agent 身份、注册与上下文机器契约

**Files:** Create `backend/src/main/resources/db/migration/V12__agent_device_identity.sql`; `backend/src/main/kotlin/com/ricezhou/vsrqg/testmanagement/application/AgentAccess.kt`, `RegisterAgent.kt`; `backend/src/main/kotlin/com/ricezhou/vsrqg/testmanagement/adapter/AgentSecurityConfiguration.kt`, `JdbcAgentAccess.kt`, `AgentRegistrationController.kt`; `backend/src/test/kotlin/com/ricezhou/vsrqg/testmanagement/AgentIdentityIntegrationTest.kt`, `AgentTlsIntegrationTest.kt`; `schemas/v0.2/agent-execution-context.schema.json`; `contracts/examples/v0.2/agent/execution-context.json`, `invalid-execution-context.json`。Modify `access/domain/Permission.kt`, `access/adapter/SecurityConfig.kt`, `contracts/openapi/v0.2/openapi.json`, `contracts/examples/v0.2/validation-cases.json`, `scripts/contract-validator.mjs`, `docs/v0.2/03-api-design.md`, `08-test-agent-protocol.md`, `09-evidence-design.md`；同一组省写的文件名继承该组首个文件的目录；后续 Task 中领域/应用/适配器路径以 backend/src/main/kotlin/com/ricezhou/vsrqg/ 为根，SQL 以 backend/src/main/resources/db/migration/ 为根，测试以 backend/src/test/kotlin/com/ricezhou/vsrqg/ 对应模块为根。

**Interfaces:** 产生 AgentAccess/AgentActor、注册响应 `{protocolVersion,agentId,heartbeatIntervalSeconds:20,leaseDurationSeconds:90}` 和上述上下文 Schema；Context 运行端点在 Task 3、Payload 运行端点在 Task 4 实现。更新 OpenAPI 的两条新路径及本地下载 Profile 描述，不能修改严格旧 Command Payload 或未经授权降低 HIGH 下载控制。

- [ ] **Step 1:** 在现有 PostgresIntegrationTest 模式中创建身份 fixture：project、SERVICE principal、ENGINEER assignment、指定 Device、预登记 Agent 及证书 DER SHA-256 绑定；绝不在 fixture 填入 Run/Result。先测试同证书重复注册同 agentId、证书换 Device/项目拒绝、disabled/revoked 拒绝、无共同协议 426。契约测试固定 context 缺字段和未知字段均拒绝。

```kotlin
@Test @Timeout(60)
fun `certificate request attribute cannot be replaced by a header`() {
    mockMvc.perform(post("/agent-api/v1/agents:register")
        .header("X-Client-Cert", "untrusted")
        .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized)
}
```

- [ ] **Step 2:** 运行 `backend/gradlew -p backend test --tests '*AgentIdentityIntegrationTest' --tests '*AgentTlsIntegrationTest'` 和 `node scripts/contract-validator.mjs`，记录预期 RED；不能把已有全部请求 401 当作成功注册功能已实现。
- [ ] **Step 3:** Migration 固定 agent→principal/project/device 关联和唯一 certificate fingerprint，复用现有 principal/project_assignment。Permission 单一目录增加 test:execute/test:read、Evidence 权限及 Agent scopes；用户执行为 ENGINEER/RELEASE_MANAGER/ADMINISTRATOR，读取为已有全部项目角色，敏感 Payload 只允许 QUALITY_OWNER/ADMINISTRATOR；Agent scopes 只允许已绑定 SERVICE 身份并在专用证书链校验。复用 ProjectAuthorizer 检查角色，不另建角色表。

```kotlin
// Configure this extractor in the ordered /agent-api/** X509 security chain.
class CertificateFingerprintExtractor : X509PrincipalExtractor {
    override fun extractPrincipal(certificate: X509Certificate): Any =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded))
}
// JdbcAgentAccess resolves binding, revocation and ProjectAuthorizer.require.
```

X509PrincipalExtractor 使用 Spring Security 的 x509 包接口；X509Certificate、HexFormat 与 MessageDigest 使用 JDK 类型。Agent chain 优先匹配 /agent-api/**、无状态且要求 authenticated，配置上述证书 DER 指纹提取器；不以 CN/DN 作为授权标识。用户链仍为 JWT，两个入口的凭证不能互换。TLS 终止在 Backend；client-auth=want 仅允许用户路由无客户端证书，Agent 路由必须有受信证书。拒绝把代理 Header 当证书。
- [ ] **Step 4:** 真正启动随机端口 HTTPS 测试服务，以测试专用临时 CA/客户端证书验证：受信/未受信/过期、错误项目、撤销、只有 JWT 的 Agent 请求及只有证书的用户请求。分别断言成功和明确拒绝，不只做 MockMvc 证书注入。运行受影响 access 单测与契约检查。
- [ ] **Step 5:** 文档明确两条新增端点及 TDR-025 演示例外、Agent 权限域和开发证书路径配置；校验并配对提交 `feat(agent): register certificate-bound device agents`，保存机器证据，推送。

## Task 3: Run、Attempt、调度与租约

**Files:** Create `V13__test_run_attempt_authority.sql`; `testmanagement/domain/TestStates.kt`, `LeaseWindow.kt`, `AttemptIds.kt`; `testmanagement/application/CreateTestRun.kt`, `AttemptAccess.kt`, `ClaimCommand.kt`, `AcknowledgeCommand.kt`, `HeartbeatAgent.kt`, `AdvanceTestDeadlines.kt`, `CancelTestRun.kt`; `testmanagement/adapter/JdbcTestRunRepository.kt`, `TestRunController.kt`, `AgentExecutionController.kt`, `TestDeadlineWorker.kt`; tests `TestRunIntegrationTest.kt`, `AgentLeaseIntegrationTest.kt`, `LeaseWindowTest.kt`。这些 Kotlin/SQL/test 相对路径沿用 Task 2 的 Backend 根目录。

**Interfaces:** 产生 AttemptAccess；实现 Create/Cancel Run、heartbeat/poll/ACK/context。`LeaseWindow.writable(now:Instant, expiresAt:Instant, supplied:Long, current:Long, terminal:Boolean):Boolean` 为唯一租约判定；结果提交与 Event 在 Task 5。V13 同时建立空的 Test Result 结构，供 deadline/cancel 及 Task 4 FK 使用，绝不预写成功记录。

- [ ] **Step 1:** 写到期边界的纯函数测试和并发集成场景：未 Lock/错误 Plan/能力不足拒绝、相同请求重放、同 Device 最多一个活动 Run、重复 poll/ACK 不多出 Command/Attempt、context 只给已分配 Agent。

```kotlin
@Test @Timeout(60)
fun `lease expiry is an exclusive boundary`() {
    val expiry = Instant.parse("2026-09-09T00:01:30Z")
    assertFalse(LeaseWindow.writable(expiry, expiry, 4, 4, false))
    assertTrue(LeaseWindow.writable(expiry.minusNanos(1), expiry, 4, 4, false))
    assertFalse(LeaseWindow.writable(expiry.minusSeconds(1), expiry, 3, 4, false))
    assertFalse(LeaseWindow.writable(expiry.minusSeconds(1), expiry, 4, 4, true))
}
```

- [ ] **Step 2:** 运行 `backend/gradlew -p backend test --tests '*TestRunIntegrationTest' --tests '*AgentLeaseIntegrationTest' --tests '*LeaseWindowTest'`，确认因目标行为缺失 RED。
- [ ] **Step 3:** 按既有 ER 建立不可变 Plan/Case Version、Environment、Run、Attempt、Command/Event、Result 和相应 FK/唯一键。Create Run 从 Locked Manifest 实际内容解析，限一 APK 与配置范围，拒绝其他 required Artifact；环境配置 bytes 与已验证 CONFIG checksum 一致，不能接收任意“环境已匹配”布尔值。将 Context 按 Schema 固化后计算 JCS 摘要，事务内保存 Run、Environment、Audit/Outbox。

```kotlin
fun writable(now: Instant, expiresAt: Instant,
    supplied: Long, current: Long, terminal: Boolean): Boolean =
    !terminal && supplied == current && now.isBefore(expiresAt)
// AttemptIds.fromGenerated consumes IdGenerator.nextId("att_") once.
fun fromGenerated(value: String): String {
    require(Regex("att_[0-9a-f]{32}").matches(value))
    val h = value.removePrefix("att_")
    return "${h.take(8)}-${h.substring(8,12)}-${h.substring(12,16)}-${h.substring(16,20)}-${h.substring(20)}"
}
```

Attempt 的标准 UUID 是唯一持久化/API 值；转换只复用现有 UUID v7 生成结果，不保留第二个 att_ 别名。其他新实体继续使用 IdGenerator 的现有前缀格式。全部引用、APK 标记和 Result 使用同一 Attempt UUID。
- [ ] **Step 4:** 调度用同事务行锁与部分唯一索引保证设备独占；poll 不在等待期间持有数据库事务，空返回 null，maxCommands>1 仍最多一个。固定顺序 Run→Attempt→Evidence 加锁，Heartbeat 只对当前代续租。使用 TimeProvider 推进测试时间，worker 通过 CAS/行锁可重复执行。deadline/cancel 在一次事务中 fence Attempt、写一个 Server terminal Result、Audit/Outbox、释放设备并封闭 Run；未开始时 startedAt 保留 null。恢复窗口到期为 TIMEOUT，不可恢复的身份/环境变化为 ERROR，不凭“执行也许成功”恢复安装。
- [ ] **Step 5:** 并发重复领取、Server 重启重建期限、当前/过期 lease、上下文跨项目、Run 取消与 timeout 的 Result 数量和历史检查通过；用户结果查询暂只返回已有事实，不虚构完成。校验、配对提交 `feat(test): persist single-device runs and leases`，推送。

## Task 4: 本地 Evidence 上传、下载与恢复

**Files:** Create `V14__local_evidence_sessions.sql`; `evidence/domain/EvidenceState.kt`; `evidence/application/AttemptEvidence.kt`, `EvidenceUploadService.kt`, `EvidenceDownloadService.kt`, `EvidenceReconciler.kt`; `evidence/adapter/ControlledPayloadStore.kt`, `JdbcEvidenceRepository.kt`, `EvidenceUploadController.kt`, `EvidenceQueryController.kt`, `LocalEvidenceConfiguration.kt`; tests `EvidenceUploadIntegrationTest.kt`, `ControlledPayloadStoreTest.kt`, `EvidenceDownloadIntegrationTest.kt`, `EvidenceRecoveryIntegrationTest.kt`；根目录规则同 Task 2。Modify `docs/v0.2/09-evidence-design.md`, `03-api-design.md`, OpenAPI 本地下载 Profile 表述。

**Interfaces:** 实现 AttemptEvidence。`ControlledPayloadStore.write(sessionId:String, input:InputStream, limit:Long):StoredPayload`；`StoredPayload(size:Long, sha256:String)` 不公开路径。`verify(sessionId:String, expected:StoredPayload):StoredPayload`；读写均只接受服务端生成 ID；在同一文件定义 `class PayloadLimitExceeded : RuntimeException("PAYLOAD_LIMIT_EXCEEDED")`。Upload Create 返回 `{uploadId,evidenceId,uploadUrl,expiresAt}`，URL 指向同 Backend mTLS PUT，不是 Bearer/S3 URL；Complete 返回固定 Evidence Metadata。

- [ ] **Step 1:** 写流式大小边界和真实文件保全测试；集成测试创建真实 Run/Attempt fixture 后走 API，不直接填 AVAILABLE。测试矩阵包含跨 Agent/Run、错误 size/hash/mediaType、空/超限、同 bytes 重传、不同 bytes 冲突、租约过期、取消与 Complete 竞态。

```kotlin
@Test @Timeout(60)
fun `an oversized stream never replaces a completed payload`() {
    val id = "upload_test_01"
    val store = ControlledPayloadStore(tempDir)
    val first = store.write(id, ByteArrayInputStream(byteArrayOf(1)), 1)
    assertThrows<PayloadLimitExceeded> {
        store.write(id, ByteArrayInputStream(byteArrayOf(1, 2)), 1)
    }
    assertEquals(first, store.verify(id, first))
}
```

- [ ] **Step 2:** 运行 `backend/gradlew -p backend test --tests '*Evidence*IntegrationTest' --tests '*ControlledPayloadStoreTest'`，确认 RED，不以权限全部拒绝代替正向完整链。
- [ ] **Step 3:** 实现原上传状态机与 composite FK；sessionId 验证、根目录在仓库/静态目录之外、拒绝路径穿越/链接/特殊文件。流式写独占临时文件，不用 unbounded readAllBytes；断流保留 Session 可重试、不会生成 AVAILABLE。完整文件 size/hash/type 校验后才固化；重传先比较，不覆盖不同内容。Complete 持有 Attempt 锁直到 Metadata/Audit/Outbox 提交，以排除终态竞态。

```kotlin
val digest = MessageDigest.getInstance("SHA-256")
// input/limit are write parameters; output is the exclusively opened owned temporary file stream.
var size = 0L
val buffer = ByteArray(64 * 1024)
while (true) {
    val count = input.read(buffer)
    if (count == -1) break
    size += count
    if (size > limit) throw PayloadLimitExceeded()
    digest.update(buffer, 0, count)
    output.write(buffer, 0, count)
}
```

LOG 严格 UTF-8/text/plain，PNG 校验固定签名与 image/png；临时文件和固定 Payload 均由服务端生成路径。文件已固化而 DB 失败保留孤儿，同 Session 重试校验原 bytes 后完成，不伪装成分布式事务。Metadata 存在但文件缺失/损坏时公开 integrity 状态；对已封闭 Run 只追加完整性观察/诊断，原 Result/Evidence 事实与摘要不改写。
- [ ] **Step 4:** 新增 `evidence_download_grant` 作为短期下载申请记录（不是第二角色权限源），绑定 actor/project/evidence/purpose、60 秒 expiry。POST :download 的同 key 重放仅在有效期内；过期同 key 明确拒绝并要求新 key，URL 是 Backend 受鉴权路径带不透明 grant ID。GET 每次同时检查当前主体、项目权限、grant owner/purpose/expiry、retention/legal hold；HIGH 额外要求 evidence:read:sensitive，复制 URL 给其他用户仍 403。更新 OpenAPI 为条件权限说明，保持 HIGH 原约束；no-store、开始前 Audit、禁止重定向/路径泄漏，拒绝 Range（本切片不实现分段下载）。
- [ ] **Step 5:** 下载正/负、Audit 写失败 fail closed、文件权限/链接、DB rollback 后重试、Metadata+Payload 成对备份恢复及逐个 hash 复验通过。对账不扫描任意目录，不自动删除无法归属的文件；输出固定 ID 与诊断。配对提交 `feat(evidence): store and verify bounded local payloads`，推送。

## Task 5: Event、Result 与 Run 完成契约

**Files:** Create `testmanagement/application/AppendCommandEvent.kt`, `SubmitAttemptResult.kt`, `GetTestRunResults.kt`; `testmanagement/domain/ResultCanonicalizer.kt`; `testmanagement/adapter/AgentResultController.kt`; tests `AttemptResultIntegrationTest.kt`, `TestRunCompletionIntegrationTest.kt`, `ResultCanonicalizerTest.kt`; `contracts/examples/v0.2/agent/result-canonical-input.json`, `result-canonical-expected.json`。Modify `JdbcTestRunRepository.kt`, `TestRunController.kt`, OpenAPI Results response 的具体字段定义。

**Interfaces:** ResultCanonicalizer 为 object，内部与测试各定义 `private val mapper = ObjectMapper()`；`ResultCanonicalizer.digest(request:JsonNode):String`；`SubmitAttemptResult.submit(actor:AgentActor, request:JsonNode, idempotencyKey:String, requestId:String):JsonNode`。消费 AttemptAccess 与 AttemptEvidence。返回结果查询 `{runId,releaseId,manifestId,manifestDigest,plan,environment,status,attempts:[{attemptId,status,result,evidenceRequirements}],inputDigest}`，明确运行状态和 Test Result 状态，禁止加入 Quality Result。

- [ ] **Step 1:** 固定 golden JSON（已验证 request 去掉 resultDigest，evidenceIds 升序/唯一），先写不同字段顺序/证据顺序摘要一致、内容变化摘要不同、无修改入参测试，再写重复/冲突/迟到 Event/Result 与 required Evidence 场景。

```kotlin
@Test @Timeout(60)
fun `digest ignores the supplied digest and preserves the request`() {
    val request = mapper.readTree("""{"attemptId":"a","evidenceIds":["b","a"],"resultDigest":"ignored"}""")
    val before = request.deepCopy<JsonNode>()
    val digest = ResultCanonicalizer.digest(request)
    val reordered = mapper.readTree("""{"evidenceIds":["a","b"],"attemptId":"a"}""")
    assertEquals(digest, ResultCanonicalizer.digest(reordered))
    assertEquals(before, request)
}
```

- [ ] **Step 2:** 运行 `backend/gradlew -p backend test --tests '*AttemptResultIntegrationTest' --tests '*TestRunCompletionIntegrationTest' --tests '*ResultCanonicalizerTest'`；记录 RED。canonical 单测只测函数，API 仍必须先按严格 resultRequest Schema 校验，不能因为上述最小函数样例放宽 API。
- [ ] **Step 3:** 复用已存在 JCS 库，对副本移除 resultDigest、规范化 evidenceIds 后计算 SHA-256；服务器重算并拒绝不同的提交摘要。同事务锁 Run→Attempt，校验证书绑定/fencing、Result 终态、Event sequence 和 Evidence 集合，写唯一 Result、Attempt 终态、Audit/Outbox；将 Session 封闭后再汇总 Run。

```kotlin
val canonicalInput = request.deepCopy<ObjectNode>().also { node ->
    node.remove("resultDigest")
    val ids = node.withArray("evidenceIds").map { it.textValue() }.toSortedSet()
    node.putArray("evidenceIds").also { array -> ids.forEach(array::add) }
}
val bytes = JsonCanonicalizer(mapper.writeValueAsBytes(canonicalInput)).encodedUTF8
val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
```

- [ ] **Step 4:** 相同终态 digest 在仍获授权的原 Agent 重试时返回旧确认，无新增副作用；不同摘要/过期代的新写入为 409 LATE_EVENT_CONFLICT/STALE_LEASE。封闭 Run 不接收新有效 Evidence；全部 Case 已 Resolution、Attempt 均终态、required Evidence AVAILABLE 或明确失败后才可 COMPLETED。Agent 报 ERROR/部分 Evidence 可保留，PASS 缺 required 拒绝；取消/期限场景必须唯一 Result，不遗漏 optional Case（通用完成判定按原契约测试，本切片只发布一个 required Case）。
- [ ] **Step 5:** 故障注入 Audit/Outbox/DB 失败后无半个终态、竞争 Complete/Cancel 无晚写、历史 digest/集合不变、同键跨主体不互读。验收记录只引用实际测试结果；配对提交 `feat(test): finalize attempts with verified evidence`，推送。

## Task 6: 主机 Agent、ADB 与两项 Collector

**Files:** Create `agent/settings.gradle.kts`, `build.gradle.kts`, Gradle wrapper files, `src/main/kotlin/com/ricezhou/vsrqg/agent/AgentMain.kt`, `AgentClient.kt`, `ExecutionJournal.kt`, `AgentLoop.kt`, `AdbExecutor.kt`, `SmokeAssertions.kt`, `CollectorPlugin.kt`, `LogCollector.kt`, `ScreenshotCollector.kt`, `ResultDigest.kt`; tests `AgentRecoveryTest.kt`, `AdbExecutorTest.kt`, `SmokeAssertionsTest.kt`, `AgentClientIntegrationTest.kt`, `ResultDigestContractTest.kt`。Wrapper 复用 Backend 已固定 Gradle 8.14.4；依赖版本导入其 catalog/BOM，JCS 与 Backend 相同。

**Interfaces:** `AgentClient.call(method:String,path:String,body:JsonNode?,key:String?):JsonNode`（仅固定路径/同源 HTTPS，禁重定向）；`AgentClient.putPayload(path:String,file:Path):Unit` 以有界二进制流上传，同样检查响应码与同源约束；`AdbExecutor.run(arguments:List<String>,timeout:Duration,stdoutLimit:Long):CommandOutput(exitCode:Int,stdout:ByteArray,stderr:ByteArray)`；`ExecutionJournal.load(attemptId:String):JournalEntry?`、`save(entry:JournalEntry)`。JournalEntry 字段 attemptId/commandId/leaseId/bootSessionId 为 String，fencingToken/lastSequence 为 Long，phase 为 Phase，evidenceIds 为 Set<String>，resultDigest 为 String?；phase 枚举 RECEIVED、ACKED、INSTALL_INTENT、INSTALLED、LAUNCH_INTENT、OBSERVED、UPLOADED、RESULT_ACKED；损坏记录明确错误，不返回“未执行”。

CollectorPlugin 复用设计 descriptor/start/mark/collect/stop/health；本切片 `collect` 输出本地 `EvidenceCandidate(type:String,mediaType:String,size:Long,checksum:String,capturedAt:Instant,collectorVersion:String,localFile:Path)`，localFile 只在主机内部，绝不进协议/摘要。无 Collector 质量阈值。

- [ ] **Step 1:** 写 UI 断言/输入恶意值/超限和 journal 恢复测试；用真实受限子进程验证超时、stderr、binary stdout，不用内存 mock 代替进程边界。测试 JVM HTTPS server 验证 mTLS、单源URL、上传重试和不能自动跟随重定向。

```kotlin
@Test fun `uncertain installation is never replayed`() {
    assertEquals(RecoveryAction.WAIT_FOR_DEADLINE,
        RecoveryPolicy.decide(Phase.INSTALL_INTENT, leaseValid = true, sameBoot = true))
    assertEquals(RecoveryAction.REPORT_ONLY,
        RecoveryPolicy.decide(Phase.UPLOADED, leaseValid = true, sameBoot = true))
    assertEquals(RecoveryAction.DIAGNOSTICS_ONLY,
        RecoveryPolicy.decide(Phase.UPLOADED, leaseValid = false, sameBoot = true))
}
```

`RecoveryPolicy.decide(phase:Phase,leaseValid:Boolean,sameBoot:Boolean):RecoveryAction`、Phase 和 RecoveryAction 放在 AgentLoop.kt；RecoveryAction 枚举 START、WAIT_FOR_DEADLINE、REPORT_ONLY、DIAGNOSTICS_ONLY、DONE，RESULT_ACKED 返回 DONE，INSTALLED 只恢复尚未写 LAUNCH_INTENT 的启动阶段，确切规则：过期代或 boot 改变只诊断；INSTALL_INTENT/LAUNCH_INTENT 等不确定阶段等待期限、不重放；OBSERVED/UPLOADED 只继续已有数据上传/报告；RECEIVED/ACKED 且无动作意图才可开始动作。与 Server deadline 状态配合，不自行恢复新 lease。
- [ ] **Step 2:** 从 agent 运行 `./gradlew test` 确认 RED；API 客户端校验使用 Task 2 Schema 与 Task 5 golden JSON 同一仓库源，通过 Gradle resources 显式纳入，不手工维护另一套 wire 字段。
- [ ] **Step 3:** 主机 CLI 必填 server、受控证书/信任配置引用、显式 Device 引用与 ADB selector 配置文件、APK、spool；凭据文件不在日志/命令行展开内容。首台设备选择、未知参数、非 HTTPS、缺证书、符号链接配置/输出明确拒绝。ProcessBuilder 参数列表传值，外部命令结果检查 exit code；读取限制触发后结束本次进程并报错，不裁剪为成功日志。

```kotlin
val process = ProcessBuilder(adbExecutable.toString(), "-s", selectedDevice,
    "shell", "am", "start", "-W", "-n", "com.ricezhou.vsrqg.smoke/.SmokeActivity",
    "--es", "attemptId", validatedAttemptId, "--es", "mode", validatedMode).start()
// Consume stdout/stderr concurrently with separate limits before bounded waitFor.
// On timeout terminate only this owned process; never adb kill-server.
```

- [ ] **Step 4:** 从 Context 校验 APK bytes/签名/版本，检查 boot/build/fingerprint；已有包签名不同不卸载。安装后定向读取唯一 base APK 并复验，split/不可读明确 BLOCKED。使用固定组件前台检查、uiautomator 仅写本次 `/data/local/tmp/vsrqg-smoke-<uuid>.xml` 后读取（不能接受外部路径），禁外部实体且限 1 MiB；精确匹配当前 READY 文本。只对该测试包定向 logcat，不清空整机日志；截图以 binary exec-out 读取。临时设备文件仅按完整 UUID 验证后清理由本次创建的那个文件，无递归删除。所有步骤与 Collector 结果保持相同 Attempt。
- [ ] **Step 5:** journal 使用独占锁、临时文件+原子替换并持久化，再 ACK/执行；同主机只一个 Agent 操作此设备。Heartbeat 独立于最长 300 秒 Case，Server 租约无效停止副作用；网络/断连/超时/不确定阶段保留 spool。上传和 Result 都确认前不删 required 数据；重复运行进程不得重装已处于不确定阶段的 APK。
- [ ] **Step 6:** 运行 Agent 全部测试与 compile/build、共用 canonical vectors、真实子进程负例；后台代码不输出质量阈值或敏感原始内容，单机恢复与 corrupted journal 可见。配对提交 `feat(agent): execute bounded Android smoke commands`，推送；此时尚未声称真机通过。

## Task 7: 串联、CI 和真实设备交付

**Files:** Create `scripts/demo/run-m3.ps1`, `scripts/tests/m3-demo.tests.ps1`, `scripts/tests/fixtures/m3-demo-command.ps1`, `backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/M3DemoBootstrap.kt`, `M3DemoScenario.kt`, `M3DemoReport.kt`, `docs/m3/single-device-smoke-runbook.md`, `docs/m3/single-device-smoke-verification.md`, `.github/workflows/m3-smoke.yml`。Modify `backend/build.gradle.kts` 仅新增独立 M3 demo entry；不改原 run-m1/M1DemoMain 行为。

**Interfaces:** `run-m3.ps1 -Config <path>` 读取严格 JSON 配置，必填 server、identityConfig、apk、deviceConfig、payloadRoot、spool、outputRoot、planVersion，拒绝未知字段；身份配置只传受控文件引用，不能在配置内复制凭据；`summary.json` 输出 schemaVersion、SYNTHETIC_DEMO、source commit/dirty、实际执行模式（CI_FIXTURE 或 REAL_DEVICE）、Release/Manifest、Run/Attempt/Case、Test Result、Evidence IDs/size/checksum、scenario outcome、未覆盖项。不输出原始设备序列号、账号、证书私钥、URL token 或本机绝对路径；生成成功与源测试状态分开。

- [ ] **Step 1:** 包装层先测参数缺失、子进程非零、输出不存在、服务已有/本次启动、超时与清理权限；继承 m1-demo.tests.ps1 的命令注入模式，夹具不得进入生产 Agent 默认配置。缺失配置的 Pester 断言同时验证非零退出码、固定错误码 CONFIG_INVALID，以及没有生成结果报告；已进入场景的失败则检查失败摘要。

```powershell
$diagnostic = & $pwsh -NoProfile -File $script -Config $invalidConfig 2>&1
$LASTEXITCODE | Should -Not -Be 0
($diagnostic -join "\n") | Should -Match "CONFIG_INVALID"
Test-Path -LiteralPath $reportPath | Should -BeFalse
# valid fixture scenario separately asserts Run ID, Case status and both Evidence hashes
```

pwsh 在 BeforeAll 中由 Get-Command 解析。其他变量在 m3-demo.tests.ps1 的 BeforeAll/BeforeEach 显式绑定本任务脚本与 TestDrive 下创建的文件；不引用用户配置或真实设备。
- [ ] **Step 2:** 初始化仅写项目/身份/Agent/Device/Published Plan v1/v2 定义，用户/Agent service 身份分离；使用已有真实文件验证器供 APK+CONFIG，HTTP 注册/校验/Lock 后创建 Run。Case v2 预期 FAIL 作为场景通过，但汇总仍显示原 FAIL。禁止直接 seed Result、Evidence AVAILABLE 或修改 Traceability Snapshot。
- [ ] **Step 3:** 新 CI 复用 GitHub Actions，仅构建 APK、Agent 和 Backend 目标测试/受控协议夹具。显式预检 SDK/JDK，缺失时失败并说明，不偷偷跳过；使用现有仓库已固定的 checkout/setup-java 等 action 引用。上传同次 APK 摘要、测试 XML、夹具 summary 与 Payload 样例，保留失败材料；CI_FIXTURE 从不记为 REAL_DEVICE。不要求配置自托管设备 Runner、Company 或新存储服务。
- [ ] **Step 4:** 获实施指令后，在首次真机操作前核实明确选定的设备、API Level≥26、ADB 授权与允许安装/启动范围；多个设备时由 Owner 指定，不自动选首台。按正常/确定 FAIL 各执行一次，读取 API 验证 Run→Result→LOG/PNG、下载每份并重算 SHA-256。只使用由本任务启动的进程/目录；不终止既有服务、不删除旧结果、不自动卸载 App。设备不可用时继续独立 CI，但真实设备检查写 UNKNOWN，交付项不勾完成。
- [ ] **Step 5:** 在实际支持范围内演练连接中断和 Agent 进程重启；不自动设备断电/重启。保存前置状态、注入方式、时序、旧/新租约、终态及恢复 bytes；确认没有重复安装、晚写或假 PASS。恢复 Server 数据与 Payload 副本，实际校验清单与结果；明示未覆盖完整 M3 Crash/ANR/断电出口。
- [ ] **Step 6:** 独立工程复审、准确实施提交的双语 CI/Artifact/真机材料核对，验收记录使用新的固定实施 Subject，状态初始 PENDING；不能复用本设计 APPROVE。产品提交与记录提交分离，契约/验收校验、Pair Gate、原子推送与远端 HEAD 核对通过；填写实际已执行检查，不声称全部 M3 或 Company 完成。

## 计划自检与下一步

覆盖关系：APK/Identity 与输入校验→1/2/6；固定 Release/Plan/Environment、Lease/Recovery→3；Evidence 上传/下载/备份→4；Result digest/幂等/Run 完成→5；实际进程、日志和截图→6；CI/真机差异、串联及独立验收→7。跨任务类型由接口段及对应 Task 定义；自检确保无临时成功适配器或额外业务权威。

所有 Task 当前未执行。本次文档校验不能证明新增构建、数据库迁移、mTLS 或 ADB 行为通过；设备与 SDK 预检由对应 Task 实际执行。若设计中实际平台假设不成立，停止受影响动作并记录差异，不以降级放宽身份或成功条件。

当前结果：设计已确认，七项实施任务、接口、验证与交付边界已列明。Git 状态：计划按双语治理版本化，推送以远端核对为准。下一步动作：执行 Task 1，交付可构建的最小演示 APK。前置条件：实施指令及 Task 1 工具链预检；不需要先连接设备。验收目标：目标单测、lint、APK 构建与签名/文件摘要可核对，双语提交已推送，不声称设备或 M3 验收完成。
