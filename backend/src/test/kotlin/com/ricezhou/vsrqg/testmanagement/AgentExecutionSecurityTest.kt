package com.ricezhou.vsrqg.testmanagement

import com.ricezhou.vsrqg.access.adapter.SecurityConfig
import com.ricezhou.vsrqg.access.application.AuthenticatedPrincipalResolver
import com.ricezhou.vsrqg.shared.id.UuidV7IdGenerator
import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import com.ricezhou.vsrqg.testmanagement.adapter.*
import com.ricezhou.vsrqg.testmanagement.application.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
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

@WebMvcTest(controllers=[AgentExecutionController::class,TestRunController::class],
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
    @MockitoBean lateinit var principals:AuthenticatedPrincipalResolver
    @MockitoBean lateinit var decoder:JwtDecoder

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
}
