package com.ricezhou.vsrqg.traceability.application

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
import com.ricezhou.vsrqg.traceability.domain.LockedManifest
import com.ricezhou.vsrqg.traceability.domain.PinnedIssueSnapshot
import com.ricezhou.vsrqg.traceability.domain.TraceabilityOrdering
import com.ricezhou.vsrqg.traceability.domain.VerificationInput
import com.ricezhou.vsrqg.traceability.domain.VerificationStatus
import java.time.temporal.ChronoUnit
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class StartTraceabilityVerificationCommand(
    val principal: Principal,
    val releaseId: String,
    val issueSourceId: String,
    val idempotencyKey: String,
    val requestDigest: String,
    val requestId: String,
)

data class TraceabilityVerificationAccepted(
    val verificationRunId: String,
    val releaseId: String,
    val issueSnapshotId: String,
    val inputDigest: String,
    val statusUrl: String,
    val status: TraceabilityVerificationRunState = TraceabilityVerificationRunState.QUEUED,
)

enum class TraceabilityVerificationRunState { QUEUED }

data class TraceabilityVerificationPolicy(
    val enabled: Boolean,
    val policyVersion: String,
    val validatorVersion: String,
    val maxIssues: Int,
    val maxEdgeRevisions: Int,
)

class TraceabilityVerificationUnavailable : RuntimeException("Traceability verification is disabled")

class TraceabilityInputRejected(val code: String) : RuntimeException(code)

@Service
class StartTraceabilityVerification(
    private val policy: TraceabilityVerificationPolicy,
    private val authorizer: ProjectAuthorizer,
    private val idempotentExecutor: IdempotentExecutor,
    private val repository: TraceabilityVerificationRepository,
    private val canonicalizer: TraceabilityCanonicalizer,
    private val governanceStore: GovernanceStore,
    private val idGenerator: IdGenerator,
    private val timeProvider: TimeProvider,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun start(command: StartTraceabilityVerificationCommand): TraceabilityVerificationAccepted {
        if (!policy.enabled) throw TraceabilityVerificationUnavailable()
        val projectId = repository.findProjectId(command.releaseId, command.issueSourceId) ?: hiddenResource()
        val authorization = try {
            authorizer.require(command.principal, projectId, Permission.TRACEABILITY_VERIFY)
        } catch (_: AccessDeniedException) {
            hiddenResource()
        }
        return idempotentExecutor.execute(
            scope = IDEMPOTENCY_SCOPE,
            principalId = authorization.principalId,
            key = command.idempotencyKey,
            requestDigest = command.requestDigest,
            responseType = TraceabilityVerificationAccepted::class.java,
        ) {
            create(command, projectId, authorization.principalId)
        }
    }

    private fun create(
        command: StartTraceabilityVerificationCommand,
        authorizedProjectId: String,
        actorId: String,
    ): TraceabilityVerificationAccepted {
        val authority = repository.lockAndLoadAuthority(
            command.releaseId,
            command.issueSourceId,
            policy.maxIssues + 1,
            policy.maxEdgeRevisions + 1,
        ) ?: hiddenResource()
        if (authority.projectId != authorizedProjectId || authority.releaseId != command.releaseId) hiddenResource()
        val manifestRevisionId = authority.manifestRevisionId ?: manifestNotLocked()
        val manifestDigest = authority.manifestDigest ?: manifestNotLocked()
        if (authority.manifestState != "LOCKED") manifestNotLocked()
        val issueSnapshotId = authority.issueSnapshotId ?: hiddenResource()
        val issueSnapshotDigest = authority.issueSnapshotDigest ?: hiddenResource()
        if (authority.issueSnapshotCanonicalizationVersion != ISSUE_SNAPSHOT_CANONICALIZATION_VERSION) {
            reject("TRACEABILITY_INPUT_NOT_VALID")
        }
        val declaredIssueCount = authority.declaredIssueCount ?: reject("TRACEABILITY_INPUT_NOT_VALID")
        if (declaredIssueCount > policy.maxIssues || authority.issues.size > policy.maxIssues) {
            reject("TRACEABILITY_ISSUE_LIMIT_EXCEEDED")
        }
        if (authority.issues.size != declaredIssueCount) reject("TRACEABILITY_INPUT_NOT_VALID")
        if (authority.edges.size > policy.maxEdgeRevisions) reject("TRACEABILITY_INPUT_LIMIT_EXCEEDED")
        if (authority.edges.any { it.verificationStatus != VerificationStatus.VALID }) {
            reject("TRACEABILITY_INPUT_NOT_VALID")
        }

        val input = try {
            VerificationInput(
                schemaVersion = INPUT_SCHEMA_VERSION,
                policyVersion = policy.policyVersion,
                validatorVersion = policy.validatorVersion,
                projectId = authority.projectId,
                releaseId = authority.releaseId,
                issueSnapshot = PinnedIssueSnapshot(
                    authority.projectId,
                    authority.releaseId,
                    issueSnapshotId,
                    issueSnapshotDigest,
                    authority.issues,
                ),
                manifest = LockedManifest(
                    authority.projectId,
                    authority.releaseId,
                    manifestRevisionId,
                    manifestDigest,
                ),
                edgeRevisions = authority.edges,
            )
        } catch (failure: TraceabilityVerificationFailure) {
            reject(failure.diagnosticCode)
        }
        val inputDigest = try {
            canonicalizer.canonicalizeInput(input).digest
        } catch (failure: TraceabilityVerificationFailure) {
            reject(failure.diagnosticCode)
        }
        val now = timeProvider.now().truncatedTo(ChronoUnit.MICROS)
        val runId = idGenerator.nextId("trv_")
        repository.insertRun(
            TraceabilityVerificationRunRecord(
                id = runId,
                projectId = authority.projectId,
                releaseId = authority.releaseId,
                issueSnapshotId = issueSnapshotId,
                manifestRevisionId = manifestRevisionId,
                policyVersion = policy.policyVersion,
                validatorVersion = policy.validatorVersion,
                inputDigest = inputDigest,
                inputEdgeCount = input.edgeRevisions.size,
                requestedBy = actorId,
                requestId = command.requestId,
                createdAt = now,
            ),
        )
        val orderedEdges = input.edgeRevisions.sortedWith(TraceabilityOrdering.inputEdgeOrder)
        repository.insertInputLedger(runId, authority.projectId, orderedEdges, now)
        appendGovernance(
            runId,
            command,
            authority.projectId,
            actorId,
            issueSnapshotId,
            manifestRevisionId,
            manifestDigest,
            inputDigest,
            orderedEdges.size,
        )
        repository.insertJob(
            jobId = idGenerator.nextId("job_"),
            projectId = authority.projectId,
            runId = runId,
            payload = objectMapper.createObjectNode().put("verificationRunId", runId),
            createdAt = now,
        )
        val accepted = TraceabilityVerificationAccepted(
            verificationRunId = runId,
            releaseId = authority.releaseId,
            issueSnapshotId = issueSnapshotId,
            inputDigest = inputDigest,
            statusUrl = "$RUN_STATUS_PATH/$runId",
        )
        return accepted
    }

    private fun appendGovernance(
        runId: String,
        command: StartTraceabilityVerificationCommand,
        projectId: String,
        actorId: String,
        issueSnapshotId: String,
        manifestRevisionId: String,
        manifestDigest: String,
        inputDigest: String,
        inputEdgeCount: Int,
    ) {
        val metadata = objectMapper.createObjectNode()
            .put("schemaVersion", 1)
            .put("verificationRunId", runId)
            .put("releaseId", command.releaseId)
            .put("issueSnapshotId", issueSnapshotId)
            .put("manifestRevisionId", manifestRevisionId)
            .put("manifestDigest", manifestDigest)
            .put("policyVersion", policy.policyVersion)
            .put("validatorVersion", policy.validatorVersion)
            .put("inputDigest", inputDigest)
            .put("inputEdgeCount", inputEdgeCount)
            .put("status", "QUEUED")
        governanceStore.appendAudit(
            projectId = projectId,
            actorId = actorId,
            action = AUDIT_ACTION,
            resourceType = AGGREGATE_TYPE,
            resourceId = runId,
            requestId = command.requestId,
            reason = null,
            afterState = metadata,
        )
        governanceStore.appendOutbox(
            eventType = OUTBOX_EVENT,
            aggregateType = AGGREGATE_TYPE,
            aggregateId = runId,
            payload = metadata.deepCopy(),
        )
    }

    private fun manifestNotLocked(): Nothing = throw ResourceConflict(
        code = "RELEASE_MANIFEST_NOT_LOCKED",
        resourceTitle = "Release Manifest is not locked",
        detail = "A locked Release Manifest is required before traceability verification",
    )

    private fun hiddenResource(): Nothing = throw ResourceNotFound(
        code = "RESOURCE_NOT_FOUND",
        resourceTitle = "Resource not found",
        detail = "The requested resource was not found",
    )

    private fun reject(code: String): Nothing = throw TraceabilityInputRejected(code)

    private companion object {
        const val INPUT_SCHEMA_VERSION = "m2.5-traceability-input/v1"
        const val ISSUE_SNAPSHOT_CANONICALIZATION_VERSION = "release-issue-snapshot-jcs/v1"
        const val IDEMPOTENCY_SCOPE = "traceability:verify"
        const val AUDIT_ACTION = "TRACEABILITY_VERIFICATION_QUEUED"
        const val OUTBOX_EVENT = "traceability.verification.queued"
        const val AGGREGATE_TYPE = "TRACEABILITY_VERIFICATION_RUN"
        const val RUN_STATUS_PATH = "/api/v1/traceability-verification-runs"
    }
}
