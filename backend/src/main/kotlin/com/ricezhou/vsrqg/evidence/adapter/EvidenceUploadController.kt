package com.ricezhou.vsrqg.evidence.adapter

import com.ricezhou.vsrqg.evidence.application.*
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import com.ricezhou.vsrqg.testmanagement.adapter.TestWire
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.ReadListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

@RestController
@ConditionalOnProperty(name=["vsrqg.demo.evidence.enabled"],havingValue="true")
class EvidenceUploadController(private val uploads:EvidenceUploadService,private val wire:TestWire,
    private val payloads:PayloadStore,private val problems:ProblemWriter,
    @param:Value("\${vsrqg.demo.evidence.upload-timeout:PT30S}") private val uploadTimeout:Duration) {
    init { require(uploadTimeout.toMillis() in 1..30_000) { "UPLOAD_TIMEOUT_INVALID" } }
    @PostMapping("/agent-api/v1/evidence/uploads") @ResponseStatus(HttpStatus.CREATED)
    fun create(authentication:Authentication,@RequestHeader("Idempotency-Key") key:String,request:HttpServletRequest)=
        uploads.create(authentication.name,wire.read(request,key),key,RequestIdFilter.from(request))

    @PutMapping("/agent-api/v1/evidence/uploads/{id}/payload",consumes=["application/octet-stream"])
    fun put(authentication:Authentication,@PathVariable id:String,request:HttpServletRequest,response:HttpServletResponse) {
        val fingerprint=authentication.name
        val requestId=RequestIdFilter.from(request)
        val session=uploads.prepare(fingerprint,id)
        val candidate=payloads.receive(id,session.expected)
        val async=try { request.startAsync() } catch(e:Exception) { candidate.close();throw e }
        val deadline=System.nanoTime()+uploadTimeout.toNanos()
        val ended=AtomicBoolean(false)
        val terminalLock=Any()
        fun finish(error:Throwable?) {
            synchronized(terminalLock) {
                if(ended.getAndSet(true)) return
                try {
                    if(error!=null) throw error
                    uploads.received(fingerprint,session,candidate)
                    response.status=HttpStatus.NO_CONTENT.value()
                } catch(e:Exception) {
                    val (status,code)=when(e) {
                        is UploadTimeout -> HttpStatus.REQUEST_TIMEOUT to "UPLOAD_TIMEOUT"
                        is PayloadLimitExceeded -> HttpStatus.PAYLOAD_TOO_LARGE to "PAYLOAD_LIMIT_EXCEEDED"
                        is EvidenceConflict -> HttpStatus.CONFLICT to e.code
                        is ResourceConflict -> HttpStatus.CONFLICT to e.code
                        is AccessDeniedException -> HttpStatus.FORBIDDEN to "ACCESS_DENIED"
                        is IOException -> HttpStatus.CONFLICT to "PAYLOAD_IO_ERROR"
                        else -> HttpStatus.INTERNAL_SERVER_ERROR to "INTERNAL_ERROR"
                    }
                    try { problems.write(request,response,status,code,code,code) }
                    catch(_:IOException) { logger.warn("Evidence upload response unavailable requestId={} code={}",requestId,code) }
                    if(status==HttpStatus.INTERNAL_SERVER_ERROR) logger.error("Evidence upload failed requestId={} exceptionType={}",requestId,e.javaClass.simpleName)
                } finally {
                    try { candidate.close() }
                    catch(e:Exception) { logger.error("Evidence candidate cleanup failed requestId={} exceptionType={}",requestId,e.javaClass.simpleName) }
                    finally { async.complete() }
                }
            }
        }
        async.timeout=uploadTimeout.toMillis()
        async.addListener(object:AsyncListener {
            override fun onTimeout(event:AsyncEvent)=finish(UploadTimeout())
            override fun onError(event:AsyncEvent)=finish(event.throwable?:IOException("UPLOAD_DISCONNECTED"))
            override fun onComplete(event:AsyncEvent) { candidate.close() }
            override fun onStartAsync(event:AsyncEvent) {}
        })
        val input=request.inputStream
        try {
            input.setReadListener(object:ReadListener {
                override fun onDataAvailable() {
                    try {
                        val buffer=ByteArray(64*1024)
                        while(!ended.get() && input.isReady && !input.isFinished) {
                            if(System.nanoTime()>=deadline) { finish(UploadTimeout());break }
                            val count=input.read(buffer)
                            if(count<0) break
                            if(count>0) candidate.append(buffer,count)
                        }
                    } catch(e:Exception) { finish(e) }
                }
                override fun onAllDataRead()=finish(if(System.nanoTime()>=deadline) UploadTimeout() else null)
                override fun onError(error:Throwable)=finish(error)
            })
        } catch(e:Exception) { finish(e) }
    }
    @PostMapping("/agent-api/v1/evidence/uploads/{id}:complete")
    fun complete(authentication:Authentication,@PathVariable id:String,@RequestHeader("Idempotency-Key") key:String,request:HttpServletRequest)=
        uploads.complete(authentication.name,id,wire.read(request,key),key,RequestIdFilter.from(request))

    private class UploadTimeout:RuntimeException("UPLOAD_TIMEOUT")
    companion object { private val logger=org.slf4j.LoggerFactory.getLogger(EvidenceUploadController::class.java) }
}
