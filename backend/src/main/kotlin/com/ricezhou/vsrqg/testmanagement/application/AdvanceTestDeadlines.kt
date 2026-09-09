package com.ricezhou.vsrqg.testmanagement.application

import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.testmanagement.domain.*
import org.springframework.stereotype.Service
import org.springframework.security.access.AccessDeniedException
import org.springframework.transaction.annotation.Transactional

@Service
class AdvanceTestDeadlines(private val repository:TestRunRepository,private val lifecycle:TestRunLifecycle,
    private val clock:TimeProvider,private val access:AgentAccess) {
    fun activeRuns(afterId:String=""):List<String> = repository.activeRuns(afterId)
    @Transactional
    fun advance(runId:String) {
        val reference=repository.run(runId)
        val agent=repository.lockAgent(reference.agentId)
        val identityActive=try { access.requireAgent(agent.fingerprint,"agent:execute"); true }
            catch(_:AccessDeniedException) { false }
        val run=repository.run(runId,true)
        if(run.state.terminal) return
        val attempt=repository.attempt(runId,true); val now=clock.now()
        val reason=when {
            !now.isBefore(run.deadline) -> "RUN_DEADLINE_EXCEEDED"
            run.state==RunState.WAITING_FOR_AGENT && !now.isBefore(run.allocationDeadline) -> "ALLOCATION_DEADLINE_EXCEEDED"
            attempt.caseDeadline?.let { !now.isBefore(it) }==true -> "CASE_DEADLINE_EXCEEDED"
            attempt.recoveryDeadline?.let { !now.isBefore(it) }==true -> "RECOVERY_DEADLINE_EXCEEDED"
            attempt.leaseExpiresAt?.let { !now.isBefore(it.plusSeconds(SmokePolicy.RECOVERY_SECONDS)) }==true -> "RECOVERY_DEADLINE_EXCEEDED"
            else -> null
        }
        if(reason!=null) {
            lifecycle.finish(run,attempt,RunState.TIMEOUT,reason,run.createdBy,"deadline:"+run.id,now)
            return
        }
        if(!identityActive || !lifecycle.executionEligible(agent,run)) {
            lifecycle.finish(run,attempt,RunState.ERROR,"AGENT_IDENTITY_OR_CAPABILITY_CHANGED",run.createdBy,"deadline:"+run.id,now)
            return
        }
        if(attempt.leaseExpiresAt?.let { !now.isBefore(it) }==true) lifecycle.recovery(run,attempt,now)
    }
}
