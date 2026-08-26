# Pilot / Company Dual-Mode Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `PILOT` / `COMPANY` deployment Profiles, truthful Archive Capability, filesystem staging, S3-compatible long-term archival, and auditable Archive Receipts without changing the frozen V0.1 architecture.

**Architecture:** Configuration enters a framework-independent application contract through `ArchivePolicy`. `ArchiveAdapter` is the only archival Port, with Adapter implementations for `NONE`, `FILESYSTEM_STAGING`, and `S3_COMPATIBLE`. Capability is actively verified before every operation. `PILOT` permits an unconfigured state but never produces a fabricated long-term archival `PASS`; `COMPANY` fails readiness and archival operations closed until it reaches `EXTERNAL_VERIFIED`.

**Tech Stack:** Kotlin 2.2.21, Spring Boot 3.5.16, Java 21, Spring Actuator, Jackson, AWS SDK for Java v2 BOM `2.54.4`, JUnit 5, AssertJ, Mockito, and Gradle.

---

## Implementation Boundary and File Structure

This plan adds only shared deployment and archival implementation. It does not prematurely create a complete Evidence Domain, database tables, a Controller, or a second Quality Engine. An Archive Receipt is deployment and acceptance evidence, not a Core Evidence Entity.

New file responsibilities:

- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveModels.kt`: framework-independent configuration, state, commands, and receipts.
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveAdapter.kt`: the only Port.
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/EvaluateArchiveCapability.kt`: derives truthful Capability.
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveEvidence.kt`: enforces Profile rules before execution.
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveConfiguration.kt`: Spring binding, normalization, and S3 client wiring.
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/NoneArchiveAdapter.kt`: explicit unconfigured state.
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/FilesystemStagingArchiveAdapter.kt`: local staging and non-long-term receipts.
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3Gateway.kt`: narrow AWS SDK wrapper.
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3ArchiveAdapter.kt`: control probing, upload, read-back, and long-term receipts.
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveCapabilityHealthIndicator.kt`: readiness evidence.

### Task 1: Record the TDR-011 Technology Decision

**Files:**
- Create: `docs/v0.2/tdr/TDR-011-pilot-company-deployment-profiles.md`
- Reference: `docs/v0.2/tdr/TDR-004-s3-compatible-evidence-storage.md`
- Reference: `docs/v0.2/tdr/TDR-010-containerized-vm-deployment.md`

- [ ] **Step 1: Write the complete TDR**

The document must state that Profiles do not change the Core Contract; target booleans default to `true`; only probes produce Capability; `FILESYSTEM_STAGING` cannot produce long-term archival `PASS`; `COMPANY` uses readiness and operation fail-closed behavior; AWS SDK v2 is imported through BOM `2.54.4` with only the S3 module; credentials use the default credential chain; migration never deletes source objects.

- [ ] **Step 2: Check that the TDR does not conflict with the approved design**

Run:

```powershell
rg -n "T[B]D|T[O]DO|PEND[I]NG" docs/v0.2/tdr/TDR-011-pilot-company-deployment-profiles.md
```

Expected: no output; exit code `1` means only that there were no matches.

- [ ] **Step 3: Commit the TDR**

```powershell
git add docs/v0.2/tdr/TDR-011-pilot-company-deployment-profiles.md
git commit -m "docs(v0.2): record deployment profile decision"
```

### Task 2: Establish the Framework-Independent Archive Contract

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveModels.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveAdapter.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveContractTest.kt`

- [ ] **Step 1: Write the failing contract test**

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

- [ ] **Step 2: Run the test and verify that missing types make it fail**

Run:

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.ArchiveContractTest"
```

Expected: compilation FAIL with unresolved `ArchiveCapabilityState`.

- [ ] **Step 3: Implement the models and only Port**

`ArchiveModels.kt` must contain the following signatures. Every collection uses immutable `List`; every digest is 64-character lowercase hexadecimal; no free-form Map may carry secrets:

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

`ArchiveAdapter.kt`:

```kotlin
package com.ricezhou.vsrqg.shared.application.archive

interface ArchiveAdapter {
    val provider: ArchiveProvider
    fun probe(policy: ArchivePolicy): List<CapabilityCheck>
    fun archive(command: ArchiveCommand, policy: ArchivePolicy): ArchiveReceipt
}
```

- [ ] **Step 4: Run the contract test**

Use the command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit the contract**

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveContractTest.kt
git commit -m "feat(archive): define deployment archive contract"
```

### Task 3: Bind and Validate Default Configuration

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveConfiguration.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/VsrqgApplication.kt`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveConfigurationTest.kt`

- [ ] **Step 1: Write failing tests for defaults and an invalid prefix**

Use `ApplicationContextRunner` to assert that the default `ArchivePolicy` is `PILOT` plus `NONE`; all six target-control booleans are `true`; `objectPrefix` is `acceptance/`; `../escape` causes bean creation failure; a relative `stagingRoot` with `FILESYSTEM_STAGING` causes failure; missing external properties in `COMPANY` do not cause startup failure and are reported as NOT_READY only by Capability.

- [ ] **Step 2: Run and verify that the `ArchivePolicy` bean is missing**

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.ArchiveConfigurationTest"
```

Expected: FAIL because there is no `ArchivePolicy` bean.

- [ ] **Step 3: Enable property scanning and add default YAML**

Add `@ConfigurationPropertiesScan` to `VsrqgApplication`. Add the following below `vsrqg` in `application.yml`:

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

- [ ] **Step 4: Implement `ArchiveConfiguration`**

Define two `@ConfigurationProperties` data classes and use one `@Bean` to normalize empty strings. Reject only dangerous formats: a prefix that is empty, absolute, contains `..`, or contains backslashes; when `FILESYSTEM_STAGING` is selected, staging root must be configured and absolute; non-positive retention must be rejected. Missing Endpoint/Bucket/owner/retention in `COMPANY` does not throw during startup and is handled as truthful NOT_READY by Task 4.

- [ ] **Step 5: Run configuration and context tests**

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.ArchiveConfigurationTest" --tests "com.ricezhou.vsrqg.ApplicationContextTest"
```

Expected: PASS.

- [ ] **Step 6: Commit the configuration contract**

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/VsrqgApplication.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveConfiguration.kt backend/src/main/resources/application.yml backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveConfigurationTest.kt
git commit -m "feat(archive): bind deployment profile configuration"
```

### Task 4: Derive Capability and Integrate Readiness

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/EvaluateArchiveCapability.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/NoneArchiveAdapter.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveCapabilityHealthIndicator.kt`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveCapabilityTest.kt`

- [ ] **Step 1: Write a failing state-matrix test**

Use fake `ArchiveAdapter` instances to cover: `NONE` to `UNCONFIGURED`; all filesystem checks passing to `LOCAL_PILOT`; any S3 check failing to `EXTERNAL_UNVERIFIED`; every S3 check passing to `EXTERNAL_VERIFIED`; duplicate Provider Adapters fail construction. Also assert health: `PILOT` is UP while preserving the real state; `COMPANY` is UP only for `EXTERNAL_VERIFIED`.

- [ ] **Step 2: Run and verify that the evaluator is missing**

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.ArchiveCapabilityTest"
```

Expected: FAIL with unresolved `EvaluateArchiveCapability`.

- [ ] **Step 3: Implement the evaluator**

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

Register the evaluator as a Spring bean. `NoneArchiveAdapter.probe` returns one failed `provider` check, and `archive` throws `ArchiveUnavailable`.

- [ ] **Step 4: Implement the health indicator and health groups**

Health details expose only `mode`, `provider`, `state`, `checkedAt`, and each boolean/name/detail check. They never expose Endpoint, credentials, or presigned URLs. `COMPANY` with a state other than `EXTERNAL_VERIFIED` returns DOWN; every other combination returns UP. Add `archiveCapability` only to the readiness group; the liveness group must not depend on external storage.

- [ ] **Step 5: Run the tests**

Use the command from Step 2. Expected: PASS.

- [ ] **Step 6: Commit Capability**

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/EvaluateArchiveCapability.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/NoneArchiveAdapter.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveCapabilityHealthIndicator.kt backend/src/main/resources/application.yml backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveCapabilityTest.kt
git commit -m "feat(archive): expose truthful archive readiness"
```

### Task 5: Implement Filesystem Staging and a Non-Long-Term Receipt

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveEvidence.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/FilesystemStagingArchiveAdapter.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/FilesystemStagingArchiveTest.kt`

- [ ] **Step 1: Write failing path, digest, and receipt tests**

Use `@TempDir` to create an explicit root and source ZIP. Test that a successful probe yields `LOCAL_PILOT`; a source outside root is rejected; an expected SHA-256 mismatch preserves the source and creates no receipt; replaying the same command returns the same locator; an existing target with another digest fails closed; a successful receipt has `longTerm=false`, `retentionPolicy=PILOT_ONLY`, and `immutabilityControl=NONE`.

- [ ] **Step 2: Run and verify that the Adapter is missing**

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.FilesystemStagingArchiveTest"
```

Expected: FAIL with unresolved `FilesystemStagingArchiveAdapter`.

- [ ] **Step 3: Implement the `ArchiveEvidence` Gate**

The service evaluates before every call. `enabled=false`, `UNCONFIGURED`, and `EXTERNAL_UNVERIFIED` throw `ArchiveUnavailable`; `COMPANY` proceeds only with `EXTERNAL_VERIFIED`; `PILOT` plus `LOCAL_PILOT` may create a staging receipt, but it remains `longTerm=false`.

- [ ] **Step 4: Implement safe filesystem staging**

Resolve the source with `toRealPath()` and require it to be within the explicit staging root. Build a normalized target from `objectPrefix/acceptanceId/sourceCommit/sourceArtifactId`, then require `startsWith(root)` again. Copy first to a sibling `.partial`, recompute SHA-256, and then atomically move. Replay accepts an existing target only when its digest equals expected. Write the receipt with Jackson as `<sourceArtifactId>-archive-receipt.json`, and never overwrite a different existing receipt.

- [ ] **Step 5: Run the tests**

Use the command from Step 2. Expected: PASS with no file changes outside the temporary directory.

- [ ] **Step 6: Commit the filesystem Adapter**

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveEvidence.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/FilesystemStagingArchiveAdapter.kt backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/FilesystemStagingArchiveTest.kt
git commit -m "feat(archive): add pilot filesystem staging"
```

### Task 6: Add the Minimal AWS SDK and Build a Safe Client

**Files:**
- Modify: `backend/build.gradle.kts`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3Gateway.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveConfiguration.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/S3ConfigurationTest.kt`

- [ ] **Step 1: Write a failing test that NONE mode creates no S3 client**

Assert that the default context has no `S3Client`; `S3_COMPATIBLE` creates a client only with a test credential provider; Endpoint, Region, and Bucket do not appear in bean `toString()` or exception details.

- [ ] **Step 2: Add pinned dependencies**

```kotlin
implementation(platform("software.amazon.awssdk:bom:2.54.4"))
implementation("software.amazon.awssdk:s3")
implementation("software.amazon.awssdk:url-connection-client")
```

Do not add the complete `aws-sdk-java`, Transfer Manager, LocalStack, or a second configuration library.

- [ ] **Step 3: Implement the conditional `S3Client`**

Create it only when Provider is `S3_COMPATIBLE`. Use `DefaultCredentialsProvider`, take Region from normalized configuration, apply an endpoint override only when Endpoint is non-empty, and enable path-style access for S3-compatible endpoints. Never read access or secret keys from Git or YAML.

- [ ] **Step 4: Define the narrow `S3Gateway`**

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

`AwsS3Gateway` converts SDK exceptions into secret-free `ArchiveUnavailable` values while preserving the operation and AWS error code.

- [ ] **Step 5: Run dependency and configuration tests**

```powershell
./backend/gradlew -p backend dependencies --configuration runtimeClasspath
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.S3ConfigurationTest"
```

Expected: the dependency tree contains only AWS SDK v2 `2.54.4`; tests PASS.

- [ ] **Step 6: Commit S3 wiring**

```powershell
git add backend/build.gradle.kts backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveConfiguration.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3Gateway.kt backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/S3ConfigurationTest.kt
git commit -m "feat(archive): wire minimal s3 client"
```

### Task 7: Implement S3 Probe, Read-Back Verification, and Long-Term Receipt

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3ArchiveAdapter.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/S3ArchiveAdapterTest.kt`

- [ ] **Step 1: Write a failing control-matrix test**

Use an in-memory fake `S3Gateway` to cover unreachable, missing encryption, public access, missing versioning, missing Object Lock, bucket default retention shorter than configuration, and all controls passing. Only every required control passing may let the evaluator produce `EXTERNAL_VERIFIED`.

- [ ] **Step 2: Write archive failure-path tests**

Cover upload errors producing no receipt; read-back digest mismatch preserving the source and throwing `ArchiveIntegrityFailure`; receipt upload failure never deleting the payload; success returning a destination locator of `s3://<bucket>/<key>`, a stable receipt locator, and `longTerm=true`; replaying the same source produces the same key and never overwrites different content.

- [ ] **Step 3: Run and verify that the Adapter is missing**

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.S3ArchiveAdapterTest"
```

Expected: FAIL with unresolved `S3ArchiveAdapter`.

- [ ] **Step 4: Implement probe**

Map `S3ControlSnapshot` into fixed check names: `connection`, `encryption`, `privateAccess`, `versioning`, `immutability`, and `retention`. Record only booleans and generic reasons. Round configured `retentionPeriod` up to whole days; bucket default retention must be at least that value.

- [ ] **Step 5: Implement content-addressed archival**

Use a normalized key of `objectPrefix/acceptanceId/sourceCommit/<sha256>/<sourceArtifactId>.zip`. Upload with create-if-absent, download to a temporary file, and recompute SHA-256. After success, serialize the receipt and upload it create-if-absent as `<sourceArtifactId>-archive-receipt.json`. Delete the temporary read-back file in finally, but never delete the source or an uploaded payload.

- [ ] **Step 6: Run the S3 Adapter tests**

Use the command from Step 3. Expected: PASS.

- [ ] **Step 7: Commit the S3 Adapter**

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3ArchiveAdapter.kt backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/S3ArchiveAdapterTest.kt
git commit -m "feat(archive): verify and archive evidence to s3"
```

### Task 8: Integration Verification, Runbook, and Bilingual Synchronization

**Files:**
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/ApplicationContextTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveProfileIntegrationTest.kt`
- Modify: `docs/m1/runbook.md`
- Modify: `docs/v0.2/13-deployment-design.md`
- Modify: `docs/superpowers/specs/2026-08-26-pilot-company-deployment-profile-design.md` only if implementation exposes a verified contradiction

- [ ] **Step 1: Write Profile integration tests**

Use Spring contexts for default `PILOT` plus `NONE`, `PILOT` plus filesystem, and `COMPANY` plus `NONE`. Assert that the default context starts; the filesystem report is `LOCAL_PILOT`; Company readiness is DOWN; no long-term archival `PASS` can exist without a successful Archive Receipt. Tests must not require real corporate credentials.

- [ ] **Step 2: Update the runbook**

Document every environment variable, the Profile matrix, secret injection rules, readiness checks, why staging is not long-term archival, S3 transition steps, read-back digest checks, and rollback without object deletion. Do not include real credentials, internal endpoints, or temporary presigned URLs.

- [ ] **Step 3: Run targeted tests**

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.*" --tests "com.ricezhou.vsrqg.ApplicationContextTest" --tests "com.ricezhou.vsrqg.ArchitectureTest"
```

Expected: PASS with 0 failed.

- [ ] **Step 4: Check the diff and secret leakage**

```powershell
git diff --check
rg -n -i "aws_access_key_id|aws_secret_access_key|password=|presigned" backend/src docs/m1 docs/v0.2
```

Expected: `git diff --check` has no output; the search finds only prohibitive documentation and test fixture key names, with no secret values.

- [ ] **Step 5: Commit integration and documentation**

```powershell
git add backend/src/test/kotlin/com/ricezhou/vsrqg/ApplicationContextTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveProfileIntegrationTest.kt docs/m1/runbook.md docs/v0.2/13-deployment-design.md
git commit -m "test(archive): verify pilot and company profiles"
```

- [ ] **Step 6: Run the complete M1 Gate on a clean commit**

```powershell
./scripts/m1/verify.ps1
```

Expected: `PASS M1 gates=contract,build,test,security,concurrency,smoke,recovery` and an `evidence.json` for the current commit; the worktree is clean at the beginning and end of the Gate.

- [ ] **Step 7: Synchronize the English branch**

Cherry-pick every non-Markdown commit from Tasks 2 through 7 in order onto `feat/m1-release-manifest-en`. TDR, runbook, deployment design, and this plan use paired pure-English content. Never push Chinese Markdown to the English branch. Non-Markdown blobs must be identical in both branches.

- [ ] **Step 8: Run the bilingual and byte Gate**

```powershell
./scripts/verify-language-branches.ps1 -ChineseRef feat/m1-release-manifest -EnglishRef feat/m1-release-manifest-en -Mode Pair
```

Expected: `PASS mode=Pair`; English Markdown Han count is `0`; non-Markdown diff is `0`.

- [ ] **Step 9: Push normally and verify CI**

Verify that each remote branch is an ancestor of its local HEAD and use a normal push; force push is prohibited. Wait for the `M1 Backend` run for both exact HEAD values and record run ID, Artifact ID, digest, and conclusion. If CI fails, preserve failure Evidence and fix the root cause without weakening tests or Capability conditions.

## Plan Completion Criteria

- Default `PILOT` starts without corporate resources.
- Every target-control boolean defaults to `true`, but configuration never fabricates actual state.
- Filesystem produces only `LOCAL_PILOT` and a `longTerm=false` receipt.
- S3 produces `EXTERNAL_VERIFIED` only after connection, encryption, private access, versioning, Object Lock, and retention all verify.
- `COMPANY` without `EXTERNAL_VERIFIED` fails readiness and archival operations closed, while liveness does not depend on external storage.
- Upload, read-back, and receipt digests are reviewable; failure never deletes the source and never silently falls back.
- Configuration never changes the current `M1-OWNER-GATE-001` automatically.
- Chinese and English CI pass, the Pair Gate passes, and every non-Markdown file is byte-identical.
