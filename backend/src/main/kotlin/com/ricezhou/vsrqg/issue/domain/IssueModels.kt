package com.ricezhou.vsrqg.issue.domain

import java.time.Instant

enum class IssueSeverity { CRITICAL, HIGH, MEDIUM, LOW, UNKNOWN }

enum class IssueStatus { OPEN, IN_PROGRESS, RESOLVED, CLOSED, UNKNOWN }

enum class IssueMappingWarning { UNKNOWN_STATUS, UNKNOWN_SEVERITY }

data class NormalizedIssue(
    val source: String,
    val sourceIssueId: String,
    val title: String,
    val severity: IssueSeverity,
    val status: IssueStatus,
    val rawSeverity: String,
    val rawStatus: String,
    val sourceVersion: String,
    val sourceReference: String,
    val observedAt: Instant,
    val mappingVersion: String,
    val tombstone: Boolean = false,
    val warnings: Set<IssueMappingWarning> = emptySet(),
)

data class IssueFilter(
    val includeTombstones: Boolean = true,
)

data class IssuePage(
    val issues: List<NormalizedIssue>,
    val nextCursor: String?,
    val sourceWatermark: String,
    val observedAt: Instant,
    val mappingVersion: String,
    val terminal: Boolean,
)

data class IssueBatch(
    val issues: List<NormalizedIssue>,
    val missingIds: Set<String>,
    val observedAt: Instant,
    val mappingVersion: String,
)

data class SourceCapabilities(
    val readOnly: Boolean,
    val incremental: Boolean,
    val tombstones: Boolean,
)

data class SourceHealth(
    val available: Boolean,
    val code: String,
)
