package com.ricezhou.vsrqg.shared.archive.operations

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveExecutionReport
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveDirectoryAccessReader
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveFileKeyReader
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveOperationFailure
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchivePartialChannelDecorator
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveReadChannelOpener
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveReportChannel
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveReportFileOperations
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveReportPartial
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveReportWriter
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveTrustedDirectory
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
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
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
    fun `raw S3 producer references become a canonical PASS report without URI semantics`() {
        val firstResult = rawResultFor(FIRST_SOURCE)
        val secondResult = rawResultFor(SECOND_SOURCE)
        val report = runner(ScriptedArchiveAdapter(firstResult, secondResult)).run(WORK_PACKAGE)
        val bytes = EvidenceArchiveReportWriter().canonicalBytes(report)
        val parsed = ObjectMapper().readTree(bytes)

        assertThat(report.status).isEqualTo(OperationStatus.PASS)
        assertThat(report.artifacts).hasSize(2)
        assertThat(report.artifacts).allSatisfy { artifact ->
            assertThat(artifact.payload.bucket).isEqualTo(RAW_BUCKET)
            assertThat(artifact.payload.key).contains("raw % # ? [x] café 😀")
            assertThat(artifact.payload.locator).isEqualTo("s3://$RAW_BUCKET/${artifact.payload.key}")
            assertThat(artifact.payload.versionId).isEqualTo("latest")
            assertThat(artifact.receiptReference.key).contains("receipt % # ? [x] café 😀")
            assertThat(artifact.receiptReference.locator)
                .isEqualTo("s3://$RAW_BUCKET/${artifact.receiptReference.key}")
        }
        assertThat(parsed["status"].textValue()).isEqualTo("PASS")
        assertThat(bytes.toString(Charsets.UTF_8)).contains("raw % # ? [x] café 😀")
    }

    @Test
    fun `canonical producer fixture stays byte-identical to Runner output`() {
        val fixtureWorkPackage = WORK_PACKAGE.copy(
            descriptorSha256 = "c15e1245f172462db85cc70aef197b4109da3aa428c59992ba2de737e22e9b49",
            pilotManifestSha256 = "7bcb4d9df5ce0e28fe6150e0593c9824ea2533a2f7885f17d61d3ae813aa4a32",
        )
        val report = runner(ScriptedArchiveAdapter(rawResultFor(FIRST_SOURCE), rawResultFor(SECOND_SOURCE)))
            .run(fixtureWorkPackage)
        val actual = EvidenceArchiveReportWriter().canonicalBytes(report)
        val fixturePath = Path.of(System.getProperty("user.dir"))
            .resolve("../ops/evidence-archive/fixtures/runner-pass-report.json")
            .normalize()

        assertThat(report.status).isEqualTo(OperationStatus.PASS)
        assertThat(actual).isEqualTo(Files.readAllBytes(fixturePath))
    }

    @Test
    fun `raw S3 producer reference fails when locator and key differ`() {
        val original = rawResultFor(FIRST_SOURCE)
        val mismatched = original.copy(
            receipt = original.receipt.copy(
                payload = original.receipt.payload.copy(locator = "${original.receipt.payload.locator}-different"),
            ),
        )

        val report = runner(ScriptedArchiveAdapter(mismatched)).run(WORK_PACKAGE)

        assertThat(report.status).isEqualTo(OperationStatus.FAIL)
        assertThat(report.errorCode).isEqualTo("ARCHIVE_RESULT_INVALID")
        assertThat(report.artifacts).isEmpty()
    }

    @Test
    fun `business object key may contain path-like text but not high-confidence secrets`() {
        val businessKey = "evidence/path=/var/tmp/release-token-principal/secret-object.json"
        val accepted = resultFor(FIRST_SOURCE).let { result ->
            result.copy(
                receipt = result.receipt.copy(
                    payload = result.receipt.payload.copy(key = businessKey, locator = "s3://$BUCKET/$businessKey"),
                ),
            )
        }
        val rejectedKey = "evidence/secret=credential-value/object.json"
        val rejected = resultFor(FIRST_SOURCE).let { result ->
            result.copy(
                receipt = result.receipt.copy(
                    payload = result.receipt.payload.copy(key = rejectedKey, locator = "s3://$BUCKET/$rejectedKey"),
                ),
            )
        }

        assertThat(runner(ScriptedArchiveAdapter(accepted, resultFor(SECOND_SOURCE))).run(WORK_PACKAGE).status)
            .isEqualTo(OperationStatus.PASS)
        assertThat(runner(ScriptedArchiveAdapter(rejected)).run(WORK_PACKAGE).errorCode)
            .isEqualTo("ARCHIVE_RESULT_INVALID")
    }

    @Test
    fun `PASS report controls satisfy the consumer report domain`() {
        listOf(
            resultFor(FIRST_SOURCE, retentionPolicy = "P1Y"),
            resultFor(FIRST_SOURCE, immutabilityControl = "GOVERNANCE"),
        ).forEach { unsafe ->
            val report = runner(ScriptedArchiveAdapter(unsafe)).run(WORK_PACKAGE)
            assertThat(report.status).isEqualTo(OperationStatus.FAIL)
            assertThat(report.errorCode).isEqualTo("ARCHIVE_RESULT_INVALID")
        }
    }

    @Test
    fun `PASS report accepts production access owner text and rejects unsafe values`() {
        listOf(
            "Release Security",
            "release/security",
            "发布安全团队",
            "Company / Division / Security",
            "公司 / 平台 / 安全",
            "Security https://organization.example/division",
            "path=/Company/Division/Security",
            "Release Owner ".repeat(256),
        ).forEachIndexed { index, accessOwner ->
            val report = runner(
                ScriptedArchiveAdapter(
                    resultFor(FIRST_SOURCE, accessOwner = accessOwner),
                    resultFor(SECOND_SOURCE, accessOwner = accessOwner, capabilityCheckedAt = SECOND_CHECKED_AT),
                ),
            ).run(WORK_PACKAGE)
            assertThat(report.status).describedAs("valid owner $index").isEqualTo(OperationStatus.PASS)
        }

        listOf(
            "",
            " ",
            "\u00a0",
            "\u3000",
            "owner\u0085team",
            "principal=raw-role",
            "secret=credential-value",
            "Bearer opaque-access-token",
            "arn:aws:iam::123456789012:role/release-security",
            "  https://organization.example/division  ",
            "C:\\Company\\Division\\Security",
            "\\\\server\\division\\security",
            "/Company/Division/Security",
        ).forEachIndexed { index, accessOwner ->
            val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE, accessOwner = accessOwner))).run(WORK_PACKAGE)
            assertThat(report.status).describedAs("unsafe owner $index").isEqualTo(OperationStatus.FAIL)
            assertThat(report.errorCode).isEqualTo("ARCHIVE_RESULT_INVALID")
        }
    }

    @Test
    fun `unsafe configured access owner fails before external archive writes`() {
        val adapter = ScriptedArchiveAdapter(resultFor(FIRST_SOURCE))
        val report = runner(adapter, POLICY.copy(accessOwner = "  https://organization.example/division  ")).run(WORK_PACKAGE)

        assertThat(report.status).isEqualTo(OperationStatus.FAIL)
        assertThat(report.errorCode).isEqualTo("ARCHIVE_POLICY_FAILURE")
        assertThat(adapter.commands).isEmpty()
    }

    @Test
    fun `PASS report capability and archive times stay inside the execution window`() {
        val beforeStart = resultFor(FIRST_SOURCE, capabilityCheckedAt = STARTED_AT.minusNanos(1))
        val archiveBeforeCheck = resultFor(FIRST_SOURCE).let { result ->
            result.copy(receipt = result.receipt.copy(archivedAt = CHECKED_AT.minusNanos(1)))
        }
        val archiveAfterCompletion = resultFor(FIRST_SOURCE).let { result ->
            result.copy(receipt = result.receipt.copy(archivedAt = COMPLETED_AT.plusNanos(1)))
        }

        listOf(beforeStart, archiveBeforeCheck, archiveAfterCompletion).forEach { unsafe ->
            val report = runner(ScriptedArchiveAdapter(unsafe, resultFor(SECOND_SOURCE))).run(WORK_PACKAGE)
            assertThat(report.status).isEqualTo(OperationStatus.FAIL)
            assertThat(report.errorCode).isEqualTo("ARCHIVE_RESULT_INVALID")
        }
    }

    @Test
    fun `PASS report requires the production receipt verifier`() {
        val unsafe = resultFor(FIRST_SOURCE).let { result ->
            result.copy(receipt = result.receipt.copy(verifier = "untrusted-verifier"))
        }

        val report = runner(ScriptedArchiveAdapter(unsafe)).run(WORK_PACKAGE)

        assertThat(report.status).isEqualTo(OperationStatus.FAIL)
        assertThat(report.errorCode).isEqualTo("ARCHIVE_RESULT_INVALID")
    }

    @Test
    fun `PASS report exact object identities are globally unique`() {
        val sameWithinArtifact = resultFor(FIRST_SOURCE).let { result ->
            result.copy(
                receiptReference = result.receiptReference.copy(
                    locator = result.receipt.payload.locator,
                    versionId = result.receipt.payload.versionId,
                ),
            )
        }
        val first = resultFor(FIRST_SOURCE)
        val sameAcrossArtifacts = resultFor(SECOND_SOURCE).let { result ->
            result.copy(
                receipt = result.receipt.copy(
                    payload = result.receipt.payload.copy(
                        locator = first.receipt.payload.locator,
                        bucket = first.receipt.payload.bucket,
                        key = first.receipt.payload.key,
                        versionId = first.receipt.payload.versionId,
                    ),
                ),
            )
        }

        listOf(
            runner(ScriptedArchiveAdapter(sameWithinArtifact)).run(WORK_PACKAGE),
            runner(ScriptedArchiveAdapter(first, sameAcrossArtifacts)).run(WORK_PACKAGE),
        ).forEach { report ->
            assertThat(report.status).isEqualTo(OperationStatus.FAIL)
            assertThat(report.errorCode).isEqualTo("ARCHIVE_RESULT_INVALID")
        }
    }

    @Test
    fun `opaque bucket and version use the same report-safe boundary`() {
        val unsafeVersion = resultFor(FIRST_SOURCE).let { result ->
            result.copy(receipt = result.receipt.copy(payload = result.receipt.payload.copy(versionId = "path=/var/tmp/v1")))
        }
        val unsafeBucket = resultFor(FIRST_SOURCE).let { result ->
            val bucket = "principal=raw-role"
            result.copy(
                receipt = result.receipt.copy(
                    payload = result.receipt.payload.copy(bucket = bucket, locator = "s3://$bucket/${result.receipt.payload.key}"),
                ),
            )
        }

        listOf(unsafeVersion, unsafeBucket).forEach { unsafe ->
            assertThat(runner(ScriptedArchiveAdapter(unsafe)).run(WORK_PACKAGE).errorCode)
                .isEqualTo("ARCHIVE_RESULT_INVALID")
        }
    }

    @Test
    fun `accepts fresh facade authorizations and reports the latest capability check`() {
        val adapter = AuthorizationBoundArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE))
        val capabilityClock = AdvancingTimeProvider(CHECKED_AT)
        val facade = ArchiveEvidence(POLICY, EvaluateArchiveCapability(listOf(adapter), capabilityClock), listOf(adapter))
        val operationTimes = ArrayDeque(listOf(STARTED_AT, COMPLETED_AT))
        val runner = EvidenceArchiveRunner(
            archiveEvidence = facade,
            timeProvider = TimeProvider { operationTimes.removeFirst() },
            executionIdProvider = { EXECUTION_ID },
        )

        val report = runner.run(WORK_PACKAGE)

        assertThat(report.status).isEqualTo(OperationStatus.PASS)
        assertThat(report.artifacts.map { it.artifactId }).containsExactly(FIRST_SOURCE.artifactId, SECOND_SOURCE.artifactId)
        assertThat(adapter.checkedAt).containsExactly(CHECKED_AT, CHECKED_AT.plusSeconds(1))
        assertThat(report.capabilityCheckedAt).isEqualTo(CHECKED_AT.plusSeconds(1))
        assertThat(report.startedAt).isEqualTo(STARTED_AT)
        assertThat(report.completedAt).isEqualTo(COMPLETED_AT)
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
        assertThat(report.startedAt).isEqualTo(STARTED_AT)
        assertThat(report.completedAt).isEqualTo(COMPLETED_AT)
        assertThat(report.toString()).doesNotContain("provider secret", "C:\\private")
    }

    @Test
    fun `keeps only the first reference when the second result is individually invalid`() {
        val invalidSecond = resultFor(SECOND_SOURCE).copy(runtimeIdentity = null)

        val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), invalidSecond)).run(WORK_PACKAGE)

        assertThat(report.status).isEqualTo(OperationStatus.FAIL)
        assertThat(report.errorCode).isEqualTo("ARCHIVE_RESULT_INVALID")
        assertThat(report.artifacts.map { it.artifactId }).containsExactly(FIRST_SOURCE.artifactId)
        assertThat(report.capabilityCheckedAt).isEqualTo(CHECKED_AT)
        assertThat(report.runtimeIdentity).isEqualTo(IDENTITY)
    }

    @Test
    fun `sanitizes unknown operation failure codes before they enter the report`() {
        val unknownCode = "MALICIOUS_BUT_WELL_FORMED"
        val pathCode = "C:\\private\\SENSITIVE_CODE"
        val writer = EvidenceArchiveReportWriter()

        listOf(unknownCode, pathCode).forEach { untrustedCode ->
            val report = runner(ScriptedArchiveAdapter(EvidenceArchiveOperationFailure(untrustedCode))).run(WORK_PACKAGE)
            val bytes = writer.canonicalBytes(report).decodeToString()

            assertThat(report.status).isEqualTo(OperationStatus.FAIL)
            assertThat(report.errorCode).isEqualTo("UNEXPECTED_FAILURE")
            assertThat(bytes).containsOnlyOnce("UNEXPECTED_FAILURE")
            assertThat(bytes).doesNotContain(untrustedCode, "private", "SENSITIVE_CODE")
        }
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
            resultFor(
                SECOND_SOURCE,
                identity = IDENTITY.copy(principalFingerprint = "b".repeat(64)),
                capabilityCheckedAt = SECOND_CHECKED_AT,
            ),
            resultFor(SECOND_SOURCE, policyFingerprint = "c".repeat(64), capabilityCheckedAt = SECOND_CHECKED_AT),
            resultFor(SECOND_SOURCE, accessOwner = "different-owner", capabilityCheckedAt = SECOND_CHECKED_AT),
            resultFor(SECOND_SOURCE, retentionPolicy = "P731D", capabilityCheckedAt = SECOND_CHECKED_AT),
        )

        variants.forEach { contradictory ->
            val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), contradictory)).run(WORK_PACKAGE)
            assertThat(report.status).isEqualTo(OperationStatus.FAIL)
            assertThat(report.errorCode).isEqualTo("ARCHIVE_RESULT_CONFLICT")
            assertThat(report.artifacts.map { it.artifactId })
                .containsExactly(FIRST_SOURCE.artifactId, SECOND_SOURCE.artifactId)
            assertThat(report.policyFingerprint).isEqualTo(POLICY_FINGERPRINT)
            assertThat(report.runtimeIdentity).isEqualTo(IDENTITY)
            assertThat(report.capabilityCheckedAt).isEqualTo(SECOND_CHECKED_AT)
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
    fun `rejects a null runtime identity`() {
        val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE).copy(runtimeIdentity = null))).run(WORK_PACKAGE)

        assertThat(report.status).isEqualTo(OperationStatus.FAIL)
        assertThat(report.errorCode).isEqualTo("ARCHIVE_RESULT_INVALID")
        assertThat(report.artifacts).isEmpty()
    }

    @Test
    fun `rejects a non company runtime identity provider`() {
        val nonCompany = RuntimeIdentityRef(ArchiveProvider.FILESYSTEM_STAGING, IDENTITY.principalFingerprint)
        val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE, identity = nonCompany))).run(WORK_PACKAGE)

        assertThat(report.status).isEqualTo(OperationStatus.FAIL)
        assertThat(report.errorCode).isEqualTo("ARCHIVE_RESULT_INVALID")
        assertThat(report.artifacts).isEmpty()
    }

    @Test
    fun `reports no controls when the first archive attempt fails`() {
        val report = runner(ScriptedArchiveAdapter(ArchiveUnavailable("unavailable"))).run(WORK_PACKAGE)

        assertThat(report.status).isEqualTo(OperationStatus.FAIL)
        assertThat(report.errorCode).isEqualTo("ARCHIVE_UNAVAILABLE")
        assertThat(report.artifacts).isEmpty()
        assertThat(report.capabilityCheckedAt).isNull()
        assertThat(report.policyFingerprint).isNull()
        assertThat(report.runtimeIdentity).isNull()
        assertThat(report.accessOwner).isNull()
        assertThat(report.retentionPolicy).isNull()
        assertThat(report.immutabilityControl).isNull()
    }

    @Test
    fun `uses the maximum capability check when successful results arrive in reverse time order`() {
        val report = runner(
            ScriptedArchiveAdapter(
                resultFor(FIRST_SOURCE, capabilityCheckedAt = SECOND_CHECKED_AT),
                resultFor(SECOND_SOURCE, capabilityCheckedAt = CHECKED_AT),
            ),
        ).run(WORK_PACKAGE)

        assertThat(report.status).isEqualTo(OperationStatus.PASS)
        assertThat(report.capabilityCheckedAt).isEqualTo(SECOND_CHECKED_AT)
    }

    @Test
    fun `rejects the literal null as an inexact payload or receipt version`() {
        listOf("null", "NULL").flatMap { versionId ->
            val result = resultFor(FIRST_SOURCE)
            listOf(
                result.copy(receipt = result.receipt.copy(payload = result.receipt.payload.copy(versionId = versionId))),
                result.copy(receiptReference = result.receiptReference.copy(versionId = versionId)),
            )
        }.forEach { inexact ->
            val report = runner(ScriptedArchiveAdapter(inexact)).run(WORK_PACKAGE)
            assertThat(report.status).isEqualTo(OperationStatus.FAIL)
            assertThat(report.errorCode).isEqualTo("ARCHIVE_RESULT_INVALID")
            assertThat(report.artifacts).isEmpty()
        }
    }

    @Test
    fun `rejects JVM whitespace-only payload and receipt object keys`() {
        listOf(" ", "\u00a0", "\u3000").flatMap { key ->
            val result = resultFor(FIRST_SOURCE)
            listOf(
                result.copy(
                    receipt = result.receipt.copy(
                        payload = result.receipt.payload.copy(locator = "s3://$BUCKET/$key", key = key),
                    ),
                ),
                result.copy(receiptReference = result.receiptReference.copy(locator = "s3://$BUCKET/$key")),
            )
        }.forEach { invalid ->
            val report = runner(ScriptedArchiveAdapter(invalid)).run(WORK_PACKAGE)
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
        val files = object : EvidenceArchiveReportFileOperations by testFileOperations() {
            override fun writeAndForce(partial: EvidenceArchiveReportPartial, bytes: ByteArray) =
                throw IOException("write failure")
            override fun cleanupPartial(partial: EvidenceArchiveReportPartial) = throw IOException("cleanup failure")
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
        val delegate = testFileOperations()
        val deleted = mutableListOf<Path>()
        val files = object : EvidenceArchiveReportFileOperations by delegate {
            override fun cleanupPartial(partial: EvidenceArchiveReportPartial) {
                deleted.add(partial.path)
                delegate.cleanupPartial(partial)
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
        val delegate = testFileOperations()
        val files = object : EvidenceArchiveReportFileOperations by delegate {
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
    }

    @Test
    fun `uses create new without truncating a colliding partial`() {
        val partialId = UUID.fromString("11111111-2222-4333-8444-555555555555")
        val output = tempDirectory.resolve("report.json")
        val collision = tempDirectory.resolve(".report.json-$partialId.partial")
        Files.writeString(collision, "foreign")
        val writer = EvidenceArchiveReportWriter(testFileOperations(), partialIdProvider = { partialId })
        val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE))).run(WORK_PACKAGE)

        assertThatThrownBy { writer.write(report, output) }
            .isInstanceOf(EvidenceArchiveOperationFailure::class.java)
            .extracting("code")
            .isEqualTo("REPORT_WRITE_FAILED")
        assertThat(Files.readString(collision)).isEqualTo("foreign")
        assertThat(Files.exists(output)).isFalse()
    }

    @Test
    fun `uses one partial channel and never reopens the partial path for reading`() {
        val readPaths = mutableListOf<Path>()
        val delegate = testFileOperations(readPaths = readPaths)
        var openCount = 0
        lateinit var partialPath: Path
        val files = object : EvidenceArchiveReportFileOperations by delegate {
            override fun openPartial(parent: Path, partialFileName: String): EvidenceArchiveReportPartial {
                openCount += 1
                return delegate.openPartial(parent, partialFileName).also { partialPath = it.path }
            }
        }
        val writer = EvidenceArchiveReportWriter(files)
        val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE))).run(WORK_PACKAGE)
        val output = tempDirectory.resolve("report.json")

        writer.write(report, output)

        assertThat(openCount).isEqualTo(1)
        assertThat(readPaths).containsExactly(output)
        assertThat(readPaths).doesNotContain(partialPath)
    }

    @Test
    fun `fails a zero progress partial write without retrying and cleans the owned partial`() {
        val files = testFileOperations(
            partialChannelDecorator = EvidenceArchivePartialChannelDecorator { _, channel ->
                ProgressChannel(channel, zeroWrite = true)
            },
        )
        assertZeroProgressFailure(files, targetPublished = false)
    }

    @Test
    fun `fails a zero progress partial read without retrying and cleans the owned partial`() {
        val files = testFileOperations(
            partialChannelDecorator = EvidenceArchivePartialChannelDecorator { _, channel ->
                ProgressChannel(channel, zeroRead = true)
            },
        )
        assertZeroProgressFailure(files, targetPublished = false)
    }

    @Test
    fun `fails a zero progress target read without retrying and keeps the published target`() {
        val files = testFileOperations(targetChannelDecorator = { ProgressChannel(it, zeroRead = true) })
        assertZeroProgressFailure(files, targetPublished = true)
    }

    @Test
    fun `continues through positive short writes and reads`() {
        val files = testFileOperations(
            partialChannelDecorator = EvidenceArchivePartialChannelDecorator { _, channel ->
                ProgressChannel(channel, maxWrite = 3, maxRead = 5)
            },
            targetChannelDecorator = { ProgressChannel(it, maxRead = 7) },
        )
        val writer = EvidenceArchiveReportWriter(files)
        val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE))).run(WORK_PACKAGE)
        val output = tempDirectory.resolve("report.json")

        writer.write(report, output)

        assertThat(Files.readAllBytes(output)).containsExactly(*writer.canonicalBytes(report))
        assertThat(partialFiles()).isEmpty()
    }

    @Test
    fun `rejects a parent whose ownership key is unavailable`() {
        val files = testFileOperations(EvidenceArchiveFileKeyReader { _, _ -> null })

        assertThatThrownBy { EvidenceArchiveReportWriter(files).validate(tempDirectory.resolve("report.json")) }
            .isInstanceOf(EvidenceArchiveOperationFailure::class.java)
            .extracting("code")
            .isEqualTo("REPORT_OUTPUT_INVALID")
    }

    @Test
    fun `does not publish or delete a partial whose ownership key is unavailable`() {
        val files = testFileOperations(
            EvidenceArchiveFileKeyReader { path, _ -> if (path == tempDirectory) PARENT_FILE_KEY else null },
        )
        val writer = EvidenceArchiveReportWriter(files)
        val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE))).run(WORK_PACKAGE)
        val output = tempDirectory.resolve("report.json")

        assertThatThrownBy { writer.write(report, output) }
            .isInstanceOf(EvidenceArchiveOperationFailure::class.java)
            .extracting("code")
            .isEqualTo("REPORT_WRITE_FAILED")
        assertThat(Files.exists(output)).isFalse()
        assertThat(Files.list(tempDirectory).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".partial") } })
            .isTrue()
    }

    @Test
    fun `keeps a published target whose ownership key is unavailable`() {
        val output = tempDirectory.resolve("report.json")
        val files = testFileOperations(
            EvidenceArchiveFileKeyReader { path, _ -> if (path == output) null else TEST_FILE_KEY },
        )
        val writer = EvidenceArchiveReportWriter(files)
        val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE))).run(WORK_PACKAGE)

        assertThatThrownBy { writer.write(report, output) }
            .isInstanceOf(EvidenceArchiveOperationFailure::class.java)
            .extracting("code")
            .isEqualTo("REPORT_WRITE_FAILED")
        assertThat(Files.readAllBytes(output)).containsExactly(*writer.canonicalBytes(report))
    }

    @Test
    fun `does not delete a partial when cleanup ownership is null or changed`() {
        listOf<Any?>(null, "changed-file-key").forEachIndexed { index, cleanupKey ->
            val directory = Files.createDirectory(tempDirectory.resolve("cleanup-key-$index"))
            var partialReads = 0
            val delegate = testFileOperations(
                EvidenceArchiveFileKeyReader { path, _ ->
                    if (path.fileName.toString().endsWith(".partial")) {
                        partialReads += 1
                        if (partialReads == 1) PARTIAL_FILE_KEY else cleanupKey
                    } else {
                        PARENT_FILE_KEY
                    }
                },
            )
            lateinit var partialPath: Path
            val files = object : EvidenceArchiveReportFileOperations by delegate {
                override fun openPartial(parent: Path, partialFileName: String): EvidenceArchiveReportPartial =
                    delegate.openPartial(parent, partialFileName).also { partialPath = it.path }
                override fun writeAndForce(partial: EvidenceArchiveReportPartial, bytes: ByteArray) =
                    throw IOException("write failure")
            }
            val writer = EvidenceArchiveReportWriter(files)
            val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE))).run(WORK_PACKAGE)

            assertThatThrownBy { writer.write(report, directory.resolve("report.json")) }
                .isInstanceOf(EvidenceArchiveOperationFailure::class.java)
                .extracting("code")
                .isEqualTo("REPORT_CLEANUP_FAILED")
            assertThat(Files.exists(partialPath)).isTrue()
        }
    }

    @Test
    fun `rejects a posix report directory writable by group or others`() {
        val directory = Files.createDirectory(tempDirectory.resolve("shared-writable"))
        val posixView = Files.getFileAttributeView(directory, PosixFileAttributeView::class.java)
        assumeTrue(posixView != null)
        val permissions = checkNotNull(posixView).readAttributes().permissions().toMutableSet()
        permissions += PosixFilePermission.GROUP_WRITE
        Files.setPosixFilePermissions(directory, permissions)

        assertThatThrownBy { EvidenceArchiveReportWriter().validate(directory.resolve("report.json")) }
            .isInstanceOf(EvidenceArchiveOperationFailure::class.java)
            .extracting("code")
            .isEqualTo("REPORT_OUTPUT_INVALID")
    }

    @Test
    fun `report writer delegates directory access trust to the shared primitive`() {
        val checked = mutableListOf<Path>()
        val files = EvidenceArchiveReportFileOperations.nio(
            fileKeyReader = EvidenceArchiveFileKeyReader { _, _ -> TEST_FILE_KEY },
            directoryAccessReader = EvidenceArchiveDirectoryAccessReader { path ->
                checked.add(path)
                throw IOException("shared writable")
            },
        )
        val output = tempDirectory.resolve("shared-check.json")

        assertThatThrownBy { EvidenceArchiveReportWriter(files).validate(output) }
            .isInstanceOf(EvidenceArchiveOperationFailure::class.java)
            .extracting("code")
            .isEqualTo("REPORT_OUTPUT_INVALID")
        assertThat(checked).containsExactly(tempDirectory)
    }

    @Test
    fun `does not publish when the trusted parent identity changes`() {
        val delegate = testFileOperations()
        var revalidations = 0
        var published = false
        val files = object : EvidenceArchiveReportFileOperations by delegate {
            override fun revalidateDirectory(directory: EvidenceArchiveTrustedDirectory) {
                revalidations += 1
                if (revalidations == 1) throw IOException("parent changed")
                delegate.revalidateDirectory(directory)
            }
            override fun commitCreateOnly(partial: EvidenceArchiveReportPartial, output: Path) {
                published = true
                delegate.commitCreateOnly(partial, output)
            }
        }
        val writer = EvidenceArchiveReportWriter(files)
        val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE))).run(WORK_PACKAGE)
        val output = tempDirectory.resolve("report.json")

        assertThatThrownBy { writer.write(report, output) }
            .isInstanceOf(EvidenceArchiveOperationFailure::class.java)
            .extracting("code")
            .isEqualTo("REPORT_WRITE_FAILED")
        assertThat(published).isFalse()
        assertThat(Files.exists(output)).isFalse()
    }

    @Test
    fun `does not publish or delete when partial identity changes before publication`() {
        listOf("validate", "publish").forEach { stage ->
            val directory = Files.createDirectory(tempDirectory.resolve("partial-$stage"))
            val output = directory.resolve("report.json")
            val delegate = testFileOperations()
            var published = false
            var cleanupRejected = false
            val files = object : EvidenceArchiveReportFileOperations by delegate {
                override fun validatePartial(partial: EvidenceArchiveReportPartial, expectedBytes: ByteArray) {
                    if (stage == "validate") throw IOException("partial became a symlink")
                    delegate.validatePartial(partial, expectedBytes)
                }
                override fun commitCreateOnly(partial: EvidenceArchiveReportPartial, output: Path) {
                    if (stage == "publish") throw IOException("partial identity changed")
                    published = true
                    delegate.commitCreateOnly(partial, output)
                }
                override fun cleanupPartial(partial: EvidenceArchiveReportPartial) {
                    cleanupRejected = true
                    throw IOException("foreign partial must not be deleted")
                }
            }
            val writer = EvidenceArchiveReportWriter(files)
            val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE))).run(WORK_PACKAGE)

            assertThatThrownBy { writer.write(report, output) }
                .isInstanceOf(EvidenceArchiveOperationFailure::class.java)
                .extracting("code")
                .isEqualTo("REPORT_CLEANUP_FAILED")
            assertThat(published).isFalse()
            assertThat(cleanupRejected).isTrue()
            assertThat(Files.exists(output)).isFalse()
            assertThat(Files.list(directory).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".partial") } })
                .isTrue()
        }
    }

    @Test
    fun `keeps a changed published target for review`() {
        val output = tempDirectory.resolve("report.json")
        val replacement = "foreign target".toByteArray()
        val delegate = testFileOperations()
        val files = object : EvidenceArchiveReportFileOperations by delegate {
            override fun validatePublished(
                partial: EvidenceArchiveReportPartial,
                target: Path,
                expectedBytes: ByteArray,
            ) {
                Files.delete(target)
                Files.write(target, replacement)
                delegate.validatePublished(partial, target, expectedBytes)
            }
        }
        val writer = EvidenceArchiveReportWriter(files)
        val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE))).run(WORK_PACKAGE)

        assertThatThrownBy { writer.write(report, output) }
            .isInstanceOf(EvidenceArchiveOperationFailure::class.java)
            .extracting("code")
            .isEqualTo("REPORT_WRITE_FAILED")
        assertThat(Files.readAllBytes(output)).containsExactly(*replacement)
    }

    @Test
    fun `rethrows writer errors after cleaning only an owned partial`() {
        listOf("write", "publish", "force").forEach { stage ->
            val directory = Files.createDirectory(tempDirectory.resolve(stage))
            val output = directory.resolve("report.json")
            val delegate = testFileOperations()
            val files = object : EvidenceArchiveReportFileOperations by delegate {
                override fun writeAndForce(partial: EvidenceArchiveReportPartial, bytes: ByteArray) {
                    if (stage == "write") throw AssertionError("fatal-write")
                    delegate.writeAndForce(partial, bytes)
                }
                override fun commitCreateOnly(partial: EvidenceArchiveReportPartial, output: Path) {
                    if (stage == "publish") throw AssertionError("fatal-publish")
                    delegate.commitCreateOnly(partial, output)
                }
                override fun forceDirectory(path: Path) {
                    if (stage == "force") throw AssertionError("fatal-force")
                    delegate.forceDirectory(path)
                }
            }
            val writer = EvidenceArchiveReportWriter(files)
            val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE))).run(WORK_PACKAGE)

            assertThatThrownBy { writer.write(report, output) }
                .isInstanceOf(AssertionError::class.java)
                .hasMessage("fatal-$stage")
            assertThat(Files.list(directory).use { it.map(Path::getFileName).map(Path::toString).toList() })
                .containsExactlyElementsOf(if (stage == "force") listOf("report.json") else emptyList())
        }
    }

    @Test
    fun `does not delete a foreign partial while cleaning after an error`() {
        val foreign = "foreign partial".toByteArray()
        val delegate = testFileOperations()
        lateinit var partialPath: Path
        val files = object : EvidenceArchiveReportFileOperations by delegate {
            override fun openPartial(parent: Path, partialFileName: String): EvidenceArchiveReportPartial =
                delegate.openPartial(parent, partialFileName).also { partialPath = it.path }
            override fun writeAndForce(partial: EvidenceArchiveReportPartial, bytes: ByteArray) =
                throw AssertionError("fatal-write")
            override fun cleanupPartial(partial: EvidenceArchiveReportPartial) {
                Files.delete(partial.path)
                Files.write(partial.path, foreign)
                throw IOException("partial identity changed")
            }
        }
        val writer = EvidenceArchiveReportWriter(files)
        val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE))).run(WORK_PACKAGE)

        val failure = runCatching { writer.write(report, tempDirectory.resolve("report.json")) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(AssertionError::class.java).hasMessage("fatal-write")
        assertThat(checkNotNull(failure).suppressed).isNotEmpty()
        assertThat(Files.readAllBytes(partialPath)).containsExactly(*foreign)
    }

    private fun testFileOperations(
        fileKeyReader: EvidenceArchiveFileKeyReader = EvidenceArchiveFileKeyReader { _, _ -> TEST_FILE_KEY },
        readPaths: MutableList<Path>? = null,
        partialChannelDecorator: EvidenceArchivePartialChannelDecorator =
            EvidenceArchivePartialChannelDecorator { _, channel -> channel },
        targetChannelDecorator: (EvidenceArchiveReportChannel) -> EvidenceArchiveReportChannel = { it },
    ): EvidenceArchiveReportFileOperations = EvidenceArchiveReportFileOperations.nio(
        fileKeyReader,
        EvidenceArchiveReadChannelOpener { path ->
            readPaths?.add(path)
            targetChannelDecorator(testChannel(FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))
        },
        partialChannelDecorator,
    )

    private fun assertZeroProgressFailure(files: EvidenceArchiveReportFileOperations, targetPublished: Boolean) {
        val writer = EvidenceArchiveReportWriter(files)
        val report = runner(ScriptedArchiveAdapter(resultFor(FIRST_SOURCE), resultFor(SECOND_SOURCE))).run(WORK_PACKAGE)
        val output = tempDirectory.resolve("report.json")

        assertThatThrownBy { writer.write(report, output) }
            .isInstanceOf(EvidenceArchiveOperationFailure::class.java)
            .extracting("code")
            .isEqualTo("REPORT_WRITE_FAILED")
        assertThat(Files.exists(output)).isEqualTo(targetPublished)
        if (targetPublished) {
            assertThat(Files.readAllBytes(output)).containsExactly(*writer.canonicalBytes(report))
        }
        assertThat(partialFiles()).isEmpty()
    }

    private fun partialFiles(): List<Path> = Files.list(tempDirectory).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".partial") }.toList()
    }

    private fun testChannel(channel: FileChannel): EvidenceArchiveReportChannel =
        object : EvidenceArchiveReportChannel {
            override fun write(buffer: ByteBuffer): Int = channel.write(buffer)
            override fun read(buffer: ByteBuffer): Int = channel.read(buffer)
            override fun position(position: Long) { channel.position(position) }
            override fun size(): Long = channel.size()
            override fun force(metadata: Boolean) = channel.force(metadata)
            override fun close() = channel.close()
        }

    private class ProgressChannel(
        private val delegate: EvidenceArchiveReportChannel,
        private val zeroWrite: Boolean = false,
        private val zeroRead: Boolean = false,
        private val maxWrite: Int = Int.MAX_VALUE,
        private val maxRead: Int = Int.MAX_VALUE,
    ) : EvidenceArchiveReportChannel {
        private var writeCalls = 0
        private var readCalls = 0

        override fun write(buffer: ByteBuffer): Int {
            writeCalls += 1
            if (zeroWrite) {
                if (writeCalls > 1) throw AssertionError("zero write was retried")
                return 0
            }
            return withLimitedBuffer(buffer, maxWrite, delegate::write)
        }

        override fun read(buffer: ByteBuffer): Int {
            readCalls += 1
            if (zeroRead) {
                if (readCalls > 1) throw AssertionError("zero read was retried")
                return 0
            }
            return withLimitedBuffer(buffer, maxRead, delegate::read)
        }

        override fun position(position: Long) = delegate.position(position)
        override fun size(): Long = delegate.size()
        override fun force(metadata: Boolean) = delegate.force(metadata)
        override fun close() = delegate.close()

        private fun withLimitedBuffer(buffer: ByteBuffer, maximum: Int, operation: (ByteBuffer) -> Int): Int {
            val originalLimit = buffer.limit()
            buffer.limit(minOf(originalLimit, buffer.position() + maximum))
            return try {
                operation(buffer)
            } finally {
                buffer.limit(originalLimit)
            }
        }
    }

    private fun runner(adapter: ScriptedArchiveAdapter, policy: ArchivePolicy = POLICY): EvidenceArchiveRunner {
        val evaluator = EvaluateArchiveCapability(listOf(adapter), TimeProvider { CHECKED_AT })
        val facade = ArchiveEvidence(policy, evaluator, listOf(adapter))
        val times = ArrayDeque(listOf(STARTED_AT, COMPLETED_AT))
        return EvidenceArchiveRunner(
            archiveEvidence = facade,
            timeProvider = TimeProvider { times.removeFirstOrNull() ?: COMPLETED_AT },
            executionIdProvider = { EXECUTION_ID },
            reportWriter = EvidenceArchiveReportWriter(testFileOperations()),
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
                verifier = "SHA-256",
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

    private fun rawResultFor(source: VerifiedArchiveSource): ArchiveResult {
        val result = resultFor(source)
        val payloadKey = "raw % # ? [x] café 😀/path=/var/tmp/${source.artifactId}.zip"
        val receiptKey = "receipt % # ? [x] café 😀/path=/var/tmp/${source.artifactId}.json"
        return result.copy(
            receipt = result.receipt.copy(
                payload = result.receipt.payload.copy(
                    locator = "s3://$RAW_BUCKET/$payloadKey",
                    bucket = RAW_BUCKET,
                    key = payloadKey,
                    versionId = "latest",
                ),
            ),
            receiptReference = result.receiptReference.copy(
                locator = "s3://$RAW_BUCKET/$receiptKey",
                versionId = "latest",
            ),
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

    private class AuthorizationBoundArchiveAdapter(vararg results: ArchiveResult) : ArchiveAdapter {
        override val provider = ArchiveProvider.S3_COMPATIBLE
        private val results = ArrayDeque(results.toList())
        val checkedAt = mutableListOf<Instant>()

        override fun probe(policy: ArchivePolicy, context: CapabilityProbeContext): List<CapabilityCheck> =
            listOf(CapabilityCheck("provider", true, ArchiveCapabilityState.EXTERNAL_VERIFIED.name))

        override fun archive(
            command: ArchiveCommand,
            policy: ArchivePolicy,
            authorization: ArchiveAuthorization,
        ): ArchiveResult {
            val capabilityCheckedAt = authorization.report.checkedAt
            checkedAt += capabilityCheckedAt
            val result = results.removeFirst()
            return result.copy(receipt = result.receipt.copy(capabilityCheckedAt = capabilityCheckedAt))
        }
    }

    private class AdvancingTimeProvider(private val initial: Instant) : TimeProvider {
        private var invocation = 0L

        override fun now(): Instant = initial.plusSeconds(invocation++)
    }

    private companion object {
        const val WORK_PACKAGE_ID = "V0-2-EVIDENCE-ARCHIVE-001"
        const val FIRST_COMMIT = "892fb23ce75e7f74a05c1b5e304fccace70ee8d3"
        const val SECOND_COMMIT = "8687d49c9566030bb0829752dbe5dda45af02f4b"
        const val FIRST_SHA = "1f087ef27cfabbb2152d06fc002eb0772c2efbbb63964d6b13ec5f0d7a73ed7a"
        const val SECOND_SHA = "e7602924fe67fd6eff75ebfe5d48122240639d883edc58dc164c419893d979ca"
        const val BUCKET = "company-evidence"
        const val ACCESS_OWNER = "Release Security"
        const val RETENTION_POLICY = "P730D"
        const val IMMUTABILITY_CONTROL = "COMPLIANCE"
        const val RAW_BUCKET = "Tenant_Bucket[Prod] raw"
        const val TEST_FILE_KEY = "test-file-key"
        const val PARENT_FILE_KEY = "parent-file-key"
        const val PARTIAL_FILE_KEY = "partial-file-key"
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
        val CHECKED_AT: Instant = STARTED_AT
        val SECOND_CHECKED_AT: Instant = CHECKED_AT.plusSeconds(1)
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
