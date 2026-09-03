package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceCanonicalizer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer

@EnabledIfEnvironmentVariable(named = "GITHUB_ACTIONS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.vsrqg.test",
        "spring.security.oauth2.resourceserver.jwt.audiences[0]=vsrqg-api",
        "vsrqg.traceability.ingestion.enabled=true",
    ],
)
class BuildProvenanceGithubSmokeTest {
    @LocalServerPort
    private var port: Int = 0

    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var canonicalizer: BuildProvenanceCanonicalizer

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private lateinit var context: GithubContext
    private lateinit var fixture: BuildProvenanceTestFixture

    @BeforeEach
    fun seedLockedAuthority() {
        context = GithubContext.load()
        val controlledArtifact = "vsrqg-m2.4:${context.commit}:${context.runId}:${context.runAttempt}".toByteArray()
        val artifactSha256 = digestHex(String(controlledArtifact, Charsets.UTF_8))
        fixture = BuildProvenanceFixtureSeeder(jdbc, transactionTemplate).seed(artifactSha256)
        transactionTemplate.executeWithoutResult {
            val manifestId = "mft_ing_${fixture.suffix}"
            val releaseId = "rel_ing_${fixture.suffix}"
            jdbc.sql(
                "UPDATE manifest_revision SET state = 'LOCKED', row_version = row_version + 1, updated_at = now() " +
                    "WHERE id = :manifestId AND state = 'REGISTERED'",
            ).param("manifestId", manifestId).update()
            jdbc.sql("UPDATE release_record SET locked_manifest_id = :manifestId WHERE id = :releaseId")
                .param("manifestId", manifestId)
                .param("releaseId", releaseId)
                .update()
        }
        Mockito.`when`(jwtDecoder.decode(SERVICE_TOKEN)).thenReturn(
            jwt(SERVICE_TOKEN, fixture.serviceSubject, "SERVICE", fixture.projectReference),
        )
        Mockito.`when`(jwtDecoder.decode(USER_TOKEN)).thenReturn(
            jwt(USER_TOKEN, fixture.userSubject, "USER", fixture.projectReference),
        )
        Mockito.`when`(jwtDecoder.decode(WRONG_PROJECT_TOKEN)).thenReturn(
            jwt(WRONG_PROJECT_TOKEN, fixture.serviceSubject, "SERVICE", "wrong-${fixture.suffix}"),
        )
    }

    @Test
    fun `exact head GitHub context produces a replayable redacted provenance chain over HTTP`() {
        val envelope = githubEnvelope()
        val first = post(envelope, "github-smoke-${fixture.suffix}", SERVICE_TOKEN)
        assertThat(first.statusCode).isEqualTo(HttpStatus.OK)

        val sameKey = post(envelope, "github-smoke-${fixture.suffix}", SERVICE_TOKEN)
        assertThat(sameKey.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(sameKey.body).isEqualTo(first.body)

        val differentKey = post(envelope, "github-smoke-replay-${fixture.suffix}", SERVICE_TOKEN)
        assertThat(differentKey.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(differentKey.body).isEqualTo(first.body)

        val conflicting = envelope.deepCopy().put("sourceRevision", alternateRevision(context.commit))
        val conflict = post(conflicting, "github-smoke-conflict-${fixture.suffix}", SERVICE_TOKEN)
        assertProblem(conflict.statusCode, conflict.body, HttpStatus.CONFLICT, "BUILD_PROVENANCE_CONFLICT")

        val userDenied = post(envelope, "github-smoke-user-${fixture.suffix}", USER_TOKEN)
        assertProblem(userDenied.statusCode, userDenied.body, HttpStatus.FORBIDDEN, "PROJECT_SCOPE_MISMATCH")

        val wrongProject = post(envelope, "github-smoke-project-${fixture.suffix}", WRONG_PROJECT_TOKEN)
        assertProblem(wrongProject.statusCode, wrongProject.body, HttpStatus.FORBIDDEN, "PROJECT_SCOPE_MISMATCH")

        val accepted = requireNotNull(first.body)
        val result = objectMapper.readTree(accepted)
        assertThat(result.path("verificationStatus").asText()).isEqualTo("VALID")
        assertThat(result.path("confidence").asText()).isEqualTo("MEDIUM")
        assertThat(result.path("edgeRevisions")).hasSize(3)
        assertDatabaseChain()
        writeEvidence(result)
    }

    private fun githubEnvelope(): ObjectNode {
        val draft = fixture.envelope(
            objectMapper = objectMapper,
            proofReference = context.proofReference,
            proofDigest = PLACEHOLDER_DIGEST,
        ).put("repository", context.repository)
            .put("sourceRevision", context.commit)
            .put("pipeline", context.job)
            .put("buildId", context.runId)
            .put("buildAttempt", context.runAttempt)
            .put("workflowReference", context.workflowReference)
        val proofDigest = canonicalizer.canonicalize(draft.toDomainEnvelope()).recomputedProofDigest
        return draft.put("proofDigest", proofDigest)
    }

    private fun ObjectNode.toDomainEnvelope() = com.ricezhou.vsrqg.traceability.domain.BuildProvenanceEnvelope(
        schemaVersion = path("schemaVersion").asInt(),
        projectReference = path("project").asText(),
        releaseIssueSnapshotId = path("releaseIssueSnapshotId").asText(),
        provider = com.ricezhou.vsrqg.traceability.domain.ProvenanceProviderId("github-actions"),
        repository = path("repository").asText(),
        sourceRevision = path("sourceRevision").asText(),
        pipeline = path("pipeline").asText(),
        buildId = path("buildId").asText(),
        buildAttempt = path("buildAttempt").asInt(),
        workflowReference = path("workflowReference").asText(),
        proofReference = path("proofReference").asText(),
        proofDigest = path("proofDigest").asText(),
        sourceIssueIds = path("sourceIssueIds").map(JsonNode::asText),
        artifactSha256s = path("artifactSha256s").map(JsonNode::asText),
    )

    private fun post(body: ObjectNode, key: String, token: String) = restTemplate.exchange(
        "http://127.0.0.1:$port/api/v1/traceability/facts:ingest",
        HttpMethod.POST,
        HttpEntity(objectMapper.writeValueAsBytes(body), HttpHeaders().also {
            it.contentType = MediaType.APPLICATION_JSON
            it.setBearerAuth(token)
            it.set("Idempotency-Key", key)
        }),
        String::class.java,
    )

    private fun assertProblem(
        actualStatus: HttpStatusCode,
        body: String?,
        expectedStatus: HttpStatusCode,
        expectedCode: String,
    ) {
        assertThat(actualStatus).isEqualTo(expectedStatus)
        assertThat(objectMapper.readTree(requireNotNull(body)).path("code").asText()).isEqualTo(expectedCode)
    }

    private fun assertDatabaseChain() {
        assertThat(countProject("build_provenance_receipt")).isOne()
        assertThat(countProject("build_provenance_rejected_receipt")).isOne()
        assertThat(countProject("traceability_edge_identity")).isEqualTo(3)
        assertThat(countProject("issue_commit_edge_revision")).isOne()
        assertThat(countProject("commit_build_edge_revision")).isOne()
        assertThat(countProject("build_artifact_edge_revision")).isOne()
        assertThat(countProject("audit_event")).isEqualTo(2)
        assertThat(
            jdbc.sql(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id IN " +
                    "(SELECT id FROM build_provenance_receipt WHERE project_id = :projectId)",
            ).param("projectId", fixture.projectId).query(Int::class.java).single(),
        ).isOne()
        assertThat(
            jdbc.sql(
                "SELECT count(*) FROM traceability_edge_identity " +
                    "WHERE project_id = :projectId AND edge_type = 'ARTIFACT_RELEASE'",
            ).param("projectId", fixture.projectId).query(Int::class.java).single(),
        ).isZero()
        assertThat(
            jdbc.sql(
                "SELECT count(*) FROM release_record release " +
                    "JOIN manifest_revision manifest ON manifest.id = release.locked_manifest_id " +
                    "WHERE release.project_id = :projectId AND manifest.state = 'LOCKED'",
            ).param("projectId", fixture.projectId).query(Int::class.java).single(),
        ).isOne()
    }

    private fun countProject(table: String): Int {
        require(table in PROJECT_TABLES)
        return jdbc.sql("SELECT count(*) FROM $table WHERE project_id = :projectId")
            .param("projectId", fixture.projectId)
            .query(Int::class.java)
            .single()
    }

    private fun writeEvidence(result: JsonNode) {
        val outputDirectory = Path.of("build", "m2").toAbsolutePath().normalize()
        Files.createDirectories(outputDirectory)
        val document = objectMapper.createObjectNode()
            .put("schemaVersion", 2)
            .put("exactCommit", context.commit)
            .put("runId", context.runId)
            .put("runAttempt", context.runAttempt)
            .put("validatorVersion", result.path("validatorVersion").asText())
            .put("envelopeDigest", result.path("envelopeDigest").asText())
            .put("artifactDigest", "sha256:${fixture.artifactSha256}")
        document.putArray("edgeRevisionIds").addAll(result.path("edgeRevisions").map { revision ->
            objectMapper.createObjectNode()
                .put("edgeId", revision.path("edgeId").asText())
                .put("revisionId", revision.path("revisionId").asText())
        })
        document.putObject("replayResults")
            .put("sameIdempotencyKey", true)
            .put("differentIdempotencyKey", true)
        document.putArray("fixedDiagnostics")
            .add("BUILD_PROVENANCE_CONFLICT")
            .add("PROJECT_SCOPE_MISMATCH")
        document.putObject("testCounts")
            .put("acceptedRequests", 3)
            .put("rejectedRequests", 3)
            .put("receipts", 1)
            .put("edgeIdentities", 3)
            .put("edgeRevisions", 3)
            .put("auditEvents", 2)
            .put("outboxEvents", 1)
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
            outputDirectory.resolve("build-provenance-smoke.json").toFile(),
            document,
        )
    }

    private fun jwt(token: String, subject: String, principalType: String, project: String): Jwt =
        Jwt.withTokenValue(token)
            .header("alg", "none")
            .issuer(ISSUER)
            .subject(subject)
            .issuedAt(Instant.parse("2026-09-03T00:00:00Z"))
            .expiresAt(Instant.parse("2099-09-03T00:00:00Z"))
            .claim("principal_type", principalType)
            .claim("project", project)
            .claim("scope", "traceability:ingest")
            .build()

    private fun alternateRevision(commit: String): String =
        (if (commit[0] == 'a') "b" else "a") + commit.drop(1)

    private data class GithubContext(
        val repository: String,
        val commit: String,
        val workflowReference: String,
        val runId: String,
        val runAttempt: Int,
        val job: String,
    ) {
        val proofReference: String
            get() = "https://github.com/$repository/actions/runs/$runId/attempts/$runAttempt"

        companion object {
            fun load() = GithubContext(
                repository = required("GITHUB_REPOSITORY"),
                commit = required("GITHUB_SHA"),
                workflowReference = required("GITHUB_WORKFLOW_REF"),
                runId = required("GITHUB_RUN_ID"),
                runAttempt = required("GITHUB_RUN_ATTEMPT").toIntOrNull()
                    ?: error("GITHUB_RUN_ATTEMPT_INVALID"),
                job = required("GITHUB_JOB"),
            )

            private fun required(name: String): String =
                System.getenv(name)?.takeIf(String::isNotBlank) ?: error("${name}_MISSING")
        }
    }

    companion object {
        private const val SERVICE_TOKEN = "m2-smoke-service"
        private const val USER_TOKEN = "m2-smoke-user"
        private const val WRONG_PROJECT_TOKEN = "m2-smoke-wrong-project"
        private const val PLACEHOLDER_DIGEST =
            "sha256:0000000000000000000000000000000000000000000000000000000000000000"
        private val PROJECT_TABLES = setOf(
            "build_provenance_receipt",
            "build_provenance_rejected_receipt",
            "traceability_edge_identity",
            "issue_commit_edge_revision",
            "commit_build_edge_revision",
            "build_artifact_edge_revision",
            "audit_event",
        )
        private val postgres = PostgreSQLContainer<Nothing>("postgres:17.11").apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
