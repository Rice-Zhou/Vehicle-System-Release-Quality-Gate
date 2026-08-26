# Pilot / Company Dual-Mode Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `PILOT` / `COMPANY` deployment Profiles, truthful Archive Capability, filesystem staging, S3-compatible long-term archival, and auditable Archive Receipts without changing the frozen V0.1 architecture.

**Architecture:** Configuration enters a framework-independent application contract through `ArchivePolicy`. Public `ArchiveEvidence` is the only archive entry point, and internal `ArchiveAdapter` is the only Port and accepts only opaque `ArchiveAuthorization` issued by the same internal evaluator. Every readiness evaluation and archive command uses a fresh probe. External Provider requests use bounded timeouts, while filesystem staging uses atomic partial recovery. S3 reads and writes bind exact object versions, and an independent receipt reference avoids a self-hash cycle.

**Tech Stack:** Kotlin 2.2.21, Spring Boot 3.5.16, Java 21, Spring Actuator, Jackson, AWS SDK for Java v2 BOM `2.54.4`, JUnit 5, AssertJ, Mockito, and Gradle.

---

## Implementation Boundary and File Structure

This plan adds only shared deployment and archival implementation. It does not prematurely create a complete Evidence Domain, database tables, a Controller, or a second Quality Engine. An Archive Receipt is deployment and acceptance evidence, not a Core Evidence Entity.

New file responsibilities:

- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveModels.kt`: framework-independent configuration, state, commands, and receipts.
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveAdapter.kt`: the only internal Port and opaque authorization.
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/EvaluateArchiveCapability.kt`: the only trusted evaluator, deriving a report or issuing authorization.
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveEvidence.kt`: the only public entry point and pre-execution Profile gate.
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

The document must state that Profiles do not change the Core Contract; target booleans default to `true`; only a single-use fresh probe produces Capability; `FILESYSTEM_STAGING` cannot produce long-term archival `PASS`; the `COMPANY` READY invariant; bounded timeouts; actual immutable protection for payload and receipt; AWS SDK v2 is imported through BOM `2.54.4` with only the S3 module; credentials use the default credential chain; migration never deletes source objects.

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

data class StoredObjectRef(
    val provider: ArchiveProvider,
    val locator: String,
    val bucket: String?,
    val key: String,
    val versionId: String?,
    val sha256: String,
    val sizeBytes: Long,
)

data class ArchiveReceipt(
    val acceptanceId: String,
    val sourceArtifactId: String,
    val sourceRunId: String,
    val sourceCommit: String,
    val sourceSha256: String,
    val payload: StoredObjectRef,
    val accessOwner: String,
    val retentionPolicy: String,
    val immutabilityControl: String,
    val policyFingerprint: String,
    val capabilityCheckedAt: Instant,
    val archivedAt: Instant,
    val verifier: String,
    val longTerm: Boolean,
)

data class ArchiveReceiptReference(
    val locator: String,
    val versionId: String?,
    val sha256: String,
)

data class ArchiveResult(
    val receipt: ArchiveReceipt,
    val receiptReference: ArchiveReceiptReference,
)

class ArchiveUnavailable(message: String) : IllegalStateException(message)
class ArchiveIntegrityFailure(message: String) : IllegalStateException(message)
```

`ArchiveAdapter.kt`:

```kotlin
package com.ricezhou.vsrqg.shared.application.archive

internal class ArchiveAuthorization internal constructor(
    internal val report: ArchiveCapabilityReport,
    private val issuer: Any,
) {
    internal fun requireIssuedBy(expectedIssuer: Any) {
        require(issuer === expectedIssuer) { "Archive authorization was not issued by the trusted evaluator" }
    }
}

internal interface ArchiveAdapter {
    val provider: ArchiveProvider
    fun probe(policy: ArchivePolicy, context: CapabilityProbeContext): List<CapabilityCheck>
    fun archive(
        command: ArchiveCommand,
        policy: ArchivePolicy,
        authorization: ArchiveAuthorization,
    ): ArchiveResult
}
```

The contract test also constructs these models with named arguments and asserts `probeTimeout=PT5S` and `operationTimeout=PT30S`; `CapabilityProbeContext`, report, and receipt use the same 64-character lowercase-hex `policyFingerprint`; context and report have the same `checkedAt`, and receipt `capabilityCheckedAt` also equals it. An S3 `StoredObjectRef` requires a non-empty bucket and `versionId`; a filesystem ref allows both to be absent. Receipt contains none of its own locator/version/digest; the independent `ArchiveReceiptReference` holds those values. A business-call boundary rejects every invalid digest, fingerprint, or Provider/ref combination.

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

Use `ApplicationContextRunner` to assert that the default `ArchivePolicy` is `PILOT` plus `NONE`; all six target-control booleans are `true`; `objectPrefix` is `acceptance/`; `probeTimeout` is `PT5S`; `operationTimeout` is `PT30S`; `../escape` causes bean creation failure; a relative `stagingRoot` with `FILESYSTEM_STAGING` causes failure; a zero or negative timeout and an operation timeout shorter than the probe timeout each cause failure; missing external properties in `COMPANY` do not cause startup failure and are reported as NOT_READY only by Capability. Positive Endpoint cases cover absolute `http` and `https` URIs. Negative cases cover a relative URI, a non-`http`/`https` scheme, an empty host, user-info, query, and fragment, and the bean creation error must not echo the original URI.

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
      probe-timeout: ${VSRQG_EVIDENCE_ARCHIVE_PROBE_TIMEOUT:PT5S}
      operation-timeout: ${VSRQG_EVIDENCE_ARCHIVE_OPERATION_TIMEOUT:PT30S}
```

- [ ] **Step 4: Implement `ArchiveConfiguration`**

Define two `@ConfigurationProperties` data classes and use one `@Bean` to normalize empty strings and durations. Reject only dangerous formats: a prefix that is empty, absolute, contains `..`, or contains backslashes; when `FILESYSTEM_STAGING` is selected, staging root must be configured and absolute; non-positive retention must be rejected; `probe-timeout` and `operation-timeout` must be positive, and the latter must be greater than or equal to the former. A non-empty Endpoint must be an absolute `http` or `https` URI with a non-empty host and no user-info, query, or fragment. A failure message uses only a generic field name and reason and never concatenates the URI. Missing Endpoint/Bucket/owner/retention in `COMPANY` does not throw during startup and is handled as truthful NOT_READY by Task 4. The two timeouts constrain external Provider requests. Filesystem staging retains both configuration values in the fingerprint but does not promise cancelable local I/O timeouts.

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
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveBoundaryTest.kt`

- [ ] **Step 1: Write a failing state-matrix test**

Use counting fake `ArchiveAdapter` instances to cover: `NONE` to `UNCONFIGURED`; all filesystem checks passing to `LOCAL_PILOT`; any S3 check failing to `EXTERNAL_UNVERIFIED`; every S3 check passing to `EXTERNAL_VERIFIED`; duplicate Provider Adapters fail construction; two consecutive readiness calls and two consecutive archive-authorization calls each invoke two probes. For the same normalized policy, `policyFingerprint` is stable 64-character lowercase hexadecimal. Changing Profile, Provider, a policy boolean, path, Endpoint, Region, Bucket, prefix, owner, retention, or timeout one at a time changes the fingerprint. Also assert that health probes again on every call: `PILOT` is UP while preserving the real state; `COMPANY` is UP only with `enabled=true` and `EXTERNAL_VERIFIED`; `enabled=false` leaves the Provider-derived state unchanged but health is DOWN.

`ArchiveBoundaryTest` proves the framework-independent package boundary: the only public API is `ArchiveEvidence.archive(ArchiveCommand)`, which accepts no `ArchiveCapabilityReport`, `ArchivePolicy`, or `ArchiveAuthorization`; `ArchiveAdapter`, adapter implementations, evaluator, and authorization are internal. ArchUnit asserts that only the evaluator calls `ArchiveAdapter.probe`, only `ArchiveEvidence` calls `ArchiveAdapter.archive`, only the evaluator constructs authorization, and application does not depend on a concrete adapter. Within the same module, a test constructs forged authorization from a fake report and a different issuer and asserts that the trusted evaluator rejects it. Do not add a second Capability evaluator, cache, or path that derives state directly from configuration.

- [ ] **Step 2: Run and verify that the evaluator is missing**

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.ArchiveCapabilityTest"
```

Expected: FAIL with unresolved `EvaluateArchiveCapability`.

- [ ] **Step 3: Implement the evaluator**

```kotlin
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Locale

internal class EvaluateArchiveCapability(
    adapters: List<ArchiveAdapter>,
    private val timeProvider: TimeProvider,
) {
    private val issuer = Any()
    private val adaptersByProvider = adapters.associateBy { it.provider }.also {
        require(it.size == adapters.size) { "Archive providers must be unique" }
    }

    internal fun evaluateReadiness(policy: ArchivePolicy): ArchiveCapabilityReport = evaluate(policy)

    internal fun authorizeArchive(policy: ArchivePolicy): ArchiveAuthorization =
        ArchiveAuthorization(evaluate(policy), issuer)

    internal fun requireIssued(authorization: ArchiveAuthorization) =
        authorization.requireIssuedBy(issuer)

    private fun evaluate(policy: ArchivePolicy): ArchiveCapabilityReport {
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

`ArchiveConfiguration` normalizes every nullable string, Path, URI, and duration field before the evaluator. The fingerprint covers only the listed non-secret fields and never exposes their values. Register the evaluator as an internal Spring bean without adding a cache. The evaluator is the only probe and state-derivation source. Before calling an Adapter, it creates `CapabilityProbeContext`, ensuring that the fingerprint and check time used by the S3 probe enter the report exactly. Readiness can obtain only a report. Archive can obtain only opaque authorization carrying the same fresh report, and the evaluator validates the issuer before entering the Adapter. `NoneArchiveAdapter.probe` accepts context and returns one failed `provider` check, and `archive` uses the new Port signature and throws `ArchiveUnavailable`.

- [ ] **Step 4: Implement the health indicator and health groups**

Health calls `evaluateReadiness` for a fresh probe on every invocation. Details expose only `mode`, `provider`, `state`, `policyFingerprint`, `checkedAt`, and each boolean/name/detail check. They never expose Endpoint, credentials, or presigned URLs. `COMPANY` returns DOWN when `enabled=false` or state is not `EXTERNAL_VERIFIED`; `enabled` is an independent Gate and never rewrites the evaluator-derived state. Append `archiveCapability` to the existing readiness group instead of replacing other checks; the liveness group must not depend on external storage.

- [ ] **Step 5: Run the tests**

Use the command from Step 2. Expected: PASS.

- [ ] **Step 6: Commit Capability**

```powershell
git add backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/EvaluateArchiveCapability.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/NoneArchiveAdapter.kt backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveCapabilityHealthIndicator.kt backend/src/main/resources/application.yml backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveCapabilityTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveBoundaryTest.kt
git commit -m "feat(archive): expose truthful archive readiness"
```

### Task 5: Implement Filesystem Staging and a Non-Long-Term Receipt

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/archive/ArchiveEvidence.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/FilesystemStagingArchiveAdapter.kt`
- Test: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/FilesystemStagingArchiveTest.kt`

- [ ] **Step 1: Write failing path, digest, and receipt tests**

Use `@TempDir` to create an explicit root and source ZIP. Test that a successful probe yields `LOCAL_PILOT`; a source outside root is rejected; an expected SHA-256 mismatch preserves the source and creates no receipt; replaying the same command returns the same locator; an existing target with another digest fails closed; every `ArchiveEvidence.archive(ArchiveCommand)` call increments the probe count; `enabled=false` still produces a truthful report before the independent Gate rejects it without rewriting state. Inject copy, digest, payload-move, receipt-write, and receipt-move failures separately. Assert cleanup of the corresponding `.partial`, preservation of the source and every committed target, and that the next call probes again and retries safely. In a successful `ArchiveResult`, the receipt has `longTerm=false`, `retentionPolicy=PILOT_ONLY`, and `immutabilityControl=NONE`; its `policyFingerprint` and `capabilityCheckedAt` exactly equal this authorization report. The payload `StoredObjectRef` and separate `ArchiveReceiptReference` use filesystem locators and SHA-256 values, and their `bucket` and `versionId` are null. Do not use sleep, fake async, or local-I/O timeout assertions.

- [ ] **Step 2: Run and verify that the Adapter is missing**

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.FilesystemStagingArchiveTest"
```

Expected: FAIL with unresolved `FilesystemStagingArchiveAdapter`.

- [ ] **Step 3: Implement the `ArchiveEvidence` Gate**

`ArchiveEvidence` is a public facade with an internal constructor, and its only public method is `archive(ArchiveCommand): ArchiveResult`. Trusted wiring injects policy and evaluator, so a caller cannot submit a report or authorization. On every call, the service obtains opaque authorization from `authorizeArchive` and has the evaluator validate its issuer. It then separately checks `enabled=false` from the internal authorization report and throws `ArchiveUnavailable` without rewriting Provider state. `UNCONFIGURED` and `EXTERNAL_UNVERIFIED` are rejected. `COMPANY` proceeds only with `enabled=true` and `EXTERNAL_VERIFIED` in this report. `PILOT` plus `LOCAL_PILOT` may create a staging receipt, but it remains `longTerm=false`. Pass only validated authorization to internal `ArchiveAdapter.archive`. Every operation failure naturally discards authorization, and a retry starts with a new probe.

- [ ] **Step 4: Implement safe filesystem staging**

Resolve the source with `toRealPath()` and require it to be within the explicit staging root. Build a normalized target from `objectPrefix/acceptanceId/sourceCommit/sourceArtifactId`, then require `startsWith(root)` again. Copy synchronously to a sibling `.partial`, recompute SHA-256, and then atomically move. Replay accepts an existing target only when its digest equals expected. Use Jackson to write the receipt to another `.partial`; include the payload ref and authorization report `policyFingerprint` and `checkedAt`; then atomically move it to `<sourceArtifactId>-archive-receipt.json` without overwriting a different existing receipt. Each failure path cleans up only uncommitted partial files. Filesystem staging neither wraps threads with `operationTimeout` nor promises cancelable local file I/O timeouts. Compute the returned receipt-reference digest over the final receipt file separately, avoiding a receipt that contains its own digest.

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

Assert that the default context has no `S3Client`; `S3_COMPATIBLE` creates a client only with a test credential provider; Endpoint, Region, Bucket, and full URI do not appear in bean `toString()` or exception details. Then use a fake/interceptor to assert that every control request receives `probeTimeout`, and every upload, download, HeadObject-style protection check, and receipt request receives `operationTimeout`; timeout exceptions become secret-free `ArchiveUnavailable` values. The gateway contract test also proves that Put returns a `StoredObjectRef` with an exact `versionId` and that download and head accept only that ref rather than a bare key. A delete marker, a new version under the same key, or concurrent replacement must never silently switch a read to latest.

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
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class ObjectProtectionSnapshot(
    val actualMode: String?,
    val retainUntil: Instant?,
)

enum class MutationCheckResult { DENIED_AS_EXPECTED, ALLOWED, INDETERMINATE }

data class DailyControlResult(
    val policyFingerprint: String,
    val utcDate: LocalDate,
    val validUntil: Instant,
    val target: StoredObjectRef?,
    val result: StoredObjectRef?,
    val overwrite: MutationCheckResult,
    val delete: MutationCheckResult,
    val bypass: MutationCheckResult,
)

data class S3ControlSnapshot(
    val reachable: Boolean,
    val encrypted: Boolean,
    val privateAccess: Boolean,
    val versioningEnabled: Boolean,
    val objectLockEnabled: Boolean,
    val defaultRetentionDays: Long?,
    val controlObjectProtection: ObjectProtectionSnapshot?,
    val dailyControl: DailyControlResult?,
)

interface S3Gateway {
    fun controls(
        bucket: String,
        targetKey: String,
        resultKey: String,
        policyFingerprint: String,
        utcDate: LocalDate,
        requiredRetainUntil: Instant,
        validUntil: Instant,
        timeout: Duration,
    ): S3ControlSnapshot
    fun putFileIfAbsent(bucket: String, key: String, source: Path, sha256: String, timeout: Duration): StoredObjectRef
    fun download(source: StoredObjectRef, target: Path, timeout: Duration)
    fun putJsonIfAbsent(bucket: String, key: String, bytes: ByteArray, sha256: String, timeout: Duration): StoredObjectRef
    fun headProtection(source: StoredObjectRef, timeout: Duration): ObjectProtectionSnapshot
}
```

`ObjectProtectionSnapshot` is a Provider-neutral object-protection contract. `controls` runs an atomic create-only race for the deterministic target key. Only the winner that creates it performs negative overwrite/delete/bypass attempts against that target version and writes `DailyControlResult` with a create-only result key. A loser only reads the recorded result by exact result version; if the result is still absent or inconsistent within `probeTimeout`, the outcome is `INDETERMINATE`. Only an explicit Provider permission denial is `DENIED_AS_EXPECTED`. A network error, timeout, 5xx, or unknown error is always `INDETERMINATE` and never counts as denial. An Evidence key is never used for destructive checks.

Every Put returns `StoredObjectRef` with actual bucket/key/versionId/SHA-256. S3 `versionId` must be non-empty, and download and HeadObject-style checks must specify that exact version with no fallback to latest. Every SDK request builds a per-request API call timeout from the supplied Duration. `AwsS3Gateway` converts SDK exceptions and timeouts into `ArchiveUnavailable` values that contain no endpoint, credential, token, or URI while preserving the operation and AWS error code.

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

Use an in-memory fake `S3Gateway` to cover unreachable, missing encryption, public access, missing versioning, a true bucket Object Lock flag alone, a control target without actual mode, retain-until shorter than policy, each mutation result being `ALLOWED` or `INDETERMINATE`, and all being `DENIED_AS_EXPECTED`. Network errors, timeouts, and 5xx responses must map to `INDETERMINATE` and cannot impersonate denial. Assert exact target/result keys of `objectPrefix/capability-probe/<policyFingerprint>/<yyyy-MM-dd>/target.json` and `objectPrefix/capability-probe/<policyFingerprint>/<yyyy-MM-dd>/result.json`, using the UTC date from `CapabilityProbeContext.checkedAt`. Required retain-until is exactly the next UTC midnight plus `retentionPeriod`, and result validity ends exactly at the next UTC midnight.

Sequential and concurrent probes for the same policy fingerprint and day allow only one atomic create-only winner to perform one mutation-negative test. Other invocations read only the same result by exact version. A result not yet visible, inconsistent, or expired fails closed. Repeated calls on the same day write no more objects; only another date or fingerprint permits new target/result objects, at most two small objects per policy fingerprint per day. Test that lifecycle cleanup is permitted only after each retain-until and that failure never actively deletes. Every control call receives `probeTimeout`. Only all required controls passing with all three mutation results equal to `DENIED_AS_EXPECTED` lets the evaluator produce `EXTERNAL_VERIFIED`.

- [ ] **Step 2: Write archive failure-path tests**

Cover upload errors producing no receipt; read-back digest mismatch preserving the source and throwing `ArchiveIntegrityFailure`; receipt upload failure never deleting the payload; payload or receipt HeadObject-style mode missing, retain-until earlier than `archivedAt + retentionPeriod`, or receipt mode differing from the recorded value fails closed. The fake proves that negative overwrite/delete/bypass attempts target only the control target and never a payload or receipt key. The Put-returned payload `StoredObjectRef` contains bucket/key/versionId/sha256, and read-back plus protection checks bind that version. A later version under the same key, a delete marker, or concurrent replacement cannot change the verified object; fallback to latest is prohibited.

The candidate `ArchiveReceipt` records the complete payload ref, `policyFingerprint`, `capabilityCheckedAt`, and the actual mode or approved equivalent verified identically on both actual objects, but not its own locator/version/digest. Receipt Put returns a second `StoredObjectRef`; only after verifying that exact version is a separate `ArchiveReceiptReference` derived. Acceptance evidence stores this reference, avoiding a receipt self-hash cycle. A successful `ArchiveResult` destination is `s3://<bucket>/<key>` and has `longTerm=true`; every upload/download/head/receipt call receives `operationTimeout`. Replaying the same source returns the same exact ref and never overwrites different content. Every failure preserves the source, payload, control target/result, and any uploaded receipt.

- [ ] **Step 3: Run and verify that the Adapter is missing**

```powershell
./backend/gradlew -p backend test --tests "com.ricezhou.vsrqg.shared.archive.S3ArchiveAdapterTest"
```

Expected: FAIL with unresolved `S3ArchiveAdapter`.

- [ ] **Step 4: Implement probe**

First produce explicit failed checks for a missing bucket, owner, or positive retention without calling a Gateway method that requires those values. When configuration is complete, build normalized `objectPrefix/capability-probe/<policyFingerprint>/<yyyy-MM-dd>/target.json` and `objectPrefix/capability-probe/<policyFingerprint>/<yyyy-MM-dd>/result.json` from `CapabilityProbeContext` `policyFingerprint` and the UTC date of `checkedAt`. Target content is fixed as `{"purpose":"archive-capability-probe","version":1}` and contains no Evidence or secret. `requiredRetainUntil` is `nextUtcMidnight(checkedAt) + retentionPeriod`, and `validUntil` is `nextUtcMidnight(checkedAt)`.

The Gateway atomic create-only winner performs at most one mutation-negative test per fingerprint per day. A loser reads only the recorded result. The result records fixed target exact ref, three `MutationCheckResult` values, policy fingerprint, UTC date, and validity. A fresh probe on the same day still calls the Gateway but reuses the recorded result without repeating mutation. A new date is required after `validUntil`. Target/result lifecycle cleanup is permitted only after their retention ends, bounding garbage to two small objects per policy fingerprint per day. Any race, read, network, or timeout uncertainty fails closed and preserves objects for recovery and audit.

Map `S3ControlSnapshot` into fixed check names: `connection`, `encryption`, `privateAccess`, `versioning`, `immutability`, and `retention`. Record only booleans and generic reasons. Round configured `retentionPeriod` up to whole days; bucket default retention must be at least that value, but the bucket Object Lock flag alone cannot pass `immutability`. Immutability passes only when the target exact version has actual mode, retain-until satisfies `requiredRetainUntil`, the result is unexpired, and all three results are `DENIED_AS_EXPECTED`. Never run overwrite, delete, or bypass tests against an Evidence key.

- [ ] **Step 5: Implement content-addressed archival**

Use a normalized key of `objectPrefix/acceptanceId/sourceCommit/<sha256>/<sourceArtifactId>.zip`. Use `operationTimeout` for create-if-absent upload and obtain payload `StoredObjectRef`; then download a temporary file by that exact ref and perform SHA-256 read-back. Call `headProtection` with the same exact ref to verify payload actual mode and retain-until, with `archivedAt + retentionPeriod` as the minimum effective retention.

The candidate receipt includes the payload exact ref, this authorization report's `policyFingerprint`, `checkedAt`, and the actual mode or approved equivalent already verified on the control target and payload. Upload it create-if-absent as `<sourceArtifactId>-archive-receipt.json` and obtain receipt `StoredObjectRef`. After upload, call `headProtection` with the receipt exact ref. Only when actual modes for payload and receipt both match the recorded value, both retain-until values satisfy policy, and this unexpired control result proves runtime identity cannot overwrite/delete/bypass may a separate `ArchiveReceiptReference` be derived from the receipt ref and a long-term `ArchiveResult` returned; later acceptance evidence stores that reference. Any probe, upload, read-back, Head, or receipt failure discards this authorization without caching and never deletes the source, control target/result, or any uploaded object. Delete only the temporary read-back file in finally.

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
- Modify/Create paired ZH and EN: `docs/m1/runbook.md`
- Modify/Create paired ZH and EN: `docs/v0.2/13-deployment-design.md`

- [ ] **Step 1: Write Profile integration tests**

Use Spring contexts for default `PILOT` plus `NONE`, `PILOT` plus filesystem, `COMPANY` plus `NONE`, `COMPANY` plus `archive.enabled=false` and a verifiable fake Provider, and `COMPANY` plus `archive.enabled=true` and `EXTERNAL_VERIFIED`. Assert that the default context starts; the filesystem report is `LOCAL_PILOT`; when Company is disabled the Provider state remains truthful but readiness is DOWN; Company archive-Capability readiness is UP only when both conditions hold; consecutive readiness calls increment the probe count without replacing other readiness checks; liveness always remains independent.

Integration tests also cover: the public facade accepts no report/policy/authorization, forged authorization is rejected, and architecture dependency rules hold; strict Endpoint validation with no URI disclosure in errors; default and invalid timeouts; filesystem partial-failure recovery without a cancelable-I/O-timeout assumption; concurrent same-day probes with one control winner while losers read `DENIED_AS_EXPECTED`/`ALLOWED`/`INDETERMINATE` results and never treat a network error as denial; policy or date changes produce a new fingerprint/control; payload and receipt use exact version refs throughout, with version shadow, delete marker, and concurrent replacement failing closed; `ArchiveReceiptReference` is stored separately without a self-hash cycle. No long-term archival `PASS` is produced without a successful Archive Receipt. Tests must not require real corporate credentials.

- [ ] **Step 2: Update the runbook**

First update `docs/m1/runbook.md` and `docs/v0.2/13-deployment-design.md` in the Chinese worktree. Document every environment variable, including `VSRQG_EVIDENCE_ARCHIVE_PROBE_TIMEOUT` and `VSRQG_EVIDENCE_ARCHIVE_OPERATION_TIMEOUT`; explain that timeouts constrain external Provider calls while filesystem uses atomic partial cleanup/retry. Document the Profile/enablement matrix, trusted facade, fresh probes and fingerprint, readiness/liveness boundary, Endpoint rules, secret injection, why staging is not long-term archival, daily target/result control, explicit mutation states and validity, exact-version payload/receipt, separate receipt reference, S3 transition, read-back digest, and fail-closed rollback. Do not include real credentials, internal endpoints, or temporary presigned URLs.

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

- [ ] **Step 5: Commit shared non-Markdown integration tests separately**

```powershell
git add backend/src/test/kotlin/com/ricezhou/vsrqg/ApplicationContextTest.kt backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/ArchiveProfileIntegrationTest.kt
git diff --cached --name-only
git commit -m "test(archive): verify pilot and company profiles"
```

Expected: staging and the commit contain only shared non-Markdown tests and no Markdown.

- [ ] **Step 6: Synchronize every shared commit to the English branch**

Cherry-pick every shared non-Markdown commit from Tasks 2 through 8 in order onto `feat/m1-release-manifest-en`, including the just-committed Task 8 integration tests; do not stop at Task 7. Never cherry-pick Chinese Markdown. On completion, every non-Markdown blob must be identical in both branches.

- [ ] **Step 7: Commit Chinese Markdown separately**

```powershell
git add docs/m1/runbook.md docs/v0.2/13-deployment-design.md
git diff --cached --name-only
git commit -m "docs(v0.2): document pilot and company deployment"
```

Expected: the commit contains only the Chinese runbook and deployment design.

- [ ] **Step 8: Create or update and commit English Markdown**

In the English worktree, explicitly create a missing file or update the existing `docs/m1/runbook.md` and `docs/v0.2/13-deployment-design.md` with pure-English content semantically paired to Chinese. Do not cherry-pick Markdown from the Chinese branch. Then run:

```powershell
git add docs/m1/runbook.md docs/v0.2/13-deployment-design.md
git diff --cached --name-only
git commit -m "docs(v0.2): document pilot and company deployment"
```

Expected: the commit contains only the English runbook and deployment design, and the English Markdown Han count is 0.

- [ ] **Step 9: Verify two clean HEADs and identical shared bytes**

```powershell
git status --short
git diff --check HEAD~1 HEAD
```

Run this in both worktrees; `git status --short` has no output. Compare blob IDs for every non-Markdown path across branches. They must be identical before M1 begins.

- [ ] **Step 10: Run the complete M1 Gate on the clean Chinese HEAD**

```powershell
./scripts/m1/verify.ps1
```

Expected: `PASS M1 gates=contract,build,test,security,concurrency,smoke,recovery` and an `evidence.json` for the current commit; the worktree is clean at the beginning and end of the Gate.

- [ ] **Step 11: Run the complete M1 Gate on the clean English HEAD**

```powershell
./scripts/m1/verify.ps1
```

Expected: `PASS M1 gates=contract,build,test,security,concurrency,smoke,recovery` and an `evidence.json` for the current English commit; the worktree is clean at the beginning and end of the Gate.

- [ ] **Step 12: Run the bilingual and byte Gate**

```powershell
./scripts/verify-language-branches.ps1 -ChineseRef feat/m1-release-manifest -EnglishRef feat/m1-release-manifest-en -Mode Pair
```

Expected: `PASS mode=Pair`; English Markdown Han count is `0`; non-Markdown diff is `0`.

- [ ] **Step 13: Push normally and verify CI**

Only after the complete M1 passes for both clean HEADs and the Pair Gate passes, verify that each remote branch is an ancestor of its local HEAD and use a normal push; force push is prohibited. Wait for the `M1 Backend` run for both exact HEAD values and record run ID, Artifact ID, digest, and conclusion. If CI fails, preserve failure Evidence and fix the root cause without weakening tests or Capability conditions.

## Plan Completion Criteria

- Default `PILOT` starts without corporate resources.
- Every target-control boolean defaults to `true`, but configuration never fabricates actual state.
- Public `ArchiveEvidence.archive(ArchiveCommand)` is the only entry, and the internal evaluator/authorization/Adapter form the only trusted chain. A caller-forged report or authorization cannot trigger archival, and architecture tests prevent a second data source.
- Filesystem produces only `LOCAL_PILOT` and a `longTerm=false` receipt. `operationTimeout` does not impersonate a local I/O cancellation mechanism; partial cleanup and atomic commit are recoverable.
- S3 produces `EXTERNAL_VERIFIED` only after connection, encryption, private access, versioning, daily target/result control actual protection, and all three `DENIED_AS_EXPECTED` results satisfy policy. A bucket flag alone is ineffective; `ALLOWED`, `INDETERMINATE`, network errors, and timeouts all fail closed.
- `COMPANY` archive readiness is UP only with `archive.enabled=true` and fresh state `EXTERNAL_VERIFIED`; otherwise readiness and archive operations fail closed while liveness and other readiness checks remain independent.
- Capability probes again for every use. Daily control has one mutation winner, expires at the next UTC midnight, and bounds garbage to two small objects per fingerprint per day. Deterministic `policyFingerprint` plus `checkedAt` enter the receipt; external calls use valid timeouts, and any failure invalidates current authorization.
- Put returns exact-version `StoredObjectRef`, and upload, read-back, and payload/receipt protection bind exact versions. Receipt records the payload ref, and acceptance evidence stores a separate `ArchiveReceiptReference` without a self-hash cycle. Failure never deletes the source, control, or an uploaded object and never falls back to latest.
- Endpoint accepts only an absolute `http`/`https` URI with a non-empty host and no user-info/query/fragment, and errors never echo the URI.
- Configuration never changes the current `M1-OWNER-GATE-001` automatically.
- Task 8 shared tests have a separate commit, the English branch receives every shared Task 2 through 8 commit, and ZH/EN Markdown commits remain separate. Complete M1 passes on both clean HEADs, then Pair Gate passes, then normal push and CI occur, with every non-Markdown file byte-identical.
