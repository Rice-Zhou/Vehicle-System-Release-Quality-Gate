package com.ricezhou.vsrqg.issue.application

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
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class StartIssueSyncCommand(
    val principal: Principal,
    val sourceId: String,
    val idempotencyKey: String,
    val requestDigest: String,
    val requestId: String,
)

data class StartIssueSyncResult(
    val operationId: String,
    val syncRunId: String,
    val status: IssueSyncStatus,
    val createdAt: Instant,
)

data class IssueSyncRunResult(
    val syncRunId: String,
    val sourceId: String,
    val status: IssueSyncStatus,
    val issueCount: Int,
    val warningCount: Int,
    val diagnosticCode: String?,
    val createdAt: Instant,
)

@Service
class StartIssueSync(
    private val authorizer: ProjectAuthorizer,
    private val idempotentExecutor: IdempotentExecutor,
    private val repository: IssueSyncRepository,
    private val governanceStore: GovernanceStore,
    private val idGenerator: IdGenerator,
    private val timeProvider: TimeProvider,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun start(command: StartIssueSyncCommand): StartIssueSyncResult {
        val source = repository.findSource(command.sourceId) ?: throw sourceNotFound(command.sourceId)
        if (!source.enabled) throw sourceDisabled(command.sourceId)
        val authorization = authorizer.require(command.principal, source.projectId, Permission.ISSUE_SYNC)
        return idempotentExecutor.execute(
            scope = IDEMPOTENCY_SCOPE,
            principalId = authorization.principalId,
            key = command.idempotencyKey,
            requestDigest = command.requestDigest,
            responseType = StartIssueSyncResult::class.java,
        ) {
            createAuthorized(command, source, authorization.principalId)
        }
    }

    private fun createAuthorized(
        command: StartIssueSyncCommand,
        source: IssueSourceRecord,
        actorId: String,
    ): StartIssueSyncResult {
        val now = timeProvider.now()
        val syncRunId = idGenerator.nextId("sync_")
        val jobId = idGenerator.nextId("job_")
        repository.insertRun(
            IssueSyncRunRecord(
                id = syncRunId,
                projectId = source.projectId,
                sourceId = source.id,
                status = IssueSyncStatus.QUEUED,
                cursorBefore = repository.currentSuccessfulCursor(source.id),
                cursorAfter = null,
                sourceWatermark = null,
                adapterVersion = source.adapterVersion,
                mappingVersion = source.mappingVersion,
                issueCount = 0,
                warningCount = 0,
                diagnosticCode = null,
                createdAt = now,
            ),
        )
        governanceStore.appendAudit(
            projectId = source.projectId,
            actorId = actorId,
            action = AUDIT_ACTION,
            resourceType = AGGREGATE_TYPE,
            resourceId = syncRunId,
            requestId = command.requestId,
            reason = null,
            afterState = objectMapper.createObjectNode()
                .put("schemaVersion", 1)
                .put("syncRunId", syncRunId)
                .put("sourceId", source.id)
                .put("status", IssueSyncStatus.QUEUED.name),
        )
        governanceStore.appendOutbox(
            eventType = OUTBOX_EVENT_TYPE,
            aggregateType = AGGREGATE_TYPE,
            aggregateId = syncRunId,
            payload = objectMapper.createObjectNode()
                .put("schemaVersion", 1)
                .put("syncRunId", syncRunId)
                .put("sourceId", source.id)
                .put("projectId", source.projectId)
                .put("requestId", command.requestId),
        )
        repository.insertJob(
            jobId = jobId,
            projectId = source.projectId,
            idempotencyKey = command.idempotencyKey,
            payload = objectMapper.createObjectNode()
                .put("schemaVersion", 1)
                .put("syncRunId", syncRunId),
            createdAt = now,
        )
        return StartIssueSyncResult(jobId, syncRunId, IssueSyncStatus.QUEUED, now)
    }

    private companion object {
        const val IDEMPOTENCY_SCOPE = "issue:sync"
        const val AUDIT_ACTION = "ISSUE_SYNC_QUEUED"
        const val AGGREGATE_TYPE = "ISSUE_SYNC_RUN"
        const val OUTBOX_EVENT_TYPE = "issue.sync.queued"
    }
}

@Service
class GetIssueSync(
    private val repository: IssueSyncRepository,
    private val authorizer: ProjectAuthorizer,
) {
    fun get(principal: Principal, syncRunId: String): IssueSyncRunResult {
        val run = repository.findRun(syncRunId) ?: throw syncRunNotFound(syncRunId)
        authorizer.require(principal, run.projectId, Permission.ISSUE_READ)
        return run.toResult()
    }
}

internal fun IssueSyncRunRecord.toResult() = IssueSyncRunResult(
    syncRunId = id,
    sourceId = sourceId,
    status = status,
    issueCount = issueCount,
    warningCount = warningCount,
    diagnosticCode = diagnosticCode,
    createdAt = createdAt,
)

private fun sourceNotFound(id: String) = ResourceNotFound(
    code = "ISSUE_SOURCE_NOT_FOUND",
    resourceTitle = "Issue source not found",
    detail = "Issue source '$id' was not found",
)

private fun syncRunNotFound(id: String) = ResourceNotFound(
    code = "ISSUE_SYNC_RUN_NOT_FOUND",
    resourceTitle = "Issue sync run not found",
    detail = "Issue sync run '$id' was not found",
)

private fun sourceDisabled(id: String) = ResourceConflict(
    code = "ISSUE_SOURCE_DISABLED",
    resourceTitle = "Issue source is disabled",
    detail = "Issue source '$id' is disabled",
)
