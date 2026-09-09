package com.ricezhou.vsrqg.demo

import com.ricezhou.vsrqg.traceability.application.BuildProvenanceValidatorPort
import com.ricezhou.vsrqg.traceability.domain.CanonicalBuildProvenance
import com.ricezhou.vsrqg.traceability.domain.Confidence
import com.ricezhou.vsrqg.traceability.domain.ProvenanceValidation
import com.ricezhou.vsrqg.traceability.domain.VerificationStatus

class M2DemoProvenanceValidator(
    private val payloadSha256: String,
) : BuildProvenanceValidatorPort {
    init {
        require(SHA256.matches(payloadSha256)) { "M2_DEMO_PAYLOAD_SHA256_INVALID" }
    }

    override fun validate(provenance: CanonicalBuildProvenance): ProvenanceValidation {
        val envelope = provenance.normalized
        val pairMatches = when (envelope.buildId) {
            "1" -> envelope.sourceRevision == "a".repeat(40) && envelope.sourceIssueIds == listOf("DEMO-1")
            "2" -> envelope.sourceRevision == "b".repeat(40) && envelope.sourceIssueIds == listOf("DEMO-2")
            else -> false
        }
        val matches = pairMatches &&
            envelope.provider.value == "github-actions" &&
            envelope.repository == "vsrqg-synthetic/demo" &&
            envelope.pipeline == "synthetic-m2" &&
            envelope.buildAttempt == 1 &&
            envelope.workflowReference == "vsrqg-synthetic/demo/.github/workflows/demo.yml@synthetic" &&
            envelope.proofReference ==
            "https://github.com/vsrqg-synthetic/demo/actions/runs/${envelope.buildId}/attempts/1" &&
            envelope.artifactSha256s == listOf(payloadSha256) &&
            envelope.proofDigest == provenance.recomputedProofDigest
        return ProvenanceValidation(
            verificationStatus = if (matches) VerificationStatus.VALID else VerificationStatus.INVALID,
            confidence = Confidence.LOW,
            validatorVersion = "m2-demo-fixture-provenance/v1",
            reasonCode = if (matches) "SYNTHETIC_FIXTURE_MATCHED" else "SYNTHETIC_FIXTURE_MISMATCH",
        )
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
