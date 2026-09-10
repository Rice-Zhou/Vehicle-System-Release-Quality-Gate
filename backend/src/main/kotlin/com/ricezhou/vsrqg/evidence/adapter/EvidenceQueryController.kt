package com.ricezhou.vsrqg.evidence.adapter

import com.ricezhou.vsrqg.access.application.AuthenticatedPrincipalResolver
import com.ricezhou.vsrqg.evidence.application.*
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import com.ricezhou.vsrqg.testmanagement.adapter.TestWire
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@ConditionalOnProperty(name=["vsrqg.demo.evidence.enabled"],havingValue="true")
class EvidenceQueryController(private val downloads:EvidenceDownloadService,private val principals:AuthenticatedPrincipalResolver,private val wire:TestWire) {
    @GetMapping("/api/v1/evidence/{evidenceId}") @PreAuthorize("hasAuthority('SCOPE_evidence:read')")
    fun metadata(@AuthenticationPrincipal jwt:Jwt,@PathVariable evidenceId:String)=downloads.metadata(principal(jwt),evidenceId)
    @PostMapping("/api/v1/evidence/{evidenceId}:download") @PreAuthorize("hasAuthority('SCOPE_evidence:read')")
    fun request(@AuthenticationPrincipal jwt:Jwt,authentication:Authentication,@PathVariable evidenceId:String,
        @RequestHeader("Idempotency-Key") key:String,request:HttpServletRequest,response:HttpServletResponse):Any {
        response.setHeader("Cache-Control","no-store")
        val body=wire.read(request,key)
        if(!body.isObject || body.size()!=1 || !body.path("reason").isTextual) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST")
        return downloads.request(principal(jwt),evidenceId,body.path("reason").asText(),key,RequestIdFilter.from(request),sensitive(authentication))
    }
    @GetMapping("/api/v1/evidence/{evidenceId}/payload") @PreAuthorize("hasAuthority('SCOPE_evidence:read')")
    fun payload(@AuthenticationPrincipal jwt:Jwt,authentication:Authentication,@PathVariable evidenceId:String,
        @RequestParam grantId:String,request:HttpServletRequest,response:HttpServletResponse) {
        if(request.getHeader("Range")!=null) throw ResponseStatusException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,"RANGE_UNSUPPORTED")
        val principal=principal(jwt); val requestId=RequestIdFilter.from(request)
        val stream=downloads.open(principal,evidenceId,grantId,requestId,sensitive(authentication))
        response.setHeader("Cache-Control","no-store"); response.setHeader("X-Content-Type-Options","nosniff")
        response.setHeader("Content-Disposition","attachment; filename=\"evidence.bin\"")
        response.contentType=stream.contentType; response.setContentLengthLong(stream.size)
        try { stream.input.use { it.copyTo(response.outputStream,64*1024) } }
        catch(e:java.io.IOException) { downloads.transferFailed(principal,evidenceId,grantId,requestId); throw e }
    }
    private fun principal(jwt:Jwt)=principals.resolve(jwt.issuer?.toString(),jwt.subject,jwt.getClaimAsString("principal_type"))
    private fun sensitive(authentication:Authentication)=authentication.authorities.any { it.authority=="SCOPE_evidence:read:sensitive" }
}

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=[EvidenceUploadController::class,EvidenceQueryController::class])
class EvidenceProblemHandler(private val problems:com.ricezhou.vsrqg.shared.problem.ProblemWriter) {
    private fun problem(request:HttpServletRequest,status:Int,code:String)=org.springframework.http.ResponseEntity.status(status)
        .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
        .body(problems.problem(request,HttpStatus.valueOf(status),code,code,code))
    @ExceptionHandler(PayloadLimitExceeded::class)
    fun limit(request:HttpServletRequest)=problem(request,413,"PAYLOAD_LIMIT_EXCEEDED")
    @ExceptionHandler(EvidenceNotFound::class)
    fun missing(request:HttpServletRequest)=problem(request,404,"EVIDENCE_NOT_FOUND")
    @ExceptionHandler(java.io.IOException::class)
    fun io(request:HttpServletRequest)=problem(request,409,"PAYLOAD_IO_ERROR")
    @ExceptionHandler(ResponseStatusException::class)
    fun invalid(e:ResponseStatusException,request:HttpServletRequest)=problem(request,e.statusCode.value(),
        when(e.statusCode.value()) { 413->"PAYLOAD_TOO_LARGE"; 415->"UNSUPPORTED_MEDIA_TYPE";416->"RANGE_UNSUPPORTED";else->"INVALID_REQUEST" })
}

// Evidence port failures can cross module/controller boundaries; keep one mapping for this exception type.
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class EvidenceConflictProblemHandler(private val problems:com.ricezhou.vsrqg.shared.problem.ProblemWriter) {
    @ExceptionHandler(EvidenceConflict::class)
    fun conflict(e:EvidenceConflict,request:HttpServletRequest):Any {
        val status=if(e.code=="INVALID_REQUEST") HttpStatus.BAD_REQUEST else HttpStatus.CONFLICT
        return org.springframework.http.ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
            .body(problems.problem(request,status,e.code,e.code,e.code))
    }
}
