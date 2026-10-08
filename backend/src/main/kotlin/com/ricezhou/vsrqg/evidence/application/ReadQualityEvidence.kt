package com.ricezhou.vsrqg.evidence.application

import com.ricezhou.vsrqg.evidence.domain.EvidenceState
import com.ricezhou.vsrqg.testmanagement.application.AttemptBinding
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

data class QualityEvidenceItem(
    val evidenceId: String,
    val uploadId: String,
    val binding: AttemptBinding,
    val type: String,
    val payload: StoredPayload,
    val metadataDigest: String,
)

interface QualityEvidenceSource {
    fun pin(ids: Set<String>, projectId: String, releaseId: String, runId: String, attemptId: String): List<QualityEvidenceItem>
    fun verify(items: List<QualityEvidenceItem>)
}

@Service
class ReadQualityEvidence(
    private val repository: EvidenceRepository,
    private val payloads: ObjectProvider<PayloadStore>,
) : QualityEvidenceSource {
    override fun pin(
        ids: Set<String>, projectId: String, releaseId: String, runId: String, attemptId: String,
    ): List<QualityEvidenceItem> {
        if (ids.isEmpty()) return emptyList()
        val sessions = repository.inventory(ids)
        if (sessions.size != ids.size || sessions.map { it.evidenceId }.toSet() != ids) {
            throw EvidenceConflict("QUALITY_EVIDENCE_NOT_FOUND")
        }
        return sessions.sortedBy(EvidenceSession::evidenceId).map { session ->
            val binding = session.binding
            if (binding.projectId != projectId || binding.releaseId != releaseId ||
                binding.runId != runId || binding.attemptId != attemptId) {
                throw EvidenceConflict("QUALITY_EVIDENCE_BINDING_MISMATCH")
            }
            if (session.state != EvidenceState.AVAILABLE || session.metadata == null) {
                throw EvidenceConflict("QUALITY_EVIDENCE_UNAVAILABLE")
            }
            QualityEvidenceItem(
                session.evidenceId, session.id, binding, session.type, session.expected,
                com.ricezhou.vsrqg.testmanagement.application.TestJson.digest(session.metadata),
            )
        }
    }

    override fun verify(items: List<QualityEvidenceItem>) {
        if (items.isEmpty()) return
        val store = payloads.ifAvailable ?: throw EvidenceConflict("EVIDENCE_STORAGE_DISABLED")
        items.forEach { pinned ->
            val session = repository.evidence(pinned.evidenceId)
            if (session.id != pinned.uploadId || session.binding != pinned.binding ||
                session.state != EvidenceState.AVAILABLE || session.type != pinned.type ||
                session.expected != pinned.payload || session.metadata == null ||
                com.ricezhou.vsrqg.testmanagement.application.TestJson.digest(session.metadata) != pinned.metadataDigest) {
                throw EvidenceConflict("QUALITY_EVIDENCE_CHANGED")
            }
            try {
                if (store.verify(session.id, pinned.payload) != pinned.payload) {
                    throw EvidenceConflict("QUALITY_EVIDENCE_INTEGRITY_ERROR")
                }
            } catch (failure: java.io.IOException) {
                throw EvidenceConflict("QUALITY_EVIDENCE_INTEGRITY_ERROR").also { it.initCause(failure) }
            }
        }
    }
}