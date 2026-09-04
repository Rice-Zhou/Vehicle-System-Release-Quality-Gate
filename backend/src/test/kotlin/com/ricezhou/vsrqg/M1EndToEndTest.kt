package com.ricezhou.vsrqg

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.access.adapter.JdbcProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.manifest.adapter.JdbcManifestRepository
import com.ricezhou.vsrqg.manifest.application.ExportManifest
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import com.ricezhou.vsrqg.shared.problem.ProblemHandler

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "vsrqg.manifest.trusted-validator-versions=m1-acceptance-validator/1",
    ],
)
class M1EndToEndTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private lateinit var failureAppender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun captureUnhandledApiFailures() {
        failureAppender = ListAppender<ILoggingEvent>().also { it.start() }
        (LoggerFactory.getLogger(ProblemHandler::class.java) as Logger).addAppender(failureAppender)
    }

    @AfterEach
    fun stopCapturingUnhandledApiFailures() {
        (LoggerFactory.getLogger(ProblemHandler::class.java) as Logger).detachAppender(failureAppender)
        failureAppender.stop()
    }

    @Test
    fun `create register validate lock export survives restore`() {
        val result = M1RecoveryScenario(mockMvc, jdbc, objectMapper, postgres, ::unhandledFailure).run(validManifest)

        assertThat(result.lockedDigest).isEqualTo(result.exportedDigest)
        assertThat(result.restoredDigest).isEqualTo(result.lockedDigest)
        assertThat(result.auditActions).containsExactly(
            "RELEASE_CREATED",
            "MANIFEST_REGISTERED",
            "MANIFEST_VALIDATED",
            "MANIFEST_LOCKED",
        )
        assertThat(result.restoredAuditRows).containsExactlyElementsOf(result.auditRows)
        assertThat(result.restoredReleaseHistoryRows).containsExactlyElementsOf(result.releaseHistoryRows)
        assertThat(result.restoredLockedValidationRow).isEqualTo(result.lockedValidationRow)
        assertThat(result.schemaExport).exists().isNotEmptyFile()
        assertThat(result.report).exists().isNotEmptyFile()
    }

    private fun unhandledFailure(): String? {
        val proxy = failureAppender.list.lastOrNull()?.throwableProxy ?: return null
        val root = generateSequence(proxy) { it.cause }.last()
        return "${root.className}: ${root.message}"
    }

    private companion object {
        val validManifest: String = Files.readString(
            Path.of("..").toAbsolutePath().normalize()
                .resolve("contracts/examples/v0.2/manifest/valid-apk.json"),
        )
    }
}

data class M1ScenarioResult(
    val lockedDigest: String,
    val exportedDigest: String,
    val restoredDigest: String,
    val auditActions: List<String>,
    val auditRows: List<String>,
    val restoredAuditRows: List<String>,
    val releaseHistoryRows: List<String>,
    val restoredReleaseHistoryRows: List<String>,
    val lockedValidationRow: String,
    val restoredLockedValidationRow: String,
    val schemaExport: Path,
    val report: Path,
)

class M1RecoveryScenario(
    private val mockMvc: MockMvc,
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
    private val sourceDatabase: PostgreSQLContainer<Nothing>,
    private val unhandledFailure: () -> String?,
) {
    fun run(manifestFixture: String): M1ScenarioResult {
        val suffix = System.nanoTime().toString(16)
        val buildId = "m1-build-$suffix"
        insertAuthorityFixtures()
        val releaseId = createRelease(buildId, suffix)
        val manifest = manifestFixture
            .replace("rel_01V02EXAMPLE", releaseId)
            .replace("build-1842", buildId)
            .replace("\"project\": \"vehicle-x\"", "\"project\": \"$PROJECT_KEY\"")
        val registration = registerManifest(releaseId, manifest, suffix)
        val manifestId = registration.path("manifestId").asText()
        val validation = validateManifest(releaseId, manifestId, suffix)
        persistTrustedValidation(manifestId, validation, suffix)
        val locked = lockManifest(releaseId, manifestId, suffix)
        val exported = exportManifest(releaseId, manifestId)
        val auditActions = auditActions(releaseId, manifestId)
        val auditRows = auditRows(jdbc, releaseId, manifestId)
        val releaseHistoryRows = releaseHistoryRows(jdbc, releaseId)
        val lockedValidationRow = lockedValidationRow(jdbc, manifestId)
        val outputDirectory = Path.of("build", "m1").toAbsolutePath().normalize()
        Files.createDirectories(outputDirectory)
        val schemaExport = outputDirectory.resolve("schema.sql")
        val dump = outputDirectory.resolve("m1.dump")
        exportDatabase(dump, schemaExport)
        val restored = restoreAndInspect(dump, releaseId, manifestId)
        val lockedDigest = locked.path("contentDigest").asText()
        val exportedDigest = exported.path("contentDigest").asText()
        check(auditActions == EXPECTED_AUDIT_ACTIONS) { "Source Audit timeline differs from the M1 acceptance sequence" }
        check(lockedDigest == exportedDigest && lockedDigest == restored.digest) {
            "Locked, exported, and restored Manifest digests differ"
        }
        check(restored.auditRows == auditRows) { "Restored Audit rows differ from source" }
        check(restored.releaseHistoryRows == releaseHistoryRows) { "Restored Release state history rows differ from source" }
        check(restored.lockedValidationRow == lockedValidationRow) { "Restored locked Validation row differs from source" }
        val report = writeReport(
            outputDirectory = outputDirectory,
            releaseId = releaseId,
            manifestId = manifestId,
            lockedDigest = lockedDigest,
            exportedDigest = exportedDigest,
            restoredDigest = restored.digest,
            auditActions = auditActions,
            auditRows = auditRows,
            restoredAuditRows = restored.auditRows,
            releaseHistoryRows = releaseHistoryRows,
            restoredReleaseHistoryRows = restored.releaseHistoryRows,
            lockedValidationRow = lockedValidationRow,
            restoredLockedValidationRow = restored.lockedValidationRow,
            schemaExport = schemaExport,
        )
        Files.deleteIfExists(dump)
        return M1ScenarioResult(
            lockedDigest = locked.path("contentDigest").asText(),
            exportedDigest = exported.path("contentDigest").asText(),
            restoredDigest = restored.digest,
            auditActions = auditActions,
            auditRows = auditRows,
            restoredAuditRows = restored.auditRows,
            releaseHistoryRows = releaseHistoryRows,
            restoredReleaseHistoryRows = restored.releaseHistoryRows,
            lockedValidationRow = lockedValidationRow,
            restoredLockedValidationRow = restored.lockedValidationRow,
            schemaExport = schemaExport,
            report = report,
        )
    }

    private fun createRelease(buildId: String, suffix: String): String {
        val response = mockMvc.post("/api/v1/releases") {
            with(operatorJwt("release:create"))
            header("Idempotency-Key", "m1-create-$suffix")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                mapOf(
                    "project" to PROJECT_KEY,
                    "vehicle" to "model-a",
                    "platform" to "android-automotive",
                    "systemVersion" to "2026.08-rc1",
                    "buildId" to buildId,
                ),
            )
        }.andExpect { status { isCreated() } }.andReturn().response
        return objectMapper.readTree(response.contentAsString).path("releaseId").asText()
    }

    private fun registerManifest(releaseId: String, manifest: String, suffix: String) = objectMapper.readTree(
        mockMvc.post("/api/v1/releases/{releaseId}/manifests", releaseId) {
            with(operatorJwt("manifest:write"))
            header("Idempotency-Key", "m1-register-$suffix")
            contentType = MediaType.APPLICATION_JSON
            content = manifest
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString,
    )

    private fun validateManifest(releaseId: String, manifestId: String, suffix: String): com.fasterxml.jackson.databind.JsonNode {
        val response = mockMvc.post(
            "/api/v1/releases/{releaseId}/manifests/{manifestId}:validate",
            releaseId,
            manifestId,
        ) {
            with(operatorJwt("manifest:write"))
            header("Idempotency-Key", "m1-validate-$suffix")
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"M1 acceptance validation"}"""
        }.andReturn().response
        assertHttpStatus("validate", response.status, response.contentAsString, 200)
        return objectMapper.readTree(response.contentAsString).also {
            check(it.path("status").asText() == "INCOMPLETE") { "Validate response is not INCOMPLETE: $it" }
        }
    }

    private fun lockManifest(releaseId: String, manifestId: String, suffix: String): com.fasterxml.jackson.databind.JsonNode {
        val response = mockMvc.post(
            "/api/v1/releases/{releaseId}/manifests/{manifestId}:lock",
            releaseId,
            manifestId,
        ) {
            with(operatorJwt("manifest:lock"))
            header("Idempotency-Key", "m1-lock-$suffix")
            header("If-Match", "\"1\"")
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"M1 trusted checksum fixture verified"}"""
        }.andReturn().response
        assertHttpStatus("lock", response.status, response.contentAsString, 200)
        return objectMapper.readTree(response.contentAsString).also {
            check(it.path("state").asText() == "LOCKED") { "Lock response is not LOCKED: $it" }
        }
    }

    private fun exportManifest(releaseId: String, manifestId: String): com.fasterxml.jackson.databind.JsonNode {
        val response = mockMvc.get("/api/v1/releases/{releaseId}/manifests/{manifestId}", releaseId, manifestId) {
            with(operatorJwt("release:read"))
        }.andReturn().response
        assertHttpStatus("export", response.status, response.contentAsString, 200)
        return objectMapper.readTree(response.contentAsString).also {
            check(it.path("state").asText() == "LOCKED") { "Export response is not LOCKED: $it" }
            check(it.path("validation").path("status").asText() == "VALID") { "Export validation is not VALID: $it" }
        }
    }

    private fun assertHttpStatus(stage: String, actual: Int, body: String, expected: Int) {
        check(actual == expected) {
            val rootCause = unhandledFailure()?.let { " rootCause=$it" }.orEmpty()
            "M1 $stage expected HTTP $expected but received $actual:$rootCause response=$body"
        }
    }

    private fun persistTrustedValidation(manifestId: String, sourceReport: com.fasterxml.jackson.databind.JsonNode, suffix: String) {
        val now = Instant.now()
        val report = sourceReport.deepCopy<ObjectNode>()
            .put("validationId", "mvl_m1_$suffix")
            .put("status", "VALID")
            .put("validatorVersion", TRUSTED_VALIDATOR)
            .put("validatedAt", now.toString())
        report.putArray("violations")
        val inserted = jdbc.sql(
            """
            INSERT INTO manifest_validation(
              id, manifest_id, status, content_digest, schema_version,
              validator_version, report, validated_at, created_at
            ) VALUES (
              :id, :manifestId, 'VALID', :contentDigest, :schemaVersion,
              :validatorVersion, CAST(:report AS jsonb), :validatedAt, :createdAt
            )
            """.trimIndent(),
        )
            .param("id", report.path("validationId").asText())
            .param("manifestId", manifestId)
            .param("contentDigest", report.path("contentDigest").asText())
            .param("schemaVersion", report.path("schemaVersion").asText())
            .param("validatorVersion", TRUSTED_VALIDATOR)
            .param("report", objectMapper.writeValueAsString(report))
            .param("validatedAt", now.atOffset(java.time.ZoneOffset.UTC))
            .param("createdAt", now.atOffset(java.time.ZoneOffset.UTC))
            .update()
        check(inserted == 1) { "Trusted M1 validation fixture was not persisted" }
    }

    private fun exportDatabase(dump: Path, schemaExport: Path) {
        containerCommand(
            sourceDatabase,
            "pg_dump",
            "-U",
            sourceDatabase.username,
            "-d",
            sourceDatabase.databaseName,
            "--format=custom",
            "--no-owner",
            "--no-privileges",
            "--file=/tmp/m1.dump",
        )
        containerCommand(
            sourceDatabase,
            "pg_dump",
            "-U",
            sourceDatabase.username,
            "-d",
            sourceDatabase.databaseName,
            "--schema-only",
            "--no-owner",
            "--no-privileges",
            "--file=/tmp/m1-schema.sql",
        )
        sourceDatabase.copyFileFromContainer("/tmp/m1.dump", dump.toString())
        sourceDatabase.copyFileFromContainer("/tmp/m1-schema.sql", schemaExport.toString())
    }

    private fun restoreAndInspect(dump: Path, releaseId: String, manifestId: String): RecoverySnapshot {
        val restored = PostgreSQLContainer<Nothing>("postgres:17.11")
        try {
            restored.start()
            restored.copyFileToContainer(MountableFile.forHostPath(dump), "/tmp/m1.dump")
            containerCommand(
                restored,
                "pg_restore",
                "-U",
                restored.username,
                "-d",
                restored.databaseName,
                "--exit-on-error",
                "--no-owner",
                "--no-privileges",
                "/tmp/m1.dump",
            )
            val dataSource = DriverManagerDataSource(restored.jdbcUrl, restored.username, restored.password)
            val restoredJdbc = JdbcClient.create(dataSource)
            val repository = JdbcManifestRepository(restoredJdbc, objectMapper)
            val authorizer = JdbcProjectAuthorizer(restoredJdbc)
            val digest = ExportManifest(repository, authorizer)
                .export(PRINCIPAL, releaseId, manifestId)
                .contentDigest
            return RecoverySnapshot(
                digest = digest,
                auditRows = auditRows(restoredJdbc, releaseId, manifestId),
                releaseHistoryRows = releaseHistoryRows(restoredJdbc, releaseId),
                lockedValidationRow = lockedValidationRow(restoredJdbc, manifestId),
            )
        } finally {
            restored.stop()
        }
    }

    private fun auditActions(releaseId: String, manifestId: String): List<String> =
        auditActions(jdbc, releaseId, manifestId)

    private fun auditActions(client: JdbcClient, releaseId: String, manifestId: String): List<String> = client.sql(
        """
        SELECT action FROM audit_event
        WHERE aggregate_id IN (:releaseId, :manifestId)
        ORDER BY occurred_at, id
        """.trimIndent(),
    )
        .param("releaseId", releaseId)
        .param("manifestId", manifestId)
        .query(String::class.java)
        .list()

    private fun auditRows(client: JdbcClient, releaseId: String, manifestId: String): List<String> = client.sql(
        """
        SELECT row_to_json(snapshot)::text FROM (
          SELECT id, event_id, project_id, actor_id, action, aggregate_type, aggregate_id,
                 before_state, after_state, reason, correlation_id, occurred_at, created_at
          FROM audit_event WHERE aggregate_id IN (:releaseId, :manifestId)
          ORDER BY occurred_at, id
        ) snapshot
        """.trimIndent(),
    ).param("releaseId", releaseId).param("manifestId", manifestId).query(String::class.java).list()

    private fun releaseHistoryRows(client: JdbcClient, releaseId: String): List<String> = client.sql(
        """
        SELECT row_to_json(snapshot)::text FROM (
          SELECT id, release_id, previous_status, new_status, actor_id, reason, occurred_at, created_at
          FROM release_state_history WHERE release_id = :releaseId ORDER BY occurred_at, id
        ) snapshot
        """.trimIndent(),
    ).param("releaseId", releaseId).query(String::class.java).list()

    private fun lockedValidationRow(client: JdbcClient, manifestId: String): String = client.sql(
        """
        SELECT row_to_json(snapshot)::text FROM (
          SELECT v.id, v.manifest_id, v.status, v.content_digest, v.schema_version,
                 v.validator_version, v.report, v.validated_at, v.created_at
          FROM manifest_revision m
          JOIN manifest_validation v ON v.id = m.locked_validation_id
          WHERE m.id = :manifestId
        ) snapshot
        """.trimIndent(),
    ).param("manifestId", manifestId).query(String::class.java).single()

    private fun writeReport(
        outputDirectory: Path,
        releaseId: String,
        manifestId: String,
        lockedDigest: String,
        exportedDigest: String,
        restoredDigest: String,
        auditActions: List<String>,
        auditRows: List<String>,
        restoredAuditRows: List<String>,
        releaseHistoryRows: List<String>,
        restoredReleaseHistoryRows: List<String>,
        lockedValidationRow: String,
        restoredLockedValidationRow: String,
        schemaExport: Path,
    ): Path {
        val report = outputDirectory.resolve("acceptance-smoke.json")
        val document = objectMapper.createObjectNode()
            .put("schemaVersion", 1)
            .put("status", "PASS")
            .put("databaseImage", "postgres:17.11")
            .put("trustedValidationFixture", TRUSTED_VALIDATOR)
            .put("releaseId", releaseId)
            .put("manifestId", manifestId)
            .put("lockedDigest", lockedDigest)
            .put("exportedDigest", exportedDigest)
            .put("restoredDigest", restoredDigest)
            .put("candidateCommit", candidateCommit())
            .put("schemaSha256", sha256(schemaExport))
            .put("lockedValidationRow", lockedValidationRow)
            .put("restoredLockedValidationRow", restoredLockedValidationRow)
            .put("generatedAt", Instant.now().toString())
        document.putArray("auditActions").addAll(auditActions.map(objectMapper.nodeFactory::textNode))
        document.putArray("auditRows").addAll(auditRows.map(objectMapper.nodeFactory::textNode))
        document.putArray("restoredAuditRows").addAll(restoredAuditRows.map(objectMapper.nodeFactory::textNode))
        document.putArray("releaseHistoryRows").addAll(releaseHistoryRows.map(objectMapper.nodeFactory::textNode))
        document.putArray("restoredReleaseHistoryRows").addAll(restoredReleaseHistoryRows.map(objectMapper.nodeFactory::textNode))
        Files.writeString(report, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(document))
        return report
    }

    private fun candidateCommit(): String {
        val process = ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start()
        val value = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0 && value.matches(Regex("[0-9a-f]{40}"))) { "Unable to resolve candidate commit: $value" }
        return value
    }

    private fun sha256(path: Path): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun insertAuthorityFixtures() {
        jdbc.sql(
            """
            INSERT INTO project(id, project_key, name, created_at)
            VALUES (:id, :projectKey, 'M1 Acceptance Project', now())
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        ).param("id", PROJECT_ID).param("projectKey", PROJECT_KEY).update()
        jdbc.sql(
            """
            INSERT INTO principal(id, issuer, subject, principal_type, disabled, created_at)
            VALUES (:id, :issuer, :subject, 'USER', false, now())
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
            .param("id", PRINCIPAL_ID)
            .param("issuer", PRINCIPAL.issuer)
            .param("subject", PRINCIPAL.subject)
            .update()
        jdbc.sql(
            """
            INSERT INTO project_assignment(project_id, principal_id, role, created_at)
            VALUES (:projectId, :principalId, 'RELEASE_MANAGER', now())
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).param("projectId", PROJECT_ID).param("principalId", PRINCIPAL_ID).update()
    }

    private fun operatorJwt(scope: String) = jwt()
        .jwt { token -> token.issuer(PRINCIPAL.issuer).subject(PRINCIPAL.subject) }
        .authorities(SimpleGrantedAuthority("SCOPE_$scope"))

    private fun containerCommand(container: PostgreSQLContainer<Nothing>, vararg command: String) {
        val result = container.execInContainer(*command)
        check(result.exitCode == 0) {
            "Container command '${command.joinToString(" ")}' failed: ${result.stderr}"
        }
    }

    private companion object {
        const val PROJECT_ID = "project_m1_acceptance"
        const val PROJECT_KEY = "vehicle-m1-acceptance"
        const val PRINCIPAL_ID = "principal_m1_acceptance"
        const val TRUSTED_VALIDATOR = "m1-acceptance-validator/1"
        val EXPECTED_AUDIT_ACTIONS = listOf(
            "RELEASE_CREATED",
            "MANIFEST_REGISTERED",
            "MANIFEST_VALIDATED",
            "MANIFEST_LOCKED",
        )
        val PRINCIPAL = Principal("https://idp.vsrqg.test", "m1-acceptance-owner", service = false)
    }
}

data class RecoverySnapshot(
    val digest: String,
    val auditRows: List<String>,
    val releaseHistoryRows: List<String>,
    val lockedValidationRow: String,
)
