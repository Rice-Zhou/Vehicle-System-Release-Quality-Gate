package com.ricezhou.vsrqg.demo

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Instant
import com.ricezhou.vsrqg.issue.adapter.IssueSourceRuntimeFactory
import com.ricezhou.vsrqg.issue.application.IssueSourceDescriptorRegistry
import com.ricezhou.vsrqg.issue.application.ActivateIssueMappingProfile
import com.ricezhou.vsrqg.issue.application.ActivateIssueMappingProfileCommand
import com.ricezhou.vsrqg.issue.adapter.IssueRuntimeConfigurationException
import com.ricezhou.vsrqg.issue.adapter.IssueRuntimeFailureCode
import com.ricezhou.vsrqg.issue.adapter.IssueSourceRuntimeRegistry
import com.ricezhou.vsrqg.issue.application.IssueSyncRunRecord
import com.ricezhou.vsrqg.issue.application.IssueSyncStatus
import com.ricezhou.vsrqg.access.application.AuthenticatedPrincipalResolver
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceValidatorPort
import com.ricezhou.vsrqg.traceability.application.TraceabilityIngestAuthorizer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.springframework.jdbc.core.simple.JdbcClient

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

    @Test
    fun `M2 startup selects isolated beans and bootstrap writes only input configuration`() {
        val database = demoDatabase()
        val identity = M1DemoIdentity()
        M1DemoMain.start(database, root, identity).use { m1 ->
            assertThat(m1.containsBean("m2DemoIssueFactory")).isFalse()
            assertThat(m1.containsBean("m2DemoDescriptorRegistry")).isFalse()
            assertThat(m1.containsBean("m2DemoProvenanceValidator")).isFalse()
        }

        M1DemoMain.start(database, root, identity, includeM2 = true, payloadSha256 = "0".repeat(64)).use { m2 ->
            assertThat(m2.getBean(BuildProvenanceValidatorPort::class.java))
                .isInstanceOf(M2DemoProvenanceValidator::class.java)
            assertThat(m2.getBean(IssueSourceDescriptorRegistry::class.java).require("FIXTURE"))
                .isEqualTo(M2DemoInputs.descriptor)
            assertThat(m2.getBeansOfType(IssueSourceRuntimeFactory::class.java))
                .containsKey("m2DemoIssueFactory")

            val baseActors = M1DemoBootstrap(m2).initialize()
            val actors = M1DemoBootstrap(m2).initializeM2(baseActors)
            val jdbc = m2.getBean(JdbcClient::class.java)
            assertThat(jdbc.sql("""
                SELECT principal_type FROM principal
                WHERE issuer = :issuer AND subject IN (:engineer, :service)
                ORDER BY principal_type
            """.trimIndent()).param("issuer", M1DemoIdentity.ISSUER)
                .param("engineer", actors.engineerSubject).param("service", actors.serviceSubject)
                .query(String::class.java).list()).containsExactly("SERVICE", "USER")
            assertThat(jdbc.sql("""
                SELECT count(*) FROM project_assignment assignment
                JOIN principal ON principal.id = assignment.principal_id
                WHERE assignment.project_id = :project
                  AND principal.subject IN (:engineer, :service)
                  AND assignment.role = 'ENGINEER'
            """.trimIndent()).param("project", baseActors.projectId)
                .param("engineer", actors.engineerSubject).param("service", actors.serviceSubject)
                .query(Int::class.java).single()).isEqualTo(2)
            assertThat(jdbc.sql("""
                SELECT count(*) FROM issue_source
                WHERE id = :source AND project_id = :project AND source_type = 'FIXTURE'
                  AND adapter_version = :adapter AND mapping_version = 'M2_DEMO_MAPPING_NOT_ACTIVATED'
                  AND credential_reference IS NULL AND enabled = true
            """.trimIndent()).param("source", actors.sourceId).param("project", baseActors.projectId)
                .param("adapter", M2DemoInputs.descriptor.adapterVersion)
                .query(Int::class.java).single()).isEqualTo(1)
            listOf("issue_mapping_profile", "issue_sync_run", "normalized_issue", "release_issue_snapshot",
                "source_commit", "build_record", "traceability_snapshot").forEach { table ->
                assertThat(jdbc.sql("SELECT count(*) FROM $table WHERE project_id = :project")
                    .param("project", baseActors.projectId).query(Int::class.java).single())
                    .describedAs(table).isZero()
            }

            val runtime = m2.getBean(IssueSourceRuntimeRegistry::class.java)
            val unactivatedRun = issueRun(baseActors, actors, "M2_DEMO_MAPPING_NOT_ACTIVATED")
            assertThatThrownBy { runtime.open(unactivatedRun) }
                .isInstanceOfSatisfying(IssueRuntimeConfigurationException::class.java) {
                    assertThat(it.code).isEqualTo(IssueRuntimeFailureCode.MAPPING_PROFILE_NOT_CONFIGURED)
                }

            val resolver = m2.getBean(AuthenticatedPrincipalResolver::class.java)
            val managerJwt = identity.decoder.decode(identity.token(baseActors.managerSubject))
            val manager = resolver.resolve(managerJwt.issuer.toString(), managerJwt.subject,
                managerJwt.getClaimAsString("principal_type"))
            val activation = m2.getBean(ActivateIssueMappingProfile::class.java).activate(
                ActivateIssueMappingProfileCommand(
                    principal = manager,
                    sourceId = actors.sourceId,
                    idempotencyKey = java.util.UUID.randomUUID().toString(),
                    definition = M2DemoInputs.mappingDefinition(),
                    requestId = java.util.UUID.randomUUID().toString(),
                ),
            )
            val issues = runtime.open(issueRun(baseActors, actors, activation.mappingVersion))
                .fetchByIds(setOf("DEMO-1", "DEMO-2")).issues
            assertThat(issues).extracting<String> { it.mappingVersion }.containsOnly(activation.mappingVersion)

            val serviceJwt = identity.decoder.decode(identity.token(
                subject = actors.serviceSubject,
                scopes = "traceability:ingest",
                principalType = "SERVICE",
                projectReference = baseActors.projectKey,
            ))
            val service = resolver.resolve(serviceJwt.issuer.toString(), serviceJwt.subject,
                serviceJwt.getClaimAsString("principal_type"))
            val authorization = m2.getBean(TraceabilityIngestAuthorizer::class.java).require(
                service,
                serviceJwt.getClaimAsString("project"),
                baseActors.projectKey,
            )
            assertThat(authorization.projectId).isEqualTo(baseActors.projectId)
            assertThat(authorization.projectReference).isEqualTo(baseActors.projectKey)
        }
    }

    private fun issueRun(baseActors: DemoActors, actors: M2DemoActors, mappingVersion: String) = IssueSyncRunRecord(
        id = java.util.UUID.randomUUID().toString(),
        projectId = baseActors.projectId,
        sourceId = actors.sourceId,
        status = IssueSyncStatus.QUEUED,
        cursorBefore = null,
        cursorAfter = null,
        sourceWatermark = null,
        adapterVersion = M2DemoInputs.descriptor.adapterVersion,
        mappingVersion = mappingVersion,
        resultSetMode = M2DemoInputs.descriptor.resultSetMode,
        filterReference = M2DemoInputs.descriptor.filterReference,
        issueCount = 0,
        warningCount = 0,
        diagnosticCode = null,
        createdAt = Instant.now(),
    )

    private fun demoDatabase(): DemoDatabase {
        val databaseUri = URI("postgresql", null, postgres.host, postgres.getMappedPort(5432),
            "/${postgres.databaseName}", null, null)
        return DemoDatabase("jdbc:$databaseUri", postgres.username, postgres.password)
    }

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:17.11")
            .withDatabaseName("vsrqg_demo")
    }
}
