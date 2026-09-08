package com.ricezhou.vsrqg.demo

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@Timeout(60)
class M1DemoIntegrationTest {
    @TempDir lateinit var root: Path

    @Test
    fun `real HTTP JWT RBAC file validation lock export replay and immutable history`() {
        val identity = M1DemoIdentity()
        // The container's jdbcUrl adds driver parameters that the demo intentionally rejects.
        val databaseUri = URI("postgresql", null, postgres.host, postgres.getMappedPort(5432),
            "/${postgres.databaseName}", null, null)
        val database = DemoDatabase("jdbc:$databaseUri", postgres.username, postgres.password)
        M1DemoMain.start(database, root, identity).use { context ->
            val actors = M1DemoBootstrap(context).initialize()
            val uri = URI("http://127.0.0.1:${context.webServer.port}")
            val result = M1DemoScenario(actors, root, M1DemoBootstrap(context)::lookupRejectedManifestId,
                Path.of("../demo/m1/sample-config.txt"), DemoReport(actors.runId, "a".repeat(40)), root)
                .run(uri, identity.token(actors.managerSubject), identity.token(actors.viewerSubject))
            assertThat(result.scenarioStatuses).hasSize(6).allSatisfy { _, status -> assertThat(status).isEqualTo("PASS") }
            val now = Instant.now()
            val tokens = listOf(
                M1DemoIdentity().token(actors.managerSubject),
                identity.token(actors.managerSubject, issuedAt = now.minusSeconds(1200)),
                identity.token(actors.managerSubject, issuer = "http://localhost/wrong"),
                identity.token(actors.managerSubject, audience = "wrong"),
                identity.token(actors.managerSubject, notBefore = now.plusSeconds(120)),
            )
            tokens.forEach { token ->
                val request = HttpRequest.newBuilder(uri.resolve("/api/v1/releases"))
                    .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                    .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build()
                assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(401)
            }
            assertThat(context.environment.getProperty("server.address")).isEqualTo("127.0.0.1")
            assertThat(context.environment.getProperty("vsrqg.deployment.mode")).isEqualTo("PILOT")
            assertThat(context.environment.getProperty("vsrqg.evidence.archive.provider")).isEqualTo("NONE")
        }
    }

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:17.11")
            .withDatabaseName("vsrqg_demo")
    }
}
