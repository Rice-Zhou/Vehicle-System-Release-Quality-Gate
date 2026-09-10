package com.ricezhou.vsrqg.testmanagement

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.evidence.EvidenceFixture
import com.ricezhou.vsrqg.testmanagement.application.*
import com.ricezhou.vsrqg.testmanagement.domain.ResultCanonicalizer
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import java.util.UUID

@Timeout(60)
open class ResultFixture:EvidenceFixture() {
    @Autowired lateinit var submit:SubmitAttemptResult
    @Autowired lateinit var events:AppendCommandEvent
    @Autowired lateinit var query:GetTestRunResults
    fun actor()=AgentActor(serviceId,project,agent,device)
    fun runId()=jdbc.sql("SELECT test_run_id FROM test_attempt WHERE id=CAST(:a AS uuid)")
        .param("a",command.path("attemptId").asText()).query(String::class.java).single()
    fun resultBody(status:String="ERROR",evidence:List<String> = emptyList()):ObjectNode {
        val lease=accept(command.path("commandId").asText())
        val node=mapper.createObjectNode().put("messageType","ATTEMPT_RESULT").put("protocolVersion","1.0")
            .put("attemptId",command.path("attemptId").asText()).put("leaseId",lease.path("leaseId").asText())
            .put("fencingToken",lease.path("fencingToken").asLong()).put("status",status)
            .put("startedAt",now.toString()).put("finishedAt",now.toString()).put("reasonCode","SYNTHETIC_RESULT")
        node.putArray("evidenceIds").also { array->evidence.forEach(array::add) }
        return signed(node)
    }
    fun signed(node:ObjectNode)=node.put("resultDigest",ResultCanonicalizer.digest(node))
    fun eventBody(sequence:Long=1,type:String="STARTED"):ObjectNode {
        val result=resultBody()
        return mapper.createObjectNode().put("messageType","COMMAND_EVENT").put("protocolVersion","1.0")
            .put("commandId",command.path("commandId").asText()).put("attemptId",result.path("attemptId").asText())
            .put("leaseId",result.path("leaseId").asText()).put("fencingToken",result.path("fencingToken").asLong())
            .put("sequenceNo",sequence).put("eventType",type).put("occurredAt",now.toString()).apply { putObject("payload") }
    }
    fun submitHttp(body:JsonNode,expected:Int=200,key:String=UUID.randomUUID().toString()):JsonNode {
        val response=mvc.perform(put("/agent-api/v1/attempts/"+body.path("attemptId").asText()+"/result")
            .with(agentAuth()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn().response
        assertThat(response.status).describedAs(response.contentAsString).isEqualTo(expected)
        return mapper.readTree(response.contentAsString)
    }
}

class AttemptResultIntegrationTest:ResultFixture() {
    @Test fun `verified LOG and PNG accept PASS and immutable replay after later file loss`() {
        start()
        val (session,log)=available()
        val (_,png)=available(byteArrayOf(137.toByte(),80,78,71,13,10,26,10),"SCREENSHOT")
        val body=resultBody("PASS",listOf(png.path("evidenceId").asText(),log.path("evidenceId").asText()))
        val before=body.deepCopy()
        val receipt=submitHttp(body,key="result")
        assertThat(receipt.path("status").asText()).isEqualTo("PASS")
        assertThat(body).isEqualTo(before)
        val snapshot=query.get(user,runId())
        assertThat(snapshot.path("status").asText()).isEqualTo("COMPLETED")
        assertThat(snapshot.path("attempts")[0].path("result").path("status").asText()).isEqualTo("PASS")
        assertThat(snapshot.has("qualityResult")).isFalse()
        java.nio.file.Files.delete(storage.resolve(session.path("uploadId").asText()+".payload"))
        assertThat(recovery.reconcile(setOf(log.path("evidenceId").asText())).single().code).isEqualTo("INTEGRITY_ERROR")
        now=now.plusSeconds(1000)
        assertThat(submitHttp(body,key="different-retry")).isEqualTo(receipt)
        assertThat(query.get(user,runId())).isEqualTo(snapshot)
        apiCreate(declaration(),409)
        assertThat(count("test_result")).isOne()
        signed(body.put("status","FAIL"));submitHttp(body,409)
        assertThat(query.get(user,runId())).isEqualTo(snapshot)
    }
    @Test fun `PASS missing required evidence and wrong digest fail but ERROR preserves partial evidence`() {
        start();val (_,log)=available()
        val body=resultBody("PASS",listOf(log.path("evidenceId").asText()))
        submitHttp(body,409)
        assertThat(count("test_result")).isZero()
        signed(body.put("status","ERROR")); val pending=apiCreate(declaration())
        val original=body.path("resultDigest").asText();body.put("resultDigest","sha256:"+"0".repeat(64));submitHttp(body,409)
        body.put("resultDigest",original);submitHttp(body)
        val snapshot=query.get(user,runId())
        assertThat(snapshot.path("attempts")[0].path("result").path("evidenceIds")).hasSize(1)
        assertThat(snapshot.path("attempts")[0].path("evidenceRequirements").toString()).contains("FAILED","SCREENSHOT")
        assertThat(downloads.metadata(user,pending.path("evidenceId").asText()).path("state").asText()).isEqualTo("EXPIRED")
        apiComplete(pending.path("uploadId").asText(),declaration(),409)
    }
    @Test fun `event sequence repeats without side effects rejects gaps and freezes terminal replay`() {
        start(); val body=eventBody()
        val first=events.append(actor(),body,"event","event")
        val response=mvc.perform(post("/agent-api/v1/commands/"+command.path("commandId").asText()+"/events")
            .with(agentAuth()).header("Idempotency-Key","http-event").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
            .andReturn().response
        assertThat(response.status).isEqualTo(200)
        // HTTP and JDBC JSON parsing may choose IntNode where application construction uses LongNode.
        // Compare the complete wire facts, including the sequence and digest, through the existing JCS implementation.
        val firstWire=TestJson.canonical(first)
        assertThat(TestJson.canonical(mapper.readTree(response.contentAsString))).isEqualTo(firstWire)
        assertThat(TestJson.canonical(events.append(actor(),body,"other","other"))).isEqualTo(firstWire)
        assertSingleEventSideEffect()
        assertThatThrownBy { events.append(actor(),eventBody(3),"gap","gap") }.isInstanceOf(TestRunConflict::class.java)
        val changed=body.deepCopy().put("eventType","PROGRESS")
        assertThatThrownBy { events.append(actor(),changed,"different","different") }.isInstanceOf(TestRunConflict::class.java)
        val result=resultBody();submitHttp(result)
        assertThat(TestJson.canonical(events.append(actor(),body,"late-identical","late"))).isEqualTo(firstWire)
        assertThatThrownBy { events.append(actor(),changed,"late-change","late") }.isInstanceOf(TestRunConflict::class.java)
        assertThat(jdbc.sql("SELECT count(*) FROM agent_command_event WHERE command_id=:c").param("c",command.path("commandId").asText()).query(Int::class.java).single()).isOne()
        assertSingleEventSideEffect()
    }
    private fun assertSingleEventSideEffect() {
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE aggregate_id=:id AND action='COMMAND_EVENT_ACCEPTED'")
            .param("id",runId()).query(Int::class.java).single()).isOne()
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE aggregate_id=:id AND event_type='test.command.event'")
            .param("id",runId()).query(Int::class.java).single()).isOne()
    }
    @Test fun `stale result and same key across principals cannot read immutable confirmation`() {
        start();val body=resultBody(); val original=actor()
        val stale=signed(body.deepCopy().put("fencingToken",2));submitHttp(stale,409)
        submitHttp(body,key="shared")
        fixture()
        assertThatThrownBy { submit.submit(actor(),body,"shared","other-principal") }.isInstanceOf(org.springframework.security.access.AccessDeniedException::class.java)
        jdbc.sql("UPDATE agent SET revoked=true WHERE id=:a").param("a",original.agentId).update()
        assertThatThrownBy { submit.submit(original,body,"shared","revoked") }.isInstanceOf(org.springframework.security.access.AccessDeniedException::class.java)
    }
}
