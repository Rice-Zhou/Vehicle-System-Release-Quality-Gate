package com.ricezhou.vsrqg.testmanagement.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.testmanagement.domain.AttemptState
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AppendCommandEvent(private val repository:TestRunRepository,private val agents:AgentAccess,
    private val attempts:AttemptAccess,private val lifecycle:TestRunLifecycle,private val validator:TestInputValidator,
    private val idempotency:IdempotentExecutor,private val governance:GovernanceStore,
    private val conflicts:TestMessageConflicts,private val clock:TimeProvider,private val mapper:ObjectMapper) {
    @Transactional(rollbackFor=[Exception::class])
    fun append(actor:AgentActor,request:JsonNode,idempotencyKey:String,requestId:String):JsonNode {
        validator.validateAgent(request,"eventRequest")
        val agent=repository.lockAgent(actor.agentId)
        if(agents.requireAgent(agent.fingerprint,"agent:execute")!=actor) throw AccessDeniedException("Agent binding is no longer active")
        val run=repository.run(repository.runForAttempt(request.path("attemptId").asText()),true)
        lifecycle.owned(actor,run)
        val attempt=repository.attempts(run.id,true).single { it.id==request.path("attemptId").asText() }
        val digest=TestJson.digest(request)
        fun conflict(code:String):Nothing { conflicts.record(actor,run.id,digest,code,requestId);throw TestRunConflict(code) }
        if(attempt.commandId!=request.path("commandId").asText()) conflict("LATE_EVENT_CONFLICT")
        val sequence=request.path("sequenceNo").asLong()
        val previous=repository.event(attempt.commandId,sequence)
        if(previous!=null) {
            if(TestJson.digest(previous)!=digest) conflict("LATE_EVENT_CONFLICT")
            return receipt(previous,digest)
        }
        if(run.state.terminal || attempt.state.terminal) conflict("LATE_EVENT_CONFLICT")
        val now=clock.now()
        val binding=try { attempts.lockWritable(actor,attempt.id,now) }
            catch(error:TestRunConflict) { if(error.code=="STALE_LEASE") conflict(error.code);throw error }
        if(request.path("leaseId").asText()!=binding.leaseId || request.path("fencingToken").asLong()!=binding.fencingToken) conflict("STALE_LEASE")
        val stream=repository.events(attempt.commandId)
        if(sequence!=stream.size.toLong()+1 || stream.withIndex().any { it.value.path("sequenceNo").asLong()!=it.index.toLong()+1 })
            conflict("LATE_EVENT_CONFLICT")
        val type=request.path("eventType").asText()
        if(type=="STARTED" && attempt.state!=AttemptState.ACKED) conflict("LATE_EVENT_CONFLICT")
        return idempotency.execute("agent:event:"+attempt.commandId,actor.principalId,idempotencyKey,digest,JsonNode::class.java) {
            repository.insertEvent(request,digest,now)
            if(type=="STARTED") repository.updateAttempt(attempt.copy(state=AttemptState.RUNNING,startedAt=now),now)
            if(type in setOf("DEVICE_UNREACHABLE","RECOVERY_PENDING")) lifecycle.recovery(run,attempt,now)
            val receipt=receipt(request,digest)
            governance.appendAudit(run.projectId,actor.principalId,"COMMAND_EVENT_ACCEPTED","TEST_RUN",run.id,requestId,null,afterState=receipt)
            governance.appendOutbox("test.command.event","TEST_RUN",run.id,receipt)
            receipt
        }
    }
    private fun receipt(request:JsonNode,digest:String):JsonNode=mapper.createObjectNode()
        .put("commandId",request.path("commandId").asText()).put("attemptId",request.path("attemptId").asText())
        .put("sequenceNo",request.path("sequenceNo").asLong()).put("eventDigest",digest).put("accepted",true)
}
