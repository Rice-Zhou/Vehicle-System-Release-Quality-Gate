package com.ricezhou.vsrqg.testmanagement

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.evidence.application.*
import com.ricezhou.vsrqg.shared.application.*
import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.testmanagement.adapter.TestWire
import com.ricezhou.vsrqg.testmanagement.application.*
import com.ricezhou.vsrqg.testmanagement.domain.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.*
import org.springframework.security.access.AccessDeniedException
import java.time.Instant

@Timeout(60)
class AttemptResultApplicationTest {
    private val mapper=ObjectMapper()
    private val repository=mock(TestRunRepository::class.java)
    private val audit=mock(GovernanceStore::class.java)
    private val evidence=mock(AttemptEvidence::class.java)
    private val actor=AgentActor("svc","project","agent","device")
    private var allowed=actor
    private val access=AgentAccess { _,_->if(allowed!=actor) throw AccessDeniedException("revoked") else actor }
    private val now=Instant.parse("2026-09-09T00:00:00Z")
    private var run=RunRecord("run","release","project","agent","device","user",RunState.RUNNING,now.plusSeconds(60),now.plusSeconds(600),now,null,now)
    private var attempt=AttemptRecord("01992560-aaab-7000-8000-123456789abc","run",AttemptState.ACKED,"cmd","lease",1,
        now.plusSeconds(90),now.plusSeconds(300),null,null,mapper.readTree("""{"case":{"caseId":"apk-launch-smoke","version":1,"requiredEvidence":["LOG","SCREENSHOT"]}}"""),null,null)
    private val stored=mutableListOf<JsonNode>()
    private val stream=mutableListOf<JsonNode>()
    private var snapshot:JsonNode?=null
    private var resolution=EvidenceResolution(setOf("log","png"),emptySet())
    private val lifecycle=TestRunLifecycle(repository,audit,mapper,access,evidence)
    private val idempotency=object:IdempotentExecutor {
        override fun <T:Any> execute(scope:String,principalId:String,key:String,requestDigest:String,responseType:Class<T>,action:()->T):T=action()
    }
    private val submit=SubmitAttemptResult(repository,access,lifecycle,evidence,lifecycle,TestWire(mapper),idempotency,audit,
        TestMessageConflicts(audit,mapper),TimeProvider { now },mapper)
    private val events=AppendCommandEvent(repository,access,lifecycle,lifecycle,TestWire(mapper),idempotency,audit,
        TestMessageConflicts(audit,mapper),TimeProvider { now },mapper)
    init {
        `when`(repository.lockAgent(actor.agentId)).thenReturn(AgentSelection(actor,"a".repeat(64),"v","p",SmokePolicy.capabilities,true))
        `when`(repository.manifest("release")).thenReturn(LockedTestManifest("release","project","manifest","sha256:"+"a".repeat(64),"v","p",byteArrayOf()))
        `when`(repository.runForAttempt(attempt.id)).thenReturn("run")
        `when`(repository.run("run",true)).thenAnswer { run }
        `when`(repository.attempt("run",true)).thenAnswer { attempt }
        `when`(repository.attempts("run",true)).thenAnswer { listOf(attempt) }
        `when`(repository.results("run")).thenAnswer { stored.toList() }
        `when`(repository.events("cmd")).thenAnswer { stream.toList() }
        `when`(repository.command("cmd")).thenReturn(mapper.createObjectNode())
        `when`(repository.event(eq("cmd") ?: "cmd",anyLong())).thenAnswer { call->stream.singleOrNull { it.path("sequenceNo").asLong()==call.arguments[1] } }
        `when`(repository.completion("run")).thenAnswer { listOf(CompletionCase(listOf(CompletionAttempt(attempt.state,stored.isNotEmpty(),true)))) }
        `when`(repository.resultView("run")).thenAnswer {
            mapper.createObjectNode().put("status",run.state.name).set<JsonNode>("attempts",mapper.valueToTree(stored))
        }
        doAnswer { attempt=it.arguments[0] as AttemptRecord;null }.`when`(repository).updateAttempt(any(AttemptRecord::class.java) ?: attempt,any(Instant::class.java) ?: now)
        doAnswer { run=it.arguments[0] as RunRecord;null }.`when`(repository).updateRun(any(RunRecord::class.java) ?: run,any(Instant::class.java) ?: now)
        doAnswer { stored.add((it.arguments[2] as JsonNode).deepCopy());null }.`when`(repository)
            .insertResult(any(RunRecord::class.java) ?: run,any(AttemptRecord::class.java) ?: attempt,any(JsonNode::class.java) ?: mapper.nullNode(),any(Instant::class.java) ?: now)
        doAnswer { stream.add((it.arguments[0] as JsonNode).deepCopy());null }.`when`(repository)
            .insertEvent(any(JsonNode::class.java) ?: mapper.nullNode(),anyString(),any(Instant::class.java) ?: now)
        doAnswer { snapshot=(it.arguments[1] as JsonNode).deepCopy();null }.`when`(repository)
            .saveTerminalSnapshot(eq("run") ?: "run",any(JsonNode::class.java) ?: mapper.nullNode())
        `when`(evidence.resolve(any(AttemptBinding::class.java) ?: lifecycle.binding(run,attempt),anySet())).thenAnswer { resolution }
        doAnswer { call->
            assertThat((call.arguments[0] as AttemptBinding).fencingToken).isEqualTo(attempt.fencingToken)
            assertThat(attempt.state.terminal).isFalse()
            null
        }.`when`(evidence).seal(any(AttemptBinding::class.java) ?: lifecycle.binding(run,attempt),any(Instant::class.java) ?: now)
    }
    private fun body(status:String="PASS"):ObjectNode {
        val body=mapper.readTree("""{"messageType":"ATTEMPT_RESULT","protocolVersion":"1.0","attemptId":"01992560-aaab-7000-8000-123456789abc",
            "leaseId":"lease","fencingToken":1,"status":"$status","startedAt":"2026-09-09T00:00:00Z","finishedAt":"2026-09-09T00:00:01Z",
            "reasonCode":"SYNTHETIC_TEST","evidenceIds":["png","log"]}""") as ObjectNode
        return body.put("resultDigest",ResultCanonicalizer.digest(body))
    }
    @Test fun `PASS stores one terminal result and frozen run and replays without reading current payload`() {
        val body=body();val receipt=submit.submit(actor,body,"key","request")
        assertThat(run.state).isEqualTo(RunState.COMPLETED)
        assertThat(attempt.state).isEqualTo(AttemptState.COMPLETED)
        assertThat(attempt.fencingToken).isEqualTo(2)
        assertThat(snapshot!!.path("status").asText()).isEqualTo("COMPLETED")
        resolution=EvidenceResolution(emptySet(),setOf("LOG","SCREENSHOT"))
        assertThat(submit.submit(actor,body,"retry","request")).isEqualTo(receipt)
        assertThat(stored).hasSize(1)
        assertThatThrownBy { submit.submit(actor,body("FAIL"),"changed","request") }.isInstanceOf(TestRunConflict::class.java)
        allowed=actor.copy(principalId="revoked")
        assertThatThrownBy { submit.submit(actor,body,"key","request") }.isInstanceOf(AccessDeniedException::class.java)
    }
    @Test fun `missing required rejects PASS but ERROR retains explicit failed requirement`() {
        resolution=EvidenceResolution(setOf("log"),setOf("SCREENSHOT"))
        assertThatThrownBy { submit.submit(actor,body(),"key","request") }.isInstanceOf(TestRunConflict::class.java)
        assertThat(stored).isEmpty()
        val result=submit.submit(actor,body("ERROR"),"error","request")
        assertThat(result.path("evidenceRequirements").toString()).contains("SCREENSHOT","FAILED")
        assertThat(attempt.state).isEqualTo(AttemptState.ERROR)
        assertThat(run.state).isEqualTo(RunState.COMPLETED)
    }
    @Test fun `invalid digest stale generation skip and stored sequence gaps cannot finalize`() {
        val cases=listOf(body().put("resultDigest","sha256:"+"0".repeat(64)),
            body().put("fencingToken",2).let { it.put("resultDigest",ResultCanonicalizer.digest(it)) },body("SKIPPED"))
        for(body in cases) assertThatThrownBy { submit.submit(actor,body,"key","request") }.isInstanceOf(TestRunConflict::class.java)
        stream.add(mapper.readTree("""{"sequenceNo":2}"""))
        assertThatThrownBy { submit.submit(actor,body(),"key","request") }.isInstanceOf(TestRunConflict::class.java)
        assertThat(stored).isEmpty()
        assertThat(run.state).isEqualTo(RunState.RUNNING)
    }
    @Test fun `event progresses once replay survives terminal and gaps are rejected`() {
        val event=mapper.readTree("""{"messageType":"COMMAND_EVENT","protocolVersion":"1.0","commandId":"cmd",
            "attemptId":"01992560-aaab-7000-8000-123456789abc","leaseId":"lease","fencingToken":1,"sequenceNo":1,
            "eventType":"STARTED","occurredAt":"2026-09-09T00:00:00Z","payload":{}}""") as ObjectNode
        val first=events.append(actor,event,"one","request")
        assertThat(attempt.state).isEqualTo(AttemptState.RUNNING)
        assertThat(events.append(actor,event,"same","request")).isEqualTo(first)
        assertThat(stream).hasSize(1)
        assertThatThrownBy { events.append(actor,event.deepCopy().put("sequenceNo",3),"gap","request") }.isInstanceOf(TestRunConflict::class.java)
        submit.submit(actor,body(),"result","request")
        assertThat(events.append(actor,event,"late","request")).isEqualTo(first)
        assertThatThrownBy { events.append(actor,event.deepCopy().put("eventType","PROGRESS"),"conflict","request") }.isInstanceOf(TestRunConflict::class.java)
        assertThat(stream).hasSize(1)
    }
    @Test fun `checked persistence failure rolls back the actual Spring result transaction`() {
        val connection=mock(java.sql.Connection::class.java).also { `when`(it.autoCommit).thenReturn(true) }
        val source=mock(javax.sql.DataSource::class.java).also { `when`(it.connection).thenReturn(connection) }
        val manager=org.springframework.jdbc.datasource.DataSourceTransactionManager(source)
        var completion:Int?=null
        doAnswer {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                object:org.springframework.transaction.support.TransactionSynchronization {
                    override fun afterCompletion(status:Int) { completion=status }
                })
            throw java.io.IOException("synthetic checked persistence failure")
        }.`when`(repository).insertResult(any(RunRecord::class.java) ?: run,any(AttemptRecord::class.java) ?: attempt,
            any(JsonNode::class.java) ?: mapper.nullNode(),any(Instant::class.java) ?: now)
        val proxy=org.springframework.aop.framework.ProxyFactory(submit).apply {
            isProxyTargetClass=true
            addAdvice(org.springframework.transaction.interceptor.TransactionInterceptor().also {
                it.transactionManager=manager
                it.transactionAttributeSource=org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()
                it.afterPropertiesSet()
            })
        }.proxy as SubmitAttemptResult
        assertThatThrownBy { proxy.submit(actor,body(),"checked","checked") }.isInstanceOf(java.io.IOException::class.java)
        assertThat(completion).isEqualTo(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK)
        // JDBC is a double here; actual persisted rollback is covered separately by PostgreSQL fault injection.
    }
    @Test fun `expired lease cannot append events or submit new results`() {
        attempt=attempt.copy(leaseExpiresAt=now)
        assertThatThrownBy { submit.submit(actor,body(),"expired","request") }.isInstanceOf(TestRunConflict::class.java)
            .extracting("code").isEqualTo("STALE_LEASE")
        val event=mapper.readTree("""{"messageType":"COMMAND_EVENT","protocolVersion":"1.0","commandId":"cmd",
            "attemptId":"01992560-aaab-7000-8000-123456789abc","leaseId":"lease","fencingToken":1,"sequenceNo":1,
            "eventType":"STARTED","occurredAt":"2026-09-09T00:00:00Z","payload":{}}""")
        assertThatThrownBy { events.append(actor,event,"expired-event","request") }.isInstanceOf(TestRunConflict::class.java)
            .extracting("code").isEqualTo("STALE_LEASE")
        assertThat(stored).isEmpty()
        assertThat(stream).isEmpty()
        assertThat(run.state).isEqualTo(RunState.RUNNING)
    }
}
