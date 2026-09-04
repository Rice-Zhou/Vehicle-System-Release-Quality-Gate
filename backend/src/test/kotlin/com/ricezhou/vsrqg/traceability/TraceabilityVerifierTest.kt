package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.traceability.adapter.JcsTraceabilityCanonicalizer
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationFailure
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerifier
import com.ricezhou.vsrqg.traceability.domain.Confidence
import com.ricezhou.vsrqg.traceability.domain.LockedManifest
import com.ricezhou.vsrqg.traceability.domain.PinnedIssueSnapshot
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdge
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdgeAuthority
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdgeType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityEntityType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityExpectedEdgeType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityGapCode
import com.ricezhou.vsrqg.traceability.domain.TraceabilityIssue
import com.ricezhou.vsrqg.traceability.domain.TraceabilityIssueResult
import com.ricezhou.vsrqg.traceability.domain.VerificationInput
import com.ricezhou.vsrqg.traceability.domain.VerificationStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class TraceabilityVerifierTest {
    private val verifier = TraceabilityVerifier(JcsTraceabilityCanonicalizer(ObjectMapper()))

    @Test
    fun `complete path is fixed and included but never verified in m25`() {
        val result = verifier.verify(knownChain())

        assertThat(result.issueResults.single())
            .extracting("fixed", "included", "verified")
            .containsExactly(true, true, false)
        assertThat(result.issueResults.single().path.map(PinnedTraceabilityEdge::sourceEdgeId))
            .containsExactly("ic-1", "cb-1", "ba-1", "ar-1")
        assertThat(result.gaps.map { it.diagnosticCode })
            .containsExactly(TraceabilityGapCode.TEST_RESULT_EVIDENCE_MISSING)
        assertThat(result.gaps.single().breakEntityType).isEqualTo(TraceabilityEntityType.RELEASE)
        assertThat(result.gaps.single().breakEntityId).isEqualTo("release-1")
        assertThat(result.gaps.single().expectedEdgeType)
            .isEqualTo(TraceabilityExpectedEdgeType.TEST_RESULT_EVIDENCE)
        assertThat(result.gaps.single().predecessorEdge?.sourceEdgeId).isEqualTo("ar-1")
        assertThat(result.contentDigest).matches(PREFIXED_DIGEST.pattern)
    }

    @ParameterizedTest
    @EnumSource(
        value = TraceabilityGapCode::class,
        names = [
            "ISSUE_COMMIT_MISSING",
            "COMMIT_BUILD_MISSING",
            "BUILD_ARTIFACT_MISSING",
            "ARTIFACT_RELEASE_MISSING",
        ],
    )
    fun `first broken segment produces one exact structural gap`(code: TraceabilityGapCode) {
        val computation = verifier.verify(chainMissing(code))
        val result = computation.issueResults.single()
        val gap = computation.gaps.single()
        val expectation = gapExpectation(code)

        assertThat(gap.diagnosticCode).isEqualTo(code)
        assertThat(gap.breakEntityType).isEqualTo(expectation.breakEntityType)
        assertThat(gap.breakEntityId).isEqualTo(expectation.breakEntityId)
        assertThat(gap.expectedEdgeType).isEqualTo(expectation.expectedEdgeType)
        assertThat(gap.predecessorEdge?.sourceEdgeId).isEqualTo(expectation.predecessorEdgeId)
        assertThat(result.path.map(PinnedTraceabilityEdge::sourceEdgeId)).containsExactlyElementsOf(expectation.path)
        assertThat(result.fixed).isEqualTo(code != TraceabilityGapCode.ISSUE_COMMIT_MISSING)
        assertThat(result.included).isFalse()
        assertThat(result.verified).isFalse()
    }

    @Test
    fun `deterministic full path wins even when an earlier branch is dead`() {
        val edges = knownEdges() + listOf(
            edge(PinnedTraceabilityEdgeType.ISSUE_COMMIT, "issue-1", "commit-0", "ic-dead"),
            edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "commit-0", "build-0", "cb-dead"),
            edge(PinnedTraceabilityEdgeType.BUILD_ARTIFACT, "build-0", "artifact-0", "ba-dead"),
            edge(PinnedTraceabilityEdgeType.ISSUE_COMMIT, "issue-1", "commit-2", "ic-2"),
            edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "commit-2", "build-2", "cb-2"),
            edge(PinnedTraceabilityEdgeType.BUILD_ARTIFACT, "build-2", "artifact-2", "ba-2"),
            manifestEdge("artifact-2", "release-1", "ar-2"),
        )

        val selectedPaths = List(30) { seed ->
            verifier.verify(input(edges.shuffled(kotlin.random.Random(seed)))).issueResults.single().path
                .map(PinnedTraceabilityEdge::sourceEdgeId)
        }

        assertThat(selectedPaths).containsOnly(listOf("ic-1", "cb-1", "ba-1", "ar-1"))
    }

    @Test
    fun `deepest deterministic reachable prefix identifies the actual first break`() {
        val edges = listOf(
            edge(PinnedTraceabilityEdgeType.ISSUE_COMMIT, "issue-1", "commit-0", "ic-dead"),
            edge(PinnedTraceabilityEdgeType.ISSUE_COMMIT, "issue-1", "commit-1", "ic-live"),
            edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "commit-1", "build-1", "cb-live"),
        )

        val result = verifier.verify(input(edges))

        assertThat(result.gaps.single().diagnosticCode).isEqualTo(TraceabilityGapCode.BUILD_ARTIFACT_MISSING)
        assertThat(result.issueResults.single().path.map(PinnedTraceabilityEdge::sourceEdgeId))
            .containsExactly("ic-live", "cb-live")
    }

    @Test
    fun `issues are isolated and cross issue edges cannot complete another issue path`() {
        val issues = listOf(
            TraceabilityIssue("issue-1", "SRC-1"),
            TraceabilityIssue("issue-2", "SRC-2"),
        )
        val edges = listOf(
            edge(PinnedTraceabilityEdgeType.ISSUE_COMMIT, "issue-1", "commit-1", "ic-1"),
            edge(PinnedTraceabilityEdgeType.ISSUE_COMMIT, "issue-2", "commit-2", "ic-2"),
            edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "commit-2", "build-2", "cb-2"),
            edge(PinnedTraceabilityEdgeType.BUILD_ARTIFACT, "build-2", "artifact-2", "ba-2"),
            manifestEdge("artifact-2", "release-1", "ar-2"),
        )

        val results = verifier.verify(input(edges, issues)).issueResults.associateBy(TraceabilityIssueResult::issueId)

        assertThat(results.getValue("issue-1"))
            .extracting("fixed", "included", "verified")
            .containsExactly(true, false, false)
        assertThat(results.getValue("issue-1").gaps.single().diagnosticCode)
            .isEqualTo(TraceabilityGapCode.COMMIT_BUILD_MISSING)
        assertThat(results.getValue("issue-2"))
            .extracting("fixed", "included", "verified")
            .containsExactly(true, true, false)
    }

    @ParameterizedTest
    @EnumSource(value = VerificationStatus::class, names = ["INVALID", "CONFLICT", "ERROR"])
    fun `any untrusted pinned revision fails closed`(status: VerificationStatus) {
        val edges = knownEdges().toMutableList()
        edges[2] = edges[2].copy(verificationStatus = status)

        assertDiagnostic("TRACEABILITY_INPUT_NOT_VALID") {
            verifier.verify(input(edges))
        }
    }

    @Test
    fun `artifact release fact must be manifest derived`() {
        val edges = knownEdges().toMutableList()
        edges[3] = edges[3].copy(authority = PinnedTraceabilityEdgeAuthority.EDGE_REVISION)

        assertDiagnostic("TRACEABILITY_INPUT_NOT_VALID") {
            verifier.verify(input(edges))
        }
    }

    @Test
    fun `twenty issues and two thousand pinned edges are accepted`() {
        val issues = (1..20).map { TraceabilityIssue("issue-$it", "SRC-$it") }
        val edges = (1..2_000).map { ordinal ->
            edge(
                PinnedTraceabilityEdgeType.COMMIT_BUILD,
                "unrelated-commit-$ordinal",
                "unrelated-build-$ordinal",
                "edge-${ordinal.toString().padStart(4, '0')}",
            )
        }

        val result = verifier.verify(input(edges, issues))

        assertThat(result.issueResults).hasSize(20)
        assertThat(result.gaps).hasSize(20)
        assertThat(result.gaps).allMatch { it.diagnosticCode == TraceabilityGapCode.ISSUE_COMMIT_MISSING }
    }

    @Test
    fun `two thousand and one pinned edges fail before traversal`() {
        val edges = (1..2_001).map { ordinal ->
            edge(
                PinnedTraceabilityEdgeType.COMMIT_BUILD,
                "commit-$ordinal",
                "build-$ordinal",
                "edge-${ordinal.toString().padStart(4, '0')}",
            )
        }

        assertDiagnostic("TRACEABILITY_INPUT_LIMIT_EXCEEDED") {
            verifier.verify(input(edges))
        }
    }

    @Test
    fun `twenty one snapshot issues fail closed`() {
        val issues = (1..21).map { TraceabilityIssue("issue-$it", "SRC-$it") }

        assertDiagnostic("TRACEABILITY_ISSUE_LIMIT_EXCEEDED") {
            verifier.verify(input(emptyList(), issues))
        }
    }

    @Test
    fun `m25 issue result constructor rejects verified true`() {
        assertThatThrownBy {
            TraceabilityIssueResult(
                issueId = "issue-1",
                sourceIssueId = "SRC-1",
                fixed = true,
                included = true,
                verified = true,
                path = knownEdges(),
                gaps = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("VERIFIED_TRUE_NOT_SUPPORTED")
    }

    @Test
    fun `included issue result requires fixed`() {
        assertThatThrownBy {
            TraceabilityIssueResult(
                issueId = "issue-1",
                sourceIssueId = "SRC-1",
                fixed = false,
                included = true,
                verified = false,
                path = emptyList(),
                gaps = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("INCLUDED_REQUIRES_FIXED")
    }

    @Test
    fun `verification input takes immutable snapshots of supplied collections`() {
        val suppliedIssues = mutableListOf(TraceabilityIssue("issue-1", "SRC-1"))
        val suppliedEdges = knownEdges().toMutableList()
        val candidate = input(suppliedEdges, suppliedIssues)

        suppliedIssues.clear()
        suppliedEdges.clear()

        assertThat(candidate.issueSnapshot.issues).hasSize(1)
        assertThat(candidate.edgeRevisions).hasSize(4)
        assertThatThrownBy {
            @Suppress("UNCHECKED_CAST")
            (candidate.edgeRevisions as MutableList<PinnedTraceabilityEdge>).clear()
        }.isInstanceOf(UnsupportedOperationException::class.java)
    }

    private fun knownChain(): VerificationInput = input(knownEdges())

    private fun knownEdges(): List<PinnedTraceabilityEdge> = listOf(
        edge(PinnedTraceabilityEdgeType.ISSUE_COMMIT, "issue-1", "commit-1", "ic-1"),
        edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "commit-1", "build-1", "cb-1"),
        edge(PinnedTraceabilityEdgeType.BUILD_ARTIFACT, "build-1", "artifact-1", "ba-1"),
        manifestEdge("artifact-1", "release-1", "ar-1"),
    )

    private fun chainMissing(code: TraceabilityGapCode): VerificationInput {
        val missingType = when (code) {
            TraceabilityGapCode.ISSUE_COMMIT_MISSING -> PinnedTraceabilityEdgeType.ISSUE_COMMIT
            TraceabilityGapCode.COMMIT_BUILD_MISSING -> PinnedTraceabilityEdgeType.COMMIT_BUILD
            TraceabilityGapCode.BUILD_ARTIFACT_MISSING -> PinnedTraceabilityEdgeType.BUILD_ARTIFACT
            TraceabilityGapCode.ARTIFACT_RELEASE_MISSING -> PinnedTraceabilityEdgeType.ARTIFACT_RELEASE
            TraceabilityGapCode.TEST_RESULT_EVIDENCE_MISSING -> error("not a structural gap")
        }
        return input(knownEdges().filterNot { it.edgeType == missingType })
    }

    private fun input(
        edges: List<PinnedTraceabilityEdge>,
        issues: List<TraceabilityIssue> = listOf(TraceabilityIssue("issue-1", "SRC-1")),
    ) = VerificationInput(
        schemaVersion = "traceability-verification/v1",
        policyVersion = "m2.5-traceability-policy/v1",
        validatorVersion = "m2.5-path-validator/v1",
        releaseId = "release-1",
        issueSnapshot = PinnedIssueSnapshot("isnap-1", "sha256:${"1".repeat(64)}", issues),
        manifest = LockedManifest("release-1", "mrev-1", "sha256:${"2".repeat(64)}"),
        edgeRevisions = edges,
    )

    private fun edge(
        type: PinnedTraceabilityEdgeType,
        fromId: String,
        toId: String,
        sourceEdgeId: String,
        revision: Int = 1,
        status: VerificationStatus = VerificationStatus.VALID,
    ) = PinnedTraceabilityEdge(
        edgeType = type,
        fromId = fromId,
        toId = toId,
        sourceEdgeId = sourceEdgeId,
        sourceEdgeRevision = revision,
        verificationStatus = status,
        confidence = Confidence.HIGH,
        factDigest = "sha256:${sourceEdgeId.hashCode().toUInt().toString(16).padStart(64, '0')}",
        authority = PinnedTraceabilityEdgeAuthority.EDGE_REVISION,
    )

    private fun manifestEdge(fromId: String, toId: String, sourceEdgeId: String) =
        edge(PinnedTraceabilityEdgeType.ARTIFACT_RELEASE, fromId, toId, sourceEdgeId)
            .copy(authority = PinnedTraceabilityEdgeAuthority.LOCKED_MANIFEST)

    private fun gapExpectation(code: TraceabilityGapCode): GapExpectation = when (code) {
        TraceabilityGapCode.ISSUE_COMMIT_MISSING -> GapExpectation(
            TraceabilityEntityType.ISSUE,
            "issue-1",
            TraceabilityExpectedEdgeType.ISSUE_COMMIT,
            null,
            emptyList(),
        )
        TraceabilityGapCode.COMMIT_BUILD_MISSING -> GapExpectation(
            TraceabilityEntityType.COMMIT,
            "commit-1",
            TraceabilityExpectedEdgeType.COMMIT_BUILD,
            "ic-1",
            listOf("ic-1"),
        )
        TraceabilityGapCode.BUILD_ARTIFACT_MISSING -> GapExpectation(
            TraceabilityEntityType.BUILD,
            "build-1",
            TraceabilityExpectedEdgeType.BUILD_ARTIFACT,
            "cb-1",
            listOf("ic-1", "cb-1"),
        )
        TraceabilityGapCode.ARTIFACT_RELEASE_MISSING -> GapExpectation(
            TraceabilityEntityType.ARTIFACT,
            "artifact-1",
            TraceabilityExpectedEdgeType.ARTIFACT_RELEASE,
            "ba-1",
            listOf("ic-1", "cb-1", "ba-1"),
        )
        TraceabilityGapCode.TEST_RESULT_EVIDENCE_MISSING -> error("not a structural gap")
    }

    private fun assertDiagnostic(code: String, block: () -> Unit) {
        assertThatThrownBy(block)
            .isInstanceOf(TraceabilityVerificationFailure::class.java)
            .extracting("diagnosticCode")
            .isEqualTo(code)
    }

    private data class GapExpectation(
        val breakEntityType: TraceabilityEntityType,
        val breakEntityId: String,
        val expectedEdgeType: TraceabilityExpectedEdgeType,
        val predecessorEdgeId: String?,
        val path: List<String>,
    )

    private companion object {
        val PREFIXED_DIGEST = Regex("^sha256:[0-9a-f]{64}$")
    }
}
