package com.ricezhou.vsrqg.evidence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.evidence.application.*
import com.ricezhou.vsrqg.testmanagement.RunFixture
import com.ricezhou.vsrqg.testmanagement.application.TestJson
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import java.nio.file.Files
import java.util.UUID

@org.springframework.test.context.ContextConfiguration(initializers=[EvidenceStorageTestInitializer::class])
@AutoConfigureMockMvc
@TestPropertySource(properties=["vsrqg.demo.evidence.enabled=true"])
@Timeout(60)
open class EvidenceFixture:RunFixture() {
    @Autowired lateinit var uploads:EvidenceUploadService
    @Autowired lateinit var downloads:EvidenceDownloadService
    @Autowired lateinit var recovery:EvidenceReconciler
    @Autowired lateinit var payloads:PayloadStore
    @Autowired lateinit var mvc:MockMvc
    lateinit var command:JsonNode
    @org.junit.jupiter.api.BeforeEach fun oidcFixture() {
        jdbc.sql("UPDATE principal SET issuer='https://idp.vsrqg.test' WHERE id=:id").param("id",user.subject).update()
        user=user.copy(issuer="https://idp.vsrqg.test")
    }
    fun start() { run(); command=poll(); accept(command.path("commandId").asText()) }
    fun declaration(bytes:ByteArray="hello".toByteArray(),type:String="LOG")=mapper.createObjectNode()
        .put("messageType","EVIDENCE_UPLOAD_CREATE").put("protocolVersion","1.0").put("attemptId",command.path("attemptId").asText())
        .put("evidenceType",type).put("contentType",if(type=="LOG") "text/plain" else "image/png").put("sizeBytes",bytes.size)
        .put("payloadChecksum",TestJson.sha256(bytes)).put("capturedAt",now.toString()).put("collectorVersion","1.0")
    fun completed(body:JsonNode):ObjectNode=body.deepCopy<ObjectNode>().apply { remove(listOf("attemptId","evidenceType")); put("messageType","EVIDENCE_UPLOAD_COMPLETE") }
    fun agentAuth()=authentication(UsernamePasswordAuthenticationToken(fingerprint,"",listOf(SimpleGrantedAuthority("AGENT_CERTIFICATE"))))
    fun apiCreate(body:JsonNode=declaration(),expected:Int=201):JsonNode {
        val result=mvc.perform(post("/agent-api/v1/evidence/uploads").with(agentAuth()).header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn().response
        assertThat(result.status).describedAs(result.contentAsString).isEqualTo(expected)
        return mapper.readTree(result.contentAsString)
    }
    fun apiPut(id:String,bytes:ByteArray="hello".toByteArray(),expected:Int=204) {
        val result=mvc.perform { context->
            val stream=object:jakarta.servlet.ServletInputStream() {
                private val source=bytes.inputStream()
                override fun read()=source.read()
                override fun isReady()=true
                override fun isFinished()=source.available()==0
                override fun setReadListener(listener:jakarta.servlet.ReadListener) {
                    try { if(!isFinished) listener.onDataAvailable();listener.onAllDataRead() }
                    catch(error:java.io.IOException) { listener.onError(error) }
                }
            }
            val request=object:org.springframework.mock.web.MockHttpServletRequest(context,"PUT","/agent-api/v1/evidence/uploads/$id/payload") {
                override fun getInputStream()=stream
            }
            request.isAsyncSupported=true
            request.contentType=MediaType.APPLICATION_OCTET_STREAM_VALUE
            agentAuth().postProcessRequest(request)
        }.andReturn().response
        assertThat(result.status).describedAs(result.contentAsString).isEqualTo(expected)
    }
    fun apiComplete(id:String,body:JsonNode,expected:Int=200,key:String=UUID.randomUUID().toString()):JsonNode {
        val result=mvc.perform(post("/agent-api/v1/evidence/uploads/$id:complete").with(agentAuth()).header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(completed(body).toString())).andReturn().response
        assertThat(result.status).describedAs(result.contentAsString).isEqualTo(expected)
        return mapper.readTree(result.contentAsString)
    }
    fun available(bytes:ByteArray="hello".toByteArray(),type:String="LOG"):Pair<JsonNode,JsonNode> {
        val body=declaration(bytes,type); val session=apiCreate(body); apiPut(session.path("uploadId").asText(),bytes)
        return session to apiComplete(session.path("uploadId").asText(),body)
    }
    companion object {
        val storage=ownedTestRoot(Files.createTempDirectory("evidence-fixture-"))

    }
}

class EvidenceUploadIntegrationTest:EvidenceFixture() {
    @Test fun `HTTP creates uploads verifies and returns immutable metadata with one audit outbox`() {
        start(); val body=declaration(); val session=apiCreate(body); val id=session.path("uploadId").asText()
        assertThat(session.path("uploadUrl").asText()).isEqualTo("/agent-api/v1/evidence/uploads/$id/payload")
        assertThat(downloads.metadata(user,session.path("evidenceId").asText()).path("state").asText()).isEqualTo("PENDING_UPLOAD")
        apiPut(id); apiPut(id)
        val result=apiComplete(id,body,key="complete"); assertThat(result.path("state").asText()).isEqualTo("AVAILABLE")
        assertThat(TestJson.canonical(apiComplete(id,body,key="complete"))).isEqualTo(TestJson.canonical(result))
        apiPut(id,"other".toByteArray(),409)
        assertThat(payloads.verify(id,StoredPayload(5,TestJson.sha256("hello".toByteArray()))).size).isEqualTo(5)
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE aggregate_id=:e AND action='EVIDENCE_AVAILABLE'").param("e",session.path("evidenceId").asText()).query(Int::class.java).single()).isOne()
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE aggregate_id=:e AND event_type='evidence.available'").param("e",session.path("evidenceId").asText()).query(Int::class.java).single()).isOne()
    }
    @Test fun `short and wrong hash initial PUT can retry to AVAILABLE in same Session`() {
        start();val body=declaration();val session=apiCreate(body);val id=session.path("uploadId").asText()
        for(bytes in listOf("hel","other")) {
            apiPut(id,bytes.toByteArray(),409)
            assertThat(Files.exists(storage.resolve("$id.payload"))).isFalse()
            assertThat(downloads.metadata(user,session.path("evidenceId").asText()).path("state").asText()).isEqualTo("PENDING_UPLOAD")
        }
        apiPut(id)
        assertThat(apiComplete(id,body).path("state").asText()).isEqualTo("AVAILABLE")
    }
    @Test fun `wrong media empty oversized mismatched complete and hash do not become available`() {
        start(); apiCreate(declaration().put("contentType","image/png"),409); apiCreate(declaration().put("sizeBytes",0),400)
        apiCreate(declaration().put("sizeBytes",1048577),409)
        val body=declaration(); val session=apiCreate(body); val id=session.path("uploadId").asText()
        apiPut(id,byteArrayOf(),409); apiPut(id,"toolong".toByteArray(),413); apiPut(id)
        apiComplete(id,body.deepCopy().put("collectorVersion","2.0"),409)
        apiComplete(id,body.deepCopy().put("sizeBytes",4),409)
        apiComplete(id,body.deepCopy().put("payloadChecksum","sha256:"+"a".repeat(64)),409)
        assertThat(downloads.metadata(user,session.path("evidenceId").asText()).path("state").asText()).isEqualTo("UPLOADING")
        val invalid=declaration("bad".toByteArray()).put("payloadChecksum","sha256:"+"b".repeat(64))
        val second=apiCreate(invalid); apiPut(second.path("uploadId").asText(),"bad".toByteArray(),409)
        apiComplete(second.path("uploadId").asText(),invalid,409)
        assertThat(downloads.metadata(user,second.path("evidenceId").asText()).path("state").asText()).isEqualTo("PENDING_UPLOAD")
    }
    @Test fun `stale lease and cancellation reject pending uploads and complete`() {
        start(); val body=declaration(); val session=apiCreate(body); val id=session.path("uploadId").asText(); apiPut(id)
        now=now.plusSeconds(90); apiComplete(id,body,409); apiPut(id,expected=409)
        val runId=jdbc.sql("SELECT test_run_id FROM test_attempt WHERE id=CAST(:a AS uuid)").param("a",command.path("attemptId").asText()).query(String::class.java).single()
        cancel.cancel(user,runId,"synthetic cancel","cancel","cancel")
        assertThat(downloads.metadata(user,session.path("evidenceId").asText()).path("state").asText()).isEqualTo("UPLOADING")
    }
    @Test fun `Agent from another project cannot create put or complete`() {
        start(); val body=declaration(); val session=apiCreate(body)
        fixture()
        fingerprint=jdbc.sql("SELECT certificate_sha256 FROM agent WHERE id=:a").param("a",agent).query(String::class.java).single()
        apiCreate(body,403); apiPut(session.path("uploadId").asText(),expected=403); apiComplete(session.path("uploadId").asText(),body,403)
    }
}
