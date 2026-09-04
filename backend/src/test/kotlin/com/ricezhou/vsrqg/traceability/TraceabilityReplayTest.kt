package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.traceability.application.GetTraceabilityVerification
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

internal class TraceabilityReplayTest : TraceabilityVerificationWorkerPostgresTest() {
    @Autowired
    private lateinit var query: GetTraceabilityVerification

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Test
    fun `later edge revision and issue snapshot cannot change historical response bytes or digest`() {
        val accepted = start("replay-${fixture.suffix}")
        assertThat(worker.runNext()).isTrue()
        val snapshotId = requireNotNull(runState(accepted.verificationRunId)[1])
        val before = query.getSnapshot(principal(), fixture.releaseId, snapshotId)
        val beforeBytes = mapper.writeValueAsBytes(before)

        val seeder = TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
        seeder.appendIssueCommitRevision(fixture, "INVALID")
        seeder.appendLatestSnapshot(fixture)

        val after = query.getSnapshot(principal(), fixture.releaseId, snapshotId)
        val afterBytes = mapper.writeValueAsBytes(after)

        assertThat(after.header.contentDigest).isEqualTo(before.header.contentDigest)
        assertThat(afterBytes).containsExactly(*beforeBytes)
    }

    private fun principal() = Principal(ISSUER, fixture.userSubject, true)
}
