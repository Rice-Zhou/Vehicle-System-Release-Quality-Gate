package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.traceability.adapter.JcsTraceabilityCanonicalizer
import com.ricezhou.vsrqg.traceability.application.PinnedTraceabilityVerificationExecution
import com.ricezhou.vsrqg.traceability.application.RunTraceabilityVerification
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotMaterialization
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotVersionConflict
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationAuthority
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationAccepted
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationJobClaim
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRepository
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRunRecord
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRunView
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotGapView
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotHeaderView
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotIssueView
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotPathEdgeView
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerifier
import com.ricezhou.vsrqg.traceability.domain.LockedManifest
import com.ricezhou.vsrqg.traceability.domain.PinnedIssueSnapshot
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdge
import com.ricezhou.vsrqg.traceability.domain.TraceabilityIssue
import com.ricezhou.vsrqg.traceability.domain.VerificationInput
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataIntegrityViolationException

internal class TraceabilityVerificationWorkerFailureTest : TraceabilityVerificationWorkerPostgresTest() {
    @AfterEach
    fun removeWorkerFailureInjection() {
        WorkerWriteBoundary.entries.forEach { boundary ->
            jdbc.sql("DROP TRIGGER IF EXISTS reject_traceability_worker_tx_test ON ${boundary.table}").update()
        }
        jdbc.sql("DROP TRIGGER IF EXISTS mutate_traceability_snapshot_insert_test ON traceability_snapshot").update()
        jdbc.sql("DROP FUNCTION IF EXISTS reject_traceability_worker_tx_test() ").update()
        jdbc.sql("DROP FUNCTION IF EXISTS mutate_traceability_snapshot_insert_test() ").update()
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

    @Test
    fun `target release version unique violation is translated to the bounded conflict signal`() {
        val first = start("worker-target-conflict-base-${fixture.suffix}")
        assertThat(worker.runNext()).isTrue()
        assertThat(runState(first.verificationRunId)[0]).isEqualTo("SUCCEEDED")
        val second = startDifferentInput("worker-target-conflict-${fixture.suffix}")
        val candidate = materializationCandidate(second.verificationRunId)
        installSnapshotInsertMutation(SnapshotInsertMutation.TARGET_VERSION_UNIQUE)

        assertThatThrownBy {
            repository.materializeResult(candidate.claim, candidate.execution, candidate.materialization)
        }.isExactlyInstanceOf(TraceabilitySnapshotVersionConflict::class.java)
    }

    @ParameterizedTest
    @EnumSource(value = SnapshotInsertMutation::class, names = ["OTHER_UNIQUE", "CHECK_VIOLATION"])
    fun `non target integrity failures retain their data access type and enter retry`(
        mutation: SnapshotInsertMutation,
    ) {
        val accepted = start("worker-other-integrity-${mutation.name.lowercase()}-${fixture.suffix}")
        installSnapshotInsertMutation(mutation)

        assertThat(worker.runNext()).isTrue()
        assertThat(runState(accepted.verificationRunId)).containsExactly("RUNNING", null, null)
        assertThat(jobState(accepted.verificationRunId).take(2)).containsExactly("QUEUED", "1")

        jdbc.sql("UPDATE background_job SET available_at = now() WHERE idempotency_key = :runId")
            .param("runId", accepted.verificationRunId).update()
        val candidate = materializationCandidate(accepted.verificationRunId)
        assertThatThrownBy {
            repository.materializeResult(candidate.claim, candidate.execution, candidate.materialization)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .isNotInstanceOf(TraceabilitySnapshotVersionConflict::class.java)
    }

    @Test
    fun `corrupted fixed input digest fails closed with only the safe diagnostic`() {
        val accepted = start("worker-corrupt-digest-${fixture.suffix}")
        corruptRunInputDigest(accepted.verificationRunId)

        assertThat(worker.runNext()).isTrue()

        assertInvalidInputTerminal(accepted.verificationRunId)
    }

    @Test
    fun `corrupted fixed ledger fails closed with only the safe diagnostic`() {
        val accepted = start("worker-corrupt-ledger-${fixture.suffix}")
        corruptLedgerDigest(accepted.verificationRunId)

        assertThat(worker.runNext()).isTrue()

        assertInvalidInputTerminal(accepted.verificationRunId)
    }

    @Test
    fun `invalid input terminal write failure rolls back the run before safe retry`() {
        val accepted = start("worker-invalid-terminal-rollback-${fixture.suffix}")
        corruptRunInputDigest(accepted.verificationRunId)
        installJobDeadLetterFailure()

        assertThat(worker.runNext()).isTrue()

        assertThat(runState(accepted.verificationRunId)).containsExactly("RUNNING", null, null)
        assertThat(jobState(accepted.verificationRunId).take(2)).containsExactly("QUEUED", "1")
        assertThat(snapshotCount(accepted.verificationRunId)).isZero()
        assertThat(runGapCount(accepted.verificationRunId)).isZero()
        assertThat(successGovernanceText(accepted.verificationRunId)).isEmpty()
        assertThat(jobState(accepted.verificationRunId).joinToString()).doesNotContain(INJECTED_SECRET)
    }

    private fun startDifferentInput(key: String): TraceabilityVerificationAccepted {
        val latest = TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendLatestSnapshot(fixture)
        TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendIssueCommitForIssue(fixture, latest.issueId, "target-conflict")
        return start(key)
    }

    private fun materializationCandidate(runId: String): MaterializationCandidate {
        val claim = requireNotNull(repository.claimNext(Instant.now().plusSeconds(60)))
        check(claim.verificationRunId == runId) { "Claimed an unexpected verification run" }
        val execution = repository.loadPinnedExecution(runId)
        val computation = TraceabilityVerifier(canonicalizer).verify(execution.input)
        return MaterializationCandidate(
            claim,
            execution,
            TraceabilitySnapshotMaterialization(
                snapshotId = "trs_probe_${fixture.suffix}",
                runGapIds = computation.gaps.indices.map { ordinal -> "gap_probe_${ordinal}_${fixture.suffix}" },
                computation = computation,
                completedAt = Instant.now().plusSeconds(60),
            ),
        )
    }

    private fun installSnapshotInsertMutation(mutation: SnapshotInsertMutation) {
        jdbc.sql(
            """
            CREATE OR REPLACE FUNCTION mutate_traceability_snapshot_insert_test() RETURNS trigger
            LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
              ${mutation.statement}
            END;
            ${'$'}${'$'}
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            CREATE TRIGGER mutate_traceability_snapshot_insert_test
            BEFORE INSERT ON traceability_snapshot FOR EACH ROW
            EXECUTE FUNCTION mutate_traceability_snapshot_insert_test()
            """.trimIndent(),
        ).update()
    }

    private fun corruptRunInputDigest(runId: String) {
        jdbc.sql("ALTER TABLE traceability_verification_run DISABLE TRIGGER validate_traceability_verification_run")
            .update()
        try {
            jdbc.sql("UPDATE traceability_verification_run SET input_digest = :digest WHERE id = :runId")
                .param("digest", "sha256:${"9".repeat(64)}").param("runId", runId).update()
        } finally {
            jdbc.sql("ALTER TABLE traceability_verification_run ENABLE TRIGGER validate_traceability_verification_run")
                .update()
        }
    }

    private fun corruptLedgerDigest(runId: String) {
        jdbc.sql(
            "ALTER TABLE traceability_verification_run_edge_input " +
                "DISABLE TRIGGER immutable_verification_run_edge_input",
        ).update()
        try {
            jdbc.sql(
                "UPDATE traceability_verification_run_edge_input SET fact_digest = :digest " +
                    "WHERE verification_run_id = :runId AND ordinal = 0",
            ).param("digest", "sha256:${"8".repeat(64)}").param("runId", runId).update()
        } finally {
            jdbc.sql(
                "ALTER TABLE traceability_verification_run_edge_input " +
                    "ENABLE TRIGGER immutable_verification_run_edge_input",
            ).update()
        }
    }

    private fun installJobDeadLetterFailure() {
        jdbc.sql(
            """
            CREATE OR REPLACE FUNCTION reject_traceability_worker_tx_test() RETURNS trigger
            LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION '$INJECTED_SECRET'; END;
            ${'$'}${'$'}
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            CREATE TRIGGER reject_traceability_worker_tx_test BEFORE UPDATE ON background_job
            FOR EACH ROW WHEN (NEW.status = 'DEAD_LETTER')
            EXECUTE FUNCTION reject_traceability_worker_tx_test()
            """.trimIndent(),
        ).update()
    }

    private fun assertInvalidInputTerminal(runId: String) {
        assertThat(runState(runId)).containsExactly("FAILED", null, "TRACEABILITY_INPUT_NOT_VALID")
        val job = jobState(runId)
        assertThat(job.take(2)).containsExactly("DEAD_LETTER", "1")
        assertThat(job[2]).contains("TRACEABILITY_INPUT_NOT_VALID")
            .doesNotContain("PINNED", "DIGEST", "LEDGER", "sha256:", INJECTED_SECRET)
        assertThat(snapshotCount(runId)).isZero()
        assertThat(runGapCount(runId)).isZero()
        assertThat(successGovernanceText(runId)).isEmpty()
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

    internal enum class SnapshotInsertMutation(val statement: String) {
        TARGET_VERSION_UNIQUE("NEW.version := 1; RETURN NEW;"),
        OTHER_UNIQUE(
            "RAISE EXCEPTION 'other unique' USING ERRCODE = '23505', " +
                "CONSTRAINT = 'uq_other_traceability_test';",
        ),
        CHECK_VIOLATION("NEW.version := 0; RETURN NEW;"),
    }

    private data class MaterializationCandidate(
        val claim: TraceabilityVerificationJobClaim,
        val execution: PinnedTraceabilityVerificationExecution,
        val materialization: TraceabilitySnapshotMaterialization,
    )
}

internal class RunTraceabilityVerificationRetryTest {
    private val canonicalizer = JcsTraceabilityCanonicalizer(ObjectMapper())
    private val execution = pinnedExecution(canonicalizer)
    private val claim = TraceabilityVerificationJobClaim("job-1", "run-1", "project-1", 1)

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun `version conflict succeeds only after retrying the complete materialization transaction`(conflicts: Int) {
        val repository = ConflictRepository(execution, conflicts)

        useCase(repository).run(claim)

        assertThat(repository.materializationAttempts).isEqualTo(conflicts + 1)
        assertThat(repository.snapshotIds).doesNotHaveDuplicates()
    }

    @Test
    fun `third consecutive version conflict escapes at the strict transaction retry bound`() {
        val repository = ConflictRepository(execution, conflictsBeforeSuccess = 3)

        assertThatThrownBy { useCase(repository).run(claim) }
            .isExactlyInstanceOf(TraceabilitySnapshotVersionConflict::class.java)
        assertThat(repository.materializationAttempts).isEqualTo(3)
    }

    private fun useCase(repository: TraceabilityVerificationRepository): RunTraceabilityVerification {
        var sequence = 0
        return RunTraceabilityVerification(
            repository,
            canonicalizer,
            IdGenerator { prefix -> "$prefix${++sequence}" },
            TimeProvider { FIXED_TIME },
        )
    }

    private class ConflictRepository(
        private val execution: PinnedTraceabilityVerificationExecution,
        private val conflictsBeforeSuccess: Int,
    ) : TraceabilityVerificationRepository {
        var materializationAttempts = 0
            private set
        val snapshotIds = mutableListOf<String>()

        override fun findVerificationRun(verificationRunId: String): TraceabilityVerificationRunView? =
            error("unused")

        override fun findReleaseProjectId(releaseId: String): String? = error("unused")

        override fun findSnapshotHeader(releaseId: String, snapshotId: String?): TraceabilitySnapshotHeaderView? =
            error("unused")

        override fun findSnapshotIssues(snapshotId: String): List<TraceabilitySnapshotIssueView> = error("unused")

        override fun findSnapshotPathEdges(snapshotId: String): List<TraceabilitySnapshotPathEdgeView> =
            error("unused")

        override fun findSnapshotGaps(snapshotId: String): List<TraceabilitySnapshotGapView> = error("unused")

        override fun loadPinnedExecution(verificationRunId: String): PinnedTraceabilityVerificationExecution = execution

        override fun materializeResult(
            claim: TraceabilityVerificationJobClaim,
            execution: PinnedTraceabilityVerificationExecution,
            materialization: TraceabilitySnapshotMaterialization,
        ): String {
            materializationAttempts++
            snapshotIds += materialization.snapshotId
            if (materializationAttempts <= conflictsBeforeSuccess) throw TraceabilitySnapshotVersionConflict()
            return materialization.snapshotId
        }

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

        override fun failInvalidInput(
            claim: TraceabilityVerificationJobClaim,
            diagnosticCode: String,
            completedAt: Instant,
        ) = error("unused")

        override fun recordInfrastructureFailure(claim: TraceabilityVerificationJobClaim, failedAt: Instant) =
            error("unused")
    }

    private companion object {
        val FIXED_TIME: Instant = Instant.parse("2026-09-04T00:00:00Z")

        fun pinnedExecution(canonicalizer: JcsTraceabilityCanonicalizer): PinnedTraceabilityVerificationExecution {
            val input = VerificationInput(
                schemaVersion = "m2.5-traceability-input/v1",
                policyVersion = "m2.5-traceability-policy/v1",
                validatorVersion = "m2.5-path-validator/v1",
                projectId = "project-1",
                releaseId = "release-1",
                issueSnapshot = PinnedIssueSnapshot(
                    "project-1",
                    "release-1",
                    "issue-snapshot-1",
                    "sha256:${"1".repeat(64)}",
                    listOf(TraceabilityIssue("issue-1", "SRC-1")),
                ),
                manifest = LockedManifest(
                    "project-1",
                    "release-1",
                    "manifest-revision-1",
                    "sha256:${"2".repeat(64)}",
                ),
                edgeRevisions = emptyList(),
            )
            return PinnedTraceabilityVerificationExecution(
                "run-1",
                "project-1",
                "release-1",
                "principal-1",
                "request-1",
                canonicalizer.canonicalizeInput(input).digest,
                input,
            )
        }
    }
}
