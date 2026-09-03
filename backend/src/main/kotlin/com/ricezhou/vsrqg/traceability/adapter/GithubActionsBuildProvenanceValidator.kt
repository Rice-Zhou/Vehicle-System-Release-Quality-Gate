package com.ricezhou.vsrqg.traceability.adapter

import com.ricezhou.vsrqg.traceability.application.BuildProvenanceValidatorPort
import com.ricezhou.vsrqg.traceability.domain.BuildProvenanceEnvelope
import com.ricezhou.vsrqg.traceability.domain.CanonicalBuildProvenance
import com.ricezhou.vsrqg.traceability.domain.Confidence
import com.ricezhou.vsrqg.traceability.domain.ProvenanceValidation
import com.ricezhou.vsrqg.traceability.domain.VerificationStatus
import java.net.URI
import java.net.URISyntaxException
import org.springframework.stereotype.Component

@Component
class GithubActionsBuildProvenanceValidator : BuildProvenanceValidatorPort {
    override fun validate(provenance: CanonicalBuildProvenance): ProvenanceValidation {
        if (!hasAvailableProof(provenance.normalized)) return UNAVAILABLE
        if (provenance.normalized.proofDigest != provenance.recomputedProofDigest) return DIGEST_MISMATCH
        return MATCHED
    }

    private fun hasAvailableProof(envelope: BuildProvenanceEnvelope): Boolean {
        if (envelope.provider.value != PROVIDER_ID) return false
        val workflowRepository = envelope.workflowReference.substringBefore(WORKFLOW_MARKER, missingDelimiterValue = "")
        if (workflowRepository != envelope.repository) return false

        val proof = try {
            URI(envelope.proofReference)
        } catch (_: URISyntaxException) {
            return false
        }
        if (
            proof.scheme != HTTPS || proof.host != GITHUB_HOST || proof.port != -1 ||
            proof.rawUserInfo != null || proof.rawQuery != null || proof.rawFragment != null
        ) {
            return false
        }
        val expectedPath =
            "/${envelope.repository}/actions/runs/${envelope.buildId}/attempts/${envelope.buildAttempt}"
        return proof.rawPath == expectedPath
    }

    private companion object {
        const val PROVIDER_ID = "github-actions"
        const val WORKFLOW_MARKER = "/.github/workflows/"
        const val HTTPS = "https"
        const val GITHUB_HOST = "github.com"
        const val VALIDATOR_VERSION = "github-actions-provenance/v1"

        val MATCHED = ProvenanceValidation(
            VerificationStatus.VALID,
            Confidence.MEDIUM,
            VALIDATOR_VERSION,
            "PROOF_MATCHED",
        )
        val DIGEST_MISMATCH = ProvenanceValidation(
            VerificationStatus.INVALID,
            Confidence.LOW,
            VALIDATOR_VERSION,
            "PROOF_DIGEST_MISMATCH",
        )
        val UNAVAILABLE = ProvenanceValidation(
            VerificationStatus.ERROR,
            Confidence.UNKNOWN,
            VALIDATOR_VERSION,
            "PROOF_UNAVAILABLE",
        )
    }
}
