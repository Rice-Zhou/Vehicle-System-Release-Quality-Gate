package com.ricezhou.vsrqg.testmanagement.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.ricezhou.vsrqg.shared.adapter.toJdbcTimestamp
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.testmanagement.application.*
import com.ricezhou.vsrqg.testmanagement.domain.*
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Repository
class JdbcTestRunRepository(private val jdbc:JdbcClient,private val mapper:ObjectMapper,private val ids:IdGenerator):TestRunRepository {
    override fun lockAgent(agentId:String):AgentSelection {
        // Match AgentAccess: Agent first, then shared identity references. Never upgrade Device locks.
        jdbc.sql("SELECT id FROM agent WHERE id=:id FOR UPDATE").param("id",agentId).query(String::class.java).optional()
            .orElseThrow { missing() }
        return jdbc.sql("""
            SELECT a.*,d.vehicle,d.platform
            FROM agent a JOIN device d ON d.id=a.device_id JOIN principal p ON p.id=a.principal_id
              JOIN project pr ON pr.id=a.project_id
            WHERE a.id=:id FOR SHARE OF d,p,pr
        """.trimIndent()).param("id",agentId).query { row,_ ->
            AgentSelection(AgentActor(row.getString("principal_id"),row.getString("project_id"),agentId,row.getString("device_id")),
                row.getString("certificate_sha256"),row.getString("vehicle"),row.getString("platform"),
                row.getString("capabilities")?.let { mapper.readTree(it).map(JsonNode::asText).toSet() } ?: emptySet(),
                row.getString("negotiated_protocol")=="1.0" && row.getTimestamp("registered_at")!=null)
        }.single()
    }
    override fun projectForRelease(releaseId:String):String = jdbc.sql("SELECT project_id FROM release_record WHERE id=:r")
        .param("r",releaseId).query(String::class.java).optional().orElseThrow { missing() }
    override fun manifest(releaseId:String):LockedTestManifest =
        jdbc.sql("""SELECT r.id,r.project_id,r.vehicle,r.platform,m.id AS manifest_id,m.content_digest,m.canonical_bytes
            FROM release_record r JOIN manifest_revision m ON m.id=r.locked_manifest_id AND m.release_id=r.id
            JOIN manifest_validation v ON v.id=m.locked_validation_id AND v.manifest_id=m.id
            WHERE r.id=:r AND m.state='LOCKED' AND v.status='VALID' AND v.content_digest=m.content_digest""")
            .param("r",releaseId).query { row,_ ->
                val bytes=row.getBytes("canonical_bytes")
                if(TestJson.sha256(bytes)!=row.getString("content_digest")) throw TestRunConflict("MANIFEST_CONTENT_DIGEST_MISMATCH")
                LockedTestManifest(row.getString("id"),row.getString("project_id"),row.getString("manifest_id"),
                    row.getString("content_digest"),row.getString("vehicle"),row.getString("platform"),bytes)
            }.optional().orElseThrow { TestRunConflict("MANIFEST_NOT_LOCKED") }

    override fun plan(planId:String,version:Int):SmokePlan {
        if(planId!="single-device-smoke" || version !in 1..2) throw TestRunConflict("TEST_PLAN_UNSUPPORTED")
        return jdbc.sql("""SELECT p.id AS pid,c.id AS cid,c.version,c.definition
            FROM test_plan_version p JOIN test_plan_case pc ON pc.plan_version_id=p.id
            JOIN test_case_version c ON c.id=pc.case_version_id
            WHERE p.plan_id=:p AND p.version=:v AND p.state='PUBLISHED' AND c.state='PUBLISHED'
              AND pc.required=true AND pc.ordinal=0 AND p.max_attempts=1 AND c.case_id='apk-launch-smoke' AND c.version=p.version""")
            .param("p",planId).param("v",version).query { r,_ ->
                SmokePlan(r.getString("pid"),r.getString("cid"),r.getInt("version"),mapper.readTree(r.getString("definition")))
            }.optional().orElseThrow { TestRunConflict("TEST_PLAN_UNSUPPORTED") }
    }
    override fun activeDeviceRun(deviceId:String):Boolean = jdbc.sql("SELECT EXISTS(SELECT 1 FROM test_run WHERE device_id=:d AND finished_at IS NULL)")
        .param("d",deviceId).query(Boolean::class.java).single()

    override fun insert(run:NewRun) {
        val r=run.run; val a=run.attempt
        jdbc.sql("""INSERT INTO environment_snapshot(id,project_id,agent_id,device_id,config_bytes,content_digest,environment,created_at)
            VALUES (:id,:p,:ag,:d,:bytes,:hash,CAST(:env AS jsonb),:now)""").param("id",run.environmentId)
            .param("p",r.projectId).param("ag",r.agentId).param("d",r.deviceId).param("bytes",run.configBytes)
            .param("hash",TestJson.sha256(run.configBytes)).param("env",run.environment.toString()).param("now",r.createdAt.toJdbcTimestamp()).update()
        try {
            jdbc.sql("""INSERT INTO test_run(id,release_id,project_id,manifest_revision_id,manifest_digest,plan_version_id,
                environment_snapshot_id,agent_id,device_id,created_by,state,allocation_deadline,deadline,created_at,updated_at)
                VALUES (:id,:release,:p,:m,:hash,:plan,:env,:ag,:d,:creator,:state,:allocation,:deadline,:now,:now)""")
                .param("id",r.id).param("release",r.releaseId).param("p",r.projectId).param("m",run.manifest.manifestId)
                .param("hash",run.manifest.digest).param("plan",run.plan.planVersionId).param("env",run.environmentId)
                .param("ag",r.agentId).param("d",r.deviceId).param("creator",r.createdBy).param("state",r.state.name)
                .param("allocation",r.allocationDeadline.toJdbcTimestamp()).param("deadline",r.deadline.toJdbcTimestamp())
                .param("now",r.createdAt.toJdbcTimestamp()).update()
        } catch(e:DataIntegrityViolationException) {
            // Only the device reservation index is a business conflict; other integrity failures stay visible.
            if(e.mostSpecificCause.message?.contains("uq_active_run_device")==true) throw TestRunConflict("DEVICE_BUSY")
            throw e
        }
        jdbc.sql("""INSERT INTO test_attempt(id,test_run_id,project_id,agent_id,device_id,case_version_id,attempt_no,state,
            command_id,lease_id,fencing_token,context,context_digest,created_at,updated_at)
            VALUES (:id,:r,:p,:ag,:d,:c,1,'QUEUED',:cmd,:lease,1,CAST(:context AS jsonb),:hash,:now,:now)""")
            .param("id",UUID.fromString(a.id)).param("r",r.id).param("p",r.projectId).param("ag",r.agentId)
            .param("d",r.deviceId).param("c",run.plan.caseVersionId).param("cmd",a.commandId).param("lease",a.leaseId)
            .param("context",a.context.toString()).param("hash",TestJson.digest(a.context)).param("now",r.createdAt.toJdbcTimestamp()).update()
    }
    override fun run(id:String,lock:Boolean):RunRecord = jdbc.sql("SELECT * FROM test_run WHERE id=:id"+if(lock) " FOR UPDATE" else "")
        .param("id",id).query { r,_ -> RunRecord(r.getString("id"),r.getString("release_id"),r.getString("project_id"),
            r.getString("agent_id"),r.getString("device_id"),r.getString("created_by"),RunState.valueOf(r.getString("state")),
            r.instant("allocation_deadline")!!,r.instant("deadline")!!,r.instant("started_at"),r.instant("finished_at"),r.instant("created_at")!!)
        }.optional().orElseThrow { missing() }
    override fun attempt(runId:String,lock:Boolean):AttemptRecord =
        jdbc.sql("SELECT * FROM test_attempt WHERE test_run_id=:id"+if(lock) " FOR UPDATE" else "").param("id",runId).query { r,_ ->
            val context=mapper.readTree(r.getString("context"))
            if(TestJson.digest(context)!=r.getString("context_digest")) throw TestRunConflict("CONTEXT_INTEGRITY_ERROR")
            AttemptRecord(r.getString("id"),runId,AttemptState.valueOf(r.getString("state")),r.getString("command_id"),
                r.getString("lease_id"),r.getLong("fencing_token"),r.instant("lease_expires_at"),r.instant("case_deadline"),
                r.instant("recovery_deadline"),r.getString("recovery_state")?.let(AttemptState::valueOf),context,
                r.instant("started_at"),r.instant("finished_at"))
        }.optional().orElseThrow { missing() }
    override fun runForAttempt(attemptId:String):String {
        val uuid=try { UUID.fromString(attemptId) } catch(_:IllegalArgumentException) { throw missing() }
        if(uuid.toString()!=attemptId) throw missing()
        return jdbc.sql("SELECT test_run_id FROM test_attempt WHERE id=:id").param("id",uuid).query(String::class.java).optional().orElseThrow { missing() }
    }
    override fun runForCommand(commandId:String):String = jdbc.sql("SELECT test_run_id FROM agent_command WHERE id=:id")
        .param("id",commandId).query(String::class.java).optional().orElseThrow { missing() }
    override fun activeRun(agentId:String):String? = jdbc.sql("""SELECT r.id FROM test_run r JOIN agent a ON a.id=r.agent_id
        WHERE r.agent_id=:id AND r.finished_at IS NULL
          AND (a.heartbeat IS NULL OR a.heartbeat->>'state' IN ('ONLINE','BUSY'))
        ORDER BY r.created_at,r.id LIMIT 1""")
        .param("id",agentId).query(String::class.java).optional().orElse(null)
    override fun activeRuns(afterId:String):List<String> = jdbc.sql("""SELECT id FROM test_run
        WHERE finished_at IS NULL AND id>:afterId ORDER BY id LIMIT 100""")
        .param("afterId",afterId).query(String::class.java).list()
    override fun updateRun(run:RunRecord,now:Instant) {
        check(jdbc.sql("UPDATE test_run SET state=:s,started_at=:start,finished_at=:finish,updated_at=:now WHERE id=:id")
            .param("s",run.state.name).param("start",run.startedAt?.toJdbcTimestamp()).param("finish",run.finishedAt?.toJdbcTimestamp())
            .param("now",now.toJdbcTimestamp()).param("id",run.id).update()==1)
    }
    override fun updateAttempt(attempt:AttemptRecord,now:Instant) {
        check(jdbc.sql("""UPDATE test_attempt SET state=:s,fencing_token=:f,lease_expires_at=:lease,case_deadline=:deadline,
            recovery_deadline=:recovery,recovery_state=:resume,started_at=:start,finished_at=:finish,updated_at=:now WHERE id=:id""")
            .param("s",attempt.state.name).param("f",attempt.fencingToken).param("lease",attempt.leaseExpiresAt?.toJdbcTimestamp())
            .param("deadline",attempt.caseDeadline?.toJdbcTimestamp()).param("recovery",attempt.recoveryDeadline?.toJdbcTimestamp())
            .param("resume",attempt.recoveryState?.name).param("start",attempt.startedAt?.toJdbcTimestamp())
            .param("finish",attempt.finishedAt?.toJdbcTimestamp()).param("now",now.toJdbcTimestamp()).param("id",UUID.fromString(attempt.id)).update()==1)
    }
    override fun command(commandId:String):JsonNode? = jdbc.sql("SELECT envelope::text FROM agent_command WHERE id=:id")
        .param("id",commandId).query(String::class.java).optional().map(mapper::readTree).orElse(null)
    override fun insertCommand(run:RunRecord,attempt:AttemptRecord,envelope:JsonNode,now:Instant) {
        jdbc.sql("""INSERT INTO agent_command(id,attempt_id,test_run_id,agent_id,idempotency_key,envelope,created_at)
            VALUES (:id,:a,:r,:ag,:key,CAST(:env AS jsonb),:now)""")
            .param("id",attempt.commandId).param("a",UUID.fromString(attempt.id)).param("r",run.id).param("ag",run.agentId)
            .param("key",attempt.id+":execute").param("env",envelope.toString()).param("now",now.toJdbcTimestamp()).update()
    }
    override fun ack(commandId:String):Pair<String,JsonNode>? = jdbc.sql("SELECT ack_request_digest,ack_response::text FROM agent_command WHERE id=:id AND ack_response IS NOT NULL")
        .param("id",commandId).query { r,_ -> r.getString("ack_request_digest") to mapper.readTree(r.getString("ack_response")) }.optional().orElse(null)
    override fun saveAck(commandId:String,digest:String,response:JsonNode) {
        check(jdbc.sql("UPDATE agent_command SET ack_request_digest=:d,ack_response=CAST(:r AS jsonb) WHERE id=:id AND ack_response IS NULL")
            .param("d",digest).param("r",response.toString()).param("id",commandId).update()==1)
    }
    override fun saveHeartbeat(agentId:String,body:JsonNode,now:Instant) {
        check(jdbc.sql("UPDATE agent SET last_heartbeat_at=:now,heartbeat=CAST(:body AS jsonb) WHERE id=:id")
            .param("now",now.toJdbcTimestamp()).param("body",body.toString()).param("id",agentId).update()==1)
    }
    override fun insertResult(run:RunRecord,attempt:AttemptRecord,result:JsonNode,now:Instant) {
        jdbc.sql("""INSERT INTO test_result(id,attempt_id,test_run_id,status,origin,result_digest,result,created_at)
            VALUES (:id,:a,:r,:s,'SERVER',:d,CAST(:body AS jsonb),:now)""").param("id",ids.nextId("res_"))
            .param("a",UUID.fromString(attempt.id)).param("r",run.id).param("s",result.path("status").asText())
            .param("d",TestJson.digest(result)).param("body",result.toString()).param("now",now.toJdbcTimestamp()).update()
    }
    override fun results(runId:String):List<JsonNode> = jdbc.sql("SELECT result::text,result_digest FROM test_result WHERE test_run_id=:id ORDER BY created_at,id")
        .param("id",runId).query { row,_ ->
            (mapper.readTree(row.getString("result")) as com.fasterxml.jackson.databind.node.ObjectNode)
                .put("resultDigest",row.getString("result_digest")) as JsonNode
        }.list()
    private fun missing()=ResourceNotFound("TEST_RESOURCE_NOT_FOUND","Test resource not found","Test resource not found")
    private fun ResultSet.instant(name:String)=getTimestamp(name)?.toInstant()
}
