package com.ricezhou.vsrqg.traceability

import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.Ports
import java.sql.SQLTransientConnectionException
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

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

    @Test
    fun `postgres port resolution accepts dual stack bindings for one published port`() {
        val ports = Ports().apply {
            bind(ExposedPort.tcp(5432), Ports.Binding(null, "32772"))
            bind(ExposedPort.tcp(5432), Ports.Binding("::", "32772"))
            bind(ExposedPort.tcp(6432), Ports.Binding.bindPort(32773))
        }

        assertThat(resolvePublishedPostgresPort(ports)).isEqualTo(32772)
    }

    @Test
    fun `postgres port resolution fails closed for conflicting published ports`() {
        val ports = Ports().apply {
            bind(ExposedPort.tcp(5432), Ports.Binding.bindPort(32772))
            bind(ExposedPort.tcp(5432), Ports.Binding.bindPort(32774))
        }

        assertThatThrownBy { resolvePublishedPostgresPort(ports) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Restored PostgreSQL container has multiple current port bindings")
    }

    @Test
    fun `postgres port resolution fails closed for missing and null bindings`() {
        val nullBindings = Ports().apply {
            bind(ExposedPort.tcp(5432), null)
        }

        listOf<Ports?>(null, Ports(), nullBindings).forEach { ports ->
            assertThatThrownBy { resolvePublishedPostgresPort(ports) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("Restored PostgreSQL container has no current port binding")
        }
    }

    @Test
    fun `postgres port resolution rejects a udp-only publication`() {
        val ports = Ports(ExposedPort.udp(5432), Ports.Binding.bindPort(32772))

        assertThatThrownBy { resolvePublishedPostgresPort(ports) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Restored PostgreSQL container has no current port binding")
    }

    @ParameterizedTest
    @ValueSource(strings = ["not-a-number", "0", "65536"])
    fun `postgres port resolution rejects malformed and out of range ports`(hostPort: String) {
        val ports = Ports(ExposedPort.tcp(5432), Ports.Binding(null, hostPort))

        assertThatThrownBy { resolvePublishedPostgresPort(ports) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Restored PostgreSQL container has an invalid current port binding")
    }
}
