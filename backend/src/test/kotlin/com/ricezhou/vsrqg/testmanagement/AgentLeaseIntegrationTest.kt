package com.ricezhou.vsrqg.testmanagement

import com.ricezhou.vsrqg.shared.runConcurrently
import com.ricezhou.vsrqg.testmanagement.application.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(60)
class AgentLeaseIntegrationTest : RunFixture() {
    @Test fun `draining Agent does not receive a new command and allocation deadline remains effective`() {
        val id=run().path("testRunId").asText()
        heartbeat.execute(fingerprint,agent,mapper.readTree("""{"messageType":"AGENT_HEARTBEAT","protocolVersion":"1.0",
            "agentUptimeMs":100,"state":"DRAINING","device":{"power":"ON","connectivity":"CONNECTED","bootSessionId":"boot-1"},
            "spoolFreeBytes":10000000,"clockOffsetMs":0}"""),"drain","drain")
        assertThat(poll().isNull).isTrue()
        assertThat(count("agent_command")).isZero()
        now=now.plusSeconds(60)
        deadlines.advance(id)
        assertThat(results(id).path("items")[0].path("reasonCode").asText()).isEqualTo("ALLOCATION_DEADLINE_EXCEEDED")
    }
    @Test fun `concurrent repeated poll and ACK keep one command one attempt and one lease`() {
        run()
        val commands=runConcurrently(2) { poll() }
        assertThat(commands[0]).isEqualTo(commands[1])
        val id=commands[0].path("commandId").asText()
        val leases=runConcurrently(2) { accept(id) }
        assertThat(leases[0]).isEqualTo(leases[1])
        assertThat(count("test_attempt")).isOne()
        assertThat(count("agent_command")).isOne()
    }
    @Test fun `context is assigned only and rejects another project agent`() {
        run(); val cmd=poll()
        val id=cmd.path("attemptId").asText()
        assertThat(attempts.context(AgentActor(serviceId,project,agent,device),id).path("attemptId").asText()).isEqualTo(id)
        assertThatThrownBy { attempts.context(AgentActor(serviceId,"other",agent,device),id) }
            .isInstanceOf(org.springframework.security.access.AccessDeniedException::class.java)
    }
    @Test fun `server restart reconstructs recovery and timeout from durable deadlines`() {
        val runId=run().path("testRunId").asText()
        val cmd=poll(); accept(cmd.path("commandId").asText())
        now=now.plusSeconds(90)
        deadlines.advance(runId)
        assertThat(jdbc.sql("SELECT state FROM test_attempt WHERE test_run_id=:r").param("r",runId).query(String::class.java).single()).isEqualTo("RECOVERY_PENDING")
        assertThatThrownBy { beat(cmd.path("commandId").asText()) }.isInstanceOf(TestRunConflict::class.java)
        now=now.plusSeconds(120)
        deadlines.advance(runId); deadlines.advance(runId)
        assertThat(count("test_result")).isOne()
        assertThat(results(runId).path("items")[0].path("status").asText()).isEqualTo("TIMEOUT")
    }
    @Test fun `only current command and same session can renew and changed session writes ERROR`() {
        val id=run().path("testRunId").asText(); val cmd=poll().path("commandId").asText()
        accept(cmd); now=now.plusSeconds(80); beat(cmd)
        now=now.plusSeconds(20); deadlines.advance(id)
        assertThat(count("test_result")).isZero()
        beat(cmd,"boot-2")
        assertThat(results(id).path("items")[0].path("status").asText()).isEqualTo("ERROR")
    }
    @Test fun `heartbeat cancellation and worker race cannot deadlock or duplicate terminal result`() {
        val id=run().path("testRunId").asText(); val cmd=poll().path("commandId").asText(); accept(cmd)
        now=now.plusSeconds(300)
        runConcurrently(2) {
            deadlines.advance(id)
            cancel.cancel(user,id,"Stop","race","race")
        }
        assertThat(count("test_result")).isOne()
        val previous=results(id)
        assertThatThrownBy { beat(cmd) }.isInstanceOf(TestRunConflict::class.java)
        assertThat(results(id)).isEqualTo(previous)
    }
}
