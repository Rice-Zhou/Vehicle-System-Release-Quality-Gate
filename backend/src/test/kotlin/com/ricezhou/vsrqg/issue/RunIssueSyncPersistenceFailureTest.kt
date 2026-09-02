package com.ricezhou.vsrqg.issue

import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.issue.application.IssueSourcePort
import com.ricezhou.vsrqg.issue.application.IssueSyncRepository
import com.ricezhou.vsrqg.issue.application.IssueSyncResultSetMode
import com.ricezhou.vsrqg.issue.application.IssueSyncRunRecord
import com.ricezhou.vsrqg.issue.application.IssueSyncStatus
import com.ricezhou.vsrqg.issue.application.QueuedIssueSync
import com.ricezhou.vsrqg.issue.application.RunIssueSync
import com.ricezhou.vsrqg.issue.domain.IssueBatch
import com.ricezhou.vsrqg.issue.domain.IssueFilter
import com.ricezhou.vsrqg.issue.domain.IssuePage
import com.ricezhou.vsrqg.issue.domain.SourceCapabilities
import com.ricezhou.vsrqg.issue.domain.SourceHealth
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException

class RunIssueSyncPersistenceFailureTest {
    @Test
    fun `legacy run model preserves absent result metadata`() {
        val legacy = queuedRun().copy(resultSetMode = null, filterReference = null)

        assertThat(legacy.resultSetMode).isNull()
        assertThat(legacy.filterReference).isNull()
    }

    @Test
    fun `page failure remains primary when failed terminal coordination also fails`() {
        val original = DataIntegrityViolationException("page write failed")
        val coordination = IllegalStateException("terminal coordination failed")
        val repository = FailureRepository(pageFailure = original, failedTerminalFailure = coordination)

        assertThatThrownBy { RunIssueSync(repository).run(RUN_ID, terminalSource()) }
            .isSameAs(original)
        assertThat(original.suppressed).containsExactly(coordination)
    }

    @Test
    fun `page failure remains primary when terminal coordination returns different facts`() {
        val original = DataIntegrityViolationException("page write failed")
        val repository = FailureRepository(pageFailure = original, failedTerminalStatus = IssueSyncStatus.SUCCEEDED)

        assertThatThrownBy { RunIssueSync(repository).run(RUN_ID, terminalSource()) }
            .isSameAs(original)
        assertThat(original.suppressed.map { it.message })
            .containsExactly("ISSUE_SYNC_TERMINAL_RECONCILIATION_FAILED")
    }

    @Test
    fun `uncertain success reconciles an already committed run with identical terminal facts`() {
        val original = DataIntegrityViolationException("connection lost after commit")
        val repository = FailureRepository(successFailure = original, commitSuccessBeforeFailure = true)

        val result = RunIssueSync(repository).run(RUN_ID, terminalSource())

        assertThat(result.status).isEqualTo(IssueSyncStatus.SUCCEEDED)
        assertThat(result.diagnosticCode).isNull()
    }

    @Test
    fun `uncertain success with different terminal facts preserves original failure`() {
        val original = DataIntegrityViolationException("connection lost after commit")
        val repository = FailureRepository(
            successFailure = original,
            commitSuccessBeforeFailure = true,
            committedWatermark = "different-watermark",
        )

        assertThatThrownBy { RunIssueSync(repository).run(RUN_ID, terminalSource()) }
            .isSameAs(original)
        assertThat(original.suppressed.map { it.message })
            .containsExactly("ISSUE_SYNC_TERMINAL_RECONCILIATION_FAILED")
    }

    private fun terminalSource() = object : IssueSourcePort {
        override fun fetchChanges(cursor: String?, filter: IssueFilter, pageSize: Int) = IssuePage(
            issues = emptyList(),
            nextCursor = null,
            sourceWatermark = WATERMARK,
            observedAt = NOW,
            mappingVersion = MAPPING_VERSION,
            terminal = true,
        )

        override fun fetchByIds(sourceIssueIds: Set<String>) = IssueBatch(
            emptyList(),
            sourceIssueIds,
            NOW,
            MAPPING_VERSION,
        )

        override fun capabilities() = SourceCapabilities(readOnly = true, incremental = true, tombstones = true)
        override fun health() = SourceHealth(available = true, code = "AVAILABLE")
    }

    private class FailureRepository(
        private val pageFailure: RuntimeException? = null,
        private val failedTerminalFailure: RuntimeException? = null,
        private val successFailure: RuntimeException? = null,
        private val commitSuccessBeforeFailure: Boolean = false,
        private val committedWatermark: String = WATERMARK,
        private val failedTerminalStatus: IssueSyncStatus = IssueSyncStatus.FAILED,
    ) : IssueSyncRepository {
        private var run = queuedRun()

        override fun markRunning(syncRunId: String): IssueSyncRunRecord = run.copy(
            status = IssueSyncStatus.RUNNING,
        ).also { run = it }

        override fun persistPage(syncRunId: String, page: IssuePage) {
            pageFailure?.let { throw it }
        }

        override fun markSucceeded(
            syncRunId: String,
            successfulCursor: String?,
            sourceWatermark: String,
        ): IssueSyncRunRecord {
            if (commitSuccessBeforeFailure) {
                run = run.copy(
                    status = IssueSyncStatus.SUCCEEDED,
                    cursorAfter = successfulCursor,
                    sourceWatermark = committedWatermark,
                )
            }
            successFailure?.let { throw it }
            return run
        }

        override fun markFailed(syncRunId: String, diagnosticCode: String): IssueSyncRunRecord {
            failedTerminalFailure?.let { throw it }
            return run.copy(status = failedTerminalStatus, diagnosticCode = diagnosticCode).also { run = it }
        }

        override fun findRun(syncRunId: String) = run.takeIf { it.id == syncRunId }
        override fun findSource(sourceId: String) = error("unused")
        override fun lockSource(sourceId: String) = error("unused")
        override fun currentSuccessfulCursor(sourceId: String): String? = error("unused")
        override fun insertRun(run: IssueSyncRunRecord) = error("unused")
        override fun insertJob(jobId: String, projectId: String, idempotencyKey: String, payload: JsonNode, createdAt: Instant) = error("unused")
        override fun claimNextJob(): QueuedIssueSync? = error("unused")
        override fun markJobSucceeded(jobId: String) = error("unused")
        override fun markJobFailed(jobId: String, diagnosticCode: String) = error("unused")
    }

    private companion object {
        const val RUN_ID = "sync-1"
        const val MAPPING_VERSION = "mapping-v1"
        const val WATERMARK = "watermark-1"
        val NOW: Instant = Instant.parse("2026-09-02T12:00:00Z")

        fun queuedRun() = IssueSyncRunRecord(
            id = RUN_ID,
            projectId = "project-1",
            sourceId = "source-1",
            status = IssueSyncStatus.QUEUED,
            cursorBefore = null,
            cursorAfter = null,
            sourceWatermark = null,
            adapterVersion = "adapter-v1",
            mappingVersion = MAPPING_VERSION,
            resultSetMode = IssueSyncResultSetMode.FULL,
            filterReference = "all-relevant-issues/v1",
            issueCount = 0,
            warningCount = 0,
            diagnosticCode = null,
            createdAt = NOW,
        )
    }
}
