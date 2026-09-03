package com.ricezhou.vsrqg.traceability.application

import com.ricezhou.vsrqg.traceability.domain.Confidence
import com.ricezhou.vsrqg.traceability.domain.ProvenanceProviderId
import com.ricezhou.vsrqg.traceability.domain.TraceabilityEdgeType
import com.ricezhou.vsrqg.traceability.domain.VerificationStatus
import java.time.Instant
import java.util.Collections

data class BuildAttemptKey(
    val projectId: String,
    val provider: ProvenanceProviderId,
    val pipeline: String,
    val buildId: String,
    val buildAttempt: Int,
)

data class BuildProvenanceContext(
    val projectId: String,
    val projectReference: String,
    val snapshotId: String,
    val releaseId: String,
    val snapshotDigest: String,
)

data class IssueEndpoint(val issueId: String, val sourceIssueId: String)

data class ArtifactEndpoint(val artifactId: String, val checksumSha256: String)

data class CommitEndpoint(val commitId: String)

data class BuildEndpoint(val buildRecordId: String)

data class EdgeCandidate(
    val projectId: String,
    val edgeType: TraceabilityEdgeType,
    val fromEntityId: String,
    val toEntityId: String,
    val sourceType: String,
    val sourceReference: String,
    val proofReference: String,
    val proofDigest: String,
)

data class EdgeRevisionRecord(
    val edgeId: String,
    val edgeType: TraceabilityEdgeType,
    val revisionId: String,
    val revision: Int,
    val verificationStatus: VerificationStatus,
    val confidence: Confidence,
    val factDigest: String,
)

class BuildProvenanceResult(
    val receiptId: String,
    val releaseIssueSnapshotId: String,
    val sourceCommitId: String,
    val buildRecordId: String,
    val envelopeDigest: String,
    val validatorVersion: String,
    val verificationStatus: VerificationStatus,
    val confidence: Confidence,
    edgeRevisions: List<EdgeRevisionRecord>,
) {
    private val edgeRevisionSnapshot = immutableList(
        edgeRevisions.sortedWith(
            compareBy<EdgeRevisionRecord>(
                { it.edgeType.name },
                EdgeRevisionRecord::edgeId,
                EdgeRevisionRecord::revision,
                EdgeRevisionRecord::revisionId,
            ),
        ),
    )

    val edgeRevisions: List<EdgeRevisionRecord>
        get() = edgeRevisionSnapshot

    fun copy(
        receiptId: String = this.receiptId,
        releaseIssueSnapshotId: String = this.releaseIssueSnapshotId,
        sourceCommitId: String = this.sourceCommitId,
        buildRecordId: String = this.buildRecordId,
        envelopeDigest: String = this.envelopeDigest,
        validatorVersion: String = this.validatorVersion,
        verificationStatus: VerificationStatus = this.verificationStatus,
        confidence: Confidence = this.confidence,
        edgeRevisions: List<EdgeRevisionRecord> = this.edgeRevisions,
    ) = BuildProvenanceResult(
        receiptId,
        releaseIssueSnapshotId,
        sourceCommitId,
        buildRecordId,
        envelopeDigest,
        validatorVersion,
        verificationStatus,
        confidence,
        edgeRevisions,
    )

    operator fun component1(): String = receiptId
    operator fun component2(): String = releaseIssueSnapshotId
    operator fun component3(): String = sourceCommitId
    operator fun component4(): String = buildRecordId
    operator fun component5(): String = envelopeDigest
    operator fun component6(): String = validatorVersion
    operator fun component7(): VerificationStatus = verificationStatus
    operator fun component8(): Confidence = confidence
    operator fun component9(): List<EdgeRevisionRecord> = edgeRevisions

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is BuildProvenanceResult &&
                    receiptId == other.receiptId &&
                    releaseIssueSnapshotId == other.releaseIssueSnapshotId &&
                    sourceCommitId == other.sourceCommitId &&
                    buildRecordId == other.buildRecordId &&
                    envelopeDigest == other.envelopeDigest &&
                    validatorVersion == other.validatorVersion &&
                    verificationStatus == other.verificationStatus &&
                    confidence == other.confidence &&
                    edgeRevisionSnapshot == other.edgeRevisionSnapshot
            )

    override fun hashCode(): Int {
        var result = receiptId.hashCode()
        result = 31 * result + releaseIssueSnapshotId.hashCode()
        result = 31 * result + sourceCommitId.hashCode()
        result = 31 * result + buildRecordId.hashCode()
        result = 31 * result + envelopeDigest.hashCode()
        result = 31 * result + validatorVersion.hashCode()
        result = 31 * result + verificationStatus.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + edgeRevisionSnapshot.hashCode()
        return result
    }

    override fun toString(): String =
        "BuildProvenanceResult(" +
            "receiptId=$receiptId, " +
            "releaseIssueSnapshotId=$releaseIssueSnapshotId, " +
            "sourceCommitId=$sourceCommitId, " +
            "buildRecordId=$buildRecordId, " +
            "envelopeDigest=$envelopeDigest, " +
            "validatorVersion=$validatorVersion, " +
            "verificationStatus=$verificationStatus, " +
            "confidence=$confidence, " +
            "edgeRevisions=$edgeRevisionSnapshot)"
}

data class BuildProvenanceReceipt(
    val receiptId: String,
    val key: BuildAttemptKey,
    val envelopeDigest: String,
    val result: BuildProvenanceResult,
    val issueCount: Int,
    val artifactCount: Int,
    val actorId: String,
    val createdAt: Instant,
)

private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
