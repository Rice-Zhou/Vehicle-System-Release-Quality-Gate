package com.ricezhou.vsrqg.issue.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.ricezhou.vsrqg.shared.application.SafeValidationDiagnostic
import com.ricezhou.vsrqg.shared.application.SafeValidationFailure
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.time.Duration
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class CreateIssueSnapshotCommand(
    val principal: Principal,
    val releaseId: String,
    val sourceId: String,
    val idempotencyKey: String,
    val requestDigest: String,
    val requestId: String,
)

data class CreateIssueSnapshotResult(
    val snapshotId: String,
    val releaseId: String,
    val syncRunId: String,
    val snapshotVersion: Int,
    val contentDigest: String,
    val selectedCount: Int,
    val createdAt: Instant,
)

data class IssueSnapshotPolicy(
    val enabled: Boolean,
    val maxSyncAge: Duration,
)

class IssueSnapshotInvalid(code: String, cause: Throwable? = null) : SafeValidationFailure(
    SafeValidationDiagnostic.ISSUE_SNAPSHOT_INVALID,
    listOf(code),
) {
    init {
        if (cause != null) initCause(cause)
    }
}

open class SnapshotContentIntegrityFailure(cause: Throwable? = null) :
    RuntimeException("Snapshot semantic integrity validation failed", cause)

class SnapshotFactIntegrityFailure(cause: Throwable? = null) : SnapshotContentIntegrityFailure(cause)

class SyncObservationIntegrityFailure(cause: Throwable? = null) :
    RuntimeException("Sync observation semantic integrity validation failed", cause)

@Service
class CreateIssueSnapshot(
    private val authorizer: ProjectAuthorizer,
    private val idempotentExecutor: IdempotentExecutor,
    private val repository: IssueSnapshotRepository,
    private val canonicalizer: IssueSnapshotCanonicalizer,
    private val governanceStore: GovernanceStore,
    private val idGenerator: IdGenerator,
    private val timeProvider: TimeProvider,
    private val objectMapper: ObjectMapper,
    private val policy: IssueSnapshotPolicy,
) {
    @Transactional
    fun create(command: CreateIssueSnapshotCommand): CreateIssueSnapshotResult {
        val transactionStartedAt = timeProvider.now()
        if (!policy.enabled) throw notFound()
        val context = repository.findContext(command.releaseId, command.sourceId) ?: throw notFound()
        try {
            authorizer.require(command.principal, context.projectId, Permission.RELEASE_READ)
        } catch (_: org.springframework.security.access.AccessDeniedException) {
            throw notFound()
        }
        val authorization = authorizer.require(command.principal, context.projectId, Permission.ISSUE_SNAPSHOT)
        return idempotentExecutor.execute(
            IDEMPOTENCY_SCOPE,
            authorization.principalId,
            command.idempotencyKey,
            command.requestDigest,
            CreateIssueSnapshotResult::class.java,
        ) {
            createLocked(command, context.projectId, authorization.principalId, transactionStartedAt)
        }
    }

    private fun createLocked(
        command: CreateIssueSnapshotCommand,
        projectId: String,
        actorId: String,
        transactionStartedAt: Instant,
    ): CreateIssueSnapshotResult {
        val context = repository.lockContext(command.releaseId, command.sourceId) ?: throw notFound()
        if (context.projectId != projectId || context.releaseId != command.releaseId || context.sourceId != command.sourceId) {
            throw notFound()
        }
        if (context.lockedManifestId == null) throw ResourceConflict(
            "RELEASE_MANIFEST_NOT_LOCKED",
            "Release Manifest is not locked",
            "A locked Release Manifest is required before issue snapshot creation",
        )
        val run = try {
            repository.findLatestSuccessfulFullRun(projectId, command.sourceId)
        } catch (exception: SyncObservationIntegrityFailure) {
            throw IssueSnapshotInvalid("SYNC_OBSERVATION_INTEGRITY_FAILED", exception)
        } ?: throw ResourceConflict(
            "ELIGIBLE_SYNC_NOT_FOUND",
            "Eligible issue sync run is unavailable",
            "A successful FULL issue sync run is required before issue snapshot creation",
        )
        validateAge(run.completedAt, transactionStartedAt, policy.maxSyncAge)
        val observations = try {
            repository.loadObservations(run)
        } catch (exception: SnapshotContentIntegrityFailure) {
            throw IssueSnapshotInvalid("SNAPSHOT_INTEGRITY_FAILED", exception)
        } catch (exception: SyncObservationIntegrityFailure) {
            throw IssueSnapshotInvalid("SYNC_OBSERVATION_INTEGRITY_FAILED", exception)
        }
        if (observations.size != run.issueCount) throw IssueSnapshotInvalid("SYNC_OBSERVATION_INTEGRITY_FAILED")

        snapshotIntegrity {
            repository.findExisting(command.releaseId, run.id, run.filterReference)
        }?.let(::result)
            ?.let { return it }

        val candidate = IssueSnapshotCandidate(
            projectId = projectId,
            releaseId = command.releaseId,
            snapshotVersion = repository.nextSnapshotVersion(command.releaseId),
            syncRunId = run.id,
            sourceId = run.sourceId,
            sourceWatermark = run.sourceWatermark,
            adapterVersion = run.adapterVersion,
            mappingVersion = run.mappingVersion,
            filterReference = run.filterReference,
            agePolicyVersion = SNAPSHOT_AGE_POLICY_VERSION,
            observations = observations,
        )
        val canonical = snapshotIntegrity { canonicalizer.canonicalize(candidate) }
        val snapshot = MaterializedIssueSnapshot(idGenerator.nextId("isnap_"), candidate, canonical, transactionStartedAt)
        try {
            repository.insert(snapshot)
        } catch (exception: SyncObservationIntegrityFailure) {
            throw IssueSnapshotInvalid("SYNC_OBSERVATION_INTEGRITY_FAILED", exception)
        } catch (exception: SnapshotContentIntegrityFailure) {
            throw IssueSnapshotInvalid("SNAPSHOT_INTEGRITY_FAILED", exception)
        }
        val payload = objectMapper.createObjectNode()
            .put("schemaVersion", 1)
            .put("snapshotId", snapshot.snapshotId)
            .put("releaseId", command.releaseId)
            .put("sourceId", command.sourceId)
            .put("syncRunId", run.id)
            .put("snapshotVersion", candidate.snapshotVersion)
            .put("selectedCount", candidate.selectedCount)
            .put("contentDigest", canonical.digest)
        governanceStore.appendAudit(
            projectId, actorId, AUDIT_ACTION, AGGREGATE_TYPE, snapshot.snapshotId,
            command.requestId, null, afterState = payload,
        )
        governanceStore.appendOutbox(OUTBOX_EVENT_TYPE, AGGREGATE_TYPE, snapshot.snapshotId, payload.deepCopy())
        val persisted = snapshotIntegrity { repository.read(snapshot.snapshotId) }
            ?: throw IssueSnapshotInvalid("SNAPSHOT_INTEGRITY_FAILED")
        if (persisted.canonical.digest != canonical.digest || !persisted.canonical.bytes.contentEquals(canonical.bytes)) {
            throw IssueSnapshotInvalid("SNAPSHOT_INTEGRITY_FAILED")
        }
        return result(persisted)
    }

    private fun validateAge(completedAt: Instant, now: Instant, maxAge: Duration) {
        if (completedAt > now || Duration.between(completedAt, now) > maxAge) {
            throw IssueSnapshotInvalid("SYNC_RUN_STALE")
        }
    }

    private fun result(snapshot: MaterializedIssueSnapshot) = CreateIssueSnapshotResult(
        snapshot.snapshotId,
        snapshot.candidate.releaseId,
        snapshot.candidate.syncRunId,
        snapshot.candidate.snapshotVersion,
        snapshot.canonical.digest,
        snapshot.candidate.selectedCount,
        snapshot.createdAt,
    )

    private inline fun <T> snapshotIntegrity(block: () -> T): T = try {
        block()
    } catch (exception: SnapshotContentIntegrityFailure) {
        throw IssueSnapshotInvalid("SNAPSHOT_INTEGRITY_FAILED", exception)
    }

    private fun notFound() = ResourceNotFound(
        "RESOURCE_NOT_FOUND",
        "Resource not found",
        "The release and issue source combination was not found",
    )

    private companion object {
        const val IDEMPOTENCY_SCOPE = "issue:snapshot:create"
        const val AUDIT_ACTION = "RELEASE_ISSUE_SNAPSHOT_CREATED"
        const val AGGREGATE_TYPE = "RELEASE_ISSUE_SNAPSHOT"
        const val OUTBOX_EVENT_TYPE = "release.issue-snapshot.created"
    }
}
