package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@AutoConfigureMockMvc
internal class TraceabilityReplayTest : TraceabilityVerificationWorkerPostgresTest() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Test
    fun `later edge revision and issue snapshot cannot change historical public response bytes or digest`() {
        fixture = TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate).seed(issueCount = 2)
        val accepted = start("replay-${fixture.suffix}")
        assertThat(worker.runNext()).isTrue()
        val snapshotId = requireNotNull(runState(accepted.verificationRunId)[1])
        val beforeBytes = getHistoricalSnapshot(snapshotId)
        val before = mapper.readTree(beforeBytes)

        assertThat(before.fieldNames().asSequence().toList()).containsExactly("snapshot", "issues")
        assertThat(before.path("issues").map { it.path("sourceIssueId").textValue() }).containsExactly(
            "ISSUE-1",
            "ISSUE-2-${fixture.suffix}",
        )
        assertThat(before.path("issues").first().path("path").map { it.path("edgeType").textValue() })
            .containsExactly("ISSUE_COMMIT", "COMMIT_BUILD", "BUILD_ARTIFACT", "ARTIFACT_RELEASE")
        val beforeDigest = before.path("snapshot").path("contentDigest").textValue()

        val seeder = TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
        seeder.appendIssueCommitRevision(fixture, "INVALID")
        seeder.appendLatestSnapshot(fixture)

        val afterBytes = getHistoricalSnapshot(snapshotId)
        val after = mapper.readTree(afterBytes)

        assertThat(after.path("snapshot").path("contentDigest").textValue()).isEqualTo(beforeDigest)
        assertThat(afterBytes).containsExactly(*beforeBytes)
    }

    private fun getHistoricalSnapshot(snapshotId: String): ByteArray = mockMvc.get(
        "/api/v1/releases/{releaseId}/traceability",
        fixture.releaseId,
    ) {
        param("snapshotId", snapshotId)
        with(
            jwt().jwt {
                it.issuer(ISSUER).subject(fixture.userSubject).claim("principal_type", "USER")
            }.authorities(SimpleGrantedAuthority("SCOPE_traceability:read")),
        )
    }.andExpect {
        status { isOk() }
        content { contentType(MediaType.APPLICATION_JSON) }
    }.andReturn().response.contentAsByteArray
}
