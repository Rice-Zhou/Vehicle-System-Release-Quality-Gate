package com.ricezhou.vsrqg.shared.archive.operations

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveExecutionReport
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveOperationFailure
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveReportFileOperations
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveReportWriter
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveRunner
import com.ricezhou.vsrqg.shared.adapter.archive.operations.OperationStatus
import com.ricezhou.vsrqg.shared.adapter.archive.operations.VerifiedArchiveSource
import com.ricezhou.vsrqg.shared.adapter.archive.operations.VerifiedEvidenceArchiveWorkPackage
import com.ricezhou.vsrqg.shared.application.archive.ArchiveAdapter
import com.ricezhou.vsrqg.shared.application.archive.ArchiveAuthorization
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCapabilityState
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCommand
import com.ricezhou.vsrqg.shared.application.archive.ArchiveEvidence
import com.ricezhou.vsrqg.shared.application.archive.ArchiveIntegrityFailure
import com.ricezhou.vsrqg.shared.application.archive.ArchivePolicy
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.ArchiveReceipt
import com.ricezhou.vsrqg.shared.application.archive.ArchiveReceiptReference
import com.ricezhou.vsrqg.shared.application.archive.ArchiveResult
import com.ricezhou.vsrqg.shared.application.archive.ArchiveUnavailable
import com.ricezhou.vsrqg.shared.application.archive.CapabilityCheck
import com.ricezhou.vsrqg.shared.application.archive.CapabilityProbeContext
import com.ricezhou.vsrqg.shared.application.archive.DeploymentMode
import com.ricezhou.vsrqg.shared.application.archive.EvaluateArchiveCapability
import com.ricezhou.vsrqg.shared.application.archive.RuntimeIdentityRef
import com.ricezhou.vsrqg.shared.application.archive.StoredObjectRef
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.io.IOException
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EvidenceArchiveRunnerTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `archives exactly two verified sources in order through the existing facade`() {
        val adapter = ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE))
        val runner = runner(adapter)

        val report = runner.run(WORK_PACKAGE)

        assertThat(adapter.commands).containsExactly(
            ArchiveCommand(WORK_PACKAGE_ID, "9631253528", "33033752846", FIRST_COMMIT, FIRST_PATH, FIRST_SHA),
            ArchiveCommand(WORK_PACKAGE_ID, "9631250285", "33033740162", SECOND_COMMIT, SECOND_PATH, SECOND_SHA),
        )
        assertThat(report.status).isEqualTo(OperationStatus.PASS)
        assertThat(report.errorCode).isNull()
        assertThat(report.executionId).isEqualTo(EXECUTION_ID.toString())
        assertThat(report.startedAt).isEqualTo(STARTED_AT)
        assertThat(report.completedAt).isEqualTo(COMPLETED_AT)
        assertThat(report.runtimeIdentity).isEqualTo(IDENTITY)
        assertThat(report.artifacts).hasSize(2)
        assertThat(report.artifacts.map { it.artifactId }).containsExactly("9631253528", "9631250285")
        assertThat(report.artifacts).allSatisfy { artifact ->
            assertThat(artifact.payload.versionId).isNotBlank()
            assertThat(artifact.receiptReference.versionId).isNotBlank()
            assertThat(artifact.payload.provider).isEqualTo(ArchiveProvider.S3_COMPATIBLE)
            assertThat(artifact.receiptReference.provider).isEqualTo(ArchiveProvider.S3_COMPATIBLE)
            assertThat(artifact.payload.bucket).isEqualTo(BUCKET)
            assertThat(artifact.receiptReference.bucket).isEqualTo(BUCKET)
            assertThat(artifact.payload.key).isNotBlank()
            assertThat(artifact.receiptReference.key).isNotBlank()
        }
    }

    @Test
    fun `keeps the first exact reference when the second archive is unavailable`() {
        val adapter = ScriptedArchiveAdapter(
            resultFor(FIRST_SOURCE),
            ArchiveUnavailable("provider secret at C:\\private\\evidence.zip"),
        )

        val report = runner(adapter).run(WORK_PACKAGE)

        assertThat(report.status).isEqualTo(OperationStatus.FAIL)
        assertThat(report.errorCode).isEqualTo("ARCHIVE_UNAVAILABLE")
        assertThat(report.artifacts.map { it.artifactId }).containsExactly(FIRST_SOURCE.artifactId)
        val first = report.artifacts.single()
        assertThat(first.sourceRunId).isEqualTo(FIRST_SOURCE.sourceRunId)
        assertThat(first.sourceCommit).isEqualTo(FIRST_SOURCE.sourceCommit)
        assertThat(first.payload.provider).isEqualTo(ArchiveProvider.S3_COMPATIBLE)
        assertThat(first.payload.locator).isEqualTo("s3://$BUCKET/acceptance/payloads/$WORK_PACKAGE_ID/${FIRST_SOURCE.artifactId}.zip")
        assertThat(first.payload.bucket).isEqualTo(BUCKET)
        assertThat(first.payload.key).isEqualTo("acceptance/payloads/$WORK_PACKAGE_ID/${FIRST_SOURCE.artifactId}.zip")
        assertThat(first.payload.versionId).isEqualTo("payload-${FIRST_SOURCE.artifactId}")
        assertThat(first.payload.sha256).isEqualTo(FIRST_SOURCE.sha256)
        assertThat(first.payload.sizeBytes).isEqualTo(FIRST_SOURCE.sizeBytes)
        assertThat(first.receiptReference.provider).isEqualTo(ArchiveProvider.S3_COMPATIBLE)
        assertThat(first.receiptReference.locator).isEqualTo("s3://$BUCKET/acceptance/receipts/${FIRST_SOURCE.artifactId}.json")
        assertThat(first.receiptReference.bucket).isEqualTo(BUCKET)
        assertThat(first.receiptReference.key).isEqualTo("acceptance/receipts/${FIRST_SOURCE.artifactId}.json")
        assertThat(first.receiptReference.versionId).isEqualTo("receipt-${FIRST_SOURCE.artifactId}")
        assertThat(first.receiptReference.sha256).isEqualTo("d".repeat(64))
        assertThat(first.receiptReference.sizeBytes).isEqualTo(512)
        assertThat(report.policyFingerprint).isEqualTo(POLICY_FINGERPRINT)
        assertThat(report.capabilityCheckedAt).isEqualTo(CHECKED_AT)
        assertThat(report.runtimeIdentity).isEqualTo(IDENTITY)
        assertThat(report.accessOwner).isEqualTo(ACCESS_OWNER)
        assertThat(report.retentionPolicy).isEqualTo(RETENTION_POLICY)
        assertThat(report.immutabilityControl).isEqualTo(IMMUTABILITY_CONTROL)
        assertThat(report.completedAt).isAfterOrEqualTo(report.startedAt)
        assertThat(report.toString()).doesNotContain("provider secret", "C:\\private")
    }

    @Test
    fun `maps integrity and unknown exceptions to stable codes without leaking details`() {
        val integrity = runner(ScriptedArchiveAdapter(ArchiveIntegrityFailure("digest at C:\\secret")))
            .run(WORK_PACKAGE)
        val unexpected = runner(ScriptedArchiveAdapter(IllegalStateException("SENSITIVE_MARKER C:\\source.zip")))
            .run(WORK_PACKAGE)

        assertThat(integrity.errorCode).isEqualTo("ARCHIVE_INTEGRITY_FAILURE")
        assertThat(unexpected.errorCode).isEqualTo("UNEXPECTED_FAILURE")
        assertThat(unexpected.toString()).doesNotContain("SENSITIVE_MARKER", "source.zip")
    }

    @Test
    fun `fails closed instead of combining contradictory archive controls`() {
        val variants = listOf(
            resultFor(SECOND_SOURCE, identity = IDENTITY.copy(principalFingerprint = "b".repeat(64))),
            resultFor(SECOND_SOURCE, policyFingerprint = "c".repeat(64)),
            resultFor(SECOND_SOURCE, capabilityCheckedAt = CHECKED_AT.plusSeconds(1)),
            resultFor(SECOND_SOURCE, accessOwner = "different-owner"),
            resultFor(SECOND_SOURCE, retentionPolicy = "P731D"),
            resultFor(SECOND_SOURCE, immutabilityControl = "GOVERNANCE"),
        )

        variants.forEach { contradictory ->
            val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), contradictory)).run(WORK_PACKAGE)
            assertThat(report.status).isEqualTo(OperationStatus.FAIL)
            assertThat(report.errorCode).isEqualTo("ARCHIVE_RESULT_CONFLICT")
            assertThat(report.artifacts.map { it.artifactId }).containsExactly(FIRST_SOURCE.artifactId)
        }
    }

    @Test
    fun `rejects non company or inexact successful results`() {
        val inexact = resultFor(FIRST_SOURCE).copy(
            receiptReference = resultFor(FIRST_SOURCE).receiptReference.copy(versionId = null),
        )

        val report = runner(ScriptedArchiveAdapter(inexact)).run(WORK_PACKAGE)

        assertThat(report.status).isEqualTo(OperationStatus.FAIL)
        assertThat(report.errorCode).isEqualTo("ARCHIVE_RESULT_INVALID")
        assertThat(report.artifacts).isEmpty()
    }

    @Test
    fun `rejects the literal null as an inexact payload or receipt version`() {
        val payloadNull = resultFor(FIRST_SOURCE).let { result ->
            result.copy(receipt = result.receipt.copy(payload = result.receipt.payload.copy(versionId = "null")))
        }
        val receiptNull = resultFor(FIRST_SOURCE).let { result ->
            result.copy(receiptReference = result.receiptReference.copy(versionId = "null"))
        }

        listOf(payloadNull, receiptNull).forEach { inexact ->
            val report = runner(ScriptedArchiveAdapter(inexact)).run(WORK_PACKAGE)
            assertThat(report.status).isEqualTo(OperationStatus.FAIL)
            assertThat(report.errorCode).isEqualTo("ARCHIVE_RESULT_INVALID")
            assertThat(report.artifacts).isEmpty()
        }
    }

    @Test
    fun `returns a stable failed report when archive control metadata is invalid`() {
        val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE, accessOwner = ""))).run(WORK_PACKAGE)

        assertThat(report.status).isEqualTo(OperationStatus.FAIL)
        assertThat(report.errorCode).isEqualTo("ARCHIVE_RESULT_INVALID")
        assertThat(report.artifacts).isEmpty()
    }

    @Test
    fun `does not catch JVM errors`() {
        val adapter = ScriptedArchiveAdapter(AssertionError("fatal"))

        assertThatThrownBy { runner(adapter).run(WORK_PACKAGE) }
            .isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun `writes deterministic canonical JSON with no local paths or sensitive fields`() {
        val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE)))
            .run(WORK_PACKAGE)
        val first = tempDirectory.resolve("first.json")
        val second = tempDirectory.resolve("second.json")

        val runner = runner(ScriptedArchiveAdapter())
        runner.writeReport(report, first)
        runner.writeReport(report, second)

        val firstBytes = Files.readAllBytes(first)
        assertThat(firstBytes).containsExactly(*Files.readAllBytes(second))
        val json = firstBytes.toString(Charsets.UTF_8)
        assertThat(json).startsWith("{").doesNotContain("sourcePath", "exception", "presignedUrl", "secret", FIRST_PATH.toString())
        val parsed = ObjectMapper().readTree(firstBytes)
        assertThat(parsed["schemaVersion"].intValue()).isEqualTo(1)
        assertThat(parsed["artifacts"].size()).isEqualTo(2)
        assertThat(parsed["errorCode"].isNull).isTrue()
    }

    @Test
    fun `uses create only output and leaves no partial file`() {
        val runner = runner(ScriptedArchiveAdapter())
        val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE)))
            .run(WORK_PACKAGE)
        val output = tempDirectory.resolve("report.json")
        Files.writeString(output, "existing")

        assertThatThrownBy { runner.writeReport(report, output) }
            .isInstanceOf(EvidenceArchiveOperationFailure::class.java)
            .extracting("code")
            .isEqualTo("REPORT_TARGET_EXISTS")
        assertThat(Files.readString(output)).isEqualTo("existing")
        assertThat(Files.list(tempDirectory).use { it.map(Path::getFileName).map(Path::toString).toList() })
            .containsExactly("report.json")
    }

    @Test
    fun `makes partial cleanup failure explicit`() {
        val partial = tempDirectory.resolve("report.unique.partial")
        Files.writeString(partial, "partial")
        val files = object : EvidenceArchiveReportFileOperations by EvidenceArchiveReportFileOperations.nio() {
            override fun createPartial(parent: Path, outputFileName: String): Path = partial
            override fun writeAndForce(path: Path, bytes: ByteArray) = throw IOException("write failure")
            override fun deleteIfExists(path: Path): Boolean = throw IOException("cleanup failure")
        }
        val writer = EvidenceArchiveReportWriter(files)
        val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE)))
            .run(WORK_PACKAGE)

        assertThatThrownBy { writer.write(report, tempDirectory.resolve("report.json")) }
            .isInstanceOf(EvidenceArchiveOperationFailure::class.java)
            .extracting("code")
            .isEqualTo("REPORT_CLEANUP_FAILED")
    }

    @Test
    fun `keeps a complete published report when directory force fails`() {
        val output = tempDirectory.resolve("report.json")
        val delegate = EvidenceArchiveReportFileOperations.nio()
        val deleted = mutableListOf<Path>()
        val files = object : EvidenceArchiveReportFileOperations by delegate {
            override fun deleteIfExists(path: Path): Boolean {
                deleted.add(path)
                return delegate.deleteIfExists(path)
            }
            override fun forceDirectory(path: Path) = throw FileAlreadyExistsException(path.toString())
        }
        val writer = EvidenceArchiveReportWriter(files)
        val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE)))
            .run(WORK_PACKAGE)

        assertThatThrownBy { writer.write(report, output) }
            .isInstanceOf(EvidenceArchiveOperationFailure::class.java)
            .extracting("code")
            .isEqualTo("REPORT_WRITE_FAILED")
        assertThat(Files.readAllBytes(output)).containsExactly(*writer.canonicalBytes(report))
        assertThat(deleted).doesNotContain(output)
        assertThat(Files.list(tempDirectory).use { it.toList() }).containsExactly(output)
    }

    @Test
    fun `never deletes a published target that is externally replaced after commit`() {
        val output = tempDirectory.resolve("report.json")
        val replacement = "external replacement".toByteArray()
        val delegate = EvidenceArchiveReportFileOperations.nio()
        val deleted = mutableListOf<Path>()
        val files = object : EvidenceArchiveReportFileOperations by delegate {
            override fun deleteIfExists(path: Path): Boolean {
                deleted.add(path)
                return delegate.deleteIfExists(path)
            }
            override fun forceDirectory(path: Path) {
                Files.delete(output)
                Files.write(output, replacement)
                throw IOException("directory force failure")
            }
        }
        val writer = EvidenceArchiveReportWriter(files)
        val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE)))
            .run(WORK_PACKAGE)

        assertThatThrownBy { writer.write(report, output) }
            .isInstanceOf(EvidenceArchiveOperationFailure::class.java)
            .extracting("code")
            .isEqualTo("REPORT_WRITE_FAILED")
        assertThat(Files.readAllBytes(output)).containsExactly(*replacement)
        assertThat(deleted).doesNotContain(output)
    }

    private fun runner(adapter: ScriptedArchiveAdapter): EvidenceArchiveRunner {
        val evaluator = EvaluateArchiveCapability(listOf(adapter), TimeProvider { CHECKED_AT })
        val facade = ArchiveEvidence(POLICY, evaluator, listOf(adapter))
        val times = ArrayDeque(listOf(STARTED_AT, COMPLETED_AT))
        return EvidenceArchiveRunner(
            archiveEvidence = facade,
            timeProvider = TimeProvider { times.removeFirstOrNull() ?: COMPLETED_AT },
            executionIdProvider = { EXECUTION_ID },
        )
    }

    private fun resultFor(
        source: VerifiedArchiveSource,
        identity: RuntimeIdentityRef = IDENTITY,
        policyFingerprint: String = POLICY_FINGERPRINT,
        capabilityCheckedAt: Instant = CHECKED_AT,
        accessOwner: String = ACCESS_OWNER,
        retentionPolicy: String = RETENTION_POLICY,
        immutabilityControl: String = IMMUTABILITY_CONTROL,
    ): ArchiveResult {
        val payloadKey = "acceptance/payloads/$WORK_PACKAGE_ID/${source.artifactId}.zip"
        val receiptKey = "acceptance/receipts/${source.artifactId}.json"
        return ArchiveResult(
            receipt = ArchiveReceipt(
                acceptanceId = WORK_PACKAGE_ID,
                sourceArtifactId = source.artifactId,
                sourceRunId = source.sourceRunId,
                sourceCommit = source.sourceCommit,
                sourceSha256 = source.sha256,
                payload = StoredObjectRef(
                    provider = ArchiveProvider.S3_COMPATIBLE,
                    locator = "s3://$BUCKET/$payloadKey",
                    bucket = BUCKET,
                    key = payloadKey,
                    versionId = "payload-${source.artifactId}",
                    sha256 = source.sha256,
                    sizeBytes = source.sizeBytes,
                ),
                accessOwner = accessOwner,
                retentionPolicy = retentionPolicy,
                immutabilityControl = immutabilityControl,
                policyFingerprint = policyFingerprint,
                capabilityCheckedAt = capabilityCheckedAt,
                archivedAt = ARCHIVED_AT,
                verifier = "S3ArchiveAdapter",
                longTerm = true,
            ),
            receiptReference = ArchiveReceiptReference(
                locator = "s3://$BUCKET/$receiptKey",
                versionId = "receipt-${source.artifactId}",
                sha256 = "d".repeat(64),
                sizeBytes = 512,
            ),
            runtimeIdentity = identity,
        )
    }

    private class ScriptedArchiveAdapter(vararg outcomes: Any) : ArchiveAdapter {
        override val provider = ArchiveProvider.S3_COMPATIBLE
        private val outcomes = ArrayDeque(outcomes.toList())
        val commands = mutableListOf<ArchiveCommand>()

        override fun probe(policy: ArchivePolicy, context: CapabilityProbeContext): List<CapabilityCheck> =
            listOf(CapabilityCheck("provider", true, ArchiveCapabilityState.EXTERNAL_VERIFIED.name))

        override fun archive(
            command: ArchiveCommand,
            policy: ArchivePolicy,
            authorization: ArchiveAuthorization,
        ): ArchiveResult {
            commands += command
            return when (val outcome = outcomes.removeFirst()) {
                is ArchiveResult -> outcome
                is Throwable -> throw outcome
                else -> error("unsupported test outcome")
            }
        }
    }

    private companion object {
        const val WORK_PACKAGE_ID = "V0-2-EVIDENCE-ARCHIVE-001"
        const val FIRST_COMMIT = "892fb23ce75e7f74a05c1b5e304fccace70ee8d3"
        const val SECOND_COMMIT = "8687d49c9566030bb0829752dbe5dda45af02f4b"
        const val FIRST_SHA = "1f087ef27cfabbb2152d06fc002eb0772c2efbbb63964d6b13ec5f0d7a73ed7a"
        const val SECOND_SHA = "e7602924fe67fd6eff75ebfe5d48122240639d883edc58dc164c419893d979ca"
        const val BUCKET = "company-evidence"
        const val ACCESS_OWNER = "release-security"
        const val RETENTION_POLICY = "P730D"
        const val IMMUTABILITY_CONTROL = "COMPLIANCE"
        val FIRST_PATH: Path = Path.of("C:\\verified\\first.zip")
        val SECOND_PATH: Path = Path.of("C:\\verified\\second.zip")
        val FIRST_SOURCE = VerifiedArchiveSource("9631253528", "33033752846", FIRST_COMMIT, FIRST_PATH, 55065, FIRST_SHA)
        val SECOND_SOURCE = VerifiedArchiveSource("9631250285", "33033740162", SECOND_COMMIT, SECOND_PATH, 55099, SECOND_SHA)
        val WORK_PACKAGE = VerifiedEvidenceArchiveWorkPackage(
            WORK_PACKAGE_ID,
            "a".repeat(64),
            "b".repeat(64),
            listOf(FIRST_SOURCE, SECOND_SOURCE),
        )
        val STARTED_AT: Instant = Instant.parse("2026-08-27T01:00:00Z")
        val COMPLETED_AT: Instant = Instant.parse("2026-08-27T01:00:02Z")
        val CHECKED_AT: Instant = Instant.parse("2026-08-27T00:59:59Z")
        val ARCHIVED_AT: Instant = Instant.parse("2026-08-27T01:00:01Z")
        val EXECUTION_ID: UUID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        val POLICY_FINGERPRINT = "f".repeat(64)
        val IDENTITY = RuntimeIdentityRef(ArchiveProvider.S3_COMPATIBLE, "a".repeat(64))
        val POLICY = ArchivePolicy(
            mode = DeploymentMode.COMPANY,
            enabled = true,
            checksumVerificationEnabled = true,
            encryptionRequired = true,
            privateAccessRequired = true,
            retentionPolicyRequired = true,
            immutabilityRequired = true,
            provider = ArchiveProvider.S3_COMPATIBLE,
            stagingRoot = null,
            endpoint = URI("https://s3.example.test"),
            region = "test-1",
            bucket = BUCKET,
            objectPrefix = "acceptance/",
            accessOwner = ACCESS_OWNER,
            retentionPeriod = Duration.ofDays(730),
            probeTimeout = Duration.ofSeconds(1),
            operationTimeout = Duration.ofSeconds(2),
        )
    }
}
