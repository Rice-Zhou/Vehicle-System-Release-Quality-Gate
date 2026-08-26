package com.ricezhou.vsrqg.shared.adapter.archive

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.application.archive.ArchivePolicy
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.DeploymentMode
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sts.StsClient

@ConfigurationProperties("vsrqg.deployment")
data class DeploymentProperties(
    val mode: DeploymentMode = DeploymentMode.PILOT,
)

@ConfigurationProperties("vsrqg.evidence.archive")
data class ArchiveProperties(
    val enabled: Boolean = true,
    val checksumVerificationEnabled: Boolean = true,
    val encryptionRequired: Boolean = true,
    val privateAccessRequired: Boolean = true,
    val retentionPolicyRequired: Boolean = true,
    val immutabilityRequired: Boolean = true,
    val provider: ArchiveProvider = ArchiveProvider.NONE,
    val stagingRoot: String? = null,
    val endpoint: String? = null,
    val region: String? = null,
    val bucket: String? = null,
    val objectPrefix: String? = DEFAULT_OBJECT_PREFIX,
    val accessOwner: String? = null,
    val retentionPeriod: Duration? = null,
    val probeTimeout: Duration = DEFAULT_PROBE_TIMEOUT,
    val operationTimeout: Duration = DEFAULT_OPERATION_TIMEOUT,
)

@Configuration(proxyBeanMethods = false)
internal class ArchiveConfiguration {
    @Bean
    fun archivePolicy(
        deployment: DeploymentProperties,
        archive: ArchiveProperties,
    ): ArchivePolicy {
        val objectPrefix = normalizeObjectPrefix(archive.objectPrefix)
        val stagingRoot = archive.stagingRoot.normalizedOrNull()?.let(Path::of)?.normalize()
        if (archive.provider == ArchiveProvider.FILESYSTEM_STAGING) {
            require(stagingRoot != null && stagingRoot.isAbsolute) {
                "vsrqg.evidence.archive.staging-root must be an absolute path for FILESYSTEM_STAGING"
            }
        }

        archive.retentionPeriod?.let { retentionPeriod ->
            require(retentionPeriod.isPositive()) {
                "vsrqg.evidence.archive.retention-period must be positive"
            }
        }
        require(archive.probeTimeout.isPositive()) {
            "vsrqg.evidence.archive.probe-timeout must be positive"
        }
        require(archive.operationTimeout.isPositive()) {
            "vsrqg.evidence.archive.operation-timeout must be positive"
        }
        require(archive.operationTimeout >= archive.probeTimeout) {
            "vsrqg.evidence.archive.operation-timeout must not be shorter than probe-timeout"
        }

        return ArchivePolicy(
            mode = deployment.mode,
            enabled = archive.enabled,
            checksumVerificationEnabled = archive.checksumVerificationEnabled,
            encryptionRequired = archive.encryptionRequired,
            privateAccessRequired = archive.privateAccessRequired,
            retentionPolicyRequired = archive.retentionPolicyRequired,
            immutabilityRequired = archive.immutabilityRequired,
            provider = archive.provider,
            stagingRoot = stagingRoot,
            endpoint = normalizeEndpoint(archive.endpoint),
            region = archive.region.normalizedOrNull(),
            bucket = archive.bucket.normalizedOrNull(),
            objectPrefix = objectPrefix,
            accessOwner = archive.accessOwner.normalizedOrNull(),
            retentionPeriod = archive.retentionPeriod,
            probeTimeout = archive.probeTimeout,
            operationTimeout = archive.operationTimeout,
        )
    }

    @Bean(destroyMethod = "close")
    @Conditional(S3ArchiveConfiguredCondition::class)
    fun archiveCredentialsProvider(): DefaultCredentialsProvider = DefaultCredentialsProvider.builder().build()

    @Bean(destroyMethod = "close")
    @Conditional(S3ArchiveConfiguredCondition::class)
    fun archiveS3Client(
        policy: ArchivePolicy,
        credentials: DefaultCredentialsProvider,
    ): S3Client {
        val builder = S3Client.builder()
            .credentialsProvider(credentials)
            .region(Region.of(requireNotNull(policy.region)))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
        policy.endpoint?.let { endpoint ->
            builder.endpointOverride(endpoint).forcePathStyle(true)
        }
        return builder.build()
    }

    @Bean(destroyMethod = "close")
    @Conditional(NativeAwsArchiveConfiguredCondition::class)
    fun archiveStsClient(
        policy: ArchivePolicy,
        credentials: DefaultCredentialsProvider,
    ): StsClient = StsClient.builder()
        .credentialsProvider(credentials)
        .region(Region.of(requireNotNull(policy.region)))
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .build()

    @Bean
    @Conditional(S3ArchiveConfiguredCondition::class)
    fun archiveS3Gateway(
        policy: ArchivePolicy,
        s3: S3Client,
        sts: ObjectProvider<StsClient>,
        approvedAttestors: ObjectProvider<ProviderIdentityAttestor>,
        objectMapper: ObjectMapper,
    ): S3Gateway {
        val attestor = if (policy.endpoint == null) {
            sts.getIfAvailable()?.let(::AwsStsIdentityAttestor) ?: MissingProviderIdentityAttestor
        } else {
            approvedAttestors.orderedStream().toList().singleOrNull() ?: MissingProviderIdentityAttestor
        }
        return AwsS3Gateway(s3, objectMapper, attestor)
    }

    private fun normalizeObjectPrefix(value: String?): String {
        val prefix = value?.trim().orEmpty()
        require(prefix.isNotEmpty()) {
            "vsrqg.evidence.archive.object-prefix must not be empty"
        }
        require(!prefix.startsWith('/') && !WINDOWS_ABSOLUTE_PREFIX.containsMatchIn(prefix)) {
            "vsrqg.evidence.archive.object-prefix must be relative"
        }
        require('\\' !in prefix) {
            "vsrqg.evidence.archive.object-prefix must use forward slashes"
        }

        val segments = prefix.split('/')
        require(segments.none { it == ".." }) {
            "vsrqg.evidence.archive.object-prefix must not contain parent traversal segments"
        }
        val normalized = segments.filterNot { it.isEmpty() || it == "." }.joinToString("/")
        require(normalized.isNotEmpty()) {
            "vsrqg.evidence.archive.object-prefix must not be empty"
        }
        return "$normalized/"
    }

    private fun normalizeEndpoint(value: String?): URI? {
        val endpoint = value.normalizedOrNull() ?: return null
        val uri = try {
            URI(endpoint).normalize()
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException(ENDPOINT_REQUIREMENT)
        }
        require(
            uri.isAbsolute &&
                uri.scheme.lowercase() in HTTP_SCHEMES &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null,
        ) { ENDPOINT_REQUIREMENT }
        return uri
    }
}

internal class S3ArchiveConfiguredCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
        context.environment.getProperty("$ARCHIVE_PROPERTY_PREFIX.provider")
            ?.equals(ArchiveProvider.S3_COMPATIBLE.name, ignoreCase = true) == true &&
            context.environment.getProperty("$ARCHIVE_PROPERTY_PREFIX.region").normalizedOrNull() != null
}

internal class NativeAwsArchiveConfiguredCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
        S3ArchiveConfiguredCondition().matches(context, metadata) &&
            context.environment.getProperty("$ARCHIVE_PROPERTY_PREFIX.endpoint").normalizedOrNull() == null
}

private fun String?.normalizedOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private val DEFAULT_PROBE_TIMEOUT: Duration = Duration.ofSeconds(5)
private val DEFAULT_OPERATION_TIMEOUT: Duration = Duration.ofSeconds(30)
private const val DEFAULT_OBJECT_PREFIX = "acceptance/"
private val WINDOWS_ABSOLUTE_PREFIX = Regex("^[A-Za-z]:/")
private val HTTP_SCHEMES = setOf("http", "https")
private const val ENDPOINT_REQUIREMENT =
    "vsrqg.evidence.archive.endpoint must be an absolute HTTP(S) URI with a host and without user-info, query, or fragment"
private const val ARCHIVE_PROPERTY_PREFIX = "vsrqg.evidence.archive"
