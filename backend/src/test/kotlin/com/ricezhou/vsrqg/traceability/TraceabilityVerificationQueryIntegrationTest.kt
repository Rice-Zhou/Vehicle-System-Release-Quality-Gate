package com.ricezhou.vsrqg.traceability

import com.ricezhou.vsrqg.access.adapter.JwtPrincipalMapper
import com.ricezhou.vsrqg.access.adapter.SecurityConfig
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.access.application.ProjectAuthorization
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import com.ricezhou.vsrqg.traceability.adapter.BuildProvenanceIngestionProperties
import com.ricezhou.vsrqg.traceability.adapter.TraceabilityVerificationController
import com.ricezhou.vsrqg.traceability.adapter.TraceabilityVerificationProblemAdvice
import com.ricezhou.vsrqg.traceability.application.GetTraceabilityVerification
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotHeaderResult
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotIssueResult
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotResult
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRunResult
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRunStatus
import com.ricezhou.vsrqg.traceability.application.StartTraceabilityVerification
import com.ricezhou.vsrqg.traceability.application.PinnedTraceabilityVerificationExecution
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotGapView
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotHeaderView
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotIssueView
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotMaterialization
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotPathEdgeView
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationAuthority
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationJobClaim
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRepository
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRunRecord
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRunView
import com.ricezhou.vsrqg.traceability.domain.Confidence
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdge
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant

internal class TraceabilityVerificationQueryIntegrationTest : TraceabilityVerificationWorkerPostgresTest() {
    @Autowired
    private lateinit var query: GetTraceabilityVerification

    @Test
    fun `run polling returns the persisted queued run without a snapshot locator`() {
        val accepted = start("query-queued-${fixture.suffix}")

        val result = query.getRun(principal(), accepted.verificationRunId)

        assertThat(result.verificationRunId).isEqualTo(accepted.verificationRunId)
        assertThat(result.releaseId).isEqualTo(fixture.releaseId)
        assertThat(result.status.name).isEqualTo("QUEUED")
        assertThat(result.inputDigest).isEqualTo(accepted.inputDigest)
        assertThat(result.resultSnapshotId).isNull()
        assertThat(result.diagnosticCode).isNull()
        assertThat(result.startedAt).isNull()
        assertThat(result.completedAt).isNull()
    }

    @Test
    fun `completed snapshot is assembled only from persisted issue path and gap ordinals`() {
        fixture = TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate).seed(issueCount = 2)
        val accepted = start("query-complete-${fixture.suffix}")
        assertThat(worker.runNext()).isTrue()

        val result = query.getSnapshot(principal(), fixture.releaseId, null)

        assertThat(result.header.snapshotId).isEqualTo(runState(accepted.verificationRunId)[1])
        assertThat(result.header.releaseId).isEqualTo(fixture.releaseId)
        assertThat(result.header.version).isOne()
        assertThat(result.header.issueSnapshotId).isEqualTo(fixture.snapshotId)
        assertThat(result.header.manifestRevisionId).isEqualTo(fixture.manifestRevisionId)
        assertThat(result.header.manifestDigest).startsWith("sha256:")
        assertThat(result.header.inputDigest).isEqualTo(accepted.inputDigest)
        assertThat(result.issues.map { it.sourceIssueId }).containsExactly(
            "ISSUE-1",
            "ISSUE-2-${fixture.suffix}",
        )
        assertThat(result.issues.first().path.map { it.edgeType.name }).containsExactly(
            "ISSUE_COMMIT",
            "COMMIT_BUILD",
            "BUILD_ARTIFACT",
            "ARTIFACT_RELEASE",
        )
        assertThat(result.issues.first().gaps.map { it.diagnosticCode.name })
            .containsExactly("TEST_RESULT_EVIDENCE_MISSING")
        assertThat(result.issues.last().path).isEmpty()
        assertThat(result.issues.last().gaps.map { it.diagnosticCode.name })
            .containsExactly("ISSUE_COMMIT_MISSING")
    }

    @Test
    fun `latest selects the newest succeeded snapshot while snapshot id replays exact history`() {
        val first = start("query-history-1-${fixture.suffix}")
        assertThat(worker.runNext()).isTrue()
        val firstSnapshotId = requireNotNull(runState(first.verificationRunId)[1])
        val firstResponse = query.getSnapshot(principal(), fixture.releaseId, firstSnapshotId)

        val latestIssue = TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendLatestSnapshot(fixture)
        TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendIssueCommitForIssue(fixture, latestIssue.issueId, "query-history")
        val second = start("query-history-2-${fixture.suffix}")
        assertThat(worker.runNext()).isTrue()
        val secondSnapshotId = requireNotNull(runState(second.verificationRunId)[1])

        assertThat(query.getSnapshot(principal(), fixture.releaseId, null).header.snapshotId)
            .isEqualTo(secondSnapshotId)
        assertThat(query.getSnapshot(principal(), fixture.releaseId, firstSnapshotId))
            .usingRecursiveComparison()
            .isEqualTo(firstResponse)
    }

    @Test
    fun `queued running and failed runs never replace the latest succeeded snapshot`() {
        val succeeded = start("query-state-success-${fixture.suffix}")
        assertThat(worker.runNext()).isTrue()
        val succeededSnapshotId = requireNotNull(runState(succeeded.verificationRunId)[1])

        start("query-state-queued-${fixture.suffix}")
        val running = start("query-state-running-${fixture.suffix}")
        val firstClaim = requireNotNull(repository.claimNext(Instant.now().plusSeconds(30)))
        val secondClaim = if (firstClaim.verificationRunId == running.verificationRunId) {
            firstClaim
        } else {
            requireNotNull(repository.claimNext(Instant.now().plusSeconds(30)))
        }
        repository.failInvalidInput(secondClaim, "TRACEABILITY_INPUT_NOT_VALID", Instant.now().plusSeconds(31))

        assertThat(query.getRun(principal(), running.verificationRunId).status.name).isEqualTo("FAILED")
        assertThat(query.getSnapshot(principal(), fixture.releaseId, null).header.snapshotId)
            .isEqualTo(succeededSnapshotId)
    }

    @Test
    fun `unknown and unauthorized read targets share the enumeration safe resource not found`() {
        val accepted = start("query-hidden-${fixture.suffix}")

        listOf(
            { query.getRun(principal(), "trv_missing_${fixture.suffix}") },
            { query.getRun(Principal(ISSUER, "unassigned-${fixture.suffix}", true), accepted.verificationRunId) },
            { query.getSnapshot(principal(), "release_missing_${fixture.suffix}", null) },
            { query.getSnapshot(Principal(ISSUER, "unassigned-${fixture.suffix}", true), fixture.releaseId, null) },
        ).forEach { request ->
            assertThatThrownBy { request(); Unit }
                .isInstanceOf(ResourceNotFound::class.java)
                .extracting("code")
                .isEqualTo("RESOURCE_NOT_FOUND")
        }
    }

    private fun principal() = Principal(ISSUER, fixture.userSubject, true)
}

internal class TraceabilityVerificationReadQueryShapeTest {
    @Test
    fun `twenty issues still use one set based read per persisted snapshot relation`() {
        val repository = ReadCountingRepository(issueCount = 20)
        val query = GetTraceabilityVerification(
            repository,
            ProjectAuthorizer { _, _, _ -> ProjectAuthorization("principal-read") },
        )

        val result = query.getSnapshot(
            Principal("https://idp.vsrqg.test", "reader", true),
            "release-read",
            null,
        )

        assertThat(result.issues).hasSize(20)
        assertThat(repository.readCounts).containsExactlyEntriesOf(
            mapOf(
                "release" to 1,
                "header" to 1,
                "issues" to 1,
                "paths" to 1,
                "gaps" to 1,
            ),
        )
    }
}

private class ReadCountingRepository(issueCount: Int) : TraceabilityVerificationRepository {
    val readCounts = linkedMapOf(
        "release" to 0,
        "header" to 0,
        "issues" to 0,
        "paths" to 0,
        "gaps" to 0,
    )
    private val issues = List(issueCount) { ordinal ->
        TraceabilitySnapshotIssueView(
            ordinal,
            "issue-$ordinal",
            "ISSUE-$ordinal",
            fixed = false,
            included = false,
            verified = false,
            Confidence.UNKNOWN,
        )
    }

    override fun findReleaseProjectId(releaseId: String): String? {
        increment("release")
        return "project-read"
    }

    override fun findSnapshotHeader(releaseId: String, snapshotId: String?): TraceabilitySnapshotHeaderView {
        increment("header")
        return TraceabilitySnapshotHeaderView(
            "snapshot-read",
            "project-read",
            "release-read",
            1,
            "issue-snapshot-read",
            "manifest-read",
            "sha256:${"1".repeat(64)}",
            "m2.5-traceability-policy/v1",
            "m2.5-path-validator/v1",
            "sha256:${"2".repeat(64)}",
            "sha256:${"3".repeat(64)}",
            Instant.parse("2026-09-04T00:00:00Z"),
        )
    }

    override fun findSnapshotIssues(snapshotId: String): List<TraceabilitySnapshotIssueView> {
        increment("issues")
        return issues
    }

    override fun findSnapshotPathEdges(snapshotId: String): List<TraceabilitySnapshotPathEdgeView> {
        increment("paths")
        return emptyList()
    }

    override fun findSnapshotGaps(snapshotId: String): List<TraceabilitySnapshotGapView> {
        increment("gaps")
        return emptyList()
    }

    private fun increment(key: String) {
        readCounts[key] = readCounts.getValue(key) + 1
    }

    override fun findVerificationRun(verificationRunId: String): TraceabilityVerificationRunView? = error("unused")
    override fun findProjectId(releaseId: String, issueSourceId: String): String? = error("unused")
    override fun lockAndLoadAuthority(
        releaseId: String,
        issueSourceId: String,
        issueFetchLimit: Int,
        edgeFetchLimit: Int,
    ): TraceabilityVerificationAuthority? = error("unused")
    override fun insertRun(run: TraceabilityVerificationRunRecord) = error("unused")
    override fun insertInputLedger(
        runId: String,
        projectId: String,
        edges: List<PinnedTraceabilityEdge>,
        createdAt: Instant,
    ) = error("unused")
    override fun insertJob(
        jobId: String,
        projectId: String,
        runId: String,
        payload: JsonNode,
        createdAt: Instant,
    ) = error("unused")
    override fun claimNext(now: Instant): TraceabilityVerificationJobClaim? = error("unused")
    override fun loadPinnedExecution(verificationRunId: String): PinnedTraceabilityVerificationExecution =
        error("unused")
    override fun materializeResult(
        claim: TraceabilityVerificationJobClaim,
        execution: PinnedTraceabilityVerificationExecution,
        materialization: TraceabilitySnapshotMaterialization,
    ): String = error("unused")
    override fun failInvalidInput(
        claim: TraceabilityVerificationJobClaim,
        diagnosticCode: String,
        completedAt: Instant,
    ) = error("unused")
    override fun recordInfrastructureFailure(claim: TraceabilityVerificationJobClaim, failedAt: Instant) =
        error("unused")
}

@WebMvcTest(controllers = [TraceabilityVerificationController::class])
@Import(
    SecurityConfig::class,
    JwtPrincipalMapper::class,
    ProblemWriter::class,
    TraceabilityVerificationProblemAdvice::class,
)
@TestPropertySource(
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.vsrqg.test",
        "spring.security.oauth2.resourceserver.jwt.audiences[0]=vsrqg-api",
    ],
)
internal class TraceabilityVerificationQueryHttpTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var query: GetTraceabilityVerification

    @MockitoBean
    private lateinit var start: StartTraceabilityVerification

    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @MockitoBean
    private lateinit var idGenerator: IdGenerator

    @MockitoBean
    private lateinit var ingestionProperties: BuildProvenanceIngestionProperties

    @BeforeEach
    fun requestIds() {
        doReturn("req_traceability_query").`when`(idGenerator).nextId("req_")
    }

    @Test
    fun `run and snapshot get mappings serialize only the strict public contract`() {
        doReturn(runResult()).`when`(query).getRun(queryAny(), anyString())
        doReturn(snapshotResult()).`when`(query)
            .getSnapshot(queryAny(), queryAny(), queryAny())

        mockMvc.get("/api/v1/traceability-verification-runs/trv-http") {
            with(readJwt())
        }.andExpect {
            status { isOk() }
            content { contentType(MediaType.APPLICATION_JSON) }
            jsonPath("$.verificationRunId") { value("trv-http") }
            jsonPath("$.status") { value("SUCCEEDED") }
            jsonPath("$.resultSnapshotId") { value("trs-http") }
            jsonPath("$.rawReason") { doesNotExist() }
        }
        mockMvc.get("/api/v1/releases/rel-http/traceability") {
            with(readJwt())
            param("snapshotId", "trs-http")
        }.andExpect {
            status { isOk() }
            jsonPath("$.snapshot.snapshotId") { value("trs-http") }
            jsonPath("$.issues[0].verified") { value(false) }
            jsonPath("$.issues[0].path") { isEmpty() }
            jsonPath("$.issues[0].gaps") { isEmpty() }
            jsonPath("$.sourceTitle") { doesNotExist() }
            jsonPath("$.proofUrl") { doesNotExist() }
        }
    }

    @Test
    fun `traceability reads redact persistence outage as fixed 503`() {
        doThrow(DataAccessResourceFailureException("jdbc:postgresql://token-host/secret-database"))
            .`when`(query).getRun(queryAny(), anyString())

        val response = mockMvc.get("/api/v1/traceability-verification-runs/trv-http") {
            with(readJwt())
        }.andExpect {
            status { isServiceUnavailable() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("PERSISTENCE_UNAVAILABLE") }
            jsonPath("$.detail") { value("The traceability result could not be read; retry the request") }
        }.andReturn().response.contentAsString

        assertThat(response).doesNotContain("token-host", "secret-database", "jdbc:postgresql")
    }

    private fun readJwt() = jwt().jwt {
        it.issuer("https://idp.vsrqg.test").subject("traceability-reader").claim("principal_type", "USER")
    }.authorities(SimpleGrantedAuthority("SCOPE_traceability:read"))

    private fun runResult() = TraceabilityVerificationRunResult(
        verificationRunId = "trv-http",
        releaseId = "rel-http",
        status = TraceabilityVerificationRunStatus.SUCCEEDED,
        policyVersion = "m2.5-traceability-policy/v1",
        validatorVersion = "m2.5-path-validator/v1",
        inputDigest = "sha256:${"1".repeat(64)}",
        resultSnapshotId = "trs-http",
        diagnosticCode = null,
        createdAt = Instant.parse("2026-09-04T00:00:00Z"),
        startedAt = Instant.parse("2026-09-04T00:00:01Z"),
        completedAt = Instant.parse("2026-09-04T00:00:02Z"),
    )

    private fun snapshotResult() = TraceabilitySnapshotResult(
        header = TraceabilitySnapshotHeaderResult(
            snapshotId = "trs-http",
            releaseId = "rel-http",
            version = 1,
            issueSnapshotId = "ris-http",
            manifestRevisionId = "mft-http",
            manifestDigest = "sha256:${"2".repeat(64)}",
            policyVersion = "m2.5-traceability-policy/v1",
            validatorVersion = "m2.5-path-validator/v1",
            inputDigest = "sha256:${"1".repeat(64)}",
            contentDigest = "sha256:${"3".repeat(64)}",
            createdAt = Instant.parse("2026-09-04T00:00:02Z"),
        ),
        issues = listOf(
            TraceabilitySnapshotIssueResult(
                issueId = "iss-http",
                sourceIssueId = "ISSUE-HTTP",
                fixed = false,
                included = false,
                verified = false,
                path = emptyList(),
                gaps = emptyList(),
                confidence = com.ricezhou.vsrqg.traceability.domain.Confidence.UNKNOWN,
            ),
        ),
    )
}

@Suppress("UNCHECKED_CAST")
private fun <T> queryAny(): T {
    any<T>()
    return null as T
}
