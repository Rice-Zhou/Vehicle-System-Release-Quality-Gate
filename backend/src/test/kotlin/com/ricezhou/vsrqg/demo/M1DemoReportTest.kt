package com.ricezhou.vsrqg.demo

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

@Timeout(60)
class M1DemoReportTest {
    @TempDir lateinit var output: Path

    @Test
    fun `failure keeps observed HTTP status and never marks unexecuted scenarios PASS`() {
        val report = DemoReport("d029fa0b-a4a7-415a-bf3e-86b4f65147ef", "a".repeat(40))
        report.begin(DemoScenario.VALID_FILE)
        report.http(503)
        report.fail(DemoFailure.HTTP_STATUS)
        report.write(output)
        val json = jacksonObjectMapper().readTree(output.resolve("summary.json").toFile())
        assertThat(json.path("status").asText()).isEqualTo("FAILED")
        assertThat(json.path("scenarioStatuses").path("validFileLockExport").asText()).isEqualTo("FAILED")
        assertThat(json.path("scenarioStatuses").path("corruptFileRejected").asText()).isEqualTo("NOT_RUN")
        assertThat(json.path("httpStatuses").path("validFileLockExport").first().asInt()).isEqualTo(503)
        assertThat(json.path("errorCodes").first().asText()).isEqualTo("DEMO_HTTP_STATUS")
    }

    @Test
    fun `actual unexpected HTTP status is retained without serializing response secrets`() {
        val report = DemoReport("d029fa0b-a4a7-415a-bf3e-86b4f65147ef", "a".repeat(40))
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/releases") { exchange ->
            val bytes = "PASSWORD_SECRET_SENTINEL".toByteArray()
            exchange.sendResponseHeaders(503, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val actors = DemoActors(report.runId, "project", "project-key", "manager-secret", "viewer-secret")
            org.assertj.core.api.Assertions.assertThatThrownBy {
                M1DemoScenario(actors, output, { _, _ -> error("lookup must not run") },
                    output.resolve("unused"), report, output).run(
                    java.net.URI("http://127.0.0.1:${server.address.port}"), "manager-token-secret", "viewer-token-secret")
            }.isInstanceOf(DemoHttpFailure::class.java)
            report.fail(DemoFailure.HTTP_STATUS)
            report.write(output)
            val text = Files.readString(output.resolve("summary.json"))
            val json = jacksonObjectMapper().readTree(text)
            assertThat(json.path("httpStatuses").path("unauthenticatedRejected").first().asInt()).isEqualTo(503)
            assertThat(json.path("scenarioStatuses").path("validFileLockExport").asText()).isEqualTo("NOT_RUN")
            assertThat(text).doesNotContain("PASSWORD_SECRET_SENTINEL", "manager-secret", "viewer-secret", "token-secret")
        } finally { server.stop(0) }
    }

    @Test
    fun `report serializes an explicit safe schema and preserves completed result on later failure`() {
        val report = DemoReport("d029fa0b-a4a7-415a-bf3e-86b4f65147ef", "a".repeat(40))
        report.begin(DemoScenario.UNAUTHENTICATED)
        report.http(401)
        report.pass()
        report.fail(DemoFailure.STARTUP)
        report.write(output)
        val json = jacksonObjectMapper().readTree(output.resolve("summary.json").toFile())
        assertThat(json.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "classification", "status", "runId", "codeCommit", "workingTreeDirty", "scenarioStatuses", "httpStatuses",
            "releaseId", "manifestId", "corruptReleaseId", "corruptManifestId", "contentDigest", "payloadSha256", "errorCodes", "apiErrorCodes")
        assertThat(json.path("scenarioStatuses").path("unauthenticatedRejected").asText()).isEqualTo("PASS")
        assertThat(Files.readString(output.resolve("summary.json"))).doesNotContain("password", "token", "jdbc", "subject")
    }
}
