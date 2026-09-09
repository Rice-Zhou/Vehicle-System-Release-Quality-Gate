package com.ricezhou.vsrqg.testmanagement

import com.ricezhou.vsrqg.access.adapter.SecurityConfig
import com.ricezhou.vsrqg.shared.id.UuidV7IdGenerator
import com.ricezhou.vsrqg.shared.problem.ProblemHandler
import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import com.ricezhou.vsrqg.testmanagement.adapter.AgentRegistrationController
import com.ricezhou.vsrqg.testmanagement.adapter.AgentSecurityConfiguration
import com.ricezhou.vsrqg.testmanagement.application.RegisterAgent
import com.ricezhou.vsrqg.testmanagement.application.AgentRegistrationResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito
import org.mockito.Answers
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.test.context.ContextConfiguration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import javax.net.ssl.SSLException

/** Exercises the production filter chains over TLS; PostgreSQL authorization has separate integration coverage. */
@SpringBootTest(classes = [AgentTlsSecurityTest.Server::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.vsrqg.test", "spring.security.oauth2.resourceserver.jwt.audiences[0]=vsrqg-api", "management.endpoint.health.group.readiness.include=readinessState"])
@Timeout(60)
@org.springframework.test.context.ActiveProfiles("agent-tls-isolated")
@ContextConfiguration(initializers = [AgentTlsTestInitializer::class])
class AgentTlsSecurityTest {
    @LocalServerPort var port: Int = 0
    @org.springframework.beans.factory.annotation.Autowired lateinit var registration: RegisterAgent

    @Test
    fun `trusted TLS certificate reaches registration`() {
        assertThat(send("trusted", "/agent-api/v1/agents:register", true).statusCode()).isEqualTo(200)
    }

    @Test
    fun `valid user bearer cannot enter Agent chain and certificate cannot enter user chain`() {
        assertThat(send(null, "/api/v1/tls-test", bearer = true).statusCode()).isEqualTo(200)
        assertThat(send(null, "/agent-api/v1/agents:register", true, true).statusCode()).isEqualTo(401)
        assertThat(send("trusted", "/api/v1/tls-test").statusCode()).isEqualTo(401)
    }

    @Test
    fun `untrusted client certificate is rejected`() { assertRejected("untrusted") }

    @Test
    fun `expired client certificate is rejected`() { assertRejected("expired") }

    @Test
    fun `known length and chunked oversized HTTPS bodies are rejected before registration`() {
        Mockito.clearInvocations(registration)
        val bytes = ByteArray(100_000) { ' '.code.toByte() }
        for (publisher in listOf(
            HttpRequest.BodyPublishers.ofByteArray(bytes),
            HttpRequest.BodyPublishers.ofInputStream { java.io.ByteArrayInputStream(bytes) },
        )) {
            val request = HttpRequest.newBuilder(URI("https://localhost:$port/agent-api/v1/agents:register"))
                .version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(publisher).build()
            val response = HttpClient.newBuilder().sslContext(TestAgentCertificates.sslContext("trusted"))
                .connectTimeout(Duration.ofSeconds(10)).build().use { it.send(request, HttpResponse.BodyHandlers.ofString()) }
            assertThat(response.statusCode()).isEqualTo(413)
            assertThat(response.body()).contains("\"code\":\"PAYLOAD_TOO_LARGE\"")
        }
        Mockito.verifyNoInteractions(registration)
    }

    private fun assertRejected(identity: String) {
        val failure = org.junit.jupiter.api.assertThrows<IOException> { send(identity, "/agent-api/v1/agents:register", true) }
        assertThat(generateSequence<Throwable>(failure) { it.cause }.any { it is SSLException }).isTrue()
    }

    private fun send(identity: String?, path: String, post: Boolean = false, bearer: Boolean = false): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("https://localhost:$port$path")).timeout(Duration.ofSeconds(10))
            .header("X-Client-Cert", "untrusted-header")
        if (bearer) request.header("Authorization", "Bearer valid-test-user")
        if (post) request.header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
            .POST(HttpRequest.BodyPublishers.ofString("""{"messageType":"AGENT_REGISTRATION","protocolVersion":"1.0","agentVersion":"0.2.0","supportedProtocolVersions":["1.0"],"deviceRef":"device-test","capabilities":["ADB"],"collectorVersions":{}}"""))
        else request.GET()
        return HttpClient.newBuilder().sslContext(TestAgentCertificates.sslContext(identity)).connectTimeout(Duration.ofSeconds(10)).build()
            .send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.Profile("agent-tls-isolated")
    @EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class, FlywayAutoConfiguration::class])
    @Import(SecurityConfig::class, AgentSecurityConfiguration::class, AgentRegistrationController::class, ProblemWriter::class, ProblemHandler::class, RequestIdFilter::class, UuidV7IdGenerator::class)
    class Server {
        @Bean fun registration(): RegisterAgent = Mockito.mock(RegisterAgent::class.java) { invocation ->
            if (invocation.method.name == "register") AgentRegistrationResult(agentId = "tls-agent")
            else Answers.RETURNS_DEFAULTS.answer(invocation)
        }
        @Bean fun jwtDecoder() = JwtDecoder { token ->
            if (token != "valid-test-user") throw BadJwtException("Invalid test bearer")
            Jwt.withTokenValue(token).header("alg", "test-only").subject("test-user").claim("scope", "test:read").build()
        }
        @Bean fun userProbe() = UserProbe()
    }

    @RestController
    @org.springframework.context.annotation.Profile("agent-tls-isolated")
    class UserProbe {
        @GetMapping("/api/v1/tls-test") fun get() = mapOf("authenticated" to true)
    }

}
