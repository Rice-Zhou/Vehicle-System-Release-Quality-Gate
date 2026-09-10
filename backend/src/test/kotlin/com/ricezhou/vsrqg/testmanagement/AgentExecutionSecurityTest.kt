package com.ricezhou.vsrqg.testmanagement

import com.ricezhou.vsrqg.access.adapter.SecurityConfig
import com.ricezhou.vsrqg.access.application.AuthenticatedPrincipalResolver
import com.ricezhou.vsrqg.shared.id.UuidV7IdGenerator
import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import com.ricezhou.vsrqg.testmanagement.adapter.*
import com.ricezhou.vsrqg.testmanagement.application.*
import com.ricezhou.vsrqg.testmanagement.domain.ResultCanonicalizer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(controllers=[AgentExecutionController::class,TestRunController::class,AgentResultController::class],
    properties=["spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.vsrqg.test","spring.security.oauth2.resourceserver.jwt.audiences[0]=vsrqg-api"],
    excludeFilters=[org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
        classes=[com.ricezhou.vsrqg.traceability.adapter.BuildProvenancePayloadLimitFilter::class])])
@Import(SecurityConfig::class,AgentSecurityConfiguration::class,ProblemWriter::class,RequestIdFilter::class,
    UuidV7IdGenerator::class,TestWire::class,TestExecutionProblemHandler::class)
@Timeout(60)
class AgentExecutionSecurityTest {
    @Autowired lateinit var mvc:MockMvc
    @MockitoBean lateinit var heartbeat:HeartbeatAgent
    @MockitoBean lateinit var claim:ClaimCommand
    @MockitoBean lateinit var ack:AcknowledgeCommand
    @MockitoBean lateinit var attempts:AttemptAccess
    @MockitoBean lateinit var access:AgentAccess
    @MockitoBean lateinit var repository:TestRunRepository
    @MockitoBean lateinit var create:CreateTestRun
    @MockitoBean lateinit var cancel:CancelTestRun
    @MockitoBean lateinit var results:GetTestRunResults
    @MockitoBean lateinit var submit:SubmitAttemptResult
    @MockitoBean lateinit var events:AppendCommandEvent
    @MockitoBean lateinit var principals:AuthenticatedPrincipalResolver
    @MockitoBean lateinit var decoder:JwtDecoder

    @ParameterizedTest
    @CsvSource("occurredAt,not-a-date","occurredAt,2026-02-30T00:00:00Z",
        "startedAt,not-a-date","startedAt,2026-02-30T00:00:00Z",
        "finishedAt,not-a-date","finishedAt,2026-02-30T00:00:00Z")
    fun `raw HTTP rejects invalid protocol times before application`(field:String,value:String) {
        echoAcceptedRequests()
        val result=field!="occurredAt"
        val valid=if(result) resultBody("1") else eventBody()
        val body=valid.replace(Regex("\"$field\":\"[^\"]*\""),"\"$field\":\"$value\"")
        rawRequest(body,result)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("INVALID_REQUEST"))
        verifyNoInteractions(access,submit,events)
    }

    @ParameterizedTest
    @ValueSource(strings=["result-fence","event-fence","event-sequence","event-payload"])
    fun `raw HTTP rejects decimals that would silently round before application`(field:String) {
        echoAcceptedRequests()
        val body=when(field) {
            "result-fence" -> resultBody("1.0000000000000001")
            "event-fence" -> eventBody(fence="1.0000000000000001")
            "event-sequence" -> eventBody(sequence="1.0000000000000001")
            else -> eventBody(measurement="0.1234567890123456789")
        }
        rawRequest(body,field.startsWith("result"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("INVALID_REQUEST"))
        verifyNoInteractions(access,submit,events)
    }

    @Test fun `raw HTTP preserves supported decimals and equivalent integer digests`() {
        val accepted=echoAcceptedRequests()
        for(integer in listOf("1","1.0")) {
            rawRequest(resultBody(integer),true)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk)
            rawRequest(eventBody(integer,integer,"0.125"),false)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk)
        }
        assertEquals(4,accepted.size)
        assertEquals(ResultCanonicalizer.digest(accepted[0]),ResultCanonicalizer.digest(accepted[2]))
        assertEquals(TestJson.digest(accepted[1]),TestJson.digest(accepted[3]))
        for(node in listOf(accepted[1],accepted[3])) {
            assertEquals(0,java.math.BigDecimal("0.125").compareTo(node.path("payload").path("measurement").decimalValue()))
            assertEquals(1L,node.path("fencingToken").longValue())
            assertEquals(1L,node.path("sequenceNo").longValue())
        }
    }

    @ParameterizedTest
    @ValueSource(strings=["9007199254740992.0","1e100"])
    fun `raw HTTP decimal notation cannot bypass safe integer range`(number:String) {
        echoAcceptedRequests()
        rawRequest(eventBody(sequence=number),false)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest)
        verifyNoInteractions(access,submit,events)
    }

    private fun echoAcceptedRequests():MutableList<JsonNode> {
        val accepted=mutableListOf<JsonNode>()
        val actor=AgentActor("principal","project","agent","device")
        doAnswer { actor }.`when`(access).requireAgent(anyString(),anyString())
        val echo=org.mockito.stubbing.Answer<JsonNode> { invocation ->
            invocation.getArgument<JsonNode>(1).also { accepted.add(it) }
        }
        doAnswer(echo).`when`(submit).submit(any(AgentActor::class.java) ?: actor,
            any(JsonNode::class.java) ?: ObjectMapper().createObjectNode(),anyString(),anyString())
        doAnswer(echo).`when`(events).append(any(AgentActor::class.java) ?: actor,
            any(JsonNode::class.java) ?: ObjectMapper().createObjectNode(),anyString(),anyString())
        return accepted
    }
    private fun rawRequest(body:String,result:Boolean)=mvc.perform(
        (if(result) org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/agent-api/v1/attempts/attempt/result")
        else org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/agent-api/v1/commands/cmd/events"))
            .requestAttr("jakarta.servlet.request.X509Certificate",arrayOf(TestAgentCertificates.trustedCertificate))
            .header("Idempotency-Key","precision-key").contentType(MediaType.APPLICATION_JSON)
            .content(body.toByteArray(Charsets.UTF_8)))
    private fun resultBody(fence:String)="""{"messageType":"ATTEMPT_RESULT","protocolVersion":"1.0","attemptId":"attempt","leaseId":"lease","fencingToken":$fence,"status":"PASS","startedAt":"2026-09-09T00:00:00Z","finishedAt":"2026-09-09T00:00:01Z","resultDigest":"sha256:${"a".repeat(64)}","evidenceIds":["log"]}"""
    private fun eventBody(fence:String="1",sequence:String="1",measurement:String="0.125")="""{"messageType":"COMMAND_EVENT","protocolVersion":"1.0","commandId":"cmd","attemptId":"attempt","leaseId":"lease","fencingToken":$fence,"sequenceNo":$sequence,"eventType":"PROGRESS","occurredAt":"2026-09-09T00:00:00Z","payload":{"measurement":$measurement}}"""

    @Test fun `execution endpoints do not accept a JWT or proxy certificate header`() {
        mvc.get("/agent-api/v1/attempts/01992560-aaab-7000-8000-123456789abc/context") {
            header("Authorization","Bearer untrusted"); header("X-Client-Cert","untrusted")
        }.andExpect { status { isUnauthorized() } }
        verifyNoInteractions(attempts,access,decoder)
    }
    @Test fun `Agent certificate cannot authorize user results`() {
        mvc.get("/api/v1/test-runs/run_test/results") {
            requestAttr("jakarta.servlet.request.X509Certificate",arrayOf(TestAgentCertificates.trustedCertificate))
        }.andExpect { status { isUnauthorized() } }
        verifyNoInteractions(cancel)
    }
    @Test fun `strict ACK schema rejects extra fencing fields without invoking application`() {
        post("""{"messageType":"COMMAND_ACK","protocolVersion":"1.0","status":"ACCEPTED","fencingToken":99}""")
            .andExpect { status { isBadRequest() }; jsonPath("$.code") { value("INVALID_REQUEST") } }
        post("""{"messageType":"COMMAND_ACK","protocolVersion":"1.0","status":"REJECTED"}""")
            .andExpect { status { isBadRequest() } }
        verifyNoInteractions(ack)
    }
    @Test fun `a second JSON document after a valid ACK is rejected before application code`() {
        post("""{"messageType":"COMMAND_ACK","protocolVersion":"1.0","status":"ACCEPTED"} {}""")
            .andExpect { status { isBadRequest() }; jsonPath("$.code") { value("INVALID_REQUEST") } }
        verifyNoInteractions(ack)
    }
    @Test fun `oversized duplicate key and wrong media requests fail at the boundary`() {
        post("x".repeat(65537)).andExpect { status { isPayloadTooLarge() }; jsonPath("$.code") { value("PAYLOAD_TOO_LARGE") } }
        post("""{"messageType":"COMMAND_ACK","protocolVersion":"1.0","status":"ACCEPTED","status":"REJECTED"}""")
            .andExpect { status { isBadRequest() } }
        post("{}",MediaType.TEXT_PLAIN).andExpect { status { isUnsupportedMediaType() } }
        verifyNoInteractions(ack)
    }
    private fun post(body:String,type:MediaType=MediaType.APPLICATION_JSON)=mvc.post("/agent-api/v1/commands/cmd_test:ack") {
        requestAttr("jakarta.servlet.request.X509Certificate",arrayOf(TestAgentCertificates.trustedCertificate))
        header("Idempotency-Key","ack-key"); contentType=type; content=body
    }
    @Test fun `Result and Event reject JWT and proxy identity before application`() {
        for(path in listOf("/agent-api/v1/attempts/attempt/result","/agent-api/v1/commands/cmd/events")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                if(path.endsWith("result")) org.springframework.http.HttpMethod.PUT else org.springframework.http.HttpMethod.POST,path)
                .header("Authorization","Bearer untrusted").header("X-Client-Cert","untrusted"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized)
        }
        verifyNoInteractions(submit,events,access,decoder)
    }
    @Test fun `Result strict wire rejects duplicate ids unknown fields overflow and mismatched path`() {
        val valid="""{"messageType":"ATTEMPT_RESULT","protocolVersion":"1.0","attemptId":"attempt","leaseId":"lease","fencingToken":1,
            "status":"PASS","startedAt":"2026-09-09T00:00:00Z","finishedAt":"2026-09-09T00:00:01Z","resultDigest":"sha256:${"a".repeat(64)}","evidenceIds":["log"]}"""
        val invalid=listOf(valid.replace("[\"log\"]","[\"log\",\"log\"]"),valid.dropLast(1)+",\"sequenceNo\":1}",
            valid.replace("\"fencingToken\":1","\"fencingToken\":9223372036854775808"),
            valid.replace("\"fencingToken\":1","\"fencingToken\":9007199254740993"),
            valid.replace("\"attemptId\":\"attempt\"","\"attemptId\":\"other\""),valid+" {}",
            valid.replace("\"status\":\"PASS\"","\"status\":\"PASS\",\"status\":\"ERROR\""),
            valid.replace("\"fencingToken\":1","\"fencingToken\":1.5"))
        for(body in invalid) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/agent-api/v1/attempts/attempt/result")
                .requestAttr("jakarta.servlet.request.X509Certificate",arrayOf(TestAgentCertificates.trustedCertificate))
                .header("Idempotency-Key","key").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest)
        }
        verifyNoInteractions(submit,events)
    }
}
