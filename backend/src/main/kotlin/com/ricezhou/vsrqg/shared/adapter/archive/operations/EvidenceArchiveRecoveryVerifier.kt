package com.ricezhou.vsrqg.shared.adapter.archive.operations

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.ricezhou.vsrqg.shared.adapter.archive.ExactObjectDownload
import com.ricezhou.vsrqg.shared.adapter.archive.ObjectProtectionSnapshot
import com.ricezhou.vsrqg.shared.adapter.archive.S3Gateway
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.ArchiveReceipt
import com.ricezhou.vsrqg.shared.application.archive.ArchiveUnavailable
import com.ricezhou.vsrqg.shared.application.archive.RuntimeIdentityRef
import com.ricezhou.vsrqg.shared.application.archive.StoredObjectRef
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Collections
import java.util.UUID
import org.erdtman.jcs.JsonCanonicalizer

data class EvidenceArchiveProtectionFacts(
    val actualMode: String,
    val retainUntil: Instant,
)

data class EvidenceArchiveRecoveredObject(
    val reference: EvidenceArchiveExactObjectReference,
    val recoveredSha256: String,
    val recoveredSizeBytes: Long,
    val protection: EvidenceArchiveProtectionFacts,
)

data class EvidenceArchiveRecoveredArtifact(
    val artifactId: String,
    val sourceRunId: String,
    val sourceCommit: String,
    val receiptArchivedAt: Instant,
    val payload: EvidenceArchiveRecoveredObject,
    val receipt: EvidenceArchiveRecoveredObject,
)

data class EvidenceArchiveRecoveryReport(
    val schemaVersion: Int,
    val workPackageId: String,
    val executionId: String?,
    val descriptorSha256: String?,
    val pilotManifestSha256: String?,
    val startedAt: Instant,
    val completedAt: Instant,
    val archiveIdentity: RuntimeIdentityRef?,
    val verifierIdentity: RuntimeIdentityRef?,
    val artifacts: List<EvidenceArchiveRecoveredArtifact>,
    val status: OperationStatus,
    val errorCode: String?,
    val cleanupStatus: OperationStatus,
    val cleanupErrorCode: String?,
)

internal class TrustedArchiveExecution private constructor(
    val report: EvidenceArchiveExecutionReport,
) {
    companion object {
        fun fromValidated(report: EvidenceArchiveExecutionReport) = TrustedArchiveExecution(report)
    }
}

internal class UntrustedArchiveExecution internal constructor(
    internal val schemaVersion: Int,
    internal val workPackageId: String,
    internal val executionId: String,
    internal val descriptorSha256: String,
    internal val pilotManifestSha256: String,
    internal val startedAt: Instant,
    internal val completedAt: Instant,
    internal val policyFingerprint: String?,
    internal val capabilityCheckedAt: Instant?,
    internal val runtimeIdentity: RuntimeIdentityRef?,
    internal val artifacts: List<EvidenceArchiveArtifactReport>,
    internal val accessOwner: String?,
    internal val retentionPolicy: String?,
    internal val immutabilityControl: String?,
    internal val status: OperationStatus,
    internal val errorCode: String?,
) {
    internal fun candidate() = EvidenceArchiveExecutionReport(
        schemaVersion,
        workPackageId,
        executionId,
        descriptorSha256,
        pilotManifestSha256,
        startedAt,
        completedAt,
        policyFingerprint,
        capabilityCheckedAt,
        runtimeIdentity,
        artifacts,
        accessOwner,
        retentionPolicy,
        immutabilityControl,
        status,
        errorCode,
    )

    override fun toString(): String = "UntrustedArchiveExecution(redacted)"

    internal companion object {
        fun from(candidate: EvidenceArchiveExecutionReport) = UntrustedArchiveExecution(
            candidate.schemaVersion,
            candidate.workPackageId,
            candidate.executionId,
            candidate.descriptorSha256,
            candidate.pilotManifestSha256,
            candidate.startedAt,
            candidate.completedAt,
            candidate.policyFingerprint,
            candidate.capabilityCheckedAt,
            candidate.runtimeIdentity,
            Collections.unmodifiableList(candidate.artifacts.toList()),
            candidate.accessOwner,
            candidate.retentionPolicy,
            candidate.immutabilityControl,
            candidate.status,
            candidate.errorCode,
        )
    }
}

internal fun interface RecoveryFileKeyReader {
    fun read(path: Path, attributes: BasicFileAttributes): Any?
}

internal fun interface RecoveryPartialCleanup {
    fun cleanup(path: Path)
}

internal fun interface RecoveryRealPathResolver {
    fun resolve(path: Path): Path
}

internal enum class RecoveryRootExpectation {
    EMPTY,
    IDENTITY_ONLY,
    NON_EMPTY,
}

internal enum class RecoveryRootGuardFailureReason {
    IDENTITY_CHANGED,
    NOT_EMPTY,
}

internal class RecoveryRootGuardFailure(val reason: RecoveryRootGuardFailureReason) : IOException()

internal fun interface RecoveryRootGuard {
    fun require(root: EvidenceArchiveTrustedDirectory, expectation: RecoveryRootExpectation)

    companion object {
        fun nio(
            realPathResolver: RecoveryRealPathResolver,
            fileKeyReader: RecoveryFileKeyReader,
            directoryAccessReader: EvidenceArchiveDirectoryAccessReader,
        ): RecoveryRootGuard = RecoveryRootGuard { root, expectation ->
            val current = EvidenceArchiveTrustedDirectory.require(
                root.path,
                realPathResolver::resolve,
                fileKeyReader::read,
                directoryAccessReader,
            )
            if (current != root) throw RecoveryRootGuardFailure(RecoveryRootGuardFailureReason.IDENTITY_CHANGED)
            when (expectation) {
                RecoveryRootExpectation.EMPTY -> if (Files.list(root.path).use { it.findAny().isPresent }) {
                    throw RecoveryRootGuardFailure(RecoveryRootGuardFailureReason.NOT_EMPTY)
                }
                RecoveryRootExpectation.NON_EMPTY -> if (Files.list(root.path).use { it.findAny().isEmpty }) {
                    throw RecoveryRootGuardFailure(RecoveryRootGuardFailureReason.IDENTITY_CHANGED)
                }
                RecoveryRootExpectation.IDENTITY_ONLY -> Unit
            }
        }
    }
}

internal interface RecoveryReportPublishOperations {
    fun createLink(target: Path, partial: Path)
    fun validatePublished(
        target: Path,
        partial: Path,
        directory: EvidenceArchiveTrustedDirectory,
        expectedBytes: ByteArray,
    )
    fun forceDirectory(directory: Path)
    fun cleanupPartial(partial: Path)
    fun createCompletionMarker(marker: Path)

    companion object {
        fun nio(
            stableFileReader: EvidenceArchiveStableFileReader = EvidenceArchiveStableFileReader(),
        ): RecoveryReportPublishOperations = object : RecoveryReportPublishOperations {

            override fun createLink(target: Path, partial: Path) {
                Files.createLink(target, partial)
            }

            override fun validatePublished(
                target: Path,
                partial: Path,
                directory: EvidenceArchiveTrustedDirectory,
                expectedBytes: ByteArray,
            ) {
                if (!Files.isSameFile(target, partial)) throw IOException()
                stableFileReader.read(target, directory, expectedBytes.size, 1, expectedBytes)
            }

            override fun forceDirectory(directory: Path) {
                if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                    FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
                }
            }

            override fun cleanupPartial(partial: Path) {
                Files.delete(partial)
            }

            override fun createCompletionMarker(marker: Path) {
                Files.createFile(marker)
                try {
                    stableFileReader.read(marker, 0, 0, byteArrayOf())
                } catch (failure: Throwable) {
                    try {
                        Files.deleteIfExists(marker)
                    } catch (cleanup: Throwable) {
                        failure.addSuppressed(cleanup)
                    }
                    throw failure
                }
            }
        }
    }
}

class EvidenceArchiveRecoveryVerifier internal constructor(
    private val gateway: S3Gateway,
    private val timeProvider: TimeProvider,
    private val operationTimeout: Duration,
    private val fileKeyReader: RecoveryFileKeyReader = RecoveryFileKeyReader { _, attributes -> attributes.fileKey() },
    private val partialCleanup: RecoveryPartialCleanup = RecoveryPartialCleanup(Files::delete),
    private val realPathResolver: RecoveryRealPathResolver = RecoveryRealPathResolver { it.toRealPath() },
    private val directoryAccessReader: EvidenceArchiveDirectoryAccessReader = EvidenceArchiveDirectoryAccessReader.nio(),
    private val stableFileReader: EvidenceArchiveStableFileReader = EvidenceArchiveStableFileReader(
        EvidenceArchiveStableFileAccess.nio().copy(fileKey = fileKeyReader::read),
        trustedDirectoryReader = { parent ->
            EvidenceArchiveTrustedDirectory.require(
                parent,
                realPathResolver::resolve,
                fileKeyReader::read,
                directoryAccessReader,
            )
        },
    ),
    private val reportPublishOperations: RecoveryReportPublishOperations = RecoveryReportPublishOperations.nio(
        stableFileReader,
    ),
    private val recoveryRootGuard: RecoveryRootGuard = RecoveryRootGuard.nio(
        realPathResolver,
        fileKeyReader,
        directoryAccessReader,
    ),
) {
    private val workPackageParser = EvidenceArchiveWorkPackageParser()

    fun parseWorkPackage(descriptorBytes: ByteArray): VerifiedEvidenceArchiveWorkPackage {
        val descriptor = try {
            workPackageParser.parse(descriptorBytes)
        } catch (_: EvidenceArchiveInputFailure) {
            mismatch("workPackage")
        }
        val artifacts = descriptor.artifacts.map {
            VerifiedArchiveSource(
                it.artifactId,
                it.sourceRunId,
                it.sourceCommit,
                Path.of(it.fileName),
                it.sizeBytes,
                it.sha256,
            )
        }
        return VerifiedEvidenceArchiveWorkPackage(
            descriptor.workPackageId,
            sha256(descriptorBytes),
            descriptor.pilotManifest.sha256,
            Collections.unmodifiableList(artifacts),
        )
    }

    internal fun parseArchiveReport(reportBytes: ByteArray): UntrustedArchiveExecution {
        if (reportBytes.isEmpty() || reportBytes.size > MAX_INPUT_BYTES) mismatch("archiveReport")
        val canonical = canonicalInput(reportBytes, "archiveReport")
        if (!canonical.contentEquals(reportBytes)) mismatch("archiveReport.canonical")
        val root = readJson(reportBytes, "archiveReport")
        requireObject(root, ARCHIVE_REPORT_FIELDS, "archiveReport")
        val artifactsNode = requireField(root, "artifacts", "archiveReport")
        if (!artifactsNode.isArray) mismatch("archiveReport.artifacts")
        val artifacts = artifactsNode.mapIndexed { index, node ->
            val prefix = "archiveReport.artifacts[$index]"
            requireObject(node, ARCHIVE_ARTIFACT_FIELDS, prefix)
            EvidenceArchiveArtifactReport(
                text(node, "artifactId", prefix),
                text(node, "sourceRunId", prefix),
                text(node, "sourceCommit", prefix),
                parseExactReference(requireField(node, "payload", prefix), "$prefix.payload"),
                parseExactReference(requireField(node, "receiptReference", prefix), "$prefix.receiptReference"),
            )
        }
        return UntrustedArchiveExecution(
            schemaVersion = exactSchemaVersion(root, "schemaVersion", "archiveReport"),
            workPackageId = text(root, "workPackageId", "archiveReport"),
            executionId = text(root, "executionId", "archiveReport"),
            descriptorSha256 = text(root, "descriptorSha256", "archiveReport"),
            pilotManifestSha256 = text(root, "pilotManifestSha256", "archiveReport"),
            startedAt = instant(root, "startedAt", "archiveReport"),
            completedAt = instant(root, "completedAt", "archiveReport"),
            policyFingerprint = nullableText(root, "policyFingerprint", "archiveReport"),
            capabilityCheckedAt = nullableText(root, "capabilityCheckedAt", "archiveReport")?.let(::parseInstant),
            runtimeIdentity = parseNullableIdentity(requireField(root, "runtimeIdentity", "archiveReport")),
            artifacts = Collections.unmodifiableList(artifacts),
            accessOwner = nullableText(root, "accessOwner", "archiveReport"),
            retentionPolicy = nullableText(root, "retentionPolicy", "archiveReport"),
            immutabilityControl = nullableText(root, "immutabilityControl", "archiveReport"),
            status = enumValue(text(root, "status", "archiveReport"), "archiveReport.status"),
            errorCode = nullableText(root, "errorCode", "archiveReport"),
        )
    }

    /** Stages a safe diagnostic before parsing either untrusted input, then publishes one immutable final report. */
    fun recover(
        descriptorBytes: ByteArray,
        archiveReportBytes: ByteArray,
        recoveryRoot: Path,
        output: Path,
    ): EvidenceArchiveRecoveryReport = recoverWithStagedOutput(recoveryRoot, output) {
        descriptorBytes to archiveReportBytes
    }

    fun recoverFiles(
        descriptor: Path,
        archiveReport: Path,
        recoveryRoot: Path,
        output: Path,
    ): EvidenceArchiveRecoveryReport = recoverWithStagedOutput(recoveryRoot, output) {
        readInput(descriptor, MAX_INPUT_BYTES, "workPackage") to
            readInput(archiveReport, MAX_INPUT_BYTES, "archiveReport")
    }

    private fun recoverWithStagedOutput(
        recoveryRoot: Path,
        output: Path,
        inputs: () -> Pair<ByteArray, ByteArray>,
    ): EvidenceArchiveRecoveryReport {
        val startedAt = timeProvider.now()
        val staging = beginOutput(output, recoveryRoot, startedAt)
        try {
            val report = try {
                val (descriptorBytes, archiveReportBytes) = inputs()
                val workPackage = parseWorkPackage(descriptorBytes)
                val archiveExecution = parseArchiveReport(archiveReportBytes)
                execute(workPackage, archiveExecution, recoveryRoot, throwFailure = false, startedAt = startedAt)
            } catch (expected: EvidenceArchiveVerificationFailure) {
                safeFailureReport(startedAt, expected.code.substringBefore(':'))
            } catch (_: EvidenceArchiveInputFailure) {
                safeFailureReport(startedAt, "RECEIPT_MISMATCH")
            } catch (_: ArchiveUnavailable) {
                safeFailureReport(startedAt, "DOWNLOAD_FAILED")
            } catch (_: IOException) {
                safeFailureReport(startedAt, "UNEXPECTED_FAILURE")
            } catch (_: SecurityException) {
                safeFailureReport(startedAt, "UNEXPECTED_FAILURE")
            } catch (_: IllegalArgumentException) {
                safeFailureReport(startedAt, "UNEXPECTED_FAILURE")
            } catch (_: RuntimeException) {
                safeFailureReport(startedAt, "UNEXPECTED_FAILURE")
            }
            val finalReport = staging.reconcileFinalCleanup(report)
            return staging.publish(finalReport)
        } catch (error: Error) {
            staging.cleanupAfterError(error)
            throw error
        } catch (expected: EvidenceArchiveVerificationFailure) {
            staging.cleanupOrSuppress(expected)
            throw expected
        } catch (_: Exception) {
            val failure = verificationFailure("REPORT_WRITE_FAILED", "output")
            staging.cleanupOrSuppress(failure)
            throw failure
        }
    }

    private fun safeFailureReport(startedAt: Instant, code: String): EvidenceArchiveRecoveryReport =
        EvidenceArchiveRecoveryReport(
            schemaVersion = REPORT_SCHEMA_VERSION,
            workPackageId = WORK_PACKAGE_ID,
            executionId = null,
            descriptorSha256 = null,
            pilotManifestSha256 = null,
            startedAt = startedAt,
            completedAt = timeProvider.now().coerceAtLeast(startedAt),
            archiveIdentity = null,
            verifierIdentity = null,
            artifacts = emptyList(),
            status = OperationStatus.FAIL,
            errorCode = sanitizeFailureCode(code),
            cleanupStatus = OperationStatus.PASS,
            cleanupErrorCode = null,
        )

    private fun sanitizeFailureCode(code: String): String = code.takeIf { it in FAILURE_CODES } ?: "UNEXPECTED_FAILURE"

    fun verify(
        workPackage: VerifiedEvidenceArchiveWorkPackage,
        archiveReport: EvidenceArchiveExecutionReport,
        recoveryRoot: Path,
    ): EvidenceArchiveRecoveryReport = execute(
        workPackage,
        UntrustedArchiveExecution.from(archiveReport),
        recoveryRoot,
        throwFailure = true,
        startedAt = null,
    )

    fun verifyReport(
        workPackage: VerifiedEvidenceArchiveWorkPackage,
        archiveReport: EvidenceArchiveExecutionReport,
        recoveryRoot: Path,
    ): EvidenceArchiveRecoveryReport = execute(
        workPackage,
        UntrustedArchiveExecution.from(archiveReport),
        recoveryRoot,
        throwFailure = false,
        startedAt = null,
    )

    private fun execute(
        workPackage: VerifiedEvidenceArchiveWorkPackage,
        archiveReport: UntrustedArchiveExecution,
        recoveryRoot: Path,
        throwFailure: Boolean,
        startedAt: Instant?,
    ): EvidenceArchiveRecoveryReport {
        val operationStartedAt = startedAt ?: timeProvider.now()
        val recovered = mutableListOf<EvidenceArchiveRecoveredArtifact>()
        val receiptCapabilityChecks = mutableListOf<Instant>()
        val partials = mutableListOf<OwnedRecoveryPartial>()
        var trustedArchive: TrustedArchiveExecution? = null
        var archiveIdentity: RuntimeIdentityRef? = null
        var verifierIdentity: RuntimeIdentityRef? = null
        var failure: EvidenceArchiveVerificationFailure? = null
        var root: EvidenceArchiveTrustedDirectory? = null
        try {
            validateWorkPackage(workPackage)
            trustedArchive = validateArchiveReport(workPackage, archiveReport, operationStartedAt)
            val trustedReport = trustedArchive.report
            archiveIdentity = trustedReport.runtimeIdentity
            root = trustRecoveryRoot(recoveryRoot)
            verifierIdentity = attestVerifierIdentity(archiveIdentity)
            for ((index, pair) in workPackage.artifacts.zip(trustedReport.artifacts).withIndex()) {
                recovered += recoverArtifact(
                    index,
                    pair.first,
                    pair.second,
                    trustedReport,
                    root,
                    partials,
                    receiptCapabilityChecks,
                    operationStartedAt,
                )
            }
            if (receiptCapabilityChecks.maxOrNull() != trustedReport.capabilityCheckedAt) {
                mismatch("archiveReport.capabilityCheckedAt")
            }
            verifierIdentity = reattestVerifierIdentity(archiveIdentity, verifierIdentity)
        } catch (expected: EvidenceArchiveVerificationFailure) {
            failure = expected
        } catch (expected: ArchiveUnavailable) {
            failure = verificationFailure("DOWNLOAD_FAILED", "provider")
        } catch (_: IOException) {
            failure = verificationFailure("UNEXPECTED_FAILURE", "operation")
        } catch (_: SecurityException) {
            failure = verificationFailure("UNEXPECTED_FAILURE", "operation")
        } catch (_: IllegalArgumentException) {
            failure = verificationFailure("UNEXPECTED_FAILURE", "operation")
        } catch (_: RuntimeException) {
            failure = verificationFailure("UNEXPECTED_FAILURE", "operation")
        } catch (error: Error) {
            cleanupAfterError(root, partials, error)
            throw error
        }

        val cleanupFailed = try {
            cleanupPartials(root, partials)
        } catch (error: Error) {
            cleanupPartialsAfterError(root, partials, error)
            throw error
        }
        if (cleanupFailed && failure == null) failure = verificationFailure("RECOVERY_CLEANUP_FAILED", "recoveryRoot")
        val completedAt = timeProvider.now().coerceAtLeast(operationStartedAt)
        if (failure == null) {
            expiryCheck@ for ((index, artifact) in recovered.withIndex()) {
                if (!artifact.receipt.protection.retainUntil.isAfter(completedAt)) {
                    failure = verificationFailure("PROTECTION_INSUFFICIENT", "artifacts[$index].receiptReference.protection")
                    break@expiryCheck
                }
                if (!artifact.payload.protection.retainUntil.isAfter(completedAt)) {
                    failure = verificationFailure("PROTECTION_INSUFFICIENT", "artifacts[$index].payload.protection")
                    break@expiryCheck
                }
            }
        }
        val trusted = trustedArchive?.report
        val result = EvidenceArchiveRecoveryReport(
            schemaVersion = REPORT_SCHEMA_VERSION,
            workPackageId = WORK_PACKAGE_ID,
            executionId = trusted?.executionId,
            descriptorSha256 = trusted?.let { workPackage.descriptorSha256 },
            pilotManifestSha256 = trusted?.let { workPackage.pilotManifestSha256 },
            startedAt = operationStartedAt,
            completedAt = completedAt,
            archiveIdentity = archiveIdentity,
            verifierIdentity = verifierIdentity,
            artifacts = Collections.unmodifiableList(recovered.toList()),
            status = if (failure == null && recovered.size == REQUIRED_ARTIFACT_COUNT) OperationStatus.PASS else OperationStatus.FAIL,
            errorCode = failure?.code?.substringBefore(':'),
            cleanupStatus = if (cleanupFailed) OperationStatus.FAIL else OperationStatus.PASS,
            cleanupErrorCode = if (cleanupFailed) "RECOVERY_CLEANUP_FAILED" else null,
        )
        if (throwFailure && failure != null) throw failure
        return result
    }

    private fun validateWorkPackage(workPackage: VerifiedEvidenceArchiveWorkPackage) {
        if (workPackage.workPackageId != WORK_PACKAGE_ID) mismatch("workPackage.workPackageId")
        if (!SHA256.matches(workPackage.descriptorSha256)) mismatch("workPackage.descriptorSha256")
        if (!SHA256.matches(workPackage.pilotManifestSha256)) mismatch("workPackage.pilotManifestSha256")
        if (workPackage.artifacts.size != REQUIRED_ARTIFACT_COUNT ||
            workPackage.artifacts.map { it.artifactId }.toSet().size != REQUIRED_ARTIFACT_COUNT
        ) mismatch("workPackage.artifacts")
        workPackage.artifacts.forEachIndexed { index, source ->
            val prefix = "workPackage.artifacts[$index]"
            if (!DECIMAL_ID.matches(source.artifactId) || !DECIMAL_ID.matches(source.sourceRunId) ||
                !COMMIT.matches(source.sourceCommit) || !SHA256.matches(source.sha256) || source.sizeBytes <= 0
            ) mismatch(prefix)
        }
    }

    private fun validateArchiveReport(
        workPackage: VerifiedEvidenceArchiveWorkPackage,
        untrusted: UntrustedArchiveExecution,
        operationStartedAt: Instant,
    ): TrustedArchiveExecution {
        val report = untrusted.candidate()
        if (report.status != OperationStatus.PASS || report.errorCode != null) mismatch("archiveReport.status")
        if (report.schemaVersion != REPORT_SCHEMA_VERSION) mismatch("archiveReport.schemaVersion")
        if (report.workPackageId != workPackage.workPackageId) mismatch("archiveReport.workPackageId")
        if (report.descriptorSha256 != workPackage.descriptorSha256) mismatch("archiveReport.descriptorSha256")
        if (report.pilotManifestSha256 != workPackage.pilotManifestSha256) mismatch("archiveReport.pilotManifestSha256")
        if (!EXECUTION_ID.matches(report.executionId)) mismatch("archiveReport.executionId")
        if (report.completedAt < report.startedAt) mismatch("archiveReport.completedAt")
        if (report.completedAt > operationStartedAt) mismatch("archiveReport.completedAt")
        if (!SHA256.matches(report.policyFingerprint ?: "")) mismatch("archiveReport.policyFingerprint")
        if (report.capabilityCheckedAt == null) mismatch("archiveReport.capabilityCheckedAt")
        val identity = report.runtimeIdentity
        if (identity == null || identity.provider != ArchiveProvider.S3_COMPATIBLE ||
            !SHA256.matches(identity.principalFingerprint)
        ) mismatch("archiveReport.runtimeIdentity")
        if (!EvidenceArchiveReportSafety.safeOwner(report.accessOwner ?: "")) mismatch("archiveReport.accessOwner")
        val retention = parseRetention(report.retentionPolicy ?: mismatch("archiveReport.retentionPolicy"))
        if (retention.isZero || retention.isNegative) mismatch("archiveReport.retentionPolicy")
        if (report.immutabilityControl != APPROVED_MODE) mismatch("archiveReport.immutabilityControl")
        if (report.artifacts.size != REQUIRED_ARTIFACT_COUNT || workPackage.artifacts.size != REQUIRED_ARTIFACT_COUNT) {
            mismatch("archiveReport.artifacts")
        }
        val artifactIds = report.artifacts.map { it.artifactId }
        if (artifactIds.toSet().size != artifactIds.size) mismatch("archiveReport.artifacts")
        workPackage.artifacts.zip(report.artifacts).forEachIndexed { index, (source, artifact) ->
            if (artifact.artifactId != source.artifactId) mismatch("archiveReport.artifacts[$index].artifactId")
            if (artifact.sourceRunId != source.sourceRunId) mismatch("archiveReport.artifacts[$index].sourceRunId")
            if (artifact.sourceCommit != source.sourceCommit) mismatch("archiveReport.artifacts[$index].sourceCommit")
            validateExactReference(artifact.payload, "artifacts[$index].payload")
            validateExactReference(artifact.receiptReference, "artifacts[$index].receiptReference")
            if (artifact.payload.sha256 != source.sha256) mismatch("archiveReport.artifacts[$index].payload.sha256")
            if (artifact.payload.sizeBytes != source.sizeBytes) mismatch("archiveReport.artifacts[$index].payload.sizeBytes")
        }
        return TrustedArchiveExecution.fromValidated(report)
    }

    private fun attestVerifierIdentity(archiveIdentity: RuntimeIdentityRef?): RuntimeIdentityRef {
        val identity = try {
            gateway.runtimeIdentity(operationTimeout)
        } catch (_: ArchiveUnavailable) {
            fail("DOWNLOAD_FAILED", "runtimeIdentity")
        }
        if (identity.provider != ArchiveProvider.S3_COMPATIBLE || !SHA256.matches(identity.principalFingerprint)) {
            fail("DOWNLOAD_FAILED", "runtimeIdentity")
        }
        if (identity == archiveIdentity) fail("SAME_RUNTIME_IDENTITY", "runtimeIdentity")
        return identity
    }

    private fun reattestVerifierIdentity(
        archiveIdentity: RuntimeIdentityRef?,
        initialIdentity: RuntimeIdentityRef?,
    ): RuntimeIdentityRef {
        val finalIdentity = attestVerifierIdentity(archiveIdentity)
        if (finalIdentity != initialIdentity) fail("DOWNLOAD_FAILED", "runtimeIdentity")
        return finalIdentity
    }

    private fun recoverArtifact(
        index: Int,
        source: VerifiedArchiveSource,
        artifact: EvidenceArchiveArtifactReport,
        report: EvidenceArchiveExecutionReport,
        root: EvidenceArchiveTrustedDirectory,
        partials: MutableList<OwnedRecoveryPartial>,
        receiptCapabilityChecks: MutableList<Instant>,
        operationStartedAt: Instant,
    ): EvidenceArchiveRecoveredArtifact {
        val receiptField = "artifacts[$index].receiptReference"
        val receiptDownload = download(artifact.receiptReference, MAX_RECEIPT_BYTES, receiptField)
        val receiptBytes = validateDownload(receiptDownload, artifact.receiptReference, receiptField)
        writePartial(root, receiptBytes, partials)
        val receipt = parseReceipt(receiptBytes)
        validateReceipt(index, source, artifact, report, receipt)
        receiptCapabilityChecks += receipt.capabilityCheckedAt

        val payloadField = "artifacts[$index].payload"
        val payloadDownload = download(artifact.payload, MAX_PAYLOAD_BYTES, payloadField)
        val payloadBytes = validateDownload(payloadDownload, artifact.payload, payloadField)
        writePartial(root, payloadBytes, partials)
        if (payloadBytes.size.toLong() != source.sizeBytes) fail("SIZE_MISMATCH", "$payloadField.sizeBytes")
        if (sha256(payloadBytes) != source.sha256) fail("DIGEST_MISMATCH", "$payloadField.sha256")

        val requiredRetainUntil = try {
            receipt.archivedAt.plus(parseRetention(receipt.retentionPolicy))
        } catch (_: DateTimeException) {
            fail("PROTECTION_INSUFFICIENT", "$receiptField.protection")
        } catch (_: ArithmeticException) {
            fail("PROTECTION_INSUFFICIENT", "$receiptField.protection")
        }
        val receiptProtection = protection(
            artifact.receiptReference,
            requiredRetainUntil,
            operationStartedAt,
            "$receiptField.protection",
        )
        val payloadProtection = protection(
            artifact.payload,
            requiredRetainUntil,
            operationStartedAt,
            "$payloadField.protection",
        )
        return EvidenceArchiveRecoveredArtifact(
            artifactId = source.artifactId,
            sourceRunId = source.sourceRunId,
            sourceCommit = source.sourceCommit,
            receiptArchivedAt = receipt.archivedAt,
            payload = EvidenceArchiveRecoveredObject(
                artifact.payload,
                sha256(payloadBytes),
                payloadBytes.size.toLong(),
                payloadProtection,
            ),
            receipt = EvidenceArchiveRecoveredObject(
                artifact.receiptReference,
                sha256(receiptBytes),
                receiptBytes.size.toLong(),
                receiptProtection,
            ),
        )
    }

    private fun download(
        reference: EvidenceArchiveExactObjectReference,
        limit: Long,
        field: String,
    ): ExactObjectDownload = try {
        gateway.downloadExact(reference.toStoredObjectRef(), limit, operationTimeout)
    } catch (_: ArchiveUnavailable) {
        fail("DOWNLOAD_FAILED", field)
    }

    private fun validateDownload(
        download: ExactObjectDownload,
        reference: EvidenceArchiveExactObjectReference,
        field: String,
    ): ByteArray {
        if (download.versionId != reference.versionId) fail("VERSION_MISMATCH", "$field.versionId")
        val bytes = download.bytes()
        if (download.sizeBytes != reference.sizeBytes || bytes.size.toLong() != reference.sizeBytes) {
            fail("SIZE_MISMATCH", "$field.sizeBytes")
        }
        if (sha256(bytes) != reference.sha256) fail("DIGEST_MISMATCH", "$field.sha256")
        return bytes
    }

    private fun validateReceipt(
        index: Int,
        source: VerifiedArchiveSource,
        artifact: EvidenceArchiveArtifactReport,
        report: EvidenceArchiveExecutionReport,
        receipt: ArchiveReceipt,
    ) {
        val prefix = "artifacts[$index].receipt"
        if (receipt.acceptanceId != report.workPackageId) mismatch("$prefix.acceptanceId")
        if (receipt.sourceArtifactId != source.artifactId) mismatch("$prefix.sourceArtifactId")
        if (receipt.sourceRunId != source.sourceRunId) mismatch("$prefix.sourceRunId")
        if (receipt.sourceCommit != source.sourceCommit) mismatch("$prefix.sourceCommit")
        if (receipt.sourceSha256 != source.sha256) mismatch("$prefix.sourceSha256")
        if (receipt.payload != artifact.payload.toStoredObjectRef()) mismatch("$prefix.payload")
        if (receipt.accessOwner != report.accessOwner) mismatch("$prefix.accessOwner")
        if (receipt.retentionPolicy != report.retentionPolicy) mismatch("$prefix.retentionPolicy")
        if (receipt.immutabilityControl != report.immutabilityControl) mismatch("$prefix.immutabilityControl")
        if (receipt.policyFingerprint != report.policyFingerprint) mismatch("$prefix.policyFingerprint")
        if (receipt.capabilityCheckedAt < report.startedAt || receipt.capabilityCheckedAt > receipt.archivedAt ||
            receipt.capabilityCheckedAt > checkNotNull(report.capabilityCheckedAt)
        ) mismatch("$prefix.capabilityCheckedAt")
        if (receipt.archivedAt < report.startedAt || receipt.archivedAt > report.completedAt) mismatch("$prefix.archivedAt")
        if (receipt.verifier != RECEIPT_VERIFIER) mismatch("$prefix.verifier")
        if (!receipt.longTerm) mismatch("$prefix.longTerm")
    }

    private fun protection(
        reference: EvidenceArchiveExactObjectReference,
        requiredRetainUntil: Instant,
        operationStartedAt: Instant,
        field: String,
    ): EvidenceArchiveProtectionFacts {
        val snapshot = try {
            gateway.headProtection(reference.toStoredObjectRef(), operationTimeout)
        } catch (_: ArchiveUnavailable) {
            fail("DOWNLOAD_FAILED", field)
        }
        val retainUntil = snapshot.retainUntil
        if (snapshot.actualMode != APPROVED_MODE || retainUntil == null || retainUntil < requiredRetainUntil ||
            !retainUntil.isAfter(operationStartedAt)
        ) {
            fail("PROTECTION_INSUFFICIENT", field)
        }
        return EvidenceArchiveProtectionFacts(APPROVED_MODE, retainUntil)
    }

    private fun validateExactReference(reference: EvidenceArchiveExactObjectReference, field: String) {
        if (reference.versionId.isBlank() || reference.versionId.equals("null", ignoreCase = true)) {
            fail("INVALID_EXACT_REFERENCE", "$field.versionId")
        }
        val valid = reference.provider == ArchiveProvider.S3_COMPATIBLE &&
            EvidenceArchiveS3ReferenceContract.validBucket(reference.bucket) &&
            EvidenceArchiveS3ReferenceContract.validKey(reference.key) &&
            EvidenceArchiveS3ReferenceContract.validVersion(reference.versionId) &&
            SHA256.matches(reference.sha256) && reference.sizeBytes > 0 &&
            EvidenceArchiveS3ReferenceContract.matchesLocator(reference.locator, reference.bucket, reference.key)
        if (!valid) mismatch("archiveReport.$field")
    }

    private fun parseReceipt(bytes: ByteArray): ArchiveReceipt {
        val canonical = try {
            JsonCanonicalizer(bytes).encodedUTF8
        } catch (_: IOException) {
            fail("RECEIPT_MISMATCH", "receipt.canonical")
        } catch (_: IllegalArgumentException) {
            fail("RECEIPT_MISMATCH", "receipt.canonical")
        }
        if (!canonical.contentEquals(bytes)) mismatch("receipt.canonical")
        val root = try {
            JSON.readTree(bytes)
        } catch (_: JacksonException) {
            fail("RECEIPT_MISMATCH", "receipt.json")
        }
        requireObject(root, RECEIPT_FIELDS, "receipt")
        return ArchiveReceipt(
            acceptanceId = text(root, "acceptanceId", "receipt"),
            sourceArtifactId = text(root, "sourceArtifactId", "receipt"),
            sourceRunId = text(root, "sourceRunId", "receipt"),
            sourceCommit = text(root, "sourceCommit", "receipt"),
            sourceSha256 = text(root, "sourceSha256", "receipt"),
            payload = parseStoredObject(requireField(root, "payload", "receipt")),
            accessOwner = text(root, "accessOwner", "receipt"),
            retentionPolicy = text(root, "retentionPolicy", "receipt"),
            immutabilityControl = text(root, "immutabilityControl", "receipt"),
            policyFingerprint = text(root, "policyFingerprint", "receipt"),
            capabilityCheckedAt = instant(root, "capabilityCheckedAt", "receipt"),
            archivedAt = instant(root, "archivedAt", "receipt"),
            verifier = text(root, "verifier", "receipt"),
            longTerm = boolean(root, "longTerm", "receipt"),
        )
    }

    private fun parseStoredObject(node: JsonNode): StoredObjectRef {
        requireObject(node, STORED_OBJECT_FIELDS, "receipt.payload")
        val provider = enumValue<ArchiveProvider>(text(node, "provider", "receipt.payload"), "receipt.payload.provider")
        val bucket = text(node, "bucket", "receipt.payload")
        val versionId = text(node, "versionId", "receipt.payload")
        return StoredObjectRef(
            provider,
            text(node, "locator", "receipt.payload"),
            bucket,
            rawText(node, "key", "receipt.payload"),
            versionId,
            text(node, "sha256", "receipt.payload"),
            positiveLong(node, "sizeBytes", "receipt.payload"),
        )
    }

    private fun trustRecoveryRoot(path: Path): EvidenceArchiveTrustedDirectory {
        if (!path.isAbsolute || path.normalize() != path || Files.isSymbolicLink(path)) invalidRoot()
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path)
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) invalidRoot()
            Files.list(path).use { if (it.findAny().isPresent) invalidRoot() }
            return EvidenceArchiveTrustedDirectory.require(
                path,
                realPathResolver::resolve,
                fileKeyReader::read,
                directoryAccessReader,
            )
        } catch (failure: EvidenceArchiveVerificationFailure) {
            throw failure
        } catch (_: IOException) {
            invalidRoot()
        } catch (_: SecurityException) {
            invalidRoot()
        } catch (_: UnsupportedOperationException) {
            invalidRoot()
        }
    }

    private fun revalidateRoot(root: EvidenceArchiveTrustedDirectory) {
        try {
            val current = EvidenceArchiveTrustedDirectory.require(
                root.path,
                realPathResolver::resolve,
                fileKeyReader::read,
                directoryAccessReader,
            )
            if (current != root) invalidRoot()
        } catch (failure: EvidenceArchiveVerificationFailure) {
            throw failure
        } catch (_: IOException) {
            invalidRoot()
        } catch (_: SecurityException) {
            invalidRoot()
        }
    }

    private fun writePartial(
        root: EvidenceArchiveTrustedDirectory,
        bytes: ByteArray,
        partials: MutableList<OwnedRecoveryPartial>,
    ) {
        revalidateRoot(root)
        val path = root.path.resolve(".recovery-${UUID.randomUUID()}.partial")
        try {
            FileChannel.open(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (!attributes.isRegularFile || Files.isSymbolicLink(path)) throw IOException()
                val identity = OwnedRecoveryPartial(
                    path,
                    EvidenceArchiveLocalFileIdentity.require(
                        path,
                        attributes,
                        fileKeyReader.read(path, attributes),
                        directoryAccessReader.proof(path),
                    ),
                )
                partials += identity
                writeAll(channel, bytes)
                channel.force(true)
                validatePartial(channel, bytes)
                identity.identity = currentLocalIdentity(path)
            }
        } catch (failure: EvidenceArchiveVerificationFailure) {
            throw failure
        } catch (_: IOException) {
            fail("UNEXPECTED_FAILURE", "recoveryRoot")
        } catch (_: SecurityException) {
            fail("UNEXPECTED_FAILURE", "recoveryRoot")
        } catch (_: UnsupportedOperationException) {
            fail("UNEXPECTED_FAILURE", "recoveryRoot")
        }
    }

    private fun writeAll(channel: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) <= 0) throw IOException()
        }
    }

    private fun validatePartial(channel: FileChannel, bytes: ByteArray) {
        if (channel.size() != bytes.size.toLong()) throw IOException()
        channel.position(0)
        val digest = MessageDigest.getInstance(SHA_256)
        val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
        var size = 0L
        while (true) {
            val count = channel.read(buffer)
            if (count < 0) break
            if (count == 0) throw IOException()
            size += count
            buffer.flip()
            digest.update(buffer)
            buffer.clear()
        }
        if (size != bytes.size.toLong() || hex(digest.digest()) != sha256(bytes)) throw IOException()
    }

    private fun cleanupPartials(root: EvidenceArchiveTrustedDirectory?, partials: List<OwnedRecoveryPartial>): Boolean {
        if (root == null) return false
        var failed = false
        for (partial in partials.asReversed()) {
            try {
                revalidateRoot(root)
                val attributes = Files.readAttributes(partial.path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                val currentIdentity = EvidenceArchiveLocalFileIdentity.require(
                    partial.path,
                    attributes,
                    fileKeyReader.read(partial.path, attributes),
                    directoryAccessReader.proof(partial.path),
                )
                val owned = attributes.isRegularFile && !Files.isSymbolicLink(partial.path) &&
                    currentIdentity == partial.identity
                if (!owned) throw IOException()
                partialCleanup.cleanup(partial.path)
            } catch (_: Exception) {
                failed = true
            }
        }
        try {
            revalidateRoot(root)
            Files.list(root.path).use { if (it.findAny().isPresent) failed = true }
        } catch (_: Exception) {
            failed = true
        }
        return failed
    }

    private fun cleanupAfterError(root: EvidenceArchiveTrustedDirectory?, partials: List<OwnedRecoveryPartial>, error: Error) {
        cleanupPartialsAfterError(root, partials, error)
    }

    private fun cleanupPartialsAfterError(
        root: EvidenceArchiveTrustedDirectory?,
        partials: List<OwnedRecoveryPartial>,
        original: Error,
    ) {
        if (root == null) return
        for (partial in partials.asReversed()) {
            try {
                revalidateRoot(root)
                val attributes = Files.readAttributes(partial.path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                val currentIdentity = EvidenceArchiveLocalFileIdentity.require(
                    partial.path,
                    attributes,
                    fileKeyReader.read(partial.path, attributes),
                    directoryAccessReader.proof(partial.path),
                )
                val owned = attributes.isRegularFile && !Files.isSymbolicLink(partial.path) &&
                    currentIdentity == partial.identity
                if (!owned) throw IOException()
                partialCleanup.cleanup(partial.path)
            } catch (cleanup: Throwable) {
                if (cleanup !== original) original.addSuppressed(cleanup)
            }
        }
        try {
            revalidateRoot(root)
            Files.list(root.path).use { if (it.findAny().isPresent) throw IOException("RECOVERY_CLEANUP_FAILED") }
        } catch (cleanup: Throwable) {
            if (cleanup !== original) original.addSuppressed(cleanup)
        }
    }

    private fun beginOutput(output: Path, recoveryRoot: Path, startedAt: Instant): RecoveryOutputStaging {
        val outputFileName = output.fileName?.toString()
        if (!output.isAbsolute || output.normalize() != output || outputFileName == null ||
            !EvidenceArchivePortableFileName.isSafe(outputFileName, MAX_REPORT_FILE_NAME_UTF8_BYTES) ||
            !recoveryRoot.isAbsolute || recoveryRoot.normalize() != recoveryRoot ||
            output == recoveryRoot || output.startsWith(recoveryRoot) || recoveryRoot.startsWith(output)
        ) fail("REPORT_OUTPUT_INVALID", "output")
        val trustedRecoveryRoot = trustRecoveryRoot(recoveryRoot)
        val parent = output.parent ?: fail("REPORT_OUTPUT_INVALID", "output")
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) fail("REPORT_TARGET_EXISTS", "output")
        try {
            if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                fail("REPORT_OUTPUT_INVALID", "output")
            }
            val directory = try {
                EvidenceArchiveTrustedDirectory.require(
                    parent,
                    realPathResolver::resolve,
                    fileKeyReader::read,
                    directoryAccessReader,
                )
            } catch (_: IOException) {
                fail("REPORT_OUTPUT_INVALID", "output")
            } catch (_: SecurityException) {
                fail("REPORT_OUTPUT_INVALID", "output")
            } catch (_: UnsupportedOperationException) {
                fail("REPORT_OUTPUT_INVALID", "output")
            }
            val directoryRealPath = directory.realPath
            val futureTarget = directoryRealPath.resolve(output.fileName).normalize()
            if (directoryRealPath.startsWith(trustedRecoveryRoot.realPath) ||
                futureTarget.startsWith(trustedRecoveryRoot.realPath)
            ) fail("REPORT_OUTPUT_INVALID", "output")
            val partialPath = parent.resolve(".${output.fileName}-${UUID.randomUUID()}.partial")
            val channel = FileChannel.open(
                partialPath,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
            var expectedIdentity: OwnedOutputPartial? = null
            try {
                val partialAttributes = Files.readAttributes(partialPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (!partialAttributes.isRegularFile || Files.isSymbolicLink(partialPath)) throw IOException()
                val partialIdentity = EvidenceArchiveLocalFileIdentity.require(
                    partialPath,
                    partialAttributes,
                    fileKeyReader.read(partialPath, partialAttributes),
                    directoryAccessReader.proof(partialPath),
                )
                expectedIdentity = OwnedOutputPartial(partialPath, partialIdentity)
                val provisionalBytes = provisionalReportBytes(startedAt)
                val staging = RecoveryOutputStaging(
                    output,
                    directory,
                    partialPath,
                    partialIdentity,
                    trustedRecoveryRoot,
                    channel,
                )
                staging.rewriteAndForce(provisionalBytes)
                return staging
            } catch (error: Error) {
                cleanupNewOutputPartial(channel, expectedIdentity, error)
                throw error
            } catch (_: Exception) {
                val failure = verificationFailure("REPORT_WRITE_FAILED", "output")
                if (cleanupNewOutputPartial(channel, expectedIdentity, failure)) {
                    throw verificationFailure("REPORT_CLEANUP_FAILED", "output").also { it.addSuppressed(failure) }
                }
                throw failure
            }
        } catch (failure: EvidenceArchiveVerificationFailure) {
            throw failure
        } catch (_: FileAlreadyExistsException) {
            fail("REPORT_TARGET_EXISTS", "output")
        } catch (_: IOException) {
            fail("REPORT_WRITE_FAILED", "output")
        } catch (_: SecurityException) {
            fail("REPORT_WRITE_FAILED", "output")
        } catch (_: UnsupportedOperationException) {
            fail("REPORT_WRITE_FAILED", "output")
        }
    }

    private fun cleanupNewOutputPartial(
        channel: FileChannel,
        expected: OwnedOutputPartial?,
        original: Throwable,
    ): Boolean {
        var failed = false
        try {
            channel.close()
        } catch (error: Error) {
            if (original is Error) {
                original.addSuppressed(error)
            } else {
                error.addSuppressed(original)
                throw error
            }
            failed = true
        } catch (_: Exception) {
            failed = true
        }
        if (expected == null) {
            failed = true
        } else {
            try {
                val attributes = Files.readAttributes(
                    expected.path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                val currentIdentity = EvidenceArchiveLocalFileIdentity.require(
                    expected.path,
                    attributes,
                    fileKeyReader.read(expected.path, attributes),
                    directoryAccessReader.proof(expected.path),
                )
                val owned = attributes.isRegularFile && !Files.isSymbolicLink(expected.path) &&
                    currentIdentity == expected.identity
                if (!owned) {
                    failed = true
                } else {
                    Files.delete(expected.path)
                }
            } catch (error: Error) {
                if (original is Error) {
                    original.addSuppressed(error)
                } else {
                    error.addSuppressed(original)
                    throw error
                }
                failed = true
            } catch (_: Exception) {
                failed = true
            }
        }
        if (failed) original.addSuppressed(verificationFailure("REPORT_CLEANUP_FAILED", "output"))
        return failed
    }

    private fun provisionalReportBytes(startedAt: Instant): ByteArray {
        val root = JSON.createObjectNode().apply {
            put("schemaVersion", REPORT_SCHEMA_VERSION)
            put("workPackageId", WORK_PACKAGE_ID)
            put("startedAt", startedAt.toString())
            put("status", "IN_PROGRESS")
        }
        return try {
            JsonCanonicalizer(JSON.writeValueAsBytes(root)).encodedUTF8
        } catch (_: IOException) {
            fail("REPORT_WRITE_FAILED", "output")
        }
    }

    private fun readInput(path: Path, maxBytes: Int, field: String): ByteArray = try {
        stableFileReader.read(path, maxBytes)
    } catch (failure: EvidenceArchiveVerificationFailure) {
        throw failure
    } catch (_: IOException) {
        mismatch(field)
    } catch (_: SecurityException) {
        mismatch(field)
    } catch (_: UnsupportedOperationException) {
        mismatch(field)
    }

    private fun currentLocalIdentity(path: Path): EvidenceArchiveLocalFileIdentity {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || Files.isSymbolicLink(path)) throw IOException()
        return EvidenceArchiveLocalFileIdentity.require(
            path,
            attributes,
            fileKeyReader.read(path, attributes),
            directoryAccessReader.proof(path),
        )
    }

    private fun revalidateOutputDirectory(directory: EvidenceArchiveTrustedDirectory) {
        val current = EvidenceArchiveTrustedDirectory.require(
            directory.path,
            realPathResolver::resolve,
            fileKeyReader::read,
            directoryAccessReader,
        )
        if (current != directory) throw IOException()
    }

    private inner class RecoveryOutputStaging(
        private val output: Path,
        private val directory: EvidenceArchiveTrustedDirectory,
        private val partial: Path,
        private var partialIdentity: EvidenceArchiveLocalFileIdentity,
        private val recoveryRoot: EvidenceArchiveTrustedDirectory,
        private val channel: FileChannel,
    ) {
        private var closed = false
        private var partialExists = true

        fun reconcileFinalCleanup(report: EvidenceArchiveRecoveryReport): EvidenceArchiveRecoveryReport {
            val cleanupFailed = try {
                recoveryRootGuard.require(recoveryRoot, RecoveryRootExpectation.EMPTY)
                false
            } catch (_: Exception) {
                true
            }
            return if (cleanupFailed) cleanupFailureReport(report) else report
        }

        fun rewriteAndForce(bytes: ByteArray) {
            requireOwnedPartial()
            channel.truncate(0)
            channel.position(0)
            writeAll(channel, bytes)
            channel.force(true)
            validatePartial(channel, bytes)
            partialIdentity = currentLocalIdentity(partial)
        }

        fun publish(report: EvidenceArchiveRecoveryReport): EvidenceArchiveRecoveryReport {
            var finalReport = report
            var bytes = canonicalReportBytes(finalReport)
            var marker = completionMarker(bytes)
            requireMarkerAbsent(marker)
            rewriteAndForce(bytes)
            revalidateOutputDirectory(directory)
            try {
                recoveryRootGuard.require(recoveryRoot, RecoveryRootExpectation.EMPTY)
            } catch (failure: RecoveryRootGuardFailure) {
                if (failure.reason != RecoveryRootGuardFailureReason.NOT_EMPTY) throw failure
                finalReport = cleanupFailureReport(finalReport)
                bytes = canonicalReportBytes(finalReport)
                marker = completionMarker(bytes)
                requireMarkerAbsent(marker)
                rewriteAndForce(bytes)
                revalidateOutputDirectory(directory)
                recoveryRootGuard.require(recoveryRoot, RecoveryRootExpectation.IDENTITY_ONLY)
            }
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) fail("REPORT_TARGET_EXISTS", "output")
            channel.close()
            closed = true
            var phase = PublishPhase.LINK
            var published = false
            try {
                reportPublishOperations.createLink(output, partial)
                published = true
                phase = PublishPhase.VALIDATE
                revalidateOutputDirectory(directory)
                revalidateRoot(recoveryRoot)
                requireOwnedPartial()
                requirePublishedOwnership()
                reportPublishOperations.validatePublished(output, partial, directory, bytes)
                phase = PublishPhase.FORCE_DIRECTORY
                reportPublishOperations.forceDirectory(directory.path)
                phase = PublishPhase.CLEANUP_PARTIAL
                cleanupOwnedPartial()
                recoveryRootGuard.require(
                    recoveryRoot,
                    if (finalReport.cleanupStatus == OperationStatus.PASS) {
                        RecoveryRootExpectation.EMPTY
                    } else {
                        RecoveryRootExpectation.NON_EMPTY
                    },
                )
                revalidateOutputDirectory(directory)
                if (finalReport.status == OperationStatus.PASS && finalReport.cleanupStatus == OperationStatus.PASS) {
                    phase = PublishPhase.COMPLETION_MARKER
                    // Task 6/offline consumers must require this derived empty marker before trusting the PASS report.
                    // Marker creation is deliberately the final operation: nothing fallible follows it.
                    reportPublishOperations.createCompletionMarker(marker)
                }
            } catch (_: FileAlreadyExistsException) {
                if (published) fail("REPORT_WRITE_FAILED", "output") else fail("REPORT_TARGET_EXISTS", "output")
            } catch (error: Error) {
                if (published) cleanupPartialAfterPublish(error)
                throw error
            } catch (failure: Exception) {
                if (published) cleanupPartialAfterPublish(failure)
                val code = if (phase == PublishPhase.CLEANUP_PARTIAL) {
                    "REPORT_CLEANUP_FAILED"
                } else {
                    "REPORT_WRITE_FAILED"
                }
                throw verificationFailure(code, "output")
            }
            return finalReport
        }

        private fun completionMarker(bytes: ByteArray): Path {
            val name = "${output.fileName}.complete.${sha256(bytes)}"
            if (!EvidenceArchivePortableFileName.isSafe(name)) throw IOException()
            val marker = directory.path.resolve(name)
            if (marker.parent != directory.path || marker.fileName.toString() != name) throw IOException()
            return marker
        }

        private fun requireMarkerAbsent(marker: Path) {
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) fail("REPORT_TARGET_EXISTS", "output")
        }

        private fun cleanupFailureReport(report: EvidenceArchiveRecoveryReport): EvidenceArchiveRecoveryReport = report.copy(
            status = OperationStatus.FAIL,
            errorCode = report.errorCode ?: "RECOVERY_CLEANUP_FAILED",
            cleanupStatus = OperationStatus.FAIL,
            cleanupErrorCode = "RECOVERY_CLEANUP_FAILED",
        )

        private fun cleanupPartialAfterPublish(original: Throwable) {
            if (!partialExists) return
            try {
                cleanupOwnedPartial()
            } catch (cleanup: Throwable) {
                if (cleanup !== original) original.addSuppressed(cleanup)
            }
        }

        private fun cleanupOwnedPartial() {
            requireOwnedPartial()
            reportPublishOperations.cleanupPartial(partial)
            partialExists = false
        }

        private fun requireOwnedPartial() {
            val attributes = Files.readAttributes(partial, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val currentIdentity = EvidenceArchiveLocalFileIdentity.require(
                partial,
                attributes,
                fileKeyReader.read(partial, attributes),
                directoryAccessReader.proof(partial),
            )
            if (!attributes.isRegularFile || Files.isSymbolicLink(partial) ||
                currentIdentity != partialIdentity
            ) throw IOException()
        }

        private fun requirePublishedOwnership() {
            val attributes = Files.readAttributes(output, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val currentIdentity = EvidenceArchiveLocalFileIdentity.require(
                output,
                attributes,
                fileKeyReader.read(output, attributes),
                directoryAccessReader.proof(output),
            )
            if (!attributes.isRegularFile || Files.isSymbolicLink(output) ||
                currentIdentity != partialIdentity || !Files.isSameFile(output, partial)
            ) throw IOException()
        }

        fun cleanupOrSuppress(original: Throwable) {
            try {
                if (!closed) {
                    channel.close()
                    closed = true
                }
            } catch (cleanup: Throwable) {
                original.addSuppressed(cleanup)
            }
            if (partialExists) {
                try {
                    cleanupOwnedPartial()
                } catch (cleanup: Throwable) {
                    original.addSuppressed(cleanup)
                }
            }
        }

        fun cleanupAfterError(error: Error) = cleanupOrSuppress(error)
    }

    private fun canonicalReportBytes(report: EvidenceArchiveRecoveryReport): ByteArray {
        val root = JSON.createObjectNode().apply {
            put("schemaVersion", report.schemaVersion)
            put("workPackageId", report.workPackageId)
            report.executionId?.let { put("executionId", it) } ?: putNull("executionId")
            report.descriptorSha256?.let { put("descriptorSha256", it) } ?: putNull("descriptorSha256")
            report.pilotManifestSha256?.let { put("pilotManifestSha256", it) } ?: putNull("pilotManifestSha256")
            put("startedAt", report.startedAt.toString())
            put("completedAt", report.completedAt.toString())
            set<ObjectNode>("archiveIdentity", identityNode(report.archiveIdentity))
            set<ObjectNode>("verifierIdentity", identityNode(report.verifierIdentity))
            putArray("artifacts").apply {
                report.artifacts.forEach { artifact ->
                    addObject().apply {
                        put("artifactId", artifact.artifactId)
                        put("sourceRunId", artifact.sourceRunId)
                        put("sourceCommit", artifact.sourceCommit)
                        put("receiptArchivedAt", artifact.receiptArchivedAt.toString())
                        set<ObjectNode>("payload", recoveredObjectNode(artifact.payload))
                        set<ObjectNode>("receipt", recoveredObjectNode(artifact.receipt))
                    }
                }
            }
            put("status", report.status.name)
            report.errorCode?.let { put("errorCode", it) } ?: putNull("errorCode")
            put("cleanupStatus", report.cleanupStatus.name)
            report.cleanupErrorCode?.let { put("cleanupErrorCode", it) } ?: putNull("cleanupErrorCode")
        }
        return try {
            JsonCanonicalizer(JSON.writeValueAsBytes(root)).encodedUTF8
        } catch (_: IOException) {
            fail("UNEXPECTED_FAILURE", "output")
        }
    }

    private fun identityNode(identity: RuntimeIdentityRef?): ObjectNode = JSON.createObjectNode().apply {
        if (identity == null) {
            putNull("provider")
            putNull("principalFingerprint")
        } else {
            put("provider", identity.provider.name)
            put("principalFingerprint", identity.principalFingerprint)
        }
    }

    private fun recoveredObjectNode(recovered: EvidenceArchiveRecoveredObject): ObjectNode =
        JSON.createObjectNode().apply {
            set<ObjectNode>("reference", exactReferenceNode(recovered.reference))
            put("recoveredSha256", recovered.recoveredSha256)
            put("recoveredSizeBytes", recovered.recoveredSizeBytes)
            putObject("protection").apply {
                put("actualMode", recovered.protection.actualMode)
                put("retainUntil", recovered.protection.retainUntil.toString())
            }
        }

    private fun exactReferenceNode(reference: EvidenceArchiveExactObjectReference): ObjectNode =
        JSON.createObjectNode().apply {
            put("provider", reference.provider.name)
            put("locator", reference.locator)
            put("bucket", reference.bucket)
            put("key", reference.key)
            put("versionId", reference.versionId)
            put("sha256", reference.sha256)
            put("sizeBytes", reference.sizeBytes)
        }

    private fun parseExactReference(node: JsonNode, field: String): EvidenceArchiveExactObjectReference {
        requireObject(node, EXACT_REFERENCE_FIELDS, field)
        return EvidenceArchiveExactObjectReference(
            enumValue(text(node, "provider", field), "$field.provider"),
            text(node, "locator", field),
            text(node, "bucket", field),
            rawText(node, "key", field),
            rawText(node, "versionId", field),
            text(node, "sha256", field),
            positiveLong(node, "sizeBytes", field),
        )
    }

    private fun parseNullableIdentity(node: JsonNode): RuntimeIdentityRef? {
        if (node.isNull) return null
        requireObject(node, IDENTITY_FIELDS, "archiveReport.runtimeIdentity")
        return RuntimeIdentityRef(
            enumValue(text(node, "provider", "archiveReport.runtimeIdentity"), "archiveReport.runtimeIdentity.provider"),
            text(node, "principalFingerprint", "archiveReport.runtimeIdentity"),
        )
    }

    private fun readJson(bytes: ByteArray, field: String): JsonNode = try {
        JSON.readTree(bytes)
    } catch (_: JacksonException) {
        mismatch(field)
    }

    private fun canonicalInput(bytes: ByteArray, field: String): ByteArray = try {
        JsonCanonicalizer(bytes).encodedUTF8
    } catch (_: IOException) {
        mismatch(field)
    } catch (_: IllegalArgumentException) {
        mismatch(field)
    }

    private fun nullableText(node: JsonNode, name: String, prefix: String): String? {
        val value = requireField(node, name, prefix)
        if (value.isNull) return null
        if (!value.isTextual || value.textValue().isBlank()) mismatch("$prefix.$name")
        return value.textValue()
    }

    private fun parseInstant(value: String): Instant = try {
        Instant.parse(value).also { if (it.toString() != value) mismatch("archiveReport.capabilityCheckedAt") }
    } catch (_: DateTimeParseException) {
        mismatch("archiveReport.capabilityCheckedAt")
    } catch (_: DateTimeException) {
        mismatch("archiveReport.capabilityCheckedAt")
    } catch (_: ArithmeticException) {
        mismatch("archiveReport.capabilityCheckedAt")
    }

    private fun parseRetention(value: String): Duration = try {
        Duration.parse(value)
    } catch (_: DateTimeParseException) {
        mismatch("archiveReport.retentionPolicy")
    } catch (_: DateTimeException) {
        mismatch("archiveReport.retentionPolicy")
    } catch (_: ArithmeticException) {
        mismatch("archiveReport.retentionPolicy")
    }

    private fun requireObject(node: JsonNode, fields: Set<String>, field: String) {
        if (!node.isObject || node.fieldNames().asSequence().toSet() != fields) mismatch(field)
    }

    private fun requireField(node: JsonNode, name: String, prefix: String): JsonNode =
        node.get(name) ?: mismatch("$prefix.$name")

    private fun text(node: JsonNode, name: String, prefix: String): String {
        val value = requireField(node, name, prefix)
        if (!value.isTextual || value.textValue().isBlank()) mismatch("$prefix.$name")
        return value.textValue()
    }

    private fun rawText(node: JsonNode, name: String, prefix: String): String {
        val value = requireField(node, name, prefix)
        if (!value.isTextual) mismatch("$prefix.$name")
        return value.textValue()
    }

    private fun instant(node: JsonNode, name: String, prefix: String): Instant = try {
        val value = text(node, name, prefix)
        Instant.parse(value).also { if (it.toString() != value) mismatch("$prefix.$name") }
    } catch (_: DateTimeParseException) {
        mismatch("$prefix.$name")
    } catch (_: DateTimeException) {
        mismatch("$prefix.$name")
    } catch (_: ArithmeticException) {
        mismatch("$prefix.$name")
    }

    private fun boolean(node: JsonNode, name: String, prefix: String): Boolean {
        val value = requireField(node, name, prefix)
        if (!value.isBoolean) mismatch("$prefix.$name")
        return value.booleanValue()
    }

    private fun positiveLong(node: JsonNode, name: String, prefix: String): Long {
        val value = requireField(node, name, prefix)
        if (!value.isIntegralNumber || !value.canConvertToLong() || value.longValue() <= 0) mismatch("$prefix.$name")
        return value.longValue()
    }

    private fun exactSchemaVersion(node: JsonNode, name: String, prefix: String): Int {
        val value = requireField(node, name, prefix)
        if (!value.isIntegralNumber || !value.canConvertToInt() || value.intValue() != REPORT_SCHEMA_VERSION) {
            mismatch("$prefix.$name")
        }
        return REPORT_SCHEMA_VERSION
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T = try {
        enumValueOf<T>(value)
    } catch (_: IllegalArgumentException) {
        mismatch(field)
    }

    private fun mismatch(field: String): Nothing = fail("RECEIPT_MISMATCH", field)

    private fun invalidRoot(): Nothing = fail("RECOVERY_ROOT_INVALID", "recoveryRoot")

    private fun fail(code: String, field: String): Nothing = throw verificationFailure(code, field)

    private fun verificationFailure(code: String, field: String) = EvidenceArchiveVerificationFailure("$code:$field")

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance(SHA_256).digest(bytes))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun EvidenceArchiveExactObjectReference.toStoredObjectRef() = StoredObjectRef(
        provider,
        locator,
        bucket,
        key,
        versionId,
        sha256,
        sizeBytes,
    )

    private data class OwnedRecoveryPartial(
        val path: Path,
        var identity: EvidenceArchiveLocalFileIdentity,
    )

    private data class OwnedOutputPartial(
        val path: Path,
        val identity: EvidenceArchiveLocalFileIdentity,
    )

    private enum class PublishPhase {
        LINK,
        VALIDATE,
        FORCE_DIRECTORY,
        CLEANUP_PARTIAL,
        COMPLETION_MARKER,
    }

    private companion object {
        const val REPORT_SCHEMA_VERSION = 1
        const val MAX_REPORT_FILE_NAME_UTF8_BYTES = 181
        const val REQUIRED_ARTIFACT_COUNT = 2
        const val MAX_RECEIPT_BYTES = 1L * 1024 * 1024
        const val MAX_PAYLOAD_BYTES = 64L * 1024 * 1024
        const val MAX_INPUT_BYTES = 1 * 1024 * 1024
        const val WORK_PACKAGE_ID = "V0-2-EVIDENCE-ARCHIVE-001"
        const val APPROVED_MODE = "COMPLIANCE"
        const val RECEIPT_VERIFIER = "SHA-256"
        const val SHA_256 = "SHA-256"
        val SHA256 = EVIDENCE_SHA256_PATTERN
        val COMMIT = EVIDENCE_COMMIT_PATTERN
        val DECIMAL_ID = EVIDENCE_DECIMAL_ID_PATTERN
        val EXECUTION_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val FAILURE_CODES = setOf(
            "SAME_RUNTIME_IDENTITY", "INVALID_EXACT_REFERENCE", "VERSION_MISMATCH", "DIGEST_MISMATCH",
            "SIZE_MISMATCH", "RECEIPT_MISMATCH", "PROTECTION_INSUFFICIENT", "RECOVERY_ROOT_INVALID",
            "RECOVERY_CLEANUP_FAILED", "DOWNLOAD_FAILED", "UNEXPECTED_FAILURE",
        )
        val RECEIPT_FIELDS = setOf(
            "acceptanceId", "sourceArtifactId", "sourceRunId", "sourceCommit", "sourceSha256", "payload",
            "accessOwner", "retentionPolicy", "immutabilityControl", "policyFingerprint", "capabilityCheckedAt",
            "archivedAt", "verifier", "longTerm",
        )
        val STORED_OBJECT_FIELDS = setOf(
            "provider", "locator", "bucket", "key", "versionId", "sha256", "sizeBytes",
        )
        val ARCHIVE_REPORT_FIELDS = setOf(
            "schemaVersion", "workPackageId", "executionId", "descriptorSha256", "pilotManifestSha256", "startedAt",
            "completedAt", "policyFingerprint", "capabilityCheckedAt", "runtimeIdentity", "artifacts", "accessOwner",
            "retentionPolicy", "immutabilityControl", "status", "errorCode",
        )
        val ARCHIVE_ARTIFACT_FIELDS = setOf(
            "artifactId", "sourceRunId", "sourceCommit", "payload", "receiptReference",
        )
        val EXACT_REFERENCE_FIELDS = setOf(
            "provider", "locator", "bucket", "key", "versionId", "sha256", "sizeBytes",
        )
        val IDENTITY_FIELDS = setOf("provider", "principalFingerprint")
        val JSON: JsonMapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()
    }
}
