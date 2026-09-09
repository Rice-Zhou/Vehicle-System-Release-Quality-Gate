package com.ricezhou.vsrqg.demo

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ricezhou.vsrqg.issue.adapter.JcsIssueMappingProfileCodec
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import com.ricezhou.vsrqg.traceability.domain.BuildProvenanceEnvelope
import com.ricezhou.vsrqg.traceability.domain.CanonicalBuildProvenance
import com.ricezhou.vsrqg.traceability.domain.Confidence
import com.ricezhou.vsrqg.traceability.domain.ProvenanceProviderId
import com.ricezhou.vsrqg.traceability.domain.VerificationStatus
import com.ricezhou.vsrqg.traceability.adapter.JcsBuildProvenanceCanonicalizer
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(60)
class M2DemoInputsTest {
    @Test
    fun `fixture uses compiled mapping version and fixed terminal input`() {
        val profile = JcsIssueMappingProfileCodec(jacksonObjectMapper()).compile(M2DemoInputs.mappingDefinition())
        val observedAt = Instant.parse("2026-09-08T00:00:00Z")
        val factory = M2DemoInputs.factory(observedAt)

        assertThat(factory.descriptor.sourceType).isEqualTo("FIXTURE")
        assertThat(factory.descriptor.adapterId).isEqualTo("m2-demo-fixture")
        assertThat(factory.descriptor.adapterVersion).isEqualTo("m2-demo-fixture/v1")
        val page = factory.open(profile).fetchChanges(null, com.ricezhou.vsrqg.issue.domain.IssueFilter(), 20)
        assertThat(page.terminal).isTrue()
        assertThat(page.observedAt).isEqualTo(observedAt)
        assertThat(page.issues).extracting<String> { it.sourceIssueId }.containsExactly("DEMO-1", "DEMO-2")
        assertThat(page.issues).allSatisfy {
            assertThat(it.status).isEqualTo(IssueStatus.CLOSED)
            assertThat(it.severity).isEqualTo(IssueSeverity.HIGH)
            assertThat(it.sourceVersion).isEqualTo("1")
            assertThat(it.sourceReference).isEqualTo("SYNTHETIC_DEMO")
            assertThat(it.observedAt).isEqualTo(observedAt)
            assertThat(it.mappingVersion).isEqualTo(profile.mappingVersion)
        }
        assertThat(factory.open(profile).fetchByIds(setOf("DEMO-1", "DEMO-2")).issues)
            .extracting<String> { it.mappingVersion }.containsOnly(profile.mappingVersion)
    }

    @Test
    fun `mapping definition has the fixed synthetic aliases and policies`() {
        assertThat(M2DemoInputs.mappingDefinition()).isEqualTo(jacksonObjectMapper().readTree("""
            {
              "schemaVersion":"jira-mapping-profile/v1",
              "normalizationVersion":"unicode-nfc-trim-root-lower/v1",
              "unknownStatusPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
              "unknownSeverityPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
              "statusAliases":{"CLOSED":["Closed"]},
              "severityAliases":{"HIGH":["Major"]}
            }
        """.trimIndent()))
    }

    @Test
    fun `validator accepts only the two bound synthetic build pairs`() {
        val validator = M2DemoProvenanceValidator(PAYLOAD_SHA)
        listOf("1" to "a", "2" to "b").forEach { (buildId, revision) ->
            val validation = validator.validate(provenance(buildId, revision.repeat(40), "DEMO-$buildId"))
            assertThat(validation.verificationStatus).isEqualTo(VerificationStatus.VALID)
            assertThat(validation.confidence).isEqualTo(Confidence.LOW)
            assertThat(validation.validatorVersion).isEqualTo("m2-demo-fixture-provenance/v1")
            assertThat(validation.reasonCode).isEqualTo("SYNTHETIC_FIXTURE_MATCHED")
        }
    }

    @Test
    fun `validator rejects every mismatch without changing its confidence or version`() {
        val baseline = provenance()
        val mismatches = listOf(
            baseline.copy(normalized = baseline.normalized.copy(buildId = "3")),
            baseline.copy(normalized = baseline.normalized.copy(sourceRevision = "b".repeat(40))),
            baseline.copy(normalized = baseline.normalized.copy(sourceIssueIds = listOf("DEMO-2"))),
            baseline.copy(normalized = baseline.normalized.copy(provider = ProvenanceProviderId("gitlab"))),
            baseline.copy(normalized = baseline.normalized.copy(repository = "other/demo")),
            baseline.copy(normalized = baseline.normalized.copy(pipeline = "other")),
            baseline.copy(normalized = baseline.normalized.copy(buildAttempt = 2)),
            baseline.copy(normalized = baseline.normalized.copy(workflowReference = "other/demo/.github/workflows/demo.yml@synthetic")),
            baseline.copy(normalized = baseline.normalized.copy(proofReference = "https://github.com/vsrqg-synthetic/demo/actions/runs/2/attempts/1")),
            baseline.copy(normalized = baseline.normalized.copy(artifactSha256s = listOf("1".repeat(64)))),
            baseline.copy(normalized = baseline.normalized.copy(proofDigest = "sha256:${"1".repeat(64)}")),
        )

        mismatches.forEach { provenance ->
            val validation = M2DemoProvenanceValidator(PAYLOAD_SHA).validate(provenance)
            assertThat(validation.verificationStatus).isEqualTo(VerificationStatus.INVALID)
            assertThat(validation.confidence).isEqualTo(Confidence.LOW)
            assertThat(validation.validatorVersion).isEqualTo("m2-demo-fixture-provenance/v1")
            assertThat(validation.reasonCode).isEqualTo("SYNTHETIC_FIXTURE_MISMATCH")
        }
    }

    private fun provenance(
        buildId: String = "1",
        revision: String = "a".repeat(40),
        issueId: String = "DEMO-1",
    ): CanonicalBuildProvenance {
        val canonicalizer = JcsBuildProvenanceCanonicalizer(jacksonObjectMapper())
        val envelope = BuildProvenanceEnvelope(
                schemaVersion = 2,
                projectReference = "demo-project",
                releaseIssueSnapshotId = "snapshot",
                provider = ProvenanceProviderId("github-actions"),
                repository = "vsrqg-synthetic/demo",
                sourceRevision = revision,
                pipeline = "synthetic-m2",
                buildId = buildId,
                buildAttempt = 1,
                workflowReference = "vsrqg-synthetic/demo/.github/workflows/demo.yml@synthetic",
                proofReference = "https://github.com/vsrqg-synthetic/demo/actions/runs/$buildId/attempts/1",
                proofDigest = "sha256:${"0".repeat(64)}",
                sourceIssueIds = listOf(issueId),
                artifactSha256s = listOf(PAYLOAD_SHA),
            )
        val measured = canonicalizer.canonicalize(envelope)
        return canonicalizer.canonicalize(envelope.copy(proofDigest = measured.recomputedProofDigest))
    }

    private companion object {
        const val PAYLOAD_SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
