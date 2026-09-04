package com.ricezhou.vsrqg.traceability

import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import com.ricezhou.vsrqg.traceability.adapter.TraceabilityVerificationJobWorker
import com.ricezhou.vsrqg.traceability.application.StartTraceabilityVerification
import com.ricezhou.vsrqg.traceability.application.StartTraceabilityVerificationCommand
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationAccepted
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate

@TestPropertySource(properties = ["vsrqg.traceability.verification.enabled=true"])
internal abstract class TraceabilityVerificationWorkerPostgresTest : PostgresIntegrationTest() {
    @Autowired
    protected lateinit var jdbc: JdbcClient

    @Autowired
    protected lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    protected lateinit var startUseCase: StartTraceabilityVerification

    @Autowired
    protected lateinit var worker: TraceabilityVerificationJobWorker

    protected lateinit var fixture: TraceabilityVerificationStartFixture

    @BeforeEach
    fun seedWorkerAuthority() {
        jdbc.sql(
            """
            UPDATE background_job
            SET status = 'DEAD_LETTER', completed_at = now(), updated_at = now(),
                result_summary = '{"diagnosticCode":"TEST_ISOLATION"}'::jsonb
            WHERE job_type = 'TRACEABILITY_VERIFY' AND status IN ('QUEUED', 'RUNNING')
            """.trimIndent(),
        ).update()
        fixture = TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate).seed()
    }

    @AfterEach
    fun retireUnfinishedWorkerArtifacts() {
        jdbc.sql(
            """
            UPDATE traceability_verification_run
            SET status = 'FAILED', diagnostic_code = 'TEST_CLEANUP', completed_at = now()
            WHERE project_id = :projectId AND status = 'RUNNING'
            """.trimIndent(),
        ).param("projectId", fixture.projectId).update()
        jdbc.sql(
            """
            UPDATE background_job
            SET status = 'DEAD_LETTER', completed_at = now(), updated_at = now(),
                result_summary = '{"diagnosticCode":"TEST_CLEANUP"}'::jsonb
            WHERE project_id = :projectId AND job_type = 'TRACEABILITY_VERIFY'
              AND status IN ('QUEUED', 'RUNNING')
            """.trimIndent(),
        ).param("projectId", fixture.projectId).update()
    }

    protected fun start(key: String): TraceabilityVerificationAccepted = startUseCase.start(
        StartTraceabilityVerificationCommand(
            principal = Principal(ISSUER, fixture.userSubject, true),
            releaseId = fixture.releaseId,
            issueSourceId = fixture.sourceId,
            idempotencyKey = key,
            requestDigest = TraceabilityVerificationStartFixtureSeeder.requestDigest(
                fixture.releaseId,
                fixture.sourceId,
            ),
            requestId = "req_$key",
        ),
    )

    protected fun runState(runId: String): List<String?> = jdbc.sql(
        """
        SELECT status, result_snapshot_id, diagnostic_code
        FROM traceability_verification_run WHERE id = :runId
        """.trimIndent(),
    ).param("runId", runId).query { rs, _ ->
        listOf(rs.getString("status"), rs.getString("result_snapshot_id"), rs.getString("diagnostic_code"))
    }.single()

    protected fun jobState(runId: String): List<String?> = jdbc.sql(
        """
        SELECT status, attempt_count::text, result_summary::text
        FROM background_job
        WHERE job_type = 'TRACEABILITY_VERIFY' AND idempotency_key = :runId
        """.trimIndent(),
    ).param("runId", runId).query { rs, _ ->
        listOf(rs.getString("status"), rs.getString("attempt_count"), rs.getString("result_summary"))
    }.single()
}

internal class TraceabilityVerificationWorkerIntegrationTest : TraceabilityVerificationWorkerPostgresTest() {
    @Test
    fun `worker materializes one complete immutable snapshot from the pinned ledger`() {
        val accepted = start("worker-success-${fixture.suffix}")

        assertThat(worker.runNext()).isTrue()

        val run = runState(accepted.verificationRunId)
        assertThat(run[0]).isEqualTo("SUCCEEDED")
        assertThat(run[1]).isNotNull()
        assertThat(run[2]).isNull()
        assertThat(jobState(accepted.verificationRunId).take(2)).containsExactly("SUCCEEDED", "1")
        val snapshotId = run[1]!!
        assertThat(count("traceability_snapshot", "id", snapshotId)).isOne()
        assertThat(count("traceability_snapshot_edge", "snapshot_id", snapshotId)).isEqualTo(4)
        assertThat(count("traceability_snapshot_issue_path_edge", "snapshot_id", snapshotId)).isEqualTo(4)
        assertThat(count("traceability_snapshot_gap", "snapshot_id", snapshotId)).isOne()
        assertThat(count("traceability_gap", "verification_run_id", accepted.verificationRunId)).isOne()
        val issue = jdbc.sql(
            """
            SELECT fixed, included, verified, confidence
            FROM traceability_snapshot_issue_result WHERE snapshot_id = :snapshotId
            """.trimIndent(),
        ).param("snapshotId", snapshotId).query { rs, _ ->
            listOf(
                rs.getBoolean("fixed").toString(),
                rs.getBoolean("included").toString(),
                rs.getBoolean("verified").toString(),
                rs.getString("confidence"),
            )
        }.single()
        assertThat(issue).containsExactly("true", "true", "false", "HIGH")
        assertThat(
            jdbc.sql("SELECT diagnostic_code FROM traceability_snapshot_gap WHERE snapshot_id = :snapshotId")
                .param("snapshotId", snapshotId).query(String::class.java).single(),
        ).isEqualTo("TEST_RESULT_EVIDENCE_MISSING")
        assertThat(countSuccessGovernance(accepted.verificationRunId, "audit_event")).isOne()
        assertThat(countSuccessGovernance(accepted.verificationRunId, "outbox_event")).isOne()
        assertThat(worker.runNext()).isFalse()
    }

    @Test
    fun `same input different runs reuse one content identical snapshot`() {
        val first = start("worker-reuse-1-${fixture.suffix}")
        val second = start("worker-reuse-2-${fixture.suffix}")

        assertThat(worker.runNext()).isTrue()
        assertThat(worker.runNext()).isTrue()

        assertThat(runState(second.verificationRunId)[1]).isEqualTo(runState(first.verificationRunId)[1])
        assertThat(
            jdbc.sql("SELECT count(*) FROM traceability_verification_run WHERE project_id = :projectId")
                .param("projectId", fixture.projectId).query(Int::class.java).single(),
        ).isEqualTo(2)
        assertThat(
            jdbc.sql("SELECT count(*) FROM traceability_snapshot WHERE project_id = :projectId")
                .param("projectId", fixture.projectId).query(Int::class.java).single(),
        ).isOne()
    }

    @Test
    fun `worker ignores a later edge revision and materializes the exact pinned revision`() {
        val accepted = start("worker-pinned-${fixture.suffix}")
        TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendIssueCommitRevision(fixture, "INVALID")

        assertThat(worker.runNext()).isTrue()

        val snapshotId = runState(accepted.verificationRunId)[1]!!
        val revision = jdbc.sql(
            """
            SELECT source_edge_revision, source_edge_revision_id
            FROM traceability_snapshot_edge
            WHERE snapshot_id = :snapshotId AND edge_type = 'ISSUE_COMMIT'
            """.trimIndent(),
        ).param("snapshotId", snapshotId).query { rs, _ ->
            rs.getInt("source_edge_revision") to rs.getString("source_edge_revision_id")
        }.single()
        assertThat(revision).isEqualTo(1 to fixture.issueCommit.revisionId)
    }

    private fun count(table: String, column: String, value: String): Int {
        require(table in RESULT_TABLES)
        require(column in setOf("id", "snapshot_id", "verification_run_id"))
        return jdbc.sql("SELECT count(*) FROM $table WHERE $column = :value")
            .param("value", value).query(Int::class.java).single()
    }

    private fun countSuccessGovernance(runId: String, table: String): Int {
        require(table in setOf("audit_event", "outbox_event"))
        val discriminator = if (table == "audit_event") "action" else "event_type"
        val value = if (table == "audit_event") {
            "TRACEABILITY_VERIFICATION_SUCCEEDED"
        } else {
            "traceability.verification.succeeded"
        }
        return jdbc.sql("SELECT count(*) FROM $table WHERE aggregate_id = :runId AND $discriminator = :value")
            .param("runId", runId).param("value", value).query(Int::class.java).single()
    }

    private companion object {
        val RESULT_TABLES = setOf(
            "traceability_snapshot",
            "traceability_snapshot_edge",
            "traceability_snapshot_issue_result",
            "traceability_snapshot_issue_path_edge",
            "traceability_snapshot_gap",
            "traceability_gap",
        )
    }
}
