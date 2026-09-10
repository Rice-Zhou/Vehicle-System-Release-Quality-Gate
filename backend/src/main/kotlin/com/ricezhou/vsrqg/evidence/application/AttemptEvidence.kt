package com.ricezhou.vsrqg.evidence.application

import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.evidence.domain.EvidenceState
import com.ricezhou.vsrqg.testmanagement.application.AttemptBinding
import java.io.InputStream
import java.time.Instant

data class EvidenceResolution(val availableIds:Set<String>,val failedRequiredTypes:Set<String>)
interface AttemptEvidence {
    fun resolve(binding:AttemptBinding,evidenceIds:Set<String>):EvidenceResolution
    fun seal(binding:AttemptBinding,now:Instant)
}
data class StoredPayload(val size:Long,val sha256:String)
interface PayloadStore {
    fun write(sessionId:String,input:InputStream,limit:Long):StoredPayload
    fun verify(sessionId:String,expected:StoredPayload):StoredPayload
    fun validateType(sessionId:String,expected:StoredPayload,type:String)
    fun open(sessionId:String,expected:StoredPayload):InputStream
}
open class EvidenceConflict(val code:String):RuntimeException(code)
class EvidenceRejected(code:String):EvidenceConflict(code)
class EvidenceNotFound:RuntimeException("EVIDENCE_NOT_FOUND")
interface EvidenceInputValidator { fun validate(body:JsonNode,complete:Boolean) }
data class EvidenceSession(val id:String,val evidenceId:String,val binding:AttemptBinding,val request:JsonNode,
    val state:EvidenceState,val expiresAt:Instant,val createdAt:Instant,val metadata:JsonNode?,
    val sensitivity:String,val retentionUntil:Instant?,val legalHold:Boolean) {
    val expected get()=StoredPayload(request.path("sizeBytes").asLong(),request.path("payloadChecksum").asText())
    val type get()=request.path("evidenceType").asText()
}
data class DownloadGrant(val id:String,val actorId:String,val projectId:String,val evidenceId:String,val purpose:String,val expiresAt:Instant)
interface EvidenceRepository {
    fun session(id:String,lock:Boolean=false):EvidenceSession
    fun evidence(id:String,lock:Boolean=false):EvidenceSession
    fun insert(session:EvidenceSession)
    fun state(id:String,state:EvidenceState)
    fun available(id:String,metadata:JsonNode)
    fun sessions(attemptId:String):List<EvidenceSession>
    fun inventory(ids:Set<String>):List<EvidenceSession>
    fun observe(id:String,code:String,now:Instant)
    fun latestObservation(id:String):String?
    fun saveGrant(grant:DownloadGrant)
    fun grant(id:String):DownloadGrant
}
