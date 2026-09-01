package com.ricezhou.vsrqg.issue.application

import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus

data class CompiledIssueMappingProfile(
    val schemaVersion: String,
    val mappingVersion: String,
    val definition: JsonNode,
    val statusByToken: Map<String, IssueStatus>,
    val severityByToken: Map<String, IssueSeverity>,
)

fun interface IssueMappingProfileCodec {
    fun compile(definition: JsonNode): CompiledIssueMappingProfile
}

class MappingProfileInvalid(val violationCodes: List<String>) : RuntimeException("MAPPING_PROFILE_INVALID")
