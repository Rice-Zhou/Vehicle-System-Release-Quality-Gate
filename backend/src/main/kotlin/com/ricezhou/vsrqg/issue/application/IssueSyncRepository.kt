package com.ricezhou.vsrqg.issue.application

import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.issue.domain.IssuePage
import java.time.Instant

enum class IssueSyncStatus { QUEUED, RUNNING, SUCCEEDED, FAILED }

enum class IssueSyncResultSetMode { FULL, DELTA }

data class IssueSourceRecord(
    val id: String,
    val projectId: String,
    val sourceType: String,
    val adapterVersion: String,
    val mappingVersion: String,
    val enabled: Boolean,
)

data class IssueSyncRunRecord(
    val id: String,
    val projectId: String,
    val sourceId: String,
    val status: IssueSyncStatus,
    val cursorBefore: String?,
    val cursorAfter: String?,
    val sourceWatermark: String?,
    val adapterVersion: String,
    val mappingVersion: String,
    val resultSetMode: IssueSyncResultSetMode?,
    val filterReference: String?,
    val issueCount: Int,
    val warningCount: Int,
    val diagnosticCode: String?,
    val createdAt: Instant,
)

data class QueuedIssueSync(
    val syncRunId: String,
    val jobId: String,
)

interface IssueSyncRepository {
    fun findSource(sourceId: String): IssueSourceRecord?

    fun lockSource(sourceId: String): IssueSourceRecord?

    fun currentSuccessfulCursor(sourceId: String): String?

    fun insertRun(run: IssueSyncRunRecord)

    fun insertJob(
        jobId: String,
        projectId: String,
        idempotencyKey: String,
        payload: JsonNode,
        createdAt: Instant,
    )

    fun markRunning(syncRunId: String): IssueSyncRunRecord

    fun persistPage(syncRunId: String, page: IssuePage)

    fun markSucceeded(
        syncRunId: String,
        successfulCursor: String?,
        sourceWatermark: String,
    ): IssueSyncRunRecord

    fun markFailed(syncRunId: String, diagnosticCode: String): IssueSyncRunRecord

    fun findRun(syncRunId: String): IssueSyncRunRecord?

    fun claimNextJob(): QueuedIssueSync?

    fun markJobSucceeded(jobId: String)

    fun markJobFailed(jobId: String, diagnosticCode: String)
}
