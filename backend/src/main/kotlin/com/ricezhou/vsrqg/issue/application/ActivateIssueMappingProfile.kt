package com.ricezhou.vsrqg.issue.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ActivateIssueMappingProfileCommand(
    val principal: Principal,
    val sourceId: String,
    val idempotencyKey: String,
    val definition: JsonNode,
    val requestId: String,
)

data class ActivateIssueMappingProfileResult(
    val profileId: String,
    val sourceId: String,
    val schemaVersion: String,
    val mappingVersion: String,
    val activatedAt: Instant,
)

@Service
class ActivateIssueMappingProfile(
    private val authorizer: ProjectAuthorizer,
    private val codec: IssueMappingProfileCodec,
    private val idempotentExecutor: IdempotentExecutor,
    private val repository: IssueMappingProfileRepository,
    private val descriptorRegistry: IssueSourceDescriptorRegistry,
    private val governanceStore: GovernanceStore,
    private val idGenerator: IdGenerator,
    private val timeProvider: TimeProvider,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun activate(command: ActivateIssueMappingProfileCommand): ActivateIssueMappingProfileResult {
        val source = repository.findSource(command.sourceId) ?: throw sourceNotFound()
        val authorization = authorizer.require(command.principal, source.projectId, Permission.ISSUE_CONFIGURE)
        val compiled = codec.compile(command.definition)
        val requestDigest = requestDigest(
            principalId = authorization.principalId,
            projectId = source.projectId,
            sourceId = source.id,
            compiled = compiled,
        )
        return idempotentExecutor.execute(
            scope = IDEMPOTENCY_SCOPE,
            principalId = authorization.principalId,
            key = command.idempotencyKey,
            requestDigest = requestDigest,
            responseType = ActivateIssueMappingProfileResult::class.java,
        ) {
            activateLocked(command, source.projectId, authorization.principalId, compiled)
        }
    }

    private fun activateLocked(
        command: ActivateIssueMappingProfileCommand,
        authorizedProjectId: String,
        actorId: String,
        compiled: CompiledIssueMappingProfile,
    ): ActivateIssueMappingProfileResult {
        val source = repository.lockSource(command.sourceId) ?: throw sourceNotFound()
        if (source.projectId != authorizedProjectId) {
            throw AccessDeniedException("Issue source project changed during activation")
        }
        val descriptor = descriptorRegistry.require(source.sourceType)
        if (compiled.schemaVersion !in descriptor.supportedMappingSchemas) {
            throw ResourceConflict(
                code = "MAPPING_PROFILE_SCHEMA_UNSUPPORTED",
                resourceTitle = "Mapping profile schema is unsupported",
                detail = "The configured adapter does not support the mapping profile schema",
            )
        }

        val activatedAt = timeProvider.now()
        val proposed = IssueMappingProfileRecord(
            id = idGenerator.nextId("map_"),
            projectId = source.projectId,
            sourceId = source.id,
            schemaVersion = compiled.schemaVersion,
            mappingVersion = compiled.mappingVersion,
            definition = compiled.definition,
            createdBy = actorId,
            createdAt = activatedAt,
        )
        repository.insert(proposed)
        val persisted = repository.find(source.id, compiled.mappingVersion)
            ?: error("Inserted mapping profile could not be read back")
        repository.activate(source.id, descriptor.adapterVersion, compiled.mappingVersion, activatedAt)

        val safeMetadata = objectMapper.createObjectNode()
            .put("schemaVersion", compiled.schemaVersion)
            .put("profileId", persisted.id)
            .put("projectId", source.projectId)
            .put("sourceId", source.id)
            .put("adapterVersion", descriptor.adapterVersion)
            .put("mappingVersion", compiled.mappingVersion)
            .put("requestId", command.requestId)
        governanceStore.appendAudit(
            projectId = source.projectId,
            actorId = actorId,
            action = AUDIT_ACTION,
            resourceType = AGGREGATE_TYPE,
            resourceId = persisted.id,
            requestId = command.requestId,
            reason = null,
            afterState = safeMetadata,
        )
        governanceStore.appendOutbox(
            eventType = OUTBOX_EVENT_TYPE,
            aggregateType = AGGREGATE_TYPE,
            aggregateId = persisted.id,
            payload = safeMetadata.deepCopy(),
        )
        return ActivateIssueMappingProfileResult(
            profileId = persisted.id,
            sourceId = source.id,
            schemaVersion = compiled.schemaVersion,
            mappingVersion = compiled.mappingVersion,
            activatedAt = activatedAt,
        )
    }

    private fun requestDigest(
        principalId: String,
        projectId: String,
        sourceId: String,
        compiled: CompiledIssueMappingProfile,
    ): String {
        val authoritativeRequest = objectMapper.createObjectNode()
            .put("principalId", principalId)
            .put("projectId", projectId)
            .put("sourceId", sourceId)
            .put("schemaVersion", compiled.schemaVersion)
            .put("mappingVersion", compiled.mappingVersion)
        val bytes = authoritativeRequest.toString().toByteArray(StandardCharsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "sha256:" + hash.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sourceNotFound() = ResourceNotFound(
        code = "ISSUE_SOURCE_NOT_FOUND",
        resourceTitle = "Issue source not found",
        detail = "The issue source was not found",
    )

    private companion object {
        const val IDEMPOTENCY_SCOPE = "issue:mapping-profile:activate"
        const val AUDIT_ACTION = "ISSUE_MAPPING_PROFILE_ACTIVATED"
        const val AGGREGATE_TYPE = "ISSUE_MAPPING_PROFILE"
        const val OUTBOX_EVENT_TYPE = "issue.mapping-profile.activated"
    }
}
