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

    @Test
    fun `known oversized body is rejected before consuming any stream bytes`() {
        val stream = CountingStream(100_000)
        val result = mvc.perform(streamRequest(stream, 100_000)).andReturn()
        org.assertj.core.api.Assertions.assertThat(stream.consumed).isZero()
        org.assertj.core.api.Assertions.assertThat(result.response.status).isEqualTo(413)
        verifyNoInteractions(registration)
    }

    @Test
    fun `unknown body length stops reading after the first excess byte`() {
        val stream = CountingStream(100_000)
        val result = mvc.perform(streamRequest(stream, -1)).andReturn()
        org.assertj.core.api.Assertions.assertThat(stream.consumed).isEqualTo(65_537)
        org.assertj.core.api.Assertions.assertThat(result.response.status).isEqualTo(413)
        verifyNoInteractions(registration)
    }

    @Test
    fun `body exactly at the limit is accepted`() {
        post(body.padEnd(65_536, ' ')).andExpect { status { isOk() } }
    }

    @Test
    fun `UTF8 registration text reaches the application unchanged`() {
        post(body.replace("0.2.0", "版本-1")).andExpect { status { isOk() } }
        val captured = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode::class.java)
        org.mockito.Mockito.verify(registration).register(anyString(),
            captured.capture() ?: com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
            anyString(), anyString(), anyString())
        org.assertj.core.api.Assertions.assertThat(captured.value.path("agentVersion").asText()).isEqualTo("版本-1")
    }

    @Test
    fun `empty body invalid UTF8 and unsupported media types are rejected`() {
        post("").andExpect { status { isBadRequest() } }
        for (type in listOf("text/plain", "application/json;charset=ISO-8859-1")) {
            mvc.post("/agent-api/v1/agents:register") {
                requestAttr("jakarta.servlet.request.X509Certificate", arrayOf(TestAgentCertificates.trustedCertificate))
                header("Idempotency-Key", "media-test"); contentType = MediaType.parseMediaType(type); content = body
            }.andExpect { status { isUnsupportedMediaType() } }
        }
        mvc.post("/agent-api/v1/agents:register") {
            requestAttr("jakarta.servlet.request.X509Certificate", arrayOf(TestAgentCertificates.trustedCertificate))
            header("Idempotency-Key", "utf8-test"); contentType = MediaType.APPLICATION_JSON
            content = byteArrayOf(0xc3.toByte(), 0x28)
        }.andExpect { status { isBadRequest() } }
        verifyNoInteractions(registration)
    }

    private fun streamRequest(stream: jakarta.servlet.ServletInputStream, length: Int) = org.springframework.test.web.servlet.RequestBuilder { context ->
        object : org.springframework.mock.web.MockHttpServletRequest(context, "POST", "/agent-api/v1/agents:register") {
            override fun getInputStream() = stream
            override fun getContentLength() = length
            override fun getContentLengthLong() = length.toLong()
        }.apply {
            servletPath = "/agent-api/v1/agents:register"
            contentType = MediaType.APPLICATION_JSON_VALUE
            addHeader("Idempotency-Key", "body-limit-test")
            setAttribute("jakarta.servlet.request.X509Certificate", arrayOf(TestAgentCertificates.trustedCertificate))
        }
    }

    private class CountingStream(private val size: Int) : jakarta.servlet.ServletInputStream() {
        var consumed: Int = 0
            private set
        override fun read(): Int = if (consumed == size) -1 else { consumed++; ' '.code }
        override fun isFinished() = consumed == size
        override fun isReady() = true
        override fun setReadListener(listener: jakarta.servlet.ReadListener?) = error("Synchronous test stream")
    }

    private fun post(source: String, key: String = "test-key") = mvc.post("/agent-api/v1/agents:register") {
        requestAttr("jakarta.servlet.request.X509Certificate", arrayOf(TestAgentCertificates.trustedCertificate))
        header("Idempotency-Key", key); contentType = MediaType.APPLICATION_JSON; content = source
    }

    private val body = """{"messageType":"AGENT_REGISTRATION","protocolVersion":"1.0","agentVersion":"0.2.0","supportedProtocolVersions":["1.0"],"deviceRef":"device-test","capabilities":["ADB"],"collectorVersions":{}}"""
}
