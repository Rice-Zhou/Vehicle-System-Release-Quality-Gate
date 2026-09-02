package com.ricezhou.vsrqg.issue.application

import com.ricezhou.vsrqg.issue.domain.IssueFilter
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service

@Service
class RunIssueSync(
    private val repository: IssueSyncRepository,
) {
    fun run(syncRunId: String, source: IssueSourcePort): IssueSyncRunResult {
        val running = repository.markRunning(syncRunId)
        var cursor = running.cursorBefore
        val visited = mutableSetOf<String?>()
        var successTransitionStarted = false
        return try {
            repeat(MAX_PAGES) {
                if (!visited.add(cursor)) throw IssueSourceException(IssueSourceFailureCode.INVALID_OUTPUT)
                val page = source.fetchChanges(cursor, IssueFilter(), PAGE_SIZE)
                validatePage(running, page.mappingVersion, page.nextCursor, page.terminal)
                repository.persistPage(syncRunId, page)
                if (page.terminal) {
                    successTransitionStarted = true
                    return completeSuccessfully(syncRunId, page.nextCursor, page.sourceWatermark).toResult()
                }
                cursor = page.nextCursor
            }
            throw IssueSourceException(IssueSourceFailureCode.INVALID_OUTPUT)
        } catch (exception: IssueSourceException) {
            repository.markFailed(syncRunId, exception.code.name).toResult()
        } catch (exception: DataAccessException) {
            if (successTransitionStarted) throw exception
            failPersistence(syncRunId, exception)
        }
    }

    private fun completeSuccessfully(
        syncRunId: String,
        successfulCursor: String?,
        sourceWatermark: String,
    ): IssueSyncRunRecord = try {
        repository.markSucceeded(syncRunId, successfulCursor, sourceWatermark)
    } catch (original: DataAccessException) {
        val reconciled = try {
            repository.findRun(syncRunId)
        } catch (coordinationFailure: RuntimeException) {
            if (coordinationFailure !== original) original.addSuppressed(coordinationFailure)
            throw original
        }
        if (reconciled?.status == IssueSyncStatus.SUCCEEDED &&
            reconciled.cursorAfter == successfulCursor &&
            reconciled.sourceWatermark == sourceWatermark
        ) {
            reconciled
        } else {
            original.addSuppressed(IllegalStateException(TERMINAL_RECONCILIATION_FAILED))
            throw original
        }
    }

    private fun failPersistence(syncRunId: String, original: DataAccessException): IssueSyncRunResult = try {
        val failed = repository.markFailed(syncRunId, PERSISTENCE_FAILED)
        if (failed.status != IssueSyncStatus.FAILED || failed.diagnosticCode != PERSISTENCE_FAILED) {
            original.addSuppressed(IllegalStateException(TERMINAL_RECONCILIATION_FAILED))
            throw original
        }
        failed.toResult()
    } catch (coordinationFailure: RuntimeException) {
        if (coordinationFailure !== original) original.addSuppressed(coordinationFailure)
        throw original
    }

    private fun validatePage(
        run: IssueSyncRunRecord,
        mappingVersion: String,
        nextCursor: String?,
        terminal: Boolean,
    ) {
        if (mappingVersion != run.mappingVersion || terminal != (nextCursor == null)) {
            throw IssueSourceException(IssueSourceFailureCode.INVALID_OUTPUT)
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val MAX_PAGES = 10_000
        const val PERSISTENCE_FAILED = "PERSISTENCE_FAILED"
        const val TERMINAL_RECONCILIATION_FAILED = "ISSUE_SYNC_TERMINAL_RECONCILIATION_FAILED"
    }
}
