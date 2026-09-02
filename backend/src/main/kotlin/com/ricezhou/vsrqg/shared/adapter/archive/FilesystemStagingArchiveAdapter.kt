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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
    private val ownedOrphanPartials = ConcurrentHashMap.newKeySet<Path>()

    override fun probe(policy: ArchivePolicy, context: CapabilityProbeContext): List<CapabilityCheck> {
        val providerConfigured = policy.provider == provider
        val configuredRoot = policy.stagingRoot
        val realRoot = if (providerConfigured && configuredRoot?.isAbsolute == true) {
            probeExpectedFailure { files.toRealPath(configuredRoot) }
        } else {
            null
        }
        val rootAvailable = realRoot != null && probeExpectedFailure { files.isDirectory(realRoot) } == true
        val writable = rootAvailable && probeExpectedFailure { files.isWritable(requireNotNull(realRoot)) } == true
        val checksum = try {
            MessageDigest.getInstance(SHA_256)
            true
        } catch (_: NoSuchAlgorithmException) {
            false
        }
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
        cleanupOwnedOrphans()
        val root = resolveRoot(policy)
        val source = resolveSource(command.source, root)
        val targets = resolveTargets(policy.objectPrefix, command, root)
        ensureRealParentWithinRoot(targets.payload.parent, root)
        ensureRealParentWithinRoot(targets.receipt.parent, root)

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
        val root = expectedIo("Filesystem staging root is unavailable") { files.toRealPath(configured) }
        if (!expectedIo("Filesystem staging root is unavailable") { files.isDirectory(root) } ||
            !expectedIo("Filesystem staging root is unavailable") { files.isWritable(root) }
        ) {
            throw ArchiveUnavailable("Filesystem staging root is unavailable")
        }
        return root
    }

    private fun resolveSource(source: Path, root: Path): Path {
        val realSource = expectedIo("Archive source is unavailable") { files.toRealPath(source) }
        if (!realSource.startsWith(root) ||
            !expectedIo("Archive source is unavailable") { files.isRegularFile(realSource) }
        ) {
            throw ArchiveUnavailable("Archive source is outside the configured staging root")
        }
        return realSource
    }

    private fun resolveTargets(prefix: String, command: ArchiveCommand, root: Path): ArchiveTargets {
        val prefixSegments = validatePrefix(prefix)
        val acceptanceId = encodeDynamicId(ACCEPTANCE_ID_DOMAIN, validateDynamicSegment(command.acceptanceId))
        val sourceCommit = encodeDynamicId(SOURCE_COMMIT_DOMAIN, validateDynamicSegment(command.sourceCommit))
        val sourceArtifactId = encodeDynamicId(SOURCE_ARTIFACT_ID_DOMAIN, validateDynamicSegment(command.sourceArtifactId))
        val prefixRoot = prefixSegments.fold(root) { current, segment -> current.resolve(segment) }.normalize()
        val payload = prefixRoot.resolve(PAYLOAD_NAMESPACE)
            .resolve(acceptanceId)
            .resolve(sourceCommit)
            .resolve(sourceArtifactId)
            .normalize()
        val receipt = prefixRoot.resolve(RECEIPT_NAMESPACE)
            .resolve(acceptanceId)
            .resolve(sourceCommit)
            .resolve("$sourceArtifactId.json")
            .normalize()
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

    private fun encodeDynamicId(domain: String, value: String): String {
        val digest = try {
            MessageDigest.getInstance(SHA_256)
        } catch (_: NoSuchAlgorithmException) {
            throw ArchiveUnavailable("Archive object naming is unavailable")
        }
        listOf(domain, value).forEach { field ->
            val bytes = field.toByteArray(UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
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
                if (!expectedIo("Archive target directory is unavailable") { files.existsNoFollow(candidate) }) {
                    try {
                        files.createDirectory(candidate)
                    } catch (_: IOException) {
                        if (!expectedIo("Archive target directory is unavailable") { files.existsNoFollow(candidate) }) {
                            throw ArchiveUnavailable("Archive target directory is unavailable")
                        }
                    } catch (_: SecurityException) {
                        if (!expectedIo("Archive target directory is unavailable") { files.existsNoFollow(candidate) }) {
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
        if (!expectedIo("Archive target directory is unavailable") { files.existsNoFollow(directory) } ||
            !expectedIo("Archive target directory is unavailable") { files.isDirectoryNoFollow(directory) }
        ) {
            throw ArchiveUnavailable("Archive target escapes the configured staging root")
        }
        val realDirectory = expectedIo("Archive target directory is unavailable") { files.toRealPath(directory) }
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
        if (expectedIo("Archive payload lookup failed") { files.exists(targets.payload) }) {
            verifySourceDigest(source, expectedSha256)
            verifyExistingPayload(targets.payload, expectedSha256, root)
            return payloadReference(targets.payload, expectedSha256, root)
        }

        return withOwnedPartial(
            targets.payload.parent,
            ".${targets.payload.fileName}-",
            "Archive payload partial creation failed",
        ) { partial ->
            expectedIo("Archive payload copy failed") { files.copy(source, partial) }
            val actual = expectedIo("Archive payload digest failed") { files.sha256(partial) }
            if (actual != expectedSha256) {
                throw ArchiveIntegrityFailure("Archive payload digest does not match the expected SHA-256")
            }
            when (commitCreateOnly(partial, targets.payload, "Archive payload commit failed")) {
                CreateOnlyCommit.COMMITTED -> Unit
                CreateOnlyCommit.ALREADY_EXISTS -> {
                    verifyExistingPayload(targets.payload, expectedSha256, root)
                }
            }
            payloadReference(targets.payload, expectedSha256, root)
        }
    }

    private fun verifySourceDigest(source: Path, expectedSha256: String) {
        if (expectedIo("Archive source digest failed") { files.sha256(source) } != expectedSha256) {
            throw ArchiveIntegrityFailure("Archive payload digest does not match the expected SHA-256")
        }
    }

    private fun verifyExistingPayload(payload: Path, expectedSha256: String, root: Path) {
        ensureCommittedPathWithinRoot(payload, root)
        if (!expectedIo("Existing archive payload is unavailable") { files.isRegularFile(payload) } ||
            expectedIo("Existing archive payload digest failed") { files.sha256(payload) } != expectedSha256
        ) {
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
            sizeBytes = expectedIo("Archive payload reference failed") { files.size(payload) },
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
        if (expectedIo("Archive receipt lookup failed") { files.exists(targets.receipt) }) {
            return resultFromCommittedReceipt(targets.receipt, candidate, root)
        }

        return withOwnedPartial(
            targets.receipt.parent,
            ".${targets.receipt.fileName}-",
            "Archive receipt partial creation failed",
        ) { partial ->
            val serialized = expectedIo("Archive receipt serialization failed") {
                receiptMapper.writeValueAsBytes(candidate)
            }
            expectedIo("Archive receipt write failed") { files.write(partial, serialized) }
            when (commitCreateOnly(partial, targets.receipt, "Archive receipt commit failed")) {
                CreateOnlyCommit.COMMITTED -> Unit
                CreateOnlyCommit.ALREADY_EXISTS -> {
                    return@withOwnedPartial resultFromCommittedReceipt(targets.receipt, candidate, root)
                }
            }
            resultFromCommittedReceipt(targets.receipt, candidate, root)
        }
    }

    private fun resultFromCommittedReceipt(
        receiptPath: Path,
        candidate: ArchiveReceipt,
        root: Path,
    ): ArchiveResult {
        val snapshot = committedReceiptSnapshot(receiptPath, root)
        val existing = try {
            receiptMapper.readValue(snapshot.bytes, ArchiveReceipt::class.java)
        } catch (_: IOException) {
            throw ArchiveIntegrityFailure("Existing archive receipt is not replayable")
        } catch (_: SecurityException) {
            throw ArchiveIntegrityFailure("Existing archive receipt is not replayable")
        }
        if (!sameReceiptIdentity(existing, candidate)) {
            throw ArchiveIntegrityFailure("Existing archive receipt is not replayable")
        }
        return result(existing, receiptPath, snapshot)
    }

    private fun sameReceiptIdentity(existing: ArchiveReceipt, candidate: ArchiveReceipt): Boolean =
        existing.copy(
            capabilityCheckedAt = candidate.capabilityCheckedAt,
            archivedAt = candidate.archivedAt,
        ) == candidate

    private fun result(
        receipt: ArchiveReceipt,
        receiptPath: Path,
        snapshot: CommittedReceiptSnapshot,
    ): ArchiveResult = ArchiveResult(
        receipt = receipt,
        receiptReference = ArchiveReceiptReference(
            locator = receiptPath.toUri().toASCIIString(),
            versionId = null,
            sha256 = snapshot.sha256,
            sizeBytes = snapshot.sizeBytes,
        ),
        runtimeIdentity = null,
    )

    private fun committedReceiptSnapshot(receiptPath: Path, root: Path): CommittedReceiptSnapshot {
        // LOCAL_PILOT assumes a trusted single writer. These checks detect non-cooperative changes but cannot
        // provide atomic immutability or ABA guarantees; Company evidence requires a versioned immutable provider.
        ensureCommittedPathWithinRoot(receiptPath, root)
        val before = expectedIo("Archive receipt reference failed") { files.attributesNoFollow(receiptPath) }
        requireStableCommittedReceipt(before)
        val bytes = expectedIo("Archive receipt reference failed") { files.read(receiptPath) }
        val after = expectedIo("Archive receipt reference failed") { files.attributesNoFollow(receiptPath) }
        ensureCommittedPathWithinRoot(receiptPath, root)
        requireStableCommittedReceipt(after)
        if (after != before || bytes.size.toLong() != before.sizeBytes) {
            throw ArchiveIntegrityFailure("Committed archive receipt changed during read")
        }
        return CommittedReceiptSnapshot(
            bytes = bytes,
            sha256 = sha256(bytes),
            sizeBytes = bytes.size.toLong(),
        )
    }

    private fun requireStableCommittedReceipt(attributes: ArchiveFileAttributes) {
        if (!attributes.regularFile || attributes.symbolicLink) {
            throw ArchiveIntegrityFailure("Committed archive receipt is not a regular file")
        }
        if (attributes.fileKey == null) {
            throw ArchiveIntegrityFailure("Committed archive receipt file identity is unavailable")
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance(SHA_256)
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    private fun ensureCommittedPathWithinRoot(path: Path, root: Path) {
        val realPath = expectedIo("Archive target is unavailable") { files.toRealPath(path) }
        if (!realPath.startsWith(root) || realPath != path.toAbsolutePath().normalize()) {
            throw ArchiveUnavailable("Archive target escapes the configured staging root")
        }
    }

    private fun commitCreateOnly(partial: Path, target: Path, failureMessage: String): CreateOnlyCommit = try {
        files.linkCreateOnly(partial, target)
        CreateOnlyCommit.COMMITTED
    } catch (_: IOException) {
        if (!expectedIo(failureMessage) { files.exists(target) }) throw ArchiveUnavailable(failureMessage)
        CreateOnlyCommit.ALREADY_EXISTS
    } catch (_: SecurityException) {
        throw ArchiveUnavailable(failureMessage)
    }

    private inline fun <T> withOwnedPartial(
        directory: Path,
        prefix: String,
        creationFailureMessage: String,
        operation: (Path) -> T,
    ): T {
        val partial = expectedIo(creationFailureMessage) {
            files.createTempFile(directory, prefix, PARTIAL_SUFFIX)
        }
        val result = try {
            operation(partial)
        } catch (error: Exception) {
            cleanupOwnedPartial(partial, error)
            throw error
        } catch (error: Error) {
            cleanupOwnedPartial(partial, error)
            throw error
        }
        cleanupOwnedPartial(partial, null)
        return result
    }

    private fun cleanupOwnedPartial(partial: Path, primary: Throwable?) {
        try {
            files.deleteIfExists(partial)
            ownedOrphanPartials.remove(partial)
        } catch (_: IOException) {
            recordCleanupFailure(partial, primary)
        } catch (_: SecurityException) {
            recordCleanupFailure(partial, primary)
        }
    }

    private fun recordCleanupFailure(partial: Path, primary: Throwable?) {
        ownedOrphanPartials.add(partial)
        val cleanupFailure = ArchiveUnavailable("Archive partial cleanup failed")
        if (primary == null) {
            throw cleanupFailure
        }
        primary.addSuppressed(cleanupFailure)
    }

    private fun cleanupOwnedOrphans() {
        // Collection.toList() may trust a stale concurrent size and call next() after the final element disappears.
        val ownedAtTraversal = mutableListOf<Path>()
        val iterator = ownedOrphanPartials.iterator()
        while (iterator.hasNext()) {
            ownedAtTraversal.add(iterator.next())
        }
        ownedAtTraversal.forEach { partial ->
            if (!ownedOrphanPartials.remove(partial)) return@forEach
            try {
                files.deleteIfExists(partial)
            } catch (_: IOException) {
                ownedOrphanPartials.add(partial)
                throw ArchiveUnavailable("Archive partial cleanup failed")
            } catch (_: SecurityException) {
                ownedOrphanPartials.add(partial)
                throw ArchiveUnavailable("Archive partial cleanup failed")
            }
        }
    }

    private inline fun <T> expectedIo(message: String, operation: () -> T): T = try {
        operation()
    } catch (_: IOException) {
        throw ArchiveUnavailable(message)
    } catch (_: SecurityException) {
        throw ArchiveUnavailable(message)
    }

    private inline fun <T> probeExpectedFailure(operation: () -> T): T? = try {
        operation()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
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

    private data class CommittedReceiptSnapshot(
        val bytes: ByteArray,
        val sha256: String,
        val sizeBytes: Long,
    )

    private enum class CreateOnlyCommit {
        COMMITTED,
        ALREADY_EXISTS,
    }

    private companion object {
        const val SHA_256 = "SHA-256"
        const val PARTIAL_SUFFIX = ".partial"
        const val PAYLOAD_NAMESPACE = "payload"
        const val RECEIPT_NAMESPACE = "receipt"
        const val ACCEPTANCE_ID_DOMAIN = "vsrqg.archive.path.v1/acceptanceId"
        const val SOURCE_COMMIT_DOMAIN = "vsrqg.archive.path.v1/sourceCommit"
        const val SOURCE_ARTIFACT_ID_DOMAIN = "vsrqg.archive.path.v1/sourceArtifactId"
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
    fun linkCreateOnly(source: Path, target: Path)
    fun write(path: Path, bytes: ByteArray)
    fun read(path: Path): ByteArray
    fun attributesNoFollow(path: Path): ArchiveFileAttributes
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

    override fun linkCreateOnly(source: Path, target: Path) {
        try {
            Files.createLink(target, source)
        } catch (_: UnsupportedOperationException) {
            throw ArchiveUnavailable("Create-only filesystem commit is unavailable")
        }
    }

    override fun write(path: Path, bytes: ByteArray) {
        Files.write(path, bytes, WRITE, TRUNCATE_EXISTING)
    }

    override fun read(path: Path): ByteArray = Files.newByteChannel(
        path,
        setOf<OpenOption>(READ, NOFOLLOW_LINKS),
    ).use { channel ->
        val output = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = channel.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer.array(), 0, read)
            buffer.clear()
        }
        output.toByteArray()
    }

    override fun attributesNoFollow(path: Path): ArchiveFileAttributes {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        return ArchiveFileAttributes(
            regularFile = attributes.isRegularFile,
            symbolicLink = attributes.isSymbolicLink,
            sizeBytes = attributes.size(),
            lastModifiedTime = attributes.lastModifiedTime(),
            fileKey = attributes.fileKey(),
        )
    }

    override fun size(path: Path): Long = Files.size(path)
    override fun exists(path: Path): Boolean = Files.exists(path)
    override fun deleteIfExists(path: Path) {
        Files.deleteIfExists(path)
    }
}

internal data class ArchiveFileAttributes(
    val regularFile: Boolean,
    val symbolicLink: Boolean,
    val sizeBytes: Long,
    val lastModifiedTime: FileTime,
    val fileKey: Any?,
)

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
