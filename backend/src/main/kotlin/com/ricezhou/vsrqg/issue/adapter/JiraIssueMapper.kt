package com.ricezhou.vsrqg.issue.adapter

import com.ricezhou.vsrqg.issue.application.CompiledIssueMappingProfile
import com.ricezhou.vsrqg.issue.domain.IssueMappingWarning
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus

class JiraIssueMapper(private val profile: CompiledIssueMappingProfile) {
    internal val mappingVersion: String
        get() = profile.mappingVersion

    fun status(raw: String): Pair<IssueStatus, IssueMappingWarning?> =
        profile.statusByToken[normalizeMappingToken(raw)]?.let { it to null }
            ?: (IssueStatus.UNKNOWN to IssueMappingWarning.UNKNOWN_STATUS)

    fun severity(raw: String): Pair<IssueSeverity, IssueMappingWarning?> =
        profile.severityByToken[normalizeMappingToken(raw)]?.let { it to null }
            ?: (IssueSeverity.UNKNOWN to IssueMappingWarning.UNKNOWN_SEVERITY)
}
