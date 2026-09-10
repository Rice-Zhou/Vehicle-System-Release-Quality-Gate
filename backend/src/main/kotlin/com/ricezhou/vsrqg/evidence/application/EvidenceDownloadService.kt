package com.ricezhou.vsrqg.evidence.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.evidence.domain.EvidenceState
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.testmanagement.application.TestJson
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.time.Instant

data class EvidenceStream(val input:InputStream,val size:Long,val contentType:String)
@Service
@ConditionalOnProperty(name=["vsrqg.demo.evidence.enabled"],havingValue="true")
class EvidenceDownloadService(private val repository:EvidenceRepository,private val payloads:PayloadStore,
    private val authorizer:ProjectAuthorizer,private val idempotency:IdempotentExecutor,private val governance:GovernanceStore,
    private val ids:IdGenerator,private val clock:TimeProvider,private val mapper:ObjectMapper) {
    private fun authorize(principal:Principal,session:EvidenceSession,sensitiveScope:Boolean):String {
        val actor=authorizer.require(principal,session.binding.projectId,Permission.EVIDENCE_READ).principalId
        if(session.sensitivity=="HIGH") {
            if(!sensitiveScope) throw AccessDeniedException("Sensitive evidence scope required")
            authorizer.require(principal,session.binding.projectId,Permission.EVIDENCE_READ_SENSITIVE)
        }
        return actor
    }
    private fun retained(session:EvidenceSession) {
        if(session.state!=EvidenceState.AVAILABLE) throw EvidenceConflict("EVIDENCE_NOT_AVAILABLE")
        if(session.retentionUntil?.let { !clock.now().isBefore(it) }==true && !session.legalHold) throw EvidenceConflict("EVIDENCE_RETENTION_EXPIRED")
    }
    @Transactional
    fun metadata(principal:Principal,id:String):JsonNode {
        val session=repository.evidence(id)
        authorizer.require(principal,session.binding.projectId,Permission.EVIDENCE_READ)
        val response=(session.metadata?.deepCopy<ObjectNode>() ?: mapper.createObjectNode().put("evidenceId",id).put("state",session.state.name))
        if(session.state==EvidenceState.AVAILABLE) {
            val code=integrity(session)
            repository.observe(id,code,clock.now())
            response.put("integrity",code)
        }
        return response
    }
    private fun integrity(session:EvidenceSession):String = try {
        payloads.verify(session.id,session.expected); "VERIFIED"
    } catch(_:java.io.IOException) { "INTEGRITY_ERROR" } catch(_:EvidenceConflict) { "INTEGRITY_ERROR" }
    @Transactional
    fun request(principal:Principal,id:String,purpose:String,key:String,requestId:String,sensitiveScope:Boolean):JsonNode {
        if(purpose.isBlank() || purpose.length>1000) throw EvidenceConflict("DOWNLOAD_PURPOSE_INVALID")
        val session=repository.evidence(id,true); val actor=authorize(principal,session,sensitiveScope); retained(session)
        val body=mapper.createObjectNode().put("evidenceId",id).put("reason",purpose)
        val response=idempotency.execute("evidence:download",actor,key,TestJson.digest(body),JsonNode::class.java) {
            val grant=DownloadGrant(ids.nextId("dgr_"),actor,session.binding.projectId,id,purpose,clock.now().plusSeconds(60))
            repository.saveGrant(grant)
            governance.appendAudit(grant.projectId,actor,"EVIDENCE_DOWNLOAD_REQUEST","Evidence",id,requestId,purpose)
            mapper.createObjectNode().put("url","/api/v1/evidence/$id/payload?grantId=${grant.id}").put("expiresAt",grant.expiresAt.toString())
        }
        if(!clock.now().isBefore(Instant.parse(response.path("expiresAt").asText()))) throw EvidenceConflict("DOWNLOAD_GRANT_EXPIRED_NEW_KEY_REQUIRED")
        return response
    }
    @Transactional
    fun open(principal:Principal,id:String,grantId:String,requestId:String,sensitiveScope:Boolean):EvidenceStream {
        val session=repository.evidence(id,true); val actor=authorize(principal,session,sensitiveScope); retained(session)
        val grant=repository.grant(grantId)
        if(grant.actorId!=actor || grant.projectId!=session.binding.projectId || grant.evidenceId!=id || grant.purpose.isBlank())
            throw AccessDeniedException("Download grant is not owned by this principal")
        if(!clock.now().isBefore(grant.expiresAt)) throw EvidenceConflict("DOWNLOAD_GRANT_EXPIRED_NEW_KEY_REQUIRED")
        // Audit commits before the controller can read any payload byte. A database failure fails closed.
        governance.appendAudit(grant.projectId,actor,"EVIDENCE_DOWNLOAD_STARTED","Evidence",id,requestId,grant.purpose)
        val input=payloads.open(session.id,session.expected)
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                object:org.springframework.transaction.support.TransactionSynchronization {
                    override fun afterCompletion(status:Int) { if(status!=org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED) input.close() }
                })
        }
        return EvidenceStream(input,session.expected.size,session.request.path("contentType").asText())
    }
    @Transactional
    fun transferFailed(principal:Principal,id:String,grantId:String,requestId:String) {
        val session=repository.evidence(id); val actor=authorizer.require(principal,session.binding.projectId,Permission.EVIDENCE_READ).principalId
        val grant=repository.grant(grantId)
        if(grant.actorId!=actor || grant.evidenceId!=id) throw AccessDeniedException("Download grant owner mismatch")
        governance.appendAudit(session.binding.projectId,actor,"EVIDENCE_DOWNLOAD_FAILED","Evidence",id,requestId,grant.purpose)
    }
}
