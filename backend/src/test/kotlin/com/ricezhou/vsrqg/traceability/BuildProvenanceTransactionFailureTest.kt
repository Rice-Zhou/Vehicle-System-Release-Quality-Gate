package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import com.ricezhou.vsrqg.shared.problem.ProblemHandler
import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceConflict
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceResult
import com.ricezhou.vsrqg.traceability.application.IngestBuildProvenance
import com.ricezhou.vsrqg.traceability.application.IngestBuildProvenanceCommand
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceTransaction
import com.ricezhou.vsrqg.traceability.application.EdgeRevisionRecord
import com.ricezhou.vsrqg.traceability.adapter.JdbcBuildProvenanceConflictRecorder
import com.ricezhou.vsrqg.traceability.domain.Confidence
import com.ricezhou.vsrqg.traceability.domain.TraceabilityEdgeType
import com.ricezhou.vsrqg.traceability.domain.VerificationStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

class BuildProvenanceTransactionStructureTest {
    @Test
    fun `facade transaction and conflict recorder are separate proxy boundaries`() {
        val facadeMethod = IngestBuildProvenance::class.java.getDeclaredMethod(
            "ingest",
            IngestBuildProvenanceCommand::class.java,
        )
        val transactionMethod = BuildProvenanceTransaction::class.java.getDeclaredMethod(
            "execute",
            com.ricezhou.vsrqg.traceability.application.PreparedBuildProvenance::class.java,
        )
        val recorderMethod = JdbcBuildProvenanceConflictRecorder::class.java.declaredMethods
            .single { it.name == "record" }

        assertThat(facadeMethod.getAnnotation(Transactional::class.java)).isNull()
        assertThat(transactionMethod.getAnnotation(Transactional::class.java))
            .extracting(Transactional::propagation)
            .isEqualTo(Propagation.REQUIRED)
        assertThat(recorderMethod.getAnnotation(Transactional::class.java))
            .extracting(Transactional::propagation)
            .isEqualTo(Propagation.REQUIRES_NEW)
        assertThat(BuildProvenanceTransaction::class.java)
            .isNotEqualTo(JdbcBuildProvenanceConflictRecorder::class.java)
    }

    @Test
    fun `ingestion result is replayable through the shared idempotency JSON codec`() {
        val result = BuildProvenanceResult(
            receiptId = "bpr_replay",
            releaseIssueSnapshotId = "ris_replay",
            sourceCommitId = "cmt_replay",
            buildRecordId = "bld_replay",
            envelopeDigest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            validatorVersion = "github-actions-provenance/v1",
            verificationStatus = VerificationStatus.VALID,
            confidence = Confidence.MEDIUM,
            edgeRevisions = listOf(
                EdgeRevisionRecord(
                    edgeId = "edg_replay",
                    edgeType = TraceabilityEdgeType.COMMIT_BUILD,
                    revisionId = "rev_replay",
                    revision = 1,
                    verificationStatus = VerificationStatus.VALID,
                    confidence = Confidence.MEDIUM,
                    factDigest = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                ),
            ),
        )
        val mapper = ObjectMapper().findAndRegisterModules()

        val replay = mapper.readValue(mapper.writeValueAsBytes(result), BuildProvenanceResult::class.java)

        assertThat(replay).isEqualTo(result)
    }

    @Test
    fun `persistence taxonomy exposes retry only for resource failures`() {
        val mapper = ObjectMapper().findAndRegisterModules()
        val handler = ProblemHandler(ProblemWriter(mapper))
        val request = MockHttpServletRequest("POST", "/api/v1/traceability/facts:ingest").apply {
            setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "req_taxonomy")
        }

        val unavailable = handler.persistenceUnavailable(
            DataAccessResourceFailureException("jdbc:postgresql://secret-host/database"),
            request,
        )
        val integrity = handler.persistenceIntegrityFailure(
            DataIntegrityViolationException("constraint sql and secret row"),
            request,
        )

        assertThat(unavailable.statusCode.value()).isEqualTo(503)
        assertThat(unavailable.body!!.code).isEqualTo("PERSISTENCE_UNAVAILABLE")
        assertThat(unavailable.body!!.detail).doesNotContain("secret", "jdbc", "sql")
        assertThat(integrity.statusCode.value()).isEqualTo(500)
        assertThat(integrity.body!!.code).isEqualTo("INTERNAL_ERROR")
        assertThat(integrity.body!!.detail).doesNotContain("secret", "constraint", "sql")
    }
}

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "vsrqg.traceability.ingestion.enabled=true",
    ],
)
class BuildProvenanceTransactionFailureTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var useCase: IngestBuildProvenance

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private lateinit var fixture: BuildProvenanceTestFixture

    @BeforeEach
    fun seedAuthority() {
        fixture = BuildProvenanceFixtureSeeder(jdbc, transactionTemplate).seed()
    }

    @AfterEach
    fun removeFailureTriggers() {
        InjectionPoint.entries.forEach { point ->
            jdbc.sql("DROP TRIGGER IF EXISTS reject_provenance_tx_test ON ${point.table}").update()
        }
        jdbc.sql("DROP FUNCTION IF EXISTS reject_provenance_tx_test()").update()
    }

    @Test
    fun `every persistence boundary failure rolls back domain receipt idempotency and governance writes`() {
        InjectionPoint.entries.forEachIndexed { index, point ->
            installFailureTrigger(point)
            val key = "rollback-$index-${fixture.suffix}"

            assertThatThrownBy { useCase.ingest(command(key)) }

            assertEmptyIngestion(key)
            jdbc.sql("DROP TRIGGER reject_provenance_tx_test ON ${point.table}").update()
        }
    }

    @Test
    fun `build attempt conflict rolls back the outer transaction before one rejected receipt is recorded`() {
        val acceptedKey = "accepted-${fixture.suffix}"
        val accepted = useCase.ingest(command(acceptedKey))
        val acceptedCounts = acceptedFactCounts()
        val rejectedEnvelope = fixture.domainEnvelope(sourceRevision = "b".repeat(40))
        val rejectedKey = "rejected-${fixture.suffix}"

        repeat(2) {
            assertThatThrownBy {
                useCase.ingest(command(rejectedKey, rejectedEnvelope))
            }.isInstanceOfSatisfying(BuildProvenanceConflict::class.java) { conflict ->
                assertThat(conflict.acceptedReceiptId).isEqualTo(accepted.receiptId)
                assertThat(conflict.rejectedEnvelopeDigest).isNotEqualTo(accepted.envelopeDigest)
            }
        }

        assertThat(acceptedFactCounts()).isEqualTo(acceptedCounts)
        assertThat(countProject("build_provenance_rejected_receipt")).isOne()
        assertThat(countProject("audit_event")).isEqualTo(2)
        assertThat(countOutbox()).isOne()
        assertThat(countIdempotency(acceptedKey)).isOne()
        assertThat(countIdempotency(rejectedKey)).isZero()

        val rejected = jdbc.sql(
            """
            SELECT rejected_envelope_digest, diagnostic_code
            FROM build_provenance_rejected_receipt
            WHERE project_id = :projectId
            """.trimIndent(),
        ).param("projectId", fixture.projectId)
            .query { rs, _ -> rs.getString("rejected_envelope_digest") to rs.getString("diagnostic_code") }
            .single()
        assertThat(rejected.first).matches("^sha256:[0-9a-f]{64}$")
        assertThat(rejected.second).isEqualTo("BUILD_PROVENANCE_CONFLICT")

        val auditText = jdbc.sql(
            "SELECT coalesce(before_state::text, '') || coalesce(after_state::text, '') " +
                "FROM audit_event WHERE project_id = :projectId AND action = 'BUILD_PROVENANCE_REJECTED'",
        ).param("projectId", fixture.projectId).query(String::class.java).single()
        assertThat(auditText)
            .contains("rejectedEnvelopeDigest", "acceptedReceiptId")
            .doesNotContain(
                fixture.serviceSubject,
                BuildProvenanceTestFixture.DEFAULT_SOURCE_REVISION,
                "b".repeat(40),
                BuildProvenanceTestFixture.DEFAULT_PROOF_REFERENCE,
            )
    }

    @Test
    fun `database integrity failure is mapped to a sanitized non retryable problem after rollback`() {
        installFailureTrigger(InjectionPoint.COMMIT)
        val key = "unavailable-${fixture.suffix}"

        val response = mockMvc.post("/api/v1/traceability/facts:ingest") {
            with(
                jwt().jwt {
                    it.issuer(ISSUER).subject(fixture.serviceSubject)
                        .claim("principal_type", "SERVICE")
                        .claim("project", fixture.projectReference)
                }.authorities(SimpleGrantedAuthority("SCOPE_traceability:ingest")),
            )
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsBytes(fixture.envelope(objectMapper))
        }.andExpect {
            status { isInternalServerError() }
            jsonPath("$.code") { value("INTERNAL_ERROR") }
        }.andReturn().response.contentAsString

        assertThat(response).doesNotContain(
            "injected provenance transaction failure",
            fixture.serviceSubject,
            BuildProvenanceTestFixture.DEFAULT_SOURCE_REVISION,
            BuildProvenanceTestFixture.DEFAULT_PROOF_REFERENCE,
            "stack",
        )
        assertEmptyIngestion(key)
    }

    private fun command(
        key: String,
        envelope: com.ricezhou.vsrqg.traceability.domain.BuildProvenanceEnvelope = fixture.domainEnvelope(),
    ) = IngestBuildProvenanceCommand(
        principal = Principal(ISSUER, fixture.serviceSubject, true),
        tokenProjectReference = fixture.projectReference,
        envelope = envelope,
        idempotencyKey = key,
        requestId = "req_$key",
    )

    private fun installFailureTrigger(point: InjectionPoint) {
        jdbc.sql(
            """CREATE OR REPLACE FUNCTION reject_provenance_tx_test() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
               BEGIN RAISE EXCEPTION 'injected provenance transaction failure'; END; ${'$'}${'$'}""",
        ).update()
        jdbc.sql(
            "CREATE TRIGGER reject_provenance_tx_test BEFORE ${point.operation} ON ${point.table} " +
                "FOR EACH ROW${point.whenClause} EXECUTE FUNCTION reject_provenance_tx_test()",
        ).update()
    }

    private fun assertEmptyIngestion(key: String) {
        assertThat(countProject("source_commit")).isZero()
        assertThat(countProject("build_record")).isZero()
        assertThat(countProject("traceability_edge_identity")).isZero()
        assertThat(countProject("build_provenance_receipt")).isZero()
        assertThat(countProject("build_provenance_rejected_receipt")).isZero()
        assertThat(countProject("audit_event")).isZero()
        assertThat(countOutbox()).isZero()
        assertThat(countIdempotency(key)).isZero()
    }

    private fun acceptedFactCounts(): List<Int> = listOf(
        countProject("source_commit"),
        countProject("build_record"),
        countProject("traceability_edge_identity"),
        countProject("issue_commit_edge_revision"),
        countProject("commit_build_edge_revision"),
        countProject("build_artifact_edge_revision"),
        countProject("build_provenance_receipt"),
    )

    private fun countProject(table: String): Int {
        require(table in PROJECT_TABLES)
        return jdbc.sql("SELECT count(*) FROM $table WHERE project_id = :projectId")
            .param("projectId", fixture.projectId).query(Int::class.java).single()
    }

    private fun countOutbox(): Int = jdbc.sql(
        "SELECT count(*) FROM outbox_event WHERE aggregate_id IN " +
            "(SELECT id FROM build_provenance_receipt WHERE project_id = :projectId)",
    ).param("projectId", fixture.projectId).query(Int::class.java).single()

    private fun countIdempotency(key: String): Int = jdbc.sql(
        "SELECT count(*) FROM idempotency_record WHERE principal_id = :principalId AND idempotency_key = :key",
    ).param("principalId", fixture.servicePrincipalId).param("key", key)
        .query(Int::class.java).single()

    private enum class InjectionPoint(
        val table: String,
        val operation: String = "INSERT",
        val whenClause: String = "",
    ) {
        COMMIT("source_commit"),
        BUILD("build_record"),
        SECOND_EDGE("traceability_edge_identity", whenClause = " WHEN (NEW.edge_type = 'COMMIT_BUILD')"),
        RECEIPT("build_provenance_receipt"),
        AUDIT("audit_event"),
        OUTBOX("outbox_event"),
        IDEMPOTENCY_RESPONSE(
            "idempotency_record",
            operation = "UPDATE",
            whenClause = " WHEN (NEW.response_status = 200)",
        ),
    }

    private companion object {
        val PROJECT_TABLES = setOf(
            "source_commit",
            "build_record",
            "traceability_edge_identity",
            "issue_commit_edge_revision",
            "commit_build_edge_revision",
            "build_artifact_edge_revision",
            "build_provenance_receipt",
            "build_provenance_rejected_receipt",
            "audit_event",
        )
    }
}
