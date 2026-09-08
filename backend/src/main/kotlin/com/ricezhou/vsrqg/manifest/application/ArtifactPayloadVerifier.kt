package com.ricezhou.vsrqg.manifest.application

interface ArtifactPayloadVerifier {
    fun verify(sha256Values: List<String>): PayloadVerification
}

data class PayloadVerification(
    val status: ValidationStatus,
    val violations: List<ManifestViolation>,
    val validatorVersion: String,
)
