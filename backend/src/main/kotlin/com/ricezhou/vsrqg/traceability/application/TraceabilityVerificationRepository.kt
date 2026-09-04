package com.ricezhou.vsrqg.traceability.application

import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdge
import com.ricezhou.vsrqg.traceability.domain.Confidence
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdgeType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityEntityType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityExpectedEdgeType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityGapCode
import com.ricezhou.vsrqg.traceability.domain.TraceabilityIssue
import com.ricezhou.vsrqg.traceability.domain.VerificationComputation
import com.ricezhou.vsrqg.traceability.domain.VerificationInput
import java.time.Instant

data class TraceabilityVerificationAuthority(
    val projectId: String,
    val releaseId: String,
    val manifestRevisionId: String?,
    val manifestDigest: String?,
    val manifestState: String?,
    val issueSnapshotId: String?,
    val issueSnapshotDigest: String?,
    val issueSnapshotCanonicalizationVersion: String?,
    val declaredIssueCount: Int?,
    val issues: List<TraceabilityIssue>,
    val edges: List<PinnedTraceabilityEdge>,
)

data class TraceabilityVerificationRunRecord(
    val id: String,
    val projectId: String,
    val releaseId: String,
    val issueSnapshotId: String,
    val manifestRevisionId: String,
    val policyVersion: String,
    val validatorVersion: String,
    val inputDigest: String,
    val inputEdgeCount: Int,
    val requestedBy: String,
    val requestId: String,
    val createdAt: Instant,
)

data class TraceabilityVerificationJobClaim(
    val jobId: String,
    val verificationRunId: String,
    val projectId: String,
    val attemptCount: Int,
)

data class PinnedTraceabilityVerificationExecution(
    val verificationRunId: String,
    val projectId: String,
    val releaseId: String,
    val requestedBy: String,
    val requestId: String,
    val inputDigest: String,
    val input: VerificationInput,
)

data class TraceabilitySnapshotMaterialization(
    val snapshotId: String,
    val runGapIds: List<String>,
    val computation: VerificationComputation,
    val completedAt: Instant,
)

data class TraceabilityVerificationRunView(
    val verificationRunId: String,
    val projectId: String,
    val releaseId: String,
    val status: String,
    val policyVersion: String,
    val validatorVersion: String,
    val inputDigest: String,
    val resultSnapshotId: String?,
    val diagnosticCode: String?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
)

data class TraceabilitySnapshotHeaderView(
    val snapshotId: String,
    val projectId: String,
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

data class TraceabilitySnapshotIssueView(
    val ordinal: Int,
    val issueId: String,
    val sourceIssueId: String,
    val fixed: Boolean,
    val included: Boolean,
    val verified: Boolean,
    val confidence: Confidence,
)

data class TraceabilitySnapshotPathEdgeView(
    val issueOrdinal: Int,
    val pathOrdinal: Int,
    val edgeId: String,
    val edgeType: PinnedTraceabilityEdgeType,
    val revisionId: String,
    val revision: Int,
    val fromId: String,
    val toId: String,
    val factDigest: String,
)

data class TraceabilitySnapshotGapView(
    val issueOrdinal: Int,
    val ordinal: Int,
    val diagnosticCode: TraceabilityGapCode,
    val breakEntityType: TraceabilityEntityType,
    val breakEntityId: String,
    val expectedEdgeType: TraceabilityExpectedEdgeType,
    val predecessorEdgeId: String?,
    val predecessorRevision: Int?,
    val gapDigest: String,
)

class TraceabilitySnapshotVersionConflict : RuntimeException("TRACEABILITY_SNAPSHOT_VERSION_CONFLICT")

interface TraceabilityVerificationRepository {
    fun findVerificationRun(verificationRunId: String): TraceabilityVerificationRunView?

    fun findReleaseProjectId(releaseId: String): String?

    fun findSnapshotHeader(releaseId: String, snapshotId: String?): TraceabilitySnapshotHeaderView?

    fun findSnapshotIssues(snapshotId: String): List<TraceabilitySnapshotIssueView>

    fun findSnapshotPathEdges(snapshotId: String): List<TraceabilitySnapshotPathEdgeView>

    fun findSnapshotGaps(snapshotId: String): List<TraceabilitySnapshotGapView>

    fun findProjectId(releaseId: String, issueSourceId: String): String?

    fun lockAndLoadAuthority(
        releaseId: String,
        issueSourceId: String,
        issueFetchLimit: Int,
        edgeFetchLimit: Int,
    ): TraceabilityVerificationAuthority?

    fun insertRun(run: TraceabilityVerificationRunRecord)

    fun insertInputLedger(
        runId: String,
        projectId: String,
        edges: List<PinnedTraceabilityEdge>,
        createdAt: Instant,
    )

    fun insertJob(
        jobId: String,
        projectId: String,
        runId: String,
        payload: JsonNode,
        createdAt: Instant,
    )

    fun claimNext(now: Instant): TraceabilityVerificationJobClaim?

    fun loadPinnedExecution(verificationRunId: String): PinnedTraceabilityVerificationExecution

    fun materializeResult(
        claim: TraceabilityVerificationJobClaim,
        execution: PinnedTraceabilityVerificationExecution,
        materialization: TraceabilitySnapshotMaterialization,
    ): String

    fun failInvalidInput(
        claim: TraceabilityVerificationJobClaim,
        diagnosticCode: String,
        completedAt: Instant,
    )

    fun recordInfrastructureFailure(
        claim: TraceabilityVerificationJobClaim,
        failedAt: Instant,
    )
}
