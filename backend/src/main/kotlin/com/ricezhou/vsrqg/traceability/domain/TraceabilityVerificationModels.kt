package com.ricezhou.vsrqg.traceability.domain

import java.util.Collections

enum class PinnedTraceabilityEdgeType {
    ISSUE_COMMIT,
    COMMIT_BUILD,
    BUILD_ARTIFACT,
    ARTIFACT_RELEASE,
}

enum class PinnedTraceabilityEdgeAuthority { EDGE_REVISION, LOCKED_MANIFEST }

enum class TraceabilityEntityType { ISSUE, COMMIT, BUILD, ARTIFACT, RELEASE }

enum class TraceabilityExpectedEdgeType {
    ISSUE_COMMIT,
    COMMIT_BUILD,
    BUILD_ARTIFACT,
    ARTIFACT_RELEASE,
    TEST_RESULT_EVIDENCE,
}

enum class TraceabilityGapCode {
    ISSUE_COMMIT_MISSING,
    COMMIT_BUILD_MISSING,
    BUILD_ARTIFACT_MISSING,
    ARTIFACT_RELEASE_MISSING,
    TEST_RESULT_EVIDENCE_MISSING,
}

data class TraceabilityIssue(
    val issueId: String,
    val sourceIssueId: String,
)

class PinnedIssueSnapshot(
    val snapshotId: String,
    val digest: String,
    issues: List<TraceabilityIssue>,
) {
    val issues: List<TraceabilityIssue> = immutableList(issues)
}

data class LockedManifest(
    val releaseId: String,
    val revisionId: String,
    val digest: String,
)

data class PinnedTraceabilityEdge(
    val edgeType: PinnedTraceabilityEdgeType,
    val fromId: String,
    val toId: String,
    val sourceEdgeId: String,
    val sourceEdgeRevision: Int,
    val verificationStatus: VerificationStatus,
    val confidence: Confidence,
    val factDigest: String,
    val authority: PinnedTraceabilityEdgeAuthority,
)

class VerificationInput(
    val schemaVersion: String,
    val policyVersion: String,
    val validatorVersion: String,
    val releaseId: String,
    val issueSnapshot: PinnedIssueSnapshot,
    val manifest: LockedManifest,
    edgeRevisions: List<PinnedTraceabilityEdge>,
) {
    val edgeRevisions: List<PinnedTraceabilityEdge> = immutableList(edgeRevisions)
}

data class TraceabilityGap(
    val issueId: String,
    val diagnosticCode: TraceabilityGapCode,
    val breakEntityType: TraceabilityEntityType,
    val breakEntityId: String,
    val expectedEdgeType: TraceabilityExpectedEdgeType,
    val predecessorEdge: PinnedTraceabilityEdge?,
)

class TraceabilityIssueResult(
    val issueId: String,
    val sourceIssueId: String,
    val fixed: Boolean,
    val included: Boolean,
    val verified: Boolean = false,
    path: List<PinnedTraceabilityEdge>,
    gaps: List<TraceabilityGap>,
    val minimumConfidence: Confidence = path.maxByOrNull { it.confidence.ordinal }?.confidence ?: Confidence.UNKNOWN,
) {
    val path: List<PinnedTraceabilityEdge> = immutableList(path)
    val gaps: List<TraceabilityGap> = immutableList(gaps)

    init {
        require(!included || fixed) { "INCLUDED_REQUIRES_FIXED" }
        require(!verified) { "VERIFIED_TRUE_NOT_SUPPORTED" }
    }
}

data class TraceabilityPathEdge(
    val issueId: String,
    val pathOrdinal: Int,
    val edge: PinnedTraceabilityEdge,
)

class VerificationComputation(
    issueResults: List<TraceabilityIssueResult>,
    pathEdges: List<TraceabilityPathEdge>,
    gaps: List<TraceabilityGap>,
    val contentDigest: String,
) {
    val issueResults: List<TraceabilityIssueResult> = immutableList(issueResults)
    val pathEdges: List<TraceabilityPathEdge> = immutableList(pathEdges)
    val gaps: List<TraceabilityGap> = immutableList(gaps)
}

class CanonicalTraceability(
    bytes: ByteArray,
    val digest: String,
) {
    private val byteSnapshot = bytes.copyOf()

    val bytes: ByteArray
        get() = byteSnapshot.copyOf()
}

object TraceabilityOrdering {
    val unicodeCodePointOrder: Comparator<String> = Comparator(::compareCodePoints)

    val inputEdgeOrder: Comparator<PinnedTraceabilityEdge> =
        compareBy<PinnedTraceabilityEdge> { it.edgeType.ordinal }
            .thenBy(unicodeCodePointOrder) { it.sourceEdgeId }
            .thenBy { it.sourceEdgeRevision }

    val pathEdgeOrder: Comparator<PinnedTraceabilityEdge> =
        compareBy<PinnedTraceabilityEdge> { it.edgeType.ordinal }
            .thenBy(unicodeCodePointOrder) { it.fromId }
            .thenBy(unicodeCodePointOrder) { it.toId }
            .thenBy(unicodeCodePointOrder) { it.sourceEdgeId }
            .thenBy { it.sourceEdgeRevision }

    val issueOrder: Comparator<TraceabilityIssue> = Comparator { left, right ->
        unicodeCodePointOrder.compare(left.sourceIssueId, right.sourceIssueId)
            .takeIf { it != 0 }
            ?: unicodeCodePointOrder.compare(left.issueId, right.issueId)
    }

    val issueResultOrder: Comparator<TraceabilityIssueResult> = Comparator { left, right ->
        unicodeCodePointOrder.compare(left.sourceIssueId, right.sourceIssueId)
            .takeIf { it != 0 }
            ?: unicodeCodePointOrder.compare(left.issueId, right.issueId)
    }

    private fun compareCodePoints(left: String, right: String): Int {
        val leftCodePoints = left.codePoints().iterator()
        val rightCodePoints = right.codePoints().iterator()
        while (leftCodePoints.hasNext() && rightCodePoints.hasNext()) {
            val compared = leftCodePoints.nextInt().compareTo(rightCodePoints.nextInt())
            if (compared != 0) return compared
        }
        return leftCodePoints.hasNext().compareTo(rightCodePoints.hasNext())
    }
}

private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
