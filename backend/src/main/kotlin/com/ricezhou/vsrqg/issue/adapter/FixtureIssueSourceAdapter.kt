package com.ricezhou.vsrqg.issue.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.issue.application.IssueSourceException
import com.ricezhou.vsrqg.issue.application.IssueSourceFailureCode
import com.ricezhou.vsrqg.issue.application.IssueSourcePort
import com.ricezhou.vsrqg.issue.domain.IssueBatch
import com.ricezhou.vsrqg.issue.domain.IssueFilter
import com.ricezhou.vsrqg.issue.domain.IssueMappingWarning
import com.ricezhou.vsrqg.issue.domain.IssuePage
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import com.ricezhou.vsrqg.issue.domain.NormalizedIssue
import com.ricezhou.vsrqg.issue.domain.SourceCapabilities
import com.ricezhou.vsrqg.issue.domain.SourceHealth
import java.io.InputStream
import java.time.Duration
import java.time.Instant

data class FixtureFailure(
    val code: IssueSourceFailureCode,
    val retryAfter: Duration? = null,
    val occurrences: Int = 1,
) {
    init {
        require(occurrences > 0)
    }
}

data class FixturePage(
    val cursor: String?,
    val issues: List<NormalizedIssue>,
    val nextCursor: String?,
    val sourceWatermark: String,
    val observedAt: Instant,
    val terminal: Boolean,
)

data class FixtureScenario(
    val source: String,
    val mappingVersion: String,
    val pages: List<FixturePage>,
)

class FixtureIssueSourceAdapter(
    private val scenario: FixtureScenario,
    private val failures: Map<String?, FixtureFailure> = emptyMap(),
) : IssueSourcePort {
    private val pagesByCursor = scenario.pages.associateBy(FixturePage::cursor)
    private val attemptsByCursor = mutableMapOf<String?, Int>()

    init {
        require(scenario.source.isNotBlank())
        require(scenario.mappingVersion.isNotBlank())
        require(pagesByCursor.size == scenario.pages.size)
    }

    override fun capabilities() = SourceCapabilities(readOnly = true, incremental = false, tombstones = true)

    @Synchronized
    override fun fetchChanges(cursor: String?, filter: IssueFilter, pageSize: Int): IssuePage {
        requirePageSize(pageSize)
        failures[cursor]?.let { failure ->
            val attempt = attemptsByCursor.getOrDefault(cursor, 0) + 1
            attemptsByCursor[cursor] = attempt
            if (attempt <= failure.occurrences) throw IssueSourceException(failure.code, failure.retryAfter)
        }
        val page = pagesByCursor[cursor] ?: throw IssueSourceException(IssueSourceFailureCode.INVALID_REQUEST)
        val issues = page.issues
            .asSequence()
            .filter { filter.includeTombstones || !it.tombstone }
            .take(pageSize)
            .toList()
        return IssuePage(
            issues = issues,
            nextCursor = page.nextCursor,
            sourceWatermark = page.sourceWatermark,
            observedAt = page.observedAt,
            mappingVersion = scenario.mappingVersion,
            terminal = page.terminal,
        )
    }

    override fun fetchByIds(sourceIssueIds: Set<String>): IssueBatch {
        requireBoundedIds(sourceIssueIds)
        val latestById = scenario.pages
            .flatMap(FixturePage::issues)
            .filter { it.sourceIssueId in sourceIssueIds }
            .associateBy(NormalizedIssue::sourceIssueId)
        val issues = latestById.values.sortedBy(NormalizedIssue::sourceIssueId)
        val observedAt = scenario.pages.maxOfOrNull(FixturePage::observedAt) ?: Instant.EPOCH
        return IssueBatch(
            issues = issues,
            missingIds = sourceIssueIds - latestById.keys,
            observedAt = observedAt,
            mappingVersion = scenario.mappingVersion,
        )
    }

    override fun health() = SourceHealth(available = true, code = "AVAILABLE")

    companion object {
        fun fromJson(objectMapper: ObjectMapper, input: InputStream): FixtureIssueSourceAdapter {
            val document = objectMapper.readValue(input, FixtureDocument::class.java)
            val pages = document.pages.map { page ->
                val observedAt = Instant.parse(page.observedAt)
                FixturePage(
                    cursor = page.cursor,
                    issues = page.issues.map { issue -> issue.normalize(document.source, document.mappingVersion, observedAt) },
                    nextCursor = page.nextCursor,
                    sourceWatermark = page.sourceWatermark,
                    observedAt = observedAt,
                    terminal = page.terminal,
                )
            }
            return FixtureIssueSourceAdapter(FixtureScenario(document.source, document.mappingVersion, pages))
        }
    }
}

private data class FixtureDocument(
    val source: String,
    val mappingVersion: String,
    val pages: List<FixturePageDocument>,
)

private data class FixturePageDocument(
    val cursor: String?,
    val nextCursor: String?,
    val sourceWatermark: String,
    val observedAt: String,
    val terminal: Boolean,
    val issues: List<FixtureIssueDocument>,
)

private data class FixtureIssueDocument(
    val sourceIssueId: String,
    val title: String,
    val severity: IssueSeverity,
    val status: IssueStatus,
    val rawSeverity: String,
    val rawStatus: String,
    val sourceVersion: String,
    val sourceReference: String,
    val tombstone: Boolean = false,
    val warnings: Set<IssueMappingWarning> = emptySet(),
) {
    fun normalize(source: String, mappingVersion: String, observedAt: Instant) = NormalizedIssue(
        source = source,
        sourceIssueId = sourceIssueId,
        title = title,
        severity = severity,
        status = status,
        rawSeverity = rawSeverity,
        rawStatus = rawStatus,
        sourceVersion = sourceVersion,
        sourceReference = sourceReference,
        observedAt = observedAt,
        mappingVersion = mappingVersion,
        tombstone = tombstone,
        warnings = warnings,
    )
}

private fun requirePageSize(pageSize: Int) {
    if (pageSize !in 1..MAX_ISSUES) throw IssueSourceException(IssueSourceFailureCode.INVALID_REQUEST)
}

private fun requireBoundedIds(sourceIssueIds: Set<String>) {
    if (sourceIssueIds.size > MAX_ISSUES || sourceIssueIds.any(String::isBlank)) {
        throw IssueSourceException(IssueSourceFailureCode.INVALID_REQUEST)
    }
}

internal const val MAX_ISSUES = 20
