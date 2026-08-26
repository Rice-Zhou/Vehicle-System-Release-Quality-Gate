package com.ricezhou.vsrqg.shared.adapter.archive

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.ArchiveUnavailable
import com.ricezhou.vsrqg.shared.application.archive.DailyControlSnapshot
import com.ricezhou.vsrqg.shared.application.archive.DailyControlRecord
import com.ricezhou.vsrqg.shared.application.archive.MutationCheckResult
import com.ricezhou.vsrqg.shared.application.archive.RuntimeIdentityRef
import com.ricezhou.vsrqg.shared.application.archive.StoredObjectRef
import java.nio.file.Path
import java.nio.file.Files
import java.nio.charset.StandardCharsets.UTF_8
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset.UTC
import java.util.Locale
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionRequest
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationRequest
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockRequest
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled
import software.amazon.awssdk.services.s3.model.ObjectLockMode
import software.amazon.awssdk.services.s3.model.ObjectLockRetention
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode
import software.amazon.awssdk.services.s3.model.PutObjectRetentionRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest
import org.erdtman.jcs.JsonCanonicalizer

internal data class ObjectProtectionSnapshot(
    val actualMode: String?,
    val retainUntil: Instant?,
)

internal data class S3ControlSnapshot(
    val reachable: Boolean,
    val encrypted: Boolean,
    val privateAccess: Boolean,
    val versioningEnabled: Boolean,
    val objectLockEnabled: Boolean,
    val defaultRetentionDays: Long?,
    val controlObjectProtection: ObjectProtectionSnapshot?,
    val dailyControl: DailyControlSnapshot?,
)

internal fun interface ProviderIdentityAttestor {
    fun attest(timeout: Duration): ProviderAttestedIdentity
}

internal class ProviderAttestedIdentity(
    internal val partition: String,
    internal val account: String,
    internal val principalKind: String,
    internal val stableResource: String,
) {
    override fun toString(): String = "ProviderAttestedIdentity(<redacted>)"
}

internal interface S3Gateway {
    fun runtimeIdentity(timeout: Duration): RuntimeIdentityRef

    fun controls(
        bucket: String,
        targetKey: String,
        resultKey: String,
        policyFingerprint: String,
        identity: RuntimeIdentityRef,
        utcDate: LocalDate,
        requiredRetainUntil: Instant,
        validUntil: Instant,
        timeout: Duration,
    ): S3ControlSnapshot

    fun putFileIfAbsent(
        bucket: String,
        key: String,
        source: Path,
        sha256: String,
        timeout: Duration,
    ): StoredObjectRef

    fun download(source: StoredObjectRef, target: Path, timeout: Duration)

    fun putJsonIfAbsent(
        bucket: String,
        key: String,
        bytes: ByteArray,
        sha256: String,
        timeout: Duration,
    ): StoredObjectRef

    fun headProtection(source: StoredObjectRef, timeout: Duration): ObjectProtectionSnapshot
}

internal class AwsStsIdentityAttestor(
    private val sts: StsClient,
) : ProviderIdentityAttestor {
    override fun attest(timeout: Duration): ProviderAttestedIdentity {
        val response = try {
            sts.getCallerIdentity(
                GetCallerIdentityRequest.builder()
                    .overrideConfiguration { it.apiCallTimeout(timeout) }
                    .build(),
            )
        } catch (error: SdkException) {
            throw ProviderAttestationFailure(safeAwsErrorCode(error))
        }
        return parseAwsIdentity(
            response.account(),
            response.arn(),
            response.userId(),
        )
    }

    override fun toString(): String = "AwsStsIdentityAttestor(<redacted>)"
}

internal class AwsS3Gateway(
    private val s3: S3Client,
    private val objectMapper: ObjectMapper,
    private val identityAttestor: ProviderIdentityAttestor,
) : S3Gateway {
    override fun runtimeIdentity(timeout: Duration): RuntimeIdentityRef {
        if (timeout.isZero || timeout.isNegative) {
            throw identityUnavailable("INVALID_TIMEOUT")
        }
        val claim = try {
            identityAttestor.attest(timeout)
        } catch (error: ProviderAttestationFailure) {
            throw identityUnavailable(error.code)
        } catch (error: IllegalArgumentException) {
            throw identityUnavailable("INVALID_IDENTITY")
        } catch (error: RuntimeException) {
            throw identityUnavailable("IDENTITY_UNAVAILABLE")
        }
        val fingerprint = try {
            fingerprint(claim)
        } catch (error: IllegalArgumentException) {
            throw identityUnavailable("INVALID_IDENTITY")
        }
        if (!SHA256_PATTERN.matches(fingerprint)) {
            throw identityUnavailable("INVALID_FINGERPRINT")
        }
        return RuntimeIdentityRef(ArchiveProvider.S3_COMPATIBLE, fingerprint)
    }

    override fun controls(
        bucket: String,
        targetKey: String,
        resultKey: String,
        policyFingerprint: String,
        identity: RuntimeIdentityRef,
        utcDate: LocalDate,
        requiredRetainUntil: Instant,
        validUntil: Instant,
        timeout: Duration,
    ): S3ControlSnapshot {
        requireControlBinding(
            bucket,
            targetKey,
            resultKey,
            policyFingerprint,
            identity,
            utcDate,
            requiredRetainUntil,
            validUntil,
            timeout,
        )
        val encryption = sdkCall("controls") {
            s3.getBucketEncryption(
                GetBucketEncryptionRequest.builder()
                    .bucket(bucket)
                    .overrideConfiguration { it.apiCallTimeout(timeout) }
                    .build(),
            )
        }
        val publicAccess = sdkCall("controls") {
            s3.getPublicAccessBlock(
                GetPublicAccessBlockRequest.builder()
                    .bucket(bucket)
                    .overrideConfiguration { it.apiCallTimeout(timeout) }
                    .build(),
            )
        }
        val versioning = sdkCall("controls") {
            s3.getBucketVersioning(
                GetBucketVersioningRequest.builder()
                    .bucket(bucket)
                    .overrideConfiguration { it.apiCallTimeout(timeout) }
                    .build(),
            )
        }
        val lock = sdkCall("controls") {
            s3.getObjectLockConfiguration(
                GetObjectLockConfigurationRequest.builder()
                    .bucket(bucket)
                    .overrideConfiguration { it.apiCallTimeout(timeout) }
                    .build(),
            )
        }
        val privateConfiguration = publicAccess.publicAccessBlockConfiguration()
        val lockConfiguration = lock.objectLockConfiguration()
        val dailyAndProtection = when (
            val claim = claimControlTarget(bucket, targetKey, requiredRetainUntil, timeout)
        ) {
            is ControlTargetClaim.Winner -> createDailyControl(
                bucket,
                targetKey,
                resultKey,
                policyFingerprint,
                identity,
                utcDate,
                requiredRetainUntil,
                validUntil,
                timeout,
                claim.target,
            )
            ControlTargetClaim.Loser -> readDailyControl(
                bucket,
                targetKey,
                resultKey,
                policyFingerprint,
                identity,
                utcDate,
                validUntil,
                timeout,
            )
        }
        return S3ControlSnapshot(
            reachable = true,
            encrypted = encryption.serverSideEncryptionConfiguration()?.rules()?.any {
                it.applyServerSideEncryptionByDefault() != null
            } == true,
            privateAccess = privateConfiguration?.blockPublicAcls() == true &&
                privateConfiguration.ignorePublicAcls() == true &&
                privateConfiguration.blockPublicPolicy() == true &&
                privateConfiguration.restrictPublicBuckets() == true,
            versioningEnabled = versioning.status() == BucketVersioningStatus.ENABLED,
            objectLockEnabled = lockConfiguration?.objectLockEnabled() == ObjectLockEnabled.ENABLED,
            defaultRetentionDays = lockConfiguration?.rule()?.defaultRetention()?.days()?.toLong(),
            controlObjectProtection = dailyAndProtection?.second,
            dailyControl = dailyAndProtection?.first,
        )
    }

    override fun putFileIfAbsent(
        bucket: String,
        key: String,
        source: Path,
        sha256: String,
        timeout: Duration,
    ): StoredObjectRef {
        requireTimeout("putFileIfAbsent", timeout)
        requireDigest("putFileIfAbsent", sha256)
        val size = try {
            Files.size(source)
        } catch (_: IOException) {
            throw operationUnavailable("putFileIfAbsent", "SOURCE_UNAVAILABLE")
        } catch (_: SecurityException) {
            throw operationUnavailable("putFileIfAbsent", "SOURCE_UNAVAILABLE")
        }
        if (fileSha256(source, "putFileIfAbsent") != sha256) {
            throw operationUnavailable("putFileIfAbsent", "DIGEST_MISMATCH")
        }
        return putCreateOnly(
            operation = "putFileIfAbsent",
            bucket = bucket,
            key = key,
            sha256 = sha256,
            size = size,
            timeout = timeout,
            body = RequestBody.fromFile(source),
        )
    }

    override fun download(source: StoredObjectRef, target: Path, timeout: Duration) {
        requireTimeout("download", timeout)
        val exact = requireExactReference("download", source)
        val response = sdkCall("download") {
            s3.getObject(
                GetObjectRequest.builder()
                    .bucket(exact.bucket)
                    .key(exact.key)
                    .versionId(exact.versionId)
                    .overrideConfiguration { it.apiCallTimeout(timeout) }
                    .build(),
                target,
            )
        }
        if (response.versionId() != exact.versionId ||
            response.contentLength() != null && response.contentLength() != exact.sizeBytes
        ) {
            throw operationUnavailable("download", "REFERENCE_MISMATCH")
        }
        if (fileSha256(target, "download") != exact.sha256) {
            throw operationUnavailable("download", "DIGEST_MISMATCH")
        }
    }

    override fun putJsonIfAbsent(
        bucket: String,
        key: String,
        bytes: ByteArray,
        sha256: String,
        timeout: Duration,
    ): StoredObjectRef {
        requireTimeout("putJsonIfAbsent", timeout)
        requireDigest("putJsonIfAbsent", sha256)
        if (sha256(bytes) != sha256) {
            throw operationUnavailable("putJsonIfAbsent", "DIGEST_MISMATCH")
        }
        return putCreateOnly(
            operation = "putJsonIfAbsent",
            bucket = bucket,
            key = key,
            sha256 = sha256,
            size = bytes.size.toLong(),
            timeout = timeout,
            body = RequestBody.fromBytes(bytes),
        )
    }

    override fun headProtection(source: StoredObjectRef, timeout: Duration): ObjectProtectionSnapshot {
        requireTimeout("headProtection", timeout)
        val exact = requireExactReference("headProtection", source)
        val response = sdkCall("headProtection") {
            s3.headObject(
                HeadObjectRequest.builder()
                    .bucket(exact.bucket)
                    .key(exact.key)
                    .versionId(exact.versionId)
                    .overrideConfiguration { it.apiCallTimeout(timeout) }
                    .build(),
            )
        }
        if (response.versionId() != exact.versionId ||
            response.contentLength() != null && response.contentLength() != exact.sizeBytes
        ) {
            throw operationUnavailable("headProtection", "REFERENCE_MISMATCH")
        }
        return ObjectProtectionSnapshot(
            actualMode = response.objectLockModeAsString(),
            retainUntil = response.objectLockRetainUntilDate(),
        )
    }

    override fun toString(): String = "AwsS3Gateway(<redacted>)"

    private fun fingerprint(claim: ProviderAttestedIdentity): String {
        val fields = listOf(
            validateIdentityField("partition", claim.partition, PARTITION_PATTERN),
            validateIdentityField("account", claim.account, STABLE_ACCOUNT_PATTERN),
            validateIdentityField("principalKind", claim.principalKind, PRINCIPAL_KIND_PATTERN),
            validateStableResource(claim.stableResource),
        )
        val canonical = fields.joinToString("") { field ->
            "${field.toByteArray(UTF_8).size}:$field"
        }
        return MessageDigest.getInstance(SHA_256)
            .digest(canonical.toByteArray(UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }

    private fun putCreateOnly(
        operation: String,
        bucket: String,
        key: String,
        sha256: String,
        size: Long,
        timeout: Duration,
        body: RequestBody,
    ): StoredObjectRef {
        if (bucket.isBlank() || key.isBlank()) throw operationUnavailable(operation, "INVALID_TARGET")
        val response = try {
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .ifNoneMatch("*")
                    .metadata(mapOf(SHA256_METADATA to sha256))
                    .overrideConfiguration { it.apiCallTimeout(timeout) }
                    .build(),
                body,
            )
        } catch (error: S3Exception) {
            if (error.statusCode() == PRECONDITION_FAILED && safeAwsErrorCode(error) in CREATE_CONFLICT_CODES) {
                return resolveExistingObject(operation, bucket, key, sha256, size, timeout)
            }
            throw operationUnavailable(operation, safeAwsErrorCode(error))
        } catch (error: SdkException) {
            throw operationUnavailable(operation, safeAwsErrorCode(error))
        }
        val versionId = response.versionId()?.takeIf(::isExactVersionId)
            ?: throw operationUnavailable(operation, "VERSION_REQUIRED")
        return StoredObjectRef(
            provider = ArchiveProvider.S3_COMPATIBLE,
            locator = s3Locator(bucket, key),
            bucket = bucket,
            key = key,
            versionId = versionId,
            sha256 = sha256,
            sizeBytes = size,
        )
    }

    private fun resolveExistingObject(
        operation: String,
        bucket: String,
        key: String,
        expectedSha256: String,
        expectedSize: Long,
        timeout: Duration,
    ): StoredObjectRef {
        val listed = sdkCall(operation) {
            s3.listObjectVersions(
                ListObjectVersionsRequest.builder()
                    .bucket(bucket)
                    .prefix(key)
                    .maxKeys(MAX_RESULT_VERSIONS)
                    .overrideConfiguration { it.apiCallTimeout(timeout) }
                    .build(),
            )
        }
        val exactVersions = listed.versions().filter { it.key() == key && isExactVersionId(it.versionId()) }
        if (listed.isTruncated == true ||
            listed.deleteMarkers().any { it.key() == key } ||
            exactVersions.size != 1
        ) {
            throw operationUnavailable(operation, "CONFLICT_UNVERIFIED")
        }
        val version = exactVersions.single()
        val stream = sdkCall(operation) {
            s3.getObject(
                GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .versionId(version.versionId())
                    .overrideConfiguration { it.apiCallTimeout(timeout) }
                    .build(),
            )
        }
        val actual = try {
            stream.use { input ->
                val digest = MessageDigest.getInstance(SHA_256)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var size = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    size += read
                }
                StreamDigest(hex(digest.digest()), size)
            }
        } catch (_: IOException) {
            throw operationUnavailable(operation, "CONFLICT_UNVERIFIED")
        }
        val response = stream.response()
        val verified = response.versionId() == version.versionId() &&
            actual.sha256 == expectedSha256 &&
            actual.size == expectedSize &&
            (response.contentLength() == null || response.contentLength() == actual.size) &&
            (version.size() == null || version.size() == actual.size)
        if (!verified) throw operationUnavailable(operation, "CONFLICT_UNVERIFIED")
        return StoredObjectRef(
            ArchiveProvider.S3_COMPATIBLE,
            s3Locator(bucket, key),
            bucket,
            key,
            version.versionId(),
            actual.sha256,
            actual.size,
        )
    }

    private fun claimControlTarget(
        bucket: String,
        key: String,
        retainUntil: Instant,
        timeout: Duration,
    ): ControlTargetClaim {
        val response = try {
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .ifNoneMatch("*")
                    .metadata(mapOf(SHA256_METADATA to CONTROL_TARGET_SHA256))
                    .objectLockMode(ObjectLockMode.COMPLIANCE)
                    .objectLockRetainUntilDate(retainUntil)
                    .overrideConfiguration { it.apiCallTimeout(timeout) }
                    .build(),
                RequestBody.fromBytes(CONTROL_TARGET_BYTES),
            )
        } catch (error: S3Exception) {
            if (error.statusCode() == PRECONDITION_FAILED && safeAwsErrorCode(error) in CREATE_CONFLICT_CODES) {
                return ControlTargetClaim.Loser
            }
            throw operationUnavailable("controls", safeAwsErrorCode(error))
        } catch (error: SdkException) {
            throw operationUnavailable("controls", safeAwsErrorCode(error))
        }
        val versionId = response.versionId()?.takeIf(::isExactVersionId)
            ?: throw operationUnavailable("controls", "VERSION_REQUIRED")
        return ControlTargetClaim.Winner(
            StoredObjectRef(
                ArchiveProvider.S3_COMPATIBLE,
                s3Locator(bucket, key),
                bucket,
                key,
                versionId,
                CONTROL_TARGET_SHA256,
                CONTROL_TARGET_BYTES.size.toLong(),
            ),
        )
    }

    private fun createDailyControl(
        bucket: String,
        targetKey: String,
        resultKey: String,
        policyFingerprint: String,
        identity: RuntimeIdentityRef,
        utcDate: LocalDate,
        requiredRetainUntil: Instant,
        validUntil: Instant,
        timeout: Duration,
        target: StoredObjectRef,
    ): Pair<DailyControlSnapshot, ObjectProtectionSnapshot> {
        val protection = headProtection(target, timeout)
        val record = DailyControlRecord(
            policyFingerprint = policyFingerprint,
            identity = identity,
            utcDate = utcDate,
            validUntil = validUntil,
            target = target,
            overwrite = mutationResult {
                s3.putObject(
                    PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(targetKey)
                        .metadata(mapOf(SHA256_METADATA to CONTROL_TARGET_SHA256))
                        .overrideConfiguration { it.apiCallTimeout(timeout) }
                        .build(),
                    RequestBody.fromBytes(CONTROL_TARGET_BYTES),
                )
            },
            delete = mutationResult {
                s3.deleteObject(
                    DeleteObjectRequest.builder()
                        .bucket(bucket)
                        .key(targetKey)
                        .versionId(requireNotNull(target.versionId))
                        .overrideConfiguration { it.apiCallTimeout(timeout) }
                        .build(),
                )
            },
            bypass = mutationResult {
                s3.putObjectRetention(
                    PutObjectRetentionRequest.builder()
                        .bucket(bucket)
                        .key(targetKey)
                        .versionId(requireNotNull(target.versionId))
                        .bypassGovernanceRetention(true)
                        .retention(
                            ObjectLockRetention.builder()
                                .mode(ObjectLockRetentionMode.GOVERNANCE)
                                .retainUntilDate(validUntil)
                                .build(),
                        )
                        .overrideConfiguration { it.apiCallTimeout(timeout) }
                        .build(),
                )
            },
        )
        val recordBytes = canonicalDailyControlRecordBytes(objectMapper, record)
        val resultReference = putControlResult(
            bucket,
            resultKey,
            recordBytes,
            requiredRetainUntil,
            timeout,
        )
        return DailyControlSnapshot(record, resultReference) to protection
    }

    private fun readDailyControl(
        bucket: String,
        targetKey: String,
        resultKey: String,
        policyFingerprint: String,
        identity: RuntimeIdentityRef,
        utcDate: LocalDate,
        validUntil: Instant,
        timeout: Duration,
    ): Pair<DailyControlSnapshot, ObjectProtectionSnapshot>? {
        return try {
            val listed = s3.listObjectVersions(
                ListObjectVersionsRequest.builder()
                    .bucket(bucket)
                    .prefix(resultKey)
                    .maxKeys(MAX_RESULT_VERSIONS)
                    .overrideConfiguration { it.apiCallTimeout(timeout) }
                    .build(),
            )
            val exactVersions = listed.versions().filter { it.key() == resultKey && isExactVersionId(it.versionId()) }
            val hasDeleteMarker = listed.deleteMarkers().any { it.key() == resultKey }
            if (listed.isTruncated == true || hasDeleteMarker || exactVersions.size != 1) return null
            val version = exactVersions.single()
            val responseBytes = s3.getObjectAsBytes(
                GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(resultKey)
                    .versionId(version.versionId())
                    .overrideConfiguration { it.apiCallTimeout(timeout) }
                    .build(),
            )
            val response = responseBytes.response()
            val bytes = responseBytes.asByteArray()
            if (response.versionId() != version.versionId() ||
                response.contentLength() != null && response.contentLength() != bytes.size.toLong() ||
                version.size() != null && version.size() != bytes.size.toLong()
            ) {
                return null
            }
            val record = objectMapper.readValue(bytes, DailyControlRecord::class.java)
            if (!canonicalDailyControlRecordBytes(objectMapper, record).contentEquals(bytes)) return null
            val resultReference = StoredObjectRef(
                ArchiveProvider.S3_COMPATIBLE,
                s3Locator(bucket, resultKey),
                bucket,
                resultKey,
                version.versionId(),
                sha256(bytes),
                bytes.size.toLong(),
            )
            val recordIsValid = validDailyControlRecord(
                record,
                bucket,
                targetKey,
                policyFingerprint,
                identity,
                utcDate,
                validUntil,
            )
            if (!recordIsValid) return null
            DailyControlSnapshot(record, resultReference) to headProtection(record.target, timeout)
        } catch (_: SdkException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ArchiveUnavailable) {
            null
        }
    }

    private fun validDailyControlRecord(
        record: DailyControlRecord,
        bucket: String,
        targetKey: String,
        policyFingerprint: String,
        identity: RuntimeIdentityRef,
        utcDate: LocalDate,
        validUntil: Instant,
    ): Boolean = record.policyFingerprint == policyFingerprint &&
        record.identity == identity &&
        record.utcDate == utcDate &&
        record.validUntil == validUntil &&
        record.target.provider == ArchiveProvider.S3_COMPATIBLE &&
        record.target.bucket == bucket &&
        record.target.key == targetKey &&
        record.target.locator == s3Locator(bucket, targetKey) &&
        isExactVersionId(record.target.versionId) &&
        SHA256_PATTERN.matches(record.target.sha256) &&
        record.target.sizeBytes >= 0

    private fun putControlResult(
        bucket: String,
        key: String,
        bytes: ByteArray,
        retainUntil: Instant,
        timeout: Duration,
    ): StoredObjectRef {
        val digest = sha256(bytes)
        val response = sdkCall("controls") {
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .ifNoneMatch("*")
                    .metadata(mapOf(SHA256_METADATA to digest))
                    .objectLockMode(ObjectLockMode.COMPLIANCE)
                    .objectLockRetainUntilDate(retainUntil)
                    .overrideConfiguration { it.apiCallTimeout(timeout) }
                    .build(),
                RequestBody.fromBytes(bytes),
            )
        }
        val versionId = response.versionId()?.takeIf(::isExactVersionId)
            ?: throw operationUnavailable("controls", "VERSION_REQUIRED")
        return StoredObjectRef(
            ArchiveProvider.S3_COMPATIBLE,
            s3Locator(bucket, key),
            bucket,
            key,
            versionId,
            digest,
            bytes.size.toLong(),
        )
    }

    private fun requireControlBinding(
        bucket: String,
        targetKey: String,
        resultKey: String,
        policyFingerprint: String,
        identity: RuntimeIdentityRef,
        utcDate: LocalDate,
        requiredRetainUntil: Instant,
        validUntil: Instant,
        timeout: Duration,
    ) {
        val binding = "capability-probe/$policyFingerprint/${identity.principalFingerprint}/$utcDate"
        val targetSuffix = "$binding/target.json"
        val resultSuffix = "$binding/result.json"
        val valid = timeout.isPositive() &&
            bucket.isNotBlank() &&
            SHA256_PATTERN.matches(policyFingerprint) &&
            identity.provider == ArchiveProvider.S3_COMPATIBLE &&
            SHA256_PATTERN.matches(identity.principalFingerprint) &&
            safeControlKey(targetKey) &&
            safeControlKey(resultKey) &&
            targetKey.endsWith(targetSuffix) &&
            resultKey.endsWith(resultSuffix) &&
            targetKey.removeSuffix("target.json") == resultKey.removeSuffix("result.json") &&
            validUntil == utcDate.plusDays(1).atStartOfDay(UTC).toInstant() &&
            requiredRetainUntil >= validUntil
        if (!valid) throw operationUnavailable("controls", "INVALID_CONTROL_BINDING")
    }

    private fun safeControlKey(key: String): Boolean = key.isNotBlank() &&
        !key.startsWith('/') &&
        '\\' !in key &&
        key.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private inline fun mutationResult(action: () -> Unit): MutationCheckResult = try {
        action()
        MutationCheckResult.ALLOWED
    } catch (error: S3Exception) {
        val code = safeAwsErrorCode(error)
        if (error.statusCode() in EXPLICIT_DENIAL_STATUS && code in EXPLICIT_DENIAL_CODES) {
            MutationCheckResult.DENIED_AS_EXPECTED
        } else {
            MutationCheckResult.INDETERMINATE
        }
    } catch (_: SdkException) {
        MutationCheckResult.INDETERMINATE
    }

    private fun requireExactReference(operation: String, source: StoredObjectRef): ExactReference {
        val bucket = source.bucket
        val versionId = source.versionId
        val valid = source.provider == ArchiveProvider.S3_COMPATIBLE &&
            !bucket.isNullOrBlank() &&
            source.key.isNotBlank() &&
            isExactVersionId(versionId) &&
            SHA256_PATTERN.matches(source.sha256) &&
            source.sizeBytes >= 0 &&
            source.locator == s3Locator(bucket, source.key)
        if (!valid) throw operationUnavailable(operation, "INVALID_REFERENCE")
        return ExactReference(requireNotNull(bucket), source.key, requireNotNull(versionId), source.sha256, source.sizeBytes)
    }

    private fun isExactVersionId(value: String?): Boolean = !value.isNullOrBlank() &&
        !value.equals("null", ignoreCase = true)

    private fun requireTimeout(operation: String, timeout: Duration) {
        if (timeout.isZero || timeout.isNegative) throw operationUnavailable(operation, "INVALID_TIMEOUT")
    }

    private fun requireDigest(operation: String, digest: String) {
        if (!SHA256_PATTERN.matches(digest)) throw operationUnavailable(operation, "INVALID_DIGEST")
    }

    private fun fileSha256(path: Path, operation: String): String = try {
        Files.newInputStream(path).use { input ->
            val digest = MessageDigest.getInstance(SHA_256)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            hex(digest.digest())
        }
    } catch (_: IOException) {
        throw operationUnavailable(operation, "IO_ERROR")
    } catch (_: SecurityException) {
        throw operationUnavailable(operation, "IO_ERROR")
    }

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance(SHA_256).digest(bytes))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }

    private inline fun <T> sdkCall(operation: String, action: () -> T): T = try {
        action()
    } catch (error: SdkException) {
        throw operationUnavailable(operation, safeAwsErrorCode(error))
    }

    private data class ExactReference(
        val bucket: String,
        val key: String,
        val versionId: String,
        val sha256: String,
        val sizeBytes: Long,
    )

    private data class StreamDigest(val sha256: String, val size: Long)

    private sealed interface ControlTargetClaim {
        data class Winner(val target: StoredObjectRef) : ControlTargetClaim
        data object Loser : ControlTargetClaim
    }

    private fun validateIdentityField(name: String, value: String, pattern: Regex): String {
        require(pattern.matches(value)) { "Invalid provider identity field: $name" }
        return value
    }

    private fun validateStableResource(value: String): String {
        require(value.isNotBlank() && value.toByteArray(UTF_8).size <= MAX_RESOURCE_BYTES) {
            "Invalid provider identity stable resource"
        }
        require(value.none(Char::isISOControl)) { "Invalid provider identity stable resource" }
        return value
    }

    private companion object {
        const val SHA_256 = "SHA-256"
        const val SHA256_METADATA = "sha256"
        val CONTROL_TARGET_BYTES = "{\"purpose\":\"archive-capability-probe\",\"version\":1}".toByteArray(UTF_8)
        val CONTROL_TARGET_SHA256 = MessageDigest.getInstance(SHA_256)
            .digest(CONTROL_TARGET_BYTES)
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
        val EXPLICIT_DENIAL_STATUS = setOf(401, 403)
        val EXPLICIT_DENIAL_CODES = setOf("AccessDenied", "AccessDeniedException", "UnauthorizedOperation")
        val CREATE_CONFLICT_CODES = setOf("PreconditionFailed", "ConditionalRequestConflict")
        const val MAX_RESOURCE_BYTES = 1024
        const val PRECONDITION_FAILED = 412
        const val MAX_RESULT_VERSIONS = 3
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        val PARTITION_PATTERN = Regex("^[a-z0-9-]{1,32}$")
        val STABLE_ACCOUNT_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        val PRINCIPAL_KIND_PATTERN = Regex("^[a-z][a-z0-9-]{0,63}$")
    }
}

private class ProviderAttestationFailure(
    val code: String,
) : RuntimeException()

internal object MissingProviderIdentityAttestor : ProviderIdentityAttestor {
    override fun attest(timeout: Duration): ProviderAttestedIdentity =
        throw ProviderAttestationFailure("IDENTITY_UNAVAILABLE")

    override fun toString(): String = "MissingProviderIdentityAttestor"
}

private fun parseAwsIdentity(account: String?, arn: String?, userId: String?): ProviderAttestedIdentity {
    if (account == null || arn == null || userId == null || !AWS_ACCOUNT_PATTERN.matches(account)) {
        throw IllegalArgumentException("Invalid AWS identity")
    }
    val arnParts = arn.split(':', limit = 6)
    if (arnParts.size != 6 || arnParts[0] != "arn" || arnParts[3].isNotEmpty() || arnParts[4] != account) {
        throw IllegalArgumentException("Invalid AWS identity")
    }
    val partition = arnParts[1]
    val service = arnParts[2]
    val resource = arnParts[5]
    val resourceParts = resource.split('/')
    val principalKind: String
    val stableResource: String
    when {
        service == "sts" && resourceParts.firstOrNull() == "assumed-role" && resourceParts.size >= 3 -> {
            val session = resourceParts.last()
            val userIdParts = userId.split(':', limit = 2)
            if (resourceParts.any(String::isBlank) ||
                userIdParts.size != 2 ||
                !AWS_ROLE_UNIQUE_ID_PATTERN.matches(userIdParts[0]) ||
                userIdParts[1] != session
            ) {
                throw IllegalArgumentException("Invalid AWS identity")
            }
            principalKind = "assumed-role"
            stableResource = resourceParts.dropLast(1).joinToString("/")
        }
        service == "sts" && resourceParts.firstOrNull() == "federated-user" && resourceParts.size >= 2 -> {
            val federatedNamePath = resourceParts.drop(1).joinToString("/")
            if (resourceParts.any(String::isBlank) || userId != "$account:$federatedNamePath") {
                throw IllegalArgumentException("Invalid AWS identity")
            }
            principalKind = "federated-user"
            stableResource = resource
        }
        service == "iam" && resourceParts.firstOrNull() == "user" && resourceParts.size >= 2 -> {
            if (resourceParts.any(String::isBlank) || !AWS_USER_UNIQUE_ID_PATTERN.matches(userId)) {
                throw IllegalArgumentException("Invalid AWS identity")
            }
            principalKind = "user"
            stableResource = resource
        }
        service == "iam" && resource == "root" && userId == account -> {
            principalKind = "root"
            stableResource = resource
        }
        else -> throw IllegalArgumentException("Invalid AWS identity")
    }
    return ProviderAttestedIdentity(partition, account, principalKind, stableResource)
}

private fun identityUnavailable(code: String): ArchiveUnavailable =
    ArchiveUnavailable("S3 operation identity failed (AWS ${sanitizeErrorCode(code)})")

private fun operationUnavailable(operation: String, code: String): ArchiveUnavailable =
    ArchiveUnavailable("S3 operation $operation failed (AWS ${sanitizeErrorCode(code)})")

private fun s3Locator(bucket: String, key: String): String = "s3://$bucket/$key"

private fun safeAwsErrorCode(error: SdkException): String {
    val awsCode = (error as? software.amazon.awssdk.awscore.exception.AwsServiceException)
        ?.awsErrorDetails()
        ?.errorCode()
    return sanitizeErrorCode(awsCode ?: "SDK_CLIENT_ERROR")
}

private fun sanitizeErrorCode(code: String): String =
    code.takeIf(SAFE_ERROR_CODE::matches) ?: "UNKNOWN"

private val AWS_ACCOUNT_PATTERN = Regex("^[0-9]{12}$")
private val AWS_ROLE_UNIQUE_ID_PATTERN = Regex("^AROA[A-Z0-9]{12,124}$")
private val AWS_USER_UNIQUE_ID_PATTERN = Regex("^AIDA[A-Z0-9]{12,124}$")
private val SAFE_ERROR_CODE = Regex("^[A-Za-z0-9._-]{1,64}$")

internal fun canonicalDailyControlRecordBytes(
    objectMapper: ObjectMapper,
    record: DailyControlRecord,
): ByteArray = try {
    JsonCanonicalizer(objectMapper.writeValueAsBytes(record)).encodedUTF8
} catch (_: IOException) {
    throw operationUnavailable("controls", "SERIALIZATION_ERROR")
}
