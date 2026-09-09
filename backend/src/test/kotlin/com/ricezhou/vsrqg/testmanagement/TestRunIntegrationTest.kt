package com.ricezhou.vsrqg.testmanagement

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import com.ricezhou.vsrqg.shared.runConcurrently
import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.testmanagement.application.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Instant
import java.util.UUID

@TestPropertySource(properties = ["vsrqg.demo.smoke.enabled=true", "vsrqg.test.deadline-worker.enabled=false"])
@Timeout(60)
open class RunFixture : PostgresIntegrationTest() {
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var create: CreateTestRun
    @Autowired lateinit var cancel: CancelTestRun
    @Autowired lateinit var claim: ClaimCommand
    @Autowired lateinit var ack: AcknowledgeCommand
    @Autowired lateinit var heartbeat: HeartbeatAgent
    @Autowired lateinit var deadlines: AdvanceTestDeadlines
    @Autowired lateinit var attempts: AttemptAccess
    @MockitoBean lateinit var source: SmokeEnvironmentSource
    @MockitoBean lateinit var clock: TimeProvider
    lateinit var release: String
    lateinit var project: String
    lateinit var agent: String
    lateinit var device: String
    lateinit var serviceId: String
    lateinit var fingerprint: String
    lateinit var user: Principal
    var now: Instant = Instant.parse("2026-09-09T00:00:00Z")
    val envBytes = """{"bootSessionId":"boot-1","buildId":"build-1","buildFingerprint":"synthetic/device:35"}""".toByteArray()

    @BeforeEach fun fixture() {
        now = Instant.parse("2026-09-09T00:00:00Z")
        `when`(clock.now()).thenAnswer { now }
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
        project = "p_$suffix"; release = "rel_$suffix"; agent = "agt_$suffix"; device = "dev_$suffix"
        serviceId = "svc_$suffix"; fingerprint = suffix.repeat(4)
        val uid = "usr_$suffix"
        user = Principal("urn:test", uid, false)
        jdbc.sql("INSERT INTO project(id,project_key,name,created_at) VALUES (:id,:id,'Synthetic',now())").param("id",project).update()
        for ((id,type) in listOf(uid to "USER",serviceId to "SERVICE")) {
            jdbc.sql("INSERT INTO principal(id,issuer,subject,principal_type,created_at) VALUES (:id,'urn:test',:id,:type,now())").param("id",id).param("type",type).update()
            jdbc.sql("INSERT INTO project_assignment(project_id,principal_id,role,created_at) VALUES (:p,:id,'ENGINEER',now())").param("p",project).param("id",id).update()
        }
        jdbc.sql("INSERT INTO device(id,project_id,vehicle,platform,created_at) VALUES (:id,:p,'synthetic','android',now())").param("id",device).param("p",project).update()
        jdbc.sql("""INSERT INTO agent(id,principal_id,project_id,device_id,certificate_sha256,negotiated_protocol,
            capabilities,registered_at,created_at) VALUES (:id,:s,:p,:d,:f,'1.0','["ADB","APK_INSTALL","LOG","SCREENSHOT"]',now(),now())""")
            .param("id",agent).param("s",serviceId).param("p",project).param("d",device).param("f",fingerprint).update()
        `when`(source.load()).thenAnswer { SmokeEnvironment(agent,device,envBytes) }
        for(version in 1..2) {
            val definition=mapper.readTree("""{"caseId":"apk-launch-smoke","version":$version,"mode":"${if(version==1) "normal" else "assertion-failure"}","timeoutMs":300000,"requiredEvidence":["LOG","SCREENSHOT"]}""")
            jdbc.sql("""INSERT INTO test_case_version(id,case_id,version,state,definition,created_at)
                VALUES (:id,'apk-launch-smoke',:v,'PUBLISHED',CAST(:d AS jsonb),now()) ON CONFLICT(case_id,version) DO NOTHING""")
                .param("id","tcv_$suffix$version").param("v",version).param("d",definition.toString()).update()
            jdbc.sql("""INSERT INTO test_plan_version(id,plan_id,version,state,max_attempts,created_at)
                VALUES (:id,'single-device-smoke',:v,'PUBLISHED',1,now()) ON CONFLICT(plan_id,version) DO NOTHING""")
                .param("id","tpv_$suffix$version").param("v",version).update()
            jdbc.sql("""INSERT INTO test_plan_case(plan_version_id,case_version_id,ordinal,required)
                SELECT p.id,c.id,0,true FROM test_plan_version p,test_case_version c
                WHERE p.plan_id='single-device-smoke' AND c.case_id='apk-launch-smoke' AND p.version=:v AND c.version=:v
                ON CONFLICT DO NOTHING""").param("v",version).update()
        }
        jdbc.sql("""INSERT INTO release_record(id,project_id,vehicle,platform,system_version,build_id,status,created_at,updated_at)
            VALUES (:id,:p,'synthetic','android',:id,:id,'DRAFT',now(),now())""").param("id",release).param("p",project).update()
        val manifest = mapper.readTree("""{"artifacts":[{"type":"APK","required":true,"checksum":{"algorithm":"SHA-256","value":"${"a".repeat(64)}"},"packageName":"com.ricezhou.vsrqg.smoke","versionCode":"1","signingCertificateSha256":"${"b".repeat(64)}"},{"type":"CONFIG","required":true,"checksum":{"algorithm":"SHA-256","value":"${TestJson.sha256(envBytes).removePrefix("sha256:")}"}}]}""")
        val mid="man_$suffix"; val vid="val_$suffix"
        val bytes=TestJson.canonical(manifest)
        jdbc.sql("""INSERT INTO manifest_revision(id,release_id,revision,content_digest,raw_manifest,canonical_bytes,schema_version,state,created_at,updated_at)
            VALUES (:m,:r,1,:hash,CAST(:body AS jsonb),:bytes,'0.2','DRAFT',now(),now())""")
            .param("m",mid).param("r",release).param("hash",TestJson.sha256(bytes)).param("body",manifest.toString()).param("bytes",bytes).update()
        jdbc.sql("""INSERT INTO manifest_validation(id,manifest_id,status,content_digest,schema_version,validator_version,report,validated_at,created_at)
            VALUES (:v,:m,'VALID',:hash,'0.2','trusted-test/1','{}',now(),now())""")
            .param("v",vid).param("m",mid).param("hash",TestJson.sha256(bytes)).update()
        jdbc.sql("UPDATE manifest_revision SET state='LOCKED',locked_validation_id=:v WHERE id=:m").param("v",vid).param("m",mid).update()
        jdbc.sql("UPDATE release_record SET status='READY_FOR_TEST',locked_manifest_id=:m WHERE id=:r").param("m",mid).param("r",release).update()
    }
    fun body(): JsonNode = mapper.readTree("""{"releaseId":"$release","testPlan":{"planId":"single-device-smoke","version":1},"deviceSelector":{"vehicle":"synthetic","requiredCapabilities":["ADB","APK_INSTALL","LOG","SCREENSHOT"]}}""")
    fun run(key: String=UUID.randomUUID().toString()): JsonNode = create.create(user,body(),key,key)
    fun poll(key: String=UUID.randomUUID().toString()): JsonNode = claim.once(fingerprint,agent,
        mapper.readTree("""{"messageType":"COMMAND_POLL","protocolVersion":"1.0","maxCommands":10,"waitSeconds":1}"""),key,key)
    fun accept(command: String,key: String=UUID.randomUUID().toString()): JsonNode = ack.execute(fingerprint,command,
        mapper.readTree("""{"messageType":"COMMAND_ACK","protocolVersion":"1.0","status":"ACCEPTED"}"""),key,key)
    fun beat(command: String,boot: String="boot-1",connected: Boolean=true): JsonNode = heartbeat.execute(fingerprint,agent,
        mapper.readTree("""{"messageType":"AGENT_HEARTBEAT","protocolVersion":"1.0","agentUptimeMs":100,"state":"BUSY","device":{"power":"ON","connectivity":"${if(connected) "CONNECTED" else "DISCONNECTED"}","bootSessionId":"$boot"},"currentCommandId":"$command","spoolFreeBytes":10000000,"clockOffsetMs":0}"""),
        UUID.randomUUID().toString(),UUID.randomUUID().toString())
    fun count(table: String) = jdbc.sql("SELECT count(*) FROM $table WHERE test_run_id IN (SELECT id FROM test_run WHERE release_id=:r)").param("r",release).query(Int::class.java).single()
    fun results(id: String) = cancel.results(user,id)
}

@Timeout(60)
class TestRunIntegrationTest : RunFixture() {
    @Test fun `create replay preserves immutable context and returns zero fabricated results`() {
        val first=run("same")
        assertThat(run("same")).isEqualTo(first)
        assertThat(count("test_attempt")).isEqualTo(1)
        assertThat(count("test_result")).isZero()
        assertThat(results(first.path("testRunId").asText()).path("items").size()).isZero()
    }
    @Test fun `unlocked release wrong plan and missing capability are rejected`() {
        val wrong=body().deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (wrong.path("testPlan") as com.fasterxml.jackson.databind.node.ObjectNode).put("version",3)
        assertThatThrownBy { create.create(user,wrong,"bad-plan","req") }.isInstanceOf(TestRunConflict::class.java)
        jdbc.sql("UPDATE agent SET capabilities='[]' WHERE id=:a").param("a",agent).update()
        assertThatThrownBy { run() }.isInstanceOf(TestRunConflict::class.java)
        jdbc.sql("UPDATE release_record SET locked_manifest_id=null,status='DRAFT' WHERE id=:r").param("r",release).update()
        assertThatThrownBy { run() }.isInstanceOf(TestRunConflict::class.java)
    }
    @Test fun `concurrent runs on one device have exactly one winner`() {
        val outcomes=runConcurrently(2) { try { run(); "created" } catch (e:TestRunConflict) { e.code } }
        assertThat(outcomes.count { it=="created" }).isOne()
        assertThat(count("test_attempt")).isOne()
    }
    @Test fun `different Agents racing for the same Device cannot bypass the unique reservation`() {
        val otherAgent="agt_"+UUID.randomUUID().toString().replace("-","")
        val otherFingerprint=UUID.randomUUID().toString().replace("-","").repeat(2)
        jdbc.sql("""INSERT INTO agent(id,principal_id,project_id,device_id,certificate_sha256,negotiated_protocol,
            capabilities,registered_at,created_at) SELECT :other,principal_id,project_id,device_id,:fingerprint,
            negotiated_protocol,capabilities,registered_at,created_at FROM agent WHERE id=:id""")
            .param("other",otherAgent).param("fingerprint",otherFingerprint).param("id",agent).update()
        val selection=java.util.concurrent.atomic.AtomicInteger()
        `when`(source.load()).thenAnswer { SmokeEnvironment(if(selection.getAndIncrement()==0) agent else otherAgent,device,envBytes) }
        val outcomes=runConcurrently(2) { try { run(); "created" } catch(e:TestRunConflict) { e.code } }
        assertThat(outcomes.count { it=="created" }).isOne()
        assertThat(outcomes.count { it=="DEVICE_BUSY" }).isOne()
        assertThat(count("test_attempt")).isOne()
    }
    @Test fun `changed CONFIG bytes and mismatched selector cannot create a Run`() {
        `when`(source.load()).thenReturn(SmokeEnvironment(agent,device,envBytes+"\n".toByteArray()))
        assertThatThrownBy { run() }.isInstanceOf(TestRunConflict::class.java).extracting("code").isEqualTo("ENVIRONMENT_CONFIG_MISMATCH")
        `when`(source.load()).thenReturn(SmokeEnvironment(agent,device,envBytes))
        val wrong=body().deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (wrong.path("deviceSelector") as com.fasterxml.jackson.databind.node.ObjectNode).put("vehicle","another")
        assertThatThrownBy { create.create(user,wrong,"mismatch","mismatch") }.isInstanceOf(TestRunConflict::class.java)
        assertThat(count("test_attempt")).isZero()
    }
    @Test fun `terminal histories and Results reject mutations and cross Run association`() {
        val id=run().path("testRunId").asText()
        cancel.cancel(user,id,"Stop","stop","stop")
        assertThatThrownBy { jdbc.sql("UPDATE test_result SET status='PASS' WHERE test_run_id=:r").param("r",id).update() }
            .isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        assertThatThrownBy { jdbc.sql("UPDATE test_attempt SET state='RUNNING',finished_at=null WHERE test_run_id=:r").param("r",id).update() }
            .isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        assertThat(jdbc.sql("SELECT count(*) FROM test_run_state_history WHERE test_run_id=:r AND state='CANCELLED'")
            .param("r",id).query(Int::class.java).single()).isOne()
        val next=run().path("testRunId").asText()
        assertThatThrownBy { jdbc.sql("""INSERT INTO test_result(id,attempt_id,test_run_id,status,origin,result_digest,result,created_at)
            SELECT :id,a.id,:old,'ERROR','SERVER',:digest,'{}',now() FROM test_attempt a WHERE a.test_run_id=:next""")
            .param("digest","sha256:"+"a".repeat(64))
            .param("id","res_"+UUID.randomUUID().toString().replace("-","")).param("next",next).param("old",id).update() }
            .isInstanceOf(org.springframework.dao.DataAccessException::class.java)
    }
    @Test fun `cancel before dispatch writes one BLOCKED result without a start time and releases reservation`() {
        val id=run().path("testRunId").asText()
        cancel.cancel(user,id,"Operator request","cancel","req")
        cancel.cancel(user,id,"Operator request","cancel","req")
        assertThat(count("test_result")).isOne()
        val result=results(id).path("items")[0]
        assertThat(result.path("status").asText()).isEqualTo("BLOCKED")
        assertThat(result.path("startedAt").isNull).isTrue()
        assertThat(run().path("state").asText()).isEqualTo("WAITING_FOR_AGENT")
    }
}
