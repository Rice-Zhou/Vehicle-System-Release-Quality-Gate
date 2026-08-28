package com.ricezhou.vsrqg.shared.archive

import com.ricezhou.vsrqg.shared.application.archive.ArchiveAdapter
import com.ricezhou.vsrqg.shared.application.archive.ArchiveAuthorization
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCapabilityReport
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCapabilityState
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCommand
import com.ricezhou.vsrqg.shared.application.archive.ArchiveIntegrityFailure
import com.ricezhou.vsrqg.shared.application.archive.ArchivePolicy
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.ArchiveReceipt
import com.ricezhou.vsrqg.shared.application.archive.ArchiveReceiptReference
import com.ricezhou.vsrqg.shared.application.archive.ArchiveResult
import com.ricezhou.vsrqg.shared.application.archive.ArchiveUnavailable
import com.ricezhou.vsrqg.shared.application.archive.CapabilityCheck
import com.ricezhou.vsrqg.shared.application.archive.CapabilityProbeContext
import com.ricezhou.vsrqg.shared.application.archive.DailyControlRecord
import com.ricezhou.vsrqg.shared.application.archive.DailyControlSnapshot
import com.ricezhou.vsrqg.shared.application.archive.DeploymentMode
import com.ricezhou.vsrqg.shared.application.archive.MutationCheckResult
import com.ricezhou.vsrqg.shared.application.archive.RuntimeIdentityRef
import com.ricezhou.vsrqg.shared.application.archive.StoredObjectRef
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class ArchiveContractTest {
    @Test
    fun `contract exposes only governed enum values`() {
        assertThat(DeploymentMode.entries.map { it.name }).containsExactly("PILOT", "COMPANY")
        assertThat(ArchiveProvider.entries.map { it.name })
            .containsExactly("NONE", "FILESYSTEM_STAGING", "S3_COMPATIBLE")
        assertThat(ArchiveCapabilityState.entries.map { it.name })
            .containsExactly("UNCONFIGURED", "LOCAL_PILOT", "EXTERNAL_UNVERIFIED", "EXTERNAL_VERIFIED")
        assertThat(MutationCheckResult.entries.map { it.name })
            .containsExactly("DENIED_AS_EXPECTED", "ALLOWED", "INDETERMINATE")
    }

    @Test
    fun `models preserve the policy snapshot and exact object references`() {
        val policyFingerprint = "a".repeat(64)
        val principalFingerprint = "b".repeat(64)
        val checkedAt = Instant.parse("2026-08-26T03:00:00Z")
        val archivedAt = Instant.parse("2026-08-26T03:00:05Z")
        val policy = ArchivePolicy(
            mode = DeploymentMode.COMPANY,
            enabled = true,
            checksumVerificationEnabled = true,
            encryptionRequired = true,
            privateAccessRequired = true,
            retentionPolicyRequired = true,
            immutabilityRequired = true,
            provider = ArchiveProvider.S3_COMPATIBLE,
            stagingRoot = null,
            endpoint = URI("https://archive.example.test"),
            region = "cn-north-1",
            bucket = "vsrqg-archive",
            objectPrefix = "acceptance/",
            accessOwner = "release-governance",
            retentionPeriod = Duration.ofDays(365),
            probeTimeout = Duration.ofSeconds(5),
            operationTimeout = Duration.ofSeconds(30),
        )
        val check = CapabilityCheck(
            name = "exact-version-readback",
            passed = true,
            detail = "verified",
        )
        val context = CapabilityProbeContext(
            policyFingerprint = policyFingerprint,
            checkedAt = checkedAt,
        )
        val report = ArchiveCapabilityReport(
            mode = DeploymentMode.COMPANY,
            provider = ArchiveProvider.S3_COMPATIBLE,
            state = ArchiveCapabilityState.EXTERNAL_VERIFIED,
            policyFingerprint = policyFingerprint,
            checkedAt = checkedAt,
            checks = listOf(check),
        )
        val command = ArchiveCommand(
            acceptanceId = "acceptance-1",
            sourceArtifactId = "artifact-1",
            sourceRunId = "run-1",
            sourceCommit = "0123456789abcdef",
            source = Path.of("build", "archive", "artifact.zip"),
            expectedSha256 = "c".repeat(64),
        )
        val payload = StoredObjectRef(
            provider = ArchiveProvider.S3_COMPATIBLE,
            locator = "s3://vsrqg-archive/acceptance/artifact.zip",
            bucket = "vsrqg-archive",
            key = "acceptance/artifact.zip",
            versionId = "payload-version-1",
            sha256 = command.expectedSha256,
            sizeBytes = 4096,
        )
        val identity = RuntimeIdentityRef(
            provider = ArchiveProvider.S3_COMPATIBLE,
            principalFingerprint = principalFingerprint,
        )
        val target = StoredObjectRef(
            provider = ArchiveProvider.S3_COMPATIBLE,
            locator = "s3://vsrqg-archive/acceptance/capability-probe/$policyFingerprint/" +
                "$principalFingerprint/2026-08-26/target.json",
            bucket = "vsrqg-archive",
            key = "acceptance/capability-probe/$policyFingerprint/" +
                "$principalFingerprint/2026-08-26/target.json",
            versionId = "target-version-1",
            sha256 = "d".repeat(64),
            sizeBytes = 256,
        )
        val controlRecord = DailyControlRecord(
            policyFingerprint = policyFingerprint,
            identity = identity,
            utcDate = LocalDate.parse("2026-08-26"),
            validUntil = Instant.parse("2026-08-27T00:00:00Z"),
            target = target,
            overwrite = MutationCheckResult.DENIED_AS_EXPECTED,
            delete = MutationCheckResult.DENIED_AS_EXPECTED,
            bypass = MutationCheckResult.DENIED_AS_EXPECTED,
        )
        val resultReference = StoredObjectRef(
            provider = ArchiveProvider.S3_COMPATIBLE,
            locator = "s3://vsrqg-archive/acceptance/capability-probe/$policyFingerprint/" +
                "$principalFingerprint/2026-08-26/result.json",
            bucket = "vsrqg-archive",
            key = "acceptance/capability-probe/$policyFingerprint/" +
                "$principalFingerprint/2026-08-26/result.json",
            versionId = "result-version-1",
            sha256 = "e".repeat(64),
            sizeBytes = 512,
        )
        val controlSnapshot = DailyControlSnapshot(
            record = controlRecord,
            resultReference = resultReference,
        )
        val receipt = ArchiveReceipt(
            acceptanceId = command.acceptanceId,
            sourceArtifactId = command.sourceArtifactId,
            sourceRunId = command.sourceRunId,
            sourceCommit = command.sourceCommit,
            sourceSha256 = command.expectedSha256,
            payload = payload,
            accessOwner = requireNotNull(policy.accessOwner),
            retentionPolicy = "GOVERNANCE_365_DAYS",
            immutabilityControl = "COMPLIANCE",
            policyFingerprint = policyFingerprint,
            capabilityCheckedAt = checkedAt,
            archivedAt = archivedAt,
            verifier = "vsrqg-archive-verifier/1",
            longTerm = true,
        )
        val receiptReference = ArchiveReceiptReference(
            locator = "s3://vsrqg-archive/acceptance/receipt.json",
            versionId = "receipt-version-1",
            sha256 = "f".repeat(64),
            sizeBytes = 640,
        )
        val result = ArchiveResult(
            receipt = receipt,
            receiptReference = receiptReference,
            runtimeIdentity = identity,
        )

        assertThat(policy.probeTimeout).hasToString("PT5S")
        assertThat(policy.operationTimeout).hasToString("PT30S")
        assertThat(context.policyFingerprint).matches("^[0-9a-f]{64}$")
        assertThat(report.policyFingerprint).isEqualTo(context.policyFingerprint)
        assertThat(report.checkedAt).isEqualTo(context.checkedAt)
        assertThat(receipt.policyFingerprint).isEqualTo(context.policyFingerprint)
        assertThat(receipt.capabilityCheckedAt).isEqualTo(context.checkedAt)
        assertThat(report.checks).containsExactly(check)
        assertThat(payload.bucket).isNotBlank()
        assertThat(payload.versionId).isNotBlank()
        assertThat(identity.provider).isEqualTo(target.provider).isEqualTo(resultReference.provider)
        assertThat(identity.principalFingerprint).matches("^[0-9a-f]{64}$")
        assertThat(identity.principalFingerprint).doesNotContain("arn:", "account", "subject")
        assertThat(controlRecord.target).isEqualTo(target)
        assertThat(controlRecord.overwrite).isEqualTo(MutationCheckResult.DENIED_AS_EXPECTED)
        assertThat(controlRecord.delete).isEqualTo(MutationCheckResult.DENIED_AS_EXPECTED)
        assertThat(controlRecord.bypass).isEqualTo(MutationCheckResult.DENIED_AS_EXPECTED)
        assertThat(controlRecord::class.java.declaredFields.map { it.name })
            .doesNotContain("resultReference", "locator", "versionId", "sha256")
        assertThat(controlSnapshot.record).isEqualTo(controlRecord)
        assertThat(controlSnapshot.resultReference).isEqualTo(resultReference)
        assertThat(result.receipt).isEqualTo(receipt)
        assertThat(result.receiptReference).isEqualTo(receiptReference)
        assertThat(result.receiptReference.sizeBytes).isEqualTo(640)
        assertThat(result.runtimeIdentity).isEqualTo(identity)
        assertThat(receiptReference.copy(sizeBytes = 641)).isNotEqualTo(receiptReference)
        assertThat(result.copy(runtimeIdentity = null)).isNotEqualTo(result)
        assertThat(receipt::class.java.declaredFields.map { it.name })
            .doesNotContain("receiptReference", "locator", "versionId", "sha256")
    }

    @Test
    fun `filesystem references may omit bucket and version`() {
        val filesystemReference = StoredObjectRef(
            provider = ArchiveProvider.FILESYSTEM_STAGING,
            locator = "file:///staging/acceptance/artifact.zip",
            bucket = null,
            key = "acceptance/artifact.zip",
            versionId = null,
            sha256 = "1".repeat(64),
            sizeBytes = 128,
        )

        assertThat(filesystemReference.bucket).isNull()
        assertThat(filesystemReference.versionId).isNull()
    }

    @Test
    fun `authorization accepts only the issuing evaluator by reference`() {
        val issuer = Any()
        val report = ArchiveCapabilityReport(
            mode = DeploymentMode.PILOT,
            provider = ArchiveProvider.NONE,
            state = ArchiveCapabilityState.UNCONFIGURED,
            policyFingerprint = "2".repeat(64),
            checkedAt = Instant.parse("2026-08-26T04:00:00Z"),
            checks = listOf(
                CapabilityCheck(
                    name = "provider",
                    passed = false,
                    detail = "not configured",
                ),
            ),
        )
        val authorization = ArchiveAuthorization(
            report = report,
            issuer = issuer,
        )

        authorization.requireIssuedBy(issuer)
        assertThatIllegalArgumentException()
            .isThrownBy { authorization.requireIssuedBy(Any()) }
            .withMessage("Archive authorization was not issued by the trusted evaluator")
    }

    @Test
    fun `adapter port remains provider specific and framework independent`() {
        val issuer = Any()
        val policy = ArchivePolicy(
            mode = DeploymentMode.PILOT,
            enabled = true,
            checksumVerificationEnabled = true,
            encryptionRequired = true,
            privateAccessRequired = true,
            retentionPolicyRequired = true,
            immutabilityRequired = true,
            provider = ArchiveProvider.NONE,
            stagingRoot = null,
            endpoint = null,
            region = null,
            bucket = null,
            objectPrefix = "acceptance/",
            accessOwner = null,
            retentionPeriod = null,
            probeTimeout = Duration.ofSeconds(5),
            operationTimeout = Duration.ofSeconds(30),
        )
        val context = CapabilityProbeContext(
            policyFingerprint = "3".repeat(64),
            checkedAt = Instant.parse("2026-08-26T05:00:00Z"),
        )
        val report = ArchiveCapabilityReport(
            mode = policy.mode,
            provider = policy.provider,
            state = ArchiveCapabilityState.UNCONFIGURED,
            policyFingerprint = context.policyFingerprint,
            checkedAt = context.checkedAt,
            checks = emptyList(),
        )
        val authorization = ArchiveAuthorization(
            report = report,
            issuer = issuer,
        )
        val command = ArchiveCommand(
            acceptanceId = "acceptance-2",
            sourceArtifactId = "artifact-2",
            sourceRunId = "run-2",
            sourceCommit = "fedcba9876543210",
            source = Path.of("artifact.zip"),
            expectedSha256 = "4".repeat(64),
        )
        val adapter = object : ArchiveAdapter {
            override val provider = ArchiveProvider.NONE

            override fun probe(policy: ArchivePolicy, context: CapabilityProbeContext): List<CapabilityCheck> =
                listOf(
                    CapabilityCheck(
                        name = "provider",
                        passed = false,
                        detail = "not configured",
                    ),
                )

            override fun archive(
                command: ArchiveCommand,
                policy: ArchivePolicy,
                authorization: ArchiveAuthorization,
            ): ArchiveResult = throw ArchiveUnavailable("Archive provider is not configured")
        }

        assertThat(adapter.provider).isEqualTo(ArchiveProvider.NONE)
        val checks = adapter.probe(policy = policy, context = context)
        assertThat(checks).hasSize(1)
        assertThat(checks.single().name).isEqualTo("provider")
        assertThat(checks.single().passed).isFalse()
        assertThat(authorization.report).isEqualTo(report)
        assertThat(ArchiveUnavailable("unavailable")).isInstanceOf(IllegalStateException::class.java)
        assertThat(ArchiveIntegrityFailure("integrity")).isInstanceOf(IllegalStateException::class.java)
        org.assertj.core.api.Assertions.assertThatThrownBy {
            adapter.archive(
                command = command,
                policy = policy,
                authorization = authorization,
            )
        }.isInstanceOf(ArchiveUnavailable::class.java)
    }
}
