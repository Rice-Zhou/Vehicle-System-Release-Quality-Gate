package com.ricezhou.vsrqg.quality

import org.assertj.core.api.Assertions.assertThat
import org.springframework.jdbc.core.simple.JdbcClient

internal fun JdbcClient.assertQualityWorkerError(evaluationId: String, code: String) {
    listOf("quality_input_snapshots", "quality_rule_results", "quality_results").forEach { table ->
        assertThat(sql("SELECT count(*) FROM $table WHERE evaluation_id=:id")
            .param("id", evaluationId).query(Int::class.java).single())
            .describedAs("$table for $evaluationId").isZero()
    }
    sql("""SELECT evaluation.state, evaluation.error_code, evaluation.completed_at,
        job.status, job.attempt_count, job.completed_at AS job_completed_at,
        job.result_summary->>'errorCode' AS job_error
        FROM quality_evaluations evaluation JOIN background_job job ON job.id=evaluation.job_id
        WHERE evaluation.id=:id""")
        .param("id", evaluationId).query { rs, _ ->
            assertThat(rs.getString("state")).isEqualTo("ERROR")
            assertThat(rs.getString("error_code")).isEqualTo(code)
            assertThat(rs.getTimestamp("completed_at")).isNotNull()
            assertThat(rs.getString("status")).isEqualTo("FAILED")
            assertThat(rs.getInt("attempt_count")).isEqualTo(1)
            assertThat(rs.getTimestamp("job_completed_at")).isNotNull()
            assertThat(rs.getString("job_error")).isEqualTo(code)
        }.single()
    assertThat(sql("""SELECT reason, after_state->>'errorCode' AS error_code FROM audit_event
        WHERE aggregate_id=:id AND action='QUALITY_EVALUATION_FINISHED'""")
        .param("id", evaluationId).query { rs, _ ->
            rs.getString("reason") to rs.getString("error_code")
        }.list()).containsExactly(code to code)
    assertThat(sql("""SELECT payload->>'state' AS state, payload->>'errorCode' AS error_code
        FROM outbox_event WHERE aggregate_id=:id AND event_type='quality.evaluation.finished'""")
        .param("id", evaluationId).query { rs, _ ->
            rs.getString("state") to rs.getString("error_code")
        }.list()).containsExactly("ERROR" to code)
}
