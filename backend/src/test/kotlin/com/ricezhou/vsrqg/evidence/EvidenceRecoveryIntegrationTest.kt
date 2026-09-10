package com.ricezhou.vsrqg.evidence
import com.ricezhou.vsrqg.testmanagement.application.AttemptEvidence
import com.ricezhou.vsrqg.testmanagement.application.EvidenceResolution

import com.ricezhou.vsrqg.evidence.adapter.ControlledPayloadStore
import com.ricezhou.vsrqg.evidence.adapter.JdbcEvidenceRepository
import com.ricezhou.vsrqg.evidence.application.*
import com.ricezhou.vsrqg.shared.runConcurrently
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.util.UUID

class EvidenceRecoveryIntegrationTest:EvidenceFixture() {
    @org.springframework.beans.factory.annotation.Autowired lateinit var attemptEvidence:AttemptEvidence
    @Autowired lateinit var transactions:PlatformTransactionManager
    @Test fun `pending network candidate holds no business locks and cancel defeats postflight publication`() {
        start();val session=apiCreate();val id=session.path("uploadId").asText()
        val prepared=uploads.prepare(fingerprint,id)
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
        payloads.receive(id,prepared.expected).use { candidate->
            candidate.append("h".toByteArray(),1)
            java.util.concurrent.Executors.newSingleThreadExecutor().use { executor->
                executor.submit { cancel.cancel(user,prepared.binding.runId,"during receive","cancel","cancel") }
                    .get(2,java.util.concurrent.TimeUnit.SECONDS)
            }
            candidate.append("ello".toByteArray(),4)
            assertThatThrownBy { uploads.received(fingerprint,prepared,candidate) }
                .isInstanceOf(com.ricezhou.vsrqg.testmanagement.application.TestRunConflict::class.java)
        }
        assertThat(Files.exists(storage.resolve("$id.payload"))).isFalse()
        Files.list(storage).use { files->assertThat(files.anyMatch { it.fileName.toString().endsWith(".partial") }).isFalse() }
        assertThat(results(prepared.binding.runId).path("attempts").size()).isEqualTo(1)
    }
    @Test fun `deadline worker progresses during pending receive and late EOF cannot publish`() {
        start();val session=apiCreate();val id=session.path("uploadId").asText();val prepared=uploads.prepare(fingerprint,id)
        payloads.receive(id,prepared.expected).use { candidate->
            candidate.append("hello".toByteArray(),5)
            now=now.plusSeconds(900)
            java.util.concurrent.Executors.newSingleThreadExecutor().use { executor->
                executor.submit { deadlines.advance(prepared.binding.runId) }.get(2,java.util.concurrent.TimeUnit.SECONDS)
            }
            assertThatThrownBy { uploads.received(fingerprint,prepared,candidate) }
                .isInstanceOf(com.ricezhou.vsrqg.testmanagement.application.TestRunConflict::class.java)
        }
        assertThat(Files.exists(storage.resolve("$id.payload"))).isFalse()
        assertThat(results(prepared.binding.runId).path("attempts").size()).isEqualTo(1)
    }
    @Test fun `DB rollback retains exact file and same Session retry atomically creates metadata`() {
        start(); val body=declaration(); val session=apiCreate(body); val id=session.path("uploadId").asText(); apiPut(id)
        assertThatThrownBy { TransactionTemplate(transactions).execute {
            uploads.complete(fingerprint,id,completed(body),"complete","rollback")
            error("synthetic transaction failure")
        } }.hasMessage("synthetic transaction failure")
        assertThat(downloads.metadata(user,session.path("evidenceId").asText()).path("state").asText()).isEqualTo("UPLOADING")
        assertThat(recovery.reconcile(setOf(session.path("evidenceId").asText())).single().code).isEqualTo("ORPHAN_RETRY_SESSION")
        apiPut(id); apiComplete(id,body,key="complete")
    }
    @Test fun `missing damaged payload adds observations without rewriting terminal Result or Metadata`() {
        start(); val (session,metadata)=available(); val id=metadata.path("evidenceId").asText()
        val runId=metadata.path("testRunId").asText(); cancel.cancel(user,runId,"synthetic cancel","cancel","cancel")
        val resultBefore=results(runId).toString()
        val metadataBefore=jdbc.sql("SELECT metadata::text FROM evidence_upload_session WHERE evidence_id=:e").param("e",id).query(String::class.java).single()
        Files.write(EvidenceFixture.storage.resolve("${session.path("uploadId").asText()}.payload"),"tamper".toByteArray())
        assertThat(recovery.reconcile(setOf(id)).single().code).isEqualTo("INTEGRITY_ERROR")
        assertThat(downloads.metadata(user,id).path("integrity").asText()).isEqualTo("INTEGRITY_ERROR")
        Files.delete(EvidenceFixture.storage.resolve("${session.path("uploadId").asText()}.payload"))
        assertThat(recovery.reconcile(setOf(id)).single().code).isEqualTo("INTEGRITY_ERROR")
        assertThat(results(runId).toString()).isEqualTo(resultBefore)
        assertThat(jdbc.sql("SELECT metadata::text FROM evidence_upload_session WHERE evidence_id=:e").param("e",id).query(String::class.java).single()).isEqualTo(metadataBefore)
    }
    @Test fun `cancel racing Complete yields only serialized metadata and terminal facts`() {
        start(); val body=declaration(); val session=apiCreate(body); val id=session.path("uploadId").asText(); apiPut(id)
        val runId=jdbc.sql("SELECT test_run_id FROM test_attempt WHERE id=CAST(:a AS uuid)").param("a",command.path("attemptId").asText()).query(String::class.java).single()
        val index=java.util.concurrent.atomic.AtomicInteger()
        val outcomes=runConcurrently(2) {
            if(index.getAndIncrement()==0) { cancel.cancel(user,runId,"race","cancel","cancel"); "CANCELLED" }
            else try { uploads.complete(fingerprint,id,completed(body),"complete","complete"); "AVAILABLE" }
                catch(_:com.ricezhou.vsrqg.testmanagement.application.TestRunConflict) { "STALE_LEASE" }
        }
        assertThat(outcomes).contains("CANCELLED")
        assertThat(outcomes).anyMatch { it=="AVAILABLE" || it=="STALE_LEASE" }
        assertThat(results(runId).path("attempts").size()).isEqualTo(1)
    }
    @Test fun `paired PostgreSQL dump and fixed payload inventory restore verifies every hash`() {
        start(); val (session,metadata)=available(); val id=metadata.path("evidenceId").asText()
        val inventory=recovery.backupInventory(setOf(id))
        val restoredRoot=ownedTestRoot(Files.createTempDirectory("evidence-restored-"))
        inventory.forEach { item->Files.copy(EvidenceFixture.storage.resolve("${item.uploadId}.payload"),restoredRoot.resolve("${item.uploadId}.payload")) }
        val dbName="evidence_restore_"+UUID.randomUUID().toString().replace("-","")
        val dump="/tmp/$dbName.dump"
        fun command(vararg arguments:String) {
            val result=postgres.execInContainer(*arguments)
            assertThat(result.exitCode).describedAs("PostgreSQL backup or restore failed: %s",result.stderr).isZero()
        }
        command("pg_dump","-U",postgres.username,"-d",postgres.databaseName,"-Fc","-f",dump)
        command("createdb","-U",postgres.username,dbName)
        try {
            command("pg_restore","-U",postgres.username,"-d",dbName,dump)
            val url=postgres.jdbcUrl.substringBeforeLast('/')+"/"+dbName
            val restoredJdbc=JdbcClient.create(DriverManagerDataSource(url,postgres.username,postgres.password))
            val restored=EvidenceReconciler(JdbcEvidenceRepository(restoredJdbc,mapper),ControlledPayloadStore(restoredRoot),clock)
            assertThat(restored.verifyRestored(inventory).map { it.code }).containsExactly("VERIFIED")
            Files.write(restoredRoot.resolve("${session.path("uploadId").asText()}.payload"),"wrong".toByteArray())
            assertThat(restored.verifyRestored(inventory).single().code).isEqualTo("INTEGRITY_ERROR")
        } finally { command("dropdb","-U",postgres.username,"--force",dbName) }
    }
    @Test fun `audit SQL failure rolls back Complete and fails download closed`() {
        start();val body=declaration();val session=apiCreate(body);val id=session.path("uploadId").asText();apiPut(id)
        val name="evfail_"+UUID.randomUUID().toString().replace("-","")
        require(Regex("[a-z0-9_]+").matches(name) && Regex("[a-z0-9_]+").matches(project))
        jdbc.sql("CREATE FUNCTION $name() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''SYNTHETIC_AUDIT_FAILURE''; END'").update()
        fun install() { jdbc.sql("CREATE TRIGGER $name BEFORE INSERT ON audit_event FOR EACH ROW WHEN (NEW.project_id='$project') EXECUTE FUNCTION $name()").update() }
        fun remove() { jdbc.sql("DROP TRIGGER $name ON audit_event").update() }
        try {
            install()
            assertThatThrownBy { uploads.complete(fingerprint,id,completed(body),"complete","audit-fail") }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
            remove()
            assertThat(downloads.metadata(user,session.path("evidenceId").asText()).path("state").asText()).isEqualTo("UPLOADING")
            val metadata=uploads.complete(fingerprint,id,completed(body),"complete","audit-retry")
            val evidence=metadata.path("evidenceId").asText()
            val grant=downloads.request(user,evidence,"diagnose","grant","grant",false)
            install()
            assertThatThrownBy { downloads.open(user,evidence,grant.path("url").asText().substringAfter("grantId="),"audit-fail",false) }
                .isInstanceOf(org.springframework.dao.DataAccessException::class.java)
            remove()
        } finally { jdbc.sql("DROP TRIGGER IF EXISTS $name ON audit_event").update();jdbc.sql("DROP FUNCTION $name()").update() }
    }
    @Test fun `composite FKs and terminal Metadata reject cross Run and mutable history`() {
        start();val (session,metadata)=available();val id=session.path("uploadId").asText()
        assertThatThrownBy { jdbc.sql("UPDATE evidence_upload_session SET metadata=jsonb_set(metadata,'{sizeBytes}','999') WHERE id=:id").param("id",id).update() }
            .isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        assertThatThrownBy { jdbc.sql("""INSERT INTO evidence_upload_session(id,evidence_id,attempt_id,test_run_id,release_id,project_id,agent_id,device_id,
            lease_id,fencing_token,request,state,expires_at,created_at,sensitivity)
            SELECT 'upl_wrongrun','ev_wrongrun',attempt_id,'run_other',release_id,project_id,agent_id,device_id,lease_id,fencing_token,request,'PENDING_UPLOAD',expires_at,created_at,sensitivity
            FROM evidence_upload_session WHERE id=:id""").param("id",id).update() }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        assertThat(jdbc.sql("SELECT metadata->>'evidenceId' FROM evidence_upload_session WHERE id=:id").param("id",id).query(String::class.java).single()).isEqualTo(metadata.path("evidenceId").asText())
    }
    @Test fun `AttemptEvidence resolves LOG and PNG and seals only incomplete sessions under Attempt lock`() {
        start();val (_,log)=available();val (_,png)=available(byteArrayOf(137.toByte(),80,78,71,13,10,26,10),"SCREENSHOT")
        val pending=apiCreate(declaration())
        TransactionTemplate(transactions).execute {
            val binding=attempts.lockWritable(com.ricezhou.vsrqg.testmanagement.application.AgentActor(serviceId,project,agent,device),command.path("attemptId").asText(),now)
            val ids=setOf(log.path("evidenceId").asText(),png.path("evidenceId").asText())
            assertThat(attemptEvidence.resolve(binding,ids)).isEqualTo(EvidenceResolution(ids,emptySet()))
            assertThat(attemptEvidence.resolve(binding,setOf(log.path("evidenceId").asText())).failedRequiredTypes).containsExactly("SCREENSHOT")
            attemptEvidence.seal(binding,now)
        }
        assertThat(downloads.metadata(user,pending.path("evidenceId").asText()).path("state").asText()).isEqualTo("EXPIRED")
        assertThat(downloads.metadata(user,log.path("evidenceId").asText()).path("state").asText()).isEqualTo("AVAILABLE")
    }
}
