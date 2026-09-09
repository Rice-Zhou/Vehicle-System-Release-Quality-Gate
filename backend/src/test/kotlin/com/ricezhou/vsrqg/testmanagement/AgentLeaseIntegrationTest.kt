package com.ricezhou.vsrqg.testmanagement

import com.ricezhou.vsrqg.shared.runConcurrently
import com.ricezhou.vsrqg.testmanagement.application.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.atomic.AtomicInteger

@Timeout(60)
@TestPropertySource(properties=["vsrqg.demo.agent-registration.enabled=true"])
class AgentLeaseIntegrationTest : RunFixture() {
    @Autowired lateinit var realAccess:AgentAccess
    @Autowired lateinit var registration:RegisterAgent
    @Autowired lateinit var transactionManager:PlatformTransactionManager

    @ParameterizedTest
    @ValueSource(strings=["HEARTBEAT","WORKER"])
    fun `legitimate capability loss closes an unexpired run even without another heartbeat`(trigger:String) {
        val id=run().path("testRunId").asText()
        val command=poll()
        accept(command.path("commandId").asText())
        now=now.plusSeconds(20)
        val body=mapper.readTree("""{"messageType":"AGENT_REGISTRATION","protocolVersion":"1.0","agentVersion":"0.2.1",
            "supportedProtocolVersions":["1.0"],"deviceRef":"$device","capabilities":[],"collectorVersions":{"LOG":"1.0","SCREENSHOT":"1.0"}}""")
        registration.register(fingerprint,body,"lost-capability",TestJson.digest(body),"lost-capability")
        assertThatThrownBy { TransactionTemplate(transactionManager).execute {
            attempts.lockWritable(AgentActor(serviceId,project,agent,device),command.path("attemptId").asText(),now)
        } }.isInstanceOf(TestRunConflict::class.java)
        if(trigger=="HEARTBEAT") {
            val response=beat(command.path("commandId").asText())
            assertThat(response.path("leaseRenewed").asBoolean()).isFalse()
            assertThat(response.path("state").asText()).isEqualTo("ERROR")
        } else {
            assertThat(deadlines.activeRuns()).contains(id)
            deadlines.advance(id)
        }
        deadlines.advance(id)
        assertThatThrownBy { beat(command.path("commandId").asText()) }.isInstanceOf(TestRunConflict::class.java)
        assertThat(count("test_result")).isOne()
        assertThat(results(id).path("items")[0].path("status").asText()).isEqualTo("ERROR")
        assertThat(results(id).path("items")[0].path("reasonCode").asText()).isEqualTo("AGENT_IDENTITY_OR_CAPABILITY_CHANGED")
        assertThat(jdbc.sql("SELECT state FROM test_run WHERE id=:id").param("id",id).query(String::class.java).single()).isEqualTo("ERROR")
        assertThat(jdbc.sql("SELECT fencing_token FROM test_attempt WHERE test_run_id=:id").param("id",id).query(Long::class.java).single()).isEqualTo(2)
        assertThat(jdbc.sql("SELECT count(*) FROM test_run WHERE device_id=:id AND finished_at IS NULL")
            .param("id",device).query(Int::class.java).single()).isZero()
        assertThat(jdbc.sql("SELECT count(*) FROM test_run_state_history WHERE test_run_id=:id AND state='ERROR'")
            .param("id",id).query(Int::class.java).single()).isOne()
        assertThat(deadlines.activeRuns()).doesNotContain(id)
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE aggregate_id=:id AND action='TEST_RUN_ERROR'")
            .param("id",id).query(Int::class.java).single()).isOne()
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE aggregate_id=:id AND event_type='test.run.terminal'")
            .param("id",id).query(Int::class.java).single()).isOne()
    }

    @Test fun `dispatched recovery restores dispatch without renewing and subsequent ACK still succeeds`() {
        val id=run().path("testRunId").asText()
        val command=poll().path("commandId").asText()
        val expires=jdbc.sql("SELECT lease_expires_at::text FROM test_attempt WHERE test_run_id=:id").param("id",id).query(String::class.java).single()
        now=now.plusSeconds(10)
        beat(command,connected=false)
        now=now.plusSeconds(10)
        assertThat(beat(command).path("leaseRenewed").asBoolean()).isFalse()
        assertThat(jdbc.sql("SELECT state FROM test_attempt WHERE test_run_id=:id").param("id",id).query(String::class.java).single()).isEqualTo("DISPATCHED")
        assertThat(jdbc.sql("SELECT lease_expires_at::text FROM test_attempt WHERE test_run_id=:id").param("id",id).query(String::class.java).single()).isEqualTo(expires)
        assertThat(accept(command).path("status").asText()).isEqualTo("ACCEPTED")
        assertThat(count("test_result")).isZero()
    }

    @ParameterizedTest
    @ValueSource(strings=["AGENT_REVOKED","DEVICE_DISABLED","ASSIGNMENT_REMOVED"])
    fun `worker closes invalid identities atomically and repeatedly without rollback only poisoning`(failure:String) {
        assertThat(AopUtils.isAopProxy(deadlines)).isTrue()
        assertThat(AopUtils.isAopProxy(realAccess)).isTrue()
        val id=run().path("testRunId").asText()
        accept(poll().path("commandId").asText())
        when(failure) {
            "AGENT_REVOKED" -> jdbc.sql("UPDATE agent SET revoked=true WHERE id=:id").param("id",agent).update()
            "DEVICE_DISABLED" -> jdbc.sql("UPDATE device SET disabled=true WHERE id=:id").param("id",device).update()
            "ASSIGNMENT_REMOVED" -> jdbc.sql("DELETE FROM project_assignment WHERE principal_id=:id AND project_id=:p")
                .param("id",serviceId).param("p",project).update()
        }
        now=now.plusSeconds(90)
        assertThatCode { deadlines.advance(id); deadlines.advance(id) }.doesNotThrowAnyException()
        assertThat(count("test_result")).isOne()
        assertThat(results(id).path("items")[0].path("status").asText()).isEqualTo("ERROR")
        assertThat(jdbc.sql("SELECT state FROM test_run WHERE id=:id").param("id",id).query(String::class.java).single()).isEqualTo("ERROR")
        assertThat(jdbc.sql("SELECT fencing_token FROM test_attempt WHERE test_run_id=:id").param("id",id).query(Long::class.java).single()).isEqualTo(2)
        assertThat(jdbc.sql("SELECT count(*) FROM test_run WHERE device_id=:id AND finished_at IS NULL")
            .param("id",device).query(Int::class.java).single()).isZero()
        assertThat(jdbc.sql("SELECT count(*) FROM test_run_state_history WHERE test_run_id=:id AND state='ERROR'")
            .param("id",id).query(Int::class.java).single()).isOne()
        when(failure) {
            "AGENT_REVOKED" -> jdbc.sql("UPDATE agent SET revoked=false WHERE id=:id").param("id",agent).update()
            "DEVICE_DISABLED" -> jdbc.sql("UPDATE device SET disabled=false WHERE id=:id").param("id",device).update()
            "ASSIGNMENT_REMOVED" -> jdbc.sql("INSERT INTO project_assignment(project_id,principal_id,role,created_at) VALUES (:p,:id,'ENGINEER',now())")
                .param("id",serviceId).param("p",project).update()
        }
        assertThat(run().path("state").asText()).isEqualTo("WAITING_FOR_AGENT")
    }

    @Test fun `DRAINING renews the current ACKed command but cannot claim new work or renew at expiry`() {
        run()
        val command=poll().path("commandId").asText()
        accept(command)
        now=now.plusSeconds(80)
        val body=mapper.readTree("""{"messageType":"AGENT_HEARTBEAT","protocolVersion":"1.0","agentUptimeMs":100,
            "state":"DRAINING","device":{"power":"ON","connectivity":"CONNECTED","bootSessionId":"boot-1"},
            "currentCommandId":"$command","spoolFreeBytes":10000000,"clockOffsetMs":0}""")
        val renewed=heartbeat.execute(fingerprint,agent,body,"draining-current","draining-current")
        assertThat(renewed.path("leaseRenewed").asBoolean()).isTrue()
        assertThat(renewed.path("fencingToken").asLong()).isEqualTo(1)
        assertThat(poll().isNull).isTrue()
        assertThat(count("agent_command")).isOne()
        now=now.plusSeconds(90)
        assertThatThrownBy { heartbeat.execute(fingerprint,agent,body,"draining-expired","draining-expired") }
            .isInstanceOf(TestRunConflict::class.java).extracting("code").isEqualTo("STALE_LEASE")
    }
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
        assertThat(TestJson.canonical(commands[0])).isEqualTo(TestJson.canonical(commands[1]))
        val id=commands[0].path("commandId").asText()
        val leases=runConcurrently(2) { accept(id) }
        assertThat(TestJson.canonical(leases[0])).isEqualTo(TestJson.canonical(leases[1]))
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
    @Test fun `duplicate deadline scans and cancellations preserve terminal history against later heartbeat`() {
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

    @Test fun `current heartbeat cancellation and deadline scan contend without deadlock or duplicate result`() {
        val id=run().path("testRunId").asText()
        val command=poll().path("commandId").asText()
        accept(command)
        now=now.plusSeconds(80)
        val contender=AtomicInteger()
        val outcomes=runConcurrently(3) {
            when(contender.getAndIncrement()) {
                0 -> try {
                    assertThat(beat(command).path("leaseRenewed").asBoolean()).isTrue()
                    "RENEWED"
                } catch(e:TestRunConflict) {
                    assertThat(e.code).isEqualTo("STALE_LEASE")
                    "CANCELLED_BEFORE_HEARTBEAT"
                }
                1 -> { cancel.cancel(user,id,"Stop","concurrent-cancel","concurrent-cancel"); "CANCELLED" }
                else -> { deadlines.advance(id); "SCANNED" }
            }
        }
        assertThat(outcomes).contains("CANCELLED","SCANNED")
        assertThat(count("test_result")).isOne()
        assertThat(results(id).path("items")[0].path("status").asText()).isEqualTo("BLOCKED")
    }
}
