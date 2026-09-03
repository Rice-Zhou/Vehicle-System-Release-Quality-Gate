package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.traceability.adapter.GithubActionsBuildProvenanceValidator
import com.ricezhou.vsrqg.traceability.adapter.JcsBuildProvenanceCanonicalizer
import com.ricezhou.vsrqg.traceability.domain.BuildProvenanceEnvelope
import com.ricezhou.vsrqg.traceability.domain.CanonicalBuildProvenance
import com.ricezhou.vsrqg.traceability.domain.Confidence
import com.ricezhou.vsrqg.traceability.domain.ProvenanceProviderId
import com.ricezhou.vsrqg.traceability.domain.VerificationStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GithubActionsBuildProvenanceValidatorTest {
    private val canonicalizer = JcsBuildProvenanceCanonicalizer(ObjectMapper())
    private val validator = GithubActionsBuildProvenanceValidator()

    @Test
    fun `matching GitHub proof is valid medium and never high`() {
        val observation = validator.validate(canonicalizer.canonicalize(githubEnvelope()))

        assertThat(observation.verificationStatus).isEqualTo(VerificationStatus.VALID)
        assertThat(observation.confidence).isEqualTo(Confidence.MEDIUM)
        assertThat(observation.validatorVersion).isEqualTo("github-actions-provenance/v1")
        assertThat(observation.reasonCode).isEqualTo("PROOF_MATCHED")
        assertThat(observation.confidence).isNotEqualTo(Confidence.HIGH)
    }

    @Test
    fun `proof digest mismatch is an invalid low observation`() {
        val observation = validator.validate(
            canonicalizer.canonicalize(githubEnvelope(proofDigest = OTHER_DIGEST)),
        )

        assertThat(observation.verificationStatus).isEqualTo(VerificationStatus.INVALID)
        assertThat(observation.confidence).isEqualTo(Confidence.LOW)
        assertThat(observation.validatorVersion).isEqualTo("github-actions-provenance/v1")
        assertThat(observation.reasonCode).isEqualTo("PROOF_DIGEST_MISMATCH")
    }

    @Test
    fun `unsupported provider is an unavailable unknown observation`() {
        val observation = validator.validate(
            canonicalizer.canonicalize(
                githubEnvelope(provider = ProvenanceProviderId("generic-provider"), proofDigest = OTHER_DIGEST),
            ),
        )

        assertUnavailable(observation)
    }

    @Test
    fun `workflow repository must exactly match envelope repository`() {
        val observation = validator.validate(
            canonicalizer.canonicalize(
                githubEnvelope(
                    workflowReference = "other/repository/.github/workflows/m1-backend.yml@refs/heads/main",
                    proofDigest = OTHER_DIGEST,
                ),
            ),
        )

        assertUnavailable(observation)
    }

    @Test
    fun `proof repository run and attempt must exactly match envelope`() {
        val mismatches = listOf(
            "https://github.com/other/repository/actions/runs/33705417856/attempts/1",
            "https://github.com/owner/repository/actions/runs/33705417857/attempts/1",
            "https://github.com/owner/repository/actions/runs/33705417856/attempts/2",
        )

        assertThat(mismatches.map { proof ->
            validator.validate(
                canonicalizer.canonicalize(githubEnvelope(proofReference = proof, proofDigest = OTHER_DIGEST)),
            )
        }).allSatisfy(::assertUnavailable)
    }

    @Test
    fun `validator independently rejects non GitHub host and non exact locator`() {
        val canonical = canonicalizer.canonicalize(githubEnvelope())
        val forged = listOf(
            canonical.withProofReference(
                "https://example.com/owner/repository/actions/runs/33705417856/attempts/1",
            ),
            canonical.withProofReference(
                "https://github.com/owner/repository/actions/runs/33705417856/attempts/1?token=secret",
            ),
            canonical.withProofReference(
                "https://user@github.com/owner/repository/actions/runs/33705417856/attempts/1",
            ),
        )

        assertThat(forged.map(validator::validate)).allSatisfy(::assertUnavailable)
    }

    @Test
    fun `validator rejects repository dot segment even when raw proof path text matches`() {
        val canonical = canonicalizer.canonicalize(githubEnvelope())
        val bypass = canonical.copy(
            normalized = canonical.normalized.copy(
                repository = "owner/..",
                workflowReference = "owner/../.github/workflows/m1-backend.yml@refs/heads/main",
                proofReference = "https://github.com/owner/../actions/runs/33705417856/attempts/1",
                proofDigest = canonical.recomputedProofDigest,
            ),
        )

        assertUnavailable(validator.validate(bypass))
    }

    @Test
    fun `every validator path has confidence below high`() {
        val valid = validator.validate(canonicalizer.canonicalize(githubEnvelope()))
        val invalid = validator.validate(canonicalizer.canonicalize(githubEnvelope(proofDigest = OTHER_DIGEST)))
        val unavailable = validator.validate(
            canonicalizer.canonicalize(
                githubEnvelope(provider = ProvenanceProviderId("generic-provider"), proofDigest = OTHER_DIGEST),
            ),
        )

        assertThat(listOf(valid, invalid, unavailable).map { it.confidence }).doesNotContain(Confidence.HIGH)
    }

    private fun assertUnavailable(observation: com.ricezhou.vsrqg.traceability.domain.ProvenanceValidation) {
        assertThat(observation.verificationStatus).isEqualTo(VerificationStatus.ERROR)
        assertThat(observation.confidence).isEqualTo(Confidence.UNKNOWN)
        assertThat(observation.validatorVersion).isEqualTo("github-actions-provenance/v1")
        assertThat(observation.reasonCode).isEqualTo("PROOF_UNAVAILABLE")
    }

    private fun CanonicalBuildProvenance.withProofReference(proofReference: String): CanonicalBuildProvenance =
        copy(normalized = normalized.copy(proofReference = proofReference))

    private fun githubEnvelope(
        provider: ProvenanceProviderId = ProvenanceProviderId("github-actions"),
        workflowReference: String = "owner/repository/.github/workflows/m1-backend.yml@refs/heads/main",
        proofReference: String = "https://github.com/owner/repository/actions/runs/33705417856/attempts/1",
        proofDigest: String = MATCHING_PROOF_DIGEST,
    ) = BuildProvenanceEnvelope(
        schemaVersion = 2,
        projectReference = "project-reference",
        releaseIssueSnapshotId = "isnap_01",
        provider = provider,
        repository = "owner/repository",
        sourceRevision = "0123456789abcdef0123456789abcdef01234567",
        pipeline = "m1-backend",
        buildId = "33705417856",
        buildAttempt = 1,
        workflowReference = workflowReference,
        proofReference = proofReference,
        proofDigest = proofDigest,
        sourceIssueIds = listOf("ISSUE-1"),
        artifactSha256s = listOf(ARTIFACT_DIGEST),
    )

    private companion object {
        const val MATCHING_PROOF_DIGEST =
            "sha256:3e455a4376effa929455a195ce1f3b71aa9865541c137ae84ac8fbd6641eb3a5"
        const val OTHER_DIGEST =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ARTIFACT_DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
