package com.ricezhou.vsrqg.testmanagement.adapter

import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import com.ricezhou.vsrqg.testmanagement.application.*
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
class AgentResultController(private val results:SubmitAttemptResult,private val events:AppendCommandEvent,
    private val access:AgentAccess,private val wire:TestWire) {
    @PutMapping("/agent-api/v1/attempts/{attemptId}/result")
    fun result(authentication:Authentication,@PathVariable attemptId:String,@RequestHeader("Idempotency-Key") key:String,request:HttpServletRequest):Any {
        val body=wire.read(request,key,"resultRequest")
        if(body.path("attemptId").asText()!=attemptId) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST")
        return results.submit(access.requireAgent(authentication.name,"agent:execute"),body,key,RequestIdFilter.from(request))
    }
    @PostMapping("/agent-api/v1/commands/{commandId}/events")
    fun event(authentication:Authentication,@PathVariable commandId:String,@RequestHeader("Idempotency-Key") key:String,request:HttpServletRequest):Any {
        val body=wire.read(request,key,"eventRequest")
        if(body.path("commandId").asText()!=commandId) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST")
        return events.append(access.requireAgent(authentication.name,"agent:execute"),body,key,RequestIdFilter.from(request))
    }
}
