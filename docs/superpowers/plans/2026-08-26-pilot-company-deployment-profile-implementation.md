# Pilot / Company 双模式配置实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变 V0.1 冻结架构的前提下，实现 `PILOT` / `COMPANY` 部署 Profile、真实 Archive Capability、filesystem staging、S3-compatible 长期归档和可审计 Archive Receipt。

**Architecture:** 配置通过 `ArchivePolicy` 进入 framework-independent application contract；`ArchiveAdapter` 是唯一归档 Port，`NONE`、`FILESYSTEM_STAGING` 和 `S3_COMPATIBLE` 由 Adapter 实现。Capability 每次执行前主动验证；`PILOT` 允许未配置状态但不产生长期归档 `PASS`，`COMPANY` 未达到 `EXTERNAL_VERIFIED` 时 readiness 和归档操作 fail closed。

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

文档必须明确：Profile 不修改 Core Contract；目标布尔开关默认 `true`；Capability 只能由 probe 产生；`FILESYSTEM_STAGING` 不能产生长期归档 `PASS`；`COMPANY` 使用 readiness + operation fail closed；AWS SDK v2 通过 BOM `2.54.4` 只引入 S3 模块；凭据使用 default credential chain；迁移不删除源对象。

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
)

data class CapabilityCheck(val name: String, val passed: Boolean, val detail: String)

data class ArchiveCapabilityReport(
    val mode: DeploymentMode,
    val provider: ArchiveProvider,
    val state: ArchiveCapabilityState,
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
    fun probe(policy: ArchivePolicy): List<CapabilityCheck>
    fun archive(command: ArchiveCommand, policy: ArchivePolicy): ArchiveReceipt
}
```

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

使用 `ApplicationContextRunner` 断言：默认 `ArchivePolicy` 为 `PILOT` + `NONE`；六个目标控制布尔值均为 `true`；`objectPrefix` 为 `acceptance/`；`../escape` 导致 bean creation failure；`FILESYSTEM_STAGING` 的相对 `stagingRoot` 导致 failure；`COMPANY` 缺外部配置不导致启动异常，只由 Capability 判定 NOT_READY。

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
```

- [ ] **Step 4: 实现 `ArchiveConfiguration`**

定义两个 `@ConfigurationProperties` data class，并用一个 `@Bean` 规范化空字符串。只拒绝格式危险项：prefix 为空、绝对、包含 `..` 或反斜杠；staging root 在选择 `FILESYSTEM_STAGING` 时必须存在配置且为绝对路径；retention 非正值必须拒绝。`COMPANY` 的 Endpoint/Bucket/owner/retention 缺失不抛启动异常，由 Task 4 产生真实 NOT_READY。

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

测试必须用 fake `ArchiveAdapter` 覆盖：`NONE` → `UNCONFIGURED`；filesystem 全部通过 → `LOCAL_PILOT`；S3 任一失败 → `EXTERNAL_UNVERIFIED`；S3 全部通过 → `EXTERNAL_VERIFIED`；重复 Provider Adapter 在构造时失败。再断言 health：`PILOT` 始终 UP 但保留实际 state；`COMPANY` 仅在 `EXTERNAL_VERIFIED` 时 UP。

- [ ] **Step 2: 运行并确认缺少 evaluator**

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.ArchiveCapabilityTest"
```

Expected: FAIL，错误包含 unresolved `EvaluateArchiveCapability`。

- [ ] **Step 3: 实现 evaluator**

```kotlin
class EvaluateArchiveCapability(
    adapters: List<ArchiveAdapter>,
    private val timeProvider: TimeProvider,
) {
    private val adaptersByProvider = adapters.associateBy { it.provider }.also {
        require(it.size == adapters.size) { "Archive providers must be unique" }
    }

    fun evaluate(policy: ArchivePolicy): ArchiveCapabilityReport {
        val checks = adaptersByProvider[policy.provider]
            ?.probe(policy)
            ?: listOf(CapabilityCheck("provider", false, "No adapter is registered"))
        val passed = checks.isNotEmpty() && checks.all { it.passed }
        val state = when (policy.provider) {
            ArchiveProvider.NONE -> ArchiveCapabilityState.UNCONFIGURED
            ArchiveProvider.FILESYSTEM_STAGING -> if (passed) ArchiveCapabilityState.LOCAL_PILOT else ArchiveCapabilityState.UNCONFIGURED
            ArchiveProvider.S3_COMPATIBLE -> if (passed) ArchiveCapabilityState.EXTERNAL_VERIFIED else ArchiveCapabilityState.EXTERNAL_UNVERIFIED
        }
        return ArchiveCapabilityReport(policy.mode, policy.provider, state, timeProvider.now(), checks.toList())
    }
}
```

将 evaluator 注册为 Spring bean。`NoneArchiveAdapter.probe` 返回一个失败的 `provider` check，`archive` 抛出 `ArchiveUnavailable`。

- [ ] **Step 4: 实现 health indicator 与 health groups**

Health detail 只输出 `mode`、`provider`、`state`、`checkedAt` 和逐项 boolean/name/detail，不输出 Endpoint、credential 或 presigned URL。`COMPANY` 且 state 非 `EXTERNAL_VERIFIED` 返回 DOWN；其余返回 UP。将 `archiveCapability` 仅加入 readiness group，liveness group 不依赖外部存储。

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

使用 `@TempDir` 创建显式 root 和源 ZIP。测试：probe 成功返回 `LOCAL_PILOT`；源路径不在 root 内被拒绝；expected SHA-256 不一致保留源文件且不产生 receipt；同一 command 重放返回相同 locator；目标已存在但摘要不同 fail closed；成功 receipt 包含 `longTerm=false`、`retentionPolicy=PILOT_ONLY`、`immutabilityControl=NONE`。

- [ ] **Step 2: 运行并确认 Adapter 不存在**

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.FilesystemStagingArchiveTest"
```

Expected: FAIL，错误包含 unresolved `FilesystemStagingArchiveAdapter`。

- [ ] **Step 3: 实现 `ArchiveEvidence` Gate**

服务每次调用先 evaluate；`enabled=false`、`UNCONFIGURED`、`EXTERNAL_UNVERIFIED` 均抛 `ArchiveUnavailable`；`COMPANY` 只有 `EXTERNAL_VERIFIED` 可以继续；`PILOT` + `LOCAL_PILOT` 可以生成 staging receipt，但 receipt 保持 `longTerm=false`。

- [ ] **Step 4: 实现安全 filesystem staging**

源路径使用 `toRealPath()`，必须位于显式 staging root。目标使用规范化的 `objectPrefix/acceptanceId/sourceCommit/sourceArtifactId`，并再次验证 `startsWith(root)`。先复制到同目录 `.partial`，复算 SHA-256 后原子 move；重放时只接受现有目标摘要等于 expected。receipt 使用 Jackson 写入 `<sourceArtifactId>-archive-receipt.json`，不得覆盖内容不同的现有 receipt。

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

测试默认 context 中不存在 `S3Client`；`S3_COMPATIBLE` 时使用测试 credential provider 才创建 client；Endpoint、Region 和 Bucket 不得出现在 bean `toString()` 或异常 detail 中。

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
data class S3ControlSnapshot(
    val reachable: Boolean,
    val encrypted: Boolean,
    val privateAccess: Boolean,
    val versioningEnabled: Boolean,
    val objectLockEnabled: Boolean,
    val defaultRetentionDays: Long?,
)

interface S3Gateway {
    fun controls(bucket: String): S3ControlSnapshot
    fun putFileIfAbsent(bucket: String, key: String, source: Path, sha256: String)
    fun download(bucket: String, key: String, target: Path)
    fun putJsonIfAbsent(bucket: String, key: String, bytes: ByteArray, sha256: String)
}
```

`AwsS3Gateway` 把 SDK exception 转换为不含 endpoint/credential/token 的 `ArchiveUnavailable`，但保留 operation 和 AWS error code。

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

使用 in-memory fake `S3Gateway` 覆盖：不可达、无 encryption、public access、无 versioning、无 Object Lock、默认 retention 小于配置、全部通过。只在全部 required control 通过时 probe 全部为 true，进而由 evaluator 产生 `EXTERNAL_VERIFIED`。

- [ ] **Step 2: 写 archive 失败路径测试**

覆盖：上传异常不产生 receipt；回读摘要不一致保留源且抛 `ArchiveIntegrityFailure`；receipt 上传失败不删除 payload；成功时 destination locator 为 `s3://<bucket>/<key>`，receipt locator 稳定，`longTerm=true`；重放相同 source 得到相同 key，不覆盖不同内容。

- [ ] **Step 3: 运行并确认 Adapter 不存在**

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.S3ArchiveAdapterTest"
```

Expected: FAIL，错误包含 unresolved `S3ArchiveAdapter`。

- [ ] **Step 4: 实现 probe**

把 `S3ControlSnapshot` 映射为固定名称 checks：`connection`、`encryption`、`privateAccess`、`versioning`、`immutability`、`retention`。只记录 boolean 和通用原因。配置 `retentionPeriod` 转为整日并向上取整；bucket default retention 必须不小于该值。

- [ ] **Step 5: 实现 content-addressed archive**

object key 使用规范化的 `objectPrefix/acceptanceId/sourceCommit/<sha256>/<sourceArtifactId>.zip`。上传使用 create-if-absent；下载到临时文件后复算 SHA-256；成功后序列化 receipt，再以 `<sourceArtifactId>-archive-receipt.json` create-if-absent。临时回读文件在 finally 中删除，但永远不删除源文件或已上传 payload。

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

用 Spring context 覆盖默认 `PILOT` + `NONE`、`PILOT` + filesystem、`COMPANY` + `NONE`。断言默认 context 正常；filesystem report 为 `LOCAL_PILOT`；Company readiness 为 DOWN；没有成功 Archive Receipt 时不产生长期归档 `PASS`。测试不能要求真实公司凭据。

- [ ] **Step 2: 更新运行手册**

记录全部环境变量、Profile 矩阵、secret 注入规则、readiness 检查、staging 不等于长期归档、S3 切换步骤、回读摘要检查和回滚不删除对象。不得包含真实 credential、内部 endpoint 或临时 presigned URL。

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
- S3 只有连接、加密、私有访问、versioning、Object Lock 和 retention 全部验证成功才产生 `EXTERNAL_VERIFIED`。
- `COMPANY` 非 `EXTERNAL_VERIFIED` 时 readiness 与归档操作 fail closed，liveness 不受外部存储故障影响。
- 上传、回读和 receipt 摘要可复核；失败不删除源对象，不静默 fallback。
- 当前 `M1-OWNER-GATE-001` 不由配置自动改变。
- 中英文 CI 成功、Pair Gate 通过、非 Markdown 文件字节一致。
