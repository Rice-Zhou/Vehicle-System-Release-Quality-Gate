package com.ricezhou.vsrqg.testmanagement

import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.http.MediaType
import java.security.cert.X509Certificate
import java.util.UUID

@AutoConfigureMockMvc
@TestPropertySource(properties = ["vsrqg.demo.agent-registration.enabled=true"])
@Timeout(60)
class AgentIdentityIntegrationTest : PostgresIntegrationTest() {
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var mvc: MockMvc
    private lateinit var fixture: AgentIdentityFixture

    @BeforeEach
    fun setup() { fixture = AgentIdentityFixture(jdbc, TestAgentCertificates.trustedCertificate) }

    @Test
    fun `registration replays same identity and audits once per idempotency key`() {
        val key = UUID.randomUUID().toString()
        val auditBefore = auditCount()
        val first = register(key).andExpect { status { isOk() }; jsonPath("$.agentId") { value(fixture.agentId) }; jsonPath("$.heartbeatIntervalSeconds") { value(20) }; jsonPath("$.leaseDurationSeconds") { value(90) } }.andReturn().response.contentAsString
        assertThat(register(key).andExpect { status { isOk() } }.andReturn().response.contentAsString).isEqualTo(first)
        register(UUID.randomUUID().toString()).andExpect { status { isOk() }; jsonPath("$.agentId") { value(fixture.agentId) } }
        assertThat(auditCount() - auditBefore).isEqualTo(2)
    }

    @Test
    fun `certificate cannot change device or supply project fields`() {
        register(body = fixture.body("another-device")).andExpect { status { isForbidden() } }
        register(body = fixture.body().dropLast(1) + ",\"projectId\":\"other-project\"}").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `disabled revoked wrong role and wrong project bindings are rejected even on replay`() {
        val key = UUID.randomUUID().toString()
        register(key).andExpect { status { isOk() } }
        jdbc.sql("UPDATE principal SET disabled = true WHERE id = :id").param("id", fixture.principalId).update()
        register(key).andExpect { status { isForbidden() } }
        jdbc.sql("UPDATE principal SET disabled = false WHERE id = :id").param("id", fixture.principalId).update()
        jdbc.sql("UPDATE agent SET revoked = true WHERE id = :id").param("id", fixture.agentId).update()
        register(key).andExpect { status { isForbidden() } }
        jdbc.sql("UPDATE agent SET revoked = false WHERE id = :id").param("id", fixture.agentId).update()
        jdbc.sql("UPDATE project_assignment SET role = 'VIEWER' WHERE principal_id = :id").param("id", fixture.principalId).update()
        register(key).andExpect { status { isForbidden() } }
        jdbc.sql("DELETE FROM project_assignment WHERE principal_id = :id").param("id", fixture.principalId).update()
        register(key).andExpect { status { isForbidden() } }
    }

    @Test
    fun `no common protocol is explicit upgrade required`() {
        register(body = fixture.body().replace("[\"1.0\"]", "[\"2.0\"]")).andExpect { status { isUpgradeRequired() }; jsonPath("$.code") { value("AGENT_PROTOCOL_UNSUPPORTED") } }
    }

    @Test
    fun `only active SERVICE principal device and project can register`() {
        jdbc.sql("UPDATE principal SET principal_type = 'USER' WHERE id = :id").param("id", fixture.principalId).update()
        register().andExpect { status { isForbidden() } }
        jdbc.sql("UPDATE principal SET principal_type = 'SERVICE' WHERE id = :id").param("id", fixture.principalId).update()
        jdbc.sql("UPDATE device SET disabled = true WHERE id = :id").param("id", fixture.deviceId).update()
        register().andExpect { status { isForbidden() } }
        jdbc.sql("UPDATE device SET disabled = false WHERE id = :id").param("id", fixture.deviceId).update()
        jdbc.sql("UPDATE project SET archived = true WHERE id = :id").param("id", fixture.projectId).update()
        register().andExpect { status { isForbidden() } }
    }

    @Test
    fun `database rejects rebinding certificate principal or project`() {
        for (column in listOf("principal_id", "project_id", "device_id", "certificate_sha256")) {
            org.assertj.core.api.Assertions.assertThatThrownBy {
                jdbc.sql("UPDATE agent SET $column = :other WHERE id = :id")
                    .param("other", if (column == "certificate_sha256") "f".repeat(64) else "other-test-binding")
                    .param("id", fixture.agentId).update()
            }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
        }
    }

    @Test
    fun `certificate request attribute cannot be replaced by a header or JWT`() {
        mvc.post("/agent-api/v1/agents:register") { header("X-Client-Cert", "untrusted"); header("Authorization", "Bearer untrusted"); contentType = MediaType.APPLICATION_JSON; content = "{}" }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `registration validates key and conflicting replay`() {
        val key = UUID.randomUUID().toString()
        register(key).andExpect { status { isOk() } }
        register(key, fixture.body().replace("0.2.0", "0.2.1")).andExpect { status { isConflict() } }
        register(" ").andExpect { status { isBadRequest() } }
    }

    private fun auditCount() = jdbc.sql("SELECT count(*) FROM audit_event WHERE aggregate_id = :id AND action = 'AGENT_REGISTERED'").param("id", fixture.agentId).query(Int::class.java).single()

    private fun register(key: String = UUID.randomUUID().toString(), body: String = fixture.body()) = mvc.post("/agent-api/v1/agents:register") {
        requestAttr("jakarta.servlet.request.X509Certificate", arrayOf<X509Certificate>(TestAgentCertificates.trustedCertificate))
        header("Idempotency-Key", key); contentType = MediaType.APPLICATION_JSON; content = body
    }
}
