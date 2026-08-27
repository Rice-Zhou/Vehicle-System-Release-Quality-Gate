package com.ricezhou.vsrqg.shared.adapter.archive.operations

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCommand
import com.ricezhou.vsrqg.shared.application.archive.ArchiveEvidence
import com.ricezhou.vsrqg.shared.application.archive.ArchiveIntegrityFailure
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.ArchiveResult
import com.ricezhou.vsrqg.shared.application.archive.ArchiveUnavailable
import com.ricezhou.vsrqg.shared.application.archive.RuntimeIdentityRef
import com.ricezhou.vsrqg.shared.application.archive.StoredObjectRef
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Collections
import java.util.UUID
import org.erdtman.jcs.JsonCanonicalizer

data class EvidenceArchiveExactObjectReference(
    val provider: ArchiveProvider,
    val locator: String,
    val bucket: String,
    val key: String,
    val versionId: String,
    val sha256: String,
    val sizeBytes: Long,
)

internal object EvidenceArchiveS3ReferenceContract {
    fun validBucket(value: String): Boolean = value.isNotBlank() && EvidenceArchiveReportSafety.safeOpaque(value)

    fun validKey(value: String): Boolean = value.isNotBlank() &&
        value.toByteArray(UTF_8).size <= 1024 &&
        !value.startsWith('/') && '\\' !in value &&
        value.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
        EvidenceArchiveReportSafety.safeStorageKey(value)

    fun validVersion(value: String?): Boolean =
        !value.isNullOrBlank() && !value.equals("null", ignoreCase = true) &&
            EvidenceArchiveReportSafety.safeOpaque(value)

    fun matchesLocator(locator: String, bucket: String, key: String): Boolean =
        validBucket(bucket) && validKey(key) && locator == "s3://$bucket/$key"

    fun keyFromLocator(locator: String, bucket: String): String? {
        if (!validBucket(bucket)) return null
        val prefix = "s3://$bucket/"
        if (!locator.startsWith(prefix)) return null
        return locator.removePrefix(prefix).takeIf(::validKey)
    }
}

internal object EvidenceArchiveReportSafety {
    private val OWNER_ABSOLUTE_LOCATION_PATTERNS = listOf(
        Regex("^[a-z]:[\\\\/]", RegexOption.IGNORE_CASE),
        Regex("^(?:\\\\\\\\|//)"),
        Regex("^/"),
        Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE),
    )
    private val LOCATION_PATTERNS = listOf(
        Regex("[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE),
        Regex("(?:^|[^a-z0-9])file:", RegexOption.IGNORE_CASE),
        Regex("(?:^|[\\s=:;,(\\[])[a-z]:[\\\\/]", RegexOption.IGNORE_CASE),
        Regex("(?:^|[\\s=;,(\\[])(?:\\\\\\\\|//)[^/\\\\]+[/\\\\][^/\\\\]+"),
        Regex("(?:^|[\\s=:;,(\\[])/(?!/)"),
    )
    private val HIGH_CONFIDENCE_PATTERNS = listOf(
        Regex("[?&](?:x-amz-|signature=|credential=|security-token=|access[_-]?token=)", RegexOption.IGNORE_CASE),
        Regex("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
        Regex("-{5}BEGIN(?: [A-Z0-9]+)? PRIVATE KEY-{5}", RegexOption.IGNORE_CASE),
        Regex("(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{50,255})"),
        Regex("(?:authorization\\s*[:=]\\s*bearer\\b|\\bbearer\\s+[A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE),
        Regex("[a-z][a-z0-9+.-]*://[^/?#\\s]*@", RegexOption.IGNORE_CASE),
        Regex(
            "(?:arn:(?:aws|aws-cn|aws-us-gov):(?:iam|sts):|" +
                "\\b(?:principal|account|subject|session[\\s_-]*name|user[\\s_-]*id|" +
                "iam[\\s_-]*(?:user|role)|role[\\s_-]*session)\\b\\s*[:=])",
            RegexOption.IGNORE_CASE,
        ),
        Regex("\\b(?:credential|secret|password|private[\\s_-]*key)\\b\\s*[:=]", RegexOption.IGNORE_CASE),
    )

    fun safeStorageKey(value: String): Boolean = safe(value, HIGH_CONFIDENCE_PATTERNS)

    fun safeOpaque(value: String): Boolean = safe(value, HIGH_CONFIDENCE_PATTERNS) &&
        LOCATION_PATTERNS.none { it.containsMatchIn(value) }

    fun safeOwner(value: String): Boolean {
        val normalized = value.trim()
        return value.isNotBlank() && safe(value, HIGH_CONFIDENCE_PATTERNS) &&
            OWNER_ABSOLUTE_LOCATION_PATTERNS.none { it.containsMatchIn(normalized) }
    }

    fun validRetention(value: String): Boolean = try {
        val duration = Duration.parse(value)
        !duration.isZero && !duration.isNegative
    } catch (_: DateTimeParseException) {
        false
    } catch (_: DateTimeException) {
        false
    } catch (_: ArithmeticException) {
        false
    }

    private fun safe(value: String, patterns: List<Regex>): Boolean =
        !value.any(Char::isISOControl) && patterns.none { it.containsMatchIn(value) }
}

data class EvidenceArchiveArtifactReport(
    val artifactId: String,
    val sourceRunId: String,
    val sourceCommit: String,
    val payload: EvidenceArchiveExactObjectReference,
    val receiptReference: EvidenceArchiveExactObjectReference,
)

data class EvidenceArchiveExecutionReport(
    val schemaVersion: Int,
    val workPackageId: String,
    val executionId: String,
    val descriptorSha256: String,
    val pilotManifestSha256: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val policyFingerprint: String?,
    val capabilityCheckedAt: Instant?,
    val runtimeIdentity: RuntimeIdentityRef?,
    val artifacts: List<EvidenceArchiveArtifactReport>,
    val accessOwner: String?,
    val retentionPolicy: String?,
    val immutabilityControl: String?,
    val status: OperationStatus,
    val errorCode: String?,
)

class EvidenceArchiveOperationFailure(
    val code: String,
) : IllegalStateException(code)

internal object EvidenceArchiveOperationErrorCodes {
    const val UNEXPECTED_FAILURE = "UNEXPECTED_FAILURE"

    private val allowed = setOf(
        "WORK_PACKAGE_INVALID",
        "WORK_PACKAGE_READ_FAILED",
        "ARCHIVE_INPUT_FAILURE",
        "ARCHIVE_INTEGRITY_FAILURE",
        "ARCHIVE_UNAVAILABLE",
        "ARCHIVE_VERIFICATION_FAILURE",
        "ARCHIVE_POLICY_FAILURE",
        "ARCHIVE_RESULT_INVALID",
        "ARCHIVE_RESULT_CONFLICT",
        "REPORT_OUTPUT_INVALID",
        "REPORT_TARGET_EXISTS",
        "REPORT_WRITE_FAILED",
        "REPORT_SERIALIZATION_FAILED",
        "REPORT_CLEANUP_FAILED",
        "CONFIGURATION_INVALID",
        "VERIFICATION_UNAVAILABLE",
        "USAGE_ERROR",
        UNEXPECTED_FAILURE,
    )

    fun sanitize(code: String): String = code.takeIf(allowed::contains) ?: UNEXPECTED_FAILURE

    fun isAllowed(code: String): Boolean = sanitize(code) == code
}

class EvidenceArchiveRunner internal constructor(
    private val archiveEvidence: ArchiveEvidence,
    private val timeProvider: TimeProvider,
    private val executionIdProvider: () -> UUID = UUID::randomUUID,
    private val reportWriter: EvidenceArchiveReportWriter = EvidenceArchiveReportWriter(),
) {
    fun run(workPackage: VerifiedEvidenceArchiveWorkPackage): EvidenceArchiveExecutionReport {
        val startedAt = timeProvider.now()
        val executionId = executionIdProvider().toString()
        val artifacts = mutableListOf<EvidenceArchiveArtifactReport>()
        val exactObjectIdentities = mutableSetOf<ExactObjectIdentity>()
        var controls: ExecutionControls? = null
        var latestCapabilityCheckedAt: Instant? = null
        var latestArchivedAt: Instant? = null
        var errorCode: String? = null

        if (archiveEvidence.configuredAccessOwner?.let(EvidenceArchiveReportSafety::safeOwner) == false) {
            errorCode = "ARCHIVE_POLICY_FAILURE"
        } else if (workPackage.artifacts.size != REQUIRED_ARTIFACT_COUNT) {
            errorCode = "WORK_PACKAGE_INVALID"
        } else {
            for (source in workPackage.artifacts) {
                val result = try {
                    archiveEvidence.archive(
                        ArchiveCommand(
                            acceptanceId = workPackage.workPackageId,
                            sourceArtifactId = source.artifactId,
                            sourceRunId = source.sourceRunId,
                            sourceCommit = source.sourceCommit,
                            source = source.path,
                            expectedSha256 = source.sha256,
                        ),
                    )
                } catch (failure: Exception) {
                    errorCode = stableFailureCode(failure)
                    break
                }

                val (mapped, candidateControls) = try {
                    mapResult(workPackage.workPackageId, source, result, startedAt) to controls(result)
                } catch (failure: EvidenceArchiveOperationFailure) {
                    errorCode = EvidenceArchiveOperationErrorCodes.sanitize(failure.code)
                    break
                }
                if (listOf(mapped.payload, mapped.receiptReference).any { reference ->
                        !exactObjectIdentities.add(reference.exactIdentity())
                    }
                ) {
                    errorCode = "ARCHIVE_RESULT_INVALID"
                    break
                }
                artifacts += mapped
                latestCapabilityCheckedAt = latestOf(latestCapabilityCheckedAt, result.receipt.capabilityCheckedAt)
                latestArchivedAt = latestOf(latestArchivedAt, result.receipt.archivedAt)
                if (controls != null && controls != candidateControls) {
                    errorCode = "ARCHIVE_RESULT_CONFLICT"
                    break
                }
                if (controls == null) controls = candidateControls
            }
        }

        val completedAt = timeProvider.now().coerceAtLeast(startedAt)
        if (errorCode == null && latestArchivedAt?.isAfter(completedAt) == true) {
            errorCode = "ARCHIVE_RESULT_INVALID"
        }
        val success = errorCode == null && artifacts.size == REQUIRED_ARTIFACT_COUNT
        val stableControls = controls
        return EvidenceArchiveExecutionReport(
            schemaVersion = REPORT_SCHEMA_VERSION,
            workPackageId = workPackage.workPackageId,
            executionId = executionId,
            descriptorSha256 = workPackage.descriptorSha256,
            pilotManifestSha256 = workPackage.pilotManifestSha256,
            startedAt = startedAt,
            completedAt = completedAt,
            policyFingerprint = stableControls?.policyFingerprint,
            capabilityCheckedAt = latestCapabilityCheckedAt,
            runtimeIdentity = stableControls?.runtimeIdentity,
            artifacts = Collections.unmodifiableList(artifacts.toList()),
            accessOwner = stableControls?.accessOwner,
            retentionPolicy = stableControls?.retentionPolicy,
            immutabilityControl = stableControls?.immutabilityControl,
            status = if (success) OperationStatus.PASS else OperationStatus.FAIL,
            errorCode = if (success) null else errorCode ?: "ARCHIVE_RESULT_INVALID",
        )
    }

    fun writeReport(report: EvidenceArchiveExecutionReport, output: Path) {
        reportWriter.write(report, output)
    }

    fun validateReportOutput(output: Path) {
        reportWriter.validate(output)
    }

    private fun mapResult(
        workPackageId: String,
        source: VerifiedArchiveSource,
        result: ArchiveResult,
        startedAt: Instant,
    ): EvidenceArchiveArtifactReport {
        val receipt = result.receipt
        val identity = result.runtimeIdentity
        if (receipt.acceptanceId != workPackageId ||
            receipt.sourceArtifactId != source.artifactId ||
            receipt.sourceRunId != source.sourceRunId ||
            receipt.sourceCommit != source.sourceCommit ||
            receipt.sourceSha256 != source.sha256 ||
            !receipt.longTerm ||
            receipt.verifier != "SHA-256" ||
            receipt.capabilityCheckedAt < startedAt ||
            receipt.archivedAt < receipt.capabilityCheckedAt ||
            identity == null ||
            identity.provider != ArchiveProvider.S3_COMPATIBLE ||
            !SHA256.matches(identity.principalFingerprint)
        ) {
            invalidResult()
        }
        val payload = exactReference(receipt.payload)
        if (payload.sha256 != source.sha256 || payload.sizeBytes != source.sizeBytes) {
            invalidResult()
        }
        val receiptReference = exactReceiptReference(result, payload)
        return EvidenceArchiveArtifactReport(
            artifactId = source.artifactId,
            sourceRunId = source.sourceRunId,
            sourceCommit = source.sourceCommit,
            payload = payload,
            receiptReference = receiptReference,
        )
    }

    private fun exactReference(reference: StoredObjectRef): EvidenceArchiveExactObjectReference {
        val bucket = reference.bucket
        val versionId = reference.versionId ?: invalidResult()
        if (reference.provider != ArchiveProvider.S3_COMPATIBLE ||
            bucket == null ||
            !EvidenceArchiveS3ReferenceContract.validBucket(bucket) ||
            !EvidenceArchiveS3ReferenceContract.validKey(reference.key) ||
            !EvidenceArchiveS3ReferenceContract.validVersion(versionId) ||
            !SHA256.matches(reference.sha256) ||
            reference.sizeBytes < 1 ||
            !EvidenceArchiveS3ReferenceContract.matchesLocator(reference.locator, bucket, reference.key)
        ) {
            invalidResult()
        }
        return EvidenceArchiveExactObjectReference(
            provider = reference.provider,
            locator = reference.locator,
            bucket = bucket,
            key = reference.key,
            versionId = versionId,
            sha256 = reference.sha256,
            sizeBytes = reference.sizeBytes,
        )
    }

    private fun exactReceiptReference(
        result: ArchiveResult,
        payload: EvidenceArchiveExactObjectReference,
    ): EvidenceArchiveExactObjectReference {
        val reference = result.receiptReference
        val versionId = reference.versionId ?: invalidResult()
        val key = EvidenceArchiveS3ReferenceContract.keyFromLocator(reference.locator, payload.bucket)
        if (!EvidenceArchiveS3ReferenceContract.validVersion(versionId) ||
            key == null || !SHA256.matches(reference.sha256) || reference.sizeBytes < 1
        ) {
            invalidResult()
        }
        return EvidenceArchiveExactObjectReference(
            provider = ArchiveProvider.S3_COMPATIBLE,
            locator = reference.locator,
            bucket = payload.bucket,
            key = key,
            versionId = versionId,
            sha256 = reference.sha256,
            sizeBytes = reference.sizeBytes,
        )
    }

    private fun controls(result: ArchiveResult): ExecutionControls {
        val receipt = result.receipt
        val identity = result.runtimeIdentity ?: invalidResult()
        if (!SHA256.matches(receipt.policyFingerprint) ||
            !EvidenceArchiveReportSafety.safeOwner(receipt.accessOwner) ||
            !EvidenceArchiveReportSafety.validRetention(receipt.retentionPolicy) ||
            receipt.immutabilityControl != "COMPLIANCE"
        ) {
            invalidResult()
        }
        return ExecutionControls(
            policyFingerprint = receipt.policyFingerprint,
            runtimeIdentity = identity,
            accessOwner = receipt.accessOwner,
            retentionPolicy = receipt.retentionPolicy,
            immutabilityControl = receipt.immutabilityControl,
        )
    }

    private fun stableFailureCode(failure: Exception): String = when (failure) {
        is EvidenceArchiveOperationFailure -> EvidenceArchiveOperationErrorCodes.sanitize(failure.code)
        is EvidenceArchiveInputFailure -> "ARCHIVE_INPUT_FAILURE"
        is ArchiveIntegrityFailure -> "ARCHIVE_INTEGRITY_FAILURE"
        is ArchiveUnavailable -> "ARCHIVE_UNAVAILABLE"
        is EvidenceArchiveVerificationFailure -> "ARCHIVE_VERIFICATION_FAILURE"
        is IllegalArgumentException -> "ARCHIVE_POLICY_FAILURE"
        else -> EvidenceArchiveOperationErrorCodes.UNEXPECTED_FAILURE
    }

    private fun invalidResult(): Nothing = throw EvidenceArchiveOperationFailure("ARCHIVE_RESULT_INVALID")

    private fun latestOf(current: Instant?, candidate: Instant): Instant =
        if (current == null || candidate.isAfter(current)) candidate else current

    private data class ExecutionControls(
        val policyFingerprint: String,
        val runtimeIdentity: RuntimeIdentityRef,
        val accessOwner: String,
        val retentionPolicy: String,
        val immutabilityControl: String,
    )

    private data class ExactObjectIdentity(
        val provider: ArchiveProvider,
        val bucket: String,
        val key: String,
        val versionId: String,
    )

    private fun EvidenceArchiveExactObjectReference.exactIdentity(): ExactObjectIdentity =
        ExactObjectIdentity(provider, bucket, key, versionId)

    private companion object {
        const val REPORT_SCHEMA_VERSION = 1
        const val REQUIRED_ARTIFACT_COUNT = 2
        val SHA256 = Regex("^[0-9a-f]{64}$")

    }
}

/**
 * Writes into an operator-controlled, single-writer directory. Identity and digest checks detect
 * accidental or concurrent replacement; they do not claim atomic safety in a hostile shared directory.
 */
internal class EvidenceArchiveReportWriter(
    private val files: EvidenceArchiveReportFileOperations = EvidenceArchiveReportFileOperations.nio(),
    private val objectMapper: ObjectMapper = JsonMapper.builder().build(),
    private val partialIdProvider: () -> UUID = UUID::randomUUID,
) {
    fun validate(output: Path) {
        validateAndTrust(output)
    }

    private fun validateAndTrust(output: Path): EvidenceArchiveTrustedDirectory {
        val normalized = output.normalize()
        if (!output.isAbsolute || normalized != output || output.fileName == null) {
            fail("REPORT_OUTPUT_INVALID")
        }
        val parent = output.parent ?: fail("REPORT_OUTPUT_INVALID")
        return try {
            val directory = files.trustDirectory(parent)
            if (files.existsNoFollow(output)) {
                fail("REPORT_TARGET_EXISTS")
            }
            directory
        } catch (failure: EvidenceArchiveOperationFailure) {
            throw failure
        } catch (_: IOException) {
            fail("REPORT_OUTPUT_INVALID")
        } catch (_: SecurityException) {
            fail("REPORT_OUTPUT_INVALID")
        }
    }

    fun write(report: EvidenceArchiveExecutionReport, output: Path) {
        val directory = validateAndTrust(output)
        val parent = checkNotNull(output.parent)
        val bytes = canonicalBytes(report)
        val partial = try {
            val partialFileName = ".${output.fileName}-${partialIdProvider()}.partial"
            files.openPartial(parent, partialFileName)
        } catch (_: IOException) {
            fail("REPORT_WRITE_FAILED")
        } catch (_: SecurityException) {
            fail("REPORT_WRITE_FAILED")
        }

        var published = false
        try {
            files.writeAndForce(partial, bytes)
            files.revalidateDirectory(directory)
            files.validatePartial(partial, bytes)
            files.commitCreateOnly(partial, output)
            published = true
            files.revalidateDirectory(directory)
            files.validatePublished(partial, output, bytes)
            closeAndCleanupOrFail(partial)
            files.forceDirectory(parent)
            files.revalidateDirectory(directory)
        } catch (failure: FileAlreadyExistsException) {
            closeAndCleanupOrFail(partial)
            fail(if (published) "REPORT_WRITE_FAILED" else "REPORT_TARGET_EXISTS")
        } catch (failure: Exception) {
            closeAndCleanupOrFail(partial)
            if (failure is EvidenceArchiveOperationFailure) throw failure
            fail("REPORT_WRITE_FAILED")
        } catch (failure: Error) {
            cleanupAfterError(partial, failure)
            throw failure
        }
    }

    internal fun canonicalBytes(report: EvidenceArchiveExecutionReport): ByteArray {
        val root = objectMapper.createObjectNode()
        root.put("schemaVersion", report.schemaVersion)
        root.put("workPackageId", report.workPackageId)
        root.put("executionId", report.executionId)
        root.put("descriptorSha256", report.descriptorSha256)
        root.put("pilotManifestSha256", report.pilotManifestSha256)
        root.put("startedAt", report.startedAt.toString())
        root.put("completedAt", report.completedAt.toString())
        putNullable(root, "policyFingerprint", report.policyFingerprint)
        putNullable(root, "capabilityCheckedAt", report.capabilityCheckedAt?.toString())
        report.runtimeIdentity?.let { identity ->
            root.putObject("runtimeIdentity").apply {
                put("provider", identity.provider.name)
                put("principalFingerprint", identity.principalFingerprint)
            }
        } ?: root.putNull("runtimeIdentity")
        root.putArray("artifacts").apply {
            report.artifacts.forEach { artifact ->
                addObject().apply {
                    put("artifactId", artifact.artifactId)
                    put("sourceRunId", artifact.sourceRunId)
                    put("sourceCommit", artifact.sourceCommit)
                    set<ObjectNode>("payload", objectReference(artifact.payload))
                    set<ObjectNode>("receiptReference", objectReference(artifact.receiptReference))
                }
            }
        }
        putNullable(root, "accessOwner", report.accessOwner)
        putNullable(root, "retentionPolicy", report.retentionPolicy)
        putNullable(root, "immutabilityControl", report.immutabilityControl)
        root.put("status", report.status.name)
        putNullable(root, "errorCode", report.errorCode)
        return try {
            JsonCanonicalizer(objectMapper.writeValueAsBytes(root)).encodedUTF8
        } catch (_: IOException) {
            fail("REPORT_SERIALIZATION_FAILED")
        }
    }

    private fun objectReference(reference: EvidenceArchiveExactObjectReference): ObjectNode =
        objectMapper.createObjectNode().apply {
            put("provider", reference.provider.name)
            put("locator", reference.locator)
            put("bucket", reference.bucket)
            put("key", reference.key)
            put("versionId", reference.versionId)
            put("sha256", reference.sha256)
            put("sizeBytes", reference.sizeBytes)
        }

    private fun putNullable(node: ObjectNode, field: String, value: String?) {
        value?.let { node.put(field, it) } ?: node.putNull(field)
    }

    private fun closeAndCleanupOrFail(partial: EvidenceArchiveReportPartial) {
        var cleanupFailure: Exception? = null
        try {
            partial.close()
        } catch (failure: Exception) {
            cleanupFailure = failure
        }
        try {
            files.cleanupPartial(partial)
        } catch (failure: Exception) {
            cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
        }
        if (cleanupFailure != null) fail("REPORT_CLEANUP_FAILED")
    }

    private fun cleanupAfterError(partial: EvidenceArchiveReportPartial, original: Error) {
        try {
            partial.close()
        } catch (cleanupFailure: Throwable) {
            original.addSuppressed(cleanupFailure)
        }
        try {
            files.cleanupPartial(partial)
        } catch (cleanupFailure: Throwable) {
            original.addSuppressed(cleanupFailure)
        }
    }

    private fun fail(code: String): Nothing = throw EvidenceArchiveOperationFailure(code)
}

internal enum class EvidenceArchiveDirectoryAccessControl {
    POSIX_NOT_SHARED_WRITABLE,
    OPERATOR_CONTROLLED_ACL,
}

internal fun interface EvidenceArchiveDirectoryAccessReader {
    fun read(path: Path): EvidenceArchiveDirectoryAccessControl

    companion object {
        fun nio(): EvidenceArchiveDirectoryAccessReader = EvidenceArchiveDirectoryAccessReader { path ->
            val posixView = Files.getFileAttributeView(
                path,
                PosixFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (posixView == null) {
                // Non-POSIX providers rely on the documented operator-controlled ACL invariant.
                EvidenceArchiveDirectoryAccessControl.OPERATOR_CONTROLLED_ACL
            } else {
                val permissions = posixView.readAttributes().permissions()
                if (PosixFilePermission.GROUP_WRITE in permissions || PosixFilePermission.OTHERS_WRITE in permissions) {
                    throw IOException("shared-writable evidence archive directory")
                }
                EvidenceArchiveDirectoryAccessControl.POSIX_NOT_SHARED_WRITABLE
            }
        }
    }
}

internal data class EvidenceArchiveTrustedDirectory(
    val path: Path,
    val realPath: Path,
    val fileKey: Any,
    val accessControl: EvidenceArchiveDirectoryAccessControl,
) {
    companion object {
        fun require(
            path: Path,
            realPathResolver: (Path) -> Path,
            fileKeyReader: (Path, BasicFileAttributes) -> Any?,
            accessReader: EvidenceArchiveDirectoryAccessReader,
        ): EvidenceArchiveTrustedDirectory {
            val noFollowRealPath = path.toRealPath(LinkOption.NOFOLLOW_LINKS)
            val realPath = realPathResolver(path)
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (Files.isSymbolicLink(path) || !attributes.isDirectory || noFollowRealPath != path || realPath != path) {
                throw IOException("untrusted evidence archive directory")
            }
            val fileKey = fileKeyReader(path, attributes) ?: throw IOException("directory ownership unavailable")
            return EvidenceArchiveTrustedDirectory(path, realPath, fileKey, accessReader.read(path))
        }
    }
}

internal data class EvidenceArchiveReportFileIdentity(
    val realPath: Path,
    val fileKey: Any,
)

internal class EvidenceArchiveReportPartial internal constructor(
    val path: Path,
    internal val identity: EvidenceArchiveReportFileIdentity,
    private val channel: EvidenceArchiveReportChannel,
) : AutoCloseable {
    internal fun writeAndForce(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) <= 0) throw IOException("report channel made no write progress")
        }
        channel.force(true)
    }

    internal fun validate(bytes: ByteArray) = requireChannelBytes(channel, bytes)

    override fun close() = channel.close()
}

internal fun interface EvidenceArchiveFileKeyReader {
    fun read(path: Path, attributes: BasicFileAttributes): Any?
}

internal fun interface EvidenceArchiveReadChannelOpener {
    fun open(path: Path): EvidenceArchiveReportChannel
}

internal fun interface EvidenceArchivePartialChannelDecorator {
    fun decorate(path: Path, channel: EvidenceArchiveReportChannel): EvidenceArchiveReportChannel
}

internal interface EvidenceArchiveReportChannel : AutoCloseable {
    fun write(buffer: ByteBuffer): Int
    fun read(buffer: ByteBuffer): Int
    fun position(position: Long)
    fun size(): Long
    fun force(metadata: Boolean)
}

internal interface EvidenceArchiveReportFileOperations {
    fun existsNoFollow(path: Path): Boolean
    fun trustDirectory(parent: Path): EvidenceArchiveTrustedDirectory
    fun revalidateDirectory(directory: EvidenceArchiveTrustedDirectory)
    fun openPartial(parent: Path, partialFileName: String): EvidenceArchiveReportPartial
    fun writeAndForce(partial: EvidenceArchiveReportPartial, bytes: ByteArray)
    fun validatePartial(partial: EvidenceArchiveReportPartial, expectedBytes: ByteArray)
    fun commitCreateOnly(partial: EvidenceArchiveReportPartial, output: Path)
    fun validatePublished(partial: EvidenceArchiveReportPartial, target: Path, expectedBytes: ByteArray)
    fun cleanupPartial(partial: EvidenceArchiveReportPartial)
    fun forceDirectory(path: Path)

    companion object {
        fun nio(
            fileKeyReader: EvidenceArchiveFileKeyReader = EvidenceArchiveFileKeyReader { _, attributes ->
                attributes.fileKey()
            },
            readChannelOpener: EvidenceArchiveReadChannelOpener = EvidenceArchiveReadChannelOpener { path ->
                NioEvidenceArchiveReportChannel(
                    FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                )
            },
            partialChannelDecorator: EvidenceArchivePartialChannelDecorator =
                EvidenceArchivePartialChannelDecorator { _, channel -> channel },
            directoryAccessReader: EvidenceArchiveDirectoryAccessReader = EvidenceArchiveDirectoryAccessReader.nio(),
        ): EvidenceArchiveReportFileOperations =
            NioEvidenceArchiveReportFileOperations(fileKeyReader, readChannelOpener, partialChannelDecorator, directoryAccessReader)
    }
}

private class NioEvidenceArchiveReportFileOperations(
    private val fileKeyReader: EvidenceArchiveFileKeyReader,
    private val readChannelOpener: EvidenceArchiveReadChannelOpener,
    private val partialChannelDecorator: EvidenceArchivePartialChannelDecorator,
    private val directoryAccessReader: EvidenceArchiveDirectoryAccessReader,
) : EvidenceArchiveReportFileOperations {
    override fun existsNoFollow(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    override fun trustDirectory(parent: Path): EvidenceArchiveTrustedDirectory {
        return EvidenceArchiveTrustedDirectory.require(
            parent,
            { it.toRealPath() },
            fileKeyReader::read,
            directoryAccessReader,
        )
    }

    override fun revalidateDirectory(directory: EvidenceArchiveTrustedDirectory) {
        val current = trustDirectory(directory.path)
        if (current.realPath != directory.realPath ||
            current.fileKey != directory.fileKey ||
            current.accessControl != directory.accessControl
        ) {
            throw IOException("report directory identity changed")
        }
    }

    override fun openPartial(parent: Path, partialFileName: String): EvidenceArchiveReportPartial {
        val path = parent.resolve(partialFileName)
        if (path.parent != parent || path.fileName.toString() != partialFileName) {
            throw IOException("invalid partial name")
        }
        val nioChannel = FileChannel.open(
            path,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        val channel = try {
            partialChannelDecorator.decorate(path, NioEvidenceArchiveReportChannel(nioChannel))
        } catch (failure: Exception) {
            nioChannel.close()
            throw failure
        }
        return try {
            val attributes = requireRegular(path)
            if (attributes.size() != 0L) throw IOException("new partial is not empty")
            val fileKey = fileKeyReader.read(path, attributes) ?: throw IOException("partial ownership unavailable")
            EvidenceArchiveReportPartial(
                path,
                EvidenceArchiveReportFileIdentity(path.toRealPath(LinkOption.NOFOLLOW_LINKS), fileKey),
                channel,
            )
        } catch (failure: Exception) {
            channel.close()
            throw failure
        }
    }

    override fun writeAndForce(partial: EvidenceArchiveReportPartial, bytes: ByteArray) = partial.writeAndForce(bytes)

    override fun validatePartial(partial: EvidenceArchiveReportPartial, expectedBytes: ByteArray) {
        requireOwned(partial)
        partial.validate(expectedBytes)
    }

    override fun commitCreateOnly(partial: EvidenceArchiveReportPartial, output: Path) {
        requireOwned(partial)
        // A same-directory hard link is create-only. The trusted-directory boundary above is what
        // makes path-based identity revalidation maintainable; this is not a hostile-directory primitive.
        Files.createLink(output, partial.path)
    }

    override fun validatePublished(
        partial: EvidenceArchiveReportPartial,
        target: Path,
        expectedBytes: ByteArray,
    ) {
        val attributes = requireRegular(target)
        val targetFileKey = fileKeyReader.read(target, attributes)
            ?: throw IOException("published target ownership unavailable")
        if (targetFileKey != partial.identity.fileKey) {
            throw IOException("published target identity mismatch")
        }
        requirePublishedBytes(target, expectedBytes)
    }

    override fun cleanupPartial(partial: EvidenceArchiveReportPartial) {
        if (!existsNoFollow(partial.path)) return
        requireOwned(partial)
        Files.delete(partial.path)
    }

    override fun forceDirectory(path: Path) {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            return // Windows NIO does not expose a supported directory fsync operation.
        }
        FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    }

    private fun requireOwned(partial: EvidenceArchiveReportPartial): BasicFileAttributes {
        val attributes = requireRegular(partial.path)
        val realPath = partial.path.toRealPath(LinkOption.NOFOLLOW_LINKS)
        val fileKey = fileKeyReader.read(partial.path, attributes) ?: throw IOException("partial ownership unavailable")
        if (realPath != partial.identity.realPath ||
            fileKey != partial.identity.fileKey
        ) {
            throw IOException("partial identity changed")
        }
        return attributes
    }

    private fun requireRegular(path: Path): BasicFileAttributes {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (Files.isSymbolicLink(path) || !attributes.isRegularFile) throw IOException("not a regular file")
        return attributes
    }

    private fun requirePublishedBytes(path: Path, expectedBytes: ByteArray) {
        val attributes = requireRegular(path)
        if (attributes.size() != expectedBytes.size.toLong()) throw IOException("report size mismatch")
        readChannelOpener.open(path).use { channel -> requireChannelBytes(channel, expectedBytes) }
    }
}

private class NioEvidenceArchiveReportChannel(private val channel: FileChannel) : EvidenceArchiveReportChannel {
    override fun write(buffer: ByteBuffer): Int = channel.write(buffer)
    override fun read(buffer: ByteBuffer): Int = channel.read(buffer)
    override fun position(position: Long) {
        channel.position(position)
    }
    override fun size(): Long = channel.size()
    override fun force(metadata: Boolean) = channel.force(metadata)
    override fun close() = channel.close()
}

private fun requireChannelBytes(channel: EvidenceArchiveReportChannel, expectedBytes: ByteArray) {
    if (channel.size() != expectedBytes.size.toLong()) throw IOException("report size mismatch")
    val expectedDigest = MessageDigest.getInstance("SHA-256").digest(expectedBytes)
    val actualDigest = MessageDigest.getInstance("SHA-256")
    channel.position(0)
    val buffer = ByteBuffer.allocate(8192)
    while (true) {
        when (val count = channel.read(buffer)) {
            -1 -> break
            0 -> throw IOException("report channel made no read progress")
            else -> {
                if (count < -1) throw IOException("report channel returned an invalid read count")
                buffer.flip()
                actualDigest.update(buffer)
                buffer.clear()
            }
        }
    }
    if (!actualDigest.digest().contentEquals(expectedDigest)) throw IOException("report digest mismatch")
}
