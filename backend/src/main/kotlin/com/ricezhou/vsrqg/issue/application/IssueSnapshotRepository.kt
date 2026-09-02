package com.ricezhou.vsrqg.issue.application

interface IssueSnapshotRepository {
    fun findContext(releaseId: String, sourceId: String): IssueSnapshotContext?

    fun lockContext(releaseId: String, sourceId: String): IssueSnapshotContext?

    fun findLatestSuccessfulFullRun(projectId: String, sourceId: String): SuccessfulFullIssueSyncRun?

    fun findExisting(
        releaseId: String,
        syncRunId: String,
        filterReference: String,
    ): MaterializedIssueSnapshot?

    fun nextSnapshotVersion(releaseId: String): Int

    fun loadObservations(run: SuccessfulFullIssueSyncRun): List<SnapshotObservation>

    fun insert(snapshot: MaterializedIssueSnapshot)

    fun read(snapshotId: String): MaterializedIssueSnapshot?
}
