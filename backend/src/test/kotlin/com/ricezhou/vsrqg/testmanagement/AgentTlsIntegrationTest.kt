package com.ricezhou.vsrqg.testmanagement

import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ContextConfiguration
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = [AgentTlsTestInitializer::class])
@Timeout(60)
class AgentTlsIntegrationTest : PostgresIntegrationTest() {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var jdbc: JdbcClient
    private lateinit var fixture: AgentIdentityFixture

    @BeforeEach
    fun setup() { fixture = AgentIdentityFixture(jdbc, TestAgentCertificates.trustedCertificate) }

    @Test
    fun `trusted TLS certificate registers and revoked identity cannot register`() {
        assertThat(register("trusted").statusCode()).isEqualTo(200)
        jdbc.sql("UPDATE agent SET revoked = true WHERE id = :id").param("id", fixture.agentId).update()
        assertThat(register("trusted").statusCode()).isEqualTo(403)
    }

    @Test
    fun `wrong project assignment cannot authorize certificate`() {
        jdbc.sql("DELETE FROM project_assignment WHERE principal_id = :id").param("id", fixture.principalId).update()
        jdbc.sql("INSERT INTO project(id, project_key, name, created_at) VALUES ('tls_other', 'tls_other', 'Other test project', now()) ON CONFLICT DO NOTHING").update()
        jdbc.sql("INSERT INTO project_assignment(project_id, principal_id, role, created_at) VALUES ('tls_other', :id, 'ENGINEER', now()) ON CONFLICT DO NOTHING").param("id", fixture.principalId).update()
        assertThat(register("trusted").statusCode()).isEqualTo(403)
    }

    @Test
    fun `untrusted and expired certificates fail TLS or certificate authentication`() {
        for (identity in listOf("untrusted", "expired")) {
            val failure = org.junit.jupiter.api.assertThrows<IOException> { register(identity) }
            assertThat(generateSequence<Throwable>(failure) { it.cause }.any { it is javax.net.ssl.SSLException }).isTrue()
        }
    }

    @Test
    fun `JWT and forwarded headers cannot replace TLS certificate and certificate cannot replace JWT`() {
        assertThat(register(null, bearer = true).statusCode()).isEqualTo(401)
        val request = HttpRequest.newBuilder(uri("/api/v1/releases/any")).timeout(Duration.ofSeconds(10)).GET().build()
        assertThat(client("trusted").send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401)
    }

    private fun register(identity: String?, bearer: Boolean = false): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(uri("/agent-api/v1/agents:register")).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
            .POST(HttpRequest.BodyPublishers.ofString(fixture.body()))
        if (bearer) builder.header("Authorization", "Bearer not-an-agent-certificate").header("X-Client-Cert", "untrusted")
        return client(identity).send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun uri(path: String) = URI("https://localhost:$port$path")
    private fun client(identity: String?) = HttpClient.newBuilder().sslContext(TestAgentCertificates.sslContext(identity)).connectTimeout(Duration.ofSeconds(10)).build()

}
