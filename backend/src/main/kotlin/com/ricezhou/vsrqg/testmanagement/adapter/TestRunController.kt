package com.ricezhou.vsrqg.testmanagement.adapter

import com.ricezhou.vsrqg.access.application.AuthenticatedPrincipalResolver
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import com.ricezhou.vsrqg.testmanagement.application.CreateTestRun
import com.ricezhou.vsrqg.testmanagement.application.CancelTestRun
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
class TestRunController(private val create:CreateTestRun,private val cancel:CancelTestRun,
    private val principals:AuthenticatedPrincipalResolver,private val wire:TestWire) {
    @PostMapping("/api/v1/test-runs")
    @PreAuthorize("hasAuthority('SCOPE_test:execute')")
    fun create(@AuthenticationPrincipal jwt:Jwt,@RequestHeader("Idempotency-Key") key:String,request:HttpServletRequest)=
        ResponseEntity.status(HttpStatus.CREATED).body(create.create(principal(jwt),wire.read(request,key),key,RequestIdFilter.from(request)))
    @PostMapping("/api/v1/test-runs/{id}:cancel")
    @PreAuthorize("hasAuthority('SCOPE_test:execute')")
    fun cancel(@AuthenticationPrincipal jwt:Jwt,@PathVariable id:String,@RequestHeader("Idempotency-Key") key:String,request:HttpServletRequest):Any {
        val body=wire.read(request,key)
        if(!body.isObject || body.size()!=1 || !body.path("reason").isTextual || body.path("reason").asText().isBlank() ||
            body.path("reason").asText().length>1000) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST")
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(cancel.cancel(principal(jwt),id,body.path("reason").asText(),key,RequestIdFilter.from(request)))
    }
    @GetMapping("/api/v1/test-runs/{id}/results")
    @PreAuthorize("hasAuthority('SCOPE_test:read')")
    fun results(@AuthenticationPrincipal jwt:Jwt,@PathVariable id:String)=cancel.results(principal(jwt),id)
    private fun principal(jwt:Jwt)=principals.resolve(jwt.issuer?.toString(),jwt.subject,jwt.getClaimAsString("principal_type"))
}
