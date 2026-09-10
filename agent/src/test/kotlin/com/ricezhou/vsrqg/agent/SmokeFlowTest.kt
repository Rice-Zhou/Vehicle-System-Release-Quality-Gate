package com.ricezhou.vsrqg.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.net.URI
import java.time.Instant
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

class SmokeFlowTest {
    @TempDir lateinit var root:Path
    @ParameterizedTest
    @CsvSource("bootSessionId,launch", "buildId,launch", "buildFingerprint,launch", "bootSessionId,complete", "buildId,complete", "buildFingerprint,complete")
    fun `post preflight environment mutation cannot collect or submit a normal result`(field:String,stage:String) {
        lateinit var device:FixtureDevice
        SmokeServer(root,onFinalComplete={if(stage=="complete") device.changeEnvironment(field)}).use {server ->
            device=FixtureDevice(server.id,environmentChangeAt=if(stage=="launch") field else null)
            ExecutionJournal(root.resolve("spool")).use {journal ->
                assertEquals("ENVIRONMENT_IDENTITY_CHANGED",assertThrows(AgentFailure::class.java) {AgentLoop(server.client,journal,device,"device_demo_01",LeaseGuard()).runOnce()}.code)
                assertNull(server.result);assertFalse(journal.exists(server.id,"result.json"))
                if(stage=="launch") assertTrue(server.payloads.isEmpty())
                else assertEquals(setOf("ev_LOG","ev_SCREENSHOT"),journal.load(server.id)!!.evidenceIds)
            }
        }
    }
    @ParameterizedTest
    @CsvSource("complete,PAYLOAD_TYPE_INVALID", "payload,PAYLOAD_INTEGRITY_ERROR", "payload,UPLOAD_NOT_WRITABLE")
    fun `permanent second Evidence rejection reports ERROR with confirmed LOG only`(stage:String,code:String) {
        SmokeServer(root,rejectionStage=stage,rejectionCode=code).use {server ->
            ExecutionJournal(root.resolve("spool")).use {journal ->
                AgentLoop(server.client,journal,FixtureDevice(server.id),"device_demo_01",LeaseGuard()).runOnce()
                assertEquals(Phase.RESULT_ACKED,journal.load(server.id)!!.phase)
                assertEquals("ERROR",server.result!!.path("status").asText());assertEquals(code,server.result!!.path("reasonCode").asText())
                assertEquals(listOf("ev_LOG"),server.result!!.path("evidenceIds").map {it.asText()})
                assertTrue(java.nio.file.Files.isRegularFile(journal.folder(server.id).resolve("screenshot.png")))
            }
        }
    }
    @ParameterizedTest
    @CsvSource("409,STALE_LEASE", "409,PAYLOAD_IO_ERROR", "409,UNKNOWN_UPLOAD_FAILURE", "503,PAYLOAD_TYPE_INVALID", "403,PAYLOAD_TYPE_INVALID")
    fun `stale transient or unknown rejection never becomes writable ERROR result`(status:Int,code:String) {
        SmokeServer(root,rejectionStage="complete",rejectionCode=code,rejectionStatus=status).use {server ->
            ExecutionJournal(root.resolve("spool")).use {journal ->
                assertThrows(AgentFailure::class.java) {AgentLoop(server.client,journal,FixtureDevice(server.id),"device_demo_01",LeaseGuard()).runOnce()}
                assertEquals(Phase.OBSERVED,journal.load(server.id)!!.phase)
                assertEquals(setOf("ev_LOG"),journal.load(server.id)!!.evidenceIds);assertNull(server.result)
            }
        }
    }
    @Test fun `ACKED restart after STARTED receipt does not create second STARTED`() {
        SmokeServer(root).use {server ->
            val spool=root.resolve("spool");val device=FixtureDevice(server.id)
            ExecutionJournal(spool).use {journal ->
                journal.save(JournalEntry(server.id,"cmd_1","lse_1","boot_demo_01",1,1,Phase.ACKED,emptySet(),null))
                journal.write(server.id,"command.json",server.command);journal.write(server.id,"context.json",server.context)
                AgentLoop(server.client,journal,device,"device_demo_01",LeaseGuard()).runOnce()
            }
            assertTrue(server.events.isEmpty());assertEquals(1,device.installs)
        }
    }
    @Test fun `heartbeat remains independent during long installation`() {
        SmokeServer(root).use {server ->
            val device=FixtureDevice(server.id,pauseMs=22000)
            ExecutionJournal(root.resolve("spool")).use {journal ->AgentLoop(server.client,journal,device,"device_demo_01",LeaseGuard()).runOnce()}
            assertTrue(server.heartbeats>=3);assertEquals("PASS",server.result!!.path("status").asText())
        }
    }
    @Test fun `cross attempt upload receipt stops result submission`() {
        SmokeServer(root,wrongEvidenceAttempt=true).use {server ->
            ExecutionJournal(root.resolve("spool")).use {journal ->
                assertThrows(AgentFailure::class.java) {AgentLoop(server.client,journal,FixtureDevice(server.id),"device_demo_01",LeaseGuard()).runOnce()}
                assertEquals(Phase.OBSERVED,journal.load(server.id)!!.phase);assertNull(server.result)
            }
        }
    }
    @Test fun `unaccepted event receipt cannot authorize installation`() {
        SmokeServer(root,rejectEvent=true).use {server ->
            val device=FixtureDevice(server.id)
            ExecutionJournal(root.resolve("spool")).use {journal ->
                assertThrows(AgentFailure::class.java) {AgentLoop(server.client,journal,device,"device_demo_01",LeaseGuard()).runOnce()}
                assertEquals(0,device.installs)
            }
        }
    }
    @Test fun `unknown STARTED response replays original event payload and sequence`() {
        SmokeServer(root,failFirstEvent=true).use {server ->
            val device=FixtureDevice(server.id);val spool=root.resolve("spool")
            ExecutionJournal(spool).use {journal ->assertThrows(AgentFailure::class.java) {AgentLoop(server.client,journal,device,"device_demo_01",LeaseGuard()).runOnce()}}
            ExecutionJournal(spool).use {journal ->AgentLoop(server.client,journal,device,"device_demo_01",LeaseGuard()).runOnce()}
            assertEquals(2,server.events.size);assertEquals(server.events[0],server.events[1]);assertEquals(1,device.installs)
        }
    }
    @Test fun `normal execution binds actual captured bytes and both evidence receipts to one result`() {
        SmokeServer(root).use {server ->
            val device=FixtureDevice(server.id)
            ExecutionJournal(root.resolve("spool")).use {journal ->
                AgentLoop(server.client,journal,device,"device_demo_01",LeaseGuard()).runOnce()
                assertEquals(Phase.RESULT_ACKED,journal.load(server.id)!!.phase)
                assertEquals("PASS",server.result!!.path("status").asText())
                assertEquals(setOf("ev_LOG","ev_SCREENSHOT"),server.result!!.path("evidenceIds").map {it.asText()}.toSet())
                assertEquals(1,device.installs);assertEquals(1,device.launches)
                assertEquals(setOf("LOG","SCREENSHOT"),server.payloads.keys)
                assertTrue(server.payloads.getValue("LOG").toString(Charsets.UTF_8).contains("attemptId=${server.id}"))
                assertFalse(server.payloads.getValue("LOG").toString(Charsets.UTF_8).contains("sensitive unrelated log"))
                assertEquals(1,server.events.size)
            }
        }
    }
    @Test fun `assertion failure is FAIL with both evidence files not a successful shortcut`() {
        SmokeServer(root,negative=true).use {server ->
            val device=FixtureDevice(server.id,ready=false)
            ExecutionJournal(root.resolve("spool")).use {journal ->AgentLoop(server.client,journal,device,"device_demo_01",LeaseGuard()).runOnce()}
            assertEquals("FAIL",server.result!!.path("status").asText());assertEquals("SMOKE_ASSERTION_FAILED",server.result!!.path("reasonCode").asText())
            assertEquals(2,server.payloads.size)
        }
    }
    @Test fun `installation timeout leaves intent and restart never installs again`() {
        SmokeServer(root).use {server ->
            val device=FixtureDevice(server.id,uncertain=true);val spool=root.resolve("spool")
            ExecutionJournal(spool).use {journal ->
                assertThrows(AgentFailure::class.java) {AgentLoop(server.client,journal,device,"device_demo_01",LeaseGuard()).runOnce()}
                assertEquals(Phase.INSTALL_INTENT,journal.load(server.id)!!.phase)
            }
            ExecutionJournal(spool).use {journal ->
                assertEquals("RECOVERY_WAIT_FOR_DEADLINE",assertThrows(AgentFailure::class.java) {AgentLoop(server.client,journal,device,"device_demo_01",LeaseGuard()).runOnce()}.code)
                assertEquals(Phase.INSTALL_INTENT,journal.load(server.id)!!.phase)
            }
            assertEquals(1,device.installs);assertEquals(0,device.launches);assertNull(server.result)
        }
    }
    @Test fun `blocked preflight reports no install or fake STARTED and preserves partial evidence`() {
        SmokeServer(root).use {server ->
            val device=FixtureDevice(server.id,preflightFailure="APK_SIGNATURE_CONFLICT")
            ExecutionJournal(root.resolve("spool")).use {journal ->AgentLoop(server.client,journal,device,"device_demo_01",LeaseGuard()).runOnce()}
            assertEquals("BLOCKED",server.result!!.path("status").asText());assertEquals(0,device.installs);assertTrue(server.events.isEmpty())
        }
    }
    @Test fun `lost upload response retries saved session and never reexecutes device`() {
        SmokeServer(root,failFirstUpload=true).use {server ->
            val device=FixtureDevice(server.id);val spool=root.resolve("spool")
            ExecutionJournal(spool).use {journal ->
                assertThrows(AgentFailure::class.java) {AgentLoop(server.client,journal,device,"device_demo_01",LeaseGuard()).runOnce()}
                assertEquals(Phase.OBSERVED,journal.load(server.id)!!.phase)
            }
            ExecutionJournal(spool).use {journal ->AgentLoop(server.client,journal,device,"device_demo_01",LeaseGuard()).runOnce()}
            assertEquals(1,device.installs);assertEquals(1,device.launches);assertEquals("PASS",server.result!!.path("status").asText())
            assertEquals(2,server.creates);assertEquals(2,server.logUploads)
        }
    }
}
class FixtureDevice(private val id:String,private val ready:Boolean=true,private val uncertain:Boolean=false,private val preflightFailure:String?=null,private val pauseMs:Long=0,private val environmentChangeAt:String?=null):SmokeDevice {
    var installs=0;var launches=0
    private var environment:ObjectNode?=null
    override fun boot()=environment?.path("bootSessionId")?.asText() ?: "boot_demo_01"
    fun changeEnvironment(field:String) {checkNotNull(environment).put(field,"changed")}
    override fun verifyEnvironment(context:JsonNode) {ensure(environment==null || environment==context.path("environment"),"ENVIRONMENT_IDENTITY_CHANGED")}
    override fun preflight(context:JsonNode) {environment=context.path("environment").deepCopy<ObjectNode>();if(preflightFailure!=null) throw AgentFailure(preflightFailure)}
    override fun install() {installs++;if(pauseMs>0) Thread.sleep(pauseMs);if(uncertain) throw AgentFailure("PROCESS_TIMEOUT")}
    override fun verifyInstalled(context:JsonNode) {}
    override fun launch(attemptId:String,mode:String):Boolean {check(attemptId==id);launches++;if(environmentChangeAt!=null) changeEnvironment(environmentChangeAt);return true}
    override fun foreground()=true
    override fun ui(attemptId:String)="<hierarchy><node package='com.ricezhou.vsrqg.smoke' text='SYNTHETIC_DEMO&#10;VSRQG_SMOKE_${if(ready) "READY" else "NOT_READY"}:$attemptId'/></hierarchy>".toByteArray()
    override fun appLog()="sensitive unrelated log\nVSRQG_SMOKE_READY:$id".toByteArray()
    override fun screenshot():ByteArray=ByteArrayOutputStream().also {ImageIO.write(BufferedImage(1,1,BufferedImage.TYPE_INT_RGB),"png",it)}.toByteArray()
}
class SmokeServer(root:Path,negative:Boolean=false,private val failFirstUpload:Boolean=false,private val wrongEvidenceAttempt:Boolean=false,private val rejectEvent:Boolean=false,private val failFirstEvent:Boolean=false,private val onFinalComplete:()->Unit={},private val rejectionStage:String?=null,private val rejectionCode:String="PAYLOAD_TYPE_INVALID",private val rejectionStatus:Int=409,private val onResultAccepted:(JsonNode)->Unit={}):AutoCloseable {
    private val https=TestHttps(root)
    val client=AgentClient(URI(https.origin),https.tls)
    fun clientOrigin()=https.origin
    val id="01992560-aaab-7000-8000-123456789abc"
    var result:JsonNode?=null
    val events=mutableListOf<JsonNode>();val payloads=mutableMapOf<String,ByteArray>();val declarations=mutableMapOf<String,JsonNode>()
    var creates=0;var logUploads=0;var heartbeats=0
    val context:ObjectNode
    val command:ObjectNode
    init {
        val now=Instant.now()
        context=Wire.parse(javaClass.getResourceAsStream("/contracts/examples/execution-context.json")!!.readAllBytes()).deepCopy<ObjectNode>()
        context.put("attemptId",id).put("commandId","cmd_1").put("deviceId","device_demo_01")
        (context.path("environment") as ObjectNode).put("bootSessionId","boot_demo_01")
        if(negative) {(context.path("plan") as ObjectNode).put("version",2);(context.path("case") as ObjectNode).put("version",2).put("mode","assertion-failure")}
        command=Wire.mapper.createObjectNode().put("protocolVersion","1.0").put("commandId","cmd_1").put("attemptId",id).put("commandType","EXECUTE_TEST_CASE")
            .put("issuedAt",now.toString()).put("deadline",now.plusSeconds(300).toString()).put("leaseDurationSeconds",90).put("idempotencyKey","$id:execute").put("payloadSchemaVersion","1.0")
        command.putObject("payload").put("caseId","apk-launch-smoke").put("caseVersion",if(negative) 2 else 1).put("timeoutMs",300000).putArray("requiredEvidence").add("LOG").add("SCREENSHOT")
        https.server.createContext("/agent-api/v1/") {x ->
            try {
                val path=x.requestURI.path;val bytes=x.requestBody.readAllBytes();val body=if(bytes.isNotEmpty() && !path.endsWith("/payload")) Wire.parse(bytes) else null
                if(path.contains("upl_SCREENSHOT") && ((rejectionStage=="payload" && path.endsWith("/payload")) || (rejectionStage=="complete" && path.endsWith(":complete")))) {
                    val problem=Wire.mapper.createObjectNode().put("type","https://vsrqg.example/problems/test").put("title",rejectionCode).put("status",rejectionStatus).put("code",rejectionCode)
                        .put("detail",rejectionCode).put("instance",path).put("requestId","fixture-request").apply {putArray("violations")}
                    val payload=Wire.mapper.writeValueAsBytes(problem);x.responseHeaders.add("Content-Type","application/problem+json");x.sendResponseHeaders(rejectionStatus,payload.size.toLong());x.responseBody.use {it.write(payload)};return@createContext
                }
                val response:JsonNode=when {
                    path.endsWith("agents:register") -> Wire.mapper.createObjectNode().put("protocolVersion","1.0").put("agentId","agt_1").put("heartbeatIntervalSeconds",20).put("leaseDurationSeconds",90)
                    path.endsWith(":heartbeat") -> {heartbeats++;Wire.mapper.createObjectNode().put("agentId","agt_1").put("serverTime",Instant.now().toString()).put("leaseRenewed",body!!.has("currentCommandId")).put("leaseId","lse_1").put("fencingToken",1).put("leaseExpiresAt",Instant.now().plusSeconds(89).toString())}
                    path.endsWith("commands:poll") -> command
                    path.endsWith("/context") -> context
                    path.endsWith(":ack") -> Wire.mapper.createObjectNode().put("commandId","cmd_1").put("attemptId",id).put("status","ACCEPTED").put("leaseId","lse_1").put("fencingToken",1).put("leaseExpiresAt",now.plusSeconds(90).toString())
                    path.endsWith("/events") -> {events.add(body!!);if(failFirstEvent && events.size==1) {x.sendResponseHeaders(503,-1);x.close();return@createContext};Wire.mapper.createObjectNode().put("commandId","cmd_1").put("attemptId",id).put("sequenceNo",body.path("sequenceNo").asLong()).put("eventDigest",Wire.sha256(org.erdtman.jcs.JsonCanonicalizer(Wire.mapper.writeValueAsBytes(body)).encodedUTF8)).put("accepted",!rejectEvent)}
                    path.endsWith("/uploads") -> {creates++;val type=body!!.path("evidenceType").asText();declarations[type]=body
                        Wire.mapper.createObjectNode().put("uploadId","upl_$type").put("evidenceId","ev_$type").put("uploadUrl","/agent-api/v1/evidence/uploads/upl_$type/payload").put("expiresAt",now.plusSeconds(300).toString())}
                    path.endsWith("/payload") -> {val type=path.substringAfter("upl_").substringBefore('/');payloads[type]=bytes
                        check(Wire.sha256(bytes)==declarations.getValue(type).path("payloadChecksum").asText());if(type=="LOG") logUploads++
                        x.sendResponseHeaders(if(failFirstUpload && type=="LOG" && logUploads==1) 503 else 204,-1);x.close();return@createContext}
                    path.endsWith(":complete") -> {val type=path.substringAfter("upl_").substringBefore(':');check(payloads.containsKey(type));if(type=="SCREENSHOT") onFinalComplete();Wire.mapper.createObjectNode().put("evidenceId","ev_$type").put("schemaVersion","1.0").put("type",type).put("releaseId","release_demo_01").put("testRunId","run_demo_01")
                        .put("attemptId",if(wrongEvidenceAttempt) "other_attempt" else id).put("deviceId","device_demo_01").put("collectorName","single-device-smoke").put("source","AGENT")
                        .put("sensitivity","RESTRICTED").put("createdAt",now.toString()).put("state","AVAILABLE").also {metadata ->
                            for(field in listOf("capturedAt","collectorVersion","sizeBytes","payloadChecksum","contentType")) metadata.set<JsonNode>(field,declarations.getValue(type).path(field))
                        }}
                    path.endsWith("/result") -> {check(body!!.path("resultDigest").asText()==ResultDigest.digest(body));result=body;onResultAccepted(body);body}
                    else -> error("unexpected endpoint")
                }
                AgentLoopIntegrationTest.respond(x,response)
            } catch(e:Exception) {x.sendResponseHeaders(500,-1);x.close();throw e}
        }
    }
    override fun close()=https.close()
}
