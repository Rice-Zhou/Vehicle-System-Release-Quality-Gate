package com.ricezhou.vsrqg.traceability.adapter

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import jakarta.validation.constraints.Size
import java.time.Instant

@JsonDeserialize(using = TraceabilityVerifyRequestDeserializer::class)
data class TraceabilityVerifyRequest(
    @field:Size(min = 1, max = 128)
    val issueSourceId: String,
)

class TraceabilityVerifyRequestDeserializer : StdDeserializer<TraceabilityVerifyRequest>(TraceabilityVerifyRequest::class.java) {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): TraceabilityVerifyRequest {
        val node = parser.codec.readTree<JsonNode>(parser)
        if (!node.isObject || node.size() != 1 || !node.has("sourceId")) invalid(parser)
        val issueSourceId = node.path("sourceId").takeIf(JsonNode::isTextual)?.textValue() ?: invalid(parser)
        return TraceabilityVerifyRequest(issueSourceId)
    }

    private fun invalid(parser: JsonParser): Nothing =
        throw JsonMappingException.from(parser, "INVALID_TRACEABILITY_VERIFY_REQUEST")
}

data class TraceabilityVerificationAccepted(
    val verificationRunId: String,
    val releaseId: String,
    val issueSnapshotId: String,
    val inputDigest: String,
    val statusUrl: String,
) {
    val status: TraceabilityVerificationStatus = TraceabilityVerificationStatus.QUEUED
}

data class TraceabilityVerificationRunResponse(
    val verificationRunId: String,
    val releaseId: String,
    val status: TraceabilityVerificationStatus,
    val policyVersion: String,
    val validatorVersion: String,
    val inputDigest: String,
    val resultSnapshotId: String?,
    val diagnosticCode: String?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
)

class TraceabilitySnapshotResponse(
    val snapshot: TraceabilitySnapshotHeader,
    issues: List<TraceabilityIssueResult>,
) {
    val issues: List<TraceabilityIssueResult> = issues
        .map(TraceabilityIssueResult::normalized)
        .sortedWith(ISSUE_RESULT_ORDER)

    companion object {
        fun from(snapshot: TraceabilitySnapshotHeader, issues: List<TraceabilityIssueResult>) =
            TraceabilitySnapshotResponse(snapshot, issues)
    }
}

data class TraceabilitySnapshotHeader(
    val snapshotId: String,
    val releaseId: String,
    val version: Int,
    val issueSnapshotId: String,
    val manifestRevisionId: String,
    val manifestDigest: String,
    val policyVersion: String,
    val validatorVersion: String,
    val inputDigest: String,
    val contentDigest: String,
    val createdAt: Instant,
)

data class TraceabilityIssueResult(
    val issueId: String,
    val sourceIssueId: String,
    val fixed: Boolean,
    val included: Boolean,
    val verified: Boolean,
    val path: List<TraceabilityPathEdge>,
    val gaps: List<TraceabilityGap>,
    val confidence: TraceabilityConfidence,
) {
    init {
        require(!verified) { "TRACEABILITY_VERIFIED_NOT_AVAILABLE" }
    }
}

data class TraceabilityPathEdge(
    val edgeId: String,
    val edgeType: TraceabilityPathEdgeType,
    val revisionId: String,
    val revision: Int,
    val fromId: String,
    val toId: String,
    val factDigest: String,
)

data class TraceabilityGap(
    val diagnosticCode: TraceabilityGapDiagnosticCode,
    val interruptedEntityType: TraceabilityEntityType,
    val interruptedEntityId: String,
    val expectedEdgeType: TraceabilityExpectedEdgeType,
    val predecessorEdgeId: String?,
    val predecessorRevision: Int?,
    val gapDigest: String,
)

enum class TraceabilityVerificationStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

enum class TraceabilityConfidence {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN,
}

enum class TraceabilityPathEdgeType {
    ISSUE_COMMIT,
    COMMIT_BUILD,
    BUILD_ARTIFACT,
    ARTIFACT_RELEASE,
}

enum class TraceabilityExpectedEdgeType {
    ISSUE_COMMIT,
    COMMIT_BUILD,
    BUILD_ARTIFACT,
    ARTIFACT_RELEASE,
    TEST_RESULT_EVIDENCE,
}

enum class TraceabilityGapDiagnosticCode {
    ISSUE_COMMIT_MISSING,
    COMMIT_BUILD_MISSING,
    BUILD_ARTIFACT_MISSING,
    ARTIFACT_RELEASE_MISSING,
    TEST_RESULT_EVIDENCE_MISSING,
}

enum class TraceabilityEntityType {
    ISSUE,
    COMMIT,
    BUILD,
    ARTIFACT,
    RELEASE,
}

private fun TraceabilityIssueResult.normalized(): TraceabilityIssueResult = copy(
    path = path.sortedWith(PATH_EDGE_ORDER),
    gaps = gaps.sortedWith(GAP_ORDER),
)

private val ISSUE_RESULT_ORDER: Comparator<TraceabilityIssueResult> = Comparator { left, right ->
    compareCodePointKeys(
        left.sourceIssueId to right.sourceIssueId,
        left.issueId to right.issueId,
    )
}

private val PATH_EDGE_ORDER: Comparator<TraceabilityPathEdge> = Comparator { left, right ->
    val stringComparison = compareCodePointKeys(
        left.fromId to right.fromId,
        left.toId to right.toId,
        left.edgeId to right.edgeId,
    )
    when {
        left.edgeType != right.edgeType -> left.edgeType.ordinal.compareTo(right.edgeType.ordinal)
        stringComparison != 0 -> stringComparison
        left.revision != right.revision -> left.revision.compareTo(right.revision)
        else -> compareCodePoints(left.factDigest, right.factDigest)
    }
}

private val GAP_ORDER: Comparator<TraceabilityGap> = Comparator { left, right ->
    val stringComparison = compareCodePointKeys(
        left.interruptedEntityType.name to right.interruptedEntityType.name,
        left.interruptedEntityId to right.interruptedEntityId,
        left.expectedEdgeType.name to right.expectedEdgeType.name,
    )
    val predecessorComparison = compareNullableString(left.predecessorEdgeId, right.predecessorEdgeId)
    when {
        left.diagnosticCode != right.diagnosticCode -> left.diagnosticCode.ordinal.compareTo(right.diagnosticCode.ordinal)
        stringComparison != 0 -> stringComparison
        predecessorComparison != 0 -> predecessorComparison
        left.predecessorRevision != right.predecessorRevision -> compareNullableInt(
            left.predecessorRevision,
            right.predecessorRevision,
        )
        else -> compareCodePoints(left.gapDigest, right.gapDigest)
    }
}

private fun compareCodePointKeys(vararg keys: Pair<String, String>): Int {
    keys.forEach { (left, right) ->
        val comparison = compareCodePoints(left, right)
        if (comparison != 0) return comparison
    }
    return 0
}

private fun compareCodePoints(left: String, right: String): Int {
    val leftCodePoints = left.codePoints().iterator()
    val rightCodePoints = right.codePoints().iterator()
    while (leftCodePoints.hasNext() && rightCodePoints.hasNext()) {
        val comparison = leftCodePoints.nextInt().compareTo(rightCodePoints.nextInt())
        if (comparison != 0) return comparison
    }
    return when {
        leftCodePoints.hasNext() -> 1
        rightCodePoints.hasNext() -> -1
        else -> 0
    }
}

private fun compareNullableInt(left: Int?, right: Int?): Int = when {
    left == null && right == null -> 0
    left == null -> -1
    right == null -> 1
    else -> left.compareTo(right)
}

private fun compareNullableString(left: String?, right: String?): Int = when {
    left == null && right == null -> 0
    left == null -> -1
    right == null -> 1
    else -> compareCodePoints(left, right)
}
