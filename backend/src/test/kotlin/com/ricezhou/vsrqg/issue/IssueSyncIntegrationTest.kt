package com.ricezhou.vsrqg.issue

import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.issue.adapter.FixtureFailure
import com.ricezhou.vsrqg.issue.adapter.FixtureIssueSourceAdapter
import com.ricezhou.vsrqg.issue.adapter.FixturePage
import com.ricezhou.vsrqg.issue.adapter.FixtureScenario
import com.ricezhou.vsrqg.issue.adapter.IssueRuntimeConfigurationException
import com.ricezhou.vsrqg.issue.adapter.IssueRuntimeFailureCode
import com.ricezhou.vsrqg.issue.adapter.IssueSourceRuntimeRegistry
import com.ricezhou.vsrqg.issue.adapter.IssueSyncJobWorker
import com.ricezhou.vsrqg.issue.adapter.IssueFactCanonicalizer
import com.ricezhou.vsrqg.issue.adapter.legacyIssueDigest
import com.ricezhou.vsrqg.issue.application.IssueSourceDescriptorRegistry
import com.ricezhou.vsrqg.issue.application.IssueSourceFailureCode
import com.ricezhou.vsrqg.issue.application.IssueSourceRuntimeDescriptor
import com.ricezhou.vsrqg.issue.application.IssueSyncResultSetMode
import com.ricezhou.vsrqg.issue.application.IssueSyncRepository
import com.ricezhou.vsrqg.issue.application.RunIssueSync
import com.ricezhou.vsrqg.issue.application.StartIssueSync
import com.ricezhou.vsrqg.issue.application.StartIssueSyncCommand
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import com.ricezhou.vsrqg.issue.domain.NormalizedIssue
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.math.absoluteValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
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

    @MockitoBean
    private lateinit var runtimeRegistry: IssueSourceRuntimeRegistry

    @MockitoBean
    private lateinit var descriptorRegistry: IssueSourceDescriptorRegistry

    @Autowired
    private lateinit var startIssueSync: StartIssueSync

    @Autowired
    private lateinit var runIssueSync: RunIssueSync

    @Autowired
    private lateinit var issueSyncJobWorker: IssueSyncJobWorker

    @MockitoSpyBean
    private lateinit var issueSyncRepository: IssueSyncRepository

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var mockMvc: MockMvc

    private lateinit var projectId: String
    private lateinit var sourceId: String
    private lateinit var principalId: String
    private lateinit var principal: Principal

    @BeforeEach
    fun prepareAuthorityAndSource() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        projectId = "project_sync_$suffix"
        sourceId = "source_sync_$suffix"
        principalId = "principal_sync_$suffix"
        principal = Principal(ISSUER, "issue-engineer-$suffix", service = false)
        doReturn(
            IssueSourceRuntimeDescriptor(
                sourceType = "FIXTURE",
                adapterId = "fixture",
                adapterVersion = "fixture-adapter-v1",
                supportedMappingSchemas = setOf("fixture-mapping/v1"),
                supportedTransportRange = "fixture/v1",
                resultSetMode = IssueSyncResultSetMode.FULL,
                filterReference = "all-relevant-issues/v1",
            ),
        ).`when`(descriptorRegistry).require("FIXTURE")
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
        jdbc.sql("DROP TRIGGER IF EXISTS reject_issue_sync_observation ON issue_sync_run_item").update()
        jdbc.sql("DROP FUNCTION IF EXISTS reject_issue_sync_observation()").update()
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
        assertThat(normalizedIssueValue("FIX-1", "fact_digest_version"))
            .isEqualTo("normalized-issue-facts/v1")
        assertThat(normalizedIssueValue("FIX-1", "raw_severity_token")).isEqualTo("high")
        assertThat(normalizedIssueValue("FIX-1", "canonical_source_token")).isEqualTo("FIXTURE")
        assertThat(normalizedIssueValue("FIX-1", "mapping_warnings")).isEmpty()
        assertThat(observations(started.syncRunId)).containsExactly(
            Observation(0, "FIX-1", OBSERVED_AT),
            Observation(1, "FIX-2", OBSERVED_AT.plusSeconds(1)),
        )
        assertThat(syncRunValue(started.syncRunId, "result_set_mode")).isEqualTo("FULL")
        assertThat(syncRunValue(started.syncRunId, "filter_reference"))
            .isEqualTo("all-relevant-issues/v1")
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
        runIssueSync.run(second.syncRunId, twoPageAdapter(OBSERVED_AT.plusSeconds(10)))

        assertThat(second.syncRunId).isNotEqualTo(first.syncRunId)
        assertThat(count("normalized_issue", "source_id", sourceId)).isEqualTo(2)
        assertThat(observations(first.syncRunId)).containsExactly(
            Observation(0, "FIX-1", OBSERVED_AT),
            Observation(1, "FIX-2", OBSERVED_AT.plusSeconds(1)),
        )
        assertThat(observations(second.syncRunId)).containsExactly(
            Observation(0, "FIX-1", OBSERVED_AT.plusSeconds(10)),
            Observation(1, "FIX-2", OBSERVED_AT.plusSeconds(11)),
        )
        assertThat(normalizedRevisionObservationTimes())
            .containsExactly(OBSERVED_AT, OBSERVED_AT.plusSeconds(1))
        assertThat(normalizedFactDigest("FIX-1"))
            .isEqualTo(IssueFactCanonicalizer.canonicalize(issue("FIX-1", "v1", OBSERVED_AT)).factDigest)
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
    fun `worker persists runtime authority failure on run and job without advancing cursor`() {
        val started = startIssueSync.start(command("sync-runtime-gate", '7', "request-runtime-gate"))
        jdbc.sql(
            "UPDATE background_job SET status = 'SUCCEEDED' WHERE status = 'QUEUED' AND id <> :jobId",
        ).param("jobId", started.operationId).update()
        val pinnedRun = requireNotNull(issueSyncRepository.findRun(started.syncRunId))
        doThrow(
            IssueRuntimeConfigurationException(IssueRuntimeFailureCode.MAPPING_PROFILE_NOT_CONFIGURED),
        ).`when`(runtimeRegistry).open(pinnedRun)

        assertThat(issueSyncJobWorker.runNext()).isTrue()

        verify(runtimeRegistry).open(pinnedRun)
        assertThat(syncRunValue(started.syncRunId, "status")).isEqualTo("FAILED")
        assertThat(syncRunValue(started.syncRunId, "diagnostic_code"))
            .isEqualTo("MAPPING_PROFILE_NOT_CONFIGURED")
        assertThat(jobValue(started.operationId, "status")).isEqualTo("FAILED")
        assertThat(jobValue(started.operationId, "result_summary ->> 'diagnosticCode'"))
            .isEqualTo("MAPPING_PROFILE_NOT_CONFIGURED")
        assertThat(count("issue_sync_cursor", "source_id", sourceId)).isZero()
    }

    @Test
    fun `page checkpoint failure rolls back every revision in that page and does not advance cursor`() {
        installPageCheckpointFailure()
        val started = startIssueSync.start(command("sync-page-rollback", 'f', "request-page-rollback"))

        val failed = runIssueSync.run(started.syncRunId, onePageAdapter())

        assertThat(failed.status.name).isEqualTo("FAILED")
        assertThat(failed.diagnosticCode).isEqualTo("PERSISTENCE_FAILED")
        assertThat(count("normalized_issue", "source_id", sourceId)).isZero()
        assertThat(count("issue_sync_run_item", "sync_run_id", started.syncRunId)).isZero()
        assertThat(count("issue_sync_cursor", "source_id", sourceId)).isZero()
    }

    @Test
    fun `observation insertion failure rolls back revisions and page checkpoint together`() {
        installObservationFailure()
        val started = startIssueSync.start(command("sync-observation-rollback", '0', "request-observation-rollback"))

        val failed = runIssueSync.run(started.syncRunId, onePageAdapter())

        assertThat(failed.status.name).isEqualTo("FAILED")
        assertThat(count("normalized_issue", "source_id", sourceId)).isZero()
        assertThat(count("issue_sync_run_item", "sync_run_id", started.syncRunId)).isZero()
        assertThat(syncRunInt(started.syncRunId, "issue_count")).isZero()
        assertThat(syncRunValue(started.syncRunId, "cursor_after")).isNull()
        assertThat(syncRunValue(started.syncRunId, "source_watermark")).isNull()
    }

    @Test
    fun `terminal repository calls are idempotent only for the same terminal facts`() {
        val started = startIssueSync.start(command("sync-terminal-seal", '6', "request-terminal-seal"))
        runIssueSync.run(started.syncRunId, onePageAdapter())

        assertThat(issueSyncRepository.markSucceeded(started.syncRunId, null, WATERMARK))
            .isEqualTo(issueSyncRepository.findRun(started.syncRunId))
        assertThatThrownBy {
            issueSyncRepository.markSucceeded(started.syncRunId, "different", WATERMARK)
        }.isInstanceOf(ResourceConflict::class.java)
        assertThatThrownBy {
            issueSyncRepository.markFailed(started.syncRunId, "PERSISTENCE_FAILED")
        }.isInstanceOf(ResourceConflict::class.java)
    }

    @Test
    fun `legacy run with null result metadata remains readable and lockable without invented defaults`() {
        val legacyRunId = "legacy_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        jdbc.sql(
            """
            INSERT INTO issue_sync_run(
              id, project_id, source_id, sync_run_id, status, adapter_version,
              mapping_version, result_set_mode, filter_reference, created_at
            ) VALUES (
              :id, :projectId, :sourceId, :id, 'QUEUED', 'fixture-adapter-v1',
              'issue-mapping-v1', NULL, NULL, now()
            )
            """.trimIndent(),
        )
            .param("id", legacyRunId)
            .param("projectId", projectId)
            .param("sourceId", sourceId)
            .update()

        val found = requireNotNull(issueSyncRepository.findRun(legacyRunId))
        assertThat(found.resultSetMode).isNull()
        assertThat(found.filterReference).isNull()

        val locked = issueSyncRepository.markRunning(legacyRunId)
        assertThat(locked.resultSetMode).isNull()
        assertThat(locked.filterReference).isNull()
        issueSyncRepository.markFailed(legacyRunId, "LEGACY_TEST_COMPLETE")
    }

    @Test
    fun `nanosecond facts and page observations use one truncated PostgreSQL microsecond authority`() {
        val nanos = Instant.parse("2026-09-02T12:00:00.123456789Z")
        val micros = Instant.parse("2026-09-02T12:00:00.123456Z")
        val first = startIssueSync.start(command("sync-nanos-first", '4', "request-nanos-first"))

        runIssueSync.run(first.syncRunId, onePageAdapter(nanos, nanos))

        assertThat(normalizedRevisionObservationTimes()).containsExactly(micros)
        assertThat(observations(first.syncRunId)).containsExactly(Observation(0, "FIX-1", micros))
        assertThat(normalizedFactDigest("FIX-1"))
            .isEqualTo(IssueFactCanonicalizer.canonicalize(issue("FIX-1", "v1", nanos)).factDigest)

        val second = startIssueSync.start(command("sync-nanos-second", '5', "request-nanos-second"))
        runIssueSync.run(second.syncRunId, onePageAdapter(nanos, nanos))

        assertThat(count("normalized_issue", "source_id", sourceId)).isOne()
        assertThat(observations(second.syncRunId)).containsExactly(Observation(0, "FIX-1", micros))
    }

    @Test
    fun `pre V7 nanosecond digest is reused with a later adapter observation`() {
        val firstObservedAt = Instant.parse("2026-09-02T12:00:00.123456789Z")
        val legacyIssue = issue("FIX-1", "v1", firstObservedAt)
        insertPreV7LegacyNormalizedIssueFixture(legacyIssue)
        val storedObservedAt = normalizedRevisionObservationTimes().single()

        assertThat(storedObservedAt.nano % 1_000).isZero()
        assertThat(java.time.Duration.between(firstObservedAt, storedObservedAt).toNanos().absoluteValue)
            .isLessThanOrEqualTo(999)

        val laterObservedAt = Instant.parse("2026-09-02T12:00:10.987654321Z")
        val started = startIssueSync.start(command("sync-legacy-nanos", '6', "request-legacy-nanos"))
        val completed = runIssueSync.run(
            started.syncRunId,
            onePageAdapterFor(legacyIssue.copy(observedAt = laterObservedAt), laterObservedAt),
        )

        assertThat(completed.status).isEqualTo(com.ricezhou.vsrqg.issue.application.IssueSyncStatus.SUCCEEDED)
        assertThat(count("normalized_issue", "source_id", sourceId)).isOne()
        assertThat(normalizedIssueValue("FIX-1", "fact_digest_version")).isNull()
        assertThat(normalizedFactDigest("FIX-1")).isEqualTo(legacyIssueDigest(legacyIssue, firstObservedAt))
        assertThat(observations(started.syncRunId)).containsExactly(
            Observation(0, "FIX-1", Instant.parse("2026-09-02T12:00:10.987654Z")),
        )
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
    fun `project change after authorization fails with fixed access denied diagnostic`() {
        val key = "sync-project-race"
        val requestId = "request-project-race"
        val authorized = requireNotNull(issueSyncRepository.findSource(sourceId))
        doReturn(authorized.copy(projectId = "project_changed"))
            .`when`(issueSyncRepository).lockSource(sourceId)

        assertThatThrownBy {
            startIssueSync.start(command(key, '8', requestId))
        }
            .isInstanceOf(AccessDeniedException::class.java)
            .hasMessage("ACCESS_DENIED")

        assertThat(count("issue_sync_run", "source_id", sourceId)).isZero()
        assertThat(count("idempotency_record", "idempotency_key", key)).isZero()
        assertThat(count("audit_event", "correlation_id", requestId)).isZero()
        assertThat(outboxCount(requestId)).isZero()
        assertThat(backgroundJobCount(key)).isZero()
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

    private fun onePageAdapter(
        issueObservedAt: Instant = OBSERVED_AT,
        pageObservedAt: Instant = OBSERVED_AT,
    ) = FixtureIssueSourceAdapter(
        FixtureScenario(
            source = "FIXTURE",
            mappingVersion = "issue-mapping-v1",
            pages = listOf(
                FixturePage(
                    null,
                    listOf(issue("FIX-1", "v1", issueObservedAt)),
                    null,
                    WATERMARK,
                    pageObservedAt,
                    true,
                ),
            ),
        ),
    )

    private fun twoPageAdapter(
        observationTime: Instant = OBSERVED_AT,
        failures: Map<String?, FixtureFailure> = emptyMap(),
    ) = FixtureIssueSourceAdapter(
        FixtureScenario(
            source = "FIXTURE",
            mappingVersion = "issue-mapping-v1",
            pages = listOf(
                FixturePage(
                    null,
                    listOf(issue("FIX-1", "v1", observationTime)),
                    "fixture-page-2",
                    WATERMARK,
                    observationTime,
                    false,
                ),
                FixturePage(
                    "fixture-page-2",
                    listOf(issue("FIX-2", "v1", observationTime.plusSeconds(1))),
                    null,
                    WATERMARK,
                    observationTime.plusSeconds(1),
                    true,
                ),
            ),
        ),
        failures,
    )

    private fun onePageAdapterFor(issue: NormalizedIssue, pageObservedAt: Instant) = FixtureIssueSourceAdapter(
        FixtureScenario(
            source = "FIXTURE",
            mappingVersion = "issue-mapping-v1",
            pages = listOf(FixturePage(null, listOf(issue), null, WATERMARK, pageObservedAt, true)),
        ),
    )

    private fun issue(
        id: String,
        version: String,
        observedAt: Instant = OBSERVED_AT,
    ) = NormalizedIssue(
        source = "FIXTURE",
        sourceIssueId = id,
        title = "Synthetic $id",
        severity = IssueSeverity.HIGH,
        status = IssueStatus.OPEN,
        rawSeverity = "high",
        rawStatus = "open",
        sourceVersion = version,
        sourceReference = "fixture:$id",
        observedAt = observedAt,
        mappingVersion = "issue-mapping-v1",
    )

    private fun count(table: String, column: String, value: String): Int = jdbc
        .sql("SELECT count(*) FROM $table WHERE $column = :value")
        .param("value", value)
        .query(Int::class.java)
        .single()

    private fun syncRunValue(syncRunId: String, column: String): String? = jdbc
        .sql("SELECT $column FROM issue_sync_run WHERE id = :id")
        .param("id", syncRunId)
        .query(String::class.java)
        .optional()
        .orElse(null)

    private fun syncRunInt(syncRunId: String, column: String): Int = jdbc
        .sql("SELECT $column FROM issue_sync_run WHERE id = :id")
        .param("id", syncRunId)
        .query(Int::class.java)
        .single()

    private fun observations(syncRunId: String): List<Observation> = jdbc.sql(
        """
        SELECT ordinal, source_issue_id, observed_at
        FROM issue_sync_run_item
        WHERE sync_run_id = :syncRunId
        ORDER BY ordinal
        """.trimIndent(),
    )
        .param("syncRunId", syncRunId)
        .query { rs, _ ->
            Observation(
                rs.getInt("ordinal"),
                rs.getString("source_issue_id"),
                rs.getObject("observed_at", java.time.OffsetDateTime::class.java).toInstant(),
            )
        }
        .list()

    private fun normalizedRevisionObservationTimes(): List<Instant> = jdbc.sql(
        """
        SELECT observed_at
        FROM normalized_issue
        WHERE source_id = :sourceId
        ORDER BY source_issue_id
        """.trimIndent(),
    )
        .param("sourceId", sourceId)
        .query { rs, _ -> rs.getObject("observed_at", java.time.OffsetDateTime::class.java).toInstant() }
        .list()

    private fun normalizedFactDigest(sourceIssueId: String): String = jdbc.sql(
        """
        SELECT fact_digest
        FROM normalized_issue
        WHERE source_id = :sourceId AND source_issue_id = :sourceIssueId
        """.trimIndent(),
    )
        .param("sourceId", sourceId)
        .param("sourceIssueId", sourceIssueId)
        .query(String::class.java)
        .single()

    private fun normalizedIssueValue(sourceIssueId: String, column: String): String? = jdbc.sql(
        "SELECT $column FROM normalized_issue WHERE source_id = :sourceId AND source_issue_id = :sourceIssueId",
    )
        .param("sourceId", sourceId)
        .param("sourceIssueId", sourceIssueId)
        .query(String::class.java)
        .optional()
        .orElse(null)

    // This bypasses only the V7 insert trigger to reproduce a row written before that trigger existed.
    private fun insertPreV7LegacyNormalizedIssueFixture(issue: NormalizedIssue) {
        dataSource.connection.use { connection ->
            var replicaModeEnabled = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute("SET session_replication_role = replica")
                    replicaModeEnabled = true
                }
                connection.prepareStatement(
                    """
                    INSERT INTO normalized_issue(
                      id, project_id, source_id, source_issue_id, title, severity, status,
                      raw_status_token, source_version, source_reference, observed_at,
                      mapping_version, tombstone, fact_digest, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, "legacy_${UUID.randomUUID().toString().replace("-", "").take(8)}")
                    statement.setString(2, projectId)
                    statement.setString(3, sourceId)
                    statement.setString(4, issue.sourceIssueId)
                    statement.setString(5, issue.title)
                    statement.setString(6, issue.severity.name)
                    statement.setString(7, issue.status.name)
                    statement.setString(8, issue.rawStatus)
                    statement.setString(9, issue.sourceVersion)
                    statement.setString(10, issue.sourceReference)
                    statement.setObject(11, issue.observedAt.atOffset(ZoneOffset.UTC))
                    statement.setString(12, issue.mappingVersion)
                    statement.setBoolean(13, issue.tombstone)
                    statement.setString(14, legacyIssueDigest(issue, issue.observedAt))
                    statement.executeUpdate()
                }
            } finally {
                if (replicaModeEnabled) {
                    connection.createStatement().use { statement ->
                        statement.execute("SET session_replication_role = origin")
                    }
                }
            }
        }
    }

    private fun cursorValue(column: String): String? = jdbc
        .sql("SELECT $column FROM issue_sync_cursor WHERE source_id = :sourceId")
        .param("sourceId", sourceId)
        .query(String::class.java)
        .optional()
        .orElse(null)

    private fun jobValue(jobId: String, column: String): String? = jdbc
        .sql("SELECT $column FROM background_job WHERE id = :id")
        .param("id", jobId)
        .query(String::class.java)
        .optional()
        .orElse(null)

    private fun outboxCount(requestId: String): Int = jdbc.sql(
        """
        SELECT count(*) FROM outbox_event
        WHERE payload ->> 'requestId' = :requestId
          AND payload ->> 'sourceId' = :sourceId
        """.trimIndent(),
    )
        .param("requestId", requestId)
        .param("sourceId", sourceId)
        .query(Int::class.java)
        .single()

    private fun backgroundJobCount(idempotencyKey: String): Int = jdbc.sql(
        """
        SELECT count(*) FROM background_job
        WHERE project_id = :projectId
          AND idempotency_key = :idempotencyKey
        """.trimIndent(),
    )
        .param("projectId", projectId)
        .param("idempotencyKey", idempotencyKey)
        .query(Int::class.java)
        .single()

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

    private fun installObservationFailure() {
        jdbc.sql(
            """
            CREATE FUNCTION reject_issue_sync_observation() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                RAISE EXCEPTION 'injected observation failure';
            END;
            ${'$'}${'$'}
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            CREATE TRIGGER reject_issue_sync_observation BEFORE INSERT ON issue_sync_run_item
            FOR EACH ROW EXECUTE FUNCTION reject_issue_sync_observation()
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

    private data class Observation(
        val ordinal: Int,
        val sourceIssueId: String,
        val observedAt: Instant,
    )
}
