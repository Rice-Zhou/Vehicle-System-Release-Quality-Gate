package com.ricezhou.vsrqg.traceability.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.adapter.toJdbcTimestamp
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceConflictRecorder
import java.time.Instant
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class JdbcBuildProvenanceConflictRecorder(
    private val jdbc: JdbcClient,
    private val idGenerator: IdGenerator,
    private val governanceStore: GovernanceStore,
    private val objectMapper: ObjectMapper,
) : BuildProvenanceConflictRecorder {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun record(
        acceptedReceiptId: String,
        projectId: String,
        actorId: String,
        rejectedEnvelopeDigest: String,
        requestId: String,
        attemptedAt: Instant,
    ) {
        val rejectedReceiptId = idGenerator.nextId("bprj_")
        val inserted = jdbc.sql(
            """
            INSERT INTO build_provenance_rejected_receipt(
              id, project_id, accepted_receipt_id, rejected_envelope_digest,
              diagnostic_code, actor_id, attempted_at
            ) VALUES (
              :id, :projectId, :acceptedReceiptId, :rejectedEnvelopeDigest,
              :diagnosticCode, :actorId, :attemptedAt
            )
            ON CONFLICT (accepted_receipt_id, rejected_envelope_digest) DO NOTHING
            """.trimIndent(),
        )
            .param("id", rejectedReceiptId)
            .param("projectId", projectId)
            .param("acceptedReceiptId", acceptedReceiptId)
            .param("rejectedEnvelopeDigest", rejectedEnvelopeDigest)
            .param("diagnosticCode", DIAGNOSTIC_CODE)
            .param("actorId", actorId)
            .param("attemptedAt", attemptedAt.toJdbcTimestamp())
            .update()
        val persisted = jdbc.sql(
            """
            SELECT id, project_id, accepted_receipt_id,
                   rejected_envelope_digest, diagnostic_code
            FROM build_provenance_rejected_receipt
            WHERE accepted_receipt_id = :acceptedReceiptId
              AND rejected_envelope_digest = :rejectedEnvelopeDigest
            """.trimIndent(),
        )
            .param("acceptedReceiptId", acceptedReceiptId)
            .param("rejectedEnvelopeDigest", rejectedEnvelopeDigest)
            .query { rs, _ ->
                PersistedRejection(
                    id = rs.getString("id"),
                    projectId = rs.getString("project_id"),
                    acceptedReceiptId = rs.getString("accepted_receipt_id"),
                    rejectedEnvelopeDigest = rs.getString("rejected_envelope_digest"),
                    diagnosticCode = rs.getString("diagnostic_code"),
                )
            }
            .optional()
            .orElseThrow {
                DataIntegrityViolationException("Build provenance rejection insert did not resolve authority")
            }
        if (!persisted.matches(projectId, acceptedReceiptId, rejectedEnvelopeDigest)) {
            throw DataIntegrityViolationException("Build provenance rejection authority conflict")
        }
        if (inserted == 0) return
        if (persisted.id != rejectedReceiptId) {
            throw DataIntegrityViolationException("Build provenance rejection identity conflict")
        }

        governanceStore.appendAudit(
            projectId = projectId,
            actorId = actorId,
            action = "BUILD_PROVENANCE_REJECTED",
            resourceType = "BUILD_PROVENANCE_REJECTED_RECEIPT",
            resourceId = rejectedReceiptId,
            requestId = requestId,
            reason = DIAGNOSTIC_CODE,
            afterState = objectMapper.createObjectNode()
                .put("schemaVersion", 1)
                .put("acceptedReceiptId", acceptedReceiptId)
                .put("rejectedEnvelopeDigest", rejectedEnvelopeDigest)
                .put("diagnosticCode", DIAGNOSTIC_CODE),
        )
    }

    private data class PersistedRejection(
        val id: String,
        val projectId: String,
        val acceptedReceiptId: String,
        val rejectedEnvelopeDigest: String,
        val diagnosticCode: String,
    ) {
        fun matches(
            expectedProjectId: String,
            expectedAcceptedReceiptId: String,
            expectedRejectedEnvelopeDigest: String,
        ): Boolean =
            projectId == expectedProjectId &&
                acceptedReceiptId == expectedAcceptedReceiptId &&
                rejectedEnvelopeDigest == expectedRejectedEnvelopeDigest &&
                diagnosticCode == DIAGNOSTIC_CODE
    }

    private companion object {
        const val DIAGNOSTIC_CODE = "BUILD_PROVENANCE_CONFLICT"
    }
}
