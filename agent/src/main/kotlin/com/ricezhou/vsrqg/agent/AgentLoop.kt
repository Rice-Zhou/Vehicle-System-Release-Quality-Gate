package com.ricezhou.vsrqg.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class Phase { RECEIVED, ACKED, INSTALL_INTENT, INSTALLED, LAUNCH_INTENT, OBSERVED, UPLOADED, RESULT_ACKED }
enum class RecoveryAction { START, WAIT_FOR_DEADLINE, REPORT_ONLY, DIAGNOSTICS_ONLY, DONE }
object RecoveryPolicy {
    fun decide(phase:Phase, leaseValid:Boolean, sameBoot:Boolean):RecoveryAction = when {
        phase==Phase.RESULT_ACKED -> RecoveryAction.DONE
        !leaseValid || !sameBoot -> RecoveryAction.DIAGNOSTICS_ONLY
        phase in setOf(Phase.INSTALL_INTENT,Phase.LAUNCH_INTENT) -> RecoveryAction.WAIT_FOR_DEADLINE
        phase in setOf(Phase.OBSERVED,Phase.UPLOADED) -> RecoveryAction.REPORT_ONLY
        else -> RecoveryAction.START
    }
}

class LeaseGuard(private val nanoTime:()->Long=System::nanoTime) {
    @Volatile private var bound=false
    @Volatile private var denied=false
    @Volatile private var expires=0L
    @Volatile private var serverBase:Instant?=null
    @Volatile private var tickBase=0L
    fun anchor(serverTime:Instant,requestStarted:Long) {serverBase=serverTime;tickBase=requestStarted}
    fun now():Instant=checkNotNull(serverBase) {"SERVER_TIME_UNAVAILABLE"}.plusNanos((nanoTime()-tickBase).coerceAtLeast(0))
    fun renew(response:JsonNode,entry:JournalEntry,deadline:Instant,requestStarted:Long) {
        ensure(!denied && response.path("leaseRenewed").asBoolean() && response.path("leaseId").asText()==entry.leaseId && response.path("fencingToken").asLong()==entry.fencingToken,"LEASE_LOST")
        val serverTime=Instant.parse(response.path("serverTime").asText())
        val leaseEnd=minOf(Instant.parse(response.path("leaseExpiresAt").asText()),deadline)
        val remaining=Duration.between(serverTime,leaseEnd)
        ensure(remaining>Duration.ZERO && remaining<=Duration.ofSeconds(90),"LEASE_INVALID")
        bound=true;expires=requestStarted+remaining.toNanos();anchor(serverTime,requestStarted)
        ensure(valid(),"LEASE_LOST")
    }
    fun valid()=bound && !denied && nanoTime()<expires
    fun allowsProcess()=!denied && (!bound || valid())
    fun requireValid()=ensure(valid(),"LEASE_LOST")
    fun invalidate() {denied=true}
    fun idle() {bound=false;denied=false}
}

class AgentLoop(private val client:AgentClient,private val journal:ExecutionJournal,private val device:SmokeDevice,
    private val deviceId:String,private val lease:LeaseGuard) {
    private var agentId:String?=null
    private val uptimeStarted=System.nanoTime()
    @Volatile private var current:JournalEntry?=null
    private var deadline:Instant?=null
    fun run() {while(!Thread.currentThread().isInterrupted) runOnce()}
    fun runOnce() {
        register()
        val pending=journal.entries().filter {it.phase!=Phase.RESULT_ACKED}
        ensure(pending.size<=1,"MULTIPLE_PENDING_ATTEMPTS")
        val saved=pending.singleOrNull()
        if(saved?.phase==Phase.UPLOADED) {replayResult(saved);return}
        lease.idle()
        val command=if(saved!=null) journal.read(saved.attemptId,"command.json") else {
            heartbeat(null)
            val poll=Wire.message("COMMAND_POLL").put("maxCommands",1).put("waitSeconds",20)
            client.call("POST","/agent-api/v1/agents/${checkNotNull(agentId)}/commands:poll",poll,key())
        }
        if(command.isNull) return
        Wire.validate(command,"commandEnvelope")
        val attempt=SmokeAssertions.attempt(command.path("attemptId").asText());val commandId=Wire.id(command.path("commandId").asText())
        deadline=Instant.parse(command.path("deadline").asText())
        val context=if(saved!=null) journal.read(attempt,"context.json") else client.call("GET","/agent-api/v1/attempts/$attempt/context",null,null)
        Wire.validate(context,"context")
        ensure(context.path("attemptId").asText()==attempt && context.path("commandId").asText()==commandId && context.path("deviceId").asText()==deviceId,"CONTEXT_BINDING_MISMATCH")
        val fixed=context.path("case");val payload=command.path("payload")
        ensure(payload.path("caseId")==fixed.path("caseId") && payload.path("caseVersion")==fixed.path("version") && payload.path("timeoutMs")==fixed.path("timeoutMs") &&
            payload.path("requiredEvidence").map {it.asText()}.toSet()==fixed.path("requiredEvidence").map {it.asText()}.toSet() && command.path("leaseDurationSeconds").asInt()==90,"COMMAND_CONTEXT_MISMATCH")
        val entry=saved ?: JournalEntry(attempt,commandId,"",context.path("environment").path("bootSessionId").asText(),0,0,Phase.RECEIVED,emptySet(),null)
        ensure(entry.commandId==commandId && entry.attemptId==attempt,"JOURNAL_BINDING_MISMATCH")
        current=entry
        if(saved==null) {
            // Crash during receipt writes leaves a visible corrupt/incomplete journal, never a fresh execution.
            journal.save(entry);journal.write(attempt,"command.json",command);journal.write(attempt,"context.json",context)
        }
        ensure(device.boot()==entry.bootSessionId,"ENVIRONMENT_IDENTITY_CHANGED")
        val ack=client.call("POST","/agent-api/v1/commands/$commandId:ack",Wire.message("COMMAND_ACK").put("status","ACCEPTED"),"$attempt:ack")
        ensure(ack.path("commandId").asText()==commandId && ack.path("attemptId").asText()==attempt && ack.path("status").asText()=="ACCEPTED","ACK_INVALID")
        val leaseId=Wire.id(ack.path("leaseId").asText());val fencing=ack.path("fencingToken").asLong();ensure(fencing>0,"ACK_INVALID")
        if(entry.phase!=Phase.RECEIVED) ensure(entry.leaseId==leaseId && entry.fencingToken==fencing,"LEASE_LOST")
        update(entry.copy(leaseId=leaseId,fencingToken=fencing,phase=if(entry.phase==Phase.RECEIVED) Phase.ACKED else entry.phase))
        heartbeat(checkNotNull(current))
        when(RecoveryPolicy.decide(checkNotNull(current).phase,lease.valid(),true)) {
            RecoveryAction.WAIT_FOR_DEADLINE -> {event("RECOVERY_PENDING");lease.invalidate();throw AgentFailure("RECOVERY_WAIT_FOR_DEADLINE")}
            RecoveryAction.DIAGNOSTICS_ONLY -> throw AgentFailure("RECOVERY_DIAGNOSTICS_ONLY")
            RecoveryAction.DONE -> return
            else -> Unit
        }
        val scheduler=Executors.newSingleThreadScheduledExecutor {task -> Thread(task,"agent-heartbeat").apply {isDaemon=true}}
        val heartbeatTask=scheduler.scheduleAtFixedRate({
            try {heartbeat(current)} catch(e:Exception) {lease.invalidate();System.err.println(if(e is AgentFailure) e.code else "HEARTBEAT_FAILED")}
        },20,20,TimeUnit.SECONDS)
        try {
            if(checkNotNull(current).phase in setOf(Phase.ACKED,Phase.INSTALLED)) observe(context)
            uploadObserved()
            heartbeatTask.cancel(false);scheduler.shutdown();ensure(scheduler.awaitTermination(31,TimeUnit.SECONDS),"HEARTBEAT_STOP_TIMEOUT")
            lease.requireValid();replayResult(checkNotNull(current))
        } finally {heartbeatTask.cancel(true);scheduler.shutdownNow();current=null;lease.invalidate()}
    }
    private fun register() {
        if(agentId!=null) return
        val request=Wire.message("AGENT_REGISTRATION").put("agentVersion","0.1.0").put("deviceRef",deviceId)
        request.putArray("supportedProtocolVersions").add("1.0")
        request.putArray("capabilities").add("ADB").add("APK_INSTALL").add("LOG").add("SCREENSHOT")
        request.putObject("collectorVersions").put("LOG","1.0").put("SCREENSHOT","1.0")
        val response=client.call("POST","/agent-api/v1/agents:register",request,key())
        ensure(response.path("protocolVersion").asText()=="1.0" && response.path("heartbeatIntervalSeconds").asInt()==20 && response.path("leaseDurationSeconds").asInt()==90,"REGISTRATION_POLICY_INVALID")
        agentId=Wire.id(response.path("agentId").asText())
    }
    private fun heartbeat(entry:JournalEntry?) {
        val boot=device.boot()
        val freeBytes=Files.getFileStore(journal.root).usableSpace
        val storageReady=freeBytes>=SPOOL_RESERVE_BYTES
        val request=Wire.message("AGENT_HEARTBEAT")
            .put("agentUptimeMs",(System.nanoTime()-uptimeStarted)/1000000).put("state",if(!storageReady) "DEGRADED" else if(entry==null) "ONLINE" else "BUSY")
            .put("spoolFreeBytes",freeBytes).put("clockOffsetMs",0)
        request.putObject("device").put("power","ON").put("connectivity","CONNECTED").put("bootSessionId",boot)
        if(entry!=null) {ensure(boot==entry.bootSessionId,"ENVIRONMENT_IDENTITY_CHANGED");request.put("currentCommandId",entry.commandId).put("lastSequenceNo",entry.lastSequence)}
        val started=System.nanoTime()
        val response=client.call("POST","/agent-api/v1/agents/${checkNotNull(agentId)}:heartbeat",request,key())
        ensure(response.path("agentId").asText()==agentId,"HEARTBEAT_BINDING_MISMATCH")
        ensure(storageReady,"SPOOL_CAPACITY_LOW")
        if(entry==null) lease.anchor(Instant.parse(response.path("serverTime").asText()),started)
        else lease.renew(response,entry,checkNotNull(deadline),started)
    }
    private fun update(entry:JournalEntry) {journal.save(entry);current=entry}
    private fun event(type:String) {
        lease.requireValid();val entry=checkNotNull(current)
        val pending=if(journal.exists(entry.attemptId,"event.json")) journal.read(entry.attemptId,"event.json") else null
        val request=if(pending!=null && pending.path("sequenceNo").asLong()>entry.lastSequence) pending else {
            Wire.message("COMMAND_EVENT").put("commandId",entry.commandId).put("attemptId",entry.attemptId).put("leaseId",entry.leaseId)
                .put("fencingToken",entry.fencingToken).put("sequenceNo",entry.lastSequence+1).put("eventType",type).put("occurredAt",lease.now().toString())
                .apply {putObject("payload")}.also {journal.write(entry.attemptId,"event.json",it)}
        }
        val sequence=request.path("sequenceNo").asLong()
        val receipt=client.call("POST","/agent-api/v1/commands/${entry.commandId}/events",request,"${entry.attemptId}:event:$sequence")
        ensure(receipt.path("commandId").asText()==entry.commandId && receipt.path("attemptId").asText()==entry.attemptId && receipt.path("sequenceNo").asLong()==sequence && receipt.path("accepted").asBoolean() &&
            receipt.path("eventDigest").asText()==Wire.sha256(org.erdtman.jcs.JsonCanonicalizer(Wire.mapper.writeValueAsBytes(request)).encodedUTF8),"EVENT_RECEIPT_INVALID")
        update(entry.copy(lastSequence=sequence))
    }
    private fun observe(context:JsonNode) {
        val entry=checkNotNull(current);val id=entry.attemptId;val started=lease.now();val steps=mutableListOf<String>()
        var status="PASS";var reason:String?=null
        try {
            lease.requireValid();device.preflight(context);steps.add("PREFLIGHT_CONFIRMED")
            if(entry.phase==Phase.ACKED) {
                if(checkNotNull(current).lastSequence==0L) event("STARTED");lease.requireValid();update(checkNotNull(current).copy(phase=Phase.INSTALL_INTENT))
                device.install();device.verifyInstalled(context);update(checkNotNull(current).copy(phase=Phase.INSTALLED));steps.add("INSTALL_CONFIRMED")
            } else device.verifyInstalled(context)
            lease.requireValid();update(checkNotNull(current).copy(phase=Phase.LAUNCH_INTENT))
            val launched=device.launch(id,context.path("case").path("mode").asText());val foreground=device.foreground()
            val ready=if(foreground) SmokeAssertions.ready(device.ui(id),id) else false
            if(!launched || !foreground || !ready) {status="FAIL";reason="SMOKE_ASSERTION_FAILED"}
            steps.add(if(status=="PASS") "SMOKE_ASSERTIONS_CONFIRMED" else "SMOKE_ASSERTION_FAILED")
        } catch(e:AgentFailure) {
            if(e.code.startsWith("HTTP_") || e.code.startsWith("EVENT_") || e.code.startsWith("WIRE_") || e.code.startsWith("LEASE_") || e.code=="ENVIRONMENT_IDENTITY_CHANGED") throw e
            if(checkNotNull(current).phase in setOf(Phase.INSTALL_INTENT,Phase.LAUNCH_INTENT) && e.code in setOf("PROCESS_TIMEOUT","PROCESS_READ_FAILED","PROCESS_INTERRUPTED","DEVICE_DISCONNECTED","PROCESS_START_FAILED","LEASE_LOST","HTTP_TIMEOUT","HTTP_IO_ERROR")) {
                if(lease.valid()) event("RECOVERY_PENDING")
                throw e
            }
            lease.requireValid()
            status=if(e.code=="PROCESS_TIMEOUT") "TIMEOUT" else if(e.code.startsWith("APK_") || e.code.startsWith("UI_") || e.code=="ENVIRONMENT_IDENTITY_CHANGED") "BLOCKED" else "ERROR"
            reason=e.code;steps.add(e.code)
        }
        lease.requireValid();val finished=lease.now();val candidates=mutableListOf<EvidenceCandidate>()
        for(collector in listOf(LogCollector(device,steps),ScreenshotCollector(device))) {
            try {collector.start(CollectorContext(id,journal.folder(id)),emptyMap());collector.mark(id)
                candidates.addAll(collector.collect("CASE_FINISHED",CollectionWindow(started,finished)))
            } catch(e:AgentFailure) {status="ERROR";reason=e.code;steps.add(e.code)}
            finally {collector.stop()}
        }
        val observation=Wire.mapper.createObjectNode().put("status",status).put("startedAt",started.toString()).put("finishedAt",finished.toString())
        if(reason!=null) observation.put("reasonCode",reason)
        observation.putArray("candidates").also {array ->candidates.forEach {candidate ->
            array.addObject().put("type",candidate.type).put("mediaType",candidate.mediaType).put("size",candidate.size).put("checksum",candidate.checksum)
                .put("capturedAt",candidate.capturedAt.toString()).put("collectorVersion",candidate.collectorVersion)
                .put("fileName",candidate.localFile.fileName.toString())
        }}
        journal.write(id,"observation.json",observation);update(checkNotNull(current).copy(phase=Phase.OBSERVED))
    }
    private fun uploadObserved() {
        var entry=checkNotNull(current);ensure(entry.phase==Phase.OBSERVED,"OBSERVATION_REQUIRED")
        val observation=journal.read(entry.attemptId,"observation.json")
        for(candidate in observation.path("candidates")) {
            lease.requireValid()
            val type=candidate.path("type").asText();ensure(type in setOf("LOG","SCREENSHOT"),"CANDIDATE_INVALID")
            val name=if(type=="LOG") "log.txt" else "screenshot.png"
            ensure(candidate.path("fileName").asText()==name,"CANDIDATE_INVALID")
            val file=journal.folder(entry.attemptId).resolve(name)
            val limit=if(type=="LOG") 1048576 else 8388608
            val bytes=SafeFiles.read(file,limit)
            ensure(bytes.size.toLong()==candidate.path("size").asLong() && Wire.sha256(bytes)==candidate.path("checksum").asText(),"SPOOL_INTEGRITY_ERROR")
            val receiptName=if(type=="LOG") "upload-log.json" else "upload-screenshot.json"
            val create=Wire.message("EVIDENCE_UPLOAD_CREATE").put("attemptId",entry.attemptId).put("evidenceType",type)
            metadata(create,candidate)
            val session=if(journal.exists(entry.attemptId,receiptName)) journal.read(entry.attemptId,receiptName) else
                client.call("POST","/agent-api/v1/evidence/uploads",create,"${entry.attemptId}:create:$type").also {journal.write(entry.attemptId,receiptName,it)}
            val upload=Wire.id(session.path("uploadId").asText());val evidence=Wire.id(session.path("evidenceId").asText())
            val path="/agent-api/v1/evidence/uploads/$upload/payload"
            ensure(session.path("uploadUrl").asText()==path,"UPLOAD_URL_INVALID")
            if(evidence !in entry.evidenceIds) {
                ensure(lease.now().isBefore(Instant.parse(session.path("expiresAt").asText())),"UPLOAD_EXPIRED")
                client.putPayload(path,file);lease.requireValid()
                val complete=metadata(Wire.message("EVIDENCE_UPLOAD_COMPLETE"),candidate)
                val receipt=client.call("POST","/agent-api/v1/evidence/uploads/$upload:complete",complete,"${entry.attemptId}:complete:$type")
                ensure(receipt.path("evidenceId").asText()==evidence && receipt.path("state").asText()=="AVAILABLE" && receipt.path("attemptId").asText()==entry.attemptId &&
                    receipt.path("deviceId").asText()==deviceId && receipt.path("type").asText()==type && receipt.path("sizeBytes").isIntegralNumber && receipt.path("sizeBytes").asLong()==complete.path("sizeBytes").asLong() && listOf("contentType","payloadChecksum","capturedAt","collectorVersion").all {receipt.path(it)==complete.path(it)},"UPLOAD_RECEIPT_INVALID")
                entry=entry.copy(evidenceIds=entry.evidenceIds+evidence);update(entry)
            }
        }
        val result=Wire.message("ATTEMPT_RESULT").put("attemptId",entry.attemptId).put("leaseId",entry.leaseId).put("fencingToken",entry.fencingToken)
            .put("status",observation.path("status").asText()).put("startedAt",observation.path("startedAt").asText()).put("finishedAt",observation.path("finishedAt").asText())
        if(observation.has("reasonCode")) result.put("reasonCode",observation.path("reasonCode").asText())
        result.putArray("evidenceIds").also {array ->entry.evidenceIds.sorted().forEach(array::add)}
        result.put("resultDigest",ResultDigest.digest(result));Wire.validate(result,"resultRequest")
        journal.write(entry.attemptId,"result.json",result);update(entry.copy(phase=Phase.UPLOADED,resultDigest=result.path("resultDigest").asText()))
    }
    private fun metadata(target:ObjectNode,candidate:JsonNode):ObjectNode=target.put("contentType",candidate.path("mediaType").asText())
        .put("sizeBytes",candidate.path("size").asLong()).put("payloadChecksum",candidate.path("checksum").asText())
        .put("capturedAt",candidate.path("capturedAt").asText()).put("collectorVersion",candidate.path("collectorVersion").asText())
    private fun replayResult(entry:JournalEntry) {
        val request=journal.read(entry.attemptId,"result.json");Wire.validate(request,"resultRequest")
        ensure(request.path("attemptId").asText()==entry.attemptId && request.path("leaseId").asText()==entry.leaseId && request.path("fencingToken").asLong()==entry.fencingToken &&
            request.path("resultDigest").asText()==entry.resultDigest && ResultDigest.digest(request)==entry.resultDigest && request.path("evidenceIds").map {it.asText()}.toSet()==entry.evidenceIds,"RESULT_SPOOL_MISMATCH")
        val receipt=client.call("PUT","/agent-api/v1/attempts/${entry.attemptId}/result",request,"${entry.attemptId}:result")
        ensure(request.properties().asSequence().all {(name,value)->receipt.path(name)==value},"RESULT_RECEIPT_MISMATCH")
        journal.write(entry.attemptId,"result-receipt.json",receipt);update(entry.copy(phase=Phase.RESULT_ACKED))
    }
    private fun key()=UUID.randomUUID().toString()
    companion object { private const val SPOOL_RESERVE_BYTES=3L*268435456+10L*1048576 }
}
