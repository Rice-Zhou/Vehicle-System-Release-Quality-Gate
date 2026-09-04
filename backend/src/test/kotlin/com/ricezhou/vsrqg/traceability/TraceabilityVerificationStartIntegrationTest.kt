package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.ricezhou.vsrqg.traceability.application.StartTraceabilityVerification
import com.ricezhou.vsrqg.traceability.application.StartTraceabilityVerificationCommand
import com.ricezhou.vsrqg.traceability.application.TraceabilityInputRejected
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.vsrqg.test",
        "spring.security.oauth2.resourceserver.jwt.audiences[0]=vsrqg-api",
        "vsrqg.traceability.verification.enabled=true",
    ],
)
class TraceabilityVerificationStartIntegrationTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var useCase: StartTraceabilityVerification

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private lateinit var fixture: TraceabilityVerificationStartFixture

    @BeforeEach
    fun seedAuthority() {
        fixture = TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate).seed()
    }

    @Test
    fun `start pins current authoritative revisions and queues one bounded job`() {
        val key = "start-${fixture.suffix}"

        val response = start(key).andExpect {
            status { isAccepted() }
            header { exists("Location") }
            jsonPath("$.status") { value("QUEUED") }
            jsonPath("$.releaseId") { value(fixture.releaseId) }
            jsonPath("$.issueSnapshotId") { value(fixture.snapshotId) }
            jsonPath("$.inputDigest") { value(org.hamcrest.Matchers.matchesPattern("^sha256:[0-9a-f]{64}$")) }
        }.andReturn().response
        val accepted = objectMapper.readTree(response.contentAsByteArray)
        val runId = accepted.path("verificationRunId").textValue()

        assertThat(response.getHeader("Location")).isEqualTo(accepted.path("statusUrl").textValue())
        assertThat(readLedger(runId)).containsExactlyElementsOf(fixture.pathEdges.map { it.ledgerIdentity })
        assertThat(readRun(runId)).containsExactly(
            fixture.projectId,
            fixture.releaseId,
            fixture.snapshotId,
            fixture.manifestRevisionId,
            "QUEUED",
            "m2.5-traceability-policy/v1",
            "m2.5-path-validator/v1",
            "4",
        )
        assertThat(objectMapper.readTree(readJobPayload(runId)).fieldNames().asSequence().toList())
            .containsExactly("verificationRunId")
        assertThat(objectMapper.readTree(readJobPayload(runId)).path("verificationRunId").textValue()).isEqualTo(runId)
        assertThat(count("audit_event", "aggregate_id", runId)).isOne()
        assertThat(count("outbox_event", "aggregate_id", runId)).isOne()
        assertThat(count("background_job", "idempotency_key", runId)).isOne()
        assertThat(countIdempotency(key)).isOne()
        assertSafeMetadata(runId)
    }

    @Test
    fun `latest immutable snapshot is pinned and edges outside its issue scope are excluded`() {
        val latest = TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendLatestSnapshot(fixture)
        TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendIssueCommitForIssue(fixture, latest.issueId, "latest")
        TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendIssueCommitOutsideSnapshot(fixture)

        val accepted = useCase.start(command("latest-${fixture.suffix}"))
        val ledger = readLedger(accepted.verificationRunId)

        assertThat(accepted.issueSnapshotId).isEqualTo(latest.snapshotId)
        assertThat(ledger.map { it.edgeId }).contains("edge_latest_${fixture.suffix}")
        assertThat(ledger.map { it.edgeId })
            .doesNotContain(fixture.issueCommit.edgeId, "edge_outside_${fixture.suffix}")
    }

    @Test
    fun `unlocked release and missing source snapshot fail before creating artifacts`() {
        val unlocked = TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate).seed(locked = false)
        val missingSourceId = TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendSourceWithoutSnapshot(fixture)

        assertThatThrownBy { useCase.start(command("unlocked-${unlocked.suffix}", unlocked)) }
            .isInstanceOf(ResourceConflict::class.java)
            .extracting("code")
            .isEqualTo("RELEASE_MANIFEST_NOT_LOCKED")
        assertThatThrownBy {
            useCase.start(command("missing-${fixture.suffix}", fixture, missingSourceId))
        }.isInstanceOf(ResourceNotFound::class.java)
            .extracting("code")
            .isEqualTo("RESOURCE_NOT_FOUND")

        assertThat(countRuns(unlocked.projectId)).isZero()
        assertThat(countRuns(fixture.projectId)).isZero()
    }

    @Test
    fun `untrusted current edge fails closed instead of becoming a gap`() {
        TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendIssueCommitRevision(fixture, "INVALID")

        assertThatThrownBy { useCase.start(command("invalid-${fixture.suffix}")) }
            .isInstanceOf(TraceabilityInputRejected::class.java)
            .extracting("code")
            .isEqualTo("TRACEABILITY_INPUT_NOT_VALID")

        assertThat(countRuns(fixture.projectId)).isZero()
    }

    @Test
    fun `exact 2000 edge boundary is accepted without truncation`() {
        TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendValidIssueCommitEdges(fixture, 1_996)

        val accepted = useCase.start(command("two-thousand-${fixture.suffix}"))

        assertThat(readLedger(accepted.verificationRunId)).hasSize(2_000)
        assertThat(readInputEdgeCount(accepted.verificationRunId)).isEqualTo(2_000)
    }

    @Test
    fun `first edge beyond 2000 is rejected and never truncated into a run`() {
        TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendValidIssueCommitEdges(fixture, 1_997)

        assertThatThrownBy { useCase.start(command("too-many-${fixture.suffix}")) }
            .isInstanceOf(TraceabilityInputRejected::class.java)
            .extracting("code")
            .isEqualTo("TRACEABILITY_INPUT_LIMIT_EXCEEDED")

        assertThat(countRuns(fixture.projectId)).isZero()
    }

    @Test
    fun `exact 20 issue boundary is accepted and the first excess issue is rejected`() {
        val exact = TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate).seed(issueCount = 20)
        val excess = TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate).seed(issueCount = 21)

        val accepted = useCase.start(command("twenty-${exact.suffix}", exact))
        assertThat(accepted.issueSnapshotId).isEqualTo(exact.snapshotId)

        assertThatThrownBy { useCase.start(command("twenty-one-${excess.suffix}", excess)) }
            .isInstanceOf(TraceabilityInputRejected::class.java)
            .extracting("code")
            .isEqualTo("TRACEABILITY_ISSUE_LIMIT_EXCEEDED")
        assertThat(countRuns(excess.projectId)).isZero()
    }

    @Test
    fun `later edge revision cannot enter an already queued run`() {
        val accepted = useCase.start(command("isolation-${fixture.suffix}"))
        val before = readLedger(accepted.verificationRunId)

        TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendIssueCommitRevision(fixture, "VALID")

        assertThat(readLedger(accepted.verificationRunId)).containsExactlyElementsOf(before)
        assertThat(readLedger(accepted.verificationRunId).single { it.edgeId == fixture.issueCommit.edgeId })
            .isEqualTo(fixture.issueCommit.ledgerIdentity)
    }

    @Test
    fun `idempotency replays the original run conflicts on another body and preserves independent keys`() {
        val otherSource = TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendSourceWithoutSnapshot(fixture)
        val key = "idem-${fixture.suffix}"

        val first = useCase.start(command(key))
        val replay = useCase.start(command(key))
        assertThat(replay).isEqualTo(first)
        assertThat(countRuns(fixture.projectId)).isOne()

        assertThatThrownBy { useCase.start(command(key, fixture, otherSource)) }
            .isInstanceOf(com.ricezhou.vsrqg.shared.application.IdempotencyConflict::class.java)
        assertThat(countRuns(fixture.projectId)).isOne()

        val independent = useCase.start(command("independent-${fixture.suffix}"))
        assertThat(independent.verificationRunId).isNotEqualTo(first.verificationRunId)
        assertThat(independent.inputDigest).isEqualTo(first.inputDigest)
        assertThat(countRuns(fixture.projectId)).isEqualTo(2)
        assertThat(countJobs(fixture.projectId)).isEqualTo(2)
    }

    @Test
    fun `post requires the dedicated scope and hides an invisible release`() {
        start("missing-scope-${fixture.suffix}", hasScope = false).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("ACCESS_DENIED") }
        }
        start("hidden-${fixture.suffix}", subject = "unassigned-${fixture.suffix}").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RESOURCE_NOT_FOUND") }
        }
        assertThat(countRuns(fixture.projectId)).isZero()
    }

    private fun command(
        key: String,
        target: TraceabilityVerificationStartFixture = fixture,
        sourceId: String = target.sourceId,
    ) = StartTraceabilityVerificationCommand(
        principal = Principal(ISSUER, target.userSubject, true),
        releaseId = target.releaseId,
        issueSourceId = sourceId,
        idempotencyKey = key,
        requestDigest = TraceabilityVerificationStartFixtureSeeder.requestDigest(target.releaseId, sourceId),
        requestId = "req_$key",
    )

    private fun start(
        key: String,
        subject: String = fixture.userSubject,
        hasScope: Boolean = true,
    ) = mockMvc.post("/api/v1/releases/{releaseId}/traceability:verify", fixture.releaseId) {
        val token = jwt().jwt { it.issuer(ISSUER).subject(subject).claim("principal_type", "USER") }
        if (hasScope) token.authorities(SimpleGrantedAuthority("SCOPE_traceability:verify")) else token.authorities()
        with(token)
        header("Idempotency-Key", key)
        contentType = MediaType.APPLICATION_JSON
        content = """{"sourceId":"${fixture.sourceId}"}"""
    }

    private fun readLedger(runId: String): List<LedgerIdentity> = jdbc.sql(
        """
        SELECT edge_type, source_edge_id, source_edge_revision_id, source_edge_revision, fact_digest
        FROM traceability_verification_run_edge_input
        WHERE verification_run_id = :runId
        ORDER BY ordinal
        """.trimIndent(),
    ).param("runId", runId).query { rs, _ ->
        LedgerIdentity(
            rs.getString("edge_type"),
            rs.getString("source_edge_id"),
            rs.getString("source_edge_revision_id"),
            rs.getInt("source_edge_revision"),
            rs.getString("fact_digest"),
        )
    }.list()

    private fun readRun(runId: String): List<String> = jdbc.sql(
        """
        SELECT project_id, release_id, issue_snapshot_id, manifest_revision_id, status,
               policy_version, validator_version, input_edge_count
        FROM traceability_verification_run WHERE id = :runId
        """.trimIndent(),
    ).param("runId", runId).query { rs, _ ->
        listOf(
            rs.getString("project_id"),
            rs.getString("release_id"),
            rs.getString("issue_snapshot_id"),
            rs.getString("manifest_revision_id"),
            rs.getString("status"),
            rs.getString("policy_version"),
            rs.getString("validator_version"),
            rs.getInt("input_edge_count").toString(),
        )
    }.single()

    private fun readJobPayload(runId: String): String = jdbc.sql(
        "SELECT payload::text FROM background_job WHERE job_type = 'TRACEABILITY_VERIFY' AND idempotency_key = :runId",
    ).param("runId", runId).query(String::class.java).single()

    private fun readInputEdgeCount(runId: String): Int = jdbc.sql(
        "SELECT input_edge_count FROM traceability_verification_run WHERE id = :runId",
    ).param("runId", runId).query(Int::class.java).single()

    private fun assertSafeMetadata(runId: String) {
        val text = jdbc.sql(
            """
            SELECT coalesce(a.after_state::text, '') || coalesce(o.payload::text, '') || coalesce(j.payload::text, '')
            FROM audit_event a
            JOIN outbox_event o ON o.aggregate_id = a.aggregate_id
            JOIN background_job j ON j.idempotency_key = a.aggregate_id
            WHERE a.aggregate_id = :runId
            """.trimIndent(),
        ).param("runId", runId).query(String::class.java).single()
        assertThat(text)
            .contains(runId, fixture.releaseId, fixture.snapshotId, fixture.manifestRevisionId)
            .doesNotContain(fixture.userSubject, "Issue 1", "credential", "proofReference", "repository")
    }

    private fun count(table: String, column: String, value: String): Int {
        require(table in setOf("audit_event", "outbox_event", "background_job"))
        require(column in setOf("aggregate_id", "idempotency_key"))
        return jdbc.sql("SELECT count(*) FROM $table WHERE $column = :value")
            .param("value", value).query(Int::class.java).single()
    }

    private fun countIdempotency(key: String): Int = jdbc.sql(
        "SELECT count(*) FROM idempotency_record WHERE principal_id = :principalId AND idempotency_key = :key",
    ).param("principalId", fixture.userPrincipalId).param("key", key).query(Int::class.java).single()

    private fun countRuns(projectId: String): Int = jdbc.sql(
        "SELECT count(*) FROM traceability_verification_run WHERE project_id = :projectId AND issue_snapshot_id IS NOT NULL",
    ).param("projectId", projectId).query(Int::class.java).single()

    private fun countJobs(projectId: String): Int = jdbc.sql(
        "SELECT count(*) FROM background_job WHERE project_id = :projectId AND job_type = 'TRACEABILITY_VERIFY'",
    ).param("projectId", projectId).query(Int::class.java).single()
}

internal data class TraceabilityVerificationStartFixture(
    val suffix: String,
    val projectId: String,
    val releaseId: String,
    val sourceId: String,
    val snapshotId: String,
    val issueId: String,
    val manifestRevisionId: String,
    val userPrincipalId: String,
    val userSubject: String,
    val issueCommit: StartEdge,
    val pathEdges: List<StartEdge>,
)

internal data class StartEdge(
    val edgeType: String,
    val edgeId: String,
    val revisionId: String,
    val revision: Int,
    val factDigest: String,
) {
    val ledgerIdentity = LedgerIdentity(edgeType, edgeId, revisionId, revision, factDigest)
}

internal data class LedgerIdentity(
    val edgeType: String,
    val edgeId: String,
    val revisionId: String,
    val revision: Int,
    val factDigest: String,
)

internal data class LatestSnapshotFixture(val snapshotId: String, val issueId: String)

internal class TraceabilityVerificationStartFixtureSeeder(
    private val jdbc: JdbcClient,
    private val transactionTemplate: TransactionTemplate,
) {
    fun seed(locked: Boolean = true, issueCount: Int = 1): TraceabilityVerificationStartFixture {
        require(issueCount >= 1)
        val base = BuildProvenanceFixtureSeeder(jdbc, transactionTemplate).seed()
        val suffix = base.suffix
        val fixture = TraceabilityVerificationStartFixture(
            suffix = suffix,
            projectId = base.projectId,
            releaseId = "rel_ing_$suffix",
            sourceId = "src_ing_$suffix",
            snapshotId = base.snapshotId,
            issueId = base.issueId,
            manifestRevisionId = "mft_ing_$suffix",
            userPrincipalId = "usr_ing_$suffix",
            userSubject = base.userSubject,
            issueCommit = edge("ISSUE_COMMIT", "edge_issue_$suffix", "rev_issue_$suffix", 1),
            pathEdges = emptyList(),
        )
        var effectiveSnapshotId = fixture.snapshotId
        transactionTemplate.executeWithoutResult {
            if (issueCount > 1) effectiveSnapshotId = appendSnapshotIssues(fixture, issueCount - 1)
            if (locked) lockManifest(fixture)
            insertPathAuthority(fixture)
        }
        val artifactRelease = if (locked) listOf(readArtifactRelease(fixture)) else emptyList()
        return fixture.copy(
            snapshotId = effectiveSnapshotId,
            pathEdges = listOf(fixture.issueCommit) + typedPathEdges(fixture) + artifactRelease,
        )
    }

    fun appendLatestSnapshot(fixture: TraceabilityVerificationStartFixture): LatestSnapshotFixture {
        val issueId = "iss_latest_${fixture.suffix}"
        val sourceIssueId = "ISSUE-LATEST-${fixture.suffix}"
        val runId = "syn_latest_${fixture.suffix}"
        val snapshotId = "ris_latest_${fixture.suffix}"
        transactionTemplate.executeWithoutResult {
            insertNormalizedIssue(fixture, issueId, sourceIssueId)
            insertSyncRun(fixture, runId, 1)
            insertSnapshot(fixture, snapshotId, runId, 2, listOf(issueId to sourceIssueId))
        }
        return LatestSnapshotFixture(snapshotId, issueId)
    }

    fun appendSourceWithoutSnapshot(fixture: TraceabilityVerificationStartFixture): String {
        val sourceId = "src_other_${fixture.suffix}"
        jdbc.sql(
            """
            INSERT INTO issue_source(
              id, project_id, source_key, source_type, adapter_version, mapping_version, created_at, updated_at
            ) VALUES (:id, :projectId, :key, 'FIXTURE', 'fixture/v1', 'mapping/v1', now(), now())
            """.trimIndent(),
        ).param("id", sourceId).param("projectId", fixture.projectId)
            .param("key", "other-${fixture.suffix}").update()
        return sourceId
    }

    fun appendIssueCommitOutsideSnapshot(fixture: TraceabilityVerificationStartFixture) {
        val issueId = "iss_outside_${fixture.suffix}"
        insertNormalizedIssue(fixture, issueId, "ISSUE-OUTSIDE-${fixture.suffix}")
        appendIssueCommitForIssue(fixture, issueId, "outside")
    }

    fun appendIssueCommitForIssue(
        fixture: TraceabilityVerificationStartFixture,
        issueId: String,
        label: String,
    ) {
        val commitId = "commit_${label}_${fixture.suffix}"
        val edge = edge("ISSUE_COMMIT", "edge_${label}_${fixture.suffix}", "rev_${label}_${fixture.suffix}", 1)
        jdbc.sql(
            "INSERT INTO source_commit(id, project_id, repository, commit_id, created_at) " +
                "VALUES (:id, :projectId, :repository, :revision, now())",
        ).param("id", commitId).param("projectId", fixture.projectId)
            .param("repository", "fixture/$label").param("revision", "revision-$label-${fixture.suffix}").update()
        insertEdgeIdentity(fixture.projectId, edge, issueId, commitId)
        insertIssueCommitRevision(fixture.projectId, issueId, commitId, edge, "VALID")
    }

    fun appendIssueCommitRevision(fixture: TraceabilityVerificationStartFixture, status: String) {
        val previous = fixture.issueCommit
        val next = edge(
            "ISSUE_COMMIT",
            previous.edgeId,
            "rev_issue2_${fixture.suffix}",
            2,
        )
        jdbc.sql(
            """
            INSERT INTO issue_commit_edge_revision(
              id, project_id, edge_id, revision, issue_id, commit_id, source_type,
              source_reference, confidence, verification_status, validator_version,
              previous_revision_id, previous_revision, content_digest, created_at
            ) VALUES (
              :id, :projectId, :edgeId, 2, :issueId, :commitId, 'FIXTURE',
              :sourceReference, 'HIGH', :status, 'fixture-validator/v1',
              :previousId, 1, :digest, now()
            )
            """.trimIndent(),
        ).param("id", next.revisionId).param("projectId", fixture.projectId)
            .param("edgeId", next.edgeId).param("issueId", fixture.issueId)
            .param("commitId", "commit_${fixture.suffix}")
            .param("sourceReference", "fixture:${next.edgeId}:2").param("status", status)
            .param("previousId", previous.revisionId).param("digest", next.factDigest).update()
    }

    fun appendValidIssueCommitEdges(fixture: TraceabilityVerificationStartFixture, count: Int) {
        if (count == 0) return
        jdbc.sql(
            """
            INSERT INTO source_commit(id, project_id, repository, commit_id, created_at)
            SELECT 'c_bulk_${fixture.suffix}_' || lpad(n::text, 4, '0'), :projectId,
                   'fixture/bulk', 'bulk-' || n::text || '-${fixture.suffix}', now()
            FROM generate_series(1, :count) n
            """.trimIndent(),
        ).param("projectId", fixture.projectId).param("count", count).update()
        jdbc.sql(
            """
            INSERT INTO traceability_edge_identity(
              edge_id, project_id, edge_type, from_entity_id, to_entity_id, created_at
            )
            SELECT 'e_bulk_${fixture.suffix}_' || lpad(n::text, 4, '0'), :projectId,
                   'ISSUE_COMMIT', :issueId,
                   'c_bulk_${fixture.suffix}_' || lpad(n::text, 4, '0'), now()
            FROM generate_series(1, :count) n
            """.trimIndent(),
        ).param("projectId", fixture.projectId).param("issueId", fixture.issueId)
            .param("count", count).update()
        jdbc.sql(
            """
            INSERT INTO issue_commit_edge_revision(
              id, project_id, edge_id, revision, issue_id, commit_id, source_type,
              source_reference, confidence, verification_status, validator_version,
              content_digest, created_at
            )
            SELECT 'r_bulk_${fixture.suffix}_' || lpad(n::text, 4, '0'), :projectId,
                   'e_bulk_${fixture.suffix}_' || lpad(n::text, 4, '0'), 1, :issueId,
                   'c_bulk_${fixture.suffix}_' || lpad(n::text, 4, '0'), 'FIXTURE',
                   'fixture:bulk:' || n::text, 'HIGH', 'VALID', 'fixture-validator/v1',
                   ('sha256:' || encode(sha256(convert_to('bulk-${fixture.suffix}-' || n::text, 'UTF8')), 'hex'))::varchar(71),
                   now()
            FROM generate_series(1, :count) n
            """.trimIndent(),
        ).param("projectId", fixture.projectId).param("issueId", fixture.issueId)
            .param("count", count).update()
    }

    private fun appendSnapshotIssues(fixture: TraceabilityVerificationStartFixture, additional: Int): String {
        val issues = mutableListOf(fixture.issueId to "ISSUE-1")
        repeat(additional) { index ->
            val ordinal = index + 1
            val issueId = "iss_${fixture.suffix}_${ordinal.toString().padStart(2, '0')}"
            val sourceIssueId = "ISSUE-${ordinal + 1}-${fixture.suffix}"
            insertNormalizedIssue(fixture, issueId, sourceIssueId)
            issues += issueId to sourceIssueId
        }
        val runId = "syn_limit_${fixture.suffix}"
        val snapshotId = "ris_limit_${fixture.suffix}"
        insertSyncRun(fixture, runId, issues.size)
        insertSnapshot(fixture, snapshotId, runId, 2, issues)
        return snapshotId
    }

    private fun lockManifest(fixture: TraceabilityVerificationStartFixture) {
        jdbc.sql("UPDATE manifest_revision SET state = 'LOCKED' WHERE id = :manifestId")
            .param("manifestId", fixture.manifestRevisionId).update()
        jdbc.sql("UPDATE release_record SET locked_manifest_id = :manifestId WHERE id = :releaseId")
            .param("manifestId", fixture.manifestRevisionId).param("releaseId", fixture.releaseId).update()
    }

    private fun insertPathAuthority(fixture: TraceabilityVerificationStartFixture) {
        val commitId = "commit_${fixture.suffix}"
        val buildId = "build_${fixture.suffix}"
        val typed = typedPathEdges(fixture)
        jdbc.sql(
            "INSERT INTO source_commit(id, project_id, repository, commit_id, created_at) " +
                "VALUES (:id, :projectId, 'fixture/repository', :revision, now())",
        ).param("id", commitId).param("projectId", fixture.projectId)
            .param("revision", "revision-${fixture.suffix}").update()
        jdbc.sql(
            """
            INSERT INTO build_record(
              id, project_id, provider, build_id, pipeline, source_revision,
              repository, build_attempt, created_at
            ) VALUES (
              :id, :projectId, 'fixture', :providerBuildId, 'pipeline', :sourceRevision,
              'fixture/repository', 1, now()
            )
            """.trimIndent(),
        ).param("id", buildId).param("projectId", fixture.projectId)
            .param("providerBuildId", "provider-${fixture.suffix}")
            .param("sourceRevision", "revision-${fixture.suffix}").update()
        insertEdgeIdentity(fixture.projectId, fixture.issueCommit, fixture.issueId, commitId)
        insertIssueCommitRevision(fixture.projectId, fixture.issueId, commitId, fixture.issueCommit, "VALID")
        insertTypedRevision(fixture, typed[0], commitId, buildId)
        insertTypedRevision(fixture, typed[1], buildId, "art_ing_${fixture.suffix}")
    }

    private fun typedPathEdges(fixture: TraceabilityVerificationStartFixture): List<StartEdge> = listOf(
        edge("COMMIT_BUILD", "edge_build_${fixture.suffix}", "rev_build_${fixture.suffix}", 1),
        edge("BUILD_ARTIFACT", "edge_artifact_${fixture.suffix}", "rev_artifact_${fixture.suffix}", 1),
    )

    private fun insertTypedRevision(
        fixture: TraceabilityVerificationStartFixture,
        edge: StartEdge,
        fromId: String,
        toId: String,
    ) {
        val table = if (edge.edgeType == "COMMIT_BUILD") "commit_build_edge_revision" else "build_artifact_edge_revision"
        val fromColumn = if (edge.edgeType == "COMMIT_BUILD") "commit_id" else "build_id"
        val toColumn = if (edge.edgeType == "COMMIT_BUILD") "build_id" else "artifact_id"
        insertEdgeIdentity(fixture.projectId, edge, fromId, toId)
        jdbc.sql(
            """
            INSERT INTO $table(
              id, project_id, edge_id, revision, $fromColumn, $toColumn, source_type,
              source_reference, confidence, verification_status, validator_version,
              content_digest, created_at
            ) VALUES (
              :id, :projectId, :edgeId, 1, :fromId, :toId, 'FIXTURE',
              :sourceReference, 'HIGH', 'VALID', 'fixture-validator/v1', :digest, now()
            )
            """.trimIndent(),
        ).param("id", edge.revisionId).param("projectId", fixture.projectId)
            .param("edgeId", edge.edgeId).param("fromId", fromId).param("toId", toId)
            .param("sourceReference", "fixture:${edge.edgeId}").param("digest", edge.factDigest).update()
    }

    private fun insertEdgeIdentity(projectId: String, edge: StartEdge, fromId: String, toId: String) {
        jdbc.sql(
            """
            INSERT INTO traceability_edge_identity(
              edge_id, project_id, edge_type, from_entity_id, to_entity_id, created_at
            ) VALUES (:edgeId, :projectId, :edgeType, :fromId, :toId, now())
            """.trimIndent(),
        ).param("edgeId", edge.edgeId).param("projectId", projectId)
            .param("edgeType", edge.edgeType).param("fromId", fromId).param("toId", toId).update()
    }

    private fun insertIssueCommitRevision(
        projectId: String,
        issueId: String,
        commitId: String,
        edge: StartEdge,
        status: String,
    ) {
        jdbc.sql(
            """
            INSERT INTO issue_commit_edge_revision(
              id, project_id, edge_id, revision, issue_id, commit_id, source_type,
              source_reference, confidence, verification_status, validator_version,
              content_digest, created_at
            ) VALUES (
              :id, :projectId, :edgeId, :revision, :issueId, :commitId, 'FIXTURE',
              :sourceReference, 'HIGH', :status, 'fixture-validator/v1', :digest, now()
            )
            """.trimIndent(),
        ).param("id", edge.revisionId).param("projectId", projectId).param("edgeId", edge.edgeId)
            .param("revision", edge.revision).param("issueId", issueId).param("commitId", commitId)
            .param("sourceReference", "fixture:${edge.edgeId}").param("status", status)
            .param("digest", edge.factDigest).update()
    }

    private fun readArtifactRelease(fixture: TraceabilityVerificationStartFixture): StartEdge = jdbc.sql(
        """
        SELECT source_edge_id, manifest_revision_id, source_edge_revision, fact_digest
        FROM artifact_release_edge_v
        WHERE project_id = :projectId AND release_id = :releaseId
          AND artifact_id = :artifactId
        """.trimIndent(),
    ).param("projectId", fixture.projectId).param("releaseId", fixture.releaseId)
        .param("artifactId", "art_ing_${fixture.suffix}").query { rs, _ ->
            StartEdge(
                "ARTIFACT_RELEASE",
                rs.getString("source_edge_id"),
                rs.getString("manifest_revision_id"),
                rs.getInt("source_edge_revision"),
                rs.getString("fact_digest"),
            )
        }.single()

    private fun insertNormalizedIssue(
        fixture: TraceabilityVerificationStartFixture,
        issueId: String,
        sourceIssueId: String,
    ) {
        jdbc.sql(
            """
            INSERT INTO normalized_issue(
              id, project_id, source_id, source_issue_id, title, severity, status,
              raw_status_token, canonical_source_token, raw_severity_token, mapping_warnings,
              source_version, source_reference, observed_at, mapping_version,
              fact_digest, fact_digest_version, created_at
            ) VALUES (
              :id, :projectId, :sourceId, :sourceIssueId, 'Issue', 'MAJOR', 'OPEN',
              'open', 'FIXTURE', 'major', '', :sourceVersion, 'fixture', now(), 'mapping/v1',
              :digest, 'normalized-issue-facts/v1', now()
            )
            """.trimIndent(),
        ).param("id", issueId).param("projectId", fixture.projectId).param("sourceId", fixture.sourceId)
            .param("sourceIssueId", sourceIssueId).param("sourceVersion", "v-$sourceIssueId")
            .param("digest", prefixedDigest("issue-$issueId")).update()
    }

    private fun insertSyncRun(fixture: TraceabilityVerificationStartFixture, runId: String, issueCount: Int) {
        jdbc.sql(
            """
            INSERT INTO issue_sync_run(
              id, project_id, source_id, sync_run_id, status, source_watermark,
              adapter_version, mapping_version, result_set_mode, filter_reference,
              issue_count, completed_at, created_at
            ) VALUES (
              :id, :projectId, :sourceId, :id, 'SUCCEEDED', :watermark,
              'fixture/v1', 'mapping/v1', 'FULL', 'all', :issueCount, now(), now()
            )
            """.trimIndent(),
        ).param("id", runId).param("projectId", fixture.projectId).param("sourceId", fixture.sourceId)
            .param("watermark", "watermark-$runId").param("issueCount", issueCount).update()
    }

    private fun insertSnapshot(
        fixture: TraceabilityVerificationStartFixture,
        snapshotId: String,
        runId: String,
        version: Int,
        issues: List<Pair<String, String>>,
    ) {
        jdbc.sql(
            """
            INSERT INTO release_issue_snapshot(
              id, project_id, release_id, sync_run_id, snapshot_version, filter_reference,
              source_id, source_watermark, adapter_version, mapping_version,
              canonicalization_version, age_policy_version, observed_count, tombstone_count,
              selected_count, content_digest, created_at
            ) VALUES (
              :id, :projectId, :releaseId, :runId, :version, 'all', :sourceId, :watermark,
              'fixture/v1', 'mapping/v1', 'release-issue-snapshot-jcs/v1', 'issue-snapshot-age/v1',
              :count, 0, :count, :digest, now()
            )
            """.trimIndent(),
        ).param("id", snapshotId).param("projectId", fixture.projectId).param("releaseId", fixture.releaseId)
            .param("runId", runId).param("version", version).param("sourceId", fixture.sourceId)
            .param("watermark", "watermark-$runId").param("count", issues.size)
            .param("digest", prefixedDigest("snapshot-$snapshotId")).update()
        issues.forEachIndexed { ordinal, (issueId, sourceIssueId) ->
            jdbc.sql(
                """
                INSERT INTO release_issue_snapshot_item(
                  snapshot_id, ordinal, project_id, issue_id, source_issue_id, title,
                  severity, status, source_version, source_reference, observed_at,
                  mapping_version, fact_digest, created_at
                ) VALUES (
                  :snapshotId, :ordinal, :projectId, :issueId, :sourceIssueId, 'Issue',
                  'MAJOR', 'OPEN', :sourceVersion, 'fixture', now(), 'mapping/v1', :digest, now()
                )
                """.trimIndent(),
            ).param("snapshotId", snapshotId).param("ordinal", ordinal).param("projectId", fixture.projectId)
                .param("issueId", issueId).param("sourceIssueId", sourceIssueId)
                .param("sourceVersion", "v-$sourceIssueId")
                .param("digest", prefixedDigest("snapshot-item-$snapshotId-$issueId")).update()
        }
    }

    private fun edge(type: String, edgeId: String, revisionId: String, revision: Int): StartEdge =
        StartEdge(type, edgeId, revisionId, revision, prefixedDigest("$type-$edgeId-$revisionId-$revision"))

    companion object {
        fun requestDigest(releaseId: String, sourceId: String): String =
            prefixedDigest("$releaseId\u0000$sourceId")
    }
}
