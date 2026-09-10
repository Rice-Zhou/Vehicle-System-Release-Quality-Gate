package com.ricezhou.vsrqg.evidence.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.evidence.application.*
import com.ricezhou.vsrqg.evidence.domain.EvidenceState
import com.ricezhou.vsrqg.testmanagement.application.AttemptBinding
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Repository
class JdbcEvidenceRepository(private val jdbc:JdbcClient,private val mapper:ObjectMapper):EvidenceRepository {
    private fun row(rs:ResultSet)=EvidenceSession(rs.getString("id"),rs.getString("evidence_id"),
        AttemptBinding(rs.getString("attempt_id"),rs.getString("test_run_id"),rs.getString("release_id"),rs.getString("project_id"),
            rs.getString("agent_id"),rs.getString("device_id"),rs.getString("lease_id"),rs.getLong("fencing_token")),
        mapper.readTree(rs.getString("request")),EvidenceState.valueOf(rs.getString("state")),rs.getTimestamp("expires_at").toInstant(),
        rs.getTimestamp("created_at").toInstant(),rs.getString("metadata")?.let(mapper::readTree),rs.getString("sensitivity"),
        rs.getTimestamp("retention_until")?.toInstant(),rs.getBoolean("legal_hold"))
    override fun session(id:String,lock:Boolean)=find("id",id,lock)
    override fun evidence(id:String,lock:Boolean)=find("evidence_id",id,lock)
    private fun find(column:String,id:String,lock:Boolean)=jdbc.sql("SELECT * FROM evidence_upload_session WHERE $column=:id"+if(lock) " FOR UPDATE" else "")
        .param("id",id).query { rs,_->row(rs) }.optional().orElseThrow(::EvidenceNotFound)
    override fun insert(session:EvidenceSession) {
        val b=session.binding
        jdbc.sql("""INSERT INTO evidence_upload_session(id,evidence_id,attempt_id,test_run_id,release_id,project_id,agent_id,device_id,
            lease_id,fencing_token,request,state,expires_at,created_at,sensitivity) VALUES (:id,:e,CAST(:a AS uuid),:r,:rel,:p,:agt,:d,:l,:f,CAST(:body AS jsonb),'PENDING_UPLOAD',:exp,:now,:s)""")
            .param("id",session.id).param("e",session.evidenceId).param("a",b.attemptId).param("r",b.runId).param("rel",b.releaseId)
            .param("p",b.projectId).param("agt",b.agentId).param("d",b.deviceId).param("l",b.leaseId).param("f",b.fencingToken)
            .param("body",session.request.toString()).param("exp",Timestamp.from(session.expiresAt)).param("now",Timestamp.from(session.createdAt))
            .param("s",session.sensitivity).update()
    }
    override fun state(id:String,state:EvidenceState) { check(jdbc.sql("UPDATE evidence_upload_session SET state=:s WHERE id=:id").param("id",id).param("s",state.name).update()==1) }
    override fun available(id:String,metadata:JsonNode) { check(jdbc.sql("UPDATE evidence_upload_session SET state='AVAILABLE',metadata=CAST(:m AS jsonb) WHERE id=:id")
        .param("id",id).param("m",metadata.toString()).update()==1) }
    override fun sessions(attemptId:String)=jdbc.sql("SELECT * FROM evidence_upload_session WHERE attempt_id=CAST(:a AS uuid) ORDER BY id FOR UPDATE")
        .param("a",attemptId).query { rs,_->row(rs) }.list()
    override fun inventory(ids:Set<String>)=ids.sorted().map { evidence(it) }
    override fun observe(id:String,code:String,now:Instant) { jdbc.sql("INSERT INTO evidence_integrity_observation(evidence_id,code,observed_at) VALUES (:e,:c,:t)")
        .param("e",id).param("c",code).param("t",Timestamp.from(now)).update() }
    override fun latestObservation(id:String):String?=jdbc.sql("SELECT code FROM evidence_integrity_observation WHERE evidence_id=:e ORDER BY sequence_no DESC LIMIT 1")
        .param("e",id).query(String::class.java).optional().orElse(null)
    override fun saveGrant(grant:DownloadGrant) { jdbc.sql("INSERT INTO evidence_download_grant(id,actor_id,project_id,evidence_id,purpose,expires_at) VALUES (:id,:a,:p,:e,:why,:exp)")
        .param("id",grant.id).param("a",grant.actorId).param("p",grant.projectId).param("e",grant.evidenceId).param("why",grant.purpose).param("exp",Timestamp.from(grant.expiresAt)).update() }
    override fun grant(id:String)=jdbc.sql("SELECT * FROM evidence_download_grant WHERE id=:id").param("id",id).query { rs,_->
        DownloadGrant(rs.getString("id"),rs.getString("actor_id"),rs.getString("project_id"),rs.getString("evidence_id"),rs.getString("purpose"),rs.getTimestamp("expires_at").toInstant())
    }.optional().orElseThrow(::EvidenceNotFound)
}
