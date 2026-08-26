package com.ricezhou.vsrqg.shared.adapter.archive

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.application.archive.ArchiveAdapter
import com.ricezhou.vsrqg.shared.application.archive.ArchiveAuthorization
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
import com.ricezhou.vsrqg.shared.application.archive.DailyControlSnapshot
import com.ricezhou.vsrqg.shared.application.archive.MutationCheckResult
import com.ricezhou.vsrqg.shared.application.archive.RuntimeIdentityRef
import com.ricezhou.vsrqg.shared.application.archive.StoredObjectRef
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset.UTC
import java.util.Locale
import org.erdtman.jcs.JsonCanonicalizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration

internal class S3ArchiveAdapter(
    private val gateway: S3Gateway,
    private val objectMapper: ObjectMapper,
    private val timeProvider: TimeProvider,
    private val files: S3ArchiveFileOperations = NioS3ArchiveFileOperations,
) : ArchiveAdapter {
    override val provider: ArchiveProvider = ArchiveProvider.S3_COMPATIBLE

    override fun probe(policy: ArchivePolicy, context: CapabilityProbeContext): List<CapabilityCheck> {
        if (!completeProbeConfiguration(policy) || !SHA256_PATTERN.matches(context.policyFingerprint)) {
            return failedChecks()
        }
        val identity = try {
            gateway.runtimeIdentity(policy.probeTimeout)
        } catch (_: ArchiveUnavailable) {
            return failedChecks()
        }
        if (!validIdentity(identity)) return failedChecks()

        val bucket = requireNotNull(policy.bucket)
        val retention = requireNotNull(policy.retentionPeriod)
        val utcDate = context.checkedAt.atZone(UTC).toLocalDate()
        val validUntil = utcDate.plusDays(1).atStartOfDay(UTC).toInstant()
        val requiredRetainUntil = validUntil.plus(retention)
        val controlPrefix = buildString {
            append(policy.objectPrefix)
            append(CONTROL_NAMESPACE).append('/')
            append(context.policyFingerprint).append('/')
            append(identity.principalFingerprint).append('/')
            append(utcDate).append('/')
        }
        val snapshot = try {
            gateway.controls(
                bucket = bucket,
                targetKey = "${controlPrefix}target.json",
                resultKey = "${controlPrefix}result.json",
                policyFingerprint = context.policyFingerprint,
                identity = identity,
                utcDate = utcDate,
                requiredRetainUntil = requiredRetainUntil,
                validUntil = validUntil,
                timeout = policy.probeTimeout,
            )
        } catch (_: ArchiveUnavailable) {
            return listOf(check("identity", true)) + failedChecks().drop(1)
        }
        return checksFromSnapshot(
            policy,
            context,
            identity,
            utcDate,
            validUntil,
            requiredRetainUntil,
            "${controlPrefix}target.json",
            "${controlPrefix}result.json",
            snapshot,
        )
    }

    override fun archive(
        command: ArchiveCommand,
        policy: ArchivePolicy,
        authorization: ArchiveAuthorization,
    ): ArchiveResult {
        requireArchiveAuthorization(policy, authorization)
        validateCommand(command)
        val bucket = requireNotNull(policy.bucket)
        val retention = requireNotNull(policy.retentionPeriod)
        val prefix = validatePrefix(policy.objectPrefix)
        val archiveControl = verifyArchiveControl(
            policy,
            authorization.report.policyFingerprint,
            timeProvider.now(),
        )
        val sourceSize = expectedIo("Archive source is unavailable") {
            if (!files.isRegularFile(command.source)) throw ArchiveUnavailable("Archive source is unavailable")
            files.size(command.source)
        }
        val sourceDigest = expectedIo("Archive source digest failed") { files.sha256(command.source) }
        if (sourceDigest != command.expectedSha256) {
            throw ArchiveIntegrityFailure("Archive source digest does not match the expected SHA-256")
        }

        val acceptanceId = encodeDynamicId(ACCEPTANCE_ID_DOMAIN, command.acceptanceId)
        val sourceCommit = encodeDynamicId(SOURCE_COMMIT_DOMAIN, command.sourceCommit)
        val sourceArtifactId = encodeDynamicId(SOURCE_ARTIFACT_ID_DOMAIN, command.sourceArtifactId)
        val payloadKey = "$prefix$PAYLOAD_NAMESPACE/$acceptanceId/$sourceCommit/$sourceArtifactId/${command.expectedSha256}.zip"
        requireValidObjectKey(payloadKey)
        val payload = gateway.putFileIfAbsent(
            bucket,
            payloadKey,
            command.source,
            command.expectedSha256,
            policy.operationTimeout,
        )
        requireExactReference(payload, bucket, payloadKey, command.expectedSha256, sourceSize, "payload")
        verifyReadback(payload, command.expectedSha256, policy.operationTimeout)

        val archivedAt = timeProvider.now()
        val requiredRetainUntil = archivedAt.plus(retention)
        val payloadProtection = gateway.headProtection(payload, policy.operationTimeout)
        val actualMode = requireProtection(payloadProtection, requiredRetainUntil, "payload")
        val receipt = ArchiveReceipt(
            acceptanceId = command.acceptanceId,
            sourceArtifactId = command.sourceArtifactId,
            sourceRunId = command.sourceRunId,
            sourceCommit = command.sourceCommit,
            sourceSha256 = command.expectedSha256,
            payload = payload,
            accessOwner = requireNotNull(policy.accessOwner),
            retentionPolicy = retention.toString(),
            immutabilityControl = actualMode,
            policyFingerprint = authorization.report.policyFingerprint,
            capabilityCheckedAt = authorization.report.checkedAt,
            archivedAt = archivedAt,
            verifier = VERIFIER,
            longTerm = true,
        )
        val receiptBytes = canonicalReceiptBytes(receipt)
        val receiptSha256 = sha256(receiptBytes)
        val receiptKey = "$prefix$RECEIPT_NAMESPACE/$receiptSha256.json"
        requireValidObjectKey(receiptKey)
        val receiptObject = gateway.putJsonIfAbsent(
            bucket,
            receiptKey,
            receiptBytes,
            receiptSha256,
            policy.operationTimeout,
        )
        requireExactReference(
            receiptObject,
            bucket,
            receiptKey,
            receiptSha256,
            receiptBytes.size.toLong(),
            "receipt",
        )
        val receiptProtection = gateway.headProtection(receiptObject, policy.operationTimeout)
        val receiptMode = requireProtection(receiptProtection, requiredRetainUntil, "receipt")
        if (receiptMode != actualMode) {
            throw ArchiveUnavailable("Archive receipt protection is not verified")
        }
        val completionIdentity = gateway.runtimeIdentity(policy.probeTimeout)
        if (completionIdentity != archiveControl.identity) {
            throw ArchiveUnavailable("S3 archive identity changed before completion")
        }
        val completedAt = timeProvider.now()
        if (completedAt >= archiveControl.validUntil) {
            throw ArchiveUnavailable("S3 archive control expired before completion")
        }
        return ArchiveResult(
            receipt,
            ArchiveReceiptReference(receiptObject.locator, receiptObject.versionId, receiptObject.sha256),
        )
    }

    private fun verifyArchiveControl(
        policy: ArchivePolicy,
        policyFingerprint: String,
        startedAt: Instant,
    ): ArchiveControlBinding {
        val identity = gateway.runtimeIdentity(policy.probeTimeout)
        if (!validIdentity(identity)) {
            throw ArchiveUnavailable("S3 archive identity is not verified")
        }
        val bucket = requireNotNull(policy.bucket)
        val utcDate = startedAt.atZone(UTC).toLocalDate()
        val validUntil = utcDate.plusDays(1).atStartOfDay(UTC).toInstant()
        val requiredRetainUntil = validUntil.plus(requireNotNull(policy.retentionPeriod))
        val controlPrefix = buildString {
            append(policy.objectPrefix)
            append(CONTROL_NAMESPACE).append('/')
            append(policyFingerprint).append('/')
            append(identity.principalFingerprint).append('/')
            append(utcDate).append('/')
        }
        val targetKey = "${controlPrefix}target.json"
        val resultKey = "${controlPrefix}result.json"
        val snapshot = gateway.controls(
            bucket = bucket,
            targetKey = targetKey,
            resultKey = resultKey,
            policyFingerprint = policyFingerprint,
            identity = identity,
            utcDate = utcDate,
            requiredRetainUntil = requiredRetainUntil,
            validUntil = validUntil,
            timeout = policy.probeTimeout,
        )
        val checks = checksFromSnapshot(
            policy,
            CapabilityProbeContext(policyFingerprint, startedAt),
            identity,
            utcDate,
            validUntil,
            requiredRetainUntil,
            targetKey,
            resultKey,
            snapshot,
        )
        if (checks.any { !it.passed }) {
            throw ArchiveUnavailable("S3 archive control is not verified")
        }
        return ArchiveControlBinding(identity, validUntil)
    }

    private fun checksFromSnapshot(
        policy: ArchivePolicy,
        context: CapabilityProbeContext,
        identity: RuntimeIdentityRef,
        utcDate: LocalDate,
        validUntil: Instant,
        requiredRetainUntil: Instant,
        targetKey: String,
        resultKey: String,
        snapshot: S3ControlSnapshot,
    ): List<CapabilityCheck> {
        val dailyValid = try {
            validDailyControl(
                snapshot.dailyControl,
                requireNotNull(policy.bucket),
                targetKey,
                resultKey,
                context.policyFingerprint,
                identity,
                utcDate,
                validUntil,
            )
        } catch (_: ArchiveUnavailable) {
            false
        }
        val protection = snapshot.controlObjectProtection
        val protected = protection?.actualMode == APPROVED_PROTECTION_MODE &&
            protection?.retainUntil?.let { it >= requiredRetainUntil } == true
        val immutable = snapshot.objectLockEnabled &&
            protected && dailyValid && mutationsDenied(snapshot.dailyControl)
        val retention = snapshot.objectLockEnabled &&
            protected &&
            dailyValid &&
            snapshot.defaultRetentionDays?.let { it >= ceilDays(requireNotNull(policy.retentionPeriod)) } == true
        return listOf(
            check("identity", true),
            check("connection", snapshot.reachable),
            check("encryption", !policy.encryptionRequired || snapshot.encrypted),
            check("privateAccess", !policy.privateAccessRequired || snapshot.privateAccess),
            check("versioning", snapshot.versioningEnabled),
            check("immutability", immutable),
            check("retention", retention),
        )
    }

    private fun validDailyControl(
        daily: DailyControlSnapshot?,
        bucket: String,
        targetKey: String,
        resultKey: String,
        policyFingerprint: String,
        identity: RuntimeIdentityRef,
        utcDate: LocalDate,
        validUntil: Instant,
    ): Boolean {
        daily ?: return false
        val record = daily.record
        val target = record.target
        val canonical = canonicalDailyControlRecordBytes(objectMapper, record)
        val result = daily.resultReference
        return record.policyFingerprint == policyFingerprint &&
            record.identity == identity &&
            record.utcDate == utcDate &&
            record.validUntil == validUntil &&
            exactReference(target, bucket, targetKey, CONTROL_TARGET_SHA256, CONTROL_TARGET_BYTES.size.toLong()) &&
            exactReference(result, bucket, resultKey, sha256(canonical), canonical.size.toLong())
    }

    private fun mutationsDenied(daily: DailyControlSnapshot?): Boolean = daily?.record?.let { record ->
        record.overwrite == MutationCheckResult.DENIED_AS_EXPECTED &&
            record.delete == MutationCheckResult.DENIED_AS_EXPECTED &&
            record.bypass == MutationCheckResult.DENIED_AS_EXPECTED
    } == true

    private fun completeProbeConfiguration(policy: ArchivePolicy): Boolean =
        policy.provider == provider &&
            !policy.bucket.isNullOrBlank() &&
            !policy.accessOwner.isNullOrBlank() &&
            policy.retentionPeriod?.isPositive() == true &&
            validPrefix(policy.objectPrefix)

    private fun requireArchiveAuthorization(policy: ArchivePolicy, authorization: ArchiveAuthorization) {
        val report = authorization.report
        val valid = policy.provider == provider &&
            policy.enabled &&
            !policy.bucket.isNullOrBlank() &&
            !policy.accessOwner.isNullOrBlank() &&
            policy.retentionPeriod?.isPositive() == true &&
            report.mode == policy.mode &&
            report.provider == provider &&
            report.state == ArchiveCapabilityState.EXTERNAL_VERIFIED &&
            SHA256_PATTERN.matches(report.policyFingerprint) &&
            report.checks.map { it.name } == CHECK_NAMES &&
            report.checks.all(CapabilityCheck::passed)
        if (!valid) throw ArchiveUnavailable("S3 archive authorization is not valid for the active policy")
    }

    private fun validateCommand(command: ArchiveCommand) {
        if (!SHA256_PATTERN.matches(command.expectedSha256)) {
            throw ArchiveIntegrityFailure("Archive expected SHA-256 is invalid")
        }
        listOf(command.acceptanceId, command.sourceCommit, command.sourceArtifactId).forEach { value ->
            if (!SAFE_DYNAMIC_ID.matches(value) || value == "." || value == "..") {
                throw ArchiveUnavailable("Archive command contains an invalid identifier")
            }
        }
        if (command.sourceRunId.isBlank() || command.sourceRunId.toByteArray(UTF_8).size > MAX_RECEIPT_ID_BYTES) {
            throw ArchiveUnavailable("Archive command contains an invalid identifier")
        }
    }

    private fun verifyReadback(payload: StoredObjectRef, expectedSha256: String, timeout: Duration) {
        val target = expectedIo("Archive download target is unavailable") { files.createDownloadTarget() }
        var primary: Throwable? = null
        try {
            expectedIo("Archive download target is unavailable") { files.prepareDownloadTarget(target) }
            gateway.download(payload, target, timeout)
            val actual = expectedIo("Archive payload readback failed") { files.sha256(target) }
            if (actual != expectedSha256) {
                throw ArchiveIntegrityFailure("Archive payload readback does not match the expected SHA-256")
            }
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            try {
                files.deleteIfExists(target)
            } catch (_: IOException) {
                handleCleanupFailure(primary)
            } catch (_: SecurityException) {
                handleCleanupFailure(primary)
            }
        }
    }

    private fun handleCleanupFailure(primary: Throwable?) {
        val cleanup = ArchiveUnavailable("Archive download cleanup failed")
        if (primary == null) throw cleanup
        primary.addSuppressed(cleanup)
    }

    private fun requireProtection(
        protection: ObjectProtectionSnapshot,
        requiredRetainUntil: Instant,
        kind: String,
    ): String {
        val mode = protection.actualMode
        if (mode != APPROVED_PROTECTION_MODE ||
            protection.retainUntil?.let { it >= requiredRetainUntil } != true
        ) {
            throw ArchiveUnavailable("Archive $kind protection is not verified")
        }
        return mode
    }

    private fun requireExactReference(
        reference: StoredObjectRef,
        bucket: String,
        key: String,
        sha256: String,
        size: Long,
        kind: String,
    ) {
        if (!exactReference(reference, bucket, key, sha256, size)) {
            throw ArchiveIntegrityFailure("Archive $kind reference is not exact")
        }
    }

    private fun exactReference(
        reference: StoredObjectRef,
        bucket: String,
        key: String,
        sha256: String,
        size: Long,
    ): Boolean = reference.provider == provider &&
        reference.bucket == bucket &&
        reference.key == key &&
        reference.locator == "s3://$bucket/$key" &&
        isExactVersion(reference.versionId) &&
        reference.sha256 == sha256 &&
        reference.sizeBytes == size

    private fun canonicalReceiptBytes(receipt: ArchiveReceipt): ByteArray {
        val root = objectMapper.createObjectNode()
        root.put("acceptanceId", receipt.acceptanceId)
        root.put("sourceArtifactId", receipt.sourceArtifactId)
        root.put("sourceRunId", receipt.sourceRunId)
        root.put("sourceCommit", receipt.sourceCommit)
        root.put("sourceSha256", receipt.sourceSha256)
        root.putObject("payload").apply {
            put("provider", receipt.payload.provider.name)
            put("locator", receipt.payload.locator)
            receipt.payload.bucket?.let { put("bucket", it) } ?: putNull("bucket")
            put("key", receipt.payload.key)
            receipt.payload.versionId?.let { put("versionId", it) } ?: putNull("versionId")
            put("sha256", receipt.payload.sha256)
            put("sizeBytes", receipt.payload.sizeBytes)
        }
        root.put("accessOwner", receipt.accessOwner)
        root.put("retentionPolicy", receipt.retentionPolicy)
        root.put("immutabilityControl", receipt.immutabilityControl)
        root.put("policyFingerprint", receipt.policyFingerprint)
        root.put("capabilityCheckedAt", receipt.capabilityCheckedAt.toString())
        root.put("archivedAt", receipt.archivedAt.toString())
        root.put("verifier", receipt.verifier)
        root.put("longTerm", receipt.longTerm)
        return try {
            JsonCanonicalizer(objectMapper.writeValueAsBytes(root)).encodedUTF8
        } catch (_: IOException) {
            throw ArchiveUnavailable("Archive receipt serialization failed")
        }
    }

    private fun validatePrefix(prefix: String): String {
        if (!validPrefix(prefix)) throw ArchiveUnavailable("Archive target prefix is invalid")
        return prefix
    }

    private fun requireValidObjectKey(key: String) {
        val valid = key.toByteArray(UTF_8).size <= MAX_OBJECT_KEY_BYTES &&
            !key.startsWith('/') &&
            '\\' !in key &&
            key.split('/').none { it.isEmpty() || it == "." || it == ".." }
        if (!valid) throw ArchiveUnavailable("Archive object key is invalid")
    }

    private fun validPrefix(prefix: String): Boolean {
        if (prefix.isBlank() || !prefix.endsWith('/') || prefix.startsWith('/') || '\\' in prefix) return false
        val segments = prefix.dropLast(1).split('/')
        return segments.isNotEmpty() && segments.none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun encodeDynamicId(domain: String, value: String): String {
        val digest = MessageDigest.getInstance(SHA_256)
        listOf(domain, value).forEach { field ->
            val bytes = field.toByteArray(UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return hex(digest.digest())
    }

    private fun ceilDays(duration: Duration): Long {
        val completeDays = duration.seconds / SECONDS_PER_DAY
        return completeDays + if (duration.seconds % SECONDS_PER_DAY != 0L || duration.nano != 0) 1 else 0
    }

    private fun validIdentity(identity: RuntimeIdentityRef): Boolean =
        identity.provider == provider && SHA256_PATTERN.matches(identity.principalFingerprint)

    private fun failedChecks(): List<CapabilityCheck> = CHECK_NAMES.map { check(it, false) }

    private fun check(name: String, passed: Boolean) = CapabilityCheck(
        name,
        passed,
        if (passed) DETAIL_VERIFIED else DETAIL_NOT_VERIFIED,
    )

    private fun isExactVersion(versionId: String?): Boolean =
        !versionId.isNullOrBlank() && !versionId.equals("null", ignoreCase = true)

    private inline fun <T> expectedIo(message: String, operation: () -> T): T = try {
        operation()
    } catch (_: IOException) {
        throw ArchiveUnavailable(message)
    } catch (_: SecurityException) {
        throw ArchiveUnavailable(message)
    }

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance(SHA_256).digest(bytes))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }

    private data class ArchiveControlBinding(
        val identity: RuntimeIdentityRef,
        val validUntil: Instant,
    )

    private companion object {
        const val SHA_256 = "SHA-256"
        const val PAYLOAD_NAMESPACE = "payload"
        const val RECEIPT_NAMESPACE = "receipt"
        const val CONTROL_NAMESPACE = "capability-probe"
        const val ACCEPTANCE_ID_DOMAIN = "vsrqg.archive.s3.path.v1/acceptanceId"
        const val SOURCE_COMMIT_DOMAIN = "vsrqg.archive.s3.path.v1/sourceCommit"
        const val SOURCE_ARTIFACT_ID_DOMAIN = "vsrqg.archive.s3.path.v1/sourceArtifactId"
        const val VERIFIER = "SHA-256"
        const val DETAIL_VERIFIED = "verified"
        const val DETAIL_NOT_VERIFIED = "not verified"
        const val APPROVED_PROTECTION_MODE = "COMPLIANCE"
        const val SECONDS_PER_DAY = 86_400L
        const val MAX_RECEIPT_ID_BYTES = 1024
        const val MAX_OBJECT_KEY_BYTES = 1024
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        val SAFE_DYNAMIC_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$")
        val CONTROL_TARGET_BYTES = "{\"purpose\":\"archive-capability-probe\",\"version\":1}".toByteArray(UTF_8)
        val CONTROL_TARGET_SHA256 = MessageDigest.getInstance(SHA_256)
            .digest(CONTROL_TARGET_BYTES)
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
        val CHECK_NAMES = listOf(
            "identity",
            "connection",
            "encryption",
            "privateAccess",
            "versioning",
            "immutability",
            "retention",
        )
    }
}

internal interface S3ArchiveFileOperations {
    fun createDownloadTarget(): Path
    fun prepareDownloadTarget(path: Path)
    fun isRegularFile(path: Path): Boolean
    fun size(path: Path): Long
    fun sha256(path: Path): String
    fun deleteIfExists(path: Path)
}

internal object NioS3ArchiveFileOperations : S3ArchiveFileOperations {
    override fun createDownloadTarget(): Path = Files.createTempFile("vsrqg-s3-download-", ".partial")

    override fun prepareDownloadTarget(path: Path) {
        Files.delete(path)
    }

    override fun isRegularFile(path: Path): Boolean = Files.isRegularFile(path)
    override fun size(path: Path): Long = Files.size(path)
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

    override fun deleteIfExists(path: Path) {
        Files.deleteIfExists(path)
    }
}

@Configuration(proxyBeanMethods = false)
@Conditional(S3ArchiveConfiguredCondition::class)
internal class S3ArchiveAdapterConfiguration {
    @Bean
    fun s3ArchiveAdapter(
        gateway: S3Gateway,
        objectMapper: ObjectMapper,
        timeProvider: TimeProvider,
    ): ArchiveAdapter = S3ArchiveAdapter(gateway, objectMapper, timeProvider)
}
