package com.ricezhou.vsrqg.shared

import org.flywaydb.core.Flyway
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

@TestPropertySource(
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.vsrqg.test",
        "spring.security.oauth2.resourceserver.jwt.audiences[0]=vsrqg-api",
    ],
)
class MigrationConstraintTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var flyway: Flyway

    @Test
    fun `flyway creates all M1 tables`() {
        val expectedTables = listOf(
            "project",
            "principal",
            "project_assignment",
            "release_record",
            "release_state_history",
            "manifest_revision",
            "artifact",
            "manifest_artifact",
            "manifest_validation",
            "audit_event",
            "idempotency_record",
            "outbox_event",
        )

        val count = jdbc.sql(
            """
            SELECT count(*)
            FROM information_schema.tables
            WHERE table_schema = 'public'
              AND table_name IN (
                'project', 'principal', 'project_assignment', 'release_record',
                'release_state_history', 'manifest_revision', 'artifact',
                'manifest_artifact', 'manifest_validation', 'audit_event',
                'idempotency_record', 'outbox_event'
              )
            """.trimIndent(),
        )
            .query(Int::class.java)
            .single()

        assertThat(count).isEqualTo(expectedTables.size)
    }

    @Test
    fun `flyway has no pending migration and repeated migrate is a no-op`() {
        assertThat(flyway.info().pending()).isEmpty()
        assertThat(flyway.migrate().migrationsExecuted).isZero()
    }

    @Test
    fun `database enforces release identity foreign keys and artifact provenance boundary`() {
        insertProject("project_constraints", "vehicle-constraints")
        insertRelease("release_constraints", "project_constraints", "build-1")

        assertThatThrownBy {
            insertRelease("release_duplicate", "project_constraints", "build-1")
        }.isInstanceOf(DataAccessException::class.java)

        assertThatThrownBy {
            insertRelease("release_bad_fk", "missing_project", "build-2")
        }.isInstanceOf(DataAccessException::class.java)

        val artifactBuildIdColumns = jdbc.sql(
            """
            SELECT count(*)
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'artifact'
              AND column_name = 'build_id'
            """.trimIndent(),
        ).query(Int::class.java).single()
        assertThat(artifactBuildIdColumns).isZero()
    }

    @Test
    fun `audit events are append only`() {
        insertProject("project_audit", "vehicle-audit")
        jdbc.sql(
            """
            INSERT INTO audit_event(
              id, event_id, project_id, action, aggregate_type, aggregate_id,
              after_state, correlation_id, occurred_at, created_at
            ) VALUES (
              'audit_1', 'event_audit_1', 'project_audit', 'RELEASE_CREATED',
              'RELEASE', 'release_audit', '{}'::jsonb, 'correlation_audit_1', now(), now()
            )
            """.trimIndent(),
        ).update()

        assertThatThrownBy {
            jdbc.sql("UPDATE audit_event SET action = 'CHANGED' WHERE id = 'audit_1'").update()
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            jdbc.sql("DELETE FROM audit_event WHERE id = 'audit_1'").update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `locked manifest and its artifact membership are immutable`() {
        insertProject("project_manifest", "vehicle-manifest")
        insertRelease("release_manifest", "project_manifest", "build-manifest")
        jdbc.sql(
            """
            INSERT INTO manifest_revision(
              id, release_id, revision, content_digest, raw_manifest, canonical_bytes,
              schema_version, state, created_at, updated_at
            ) VALUES (
              'manifest_1', 'release_manifest', 1,
              'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
              '{}'::jsonb, convert_to('{}', 'UTF8'), '0.2', 'VALIDATED', now(), now()
            )
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            INSERT INTO artifact(
              id, identity_digest, artifact_type, locator, checksum_algorithm,
              checksum_value, created_at
            ) VALUES (
              'artifact_1',
              'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
              'APK', '{"uri":"s3://bucket/app.apk"}'::jsonb, 'SHA-256',
              'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', now()
            )
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            INSERT INTO manifest_artifact(manifest_id, artifact_id, ordinal, required, created_at)
            VALUES ('manifest_1', 'artifact_1', 0, true, now())
            """.trimIndent(),
        ).update()
        jdbc.sql("UPDATE manifest_revision SET state = 'LOCKED' WHERE id = 'manifest_1'").update()
        jdbc.sql(
            "UPDATE release_record SET locked_manifest_id = 'manifest_1' WHERE id = 'release_manifest'",
        ).update()

        assertThatThrownBy {
            jdbc.sql("UPDATE manifest_revision SET raw_manifest = '{\"changed\":true}' WHERE id = 'manifest_1'").update()
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            jdbc.sql("DELETE FROM manifest_artifact WHERE manifest_id = 'manifest_1'").update()
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            jdbc.sql("DELETE FROM manifest_revision WHERE id = 'manifest_1'").update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    private fun insertProject(id: String, projectKey: String) {
        jdbc.sql(
            "INSERT INTO project(id, project_key, name, created_at) VALUES (:id, :projectKey, :name, now())",
        )
            .param("id", id)
            .param("projectKey", projectKey)
            .param("name", projectKey)
            .update()
    }

    private fun insertRelease(id: String, projectId: String, buildId: String) {
        jdbc.sql(
            """
            INSERT INTO release_record(
              id, project_id, vehicle, platform, system_version, build_id,
              status, created_at, updated_at
            ) VALUES (
              :id, :projectId, 'vehicle', 'platform', '1.0', :buildId,
              'DRAFT', now(), now()
            )
            """.trimIndent(),
        )
            .param("id", id)
            .param("projectId", projectId)
            .param("buildId", buildId)
            .update()
    }
}
