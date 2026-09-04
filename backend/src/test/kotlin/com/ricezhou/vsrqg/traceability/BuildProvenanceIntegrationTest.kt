package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import com.ricezhou.vsrqg.shared.runConcurrently
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import com.ricezhou.vsrqg.traceability.adapter.BuildProvenanceIngestionProperties
import com.ricezhou.vsrqg.traceability.adapter.BuildProvenancePayloadLimitFilter
import com.ricezhou.vsrqg.traceability.adapter.BuildProvenanceRequest
import com.ricezhou.vsrqg.traceability.application.ArtifactDigestMismatch
import com.ricezhou.vsrqg.traceability.application.IngestBuildProvenance
import com.ricezhou.vsrqg.traceability.application.IngestBuildProvenanceCommand
import com.ricezhou.vsrqg.traceability.domain.BuildProvenanceEnvelope
import com.ricezhou.vsrqg.traceability.domain.ProvenanceProviderId
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "vsrqg.traceability.ingestion.enabled=true",
    ],
)
class BuildProvenanceIntegrationTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var useCase: IngestBuildProvenance

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private lateinit var fixture: BuildProvenanceTestFixture

    @BeforeEach
    fun seedAuthority() {
        fixture = BuildProvenanceFixtureSeeder(jdbc, transactionTemplate).seed()
    }

    @Test
    fun `service ingestion creates one atomic replayable provenance chain`() {
        val key = "provenance-${fixture.suffix}"

        val first = ingest(fixture.envelope(objectMapper), key).andExpect {
            status { isOk() }
            jsonPath("$.verificationStatus") { value("VALID") }
            jsonPath("$.confidence") { value("MEDIUM") }
            jsonPath("$.edgeRevisions.length()") { value(3) }
        }.andReturn().response.contentAsString
        val replay = ingest(fixture.envelope(objectMapper), key).andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

        assertThat(replay).isEqualTo(first)
        assertThat(countProject("source_commit")).isOne()
        assertThat(countProject("build_record")).isOne()
        assertThat(countProject("traceability_edge_identity")).isEqualTo(3)
        assertThat(countProject("build_provenance_receipt")).isOne()
        assertThat(countProject("audit_event")).isOne()
        assertThat(countOutbox()).isOne()
        assertThat(countIdempotency(key)).isOne()
        assertThat(countArtifactReleaseWrites()).isZero()
        assertThat(
            jdbc.sql("SELECT DISTINCT source_type FROM issue_commit_edge_revision WHERE project_id = :projectId")
                .param("projectId", fixture.projectId)
                .query(String::class.java)
                .single(),
        ).isEqualTo("github-actions")
    }

    @Test
    fun `fixture commits snapshot header and items through one transaction`() {
        assertThat(countProject("release_issue_snapshot")).isOne()
        assertThat(
            jdbc.sql(
                "SELECT count(*) FROM release_issue_snapshot_item WHERE snapshot_id = :snapshotId",
            ).param("snapshotId", fixture.snapshotId).query(Int::class.java).single(),
        ).isOne()
    }

    @Test
    fun `service scope and project authority reject every non-service path`() {
        ingest(fixture.envelope(objectMapper), "no-scope-${fixture.suffix}", hasScope = false).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("ACCESS_DENIED") }
        }
        listOf(
            DeniedIdentity(fixture.userSubject, "USER", fixture.projectReference),
            DeniedIdentity(fixture.serviceSubject, "SERVICE", null),
            DeniedIdentity(fixture.serviceSubject, "SERVICE", "wrong-${fixture.suffix}"),
            DeniedIdentity(fixture.disabledServiceSubject, "SERVICE", fixture.projectReference),
            DeniedIdentity(fixture.unassignedServiceSubject, "SERVICE", fixture.projectReference),
        ).forEachIndexed { index, denied ->
            ingest(
                fixture.envelope(objectMapper),
                "denied-$index-${fixture.suffix}",
                subject = denied.subject,
                principalType = denied.principalType,
                projectClaim = denied.projectClaim,
            ).andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("PROJECT_SCOPE_MISMATCH") }
            }
        }
        assertThat(countProject("build_provenance_receipt")).isZero()
    }

    @Test
    fun `archived project rejects an otherwise valid service identity`() {
        jdbc.sql("UPDATE project SET archived = true WHERE id = :projectId")
            .param("projectId", fixture.projectId)
            .update()

        ingest(fixture.envelope(objectMapper), "archived-${fixture.suffix}").andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("PROJECT_SCOPE_MISMATCH") }
        }
    }

    @Test
    fun `snapshot issue and artifact authority failures are hidden and atomic`() {
        val unknownSnapshot = fixture.envelope(objectMapper).put(
            "releaseIssueSnapshotId",
            "ris_missing_${fixture.suffix}",
        )
        ingest(unknownSnapshot, "missing-snapshot-${fixture.suffix}").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RESOURCE_NOT_FOUND") }
        }

        val unknownIssue = fixture.envelope(objectMapper).also {
            it.putArray("sourceIssueIds").add("ISSUE-MISSING")
        }
        ingest(unknownIssue, "missing-issue-${fixture.suffix}").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("SNAPSHOT_ISSUE_NOT_FOUND") }
        }

        val unknownArtifact = fixture.envelope(objectMapper).also {
            it.putArray("artifactSha256s").add("e".repeat(64))
        }
        ingest(unknownArtifact, "missing-artifact-${fixture.suffix}").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("ARTIFACT_NOT_FOUND") }
        }

        assertThat(countProject("source_commit")).isZero()
        assertThat(countProject("build_record")).isZero()
        assertThat(countProject("traceability_edge_identity")).isZero()
        assertThat(countProject("build_provenance_receipt")).isZero()
        assertThat(countProject("audit_event")).isZero()
        assertThat(countOutbox()).isZero()
    }

    @Test
    fun `application rejects ambiguous artifact checksum authority without accepted facts`() {
        BuildProvenanceFixtureSeeder(jdbc, transactionTemplate).addDuplicateChecksumArtifact(fixture)
        val key = "ambiguous-application-${fixture.suffix}"

        assertThatThrownBy { useCase.ingest(command(key)) }
            .isInstanceOf(ArtifactDigestMismatch::class.java)
            .extracting("code")
            .isEqualTo("ARTIFACT_DIGEST_MISMATCH")

        assertThat(countProject("source_commit")).isZero()
        assertThat(countProject("build_record")).isZero()
        assertThat(countProject("traceability_edge_identity")).isZero()
        assertThat(countProject("build_provenance_receipt")).isZero()
        assertThat(countProject("audit_event")).isZero()
        assertThat(countOutbox()).isZero()
        assertThat(countIdempotency(key)).isZero()
    }

    @Test
    fun `ambiguous artifact checksum returns the fixed conflict problem`() {
        BuildProvenanceFixtureSeeder(jdbc, transactionTemplate).addDuplicateChecksumArtifact(fixture)

        ingest(fixture.envelope(objectMapper), "ambiguous-http-${fixture.suffix}").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("ARTIFACT_DIGEST_MISMATCH") }
        }

        assertThat(countProject("build_provenance_receipt")).isZero()
        assertThat(countProject("audit_event")).isZero()
        assertThat(countOutbox()).isZero()
    }

    @Test
    fun `invalid domain input returns an allowlisted 422 without persistence`() {
        listOf(
            fixture.envelope(objectMapper).put("schemaVersion", 1) to "SCHEMA_VERSION_UNSUPPORTED",
            fixture.envelope(objectMapper).put("provider", "JENKINS") to "PROVIDER_INVALID",
            fixture.envelope(objectMapper).put("pipeline", "unsafe\u0001pipeline") to "PIPELINE_INVALID",
        ).forEachIndexed { index, (invalid, violation) ->
            val response = ingest(invalid, "invalid-$index-${fixture.suffix}").andExpect {
                status { isUnprocessableEntity() }
                jsonPath("$.code") { value("PROOF_VALIDATION_FAILED") }
                jsonPath("$.violations[0].code") { value(violation) }
            }.andReturn().response.contentAsString
            assertThat(response).doesNotContain(
                fixture.serviceSubject,
                "unsafe",
                "Authorization",
                "Bearer",
                "stack",
            )
        }
        assertThat(countProject("build_provenance_receipt")).isZero()
    }

    @Test
    fun `strict v2 decoding rejects null array entries and scalar coercion before any write`() {
        listOf(
            fixture.envelope(objectMapper).also { it.withArray("sourceIssueIds").removeAll().addNull() },
            fixture.envelope(objectMapper).put("project", 7),
            fixture.envelope(objectMapper).put("sourceRevision", true),
            fixture.envelope(objectMapper).put("buildAttempt", "1"),
            fixture.envelope(objectMapper).put("buildAttempt", 1.5),
        ).forEachIndexed { index, malformed ->
            ingest(malformed, "strict-$index-${fixture.suffix}").andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_REQUEST") }
            }
        }

        assertThat(countProject("source_commit")).isZero()
        assertThat(countProject("build_record")).isZero()
        assertThat(countProject("traceability_edge_identity")).isZero()
        assertThat(countProject("build_provenance_receipt")).isZero()
        assertThat(countProject("audit_event")).isZero()
        assertThat(countOutbox()).isZero()
        assertThat(countPrincipalIdempotency()).isZero()
    }

    @Test
    fun `same idempotency key with a different envelope returns the dedicated conflict`() {
        val key = "same-key-${fixture.suffix}"
        ingest(fixture.envelope(objectMapper), key).andExpect { status { isOk() } }
        val changed = fixture.envelope(objectMapper).put("sourceRevision", "b".repeat(40))

        ingest(changed, key).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("IDEMPOTENCY_CONFLICT") }
        }

        assertThat(countIdempotency(key)).isOne()
        assertThat(countProject("build_provenance_receipt")).isOne()
        assertThat(countProject("build_record")).isOne()
    }

    @Test
    fun `same build attempt with another key reuses the accepted receipt response`() {
        val first = ingest(fixture.envelope(objectMapper), "attempt-a-${fixture.suffix}")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val replay = ingest(fixture.envelope(objectMapper), "attempt-b-${fixture.suffix}")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        assertThat(replay).isEqualTo(first)
        assertThat(countProject("build_provenance_receipt")).isOne()
        assertThat(countProject("traceability_edge_identity")).isEqualTo(3)
        assertThat(countPrincipalIdempotency()).isEqualTo(2)
    }

    @Test
    fun `concurrent whole ingestion with different keys converges on one accepted result`() {
        val keySequence = AtomicInteger()

        val results = runConcurrently(2) {
            useCase.ingest(command("concurrent-${keySequence.getAndIncrement()}-${fixture.suffix}"))
        }

        assertThat(results).containsOnly(results.first())
        assertThat(countProject("source_commit")).isOne()
        assertThat(countProject("build_record")).isOne()
        assertThat(countProject("traceability_edge_identity")).isEqualTo(3)
        assertThat(countProject("issue_commit_edge_revision")).isOne()
        assertThat(countProject("commit_build_edge_revision")).isOne()
        assertThat(countProject("build_artifact_edge_revision")).isOne()
        assertThat(countProject("build_provenance_receipt")).isOne()
        assertThat(countProject("audit_event")).isOne()
        assertThat(countOutbox()).isOne()
        assertThat(countPrincipalIdempotency()).isEqualTo(2)
    }

    @Test
    fun `application derives edge source type from the normalized provider identity`() {
        val provider = ProvenanceProviderId("fixture-ci")

        val result = useCase.ingest(
            command(
                key = "provider-neutral-${fixture.suffix}",
                envelope = fixture.domainEnvelope().copy(provider = provider),
            ),
        )

        assertThat(result.verificationStatus.name).isEqualTo("ERROR")
        assertThat(
            jdbc.sql("SELECT DISTINCT source_type FROM issue_commit_edge_revision WHERE project_id = :projectId")
                .param("projectId", fixture.projectId)
                .query(String::class.java)
                .single(),
        ).isEqualTo(provider.value)
    }

    @Test
    fun `a later invalid proof appends a conflict revision without replacing accepted history`() {
        ingest(fixture.envelope(objectMapper), "proof-a-${fixture.suffix}").andExpect { status { isOk() } }
        val contradicted = fixture.envelope(objectMapper).also {
            it.put("buildAttempt", 2)
            it.put("proofReference", PROOF_REFERENCE_ATTEMPT_2)
            it.put("proofDigest", "sha256:${"a".repeat(64)}")
        }

        ingest(contradicted, "proof-b-${fixture.suffix}").andExpect {
            status { isOk() }
            jsonPath("$.verificationStatus") { value("INVALID") }
            jsonPath("$.confidence") { value("LOW") }
        }

        val issueRevisions = jdbc.sql(
            """
            SELECT revision, verification_status
            FROM issue_commit_edge_revision
            WHERE project_id = :projectId
            ORDER BY revision
            """.trimIndent(),
        ).param("projectId", fixture.projectId)
            .query { rs, _ -> rs.getInt("revision") to rs.getString("verification_status") }
            .list()
        assertThat(issueRevisions).containsExactly(1 to "VALID", 2 to "CONFLICT")
        assertThat(countProject("source_commit")).isOne()
        assertThat(countProject("build_provenance_receipt")).isEqualTo(2)
    }

    @Test
    fun `successful governance records contain only bounded provenance metadata`() {
        val forbidden = listOf(
            fixture.serviceSubject,
            SOURCE_REVISION,
            REPOSITORY,
            PROOF_REFERENCE,
            "Authorization",
            "Cookie",
            "runner",
        )

        ingest(fixture.envelope(objectMapper), "safe-metadata-${fixture.suffix}").andExpect { status { isOk() } }

        val audit = jdbc.sql(
            "SELECT coalesce(before_state::text, '') || coalesce(after_state::text, '') FROM audit_event " +
                "WHERE project_id = :projectId",
        ).param("projectId", fixture.projectId).query(String::class.java).single()
        val outbox = jdbc.sql(
            "SELECT payload::text FROM outbox_event WHERE event_type = :eventType AND aggregate_id IN " +
                "(SELECT id FROM build_provenance_receipt WHERE project_id = :projectId)",
        ).param("eventType", INGESTED_EVENT).param("projectId", fixture.projectId)
            .query(String::class.java).single()
        forbidden.forEach {
            assertThat(audit).doesNotContain(it)
            assertThat(outbox).doesNotContain(it)
        }
        assertThat(audit).contains("envelopeDigest", "issueCount", "artifactCount", "edgeCount")
        assertThat(outbox).contains("envelopeDigest", "validatorVersion")
    }

    private fun command(
        key: String,
        envelope: BuildProvenanceEnvelope = fixture.domainEnvelope(),
    ) = IngestBuildProvenanceCommand(
        principal = Principal(ISSUER, fixture.serviceSubject, true),
        tokenProjectReference = fixture.projectReference,
        envelope = envelope,
        idempotencyKey = key,
        requestId = "req_$key",
    )

    private fun ingest(
        body: ObjectNode,
        key: String,
        subject: String = fixture.serviceSubject,
        principalType: String = "SERVICE",
        projectClaim: String? = fixture.projectReference,
        hasScope: Boolean = true,
    ) = mockMvc.post(INGEST_PATH) {
        val token = jwt().jwt { builder ->
            builder.issuer(ISSUER).subject(subject).claim("principal_type", principalType)
            if (projectClaim != null) builder.claim("project", projectClaim)
        }
        if (hasScope) token.authorities(SimpleGrantedAuthority(INGEST_SCOPE)) else token.authorities()
        with(token)
        header("Idempotency-Key", key)
        contentType = MediaType.APPLICATION_JSON
        content = objectMapper.writeValueAsBytes(body)
    }

    private fun countProject(table: String): Int {
        require(table in PROJECT_TABLES)
        return jdbc.sql("SELECT count(*) FROM $table WHERE project_id = :projectId")
            .param("projectId", fixture.projectId)
            .query(Int::class.java)
            .single()
    }

    private fun countIdempotency(key: String): Int = jdbc.sql(
        "SELECT count(*) FROM idempotency_record WHERE principal_id = :principalId AND idempotency_key = :key",
    ).param("principalId", fixture.servicePrincipalId).param("key", key)
        .query(Int::class.java).single()

    private fun countPrincipalIdempotency(): Int = jdbc.sql(
        "SELECT count(*) FROM idempotency_record WHERE principal_id = :principalId",
    ).param("principalId", fixture.servicePrincipalId).query(Int::class.java).single()

    private fun countOutbox(): Int = jdbc.sql(
        "SELECT count(*) FROM outbox_event WHERE event_type = :eventType AND aggregate_id IN " +
            "(SELECT id FROM build_provenance_receipt WHERE project_id = :projectId)",
    ).param("eventType", INGESTED_EVENT).param("projectId", fixture.projectId)
        .query(Int::class.java).single()

    private fun countArtifactReleaseWrites(): Int = jdbc.sql(
        "SELECT count(*) FROM traceability_edge_identity WHERE project_id = :projectId AND edge_type = 'ARTIFACT_RELEASE'",
    ).param("projectId", fixture.projectId).query(Int::class.java).single()

    private data class DeniedIdentity(
        val subject: String,
        val principalType: String,
        val projectClaim: String?,
    )

    private companion object {
        const val INGEST_PATH = "/api/v1/traceability/facts:ingest"
        const val INGEST_SCOPE = "SCOPE_traceability:ingest"
        const val INGESTED_EVENT = "traceability.build-provenance.ingested"
        const val REPOSITORY = "owner/repository"
        const val SOURCE_REVISION = "0123456789abcdef0123456789abcdef01234567"
        const val PROOF_REFERENCE =
            "https://github.com/owner/repository/actions/runs/33705417856/attempts/1"
        const val PROOF_REFERENCE_ATTEMPT_2 =
            "https://github.com/owner/repository/actions/runs/33705417856/attempts/2"
        val PROJECT_TABLES = setOf(
            "source_commit",
            "build_record",
            "traceability_edge_identity",
            "issue_commit_edge_revision",
            "commit_build_edge_revision",
            "build_artifact_edge_revision",
            "build_provenance_receipt",
            "release_issue_snapshot",
            "audit_event",
        )
    }
}

class BuildProvenancePayloadLimitFilterTest {
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    @Test
    fun `exact ingestion post rejects the first byte beyond the hard limit without echoing content`() {
        val properties = BuildProvenanceIngestionProperties(enabled = true, maxPayloadBytes = 16)
        val filter = BuildProvenancePayloadLimitFilter(properties, ProblemWriter(objectMapper))
        val request = request("0123456789secret!")
        val response = MockHttpServletResponse()
        val continued = AtomicBoolean()

        filter.doFilter(request, response) { _, _ -> continued.set(true) }

        assertThat(response.status).isEqualTo(413)
        assertThat(response.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        assertThat(response.contentAsString).contains("PAYLOAD_TOO_LARGE").doesNotContain("secret")
        assertThat(continued).isFalse()
    }

    @Test
    fun `payload at the configured limit reaches the controller with identical bytes`() {
        val bytes = "0123456789abcdef".toByteArray()
        val properties = BuildProvenanceIngestionProperties(enabled = true, maxPayloadBytes = bytes.size)
        val filter = BuildProvenancePayloadLimitFilter(properties, ProblemWriter(objectMapper))
        val request = request(String(bytes))
        val response = MockHttpServletResponse()
        var observed = ByteArray(0)

        filter.doFilter(request, response) { wrapped, _ -> observed = wrapped.inputStream.readAllBytes() }

        assertThat(observed).containsExactly(*bytes)
        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `non-target requests are not buffered or limited`() {
        val properties = BuildProvenanceIngestionProperties(enabled = true, maxPayloadBytes = 1)
        val filter = BuildProvenancePayloadLimitFilter(properties, ProblemWriter(objectMapper))
        val request = request("unbounded", path = "/api/v1/releases")
        val response = MockHttpServletResponse()
        val continued = AtomicBoolean()

        filter.doFilter(request, response) { _, _ -> continued.set(true) }

        assertThat(continued).isTrue()
    }

    @Test
    fun `matrix parameters cannot bypass the ingestion payload limit`() {
        val filter = BuildProvenancePayloadLimitFilter(
            BuildProvenanceIngestionProperties(enabled = true, maxPayloadBytes = 4),
            ProblemWriter(objectMapper),
        )
        val response = MockHttpServletResponse()
        val continued = AtomicBoolean()

        filter.doFilter(request("oversized", path = "/api/v1/traceability/facts:ingest;x=1"), response) { _, _ ->
            continued.set(true)
        }

        assertThat(response.status).isEqualTo(413)
        assertThat(continued).isFalse()
    }

    @Test
    fun `context path cannot bypass the ingestion payload limit`() {
        val filter = BuildProvenancePayloadLimitFilter(
            BuildProvenanceIngestionProperties(enabled = true, maxPayloadBytes = 4),
            ProblemWriter(objectMapper),
        )
        val response = MockHttpServletResponse()
        val continued = AtomicBoolean()
        val request = request("oversized", path = "/quality/api/v1/traceability/facts:ingest").apply {
            contextPath = "/quality"
        }

        filter.doFilter(request, response) { _, _ -> continued.set(true) }

        assertThat(response.status).isEqualTo(413)
        assertThat(continued).isFalse()
    }

    @Test
    fun `configuration rejects zero and values above the absolute 262144 byte cap`() {
        assertThatThrownBy { BuildProvenanceIngestionProperties(maxPayloadBytes = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("TRACEABILITY_MAX_PAYLOAD_BYTES_INVALID")
        assertThatThrownBy { BuildProvenanceIngestionProperties(maxPayloadBytes = 262_145) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("TRACEABILITY_MAX_PAYLOAD_BYTES_INVALID")
        assertThat(BuildProvenanceIngestionProperties().maxPayloadBytes).isEqualTo(262_144)
        assertThat(BuildProvenanceIngestionProperties().enabled).isFalse()
    }

    private fun request(body: String, path: String = "/api/v1/traceability/facts:ingest") =
        MockHttpServletRequest(HttpMethod.POST.name(), path).apply {
            setContent(body.toByteArray())
            setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "req_payload_limit")
        }
}

class BuildProvenanceRequestDecodingTest {
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    @Test
    fun `request decoder rejects scalar coercion and null array entries`() {
        val valid = objectMapper.readTree(
            """
            {
              "schemaVersion":2,
              "project":"project",
              "releaseIssueSnapshotId":"ris_1",
              "provider":"GITHUB_ACTIONS",
              "repository":"owner/repository",
              "sourceRevision":"0123456789abcdef0123456789abcdef01234567",
              "pipeline":"pipeline",
              "buildId":"1",
              "buildAttempt":1,
              "workflowReference":"workflow",
              "proofReference":"proof",
              "proofDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "sourceIssueIds":["ISSUE-1"],
              "artifactSha256s":["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]
            }
            """.trimIndent(),
        ) as ObjectNode
        val malformed = listOf(
            valid.deepCopy().put("project", 7),
            valid.deepCopy().put("sourceRevision", false),
            valid.deepCopy().put("buildAttempt", "1"),
            valid.deepCopy().put("buildAttempt", 1.5),
            valid.deepCopy().also { it.withArray("sourceIssueIds").removeAll().addNull() },
            valid.deepCopy().also { it.withArray("artifactSha256s").removeAll().addNull() },
        )

        malformed.forEach { payload ->
            assertThatThrownBy {
                objectMapper.readValue(objectMapper.writeValueAsBytes(payload), BuildProvenanceRequest::class.java)
            }.isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException::class.java)
        }
    }
}

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "vsrqg.traceability.ingestion.enabled=false",
    ],
)
class BuildProvenanceDisabledIntegrationTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `disabled ingestion is hidden before project or persistence authority is consulted`() {
        mockMvc.post("/api/v1/traceability/facts:ingest") {
            with(
                jwt().jwt {
                    it.issuer(ISSUER).subject("service-disabled-feature")
                        .claim("principal_type", "SERVICE")
                        .claim("project", "hidden-project")
                }.authorities(SimpleGrantedAuthority("SCOPE_traceability:ingest")),
            )
            header("Idempotency-Key", "disabled-feature")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "schemaVersion":2,
                  "project":"hidden-project",
                  "releaseIssueSnapshotId":"ris_hidden",
                  "provider":"GITHUB_ACTIONS",
                  "repository":"owner/repository",
                  "sourceRevision":"0123456789abcdef0123456789abcdef01234567",
                  "pipeline":"pipeline",
                  "buildId":"1",
                  "buildAttempt":1,
                  "workflowReference":"owner/repository/.github/workflows/build.yml@refs/heads/main",
                  "proofReference":"https://github.com/owner/repository/actions/runs/1/attempts/1",
                  "proofDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "sourceIssueIds":["ISSUE-1"],
                  "artifactSha256s":["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]
                }
                """.trimIndent()
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RESOURCE_NOT_FOUND") }
        }
    }
}

internal data class BuildProvenanceTestFixture(
    val suffix: String,
    val projectId: String,
    val projectReference: String,
    val servicePrincipalId: String,
    val serviceSubject: String,
    val disabledServiceSubject: String,
    val unassignedServiceSubject: String,
    val userSubject: String,
    val snapshotId: String,
    val issueId: String,
    val artifactId: String,
    val artifactSha256: String,
) {
    fun envelope(
        objectMapper: ObjectMapper,
        buildAttempt: Int = 1,
        proofReference: String = DEFAULT_PROOF_REFERENCE,
        proofDigest: String = MATCHING_PROOF_DIGEST,
    ): ObjectNode = objectMapper.createObjectNode()
        .put("schemaVersion", 2)
        .put("project", projectReference)
        .put("releaseIssueSnapshotId", snapshotId)
        .put("provider", "GITHUB_ACTIONS")
        .put("repository", DEFAULT_REPOSITORY)
        .put("sourceRevision", DEFAULT_SOURCE_REVISION)
        .put("pipeline", DEFAULT_PIPELINE)
        .put("buildId", DEFAULT_BUILD_ID)
        .put("buildAttempt", buildAttempt)
        .put("workflowReference", DEFAULT_WORKFLOW_REFERENCE)
        .put("proofReference", proofReference)
        .put("proofDigest", proofDigest)
        .also { node ->
            node.putArray("sourceIssueIds").add("ISSUE-1")
            node.putArray("artifactSha256s").add(artifactSha256)
        }

    fun domainEnvelope(
        sourceRevision: String = DEFAULT_SOURCE_REVISION,
        proofReference: String = DEFAULT_PROOF_REFERENCE,
        proofDigest: String = MATCHING_PROOF_DIGEST,
    ) = BuildProvenanceEnvelope(
        schemaVersion = 2,
        projectReference = projectReference,
        releaseIssueSnapshotId = snapshotId,
        provider = ProvenanceProviderId("github-actions"),
        repository = DEFAULT_REPOSITORY,
        sourceRevision = sourceRevision,
        pipeline = DEFAULT_PIPELINE,
        buildId = DEFAULT_BUILD_ID,
        buildAttempt = 1,
        workflowReference = DEFAULT_WORKFLOW_REFERENCE,
        proofReference = proofReference,
        proofDigest = proofDigest,
        sourceIssueIds = listOf("ISSUE-1"),
        artifactSha256s = listOf(artifactSha256),
    )

    companion object {
        const val DEFAULT_REPOSITORY = "owner/repository"
        const val DEFAULT_SOURCE_REVISION = "0123456789abcdef0123456789abcdef01234567"
        const val DEFAULT_PIPELINE = "m1-backend"
        const val DEFAULT_BUILD_ID = "33705417856"
        const val DEFAULT_WORKFLOW_REFERENCE =
            "owner/repository/.github/workflows/m1-backend.yml@refs/heads/main"
        const val DEFAULT_PROOF_REFERENCE =
            "https://github.com/owner/repository/actions/runs/33705417856/attempts/1"
        const val MATCHING_PROOF_DIGEST =
            "sha256:3e455a4376effa929455a195ce1f3b71aa9865541c137ae84ac8fbd6641eb3a5"
    }
}

internal class BuildProvenanceFixtureSeeder(
    private val jdbc: JdbcClient,
    private val transactionTemplate: TransactionTemplate,
) {
    fun seed(artifactSha256: String? = null): BuildProvenanceTestFixture {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val resolvedArtifactSha256 = artifactSha256 ?: digestHex("artifact-$suffix")
        require(resolvedArtifactSha256.matches(Regex("^[0-9a-f]{64}$")))
        val fixture = BuildProvenanceTestFixture(
            suffix = suffix,
            projectId = "prj_ing_$suffix",
            projectReference = "ing-$suffix",
            servicePrincipalId = "svc_ing_$suffix",
            serviceSubject = "service-$suffix",
            disabledServiceSubject = "disabled-$suffix",
            unassignedServiceSubject = "unassigned-$suffix",
            userSubject = "user-$suffix",
            snapshotId = "ris_ing_$suffix",
            issueId = "iss_ing_$suffix",
            artifactId = "art_ing_$suffix",
            artifactSha256 = resolvedArtifactSha256,
        )
        transactionTemplate.executeWithoutResult {
            insertProjectAndPrincipals(fixture)
            insertIssueSnapshot(fixture)
            insertArtifact(fixture)
        }
        return fixture
    }

    fun addDuplicateChecksumArtifact(fixture: BuildProvenanceTestFixture) {
        transactionTemplate.executeWithoutResult {
            val manifestId = "mfd_ing_${fixture.suffix}"
            val artifactId = "afd_ing_${fixture.suffix}"
            jdbc.sql(
                """
                INSERT INTO manifest_revision(
                  id, release_id, revision, content_digest, raw_manifest, canonical_bytes,
                  schema_version, state, created_at, updated_at
                ) VALUES (
                  :id, :releaseId, 2, :digest, '{}'::jsonb, decode('00', 'hex'),
                  'manifest/v1', 'DRAFT', :now, :now
                )
                """.trimIndent(),
            ).param("id", manifestId).param("releaseId", "rel_ing_${fixture.suffix}")
                .param("digest", prefixedDigest("manifest-$manifestId"))
                .param("now", timestamp()).update()
            jdbc.sql(
                """
                INSERT INTO artifact(
                  id, identity_digest, artifact_type, locator, checksum_algorithm, checksum_value, created_at
                ) VALUES (:id, :identityDigest, 'APK', '{}'::jsonb, 'SHA-256', :checksum, :now)
                """.trimIndent(),
            ).param("id", artifactId).param("identityDigest", prefixedDigest("identity-$artifactId"))
                .param("checksum", fixture.artifactSha256).param("now", timestamp()).update()
            jdbc.sql(
                """
                INSERT INTO manifest_artifact(manifest_id, artifact_id, ordinal, required, created_at)
                VALUES (:manifestId, :artifactId, 0, true, :now)
                """.trimIndent(),
            ).param("manifestId", manifestId).param("artifactId", artifactId)
                .param("now", timestamp()).update()
            jdbc.sql(
                """
                UPDATE manifest_revision
                SET state = 'REGISTERED', row_version = row_version + 1, updated_at = :now
                WHERE id = :manifestId AND state = 'DRAFT'
                """.trimIndent(),
            ).param("manifestId", manifestId).param("now", timestamp()).update()
        }
    }

    private fun insertProjectAndPrincipals(fixture: BuildProvenanceTestFixture) {
        jdbc.sql("INSERT INTO project(id, project_key, name, created_at) VALUES (:id, :key, :key, :now)")
            .param("id", fixture.projectId).param("key", fixture.projectReference).param("now", timestamp()).update()
        listOf(
            PrincipalFixture(fixture.servicePrincipalId, fixture.serviceSubject, "SERVICE", false, true),
            PrincipalFixture("svd_ing_${fixture.suffix}", fixture.disabledServiceSubject, "SERVICE", true, true),
            PrincipalFixture("svu_ing_${fixture.suffix}", fixture.unassignedServiceSubject, "SERVICE", false, false),
            PrincipalFixture("usr_ing_${fixture.suffix}", fixture.userSubject, "USER", false, true),
        ).forEach { principal ->
            jdbc.sql(
                """
                INSERT INTO principal(id, issuer, subject, principal_type, disabled, created_at)
                VALUES (:id, :issuer, :subject, :type, :disabled, :now)
                """.trimIndent(),
            ).param("id", principal.id).param("issuer", ISSUER).param("subject", principal.subject)
                .param("type", principal.type).param("disabled", principal.disabled).param("now", timestamp()).update()
            if (principal.assigned) {
                jdbc.sql(
                    """
                    INSERT INTO project_assignment(project_id, principal_id, role, created_at)
                    VALUES (:projectId, :principalId, 'ADMINISTRATOR', :now)
                    """.trimIndent(),
                ).param("projectId", fixture.projectId).param("principalId", principal.id)
                    .param("now", timestamp()).update()
            }
        }
    }

    private fun insertIssueSnapshot(fixture: BuildProvenanceTestFixture) {
        val sourceId = "src_ing_${fixture.suffix}"
        val releaseId = "rel_ing_${fixture.suffix}"
        val runId = "syn_ing_${fixture.suffix}"
        val factDigest = prefixedDigest("issue-${fixture.suffix}")
        jdbc.sql(
            """
            INSERT INTO issue_source(
              id, project_id, source_key, source_type, adapter_version, mapping_version, created_at, updated_at
            ) VALUES (:id, :projectId, :key, 'FIXTURE', 'fixture/v1', 'mapping/v1', :now, :now)
            """.trimIndent(),
        ).param("id", sourceId).param("projectId", fixture.projectId).param("key", "source-${fixture.suffix}")
            .param("now", timestamp()).update()
        jdbc.sql(
            """
            INSERT INTO normalized_issue(
              id, project_id, source_id, source_issue_id, title, severity, status,
              raw_status_token, canonical_source_token, raw_severity_token, mapping_warnings,
              source_version, source_reference, observed_at, mapping_version,
              fact_digest, fact_digest_version, created_at
            ) VALUES (
              :id, :projectId, :sourceId, 'ISSUE-1', 'Issue 1', 'MAJOR', 'OPEN',
              'open', 'FIXTURE', 'major', '', 'v1', 'fixture', :now, 'mapping/v1',
              :digest, 'normalized-issue-facts/v1', :now
            )
            """.trimIndent(),
        ).param("id", fixture.issueId).param("projectId", fixture.projectId).param("sourceId", sourceId)
            .param("digest", factDigest).param("now", timestamp()).update()
        jdbc.sql(
            """
            INSERT INTO release_record(
              id, project_id, vehicle, platform, system_version, build_id, status, created_at, updated_at
            ) VALUES (:id, :projectId, 'vehicle', 'platform', '1.0', :id, 'DRAFT', :now, :now)
            """.trimIndent(),
        ).param("id", releaseId).param("projectId", fixture.projectId).param("now", timestamp()).update()
        jdbc.sql(
            """
            INSERT INTO issue_sync_run(
              id, project_id, source_id, sync_run_id, status, source_watermark,
              adapter_version, mapping_version, result_set_mode, filter_reference,
              issue_count, completed_at, created_at
            ) VALUES (
              :id, :projectId, :sourceId, :id, 'SUCCEEDED', 'watermark',
              'fixture/v1', 'mapping/v1', 'FULL', 'all', 1, :now, :now
            )
            """.trimIndent(),
        ).param("id", runId).param("projectId", fixture.projectId).param("sourceId", sourceId)
            .param("now", timestamp()).update()
        jdbc.sql(
            """
            INSERT INTO release_issue_snapshot(
              id, project_id, release_id, sync_run_id, snapshot_version, filter_reference,
              source_id, source_watermark, adapter_version, mapping_version,
              canonicalization_version, age_policy_version, observed_count, tombstone_count,
              selected_count, content_digest, created_at
            ) VALUES (
              :id, :projectId, :releaseId, :runId, 1, 'all', :sourceId, 'watermark',
              'fixture/v1', 'mapping/v1', 'release-issue-snapshot-jcs/v1', 'issue-snapshot-age/v1',
              1, 0, 1, :digest, :now
            )
            """.trimIndent(),
        ).param("id", fixture.snapshotId).param("projectId", fixture.projectId).param("releaseId", releaseId)
            .param("runId", runId).param("sourceId", sourceId).param("digest", prefixedDigest("snapshot-$runId"))
            .param("now", timestamp()).update()
        jdbc.sql(
            """
            INSERT INTO release_issue_snapshot_item(
              snapshot_id, ordinal, project_id, issue_id, source_issue_id, title,
              severity, status, source_version, source_reference, observed_at,
              mapping_version, fact_digest, created_at
            ) VALUES (
              :snapshotId, 0, :projectId, :issueId, 'ISSUE-1', 'Issue 1',
              'MAJOR', 'OPEN', 'v1', 'fixture', :now, 'mapping/v1', :digest, :now
            )
            """.trimIndent(),
        ).param("snapshotId", fixture.snapshotId).param("projectId", fixture.projectId)
            .param("issueId", fixture.issueId).param("digest", factDigest).param("now", timestamp()).update()
    }

    private fun insertArtifact(fixture: BuildProvenanceTestFixture) {
        val releaseId = "rel_ing_${fixture.suffix}"
        val manifestId = "mft_ing_${fixture.suffix}"
        jdbc.sql(
            """
            INSERT INTO manifest_revision(
              id, release_id, revision, content_digest, raw_manifest, canonical_bytes,
              schema_version, state, created_at, updated_at
            ) VALUES (
              :id, :releaseId, 1, :digest, '{}'::jsonb, decode('00', 'hex'),
              'manifest/v1', 'DRAFT', :now, :now
            )
            """.trimIndent(),
        ).param("id", manifestId).param("releaseId", releaseId)
            .param("digest", prefixedDigest("manifest-$manifestId")).param("now", timestamp()).update()
        jdbc.sql(
            """
            INSERT INTO artifact(
              id, identity_digest, artifact_type, locator, checksum_algorithm, checksum_value, created_at
            ) VALUES (:id, :identityDigest, 'APK', '{}'::jsonb, 'SHA-256', :checksum, :now)
            """.trimIndent(),
        ).param("id", fixture.artifactId).param("identityDigest", prefixedDigest("identity-${fixture.artifactId}"))
            .param("checksum", fixture.artifactSha256).param("now", timestamp()).update()
        jdbc.sql(
            """
            INSERT INTO manifest_artifact(manifest_id, artifact_id, ordinal, required, created_at)
            VALUES (:manifestId, :artifactId, 0, true, :now)
            """.trimIndent(),
        ).param("manifestId", manifestId).param("artifactId", fixture.artifactId)
            .param("now", timestamp()).update()
        jdbc.sql(
            """
            UPDATE manifest_revision
            SET state = 'REGISTERED', row_version = row_version + 1, updated_at = :now
            WHERE id = :manifestId AND state = 'DRAFT'
            """.trimIndent(),
        ).param("manifestId", manifestId).param("now", timestamp()).update()
    }

    private fun timestamp() = NOW.atOffset(ZoneOffset.UTC)

    private data class PrincipalFixture(
        val id: String,
        val subject: String,
        val type: String,
        val disabled: Boolean,
        val assigned: Boolean,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-03T10:15:30Z")
    }
}

internal const val ISSUER = "https://idp.vsrqg.test"

internal fun prefixedDigest(value: String): String = "sha256:${digestHex(value)}"

internal fun digestHex(value: String): String = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()),
)
