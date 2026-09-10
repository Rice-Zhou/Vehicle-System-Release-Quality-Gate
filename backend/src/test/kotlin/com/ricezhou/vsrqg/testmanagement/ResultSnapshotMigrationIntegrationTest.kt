package com.ricezhou.vsrqg.testmanagement

import com.ricezhou.vsrqg.access.adapter.JdbcProjectAuthorizer
import com.ricezhou.vsrqg.evidence.adapter.JdbcEvidenceRepository
import com.ricezhou.vsrqg.evidence.application.PayloadStore
import com.ricezhou.vsrqg.evidence.application.ResolveAttemptEvidence
import com.ricezhou.vsrqg.shared.adapter.JdbcGovernanceStore
import com.ricezhou.vsrqg.shared.adapter.JdbcIdempotentExecutor
import com.ricezhou.vsrqg.shared.id.UuidV7IdGenerator
import com.ricezhou.vsrqg.testmanagement.adapter.JdbcAgentAccess
import com.ricezhou.vsrqg.testmanagement.adapter.JdbcTestRunRepository
import com.ricezhou.vsrqg.testmanagement.application.*
import org.assertj.core.api.Assertions.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID

@Timeout(60)
class ResultSnapshotMigrationIntegrationTest:RunFixture() {
    @Test fun `V15 preserves real server history requires old active snapshots and forbids policy bypass`() {
        val closed=run().path("testRunId").asText()
        cancel.cancel(user,closed,"migration fixture","cancel","cancel")
        val before=results(closed)
        val active=run().path("testRunId").asText()
        val db="result_migrate_"+UUID.randomUUID().toString().replace("-","")
        val dump="/tmp/$db.dump"
        fun command(vararg arguments:String) {
            val result=postgres.execInContainer(*arguments)
            assertThat(result.exitCode).describedAs(result.stderr).isZero()
        }
        command("pg_dump","-U",postgres.username,"-d",postgres.databaseName,"-Fc","-f",dump)
        command("createdb","-U",postgres.username,db)
        try {
            command("pg_restore","-U",postgres.username,"-d",db,dump)
            val source=DriverManagerDataSource(postgres.jdbcUrl.substringBeforeLast('/')+"/"+db,postgres.username,postgres.password)
            val restored=JdbcClient.create(source)
            // Reconstruct the V14 schema over actual application-created server history in an isolated backup.
            restored.sql("DROP TRIGGER terminal_run_snapshot ON test_run").update()
            restored.sql("DROP TRIGGER guard_run_snapshot ON test_run").update()
            restored.sql("DROP FUNCTION require_terminal_snapshot()").update()
            restored.sql("DROP FUNCTION guard_run_snapshot()").update()
            restored.sql("ALTER TABLE test_run DROP COLUMN terminal_snapshot,DROP COLUMN input_digest,DROP COLUMN snapshot_required").update()
            restored.sql("DELETE FROM flyway_schema_history WHERE version='15'").update()
            val facts=restored.sql("SELECT result::text FROM test_result WHERE test_run_id=:id").param("id",closed).query(String::class.java).single()
            assertThat(Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate().migrationsExecuted).isOne()
            val ids=UuidV7IdGenerator()
            val repository=JdbcTestRunRepository(restored,mapper,ids)
            val authorizer=JdbcProjectAuthorizer(restored)
            assertThat(GetTestRunResults(repository,authorizer).get(user,closed)).isEqualTo(before)
            assertThat(restored.sql("SELECT result::text FROM test_result WHERE test_run_id=:id").param("id",closed).query(String::class.java).single()).isEqualTo(facts)
            assertThat(restored.sql("SELECT snapshot_required FROM test_run WHERE id=:id").param("id",closed).query(Boolean::class.java).single()).isFalse()
            assertThat(restored.sql("SELECT snapshot_required FROM test_run WHERE id=:id").param("id",active).query(Boolean::class.java).single()).isTrue()
            assertThatThrownBy { restored.sql("UPDATE test_run SET snapshot_required=false WHERE id=:id").param("id",active).update() }
                .isInstanceOf(org.springframework.dao.DataAccessException::class.java)
            val broken=object:TestRunRepository by repository {
                override fun saveTerminalSnapshot(runId:String,snapshot:com.fasterxml.jackson.databind.JsonNode) { /* Simulate omitted persistence. */ }
            }
            val governance=JdbcGovernanceStore(restored,ids,clock)
            val evidence=ResolveAttemptEvidence(JdbcEvidenceRepository(restored,mapper),DefaultListableBeanFactory().getBeanProvider(PayloadStore::class.java))
            val lifecycle=TestRunLifecycle(broken,governance,mapper,JdbcAgentAccess(restored,authorizer),evidence)
            val cancel=CancelTestRun(broken,authorizer,JdbcIdempotentExecutor(restored,mapper,ids,clock,Duration.ofDays(1)),lifecycle,clock,mapper)
            val transaction=TransactionTemplate(DataSourceTransactionManager(source))
            assertThatThrownBy { transaction.executeWithoutResult { cancel.cancel(user,active,"missing snapshot","missing","missing") } }
                .hasRootCauseInstanceOf(java.sql.SQLException::class.java)
            assertThat(repository.run(active).state.name).isEqualTo("WAITING_FOR_AGENT")
            assertThat(repository.results(active)).isEmpty()
            assertThatThrownBy {
                restored.sql("""INSERT INTO test_run(id,release_id,project_id,manifest_revision_id,manifest_digest,plan_version_id,
                    environment_snapshot_id,agent_id,device_id,created_by,state,allocation_deadline,deadline,created_at,updated_at,finished_at,snapshot_required)
                    SELECT 'run_false',release_id,project_id,manifest_revision_id,manifest_digest,plan_version_id,
                    environment_snapshot_id,agent_id,device_id,created_by,'CANCELLED',allocation_deadline,deadline,created_at,updated_at,now(),false
                    FROM test_run WHERE id=:id""").param("id",active).update()
            }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        } finally { command("dropdb","-U",postgres.username,"--force",db) }
    }
}
