package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.adapter.JwtPrincipalMapper
import com.ricezhou.vsrqg.access.adapter.SecurityConfig
import com.ricezhou.vsrqg.access.application.ProjectAuthorization
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.traceability.adapter.BuildProvenanceIngestionProperties
import com.ricezhou.vsrqg.traceability.adapter.TraceabilityVerificationController
import com.ricezhou.vsrqg.traceability.application.StartTraceabilityVerification
import com.ricezhou.vsrqg.traceability.application.StartTraceabilityVerificationCommand
import com.ricezhou.vsrqg.traceability.application.TraceabilityCanonicalizer
import com.ricezhou.vsrqg.traceability.application.TraceabilityInputRejected
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationAuthority
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationAccepted
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationPolicy
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRepository
import com.zaxxer.hikari.HikariConfig
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate

@TestPropertySource(
    properties = [
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=0",
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
        count(
            "outbox_event",
            "event_type = 'traceability.verification.queued' AND payload->>'releaseId' = :value",
            fixture.releaseId,
        ),
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
    IDEMPOTENCY_RESPONSE("idempotency_record", "UPDATE", " WHEN (NEW.response_status = 200)"),
}

@WebMvcTest(controllers = [TraceabilityVerificationController::class])
@Import(SecurityConfig::class, JwtPrincipalMapper::class, ProblemWriter::class)
@TestPropertySource(
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.vsrqg.test",
        "spring.security.oauth2.resourceserver.jwt.audiences[0]=vsrqg-api",
    ],
)
class TraceabilityVerificationStartHttpTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var useCase: StartTraceabilityVerification

    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @MockitoBean
    private lateinit var idGenerator: IdGenerator

    @MockitoBean
    private lateinit var ingestionProperties: BuildProvenanceIngestionProperties

    @BeforeEach
    fun requestIds() {
        doReturn("req_traceability_http").`when`(idGenerator).nextId("req_")
    }

    @Test
    fun `verification post redacts persistence outage as fixed retryable 503`() {
        doThrow(DataAccessResourceFailureException("jdbc:postgresql://secret-host/secret-database"))
            .`when`(useCase).start(anyObject())

        val response = post(releaseId = "rel_http").andExpect {
            status { isServiceUnavailable() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("PERSISTENCE_UNAVAILABLE") }
            jsonPath("$.requestId") { value("req_traceability_http") }
            jsonPath("$.detail") { value("The request could not be persisted; retry with the same idempotency key") }
        }.andReturn().response.contentAsString

        assertThat(response).doesNotContain("secret-host", "secret-database", "jdbc:postgresql")
    }

    @Test
    fun `verification post accepts the contract maximum release id and rejects the first excess character`() {
        val maximumReleaseId = "r".repeat(128)
        doReturn(
            TraceabilityVerificationAccepted(
                verificationRunId = "trv_http",
                releaseId = maximumReleaseId,
                issueSnapshotId = "ris_http",
                inputDigest = "sha256:" + "a".repeat(64),
                statusUrl = "/api/v1/traceability-verification-runs/trv_http",
            ),
        ).`when`(useCase).start(anyObject())

        post(releaseId = maximumReleaseId).andExpect { status { isAccepted() } }
        post(releaseId = "r".repeat(129)).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    private fun post(releaseId: String) = mockMvc.post(
        "/api/v1/releases/{releaseId}/traceability:verify",
        releaseId,
    ) {
        with(
            jwt().jwt {
                it.issuer(ISSUER).subject("traceability-http-user").claim("principal_type", "USER")
            }.authorities(SimpleGrantedAuthority("SCOPE_traceability:verify")),
        )
        header("Idempotency-Key", "idem-http")
        contentType = MediaType.APPLICATION_JSON
        content = """{"sourceId":"src_http"}"""
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> anyObject(): T {
    any<T>()
    return null as T
}

class TraceabilityVerificationAuthorityValidationTest {
    @Test
    fun `unsupported latest issue snapshot canonicalization is rejected before digest or persistence`() {
        val repository = mock(TraceabilityVerificationRepository::class.java)
        doReturn("prj_authority").`when`(repository).findProjectId("rel_authority", "src_authority")
        doReturn(
            TraceabilityVerificationAuthority(
                projectId = "prj_authority",
                releaseId = "rel_authority",
                manifestRevisionId = "mft_authority",
                manifestDigest = "sha256:" + "a".repeat(64),
                manifestState = "LOCKED",
                issueSnapshotId = "ris_unsupported",
                issueSnapshotDigest = "sha256:" + "b".repeat(64),
                issueSnapshotCanonicalizationVersion = "release-issue-snapshot-jcs/v2-unsupported",
                declaredIssueCount = 0,
                issues = emptyList(),
                edges = emptyList(),
            ),
        ).`when`(repository).lockAndLoadAuthority("rel_authority", "src_authority", 21, 2_001)
        val useCase = StartTraceabilityVerification(
            policy = TraceabilityVerificationPolicy(
                enabled = true,
                policyVersion = "m2.5-traceability-policy/v1",
                validatorVersion = "m2.5-path-validator/v1",
                maxIssues = 20,
                maxEdgeRevisions = 2_000,
            ),
            authorizer = ProjectAuthorizer { _, _, _ -> ProjectAuthorization("usr_authority") },
            idempotentExecutor = ImmediateIdempotentExecutor,
            repository = repository,
            canonicalizer = mock(TraceabilityCanonicalizer::class.java),
            governanceStore = mock(GovernanceStore::class.java),
            idGenerator = IdGenerator { "${it}authority" },
            timeProvider = TimeProvider { Instant.parse("2026-09-04T00:00:00Z") },
            objectMapper = ObjectMapper(),
        )

        assertThatThrownBy {
            useCase.start(
                StartTraceabilityVerificationCommand(
                    principal = Principal("https://idp.vsrqg.test", "authority-user", false),
                    releaseId = "rel_authority",
                    issueSourceId = "src_authority",
                    idempotencyKey = "authority-key",
                    requestDigest = "sha256:" + "c".repeat(64),
                    requestId = "req_authority",
                ),
            )
        }.isInstanceOf(TraceabilityInputRejected::class.java)
            .extracting("code")
            .isEqualTo("TRACEABILITY_INPUT_NOT_VALID")
    }
}

private object ImmediateIdempotentExecutor : IdempotentExecutor {
    override fun <T : Any> execute(
        scope: String,
        principalId: String,
        key: String,
        requestDigest: String,
        responseType: Class<T>,
        action: () -> T,
    ): T = action()
}

class TraceabilityVerificationStartPoolBudgetTest {
    @Test
    fun `postgres contexts bind a local non-retaining two-connection pool budget`() {
        listOf(
            TraceabilityVerificationStartIntegrationTest::class.java,
            TraceabilityVerificationStartFailureTest::class.java,
        ).forEach { testClass ->
            val configuration = poolConfiguration(testClass)

            assertThat(configuration.maximumPoolSize)
                .describedAs("%s maximum pool size", testClass.simpleName)
                .isEqualTo(2)
            assertThat(configuration.minimumIdle)
                .describedAs("%s minimum idle", testClass.simpleName)
                .isZero()
        }
    }

    private fun poolConfiguration(testClass: Class<*>): HikariConfig {
        val annotation = requireNotNull(
            AnnotatedElementUtils.findMergedAnnotation(testClass, TestPropertySource::class.java),
        )
        val properties = annotation.properties.associate { property ->
            property.substringBefore('=') to property.substringAfter('=')
        }
        return HikariConfig().also { configuration ->
            Binder(MapConfigurationPropertySource(properties)).bind(
                "spring.datasource.hikari",
                Bindable.ofInstance(configuration),
            )
        }
    }
}
