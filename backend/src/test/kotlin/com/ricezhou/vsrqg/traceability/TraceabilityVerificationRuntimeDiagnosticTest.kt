package com.ricezhou.vsrqg.traceability

import java.sql.SQLTransientConnectionException
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class TraceabilityVerificationRuntimeDiagnosticTest {
    @Test
    fun `failed run reports the fixed run and job lifecycle fields`() {
        val decision = evaluateTraceabilityPerformanceAwait(
            runId = "run-failed",
            runState = listOf("FAILED", null, "TRACEABILITY_VERIFICATION_RETRY_EXHAUSTED"),
            timedOut = false,
            jobState = { listOf("DEAD_LETTER", "3", "retry exhausted") },
        )

        assertThat(decision).isEqualTo(
            TraceabilityPerformanceAwaitDecision.Failed(
                "Traceability verification performance sample failed: " +
                    "reason=RUN_FAILED runId=run-failed status=FAILED " +
                    "diagnostic=TRACEABILITY_VERIFICATION_RETRY_EXHAUSTED " +
                    "jobLifecycle={status=DEAD_LETTER,attemptCount=3,resultSummary=retry exhausted}",
            ),
        )
    }

    @Test
    fun `successful run without snapshot reports the same fixed diagnostic fields`() {
        val decision = evaluateTraceabilityPerformanceAwait(
            runId = "run-empty",
            runState = listOf("SUCCEEDED", null, null),
            timedOut = false,
            jobState = { listOf("SUCCEEDED", "1", null) },
        )

        assertThat(decision).isEqualTo(
            TraceabilityPerformanceAwaitDecision.Failed(
                "Traceability verification performance sample failed: " +
                    "reason=SUCCEEDED_WITHOUT_SNAPSHOT runId=run-empty status=SUCCEEDED " +
                    "diagnostic=MISSING " +
                    "jobLifecycle={status=SUCCEEDED,attemptCount=1,resultSummary=MISSING}",
            ),
        )
    }

    @Test
    fun `timed out run reports the same fixed diagnostic fields`() {
        val decision = evaluateTraceabilityPerformanceAwait(
            runId = "run-timeout",
            runState = listOf("RUNNING", null, null),
            timedOut = true,
            jobState = { listOf("RUNNING", "1", "lease active") },
        )

        assertThat(decision).isEqualTo(
            TraceabilityPerformanceAwaitDecision.Failed(
                "Traceability verification performance sample failed: " +
                    "reason=TIMEOUT runId=run-timeout status=RUNNING diagnostic=MISSING " +
                    "jobLifecycle={status=RUNNING,attemptCount=1,resultSummary=lease active}",
            ),
        )
    }

    @Test
    fun `restart timeout distinguishes no connection from stale process identity`() {
        val before = Instant.parse("2026-09-05T01:02:03Z")
        val connectionFailure = SQLTransientConnectionException("connection refused")

        assertThat(postgresRestartTimeoutMessage(before, null, connectionFailure)).isEqualTo(
            "Restored PostgreSQL restart verification timed out: " +
                "outcome=NO_FRESH_CONNECTION " +
                "beforePostmasterStartedAt=2026-09-05T01:02:03Z " +
                "lastObservedPostmasterStartedAt=NONE " +
                "lastConnectionFailure=java.sql.SQLTransientConnectionException",
        )
        assertThat(postgresRestartTimeoutMessage(before, before, null)).isEqualTo(
            "Restored PostgreSQL restart verification timed out: " +
                "outcome=POSTMASTER_START_TIME_NOT_ADVANCED " +
                "beforePostmasterStartedAt=2026-09-05T01:02:03Z " +
                "lastObservedPostmasterStartedAt=2026-09-05T01:02:03Z " +
                "lastConnectionFailure=NONE",
        )
    }
}
