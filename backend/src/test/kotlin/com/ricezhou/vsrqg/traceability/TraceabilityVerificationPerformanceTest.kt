package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.traceability.application.GetTraceabilityVerification
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotGapView
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotHeaderView
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotIssueView
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotPathEdgeView
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRepository
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.UUID
import kotlin.math.ceil
import kotlin.system.measureNanoTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

internal class TraceabilityVerificationPerformanceTest : TraceabilityVerificationWorkerPostgresTest() {
    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var authorizer: ProjectAuthorizer

    @Test
    fun `pilot boundary keeps twenty issues and two thousand fixed edges within reproducible hard limits`() {
        fixture = TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate).seed(issueCount = ISSUE_COUNT)
        TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendValidIssueCommitEdges(fixture, EDGE_COUNT - fixture.pathEdges.size)

        assertThat(authorityEdgeCount()).isEqualTo(EDGE_COUNT)
        assertThat(snapshotIssueCount()).isEqualTo(ISSUE_COUNT)

        val startSamples = mutableListOf<Long>()
        val workerSamples = mutableListOf<Long>()
        val querySamples = mutableListOf<Long>()
        var observedCounts: Map<String, Int> = emptyMap()
        repeat(SAMPLE_COUNT) { sample ->
            lateinit var runId: String
            startSamples += elapsedMillis {
                runId = start("performance-$sample-${fixture.suffix}").verificationRunId
            }
            assertThat(runLedgerEdgeCount(runId)).isEqualTo(EDGE_COUNT)
            lateinit var snapshotId: String
            workerSamples += elapsedMillis {
                snapshotId = awaitSuccessfulSnapshot(runId)
            }
            assertThat(materializedEdgeCount(snapshotId)).isEqualTo(EDGE_COUNT)

            val counted = CountingReadRepository(repository)
            val reader = GetTraceabilityVerification(counted, authorizer)
            querySamples += elapsedMillis {
                val result = reader.getSnapshot(
                    Principal(ISSUER, fixture.userSubject, true),
                    fixture.releaseId,
                    snapshotId,
                )
                assertThat(result.issues).hasSize(ISSUE_COUNT)
            }
            observedCounts = counted.counts.toMap()
            assertThat(observedCounts).containsExactlyEntriesOf(EXPECTED_QUERY_COUNTS)
        }

        val report = linkedMapOf<String, Any>(
            "schemaVersion" to 1,
            "fixture" to linkedMapOf("issues" to ISSUE_COUNT, "edges" to EDGE_COUNT),
            "samples" to SAMPLE_COUNT,
            "start" to metrics(startSamples, TARGET_START_P95_MS, HARD_START_MS),
            "worker" to metrics(workerSamples, TARGET_WORKER_P95_MS, HARD_WORKER_MS),
            "query" to metrics(querySamples, TARGET_QUERY_P95_MS, HARD_QUERY_MS),
            "queryCounts" to observedCounts,
            "hardware" to linkedMapOf(
                "processors" to Runtime.getRuntime().availableProcessors(),
                "maxMemoryBytes" to Runtime.getRuntime().maxMemory(),
            ),
            "runtime" to linkedMapOf(
                "java" to safeMetadata(System.getProperty("java.version")),
                "os" to safeMetadata(System.getProperty("os.name")),
            ),
        )
        writeEvidence("traceability-performance.json", report)
    }

    private fun metrics(samples: List<Long>, targetP95Ms: Long, hardLimitMs: Long): Map<String, Long> {
        val sorted = samples.sorted()
        val report = linkedMapOf(
            "p50Ms" to percentile(sorted, 0.50),
            "p95Ms" to percentile(sorted, 0.95),
            "maxMs" to sorted.last(),
            "targetP95Ms" to targetP95Ms,
            "hardLimitMs" to hardLimitMs,
        )
        assertThat(report.getValue("maxMs")).isLessThanOrEqualTo(hardLimitMs)
        return report
    }

    private fun percentile(sorted: List<Long>, percentile: Double): Long =
        sorted[(ceil(sorted.size * percentile).toInt() - 1).coerceAtLeast(0)]

    private fun elapsedMillis(block: () -> Unit): Long =
        (measureNanoTime(block) / 1_000_000).coerceAtLeast(0)

    private fun authorityEdgeCount(): Int = jdbc.sql(
        """
        SELECT
          (SELECT count(*) FROM issue_commit_edge_revision WHERE project_id = :projectId) +
          (SELECT count(*) FROM commit_build_edge_revision WHERE project_id = :projectId) +
          (SELECT count(*) FROM build_artifact_edge_revision WHERE project_id = :projectId) +
          (SELECT count(*) FROM artifact_release_edge_v
             WHERE project_id = :projectId AND release_id = :releaseId)
        """.trimIndent(),
    ).param("projectId", fixture.projectId).param("releaseId", fixture.releaseId)
        .query(Int::class.java).single()

    private fun snapshotIssueCount(): Int = jdbc.sql(
        "SELECT count(*) FROM release_issue_snapshot_item WHERE snapshot_id = :snapshotId",
    ).param("snapshotId", fixture.snapshotId).query(Int::class.java).single()

    private fun runLedgerEdgeCount(runId: String): Int = jdbc.sql(
        "SELECT count(*) FROM traceability_verification_run_edge_input WHERE verification_run_id = :runId",
    ).param("runId", runId).query(Int::class.java).single()

    private fun materializedEdgeCount(snapshotId: String): Int = jdbc.sql(
        "SELECT count(*) FROM traceability_snapshot_edge WHERE snapshot_id = :snapshotId",
    ).param("snapshotId", snapshotId).query(Int::class.java).single()

    private fun awaitSuccessfulSnapshot(runId: String): String {
        val deadline = System.nanoTime() + WORKER_COMPLETION_TIMEOUT.toNanos()
        var state = runState(runId)
        while (true) {
            when (
                val decision = evaluateTraceabilityPerformanceAwait(
                    runId = runId,
                    runState = state,
                    timedOut = System.nanoTime() >= deadline,
                    jobState = { jobState(runId) },
                )
            ) {
                is TraceabilityPerformanceAwaitDecision.Completed -> return decision.snapshotId
                is TraceabilityPerformanceAwaitDecision.Failed -> error(decision.diagnostic)
                TraceabilityPerformanceAwaitDecision.Pending -> Unit
            }
            if (!worker.runNext()) Thread.sleep(WORKER_POLL_MILLIS)
            state = runState(runId)
        }
    }

    private fun safeMetadata(value: String): String {
        require(value.matches(Regex("^[A-Za-z0-9 ._()+/-]{1,128}$")))
        return value
    }

    private fun writeEvidence(fileName: String, value: Any) {
        val directory = Path.of("build", "m2")
        Files.createDirectories(directory)
        val target = directory.resolve(fileName)
        val temporary = directory.resolve("$fileName.${UUID.randomUUID()}.tmp")
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value)
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val ISSUE_COUNT = 20
        const val EDGE_COUNT = 2_000
        const val SAMPLE_COUNT = 3
        const val TARGET_START_P95_MS = 1_000L
        const val TARGET_WORKER_P95_MS = 10_000L
        const val TARGET_QUERY_P95_MS = 1_000L
        const val HARD_START_MS = 30_000L
        const val HARD_WORKER_MS = 60_000L
        const val HARD_QUERY_MS = 30_000L
        const val WORKER_POLL_MILLIS = 25L
        val WORKER_COMPLETION_TIMEOUT: Duration = Duration.ofSeconds(60)
        val EXPECTED_QUERY_COUNTS = linkedMapOf(
            "release" to 1,
            "header" to 1,
            "issues" to 1,
            "paths" to 1,
            "gaps" to 1,
        )
    }
}

internal sealed interface TraceabilityPerformanceAwaitDecision {
    data object Pending : TraceabilityPerformanceAwaitDecision

    data class Completed(val snapshotId: String) : TraceabilityPerformanceAwaitDecision

    data class Failed(val diagnostic: String) : TraceabilityPerformanceAwaitDecision
}

internal fun evaluateTraceabilityPerformanceAwait(
    runId: String,
    runState: List<String?>,
    timedOut: Boolean,
    jobState: () -> List<String?>,
): TraceabilityPerformanceAwaitDecision {
    val status = runState.getOrNull(0)
    val snapshotId = runState.getOrNull(1)
    val reason = when {
        status == "SUCCEEDED" && snapshotId != null ->
            return TraceabilityPerformanceAwaitDecision.Completed(snapshotId)
        status == "FAILED" -> "RUN_FAILED"
        status == "SUCCEEDED" -> "SUCCEEDED_WITHOUT_SNAPSHOT"
        timedOut -> "TIMEOUT"
        else -> return TraceabilityPerformanceAwaitDecision.Pending
    }
    val job = jobState()
    val diagnostic = "Traceability verification performance sample failed: " +
        "reason=$reason runId=$runId status=${status ?: "MISSING"} " +
        "diagnostic=${runState.getOrNull(2) ?: "MISSING"} " +
        "jobLifecycle={status=${job.getOrNull(0) ?: "MISSING"}," +
        "attemptCount=${job.getOrNull(1) ?: "MISSING"}," +
        "resultSummary=${job.getOrNull(2) ?: "MISSING"}}"
    return TraceabilityPerformanceAwaitDecision.Failed(diagnostic)
}

private class CountingReadRepository(
    private val delegate: TraceabilityVerificationRepository,
) : TraceabilityVerificationRepository by delegate {
    val counts = linkedMapOf(
        "release" to 0,
        "header" to 0,
        "issues" to 0,
        "paths" to 0,
        "gaps" to 0,
    )

    override fun findReleaseProjectId(releaseId: String): String? {
        increment("release")
        return delegate.findReleaseProjectId(releaseId)
    }

    override fun findSnapshotHeader(releaseId: String, snapshotId: String?): TraceabilitySnapshotHeaderView? {
        increment("header")
        return delegate.findSnapshotHeader(releaseId, snapshotId)
    }

    override fun findSnapshotIssues(snapshotId: String): List<TraceabilitySnapshotIssueView> {
        increment("issues")
        return delegate.findSnapshotIssues(snapshotId)
    }

    override fun findSnapshotPathEdges(snapshotId: String): List<TraceabilitySnapshotPathEdgeView> {
        increment("paths")
        return delegate.findSnapshotPathEdges(snapshotId)
    }

    override fun findSnapshotGaps(snapshotId: String): List<TraceabilitySnapshotGapView> {
        increment("gaps")
        return delegate.findSnapshotGaps(snapshotId)
    }

    private fun increment(name: String) {
        counts[name] = counts.getValue(name) + 1
    }
}
