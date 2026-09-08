package com.ricezhou.vsrqg.manifest.adapter

import com.ricezhou.vsrqg.manifest.application.ArtifactPayloadVerifier
import com.ricezhou.vsrqg.manifest.application.ManifestViolation
import com.ricezhou.vsrqg.manifest.application.PayloadVerification
import com.ricezhou.vsrqg.manifest.application.ValidateManifest
import com.ricezhou.vsrqg.manifest.application.ValidationStatus
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class ArtifactPayloadVerificationConfiguration {
    @Bean
    @ConditionalOnMissingBean(ArtifactPayloadVerifier::class)
    fun artifactPayloadVerifier(): ArtifactPayloadVerifier = object : ArtifactPayloadVerifier {
        override fun verify(sha256Values: List<String>) = PayloadVerification(
            ValidationStatus.INCOMPLETE,
            listOf(ManifestViolation(
                code = "ARTIFACT_CHECKSUM_NOT_VERIFIED",
                path = "/artifacts",
                message = "Declared checksums are stored but no artifact payload was available for verification",
            )),
            ValidateManifest.VALIDATOR_VERSION,
        )
    }
}
