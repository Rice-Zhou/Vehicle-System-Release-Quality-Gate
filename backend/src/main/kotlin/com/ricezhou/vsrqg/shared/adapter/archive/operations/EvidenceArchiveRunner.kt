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
                    errorCode = failure.code
                    break
                }
                if (controls != null && controls != candidateControls) {
                    errorCode = "ARCHIVE_RESULT_CONFLICT"
                    break
                }
                controls = candidateControls
                artifacts += mapped
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
            capabilityCheckedAt = stableControls?.capabilityCheckedAt,
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
            capabilityCheckedAt = receipt.capabilityCheckedAt,
            runtimeIdentity = identity,
            accessOwner = receipt.accessOwner,
            retentionPolicy = receipt.retentionPolicy,
            immutabilityControl = receipt.immutabilityControl,
        )
    }

    private fun stableFailureCode(failure: Exception): String = when (failure) {
        is EvidenceArchiveOperationFailure -> failure.code
        is EvidenceArchiveInputFailure -> "ARCHIVE_INPUT_FAILURE"
        is ArchiveIntegrityFailure -> "ARCHIVE_INTEGRITY_FAILURE"
        is ArchiveUnavailable -> "ARCHIVE_UNAVAILABLE"
        is EvidenceArchiveVerificationFailure -> "ARCHIVE_VERIFICATION_FAILURE"
        is IllegalArgumentException -> "ARCHIVE_POLICY_FAILURE"
        else -> "UNEXPECTED_FAILURE"
    }

    private fun invalidResult(): Nothing = throw EvidenceArchiveOperationFailure("ARCHIVE_RESULT_INVALID")

    private data class ExecutionControls(
        val policyFingerprint: String,
        val capabilityCheckedAt: Instant,
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

internal class EvidenceArchiveReportWriter(
    private val files: EvidenceArchiveReportFileOperations = EvidenceArchiveReportFileOperations.nio(),
    private val objectMapper: ObjectMapper = JsonMapper.builder().build(),
) {
    fun validate(output: Path) {
        val normalized = output.normalize()
        if (!output.isAbsolute || normalized != output || output.fileName == null) {
            fail("REPORT_OUTPUT_INVALID")
        }
        val parent = output.parent ?: fail("REPORT_OUTPUT_INVALID")
        try {
            if (files.isSymbolicLink(parent) || !files.isDirectoryNoFollow(parent) ||
                files.toRealPathNoFollow(parent) != parent || files.toRealPath(parent) != parent
            ) {
                fail("REPORT_OUTPUT_INVALID")
            }
            if (files.existsNoFollow(output)) {
                fail("REPORT_TARGET_EXISTS")
            }
        } catch (failure: EvidenceArchiveOperationFailure) {
            throw failure
        } catch (_: IOException) {
            fail("REPORT_OUTPUT_INVALID")
        } catch (_: SecurityException) {
            fail("REPORT_OUTPUT_INVALID")
        }
    }

    fun write(report: EvidenceArchiveExecutionReport, output: Path) {
        validate(output)
        val parent = checkNotNull(output.parent)
        val bytes = canonicalBytes(report)
        val partial = try {
            files.createPartial(parent, output.fileName.toString())
        } catch (_: IOException) {
            fail("REPORT_WRITE_FAILED")
        } catch (_: SecurityException) {
            fail("REPORT_WRITE_FAILED")
        }

        var published = false
        try {
            files.writeAndForce(partial, bytes)
            files.commitCreateOnly(partial, output)
            published = true
            files.deleteIfExists(partial)
            files.forceDirectory(parent)
        } catch (failure: FileAlreadyExistsException) {
            cleanupPartialOrFail(partial)
            fail(if (published) "REPORT_WRITE_FAILED" else "REPORT_TARGET_EXISTS")
        } catch (failure: Exception) {
            cleanupPartialOrFail(partial)
            if (failure is EvidenceArchiveOperationFailure) throw failure
            fail("REPORT_WRITE_FAILED")
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

    private fun cleanupPartialOrFail(partial: Path) {
        try {
            files.deleteIfExists(partial)
        } catch (_: Exception) {
            fail("REPORT_CLEANUP_FAILED")
        }
    }

    private fun fail(code: String): Nothing = throw EvidenceArchiveOperationFailure(code)
}

internal interface EvidenceArchiveReportFileOperations {
    fun existsNoFollow(path: Path): Boolean
    fun isSymbolicLink(path: Path): Boolean
    fun isDirectoryNoFollow(path: Path): Boolean
    fun toRealPathNoFollow(path: Path): Path
    fun toRealPath(path: Path): Path
    fun createPartial(parent: Path, outputFileName: String): Path
    fun writeAndForce(path: Path, bytes: ByteArray)
    fun commitCreateOnly(partial: Path, output: Path)
    fun deleteIfExists(path: Path): Boolean
    fun forceDirectory(path: Path)

    companion object {
        fun nio(): EvidenceArchiveReportFileOperations = NioEvidenceArchiveReportFileOperations
    }
}

private object NioEvidenceArchiveReportFileOperations : EvidenceArchiveReportFileOperations {
    override fun existsNoFollow(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    override fun isSymbolicLink(path: Path): Boolean = Files.isSymbolicLink(path)
    override fun isDirectoryNoFollow(path: Path): Boolean = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
    override fun toRealPathNoFollow(path: Path): Path = path.toRealPath(LinkOption.NOFOLLOW_LINKS)
    override fun toRealPath(path: Path): Path = path.toRealPath()

    override fun createPartial(parent: Path, outputFileName: String): Path =
        Files.createTempFile(parent, ".$outputFileName-", ".partial")

    override fun writeAndForce(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
    }

    override fun commitCreateOnly(partial: Path, output: Path) {
        // A same-directory hard link is an atomic create-only publication. Unlike ATOMIC_MOVE,
        // it cannot replace an existing target on providers whose move semantics permit replacement.
        Files.createLink(output, partial)
    }

    override fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)

    override fun forceDirectory(path: Path) {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            return // Windows NIO does not expose a supported directory fsync operation.
        }
        FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    }
}
