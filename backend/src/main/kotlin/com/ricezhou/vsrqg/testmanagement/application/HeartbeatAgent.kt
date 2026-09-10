package com.ricezhou.vsrqg.testmanagement.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.testmanagement.domain.*
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class HeartbeatAgent(private val repository:TestRunRepository,private val access:AgentAccess,
    private val idempotency:IdempotentExecutor,private val lifecycle:TestRunLifecycle,
    private val clock:TimeProvider,private val mapper:ObjectMapper) {
    @Transactional(rollbackFor=[Exception::class])
    fun execute(fingerprint:String,agentId:String,body:JsonNode,key:String,requestId:String):JsonNode {
        val actor=access.requireAgent(fingerprint,"agent:heartbeat")
        if(actor.agentId!=agentId) throw AccessDeniedException("Agent path does not match identity")
        val commandId=body.path("currentCommandId").asText()
        val selection=if(commandId.isEmpty()) null else repository.lockAgent(agentId)
        val run=if(commandId.isEmpty()) null else repository.run(repository.runForCommand(commandId),true)
        val attempt=run?.let { lifecycle.owned(actor,it); repository.attempt(it.id,true) }
        val now=clock.now()
        if(run!=null && attempt!=null && !lifecycle.writable(run,attempt,now)) throw TestRunConflict("STALE_LEASE")
        return idempotency.execute("agent:heartbeat:$agentId",actor.principalId,key,TestJson.digest(body),JsonNode::class.java) {
            repository.saveHeartbeat(agentId,body,now)
            val response=mapper.createObjectNode().put("agentId",agentId).put("serverTime",now.toString()).put("leaseRenewed",false)
            if(run==null || attempt==null) return@execute response
            if(!lifecycle.executionEligible(checkNotNull(selection),run)) {
                lifecycle.finish(run,attempt,RunState.ERROR,"AGENT_IDENTITY_OR_CAPABILITY_CHANGED",actor.principalId,requestId,now)
                return@execute response.put("state","ERROR")
            }
            if(body.path("device").path("bootSessionId")!=attempt.context.path("environment").path("bootSessionId")) {
                lifecycle.finish(run,attempt,RunState.ERROR,"ENVIRONMENT_IDENTITY_CHANGED",actor.principalId,requestId,now)
                response.put("state","ERROR")
                return@execute response
            }
            val connected=body.path("device").path("power").asText()=="ON" &&
                body.path("device").path("connectivity").asText()=="CONNECTED" &&
                body.path("state").asText() in setOf("ONLINE","BUSY","DRAINING")
            if(!connected) {
                lifecycle.recovery(run,attempt,now)
                return@execute response.put("state","RECOVERY_PENDING")
            }
            val resumed=if(attempt.state==AttemptState.RECOVERY_PENDING) checkNotNull(attempt.recoveryState) else attempt.state
            if(attempt.recoveryDeadline!=null && !now.isBefore(attempt.recoveryDeadline)) throw TestRunConflict("STALE_LEASE")
            if(!lifecycle.acknowledged(resumed)) {
                if(attempt.state==AttemptState.RECOVERY_PENDING)
                    repository.updateAttempt(attempt.copy(state=resumed,recoveryDeadline=null,recoveryState=null),now)
                return@execute response
            }
            val expires=minOf(now.plusSeconds(SmokePolicy.LEASE_SECONDS),checkNotNull(attempt.caseDeadline),run.deadline)
            repository.updateAttempt(attempt.copy(state=resumed,leaseExpiresAt=expires,recoveryDeadline=null,recoveryState=null),now)
            response.put("leaseRenewed",true).put("leaseId",attempt.leaseId).put("fencingToken",attempt.fencingToken)
                .put("leaseExpiresAt",expires.toString())
        }
    }
}
