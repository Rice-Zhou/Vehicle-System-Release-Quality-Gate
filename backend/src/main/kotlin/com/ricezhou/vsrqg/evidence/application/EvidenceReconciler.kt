package com.ricezhou.vsrqg.evidence.application

import com.ricezhou.vsrqg.evidence.domain.EvidenceState
import com.ricezhou.vsrqg.shared.time.TimeProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class EvidenceInventoryItem(val evidenceId:String,val uploadId:String,val payload:StoredPayload,val metadataDigest:String)
data class EvidenceDiagnostic(val evidenceId:String,val uploadId:String,val code:String)
@Service
@ConditionalOnProperty(name=["vsrqg.demo.evidence.enabled"],havingValue="true")
class EvidenceReconciler(private val repository:EvidenceRepository,private val payloads:PayloadStore,private val clock:TimeProvider) {
    private fun integrity(session:EvidenceSession):Boolean=try { payloads.verify(session.id,session.expected); true }
        catch(_:java.io.IOException) { false } catch(_:EvidenceConflict) { false }
    /** Operator supplies a bounded list of database identities; unknown files are never enumerated or deleted. */
    @Transactional
    fun reconcile(evidenceIds:Set<String>):List<EvidenceDiagnostic> {
        require(evidenceIds.size in 1..1000)
        return repository.inventory(evidenceIds).map { session->
            val code=if(integrity(session)) {
                if(session.state==EvidenceState.AVAILABLE) "VERIFIED" else "ORPHAN_RETRY_SESSION"
            } else if(session.state==EvidenceState.AVAILABLE) "INTEGRITY_ERROR" else "PAYLOAD_NOT_READY"
            repository.observe(session.evidenceId,code,clock.now())
            EvidenceDiagnostic(session.evidenceId,session.id,code)
        }
    }
    @Transactional(readOnly=true)
    fun backupInventory(evidenceIds:Set<String>):List<EvidenceInventoryItem> {
        require(evidenceIds.size in 1..1000)
        return repository.inventory(evidenceIds).map { session->
            if(session.state!=EvidenceState.AVAILABLE || !integrity(session)) throw EvidenceConflict("BACKUP_PAIR_INCOMPLETE")
            EvidenceInventoryItem(session.evidenceId,session.id,session.expected,
                com.ricezhou.vsrqg.testmanagement.application.TestJson.digest(requireNotNull(session.metadata)))
        }
    }
    @Transactional
    fun verifyRestored(inventory:List<EvidenceInventoryItem>):List<EvidenceDiagnostic> {
        require(inventory.size in 1..1000 && inventory.map { it.evidenceId }.toSet().size==inventory.size)
        return inventory.map { item->
            val session=repository.evidence(item.evidenceId)
            val matched=session.state==EvidenceState.AVAILABLE && session.id==item.uploadId && session.expected==item.payload &&
                com.ricezhou.vsrqg.testmanagement.application.TestJson.digest(requireNotNull(session.metadata))==item.metadataDigest && integrity(session)
            val code=if(matched) "VERIFIED" else "INTEGRITY_ERROR"
            repository.observe(item.evidenceId,code,clock.now())
            EvidenceDiagnostic(item.evidenceId,item.uploadId,code)
        }
    }
}
