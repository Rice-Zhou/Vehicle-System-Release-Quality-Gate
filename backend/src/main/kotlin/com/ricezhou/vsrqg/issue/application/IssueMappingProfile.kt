package com.ricezhou.vsrqg.issue.application

import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import java.util.Collections

class CompiledIssueMappingProfile(
    val schemaVersion: String,
    val mappingVersion: String,
    definition: JsonNode,
    statusByToken: Map<String, IssueStatus>,
    severityByToken: Map<String, IssueSeverity>,
) {
    private val authoritativeDefinition = definition.deepCopy<JsonNode>()
    private val authoritativeStatusByToken = immutableCopy(statusByToken)
    private val authoritativeSeverityByToken = immutableCopy(severityByToken)

    val definition: JsonNode
        get() = authoritativeDefinition.deepCopy()

    val statusByToken: Map<String, IssueStatus>
        get() = authoritativeStatusByToken

    val severityByToken: Map<String, IssueSeverity>
        get() = authoritativeSeverityByToken

    private fun <K, V> immutableCopy(source: Map<K, V>): Map<K, V> =
        Collections.unmodifiableMap(LinkedHashMap(source))
}

fun interface IssueMappingProfileCodec {
    fun compile(definition: JsonNode): CompiledIssueMappingProfile
}

class MappingProfileInvalid(violationCodes: List<String>) : RuntimeException("MAPPING_PROFILE_INVALID") {
    val violationCodes: List<String> = Collections.unmodifiableList(ArrayList(violationCodes))
}
