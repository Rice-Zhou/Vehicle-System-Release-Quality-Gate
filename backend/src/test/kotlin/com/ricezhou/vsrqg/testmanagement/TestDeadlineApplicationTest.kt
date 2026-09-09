package com.ricezhou.vsrqg.testmanagement

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.testmanagement.application.*
import com.ricezhou.vsrqg.testmanagement.domain.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.*
import java.time.Instant

@Timeout(60)
class TestDeadlineApplicationTest {
    private val mapper=jacksonObjectMapper()
    private val repository=mock(TestRunRepository::class.java)
    private val governance=mock(GovernanceStore::class.java)
    private val access=AgentAccess { _,_ -> AgentActor("svc_test","prj_test","agt_test","dev_test") }
    private val lifecycle=TestRunLifecycle(repository,governance,mapper,access)
    private val start=Instant.parse("2026-09-09T00:00:00Z")
    private val run=RunRecord("run_test","rel_test","prj_test","agt_test","dev_test","usr_test",RunState.RUNNING,
        start.plusSeconds(60),start.plusSeconds(600),start,null,start)
    private val context=mapper.readTree("""{"environment":{"bootSessionId":"boot-1"},"case":{"requiredEvidence":["LOG","SCREENSHOT"]}}""")
    private val attempt=AttemptRecord("01992560-aaab-7000-8000-123456789abc",run.id,AttemptState.ACKED,"cmd_test","lse_test",1,
        start.plusSeconds(90),start.plusSeconds(300),null,null,context,null,null)

    @Test fun `restart after entire recovery window immediately records TIMEOUT with no invented start`() {
        val now=start.plusSeconds(211)
        prepare()
        var result:JsonNode?=null
        doAnswer { result=it.arguments[2] as JsonNode; null }.`when`(repository)
            .insertResult(eq(run) ?: run,eq(attempt) ?: attempt,any(JsonNode::class.java) ?: mapper.createObjectNode(),eq(now) ?: now)
        AdvanceTestDeadlines(repository,lifecycle,TimeProvider { now },access).advance(run.id)
        assertThat(result).describedAs("Elapsed recovery deadline must be closed on the first restart scan").isNotNull()
        assertThat(result!!.path("status").asText()).isEqualTo("TIMEOUT")
        assertThat(result!!.path("reasonCode").asText()).isEqualTo("RECOVERY_DEADLINE_EXCEEDED")
        assertThat(result!!.path("startedAt").isNull).isTrue()
    }

    @Test fun `AttemptAccess rejects exclusive expiry old identity and unacknowledged dispatch`() {
        prepare()
        `when`(repository.runForAttempt(attempt.id)).thenReturn(run.id)
        val actor=AgentActor("svc_test",run.projectId,run.agentId,run.deviceId)
        assertThat(lifecycle.lockWritable(actor,attempt.id,start.plusSeconds(89)).leaseId).isEqualTo("lse_test")
        assertThatThrownBy { lifecycle.lockWritable(actor,attempt.id,start.plusSeconds(90)) }.isInstanceOf(TestRunConflict::class.java)
        `when`(repository.attempt(run.id,true)).thenReturn(attempt.copy(state=AttemptState.DISPATCHED))
        assertThatThrownBy { lifecycle.lockWritable(actor,attempt.id,start.plusSeconds(10)) }.isInstanceOf(TestRunConflict::class.java)
    }

    private fun prepare() {
        `when`(repository.run(run.id)).thenReturn(run)
        `when`(repository.run(run.id,true)).thenReturn(run)
        `when`(repository.attempt(run.id,true)).thenReturn(attempt)
        `when`(repository.lockAgent(run.agentId)).thenReturn(AgentSelection(
            AgentActor("svc_test",run.projectId,run.agentId,run.deviceId),"a".repeat(64),"synthetic","android",SmokePolicy.capabilities,true))
        `when`(repository.manifest(run.releaseId)).thenReturn(LockedTestManifest(run.releaseId,run.projectId,"man_test","sha256:"+"a".repeat(64),
            "synthetic","android","{}".toByteArray()))
    }
}
