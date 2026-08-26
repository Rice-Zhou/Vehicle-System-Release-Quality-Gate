package com.ricezhou.vsrqg.shared.adapter.archive

import com.fasterxml.jackson.databind.ObjectMapper
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
import com.ricezhou.vsrqg.shared.application.archive.StoredObjectRef
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.Locale
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

internal class FilesystemStagingArchiveAdapter internal constructor(
    objectMapper: ObjectMapper,
    private val timeProvider: TimeProvider,
    private val files: ArchiveFileOperations = NioArchiveFileOperations,
) : ArchiveAdapter {
    override val provider: ArchiveProvider = ArchiveProvider.FILESYSTEM_STAGING
    private val receiptMapper = objectMapper.copy()
    private val directoryCreationMonitor = Any()

    override fun probe(policy: ArchivePolicy, context: CapabilityProbeContext): List<CapabilityCheck> {
        val providerConfigured = policy.provider == provider
        val configuredRoot = policy.stagingRoot
        val rootAvailable = providerConfigured &&
            configuredRoot != null &&
            configuredRoot.isAbsolute &&
            runCatching { files.toRealPath(configuredRoot) }
                .map(files::isDirectory)
                .getOrDefault(false)
        val writable = rootAvailable && runCatching {
            files.isWritable(files.toRealPath(requireNotNull(configuredRoot)))
        }.getOrDefault(false)
        val checksum = runCatching { MessageDigest.getInstance(SHA_256) }.isSuccess
        return listOf(
            check("provider", providerConfigured),
            check("stagingRoot", rootAvailable),
            check("writable", writable),
            check("checksum", checksum),
        )
    }

    override fun archive(
        command: ArchiveCommand,
        policy: ArchivePolicy,
        authorization: ArchiveAuthorization,
    ): ArchiveResult {
        requireArchiveAuthorization(policy, authorization)
        validateExpectedDigest(command.expectedSha256)
        val root = resolveRoot(policy)
        val source = resolveSource(command.source, root)
        val targets = resolveTargets(policy.objectPrefix, command, root)
        ensureRealParentWithinRoot(targets.payload.parent, root)

        val payload = commitOrReplayPayload(source, command.expectedSha256, targets, root)
        return commitOrReplayReceipt(command, policy, authorization, payload, targets, root)
    }

    private fun requireArchiveAuthorization(policy: ArchivePolicy, authorization: ArchiveAuthorization) {
        val report = authorization.report
        val valid = policy.provider == provider &&
            policy.mode == DeploymentMode.PILOT &&
            report.mode == policy.mode &&
            report.provider == provider &&
            report.state == ArchiveCapabilityState.LOCAL_PILOT
        if (!valid) {
            throw ArchiveUnavailable("Filesystem staging requires a local pilot authorization")
        }
    }

    private fun resolveRoot(policy: ArchivePolicy): Path {
        val configured = policy.stagingRoot
        if (configured == null || !configured.isAbsolute) {
            throw ArchiveUnavailable("Filesystem staging root is unavailable")
        }
        val root = runCatching { files.toRealPath(configured) }
            .getOrElse { throw ArchiveUnavailable("Filesystem staging root is unavailable") }
        if (!files.isDirectory(root) || !files.isWritable(root)) {
            throw ArchiveUnavailable("Filesystem staging root is unavailable")
        }
        return root
    }

    private fun resolveSource(source: Path, root: Path): Path {
        val realSource = runCatching { files.toRealPath(source) }
            .getOrElse { throw ArchiveUnavailable("Archive source is unavailable") }
        if (!realSource.startsWith(root) || !files.isRegularFile(realSource)) {
            throw ArchiveUnavailable("Archive source is outside the configured staging root")
        }
        return realSource
    }

    private fun resolveTargets(prefix: String, command: ArchiveCommand, root: Path): ArchiveTargets {
        val prefixSegments = validatePrefix(prefix)
        val dynamicSegments = listOf(command.acceptanceId, command.sourceCommit, command.sourceArtifactId)
            .map(::validateDynamicSegment)
        val targetDirectory = (prefixSegments + dynamicSegments.dropLast(1))
            .fold(root) { current, segment -> current.resolve(segment) }
            .normalize()
        val artifactId = dynamicSegments.last()
        val payload = targetDirectory.resolve(artifactId).normalize()
        val receipt = targetDirectory.resolve("$artifactId$RECEIPT_SUFFIX").normalize()
        if (!payload.startsWith(root) || !receipt.startsWith(root)) {
            throw ArchiveUnavailable("Archive target escapes the configured staging root")
        }
        return ArchiveTargets(payload, receipt)
    }

    private fun validatePrefix(prefix: String): List<String> {
        if (prefix.isBlank() || prefix.startsWith('/') || WINDOWS_ABSOLUTE.matchesAt(prefix, 0) || '\\' in prefix) {
            throw ArchiveUnavailable("Archive target prefix is invalid")
        }
        val rawSegments = prefix.split('/')
        if (rawSegments.any { it == "." || it == ".." } || rawSegments.dropLast(1).any(String::isEmpty)) {
            throw ArchiveUnavailable("Archive target prefix is invalid")
        }
        val segments = rawSegments.filter(String::isNotEmpty)
        if (segments.isEmpty() || segments.any { !isSafeSegment(it) }) {
            throw ArchiveUnavailable("Archive target prefix is invalid")
        }
        return segments
    }

    private fun validateDynamicSegment(segment: String): String {
        if (!isSafeSegment(segment)) {
            throw ArchiveUnavailable("Archive target contains an invalid path segment")
        }
        return segment
    }

    private fun isSafeSegment(segment: String): Boolean = SAFE_SEGMENT.matches(segment) &&
        segment != "." &&
        segment != ".."

    private fun ensureRealParentWithinRoot(parent: Path, root: Path) {
        if (!parent.startsWith(root)) {
            throw ArchiveUnavailable("Archive target escapes the configured staging root")
        }
        synchronized(directoryCreationMonitor) {
            var current = root
            root.relativize(parent).forEach { segment ->
                requireExistingDirectoryWithinRoot(current, root)
                val candidate = current.resolve(segment).normalize()
                if (!files.existsNoFollow(candidate)) {
                    try {
                        files.createDirectory(candidate)
                    } catch (_: IOException) {
                        if (!files.existsNoFollow(candidate)) {
                            throw ArchiveUnavailable("Archive target directory is unavailable")
                        }
                    }
                }
                requireExistingDirectoryWithinRoot(candidate, root)
                current = candidate
            }
            requireExistingDirectoryWithinRoot(current, root)
        }
    }

    private fun requireExistingDirectoryWithinRoot(directory: Path, root: Path) {
        if (!files.existsNoFollow(directory) || !files.isDirectoryNoFollow(directory)) {
            throw ArchiveUnavailable("Archive target escapes the configured staging root")
        }
        val realDirectory = runCatching { files.toRealPath(directory) }
            .getOrElse { throw ArchiveUnavailable("Archive target directory is unavailable") }
        if (!realDirectory.startsWith(root) || realDirectory != directory.toAbsolutePath().normalize()) {
            throw ArchiveUnavailable("Archive target escapes the configured staging root")
        }
    }

    private fun commitOrReplayPayload(
        source: Path,
        expectedSha256: String,
        targets: ArchiveTargets,
        root: Path,
    ): StoredObjectRef {
        if (files.exists(targets.payload)) {
            verifySourceDigest(source, expectedSha256)
            verifyExistingPayload(targets.payload, expectedSha256, root)
            return payloadReference(targets.payload, expectedSha256, root)
        }

        var partial: Path? = null
        try {
            partial = files.createTempFile(targets.payload.parent, ".${targets.payload.fileName}-", PARTIAL_SUFFIX)
            files.copy(source, partial)
            val actual = files.sha256(partial)
            if (actual != expectedSha256) {
                throw ArchiveIntegrityFailure("Archive payload digest does not match the expected SHA-256")
            }
            when (commitCreateOnly(partial, targets.payload)) {
                CreateOnlyCommit.COMMITTED -> partial = null
                CreateOnlyCommit.ALREADY_EXISTS -> {
                    verifyExistingPayload(targets.payload, expectedSha256, root)
                }
            }
            return payloadReference(targets.payload, expectedSha256, root)
        } finally {
            partial?.let(files::deleteIfExists)
        }
    }

    private fun verifySourceDigest(source: Path, expectedSha256: String) {
        if (files.sha256(source) != expectedSha256) {
            throw ArchiveIntegrityFailure("Archive payload digest does not match the expected SHA-256")
        }
    }

    private fun verifyExistingPayload(payload: Path, expectedSha256: String, root: Path) {
        ensureCommittedPathWithinRoot(payload, root)
        if (!files.isRegularFile(payload) || files.sha256(payload) != expectedSha256) {
            throw ArchiveIntegrityFailure("Existing archive payload does not match the expected SHA-256")
        }
    }

    private fun payloadReference(payload: Path, sha256: String, root: Path): StoredObjectRef {
        ensureCommittedPathWithinRoot(payload, root)
        return StoredObjectRef(
            provider = provider,
            locator = payload.toUri().toASCIIString(),
            bucket = null,
            key = root.relativize(payload).joinToString("/") { it.toString() },
            versionId = null,
            sha256 = sha256,
            sizeBytes = files.size(payload),
        )
    }

    private fun commitOrReplayReceipt(
        command: ArchiveCommand,
        policy: ArchivePolicy,
        authorization: ArchiveAuthorization,
        payload: StoredObjectRef,
        targets: ArchiveTargets,
        root: Path,
    ): ArchiveResult {
        val candidate = ArchiveReceipt(
            acceptanceId = command.acceptanceId,
            sourceArtifactId = command.sourceArtifactId,
            sourceRunId = command.sourceRunId,
            sourceCommit = command.sourceCommit,
            sourceSha256 = command.expectedSha256,
            payload = payload,
            accessOwner = policy.accessOwner ?: LOCAL_ACCESS_OWNER,
            retentionPolicy = PILOT_RETENTION,
            immutabilityControl = NO_IMMUTABILITY,
            policyFingerprint = authorization.report.policyFingerprint,
            capabilityCheckedAt = authorization.report.checkedAt,
            archivedAt = timeProvider.now(),
            verifier = FILESYSTEM_VERIFIER,
            longTerm = false,
        )
        if (files.exists(targets.receipt)) {
            return replayReceipt(targets.receipt, candidate, root)
        }

        var partial: Path? = null
        try {
            partial = files.createTempFile(targets.receipt.parent, ".${targets.receipt.fileName}-", PARTIAL_SUFFIX)
            files.write(partial, receiptMapper.writeValueAsBytes(candidate))
            when (commitCreateOnly(partial, targets.receipt)) {
                CreateOnlyCommit.COMMITTED -> partial = null
                CreateOnlyCommit.ALREADY_EXISTS -> {
                    return replayReceipt(targets.receipt, candidate, root)
                }
            }
            return result(candidate, targets.receipt, root)
        } finally {
            partial?.let(files::deleteIfExists)
        }
    }

    private fun replayReceipt(receiptPath: Path, candidate: ArchiveReceipt, root: Path): ArchiveResult {
        ensureCommittedPathWithinRoot(receiptPath, root)
        val existing = runCatching {
            receiptMapper.readValue(files.read(receiptPath), ArchiveReceipt::class.java)
        }.getOrElse { throw ArchiveIntegrityFailure("Existing archive receipt is not replayable") }
        if (!sameReceiptIdentity(existing, candidate)) {
            throw ArchiveIntegrityFailure("Existing archive receipt is not replayable")
        }
        return result(existing, receiptPath, root)
    }

    private fun sameReceiptIdentity(existing: ArchiveReceipt, candidate: ArchiveReceipt): Boolean =
        existing.copy(
            capabilityCheckedAt = candidate.capabilityCheckedAt,
            archivedAt = candidate.archivedAt,
        ) == candidate

    private fun result(receipt: ArchiveReceipt, receiptPath: Path, root: Path): ArchiveResult {
        ensureCommittedPathWithinRoot(receiptPath, root)
        return ArchiveResult(
            receipt = receipt,
            receiptReference = ArchiveReceiptReference(
                locator = receiptPath.toUri().toASCIIString(),
                versionId = null,
                sha256 = files.sha256(receiptPath),
            ),
        )
    }

    private fun ensureCommittedPathWithinRoot(path: Path, root: Path) {
        val realPath = runCatching { files.toRealPath(path) }
            .getOrElse { throw ArchiveUnavailable("Archive target is unavailable") }
        if (!realPath.startsWith(root) || realPath != path.toAbsolutePath().normalize()) {
            throw ArchiveUnavailable("Archive target escapes the configured staging root")
        }
    }

    private fun commitCreateOnly(partial: Path, target: Path): CreateOnlyCommit = try {
        files.commitCreateOnly(partial, target)
        CreateOnlyCommit.COMMITTED
    } catch (error: IOException) {
        if (!files.exists(target)) throw error
        CreateOnlyCommit.ALREADY_EXISTS
    }

    private fun validateExpectedDigest(expectedSha256: String) {
        if (!SHA256_PATTERN.matches(expectedSha256)) {
            throw ArchiveIntegrityFailure("Archive expected SHA-256 is invalid")
        }
    }

    private fun check(name: String, passed: Boolean) = CapabilityCheck(
        name = name,
        passed = passed,
        detail = if (passed) "verified" else "not verified",
    )

    private data class ArchiveTargets(val payload: Path, val receipt: Path)

    private enum class CreateOnlyCommit {
        COMMITTED,
        ALREADY_EXISTS,
    }

    private companion object {
        const val SHA_256 = "SHA-256"
        const val PARTIAL_SUFFIX = ".partial"
        const val RECEIPT_SUFFIX = "-archive-receipt.json"
        const val LOCAL_ACCESS_OWNER = "LOCAL_PILOT"
        const val PILOT_RETENTION = "PILOT_ONLY"
        const val NO_IMMUTABILITY = "NONE"
        const val FILESYSTEM_VERIFIER = "SHA-256"
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        val SAFE_SEGMENT = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$")
        val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[/\\\\]")
    }
}

internal interface ArchiveFileOperations {
    fun toRealPath(path: Path): Path
    fun isDirectory(path: Path): Boolean
    fun isDirectoryNoFollow(path: Path): Boolean
    fun isRegularFile(path: Path): Boolean
    fun isWritable(path: Path): Boolean
    fun existsNoFollow(path: Path): Boolean
    fun createDirectory(path: Path)
    fun createTempFile(directory: Path, prefix: String, suffix: String): Path
    fun copy(source: Path, target: Path)
    fun sha256(path: Path): String
    fun commitCreateOnly(source: Path, target: Path)
    fun write(path: Path, bytes: ByteArray)
    fun read(path: Path): ByteArray
    fun size(path: Path): Long
    fun exists(path: Path): Boolean
    fun deleteIfExists(path: Path)
}

internal object NioArchiveFileOperations : ArchiveFileOperations {
    override fun toRealPath(path: Path): Path = path.toRealPath()
    override fun isDirectory(path: Path): Boolean = Files.isDirectory(path)
    override fun isDirectoryNoFollow(path: Path): Boolean = Files.isDirectory(path, NOFOLLOW_LINKS)
    override fun isRegularFile(path: Path): Boolean = Files.isRegularFile(path)
    override fun isWritable(path: Path): Boolean = Files.isWritable(path)
    override fun existsNoFollow(path: Path): Boolean = Files.exists(path, NOFOLLOW_LINKS)
    override fun createDirectory(path: Path) {
        Files.createDirectory(path)
    }

    override fun createTempFile(directory: Path, prefix: String, suffix: String): Path =
        Files.createTempFile(directory, prefix, suffix)

    override fun copy(source: Path, target: Path) {
        Files.copy(source, target, REPLACE_EXISTING)
    }

    override fun sha256(path: Path): String = Files.newInputStream(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }

    override fun commitCreateOnly(source: Path, target: Path) {
        try {
            Files.createLink(target, source)
        } catch (_: UnsupportedOperationException) {
            throw ArchiveUnavailable("Create-only filesystem commit is unavailable")
        }
        Files.delete(source)
    }

    override fun write(path: Path, bytes: ByteArray) {
        Files.write(path, bytes, WRITE, TRUNCATE_EXISTING)
    }

    override fun read(path: Path): ByteArray = Files.readAllBytes(path)
    override fun size(path: Path): Long = Files.size(path)
    override fun exists(path: Path): Boolean = Files.exists(path)
    override fun deleteIfExists(path: Path) {
        Files.deleteIfExists(path)
    }
}

@Configuration(proxyBeanMethods = false)
internal class FilesystemStagingArchiveConfiguration {
    @Bean
    fun filesystemStagingArchiveAdapter(
        objectMapper: ObjectMapper,
        timeProvider: TimeProvider,
    ): ArchiveAdapter = FilesystemStagingArchiveAdapter(objectMapper, timeProvider)

    @Bean
    fun archiveEvidence(
        policy: ArchivePolicy,
        evaluator: EvaluateArchiveCapability,
        adapters: List<ArchiveAdapter>,
    ): ArchiveEvidence = ArchiveEvidence(policy, evaluator, adapters)
}
