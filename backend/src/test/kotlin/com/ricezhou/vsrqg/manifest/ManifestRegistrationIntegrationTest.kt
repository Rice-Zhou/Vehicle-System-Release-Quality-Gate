package com.ricezhou.vsrqg.manifest

import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.manifest.application.ManifestRelease
import com.ricezhou.vsrqg.manifest.application.ManifestRepository
import com.ricezhou.vsrqg.manifest.application.ManifestRegistrationConflict
import com.ricezhou.vsrqg.manifest.application.RegisterManifest
import com.ricezhou.vsrqg.manifest.application.RegisterManifestCommand
import com.ricezhou.vsrqg.manifest.application.ValidateManifest
import com.ricezhou.vsrqg.manifest.domain.ManifestDocument
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.dao.DataAccessException
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@AutoConfigureMockMvc
class ManifestRegistrationIntegrationTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var registerManifest: RegisterManifest

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcClient

    private lateinit var releaseId: String
    private lateinit var buildId: String
    private lateinit var validManifest: String

    @BeforeEach
    fun setUpAuthorityAndRelease(testInfo: TestInfo) {
        val suffix = testInfo.testMethod.orElseThrow().name.hashCode().toUInt().toString(16)
        releaseId = "rel_$suffix"
        buildId = "build-$suffix"
        validManifest = baseManifest
            .replace("rel_01V02EXAMPLE", releaseId)
            .replace("build-1842", buildId)
        jdbc.sql(
            """
            INSERT INTO project(id, project_key, name, created_at)
            VALUES ('project_api', 'vehicle-x', 'Vehicle X', now())
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            INSERT INTO principal(id, issuer, subject, principal_type, disabled, created_at)
            VALUES ('principal_manifest', :issuer, 'manifest-engineer', 'USER', false, now())
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        ).param("issuer", ISSUER).update()
        jdbc.sql(
            """
            INSERT INTO project_assignment(project_id, principal_id, role, created_at)
            VALUES ('project_api', 'principal_manifest', 'ENGINEER', now())
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            INSERT INTO release_record(
              id, project_id, vehicle, platform, system_version, build_id,
              status, row_version, created_at, updated_at
            ) VALUES (
              :releaseId, 'project_api', 'model-a', 'android-automotive',
              '2026.08-rc1', :buildId, 'DRAFT', 1, now(), now()
            )
            """.trimIndent(),
        )
            .param("releaseId", releaseId)
            .param("buildId", buildId)
            .update()
    }

    @Test
    fun `same idempotent registration returns one immutable revision with ordinal artifacts`() {
        val command = command(validManifest, "register-once")

        val first = registerManifest.register(command)
        val replay = registerManifest.register(command)

        assertThat(replay).isEqualTo(first)
        assertThat(first.revision).isEqualTo(1)
        assertThat(first.state.name).isEqualTo("REGISTERED")
        assertThat(first.validation.status.name).isEqualTo("INCOMPLETE")
        assertThat(first.validation.violations.map { it.code })
            .containsExactly("ARTIFACT_CHECKSUM_NOT_VERIFIED")
        assertThat(rowCount("manifest_revision", "release_id", releaseId)).isOne()
        assertThat(rowCount("manifest_artifact", "manifest_id", first.manifestId)).isEqualTo(2)
        assertThat(
            jdbc.sql(
                "SELECT ordinal FROM manifest_artifact WHERE manifest_id = :manifestId ORDER BY ordinal",
            ).param("manifestId", first.manifestId).query(Int::class.java).list(),
        ).containsExactly(0, 1)
        assertThat(rowCount("manifest_validation", "manifest_id", first.manifestId)).isOne()
        assertThat(rowCount("audit_event", "aggregate_id", first.manifestId)).isOne()
        assertThat(rowCount("outbox_event", "aggregate_id", first.manifestId)).isOne()
        assertThat(
            jdbc.sql("SELECT status FROM release_record WHERE id = :releaseId")
                .param("releaseId", releaseId)
                .query(String::class.java)
                .single(),
        ).isEqualTo("REGISTERED")
        assertThat(rowCount("release_state_history", "release_id", releaseId)).isOne()
    }

    @Test
    fun `new revision reuses artifact identities without changing their records`() {
        val first = registerManifest.register(command(validManifest, "register-reuse-first"))
        val revised = validManifest.replace("2026-08-21T10:00:00Z", "2026-08-21T10:01:00Z")

        val second = registerManifest.register(command(revised, "register-reuse-second"))

        assertThat(second.revision).isEqualTo(2)
        assertThat(second.manifestId).isNotEqualTo(first.manifestId)
        assertThat(
            jdbc.sql(
                """
                SELECT count(DISTINCT ma.artifact_id)
                FROM manifest_artifact ma
                JOIN manifest_revision m ON m.id = ma.manifest_id
                WHERE m.release_id = :releaseId
                """.trimIndent(),
            ).param("releaseId", releaseId).query(Int::class.java).single(),
        ).isEqualTo(2)
        assertThat(
            jdbc.sql(
                """
                SELECT count(*)
                FROM manifest_artifact ma
                JOIN manifest_revision m ON m.id = ma.manifest_id
                WHERE m.release_id = :releaseId
                """.trimIndent(),
            ).param("releaseId", releaseId).query(Int::class.java).single(),
        ).isEqualTo(4)
        assertThat(rowCount("release_state_history", "release_id", releaseId)).isOne()
    }

    @Test
    fun `outbox failure rolls back revision links report audit and idempotency`() {
        installOutboxFailureTrigger()
        try {
            assertThatThrownBy {
                registerManifest.register(command(validManifest, "register-rollback"))
            }.isInstanceOf(DataAccessException::class.java)
        } finally {
            removeOutboxFailureTrigger()
        }

        assertThat(rowCount("manifest_revision", "release_id", releaseId)).isZero()
        assertThat(rowCount("audit_event", "correlation_id", "request-register-rollback")).isZero()
        assertThat(rowCount("idempotency_record", "idempotency_key", "register-rollback")).isZero()
        assertThat(
            jdbc.sql("SELECT status FROM release_record WHERE id = :releaseId")
                .param("releaseId", releaseId)
                .query(String::class.java)
                .single(),
        ).isEqualTo("DRAFT")
    }

    @Test
    fun `release that has entered testing cannot accept another manifest revision`() {
        jdbc.sql("UPDATE release_record SET status = 'READY_FOR_TEST' WHERE id = :releaseId")
            .param("releaseId", releaseId)
            .update()

        assertThatThrownBy {
            registerManifest.register(command(validManifest, "register-after-lock"))
        }.isInstanceOf(ManifestRegistrationConflict::class.java)

        assertThat(rowCount("manifest_revision", "release_id", releaseId)).isZero()
        assertThat(rowCount("idempotency_record", "idempotency_key", "register-after-lock")).isZero()
    }

    @Test
    fun `release identity mismatch persists rejected revision and failed report`() {
        val mismatch = validManifest.replace(releaseId, "rel_wrong")

        mockMvc.post("/api/v1/releases/{releaseId}/manifests", releaseId) {
            with(manifestJwt())
            header("Idempotency-Key", "register-mismatch")
            contentType = MediaType.APPLICATION_JSON
            content = mismatch
        }.andExpect {
            status { isUnprocessableEntity() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("MANIFEST_VALIDATION_FAILED") }
            jsonPath("$.violations[0].code") { value("MANIFEST_RELEASE_ID_MISMATCH") }
        }

        assertThat(rowCount("manifest_revision", "release_id", releaseId)).isOne()
        assertThat(
            jdbc.sql("SELECT state FROM manifest_revision WHERE release_id = :releaseId")
                .param("releaseId", releaseId)
                .query(String::class.java)
                .single(),
        ).isEqualTo("REJECTED")
        assertThat(
            jdbc.sql(
                """
                SELECT report ->> 'status'
                FROM manifest_validation v
                JOIN manifest_revision m ON m.id = v.manifest_id
                WHERE m.release_id = :releaseId
                """.trimIndent(),
            ).param("releaseId", releaseId).query(String::class.java).single(),
        ).isEqualTo("FAILED")
        assertThat(
            jdbc.sql("SELECT status FROM release_record WHERE id = :releaseId")
                .param("releaseId", releaseId)
                .query(String::class.java)
                .single(),
        ).isEqualTo("DRAFT")
    }

    @Test
    fun `schema violation returns 422 without creating a revision`() {
        val missingRequired = validManifest.replace("\"required\": false,", "")

        mockMvc.post("/api/v1/releases/{releaseId}/manifests", releaseId) {
            with(manifestJwt())
            header("Idempotency-Key", "register-schema-invalid")
            contentType = MediaType.APPLICATION_JSON
            content = missingRequired
        }.andExpect {
            status { isUnprocessableEntity() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("MANIFEST_SCHEMA_INVALID") }
            jsonPath("$.violations") { isNotEmpty() }
        }

        assertThat(rowCount("manifest_revision", "release_id", releaseId)).isZero()
        assertThat(rowCount("idempotency_record", "idempotency_key", "register-schema-invalid")).isZero()
    }

    @Test
    fun `validate endpoint returns the persisted stable report without claiming checksum verification`() {
        val registered = registerManifest.register(command(validManifest, "register-validate"))

        mockMvc.post(
            "/api/v1/releases/{releaseId}/manifests/{manifestId}:validate",
            releaseId,
            registered.manifestId,
        ) {
            with(manifestJwt())
            header("Idempotency-Key", "validate-stable")
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"Re-run validation"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.validationId") { value(registered.validation.validationId) }
            jsonPath("$.manifestId") { value(registered.manifestId) }
            jsonPath("$.status") { value("INCOMPLETE") }
            jsonPath("$.contentDigest") { value(registered.contentDigest) }
            jsonPath("$.schemaVersion") { value("0.2") }
            jsonPath("$.violations[0].code") { value("ARTIFACT_CHECKSUM_NOT_VERIFIED") }
            jsonPath("$.validatedAt") { exists() }
        }

        assertThat(rowCount("manifest_validation", "manifest_id", registered.manifestId)).isOne()
    }

    private fun command(source: String, key: String): RegisterManifestCommand {
        return RegisterManifestCommand(
            principal = Principal(ISSUER, "manifest-engineer", service = false),
            releaseId = releaseId,
            document = ManifestDocument(source),
            idempotencyKey = key,
            requestId = "request-$key",
        )
    }

    private fun manifestJwt() = jwt()
        .jwt { token ->
            token.issuer(ISSUER)
            token.subject("manifest-engineer")
        }
        .authorities(SimpleGrantedAuthority("SCOPE_manifest:write"))

    private fun rowCount(table: String, column: String, value: String): Int = jdbc
        .sql("SELECT count(*) FROM $table WHERE $column = :value")
        .param("value", value)
        .query(Int::class.java)
        .single()

    private fun installOutboxFailureTrigger() {
        jdbc.sql(
            """
            CREATE OR REPLACE FUNCTION reject_manifest_outbox() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                IF NEW.event_type = 'manifest.registered' THEN
                    RAISE EXCEPTION 'injected manifest outbox failure';
                END IF;
                RETURN NEW;
            END;
            ${'$'}${'$'}
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            CREATE TRIGGER reject_manifest_outbox
            BEFORE INSERT ON outbox_event
            FOR EACH ROW EXECUTE FUNCTION reject_manifest_outbox()
            """.trimIndent(),
        ).update()
    }

    private fun removeOutboxFailureTrigger() {
        jdbc.sql("DROP TRIGGER IF EXISTS reject_manifest_outbox ON outbox_event").update()
        jdbc.sql("DROP FUNCTION IF EXISTS reject_manifest_outbox()").update()
    }

    private companion object {
        const val ISSUER = "https://idp.vsrqg.test"
        val repositoryRoot: Path = Path.of("..").toAbsolutePath().normalize()
        val baseManifest: String = Files.readString(
            repositoryRoot.resolve("contracts/examples/v0.2/manifest/valid-apk.json"),
        )
    }
}

class ManifestSemanticValidationTest {
    private val objectMapper = ObjectMapper()
    private val validator = ValidateManifest(
        repository = mock(ManifestRepository::class.java),
        authorizer = mock(ProjectAuthorizer::class.java),
        idempotentExecutor = mock(IdempotentExecutor::class.java),
        governanceStore = mock(GovernanceStore::class.java),
    )
    private val release = ManifestRelease(
        id = "rel_01V02EXAMPLE",
        projectId = "project_api",
        projectReference = "vehicle-x",
        vehicle = "model-a",
        platform = "android-automotive",
        systemVersion = "2026.08-rc1",
        buildId = "build-1842",
        status = "DRAFT",
    )
    private val source = Files.readString(
        Path.of("..").toAbsolutePath().normalize()
            .resolve("contracts/examples/v0.2/manifest/valid-apk.json"),
    )

    @Test
    fun `matching release identity remains incomplete until payload checksum is verified`() {
        val report = evaluate(objectMapper.readTree(source))

        assertThat(report.status.name).isEqualTo("INCOMPLETE")
        assertThat(report.violations.map { it.code })
            .containsExactly("ARTIFACT_CHECKSUM_NOT_VERIFIED")
    }

    @Test
    fun `mismatched release identity fails without a false checksum claim`() {
        val root = objectMapper.readTree(source)
        (root as com.fasterxml.jackson.databind.node.ObjectNode).put("releaseId", "rel_wrong")

        val report = evaluate(root)

        assertThat(report.status.name).isEqualTo("FAILED")
        assertThat(report.violations.map { it.code })
            .containsExactly("MANIFEST_RELEASE_ID_MISMATCH")
    }

    private fun evaluate(root: com.fasterxml.jackson.databind.JsonNode) = validator.evaluate(
        release = release,
        root = root,
        validationId = "mvl_test",
        manifestId = "man_test",
        contentDigest = "sha256:" + "a".repeat(64),
        schemaVersion = "0.2",
        canonicalByteLength = 123,
        validatedAt = Instant.parse("2026-08-25T00:00:00Z"),
    )
}
