package com.ricezhou.vsrqg.manifest

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.manifest.application.ExportManifest
import com.ricezhou.vsrqg.manifest.application.LockManifest
import com.ricezhou.vsrqg.manifest.application.LockManifestCommand
import com.ricezhou.vsrqg.manifest.application.ManifestLockConflict
import com.ricezhou.vsrqg.manifest.application.RegisterManifest
import com.ricezhou.vsrqg.manifest.application.RegisterManifestCommand
import com.ricezhou.vsrqg.manifest.application.RegisterManifestResult
import com.ricezhou.vsrqg.manifest.application.ValidationStatus
import com.ricezhou.vsrqg.manifest.domain.ManifestDocument
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import com.ricezhou.vsrqg.shared.runConcurrently
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.vsrqg.test",
        "spring.security.oauth2.resourceserver.jwt.audiences[0]=vsrqg-api",
        "vsrqg.manifest.trusted-validator-versions=trusted-artifact-fixture/1",
    ],
)
class ManifestLockConcurrencyTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var registerManifest: RegisterManifest

    @Autowired
    private lateinit var lockManifest: LockManifest

    @Autowired
    private lateinit var exportManifest: ExportManifest

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private lateinit var suffix: String
    private lateinit var releaseId: String
    private lateinit var buildId: String
    private lateinit var source: String
    private lateinit var registered: RegisterManifestResult
    private val operatorA = Principal(ISSUER, "manifest-lock-a", service = false)
    private val operatorB = Principal(ISSUER, "manifest-lock-b", service = false)

    @BeforeEach
    fun setUpRegisteredManifest(testInfo: TestInfo) {
        suffix = testInfo.testMethod.orElseThrow().name.hashCode().toUInt().toString(16)
        releaseId = "rel_$suffix"
        buildId = "build-$suffix"
        source = baseManifest
            .replace("rel_01V02EXAMPLE", releaseId)
            .replace("build-1842", buildId)
        insertAuthorityFixtures()
        insertRelease()
        registered = registerManifest.register(
            RegisterManifestCommand(
                principal = operatorA,
                releaseId = releaseId,
                document = ManifestDocument(source),
                idempotencyKey = "register-lock-$suffix",
                requestId = "request-register-lock-$suffix",
            ),
        )
    }

    @Test
    fun `exactly one concurrent lock succeeds`() {
        promoteTrustedValidation()
        val sequence = AtomicInteger()

        val outcomes = runConcurrently(2) {
            val index = sequence.getAndIncrement()
            val principal = if (index == 0) operatorA else operatorB
            try {
                lockManifest.lock(command(principal, "lock-concurrent-$index"))
                "LOCKED"
            } catch (exception: ManifestLockConflict) {
                exception.code
            }
        }

        assertThat(outcomes.count { it == "LOCKED" }).isOne()
        assertThat(outcomes.count { it == "MANIFEST_LOCK_CONFLICT" }).isOne()
        assertThat(
            jdbc.sql(
                "SELECT count(*) FROM manifest_revision WHERE release_id = :releaseId AND state = 'LOCKED'",
            ).param("releaseId", releaseId).query(Int::class.java).single(),
        ).isOne()
        assertThat(
            jdbc.sql("SELECT status FROM release_record WHERE id = :releaseId")
                .param("releaseId", releaseId)
                .query(String::class.java)
                .single(),
        ).isEqualTo("READY_FOR_TEST")
        assertThat(rowCount("release_state_history", "release_id", releaseId)).isEqualTo(2)
        assertThat(rowCount("audit_event", "aggregate_id", registered.manifestId)).isEqualTo(2)
        assertThat(rowCount("outbox_event", "aggregate_id", registered.manifestId)).isEqualTo(2)
    }

    @Test
    fun `incomplete validation and stale etag cannot lock`() {
        assertThatThrownBy { lockManifest.lock(command(operatorA, "lock-incomplete")) }
            .isInstanceOf(ManifestLockConflict::class.java)
            .extracting("code")
            .isEqualTo("MANIFEST_VALIDATION_NOT_VALID")

        promoteTrustedValidation()
        mockMvc.post(
            "/api/v1/releases/{releaseId}/manifests/{manifestId}:lock",
            releaseId,
            registered.manifestId,
        ) {
            with(operatorJwt("manifest:lock"))
            header("Idempotency-Key", "lock-stale-http-$suffix")
            header("If-Match", "\"99\"")
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"Artifacts verified"}"""
        }.andExpect {
            status { isConflict() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("MANIFEST_VERSION_CONFLICT") }
        }
        assertThatThrownBy {
            lockManifest.lock(command(operatorA, "lock-stale", expectedVersion = 99))
        }
            .isInstanceOf(ManifestLockConflict::class.java)
            .extracting("code")
            .isEqualTo("MANIFEST_VERSION_CONFLICT")

        assertThat(
            jdbc.sql("SELECT state FROM manifest_revision WHERE id = :manifestId")
                .param("manifestId", registered.manifestId)
                .query(String::class.java)
                .single(),
        ).isEqualTo("REGISTERED")
        assertThat(rowCount("idempotency_record", "idempotency_key", "lock-incomplete")).isZero()
        assertThat(rowCount("idempotency_record", "idempotency_key", "lock-stale")).isZero()
        assertThat(rowCount("idempotency_record", "idempotency_key", "lock-stale-http-$suffix")).isZero()
    }

    @Test
    fun `locked export is immutable when source fixtures and database writes change`() {
        promoteTrustedValidation()
        val locked = lockManifest.lock(command(operatorA, "lock-export"))
        val before = exportManifest.export(operatorA, releaseId, registered.manifestId)

        source = source.replace(buildId, "external-build-mutated")
        assertThatThrownBy {
            jdbc.sql("UPDATE manifest_revision SET raw_manifest = '{}'::jsonb WHERE id = :manifestId")
                .param("manifestId", registered.manifestId)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            jdbc.sql("DELETE FROM manifest_artifact WHERE manifest_id = :manifestId")
                .param("manifestId", registered.manifestId)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { promoteTrustedValidation("mvl_late_$suffix") }
            .isInstanceOf(DataAccessException::class.java)
        val after = exportManifest.export(operatorA, releaseId, registered.manifestId)

        assertThat(source).contains("external-build-mutated")
        assertThat(after).isEqualTo(before)
        assertThat(after.contentDigest).isEqualTo(locked.contentDigest)
        assertThat(after.rawManifest.path("buildId").asText()).isEqualTo(buildId)
        assertThat(after.validation.status).isEqualTo(ValidationStatus.VALID)
        assertThat(after.lockedAt).isEqualTo(locked.lockedAt)
    }

    @Test
    fun `lock and export endpoints expose the frozen response contract`() {
        promoteTrustedValidation()

        mockMvc.post(
            "/api/v1/releases/{releaseId}/manifests/{manifestId}:lock",
            releaseId,
            registered.manifestId,
        ) {
            with(operatorJwt("manifest:lock"))
            header("Idempotency-Key", "lock-api-$suffix")
            header("If-Match", "\"1\"")
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"Artifacts verified"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.releaseId") { value(releaseId) }
            jsonPath("$.manifestId") { value(registered.manifestId) }
            jsonPath("$.manifestRevision") { value(1) }
            jsonPath("$.contentDigest") { value(registered.contentDigest) }
            jsonPath("$.state") { value("LOCKED") }
            jsonPath("$.lockedAt") { exists() }
        }

        mockMvc.post(
            "/api/v1/releases/{releaseId}/manifests/{manifestId}:lock",
            releaseId,
            registered.manifestId,
        ) {
            with(operatorJwt("manifest:lock"))
            header("Idempotency-Key", "lock-api-$suffix")
            header("If-Match", "\"1\"")
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"Artifacts verified"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.state") { value("LOCKED") }
        }

        mockMvc.post(
            "/api/v1/releases/{releaseId}/manifests/{manifestId}:lock",
            releaseId,
            registered.manifestId,
        ) {
            with(operatorJwt("manifest:lock"))
            header("Idempotency-Key", "lock-api-conflict-$suffix")
            header("If-Match", "\"1\"")
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"Artifacts verified"}"""
        }.andExpect {
            status { isConflict() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("MANIFEST_LOCK_CONFLICT") }
        }

        assertThat(rowCount("release_state_history", "release_id", releaseId)).isEqualTo(2)
        assertThat(rowCount("audit_event", "aggregate_id", registered.manifestId)).isEqualTo(2)
        assertThat(rowCount("outbox_event", "aggregate_id", registered.manifestId)).isEqualTo(2)

        mockMvc.get(
            "/api/v1/releases/{releaseId}/manifests/{manifestId}",
            releaseId,
            registered.manifestId,
        ) {
            with(operatorJwt("release:read"))
        }.andExpect {
            status { isOk() }
            jsonPath("$.releaseId") { value(releaseId) }
            jsonPath("$.manifestId") { value(registered.manifestId) }
            jsonPath("$.state") { value("LOCKED") }
            jsonPath("$.rawManifest.releaseId") { value(releaseId) }
            jsonPath("$.contentDigest") { value(registered.contentDigest) }
            jsonPath("$.validation.status") { value("VALID") }
            jsonPath("$.lockedAt") { exists() }
        }
    }

    private fun command(
        principal: Principal,
        key: String,
        expectedVersion: Long = 1,
    ) = LockManifestCommand(
        principal = principal,
        releaseId = releaseId,
        manifestId = registered.manifestId,
        expectedVersion = expectedVersion,
        idempotencyKey = key,
        requestId = "request-$key",
        reason = "Artifacts verified",
    )

    private fun promoteTrustedValidation(validationId: String = "mvl_$suffix") {
        val report = registered.validation.copy(
            validationId = validationId,
            status = ValidationStatus.VALID,
            violations = emptyList(),
            validatedAt = Instant.now(),
            validatorVersion = "trusted-artifact-fixture/1",
        )
        jdbc.sql(
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
            .param("id", report.validationId)
            .param("manifestId", report.manifestId)
            .param("contentDigest", report.contentDigest)
            .param("schemaVersion", report.schemaVersion)
            .param("validatorVersion", report.validatorVersion)
            .param("report", objectMapper.writeValueAsString(report))
            .param("validatedAt", report.validatedAt.atOffset(java.time.ZoneOffset.UTC))
            .param("createdAt", report.validatedAt.atOffset(java.time.ZoneOffset.UTC))
            .update()
    }

    private fun insertAuthorityFixtures() {
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
            VALUES
              ('principal_lock_a', :issuer, 'manifest-lock-a', 'USER', false, now()),
              ('principal_lock_b', :issuer, 'manifest-lock-b', 'USER', false, now())
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        ).param("issuer", ISSUER).update()
        jdbc.sql(
            """
            INSERT INTO project_assignment(project_id, principal_id, role, created_at)
            VALUES
              ('project_api', 'principal_lock_a', 'RELEASE_MANAGER', now()),
              ('project_api', 'principal_lock_b', 'RELEASE_MANAGER', now())
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).update()
    }

    private fun insertRelease() {
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

    private fun operatorJwt(scope: String) = jwt()
        .jwt { token ->
            token.issuer(ISSUER)
            token.subject(operatorA.subject)
        }
        .authorities(SimpleGrantedAuthority("SCOPE_$scope"))

    private fun rowCount(table: String, column: String, value: String): Int = jdbc
        .sql("SELECT count(*) FROM $table WHERE $column = :value")
        .param("value", value)
        .query(Int::class.java)
        .single()

    private companion object {
        const val ISSUER = "https://idp.vsrqg.test"
        val baseManifest: String = Files.readString(
            Path.of("..").toAbsolutePath().normalize()
                .resolve("contracts/examples/v0.2/manifest/valid-apk.json"),
        )
    }
}
