package com.ricezhou.vsrqg.evidence.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.evidence.domain.EvidenceState
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.testmanagement.application.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
@ConditionalOnProperty(name=["vsrqg.demo.evidence.enabled"],havingValue="true")
class EvidenceUploadService(private val repository:EvidenceRepository,private val payloads:PayloadStore,
    private val attempts:AttemptAccess,private val agents:AgentAccess,private val validator:EvidenceInputValidator,
    private val idempotency:IdempotentExecutor,private val governance:GovernanceStore,private val ids:IdGenerator,
    private val clock:TimeProvider,private val mapper:ObjectMapper,
    @param:org.springframework.beans.factory.annotation.Value("\${vsrqg.demo.evidence.sensitivity:RESTRICTED}") private val sensitivity:String) {
    init { require(sensitivity in setOf("GENERAL","RESTRICTED","HIGH")) { "EVIDENCE_SENSITIVITY_INVALID" } }
    @Transactional
    fun create(fingerprint:String,body:JsonNode,key:String,requestId:String):JsonNode {
        validator.validate(body,false)
        val actor=agents.requireAgent(fingerprint,"agent:evidence:write")
        val binding=attempts.lockWritable(actor,body.path("attemptId").asText(),clock.now())
        checkDeclaration(body)
        return idempotency.execute("evidence:create",actor.principalId,key,TestJson.digest(body),JsonNode::class.java) {
            val now=clock.now(); val id=ids.nextId("upl_"); val evidence=ids.nextId("ev_")
            repository.insert(EvidenceSession(id,evidence,binding,body.deepCopy(),EvidenceState.PENDING_UPLOAD,now.plusSeconds(300),now,null,sensitivity,null,false))
            mapper.createObjectNode().put("uploadId",id).put("evidenceId",evidence)
                .put("uploadUrl","/agent-api/v1/evidence/uploads/$id/payload").put("expiresAt",now.plusSeconds(300).toString())
        }.also { if(!clock.now().isBefore(Instant.parse(it.path("expiresAt").asText()))) throw EvidenceConflict("UPLOAD_EXPIRED") }
    }
    private fun checkDeclaration(body:JsonNode) {
        val type=body.path("evidenceType").asText()
        val limit=when(type) { "LOG"->1024L*1024; "SCREENSHOT"->8L*1024*1024; else->throw EvidenceConflict("EVIDENCE_TYPE_UNSUPPORTED") }
        val media=if(type=="LOG") "text/plain" else "image/png"
        val size=body.path("sizeBytes")
        if(body.path("contentType").asText()!=media || !size.canConvertToLong() || size.asLong() !in 1..limit)
            throw EvidenceConflict("EVIDENCE_DECLARATION_INVALID")
        try { Instant.parse(body.path("capturedAt").asText()) } catch(_:java.time.format.DateTimeParseException) { throw EvidenceConflict("EVIDENCE_DECLARATION_INVALID") }
    }
    private fun locked(fingerprint:String,id:String):Pair<AgentActor,EvidenceSession> {
        val snapshot=repository.session(id)
        val actor=agents.requireAgent(fingerprint,"agent:evidence:write")
        val binding=attempts.lockWritable(actor,snapshot.binding.attemptId,clock.now())
        if(binding!=snapshot.binding) throw EvidenceConflict("STALE_LEASE")
        val session=repository.session(id,true)
        if(!clock.now().isBefore(session.expiresAt)) throw EvidenceConflict("UPLOAD_EXPIRED")
        if(session.state in setOf(EvidenceState.REJECTED,EvidenceState.EXPIRED)) throw EvidenceConflict("UPLOAD_NOT_WRITABLE")
        return actor to session
    }
    @Transactional
    fun prepare(fingerprint:String,id:String):EvidenceSession=locked(fingerprint,id).second

    @Transactional
    fun received(fingerprint:String,prepared:EvidenceSession,candidate:PayloadReceiver) {
        val (_,current)=locked(fingerprint,prepared.id)
        if(current.binding!=prepared.binding || current.expected!=prepared.expected) throw EvidenceConflict("STALE_LEASE")
        // Network reception owns no transaction. Only verified publication and the DB update hold these locks.
        candidate.finish()
        if(current.state!=EvidenceState.AVAILABLE) repository.state(current.id,EvidenceState.UPLOADING)
    }
    @Transactional(rollbackFor=[java.io.IOException::class],noRollbackFor=[EvidenceRejected::class])
    fun complete(fingerprint:String,id:String,body:JsonNode,key:String,requestId:String):JsonNode {
        validator.validate(body,true)
        val (actor,session)=locked(fingerprint,id)
        for(field in listOf("sizeBytes","payloadChecksum","contentType","capturedAt","collectorVersion")) {
            if(body.path(field)!=session.request.path(field)) throw EvidenceConflict("UPLOAD_DECLARATION_CONFLICT")
        }
        if(session.state!=EvidenceState.AVAILABLE) {
            repository.state(id,EvidenceState.VERIFYING)
            try { payloads.verify(id,session.expected); payloads.validateType(id,session.expected,session.type) }
            catch(e:EvidenceConflict) { repository.state(id,EvidenceState.REJECTED); throw EvidenceRejected(e.code) }
            locked(fingerprint,id)
        } else payloads.verify(id,session.expected)
        return idempotency.execute("evidence:complete:$id",actor.principalId,key,TestJson.digest(body),JsonNode::class.java) {
            if(session.state==EvidenceState.AVAILABLE) return@execute requireNotNull(session.metadata)
            val b=session.binding
            val metadata=mapper.createObjectNode().put("evidenceId",session.evidenceId).put("schemaVersion","1.0")
                .put("type",session.type).put("releaseId",b.releaseId).put("testRunId",b.runId).put("attemptId",b.attemptId)
                .put("deviceId",b.deviceId).put("capturedAt",session.request.path("capturedAt").asText())
                .put("collectorName","single-device-smoke").put("collectorVersion",session.request.path("collectorVersion").asText())
                .put("source","AGENT").put("sizeBytes",session.expected.size).put("payloadChecksum",session.expected.sha256)
                .put("contentType",session.request.path("contentType").asText()).put("state","AVAILABLE")
                .put("sensitivity",session.sensitivity).put("createdAt",session.createdAt.toString())
            repository.available(id,metadata)
            governance.appendAudit(b.projectId,actor.principalId,"EVIDENCE_AVAILABLE","Evidence",session.evidenceId,requestId,null,afterState=metadata)
            governance.appendOutbox("evidence.available","Evidence",session.evidenceId,metadata)
            metadata
        }
    }
}
