package com.ricezhou.vsrqg.shared.adapter.archive

import com.ricezhou.vsrqg.shared.application.archive.ArchivePolicy
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.DeploymentMode
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
class ArchiveConfiguration {
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

private fun String?.normalizedOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private val DEFAULT_PROBE_TIMEOUT: Duration = Duration.ofSeconds(5)
private val DEFAULT_OPERATION_TIMEOUT: Duration = Duration.ofSeconds(30)
private const val DEFAULT_OBJECT_PREFIX = "acceptance/"
private val WINDOWS_ABSOLUTE_PREFIX = Regex("^[A-Za-z]:/")
private val HTTP_SCHEMES = setOf("http", "https")
private const val ENDPOINT_REQUIREMENT =
    "vsrqg.evidence.archive.endpoint must be an absolute HTTP(S) URI with a host and without user-info, query, or fragment"
