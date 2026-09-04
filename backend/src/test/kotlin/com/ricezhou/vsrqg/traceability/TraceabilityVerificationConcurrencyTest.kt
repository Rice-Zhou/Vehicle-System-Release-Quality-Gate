package com.ricezhou.vsrqg.traceability

import com.ricezhou.vsrqg.shared.runConcurrently
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class TraceabilityVerificationConcurrencyTest : TraceabilityVerificationWorkerPostgresTest() {
    @Test
    fun `two workers claim one job once with skip locked`() {
        val accepted = start("worker-single-claim-${fixture.suffix}")

        val outcomes = runConcurrently(2) { worker.runNext() }

        assertThat(outcomes).containsExactlyInAnyOrder(true, false)
        assertThat(runState(accepted.verificationRunId)[0]).isEqualTo("SUCCEEDED")
        assertThat(jobState(accepted.verificationRunId).take(2)).containsExactly("SUCCEEDED", "1")
        assertThat(
            jdbc.sql("SELECT count(*) FROM traceability_snapshot WHERE verification_run_id = :runId")
                .param("runId", accepted.verificationRunId).query(Int::class.java).single(),
        ).isOne()
    }

    @Test
    fun `concurrent same input runs converge on one immutable snapshot`() {
        val first = start("worker-converge-1-${fixture.suffix}")
        val second = start("worker-converge-2-${fixture.suffix}")

        assertThat(runConcurrently(2) { worker.runNext() }).containsOnly(true)

        val firstSnapshot = runState(first.verificationRunId)[1]
        assertThat(runState(second.verificationRunId)[1]).isEqualTo(firstSnapshot)
        assertThat(
            jdbc.sql("SELECT count(*) FROM traceability_snapshot WHERE project_id = :projectId")
                .param("projectId", fixture.projectId).query(Int::class.java).single(),
        ).isOne()
    }

    @Test
    fun `different inputs allocate consecutive release versions under database row lock`() {
        val first = start("worker-version-1-${fixture.suffix}")
        val latest = TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendLatestSnapshot(fixture)
        TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendIssueCommitForIssue(fixture, latest.issueId, "worker-version")
        val second = start("worker-version-2-${fixture.suffix}")

        assertThat(runConcurrently(2) { worker.runNext() }).containsOnly(true)

        assertThat(runState(first.verificationRunId)[0]).isEqualTo("SUCCEEDED")
        assertThat(runState(second.verificationRunId)[0]).isEqualTo("SUCCEEDED")
        assertThat(
            jdbc.sql(
                "SELECT version FROM traceability_snapshot WHERE release_id = :releaseId ORDER BY version",
            ).param("releaseId", fixture.releaseId).query(Int::class.java).list(),
        ).containsExactly(1, 2)
    }
}
