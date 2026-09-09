package com.ricezhou.vsrqg.testmanagement

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@AutoConfigureMockMvc
@TestPropertySource(properties = ["vsrqg.demo.agent-registration.enabled=true"])
@Timeout(60)
class AgentRegistrationConcurrencyIntegrationTest : PostgresIntegrationTest() {
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var transactions: PlatformTransactionManager
    @Autowired lateinit var mapper: ObjectMapper

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `concurrent registration serializes before idempotency and metadata writes`(sameKey: Boolean) {
        val fixture = AgentIdentityFixture(jdbc, TestAgentCertificates.trustedCertificate)
        val label = "agent-register-${UUID.randomUUID()}"
        val keys = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
            .let { if (sameKey) listOf(it.first(), it.first()) else it }
        val auditBefore = auditCount(fixture.agentId)
        dataSource.connection.use { blocker ->
            blocker.autoCommit = false
            // Stop idempotency INSERT after authorization, without adding a production timing hook.
            blocker.createStatement().use { it.execute("LOCK TABLE idempotency_record IN SHARE MODE") }
            Executors.newFixedThreadPool(2).use { workers ->
                val requests = keys.map { key ->
                    workers.submit<org.springframework.mock.web.MockHttpServletResponse> {
                        TransactionTemplate(transactions).execute {
                            // Label only these two real transactions; the production handler joins this transaction.
                            jdbc.sql("SELECT set_config('application_name', :label, true)")
                                .param("label", label).query(String::class.java).single()
                            jdbc.sql("SELECT set_config('lock_timeout', '10s', true)").query(String::class.java).single()
                            mvc.post("/agent-api/v1/agents:register") {
                                requestAttr("jakarta.servlet.request.X509Certificate", arrayOf(TestAgentCertificates.trustedCertificate))
                                header("Idempotency-Key", key)
                                contentType = MediaType.APPLICATION_JSON
                                content = fixture.body()
                            }.andReturn().response
                        }!!
                    }
                }
                try {
                    awaitBothLockWaiters(blocker, label)
                } finally {
                    // Old SHARE authorization lets both requests hold Agent locks before either INSERT.
                    // Exclusive Agent authorization queues the second request before it can hold that lock.
                    blocker.rollback()
                }
                val responses = requests.map { it.get(15, TimeUnit.SECONDS) }
                responses.forEach {
                    assertThat(it.status).isEqualTo(200)
                    assertThat(mapper.readTree(it.contentAsString).path("agentId").asText()).isEqualTo(fixture.agentId)
                }
                assertThat(responses[0].contentAsString).isEqualTo(responses[1].contentAsString)
            }
        }
        val expectedWrites = if (sameKey) 1 else 2
        assertThat(auditCount(fixture.agentId) - auditBefore).isEqualTo(expectedWrites)
        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_record WHERE scope = :scope AND principal_id = :principal AND idempotency_key IN (:keys)")
            .param("scope", "agent:register:${fixture.agentId}").param("principal", fixture.principalId)
            .param("keys", keys.distinct()).query(Int::class.java).single()).isEqualTo(expectedWrites)
    }

    private fun awaitBothLockWaiters(blocker: Connection, label: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            blocker.createStatement().use { it.execute("SELECT pg_stat_clear_snapshot()") }
            val waiting = blocker.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock'").use {
                it.setString(1, label)
                it.executeQuery().use { row -> row.next(); row.getInt(1) }
            }
            if (waiting == 2) return
            Thread.sleep(10)
        }
        error("Both registration transactions did not reach the controlled lock contention")
    }

    private fun auditCount(agentId: String) = jdbc.sql("SELECT count(*) FROM audit_event WHERE aggregate_id = :id AND action = 'AGENT_REGISTERED'")
        .param("id", agentId).query(Int::class.java).single()
}
