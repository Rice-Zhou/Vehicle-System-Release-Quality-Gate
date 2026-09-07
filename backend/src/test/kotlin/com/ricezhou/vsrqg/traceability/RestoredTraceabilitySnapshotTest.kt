package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.traceability.adapter.JcsTraceabilityCanonicalizer
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerifier
import com.ricezhou.vsrqg.traceability.domain.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

internal class RestoredTraceabilitySnapshotTest {
    private val canonicalizer = JcsTraceabilityCanonicalizer(ObjectMapper())

    private fun knownChain(): VerificationInput {
        val digest = "sha256:" + "1".repeat(64)
        val nodes = listOf("issue-1", "commit-1", "build-1", "artifact-1", "release-1")
        val edges = PinnedTraceabilityEdgeType.entries.mapIndexed { ordinal, type ->
            PinnedTraceabilityEdge(
                "project-1", type, nodes[ordinal], nodes[ordinal + 1], "edge-$ordinal", 1,
                if (ordinal == 3) "manifest-1" else "revision-$ordinal",
                VerificationStatus.VALID, Confidence.HIGH, digest,
                if (ordinal == 3) PinnedTraceabilityEdgeAuthority.LOCKED_MANIFEST
                else PinnedTraceabilityEdgeAuthority.EDGE_REVISION,
            )
        }
        return VerificationInput(
            "traceability-verification/v1", "m2.5-traceability-policy/v1", "m2.5-path-validator/v1",
            "project-1", "release-1",
            PinnedIssueSnapshot("project-1", "release-1", "snapshot-1", digest, listOf(TraceabilityIssue("issue-1", "SRC-1"))),
            LockedManifest("project-1", "release-1", "manifest-1", digest),
            edges + edges.first().copy(
                sourceEdgeId = "edge-alternate", sourceEdgeRevisionId = "revision-alternate", toId = "commit-dead",
            ),
        )
    }

    @Test
    fun `restored content detects mutations without changing any stored digest`() {
        val input = knownChain()
        val computed = TraceabilityVerifier(canonicalizer).verify(input)
        val projection = TraceabilityCanonicalProjectionFactory.result(
            input, computed.issueResults, computed.pathEdges, computed.gaps,
        )
        val issue = computed.issueResults.single()
        val content = TraceabilityCanonicalProjectionFactory.issueResultContent(
            issue.issueId, issue.sourceIssueId, issue.fixed, issue.included, issue.verified,
            issue.confidence, issue.path, issue.gaps,
        )
        val restored = RestoredTraceabilitySnapshot(projection, listOf(content), computed.contentDigest)
        assertThat(restored.verify(canonicalizer)).isEqualTo(computed.contentDigest)
        val corruptions = listOf(
            restored.copy(issueContents = listOf(content.copy(fixed = false))),
            restored.copy(issueContents = listOf(content.copy(included = false))),
            restored.copy(issueContents = listOf(content.copy(verified = true))),
            restored.copy(issueContents = listOf(content.copy(confidence = Confidence.LOW))),
            restored.copy(projection = projection.copy(gaps = projection.gaps.map { it.copy(reason = "CORRUPTED") })),
            restored.copy(projection = projection.copy(pathEdges = projection.pathEdges.map {
                it.copy(edge = it.edge.copy(toId = "corrupted-target"))
            })),
            restored.copy(projection = projection.copy(input = projection.input.copy(
                edgeFacts = projection.input.edgeFacts.map {
                    if (it.sourceEdgeId == "edge-alternate") it.copy(sourceEdgeRevision = it.sourceEdgeRevision + 1)
                    else it
                },
            ))),
        )
        corruptions.forEachIndexed { index, corrupted ->
            assertThatThrownBy { corrupted.verify(canonicalizer) }
                .describedAs("persisted content mutation %s", index)
                .isInstanceOf(IllegalStateException::class.java)
        }
    }
}
