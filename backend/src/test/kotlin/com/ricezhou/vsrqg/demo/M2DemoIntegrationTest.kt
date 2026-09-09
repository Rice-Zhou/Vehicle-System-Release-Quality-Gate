package com.ricezhou.vsrqg.demo

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@Timeout(60)
class M2DemoIntegrationTest {
    @TempDir lateinit var output: Path

    @Test
    fun `real HTTP M1 to M2 preserves historical snapshot and rejects invalid facts`() {
        val identity = M1DemoIdentity()
        val sample = Path.of("../demo/m1/sample-config.txt")
        val sha = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sample))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val payload = Files.createDirectory(output.resolve("payload"))
        M1DemoMain.start(database(), payload, identity, includeM2 = true, payloadSha256 = sha).use { context ->
            val bootstrap = M1DemoBootstrap(context)
            val actors = bootstrap.initialize()
            val baseUri = URI("http://127.0.0.1:${context.webServer.port}")
            val m1 = M1DemoScenario(actors, payload, bootstrap::lookupRejectedManifestId, sample,
                DemoReport(actors.runId, "a".repeat(40)), output).run(
                baseUri, identity.token(actors.managerSubject), identity.token(actors.viewerSubject))
            val m2Actors = bootstrap.initializeM2(actors)

            val invalidBase = bootstrap.initialize()
            val invalidActors = bootstrap.initializeM2(invalidBase)
            val invalidOutput = Files.createDirectory(output.resolve("invalid"))
            val invalidM1 = M1DemoScenario(invalidBase, payload, bootstrap::lookupRejectedManifestId, sample,
                DemoReport(invalidBase.runId, "a".repeat(40)), invalidOutput).run(
                baseUri, identity.token(invalidBase.managerSubject), identity.token(invalidBase.viewerSubject))
            val invalid = M2InvalidFixture(
                invalidActors.sourceId,
                manager(identity, invalidBase), engineer(identity, invalidActors, invalidBase.projectKey),
                service(identity, invalidActors, invalidBase.projectKey), invalidM1,
            )

            val report = M2DemoReport(actors.runId, "a".repeat(40))
            val snapshots = linkedMapOf<String, ByteArray>()
            M2DemoScenario(report, invalidFixture = invalid,
                observeSnapshotBytes = { label, bytes -> snapshots[label] = bytes }).run(
                baseUri, manager(identity, actors), engineer(identity, m2Actors, actors.projectKey),
                service(identity, m2Actors, actors.projectKey), m2Actors.sourceId, m1, sha,
            )
            report.write(output)
            val aBytes = snapshots.getValue("A")
            val aAgainBytes = snapshots.getValue("A_AGAIN")
            val bBytes = snapshots.getValue("B")
            assertThat(aAgainBytes).isEqualTo(aBytes)
            val first = jacksonObjectMapper().readTree(aBytes).path("issues")
                .associateBy { it.path("sourceIssueId").asText() }
            assertThat(first.getValue("DEMO-1").path("fixed").asBoolean()).isTrue()
            assertThat(first.getValue("DEMO-1").path("included").asBoolean()).isTrue()
            assertThat(first.getValue("DEMO-2").path("included").asBoolean()).isFalse()
            assertThat(first.values.none { it.path("verified").asBoolean() }).isTrue()
            assertThat(jacksonObjectMapper().readTree(bBytes).path("issues").all { it.path("included").asBoolean() }).isTrue()
            assertThat(jacksonObjectMapper().readTree(bBytes).path("issues").none { it.path("verified").asBoolean() }).isTrue()
            val secondPath = jacksonObjectMapper().readTree(bBytes).path("issues")
                .single { it.path("sourceIssueId").asText() == "DEMO-2" }.path("path")
                .map { it.path("edgeType").asText() }
            assertThat(secondPath).containsExactly(
                "ISSUE_COMMIT", "COMMIT_BUILD", "BUILD_ARTIFACT", "ARTIFACT_RELEASE",
            )
            val json = jacksonObjectMapper().readTree(output.resolve("m2-summary.json").toFile())
            assertThat(json.path("status").asText()).isEqualTo("PASS")
            val a = json.path("issues").path("A")
            val b = json.path("issues").path("B")
            assertThat(a.path("DEMO-1").path("path")).hasSize(4)
            assertThat(a.path("DEMO-2").path("path")).isEmpty()
            assertThat(a.path("DEMO-2").path("gaps").first().path("diagnosticCode").asText())
                .isEqualTo("ISSUE_COMMIT_MISSING")
            assertThat(b.elements().asSequence().all { it.path("included").asBoolean() }).isTrue()
            assertThat((a.elements().asSequence() + b.elements().asSequence()).all { !it.path("verified").asBoolean() }).isTrue()
            assertThat(json.path("scenarioStatuses").path("invalidFactsRejected").asText()).isEqualTo("PASS")
            assertThat(json.path("traceabilitySnapshotIds").path("A").asText())
                .isNotEqualTo(json.path("traceabilitySnapshotIds").path("B").asText())
            assertThat(json.path("history").path("snapshotABytesStable").asBoolean()).isTrue()
        }
    }

    private fun manager(identity: M1DemoIdentity, actors: DemoActors) = identity.token(actors.managerSubject,
        scopes = "issue:configure issue:sync issue:read issue:snapshot traceability:read")
    private fun engineer(identity: M1DemoIdentity, actors: M2DemoActors, project: String) =
        identity.token(actors.engineerSubject, scopes = "traceability:verify traceability:read traceability:ingest",
            principalType = "USER", projectReference = project)
    private fun service(identity: M1DemoIdentity, actors: M2DemoActors, project: String) =
        identity.token(actors.serviceSubject, scopes = "traceability:ingest", principalType = "SERVICE",
            projectReference = project)
    private fun database(): DemoDatabase {
        val uri = URI("postgresql", null, postgres.host, postgres.getMappedPort(5432), "/${postgres.databaseName}", null, null)
        return DemoDatabase("jdbc:$uri", postgres.username, postgres.password)
    }

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:17.11").withDatabaseName("vsrqg_demo")
    }
}
