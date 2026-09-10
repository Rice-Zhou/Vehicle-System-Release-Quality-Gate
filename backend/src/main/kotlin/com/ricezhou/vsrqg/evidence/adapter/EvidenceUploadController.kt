package com.ricezhou.vsrqg.evidence.adapter

import com.ricezhou.vsrqg.evidence.application.EvidenceUploadService
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import com.ricezhou.vsrqg.testmanagement.adapter.TestWire
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@ConditionalOnProperty(name=["vsrqg.demo.evidence.enabled"],havingValue="true")
class EvidenceUploadController(private val uploads:EvidenceUploadService,private val wire:TestWire) {
    @PostMapping("/agent-api/v1/evidence/uploads") @ResponseStatus(HttpStatus.CREATED)
    fun create(authentication:Authentication,@RequestHeader("Idempotency-Key") key:String,request:HttpServletRequest)=
        uploads.create(authentication.name,wire.read(request,key),key,RequestIdFilter.from(request))
    @PutMapping("/agent-api/v1/evidence/uploads/{id}/payload",consumes=["application/octet-stream"]) @ResponseStatus(HttpStatus.NO_CONTENT)
    fun put(authentication:Authentication,@PathVariable id:String,request:HttpServletRequest)=uploads.put(authentication.name,id,request.inputStream)
    @PostMapping("/agent-api/v1/evidence/uploads/{id}:complete")
    fun complete(authentication:Authentication,@PathVariable id:String,@RequestHeader("Idempotency-Key") key:String,request:HttpServletRequest)=
        uploads.complete(authentication.name,id,wire.read(request,key),key,RequestIdFilter.from(request))
}
