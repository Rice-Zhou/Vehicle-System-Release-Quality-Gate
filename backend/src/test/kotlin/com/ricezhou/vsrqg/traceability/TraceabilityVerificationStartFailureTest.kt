package com.ricezhou.vsrqg.traceability

import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import com.ricezhou.vsrqg.traceability.application.StartTraceabilityVerification
import com.ricezhou.vsrqg.traceability.application.StartTraceabilityVerificationCommand
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionTemplate

@TestPropertySource(
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.vsrqg.test",
        "spring.security.oauth2.resourceserver.jwt.audiences[0]=vsrqg-api",
        "vsrqg.traceability.verification.enabled=true",
    ],
)
class TraceabilityVerificationStartFailureTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var useCase: StartTraceabilityVerification

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private lateinit var fixture: TraceabilityVerificationStartFixture

    @BeforeEach
    fun seedAuthority() {
        fixture = TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate).seed()
    }

    @AfterEach
    fun removeFailureInjection() {
        StartWriteBoundary.entries.forEach { boundary ->
            jdbc.sql("DROP TRIGGER IF EXISTS reject_traceability_start_test ON ${boundary.table}").update()
        }
        jdbc.sql("DROP FUNCTION IF EXISTS reject_traceability_start_test() ").update()
    }

    @ParameterizedTest
    @EnumSource(StartWriteBoundary::class)
    fun `failure rolls back every creation artifact`(boundary: StartWriteBoundary) {
        installFailure(boundary)
        val key = "rollback-${boundary.name.lowercase()}-${fixture.suffix}"

        assertThatThrownBy { useCase.start(command(key)) }

        assertThat(artifactCounts(key)).containsOnly(0)
    }

    private fun installFailure(boundary: StartWriteBoundary) {
        jdbc.sql(
            """CREATE OR REPLACE FUNCTION reject_traceability_start_test() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
               BEGIN RAISE EXCEPTION 'injected traceability start failure'; END; ${'$'}${'$'}""",
        ).update()
        jdbc.sql(
            "CREATE TRIGGER reject_traceability_start_test BEFORE ${boundary.operation} ON ${boundary.table} " +
                "FOR EACH ROW${boundary.whenClause} EXECUTE FUNCTION reject_traceability_start_test()",
        ).update()
    }

    private fun command(key: String) = StartTraceabilityVerificationCommand(
        principal = Principal(ISSUER, fixture.userSubject, true),
        releaseId = fixture.releaseId,
        issueSourceId = fixture.sourceId,
        idempotencyKey = key,
        requestDigest = TraceabilityVerificationStartFixtureSeeder.requestDigest(fixture.releaseId, fixture.sourceId),
        requestId = "req_$key",
    )

    private fun artifactCounts(key: String): List<Int> = listOf(
        count("idempotency_record", "idempotency_key = :value", key),
        count("traceability_verification_run", "project_id = :value AND issue_snapshot_id IS NOT NULL", fixture.projectId),
        count(
            "traceability_verification_run_edge_input",
            "verification_run_id IN (SELECT id FROM traceability_verification_run WHERE project_id = :value)",
            fixture.projectId,
        ),
        count("audit_event", "project_id = :value AND action = 'TRACEABILITY_VERIFICATION_QUEUED'", fixture.projectId),
        count("outbox_event", "event_type = 'traceability.verification.queued' AND aggregate_id LIKE :value", "trv_%"),
        count("background_job", "project_id = :value AND job_type = 'TRACEABILITY_VERIFY'", fixture.projectId),
    )

    private fun count(table: String, predicate: String, value: String): Int {
        require(table in StartWriteBoundary.entries.map(StartWriteBoundary::table).toSet())
        return jdbc.sql("SELECT count(*) FROM $table WHERE $predicate")
            .param("value", value).query(Int::class.java).single()
    }
}

enum class StartWriteBoundary(
    val table: String,
    val operation: String = "INSERT",
    val whenClause: String = "",
) {
    IDEMPOTENCY("idempotency_record"),
    RUN("traceability_verification_run"),
    INPUT_LEDGER("traceability_verification_run_edge_input"),
    AUDIT("audit_event"),
    OUTBOX("outbox_event"),
    JOB("background_job"),
}
