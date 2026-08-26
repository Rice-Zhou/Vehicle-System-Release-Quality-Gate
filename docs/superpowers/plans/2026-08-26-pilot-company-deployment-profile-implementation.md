# Pilot / Company 双模式配置实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变 V0.1 冻结架构的前提下，实现 `PILOT` / `COMPANY` 部署 Profile、真实 Archive Capability、filesystem staging、S3-compatible 长期归档和可审计 Archive Receipt。

**Architecture:** 配置通过 `ArchivePolicy` 进入 framework-independent application contract；`ArchiveAdapter` 是唯一归档 Port，`NONE`、`FILESYSTEM_STAGING` 和 `S3_COMPATIBLE` 由 Adapter 实现。每次 readiness 与归档命令都使用有界超时重新 probe，Capability 报告只绑定单次规范化策略快照；`PILOT` 允许未配置状态但不产生长期归档 `PASS`，`COMPANY` 仅在 `archive.enabled=true` 且状态为 `EXTERNAL_VERIFIED` 时 READY。

**Tech Stack:** Kotlin 2.2.21、Spring Boot 3.5.16、Java 21、Spring Actuator、Jackson、AWS SDK for Java v2 BOM `2.54.4`、JUnit 5、AssertJ、Mockito、Gradle。

---

## 实施边界与文件结构

本计划只新增共享部署/归档实现，不提前建立完整 Evidence Domain、数据库表、Controller 或第二套 Quality Engine。Archive Receipt 是部署/验收证据，不是 Core Evidence Entity。

新增文件职责：

- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveModels.kt`：framework-independent 配置、状态、命令和 receipt。
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveAdapter.kt`：唯一 Port。
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/EvaluateArchiveCapability.kt`：派生真实 Capability。
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveEvidence.kt`：执行前强制 Profile 规则。
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveConfiguration.kt`：Spring 配置绑定、规范化和 S3 client wiring。
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/NoneArchiveAdapter.kt`：明确未配置状态。
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/FilesystemStagingArchiveAdapter.kt`：本地 staging 和非长期 receipt。
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3Gateway.kt`：对 AWS SDK 的窄封装。
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3ArchiveAdapter.kt`：控制探测、上传、回读和长期 receipt。
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveCapabilityHealthIndicator.kt`：readiness 证据。

### Task 1: 记录 TDR-011 技术决策

**Files:**
- Create: `docs/v0.2/tdr/TDR-011-pilot-company-deployment-profiles.md`
- Reference: `docs/v0.2/tdr/TDR-004-s3-compatible-evidence-storage.md`
- Reference: `docs/v0.2/tdr/TDR-010-containerized-vm-deployment.md`

- [ ] **Step 1: 写入完整 TDR**

文档必须明确：Profile 不修改 Core Contract；目标布尔开关默认 `true`；Capability 只能由单次新鲜 probe 产生；`FILESYSTEM_STAGING` 不能产生长期归档 `PASS`；`COMPANY` 的 READY 不变量；有界超时；payload 与 receipt 的实际不可变保护；AWS SDK v2 通过 BOM `2.54.4` 只引入 S3 模块；凭据使用 default credential chain；迁移不删除源对象。

- [ ] **Step 2: 检查 TDR 与已批准设计无冲突**

Run:

```powershell
rg -n "T[B]D|T[O]DO|PEND[I]NG" docs/v0.2/tdr/TDR-011-pilot-company-deployment-profiles.md
```

Expected: 无输出，exit code `1` 仅表示没有匹配项。

- [ ] **Step 3: 提交 TDR**

```powershell
git add docs/v0.2/tdr/TDR-011-pilot-company-deployment-profiles.md
git commit -m "docs(v0.2): record deployment profile decision"
```

### Task 2: 建立 framework-independent Archive Contract

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveModels.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveAdapter.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveContractTest.kt`

- [ ] **Step 1: 写失败的 contract test**

```kotlin
package com.ricezhou.vsrqg.shared.archive

import com.ricezhou.vsrqg.shared.application.archive.ArchiveCapabilityState
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.DeploymentMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ArchiveContractTest {
    @Test
    fun `contract exposes only governed enum values`() {
        assertThat(DeploymentMode.entries.map { it.name }).containsExactly("PILOT", "COMPANY")
        assertThat(ArchiveProvider.entries.map { it.name })
            .containsExactly("NONE", "FILESYSTEM_STAGING", "S3_COMPATIBLE")
        assertThat(ArchiveCapabilityState.entries.map { it.name })
            .containsExactly("UNCONFIGURED", "LOCAL_PILOT", "EXTERNAL_UNVERIFIED", "EXTERNAL_VERIFIED")
    }
}
```

- [ ] **Step 2: 运行测试并确认因类型不存在而失败**

Run:

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.ArchiveContractTest"
```

Expected: compilation FAIL，错误包含 unresolved `ArchiveCapabilityState`。

- [ ] **Step 3: 实现模型和唯一 Port**

`ArchiveModels.kt` 必须包含以下签名；所有 collection 使用不可变 `List`，所有摘要使用 64 位小写十六进制，不接受带 secret 的自由 Map：

```kotlin
package com.ricezhou.vsrqg.shared.application.archive

import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

enum class DeploymentMode { PILOT, COMPANY }
enum class ArchiveProvider { NONE, FILESYSTEM_STAGING, S3_COMPATIBLE }
enum class ArchiveCapabilityState { UNCONFIGURED, LOCAL_PILOT, EXTERNAL_UNVERIFIED, EXTERNAL_VERIFIED }

data class ArchivePolicy(
    val mode: DeploymentMode,
    val enabled: Boolean,
    val checksumVerificationEnabled: Boolean,
    val encryptionRequired: Boolean,
    val privateAccessRequired: Boolean,
    val retentionPolicyRequired: Boolean,
    val immutabilityRequired: Boolean,
    val provider: ArchiveProvider,
    val stagingRoot: Path?,
    val endpoint: URI?,
    val region: String?,
    val bucket: String?,
    val objectPrefix: String,
    val accessOwner: String?,
    val retentionPeriod: Duration?,
    val probeTimeout: Duration,
    val operationTimeout: Duration,
)

data class CapabilityCheck(val name: String, val passed: Boolean, val detail: String)

data class CapabilityProbeContext(
    val policyFingerprint: String,
    val checkedAt: Instant,
)

data class ArchiveCapabilityReport(
    val mode: DeploymentMode,
    val provider: ArchiveProvider,
    val state: ArchiveCapabilityState,
    val policyFingerprint: String,
    val checkedAt: Instant,
    val checks: List<CapabilityCheck>,
)

data class ArchiveCommand(
    val acceptanceId: String,
    val sourceArtifactId: String,
    val sourceRunId: String,
    val sourceCommit: String,
    val source: Path,
    val expectedSha256: String,
)

data class ArchiveReceipt(
    val acceptanceId: String,
    val sourceArtifactId: String,
    val sourceRunId: String,
    val sourceCommit: String,
    val sourceSha256: String,
    val destinationLocator: String,
    val destinationSha256: String,
    val receiptLocator: String,
    val sizeBytes: Long,
    val accessOwner: String,
    val retentionPolicy: String,
    val immutabilityControl: String,
    val policyFingerprint: String,
    val capabilityCheckedAt: Instant,
    val archivedAt: Instant,
    val verifier: String,
    val longTerm: Boolean,
)

class ArchiveUnavailable(message: String) : IllegalStateException(message)
class ArchiveIntegrityFailure(message: String) : IllegalStateException(message)
```

`ArchiveAdapter.kt`：

```kotlin
package com.ricezhou.vsrqg.shared.application.archive

interface ArchiveAdapter {
    val provider: ArchiveProvider
    fun probe(policy: ArchivePolicy, context: CapabilityProbeContext): List<CapabilityCheck>
    fun archive(
        command: ArchiveCommand,
        policy: ArchivePolicy,
        capability: ArchiveCapabilityReport,
    ): ArchiveReceipt
}
```

contract test 还必须用具名参数构造这些模型并断言：`probeTimeout=PT5S`、`operationTimeout=PT30S`；`CapabilityProbeContext`、report 与 receipt 的 `policyFingerprint` 均为同一个 64 位小写十六进制；context 与 report 的 `checkedAt` 相同，receipt 的 `capabilityCheckedAt` 也等于该值。任何不合法摘要或指纹在业务调用边界被拒绝。

- [ ] **Step 4: 运行 contract test**

Run 同 Step 2。Expected: PASS。

- [ ] **Step 5: 提交 contract**

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveContractTest.kt
git commit -m "feat(archive): define deployment archive contract"
```

### Task 3: 绑定并验证默认配置

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveConfiguration.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/VsrqgApplication.kt`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveConfigurationTest.kt`

- [ ] **Step 1: 写默认值和非法 prefix 的失败测试**

使用 `ApplicationContextRunner` 断言：默认 `ArchivePolicy` 为 `PILOT` + `NONE`；六个目标控制布尔值均为 `true`；`objectPrefix` 为 `acceptance/`；`probeTimeout` 为 `PT5S`；`operationTimeout` 为 `PT30S`；`../escape` 导致 bean creation failure；`FILESYSTEM_STAGING` 的相对 `stagingRoot` 导致 failure；任一 timeout 为零或负值以及 operation 小于 probe 均导致 failure；`COMPANY` 缺外部配置不导致启动异常，只由 Capability 判定 NOT_READY。

- [ ] **Step 2: 运行并确认缺少 `ArchivePolicy` bean**

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.ArchiveConfigurationTest"
```

Expected: FAIL，错误指出没有 `ArchivePolicy` bean。

- [ ] **Step 3: 启用 properties scan 并添加默认 YAML**

在 `VsrqgApplication` 增加 `@ConfigurationPropertiesScan`。在 `application.yml` 的 `vsrqg` 下加入：

```yaml
  deployment:
    mode: ${VSRQG_DEPLOYMENT_MODE:PILOT}
  evidence:
    archive:
      enabled: ${VSRQG_EVIDENCE_ARCHIVE_ENABLED:true}
      checksum-verification-enabled: ${VSRQG_EVIDENCE_ARCHIVE_CHECKSUM_VERIFICATION_ENABLED:true}
      encryption-required: ${VSRQG_EVIDENCE_ARCHIVE_ENCRYPTION_REQUIRED:true}
      private-access-required: ${VSRQG_EVIDENCE_ARCHIVE_PRIVATE_ACCESS_REQUIRED:true}
      retention-policy-required: ${VSRQG_EVIDENCE_ARCHIVE_RETENTION_POLICY_REQUIRED:true}
      immutability-required: ${VSRQG_EVIDENCE_ARCHIVE_IMMUTABILITY_REQUIRED:true}
      provider: ${VSRQG_EVIDENCE_ARCHIVE_PROVIDER:NONE}
      staging-root: ${VSRQG_EVIDENCE_ARCHIVE_STAGING_ROOT:}
      endpoint: ${VSRQG_EVIDENCE_ARCHIVE_ENDPOINT:}
      region: ${VSRQG_EVIDENCE_ARCHIVE_REGION:}
      bucket: ${VSRQG_EVIDENCE_ARCHIVE_BUCKET:}
      object-prefix: ${VSRQG_EVIDENCE_ARCHIVE_OBJECT_PREFIX:acceptance/}
      access-owner: ${VSRQG_EVIDENCE_ARCHIVE_ACCESS_OWNER:}
      retention-period: ${VSRQG_EVIDENCE_ARCHIVE_RETENTION_PERIOD:}
      probe-timeout: ${VSRQG_EVIDENCE_ARCHIVE_PROBE_TIMEOUT:PT5S}
      operation-timeout: ${VSRQG_EVIDENCE_ARCHIVE_OPERATION_TIMEOUT:PT30S}
```

- [ ] **Step 4: 实现 `ArchiveConfiguration`**

定义两个 `@ConfigurationProperties` data class，并用一个 `@Bean` 规范化空字符串和 duration。只拒绝格式危险项：prefix 为空、绝对、包含 `..` 或反斜杠；staging root 在选择 `FILESYSTEM_STAGING` 时必须存在配置且为绝对路径；retention 非正值必须拒绝；`probe-timeout` 与 `operation-timeout` 必须为正值，且后者必须大于或等于前者。`COMPANY` 的 Endpoint/Bucket/owner/retention 缺失不抛启动异常，由 Task 4 产生真实 NOT_READY。

- [ ] **Step 5: 运行配置测试和 context test**

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.ArchiveConfigurationTest" --tests "com.ricezhou.vsrqg.ApplicationContextTest"
```

Expected: PASS。

- [ ] **Step 6: 提交配置契约**

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/VsrqgApplication.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveConfiguration.kt backend/src/main/resources/application.yml backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveConfigurationTest.kt
git commit -m "feat(archive): bind deployment profile configuration"
```

### Task 4: 派生 Capability 并接入 readiness

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/EvaluateArchiveCapability.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/NoneArchiveAdapter.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveCapabilityHealthIndicator.kt`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveCapabilityTest.kt`

- [ ] **Step 1: 写状态矩阵失败测试**

测试必须用计数 fake `ArchiveAdapter` 覆盖：`NONE` → `UNCONFIGURED`；filesystem 全部通过 → `LOCAL_PILOT`；S3 任一失败 → `EXTERNAL_UNVERIFIED`；S3 全部通过 → `EXTERNAL_VERIFIED`；重复 Provider Adapter 在构造时失败；连续两次 evaluate 必须调用两次 probe。同一规范化 policy 的 `policyFingerprint` 稳定且为 64 位小写十六进制，逐一改变 Profile、Provider、布尔策略、路径、Endpoint、Region、Bucket、prefix、owner、retention 或 timeout 都改变指纹。再断言 health 每次调用均重新 probe：`PILOT` 始终 UP 但保留实际 state；`COMPANY` 仅在 `enabled=true` 且 `EXTERNAL_VERIFIED` 时 UP；`enabled=false` 不改变 Provider 派生 state，但 health 为 DOWN。

- [ ] **Step 2: 运行并确认缺少 evaluator**

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.ArchiveCapabilityTest"
```

Expected: FAIL，错误包含 unresolved `EvaluateArchiveCapability`。

- [ ] **Step 3: 实现 evaluator**

```kotlin
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Locale

class EvaluateArchiveCapability(
    adapters: List<ArchiveAdapter>,
    private val timeProvider: TimeProvider,
) {
    private val adaptersByProvider = adapters.associateBy { it.provider }.also {
        require(it.size == adapters.size) { "Archive providers must be unique" }
    }

    fun evaluate(policy: ArchivePolicy): ArchiveCapabilityReport {
        val checkedAt = timeProvider.now()
        val policyFingerprint = fingerprint(policy)
        val context = CapabilityProbeContext(policyFingerprint, checkedAt)
        val checks = adaptersByProvider[policy.provider]
            ?.probe(policy, context)
            ?: listOf(CapabilityCheck("provider", false, "No adapter is registered"))
        val passed = checks.isNotEmpty() && checks.all { it.passed }
        val state = when (policy.provider) {
            ArchiveProvider.NONE -> ArchiveCapabilityState.UNCONFIGURED
            ArchiveProvider.FILESYSTEM_STAGING -> if (passed) ArchiveCapabilityState.LOCAL_PILOT else ArchiveCapabilityState.UNCONFIGURED
            ArchiveProvider.S3_COMPATIBLE -> if (passed) ArchiveCapabilityState.EXTERNAL_VERIFIED else ArchiveCapabilityState.EXTERNAL_UNVERIFIED
        }
        return ArchiveCapabilityReport(
            mode = policy.mode,
            provider = policy.provider,
            state = state,
            policyFingerprint = policyFingerprint,
            checkedAt = checkedAt,
            checks = checks.toList(),
        )
    }

    private fun fingerprint(policy: ArchivePolicy): String {
        val canonical = listOf(
            "mode=${policy.mode.name}",
            "enabled=${policy.enabled}",
            "checksumVerificationEnabled=${policy.checksumVerificationEnabled}",
            "encryptionRequired=${policy.encryptionRequired}",
            "privateAccessRequired=${policy.privateAccessRequired}",
            "retentionPolicyRequired=${policy.retentionPolicyRequired}",
            "immutabilityRequired=${policy.immutabilityRequired}",
            "provider=${policy.provider.name}",
            "stagingRoot=${policy.stagingRoot?.normalize()?.toString().orEmpty()}",
            "endpoint=${policy.endpoint?.normalize()?.toASCIIString().orEmpty()}",
            "region=${policy.region.orEmpty()}",
            "bucket=${policy.bucket.orEmpty()}",
            "objectPrefix=${policy.objectPrefix}",
            "accessOwner=${policy.accessOwner.orEmpty()}",
            "retentionPeriod=${policy.retentionPeriod?.toString().orEmpty()}",
            "probeTimeout=${policy.probeTimeout}",
            "operationTimeout=${policy.operationTimeout}",
        ).joinToString("") { field ->
            "${field.toByteArray(UTF_8).size}:$field"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }
}
```

`ArchiveConfiguration` 必须在 evaluator 之前规范化所有 nullable string、Path、URI 和 duration 字段；指纹只覆盖上述非 secret 字段，绝不输出原值。将 evaluator 注册为 Spring bean 且不增加 cache。evaluator 在调用 Adapter 前生成 `CapabilityProbeContext`，保证 S3 probe 使用的指纹与检查时间精确进入 report。`NoneArchiveAdapter.probe` 接受 context 并返回一个失败的 `provider` check，`archive` 使用新 Port 签名并抛出 `ArchiveUnavailable`。

- [ ] **Step 4: 实现 health indicator 与 health groups**

Health 每次调用 evaluator 以获得新鲜 probe。detail 只输出 `mode`、`provider`、`state`、`policyFingerprint`、`checkedAt` 和逐项 boolean/name/detail，不输出 Endpoint、credential 或 presigned URL。`COMPANY` 在 `enabled=false` 或 state 非 `EXTERNAL_VERIFIED` 时返回 DOWN；`enabled` 只作为独立 Gate，不改写 evaluator 派生 state。把 `archiveCapability` 追加到现有 readiness group 而不是替换其他检查；liveness group 不依赖外部存储。

- [ ] **Step 5: 运行测试**

Run 同 Step 2。Expected: PASS。

- [ ] **Step 6: 提交 Capability**

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/EvaluateArchiveCapability.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/NoneArchiveAdapter.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveCapabilityHealthIndicator.kt backend/src/main/resources/application.yml backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveCapabilityTest.kt
git commit -m "feat(archive): expose truthful archive readiness"
```

### Task 5: 实现 filesystem staging 与非长期 Receipt

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveEvidence.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/FilesystemStagingArchiveAdapter.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/FilesystemStagingArchiveTest.kt`

- [ ] **Step 1: 写路径、摘要和 receipt 失败测试**

使用 `@TempDir` 创建显式 root 和源 ZIP。测试：probe 成功返回 `LOCAL_PILOT`；probe 超过 `probeTimeout` 时失败；源路径不在 root 内被拒绝；expected SHA-256 不一致保留源文件且不产生 receipt；同一 command 重放返回相同 locator；目标已存在但摘要不同 fail closed；每次 ArchiveEvidence 调用都增加 probe 计数；`enabled=false` 仍先产生真实 report、随后由独立 Gate 拒绝且不改写 state；成功 receipt 包含 `longTerm=false`、`retentionPolicy=PILOT_ONLY`、`immutabilityControl=NONE`，并且 `policyFingerprint` 与 `capabilityCheckedAt` 精确等于本次 report。让一次 copy、digest 或 receipt 写入超过 `operationTimeout`，断言失败后下一次调用重新 probe 且不复用旧 report。

- [ ] **Step 2: 运行并确认 Adapter 不存在**

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.FilesystemStagingArchiveTest"
```

Expected: FAIL，错误包含 unresolved `FilesystemStagingArchiveAdapter`。

- [ ] **Step 3: 实现 `ArchiveEvidence` Gate**

服务每次调用立即 evaluate 并取得新鲜 report，不保存 cache；随后单独检查 `enabled=false` 并抛 `ArchiveUnavailable`，但不改写 report 的 Provider state。`UNCONFIGURED`、`EXTERNAL_UNVERIFIED` 均拒绝；`COMPANY` 只有 `enabled=true` 且本次 report 为 `EXTERNAL_VERIFIED` 可以继续；`PILOT` + `LOCAL_PILOT` 可以生成 staging receipt，但 receipt 保持 `longTerm=false`。把整个 report 传给 `ArchiveAdapter.archive`；任何 operation failure 都自然丢弃该 report，重试必须从新 probe 开始。

- [ ] **Step 4: 实现安全 filesystem staging**

源路径使用 `toRealPath()`，必须位于显式 staging root。目标使用规范化的 `objectPrefix/acceptanceId/sourceCommit/sourceArtifactId`，并再次验证 `startsWith(root)`。先复制到同目录 `.partial`，在 `operationTimeout` 内复算 SHA-256 后原子 move；重放时只接受现有目标摘要等于 expected。receipt 使用 Jackson 写入 `<sourceArtifactId>-archive-receipt.json`，写入 report 的 `policyFingerprint` 与 `checkedAt`，不得覆盖内容不同的现有 receipt。

- [ ] **Step 5: 运行测试**

Run 同 Step 2。Expected: PASS，且临时目录外没有文件变化。

- [ ] **Step 6: 提交 filesystem Adapter**

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveEvidence.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/FilesystemStagingArchiveAdapter.kt backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/FilesystemStagingArchiveTest.kt
git commit -m "feat(archive): add pilot filesystem staging"
```

### Task 6: 引入最小 AWS SDK 并建立安全 client

**Files:**
- Modify: `backend/build.gradle.kts`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3Gateway.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveConfiguration.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/S3ConfigurationTest.kt`

- [ ] **Step 1: 写 NONE 模式不创建 S3 client 的失败测试**

测试默认 context 中不存在 `S3Client`；`S3_COMPATIBLE` 时使用测试 credential provider 才创建 client；Endpoint、Region 和 Bucket 不得出现在 bean `toString()` 或异常 detail 中。再用 fake/interceptor 断言每个 control request 接收 `probeTimeout`，每个上传、下载、HeadObject-style 保护检查和回执 request 接收 `operationTimeout`；超时异常转换为不含 secret 的 `ArchiveUnavailable`。

- [ ] **Step 2: 增加锁定依赖**

```kotlin
implementation(platform("software.amazon.awssdk:bom:2.54.4"))
implementation("software.amazon.awssdk:s3")
implementation("software.amazon.awssdk:url-connection-client")
```

不得引入整个 `aws-sdk-java`、Transfer Manager、LocalStack 或第二个配置库。

- [ ] **Step 3: 实现 conditional `S3Client`**

仅在 Provider 为 `S3_COMPATIBLE` 时创建。使用 `DefaultCredentialsProvider`，Region 来自规范化配置，非空 Endpoint 才调用 endpoint override；S3-compatible endpoint 启用 path-style access。不得从 Git/YAML 读取 access key 或 secret key。

- [ ] **Step 4: 定义窄 `S3Gateway`**

```kotlin
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

data class ObjectProtectionSnapshot(
    val actualMode: String?,
    val retainUntil: Instant?,
)

data class S3ControlSnapshot(
    val reachable: Boolean,
    val encrypted: Boolean,
    val privateAccess: Boolean,
    val versioningEnabled: Boolean,
    val objectLockEnabled: Boolean,
    val defaultRetentionDays: Long?,
    val controlObjectProtection: ObjectProtectionSnapshot?,
    val overwriteDenied: Boolean,
    val deleteDenied: Boolean,
    val bypassDenied: Boolean,
)

interface S3Gateway {
    fun controls(
        bucket: String,
        controlObjectKey: String,
        requiredRetainUntil: Instant,
        timeout: Duration,
    ): S3ControlSnapshot
    fun putFileIfAbsent(bucket: String, key: String, source: Path, sha256: String, timeout: Duration)
    fun download(bucket: String, key: String, target: Path, timeout: Duration)
    fun putJsonIfAbsent(bucket: String, key: String, bytes: ByteArray, sha256: String, timeout: Duration)
    fun headProtection(bucket: String, key: String, timeout: Duration): ObjectProtectionSnapshot
}
```

`ObjectProtectionSnapshot` 是中立于 Provider 的对象保护契约。`controls` 仅在专用 control object 上执行 create-if-absent、HeadObject-style 检查以及负向 overwrite/delete/bypass 尝试，并返回实际模式、retain-until 和运行时身份拒绝结果；Evidence key 永远不用于破坏性检查。每次 SDK request 使用传入 Duration 构建 per-request API call timeout。`AwsS3Gateway` 把 SDK exception 与 timeout 转换为不含 endpoint/credential/token 的 `ArchiveUnavailable`，但保留 operation 和 AWS error code。

- [ ] **Step 5: 运行 dependency 和配置测试**

```powershell
./backend/gradlew -p backend dependencies --configuration runtimeClasspath
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.S3ConfigurationTest"
```

Expected: dependency tree 只出现 AWS SDK v2 `2.54.4`；测试 PASS。

- [ ] **Step 6: 提交 S3 wiring**

```powershell
git add backend/build.gradle.kts backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveConfiguration.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3Gateway.kt backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/S3ConfigurationTest.kt
git commit -m "feat(archive): wire minimal s3 client"
```

### Task 7: 实现 S3 probe、回读校验与长期 Receipt

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3ArchiveAdapter.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/S3ArchiveAdapterTest.kt`

- [ ] **Step 1: 写控制矩阵失败测试**

使用 in-memory fake `S3Gateway` 覆盖：不可达、无 encryption、public access、无 versioning、bucket Object Lock flag 单独为 true、control object 无实际 mode、retain-until 小于策略、overwrite/delete/bypass 任一未被拒绝，以及全部通过。断言 control key 精确为 `objectPrefix/capability-probe/<policyFingerprint>/<yyyy-MM-dd>/control.json`，日期使用 `CapabilityProbeContext.checkedAt` 的 UTC 日期；相同策略指纹同一天的顺序与并发 probe 都只复用一个原子 create-if-absent 控制对象，跨日期或指纹才使用新 key；所有 control 调用接收 `probeTimeout`。只在全部 required control 通过时 probe 全部为 true，进而由 evaluator 产生 `EXTERNAL_VERIFIED`。

- [ ] **Step 2: 写 archive 失败路径测试**

覆盖：上传异常不产生 receipt；回读摘要不一致保留源且抛 `ArchiveIntegrityFailure`；receipt 上传失败不删除 payload；payload 或 receipt 的 HeadObject-style mode 缺失、retain-until 早于 `archivedAt + retentionPeriod` 或 receipt mode 与记录值不一致时 fail closed；fake 必须证明负向 overwrite/delete/bypass 只针对 control key，从未针对 payload 或 receipt key。成功时 destination locator 为 `s3://<bucket>/<key>`，receipt locator 稳定，`longTerm=true`，`policyFingerprint` 与 `capabilityCheckedAt` 等于本次 report，`immutabilityControl` 是两个实际对象验证一致的 mode 或批准的等价值；所有 upload/download/head/receipt 调用接收 `operationTimeout`。重放相同 source 得到相同 key，不覆盖不同内容；任一失败保留 source、payload 和已上传 receipt。

- [ ] **Step 3: 运行并确认 Adapter 不存在**

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.S3ArchiveAdapterTest"
```

Expected: FAIL，错误包含 unresolved `S3ArchiveAdapter`。

- [ ] **Step 4: 实现 probe**

先对缺失的 bucket、owner 或正 retention 产生明确失败 check，不调用需要这些值的 Gateway 方法。配置完整时，使用 `CapabilityProbeContext` 的 `policyFingerprint` 与 `checkedAt` UTC 日期构造规范化的 `objectPrefix/capability-probe/<policyFingerprint>/<yyyy-MM-dd>/control.json`；对象内容固定为 `{"purpose":"archive-capability-probe","version":1}`，不含 Evidence 或 secret。确定性 key 和 create-if-absent 保证每个策略指纹每天最多一个小控制对象。调用 `controls` 时传入 `probeTimeout` 和 `checkedAt + retentionPeriod`，仅在该对象上验证负向 overwrite、delete、retention bypass 均被运行时身份拒绝。

把 `S3ControlSnapshot` 映射为固定名称 checks：`connection`、`encryption`、`privateAccess`、`versioning`、`immutability`、`retention`。只记录 boolean 和通用原因。配置 `retentionPeriod` 转为整日并向上取整；bucket default retention 必须不小于该值，但 bucket Object Lock flag 本身不能使 `immutability` 通过。只有控制对象存在实际 mode、retain-until 满足策略且三个负向权限均拒绝时，不可变性检查才通过。禁止对 Evidence key 执行覆盖、删除或 bypass 测试。

- [ ] **Step 5: 实现 content-addressed archive**

object key 使用规范化的 `objectPrefix/acceptanceId/sourceCommit/<sha256>/<sourceArtifactId>.zip`。使用 `operationTimeout` 执行 create-if-absent 上传、临时文件下载与 SHA-256 回读。随后用 `headProtection` 验证 payload 的实际 mode 与 retain-until；以 `archivedAt + retentionPeriod` 为最低有效保留期。

候选 receipt 写入本次 report 的 `policyFingerprint`、`checkedAt` 和已由控制对象与 payload 验证的实际 mode 或批准等价值，再以 `<sourceArtifactId>-archive-receipt.json` create-if-absent。上传后必须对 receipt 调用 `headProtection`，只有 payload 与 receipt 的实际 mode 都与记录值一致、retain-until 都满足策略，且本次 control report 已证明运行时身份无法 overwrite/delete/bypass 时，才返回长期成功 receipt。任何 probe、上传、回读、Head 或 receipt 失败都丢弃本次 report，不缓存授权，不删除源文件、控制对象或任何已上传对象；临时回读文件只在 finally 中删除。

- [ ] **Step 6: 运行 S3 Adapter 测试**

Run 同 Step 3。Expected: PASS。

- [ ] **Step 7: 提交 S3 Adapter**

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3ArchiveAdapter.kt backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/S3ArchiveAdapterTest.kt
git commit -m "feat(archive): verify and archive evidence to s3"
```

### Task 8: 集成验证、运行手册与双语同步

**Files:**
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/ApplicationContextTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveProfileIntegrationTest.kt`
- Modify: `docs/m1/runbook.md`
- Modify: `docs/v0.2/13-deployment-design.md`
- Modify: `docs/superpowers/specs/2026-08-26-pilot-company-deployment-profile-design.md` only if implementation exposes a verified contradiction

- [ ] **Step 1: 写 Profile 集成测试**

用 Spring context 覆盖默认 `PILOT` + `NONE`、`PILOT` + filesystem、`COMPANY` + `NONE`、`COMPANY` + `archive.enabled=false` + 可验证 fake Provider，以及 `COMPANY` + `archive.enabled=true` + `EXTERNAL_VERIFIED`。断言默认 context 正常；filesystem report 为 `LOCAL_PILOT`；Company disabled 时 Provider state 仍真实但 readiness DOWN；Company 只有双条件满足时 archive Capability readiness UP；连续 readiness 调用增加 probe 计数且不替换其他 readiness 检查；liveness 始终独立。再断言默认 timeout、非法 timeout 启动失败、策略变化导致新 fingerprint、receipt 关联本次 fingerprint/checkedAt，且没有成功 Archive Receipt 时不产生长期归档 `PASS`。测试不能要求真实公司凭据。

- [ ] **Step 2: 更新运行手册**

记录全部环境变量，包括 `VSRQG_EVIDENCE_ARCHIVE_PROBE_TIMEOUT` 与 `VSRQG_EVIDENCE_ARCHIVE_OPERATION_TIMEOUT`；记录 Profile/enablement 矩阵、新鲜 probe 与 fingerprint、readiness 与 liveness 边界、secret 注入规则、staging 不等于长期归档、专用 control object、payload/receipt 实际保护、S3 切换步骤、回读摘要检查和回滚不删除对象或控制对象、不降低 retention、不使用 bypass identity。不得包含真实 credential、内部 endpoint 或临时 presigned URL。

- [ ] **Step 3: 运行目标测试**

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.*" --tests "com.ricezhou.vsrqg.ApplicationContextTest" --tests "com.ricezhou.vsrqg.ArchitectureTest"
```

Expected: PASS，0 failed。

- [ ] **Step 4: 检查 diff 和 secret 泄漏**

```powershell
git diff --check
rg -n -i "aws_access_key_id|aws_secret_access_key|password=|presigned" backend/src docs/m1 docs/v0.2
```

Expected: `git diff --check` 无输出；搜索只命中文档中的禁止性说明和测试 fixture key name，不包含 secret value。

- [ ] **Step 5: 提交集成和文档**

```powershell
git add backend/src/test/kotlin/com/ricezhou/vsrqg/ApplicationContextTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveProfileIntegrationTest.kt docs/m1/runbook.md docs/v0.2/13-deployment-design.md
git commit -m "test(archive): verify pilot and company profiles"
```

- [ ] **Step 6: 在 clean commit 上运行完整 M1 Gate**

```powershell
./scripts/m1/verify.ps1
```

Expected: `PASS M1 gates=contract,build,test,security,concurrency,smoke,recovery`，生成当前 commit 的 `evidence.json`；Gate 开始和结束时 worktree 均为 clean。

- [ ] **Step 7: 同步英文分支**

把 Task 2～7 的所有非 Markdown commits 按顺序 cherry-pick 到 `feat/m1-release-manifest-en`。TDR、runbook、deployment design 和本计划使用纯英文配对内容；不得把中文 Markdown 推送到英文分支。两个分支的非 Markdown blob 必须完全一致。

- [ ] **Step 8: 运行双语与字节 Gate**

```powershell
./scripts/verify-language-branches.ps1 -ChineseRef feat/m1-release-manifest -EnglishRef feat/m1-release-manifest-en -Mode Pair
```

Expected: `PASS mode=Pair`；英文 Markdown 汉字数为 `0`；非 Markdown diff 为 `0`。

- [ ] **Step 9: 普通推送并核对 CI**

分别确认远端分支是本地 HEAD 的祖先，使用普通 push；禁止 force push。等待两个精确 HEAD 的 `M1 Backend` run 结束并记录 run ID、Artifact ID、digest 和 conclusion。任一 CI 失败时保留失败 Evidence 并修复根因，不降低测试或 Capability 条件。

## 计划完成判定

- `PILOT` 默认不依赖公司资源即可启动。
- 所有目标控制布尔值默认 `true`，但实际状态不由配置伪造。
- filesystem 只产生 `LOCAL_PILOT` 和 `longTerm=false` receipt。
- S3 只有连接、加密、私有访问、versioning 和专用 control object 的实际保护与权限拒绝全部满足策略才产生 `EXTERNAL_VERIFIED`；bucket flag 单独无效。长期成功 receipt 还要求 payload 与 receipt 的实际保护都通过验证。
- `COMPANY` 只有 `archive.enabled=true` 且新鲜状态为 `EXTERNAL_VERIFIED` 时 archive readiness 为 UP；否则 readiness 与归档操作 fail closed，liveness 和其他 readiness 检查保持独立。
- Capability 每次使用都重新 probe，确定性 `policyFingerprint` 与 `checkedAt` 写入 receipt；所有调用受合法 timeout 约束，任何失败使当前 report 失效。
- 上传、回读、payload/receipt 保护和 receipt 摘要可复核；失败不删除源对象或已上传对象，不静默 fallback。
- 当前 `M1-OWNER-GATE-001` 不由配置自动改变。
- 中英文 CI 成功、Pair Gate 通过、非 Markdown 文件字节一致。
