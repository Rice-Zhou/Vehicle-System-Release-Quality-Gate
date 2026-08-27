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
import java.nio.file.FileAlreadyExistsException
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.charset.StandardCharsets.UTF_8
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset.UTC
import java.util.Locale
import java.util.Collections
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
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

internal class ExactObjectDownload(
    bytes: ByteArray,
    val versionId: String?,
    val eTag: String?,
    val sizeBytes: Long,
    metadata: Map<String, String>,
) {
    private val content = bytes.copyOf()
    val metadata: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(metadata))

    fun bytes(): ByteArray = content.copyOf()
}

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

    fun downloadExact(
        source: StoredObjectRef,
        maxBytes: Long,
        timeout: Duration,
    ): ExactObjectDownload = throw operationUnavailable("downloadExact", "UNAVAILABLE")

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
        val response = sts.getCallerIdentity(
            GetCallerIdentityRequest.builder()
                .overrideConfiguration { it.apiCallTimeout(timeout) }
                .build(),
        )
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
            throw identityUnavailable(error.code.wireCode)
        } catch (error: SdkException) {
            throw identityUnavailable(safeAwsErrorCode(error))
        }
        val fingerprint = try {
            fingerprint(claim)
        } catch (error: ProviderAttestationFailure) {
            throw identityUnavailable(error.code.wireCode)
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
        if (runtimeIdentity(timeout) != identity) {
            throw operationUnavailable("controls", "IDENTITY_MISMATCH")
        }
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
        val snapshot = try {
            Files.createTempFile("vsrqg-s3-upload-", ".snapshot")
        } catch (_: IOException) {
            throw operationUnavailable("putFileIfAbsent", "SNAPSHOT_UNAVAILABLE")
        } catch (_: SecurityException) {
            throw operationUnavailable("putFileIfAbsent", "SNAPSHOT_UNAVAILABLE")
        }
        var primaryFailure: Throwable? = null
        try {
            try {
                Files.copy(source, snapshot, REPLACE_EXISTING)
            } catch (_: IOException) {
                throw operationUnavailable("putFileIfAbsent", "SOURCE_UNAVAILABLE")
            } catch (_: SecurityException) {
                throw operationUnavailable("putFileIfAbsent", "SOURCE_UNAVAILABLE")
            }
            val size = fileSize(snapshot, "putFileIfAbsent", "SNAPSHOT_UNAVAILABLE")
            if (fileSha256(snapshot, "putFileIfAbsent") != sha256) {
                throw operationUnavailable("putFileIfAbsent", "DIGEST_MISMATCH")
            }
            return putCreateOnly(
                operation = "putFileIfAbsent",
                bucket = bucket,
                key = key,
                sha256 = sha256,
                size = size,
                timeout = timeout,
                body = RequestBody.fromFile(snapshot),
            )
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                Files.deleteIfExists(snapshot)
            } catch (_: IOException) {
                if (primaryFailure == null) throw operationUnavailable("putFileIfAbsent", "SNAPSHOT_CLEANUP_FAILED")
            } catch (_: SecurityException) {
                if (primaryFailure == null) throw operationUnavailable("putFileIfAbsent", "SNAPSHOT_CLEANUP_FAILED")
            }
        }
    }

    override fun download(source: StoredObjectRef, target: Path, timeout: Duration) {
        requireTimeout("download", timeout)
        val exact = requireExactReference("download", source)
        val absoluteTarget = target.toAbsolutePath().normalize()
        val targetExists = try {
            Files.exists(absoluteTarget)
        } catch (_: SecurityException) {
            throw operationUnavailable("download", "TARGET_UNAVAILABLE")
        }
        if (targetExists) throw operationUnavailable("download", "TARGET_EXISTS")
        val parent = absoluteTarget.parent ?: throw operationUnavailable("download", "INVALID_TARGET")
        val partial = try {
            Files.createTempFile(parent, ".${absoluteTarget.fileName}.", ".partial")
        } catch (_: IOException) {
            throw operationUnavailable("download", "TARGET_UNAVAILABLE")
        } catch (_: SecurityException) {
            throw operationUnavailable("download", "TARGET_UNAVAILABLE")
        }
        try {
            Files.delete(partial)
        } catch (_: IOException) {
            throw operationUnavailable("download", "TARGET_UNAVAILABLE")
        } catch (_: SecurityException) {
            throw operationUnavailable("download", "TARGET_UNAVAILABLE")
        }
        var primaryFailure: Throwable? = null
        try {
            val request = GetObjectRequest.builder()
                .bucket(exact.bucket)
                .key(exact.key)
                .versionId(exact.versionId)
                .overrideConfiguration { it.apiCallTimeout(timeout) }
                .build()
            val response = try {
                sdkCall("download") {
                    s3.getObject(request, ResponseTransformer.toFile(partial))
                }
            } catch (_: IOException) {
                throw operationUnavailable("download", "IO_ERROR")
            } catch (_: SecurityException) {
                throw operationUnavailable("download", "TARGET_UNAVAILABLE")
            }
            if (response.versionId() != exact.versionId ||
                response.contentLength() != exact.sizeBytes ||
                fileSize(partial, "download", "TARGET_UNAVAILABLE") != exact.sizeBytes
            ) {
                throw operationUnavailable("download", "REFERENCE_MISMATCH")
            }
            if (fileSha256(partial, "download") != exact.sha256) {
                throw operationUnavailable("download", "DIGEST_MISMATCH")
            }
            try {
                Files.createLink(absoluteTarget, partial)
            } catch (_: FileAlreadyExistsException) {
                throw operationUnavailable("download", "TARGET_EXISTS")
            } catch (_: UnsupportedOperationException) {
                throw operationUnavailable("download", "ATOMIC_PUBLISH_UNAVAILABLE")
            } catch (_: IOException) {
                throw operationUnavailable("download", "PUBLISH_FAILED")
            } catch (_: SecurityException) {
                throw operationUnavailable("download", "PUBLISH_FAILED")
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                Files.deleteIfExists(partial)
            } catch (_: IOException) {
                if (primaryFailure == null) throw operationUnavailable("download", "PARTIAL_CLEANUP_FAILED")
            } catch (_: SecurityException) {
                if (primaryFailure == null) throw operationUnavailable("download", "PARTIAL_CLEANUP_FAILED")
            }
        }
    }

    override fun downloadExact(
        source: StoredObjectRef,
        maxBytes: Long,
        timeout: Duration,
    ): ExactObjectDownload {
        requireTimeout("downloadExact", timeout)
        val exact = requireExactReference("downloadExact", source)
        if (maxBytes <= 0) throw operationUnavailable("downloadExact", "INVALID_LIMIT")
        if (exact.sizeBytes > maxBytes) throw operationUnavailable("downloadExact", "RESPONSE_TOO_LARGE")
        val request = GetObjectRequest.builder()
            .bucket(exact.bucket)
            .key(exact.key)
            .versionId(exact.versionId)
            .overrideConfiguration { it.apiCallTimeout(timeout) }
            .build()
        val result = try {
            s3.getObject(request, ExactBytesTransformer(exact.sizeBytes))
        } catch (failure: ExactDownloadReadFailure) {
            throw operationUnavailable("downloadExact", failure.code)
        } catch (error: SdkException) {
            throw operationUnavailable("downloadExact", safeAwsErrorCode(error))
        }
        return ExactObjectDownload(
            bytes = result.bytes,
            versionId = result.response.versionId(),
            eTag = result.response.eTag(),
            sizeBytes = result.response.contentLength(),
            metadata = result.response.metadata(),
        )
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
            response.contentLength() != exact.sizeBytes
        ) {
            throw operationUnavailable("headProtection", "REFERENCE_MISMATCH")
        }
        if (response.metadata()[SHA256_METADATA] != exact.sha256) {
            throw operationUnavailable("headProtection", "DIGEST_MISMATCH")
        }
        return ObjectProtectionSnapshot(
            actualMode = response.objectLockModeAsString(),
            retainUntil = response.objectLockRetainUntilDate(),
        )
    }

    override fun toString(): String = "AwsS3Gateway(<redacted>)"

    private fun fingerprint(claim: ProviderAttestedIdentity): String {
        val fields = listOf(
            validateIdentityField(claim.partition, PARTITION_PATTERN),
            validateIdentityField(claim.account, STABLE_ACCOUNT_PATTERN),
            validateIdentityField(claim.principalKind, PRINCIPAL_KIND_PATTERN),
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
        requirePostPutHistory(operation, bucket, key, versionId, size, sha256, timeout)
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
        val transformed = try {
            sdkCall(operation) {
                s3.getObject(
                    GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .versionId(version.versionId())
                        .overrideConfiguration { it.apiCallTimeout(timeout) }
                        .build(),
                    DigestingResponseTransformer(),
                )
            }
        } catch (_: IOException) {
            throw operationUnavailable(operation, "CONFLICT_UNVERIFIED")
        }
        val response = transformed.response
        val actual = transformed.digest
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
        requirePostPutHistory(
            "controls",
            bucket,
            key,
            versionId,
            CONTROL_TARGET_BYTES.size.toLong(),
            CONTROL_TARGET_SHA256,
            timeout,
        )
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

    private fun requirePostPutHistory(
        operation: String,
        bucket: String,
        key: String,
        versionId: String,
        size: Long,
        sha256: String,
        timeout: Duration,
    ) {
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
        val exactVersions = listed.versions().filter { it.key() == key }
        val valid = listed.isTruncated != true &&
            listed.deleteMarkers().none { it.key() == key } &&
            exactVersions.size == 1 &&
            exactVersions.single().versionId() == versionId &&
            exactVersions.single().size() == size
        if (!valid) throw operationUnavailable(operation, "POST_PUT_HISTORY_INVALID")
        val head = sdkCall(operation) {
            s3.headObject(
                HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .versionId(versionId)
                    .overrideConfiguration { it.apiCallTimeout(timeout) }
                    .build(),
            )
        }
        if (head.versionId() != versionId ||
            head.contentLength() != size ||
            head.metadata()[SHA256_METADATA] != sha256
        ) {
            throw operationUnavailable(operation, "POST_PUT_METADATA_INVALID")
        }
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
            delete = deleteMutationResult(bucket, targetKey, requireNotNull(target.versionId), timeout),
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
            val listedSize = version.size() ?: return null
            if (listedSize !in 0..MAX_CONTROL_RESULT_BYTES) return null
            val transformed = s3.getObject(
                GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(resultKey)
                    .versionId(version.versionId())
                    .overrideConfiguration { it.apiCallTimeout(timeout) }
                    .build(),
                BoundedBytesTransformer(MAX_CONTROL_RESULT_BYTES),
            )
            val bytes = transformed.bytes ?: return null
            val response = transformed.response
            if (response.versionId() != version.versionId() ||
                response.contentLength() != bytes.size.toLong() ||
                listedSize != bytes.size.toLong()
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
        record.target.sha256 == CONTROL_TARGET_SHA256 &&
        record.target.sizeBytes == CONTROL_TARGET_BYTES.size.toLong()

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
        requirePostPutHistory("controls", bucket, key, versionId, bytes.size.toLong(), digest, timeout)
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
        val valid = timeout.isPositive() &&
            bucket.isNotBlank() &&
            SHA256_PATTERN.matches(policyFingerprint) &&
            identity.provider == ArchiveProvider.S3_COMPATIBLE &&
            SHA256_PATTERN.matches(identity.principalFingerprint) &&
            validControlKey(targetKey, "target.json", policyFingerprint, identity.principalFingerprint, utcDate) &&
            validControlKey(resultKey, "result.json", policyFingerprint, identity.principalFingerprint, utcDate) &&
            targetKey.substringBeforeLast('/') == resultKey.substringBeforeLast('/') &&
            validUntil == utcDate.plusDays(1).atStartOfDay(UTC).toInstant() &&
            requiredRetainUntil >= validUntil
        if (!valid) throw operationUnavailable("controls", "INVALID_CONTROL_BINDING")
    }

    private fun safeControlKey(key: String): Boolean = key.isNotBlank() &&
        key.toByteArray(UTF_8).size <= MAX_CONTROL_KEY_BYTES &&
        !key.startsWith('/') &&
        '\\' !in key &&
        key.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun validControlKey(
        key: String,
        terminal: String,
        policyFingerprint: String,
        principalFingerprint: String,
        utcDate: LocalDate,
    ): Boolean {
        if (!safeControlKey(key)) return false
        val segments = key.split('/')
        val tail = listOf(
            CONTROL_NAMESPACE,
            policyFingerprint,
            principalFingerprint,
            utcDate.toString(),
            terminal,
        )
        val prefix = segments.dropLast(tail.size)
        return prefix.isNotEmpty() &&
            segments.takeLast(tail.size) == tail &&
            segments.count { it == CONTROL_NAMESPACE } == 1 &&
            prefix.none { it.lowercase(Locale.ROOT) in RESERVED_CONTROL_NAMESPACES }
    }

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

    private fun deleteMutationResult(
        bucket: String,
        key: String,
        versionId: String,
        timeout: Duration,
    ): MutationCheckResult {
        val markerDelete = mutationResult {
            s3.deleteObject(
                DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .overrideConfiguration { it.apiCallTimeout(timeout) }
                    .build(),
            )
        }
        val versionDelete = mutationResult {
            s3.deleteObject(
                DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .versionId(versionId)
                    .overrideConfiguration { it.apiCallTimeout(timeout) }
                    .build(),
            )
        }
        return when {
            markerDelete == MutationCheckResult.ALLOWED || versionDelete == MutationCheckResult.ALLOWED ->
                MutationCheckResult.ALLOWED
            markerDelete == MutationCheckResult.DENIED_AS_EXPECTED &&
                versionDelete == MutationCheckResult.DENIED_AS_EXPECTED -> MutationCheckResult.DENIED_AS_EXPECTED
            else -> MutationCheckResult.INDETERMINATE
        }
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

    private fun fileSize(path: Path, operation: String, code: String): Long = try {
        Files.size(path)
    } catch (_: IOException) {
        throw operationUnavailable(operation, code)
    } catch (_: SecurityException) {
        throw operationUnavailable(operation, code)
    }

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance(SHA_256).digest(bytes))

    private fun readBounded(input: InputStream, maxBytes: Long): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val remainingWithOverflowSentinel = maxBytes - total + 1
            val read = input.read(
                buffer,
                0,
                minOf(buffer.size.toLong(), remainingWithOverflowSentinel).toInt(),
            )
            if (read < 0) return output.toByteArray()
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
    }

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

    private data class ManagedDigest(val response: GetObjectResponse, val digest: StreamDigest)

    private data class ManagedBytes(val response: GetObjectResponse, val bytes: ByteArray?)

    private data class ExactBytesResult(val response: GetObjectResponse, val bytes: ByteArray)

    private class ExactDownloadReadFailure(val code: String) : RuntimeException()

    private inner class DigestingResponseTransformer : ResponseTransformer<GetObjectResponse, ManagedDigest> {
        override fun transform(response: GetObjectResponse, input: AbortableInputStream): ManagedDigest {
            val digest = MessageDigest.getInstance(SHA_256)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var size = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                size += read
            }
            return ManagedDigest(response, StreamDigest(hex(digest.digest()), size))
        }
    }

    private inner class BoundedBytesTransformer(
        private val maxBytes: Long,
    ) : ResponseTransformer<GetObjectResponse, ManagedBytes> {
        override fun transform(response: GetObjectResponse, input: AbortableInputStream): ManagedBytes {
            val contentLength = response.contentLength() ?: return ManagedBytes(response, null)
            if (contentLength !in 0..maxBytes) return ManagedBytes(response, null)
            return ManagedBytes(response, readBounded(input, maxBytes))
        }
    }

    private inner class ExactBytesTransformer(
        private val expectedSize: Long,
    ) : ResponseTransformer<GetObjectResponse, ExactBytesResult> {
        override fun transform(response: GetObjectResponse, input: AbortableInputStream): ExactBytesResult {
            val contentLength = response.contentLength()
                ?: throw ExactDownloadReadFailure("CONTENT_LENGTH_REQUIRED")
            if (contentLength != expectedSize) throw ExactDownloadReadFailure("RESPONSE_SIZE_MISMATCH")
            val output = ByteArrayOutputStream(contentLength.toInt())
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val remainingWithOverflowSentinel = expectedSize - total + 1
                val read = try {
                    input.read(
                        buffer,
                        0,
                        minOf(buffer.size.toLong(), remainingWithOverflowSentinel).toInt(),
                    )
                } catch (_: IOException) {
                    throw ExactDownloadReadFailure("IO_ERROR")
                }
                if (read < 0) break
                if (read == 0) throw ExactDownloadReadFailure("ZERO_PROGRESS")
                total += read
                if (total > expectedSize) throw ExactDownloadReadFailure("RESPONSE_SIZE_MISMATCH")
                output.write(buffer, 0, read)
            }
            if (total != expectedSize) throw ExactDownloadReadFailure("RESPONSE_SIZE_MISMATCH")
            return ExactBytesResult(response, output.toByteArray())
        }
    }

    private sealed interface ControlTargetClaim {
        data class Winner(val target: StoredObjectRef) : ControlTargetClaim
        data object Loser : ControlTargetClaim
    }

    private fun validateIdentityField(value: String, pattern: Regex): String {
        if (!pattern.matches(value)) throw invalidProviderIdentity()
        return value
    }

    private fun validateStableResource(value: String): String {
        if (value.isBlank() ||
            value.toByteArray(UTF_8).size > MAX_RESOURCE_BYTES ||
            value.any(Char::isISOControl)
        ) {
            throw invalidProviderIdentity()
        }
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
        const val MAX_CONTROL_KEY_BYTES = 1024
        const val CONTROL_NAMESPACE = "capability-probe"
        val RESERVED_CONTROL_NAMESPACES = setOf("evidence", "payload", "receipt")
        const val PRECONDITION_FAILED = 412
        const val MAX_RESULT_VERSIONS = 3
        const val MAX_CONTROL_RESULT_BYTES = 64L * 1024
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        val PARTITION_PATTERN = Regex("^[a-z0-9-]{1,32}$")
        val STABLE_ACCOUNT_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        val PRINCIPAL_KIND_PATTERN = Regex("^[a-z][a-z0-9-]{0,63}$")
    }
}

internal enum class ProviderAttestationFailureCode(val wireCode: String) {
    IDENTITY_UNAVAILABLE("IDENTITY_UNAVAILABLE"),
    INVALID_IDENTITY("INVALID_IDENTITY"),
}

internal class ProviderAttestationFailure(
    val code: ProviderAttestationFailureCode,
) : RuntimeException()

internal object MissingProviderIdentityAttestor : ProviderIdentityAttestor {
    override fun attest(timeout: Duration): ProviderAttestedIdentity =
        throw ProviderAttestationFailure(ProviderAttestationFailureCode.IDENTITY_UNAVAILABLE)

    override fun toString(): String = "MissingProviderIdentityAttestor"
}

private fun parseAwsIdentity(account: String?, arn: String?, userId: String?): ProviderAttestedIdentity {
    if (account == null || arn == null || userId == null || !AWS_ACCOUNT_PATTERN.matches(account)) {
        throw invalidProviderIdentity()
    }
    val arnParts = arn.split(':', limit = 6)
    if (arnParts.size != 6 || arnParts[0] != "arn" || arnParts[3].isNotEmpty() || arnParts[4] != account) {
        throw invalidProviderIdentity()
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
                throw invalidProviderIdentity()
            }
            principalKind = "assumed-role"
            stableResource = resourceParts.dropLast(1).joinToString("/")
        }
        service == "sts" && resourceParts.firstOrNull() == "federated-user" && resourceParts.size >= 2 -> {
            val federatedNamePath = resourceParts.drop(1).joinToString("/")
            if (resourceParts.any(String::isBlank) || userId != "$account:$federatedNamePath") {
                throw invalidProviderIdentity()
            }
            principalKind = "federated-user"
            stableResource = resource
        }
        service == "iam" && resourceParts.firstOrNull() == "user" && resourceParts.size >= 2 -> {
            if (resourceParts.any(String::isBlank) || !AWS_USER_UNIQUE_ID_PATTERN.matches(userId)) {
                throw invalidProviderIdentity()
            }
            principalKind = "user"
            stableResource = resource
        }
        service == "iam" && resource == "root" && userId == account -> {
            principalKind = "root"
            stableResource = resource
        }
        else -> throw invalidProviderIdentity()
    }
    return ProviderAttestedIdentity(partition, account, principalKind, stableResource)
}

private fun invalidProviderIdentity(): ProviderAttestationFailure =
    ProviderAttestationFailure(ProviderAttestationFailureCode.INVALID_IDENTITY)

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
    val root = objectMapper.createObjectNode()
    root.put("policyFingerprint", record.policyFingerprint)
    root.putObject("identity").apply {
        put("provider", record.identity.provider.name)
        put("principalFingerprint", record.identity.principalFingerprint)
    }
    root.put("utcDate", record.utcDate.toString())
    root.put("validUntil", record.validUntil.toString())
    root.putObject("target").apply {
        put("provider", record.target.provider.name)
        put("locator", record.target.locator)
        record.target.bucket?.let { put("bucket", it) } ?: putNull("bucket")
        put("key", record.target.key)
        record.target.versionId?.let { put("versionId", it) } ?: putNull("versionId")
        put("sha256", record.target.sha256)
        put("sizeBytes", record.target.sizeBytes)
    }
    root.put("overwrite", record.overwrite.name)
    root.put("delete", record.delete.name)
    root.put("bypass", record.bypass.name)
    JsonCanonicalizer(objectMapper.writeValueAsBytes(root)).encodedUTF8
} catch (_: IOException) {
    throw operationUnavailable("controls", "SERIALIZATION_ERROR")
}
