package com.ricezhou.vsrqg.traceability.application

import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdge
import com.ricezhou.vsrqg.traceability.domain.TraceabilityIssue
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

interface TraceabilityVerificationRepository {
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
}
