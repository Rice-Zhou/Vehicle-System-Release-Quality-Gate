package com.ricezhou.vsrqg.testmanagement.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.testmanagement.domain.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AcknowledgeCommand(private val repository:TestRunRepository,private val access:AgentAccess,
    private val idempotency:IdempotentExecutor,private val lifecycle:TestRunLifecycle,
    private val governance:GovernanceStore,private val clock:TimeProvider,private val mapper:ObjectMapper) {
    @Transactional
    fun execute(fingerprint:String,commandId:String,body:JsonNode,key:String,requestId:String):JsonNode {
        val actor=access.requireAgent(fingerprint,"agent:execute")
        val run=repository.run(repository.runForCommand(commandId),true)
        lifecycle.owned(actor,run)
        val attempt=repository.attempt(run.id,true); val now=clock.now(); val digest=TestJson.digest(body)
        val previous=repository.ack(commandId)
        if(previous!=null && previous.first!=digest) throw TestRunConflict("ACK_CONFLICT")
        // A rejected ACK is an immutable receipt, never execution authority.
        if(previous!=null && previous.second.path("status").asText()=="REJECTED") return previous.second
        if(!lifecycle.writable(run,attempt,now)) throw TestRunConflict("STALE_LEASE")
        return idempotency.execute("agent:ack:$commandId",actor.principalId,key,digest,JsonNode::class.java) {
            if(previous!=null) return@execute previous.second
            val response=mapper.createObjectNode().put("commandId",commandId).put("attemptId",attempt.id)
                .put("status",body.path("status").asText())
            if(body.path("status").asText()=="REJECTED") {
                lifecycle.finish(run,attempt,RunState.ERROR,body.path("reasonCode").asText(),actor.principalId,requestId,now)
            } else {
                if(attempt.state!=AttemptState.DISPATCHED) throw TestRunConflict("ACK_CONFLICT")
                repository.updateAttempt(attempt.copy(state=AttemptState.ACKED),now)
                repository.updateRun(run.copy(state=RunState.RUNNING,startedAt=now),now)
                response.put("leaseId",attempt.leaseId).put("fencingToken",attempt.fencingToken)
                    .put("leaseExpiresAt",attempt.leaseExpiresAt.toString())
                governance.appendAudit(run.projectId,actor.principalId,"COMMAND_ACKED","TEST_RUN",run.id,requestId,null)
                governance.appendOutbox("test.command.acked","TEST_RUN",run.id,response)
            }
            repository.saveAck(commandId,digest,response)
            response
        }
    }
}
