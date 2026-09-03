package com.ricezhou.vsrqg.traceability.application

import com.ricezhou.vsrqg.traceability.domain.ProvenanceValidation
import java.time.Instant

interface BuildProvenanceRepository {
    fun lockContext(projectReference: String, snapshotId: String): BuildProvenanceContext?

    fun findReceipt(key: BuildAttemptKey): BuildProvenanceReceipt?

    fun resolveSnapshotIssues(
        context: BuildProvenanceContext,
        sourceIssueIds: List<String>,
    ): List<IssueEndpoint>

    fun resolveArtifacts(projectId: String, artifactSha256s: List<String>): List<ArtifactEndpoint>

    fun resolveCommit(
        projectId: String,
        repository: String,
        sourceRevision: String,
        now: Instant,
    ): CommitEndpoint

    fun resolveBuild(
        projectId: String,
        key: BuildAttemptKey,
        repository: String,
        sourceRevision: String,
        now: Instant,
    ): BuildEndpoint

    fun appendRevisions(
        candidates: List<EdgeCandidate>,
        validation: ProvenanceValidation,
        now: Instant,
    ): List<EdgeRevisionRecord>

    fun insertReceipt(receipt: BuildProvenanceReceipt)

    fun readReceipt(receiptId: String): BuildProvenanceReceipt?
}
