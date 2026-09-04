package com.ricezhou.vsrqg.shared

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotencyConflict
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.IllegalTransactionStateException

class IdempotencyIntegrationTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var executor: IdempotentExecutor

    @Autowired
    private lateinit var governanceStore: GovernanceStore

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val principalId = "principal_idempotency"
    private val projectId = "project_idempotency"

    @BeforeEach
    fun setUpAuthorityFixtures() {
        jdbc.sql(
            "INSERT INTO project(id, project_key, name, created_at) VALUES (:id, :key, :key, now()) " +
                "ON CONFLICT (id) DO NOTHING",
        )
            .param("id", projectId)
            .param("key", projectId)
            .update()
        jdbc.sql(
            "INSERT INTO principal(id, issuer, subject, principal_type, disabled, created_at) " +
                "VALUES (:id, :issuer, :subject, 'USER', false, now()) ON CONFLICT (id) DO NOTHING",
        )
            .param("id", principalId)
            .param("issuer", "https://idp.vsrqg.test")
            .param("subject", principalId)
            .update()
    }

    @Test
    fun `same key and digest executes once while different digest conflicts`() {
        val executions = AtomicInteger()
        val digestA = digest('a')

        val results = runConcurrently(2) {
            executor.execute(
                scope = "release:create",
                principalId = principalId,
                key = "key-1",
                requestDigest = digestA,
                responseType = String::class.java,
            ) {
                executions.incrementAndGet()
                "created"
            }
        }

        assertThat(results).containsOnly("created")
        assertThat(executions).hasValue(1)
        assertThatThrownBy {
            executor.execute(
                "release:create",
                principalId,
                "key-1",
                digest('b'),
                String::class.java,
            ) { "changed" }
        }.isInstanceOf(IdempotencyConflict::class.java)
    }

    @Test
    fun `audit outbox and idempotency roll back with failed business action`() {
        assertThatThrownBy {
            executor.execute(
                "release:create",
                principalId,
                "rollback-key",
                digest('c'),
                String::class.java,
            ) {
                governanceStore.appendAudit(
                    projectId = projectId,
                    actorId = principalId,
                    action = "RELEASE_CREATE_ATTEMPTED",
                    resourceType = "RELEASE",
                    resourceId = "release_rollback",
                    requestId = "request_rollback",
                    reason = "integration-test",
                )
                governanceStore.appendOutbox(
                    eventType = "release.created",
                    aggregateType = "RELEASE",
                    aggregateId = "release_rollback",
                    payload = objectMapper.createObjectNode().put("releaseId", "release_rollback"),
                )
                throw TestFailure()
            }
        }.isInstanceOf(TestFailure::class.java)

        assertThat(countWhere("idempotency_record", "idempotency_key", "rollback-key")).isZero()
        assertThat(countWhere("audit_event", "aggregate_id", "release_rollback")).isZero()
        assertThat(countWhere("outbox_event", "aggregate_id", "release_rollback")).isZero()
    }

    @Test
    fun `successful business action commits audit outbox and replay record together`() {
        val response = executor.execute(
            "release:create",
            principalId,
            "commit-key",
            digest('d'),
            String::class.java,
        ) {
            governanceStore.appendAudit(
                projectId = projectId,
                actorId = principalId,
                action = "RELEASE_CREATED",
                resourceType = "RELEASE",
                resourceId = "release_commit",
                requestId = "request_commit",
                reason = "accepted",
            )
            governanceStore.appendOutbox(
                eventType = "release.created",
                aggregateType = "RELEASE",
                aggregateId = "release_commit",
                payload = objectMapper.createObjectNode().put("releaseId", "release_commit"),
            )
            "created"
        }

        assertThat(response).isEqualTo("created")
        assertThat(countWhere("idempotency_record", "idempotency_key", "commit-key")).isOne()
        assertThat(countWhere("audit_event", "aggregate_id", "release_commit")).isOne()
        assertThat(countWhere("outbox_event", "aggregate_id", "release_commit")).isOne()
        assertThat(
            jdbc.sql("SELECT reason FROM audit_event WHERE aggregate_id = 'release_commit'")
                .query(String::class.java)
                .single(),
        ).isEqualTo("accepted")
    }

    @Test
    fun `governance writes require an owning business transaction`() {
        assertThatThrownBy {
            governanceStore.appendOutbox(
                eventType = "release.created",
                aggregateType = "RELEASE",
                aggregateId = "release_without_transaction",
                payload = objectMapper.createObjectNode(),
            )
        }.isInstanceOf(IllegalTransactionStateException::class.java)
    }

    private fun countWhere(table: String, column: String, value: String): Int = jdbc
        .sql("SELECT count(*) FROM $table WHERE $column = :value")
        .param("value", value)
        .query(Int::class.java)
        .single()

    private fun digest(character: Char): String = "sha256:" + character.toString().repeat(64)

    private class TestFailure : RuntimeException()
}
