package com.ricezhou.vsrqg.traceability.adapter

import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("vsrqg.traceability.verification")
data class TraceabilityVerificationProperties(
    val enabled: Boolean = false,
    val policyVersion: String = "m2.5-traceability-policy/v1",
    val validatorVersion: String = "m2.5-path-validator/v1",
    val maxIssues: Int = 20,
    val maxEdgeRevisions: Int = 2_000,
) {
    init {
        require(policyVersion.matches(VERSION)) { "TRACEABILITY_POLICY_VERSION_INVALID" }
        require(validatorVersion.matches(VERSION)) { "TRACEABILITY_VALIDATOR_VERSION_INVALID" }
        require(maxIssues in 1..20) { "TRACEABILITY_MAX_ISSUES_INVALID" }
        require(maxEdgeRevisions in 1..2_000) { "TRACEABILITY_MAX_EDGE_REVISIONS_INVALID" }
    }

    private companion object {
        val VERSION = Regex("^[a-z0-9][a-z0-9._/-]{0,79}$")
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TraceabilityVerificationProperties::class)
class TraceabilityVerificationConfiguration {
    @Bean
    fun traceabilityVerificationPolicy(properties: TraceabilityVerificationProperties) =
        TraceabilityVerificationPolicy(
            properties.enabled,
            properties.policyVersion,
            properties.validatorVersion,
            properties.maxIssues,
            properties.maxEdgeRevisions,
        )
}
