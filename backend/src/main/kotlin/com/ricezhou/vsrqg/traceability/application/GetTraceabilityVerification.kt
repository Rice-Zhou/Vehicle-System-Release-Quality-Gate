package com.ricezhou.vsrqg.traceability.application

import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.ricezhou.vsrqg.traceability.domain.Confidence
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdgeType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityEntityType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityExpectedEdgeType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityGapCode
import java.time.Instant
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

enum class TraceabilityVerificationRunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

data class TraceabilityVerificationRunResult(
    val verificationRunId: String,
    val releaseId: String,
    val status: TraceabilityVerificationRunStatus,
    val policyVersion: String,
    val validatorVersion: String,
    val inputDigest: String,
    val resultSnapshotId: String?,
    val diagnosticCode: String?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
)

data class TraceabilitySnapshotResult(
    val header: TraceabilitySnapshotHeaderResult,
    val issues: List<TraceabilitySnapshotIssueResult>,
)

data class TraceabilitySnapshotHeaderResult(
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

data class TraceabilitySnapshotIssueResult(
    val issueId: String,
    val sourceIssueId: String,
    val fixed: Boolean,
    val included: Boolean,
    val verified: Boolean,
    val path: List<TraceabilitySnapshotPathEdgeResult>,
    val gaps: List<TraceabilitySnapshotGapResult>,
    val confidence: Confidence,
)

data class TraceabilitySnapshotPathEdgeResult(
    val edgeId: String,
    val edgeType: PinnedTraceabilityEdgeType,
    val revisionId: String,
    val revision: Int,
    val fromId: String,
    val toId: String,
    val factDigest: String,
)

data class TraceabilitySnapshotGapResult(
    val diagnosticCode: TraceabilityGapCode,
    val breakEntityType: TraceabilityEntityType,
    val breakEntityId: String,
    val expectedEdgeType: TraceabilityExpectedEdgeType,
    val predecessorEdgeId: String?,
    val predecessorRevision: Int?,
    val gapDigest: String,
)

@Service
class GetTraceabilityVerification(
    private val repository: TraceabilityVerificationRepository,
    private val authorizer: ProjectAuthorizer,
) {
    @Transactional(readOnly = true)
    fun getRun(principal: Principal, verificationRunId: String): TraceabilityVerificationRunResult {
        val run = repository.findVerificationRun(verificationRunId) ?: hiddenResource()
        requireVisible(principal, run.projectId)
        return TraceabilityVerificationRunResult(
            verificationRunId = run.verificationRunId,
            releaseId = run.releaseId,
            status = TraceabilityVerificationRunStatus.valueOf(run.status),
            policyVersion = run.policyVersion,
            validatorVersion = run.validatorVersion,
            inputDigest = run.inputDigest,
            resultSnapshotId = run.resultSnapshotId,
            diagnosticCode = allowlistedDiagnostic(run.diagnosticCode),
            createdAt = run.createdAt,
            startedAt = run.startedAt,
            completedAt = run.completedAt,
        )
    }

    @Transactional(readOnly = true)
    fun getSnapshot(principal: Principal, releaseId: String, snapshotId: String?): TraceabilitySnapshotResult {
        val projectId = repository.findReleaseProjectId(releaseId) ?: hiddenResource()
        requireVisible(principal, projectId)
        val header = repository.findSnapshotHeader(releaseId, snapshotId) ?: hiddenResource()
        if (header.projectId != projectId) hiddenResource()

        val persistedIssues = repository.findSnapshotIssues(header.snapshotId)
        val pathByIssue = repository.findSnapshotPathEdges(header.snapshotId)
            .groupBy(TraceabilitySnapshotPathEdgeView::issueOrdinal)
        val gapsByIssue = repository.findSnapshotGaps(header.snapshotId)
            .groupBy(TraceabilitySnapshotGapView::issueOrdinal)
        val issues = persistedIssues.map { issue ->
            TraceabilitySnapshotIssueResult(
                issueId = issue.issueId,
                sourceIssueId = issue.sourceIssueId,
                fixed = issue.fixed,
                included = issue.included,
                verified = issue.verified,
                path = pathByIssue[issue.ordinal].orEmpty().map { edge ->
                    TraceabilitySnapshotPathEdgeResult(
                        edge.edgeId,
                        edge.edgeType,
                        edge.revisionId,
                        edge.revision,
                        edge.fromId,
                        edge.toId,
                        edge.factDigest,
                    )
                },
                gaps = gapsByIssue[issue.ordinal].orEmpty().map { gap ->
                    TraceabilitySnapshotGapResult(
                        gap.diagnosticCode,
                        gap.breakEntityType,
                        gap.breakEntityId,
                        gap.expectedEdgeType,
                        gap.predecessorEdgeId,
                        gap.predecessorRevision,
                        gap.gapDigest,
                    )
                },
                confidence = issue.confidence,
            )
        }
        return TraceabilitySnapshotResult(
            header = TraceabilitySnapshotHeaderResult(
                header.snapshotId,
                header.releaseId,
                header.version,
                header.issueSnapshotId,
                header.manifestRevisionId,
                header.manifestDigest,
                header.policyVersion,
                header.validatorVersion,
                header.inputDigest,
                header.contentDigest,
                header.createdAt,
            ),
            issues = issues,
        )
    }

    private fun requireVisible(principal: Principal, projectId: String) {
        try {
            authorizer.require(principal, projectId, Permission.TRACEABILITY_READ)
        } catch (_: AccessDeniedException) {
            hiddenResource()
        }
    }

    private fun allowlistedDiagnostic(value: String?): String? = when (value) {
        null,
        "TRACEABILITY_INPUT_NOT_VALID",
        "TRACEABILITY_VERIFICATION_RETRY_EXHAUSTED",
        -> value
        else -> "TRACEABILITY_VERIFICATION_FAILED"
    }

    private fun hiddenResource(): Nothing = throw ResourceNotFound(
        code = "RESOURCE_NOT_FOUND",
        resourceTitle = "Resource not found",
        detail = "The requested resource was not found",
    )
}
