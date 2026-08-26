package com.ricezhou.vsrqg.shared.archive

import com.ricezhou.vsrqg.shared.application.archive.ArchivePolicy
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.DeploymentMode
import java.nio.file.Path
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

class ArchiveConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(ArchiveTestApplication::class.java)

    @Test
    fun `defaults bind to the pilot archive policy`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(ArchivePolicy::class.java)

            val policy = context.getBean(ArchivePolicy::class.java)
            assertThat(policy.mode).isEqualTo(DeploymentMode.PILOT)
            assertThat(policy.provider).isEqualTo(ArchiveProvider.NONE)
            assertThat(policy.enabled).isTrue()
            assertThat(policy.checksumVerificationEnabled).isTrue()
            assertThat(policy.encryptionRequired).isTrue()
            assertThat(policy.privateAccessRequired).isTrue()
            assertThat(policy.retentionPolicyRequired).isTrue()
            assertThat(policy.immutabilityRequired).isTrue()
            assertThat(policy.objectPrefix).isEqualTo("acceptance/")
            assertThat(policy.probeTimeout).isEqualTo(Duration.ofSeconds(5))
            assertThat(policy.operationTimeout).isEqualTo(Duration.ofSeconds(30))
        }
    }

    @Test
    fun `object prefix rejects empty absolute traversal and backslash values`() {
        listOf(
            "",
            "/acceptance",
            "C:/acceptance",
            "../escape",
            "release/../escape",
            "acceptance\\escape",
        ).forEach { prefix ->
            assertConfigurationFails(
                "vsrqg.evidence.archive.object-prefix=$prefix",
                description = "object prefix '$prefix'",
            )
        }
    }

    @Test
    fun `object prefix treats only complete traversal segments as unsafe`() {
        contextRunner
            .withPropertyValues("vsrqg.evidence.archive.object-prefix=release..candidate")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(ArchivePolicy::class.java).objectPrefix)
                    .isEqualTo("release..candidate/")
            }
    }

    @Test
    fun `filesystem staging requires an absolute root`() {
        assertConfigurationFails(
            "vsrqg.evidence.archive.provider=FILESYSTEM_STAGING",
        )
        assertConfigurationFails(
            "vsrqg.evidence.archive.provider=FILESYSTEM_STAGING",
            "vsrqg.evidence.archive.staging-root=relative/staging",
        )

        val absoluteRoot = Path.of(System.getProperty("java.io.tmpdir"), "vsrqg-staging").toAbsolutePath()
        contextRunner
            .withPropertyValues(
                "vsrqg.evidence.archive.provider=FILESYSTEM_STAGING",
                "vsrqg.evidence.archive.staging-root=$absoluteRoot",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(ArchivePolicy::class.java).stagingRoot)
                    .isEqualTo(absoluteRoot.normalize())
            }
    }

    @Test
    fun `retention period rejects non-positive configured values`() {
        listOf("0s", "-1s").forEach { retention ->
            assertConfigurationFails("vsrqg.evidence.archive.retention-period=$retention")
        }
    }

    @Test
    fun `timeouts must be positive and operation must not be shorter than probe`() {
        listOf("0s", "-1s").forEach { timeout ->
            assertConfigurationFails("vsrqg.evidence.archive.probe-timeout=$timeout")
            assertConfigurationFails("vsrqg.evidence.archive.operation-timeout=$timeout")
        }
        assertConfigurationFails(
            "vsrqg.evidence.archive.probe-timeout=10s",
            "vsrqg.evidence.archive.operation-timeout=5s",
        )
    }

    @Test
    fun `company mode does not require external capability configuration at startup`() {
        contextRunner
            .withPropertyValues(
                "vsrqg.deployment.mode=COMPANY",
                "vsrqg.evidence.archive.provider=S3_COMPATIBLE",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                val policy = context.getBean(ArchivePolicy::class.java)
                assertThat(policy.mode).isEqualTo(DeploymentMode.COMPANY)
                assertThat(policy.endpoint).isNull()
                assertThat(policy.bucket).isNull()
                assertThat(policy.accessOwner).isNull()
                assertThat(policy.retentionPeriod).isNull()
            }
    }

    @Test
    fun `endpoint accepts absolute http and https URIs with a host`() {
        listOf(
            "http://archive.example.test:9000",
            "https://archive.example.test/storage",
        ).forEach { endpoint ->
            contextRunner
                .withPropertyValues("vsrqg.evidence.archive.endpoint=$endpoint")
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context.getBean(ArchivePolicy::class.java).endpoint)
                        .hasToString(endpoint)
                }
        }
    }

    @Test
    fun `endpoint rejects unsafe URI shapes without echoing the value`() {
        listOf(
            "archive.example.test/path",
            "ftp://archive.example.test/path",
            "https:///missing-host",
            "https://sensitive-user:sensitive-password@archive.example.test",
            "https://archive.example.test/path?sensitive-token=secret",
            "https://archive.example.test/path#sensitive-fragment",
        ).forEach { endpoint ->
            contextRunner
                .withPropertyValues("vsrqg.evidence.archive.endpoint=$endpoint")
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(causeMessages(context.startupFailure)).doesNotContain(endpoint)
                }
        }
    }

    private fun assertConfigurationFails(vararg properties: String, description: String = properties.joinToString()) {
        contextRunner.withPropertyValues(*properties).run { context ->
            assertThat(context)
                .describedAs(description)
                .hasFailed()
        }
    }

    private fun causeMessages(failure: Throwable?): String =
        generateSequence(failure) { it.cause }
            .mapNotNull { it.message }
            .joinToString("\n")

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = ["com.ricezhou.vsrqg.shared.adapter.archive"])
    @ConfigurationPropertiesScan(basePackages = ["com.ricezhou.vsrqg.shared.adapter.archive"])
    private class ArchiveTestApplication
}
