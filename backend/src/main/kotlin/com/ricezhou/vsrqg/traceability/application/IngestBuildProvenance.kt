package com.ricezhou.vsrqg.traceability.application

import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.traceability.domain.BuildProvenanceEnvelope
import com.ricezhou.vsrqg.traceability.domain.CanonicalBuildProvenance
import org.springframework.stereotype.Service

data class IngestBuildProvenanceCommand(
    val principal: Principal,
    val tokenProjectReference: String?,
    val envelope: BuildProvenanceEnvelope,
    val idempotencyKey: String,
    val requestId: String,
)

data class PreparedBuildProvenance(
    val authorization: TraceabilityIngestAuthorization,
    val provenance: CanonicalBuildProvenance,
    val idempotencyKey: String,
    val requestId: String,
)

data class BuildProvenanceIngestionPolicy(val enabled: Boolean)

class BuildProvenanceConflict(
    val acceptedReceiptId: String,
    val key: BuildAttemptKey,
    val rejectedEnvelopeDigest: String,
) : ResourceConflict(
    code = "BUILD_PROVENANCE_CONFLICT",
    resourceTitle = "Build provenance conflict",
    detail = "The build attempt already has a different accepted provenance envelope",
)

@Service
class IngestBuildProvenance(
    private val policy: BuildProvenanceIngestionPolicy,
    private val canonicalizer: BuildProvenanceCanonicalizer,
    private val authorizer: TraceabilityIngestAuthorizer,
    private val transaction: BuildProvenanceTransaction,
    private val conflictRecorder: BuildProvenanceConflictRecorder,
    private val timeProvider: TimeProvider,
) {
    fun ingest(command: IngestBuildProvenanceCommand): BuildProvenanceResult {
        if (!policy.enabled) {
            throw ResourceNotFound(
                code = "RESOURCE_NOT_FOUND",
                resourceTitle = "Resource not found",
                detail = "The requested resource was not found",
            )
        }
        val provenance = canonicalizer.canonicalize(command.envelope)
        val authorization = authorizer.require(
            command.principal,
            command.tokenProjectReference,
            provenance.normalized.projectReference,
        )
        val prepared = PreparedBuildProvenance(
            authorization = authorization,
            provenance = provenance,
            idempotencyKey = command.idempotencyKey,
            requestId = command.requestId,
        )
        try {
            return transaction.execute(prepared)
        } catch (conflict: BuildProvenanceConflict) {
            conflictRecorder.record(
                acceptedReceiptId = conflict.acceptedReceiptId,
                projectId = authorization.projectId,
                actorId = authorization.principalId,
                rejectedEnvelopeDigest = conflict.rejectedEnvelopeDigest,
                requestId = command.requestId,
                attemptedAt = timeProvider.now(),
            )
            throw conflict
        }
    }
}
