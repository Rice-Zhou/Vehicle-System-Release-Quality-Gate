package com.ricezhou.vsrqg.testmanagement.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.testmanagement.domain.*
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Service
class TestRunLifecycle(private val repository:TestRunRepository,private val governance:GovernanceStore,
    private val mapper:ObjectMapper,private val access:AgentAccess):AttemptAccess {
    fun executionEligible(agent:AgentSelection,run:RunRecord):Boolean {
        val manifest=repository.manifest(run.releaseId)
        return agent.registered && agent.vehicle==manifest.vehicle && agent.platform==manifest.platform &&
            agent.capabilities.containsAll(SmokePolicy.capabilities)
    }
    fun acknowledged(state:AttemptState):Boolean = state in setOf(AttemptState.ACKED,AttemptState.RUNNING,AttemptState.UPLOADING)
    fun owned(actor:AgentActor,run:RunRecord) {
        if(actor.projectId!=run.projectId || actor.agentId!=run.agentId || actor.deviceId!=run.deviceId)
            throw AccessDeniedException("Attempt is not assigned to this Agent")
    }
    fun writable(run:RunRecord,attempt:AttemptRecord,now:Instant):Boolean =
        attempt.leaseExpiresAt?.let {
            LeaseWindow.writable(now,it,attempt.fencingToken,attempt.fencingToken,run.state.terminal || attempt.state.terminal)
        }==true && now.isBefore(run.deadline) && attempt.caseDeadline?.let(now::isBefore)==true &&
            (run.state!=RunState.WAITING_FOR_AGENT || now.isBefore(run.allocationDeadline))

    // Evidence/Result writers must already own a transaction, otherwise the lock would end before their write.
    @Transactional(propagation=Propagation.MANDATORY)
    override fun lockWritable(actor:AgentActor,attemptId:String,now:Instant):AttemptBinding {
        val selection=repository.lockAgent(actor.agentId)
        if(access.requireAgent(selection.fingerprint,"agent:execute")!=actor)
            throw AccessDeniedException("Agent binding is no longer active")
        val run=repository.run(repository.runForAttempt(attemptId),true)
        owned(actor,run)
        val attempt=repository.attempt(run.id,true)
        if(!writable(run,attempt,now) || !acknowledged(attempt.state) || !executionEligible(selection,run))
            throw TestRunConflict("STALE_LEASE")
        return AttemptBinding(attempt.id,run.id,run.releaseId,run.projectId,run.agentId,run.deviceId,attempt.leaseId,attempt.fencingToken)
    }
    @Transactional(readOnly=true)
    override fun context(actor:AgentActor,attemptId:String):JsonNode {
        val run=repository.run(repository.runForAttempt(attemptId))
        owned(actor,run)
        val attempt=repository.attempt(run.id)
        if(repository.command(attempt.commandId)==null) throw AccessDeniedException("Attempt has not been dispatched")
        return attempt.context.deepCopy()
    }
    fun finish(run:RunRecord,attempt:AttemptRecord,state:RunState,reason:String,actorId:String,requestId:String,now:Instant,
        operatorReason:String?=null) {
        if(run.state.terminal) return
        check(!attempt.state.terminal) { "Active Run has terminal Attempt without completion" }
        val attemptState=when(state) {
            RunState.CANCELLED -> AttemptState.CANCELLED
            RunState.TIMEOUT -> AttemptState.TIMEOUT
            RunState.ERROR -> AttemptState.ERROR
            else -> error("Unsupported server terminal state")
        }
        val status=when(state) { RunState.CANCELLED -> "BLOCKED"; RunState.TIMEOUT -> "TIMEOUT"; else -> "ERROR" }
        val result=mapper.createObjectNode().put("attemptId",attempt.id).put("testRunId",run.id).put("releaseId",run.releaseId)
            .put("origin","SERVER").put("status",status).put("reasonCode",reason).put("attemptNo",1)
            .put("caseId",attempt.context.path("case").path("caseId").asText())
            .put("caseVersion",attempt.context.path("case").path("version").asInt())
            .put("agentId",run.agentId).put("deviceId",run.deviceId).put("finishedAt",now.toString())
        if(attempt.startedAt==null) { result.putNull("startedAt"); result.putNull("durationMs") }
        else { result.put("startedAt",attempt.startedAt.toString()); result.put("durationMs",Duration.between(attempt.startedAt,now).toMillis()) }
        result.putArray("evidenceIds")
        val requirements=result.putArray("evidenceRequirements")
        attempt.context.path("case").path("requiredEvidence").forEach {
            requirements.addObject().put("type",it.asText()).put("state","FAILED").put("reasonCode",reason)
        }
        repository.updateAttempt(attempt.copy(state=attemptState,fencingToken=attempt.fencingToken+1,finishedAt=now),now)
        repository.insertResult(run,attempt,result,now)
        repository.updateRun(run.copy(state=state,finishedAt=now),now)
        governance.appendAudit(run.projectId,actorId,"TEST_RUN_"+state.name,"TEST_RUN",run.id,requestId,operatorReason ?: reason,afterState=result)
        governance.appendOutbox("test.run.terminal","TEST_RUN",run.id,result)
    }
    fun recovery(run:RunRecord,attempt:AttemptRecord,now:Instant) {
        if(attempt.state==AttemptState.RECOVERY_PENDING) return
        val origin=if(attempt.leaseExpiresAt!=null && !now.isBefore(attempt.leaseExpiresAt)) attempt.leaseExpiresAt else now
        val deadline=minOf(origin.plusSeconds(SmokePolicy.RECOVERY_SECONDS),attempt.caseDeadline ?: run.deadline,run.deadline)
        repository.updateAttempt(attempt.copy(state=AttemptState.RECOVERY_PENDING,recoveryState=attempt.state,recoveryDeadline=deadline),now)
    }
}
