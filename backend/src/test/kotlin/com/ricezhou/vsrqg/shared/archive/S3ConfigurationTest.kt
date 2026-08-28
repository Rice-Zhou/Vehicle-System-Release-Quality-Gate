package com.ricezhou.vsrqg.shared.archive

import com.ricezhou.vsrqg.shared.adapter.archive.ArchiveConfiguration
import com.ricezhou.vsrqg.shared.adapter.archive.AwsS3Gateway
import com.ricezhou.vsrqg.shared.adapter.archive.ExactObjectDownload
import com.ricezhou.vsrqg.shared.adapter.archive.AwsStsIdentityAttestor
import com.ricezhou.vsrqg.shared.adapter.archive.ProviderAttestedIdentity
import com.ricezhou.vsrqg.shared.adapter.archive.ProviderAttestationFailure
import com.ricezhou.vsrqg.shared.adapter.archive.ProviderAttestationFailureCode
import com.ricezhou.vsrqg.shared.adapter.archive.ProviderIdentityAttestor
import com.ricezhou.vsrqg.shared.adapter.archive.ObjectProtectionSnapshot
import com.ricezhou.vsrqg.shared.adapter.archive.S3ControlSnapshot
import com.ricezhou.vsrqg.shared.adapter.archive.S3Gateway
import com.ricezhou.vsrqg.shared.adapter.archive.canonicalDailyControlRecordBytes
import com.ricezhou.vsrqg.shared.application.archive.RuntimeIdentityRef
import com.ricezhou.vsrqg.shared.application.archive.ArchiveUnavailable
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import java.net.URI
import com.ricezhou.vsrqg.shared.application.archive.StoredObjectRef
import com.ricezhou.vsrqg.shared.application.archive.MutationCheckResult
import com.ricezhou.vsrqg.shared.application.archive.DailyControlRecord
import java.nio.file.Path
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.times
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.`when`
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.ObjectLockMode
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus
import software.amazon.awssdk.services.s3.model.DefaultRetention
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionRequest
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionResponse
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationRequest
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationResponse
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockRequest
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockResponse
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse
import software.amazon.awssdk.services.s3.model.ObjectLockConfiguration
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled
import software.amazon.awssdk.services.s3.model.ObjectLockRule
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode
import software.amazon.awssdk.services.s3.model.ObjectVersion
import software.amazon.awssdk.services.s3.model.PublicAccessBlockConfiguration
import software.amazon.awssdk.services.s3.model.PutObjectRetentionRequest
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionByDefault
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionConfiguration
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionRule
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.exception.ApiCallTimeoutException

class S3ConfigurationTest {
    @TempDir
    lateinit var tempDirectory: Path

    private val contextRunner = ApplicationContextRunner()
        .withBean(ObjectMapper::class.java, { jacksonObjectMapper().findAndRegisterModules() })
        .withUserConfiguration(S3TestApplication::class.java)

    @Test
    fun `none provider creates no AWS clients or gateway`() {
        listOf(emptyArray(), arrayOf("vsrqg.deployment.mode=PILOT")).forEach { properties ->
            contextRunner.withPropertyValues(*properties).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(S3Client::class.java)
                assertThat(context).doesNotHaveBean(StsClient::class.java)
                assertThat(context).doesNotHaveBean(S3Gateway::class.java)
                assertThat(context).doesNotHaveBean(DefaultCredentialsProvider::class.java)
            }
        }
    }

    @Test
    fun `gateway exposes only the exact version aware contract`() {
        assertThat(S3Gateway::class.java.declaredMethods.filterNot { it.isSynthetic }.map { it.name }.sorted()).containsExactly(
            "controls",
            "download",
            "downloadExact",
            "headProtection",
            "putFileIfAbsent",
            "putJsonIfAbsent",
            "runtimeIdentity",
        )
        assertThat(ObjectProtectionSnapshot::class.java.declaredFields.map { it.name })
            .containsExactlyInAnyOrder("actualMode", "retainUntil")
        assertThat(S3ControlSnapshot::class.java.declaredFields.map { it.name })
            .containsExactlyInAnyOrder(
                "reachable",
                "encrypted",
                "privateAccess",
                "versioningEnabled",
                "objectLockEnabled",
                "defaultRetentionDays",
                "controlObjectProtection",
                "dailyControl",
            )

        val gateway = object : S3Gateway {
            override fun runtimeIdentity(timeout: Duration): RuntimeIdentityRef = error("not invoked")

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
            ): S3ControlSnapshot = error("not invoked")

            override fun putFileIfAbsent(
                bucket: String,
                key: String,
                source: Path,
                sha256: String,
                timeout: Duration,
            ): StoredObjectRef = error("not invoked")

            override fun download(source: StoredObjectRef, target: Path, timeout: Duration) = Unit

            override fun downloadExact(
                source: StoredObjectRef,
                maxBytes: Long,
                timeout: Duration,
            ): ExactObjectDownload = error("not invoked")

            override fun putJsonIfAbsent(
                bucket: String,
                key: String,
                bytes: ByteArray,
                sha256: String,
                timeout: Duration,
            ): StoredObjectRef = error("not invoked")

            override fun headProtection(
                source: StoredObjectRef,
                timeout: Duration,
            ): ObjectProtectionSnapshot = error("not invoked")
        }

        assertThat(gateway).isInstanceOf(S3Gateway::class.java)
    }

    @Test
    fun `native AWS wiring shares credentials and region without overriding STS endpoint`() {
        contextRunner
            .withPropertyValues(
                "vsrqg.evidence.archive.provider=S3_COMPATIBLE",
                "vsrqg.evidence.archive.region=cn-north-1",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(DefaultCredentialsProvider::class.java)
                assertThat(context).hasSingleBean(S3Client::class.java)
                assertThat(context).hasSingleBean(StsClient::class.java)
                assertThat(context).hasSingleBean(S3Gateway::class.java)

                val credentials = context.getBean(DefaultCredentialsProvider::class.java)
                val s3Configuration = context.getBean(S3Client::class.java).serviceClientConfiguration()
                val stsConfiguration = context.getBean(StsClient::class.java).serviceClientConfiguration()
                assertThat(s3Configuration.credentialsProvider()).isSameAs(credentials)
                assertThat(stsConfiguration.credentialsProvider()).isSameAs(credentials)
                assertThat(s3Configuration.region().id()).isEqualTo("cn-north-1")
                assertThat(stsConfiguration.region().id()).isEqualTo("cn-north-1")
                assertThat(s3Configuration.endpointOverride()).isEmpty()
                assertThat(stsConfiguration.endpointOverride()).isEmpty()
            }
    }

    @Test
    fun `custom endpoint creates path style S3 without STS and hashes approved attestation`() {
        var observedTimeout: Duration? = null
        val attestor = ProviderIdentityAttestor { timeout ->
            observedTimeout = timeout
            ProviderAttestedIdentity(
                partition = "private",
                account = "tenant-alpha",
                principalKind = "service-account",
                stableResource = "teams/release/service-account",
            )
        }
        contextRunner
            .withBean(ProviderIdentityAttestor::class.java, { attestor })
            .withPropertyValues(
                "vsrqg.evidence.archive.provider=S3_COMPATIBLE",
                "vsrqg.evidence.archive.region=us-east-1",
                "vsrqg.evidence.archive.endpoint=https://archive.example.test/storage",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(S3Client::class.java)
                assertThat(context).doesNotHaveBean(StsClient::class.java)
                val client = context.getBean(S3Client::class.java)
                val configuration = client.serviceClientConfiguration()
                assertThat(configuration.endpointOverride())
                    .contains(URI("https://archive.example.test/storage"))
                val objectUrl = client.utilities().getUrl {
                    it.bucket("archive-bucket").key("control.json")
                }
                assertThat(objectUrl.host).isEqualTo("archive.example.test")
                assertThat(objectUrl.path).isEqualTo("/storage/archive-bucket/control.json")

                val identity = context.getBean(S3Gateway::class.java)
                    .runtimeIdentity(Duration.ofMillis(250))
                assertThat(identity.provider).isEqualTo(ArchiveProvider.S3_COMPATIBLE)
                assertThat(identity.principalFingerprint)
                    .isEqualTo("6b3e791ae5cfbefb2b9f51404224daa3a5e538bdd9088ca9fbdc5c2a8b5a63c5")
                assertThat(observedTimeout).isEqualTo(Duration.ofMillis(250))
                assertThat(identity.toString()).doesNotContain("tenant-alpha", "service-account")
            }
    }

    @Test
    fun `custom endpoint without approved attestor cannot claim an identity`() {
        val endpoint = "https://private-user:private-password@archive.example.test"
        contextRunner
            .withPropertyValues(
                "vsrqg.evidence.archive.provider=S3_COMPATIBLE",
                "vsrqg.evidence.archive.region=us-east-1",
                "vsrqg.evidence.archive.endpoint=https://archive.example.test",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(S3Client::class.java)
                assertThat(context).doesNotHaveBean(StsClient::class.java)
                assertThatThrownBy {
                    context.getBean(S3Gateway::class.java).runtimeIdentity(Duration.ofMillis(100))
                }.isInstanceOf(ArchiveUnavailable::class.java)
                    .hasMessage("S3 operation identity failed (AWS IDENTITY_UNAVAILABLE)")
                    .hasMessageNotContaining(endpoint)
                    .hasMessageNotContaining("private-user")
                    .hasMessageNotContaining("private-password")
            }
    }

    @Test
    fun `AWS attestation removes only assumed role session and applies probe timeout`() {
        val sts = mock(StsClient::class.java)
        `when`(sts.getCallerIdentity(any(GetCallerIdentityRequest::class.java))).thenReturn(
            GetCallerIdentityResponse.builder()
                .account("123456789012")
                .arn("arn:aws:sts::123456789012:assumed-role/platform/ReleaseRole/BuildSession-42")
                .userId("AROABCDEFGHIJKLMNOPQR:BuildSession-42")
                .build(),
        )
        val gateway = AwsS3Gateway(
            s3 = mock(S3Client::class.java),
            objectMapper = jacksonObjectMapper().findAndRegisterModules(),
            identityAttestor = AwsStsIdentityAttestor(sts),
        )

        val identity = gateway.runtimeIdentity(Duration.ofMillis(275))

        assertThat(identity.principalFingerprint)
            .isEqualTo("10c7ce37a63116640916d67b801a091cdebe425421063ad90c4061104eab8629")
        val request = ArgumentCaptor.forClass(GetCallerIdentityRequest::class.java)
        verify(sts).getCallerIdentity(request.capture())
        assertThat(request.value.overrideConfiguration().orElseThrow().apiCallTimeout())
            .contains(Duration.ofMillis(275))
        assertThat(gateway.toString()).doesNotContain(
            "123456789012",
            "ReleaseRole",
            "BuildSession-42",
            "AROABCDEFGHIJKLMNOPQR",
        )
        assertThat(gateway.runtimeIdentity(Duration.ofMillis(275))).isEqualTo(identity)
        verify(sts, times(2)).getCallerIdentity(any(GetCallerIdentityRequest::class.java))
    }

    @Test
    fun `AWS federated user preserves path and produces a stable bound fingerprint`() {
        val account = "123456789012"
        val federatedPath = "team/release/BuildBot"
        val sts = mock(StsClient::class.java)
        `when`(sts.getCallerIdentity(any(GetCallerIdentityRequest::class.java))).thenReturn(
            GetCallerIdentityResponse.builder()
                .account(account)
                .arn("arn:aws:sts::$account:federated-user/$federatedPath")
                .userId("$account:$federatedPath")
                .build(),
        )
        val s3 = mock(S3Client::class.java)
        val gateway = AwsS3Gateway(
            s3,
            jacksonObjectMapper().findAndRegisterModules(),
            AwsStsIdentityAttestor(sts),
        )

        val identity = gateway.runtimeIdentity(Duration.ofMillis(180))

        assertThat(identity.principalFingerprint)
            .isEqualTo("c173bc769bc4d445f77f5a286207529e19029e48b657c656350f882e981e0b22")
        assertThat(identity.toString()).doesNotContain(account, federatedPath, "BuildBot")
        verifyNoInteractions(s3)
    }

    @Test
    fun `AWS federated user rejects account path and suffix mismatches without leaking claims`() {
        val account = "123456789012"
        val federatedPath = "team/release/SecretBuildBot"
        val rawArn = "arn:aws:sts::$account:federated-user/$federatedPath"
        listOf(
            "999999999999:$federatedPath",
            "$account:team/other/SecretBuildBot",
            "$account:",
            "$account:$federatedPath:extra-session",
        ).forEach { rawUserId ->
            assertInvalidAwsIdentity(account, rawArn, rawUserId)
        }
    }

    @Test
    fun `AWS attestation rejects unique IDs from another principal kind and root mismatch`() {
        val account = "123456789012"
        listOf(
            "arn:aws:sts::$account:assumed-role/platform/ReleaseRole/BuildSession-42" to
                "AIDAABCDEFGHIJKLMNOPQ:BuildSession-42",
            "arn:aws:iam::$account:user/platform/ReleaseUser" to "AROABCDEFGHIJKLMNOPQR",
            "arn:aws:iam::$account:root" to "AIDAABCDEFGHIJKLMNOPQ",
        ).forEach { (rawArn, rawUserId) ->
            assertInvalidAwsIdentity(account, rawArn, rawUserId)
        }
    }

    @Test
    fun `identity attestation rejects malformed or unavailable claims without leaking originals`() {
        val rawArn = "arn:aws:sts::999999999999:assumed-role/platform/SecretRole/SecretSession"
        val rawUserId = "AROA_SECRET:SecretSession"
        val invalidSts = mock(StsClient::class.java)
        `when`(invalidSts.getCallerIdentity(any(GetCallerIdentityRequest::class.java))).thenReturn(
            GetCallerIdentityResponse.builder()
                .account("123456789012")
                .arn(rawArn)
                .userId(rawUserId)
                .build(),
        )
        val invalidS3 = mock(S3Client::class.java)
        val invalidGateway = AwsS3Gateway(
            invalidS3,
            jacksonObjectMapper().findAndRegisterModules(),
            AwsStsIdentityAttestor(invalidSts),
        )

        assertThatThrownBy { invalidGateway.runtimeIdentity(Duration.ofMillis(100)) }
            .isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 operation identity failed (AWS INVALID_IDENTITY)")
            .hasMessageNotContaining(rawArn)
            .hasMessageNotContaining(rawUserId)
        verifyNoInteractions(invalidS3)

        val malformedUserIdSts = mock(StsClient::class.java)
        `when`(malformedUserIdSts.getCallerIdentity(any(GetCallerIdentityRequest::class.java))).thenReturn(
            GetCallerIdentityResponse.builder()
                .account("123456789012")
                .arn("arn:aws:sts::123456789012:assumed-role/platform/ReleaseRole/BuildSession-42")
                .userId("x:BuildSession-42")
                .build(),
        )
        assertThatThrownBy {
            AwsS3Gateway(
                mock(S3Client::class.java),
                jacksonObjectMapper().findAndRegisterModules(),
                AwsStsIdentityAttestor(malformedUserIdSts),
            ).runtimeIdentity(Duration.ofMillis(100))
        }.isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 operation identity failed (AWS INVALID_IDENTITY)")

        val unavailableSts = mock(StsClient::class.java)
        `when`(unavailableSts.getCallerIdentity(any(GetCallerIdentityRequest::class.java))).thenThrow(
            SdkClientException.create("timeout at https://secret.internal?token=credential"),
        )
        val unavailableS3 = mock(S3Client::class.java)
        val unavailableGateway = AwsS3Gateway(
            unavailableS3,
            jacksonObjectMapper().findAndRegisterModules(),
            AwsStsIdentityAttestor(unavailableSts),
        )
        assertThatThrownBy { unavailableGateway.runtimeIdentity(Duration.ofMillis(100)) }
            .isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 operation identity failed (AWS SDK_CLIENT_ERROR)")
            .hasMessageNotContaining("secret.internal")
            .hasMessageNotContaining("credential")
        verifyNoInteractions(unavailableS3)
    }

    @Test
    fun `identity fingerprint remains case sensitive and attested material has redacted toString`() {
        val upper = ProviderAttestedIdentity(
            "aws",
            "123456789012",
            "assumed-role",
            "assumed-role/platform/ReleaseRole",
        )
        val lower = ProviderAttestedIdentity(
            "aws",
            "123456789012",
            "assumed-role",
            "assumed-role/platform/releaserole",
        )
        val s3 = mock(S3Client::class.java)

        val upperFingerprint = AwsS3Gateway(s3, jacksonObjectMapper(), ProviderIdentityAttestor { upper })
            .runtimeIdentity(Duration.ofSeconds(1)).principalFingerprint
        val lowerFingerprint = AwsS3Gateway(s3, jacksonObjectMapper(), ProviderIdentityAttestor { lower })
            .runtimeIdentity(Duration.ofSeconds(1)).principalFingerprint

        assertThat(upperFingerprint)
            .isEqualTo("10c7ce37a63116640916d67b801a091cdebe425421063ad90c4061104eab8629")
        assertThat(lowerFingerprint)
            .isEqualTo("95ee5af946fc99b589fd641b31736e8d7fa8f858c4cf0a57ef1fb0e6c06d5f90")
        assertThat(upper.toString()).isEqualTo("ProviderAttestedIdentity(<redacted>)")
    }

    @Test
    fun `unexpected attestor programming failures propagate unchanged without S3 access`() {
        listOf(
            IllegalStateException("opaque-illegal-state"),
            IllegalArgumentException("opaque-illegal-argument"),
            NullPointerException("opaque-null-pointer"),
        ).forEach { failure ->
            val s3 = mock(S3Client::class.java)
            val gateway = AwsS3Gateway(
                s3,
                jacksonObjectMapper().findAndRegisterModules(),
                ProviderIdentityAttestor { throw failure },
            )

            assertThatThrownBy { gateway.runtimeIdentity(Duration.ofMillis(100)) }
                .isSameAs(failure)
            verifyNoInteractions(s3)
        }
    }

    @Test
    fun `malformed custom attestation becomes an allowlisted message free domain failure`() {
        val rawClaim = "private tenant with spaces"
        val s3 = mock(S3Client::class.java)
        val gateway = AwsS3Gateway(
            s3,
            jacksonObjectMapper().findAndRegisterModules(),
            ProviderIdentityAttestor {
                ProviderAttestedIdentity(rawClaim, "tenant-a", "service-account", "release/account-a")
            },
        )

        assertThatThrownBy { gateway.runtimeIdentity(PROBE_TIMEOUT) }
            .isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 operation identity failed (AWS INVALID_IDENTITY)")
            .hasMessageNotContaining(rawClaim)
        val domainFailure = ProviderAttestationFailure(ProviderAttestationFailureCode.INVALID_IDENTITY)
        assertThat(domainFailure.message).isNull()
        verifyNoInteractions(s3)
    }

    @Test
    fun `put download and head use operation timeout and the exact returned version`() {
        val bytes = "immutable archive payload".toByteArray()
        val sha256 = sha256(bytes)
        val source = Files.write(tempDirectory.resolve("source.zip"), bytes)
        val target = tempDirectory.resolve("download.zip")
        val retainUntil = Instant.parse("2027-08-27T00:00:00Z")
        val s3 = mock(S3Client::class.java)
        `when`(
            s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java)),
        ).thenReturn(PutObjectResponse.builder().versionId("payload-version-1").build())
        `when`(s3.listObjectVersions(any(ListObjectVersionsRequest::class.java))).thenReturn(
            exactCreatedHistory("evidence/payload.zip", "payload-version-1", bytes.size.toLong()),
        )
        stubManagedGet(
            s3,
            GetObjectResponse.builder().versionId("payload-version-1").contentLength(bytes.size.toLong()).build(),
            bytes,
        )
        `when`(s3.headObject(any(HeadObjectRequest::class.java))).thenReturn(
            HeadObjectResponse.builder()
                .versionId("payload-version-1")
                .contentLength(bytes.size.toLong())
                .metadata(mapOf("sha256" to sha256))
                .objectLockMode(ObjectLockMode.COMPLIANCE)
                .objectLockRetainUntilDate(retainUntil)
                .build(),
        )
        val gateway = gateway(s3)
        val timeout = Duration.ofSeconds(19)

        val reference = gateway.putFileIfAbsent("archive-bucket", "evidence/payload.zip", source, sha256, timeout)
        gateway.download(reference, target, timeout)
        val exactDownload = gateway.downloadExact(reference, bytes.size.toLong(), timeout)
        val protection = gateway.headProtection(reference, timeout)

        assertThat(reference).isEqualTo(
            StoredObjectRef(
                provider = ArchiveProvider.S3_COMPATIBLE,
                locator = "s3://archive-bucket/evidence/payload.zip",
                bucket = "archive-bucket",
                key = "evidence/payload.zip",
                versionId = "payload-version-1",
                sha256 = sha256,
                sizeBytes = bytes.size.toLong(),
            ),
        )
        assertThat(Files.readAllBytes(target)).isEqualTo(bytes)
        assertThat(exactDownload.bytes()).isEqualTo(bytes)
        assertThat(exactDownload.versionId).isEqualTo("payload-version-1")
        assertThat(exactDownload.eTag).isNull()
        assertThat(exactDownload.sizeBytes).isEqualTo(bytes.size.toLong())
        assertThat(exactDownload.metadata).isEmpty()
        assertThat(protection).isEqualTo(ObjectProtectionSnapshot("COMPLIANCE", retainUntil))

        val put = ArgumentCaptor.forClass(PutObjectRequest::class.java)
        verify(s3).putObject(put.capture(), any(RequestBody::class.java))
        assertRequestTimeout(put.value, timeout)
        assertThat(put.value.bucket()).isEqualTo(reference.bucket)
        assertThat(put.value.key()).isEqualTo(reference.key)
        assertThat(put.value.ifNoneMatch()).isEqualTo("*")
        assertThat(put.value.metadata()).containsEntry("sha256", sha256)

        val get = ArgumentCaptor.forClass(GetObjectRequest::class.java)
        verify(s3, times(2)).getObject(get.capture(), anyGeneric<ResponseTransformer<GetObjectResponse, Any>>())
        verify(s3, times(0)).getObject(any(GetObjectRequest::class.java))
        verify(s3, times(0)).getObject(any(GetObjectRequest::class.java), any(Path::class.java))
        get.allValues.forEach {
            assertRequestTimeout(it, timeout)
            assertThat(it.bucket()).isEqualTo(reference.bucket)
            assertThat(it.key()).isEqualTo(reference.key)
            assertThat(it.versionId()).isEqualTo("payload-version-1")
        }

        val head = ArgumentCaptor.forClass(HeadObjectRequest::class.java)
        verify(s3, times(2)).headObject(head.capture())
        head.allValues.forEach {
            assertRequestTimeout(it, timeout)
            assertThat(it.versionId()).isEqualTo("payload-version-1")
        }
    }

    @Test
    fun `exact byte download rejects latest refs oversize and zero progress without fallback`() {
        val latestS3 = mock(S3Client::class.java)
        val latestGateway = gateway(latestS3)
        listOf(null, "", " ", "null", "NULL").forEach { versionId ->
            assertThatThrownBy {
                latestGateway.downloadExact(
                    s3Reference(versionId, "a".repeat(64), 1),
                    1,
                    Duration.ofSeconds(2),
                )
            }.isInstanceOf(ArchiveUnavailable::class.java)
                .hasMessage("S3 operation downloadExact failed (AWS INVALID_REFERENCE)")
        }
        verifyNoInteractions(latestS3)

        val oversizeS3 = mock(S3Client::class.java)
        var bodyReads = 0
        doAnswer { invocation ->
            val transformer = invocation.getArgument<ResponseTransformer<GetObjectResponse, Any?>>(1)
            transformer.transform(
                GetObjectResponse.builder().versionId("version-1").contentLength(2).build(),
                AbortableInputStream.create(
                    object : InputStream() {
                        override fun read(): Int {
                            bodyReads += 1
                            return -1
                        }
                    },
                ),
            )
        }.`when`(oversizeS3).getObject(
            any(GetObjectRequest::class.java),
            anyGeneric<ResponseTransformer<GetObjectResponse, Any>>(),
        )
        assertThatThrownBy {
            gateway(oversizeS3).downloadExact(
                s3Reference("version-1", "a".repeat(64), 1),
                1,
                Duration.ofSeconds(2),
            )
        }.isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 operation downloadExact failed (AWS RESPONSE_TOO_LARGE)")
        assertThat(bodyReads).isZero()

        val zeroProgressS3 = mock(S3Client::class.java)
        doAnswer { invocation ->
            val transformer = invocation.getArgument<ResponseTransformer<GetObjectResponse, Any?>>(1)
            transformer.transform(
                GetObjectResponse.builder().versionId("version-1").contentLength(1).build(),
                AbortableInputStream.create(
                    object : InputStream() {
                        override fun read(): Int = 0
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
                    },
                ),
            )
        }.`when`(zeroProgressS3).getObject(
            any(GetObjectRequest::class.java),
            anyGeneric<ResponseTransformer<GetObjectResponse, Any>>(),
        )
        assertThatThrownBy {
            gateway(zeroProgressS3).downloadExact(
                s3Reference("version-1", "a".repeat(64), 1),
                1,
                Duration.ofSeconds(2),
            )
        }.isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 operation downloadExact failed (AWS ZERO_PROGRESS)")

        listOf(oversizeS3, zeroProgressS3).forEach { s3 ->
            val request = ArgumentCaptor.forClass(GetObjectRequest::class.java)
            verify(s3).getObject(
                request.capture(),
                anyGeneric<ResponseTransformer<GetObjectResponse, Any>>(),
            )
            assertThat(request.value.versionId()).isEqualTo("version-1")
            verify(s3, times(0)).getObject(any(GetObjectRequest::class.java))
            verify(s3, times(0)).getObject(any(GetObjectRequest::class.java), any(Path::class.java))
        }
    }

    @Test
    fun `exact byte download rejects a response length shadow`() {
        val bytes = "exact-body".toByteArray()
        val s3 = mock(S3Client::class.java)
        stubManagedGet(
            s3,
            GetObjectResponse.builder().versionId("version-1").contentLength(bytes.size.toLong() + 1).build(),
            bytes,
        )

        assertThatThrownBy {
            gateway(s3).downloadExact(
                s3Reference("version-1", sha256(bytes), bytes.size.toLong()),
                bytes.size.toLong() + 1,
                Duration.ofSeconds(2),
            )
        }.isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 operation downloadExact failed (AWS RESPONSE_SIZE_MISMATCH)")

        val request = ArgumentCaptor.forClass(GetObjectRequest::class.java)
        verify(s3).getObject(request.capture(), anyGeneric<ResponseTransformer<GetObjectResponse, Any>>())
        assertThat(request.value.versionId()).isEqualTo("version-1")
    }

    @Test
    fun `exact reads reject invalid refs delete markers and version shadows without latest fallback`() {
        val bytes = "version-one".toByteArray()
        val reference = s3Reference("version-1", sha256(bytes), bytes.size.toLong())
        val s3 = mock(S3Client::class.java)
        `when`(
            s3.getObject(
                any(GetObjectRequest::class.java),
                anyGeneric<ResponseTransformer<GetObjectResponse, Any>>(),
            ),
        ).thenThrow(
            S3Exception.builder().statusCode(405).awsErrorDetails(
                AwsErrorDetails.builder().errorCode("MethodNotAllowed").errorMessage("delete marker").build(),
            ).build(),
        )
        val gateway = gateway(s3)

        assertThatThrownBy {
            gateway.download(reference, tempDirectory.resolve("delete-marker.zip"), Duration.ofSeconds(3))
        }.isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 operation download failed (AWS MethodNotAllowed)")
            .hasMessageNotContaining("delete marker")
        val request = ArgumentCaptor.forClass(GetObjectRequest::class.java)
        verify(s3, times(1)).getObject(
            request.capture(),
            anyGeneric<ResponseTransformer<GetObjectResponse, Any>>(),
        )
        assertThat(request.value.versionId()).isEqualTo("version-1")

        val untouchedS3 = mock(S3Client::class.java)
        val untouchedGateway = gateway(untouchedS3)
        listOf(
            reference.copy(provider = ArchiveProvider.FILESYSTEM_STAGING),
            reference.copy(bucket = null),
            reference.copy(versionId = null),
            reference.copy(sha256 = "not-a-digest"),
            reference.copy(locator = "s3://archive-bucket/evidence/latest.zip"),
        ).forEach { invalid ->
            assertThatThrownBy {
                untouchedGateway.download(invalid, tempDirectory.resolve("invalid.zip"), Duration.ofSeconds(3))
            }.isInstanceOf(ArchiveUnavailable::class.java)
                .hasMessage("S3 operation download failed (AWS INVALID_REFERENCE)")
        }
        verifyNoInteractions(untouchedS3)
    }

    @Test
    fun `download preserves an existing target without issuing an S3 request`() {
        val remote = "remote-version".toByteArray()
        val existing = "existing-local".toByteArray()
        val target = Files.write(tempDirectory.resolve("existing.zip"), existing)
        val s3 = mock(S3Client::class.java)

        assertThatThrownBy {
            gateway(s3).download(
                s3Reference("version-1", sha256(remote), remote.size.toLong()),
                target,
                Duration.ofSeconds(2),
            )
        }.isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 operation download failed (AWS TARGET_EXISTS)")
        assertThat(Files.readAllBytes(target)).isEqualTo(existing)
        verifyNoInteractions(s3)
    }

    @Test
    fun `failed download removes its partial and never publishes the target`() {
        val expected = "expected-version".toByteArray()
        val corrupt = "corrupt-version!".toByteArray()
        assertThat(corrupt.size).isEqualTo(expected.size)
        val target = tempDirectory.resolve("failed.zip")
        val s3 = mock(S3Client::class.java)
        stubManagedGet(
            s3,
            GetObjectResponse.builder().versionId("version-1").contentLength(corrupt.size.toLong()).build(),
            corrupt,
        )

        assertThatThrownBy {
            gateway(s3).download(
                s3Reference("version-1", sha256(expected), expected.size.toLong()),
                target,
                Duration.ofSeconds(2),
            )
        }.isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 operation download failed (AWS DIGEST_MISMATCH)")
        assertThat(target).doesNotExist()
        Files.list(tempDirectory).use { paths ->
            assertThat(paths.map { it.fileName.toString() }.toList()).noneMatch { it.endsWith(".partial") }
        }
    }

    @Test
    fun `download never replaces a target created while the exact version is in flight`() {
        val remote = "remote-version".toByteArray()
        val concurrent = "local-winner!!".toByteArray()
        assertThat(concurrent.size).isEqualTo(remote.size)
        val target = tempDirectory.resolve("concurrent.zip")
        val s3 = mock(S3Client::class.java)
        doAnswer { invocation ->
            Files.write(target, concurrent)
            applyManagedGet(
                invocation,
                GetObjectResponse.builder().versionId("version-1").contentLength(remote.size.toLong()).build(),
                remote,
            )
        }.`when`(s3).getObject(
            any(GetObjectRequest::class.java),
            anyGeneric<ResponseTransformer<GetObjectResponse, Any>>(),
        )

        assertThatThrownBy {
            gateway(s3).download(
                s3Reference("version-1", sha256(remote), remote.size.toLong()),
                target,
                Duration.ofSeconds(2),
            )
        }.isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 operation download failed (AWS TARGET_EXISTS)")
        assertThat(Files.readAllBytes(target)).isEqualTo(concurrent)
        Files.list(tempDirectory).use { paths ->
            assertThat(paths.map { it.fileName.toString() }.toList()).noneMatch { it.endsWith(".partial") }
        }
    }

    @Test
    fun `put refuses digest mismatch blank version and secret bearing SDK messages`() {
        val bytes = "payload".toByteArray()
        val source = Files.write(tempDirectory.resolve("payload.zip"), bytes)
        val mismatchS3 = mock(S3Client::class.java)
        assertThatThrownBy {
            gateway(mismatchS3).putFileIfAbsent(
                "archive-bucket",
                "evidence/payload.zip",
                source,
                "0".repeat(64),
                Duration.ofSeconds(2),
            )
        }.isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 operation putFileIfAbsent failed (AWS DIGEST_MISMATCH)")
        verifyNoInteractions(mismatchS3)

        listOf(" ", "null").forEach { invalidVersion ->
            val invalidVersionS3 = mock(S3Client::class.java)
            `when`(
                invalidVersionS3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java)),
            ).thenReturn(PutObjectResponse.builder().versionId(invalidVersion).build())
            assertThatThrownBy {
                gateway(invalidVersionS3).putFileIfAbsent(
                    "archive-bucket",
                    "evidence/payload.zip",
                    source,
                    sha256(bytes),
                    Duration.ofSeconds(2),
                )
            }.isInstanceOf(ArchiveUnavailable::class.java)
                .hasMessage("S3 operation putFileIfAbsent failed (AWS VERSION_REQUIRED)")
        }

        val secretS3 = mock(S3Client::class.java)
        `when`(
            secretS3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java)),
        ).thenThrow(
            S3Exception.builder().statusCode(500).awsErrorDetails(
                AwsErrorDetails.builder()
                    .errorCode("InternalError")
                    .errorMessage("https://secret.internal?token=credential")
                    .build(),
            ).build(),
        )
        assertThatThrownBy {
            gateway(secretS3).putFileIfAbsent(
                "archive-bucket",
                "evidence/payload.zip",
                source,
                sha256(bytes),
                Duration.ofSeconds(2),
            )
        }.isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 operation putFileIfAbsent failed (AWS InternalError)")
            .hasMessageNotContaining("secret.internal")
            .hasMessageNotContaining("credential")
    }

    @Test
    fun `file put uploads an immutable gateway snapshot when the source is replaced`() {
        val original = "original-payload".toByteArray()
        val replacement = "replaced-payload".toByteArray()
        assertThat(replacement.size).isEqualTo(original.size)
        val source = Files.write(tempDirectory.resolve("mutable-source.zip"), original)
        var uploaded = ByteArray(0)
        val s3 = mock(S3Client::class.java)
        `when`(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenAnswer { invocation ->
            Files.write(source, replacement)
            uploaded = invocation.getArgument(1, RequestBody::class.java)
                .contentStreamProvider().newStream().use { it.readAllBytes() }
            PutObjectResponse.builder().versionId("snapshot-version-1").build()
        }
        `when`(s3.listObjectVersions(any(ListObjectVersionsRequest::class.java))).thenReturn(
            exactCreatedHistory("acceptance/payload.zip", "snapshot-version-1", original.size.toLong()),
        )
        `when`(s3.headObject(any(HeadObjectRequest::class.java))).thenReturn(
            HeadObjectResponse.builder()
                .versionId("snapshot-version-1")
                .contentLength(original.size.toLong())
                .metadata(mapOf("sha256" to sha256(original)))
                .build(),
        )

        val reference = gateway(s3).putFileIfAbsent(
            BUCKET,
            "acceptance/payload.zip",
            source,
            sha256(original),
            Duration.ofSeconds(2),
        )

        assertThat(uploaded).isEqualTo(original)
        assertThat(Files.readAllBytes(source)).isEqualTo(replacement)
        assertThat(reference.sha256).isEqualTo(sha256(original))
        assertThat(reference.sizeBytes).isEqualTo(original.size.toLong())
    }

    @Test
    fun `successful file and JSON puts reject prior versions or delete markers`() {
        val bytes = "create-only-object".toByteArray()
        val digest = sha256(bytes)
        listOf(false, true).forEach { priorIsDeleteMarker ->
            listOf(false, true).forEach { isFilePut ->
                val key = if (isFilePut) "evidence/file.zip" else "acceptance/result.json"
                val versionId = if (isFilePut) "file-version-1" else "json-version-1"
                val s3 = mock(S3Client::class.java)
                `when`(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenReturn(
                    PutObjectResponse.builder().versionId(versionId).build(),
                )
                `when`(s3.listObjectVersions(any(ListObjectVersionsRequest::class.java))).thenReturn(
                    invalidCreatedHistory(key, versionId, bytes.size.toLong(), priorIsDeleteMarker),
                )
                val gateway = gateway(s3)

                assertThatThrownBy {
                    if (isFilePut) {
                        gateway.putFileIfAbsent(
                            BUCKET,
                            key,
                            Files.write(tempDirectory.resolve("history-$priorIsDeleteMarker.zip"), bytes),
                            digest,
                            OPERATION_TIMEOUT,
                        )
                    } else {
                        gateway.putJsonIfAbsent(BUCKET, key, bytes, digest, OPERATION_TIMEOUT)
                    }
                }.isInstanceOf(ArchiveUnavailable::class.java)
                    .hasMessageContaining("POST_PUT_HISTORY_INVALID")

                val list = ArgumentCaptor.forClass(ListObjectVersionsRequest::class.java)
                verify(s3).listObjectVersions(list.capture())
                assertThat(list.value.prefix()).isEqualTo(key)
                assertRequestTimeout(list.value, OPERATION_TIMEOUT)
            }
        }
    }

    @Test
    fun `successful create only put requires exact digest metadata on its returned version`() {
        val bytes = "metadata-bound".toByteArray()
        val digest = sha256(bytes)
        val key = "acceptance/metadata.json"
        val s3 = mock(S3Client::class.java)
        `when`(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenReturn(
            PutObjectResponse.builder().versionId("metadata-version-1").build(),
        )
        `when`(s3.listObjectVersions(any(ListObjectVersionsRequest::class.java))).thenReturn(
            exactCreatedHistory(key, "metadata-version-1", bytes.size.toLong()),
        )
        `when`(s3.headObject(any(HeadObjectRequest::class.java))).thenReturn(
            HeadObjectResponse.builder()
                .versionId("metadata-version-1")
                .contentLength(bytes.size.toLong())
                .metadata(mapOf("sha256" to "f".repeat(64)))
                .build(),
        )

        assertThatThrownBy {
            gateway(s3).putJsonIfAbsent(BUCKET, key, bytes, digest, OPERATION_TIMEOUT)
        }.isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 operation putJsonIfAbsent failed (AWS POST_PUT_METADATA_INVALID)")

        val head = ArgumentCaptor.forClass(HeadObjectRequest::class.java)
        verify(s3).headObject(head.capture())
        assertThat(head.value.versionId()).isEqualTo("metadata-version-1")
        assertRequestTimeout(head.value, OPERATION_TIMEOUT)
    }

    @Test
    fun `head protection requires exact size and metadata digest`() {
        val bytes = "protected bytes".toByteArray()
        val reference = s3Reference("payload-version-1", sha256(bytes), bytes.size.toLong())
        listOf(
            HeadObjectResponse.builder()
                .versionId("payload-version-1")
                .contentLength(bytes.size.toLong())
                .metadata(mapOf("sha256" to "f".repeat(64)))
                .build() to "DIGEST_MISMATCH",
            HeadObjectResponse.builder()
                .versionId("payload-version-1")
                .metadata(mapOf("sha256" to reference.sha256))
                .build() to "REFERENCE_MISMATCH",
        ).forEach { (response, errorCode) ->
            val s3 = mock(S3Client::class.java)
            `when`(s3.headObject(any(HeadObjectRequest::class.java))).thenReturn(response)

            assertThatThrownBy { gateway(s3).headProtection(reference, Duration.ofSeconds(2)) }
                .isInstanceOf(ArchiveUnavailable::class.java)
                .hasMessage("S3 operation headProtection failed (AWS $errorCode)")
        }
    }

    @Test
    fun `create only replay resolves one exact matching version and rejects shadows or delete markers`() {
        val bytes = "canonical receipt".toByteArray()
        val digest = sha256(bytes)
        val replayS3 = mock(S3Client::class.java)
        `when`(replayS3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenThrow(
            preconditionFailed(),
        )
        `when`(replayS3.listObjectVersions(any(ListObjectVersionsRequest::class.java))).thenReturn(
            ListObjectVersionsResponse.builder()
                .versions(
                    ObjectVersion.builder()
                        .key("acceptance/receipt.json")
                        .versionId("receipt-version-1")
                        .size(bytes.size.toLong())
                        .build(),
                )
                .isTruncated(false)
                .build(),
        )
        stubManagedGet(
            replayS3,
            GetObjectResponse.builder()
                .versionId("receipt-version-1")
                .contentLength(bytes.size.toLong())
                .build(),
            bytes,
        )

        val reference = gateway(replayS3).putJsonIfAbsent(
            BUCKET,
            "acceptance/receipt.json",
            bytes,
            digest,
            Duration.ofSeconds(7),
        )

        assertThat(reference.versionId).isEqualTo("receipt-version-1")
        assertThat(reference.sha256).isEqualTo(digest)
        val put = ArgumentCaptor.forClass(PutObjectRequest::class.java)
        verify(replayS3).putObject(put.capture(), any(RequestBody::class.java))
        assertThat(put.value.ifNoneMatch()).isEqualTo("*")
        assertRequestTimeout(put.value, Duration.ofSeconds(7))
        val list = ArgumentCaptor.forClass(ListObjectVersionsRequest::class.java)
        verify(replayS3).listObjectVersions(list.capture())
        assertRequestTimeout(list.value, Duration.ofSeconds(7))
        val get = ArgumentCaptor.forClass(GetObjectRequest::class.java)
        verify(replayS3).getObject(get.capture(), anyGeneric<ResponseTransformer<GetObjectResponse, Any>>())
        verify(replayS3, times(0)).getObject(any(GetObjectRequest::class.java))
        assertThat(get.value.versionId()).isEqualTo("receipt-version-1")
        assertRequestTimeout(get.value, Duration.ofSeconds(7))

        listOf(true, false).forEach { shadowVersion ->
            val conflictS3 = mock(S3Client::class.java)
            `when`(
                conflictS3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java)),
            ).thenThrow(preconditionFailed())
            val builder = ListObjectVersionsResponse.builder()
                .versions(
                    ObjectVersion.builder()
                        .key("acceptance/receipt.json")
                        .versionId("receipt-version-1")
                        .size(bytes.size.toLong())
                        .build(),
                )
                .isTruncated(false)
            if (shadowVersion) {
                builder.versions(
                    ObjectVersion.builder()
                        .key("acceptance/receipt.json")
                        .versionId("receipt-version-1")
                        .size(bytes.size.toLong())
                        .build(),
                    ObjectVersion.builder()
                        .key("acceptance/receipt.json")
                        .versionId("shadow-version-2")
                        .size(bytes.size.toLong())
                        .build(),
                )
            } else {
                builder.deleteMarkers(
                    DeleteMarkerEntry.builder()
                        .key("acceptance/receipt.json")
                        .versionId("delete-marker-2")
                        .build(),
                )
            }
            `when`(conflictS3.listObjectVersions(any(ListObjectVersionsRequest::class.java))).thenReturn(builder.build())

            assertThatThrownBy {
                gateway(conflictS3).putJsonIfAbsent(
                    BUCKET,
                    "acceptance/receipt.json",
                    bytes,
                    digest,
                    Duration.ofSeconds(7),
                )
            }.isInstanceOf(ArchiveUnavailable::class.java)
                .hasMessage("S3 operation putJsonIfAbsent failed (AWS CONFLICT_UNVERIFIED)")
            verify(conflictS3, times(0)).getObject(any(GetObjectRequest::class.java))
        }
    }

    @Test
    fun `control winner mutates only target and persists identity bound record before snapshot`() {
        val s3 = mock(S3Client::class.java)
        stubBucketControls(s3)
        val putRequests = mutableListOf<PutObjectRequest>()
        val resultBodies = mutableListOf<ByteArray>()
        `when`(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenAnswer { invocation ->
            val request = invocation.getArgument(0, PutObjectRequest::class.java)
            val body = invocation.getArgument(1, RequestBody::class.java)
            putRequests += request
            when {
                request.key() == TARGET_KEY && request.ifNoneMatch() == "*" ->
                    PutObjectResponse.builder().versionId("target-version-1").build()
                request.key() == TARGET_KEY -> throw denied("AccessDenied")
                request.key() == RESULT_KEY && request.ifNoneMatch() == "*" -> {
                    resultBodies += body.contentStreamProvider().newStream().readAllBytes()
                    PutObjectResponse.builder().versionId("result-version-1").build()
                }
                else -> error("Unexpected PutObject target")
            }
        }
        `when`(s3.deleteObject(any(DeleteObjectRequest::class.java))).thenThrow(denied("AccessDenied"))
        `when`(s3.putObjectRetention(any(PutObjectRetentionRequest::class.java))).thenThrow(denied("AccessDenied"))
        stubSuccessfulControlPostPutValidation(s3, resultBodies)
        val gateway = gateway(s3)

        val snapshot = gateway.controls(
            bucket = BUCKET,
            targetKey = TARGET_KEY,
            resultKey = RESULT_KEY,
            policyFingerprint = POLICY_FINGERPRINT,
            identity = IDENTITY,
            utcDate = UTC_DATE,
            requiredRetainUntil = REQUIRED_RETAIN_UNTIL,
            validUntil = VALID_UNTIL,
            timeout = PROBE_TIMEOUT,
        )

        assertThat(snapshot.reachable).isTrue()
        assertThat(snapshot.encrypted).isTrue()
        assertThat(snapshot.privateAccess).isTrue()
        assertThat(snapshot.versioningEnabled).isTrue()
        assertThat(snapshot.objectLockEnabled).isTrue()
        assertThat(snapshot.defaultRetentionDays).isEqualTo(365)
        assertThat(snapshot.controlObjectProtection)
            .isEqualTo(ObjectProtectionSnapshot("COMPLIANCE", REQUIRED_RETAIN_UNTIL))
        val daily = requireNotNull(snapshot.dailyControl)
        assertThat(daily.record.identity).isEqualTo(IDENTITY)
        assertThat(daily.record.overwrite).isEqualTo(MutationCheckResult.DENIED_AS_EXPECTED)
        assertThat(daily.record.delete).isEqualTo(MutationCheckResult.DENIED_AS_EXPECTED)
        assertThat(daily.record.bypass).isEqualTo(MutationCheckResult.DENIED_AS_EXPECTED)
        assertThat(daily.resultReference.versionId).isEqualTo("result-version-1")
        assertThat(daily.resultReference.sha256).isEqualTo(sha256(resultBodies.single()))
        assertThat(resultBodies.single())
            .isEqualTo(canonicalDailyControlRecordBytes(jacksonObjectMapper().findAndRegisterModules(), daily.record))
        assertThat(String(resultBodies.single())).doesNotContain(
            "resultReference",
            "result-version-1",
            daily.resultReference.sha256,
        )
        assertThat(putRequests.map { it.key() }).containsExactly(TARGET_KEY, TARGET_KEY, RESULT_KEY)
        assertThat(putRequests).allSatisfy { assertRequestTimeout(it, PROBE_TIMEOUT) }

        val deletes = ArgumentCaptor.forClass(DeleteObjectRequest::class.java)
        verify(s3, times(2)).deleteObject(deletes.capture())
        assertThat(deletes.allValues).allSatisfy {
            assertThat(it.key()).isEqualTo(TARGET_KEY)
            assertRequestTimeout(it, PROBE_TIMEOUT)
        }
        assertThat(deletes.allValues.map { it.versionId() }).containsExactly(null, "target-version-1")
        val bypass = ArgumentCaptor.forClass(PutObjectRetentionRequest::class.java)
        verify(s3).putObjectRetention(bypass.capture())
        assertThat(bypass.value.key()).isEqualTo(TARGET_KEY)
        assertThat(bypass.value.versionId()).isEqualTo("target-version-1")
        assertRequestTimeout(bypass.value, PROBE_TIMEOUT)
        verifyControlTimeouts(s3)
    }

    @Test
    fun `daily control canonical JSON and SHA match the fixed UTF8 vector`() {
        val expectedJson = """
            {
            "bypass":"DENIED_AS_EXPECTED",
            "delete":"DENIED_AS_EXPECTED",
            "identity":{"principalFingerprint":"b74043d59f26d8c9d3fdeeddc1357896026f410eed0954eaea87707ac8091326","provider":"S3_COMPATIBLE"},
            "overwrite":"DENIED_AS_EXPECTED",
            "policyFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "target":{"bucket":"archive-bucket","key":"acceptance/capability-probe/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/b74043d59f26d8c9d3fdeeddc1357896026f410eed0954eaea87707ac8091326/2026-08-26/target.json","locator":"s3://archive-bucket/acceptance/capability-probe/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/b74043d59f26d8c9d3fdeeddc1357896026f410eed0954eaea87707ac8091326/2026-08-26/target.json","provider":"S3_COMPATIBLE","sha256":"da703450f9c39ca04ee7ded8c713b049c35544a5d43a5133c81485c08ad534d7","sizeBytes":50,"versionId":"target-version-1"},
            "utcDate":"2026-08-26",
            "validUntil":"2026-08-27T00:00:00Z"
            }
        """.trimIndent().lineSequence().joinToString("") { it.trim() }.toByteArray(Charsets.UTF_8)

        val actual = canonicalDailyControlRecordBytes(
            jacksonObjectMapper().findAndRegisterModules(),
            dailyRecord(controlTargetReference()),
        )

        assertThat(actual).isEqualTo(expectedJson)
        assertThat(sha256(actual)).isEqualTo("c219de7ddeb899ec1551eadc6034f8e405531ce01655024262a45165f03cc1bf")
    }

    @Test
    fun `mutation network 5xx and unknown failures remain indeterminate`() {
        val s3 = mock(S3Client::class.java)
        stubBucketControls(s3)
        val resultBodies = mutableListOf<ByteArray>()
        `when`(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenAnswer { invocation ->
            val request = invocation.getArgument(0, PutObjectRequest::class.java)
            val body = invocation.getArgument(1, RequestBody::class.java)
            when {
                request.key() == TARGET_KEY && request.ifNoneMatch() == "*" ->
                    PutObjectResponse.builder().versionId("target-version-1").build()
                request.key() == TARGET_KEY -> throw SdkClientException.create("network secret")
                request.key() == RESULT_KEY -> {
                    resultBodies += body.contentStreamProvider().newStream().readAllBytes()
                    PutObjectResponse.builder().versionId("result-version-1").build()
                }
                else -> error("Unexpected PutObject target")
            }
        }
        `when`(s3.deleteObject(any(DeleteObjectRequest::class.java))).thenThrow(
            S3Exception.builder().statusCode(503).awsErrorDetails(
                AwsErrorDetails.builder().errorCode("SlowDown").build(),
            ).build(),
        )
        `when`(s3.putObjectRetention(any(PutObjectRetentionRequest::class.java))).thenThrow(
            S3Exception.builder().statusCode(409).awsErrorDetails(
                AwsErrorDetails.builder().errorCode("Conflict").build(),
            ).build(),
        )
        stubSuccessfulControlPostPutValidation(s3, resultBodies)

        val record = requireNotNull(
            gateway(s3).controls(
                BUCKET,
                TARGET_KEY,
                RESULT_KEY,
                POLICY_FINGERPRINT,
                IDENTITY,
                UTC_DATE,
                REQUIRED_RETAIN_UNTIL,
                VALID_UNTIL,
                PROBE_TIMEOUT,
            ).dailyControl,
        ).record

        assertThat(record.overwrite).isEqualTo(MutationCheckResult.INDETERMINATE)
        assertThat(record.delete).isEqualTo(MutationCheckResult.INDETERMINATE)
        assertThat(record.bypass).isEqualTo(MutationCheckResult.INDETERMINATE)
    }

    @Test
    fun `delete control is allowed when marker creation succeeds but version deletion is denied`() {
        val s3 = mock(S3Client::class.java)
        stubBucketControls(s3)
        val resultBodies = mutableListOf<ByteArray>()
        `when`(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenAnswer { invocation ->
            val request = invocation.getArgument(0, PutObjectRequest::class.java)
            val body = invocation.getArgument(1, RequestBody::class.java)
            when {
                request.key() == TARGET_KEY && request.ifNoneMatch() == "*" ->
                    PutObjectResponse.builder().versionId("target-version-1").build()
                request.key() == TARGET_KEY -> throw denied("AccessDenied")
                request.key() == RESULT_KEY -> {
                    resultBodies += body.contentStreamProvider().newStream().readAllBytes()
                    PutObjectResponse.builder().versionId("result-version-1").build()
                }
                else -> error("Unexpected PutObject target")
            }
        }
        `when`(s3.deleteObject(any(DeleteObjectRequest::class.java))).thenAnswer { invocation ->
            val request = invocation.getArgument(0, DeleteObjectRequest::class.java)
            if (request.versionId() == null) DeleteObjectResponse.builder().build() else throw denied("AccessDenied")
        }
        `when`(s3.putObjectRetention(any(PutObjectRetentionRequest::class.java))).thenThrow(denied("AccessDenied"))
        stubSuccessfulControlPostPutValidation(s3, resultBodies)

        val record = requireNotNull(
            gateway(s3).controls(
                BUCKET,
                TARGET_KEY,
                RESULT_KEY,
                POLICY_FINGERPRINT,
                IDENTITY,
                UTC_DATE,
                REQUIRED_RETAIN_UNTIL,
                VALID_UNTIL,
                PROBE_TIMEOUT,
            ).dailyControl,
        ).record

        assertThat(record.delete).isEqualTo(MutationCheckResult.ALLOWED)
        val deletes = ArgumentCaptor.forClass(DeleteObjectRequest::class.java)
        verify(s3, times(2)).deleteObject(deletes.capture())
        assertThat(deletes.allValues.map { it.versionId() }).containsExactly(null, "target-version-1")
    }

    @Test
    fun `successful control target put rejects prior versions or delete markers before mutations`() {
        listOf(false, true).forEach { priorIsDeleteMarker ->
            val s3 = mock(S3Client::class.java)
            stubBucketControls(s3)
            `when`(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenAnswer { invocation ->
                val request = invocation.getArgument(0, PutObjectRequest::class.java)
                when {
                    request.key() == TARGET_KEY && request.ifNoneMatch() == "*" ->
                        PutObjectResponse.builder().versionId("target-version-1").build()
                    request.key() == TARGET_KEY -> throw denied("AccessDenied")
                    request.key() == RESULT_KEY -> PutObjectResponse.builder().versionId("result-version-1").build()
                    else -> error("Unexpected PutObject target")
                }
            }
            val history = ListObjectVersionsResponse.builder()
                .versions(
                    ObjectVersion.builder()
                        .key(TARGET_KEY)
                        .versionId("target-version-1")
                        .size(CONTROL_TARGET_BYTES.size.toLong())
                        .build(),
                )
                .isTruncated(false)
            if (priorIsDeleteMarker) {
                history.deleteMarkers(
                    DeleteMarkerEntry.builder().key(TARGET_KEY).versionId("old-marker").build(),
                )
            } else {
                history.versions(
                    ObjectVersion.builder()
                        .key(TARGET_KEY)
                        .versionId("target-version-1")
                        .size(CONTROL_TARGET_BYTES.size.toLong())
                        .build(),
                    ObjectVersion.builder().key(TARGET_KEY).versionId("old-version").size(1).build(),
                )
            }
            `when`(s3.listObjectVersions(any(ListObjectVersionsRequest::class.java))).thenReturn(history.build())
            `when`(s3.headObject(any(HeadObjectRequest::class.java))).thenReturn(controlTargetHead())
            `when`(s3.deleteObject(any(DeleteObjectRequest::class.java))).thenThrow(denied("AccessDenied"))
            `when`(s3.putObjectRetention(any(PutObjectRetentionRequest::class.java))).thenThrow(denied("AccessDenied"))

            assertThatThrownBy {
                gateway(s3).controls(
                    BUCKET,
                    TARGET_KEY,
                    RESULT_KEY,
                    POLICY_FINGERPRINT,
                    IDENTITY,
                    UTC_DATE,
                    REQUIRED_RETAIN_UNTIL,
                    VALID_UNTIL,
                    PROBE_TIMEOUT,
                )
            }.isInstanceOf(ArchiveUnavailable::class.java)
                .hasMessage("S3 operation controls failed (AWS POST_PUT_HISTORY_INVALID)")
            verify(s3, times(0)).headObject(any(HeadObjectRequest::class.java))
            verify(s3, times(0)).deleteObject(any(DeleteObjectRequest::class.java))
            verify(s3, times(0)).putObjectRetention(any(PutObjectRetentionRequest::class.java))
        }
    }

    @Test
    fun `successful control result put rejects prior versions or delete markers before snapshot`() {
        listOf(false, true).forEach { priorIsDeleteMarker ->
            val s3 = mock(S3Client::class.java)
            stubBucketControls(s3)
            `when`(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenAnswer { invocation ->
                val request = invocation.getArgument(0, PutObjectRequest::class.java)
                when {
                    request.key() == TARGET_KEY && request.ifNoneMatch() == "*" ->
                        PutObjectResponse.builder().versionId("target-version-1").build()
                    request.key() == TARGET_KEY -> throw denied("AccessDenied")
                    request.key() == RESULT_KEY -> PutObjectResponse.builder().versionId("result-version-1").build()
                    else -> error("Unexpected PutObject target")
                }
            }
            `when`(s3.listObjectVersions(any(ListObjectVersionsRequest::class.java))).thenAnswer { invocation ->
                val request = invocation.getArgument(0, ListObjectVersionsRequest::class.java)
                if (request.prefix() == TARGET_KEY) {
                    exactCreatedHistory(TARGET_KEY, "target-version-1", CONTROL_TARGET_BYTES.size.toLong())
                } else {
                    invalidCreatedHistory(RESULT_KEY, "result-version-1", 1, priorIsDeleteMarker)
                }
            }
            `when`(s3.headObject(any(HeadObjectRequest::class.java))).thenReturn(controlTargetHead())
            `when`(s3.deleteObject(any(DeleteObjectRequest::class.java))).thenThrow(denied("AccessDenied"))
            `when`(s3.putObjectRetention(any(PutObjectRetentionRequest::class.java))).thenThrow(denied("AccessDenied"))

            assertThatThrownBy {
                gateway(s3).controls(
                    BUCKET,
                    TARGET_KEY,
                    RESULT_KEY,
                    POLICY_FINGERPRINT,
                    IDENTITY,
                    UTC_DATE,
                    REQUIRED_RETAIN_UNTIL,
                    VALID_UNTIL,
                    PROBE_TIMEOUT,
                )
            }.isInstanceOf(ArchiveUnavailable::class.java)
                .hasMessage("S3 operation controls failed (AWS POST_PUT_HISTORY_INVALID)")

            val lists = ArgumentCaptor.forClass(ListObjectVersionsRequest::class.java)
            verify(s3, times(2)).listObjectVersions(lists.capture())
            assertThat(lists.allValues.map { it.prefix() }).containsExactly(TARGET_KEY, RESULT_KEY)
            lists.allValues.forEach { assertRequestTimeout(it, PROBE_TIMEOUT) }
        }
    }

    @Test
    fun `control rejects identity or evidence key mismatch before any request`() {
        val invalidFingerprint = IDENTITY.copy(principalFingerprint = "INVALID")
        listOf(
            ControlInput(identity = invalidFingerprint),
            ControlInput(targetKey = "evidence/payload.zip"),
            ControlInput(resultKey = RESULT_KEY.replace(POLICY_FINGERPRINT, "f".repeat(64))),
            ControlInput(targetKey = TARGET_KEY.replace(UTC_DATE.toString(), "2026-08-25")),
        ).forEach { input ->
            val s3 = mock(S3Client::class.java)
            assertThatThrownBy {
                gateway(s3).controls(
                    BUCKET,
                    input.targetKey,
                    input.resultKey,
                    POLICY_FINGERPRINT,
                    input.identity,
                    UTC_DATE,
                    REQUIRED_RETAIN_UNTIL,
                    VALID_UNTIL,
                    PROBE_TIMEOUT,
                )
            }.isInstanceOf(ArchiveUnavailable::class.java)
                .hasMessage("S3 operation controls failed (AWS INVALID_CONTROL_BINDING)")
            verifyNoInteractions(s3)
        }
    }

    @Test
    fun `control keys reject reserved duplicate or oversized prefixes before S3 access`() {
        val binding = "capability-probe/$POLICY_FINGERPRINT/$PRINCIPAL_FINGERPRINT/$UTC_DATE"
        listOf(
            "evidence/$binding",
            "acceptance/capability-probe/$binding",
            "${"测".repeat(400)}/$binding",
        ).forEach { invalidPrefix ->
            val s3 = mock(S3Client::class.java)
            assertThatThrownBy {
                gateway(s3).controls(
                    BUCKET,
                    "$invalidPrefix/target.json",
                    "$invalidPrefix/result.json",
                    POLICY_FINGERPRINT,
                    IDENTITY,
                    UTC_DATE,
                    REQUIRED_RETAIN_UNTIL,
                    VALID_UNTIL,
                    PROBE_TIMEOUT,
                )
            }.isInstanceOf(ArchiveUnavailable::class.java)
                .hasMessage("S3 operation controls failed (AWS INVALID_CONTROL_BINDING)")
            verifyNoInteractions(s3)
        }
    }

    @Test
    fun `control reattests and rejects an identity change or failure before any S3 request`() {
        val claims = listOf(
            ProviderAttestedIdentity("private", "tenant-a", "service-account", "release/account-a"),
            ProviderAttestedIdentity("private", "tenant-b", "service-account", "release/account-b"),
        )
        var attestations = 0
        val s3 = mock(S3Client::class.java)
        val gateway = AwsS3Gateway(
            s3,
            jacksonObjectMapper().findAndRegisterModules(),
            ProviderIdentityAttestor { claims[attestations++] },
        )
        val identity = gateway.runtimeIdentity(PROBE_TIMEOUT)
        val binding = "acceptance/capability-probe/$POLICY_FINGERPRINT/${identity.principalFingerprint}/$UTC_DATE"

        assertThatThrownBy {
            gateway.controls(
                BUCKET,
                "$binding/target.json",
                "$binding/result.json",
                POLICY_FINGERPRINT,
                identity,
                UTC_DATE,
                REQUIRED_RETAIN_UNTIL,
                VALID_UNTIL,
                PROBE_TIMEOUT,
            )
        }.isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 operation controls failed (AWS IDENTITY_MISMATCH)")
        assertThat(attestations).isEqualTo(2)
        verifyNoInteractions(s3)

        val unavailableS3 = mock(S3Client::class.java)
        var unavailableAttestations = 0
        val unavailableGateway = AwsS3Gateway(
            unavailableS3,
            jacksonObjectMapper().findAndRegisterModules(),
            ProviderIdentityAttestor {
                if (unavailableAttestations++ == 0) claims.first() else throw SdkClientException.create("secret")
            },
        )
        val stableIdentity = unavailableGateway.runtimeIdentity(PROBE_TIMEOUT)
        val stableBinding =
            "acceptance/capability-probe/$POLICY_FINGERPRINT/${stableIdentity.principalFingerprint}/$UTC_DATE"
        assertThatThrownBy {
            unavailableGateway.controls(
                BUCKET,
                "$stableBinding/target.json",
                "$stableBinding/result.json",
                POLICY_FINGERPRINT,
                stableIdentity,
                UTC_DATE,
                REQUIRED_RETAIN_UNTIL,
                VALID_UNTIL,
                PROBE_TIMEOUT,
            )
        }.isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 operation identity failed (AWS SDK_CLIENT_ERROR)")
            .hasMessageNotContaining("secret")
        verifyNoInteractions(unavailableS3)
    }

    @Test
    fun `control loser reads one exact result version and validates record bindings without mutations`() {
        val s3 = mock(S3Client::class.java)
        stubBucketControls(s3)
        val target = controlTargetReference()
        val record = dailyRecord(target)
        val bytes = canonicalDailyControlRecordBytes(jacksonObjectMapper().findAndRegisterModules(), record)
        `when`(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenThrow(
            preconditionFailed(),
        )
        `when`(s3.listObjectVersions(any(ListObjectVersionsRequest::class.java))).thenReturn(
            ListObjectVersionsResponse.builder()
                .versions(
                    ObjectVersion.builder()
                        .key(RESULT_KEY)
                        .versionId("result-version-1")
                        .size(bytes.size.toLong())
                        .build(),
                )
                .isTruncated(false)
                .build(),
        )
        stubManagedGet(
            s3,
            GetObjectResponse.builder()
                .versionId("result-version-1")
                .contentLength(bytes.size.toLong())
                .build(),
            bytes,
        )
        `when`(s3.headObject(any(HeadObjectRequest::class.java))).thenReturn(controlTargetHead())

        val snapshot = gateway(s3).controls(
            BUCKET,
            TARGET_KEY,
            RESULT_KEY,
            POLICY_FINGERPRINT,
            IDENTITY,
            UTC_DATE,
            REQUIRED_RETAIN_UNTIL,
            VALID_UNTIL,
            PROBE_TIMEOUT,
        )

        assertThat(snapshot.dailyControl?.record).isEqualTo(record)
        assertThat(snapshot.dailyControl?.resultReference).isEqualTo(
            StoredObjectRef(
                ArchiveProvider.S3_COMPATIBLE,
                "s3://$BUCKET/$RESULT_KEY",
                BUCKET,
                RESULT_KEY,
                "result-version-1",
                sha256(bytes),
                bytes.size.toLong(),
            ),
        )
        assertThat(snapshot.controlObjectProtection)
            .isEqualTo(ObjectProtectionSnapshot("COMPLIANCE", REQUIRED_RETAIN_UNTIL))
        verify(s3, times(1)).putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))
        verify(s3, times(0)).deleteObject(any(DeleteObjectRequest::class.java))
        verify(s3, times(0)).putObjectRetention(any(PutObjectRetentionRequest::class.java))

        val list = ArgumentCaptor.forClass(ListObjectVersionsRequest::class.java)
        verify(s3).listObjectVersions(list.capture())
        assertThat(list.value.prefix()).isEqualTo(RESULT_KEY)
        assertRequestTimeout(list.value, PROBE_TIMEOUT)
        val get = ArgumentCaptor.forClass(GetObjectRequest::class.java)
        verify(s3).getObject(get.capture(), anyGeneric<ResponseTransformer<GetObjectResponse, Any>>())
        verify(s3, times(0)).getObject(any(GetObjectRequest::class.java))
        assertThat(get.value.key()).isEqualTo(RESULT_KEY)
        assertThat(get.value.versionId()).isEqualTo("result-version-1")
        assertRequestTimeout(get.value, PROBE_TIMEOUT)
    }

    @Test
    fun `control loser rejects declared or streamed result bytes above the bound before parsing`() {
        val maximumSize = 64 * 1024
        val oversized = ByteArray(maximumSize + 16 * 1024) { 'x'.code.toByte() }
        listOf(maximumSize.toLong() + 1, 1L).forEach { declaredSize ->
            val s3 = mock(S3Client::class.java)
            stubBucketControls(s3)
            `when`(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenThrow(
                preconditionFailed(),
            )
            `when`(s3.listObjectVersions(any(ListObjectVersionsRequest::class.java))).thenReturn(
                ListObjectVersionsResponse.builder()
                    .versions(
                        ObjectVersion.builder()
                            .key(RESULT_KEY)
                            .versionId("oversized-result-version")
                            .size(declaredSize)
                            .build(),
                    )
                    .isTruncated(false)
                    .build(),
            )
            val response = GetObjectResponse.builder()
                .versionId("oversized-result-version")
                .contentLength(declaredSize)
                .build()
            var streamedBytes = 0
            doAnswer { invocation ->
                val transformer = invocation.getArgument<ResponseTransformer<GetObjectResponse, Any?>>(1)
                val countedBody = object : ByteArrayInputStream(oversized) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        val read = super.read(bytes, offset, length)
                        if (read > 0) streamedBytes += read
                        return read
                    }
                }
                transformer.transform(response, AbortableInputStream.create(countedBody))
            }.`when`(s3).getObject(
                any(GetObjectRequest::class.java),
                anyGeneric<ResponseTransformer<GetObjectResponse, Any>>(),
            )

            val snapshot = gateway(s3).controls(
                BUCKET,
                TARGET_KEY,
                RESULT_KEY,
                POLICY_FINGERPRINT,
                IDENTITY,
                UTC_DATE,
                REQUIRED_RETAIN_UNTIL,
                VALID_UNTIL,
                PROBE_TIMEOUT,
            )

            assertThat(snapshot.dailyControl).isNull()
            verify(s3, times(if (declaredSize > maximumSize) 0 else 1))
                .getObject(
                    any(GetObjectRequest::class.java),
                    anyGeneric<ResponseTransformer<GetObjectResponse, Any>>(),
                )
            assertThat(streamedBytes).isEqualTo(if (declaredSize > maximumSize) 0 else maximumSize + 1)
            verify(s3, times(0)).getObject(any(GetObjectRequest::class.java))
        }
    }

    @Test
    fun `control loser rejects missing response length before reading its managed stream`() {
        val s3 = mock(S3Client::class.java)
        stubBucketControls(s3)
        `when`(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenThrow(
            preconditionFailed(),
        )
        `when`(s3.listObjectVersions(any(ListObjectVersionsRequest::class.java))).thenReturn(
            exactCreatedHistory(RESULT_KEY, "result-version-1", 1),
        )
        var reads = 0
        doAnswer { invocation ->
            val transformer = invocation.getArgument<ResponseTransformer<GetObjectResponse, Any?>>(1)
            val input = AbortableInputStream.create(
                object : InputStream() {
                    override fun read(): Int {
                        reads += 1
                        error("body must not be read without a declared response length")
                    }
                },
            )
            transformer.transform(GetObjectResponse.builder().versionId("result-version-1").build(), input)
        }.`when`(s3).getObject(
            any(GetObjectRequest::class.java),
            anyGeneric<ResponseTransformer<GetObjectResponse, Any>>(),
        )

        val snapshot = gateway(s3).controls(
            BUCKET,
            TARGET_KEY,
            RESULT_KEY,
            POLICY_FINGERPRINT,
            IDENTITY,
            UTC_DATE,
            REQUIRED_RETAIN_UNTIL,
            VALID_UNTIL,
            PROBE_TIMEOUT,
        )

        assertThat(snapshot.dailyControl).isNull()
        assertThat(reads).isZero()
        verify(s3).getObject(
            any(GetObjectRequest::class.java),
            anyGeneric<ResponseTransformer<GetObjectResponse, Any>>(),
        )
    }

    @Test
    fun `managed GET timeout or transformer failure fails closed for every body consumer`() {
        listOf(
            Triple(ApiCallTimeoutException.create(1), "SDK_CLIENT_ERROR", "SDK_CLIENT_ERROR"),
            Triple(IOException("opaque-transformer-failure"), "IO_ERROR", "CONFLICT_UNVERIFIED"),
        ).forEachIndexed { index, (failure, downloadCode, conflictCode) ->
            val downloadS3 = mock(S3Client::class.java)
            stubManagedGetFailure(downloadS3, failure)
            assertThatThrownBy {
                gateway(downloadS3).download(
                    s3Reference("version-1", "a".repeat(64), 1),
                    tempDirectory.resolve("managed-failure-$index.zip"),
                    OPERATION_TIMEOUT,
                )
            }.isInstanceOf(ArchiveUnavailable::class.java)
                .hasMessage("S3 operation download failed (AWS $downloadCode)")
                .hasMessageNotContaining("opaque-transformer-failure")

            val conflictS3 = mock(S3Client::class.java)
            `when`(conflictS3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenThrow(
                preconditionFailed(),
            )
            val conflictKey = "acceptance/managed-failure-$index.json"
            `when`(conflictS3.listObjectVersions(any(ListObjectVersionsRequest::class.java))).thenReturn(
                exactCreatedHistory(conflictKey, "version-1", 1),
            )
            stubManagedGetFailure(conflictS3, failure)
            assertThatThrownBy {
                gateway(conflictS3).putJsonIfAbsent(
                    BUCKET,
                    conflictKey,
                    byteArrayOf(1),
                    sha256(byteArrayOf(1)),
                    OPERATION_TIMEOUT,
                )
            }.isInstanceOf(ArchiveUnavailable::class.java)
                .hasMessage("S3 operation putJsonIfAbsent failed (AWS $conflictCode)")
                .hasMessageNotContaining("opaque-transformer-failure")

            val loserS3 = mock(S3Client::class.java)
            stubBucketControls(loserS3)
            `when`(loserS3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenThrow(
                preconditionFailed(),
            )
            `when`(loserS3.listObjectVersions(any(ListObjectVersionsRequest::class.java))).thenReturn(
                exactCreatedHistory(RESULT_KEY, "result-version-1", 1),
            )
            stubManagedGetFailure(loserS3, failure)
            val snapshot = gateway(loserS3).controls(
                BUCKET,
                TARGET_KEY,
                RESULT_KEY,
                POLICY_FINGERPRINT,
                IDENTITY,
                UTC_DATE,
                REQUIRED_RETAIN_UNTIL,
                VALID_UNTIL,
                PROBE_TIMEOUT,
            )
            assertThat(snapshot.dailyControl).isNull()

            listOf(downloadS3, conflictS3, loserS3).forEach { managedS3 ->
                verify(managedS3).getObject(
                    any(GetObjectRequest::class.java),
                    anyGeneric<ResponseTransformer<GetObjectResponse, Any>>(),
                )
                verify(managedS3, times(0)).getObject(any(GetObjectRequest::class.java))
            }
        }
    }

    @Test
    fun `download maps managed target security failure without leaking its path`() {
        val s3 = mock(S3Client::class.java)
        stubManagedGetFailure(s3, SecurityException("C:\\private\\tenant-secret.partial"))

        assertThatThrownBy {
            gateway(s3).download(
                s3Reference("version-1", "a".repeat(64), 1),
                tempDirectory.resolve("security-failure.zip"),
                OPERATION_TIMEOUT,
            )
        }.isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 operation download failed (AWS TARGET_UNAVAILABLE)")
            .hasMessageNotContaining("tenant-secret")

        verify(s3).getObject(
            any(GetObjectRequest::class.java),
            anyGeneric<ResponseTransformer<GetObjectResponse, Any>>(),
        )
        verify(s3, times(0)).getObject(any(GetObjectRequest::class.java))
    }

    @Test
    fun `control loser fails closed for multiple versions digest or identity mismatch`() {
        listOf(
            LoserFailure.MULTIPLE_VERSIONS,
            LoserFailure.DIGEST_MISMATCH,
            LoserFailure.IDENTITY_MISMATCH,
            LoserFailure.TARGET_CONTENT_MISMATCH,
            LoserFailure.TARGET_SIZE_MISMATCH,
            LoserFailure.HEAD_MISMATCH,
        ).forEach { failure ->
            val s3 = mock(S3Client::class.java)
            stubBucketControls(s3)
            val record = dailyRecord(controlTargetReference()).let { validRecord ->
                when (failure) {
                    LoserFailure.IDENTITY_MISMATCH ->
                        validRecord.copy(identity = IDENTITY.copy(principalFingerprint = "c".repeat(64)))
                    LoserFailure.TARGET_CONTENT_MISMATCH ->
                        validRecord.copy(target = validRecord.target.copy(sha256 = "c".repeat(64)))
                    LoserFailure.TARGET_SIZE_MISMATCH ->
                        validRecord.copy(target = validRecord.target.copy(sizeBytes = validRecord.target.sizeBytes + 1))
                    else -> validRecord
                }
            }
            val canonical = canonicalDailyControlRecordBytes(jacksonObjectMapper().findAndRegisterModules(), record)
            val bytes = if (failure == LoserFailure.DIGEST_MISMATCH) canonical + byteArrayOf(' '.code.toByte()) else canonical
            `when`(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenThrow(
                preconditionFailed(),
            )
            val versions = mutableListOf(
                ObjectVersion.builder().key(RESULT_KEY).versionId("result-version-1").size(bytes.size.toLong()).build(),
            )
            if (failure == LoserFailure.MULTIPLE_VERSIONS) {
                versions += ObjectVersion.builder()
                    .key(RESULT_KEY)
                    .versionId("shadow-version-2")
                    .size(bytes.size.toLong())
                    .build()
            }
            `when`(s3.listObjectVersions(any(ListObjectVersionsRequest::class.java))).thenReturn(
                ListObjectVersionsResponse.builder().versions(versions).isTruncated(false).build(),
            )
            stubManagedGet(
                s3,
                GetObjectResponse.builder()
                    .versionId("result-version-1")
                    .contentLength(bytes.size.toLong())
                    .build(),
                bytes,
            )
            if (failure == LoserFailure.HEAD_MISMATCH) {
                `when`(s3.headObject(any(HeadObjectRequest::class.java))).thenReturn(
                    HeadObjectResponse.builder()
                        .versionId("shadow-target-version")
                        .contentLength(CONTROL_TARGET_BYTES.size.toLong())
                        .metadata(mapOf("sha256" to sha256(CONTROL_TARGET_BYTES)))
                        .objectLockMode(ObjectLockMode.COMPLIANCE)
                        .objectLockRetainUntilDate(REQUIRED_RETAIN_UNTIL)
                        .build(),
                )
            } else {
                val metadataDigest = if (failure == LoserFailure.TARGET_CONTENT_MISMATCH) {
                    "c".repeat(64)
                } else {
                    sha256(CONTROL_TARGET_BYTES)
                }
                `when`(s3.headObject(any(HeadObjectRequest::class.java))).thenReturn(
                    controlTargetHead().toBuilder().metadata(mapOf("sha256" to metadataDigest)).build(),
                )
            }

            val snapshot = gateway(s3).controls(
                BUCKET,
                TARGET_KEY,
                RESULT_KEY,
                POLICY_FINGERPRINT,
                IDENTITY,
                UTC_DATE,
                REQUIRED_RETAIN_UNTIL,
                VALID_UNTIL,
                PROBE_TIMEOUT,
            )

            assertThat(snapshot.dailyControl).describedAs(failure.name).isNull()
            assertThat(snapshot.controlObjectProtection).isNull()
            verify(s3, times(0)).deleteObject(any(DeleteObjectRequest::class.java))
            verify(s3, times(0)).putObjectRetention(any(PutObjectRetentionRequest::class.java))
        }
    }

    private fun gateway(s3: S3Client): AwsS3Gateway = AwsS3Gateway(
        s3,
        jacksonObjectMapper().findAndRegisterModules(),
        ProviderIdentityAttestor {
            ProviderAttestedIdentity("aws", "123456789012", "workload", "workload/release-gate")
        },
    )

    private fun assertInvalidAwsIdentity(account: String, rawArn: String, rawUserId: String) {
        val sts = mock(StsClient::class.java)
        `when`(sts.getCallerIdentity(any(GetCallerIdentityRequest::class.java))).thenReturn(
            GetCallerIdentityResponse.builder()
                .account(account)
                .arn(rawArn)
                .userId(rawUserId)
                .build(),
        )
        val s3 = mock(S3Client::class.java)

        assertThatThrownBy {
            AwsS3Gateway(
                s3,
                jacksonObjectMapper().findAndRegisterModules(),
                AwsStsIdentityAttestor(sts),
            ).runtimeIdentity(Duration.ofMillis(100))
        }.isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 operation identity failed (AWS INVALID_IDENTITY)")
            .hasMessageNotContaining(rawArn)
            .hasMessageNotContaining(rawUserId)
            .hasMessageNotContaining(account)
        verifyNoInteractions(s3)
    }

    private fun s3Reference(versionId: String?, digest: String, size: Long) = StoredObjectRef(
        provider = ArchiveProvider.S3_COMPATIBLE,
        locator = "s3://archive-bucket/evidence/payload.zip",
        bucket = "archive-bucket",
        key = "evidence/payload.zip",
        versionId = versionId,
        sha256 = digest,
        sizeBytes = size,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun assertRequestTimeout(request: software.amazon.awssdk.core.SdkRequest, timeout: Duration) {
        assertThat(request.overrideConfiguration().orElseThrow().apiCallTimeout()).contains(timeout)
    }

    private fun stubManagedGet(s3: S3Client, response: GetObjectResponse, bytes: ByteArray) {
        doAnswer { invocation -> applyManagedGet(invocation, response, bytes) }.`when`(s3).getObject(
            any(GetObjectRequest::class.java),
            anyGeneric<ResponseTransformer<GetObjectResponse, Any>>(),
        )
    }

    private fun stubManagedGetFailure(s3: S3Client, failure: Throwable) {
        doAnswer { throw failure }.`when`(s3).getObject(
            any(GetObjectRequest::class.java),
            anyGeneric<ResponseTransformer<GetObjectResponse, Any>>(),
        )
    }

    private fun applyManagedGet(
        invocation: org.mockito.invocation.InvocationOnMock,
        response: GetObjectResponse,
        bytes: ByteArray,
    ): Any? {
        val transformer = invocation.getArgument<ResponseTransformer<GetObjectResponse, Any?>>(1)
        return transformer.transform(response, AbortableInputStream.create(ByteArrayInputStream(bytes)))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyGeneric(): T {
        org.mockito.ArgumentMatchers.any<T>()
        return null as T
    }

    private fun stubSuccessfulControlPostPutValidation(s3: S3Client, resultBodies: List<ByteArray>) {
        `when`(s3.listObjectVersions(any(ListObjectVersionsRequest::class.java))).thenAnswer { invocation ->
            val key = invocation.getArgument(0, ListObjectVersionsRequest::class.java).prefix()
            if (key == TARGET_KEY) {
                exactCreatedHistory(TARGET_KEY, "target-version-1", CONTROL_TARGET_BYTES.size.toLong())
            } else {
                val bytes = resultBodies.single()
                exactCreatedHistory(RESULT_KEY, "result-version-1", bytes.size.toLong())
            }
        }
        `when`(s3.headObject(any(HeadObjectRequest::class.java))).thenAnswer { invocation ->
            val key = invocation.getArgument(0, HeadObjectRequest::class.java).key()
            if (key == TARGET_KEY) {
                controlTargetHead()
            } else {
                val bytes = resultBodies.single()
                HeadObjectResponse.builder()
                    .versionId("result-version-1")
                    .contentLength(bytes.size.toLong())
                    .metadata(mapOf("sha256" to sha256(bytes)))
                    .build()
            }
        }
    }

    private fun stubBucketControls(s3: S3Client) {
        `when`(s3.listObjectVersions(any(ListObjectVersionsRequest::class.java))).thenReturn(
            ListObjectVersionsResponse.builder()
                .versions(
                    ObjectVersion.builder()
                        .key(TARGET_KEY)
                        .versionId("target-version-1")
                        .size(CONTROL_TARGET_BYTES.size.toLong())
                        .build(),
                )
                .isTruncated(false)
                .build(),
        )
        `when`(s3.getBucketEncryption(any(GetBucketEncryptionRequest::class.java))).thenReturn(
            GetBucketEncryptionResponse.builder()
                .serverSideEncryptionConfiguration(
                    ServerSideEncryptionConfiguration.builder()
                        .rules(
                            ServerSideEncryptionRule.builder()
                                .applyServerSideEncryptionByDefault(
                                    ServerSideEncryptionByDefault.builder().sseAlgorithm("AES256").build(),
                                )
                                .build(),
                        )
                        .build(),
                )
                .build(),
        )
        `when`(s3.getPublicAccessBlock(any(GetPublicAccessBlockRequest::class.java))).thenReturn(
            GetPublicAccessBlockResponse.builder()
                .publicAccessBlockConfiguration(
                    PublicAccessBlockConfiguration.builder()
                        .blockPublicAcls(true)
                        .ignorePublicAcls(true)
                        .blockPublicPolicy(true)
                        .restrictPublicBuckets(true)
                        .build(),
                )
                .build(),
        )
        `when`(s3.getBucketVersioning(any(GetBucketVersioningRequest::class.java))).thenReturn(
            GetBucketVersioningResponse.builder().status(BucketVersioningStatus.ENABLED).build(),
        )
        `when`(s3.getObjectLockConfiguration(any(GetObjectLockConfigurationRequest::class.java))).thenReturn(
            GetObjectLockConfigurationResponse.builder()
                .objectLockConfiguration(
                    ObjectLockConfiguration.builder()
                        .objectLockEnabled(ObjectLockEnabled.ENABLED)
                        .rule(
                            ObjectLockRule.builder()
                                .defaultRetention(
                                    DefaultRetention.builder()
                                        .mode(ObjectLockRetentionMode.COMPLIANCE)
                                        .days(365)
                                        .build(),
                                )
                                .build(),
                        )
                        .build(),
                )
                .build(),
        )
    }

    private fun verifyControlTimeouts(s3: S3Client) {
        val encryption = ArgumentCaptor.forClass(GetBucketEncryptionRequest::class.java)
        verify(s3).getBucketEncryption(encryption.capture())
        assertRequestTimeout(encryption.value, PROBE_TIMEOUT)
        val access = ArgumentCaptor.forClass(GetPublicAccessBlockRequest::class.java)
        verify(s3).getPublicAccessBlock(access.capture())
        assertRequestTimeout(access.value, PROBE_TIMEOUT)
        val versioning = ArgumentCaptor.forClass(GetBucketVersioningRequest::class.java)
        verify(s3).getBucketVersioning(versioning.capture())
        assertRequestTimeout(versioning.value, PROBE_TIMEOUT)
        val lock = ArgumentCaptor.forClass(GetObjectLockConfigurationRequest::class.java)
        verify(s3).getObjectLockConfiguration(lock.capture())
        assertRequestTimeout(lock.value, PROBE_TIMEOUT)
        val head = ArgumentCaptor.forClass(HeadObjectRequest::class.java)
        verify(s3, atLeastOnce()).headObject(head.capture())
        head.allValues.forEach { assertRequestTimeout(it, PROBE_TIMEOUT) }
    }

    private fun denied(code: String): S3Exception = S3Exception.builder().apply {
        statusCode(403)
        awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
    }.build() as S3Exception

    private fun preconditionFailed(): S3Exception = S3Exception.builder().apply {
        statusCode(412)
        awsErrorDetails(AwsErrorDetails.builder().errorCode("PreconditionFailed").build())
    }.build() as S3Exception

    private fun controlTargetReference() = StoredObjectRef(
        ArchiveProvider.S3_COMPATIBLE,
        "s3://$BUCKET/$TARGET_KEY",
        BUCKET,
        TARGET_KEY,
        "target-version-1",
        sha256(CONTROL_TARGET_BYTES),
        CONTROL_TARGET_BYTES.size.toLong(),
    )

    private fun exactCreatedHistory(key: String, versionId: String, size: Long) =
        ListObjectVersionsResponse.builder()
            .versions(ObjectVersion.builder().key(key).versionId(versionId).size(size).build())
            .isTruncated(false)
            .build()

    private fun invalidCreatedHistory(
        key: String,
        versionId: String,
        size: Long,
        priorIsDeleteMarker: Boolean,
    ): ListObjectVersionsResponse {
        val builder = exactCreatedHistory(key, versionId, size).toBuilder()
        if (priorIsDeleteMarker) {
            builder.deleteMarkers(DeleteMarkerEntry.builder().key(key).versionId("old-marker").build())
        } else {
            builder.versions(
                ObjectVersion.builder().key(key).versionId(versionId).size(size).build(),
                ObjectVersion.builder().key(key).versionId("old-version").size(1).build(),
            )
        }
        return builder.build()
    }

    private fun controlTargetHead() = HeadObjectResponse.builder()
        .versionId("target-version-1")
        .contentLength(CONTROL_TARGET_BYTES.size.toLong())
        .metadata(mapOf("sha256" to sha256(CONTROL_TARGET_BYTES)))
        .objectLockMode(ObjectLockMode.COMPLIANCE)
        .objectLockRetainUntilDate(REQUIRED_RETAIN_UNTIL)
        .build()

    private fun dailyRecord(target: StoredObjectRef) = DailyControlRecord(
        policyFingerprint = POLICY_FINGERPRINT,
        identity = IDENTITY,
        utcDate = UTC_DATE,
        validUntil = VALID_UNTIL,
        target = target,
        overwrite = MutationCheckResult.DENIED_AS_EXPECTED,
        delete = MutationCheckResult.DENIED_AS_EXPECTED,
        bypass = MutationCheckResult.DENIED_AS_EXPECTED,
    )

    private data class ControlInput(
        val targetKey: String = TARGET_KEY,
        val resultKey: String = RESULT_KEY,
        val identity: RuntimeIdentityRef = IDENTITY,
    )

    private enum class LoserFailure {
        MULTIPLE_VERSIONS,
        DIGEST_MISMATCH,
        IDENTITY_MISMATCH,
        TARGET_CONTENT_MISMATCH,
        TARGET_SIZE_MISMATCH,
        HEAD_MISMATCH,
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ArchiveConfiguration::class)
    @ConfigurationPropertiesScan(basePackages = ["com.ricezhou.vsrqg.shared.adapter.archive"])
    private class S3TestApplication

    private companion object {
        const val BUCKET = "archive-bucket"
        const val POLICY_FINGERPRINT =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val PRINCIPAL_FINGERPRINT =
            "b74043d59f26d8c9d3fdeeddc1357896026f410eed0954eaea87707ac8091326"
        val UTC_DATE: LocalDate = LocalDate.parse("2026-08-26")
        val REQUIRED_RETAIN_UNTIL: Instant = Instant.parse("2027-08-27T00:00:00Z")
        val VALID_UNTIL: Instant = Instant.parse("2026-08-27T00:00:00Z")
        val PROBE_TIMEOUT: Duration = Duration.ofSeconds(5)
        val OPERATION_TIMEOUT: Duration = Duration.ofSeconds(2)
        val CONTROL_TARGET_BYTES = "{\"purpose\":\"archive-capability-probe\",\"version\":1}".toByteArray()
        val IDENTITY = RuntimeIdentityRef(ArchiveProvider.S3_COMPATIBLE, PRINCIPAL_FINGERPRINT)
        const val TARGET_KEY =
            "acceptance/capability-probe/$POLICY_FINGERPRINT/$PRINCIPAL_FINGERPRINT/2026-08-26/target.json"
        const val RESULT_KEY =
            "acceptance/capability-probe/$POLICY_FINGERPRINT/$PRINCIPAL_FINGERPRINT/2026-08-26/result.json"
    }
}
