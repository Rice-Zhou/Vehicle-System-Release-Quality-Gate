package com.ricezhou.vsrqg.shared.archive

import com.ricezhou.vsrqg.shared.adapter.archive.ArchiveCapabilityConfiguration
import com.ricezhou.vsrqg.shared.adapter.archive.ArchiveCapabilityHealthIndicator
import com.ricezhou.vsrqg.shared.application.archive.ArchiveAdapter
import com.ricezhou.vsrqg.shared.application.archive.ArchiveAuthorization
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCapabilityState
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCommand
import com.ricezhou.vsrqg.shared.application.archive.ArchivePolicy
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.ArchiveResult
import com.ricezhou.vsrqg.shared.application.archive.CapabilityCheck
import com.ricezhou.vsrqg.shared.application.archive.CapabilityProbeContext
import com.ricezhou.vsrqg.shared.application.archive.DeploymentMode
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ArchiveProfileIntegrationTest {
    @Test
    fun `pilot filesystem wiring reports local capability and stays ready`() {
        val adapter = CountingAdapter(ArchiveProvider.FILESYSTEM_STAGING)

        runProfileContext(policy(DeploymentMode.PILOT, true, adapter.provider), adapter) { health ->
            assertThat(health.status).isEqualTo(Status.UP)
            assertThat(health.details["state"]).isEqualTo(ArchiveCapabilityState.LOCAL_PILOT.name)
        }
    }

    @Test
    fun `company none is not ready`() {
        runProfileContext(policy(DeploymentMode.COMPANY, true, ArchiveProvider.NONE), null) { health ->
            assertThat(health.status).isEqualTo(Status.DOWN)
            assertThat(health.details["state"]).isEqualTo(ArchiveCapabilityState.UNCONFIGURED.name)
        }
    }

    @Test
    fun `company disabled preserves verified provider fact but remains not ready`() {
        val adapter = CountingAdapter(ArchiveProvider.S3_COMPATIBLE)

        runProfileContext(policy(DeploymentMode.COMPANY, false, adapter.provider), adapter) { health ->
            assertThat(health.status).isEqualTo(Status.DOWN)
            assertThat(health.details["state"]).isEqualTo(ArchiveCapabilityState.EXTERNAL_VERIFIED.name)
        }
    }

    @Test
    fun `company is ready only with enabled and freshly verified external capability`() {
        val verified = CountingAdapter(ArchiveProvider.S3_COMPATIBLE)

        runProfileContext(policy(DeploymentMode.COMPANY, true, verified.provider), verified) { health ->
            assertThat(health.status).isEqualTo(Status.UP)
            assertThat(health.details["state"]).isEqualTo(ArchiveCapabilityState.EXTERNAL_VERIFIED.name)
        }

        val unverified = CountingAdapter(
            ArchiveProvider.S3_COMPATIBLE,
            listOf(CapabilityCheck("connection", false, "not verified")),
        )
        runProfileContext(policy(DeploymentMode.COMPANY, true, unverified.provider), unverified) { health ->
            assertThat(health.status).isEqualTo(Status.DOWN)
            assertThat(health.details["state"]).isEqualTo(ArchiveCapabilityState.EXTERNAL_UNVERIFIED.name)
        }
    }

    @Test
    fun `each readiness observation uses a fresh probe without replacing other health contributors`() {
        val adapter = CountingAdapter(ArchiveProvider.S3_COMPATIBLE)
        val runner = profileContext(policy(DeploymentMode.COMPANY, true, adapter.provider), adapter)
            .withBean(
                "independentReadiness",
                HealthIndicator::class.java,
                Supplier<HealthIndicator> { HealthIndicator { org.springframework.boot.actuate.health.Health.up().build() } },
            )

        runner.run { context ->
            assertThat(context).hasNotFailed()
            val indicator = context.getBean("archiveCapability", ArchiveCapabilityHealthIndicator::class.java)

            val first = indicator.health()
            val second = indicator.health()

            assertThat(first.status).isEqualTo(Status.UP)
            assertThat(second.status).isEqualTo(Status.UP)
            assertThat(first.details["checkedAt"]).isNotEqualTo(second.details["checkedAt"])
            assertThat(adapter.probeCount).isEqualTo(2)
            assertThat(context).hasBean("independentReadiness")
            assertThat(context.getBean("independentReadiness", HealthIndicator::class.java)
                .health().status).isEqualTo(Status.UP)
        }
    }

    private fun runProfileContext(
        policy: ArchivePolicy,
        adapter: CountingAdapter?,
        assertion: (org.springframework.boot.actuate.health.Health) -> Unit,
    ) {
        profileContext(policy, adapter).run { context ->
            assertThat(context).hasNotFailed()
            val indicator = context.getBean("archiveCapability", ArchiveCapabilityHealthIndicator::class.java)
            assertion(indicator.health())
        }
    }

    private fun profileContext(policy: ArchivePolicy, adapter: CountingAdapter?): ApplicationContextRunner {
        var runner = ApplicationContextRunner()
            .withBean(ArchivePolicy::class.java, { policy })
            .withBean(TimeProvider::class.java, { AdvancingTimeProvider() })
            .withUserConfiguration(
                ArchiveCapabilityConfiguration::class.java,
                ArchiveCapabilityHealthIndicator::class.java,
            )
        if (adapter != null) {
            runner = runner.withBean("profileArchiveAdapter", ArchiveAdapter::class.java, { adapter })
        }
        return runner
    }

    private fun policy(mode: DeploymentMode, enabled: Boolean, provider: ArchiveProvider) = ArchivePolicy(
        mode = mode,
        enabled = enabled,
        checksumVerificationEnabled = true,
        encryptionRequired = true,
        privateAccessRequired = true,
        retentionPolicyRequired = true,
        immutabilityRequired = true,
        provider = provider,
        stagingRoot = null,
        endpoint = if (provider == ArchiveProvider.S3_COMPATIBLE) URI("https://archive.example.test") else null,
        region = if (provider == ArchiveProvider.S3_COMPATIBLE) "cn-north-1" else null,
        bucket = if (provider == ArchiveProvider.S3_COMPATIBLE) "vsrqg-archive" else null,
        objectPrefix = "acceptance/",
        accessOwner = if (provider == ArchiveProvider.S3_COMPATIBLE) "release-governance" else null,
        retentionPeriod = if (provider == ArchiveProvider.S3_COMPATIBLE) Duration.ofDays(365) else null,
        probeTimeout = Duration.ofSeconds(5),
        operationTimeout = Duration.ofSeconds(30),
    )

    private class CountingAdapter(
        override val provider: ArchiveProvider,
        private val checks: List<CapabilityCheck> = listOf(CapabilityCheck("connection", true, "verified")),
    ) : ArchiveAdapter {
        var probeCount = 0
            private set

        override fun probe(policy: ArchivePolicy, context: CapabilityProbeContext): List<CapabilityCheck> {
            probeCount += 1
            return checks
        }

        override fun archive(
            command: ArchiveCommand,
            policy: ArchivePolicy,
            authorization: ArchiveAuthorization,
        ): ArchiveResult = throw UnsupportedOperationException("Integration test probe only")
    }

    private class AdvancingTimeProvider : TimeProvider {
        private var calls = 0L

        override fun now(): Instant = Instant.parse("2026-08-26T06:00:00Z").plusSeconds(calls++)
    }
}
