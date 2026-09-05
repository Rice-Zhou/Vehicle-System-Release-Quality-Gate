package com.ricezhou.vsrqg.traceability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class TraceabilityVerificationFixtureOrderingTest {
    @Test
    fun `snapshot fixture uses the frozen issue identity order for double digit issue ids`() {
        val unordered = listOf(
            "issue-1" to "ISSUE-1",
            "issue-2" to "ISSUE-2",
            "issue-10" to "ISSUE-10",
        )

        assertThat(orderSnapshotFixtureIssues(unordered)).containsExactly(
            "issue-1" to "ISSUE-1",
            "issue-10" to "ISSUE-10",
            "issue-2" to "ISSUE-2",
        )
    }
}
