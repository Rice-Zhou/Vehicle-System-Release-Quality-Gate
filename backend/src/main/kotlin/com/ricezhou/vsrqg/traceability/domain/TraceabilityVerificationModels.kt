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

enum class TraceabilityGapCode(val stableReason: String) {
    ISSUE_COMMIT_MISSING("POLICY_VALID_ISSUE_COMMIT_NOT_FOUND"),
    COMMIT_BUILD_MISSING("PINNED_COMMIT_BUILD_NOT_FOUND"),
    BUILD_ARTIFACT_MISSING("PINNED_BUILD_ARTIFACT_NOT_FOUND"),
    ARTIFACT_RELEASE_MISSING("LOCKED_MANIFEST_ARTIFACT_MEMBERSHIP_NOT_FOUND"),
    TEST_RESULT_EVIDENCE_MISSING("M2_5_TEST_RESULT_EVIDENCE_NOT_AVAILABLE"),
}

class TraceabilityVerificationFailure(
    val diagnosticCode: String,
    val reasonCode: String = diagnosticCode,
) : IllegalArgumentException("$diagnosticCode:$reasonCode")

data class TraceabilityIssue(
    val issueId: String,
    val sourceIssueId: String,
)

class PinnedIssueSnapshot(
    val projectId: String,
    val releaseId: String,
    val snapshotId: String,
    val digest: String,
    issues: List<TraceabilityIssue>,
) {
    val issues: List<TraceabilityIssue> = immutableList(issues)
}

data class LockedManifest(
    val projectId: String,
    val releaseId: String,
    val revisionId: String,
    val digest: String,
)

data class PinnedTraceabilityEdge(
    val projectId: String,
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
    val projectId: String,
    val releaseId: String,
    val issueSnapshot: PinnedIssueSnapshot,
    val manifest: LockedManifest,
    edgeRevisions: List<PinnedTraceabilityEdge>,
) {
    val edgeRevisions: List<PinnedTraceabilityEdge> = immutableList(edgeRevisions)

    init {
        validateCapacity()
        validateScope()
        validateIssues()
        validateEdgeAuthority()
        validateLedgerIdentity()
    }

    private fun validateCapacity() {
        if (issueSnapshot.issues.size > MAX_ISSUES) invalid("TRACEABILITY_ISSUE_LIMIT_EXCEEDED", "ISSUE_LIMIT_EXCEEDED")
        if (edgeRevisions.size > MAX_EDGES) invalid("TRACEABILITY_INPUT_LIMIT_EXCEEDED", "EDGE_LIMIT_EXCEEDED")
    }

    private fun validateScope() {
        if (
            issueSnapshot.projectId != projectId || issueSnapshot.releaseId != releaseId ||
            manifest.projectId != projectId || manifest.releaseId != releaseId ||
            edgeRevisions.any { it.projectId != projectId } ||
            edgeRevisions.any {
                it.edgeType == PinnedTraceabilityEdgeType.ARTIFACT_RELEASE && it.toId != releaseId
            }
        ) {
            invalid(reasonCode = "PINNED_INPUT_SCOPE_MISMATCH")
        }
    }

    private fun validateIssues() {
        if (issueSnapshot.issues.map(TraceabilityIssue::issueId).toSet().size != issueSnapshot.issues.size) {
            invalid(reasonCode = "DUPLICATE_ISSUE_ID")
        }
        if (issueSnapshot.issues.map(TraceabilityIssue::sourceIssueId).toSet().size != issueSnapshot.issues.size) {
            invalid(reasonCode = "DUPLICATE_SOURCE_ISSUE_ID")
        }
    }

    private fun validateEdgeAuthority() {
        edgeRevisions.forEach { edge ->
            val expectedAuthority = if (edge.edgeType == PinnedTraceabilityEdgeType.ARTIFACT_RELEASE) {
                PinnedTraceabilityEdgeAuthority.LOCKED_MANIFEST
            } else {
                PinnedTraceabilityEdgeAuthority.EDGE_REVISION
            }
            when {
                edge.verificationStatus != VerificationStatus.VALID -> invalid(reasonCode = "PINNED_EDGE_NOT_VALID")
                edge.authority != expectedAuthority -> invalid(reasonCode = "PINNED_EDGE_AUTHORITY_INVALID")
                edge.sourceEdgeRevision <= 0 -> invalid(reasonCode = "PINNED_EDGE_REVISION_INVALID")
                !PREFIXED_SHA256.matches(edge.factDigest) -> invalid(reasonCode = "PINNED_EDGE_DIGEST_INVALID")
            }
        }
    }

    private fun validateLedgerIdentity() {
        val duplicateIdentity = edgeRevisions.groupingBy {
            Triple(it.edgeType, it.sourceEdgeId, it.sourceEdgeRevision)
        }.eachCount().values.any { it > 1 }
        if (duplicateIdentity) invalid(reasonCode = "DUPLICATE_PINNED_EDGE_IDENTITY")

        val multipleRevisions = edgeRevisions.groupBy { it.edgeType to it.sourceEdgeId }
            .values
            .any { revisions -> revisions.map(PinnedTraceabilityEdge::sourceEdgeRevision).toSet().size > 1 }
        if (multipleRevisions) invalid(reasonCode = "MULTIPLE_PINNED_EDGE_REVISIONS")
    }

    private fun invalid(
        diagnosticCode: String = "TRACEABILITY_INPUT_NOT_VALID",
        reasonCode: String = diagnosticCode,
    ): Nothing = throw TraceabilityVerificationFailure(diagnosticCode, reasonCode)

    private companion object {
        const val MAX_ISSUES = 20
        const val MAX_EDGES = 2_000
        val PREFIXED_SHA256 = Regex("^sha256:[0-9a-f]{64}$")
    }
}

class TraceabilityGap private constructor(
    val issueId: String,
    val diagnosticCode: TraceabilityGapCode,
    val breakEntityType: TraceabilityEntityType,
    val breakEntityId: String,
    val expectedEdgeType: TraceabilityExpectedEdgeType,
    val predecessorEdge: PinnedTraceabilityEdge?,
    val reason: String,
    val gapDigest: String,
) {
    companion object {
        internal fun materialize(
            issueId: String,
            diagnosticCode: TraceabilityGapCode,
            breakEntityType: TraceabilityEntityType,
            breakEntityId: String,
            expectedEdgeType: TraceabilityExpectedEdgeType,
            predecessorEdge: PinnedTraceabilityEdge?,
            reason: String,
            gapDigest: String,
        ): TraceabilityGap {
            val expected = GAP_SHAPES.getValue(diagnosticCode)
            require(breakEntityType == expected.breakEntityType) { "TRACEABILITY_GAP_BREAK_TYPE_INVALID" }
            require(expectedEdgeType == expected.expectedEdgeType) { "TRACEABILITY_GAP_EXPECTED_EDGE_INVALID" }
            require(predecessorEdge?.edgeType == expected.predecessorEdgeType) {
                "TRACEABILITY_GAP_PREDECESSOR_INVALID"
            }
            require(reason == diagnosticCode.stableReason) { "TRACEABILITY_GAP_REASON_INVALID" }
            require(PREFIXED_SHA256.matches(gapDigest)) { "TRACEABILITY_GAP_DIGEST_INVALID" }
            return TraceabilityGap(
                issueId,
                diagnosticCode,
                breakEntityType,
                breakEntityId,
                expectedEdgeType,
                predecessorEdge,
                reason,
                gapDigest,
            )
        }
    }
}

class TraceabilityIssueResult private constructor(
    val issueId: String,
    val sourceIssueId: String,
    val fixed: Boolean,
    val included: Boolean,
    val verified: Boolean,
    path: List<PinnedTraceabilityEdge>,
    gaps: List<TraceabilityGap>,
    val confidence: Confidence,
    val resultDigest: String,
) {
    val path: List<PinnedTraceabilityEdge> = immutableList(path)
    val gaps: List<TraceabilityGap> = immutableList(gaps)

    companion object {
        internal fun materialize(
            issueId: String,
            sourceIssueId: String,
            fixed: Boolean,
            included: Boolean,
            verified: Boolean,
            path: List<PinnedTraceabilityEdge>,
            gaps: List<TraceabilityGap>,
            confidence: Confidence,
            resultDigest: String,
        ): TraceabilityIssueResult {
            val expectedTypes = FULL_PATH_TYPES.take(path.size)
            require(path.size <= FULL_PATH_TYPES.size && path.map(PinnedTraceabilityEdge::edgeType) == expectedTypes) {
                "TRACEABILITY_RESULT_PATH_INVALID"
            }
            require(path.firstOrNull()?.fromId == issueId || path.isEmpty()) { "TRACEABILITY_RESULT_ISSUE_PATH_INVALID" }
            require(path.zipWithNext().all { (left, right) -> left.toId == right.fromId }) {
                "TRACEABILITY_RESULT_PATH_DISCONNECTED"
            }
            require(gaps.size == 1 && gaps.single().issueId == issueId) { "TRACEABILITY_RESULT_GAP_INVALID" }
            require(gaps.single().diagnosticCode == GAP_BY_PATH_SIZE.getValue(path.size)) {
                "TRACEABILITY_RESULT_GAP_PATH_MISMATCH"
            }
            require(gaps.single().predecessorEdge == path.lastOrNull()) { "TRACEABILITY_RESULT_GAP_PREDECESSOR_MISMATCH" }
            require(fixed == path.isNotEmpty()) { "TRACEABILITY_RESULT_FIXED_INVALID" }
            require(included == (path.size == FULL_PATH_TYPES.size)) { "TRACEABILITY_RESULT_INCLUDED_INVALID" }
            require(!verified) { "VERIFIED_TRUE_NOT_SUPPORTED" }
            require(confidence == path.minimumConfidence()) { "TRACEABILITY_RESULT_CONFIDENCE_INVALID" }
            require(PREFIXED_SHA256.matches(resultDigest)) { "TRACEABILITY_RESULT_DIGEST_INVALID" }
            return TraceabilityIssueResult(
                issueId,
                sourceIssueId,
                fixed,
                included,
                verified,
                path,
                gaps,
                confidence,
                resultDigest,
            )
        }
    }
}

data class TraceabilityPathEdge(
    val issueId: String,
    val pathOrdinal: Int,
    val edge: PinnedTraceabilityEdge,
)

class VerificationComputation internal constructor(
    issueResults: List<TraceabilityIssueResult>,
    pathEdges: List<TraceabilityPathEdge>,
    gaps: List<TraceabilityGap>,
    val contentDigest: String,
) {
    val issueResults: List<TraceabilityIssueResult> = immutableList(issueResults)
    val pathEdges: List<TraceabilityPathEdge> = immutableList(pathEdges)
    val gaps: List<TraceabilityGap> = immutableList(gaps)
}

class CanonicalTraceability internal constructor(
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

private data class GapShape(
    val expectedEdgeType: TraceabilityExpectedEdgeType,
    val breakEntityType: TraceabilityEntityType,
    val predecessorEdgeType: PinnedTraceabilityEdgeType?,
)

private val GAP_SHAPES = mapOf(
    TraceabilityGapCode.ISSUE_COMMIT_MISSING to GapShape(
        TraceabilityExpectedEdgeType.ISSUE_COMMIT,
        TraceabilityEntityType.ISSUE,
        null,
    ),
    TraceabilityGapCode.COMMIT_BUILD_MISSING to GapShape(
        TraceabilityExpectedEdgeType.COMMIT_BUILD,
        TraceabilityEntityType.COMMIT,
        PinnedTraceabilityEdgeType.ISSUE_COMMIT,
    ),
    TraceabilityGapCode.BUILD_ARTIFACT_MISSING to GapShape(
        TraceabilityExpectedEdgeType.BUILD_ARTIFACT,
        TraceabilityEntityType.BUILD,
        PinnedTraceabilityEdgeType.COMMIT_BUILD,
    ),
    TraceabilityGapCode.ARTIFACT_RELEASE_MISSING to GapShape(
        TraceabilityExpectedEdgeType.ARTIFACT_RELEASE,
        TraceabilityEntityType.ARTIFACT,
        PinnedTraceabilityEdgeType.BUILD_ARTIFACT,
    ),
    TraceabilityGapCode.TEST_RESULT_EVIDENCE_MISSING to GapShape(
        TraceabilityExpectedEdgeType.TEST_RESULT_EVIDENCE,
        TraceabilityEntityType.RELEASE,
        PinnedTraceabilityEdgeType.ARTIFACT_RELEASE,
    ),
)

private val FULL_PATH_TYPES = listOf(
    PinnedTraceabilityEdgeType.ISSUE_COMMIT,
    PinnedTraceabilityEdgeType.COMMIT_BUILD,
    PinnedTraceabilityEdgeType.BUILD_ARTIFACT,
    PinnedTraceabilityEdgeType.ARTIFACT_RELEASE,
)

private val GAP_BY_PATH_SIZE = mapOf(
    0 to TraceabilityGapCode.ISSUE_COMMIT_MISSING,
    1 to TraceabilityGapCode.COMMIT_BUILD_MISSING,
    2 to TraceabilityGapCode.BUILD_ARTIFACT_MISSING,
    3 to TraceabilityGapCode.ARTIFACT_RELEASE_MISSING,
    4 to TraceabilityGapCode.TEST_RESULT_EVIDENCE_MISSING,
)

internal fun List<PinnedTraceabilityEdge>.minimumConfidence(): Confidence =
    maxByOrNull { it.confidence.ordinal }?.confidence ?: Confidence.UNKNOWN

private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

private val PREFIXED_SHA256 = Regex("^sha256:[0-9a-f]{64}$")
