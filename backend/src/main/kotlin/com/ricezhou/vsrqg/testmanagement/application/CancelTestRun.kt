package com.ricezhou.vsrqg.testmanagement.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.testmanagement.domain.RunState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CancelTestRun(private val repository:TestRunRepository,private val authorizer:ProjectAuthorizer,
    private val idempotency:IdempotentExecutor,private val lifecycle:TestRunLifecycle,
    private val clock:TimeProvider,private val mapper:ObjectMapper) {
    @Transactional(rollbackFor=[Exception::class])
    fun cancel(principal:Principal,id:String,reason:String,key:String,requestId:String):JsonNode {
        require(reason.isNotBlank() && reason.length<=1000) { "Invalid cancellation reason" }
        val reference=repository.run(id)
        val operator=authorizer.require(principal,reference.projectId,Permission.TEST_EXECUTE)
        repository.lockAgent(reference.agentId)
        val run=repository.run(id,true)
        val digest=TestJson.digest(mapper.createObjectNode().put("reason",reason))
        return idempotency.execute("test:cancel:$id",operator.principalId,key,digest,JsonNode::class.java) {
            if(!run.state.terminal) lifecycle.finish(run,repository.attempt(id,true),RunState.CANCELLED,
                "CANCELLED_BY_OPERATOR",operator.principalId,requestId,clock.now(),reason)
            mapper.createObjectNode().put("testRunId",id).put("state",if(run.state.terminal) run.state.name else "CANCELLED")
        }
    }
}
