package com.ricezhou.vsrqg.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class AgentLoopIntegrationTest {
    @TempDir lateinit var root:Path
    private val id="01992560-aaab-7000-8000-123456789abc"
    @Test fun `lost Result response replays exact durable request after restart without any device or evidence operation`() {
        TestHttps(root).use { https ->
            val resultCalls=AtomicInteger();val registration=Wire.mapper.readTree("""{"protocolVersion":"1.0","agentId":"agt_1","heartbeatIntervalSeconds":20,"leaseDurationSeconds":90}""")
            https.server.createContext("/agent-api/v1/agents:register") {x -> x.requestBody.readAllBytes();respond(x,registration)}
            val bodies=mutableListOf<ByteArray>()
            https.server.createContext("/agent-api/v1/attempts/$id/result") {x ->
                bodies.add(x.requestBody.readAllBytes());resultCalls.incrementAndGet()
                if(resultCalls.get()==1) {x.sendResponseHeaders(503,-1);x.close()} else respond(x,Wire.parse(bodies.last()))
            }
            val spool=root.resolve("spool");val device=NoDevice()
            val request=Wire.parse(javaClass.getResourceAsStream("/contracts/examples/result-canonical-input.json")!!.readAllBytes())
            ExecutionJournal(spool).use {journal ->
                journal.save(JournalEntry(id,"cmd_1","lse_golden","boot_1",1,0,Phase.UPLOADED,setOf("ev_log","ev_png"),request.path("resultDigest").asText()))
                journal.write(id,"result.json",request)
                assertThrows(AgentFailure::class.java) {AgentLoop(AgentClient(URI(https.origin),https.tls),journal,device,"dev_1",LeaseGuard()).runOnce()}
                assertEquals(Phase.UPLOADED,journal.load(id)!!.phase)
            }
            ExecutionJournal(spool).use { journal ->
                AgentLoop(AgentClient(URI(https.origin),https.tls),journal,device,"dev_1",LeaseGuard()).runOnce()
                assertEquals(Phase.RESULT_ACKED,journal.load(id)!!.phase)
            }
            assertEquals(2,resultCalls.get());assertArrayEquals(bodies[0],bodies[1]);assertEquals(0,device.calls)
        }
    }
    @Test fun `never accepted stale result stays diagnostic and cannot trigger actions`() {
        TestHttps(root).use {https ->
            https.server.createContext("/agent-api/v1/agents:register") {x ->x.requestBody.readAllBytes();respond(x,Wire.mapper.readTree("""{"protocolVersion":"1.0","agentId":"agt_1","heartbeatIntervalSeconds":20,"leaseDurationSeconds":90}"""))}
            https.server.createContext("/agent-api/v1/attempts/$id/result") {x ->x.requestBody.readAllBytes();x.sendResponseHeaders(409,-1);x.close()}
            ExecutionJournal(root.resolve("spool")).use {journal ->
                val request=Wire.parse(javaClass.getResourceAsStream("/contracts/examples/result-canonical-input.json")!!.readAllBytes())
                journal.save(JournalEntry(id,"cmd_1","lse_golden","boot_1",1,0,Phase.UPLOADED,setOf("ev_log","ev_png"),request.path("resultDigest").asText()));journal.write(id,"result.json",request)
                val device=NoDevice()
                assertThrows(AgentFailure::class.java) {AgentLoop(AgentClient(URI(https.origin),https.tls),journal,device,"dev_1",LeaseGuard()).runOnce()}
                assertEquals(Phase.UPLOADED,journal.load(id)!!.phase);assertEquals(0,device.calls)
            }
        }
    }
    @Test fun `lease uses monotonic elapsed server time and permanently fences failed renewal`() {
        var monotonic=0L;val lease=LeaseGuard {monotonic}
        val entry=JournalEntry(id,"cmd_1","lse_1","boot_1",1,0,Phase.ACKED,emptySet(),null)
        val response=Wire.mapper.readTree("""{"serverTime":"2026-09-09T00:00:00Z","leaseRenewed":true,"leaseId":"lse_1","fencingToken":1,"leaseExpiresAt":"2026-09-09T00:01:30Z"}""")
        lease.renew(response,entry,Instant.parse("2026-09-09T00:05:00Z"),0)
        monotonic=89_000_000_000;assertTrue(lease.valid())
        monotonic=90_000_000_000;assertFalse(lease.valid())
        lease.invalidate();assertFalse(lease.allowsProcess())
    }
    companion object {
        fun respond(x:com.sun.net.httpserver.HttpExchange,node:JsonNode) {val bytes=Wire.mapper.writeValueAsBytes(node);x.responseHeaders.add("Content-Type","application/json");x.sendResponseHeaders(200,bytes.size.toLong());x.responseBody.use {it.write(bytes)}}
    }
}
class NoDevice:SmokeDevice {
    var calls=0
    private fun denied():Nothing {calls++;error("unexpected device access")}
    override fun boot():String=denied()
    override fun preflight(context:JsonNode)=denied()
    override fun install()=denied()
    override fun verifyInstalled(context:JsonNode)=denied()
    override fun launch(attemptId:String,mode:String):Boolean=denied()
    override fun foreground():Boolean=denied()
    override fun ui(attemptId:String):ByteArray=denied()
    override fun appLog():ByteArray=denied()
    override fun screenshot():ByteArray=denied()
}
