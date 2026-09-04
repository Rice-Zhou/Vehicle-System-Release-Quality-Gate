package com.ricezhou.vsrqg.traceability

import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRepository
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired

internal class TraceabilityVerificationWorkerFailureTest : TraceabilityVerificationWorkerPostgresTest() {
    @Autowired
    private lateinit var repository: TraceabilityVerificationRepository

    @AfterEach
    fun removeWorkerFailureInjection() {
        WorkerWriteBoundary.entries.forEach { boundary ->
            jdbc.sql("DROP TRIGGER IF EXISTS reject_traceability_worker_tx_test ON ${boundary.table}").update()
        }
        jdbc.sql("DROP FUNCTION IF EXISTS reject_traceability_worker_tx_test() ").update()
    }

    @ParameterizedTest
    @EnumSource(WorkerWriteBoundary::class)
    internal fun `every result write boundary failure leaves no partial snapshot`(boundary: WorkerWriteBoundary) {
        val accepted = start("worker-rollback-${boundary.name.lowercase()}-${fixture.suffix}")
        installFailure(boundary)

        assertThat(worker.runNext()).isTrue()

        assertThat(snapshotCount(accepted.verificationRunId)).isZero()
        assertThat(runGapCount(accepted.verificationRunId)).isZero()
        assertThat(runState(accepted.verificationRunId)).containsExactly("RUNNING", null, null)
        assertThat(jobState(accepted.verificationRunId).take(2)).containsExactly("QUEUED", "1")
        assertThat(successGovernanceText(accepted.verificationRunId)).isEmpty()
        assertThat(jobState(accepted.verificationRunId).joinToString()).doesNotContain(INJECTED_SECRET)
    }

    @Test
    fun `bounded retry dead letters poison infrastructure without persisting exception text`() {
        val accepted = start("worker-dead-letter-${fixture.suffix}")
        installFailure(WorkerWriteBoundary.SNAPSHOT_HEADER)

        repeat(3) { attempt ->
            if (attempt > 0) {
                jdbc.sql(
                    "UPDATE background_job SET available_at = now() WHERE idempotency_key = :runId",
                ).param("runId", accepted.verificationRunId).update()
            }
            assertThat(worker.runNext()).isTrue()
        }

        assertThat(runState(accepted.verificationRunId)).containsExactly(
            "FAILED",
            null,
            "TRACEABILITY_VERIFICATION_RETRY_EXHAUSTED",
        )
        val job = jobState(accepted.verificationRunId)
        assertThat(job.take(2)).containsExactly("DEAD_LETTER", "3")
        assertThat(job[2]).contains("TRACEABILITY_VERIFICATION_RETRY_EXHAUSTED")
            .doesNotContain(INJECTED_SECRET)
        assertThat(snapshotCount(accepted.verificationRunId)).isZero()
        assertThat(runGapCount(accepted.verificationRunId)).isZero()
    }

    @Test
    fun `running crash is reclaimed only after the lease and increments attempt`() {
        val accepted = start("worker-reclaim-${fixture.suffix}")
        val now = Instant.now().plusSeconds(60)

        val first = repository.claimNext(now)
        val beforeLease = repository.claimNext(now.plusSeconds(299))
        val reclaimed = repository.claimNext(now.plusSeconds(300))

        assertThat(first!!.verificationRunId).isEqualTo(accepted.verificationRunId)
        assertThat(first.attemptCount).isOne()
        assertThat(beforeLease).isNull()
        assertThat(reclaimed!!.verificationRunId).isEqualTo(accepted.verificationRunId)
        assertThat(reclaimed.attemptCount).isEqualTo(2)
        assertThat(runState(accepted.verificationRunId)[0]).isEqualTo("RUNNING")
    }

    private fun installFailure(boundary: WorkerWriteBoundary) {
        jdbc.sql(
            """
            CREATE OR REPLACE FUNCTION reject_traceability_worker_tx_test() RETURNS trigger
            LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION '$INJECTED_SECRET'; END;
            ${'$'}${'$'}
            """.trimIndent(),
        ).update()
        jdbc.sql(
            "CREATE TRIGGER reject_traceability_worker_tx_test BEFORE ${boundary.operation} " +
                "ON ${boundary.table} FOR EACH ROW${boundary.whenClause} " +
                "EXECUTE FUNCTION reject_traceability_worker_tx_test()",
        ).update()
    }

    private fun snapshotCount(runId: String): Int = jdbc.sql(
        "SELECT count(*) FROM traceability_snapshot WHERE verification_run_id = :runId",
    ).param("runId", runId).query(Int::class.java).single()

    private fun runGapCount(runId: String): Int = jdbc.sql(
        "SELECT count(*) FROM traceability_gap WHERE verification_run_id = :runId",
    ).param("runId", runId).query(Int::class.java).single()

    private fun successGovernanceText(runId: String): String = jdbc.sql(
        """
        SELECT coalesce(string_agg(text_value, ''), '') FROM (
          SELECT coalesce(after_state::text, '') AS text_value FROM audit_event
          WHERE aggregate_id = :runId AND action = 'TRACEABILITY_VERIFICATION_SUCCEEDED'
          UNION ALL
          SELECT payload::text FROM outbox_event
          WHERE aggregate_id = :runId AND event_type = 'traceability.verification.succeeded'
        ) values_to_scan
        """.trimIndent(),
    ).param("runId", runId).query(String::class.java).single()

    internal enum class WorkerWriteBoundary(
        val table: String,
        val operation: String = "INSERT",
        val whenClause: String = "",
    ) {
        SNAPSHOT_HEADER("traceability_snapshot"),
        ISSUE_RESULT("traceability_snapshot_issue_result"),
        SNAPSHOT_EDGE("traceability_snapshot_edge"),
        PATH_EDGE("traceability_snapshot_issue_path_edge"),
        GAP("traceability_snapshot_gap"),
        AUDIT("audit_event", whenClause = " WHEN (NEW.action = 'TRACEABILITY_VERIFICATION_SUCCEEDED')"),
        OUTBOX("outbox_event", whenClause = " WHEN (NEW.event_type = 'traceability.verification.succeeded')"),
        RUN_TERMINAL(
            "traceability_verification_run",
            operation = "UPDATE",
            whenClause = " WHEN (NEW.status = 'SUCCEEDED')",
        ),
        JOB_TERMINAL(
            "background_job",
            operation = "UPDATE",
            whenClause = " WHEN (NEW.status = 'SUCCEEDED')",
        ),
    }

    private companion object {
        const val INJECTED_SECRET = "injected-secret-jdbc-stack-value"
    }
}
