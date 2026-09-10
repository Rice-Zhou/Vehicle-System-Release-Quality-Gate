package com.ricezhou.vsrqg.agent

import java.nio.file.Path
import java.time.Instant

data class EvidenceCandidate(val type:String,val mediaType:String,val size:Long,val checksum:String,val capturedAt:Instant,val collectorVersion:String,val localFile:Path)
data class CollectorDescriptor(val type:String,val version:String,val capabilities:Set<String>,val schemaVersions:Set<String>)
data class CollectorContext(val attemptId:String,val directory:Path)
data class CollectorSession(val attemptId:String)
data class CollectionWindow(val startedAt:Instant,val finishedAt:Instant)
data class CollectorSummary(val capturedCount:Int)
data class CollectorHealth(val available:Boolean,val reasonCode:String?)
interface CollectorPlugin {
    fun descriptor():CollectorDescriptor
    fun start(context:CollectorContext,config:Map<String,String>):CollectorSession
    fun mark(testCaseContext:String)
    fun collect(trigger:String,timeWindow:CollectionWindow):List<EvidenceCandidate>
    fun stop():CollectorSummary
    fun health():CollectorHealth
}
abstract class FileCollector(private val type:String,private val mediaType:String,private val name:String,private val limit:Int):CollectorPlugin {
    protected var context:CollectorContext?=null
    private var captured=0
    override fun descriptor()=CollectorDescriptor(type,"1.0",setOf(type),setOf("1.0"))
    override fun start(context:CollectorContext,config:Map<String,String>):CollectorSession {
        ensure(this.context==null && config.isEmpty(),"COLLECTOR_STATE_INVALID");SmokeAssertions.attempt(context.attemptId)
        SafeFiles.directory(context.directory);this.context=context;return CollectorSession(context.attemptId)
    }
    override fun mark(testCaseContext:String) {ensure(testCaseContext==context?.attemptId,"COLLECTOR_ATTEMPT_MISMATCH")}
    protected abstract fun bytes(window:CollectionWindow):ByteArray
    override fun collect(trigger:String,timeWindow:CollectionWindow):List<EvidenceCandidate> {
        ensure(trigger=="CASE_FINISHED" && !timeWindow.finishedAt.isBefore(timeWindow.startedAt),"COLLECTOR_TRIGGER_INVALID")
        val current=context ?: throw AgentFailure("COLLECTOR_NOT_STARTED")
        val bytes=bytes(timeWindow);ensure(bytes.isNotEmpty() && bytes.size<=limit,"COLLECTOR_SIZE_LIMIT")
        val path=current.directory.resolve(name);SafeFiles.atomic(path,bytes);captured++
        return listOf(EvidenceCandidate(type,mediaType,bytes.size.toLong(),Wire.sha256(bytes),timeWindow.finishedAt,"1.0",path))
    }
    override fun stop():CollectorSummary {context=null;return CollectorSummary(captured)}
    override fun health()=CollectorHealth(context!=null,if(context==null) "COLLECTOR_NOT_STARTED" else null)
}
