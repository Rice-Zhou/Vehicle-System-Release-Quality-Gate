package com.ricezhou.vsrqg

import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.application.ProjectReferenceResolver
import com.ricezhou.vsrqg.release.application.ReleaseRepository
import com.ricezhou.vsrqg.manifest.application.ManifestRepository
import com.ricezhou.vsrqg.issue.application.IssueSyncRepository
import com.ricezhou.vsrqg.issue.application.IssueMappingProfileRepository
import com.ricezhou.vsrqg.issue.application.IssueSnapshotRepository
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.application.archive.ArchiveEvidence
import com.ricezhou.vsrqg.shared.application.archive.ArchivePolicy
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.DeploymentMode
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceRepository
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceConflictRecorder
import com.ricezhou.vsrqg.traceability.application.TraceabilityIngestAuthorizer
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.vsrqg.test",
        "spring.security.oauth2.resourceserver.jwt.audiences[0]=vsrqg-api",
    ],
)
class ApplicationContextTest {
    @Autowired
    private lateinit var archivePolicy: ArchivePolicy

    @Autowired
    private lateinit var archiveEvidence: ArchiveEvidence

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @MockitoBean
    private lateinit var projectAuthorizer: ProjectAuthorizer

    @MockitoBean
    private lateinit var projectReferenceResolver: ProjectReferenceResolver

    @MockitoBean
    private lateinit var idempotentExecutor: IdempotentExecutor

    @MockitoBean
    private lateinit var governanceStore: GovernanceStore

    @MockitoBean
    private lateinit var releaseRepository: ReleaseRepository

    @MockitoBean
    private lateinit var manifestRepository: ManifestRepository

    @MockitoBean
    private lateinit var issueSyncRepository: IssueSyncRepository

    @MockitoBean
    private lateinit var issueMappingProfileRepository: IssueMappingProfileRepository

    @MockitoBean
    private lateinit var issueSnapshotRepository: IssueSnapshotRepository

    @MockitoBean
    private lateinit var buildProvenanceRepository: BuildProvenanceRepository

    @MockitoBean
    private lateinit var traceabilityIngestAuthorizer: TraceabilityIngestAuthorizer

    @MockitoBean
    private lateinit var buildProvenanceConflictRecorder: BuildProvenanceConflictRecorder

    @MockitoBean
    private lateinit var traceabilityVerificationRepository: TraceabilityVerificationRepository

    @Test
    fun `default pilot context loads without company archive infrastructure`() {
        assertThat(archivePolicy.mode).isEqualTo(DeploymentMode.PILOT)
        assertThat(archivePolicy.provider).isEqualTo(ArchiveProvider.NONE)
        assertThat(archivePolicy.enabled).isTrue()
        assertThat(archiveEvidence).isNotNull()
        assertThat(issueMappingProfileRepository).isNotNull()
        assertThat(issueSnapshotRepository).isNotNull()
    }

    @Test
    fun `disabled traceability verification post returns the fixed 503 problem`() {
        mockMvc.post("/api/v1/releases/rel_disabled/traceability:verify") {
            with(
                jwt().jwt {
                    it.issuer("https://idp.vsrqg.test").subject("disabled-user").claim("principal_type", "USER")
                }.authorities(SimpleGrantedAuthority("SCOPE_traceability:verify")),
            )
            header("Idempotency-Key", "disabled-key")
            contentType = MediaType.APPLICATION_JSON
            content = """{"sourceId":"src_disabled"}"""
        }.andExpect {
            status { isServiceUnavailable() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("TRACEABILITY_VERIFICATION_UNAVAILABLE") }
            jsonPath("$.detail") { value("Traceability verification is disabled by deployment policy") }
        }
    }
}
