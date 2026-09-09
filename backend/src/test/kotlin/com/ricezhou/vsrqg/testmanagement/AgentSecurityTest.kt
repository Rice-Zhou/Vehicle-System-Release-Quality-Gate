package com.ricezhou.vsrqg.testmanagement

import com.ricezhou.vsrqg.access.adapter.SecurityConfig
import com.ricezhou.vsrqg.shared.id.UuidV7IdGenerator
import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import com.ricezhou.vsrqg.testmanagement.adapter.AgentRegistrationController
import com.ricezhou.vsrqg.testmanagement.adapter.AgentSecurityConfiguration
import com.ricezhou.vsrqg.testmanagement.adapter.CertificateFingerprintExtractor
import com.ricezhou.vsrqg.testmanagement.application.RegisterAgent
import com.ricezhou.vsrqg.testmanagement.application.AgentRegistrationResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(
    controllers = [AgentRegistrationController::class],
    properties = ["spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.vsrqg.test", "spring.security.oauth2.resourceserver.jwt.audiences[0]=vsrqg-api"],
    excludeFilters = [org.springframework.context.annotation.ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE, classes = [com.ricezhou.vsrqg.traceability.adapter.BuildProvenancePayloadLimitFilter::class])],
)
@Import(SecurityConfig::class, AgentSecurityConfiguration::class, ProblemWriter::class, RequestIdFilter::class, UuidV7IdGenerator::class)
@Timeout(60)
class AgentSecurityTest {
    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var registration: RegisterAgent
    @MockitoBean lateinit var decoder: JwtDecoder

    @Test
    fun `valid certificate reaches registration with DER fingerprint`() {
        val fingerprint = CertificateFingerprintExtractor().extractPrincipal(TestAgentCertificates.trustedCertificate).toString()
        `when`(registration.register(anyString(), any<com.fasterxml.jackson.databind.JsonNode>() ?: com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(), anyString(), anyString(), anyString()))
            .thenReturn(AgentRegistrationResult(agentId = "agent-test"))
        post(body).andExpect { status { isOk() }; jsonPath("$.agentId") { value("agent-test") } }
        org.mockito.Mockito.verify(registration).register(
            org.mockito.ArgumentMatchers.eq(fingerprint) ?: "",
            any<com.fasterxml.jackson.databind.JsonNode>() ?: com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
            anyString(), anyString(), anyString(),
        )
    }

    @Test
    fun `missing certificate rejects bearer and proxy header without invoking registration or JWT decoder`() {
        mvc.post("/agent-api/v1/agents:register") {
            header("Authorization", "Bearer forged"); header("X-Client-Cert", "forged")
            header("Idempotency-Key", "test-key"); contentType = MediaType.APPLICATION_JSON; content = body
        }.andExpect { status { isUnauthorized() } }
        verifyNoInteractions(registration, decoder)
    }

    @Test
    fun `registration rejects unknown missing duplicate and invalid fields`() {
        for (invalid in listOf("{}", body.dropLast(1) + ",\"projectId\":\"other\"}", body.replace("\"deviceRef\":\"device-test\"", "\"deviceRef\":\"\""), body.dropLast(1) + ",\"agentVersion\":\"duplicate\"}")) {
            post(invalid).andExpect { status { isBadRequest() } }
        }
        verifyNoInteractions(registration)
    }

    @Test
    fun `empty idempotency key is rejected`() {
        post(body, " ").andExpect { status { isBadRequest() } }
        verifyNoInteractions(registration)
    }

    private fun post(source: String, key: String = "test-key") = mvc.post("/agent-api/v1/agents:register") {
        requestAttr("jakarta.servlet.request.X509Certificate", arrayOf(TestAgentCertificates.trustedCertificate))
        header("Idempotency-Key", key); contentType = MediaType.APPLICATION_JSON; content = source
    }

    private val body = """{"messageType":"AGENT_REGISTRATION","protocolVersion":"1.0","agentVersion":"0.2.0","supportedProtocolVersions":["1.0"],"deviceRef":"device-test","capabilities":["ADB"],"collectorVersions":{}}"""
}
