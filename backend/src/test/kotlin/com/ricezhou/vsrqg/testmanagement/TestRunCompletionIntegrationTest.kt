package com.ricezhou.vsrqg.testmanagement

import com.ricezhou.vsrqg.shared.runConcurrently
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.UUID

@Timeout(60)
class TestRunCompletionIntegrationTest:ResultFixture() {
    @Test fun `cancel and deadline seal sessions and freeze unique server result`() {
        for(deadline in listOf(false,true)) {
            start();val id=runId(); val pending=apiCreate(declaration())
            if(deadline) { now=now.plusSeconds(601);deadlines.advance(id) }
            else cancel.cancel(user,id,"operator cancel","cancel","cancel")
            val result=query.get(user,id)
            assertThat(result.path("status").asText()).isEqualTo(if(deadline) "TIMEOUT" else "CANCELLED")
            assertThat(result.path("attempts")).hasSize(1)
            assertThat(result.path("attempts")[0].path("result").path("status").asText()).isEqualTo(if(deadline) "TIMEOUT" else "BLOCKED")
            assertThat(downloads.metadata(user,pending.path("evidenceId").asText()).path("state").asText()).isEqualTo("EXPIRED")
            assertThat(query.get(user,id)).isEqualTo(result)
            fixture()
        }
    }
    @Test fun `Result audit outbox and database failures rollback result attempt run and session closure`() {
        for(table in listOf("audit_event","outbox_event","test_result")) {
            start();val body=resultBody();val pending=apiCreate(declaration());val id=runId()
            val name="resultfail_"+UUID.randomUUID().toString().replace("-","")
            require(Regex("[a-z0-9_]+").matches(name))
            jdbc.sql("CREATE FUNCTION $name() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''SYNTHETIC_RESULT_FAILURE''; END'").update()
            jdbc.sql("CREATE TRIGGER $name BEFORE INSERT ON $table FOR EACH ROW EXECUTE FUNCTION $name()").update()
            try {
                assertThatThrownBy { submit.submit(actor(),body,"retry","failure") }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
            } finally {
                jdbc.sql("DROP TRIGGER $name ON $table").update();jdbc.sql("DROP FUNCTION $name()").update()
            }
            assertThat(count("test_result")).isZero()
            assertThat(query.get(user,id).path("status").asText()).isEqualTo("RUNNING")
            assertThat(downloads.metadata(user,pending.path("evidenceId").asText()).path("state").asText()).isEqualTo("PENDING_UPLOAD")
            submitHttp(body,key="retry");assertThat(count("test_result")).isOne()
            fixture()
        }
    }
    @Test fun `competing result complete and cancel leaves exactly one terminal result and no pending session`() {
        start();val body=resultBody();val id=runId();val pending=apiCreate(declaration())
        val next=java.util.concurrent.atomic.AtomicInteger()
        runConcurrently(2) {
            if(next.getAndIncrement()==0) {
                val error=runCatching { submit.submit(actor(),body,"race","race") }.exceptionOrNull()
                if(error!=null) assertThat(error).isInstanceOf(com.ricezhou.vsrqg.testmanagement.application.TestRunConflict::class.java)
            } else cancel.cancel(user,id,"race cancel","cancel","cancel")
        }
        assertThat(count("test_result")).isOne()
        assertThat(query.get(user,id).path("status").asText()).isIn("COMPLETED","CANCELLED")
        assertThat(downloads.metadata(user,pending.path("evidenceId").asText()).path("state").asText()).isEqualTo("EXPIRED")
    }
}
