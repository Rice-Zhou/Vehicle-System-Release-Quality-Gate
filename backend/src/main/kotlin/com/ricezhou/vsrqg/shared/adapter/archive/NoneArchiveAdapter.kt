package com.ricezhou.vsrqg.shared.adapter.archive

import com.ricezhou.vsrqg.shared.application.archive.ArchiveAdapter
import com.ricezhou.vsrqg.shared.application.archive.ArchiveAuthorization
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCommand
import com.ricezhou.vsrqg.shared.application.archive.ArchivePolicy
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.ArchiveResult
import com.ricezhou.vsrqg.shared.application.archive.ArchiveUnavailable
import com.ricezhou.vsrqg.shared.application.archive.CapabilityCheck
import com.ricezhou.vsrqg.shared.application.archive.CapabilityProbeContext
import com.ricezhou.vsrqg.shared.application.archive.EvaluateArchiveCapability
import com.ricezhou.vsrqg.shared.time.TimeProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

internal class NoneArchiveAdapter : ArchiveAdapter {
    override val provider: ArchiveProvider = ArchiveProvider.NONE

    override fun probe(policy: ArchivePolicy, context: CapabilityProbeContext): List<CapabilityCheck> =
        listOf(CapabilityCheck("provider", false, UNAVAILABLE_MESSAGE))

    override fun archive(
        command: ArchiveCommand,
        policy: ArchivePolicy,
        authorization: ArchiveAuthorization,
    ): ArchiveResult = throw ArchiveUnavailable(UNAVAILABLE_MESSAGE)

    private companion object {
        const val UNAVAILABLE_MESSAGE = "Archive provider is not configured"
    }
}

@Configuration(proxyBeanMethods = false)
internal class ArchiveCapabilityConfiguration {
    @Bean
    fun noneArchiveAdapter(): ArchiveAdapter = NoneArchiveAdapter()

    @Bean
    fun evaluateArchiveCapability(
        adapters: List<ArchiveAdapter>,
        timeProvider: TimeProvider,
    ): EvaluateArchiveCapability = EvaluateArchiveCapability(adapters, timeProvider)
}
