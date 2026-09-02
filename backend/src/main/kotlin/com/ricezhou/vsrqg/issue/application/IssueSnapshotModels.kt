package com.ricezhou.vsrqg.issue.application

import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import java.time.Instant

internal const val SNAPSHOT_SCHEMA_VERSION = "release-issue-snapshot/v1"
internal const val SNAPSHOT_CANONICALIZATION_VERSION = "release-issue-snapshot-jcs/v1"
internal const val SNAPSHOT_AGE_POLICY_VERSION = "issue-snapshot-age/v1"

data class IssueSnapshotContext(
    val projectId: String,
    val releaseId: String,
    val lockedManifestId: String?,
    val sourceId: String,
)

data class SuccessfulFullIssueSyncRun(
    val id: String,
    val projectId: String,
    val sourceId: String,
    val sourceWatermark: String,
    val adapterVersion: String,
    val mappingVersion: String,
    val filterReference: String,
    val issueCount: Int,
    val completedAt: Instant,
)

data class SnapshotObservation(
    val issueId: String,
    val sourceIssueId: String,
    val title: String,
    val severity: IssueSeverity,
    val status: IssueStatus,
    val rawStatusToken: String?,
    val sourceVersion: String,
    val sourceReference: String,
    val observedAt: Instant,
    val mappingVersion: String,
    val tombstone: Boolean,
    val factDigest: String,
)

data class IssueSnapshotCandidate(
    val projectId: String,
    val releaseId: String,
    val snapshotVersion: Int,
    val syncRunId: String,
    val sourceId: String,
    val sourceWatermark: String,
    val adapterVersion: String,
    val mappingVersion: String,
    val filterReference: String,
    val agePolicyVersion: String,
    val observations: List<SnapshotObservation>,
    val observedCount: Int = observations.size,
    val tombstoneCount: Int = observations.count(SnapshotObservation::tombstone),
    val selectedCount: Int = observations.count { !it.tombstone },
)

data class CanonicalIssueSnapshot(
    val bytes: ByteArray,
    val digest: String,
)

data class MaterializedIssueSnapshot(
    val snapshotId: String,
    val candidate: IssueSnapshotCandidate,
    val canonical: CanonicalIssueSnapshot,
    val createdAt: Instant,
)

internal fun IssueSnapshotCandidate.selectedObservations(): List<SnapshotObservation> = observations
    .asSequence()
    .filterNot(SnapshotObservation::tombstone)
    .sortedWith(compareBy({ sourceId }, SnapshotObservation::sourceIssueId, SnapshotObservation::issueId))
    .toList()
