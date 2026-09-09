package com.ricezhou.vsrqg.testmanagement.application

import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.testmanagement.domain.AttemptState
import com.ricezhou.vsrqg.testmanagement.domain.RunState
import java.time.Instant

data class RunRecord(val id:String, val releaseId:String, val projectId:String, val agentId:String, val deviceId:String,
    val createdBy:String, val state:RunState, val allocationDeadline:Instant, val deadline:Instant, val startedAt:Instant?,
    val finishedAt:Instant?, val createdAt:Instant)
data class AttemptRecord(val id:String, val runId:String, val state:AttemptState, val commandId:String, val leaseId:String,
    val fencingToken:Long, val leaseExpiresAt:Instant?, val caseDeadline:Instant?, val recoveryDeadline:Instant?,
    val recoveryState:AttemptState?, val context:JsonNode, val startedAt:Instant?, val finishedAt:Instant?)
data class AgentSelection(val actor:AgentActor, val fingerprint:String, val vehicle:String?, val platform:String?,
    val capabilities:Set<String>, val registered:Boolean)
data class LockedTestManifest(val releaseId:String, val projectId:String, val manifestId:String, val digest:String,
    val vehicle:String, val platform:String, val bytes:ByteArray)
data class SmokePlan(val planVersionId:String,val caseVersionId:String,val version:Int,val definition:JsonNode)
data class NewRun(val run:RunRecord,val attempt:AttemptRecord,val manifest:LockedTestManifest,val plan:SmokePlan,
    val environmentId:String,val configBytes:ByteArray,val environment:JsonNode)

interface TestRunRepository {
    // Identity locks always precede Run -> Attempt; no execution path updates Device.
    fun lockAgent(agentId:String): AgentSelection
    fun manifest(releaseId:String): LockedTestManifest
    fun projectForRelease(releaseId:String):String
    fun plan(planId:String,version:Int):SmokePlan
    fun activeDeviceRun(deviceId:String):Boolean
    fun insert(run:NewRun)
    fun run(id:String,lock:Boolean=false):RunRecord
    fun attempt(runId:String,lock:Boolean=false):AttemptRecord
    fun runForAttempt(attemptId:String):String
    fun runForCommand(commandId:String):String
    fun activeRun(agentId:String):String?
    fun activeRuns(afterId:String):List<String>
    fun updateRun(run:RunRecord,now:Instant)
    fun updateAttempt(attempt:AttemptRecord,now:Instant)
    fun command(commandId:String):JsonNode?
    fun insertCommand(run:RunRecord,attempt:AttemptRecord,envelope:JsonNode,now:Instant)
    fun ack(commandId:String):Pair<String,JsonNode>?
    fun saveAck(commandId:String,digest:String,response:JsonNode)
    fun saveHeartbeat(agentId:String,body:JsonNode,now:Instant)
    fun insertResult(run:RunRecord,attempt:AttemptRecord,result:JsonNode,now:Instant)
    fun results(runId:String):List<JsonNode>
}
