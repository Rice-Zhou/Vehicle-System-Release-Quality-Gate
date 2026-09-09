package com.ricezhou.vsrqg.testmanagement.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.testmanagement.domain.*
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ClaimCommand(private val repository:TestRunRepository,private val access:AgentAccess,
    private val idempotency:IdempotentExecutor,private val governance:GovernanceStore,
    private val lifecycle:TestRunLifecycle,private val clock:TimeProvider,private val mapper:ObjectMapper) {
    @Transactional
    fun once(fingerprint:String,agentId:String,body:JsonNode,key:String,requestId:String):JsonNode {
        val actor=access.requireAgent(fingerprint,"agent:poll")
        if(actor.agentId!=agentId) throw AccessDeniedException("Agent path does not match identity")
        val selected=repository.lockAgent(actor.agentId)
        return idempotency.execute("agent:poll:$agentId",actor.principalId,key,TestJson.digest(body),JsonNode::class.java) {
            val runId=repository.activeRun(actor.agentId) ?: return@execute NullNode.instance
            val run=repository.run(runId,true); val attempt=repository.attempt(runId,true); val now=clock.now()
            if(run.state.terminal || !now.isBefore(run.deadline) ||
                (run.state==RunState.WAITING_FOR_AGENT && !now.isBefore(run.allocationDeadline))) return@execute NullNode.instance
            if(attempt.state!=AttemptState.QUEUED) {
                return@execute if(attempt.state==AttemptState.DISPATCHED && lifecycle.writable(run,attempt,now))
                    checkNotNull(repository.command(attempt.commandId)) else NullNode.instance
            }
            if(!selected.capabilities.containsAll(SmokePolicy.capabilities)) throw TestRunConflict("AGENT_CAPABILITY_MISMATCH")
            val deadline=minOf(now.plusSeconds(SmokePolicy.CASE_SECONDS),run.deadline)
            val updated=attempt.copy(state=AttemptState.DISPATCHED,leaseExpiresAt=minOf(now.plusSeconds(SmokePolicy.LEASE_SECONDS),deadline),caseDeadline=deadline)
            repository.updateAttempt(updated,now)
            val envelope=mapper.createObjectNode().put("protocolVersion","1.0").put("commandId",attempt.commandId)
                .put("attemptId",attempt.id).put("commandType","EXECUTE_TEST_CASE").put("issuedAt",now.toString())
                .put("deadline",deadline.toString()).put("leaseDurationSeconds",90).put("idempotencyKey",attempt.id+":execute")
                .put("payloadSchemaVersion","1.0")
            val fixed=attempt.context.path("case")
            val payload=mapper.createObjectNode().put("caseId",fixed.path("caseId").asText())
                .put("caseVersion",fixed.path("version").asInt()).put("timeoutMs",fixed.path("timeoutMs").asInt())
            payload.set<JsonNode>("requiredEvidence",fixed.path("requiredEvidence"))
            envelope.set<JsonNode>("payload",payload)
            repository.insertCommand(run,updated,envelope,now)
            governance.appendAudit(run.projectId,actor.principalId,"COMMAND_DISPATCHED","TEST_RUN",run.id,requestId,null)
            governance.appendOutbox("test.command.dispatched","TEST_RUN",run.id,envelope)
            envelope
        }
    }
}
