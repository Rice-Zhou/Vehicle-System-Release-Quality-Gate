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
        return try {
            repeat(MAX_PAGES) {
                if (!visited.add(cursor)) throw IssueSourceException(IssueSourceFailureCode.INVALID_OUTPUT)
                val page = source.fetchChanges(cursor, IssueFilter(), PAGE_SIZE)
                validatePage(running, page.mappingVersion, page.nextCursor, page.terminal)
                repository.persistPage(syncRunId, page)
                if (page.terminal) {
                    return repository.markSucceeded(syncRunId, page.nextCursor, page.sourceWatermark).toResult()
                }
                cursor = page.nextCursor
            }
            throw IssueSourceException(IssueSourceFailureCode.INVALID_OUTPUT)
        } catch (exception: IssueSourceException) {
            repository.markFailed(syncRunId, exception.code.name).toResult()
        } catch (_: DataAccessException) {
            repository.markFailed(syncRunId, PERSISTENCE_FAILED).toResult()
        }
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
    }
}
