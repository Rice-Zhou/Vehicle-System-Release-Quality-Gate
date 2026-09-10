package com.ricezhou.vsrqg.evidence.application

import com.ricezhou.vsrqg.evidence.domain.EvidenceState
import com.ricezhou.vsrqg.testmanagement.application.AttemptBinding
import com.ricezhou.vsrqg.testmanagement.application.AttemptEvidence
import com.ricezhou.vsrqg.testmanagement.application.EvidenceResolution
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ResolveAttemptEvidence(private val repository:EvidenceRepository,private val payloads:ObjectProvider<PayloadStore>):AttemptEvidence {
    @Transactional(propagation=Propagation.MANDATORY)
    override fun resolve(binding:AttemptBinding,evidenceIds:Set<String>):EvidenceResolution {
        val store=payloads.ifAvailable ?: throw EvidenceConflict("EVIDENCE_STORAGE_DISABLED")
        val sessions=repository.sessions(binding.attemptId)
        val requested=evidenceIds.map { id->sessions.singleOrNull { it.evidenceId==id && it.binding==binding }
            ?: throw EvidenceConflict("EVIDENCE_ATTEMPT_MISMATCH") }
        val available=requested.filter { session->
            session.state==EvidenceState.AVAILABLE && try { store.verify(session.id,session.expected);true }
                catch(_:java.io.IOException) { false } catch(_:EvidenceConflict) { false }
        }
        return EvidenceResolution(available.map { it.evidenceId }.toSet(),setOf("LOG","SCREENSHOT")-available.map { it.type }.toSet())
    }
    @Transactional(propagation=Propagation.MANDATORY)
    override fun seal(binding:AttemptBinding,now:Instant) {
        repository.sessions(binding.attemptId).forEach { session->
            if(session.binding!=binding) throw EvidenceConflict("EVIDENCE_ATTEMPT_MISMATCH")
            if(session.state !in setOf(EvidenceState.AVAILABLE,EvidenceState.REJECTED,EvidenceState.EXPIRED))
                repository.state(session.id,EvidenceState.EXPIRED)
        }
    }
}
