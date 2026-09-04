package com.ricezhou.vsrqg.traceability

import com.ricezhou.vsrqg.shared.runConcurrently
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class TraceabilityVerificationConcurrencyTest : TraceabilityVerificationWorkerPostgresTest() {
    @Test
    fun `claim skips a locked eligible job without waiting for its transaction`() {
        val accepted = start("worker-skip-locked-${fixture.suffix}")
        val lockAcquired = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val lockHolder = executor.submit {
                transactionTemplate.executeWithoutResult {
                    jdbc.sql(
                        "SELECT id FROM background_job WHERE idempotency_key = :runId FOR UPDATE",
                    ).param("runId", accepted.verificationRunId).query(String::class.java).single()
                    lockAcquired.countDown()
                    check(releaseLock.await(5, TimeUnit.SECONDS)) { "Timed out holding the job row lock" }
                }
            }
            assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue()

            val contender = executor.submit {
                repository.claimNext(Instant.now().plusSeconds(60))
            }

            assertThat(contender.get(1, TimeUnit.SECONDS)).isNull()
            releaseLock.countDown()
            lockHolder.get(5, TimeUnit.SECONDS)
        } finally {
            releaseLock.countDown()
            executor.shutdown()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `materialization waits on the database release row lock before allocating a version`() {
        val accepted = start("worker-release-lock-${fixture.suffix}")
        val lockAcquired = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val lockHolder = executor.submit {
                transactionTemplate.executeWithoutResult {
                    jdbc.sql(
                        "SELECT id FROM release_record WHERE id = :releaseId FOR UPDATE",
                    ).param("releaseId", fixture.releaseId).query(String::class.java).single()
                    lockAcquired.countDown()
                    check(releaseLock.await(5, TimeUnit.SECONDS)) { "Timed out holding the release row lock" }
                }
            }
            assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue()

            val workerFuture = executor.submit<Boolean> { worker.runNext() }
            assertThat(awaitReleaseLockWait(workerFuture)).isTrue()
            assertThat(workerFuture.isDone).isFalse()

            releaseLock.countDown()
            assertThat(workerFuture.get(5, TimeUnit.SECONDS)).isTrue()
            lockHolder.get(5, TimeUnit.SECONDS)
            assertThat(runState(accepted.verificationRunId)[0]).isEqualTo("SUCCEEDED")
        } finally {
            releaseLock.countDown()
            executor.shutdown()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

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

    private fun awaitReleaseLockWait(workerFuture: Future<Boolean>): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        do {
            val observed = jdbc.sql(
                """
                SELECT EXISTS (
                  SELECT 1 FROM pg_stat_activity
                  WHERE datname = current_database() AND pid <> pg_backend_pid()
                    AND state = 'active' AND wait_event_type = 'Lock'
                    AND query ILIKE '%FROM release_record%'
                )
                """.trimIndent(),
            ).query(Boolean::class.java).single()
            if (observed) return true
            if (workerFuture.isDone) return false
            Thread.sleep(20)
        } while (System.nanoTime() < deadline)
        return false
    }
}
