package com.ricezhou.vsrqg.issue

import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.issue.adapter.FixtureFailure
import com.ricezhou.vsrqg.issue.adapter.FixtureIssueSourceAdapter
import com.ricezhou.vsrqg.issue.adapter.FixturePage
import com.ricezhou.vsrqg.issue.adapter.FixtureScenario
import com.ricezhou.vsrqg.issue.application.IssueSourceFailureCode
import com.ricezhou.vsrqg.issue.application.RunIssueSync
import com.ricezhou.vsrqg.issue.application.StartIssueSync
import com.ricezhou.vsrqg.issue.application.StartIssueSyncCommand
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import com.ricezhou.vsrqg.issue.domain.NormalizedIssue
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.access.AccessDeniedException
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
    ],
)
class IssueSyncIntegrationTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var startIssueSync: StartIssueSync

    @Autowired
    private lateinit var runIssueSync: RunIssueSync

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var mockMvc: MockMvc

    private val projectId = "project_issue_sync"
    private val sourceId = "source_issue_sync"
    private val principalId = "principal_issue_sync"
    private val principal = Principal(ISSUER, "issue-engineer", service = false)

    @BeforeEach
    fun prepareAuthorityAndSource() {
        jdbc.sql(
            "INSERT INTO project(id, project_key, name, created_at) VALUES (:id, :id, :id, now()) " +
                "ON CONFLICT (id) DO NOTHING",
        ).param("id", projectId).update()
        jdbc.sql(
            """
            INSERT INTO principal(id, issuer, subject, principal_type, disabled, created_at)
            VALUES (:id, :issuer, :subject, 'USER', false, now())
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
            .param("id", principalId)
            .param("issuer", ISSUER)
            .param("subject", principal.subject)
            .update()
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
        clearPreviousSyncFixture()
        jdbc.sql(
            """
            INSERT INTO issue_source(
              id, project_id, source_key, source_type, adapter_version,
              mapping_version, enabled, created_at, updated_at
            ) VALUES (
              :id, :projectId, 'fixture-sync', 'FIXTURE', 'fixture-adapter-v1',
              'issue-mapping-v1', true, now(), now()
            ) ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
            .param("id", sourceId)
            .param("projectId", projectId)
            .update()
    }

    @AfterEach
    fun removeFailureTriggers() {
        jdbc.sql("DROP TRIGGER IF EXISTS reject_issue_sync_page ON issue_sync_run").update()
        jdbc.sql("DROP FUNCTION IF EXISTS reject_issue_sync_page()").update()
        jdbc.sql("DROP TRIGGER IF EXISTS reject_issue_sync_audit ON audit_event").update()
        jdbc.sql("DROP FUNCTION IF EXISTS reject_issue_sync_audit()").update()
        jdbc.sql("DROP TRIGGER IF EXISTS reject_issue_sync_outbox ON outbox_event").update()
        jdbc.sql("DROP FUNCTION IF EXISTS reject_issue_sync_outbox()").update()
        jdbc.sql("DROP TRIGGER IF EXISTS reject_issue_sync_job ON background_job").update()
        jdbc.sql("DROP FUNCTION IF EXISTS reject_issue_sync_job()").update()
    }

    @Test
    fun `successful multi-page sync persists revisions and advances successful cursor once`() {
        val started = startIssueSync.start(command("sync-success", 'a', "request-sync-success"))

        val completed = runIssueSync.run(started.syncRunId, twoPageAdapter())

        assertThat(completed.status.name).isEqualTo("SUCCEEDED")
        assertThat(completed.issueCount).isEqualTo(2)
        assertThat(count("normalized_issue", "source_id", sourceId)).isEqualTo(2)
        assertThat(syncRunValue(started.syncRunId, "status")).isEqualTo("SUCCEEDED")
        assertThat(cursorValue("last_successful_sync_run_id")).isEqualTo(started.syncRunId)
        assertThat(cursorValue("source_watermark")).isEqualTo(WATERMARK)
        assertThat(count("audit_event", "aggregate_id", started.syncRunId)).isOne()
        assertThat(count("outbox_event", "aggregate_id", started.syncRunId)).isOne()
        assertThat(count("background_job", "id", started.operationId)).isOne()
    }

    @Test
    fun `idempotent start replays one queued run and repeated source versions create no duplicate revision`() {
        val command = command("sync-idempotent", 'b', "request-sync-idempotent")
        val first = startIssueSync.start(command)
        val replay = startIssueSync.start(command)

        assertThat(replay).isEqualTo(first)
        assertThat(count("issue_sync_run", "id", first.syncRunId)).isOne()
        assertThat(count("background_job", "id", first.operationId)).isOne()

        runIssueSync.run(first.syncRunId, twoPageAdapter())
        val second = startIssueSync.start(command("sync-retry", 'c', "request-sync-retry"))
        runIssueSync.run(second.syncRunId, twoPageAdapter())

        assertThat(second.syncRunId).isNotEqualTo(first.syncRunId)
        assertThat(count("normalized_issue", "source_id", sourceId)).isEqualTo(2)
    }

    @Test
    fun `second page failure keeps successful cursor and a new run can recover without duplicates`() {
        val adapter = twoPageAdapter(
            failures = mapOf("fixture-page-2" to FixtureFailure(IssueSourceFailureCode.UPSTREAM_5XX)),
        )
        val failed = startIssueSync.start(command("sync-failed", 'd', "request-sync-failed"))

        val failure = runIssueSync.run(failed.syncRunId, adapter)

        assertThat(failure.status.name).isEqualTo("FAILED")
        assertThat(failure.diagnosticCode).isEqualTo("UPSTREAM_5XX")
        assertThat(count("normalized_issue", "source_id", sourceId)).isOne()
        assertThat(count("issue_sync_cursor", "source_id", sourceId)).isZero()

        val retry = startIssueSync.start(command("sync-recovered", 'e', "request-sync-recovered"))
        val recovered = runIssueSync.run(retry.syncRunId, adapter)

        assertThat(recovered.status.name).isEqualTo("SUCCEEDED")
        assertThat(count("normalized_issue", "source_id", sourceId)).isEqualTo(2)
        assertThat(cursorValue("last_successful_sync_run_id")).isEqualTo(retry.syncRunId)
    }

    @Test
    fun `page checkpoint failure rolls back every revision in that page and does not advance cursor`() {
        installPageCheckpointFailure()
        val started = startIssueSync.start(command("sync-page-rollback", 'f', "request-page-rollback"))

        val failed = runIssueSync.run(started.syncRunId, onePageAdapter())

        assertThat(failed.status.name).isEqualTo("FAILED")
        assertThat(failed.diagnosticCode).isEqualTo("PERSISTENCE_FAILED")
        assertThat(count("normalized_issue", "source_id", sourceId)).isZero()
        assertThat(count("issue_sync_cursor", "source_id", sourceId)).isZero()
    }

    @Test
    fun `audit outbox or job failure rolls back sync run and idempotency`() {
        listOf("audit", "outbox", "job").forEachIndexed { index, target ->
            installStartFailure(target)
            val key = "sync-start-rollback-$target"

            assertThatThrownBy {
                startIssueSync.start(command(key, ('1'.code + index).toChar(), "request-$target"))
            }.isInstanceOf(DataAccessException::class.java)

            assertThat(count("idempotency_record", "idempotency_key", key)).isZero()
            assertThat(count("issue_sync_run", "source_id", sourceId)).isZero()
            assertThat(count("audit_event", "correlation_id", "request-$target")).isZero()
            removeFailureTriggers()
        }
    }

    @Test
    fun `authorization failure occurs before idempotency and sync creation`() {
        val outsider = Principal(ISSUER, "issue-outsider", service = false)
        jdbc.sql(
            """
            INSERT INTO principal(id, issuer, subject, principal_type, disabled, created_at)
            VALUES ('principal_issue_outsider', :issuer, :subject, 'USER', false, now())
            """.trimIndent(),
        ).param("issuer", ISSUER).param("subject", outsider.subject).update()
        val unauthorized = command("sync-forbidden", '9', "request-forbidden", outsider)

        assertThatThrownBy { startIssueSync.start(unauthorized) }
            .isInstanceOf(AccessDeniedException::class.java)
        assertThat(count("idempotency_record", "idempotency_key", unauthorized.idempotencyKey)).isZero()
        assertThat(count("issue_sync_run", "source_id", sourceId)).isZero()
    }

    @Test
    fun `sync API returns accepted operation and read API returns queued run`() {
        val token = jwt()
            .jwt { jwt ->
                jwt.issuer(ISSUER).subject(principal.subject).claim("principal_type", "USER")
            }
            .authorities(
                org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_issue:sync"),
                org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_issue:read"),
            )

        val response = mockMvc.post("/api/v1/issue-sources/{sourceId}/sync", sourceId) {
            with(token)
            header("Idempotency-Key", "sync-api")
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.operationId") { isNotEmpty() }
            jsonPath("$.syncRunId") { isNotEmpty() }
            jsonPath("$.status") { value("QUEUED") }
        }.andReturn().response.contentAsString
        val syncRunId = com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("syncRunId").asText()

        mockMvc.get("/api/v1/issue-sync-runs/{syncRunId}", syncRunId) {
            with(token)
        }.andExpect {
            status { isOk() }
            jsonPath("$.syncRunId") { value(syncRunId) }
            jsonPath("$.status") { value("QUEUED") }
        }
    }

    private fun command(
        key: String,
        digestCharacter: Char,
        requestId: String,
        actor: Principal = principal,
    ) = StartIssueSyncCommand(
        principal = actor,
        sourceId = sourceId,
        idempotencyKey = key,
        requestDigest = "sha256:" + digestCharacter.toString().repeat(64),
        requestId = requestId,
    )

    private fun onePageAdapter() = FixtureIssueSourceAdapter(
        FixtureScenario(
            source = "FIXTURE",
            mappingVersion = "issue-mapping-v1",
            pages = listOf(
                FixturePage(null, listOf(issue("FIX-1", "v1")), null, WATERMARK, OBSERVED_AT, true),
            ),
        ),
    )

    private fun twoPageAdapter(failures: Map<String?, FixtureFailure> = emptyMap()) = FixtureIssueSourceAdapter(
        FixtureScenario(
            source = "FIXTURE",
            mappingVersion = "issue-mapping-v1",
            pages = listOf(
                FixturePage(null, listOf(issue("FIX-1", "v1")), "fixture-page-2", WATERMARK, OBSERVED_AT, false),
                FixturePage(
                    "fixture-page-2",
                    listOf(issue("FIX-2", "v1")),
                    null,
                    WATERMARK,
                    OBSERVED_AT.plusSeconds(1),
                    true,
                ),
            ),
        ),
        failures,
    )

    private fun issue(id: String, version: String) = NormalizedIssue(
        source = "FIXTURE",
        sourceIssueId = id,
        title = "Synthetic $id",
        severity = IssueSeverity.HIGH,
        status = IssueStatus.OPEN,
        rawSeverity = "high",
        rawStatus = "open",
        sourceVersion = version,
        sourceReference = "fixture:$id",
        observedAt = OBSERVED_AT,
        mappingVersion = "issue-mapping-v1",
    )

    private fun count(table: String, column: String, value: String): Int = jdbc
        .sql("SELECT count(*) FROM $table WHERE $column = :value")
        .param("value", value)
        .query(Int::class.java)
        .single()

    private fun clearPreviousSyncFixture() {
        jdbc.sql("DELETE FROM background_job WHERE project_id = :projectId")
            .param("projectId", projectId).update()
        jdbc.sql("DELETE FROM normalized_issue WHERE source_id = :sourceId")
            .param("sourceId", sourceId).update()
        jdbc.sql("DELETE FROM issue_sync_cursor WHERE source_id = :sourceId")
            .param("sourceId", sourceId).update()
        jdbc.sql("DELETE FROM issue_sync_run WHERE source_id = :sourceId")
            .param("sourceId", sourceId).update()
        jdbc.sql("DELETE FROM issue_source WHERE id = :sourceId")
            .param("sourceId", sourceId).update()
        jdbc.sql("DELETE FROM audit_event WHERE project_id = :projectId AND aggregate_type = 'ISSUE_SYNC_RUN'")
            .param("projectId", projectId).update()
        jdbc.sql("DELETE FROM outbox_event WHERE aggregate_type = 'ISSUE_SYNC_RUN'").update()
        jdbc.sql("DELETE FROM idempotency_record WHERE scope = 'issue:sync' AND principal_id = :principalId")
            .param("principalId", principalId).update()
    }

    private fun syncRunValue(syncRunId: String, column: String): String? = jdbc
        .sql("SELECT $column FROM issue_sync_run WHERE id = :id")
        .param("id", syncRunId)
        .query(String::class.java)
        .optional()
        .orElse(null)

    private fun cursorValue(column: String): String? = jdbc
        .sql("SELECT $column FROM issue_sync_cursor WHERE source_id = :sourceId")
        .param("sourceId", sourceId)
        .query(String::class.java)
        .optional()
        .orElse(null)

    private fun installPageCheckpointFailure() {
        jdbc.sql(
            """
            CREATE FUNCTION reject_issue_sync_page() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                IF NEW.status = 'RUNNING' AND NEW.issue_count > 0 THEN
                    RAISE EXCEPTION 'injected page checkpoint failure';
                END IF;
                RETURN NEW;
            END;
            ${'$'}${'$'}
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            CREATE TRIGGER reject_issue_sync_page BEFORE UPDATE ON issue_sync_run
            FOR EACH ROW EXECUTE FUNCTION reject_issue_sync_page()
            """.trimIndent(),
        ).update()
    }

    private fun installStartFailure(target: String) {
        val table = when (target) {
            "audit" -> "audit_event"
            "outbox" -> "outbox_event"
            "job" -> "background_job"
            else -> error("unsupported target")
        }
        jdbc.sql(
            """
            CREATE FUNCTION reject_issue_sync_$target() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                RAISE EXCEPTION 'injected $target failure';
            END;
            ${'$'}${'$'}
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            CREATE TRIGGER reject_issue_sync_$target BEFORE INSERT ON $table
            FOR EACH ROW EXECUTE FUNCTION reject_issue_sync_$target()
            """.trimIndent(),
        ).update()
    }

    private companion object {
        const val ISSUER = "https://idp.vsrqg.test"
        const val WATERMARK = "2026-08-31T12:00:00Z"
        val OBSERVED_AT: Instant = Instant.parse("2026-08-31T12:00:00Z")
    }
}
