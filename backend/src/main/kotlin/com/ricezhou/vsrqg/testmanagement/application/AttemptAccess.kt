package com.ricezhou.vsrqg.testmanagement.application

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

data class AttemptBinding(val attemptId:String,val runId:String,val releaseId:String,val projectId:String,
    val agentId:String,val deviceId:String,val leaseId:String,val fencingToken:Long)
interface AttemptAccess {
    // The caller transaction retains Run/Attempt locks through the dependent write.
    // Consumers must compare supplied leaseId/fencingToken with the returned binding.
    fun lockWritable(actor:AgentActor,attemptId:String,now:Instant):AttemptBinding
    fun context(actor:AgentActor,attemptId:String):JsonNode
}
