package com.ricezhou.vsrqg.testmanagement

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import com.ricezhou.vsrqg.shared.id.UuidV7IdGenerator
import com.ricezhou.vsrqg.testmanagement.adapter.JdbcTestRunRepository
import com.ricezhou.vsrqg.testmanagement.application.TestRunConflict
import org.assertj.core.api.Assertions.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.util.UUID
import javax.sql.DataSource

@Timeout(60)
class TestRunMigrationIntegrationTest : PostgresIntegrationTest() {
    @Autowired lateinit var dataSource:DataSource
    @Test fun `V12 upgrade preserves Device history and V13 never publishes test definitions implicitly`() {
        val schema="run_v13_"+UUID.randomUUID().toString().replace("-","")
        fun migration(target:String?=null):Flyway {
            val config=Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema).cleanDisabled(false)
            if(target!=null) config.target(target)
            return config.load()
        }
        val current=migration()
        try {
            migration("12").migrate()
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("SET search_path TO $schema") }
                try {
                    val jdbc=JdbcClient.create(SingleConnectionDataSource(connection,true))
                    jdbc.sql("INSERT INTO project(id,project_key,name,created_at) VALUES ('p_old','old','old',now())").update()
                    jdbc.sql("INSERT INTO device(id,project_id,created_at) VALUES ('d_old','p_old',now())").update()
                } finally { connection.createStatement().use { it.execute("SET search_path TO public") } }
            }
            assertThat(current.migrate().migrationsExecuted).isOne()
            assertThat(current.migrate().migrationsExecuted).isZero()
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("SET search_path TO $schema") }
                try {
                    val jdbc=JdbcClient.create(SingleConnectionDataSource(connection,true))
                    assertThat(jdbc.sql("SELECT count(*) FROM device WHERE id='d_old' AND disabled=false").query(Int::class.java).single()).isOne()
                    val repository=JdbcTestRunRepository(jdbc,jacksonObjectMapper(),UuidV7IdGenerator())
                    assertThatThrownBy { repository.plan("single-device-smoke",1) }.isInstanceOf(TestRunConflict::class.java)
                    assertThat(jdbc.sql("SELECT count(*) FROM test_plan_version").query(Int::class.java).single()).isZero()
                    jdbc.sql("INSERT INTO test_plan_version(id,plan_id,version,state,max_attempts,created_at) VALUES ('tpv_draft','single-device-smoke',1,'DRAFT',1,now())").update()
                    jdbc.sql("INSERT INTO test_case_version(id,case_id,version,state,definition,created_at) VALUES ('tcv_draft','apk-launch-smoke',1,'DRAFT','{}',now())").update()
                    jdbc.sql("INSERT INTO test_plan_case(plan_version_id,case_version_id,ordinal,required) VALUES ('tpv_draft','tcv_draft',0,true)").update()
                    assertThatThrownBy { repository.plan("single-device-smoke",1) }.isInstanceOf(TestRunConflict::class.java)
                    assertThat(jdbc.sql("SELECT state FROM test_plan_version").query(String::class.java).single()).isEqualTo("DRAFT")
                } finally { connection.createStatement().use { it.execute("SET search_path TO public") } }
            }
        } finally { current.clean() }
    }
}
