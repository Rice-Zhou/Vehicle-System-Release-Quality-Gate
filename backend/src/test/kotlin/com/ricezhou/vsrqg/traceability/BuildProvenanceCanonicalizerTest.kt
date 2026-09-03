package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.traceability.adapter.JcsBuildProvenanceCanonicalizer
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceInvalid
import com.ricezhou.vsrqg.traceability.domain.BuildProvenanceEnvelope
import com.ricezhou.vsrqg.traceability.domain.ProvenanceProviderId
import java.nio.charset.StandardCharsets
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BuildProvenanceCanonicalizerTest {
    private val canonicalizer = JcsBuildProvenanceCanonicalizer(ObjectMapper())

    @Test
    fun `canonical envelope sorts sets and excludes request metadata`() {
        val first = canonicalizer.canonicalize(
            envelope(
                sourceIssueIds = listOf("ISSUE-2", "ISSUE-1"),
                artifactSha256s = listOf(DIGEST_B, DIGEST_A),
            ),
        )
        val second = canonicalizer.canonicalize(
            envelope(
                sourceIssueIds = listOf("ISSUE-1", "ISSUE-2"),
                artifactSha256s = listOf(DIGEST_A, DIGEST_B),
            ),
        )

        assertThat(first.envelopeDigest).isEqualTo(second.envelopeDigest)
        assertThat(first.canonicalBytes).containsExactly(*second.canonicalBytes)
        assertThat(first.normalized.sourceIssueIds).containsExactly("ISSUE-1", "ISSUE-2")
        assertThat(first.normalized.artifactSha256s).containsExactly(DIGEST_A, DIGEST_B)
        assertThat(String(first.canonicalBytes, StandardCharsets.UTF_8))
            .doesNotContain("requestId", "idempotencyKey")
    }

    @Test
    fun `canonical envelope uses JCS bytes with all normalized request fields`() {
        val canonical = canonicalizer.canonicalize(envelope())

        assertThat(String(canonical.canonicalBytes, StandardCharsets.UTF_8)).isEqualTo(
            """{"artifactSha256s":["$DIGEST_A"],"buildAttempt":1,"buildId":"33705417856","pipeline":"m1-backend","projectReference":"project-reference","proofDigest":"$PLACEHOLDER_PROOF_DIGEST","proofReference":"https://github.com/owner/repository/actions/runs/33705417856/attempts/1","provider":"github-actions","releaseIssueSnapshotId":"isnap_01","repository":"owner/repository","schemaVersion":2,"sourceIssueIds":["ISSUE-1"],"sourceRevision":"0123456789abcdef0123456789abcdef01234567","workflowReference":"owner/repository/.github/workflows/m1-backend.yml@refs/heads/main"}""",
        )
        assertThat(canonical.envelopeDigest).matches(PREFIXED_DIGEST.pattern)
        assertThat(canonical.recomputedProofDigest).matches(PREFIXED_DIGEST.pattern)
        assertThat(canonical.derivedFactCount).isEqualTo(3)
    }

    @Test
    fun `every normalized request field contributes to envelope digest`() {
        val baseline = canonicalizer.canonicalize(envelope()).envelopeDigest
        val changed = listOf(
            envelope(projectReference = "other-project"),
            envelope(releaseIssueSnapshotId = "isnap_02"),
            envelope(provider = ProvenanceProviderId("generic-provider")),
            envelope(repository = "other/repository"),
            envelope(sourceRevision = "1123456789abcdef0123456789abcdef01234567"),
            envelope(pipeline = "other-pipeline"),
            envelope(buildId = "33705417857"),
            envelope(buildAttempt = 2),
            envelope(workflowReference = "owner/repository/.github/workflows/other.yml@refs/heads/main"),
            envelope(proofReference = "https://github.com/owner/repository/actions/runs/33705417857/attempts/1"),
            envelope(proofDigest = "sha256:$DIGEST_B"),
            envelope(sourceIssueIds = listOf("ISSUE-2")),
            envelope(artifactSha256s = listOf(DIGEST_B)),
        )

        assertThat(changed.map { canonicalizer.canonicalize(it).envelopeDigest })
            .allSatisfy { assertThat(it).isNotEqualTo(baseline) }
    }

    @Test
    fun `proof digest covers only the fixed proof fields`() {
        val baseline = canonicalizer.canonicalize(envelope()).recomputedProofDigest
        val excludedChanges = listOf(
            envelope(projectReference = "other-project"),
            envelope(releaseIssueSnapshotId = "isnap_02"),
            envelope(proofDigest = "sha256:$DIGEST_B"),
            envelope(sourceIssueIds = listOf("ISSUE-2")),
            envelope(artifactSha256s = listOf(DIGEST_B)),
        )
        val includedChanges = listOf(
            envelope(provider = ProvenanceProviderId("generic-provider")),
            envelope(repository = "other/repository"),
            envelope(sourceRevision = "1123456789abcdef0123456789abcdef01234567"),
            envelope(pipeline = "other-pipeline"),
            envelope(buildId = "33705417857"),
            envelope(buildAttempt = 2),
            envelope(workflowReference = "owner/repository/.github/workflows/other.yml@refs/heads/main"),
            envelope(proofReference = "https://github.com/owner/repository/actions/runs/33705417857/attempts/1"),
        )

        assertThat(excludedChanges.map { canonicalizer.canonicalize(it).recomputedProofDigest })
            .containsOnly(baseline)
        assertThat(includedChanges.map { canonicalizer.canonicalize(it).recomputedProofDigest })
            .allSatisfy { assertThat(it).isNotEqualTo(baseline) }
    }

    @Test
    fun `normalization is NFC and set order is Unicode code point order`() {
        val canonical = canonicalizer.canonicalize(
            envelope(
                projectReference = "Cafe\u0301",
                pipeline = "build-Cafe\u0301",
                sourceIssueIds = listOf("ISSUE-\uD800\uDC00", "ISSUE-\uE000", "ISSUE-Cafe\u0301"),
            ),
        )

        assertThat(canonical.normalized.projectReference).isEqualTo("Café")
        assertThat(canonical.normalized.pipeline).isEqualTo("build-Café")
        assertThat(canonical.normalized.sourceIssueIds)
            .containsExactly("ISSUE-Café", "ISSUE-\uE000", "ISSUE-\uD800\uDC00")
    }

    @Test
    fun `duplicates including NFC equivalent values are rejected rather than removed`() {
        assertViolation("SOURCE_ISSUE_ID_DUPLICATE") {
            canonicalizer.canonicalize(envelope(sourceIssueIds = listOf("ISSUE-Café", "ISSUE-Cafe\u0301")))
        }
        assertViolation("ARTIFACT_SHA256_DUPLICATE") {
            canonicalizer.canonicalize(envelope(artifactSha256s = listOf(DIGEST_A, DIGEST_A)))
        }
    }

    @Test
    fun `control characters in textual fields are rejected`() {
        val invalidEnvelopes = listOf(
            envelope(projectReference = "project\nreference"),
            envelope(releaseIssueSnapshotId = "isnap_\u007fbad"),
            envelope(provider = ProvenanceProviderId("github\tactions")),
            envelope(repository = "owner/repo\n"),
            envelope(pipeline = "pipeline\r"),
            envelope(buildId = "build\u0000id"),
            envelope(workflowReference = "owner/repository/.github/workflows/build.yml@refs/heads/main\n"),
            envelope(proofReference = "https://github.com/owner/repository/actions/runs/1/attempts/1\n"),
            envelope(sourceIssueIds = listOf("ISSUE\n1")),
        )

        assertThat(invalidEnvelopes).allSatisfy { invalid ->
            assertThatThrownBy { canonicalizer.canonicalize(invalid) }
                .isInstanceOf(BuildProvenanceInvalid::class.java)
        }
    }

    @Test
    fun `malformed Unicode is rejected before canonicalization`() {
        assertViolation("PROJECT_REFERENCE_INVALID") {
            canonicalizer.canonicalize(envelope(projectReference = "project-\uD800"))
        }
        assertViolation("SOURCE_ISSUE_ID_INVALID") {
            canonicalizer.canonicalize(envelope(sourceIssueIds = listOf("ISSUE-\uDC00")))
        }
    }

    @Test
    fun `only schema version two is accepted`() {
        assertViolation("SCHEMA_VERSION_UNSUPPORTED") {
            canonicalizer.canonicalize(envelope(schemaVersion = 1))
        }
    }

    @Test
    fun `full lowercase Git SHA and lowercase artifact digest are required`() {
        listOf(
            "0123456789abcdef0123456789abcdef0123456",
            "0123456789abcdef0123456789abcdef012345678",
            "0123456789abcdef0123456789abcdef0123456G",
        ).forEach { value ->
            assertViolation("SOURCE_REVISION_INVALID") {
                canonicalizer.canonicalize(envelope(sourceRevision = value))
            }
        }
        assertViolation("ARTIFACT_SHA256_INVALID") {
            canonicalizer.canonicalize(envelope(artifactSha256s = listOf(DIGEST_A.uppercase())))
        }
        assertViolation("PROOF_DIGEST_INVALID") {
            canonicalizer.canonicalize(envelope(proofDigest = "sha256:${DIGEST_A.uppercase()}"))
        }
    }

    @Test
    fun `build attempt must be positive`() {
        listOf(0, -1).forEach { attempt ->
            assertViolation("BUILD_ATTEMPT_INVALID") {
                canonicalizer.canonicalize(envelope(buildAttempt = attempt))
            }
        }
    }

    @Test
    fun `request array limits are enforced and twenty plus twenty yields forty one facts`() {
        val atLimit = canonicalizer.canonicalize(
            envelope(
                sourceIssueIds = (1..20).map { "ISSUE-$it" },
                artifactSha256s = (1..20).map(::numberedDigest),
            ),
        )

        assertThat(atLimit.derivedFactCount).isEqualTo(41)
        assertViolation("SOURCE_ISSUE_LIMIT_EXCEEDED") {
            canonicalizer.canonicalize(envelope(sourceIssueIds = (1..21).map { "ISSUE-$it" }))
        }
        assertViolation("ARTIFACT_LIMIT_EXCEEDED") {
            canonicalizer.canonicalize(envelope(artifactSha256s = (1..21).map(::numberedDigest)))
        }
    }

    @Test
    fun `empty arrays and out of bounds text are rejected`() {
        val invalidCases = listOf(
            "PROJECT_REFERENCE_INVALID" to envelope(projectReference = ""),
            "PROJECT_REFERENCE_INVALID" to envelope(projectReference = "p".repeat(129)),
            "RELEASE_ISSUE_SNAPSHOT_ID_INVALID" to envelope(releaseIssueSnapshotId = ""),
            "RELEASE_ISSUE_SNAPSHOT_ID_INVALID" to envelope(releaseIssueSnapshotId = "i".repeat(129)),
            "REPOSITORY_INVALID" to envelope(repository = "o".repeat(256) + "/" + "r".repeat(256)),
            "PIPELINE_INVALID" to envelope(pipeline = ""),
            "PIPELINE_INVALID" to envelope(pipeline = "p".repeat(256)),
            "BUILD_ID_INVALID" to envelope(buildId = ""),
            "BUILD_ID_INVALID" to envelope(buildId = "b".repeat(256)),
            "WORKFLOW_REFERENCE_INVALID" to envelope(
                workflowReference = "owner/repository/.github/workflows/${"w".repeat(1000)}.yml@refs/heads/main",
            ),
            "PROOF_REFERENCE_INVALID" to envelope(
                proofReference = "https://github.com/${"o".repeat(500)}/${"r".repeat(500)}/actions/runs/33705417856/attempts/1",
            ),
            "SOURCE_ISSUE_IDS_INVALID" to envelope(sourceIssueIds = emptyList()),
            "SOURCE_ISSUE_ID_INVALID" to envelope(sourceIssueIds = listOf("I".repeat(256))),
            "ARTIFACT_SHA256S_INVALID" to envelope(artifactSha256s = emptyList()),
        )

        invalidCases.forEach { (code, invalid) ->
            assertViolation(code) { canonicalizer.canonicalize(invalid) }
        }
    }

    @Test
    fun `derived fact hard limit fails closed before narrower request array limits`() {
        assertViolation("FACT_LIMIT_EXCEEDED") {
            canonicalizer.canonicalize(
                envelope(
                    sourceIssueIds = (1..50).map { "ISSUE-$it" },
                    artifactSha256s = (1..50).map(::numberedDigest),
                ),
            )
        }
    }

    @Test
    fun `repository workflow and proof references use strict allowlists`() {
        listOf("owner", "owner/repository/extra", "owner repository/repo", "https://github.com/owner/repo")
            .forEach { repository ->
                assertViolation("REPOSITORY_INVALID") {
                    canonicalizer.canonicalize(envelope(repository = repository))
                }
            }
        listOf(
            "owner/repository/build.yml@main",
            "owner/repository/.github/workflows/../build.yml@refs/heads/main",
            "owner/repository/.github/workflows/build.txt@refs/heads/main",
            "owner/repository/.github/workflows/build.yml?token=secret@refs/heads/main",
        ).forEach { workflow ->
            assertViolation("WORKFLOW_REFERENCE_INVALID") {
                canonicalizer.canonicalize(envelope(workflowReference = workflow))
            }
        }
        listOf(
            "http://github.com/owner/repository/actions/runs/33705417856/attempts/1",
            "https://example.com/owner/repository/actions/runs/33705417856/attempts/1",
            "https://github.com/owner/repository/actions/runs/33705417856/attempts/1?token=secret",
            "https://github.com/owner/repository/actions/runs/not-a-number/attempts/1",
            "https://github.com/owner/repository/actions/runs/33705417856/attempts/0",
        ).forEach { proof ->
            assertViolation("PROOF_REFERENCE_INVALID") {
                canonicalizer.canonicalize(envelope(proofReference = proof))
            }
        }
    }

    @Test
    fun `canonical bytes and digests are stable across three replays`() {
        val results = List(3) { canonicalizer.canonicalize(envelope()) }

        assertThat(results.map { String(it.canonicalBytes, StandardCharsets.UTF_8) }).containsOnly(
            String(results.first().canonicalBytes, StandardCharsets.UTF_8),
        )
        assertThat(results.map { it.envelopeDigest }).containsOnly(results.first().envelopeDigest)
        assertThat(results.map { it.recomputedProofDigest }).containsOnly(results.first().recomputedProofDigest)
    }

    private fun assertViolation(code: String, block: () -> Unit) {
        assertThatThrownBy(block)
            .isInstanceOf(BuildProvenanceInvalid::class.java)
            .extracting("violationCodes")
            .isEqualTo(listOf(code))
    }

    private fun envelope(
        schemaVersion: Int = 2,
        projectReference: String = "project-reference",
        releaseIssueSnapshotId: String = "isnap_01",
        provider: ProvenanceProviderId = ProvenanceProviderId("github-actions"),
        repository: String = "owner/repository",
        sourceRevision: String = "0123456789abcdef0123456789abcdef01234567",
        pipeline: String = "m1-backend",
        buildId: String = "33705417856",
        buildAttempt: Int = 1,
        workflowReference: String = "owner/repository/.github/workflows/m1-backend.yml@refs/heads/main",
        proofReference: String = "https://github.com/owner/repository/actions/runs/33705417856/attempts/1",
        proofDigest: String = PLACEHOLDER_PROOF_DIGEST,
        sourceIssueIds: List<String> = listOf("ISSUE-1"),
        artifactSha256s: List<String> = listOf(DIGEST_A),
    ) = BuildProvenanceEnvelope(
        schemaVersion,
        projectReference,
        releaseIssueSnapshotId,
        provider,
        repository,
        sourceRevision,
        pipeline,
        buildId,
        buildAttempt,
        workflowReference,
        proofReference,
        proofDigest,
        sourceIssueIds,
        artifactSha256s,
    )

    private fun numberedDigest(value: Int): String = value.toString(16).padStart(64, '0')

    private companion object {
        const val DIGEST_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val DIGEST_B = "1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val PLACEHOLDER_PROOF_DIGEST = "sha256:$DIGEST_A"
        val PREFIXED_DIGEST = Regex("^sha256:[0-9a-f]{64}$")
    }
}
