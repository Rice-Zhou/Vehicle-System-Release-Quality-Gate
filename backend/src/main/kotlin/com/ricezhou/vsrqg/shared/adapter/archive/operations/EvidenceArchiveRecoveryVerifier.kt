package com.ricezhou.vsrqg.shared.adapter.archive.operations

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.StreamReadFeature
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
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
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
    val payload: EvidenceArchiveRecoveredObject,
    val receipt: EvidenceArchiveRecoveredObject,
)

data class EvidenceArchiveRecoveryReport(
    val schemaVersion: Int,
    val workPackageId: String,
    val executionId: String,
    val descriptorSha256: String,
    val pilotManifestSha256: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val archiveIdentity: RuntimeIdentityRef?,
    val verifierIdentity: RuntimeIdentityRef?,
    val artifacts: List<EvidenceArchiveRecoveredArtifact>,
    val status: OperationStatus,
    val errorCode: String?,
)

internal fun interface RecoveryFileKeyReader {
    fun read(path: Path, attributes: BasicFileAttributes): Any?
}

internal fun interface RecoveryPartialCleanup {
    fun cleanup(path: Path)
}

class EvidenceArchiveRecoveryVerifier internal constructor(
    private val gateway: S3Gateway,
    private val timeProvider: TimeProvider,
    private val operationTimeout: Duration,
    private val fileKeyReader: RecoveryFileKeyReader = RecoveryFileKeyReader { _, attributes -> attributes.fileKey() },
    private val partialCleanup: RecoveryPartialCleanup = RecoveryPartialCleanup(Files::delete),
) {
    fun parseWorkPackage(descriptorBytes: ByteArray): VerifiedEvidenceArchiveWorkPackage {
        if (descriptorBytes.isEmpty() || descriptorBytes.size > MAX_INPUT_BYTES) mismatch("workPackage")
        val root = readJson(descriptorBytes, "workPackage")
        requireObject(root, WORK_PACKAGE_FIELDS, "workPackage")
        if (positiveLong(root, "schemaVersion", "workPackage") != 1L) mismatch("workPackage.schemaVersion")
        val workPackageId = text(root, "workPackageId", "workPackage")
        if (workPackageId != WORK_PACKAGE_ID) mismatch("workPackage.workPackageId")
        listOf("subjectCommit", "pairedSubjectCommit").forEach { name ->
            if (!COMMIT.matches(text(root, name, "workPackage"))) mismatch("workPackage.$name")
        }
        val pilot = requireField(root, "pilotManifest", "workPackage")
        requireObject(pilot, PILOT_FIELDS, "workPackage.pilotManifest")
        safeFileName(text(pilot, "fileName", "workPackage.pilotManifest"), "workPackage.pilotManifest.fileName")
        val pilotDigest = text(pilot, "sha256", "workPackage.pilotManifest")
        if (!SHA256.matches(pilotDigest) || text(pilot, "classification", "workPackage.pilotManifest") != PILOT_CLASSIFICATION ||
            boolean(pilot, "conditionBClosed", "workPackage.pilotManifest")
        ) mismatch("workPackage.pilotManifest")
        val artifactsNode = requireField(root, "artifacts", "workPackage")
        if (!artifactsNode.isArray || artifactsNode.size() != REQUIRED_ARTIFACT_COUNT) mismatch("workPackage.artifacts")
        val artifacts = artifactsNode.mapIndexed { index, node ->
            val prefix = "workPackage.artifacts[$index]"
            requireObject(node, WORK_PACKAGE_ARTIFACT_FIELDS, prefix)
            text(node, "artifactName", prefix)
            val artifactId = text(node, "artifactId", prefix)
            val sourceRunId = text(node, "sourceRunId", prefix)
            val sourceCommit = text(node, "sourceCommit", prefix)
            val fileName = text(node, "fileName", prefix)
            val digest = text(node, "sha256", prefix)
            if (!DECIMAL_ID.matches(artifactId) || !DECIMAL_ID.matches(sourceRunId) ||
                !COMMIT.matches(sourceCommit) || !SHA256.matches(digest)
            ) mismatch(prefix)
            safeFileName(fileName, "$prefix.fileName")
            VerifiedArchiveSource(
                artifactId,
                sourceRunId,
                sourceCommit,
                Path.of(fileName),
                positiveLong(node, "sizeBytes", prefix),
                digest,
            )
        }
        if (artifacts.map { it.artifactId }.toSet().size != artifacts.size ||
            artifacts.map { it.path.fileName.toString().lowercase() }.toSet().size != artifacts.size
        ) mismatch("workPackage.artifacts")
        return VerifiedEvidenceArchiveWorkPackage(
            workPackageId,
            sha256(descriptorBytes),
            pilotDigest,
            Collections.unmodifiableList(artifacts),
        )
    }

    fun parseArchiveReport(reportBytes: ByteArray): EvidenceArchiveExecutionReport {
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
        return EvidenceArchiveExecutionReport(
            schemaVersion = positiveLong(root, "schemaVersion", "archiveReport").toInt(),
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

    fun validateReportOutput(output: Path, recoveryRoot: Path? = null) {
        if (!output.isAbsolute || output.normalize() != output || Files.isSymbolicLink(output) ||
            Files.exists(output, LinkOption.NOFOLLOW_LINKS) ||
            recoveryRoot?.let { root ->
                !root.isAbsolute || root.normalize() != root || output.startsWith(root)
            } == true
        ) fail("UNEXPECTED_FAILURE", "output")
        val parent = output.parent ?: fail("UNEXPECTED_FAILURE", "output")
        try {
            if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) ||
                parent.toRealPath(LinkOption.NOFOLLOW_LINKS) != parent.toRealPath()
            ) fail("UNEXPECTED_FAILURE", "output")
        } catch (failure: EvidenceArchiveVerificationFailure) {
            throw failure
        } catch (_: IOException) {
            fail("UNEXPECTED_FAILURE", "output")
        } catch (_: SecurityException) {
            fail("UNEXPECTED_FAILURE", "output")
        }
    }

    fun writeReport(report: EvidenceArchiveRecoveryReport, output: Path) {
        validateReportOutput(output)
        val bytes = canonicalReportBytes(report)
        var created = false
        try {
            FileChannel.open(
                output,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                created = true
                writeAll(channel, bytes)
                channel.force(true)
                validatePartial(channel, bytes)
            }
        } catch (error: Error) {
            cleanupReportAfterError(output, created, error)
            throw error
        } catch (_: Exception) {
            if (created && !cleanupReport(output)) fail("RECOVERY_CLEANUP_FAILED", "output")
            fail("UNEXPECTED_FAILURE", "output")
        }
    }

    fun verify(
        workPackage: VerifiedEvidenceArchiveWorkPackage,
        archiveReport: EvidenceArchiveExecutionReport,
        recoveryRoot: Path,
    ): EvidenceArchiveRecoveryReport = execute(workPackage, archiveReport, recoveryRoot, throwFailure = true)

    fun verifyReport(
        workPackage: VerifiedEvidenceArchiveWorkPackage,
        archiveReport: EvidenceArchiveExecutionReport,
        recoveryRoot: Path,
    ): EvidenceArchiveRecoveryReport = execute(workPackage, archiveReport, recoveryRoot, throwFailure = false)

    private fun execute(
        workPackage: VerifiedEvidenceArchiveWorkPackage,
        archiveReport: EvidenceArchiveExecutionReport,
        recoveryRoot: Path,
        throwFailure: Boolean,
    ): EvidenceArchiveRecoveryReport {
        val startedAt = timeProvider.now()
        val recovered = mutableListOf<EvidenceArchiveRecoveredArtifact>()
        val receiptCapabilityChecks = mutableListOf<Instant>()
        val partials = mutableListOf<OwnedRecoveryPartial>()
        var archiveIdentity: RuntimeIdentityRef? = archiveReport.runtimeIdentity
        var verifierIdentity: RuntimeIdentityRef? = null
        var failure: EvidenceArchiveVerificationFailure? = null
        var root: TrustedRecoveryRoot? = null
        try {
            validateWorkPackage(workPackage)
            validateArchiveReport(workPackage, archiveReport)
            root = trustRecoveryRoot(recoveryRoot)
            verifierIdentity = attestVerifierIdentity(archiveIdentity)
            for ((index, pair) in workPackage.artifacts.zip(archiveReport.artifacts).withIndex()) {
                recovered += recoverArtifact(
                    index,
                    pair.first,
                    pair.second,
                    archiveReport,
                    root,
                    partials,
                    receiptCapabilityChecks,
                )
            }
            if (receiptCapabilityChecks.maxOrNull() != archiveReport.capabilityCheckedAt) {
                mismatch("archiveReport.capabilityCheckedAt")
            }
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

        val cleanupFailed = cleanupPartials(root, partials)
        if (cleanupFailed) {
            failure = verificationFailure("RECOVERY_CLEANUP_FAILED", "recoveryRoot")
        }
        val completedAt = timeProvider.now().coerceAtLeast(startedAt)
        val result = EvidenceArchiveRecoveryReport(
            schemaVersion = REPORT_SCHEMA_VERSION,
            workPackageId = workPackage.workPackageId,
            executionId = archiveReport.executionId,
            descriptorSha256 = workPackage.descriptorSha256,
            pilotManifestSha256 = workPackage.pilotManifestSha256,
            startedAt = startedAt,
            completedAt = completedAt,
            archiveIdentity = archiveIdentity,
            verifierIdentity = verifierIdentity,
            artifacts = Collections.unmodifiableList(recovered.toList()),
            status = if (failure == null && recovered.size == REQUIRED_ARTIFACT_COUNT) OperationStatus.PASS else OperationStatus.FAIL,
            errorCode = failure?.code?.substringBefore(':'),
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
        report: EvidenceArchiveExecutionReport,
    ) {
        if (report.status != OperationStatus.PASS || report.errorCode != null) mismatch("archiveReport.status")
        if (report.schemaVersion != REPORT_SCHEMA_VERSION) mismatch("archiveReport.schemaVersion")
        if (report.workPackageId != workPackage.workPackageId) mismatch("archiveReport.workPackageId")
        if (report.descriptorSha256 != workPackage.descriptorSha256) mismatch("archiveReport.descriptorSha256")
        if (report.pilotManifestSha256 != workPackage.pilotManifestSha256) mismatch("archiveReport.pilotManifestSha256")
        if (!EXECUTION_ID.matches(report.executionId)) mismatch("archiveReport.executionId")
        if (report.completedAt < report.startedAt) mismatch("archiveReport.completedAt")
        if (!SHA256.matches(report.policyFingerprint ?: "")) mismatch("archiveReport.policyFingerprint")
        if (report.capabilityCheckedAt == null) mismatch("archiveReport.capabilityCheckedAt")
        val identity = report.runtimeIdentity
        if (identity == null || identity.provider != ArchiveProvider.S3_COMPATIBLE ||
            !SHA256.matches(identity.principalFingerprint)
        ) mismatch("archiveReport.runtimeIdentity")
        if (report.accessOwner.isNullOrBlank()) mismatch("archiveReport.accessOwner")
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

    private fun recoverArtifact(
        index: Int,
        source: VerifiedArchiveSource,
        artifact: EvidenceArchiveArtifactReport,
        report: EvidenceArchiveExecutionReport,
        root: TrustedRecoveryRoot,
        partials: MutableList<OwnedRecoveryPartial>,
        receiptCapabilityChecks: MutableList<Instant>,
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

        val requiredRetainUntil = receipt.archivedAt.plus(parseRetention(receipt.retentionPolicy))
        val receiptProtection = protection(artifact.receiptReference, requiredRetainUntil, "$receiptField.protection")
        val payloadProtection = protection(artifact.payload, requiredRetainUntil, "$payloadField.protection")
        return EvidenceArchiveRecoveredArtifact(
            artifactId = source.artifactId,
            sourceRunId = source.sourceRunId,
            sourceCommit = source.sourceCommit,
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
        if (receipt.capabilityCheckedAt > checkNotNull(report.capabilityCheckedAt)) mismatch("$prefix.capabilityCheckedAt")
        if (receipt.archivedAt < report.startedAt || receipt.archivedAt > report.completedAt) mismatch("$prefix.archivedAt")
        if (receipt.verifier != RECEIPT_VERIFIER) mismatch("$prefix.verifier")
        if (!receipt.longTerm) mismatch("$prefix.longTerm")
    }

    private fun protection(
        reference: EvidenceArchiveExactObjectReference,
        requiredRetainUntil: Instant,
        field: String,
    ): EvidenceArchiveProtectionFacts {
        val snapshot = try {
            gateway.headProtection(reference.toStoredObjectRef(), operationTimeout)
        } catch (_: ArchiveUnavailable) {
            fail("DOWNLOAD_FAILED", field)
        }
        val retainUntil = snapshot.retainUntil
        if (snapshot.actualMode != APPROVED_MODE || retainUntil == null || retainUntil < requiredRetainUntil) {
            fail("PROTECTION_INSUFFICIENT", field)
        }
        return EvidenceArchiveProtectionFacts(APPROVED_MODE, retainUntil)
    }

    private fun validateExactReference(reference: EvidenceArchiveExactObjectReference, field: String) {
        if (reference.versionId.isBlank() || reference.versionId.equals("null", ignoreCase = true)) {
            fail("LATEST_REFERENCE_FORBIDDEN", "$field.versionId")
        }
        val valid = reference.provider == ArchiveProvider.S3_COMPATIBLE &&
            reference.bucket.isNotBlank() && reference.key.isNotBlank() &&
            SHA256.matches(reference.sha256) && reference.sizeBytes > 0 &&
            matchesLocator(reference)
        if (!valid) mismatch("archiveReport.$field")
    }

    private fun matchesLocator(reference: EvidenceArchiveExactObjectReference): Boolean = try {
        val uri = URI(reference.locator)
        uri.scheme == "s3" && uri.host == reference.bucket && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath == "/${reference.key}"
    } catch (_: IllegalArgumentException) {
        false
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
            text(node, "key", "receipt.payload"),
            versionId,
            text(node, "sha256", "receipt.payload"),
            positiveLong(node, "sizeBytes", "receipt.payload"),
        )
    }

    private fun trustRecoveryRoot(path: Path): TrustedRecoveryRoot {
        if (!path.isAbsolute || path.normalize() != path || Files.isSymbolicLink(path)) invalidRoot()
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path)
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) invalidRoot()
            Files.list(path).use { if (it.findAny().isPresent) invalidRoot() }
            val realNoFollow = path.toRealPath(LinkOption.NOFOLLOW_LINKS)
            if (realNoFollow != path.toRealPath()) invalidRoot()
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val key = fileKeyReader.read(path, attributes) ?: invalidRoot()
            return TrustedRecoveryRoot(path, realNoFollow, key)
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

    private fun revalidateRoot(root: TrustedRecoveryRoot) {
        try {
            if (Files.isSymbolicLink(root.path) || root.path.toRealPath(LinkOption.NOFOLLOW_LINKS) != root.realPath ||
                root.path.toRealPath() != root.realPath
            ) invalidRoot()
            val attributes = Files.readAttributes(root.path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attributes.isDirectory || fileKeyReader.read(root.path, attributes) != root.fileKey) invalidRoot()
        } catch (failure: EvidenceArchiveVerificationFailure) {
            throw failure
        } catch (_: IOException) {
            invalidRoot()
        } catch (_: SecurityException) {
            invalidRoot()
        }
    }

    private fun writePartial(
        root: TrustedRecoveryRoot,
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
                val fileKey = fileKeyReader.read(path, attributes) ?: throw IOException()
                val identity = OwnedRecoveryPartial(path, path.toRealPath(LinkOption.NOFOLLOW_LINKS), fileKey)
                partials += identity
                writeAll(channel, bytes)
                channel.force(true)
                validatePartial(channel, bytes)
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

    private fun cleanupPartials(root: TrustedRecoveryRoot?, partials: List<OwnedRecoveryPartial>): Boolean {
        if (root == null) return false
        var failed = false
        for (partial in partials.asReversed()) {
            try {
                revalidateRoot(root)
                val attributes = Files.readAttributes(partial.path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                val owned = attributes.isRegularFile && !Files.isSymbolicLink(partial.path) &&
                    partial.path.toRealPath(LinkOption.NOFOLLOW_LINKS) == partial.realPath &&
                    fileKeyReader.read(partial.path, attributes) == partial.fileKey
                if (!owned) throw IOException()
                partialCleanup.cleanup(partial.path)
            } catch (_: Exception) {
                failed = true
            }
        }
        return failed
    }

    private fun cleanupAfterError(root: TrustedRecoveryRoot?, partials: List<OwnedRecoveryPartial>, error: Error) {
        if (cleanupPartials(root, partials)) error.addSuppressed(IOException("RECOVERY_CLEANUP_FAILED"))
    }

    private fun canonicalReportBytes(report: EvidenceArchiveRecoveryReport): ByteArray {
        val root = JSON.createObjectNode().apply {
            put("schemaVersion", report.schemaVersion)
            put("workPackageId", report.workPackageId)
            put("executionId", report.executionId)
            put("descriptorSha256", report.descriptorSha256)
            put("pilotManifestSha256", report.pilotManifestSha256)
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
                        set<ObjectNode>("payload", recoveredObjectNode(artifact.payload))
                        set<ObjectNode>("receipt", recoveredObjectNode(artifact.receipt))
                    }
                }
            }
            put("status", report.status.name)
            report.errorCode?.let { put("errorCode", it) } ?: putNull("errorCode")
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
            text(node, "key", field),
            text(node, "versionId", field),
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

    private fun safeFileName(value: String, field: String) {
        val valid = value.length in 1..255 && value != "." && value != ".." &&
            '/' !in value && '\\' !in value && !value.any(Char::isISOControl) &&
            try { Path.of(value).fileName.toString() == value } catch (_: IllegalArgumentException) { false }
        if (!valid) mismatch(field)
    }

    private fun nullableText(node: JsonNode, name: String, prefix: String): String? {
        val value = requireField(node, name, prefix)
        if (value.isNull) return null
        if (!value.isTextual || value.textValue().isBlank()) mismatch("$prefix.$name")
        return value.textValue()
    }

    private fun parseInstant(value: String): Instant = try {
        Instant.parse(value)
    } catch (_: IllegalArgumentException) {
        mismatch("archiveReport.capabilityCheckedAt")
    }

    private fun cleanupReport(path: Path): Boolean = try {
        Files.delete(path)
        true
    } catch (_: Exception) {
        false
    }

    private fun cleanupReportAfterError(path: Path, created: Boolean, error: Error) {
        if (created && !cleanupReport(path)) error.addSuppressed(IOException("RECOVERY_CLEANUP_FAILED"))
    }

    private fun parseRetention(value: String): Duration = try {
        Duration.parse(value)
    } catch (_: IllegalArgumentException) {
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

    private fun instant(node: JsonNode, name: String, prefix: String): Instant = try {
        Instant.parse(text(node, name, prefix))
    } catch (_: IllegalArgumentException) {
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

    private data class TrustedRecoveryRoot(val path: Path, val realPath: Path, val fileKey: Any)

    private data class OwnedRecoveryPartial(val path: Path, val realPath: Path, val fileKey: Any)

    private companion object {
        const val REPORT_SCHEMA_VERSION = 1
        const val REQUIRED_ARTIFACT_COUNT = 2
        const val MAX_RECEIPT_BYTES = 1L * 1024 * 1024
        const val MAX_PAYLOAD_BYTES = 64L * 1024 * 1024
        const val MAX_INPUT_BYTES = 1 * 1024 * 1024
        const val WORK_PACKAGE_ID = "V0-2-EVIDENCE-ARCHIVE-001"
        const val PILOT_CLASSIFICATION = "LOCAL_PILOT_NOT_IMMUTABLE"
        const val APPROVED_MODE = "COMPLIANCE"
        const val RECEIPT_VERIFIER = "SHA-256"
        const val SHA_256 = "SHA-256"
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val COMMIT = Regex("^[0-9a-f]{40}$")
        val DECIMAL_ID = Regex("^[0-9]+$")
        val EXECUTION_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        val RECEIPT_FIELDS = setOf(
            "acceptanceId", "sourceArtifactId", "sourceRunId", "sourceCommit", "sourceSha256", "payload",
            "accessOwner", "retentionPolicy", "immutabilityControl", "policyFingerprint", "capabilityCheckedAt",
            "archivedAt", "verifier", "longTerm",
        )
        val STORED_OBJECT_FIELDS = setOf(
            "provider", "locator", "bucket", "key", "versionId", "sha256", "sizeBytes",
        )
        val WORK_PACKAGE_FIELDS = setOf(
            "schemaVersion", "workPackageId", "subjectCommit", "pairedSubjectCommit", "pilotManifest", "artifacts",
        )
        val PILOT_FIELDS = setOf("fileName", "sha256", "classification", "conditionBClosed")
        val WORK_PACKAGE_ARTIFACT_FIELDS = setOf(
            "artifactId", "artifactName", "fileName", "sourceRunId", "sourceCommit", "sizeBytes", "sha256",
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
            .build()
    }
}
