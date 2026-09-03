package com.ricezhou.vsrqg.traceability.adapter

import com.ricezhou.vsrqg.traceability.application.BuildProvenanceIngestionPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("vsrqg.traceability.ingestion")
data class BuildProvenanceIngestionProperties(
    val enabled: Boolean = false,
    val maxPayloadBytes: Int = ABSOLUTE_MAX_PAYLOAD_BYTES,
) {
    init {
        require(maxPayloadBytes in 1..ABSOLUTE_MAX_PAYLOAD_BYTES) {
            "TRACEABILITY_MAX_PAYLOAD_BYTES_INVALID"
        }
    }

    companion object {
        const val ABSOLUTE_MAX_PAYLOAD_BYTES = 262_144
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BuildProvenanceIngestionProperties::class)
class BuildProvenanceConfiguration {
    @Bean
    fun buildProvenanceIngestionPolicy(
        properties: BuildProvenanceIngestionProperties,
    ) = BuildProvenanceIngestionPolicy(properties.enabled)
}
