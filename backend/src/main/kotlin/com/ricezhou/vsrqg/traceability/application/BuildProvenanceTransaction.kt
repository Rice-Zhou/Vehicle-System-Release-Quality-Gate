package com.ricezhou.vsrqg.traceability.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.traceability.domain.TraceabilityEdgeType
import java.time.temporal.ChronoUnit
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BuildProvenanceTransaction(
    private val idempotentExecutor: IdempotentExecutor,
    private val repository: BuildProvenanceRepository,
    private val validator: BuildProvenanceValidatorPort,
    private val governanceStore: GovernanceStore,
    private val idGenerator: IdGenerator,
    private val timeProvider: TimeProvider,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun execute(prepared: PreparedBuildProvenance): BuildProvenanceResult =
        idempotentExecutor.execute(
            scope = IDEMPOTENCY_SCOPE,
            principalId = prepared.authorization.principalId,
            key = prepared.idempotencyKey,
            requestDigest = prepared.provenance.envelopeDigest,
            responseType = BuildProvenanceResult::class.java,
        ) {
            ingestNewOrReplay(prepared)
        }

    private fun ingestNewOrReplay(prepared: PreparedBuildProvenance): BuildProvenanceResult {
        val envelope = prepared.provenance.normalized
        val authorization = prepared.authorization
        val context = repository.lockContext(
            envelope.projectReference,
            envelope.releaseIssueSnapshotId,
        ) ?: hiddenResource()
        requireContextAuthority(context, authorization)

        val key = BuildAttemptKey(
            projectId = authorization.projectId,
            provider = envelope.provider,
            pipeline = envelope.pipeline,
            buildId = envelope.buildId,
            buildAttempt = envelope.buildAttempt,
        )
        repository.findReceipt(key)?.let { accepted ->
            if (accepted.envelopeDigest == prepared.provenance.envelopeDigest) {
                return accepted.result
            }
            throw BuildProvenanceConflict(
                acceptedReceiptId = accepted.receiptId,
                key = key,
                rejectedEnvelopeDigest = prepared.provenance.envelopeDigest,
            )
        }

        val issues = repository.resolveSnapshotIssues(context, envelope.sourceIssueIds)
        val now = timeProvider.now().truncatedTo(ChronoUnit.MICROS)
        val commit = repository.resolveCommit(
            projectId = authorization.projectId,
            repository = envelope.repository,
            sourceRevision = envelope.sourceRevision,
            now = now,
        )
        val build = repository.resolveBuild(
            projectId = authorization.projectId,
            key = key,
            repository = envelope.repository,
            sourceRevision = envelope.sourceRevision,
            now = now,
        )
        val artifacts = repository.resolveArtifacts(authorization.projectId, envelope.artifactSha256s)
        val validation = validator.validate(prepared.provenance)
        val revisions = repository.appendRevisions(
            edgeCandidates(
                projectId = authorization.projectId,
                commit = commit,
                build = build,
                issues = issues,
                artifacts = artifacts,
                sourceReference = envelope.workflowReference,
                proofReference = envelope.proofReference,
                proofDigest = envelope.proofDigest,
            ),
            validation,
            now,
        )

        val receiptId = idGenerator.nextId("bpr_")
        val result = BuildProvenanceResult(
            receiptId = receiptId,
            releaseIssueSnapshotId = context.snapshotId,
            sourceCommitId = commit.commitId,
            buildRecordId = build.buildRecordId,
            envelopeDigest = prepared.provenance.envelopeDigest,
            validatorVersion = validation.validatorVersion,
            verificationStatus = validation.verificationStatus,
            confidence = validation.confidence,
            edgeRevisions = revisions,
        )
        val receipt = BuildProvenanceReceipt(
            receiptId = receiptId,
            key = key,
            envelopeDigest = prepared.provenance.envelopeDigest,
            result = result,
            issueCount = issues.size,
            artifactCount = artifacts.size,
            actorId = authorization.principalId,
            createdAt = now,
        )
        repository.insertReceipt(receipt)
        appendGovernance(prepared, receipt)
        val persisted = repository.readReceipt(receiptId)
            ?: throw DataIntegrityViolationException("Build provenance receipt read-back failed")
        if (persisted != receipt) {
            throw DataIntegrityViolationException("Build provenance receipt read-back did not match the committed model")
        }
        return persisted.result
    }

    private fun edgeCandidates(
        projectId: String,
        commit: CommitEndpoint,
        build: BuildEndpoint,
        issues: List<IssueEndpoint>,
        artifacts: List<ArtifactEndpoint>,
        sourceReference: String,
        proofReference: String,
        proofDigest: String,
    ): List<EdgeCandidate> = buildList {
        issues.forEach { issue ->
            add(
                candidate(
                    projectId,
                    TraceabilityEdgeType.ISSUE_COMMIT,
                    issue.issueId,
                    commit.commitId,
                    sourceReference,
                    proofReference,
                    proofDigest,
                ),
            )
        }
        add(
            candidate(
                projectId,
                TraceabilityEdgeType.COMMIT_BUILD,
                commit.commitId,
                build.buildRecordId,
                sourceReference,
                proofReference,
                proofDigest,
            ),
        )
        artifacts.forEach { artifact ->
            add(
                candidate(
                    projectId,
                    TraceabilityEdgeType.BUILD_ARTIFACT,
                    build.buildRecordId,
                    artifact.artifactId,
                    sourceReference,
                    proofReference,
                    proofDigest,
                ),
            )
        }
    }

    private fun candidate(
        projectId: String,
        edgeType: TraceabilityEdgeType,
        fromEntityId: String,
        toEntityId: String,
        sourceReference: String,
        proofReference: String,
        proofDigest: String,
    ) = EdgeCandidate(
        projectId = projectId,
        edgeType = edgeType,
        fromEntityId = fromEntityId,
        toEntityId = toEntityId,
        sourceType = SOURCE_TYPE,
        sourceReference = sourceReference,
        proofReference = proofReference,
        proofDigest = proofDigest,
    )

    private fun appendGovernance(
        prepared: PreparedBuildProvenance,
        receipt: BuildProvenanceReceipt,
    ) {
        val result = receipt.result
        val metadata = objectMapper.createObjectNode()
            .put("schemaVersion", 1)
            .put("receiptId", receipt.receiptId)
            .put("envelopeDigest", receipt.envelopeDigest)
            .put("validatorVersion", result.validatorVersion)
            .put("issueCount", receipt.issueCount)
            .put("artifactCount", receipt.artifactCount)
            .put("edgeCount", result.edgeRevisions.size)
        governanceStore.appendAudit(
            projectId = receipt.key.projectId,
            actorId = receipt.actorId,
            action = "BUILD_PROVENANCE_INGESTED",
            resourceType = "BUILD_PROVENANCE_RECEIPT",
            resourceId = receipt.receiptId,
            requestId = prepared.requestId,
            reason = null,
            afterState = metadata,
        )
        governanceStore.appendOutbox(
            eventType = INGESTED_EVENT,
            aggregateType = "BUILD_PROVENANCE_RECEIPT",
            aggregateId = receipt.receiptId,
            payload = metadata.deepCopy(),
        )
    }

    private fun requireContextAuthority(
        context: BuildProvenanceContext,
        authorization: TraceabilityIngestAuthorization,
    ) {
        if (
            context.projectId != authorization.projectId ||
            context.projectReference != authorization.projectReference ||
            !DIGEST.matches(context.snapshotDigest)
        ) {
            throw DataIntegrityViolationException("Release issue snapshot authority did not match ingestion authority")
        }
    }

    private fun hiddenResource(): Nothing = throw ResourceNotFound(
        code = "RESOURCE_NOT_FOUND",
        resourceTitle = "Resource not found",
        detail = "The requested resource was not found",
    )

    private companion object {
        const val IDEMPOTENCY_SCOPE = "traceability:ingest"
        const val SOURCE_TYPE = "GITHUB_ACTIONS"
        const val INGESTED_EVENT = "traceability.build-provenance.ingested"
        val DIGEST = Regex("^sha256:[0-9a-f]{64}$")
    }
}
