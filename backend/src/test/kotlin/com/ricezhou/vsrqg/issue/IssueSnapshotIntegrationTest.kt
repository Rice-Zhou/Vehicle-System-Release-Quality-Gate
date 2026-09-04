package com.ricezhou.vsrqg.issue

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.application.ProjectAuthorization
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.issue.application.CanonicalIssueSnapshot
import com.ricezhou.vsrqg.issue.application.CreateIssueSnapshot
import com.ricezhou.vsrqg.issue.application.CreateIssueSnapshotCommand
import com.ricezhou.vsrqg.issue.application.CreateIssueSnapshotResult
import com.ricezhou.vsrqg.issue.application.IssueSnapshotCandidate
import com.ricezhou.vsrqg.issue.application.IssueSnapshotContext
import com.ricezhou.vsrqg.issue.application.IssueSnapshotInvalid
import com.ricezhou.vsrqg.issue.application.IssueSnapshotRepository
import com.ricezhou.vsrqg.issue.application.IssueSnapshotPolicy
import com.ricezhou.vsrqg.issue.application.MaterializedIssueSnapshot
import com.ricezhou.vsrqg.issue.application.SnapshotObservation
import com.ricezhou.vsrqg.issue.application.SnapshotFactIntegrityFailure
import com.ricezhou.vsrqg.issue.application.SnapshotContentIntegrityFailure
import com.ricezhou.vsrqg.issue.application.SyncObservationIntegrityFailure
import com.ricezhou.vsrqg.issue.application.SuccessfulFullIssueSyncRun
import com.ricezhou.vsrqg.issue.adapter.IssueSnapshotProperties
import com.ricezhou.vsrqg.issue.adapter.IssueSnapshotConfiguration
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotencyConflict
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.security.access.AccessDeniedException
import com.ricezhou.vsrqg.shared.problem.ProblemHandler
import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import com.ricezhou.vsrqg.shared.id.UuidV7IdGenerator
import org.springframework.mock.web.MockHttpServletRequest
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

class IssueSnapshotUseCaseTest {
    @Test
    fun `creates immutable snapshot from latest successful full run and replays same key`() {
        val fixture = Fixture()
        val command = fixture.command("same-key")

        val created = fixture.useCase.create(command)
        val replay = fixture.useCase.create(command)

        assertThat(replay).isEqualTo(created)
        assertThat(created.selectedCount).isEqualTo(2)
        assertThat(created.snapshotId).startsWith("isnap_").hasSizeLessThanOrEqualTo(40)
        assertThat(created.contentDigest).matches("sha256:[0-9a-f]{64}")
        assertThat(fixture.repository.inserted).hasSize(1)
        assertThat(fixture.authorizedPermission).isEqualTo(Permission.ISSUE_SNAPSHOT)
        assertThat(fixture.governance.auditPayload?.fieldNames()?.asSequence()?.toList())
            .containsExactlyInAnyOrderElementsOf(SAFE_PAYLOAD_FIELDS)
        assertThat(fixture.governance.outboxPayload).isEqualTo(fixture.governance.auditPayload)
    }

    @Test
    fun `same key with another request digest conflicts`() {
        val fixture = Fixture()
        fixture.useCase.create(fixture.command("same-key"))

        assertThatThrownBy {
            fixture.useCase.create(fixture.command("same-key", digest = sha256("another-request")))
        }.isInstanceOf(IdempotencyConflict::class.java)
    }

    @Test
    fun `different keys converge on the same logical snapshot`() {
        val fixture = Fixture()

        val first = fixture.useCase.create(fixture.command("first-key"))
        val second = fixture.useCase.create(fixture.command("second-key"))

        assertThat(second).isEqualTo(first)
        assertThat(fixture.repository.inserted).hasSize(1)
        assertThat(fixture.repository.nextVersionCalls).isOne()
    }

    @Test
    fun `snapshot max age must be strictly positive`() {
        listOf(Duration.ZERO, Duration.ofNanos(-1)).forEach { invalid ->
            assertThatThrownBy { IssueSnapshotProperties(maxSyncAge = invalid) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("ISSUE_SNAPSHOT_MAX_SYNC_AGE_INVALID")
        }
    }

    @Test
    fun `disabled snapshot capability fails closed with the approved hidden resource response`() {
        val fixture = Fixture(IssueSnapshotPolicy(false, Duration.ofHours(24)))

        assertThatThrownBy { fixture.useCase.create(fixture.command()) }
            .isInstanceOf(ResourceNotFound::class.java)
            .extracting("code")
            .isEqualTo("RESOURCE_NOT_FOUND")
    }

    @Test
    fun `company mode forces snapshot entry disabled while pilot honors its configured value`() {
        assertThat(snapshotPolicy("vsrqg.deployment.mode=PILOT").enabled).isTrue()
        assertThat(
            snapshotPolicy("vsrqg.deployment.mode=PILOT", "vsrqg.issue.snapshot.enabled=false").enabled,
        ).isFalse()
        assertThat(
            snapshotPolicy("vsrqg.deployment.mode=COMPANY", "vsrqg.issue.snapshot.enabled=true").enabled,
        ).isFalse()
    }

    @Test
    fun `age boundary and created time use timestamp captured before authority reads and authorization`() {
        val fixture = Fixture(advanceOnContext = Duration.ofNanos(1))
        fixture.repository.run = fixture.repository.run?.copy(completedAt = fixture.now.minus(Duration.ofHours(24)))

        val result = fixture.useCase.create(fixture.command())

        assertThat(result.createdAt).isEqualTo(fixture.now)
    }

    @Test
    fun `safe snapshot validation response contains no source details`() {
        val request = MockHttpServletRequest("POST", "/api/v1/releases/release-1/issue-snapshots")
        request.setAttribute(com.ricezhou.vsrqg.shared.web.RequestIdFilter.REQUEST_ID_ATTRIBUTE, "request-1")
        val response = ProblemHandler(ProblemWriter(ObjectMapper())).safeValidationFailure(
            IssueSnapshotInvalid("SYNC_OBSERVATION_INTEGRITY_FAILED"),
            request,
        )
        val body = ObjectMapper().writeValueAsString(response.body)

        assertThat(response.statusCode.value()).isEqualTo(422)
        assertThat(body).contains("ISSUE_SNAPSHOT_INVALID", "SYNC_OBSERVATION_INTEGRITY_FAILED")
        assertThat(body).doesNotContain("secret title", "jira", "jql", "stack", "payload", "sourceReference")
    }

    @Test
    fun `missing context is hidden as not found`() {
        val fixture = Fixture().also { it.repository.context = null }

        assertThatThrownBy { fixture.useCase.create(fixture.command()) }
            .isInstanceOf(ResourceNotFound::class.java)
            .extracting("code")
            .isEqualTo("RESOURCE_NOT_FOUND")
    }

    @Test
    fun `project change while locking is hidden as not found`() {
        val fixture = Fixture().also { it.repository.lockedContext = it.repository.context?.copy(projectId = "other") }

        assertThatThrownBy { fixture.useCase.create(fixture.command()) }
            .isInstanceOf(ResourceNotFound::class.java)
    }

    @Test
    fun `authorization completes before idempotency or authority locks`() {
        val fixture = Fixture(denySnapshotAuthorization = true)

        assertThatThrownBy { fixture.useCase.create(fixture.command()) }
            .isInstanceOf(AccessDeniedException::class.java)
        assertThat(fixture.idempotency.executions).isZero()
        assertThat(fixture.repository.lockCalls).isZero()
    }

    @Test
    fun `principal without target project visibility receives hidden resource not found`() {
        val fixture = Fixture(denyVisibility = true)

        assertThatThrownBy { fixture.useCase.create(fixture.command()) }
            .isInstanceOf(ResourceNotFound::class.java)
            .extracting("code")
            .isEqualTo("RESOURCE_NOT_FOUND")
        assertThat(fixture.idempotency.executions).isZero()
    }

    @Test
    fun `locked manifest and eligible full run are fixed conflicts`() {
        val unlocked = Fixture().also { it.repository.lockedContext = it.repository.context?.copy(lockedManifestId = null) }
        assertThatThrownBy { unlocked.useCase.create(unlocked.command()) }
            .isInstanceOf(ResourceConflict::class.java)
            .extracting("code").isEqualTo("RELEASE_MANIFEST_NOT_LOCKED")

        val noFull = Fixture().also { it.repository.run = null }
        assertThatThrownBy { noFull.useCase.create(noFull.command()) }
            .isInstanceOf(ResourceConflict::class.java)
            .extracting("code").isEqualTo("ELIGIBLE_SYNC_NOT_FOUND")
    }

    @Test
    fun `sync age accepts exact PT24H boundary and rejects older or future completion`() {
        val exact = Fixture()
        exact.repository.run = exact.repository.run?.copy(completedAt = exact.now.minus(Duration.ofHours(24)))
        assertThat(exact.useCase.create(exact.command()).selectedCount).isEqualTo(2)

        listOf(exact.now.minus(Duration.ofHours(24)).minusNanos(1), exact.now.plusNanos(1)).forEach { completion ->
            val invalid = Fixture().also { it.repository.run = it.repository.run?.copy(completedAt = completion) }
            assertThatThrownBy { invalid.useCase.create(invalid.command()) }
                .isInstanceOfSatisfying(IssueSnapshotInvalid::class.java) { failure ->
                    assertThat(failure.violationCodes).containsExactly("SYNC_RUN_STALE")
                }
        }
    }

    @Test
    fun `empty authoritative observations create an empty snapshot`() {
        val fixture = Fixture().also {
            it.repository.observations = emptyList()
            it.repository.run = it.repository.run?.copy(issueCount = 0)
        }

        assertThat(fixture.useCase.create(fixture.command()).selectedCount).isZero()
    }

    @Test
    fun `unknown raw status is preserved in materialized snapshot`() {
        val fixture = Fixture().also {
            it.repository.observations = it.repository.observations.mapIndexed { index, observation ->
                if (index == 0) observation.copy(status = IssueStatus.UNKNOWN, rawStatusToken = "vendor-new-state") else observation
            }
        }

        fixture.useCase.create(fixture.command())

        assertThat(fixture.repository.inserted.single().candidate.observations.first().rawStatusToken)
            .isEqualTo("vendor-new-state")
    }

    @Test
    fun `observation and snapshot integrity failures expose only fixed diagnostics`() {
        val observations = Fixture().also { it.repository.observationFailure = true }
        assertThatThrownBy { observations.useCase.create(observations.command()) }
            .isInstanceOfSatisfying(IssueSnapshotInvalid::class.java) { failure ->
                assertThat(failure.violationCodes).containsExactly("SYNC_OBSERVATION_INTEGRITY_FAILED")
            }

        val readback = Fixture().also { it.repository.readFailure = true }
        assertThatThrownBy { readback.useCase.create(readback.command()) }
            .isInstanceOfSatisfying(IssueSnapshotInvalid::class.java) { failure ->
                assertThat(failure.violationCodes).containsExactly("SNAPSHOT_INTEGRITY_FAILED")
            }

        val fact = Fixture().also { it.repository.factFailure = true }
        assertThatThrownBy { fact.useCase.create(fact.command()) }
            .isInstanceOfSatisfying(IssueSnapshotInvalid::class.java) { failure ->
                assertThat(failure.violationCodes).containsExactly("SNAPSHOT_INTEGRITY_FAILED")
            }

        val revalidatedObservations = Fixture().also { it.repository.insertObservationFailure = true }
        assertThatThrownBy { revalidatedObservations.useCase.create(revalidatedObservations.command()) }
            .isInstanceOfSatisfying(IssueSnapshotInvalid::class.java) { failure ->
                assertThat(failure.violationCodes).containsExactly("SYNC_OBSERVATION_INTEGRITY_FAILED")
            }
    }

    @Test
    fun `ordinary database failure is not converted to semantic validation failure`() {
        val fixture = Fixture().also { it.repository.loadFailure = true }

        assertThatThrownBy { fixture.useCase.create(fixture.command()) }
            .isInstanceOf(DataAccessResourceFailureException::class.java)
            .isNotInstanceOf(IssueSnapshotInvalid::class.java)
        assertThat(fixture.repository.inserted).isEmpty()
        assertThat(fixture.governance.auditPayload).isNull()
        assertThat(fixture.governance.outboxPayload).isNull()
    }

    private class Fixture(
        policy: IssueSnapshotPolicy = IssueSnapshotPolicy(true, Duration.ofHours(24)),
        denySnapshotAuthorization: Boolean = false,
        denyVisibility: Boolean = false,
        advanceOnContext: Duration = Duration.ZERO,
    ) {
        val now: Instant = Instant.parse("2026-09-03T12:00:00Z")
        private var clock = now
        val repository = FakeRepository(now) { clock = clock.plus(advanceOnContext) }
        val governance = CapturingGovernance()
        val idempotency = MemoryIdempotency()
        var authorizedPermission: Permission? = null
        private val authorizer = ProjectAuthorizer { _, _, permission ->
            authorizedPermission = permission
            if ((denyVisibility && permission == Permission.RELEASE_READ) ||
                (denySnapshotAuthorization && permission == Permission.ISSUE_SNAPSHOT)
            ) throw AccessDeniedException("denied")
            ProjectAuthorization("principal-1")
        }
        val useCase = CreateIssueSnapshot(
            authorizer,
            idempotency,
            repository,
            { candidate ->
                val bytes = candidate.observations.joinToString("|") { it.sourceIssueId + ":" + it.rawStatusToken }
                    .toByteArray(StandardCharsets.UTF_8)
                CanonicalIssueSnapshot(bytes, sha256(String(bytes, StandardCharsets.UTF_8)))
            },
            governance,
            UuidV7IdGenerator(),
            { clock },
            ObjectMapper(),
            policy,
        )

        fun command(key: String = "key", digest: String = sha256("request")) = CreateIssueSnapshotCommand(
            Principal("issuer", "subject", false), "release-1", "source-1", key, digest, "request-1",
        )
    }

    private class FakeRepository(now: Instant, private val beforeFindContext: () -> Unit = {}) : IssueSnapshotRepository {
        var context: IssueSnapshotContext? = IssueSnapshotContext("project-1", "release-1", "manifest-1", "source-1")
        var lockedContext: IssueSnapshotContext? = context
        var run: SuccessfulFullIssueSyncRun? = SuccessfulFullIssueSyncRun(
            "run-1", "project-1", "source-1", "watermark", "adapter-v1", "mapping-v1",
            "all-relevant-issues/v1", 2, now.minusSeconds(60),
        )
        var observations = listOf(observation("A"), observation("B"))
        val inserted = mutableListOf<MaterializedIssueSnapshot>()
        var nextVersionCalls = 0
        var lockCalls = 0
        var loadFailure = false
        var observationFailure = false
        var readFailure = false
        var factFailure = false
        var insertObservationFailure = false
        override fun findContext(releaseId: String, sourceId: String): IssueSnapshotContext? {
            beforeFindContext()
            return context
        }
        override fun lockContext(releaseId: String, sourceId: String): IssueSnapshotContext? {
            lockCalls++
            return lockedContext
        }
        override fun findLatestSuccessfulFullRun(projectId: String, sourceId: String) = run
        override fun findExisting(releaseId: String, syncRunId: String, filterReference: String) =
            inserted.singleOrNull()
        override fun nextSnapshotVersion(releaseId: String): Int {
            nextVersionCalls++
            return inserted.size + 1
        }
        override fun loadObservations(run: SuccessfulFullIssueSyncRun): List<SnapshotObservation> {
            if (factFailure) throw SnapshotFactIntegrityFailure()
            if (observationFailure) throw SyncObservationIntegrityFailure()
            if (loadFailure) throw DataAccessResourceFailureException("secret Jira URL and payload")
            return observations
        }
        override fun insert(snapshot: MaterializedIssueSnapshot) {
            if (insertObservationFailure) throw SyncObservationIntegrityFailure()
            inserted += snapshot
        }
        override fun read(snapshotId: String): MaterializedIssueSnapshot? {
            if (readFailure) throw SnapshotContentIntegrityFailure()
            return inserted.singleOrNull { it.snapshotId == snapshotId }
        }
    }

    private class MemoryIdempotency : IdempotentExecutor {
        private val values = mutableMapOf<String, Pair<String, Any>>()
        var executions = 0
        override fun <T : Any> execute(scope: String, principalId: String, key: String, requestDigest: String,
            responseType: Class<T>, action: () -> T): T {
            executions++
            val existing = values["$scope:$principalId:$key"]
            if (existing != null) {
                if (existing.first != requestDigest) throw IdempotencyConflict(scope)
                @Suppress("UNCHECKED_CAST") return existing.second as T
            }
            return action().also { values["$scope:$principalId:$key"] = requestDigest to it }
        }
    }

    private class CapturingGovernance : GovernanceStore {
        var auditPayload: com.fasterxml.jackson.databind.JsonNode? = null
        var outboxPayload: com.fasterxml.jackson.databind.JsonNode? = null
        override fun appendAudit(projectId: String, actorId: String, action: String, resourceType: String,
            resourceId: String, requestId: String, reason: String?, beforeState: com.fasterxml.jackson.databind.JsonNode?,
            afterState: com.fasterxml.jackson.databind.JsonNode?) { auditPayload = afterState }
        override fun appendOutbox(eventType: String, aggregateType: String, aggregateId: String,
            payload: com.fasterxml.jackson.databind.JsonNode) { outboxPayload = payload }
    }

    private companion object {
        val SAFE_PAYLOAD_FIELDS = listOf("schemaVersion", "snapshotId", "releaseId", "sourceId", "syncRunId",
            "snapshotVersion", "selectedCount", "contentDigest")
        fun observation(id: String) = SnapshotObservation(
            "issue-$id", id, "secret title", IssueSeverity.HIGH, IssueStatus.OPEN, "open", "v1",
            "https://jira.invalid/$id", Instant.parse("2026-09-03T10:00:00Z"), "mapping-v1", false, sha256("fact-$id"),
        )
        fun sha256(value: String): String = "sha256:" + MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun snapshotPolicy(vararg properties: String): IssueSnapshotPolicy {
            var result: IssueSnapshotPolicy? = null
            ApplicationContextRunner()
                .withUserConfiguration(IssueSnapshotConfiguration::class.java)
                .withPropertyValues(*properties)
                .run { context ->
                    assertThat(context.startupFailure).isNull()
                    result = context.getBean(IssueSnapshotPolicy::class.java)
                }
            return requireNotNull(result)
        }
    }
}

@AutoConfigureMockMvc
class IssueSnapshotIntegrationTest : PostgresIntegrationTest() {
    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @Autowired private lateinit var useCase: CreateIssueSnapshot
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource

    private lateinit var projectId: String
    private lateinit var releaseId: String
    private lateinit var sourceId: String
    private lateinit var runId: String
    private lateinit var principalId: String
    private val issuer = "https://issuer.vsrqg.test"

    @org.junit.jupiter.api.BeforeEach
    fun seedAuthority() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        projectId = "project_tx_$suffix"
        releaseId = "release_tx_$suffix"
        sourceId = "source_tx_$suffix"
        runId = "run_tx_$suffix"
        principalId = "principal_tx_$suffix"
        jdbc.sql("INSERT INTO project(id, project_key, name, created_at) VALUES (:id, :id, :id, now())")
            .param("id", projectId).update()
        jdbc.sql(
            """INSERT INTO principal(id, issuer, subject, principal_type, disabled, created_at)
               VALUES (:id, :issuer, :subject, 'USER', false, now())""",
        ).param("id", principalId).param("issuer", issuer).param("subject", principalId).update()
        jdbc.sql(
            """INSERT INTO project_assignment(project_id, principal_id, role, created_at)
               VALUES (:projectId, :principalId, 'ENGINEER', now())""",
        ).param("projectId", projectId).param("principalId", principalId).update()
        jdbc.sql(
            """INSERT INTO release_record(id, project_id, vehicle, platform, system_version, build_id,
                 status, created_at, updated_at)
               VALUES (:id, :projectId, 'vehicle', 'platform', 'v1', 'build', 'REGISTERED', now(), now())""",
        ).param("id", releaseId).param("projectId", projectId).update()
        val manifestId = "manifest_tx_$suffix"
        jdbc.sql(
            """INSERT INTO manifest_revision(id, release_id, revision, content_digest, raw_manifest,
                 canonical_bytes, schema_version, state, created_at, updated_at)
               VALUES (:id, :releaseId, 1, :digest, CAST('{}' AS jsonb), CAST('{}' AS bytea),
                 'release-manifest/v0.2', 'LOCKED', now(), now())""",
        ).param("id", manifestId).param("releaseId", releaseId).param("digest", hash("manifest-$suffix")).update()
        jdbc.sql("UPDATE release_record SET locked_manifest_id=:manifestId WHERE id=:releaseId")
            .param("manifestId", manifestId).param("releaseId", releaseId).update()
        jdbc.sql(
            """INSERT INTO issue_source(id, project_id, source_key, source_type, adapter_version,
                 mapping_version, enabled, created_at, updated_at)
               VALUES (:id, :projectId, :id, 'FIXTURE', 'adapter-v1', 'mapping-v1', true, now(), now())""",
        ).param("id", sourceId).param("projectId", projectId).update()
        jdbc.sql(
            """INSERT INTO issue_sync_run(id, project_id, source_id, sync_run_id, status,
                 source_watermark, adapter_version, mapping_version, result_set_mode, filter_reference,
                 issue_count, completed_at, created_at)
               VALUES (:id, :projectId, :sourceId, :id, 'RUNNING', 'watermark', 'adapter-v1',
                 'mapping-v1', 'FULL', 'all-relevant-issues/v1', 0, null, now())""",
        ).param("id", runId).param("projectId", projectId).param("sourceId", sourceId).update()
        listOf("A", "B").forEachIndexed { ordinal, key -> seedObservation(ordinal, key) }
        jdbc.sql("UPDATE issue_sync_run SET status='SUCCEEDED', issue_count=2, completed_at=now() WHERE id=:id")
            .param("id", runId).update()
    }

    @org.junit.jupiter.api.AfterEach
    fun removeFailureTriggers() {
        listOf("release_issue_snapshot", "release_issue_snapshot_item", "audit_event", "outbox_event").forEach { table ->
            jdbc.sql("DROP TRIGGER IF EXISTS reject_snapshot_tx_test ON $table").update()
        }
        jdbc.sql("DROP FUNCTION IF EXISTS reject_snapshot_tx_test()").update()
    }

    @Test
    fun `snapshot API materializes one immutable release input and replays the response`() {
        fun post(key: String) = mockMvc.post("/api/v1/releases/{releaseId}/issue-snapshots", releaseId) {
            with(
                jwt().jwt { it.issuer(issuer).subject(principalId).claim("principal_type", "USER") }
                    .authorities(SimpleGrantedAuthority("SCOPE_issue:snapshot")),
            )
            header("Idempotency-Key", key)
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"sourceId":"$sourceId"}"""
        }

        val first = post("snapshot-api-${releaseId.takeLast(8)}").andExpect {
            status { isCreated() }
            jsonPath("$.snapshotId") { value(org.hamcrest.Matchers.startsWith("isnap_")) }
            jsonPath("$.contentDigest") { value(org.hamcrest.Matchers.matchesPattern("sha256:[0-9a-f]{64}")) }
            jsonPath("$.selectedCount") { value(2) }
        }.andReturn().response.contentAsString
        val replay = post("snapshot-api-${releaseId.takeLast(8)}").andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        assertThat(replay).isEqualTo(first)
        assertThat(count("release_issue_snapshot", "release_id", releaseId)).isOne()
        assertThat(count("release_issue_snapshot_item", "project_id", projectId)).isEqualTo(2)
    }

    @Test
    fun `item audit and outbox failure each roll back the whole idempotent transaction`() {
        listOf("release_issue_snapshot_item", "audit_event", "outbox_event").forEachIndexed { index, table ->
            val outboxBefore = count("outbox_event", "aggregate_type", "RELEASE_ISSUE_SNAPSHOT")
            installFailureTrigger(table)
            val key = "rollback-$index-${releaseId.takeLast(8)}"
            assertThatThrownBy { useCase.create(command(key)) }
            assertThat(count("release_issue_snapshot", "release_id", releaseId)).isZero()
            assertThat(count("release_issue_snapshot_item", "project_id", projectId)).isZero()
            assertThat(count("audit_event", "project_id", projectId)).isZero()
            assertThat(count("outbox_event", "aggregate_type", "RELEASE_ISSUE_SNAPSHOT")).isEqualTo(outboxBefore)
            assertThat(count("idempotency_record", "idempotency_key", key)).isZero()
            jdbc.sql("DROP TRIGGER reject_snapshot_tx_test ON $table").update()
        }
    }

    @Test
    fun `ordinary database failure returns safe 500 and rolls back idempotency`() {
        installFailureTrigger("release_issue_snapshot")
        val key = "database-failure-${releaseId.takeLast(8)}"

        val response = mockMvc.post("/api/v1/releases/{releaseId}/issue-snapshots", releaseId) {
            with(
                jwt().jwt { it.issuer(issuer).subject(principalId).claim("principal_type", "USER") }
                    .authorities(SimpleGrantedAuthority("SCOPE_issue:snapshot")),
            )
            header("Idempotency-Key", key)
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"sourceId":"$sourceId"}"""
        }.andExpect {
            status { isInternalServerError() }
            jsonPath("$.code") { value("INTERNAL_ERROR") }
        }.andReturn().response.contentAsString

        assertThat(response).doesNotContain("injected transaction failure", "Synthetic", "fixture:", "stack")
        assertThat(count("release_issue_snapshot", "release_id", releaseId)).isZero()
        assertThat(count("audit_event", "project_id", projectId)).isZero()
        assertThat(count("idempotency_record", "idempotency_key", key)).isZero()
    }

    @Test
    fun `mutable source type does not alter revision local fact authority`() {
        jdbc.sql("UPDATE issue_source SET source_type='JIRA' WHERE id=:sourceId")
            .param("sourceId", sourceId).update()

        val result = useCase.create(command("source-local-${releaseId.takeLast(8)}"))

        assertThat(result.selectedCount).isEqualTo(2)
    }

    @Test
    fun `different idempotency keys concurrently converge on one logical snapshot`() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf("concurrent-a", "concurrent-b").map { prefix ->
                pool.submit<CreateIssueSnapshotResult> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) { "Concurrent snapshot start timed out" }
                    useCase.create(command("$prefix-${releaseId.takeLast(8)}"))
                }
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertThat(results.map { it.snapshotId }.distinct()).hasSize(1)
            assertThat(results.map { it.snapshotVersion }).containsOnly(1)
            assertThat(count("release_issue_snapshot", "release_id", releaseId)).isOne()
        } finally {
            start.countDown()
            pool.shutdownNow()
            check(pool.awaitTermination(10, TimeUnit.SECONDS)) { "Concurrent snapshot executor did not stop" }
        }
    }

    @Test
    fun `tampered fact digest and historical missing inputs fail closed with zero writes`() {
        listOf("tampered-digest", "missing-inputs").forEachIndexed { index, scenario ->
            bypassNormalizedIssueImmutability(tamperedDigest = index == 0)
            val key = "$scenario-${releaseId.takeLast(8)}"
            val outboxBefore = count("outbox_event", "aggregate_type", "RELEASE_ISSUE_SNAPSHOT")
            if (index == 0) {
                val response = mockMvc.post("/api/v1/releases/{releaseId}/issue-snapshots", releaseId) {
                    with(
                        jwt().jwt { it.issuer(issuer).subject(principalId).claim("principal_type", "USER") }
                            .authorities(SimpleGrantedAuthority("SCOPE_issue:snapshot")),
                    )
                    header("Idempotency-Key", key)
                    contentType = org.springframework.http.MediaType.APPLICATION_JSON
                    content = """{"sourceId":"$sourceId"}"""
                }.andExpect {
                    status { isUnprocessableEntity() }
                    jsonPath("$.code") { value("ISSUE_SNAPSHOT_INVALID") }
                    jsonPath("$.violations[0].code") { value("SNAPSHOT_INTEGRITY_FAILED") }
                }.andReturn().response.contentAsString
                assertThat(response).doesNotContain("Synthetic", "fixture:", "tampered")
            } else {
                assertThatThrownBy { useCase.create(command(key)) }
                    .isInstanceOfSatisfying(IssueSnapshotInvalid::class.java) { failure ->
                        assertThat(failure.violationCodes).containsExactly("SNAPSHOT_INTEGRITY_FAILED")
                    }
            }
            assertThat(count("release_issue_snapshot", "release_id", releaseId)).isZero()
            assertThat(count("release_issue_snapshot_item", "project_id", projectId)).isZero()
            assertThat(count("audit_event", "project_id", projectId)).isZero()
            assertThat(count("outbox_event", "aggregate_type", "RELEASE_ISSUE_SNAPSHOT")).isEqualTo(outboxBefore)
            assertThat(count("idempotency_record", "idempotency_key", key)).isZero()
            if (index == 0) seedAuthority()
        }
    }

    private fun command(key: String) = CreateIssueSnapshotCommand(
        Principal(issuer, principalId, false), releaseId, sourceId, key, hash("$releaseId\u0000$sourceId"), "request-tx",
    )

    private fun seedObservation(ordinal: Int, key: String) {
        val issueId = "issue_${key}_${runId.takeLast(8)}"
        val observedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val factDigest = com.ricezhou.vsrqg.issue.adapter.IssueFactCanonicalizer.canonicalize(
            com.ricezhou.vsrqg.issue.domain.NormalizedIssue(
                "FIXTURE", key, key, IssueSeverity.HIGH, IssueStatus.OPEN, "high", "open", "v1",
                "fixture:$key", observedAt, "mapping-v1",
            ),
        ).factDigest
        jdbc.sql(
            """INSERT INTO normalized_issue(id, project_id, source_id, source_issue_id, title,
                 severity, status, raw_status_token, canonical_source_token, source_version, source_reference, observed_at,
                 raw_severity_token, mapping_warnings, mapping_version, tombstone,
                 fact_digest, fact_digest_version, created_at)
               VALUES (:id, :projectId, :sourceId, :key, :key, 'HIGH', 'OPEN', 'open', 'FIXTURE', 'v1',
                 :reference, :observedAt, 'high', '', 'mapping-v1', false,
                 :digest, 'normalized-issue-facts/v1', now())""",
        ).param("id", issueId).param("projectId", projectId).param("sourceId", sourceId).param("key", key)
            .param("reference", "fixture:$key").param("observedAt", observedAt.atOffset(ZoneOffset.UTC))
            .param("digest", factDigest).update()
        jdbc.sql(
            """INSERT INTO issue_sync_run_item(sync_run_id, ordinal, project_id, source_id, issue_id,
                 source_issue_id, observed_at, created_at)
               VALUES (:runId, :ordinal, :projectId, :sourceId, :issueId, :key, now(), now())""",
        ).param("runId", runId).param("ordinal", ordinal).param("projectId", projectId)
            .param("sourceId", sourceId).param("issueId", issueId).param("key", key).update()
    }

    private fun installFailureTrigger(table: String) {
        jdbc.sql(
            """CREATE OR REPLACE FUNCTION reject_snapshot_tx_test() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
               BEGIN RAISE EXCEPTION 'injected transaction failure'; END; ${'$'}${'$'}""",
        ).update()
        jdbc.sql(
            "CREATE TRIGGER reject_snapshot_tx_test BEFORE INSERT ON $table FOR EACH ROW EXECUTE FUNCTION reject_snapshot_tx_test()",
        ).update()
    }

    private fun bypassNormalizedIssueImmutability(tamperedDigest: Boolean) {
        dataSource.connection.use { connection ->
            try {
                connection.createStatement().use { it.execute("SET session_replication_role = replica") }
                val sql = if (tamperedDigest) {
                    "UPDATE normalized_issue SET fact_digest=? WHERE source_id=? AND source_issue_id='A'"
                } else {
                    "UPDATE normalized_issue SET fact_digest_version=NULL, canonical_source_token=NULL, raw_severity_token=NULL, mapping_warnings=NULL " +
                        "WHERE source_id=? AND source_issue_id='A'"
                }
                connection.prepareStatement(sql).use { statement ->
                    var index = 1
                    if (tamperedDigest) statement.setString(index++, hash("tampered-$releaseId"))
                    statement.setString(index, sourceId)
                    assertThat(statement.executeUpdate()).isOne()
                }
            } finally {
                connection.createStatement().use { it.execute("SET session_replication_role = origin") }
            }
        }
    }

    private fun count(table: String, column: String, value: String): Int = jdbc.sql(
        "SELECT count(*) FROM $table WHERE $column=:value",
    ).param("value", value).query(Int::class.java).single()

    private fun hash(value: String): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
