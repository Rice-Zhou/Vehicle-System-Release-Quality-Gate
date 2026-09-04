package com.ricezhou.vsrqg.traceability.application

import com.ricezhou.vsrqg.traceability.domain.Confidence
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdge
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdgeAuthority
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdgeType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityEntityType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityExpectedEdgeType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityGap
import com.ricezhou.vsrqg.traceability.domain.TraceabilityGapCode
import com.ricezhou.vsrqg.traceability.domain.TraceabilityIssue
import com.ricezhou.vsrqg.traceability.domain.TraceabilityIssueResult
import com.ricezhou.vsrqg.traceability.domain.TraceabilityOrdering
import com.ricezhou.vsrqg.traceability.domain.TraceabilityPathEdge
import com.ricezhou.vsrqg.traceability.domain.VerificationComputation
import com.ricezhou.vsrqg.traceability.domain.VerificationInput
import com.ricezhou.vsrqg.traceability.domain.VerificationStatus

class TraceabilityVerificationFailure(
    val diagnosticCode: String,
) : RuntimeException(diagnosticCode)

class TraceabilityVerifier(
    private val canonicalizer: TraceabilityCanonicalizer,
) {
    fun verify(input: VerificationInput): VerificationComputation {
        validate(input)
        val graph = VerificationGraph(input.edgeRevisions, input.releaseId)
        val issueResults = input.issueSnapshot.issues
            .sortedWith(TraceabilityOrdering.issueOrder)
            .map { verifyIssue(it, input.releaseId, graph) }
        val pathEdges = issueResults.flatMap { result ->
            result.path.mapIndexed { ordinal, edge -> TraceabilityPathEdge(result.issueId, ordinal, edge) }
        }
        val gaps = issueResults.flatMap(TraceabilityIssueResult::gaps)
        val contentDigest = canonicalizer.canonicalizeResult(input, issueResults, pathEdges, gaps).digest
        return VerificationComputation(issueResults, pathEdges, gaps, contentDigest)
    }

    private fun validate(input: VerificationInput) {
        if (input.issueSnapshot.issues.size > MAX_ISSUES) fail("TRACEABILITY_ISSUE_LIMIT_EXCEEDED")
        if (input.edgeRevisions.size > MAX_EDGES) fail("TRACEABILITY_INPUT_LIMIT_EXCEEDED")
        if (input.releaseId != input.manifest.releaseId) fail("TRACEABILITY_INPUT_NOT_VALID")
        if (input.issueSnapshot.issues.map(TraceabilityIssue::issueId).toSet().size != input.issueSnapshot.issues.size) {
            fail("TRACEABILITY_INPUT_NOT_VALID")
        }
        if (input.issueSnapshot.issues.map(TraceabilityIssue::sourceIssueId).toSet().size != input.issueSnapshot.issues.size) {
            fail("TRACEABILITY_INPUT_NOT_VALID")
        }
        input.edgeRevisions.forEach { edge ->
            val expectedAuthority = if (edge.edgeType == PinnedTraceabilityEdgeType.ARTIFACT_RELEASE) {
                PinnedTraceabilityEdgeAuthority.LOCKED_MANIFEST
            } else {
                PinnedTraceabilityEdgeAuthority.EDGE_REVISION
            }
            if (
                edge.verificationStatus != VerificationStatus.VALID ||
                edge.authority != expectedAuthority ||
                edge.sourceEdgeRevision <= 0 ||
                !PREFIXED_SHA256.matches(edge.factDigest)
            ) {
                fail("TRACEABILITY_INPUT_NOT_VALID")
            }
        }
    }

    private fun verifyIssue(
        issue: TraceabilityIssue,
        releaseId: String,
        graph: VerificationGraph,
    ): TraceabilityIssueResult {
        val issueCommitEdges = graph.edgesFrom(PinnedTraceabilityEdgeType.ISSUE_COMMIT, issue.issueId)
        if (issueCommitEdges.isEmpty()) {
            return issueResult(
                issue,
                emptyList(),
                gap(
                    issue.issueId,
                    TraceabilityGapCode.ISSUE_COMMIT_MISSING,
                    TraceabilityEntityType.ISSUE,
                    issue.issueId,
                    TraceabilityExpectedEdgeType.ISSUE_COMMIT,
                    null,
                ),
            )
        }

        graph.completePath(issueCommitEdges)?.let { path ->
            return issueResult(
                issue,
                path,
                gap(
                    issue.issueId,
                    TraceabilityGapCode.TEST_RESULT_EVIDENCE_MISSING,
                    TraceabilityEntityType.RELEASE,
                    releaseId,
                    TraceabilityExpectedEdgeType.TEST_RESULT_EVIDENCE,
                    path.last(),
                ),
                included = true,
            )
        }

        graph.deepestPrefix(issueCommitEdges, 3)?.let { path ->
            return issueResult(
                issue,
                path,
                gap(
                    issue.issueId,
                    TraceabilityGapCode.ARTIFACT_RELEASE_MISSING,
                    TraceabilityEntityType.ARTIFACT,
                    path.last().toId,
                    TraceabilityExpectedEdgeType.ARTIFACT_RELEASE,
                    path.last(),
                ),
            )
        }
        graph.deepestPrefix(issueCommitEdges, 2)?.let { path ->
            return issueResult(
                issue,
                path,
                gap(
                    issue.issueId,
                    TraceabilityGapCode.BUILD_ARTIFACT_MISSING,
                    TraceabilityEntityType.BUILD,
                    path.last().toId,
                    TraceabilityExpectedEdgeType.BUILD_ARTIFACT,
                    path.last(),
                ),
            )
        }
        val path = listOf(issueCommitEdges.first())
        return issueResult(
            issue,
            path,
            gap(
                issue.issueId,
                TraceabilityGapCode.COMMIT_BUILD_MISSING,
                TraceabilityEntityType.COMMIT,
                path.last().toId,
                TraceabilityExpectedEdgeType.COMMIT_BUILD,
                path.last(),
            ),
        )
    }

    private fun issueResult(
        issue: TraceabilityIssue,
        path: List<PinnedTraceabilityEdge>,
        gap: TraceabilityGap,
        included: Boolean = false,
    ) = TraceabilityIssueResult(
        issueId = issue.issueId,
        sourceIssueId = issue.sourceIssueId,
        fixed = path.firstOrNull()?.edgeType == PinnedTraceabilityEdgeType.ISSUE_COMMIT,
        included = included,
        verified = false,
        path = path,
        gaps = listOf(gap),
        minimumConfidence = path.minimumConfidence(),
    )

    private fun gap(
        issueId: String,
        code: TraceabilityGapCode,
        breakType: TraceabilityEntityType,
        breakId: String,
        expectedType: TraceabilityExpectedEdgeType,
        predecessor: PinnedTraceabilityEdge?,
    ) = TraceabilityGap(issueId, code, breakType, breakId, expectedType, predecessor)

    private fun List<PinnedTraceabilityEdge>.minimumConfidence(): Confidence =
        maxByOrNull { it.confidence.ordinal }?.confidence ?: Confidence.UNKNOWN

    private fun fail(code: String): Nothing = throw TraceabilityVerificationFailure(code)

    private class VerificationGraph(
        edges: List<PinnedTraceabilityEdge>,
        private val releaseId: String,
    ) {
        private val byTypeAndSource = edges
            .groupBy { it.edgeType to it.fromId }
            .mapValues { (_, values) -> values.sortedWith(TraceabilityOrdering.pathEdgeOrder) }
        private val releaseEdges = edges
            .filter { it.edgeType == PinnedTraceabilityEdgeType.ARTIFACT_RELEASE && it.toId == releaseId }
            .sortedWith(TraceabilityOrdering.pathEdgeOrder)
        private val releaseArtifactIds = releaseEdges.mapTo(mutableSetOf(), PinnedTraceabilityEdge::fromId)
        private val releaseBuildIds = edges.asSequence()
            .filter { it.edgeType == PinnedTraceabilityEdgeType.BUILD_ARTIFACT && it.toId in releaseArtifactIds }
            .mapTo(mutableSetOf(), PinnedTraceabilityEdge::fromId)
        private val releaseCommitIds = edges.asSequence()
            .filter { it.edgeType == PinnedTraceabilityEdgeType.COMMIT_BUILD && it.toId in releaseBuildIds }
            .mapTo(mutableSetOf(), PinnedTraceabilityEdge::fromId)

        fun edgesFrom(type: PinnedTraceabilityEdgeType, sourceId: String): List<PinnedTraceabilityEdge> =
            byTypeAndSource[type to sourceId].orEmpty()

        fun completePath(issueCommitEdges: List<PinnedTraceabilityEdge>): List<PinnedTraceabilityEdge>? {
            val issueCommit = issueCommitEdges.firstOrNull { it.toId in releaseCommitIds } ?: return null
            val commitBuild = edgesFrom(PinnedTraceabilityEdgeType.COMMIT_BUILD, issueCommit.toId)
                .first { it.toId in releaseBuildIds }
            val buildArtifact = edgesFrom(PinnedTraceabilityEdgeType.BUILD_ARTIFACT, commitBuild.toId)
                .first { it.toId in releaseArtifactIds }
            val artifactRelease = edgesFrom(PinnedTraceabilityEdgeType.ARTIFACT_RELEASE, buildArtifact.toId)
                .first { it.toId == releaseId }
            return listOf(issueCommit, commitBuild, buildArtifact, artifactRelease)
        }

        fun deepestPrefix(
            issueCommitEdges: List<PinnedTraceabilityEdge>,
            length: Int,
        ): List<PinnedTraceabilityEdge>? {
            issueCommitEdges.forEach { issueCommit ->
                if (length == 1) return listOf(issueCommit)
                edgesFrom(PinnedTraceabilityEdgeType.COMMIT_BUILD, issueCommit.toId).forEach { commitBuild ->
                    if (length == 2) return listOf(issueCommit, commitBuild)
                    edgesFrom(PinnedTraceabilityEdgeType.BUILD_ARTIFACT, commitBuild.toId).firstOrNull()?.let { buildArtifact ->
                        return listOf(issueCommit, commitBuild, buildArtifact)
                    }
                }
            }
            return null
        }
    }

    private companion object {
        const val MAX_ISSUES = 20
        const val MAX_EDGES = 2_000
        val PREFIXED_SHA256 = Regex("^sha256:[0-9a-f]{64}$")
    }
}
