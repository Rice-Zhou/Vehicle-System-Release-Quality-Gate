package com.ricezhou.vsrqg.release.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.release.domain.Release
import com.ricezhou.vsrqg.release.domain.ReleaseStateHistory
import com.ricezhou.vsrqg.release.domain.ReleaseStatus
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class CreateReleaseCommand(
    val principal: Principal,
    val projectId: String,
    val vehicle: String,
    val platform: String,
    val systemVersion: String,
    val buildId: String,
    val idempotencyKey: String,
    val requestDigest: String,
    val requestId: String,
)

data class CreateReleaseResult(
    val releaseId: String,
    val status: ReleaseStatus,
    val manifestId: String?,
    val createdAt: Instant,
    val version: Long,
)

@Service
class CreateRelease(
    private val authorizer: ProjectAuthorizer,
    private val idempotentExecutor: IdempotentExecutor,
    private val repository: ReleaseRepository,
    private val governanceStore: GovernanceStore,
    private val idGenerator: IdGenerator,
    private val timeProvider: TimeProvider,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun create(command: CreateReleaseCommand): CreateReleaseResult {
        val authorization = authorizer.require(
            command.principal,
            command.projectId,
            Permission.RELEASE_CREATE,
        )
        return idempotentExecutor.execute(
            scope = IDEMPOTENCY_SCOPE,
            principalId = authorization.principalId,
            key = command.idempotencyKey,
            requestDigest = command.requestDigest,
            responseType = CreateReleaseResult::class.java,
        ) {
            createAuthorized(command, authorization.principalId)
        }
    }

    private fun createAuthorized(command: CreateReleaseCommand, actorId: String): CreateReleaseResult {
        val createdAt = timeProvider.now()
        val release = Release(
            id = idGenerator.nextId("rel_"),
            projectId = command.projectId,
            vehicle = command.vehicle,
            platform = command.platform,
            systemVersion = command.systemVersion,
            declaredBuildId = command.buildId,
            status = ReleaseStatus.DRAFT,
            lockedManifestId = null,
            createdAt = createdAt,
            version = INITIAL_VERSION,
        )
        repository.insert(release)
        repository.appendStateHistory(
            ReleaseStateHistory(
                id = idGenerator.nextId("rsh_"),
                releaseId = release.id,
                previousStatus = null,
                newStatus = release.status,
                actorId = actorId,
                reason = INITIAL_HISTORY_REASON,
                occurredAt = createdAt,
            ),
        )
        governanceStore.appendAudit(
            projectId = release.projectId,
            actorId = actorId,
            action = AUDIT_ACTION,
            resourceType = AGGREGATE_TYPE,
            resourceId = release.id,
            requestId = command.requestId,
            reason = null,
            afterState = objectMapper.valueToTree(release),
        )
        governanceStore.appendOutbox(
            eventType = OUTBOX_EVENT_TYPE,
            aggregateType = AGGREGATE_TYPE,
            aggregateId = release.id,
            payload = objectMapper.createObjectNode()
                .put("schemaVersion", 1)
                .put("releaseId", release.id)
                .put("projectId", release.projectId)
                .put("status", release.status.name)
                .put("version", release.version)
                .put("createdAt", release.createdAt.toString())
                .put("requestId", command.requestId),
        )
        return CreateReleaseResult(
            releaseId = release.id,
            status = release.status,
            manifestId = release.lockedManifestId,
            createdAt = release.createdAt,
            version = release.version,
        )
    }

    private companion object {
        const val IDEMPOTENCY_SCOPE = "release:create"
        const val INITIAL_VERSION = 1L
        const val INITIAL_HISTORY_REASON = "Release created"
        const val AUDIT_ACTION = "RELEASE_CREATED"
        const val AGGREGATE_TYPE = "RELEASE"
        const val OUTBOX_EVENT_TYPE = "release.created"
    }
}
