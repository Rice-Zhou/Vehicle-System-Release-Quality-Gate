package com.ricezhou.vsrqg.testmanagement.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import com.ricezhou.vsrqg.testmanagement.application.*
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.concurrent.Callable

@RestController
class AgentExecutionController(private val heartbeat:HeartbeatAgent,private val claim:ClaimCommand,
    private val ack:AcknowledgeCommand,private val attempts:AttemptAccess,private val access:AgentAccess,
    private val repository:TestRunRepository,private val wire:TestWire) {
    @PostMapping("/agent-api/v1/agents/{id}:heartbeat")
    fun heartbeat(authentication:Authentication,@PathVariable id:String,@RequestHeader("Idempotency-Key") key:String,request:HttpServletRequest)=
        heartbeat.execute(authentication.name,id,wire.read(request,key,"heartbeatRequest"),key,RequestIdFilter.from(request))
    @PostMapping("/agent-api/v1/agents/{id}/commands:poll")
    fun poll(authentication:Authentication,@PathVariable id:String,@RequestHeader("Idempotency-Key") key:String,request:HttpServletRequest):Callable<JsonNode> {
        val body=wire.read(request,key,"pollRequest")
        val fingerprint=authentication.name; val requestId=RequestIdFilter.from(request)
        val actor=access.requireAgent(fingerprint,"agent:poll")
        if(actor.agentId!=id) throw AccessDeniedException("Agent path does not match identity")
        return Callable {
            // Waiting owns no transaction or row lock. The final claim reauthorizes and commits one response.
            val deadline=System.nanoTime()+minOf(body.path("waitSeconds").asLong(),20L)*1_000_000_000L
            while(repository.activeRun(id)==null && System.nanoTime()<deadline) Thread.sleep(100)
            claim.once(fingerprint,id,body,key,requestId)
        }
    }
    @PostMapping("/agent-api/v1/commands/{commandId}:ack")
    fun ack(authentication:Authentication,@PathVariable commandId:String,@RequestHeader("Idempotency-Key") key:String,request:HttpServletRequest)=
        ack.execute(authentication.name,commandId,wire.read(request,key,"ackRequest"),key,RequestIdFilter.from(request))
    @GetMapping("/agent-api/v1/attempts/{attemptId}/context")
    fun context(authentication:Authentication,@PathVariable attemptId:String)=
        attempts.context(access.requireAgent(authentication.name,"agent:execute"),attemptId)
}
