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
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Instant
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
        var controls: ExecutionControls? = null
        var latestCapabilityCheckedAt: Instant? = null
        var errorCode: String? = null

        if (workPackage.artifacts.size != REQUIRED_ARTIFACT_COUNT) {
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
                    mapResult(workPackage.workPackageId, source, result) to controls(result)
                } catch (failure: EvidenceArchiveOperationFailure) {
                    errorCode = EvidenceArchiveOperationErrorCodes.sanitize(failure.code)
                    break
                }
                artifacts += mapped
                latestCapabilityCheckedAt = latestOf(latestCapabilityCheckedAt, result.receipt.capabilityCheckedAt)
                if (controls != null && controls != candidateControls) {
                    errorCode = "ARCHIVE_RESULT_CONFLICT"
                    break
                }
                if (controls == null) controls = candidateControls
            }
        }

        val completedAt = timeProvider.now().coerceAtLeast(startedAt)
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
    ): EvidenceArchiveArtifactReport {
        val receipt = result.receipt
        val identity = result.runtimeIdentity
        if (receipt.acceptanceId != workPackageId ||
            receipt.sourceArtifactId != source.artifactId ||
            receipt.sourceRunId != source.sourceRunId ||
            receipt.sourceCommit != source.sourceCommit ||
            receipt.sourceSha256 != source.sha256 ||
            !receipt.longTerm ||
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
            bucket.isNullOrBlank() ||
            reference.key.isBlank() ||
            !isExactVersion(versionId) ||
            !SHA256.matches(reference.sha256) ||
            reference.sizeBytes < 1 ||
            !matchesS3Locator(reference.locator, bucket, reference.key)
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
        val key = s3Key(reference.locator, payload.bucket)
        if (!isExactVersion(versionId) || key == null || !SHA256.matches(reference.sha256) || reference.sizeBytes < 1) {
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
            receipt.accessOwner.isBlank() ||
            receipt.retentionPolicy.isBlank() ||
            receipt.immutabilityControl.isBlank()
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

    private companion object {
        const val REPORT_SCHEMA_VERSION = 1
        const val REQUIRED_ARTIFACT_COUNT = 2
        val SHA256 = Regex("^[0-9a-f]{64}$")

        fun matchesS3Locator(locator: String, bucket: String, key: String): Boolean =
            s3Key(locator, bucket) == key

        fun isExactVersion(versionId: String?): Boolean = !versionId.isNullOrBlank() && versionId != "null"

        fun s3Key(locator: String, bucket: String): String? = try {
            val uri = URI(locator)
            val rawPath = uri.rawPath
            if (uri.scheme != "s3" || uri.host != bucket || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null || rawPath == null || !rawPath.startsWith('/')
            ) {
                null
            } else {
                rawPath.drop(1).takeIf(String::isNotBlank)
            }
        } catch (_: IllegalArgumentException) {
            null
        }
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

internal data class EvidenceArchiveTrustedDirectory(
    val path: Path,
    val realPath: Path,
    val fileKey: Any,
    val accessControl: EvidenceArchiveDirectoryAccessControl,
)

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
        ): EvidenceArchiveReportFileOperations =
            NioEvidenceArchiveReportFileOperations(fileKeyReader, readChannelOpener, partialChannelDecorator)
    }
}

private class NioEvidenceArchiveReportFileOperations(
    private val fileKeyReader: EvidenceArchiveFileKeyReader,
    private val readChannelOpener: EvidenceArchiveReadChannelOpener,
    private val partialChannelDecorator: EvidenceArchivePartialChannelDecorator,
) : EvidenceArchiveReportFileOperations {
    override fun existsNoFollow(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    override fun trustDirectory(parent: Path): EvidenceArchiveTrustedDirectory {
        val noFollowRealPath = parent.toRealPath(LinkOption.NOFOLLOW_LINKS)
        val realPath = parent.toRealPath()
        val attributes = Files.readAttributes(parent, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (Files.isSymbolicLink(parent) || !attributes.isDirectory || noFollowRealPath != parent || realPath != parent) {
            throw IOException("untrusted report directory")
        }
        val posixView = Files.getFileAttributeView(parent, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        val accessControl = if (posixView == null) {
            // Non-POSIX providers are accepted only under the operational invariant that this is a
            // single-writer directory protected by operator-controlled ACLs.
            EvidenceArchiveDirectoryAccessControl.OPERATOR_CONTROLLED_ACL
        } else {
            val permissions = posixView.readAttributes().permissions()
            if (PosixFilePermission.GROUP_WRITE in permissions || PosixFilePermission.OTHERS_WRITE in permissions) {
                throw IOException("shared-writable report directory")
            }
            EvidenceArchiveDirectoryAccessControl.POSIX_NOT_SHARED_WRITABLE
        }
        val fileKey = fileKeyReader.read(parent, attributes) ?: throw IOException("report directory ownership unavailable")
        return EvidenceArchiveTrustedDirectory(parent, realPath, fileKey, accessControl)
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
