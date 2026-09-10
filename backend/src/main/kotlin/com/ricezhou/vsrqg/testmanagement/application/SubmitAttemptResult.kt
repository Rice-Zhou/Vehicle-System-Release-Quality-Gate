package com.ricezhou.vsrqg.testmanagement.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.testmanagement.domain.*
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Service
class SubmitAttemptResult(private val repository:TestRunRepository,private val agents:AgentAccess,
    private val attempts:AttemptAccess,private val evidence:AttemptEvidence,private val lifecycle:TestRunLifecycle,
    private val validator:TestInputValidator,private val idempotency:IdempotentExecutor,
    private val governance:GovernanceStore,private val conflicts:TestMessageConflicts,
    private val clock:TimeProvider,private val mapper:ObjectMapper) {
    @Transactional(rollbackFor=[Exception::class])
    fun submit(actor:AgentActor,request:JsonNode,idempotencyKey:String,requestId:String):JsonNode {
        validator.validateAgent(request,"resultRequest")
        val digest=ResultCanonicalizer.digest(request)
        if(digest!=request.path("resultDigest").asText()) throw TestRunConflict("RESULT_DIGEST_MISMATCH")
        val agent=repository.lockAgent(actor.agentId)
        if(agents.requireAgent(agent.fingerprint,"agent:execute")!=actor) throw AccessDeniedException("Agent binding is no longer active")
        val run=repository.run(repository.runForAttempt(request.path("attemptId").asText()),true)
        lifecycle.owned(actor,run)
        val attempt=repository.attempts(run.id,true).single { it.id==request.path("attemptId").asText() }
        fun conflict(code:String):Nothing { conflicts.record(actor,run.id,digest,code,requestId);throw TestRunConflict(code) }
        val previous=repository.results(run.id).singleOrNull { it.path("attemptId").asText()==attempt.id }
        if(previous!=null) {
            if(previous.path("origin").asText()!="AGENT" || previous.path("resultDigest").asText()!=digest) conflict("LATE_EVENT_CONFLICT")
            return previous
        }
        if(run.state.terminal || attempt.state.terminal) conflict("LATE_EVENT_CONFLICT")
        val now=clock.now()
        val binding=try { attempts.lockWritable(actor,attempt.id,now) }
            catch(error:TestRunConflict) { if(error.code=="STALE_LEASE") conflict(error.code);throw error }
        if(binding.leaseId!=request.path("leaseId").asText() || binding.fencingToken!=request.path("fencingToken").asLong()) conflict("STALE_LEASE")
        if(repository.command(attempt.commandId)==null) conflict("LATE_EVENT_CONFLICT")
        val stream=repository.events(attempt.commandId)
        if(stream.withIndex().any { (index,event)->event.path("sequenceNo").asLong()!=index.toLong()+1 ||
            event.path("attemptId").asText()!=attempt.id || event.path("commandId").asText()!=attempt.commandId ||
            event.path("leaseId").asText()!=binding.leaseId || event.path("fencingToken").asLong()!=binding.fencingToken }) conflict("LATE_EVENT_CONFLICT")
        val status=request.path("status").asText()
        if(status=="SKIPPED") throw TestRunConflict("PLAN_SKIP_NOT_DEFINED")
        val started=Instant.parse(request.path("startedAt").asText());val finished=Instant.parse(request.path("finishedAt").asText())
        if(finished.isBefore(started)) throw TestRunConflict("RESULT_TIME_INVALID")
        if(status!="PASS" && !request.has("reasonCode")) throw TestRunConflict("RESULT_REASON_REQUIRED")
        val ids=request.path("evidenceIds").map(JsonNode::asText).toSet()
        val resolution=evidence.resolve(binding,ids)
        if(status=="PASS" && (resolution.failedRequiredTypes.isNotEmpty() || resolution.availableIds!=ids))
            throw TestRunConflict("REQUIRED_EVIDENCE_MISSING")
        return idempotency.execute("agent:result:"+attempt.id,actor.principalId,idempotencyKey,digest,JsonNode::class.java) {
            val result=request.deepCopy<ObjectNode>().put("testRunId",run.id).put("releaseId",run.releaseId).put("origin","AGENT")
                .put("caseId",attempt.context.path("case").path("caseId").asText()).put("caseVersion",attempt.context.path("case").path("version").asInt())
                .put("attemptNo",1).put("agentId",actor.agentId).put("deviceId",actor.deviceId).put("durationMs",Duration.between(started,finished).toMillis())
            result.putArray("evidenceRequirements").also { requirements->
                attempt.context.path("case").path("requiredEvidence").forEach { type->
                    val requirement=requirements.addObject().put("type",type.asText())
                    if(type.asText() in resolution.failedRequiredTypes) requirement.put("state","FAILED").put("reasonCode","REQUIRED_EVIDENCE_UNAVAILABLE")
                    else requirement.put("state","AVAILABLE")
                }
            }
            evidence.seal(binding,now)
            val terminal=when(status) { "ERROR"->AttemptState.ERROR;"TIMEOUT"->AttemptState.TIMEOUT;else->AttemptState.COMPLETED }
            repository.updateAttempt(attempt.copy(state=terminal,fencingToken=Math.addExact(attempt.fencingToken,1),finishedAt=now),now)
            repository.insertResult(run,attempt,result,now)
            governance.appendAudit(run.projectId,actor.principalId,"ATTEMPT_RESULT_ACCEPTED","TEST_RUN",run.id,requestId,null,afterState=result)
            governance.appendOutbox("test.attempt.result","TEST_RUN",run.id,result)
            lifecycle.complete(run,actor.principalId,requestId,now)
            result
        }
    }
}
