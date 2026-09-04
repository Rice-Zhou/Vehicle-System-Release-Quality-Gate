package com.ricezhou.vsrqg.release

import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.release.application.CreateRelease
import com.ricezhou.vsrqg.release.application.CreateReleaseCommand
import com.ricezhou.vsrqg.release.application.ReleaseRepository
import com.ricezhou.vsrqg.release.domain.ReleaseStatus
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean

class CreateReleaseIntegrationTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var useCase: CreateRelease

    @Autowired
    private lateinit var repository: ReleaseRepository

    @Autowired
    private lateinit var jdbc: JdbcClient

    private val projectId = "project_release"
    private val principalId = "principal_release"
    private val principal = Principal(ISSUER, "release-engineer", service = false)

    @BeforeEach
    fun setUpAuthorityFixtures() {
        insertProject(projectId)
        insertPrincipal(principalId, principal.subject)
        jdbc.sql(
            """
            INSERT INTO project_assignment(project_id, principal_id, role, created_at)
            VALUES (:projectId, :principalId, 'ENGINEER', now())
            ON CONFLICT (project_id, principal_id) DO NOTHING
            """.trimIndent(),
        )
            .param("projectId", projectId)
            .param("principalId", principalId)
            .update()
    }

    @Test
    fun `create persists release history audit outbox and replay atomically`() {
        val command = command("create-success", 'a', "build-success", "request-success")

        val created = useCase.create(command)
        val replayed = useCase.create(command)

        assertThat(replayed).isEqualTo(created)
        assertThat(created.status).isEqualTo(ReleaseStatus.DRAFT)
        assertThat(created.version).isEqualTo(1)
        assertThat(created.manifestId).isNull()
        assertThat(rowCount("release_record", "id", created.releaseId)).isOne()
        assertThat(rowCount("release_state_history", "release_id", created.releaseId)).isOne()
        assertThat(rowCount("audit_event", "aggregate_id", created.releaseId)).isOne()
        assertThat(rowCount("outbox_event", "aggregate_id", created.releaseId)).isOne()
        assertThat(rowCount("idempotency_record", "idempotency_key", command.idempotencyKey)).isOne()
        assertThat(
            jdbc.sql("SELECT actor_id FROM audit_event WHERE aggregate_id = :releaseId")
                .param("releaseId", created.releaseId)
                .query(String::class.java)
                .single(),
        ).isEqualTo(principalId)
    }

    @Test
    fun `outbox failure rolls back release history audit and idempotency`() {
        installOutboxFailureTrigger()
        val command = command("create-rollback", 'b', "build-rollback", "request-rollback-release")

        try {
            assertThatThrownBy { useCase.create(command) }
                .isInstanceOf(DataAccessException::class.java)
        } finally {
            removeOutboxFailureTrigger()
        }

        assertThat(rowCount("release_record", "build_id", "build-rollback")).isZero()
        assertThat(
            jdbc.sql(
                """
                SELECT count(*) FROM release_state_history h
                JOIN release_record r ON r.id = h.release_id
                WHERE r.build_id = 'build-rollback'
                """.trimIndent(),
            ).query(Int::class.java).single(),
        ).isZero()
        assertThat(rowCount("audit_event", "correlation_id", command.requestId)).isZero()
        assertThat(
            jdbc.sql("SELECT count(*) FROM outbox_event WHERE payload ->> 'requestId' = :requestId")
                .param("requestId", command.requestId)
                .query(Int::class.java)
                .single(),
        ).isZero()
        assertThat(rowCount("idempotency_record", "idempotency_key", command.idempotencyKey)).isZero()
    }

    @Test
    fun `created release identity is unchanged when external build fixture changes`() {
        var externalBuildId = "build-original"
        val created = useCase.create(command("create-stable", 'c', externalBuildId, "request-stable"))

        externalBuildId = "build-mutated"
        val stored = repository.find(created.releaseId)

        assertThat(externalBuildId).isEqualTo("build-mutated")
        assertThat(stored).isNotNull
        assertThat(stored!!.declaredBuildId).isEqualTo("build-original")
        assertThat(stored.version).isEqualTo(1)
        assertThat(stored.status).isEqualTo(ReleaseStatus.DRAFT)
    }

    @Test
    fun `authorization failure occurs before idempotency acquisition`() {
        val unauthorizedId = "principal_release_unauthorized"
        val unauthorized = Principal(ISSUER, "release-outsider", service = false)
        insertPrincipal(unauthorizedId, unauthorized.subject)
        val command = command(
            key = "create-forbidden",
            digestCharacter = 'd',
            buildId = "build-forbidden",
            requestId = "request-forbidden",
            actor = unauthorized,
        )

        assertThatThrownBy { useCase.create(command) }
            .isInstanceOf(AccessDeniedException::class.java)
        assertThat(rowCount("release_record", "build_id", "build-forbidden")).isZero()
        assertThat(rowCount("idempotency_record", "idempotency_key", command.idempotencyKey)).isZero()
    }

    private fun command(
        key: String,
        digestCharacter: Char,
        buildId: String,
        requestId: String,
        actor: Principal = principal,
    ) = CreateReleaseCommand(
        principal = actor,
        projectId = projectId,
        vehicle = "model-a",
        platform = "android-automotive",
        systemVersion = "2026.08-rc1",
        buildId = buildId,
        idempotencyKey = key,
        requestDigest = "sha256:" + digestCharacter.toString().repeat(64),
        requestId = requestId,
    )

    private fun insertProject(id: String) {
        jdbc.sql(
            "INSERT INTO project(id, project_key, name, created_at) VALUES (:id, :id, :id, now()) " +
                "ON CONFLICT (id) DO NOTHING",
        ).param("id", id).update()
    }

    private fun insertPrincipal(id: String, subject: String) {
        jdbc.sql(
            """
            INSERT INTO principal(id, issuer, subject, principal_type, disabled, created_at)
            VALUES (:id, :issuer, :subject, 'USER', false, now())
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
            .param("id", id)
            .param("issuer", ISSUER)
            .param("subject", subject)
            .update()
    }

    private fun installOutboxFailureTrigger() {
        jdbc.sql(
            """
            CREATE OR REPLACE FUNCTION reject_release_created_outbox() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                IF NEW.event_type = 'release.created' THEN
                    RAISE EXCEPTION 'injected outbox failure';
                END IF;
                RETURN NEW;
            END;
            ${'$'}${'$'}
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            CREATE TRIGGER reject_release_created_outbox
            BEFORE INSERT ON outbox_event
            FOR EACH ROW EXECUTE FUNCTION reject_release_created_outbox()
            """.trimIndent(),
        ).update()
    }

    private fun removeOutboxFailureTrigger() {
        jdbc.sql("DROP TRIGGER IF EXISTS reject_release_created_outbox ON outbox_event").update()
        jdbc.sql("DROP FUNCTION IF EXISTS reject_release_created_outbox()").update()
    }

    private fun rowCount(table: String, column: String, value: String): Int = jdbc
        .sql("SELECT count(*) FROM $table WHERE $column = :value")
        .param("value", value)
        .query(Int::class.java)
        .single()

    private companion object {
        const val ISSUER = "https://idp.vsrqg.test"
    }
}
