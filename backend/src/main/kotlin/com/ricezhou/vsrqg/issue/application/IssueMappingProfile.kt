package com.ricezhou.vsrqg.issue.application

import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import com.ricezhou.vsrqg.shared.problem.SafeUnprocessableEntity
import java.time.Instant
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

class IssueMappingProfileRecord(
    val id: String,
    val projectId: String,
    val sourceId: String,
    val schemaVersion: String,
    val mappingVersion: String,
    definition: JsonNode,
    val createdBy: String,
    val createdAt: Instant,
) {
    private val authoritativeDefinition = definition.deepCopy<JsonNode>()

    val definition: JsonNode
        get() = authoritativeDefinition.deepCopy()
}

interface IssueMappingProfileRepository {
    fun findSource(sourceId: String): IssueSourceRecord?
    fun lockSource(sourceId: String): IssueSourceRecord?
    fun insert(profile: IssueMappingProfileRecord)
    fun activate(sourceId: String, adapterVersion: String, mappingVersion: String, activatedAt: Instant)
    fun find(sourceId: String, mappingVersion: String): IssueMappingProfileRecord?
}

data class IssueSourceRuntimeDescriptor(
    val sourceType: String,
    val adapterId: String,
    val adapterVersion: String,
    val supportedMappingSchemas: Set<String>,
    val supportedTransportRange: String,
)

fun interface IssueSourceDescriptorRegistry {
    fun require(sourceType: String): IssueSourceRuntimeDescriptor
}

class MappingProfileInvalid(violationCodes: List<String>) : SafeUnprocessableEntity(
    problemCode = "MAPPING_PROFILE_INVALID",
    problemTitle = "Mapping profile is invalid",
    problemDetail = "The mapping profile does not satisfy the supported schema",
    violationCodes = violationCodes,
)
