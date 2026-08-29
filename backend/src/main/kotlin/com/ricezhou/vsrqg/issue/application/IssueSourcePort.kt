package com.ricezhou.vsrqg.issue.application

import com.ricezhou.vsrqg.issue.domain.IssueBatch
import com.ricezhou.vsrqg.issue.domain.IssueFilter
import com.ricezhou.vsrqg.issue.domain.IssuePage
import com.ricezhou.vsrqg.issue.domain.SourceCapabilities
import com.ricezhou.vsrqg.issue.domain.SourceHealth
import java.time.Duration

interface IssueSourcePort {
    fun capabilities(): SourceCapabilities

    fun fetchChanges(cursor: String?, filter: IssueFilter, pageSize: Int): IssuePage

    fun fetchByIds(sourceIssueIds: Set<String>): IssueBatch

    fun health(): SourceHealth
}

enum class IssueSourceFailureCode(val retryable: Boolean) {
    INVALID_REQUEST(false),
    RATE_LIMITED(true),
    UPSTREAM_5XX(true),
    UNAUTHORIZED(false),
    FORBIDDEN(false),
    TIMEOUT(true),
    INVALID_OUTPUT(false),
    OUTPUT_LIMIT_EXCEEDED(false),
    PROCESS_FAILED(false),
}

class IssueSourceException(
    val code: IssueSourceFailureCode,
    val retryAfter: Duration? = null,
    val diagnosticDigest: String? = null,
) : RuntimeException(code.name) {
    val retryable: Boolean = code.retryable
}
