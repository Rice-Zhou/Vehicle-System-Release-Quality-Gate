package com.ricezhou.vsrqg.quality.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.quality.application.QualityEvaluationClaim
import com.ricezhou.vsrqg.quality.application.QualityEvaluationRecord
import com.ricezhou.vsrqg.quality.application.QualityEvaluationRepository
import com.ricezhou.vsrqg.quality.application.QualityInputFailure
import com.ricezhou.vsrqg.quality.application.QualityPinnedInput
import com.ricezhou.vsrqg.quality.application.QualitySourceReader
import com.ricezhou.vsrqg.quality.application.digest
import com.ricezhou.vsrqg.quality.application.toQualityValue
import com.ricezhou.vsrqg.quality.domain.QualityCanonicalEncoder
import com.ricezhou.vsrqg.shared.adapter.toJdbcTimestamp
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.id.IdGenerator
import java.sql.ResultSet
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
class JdbcQualityEvaluationRepository(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
    private val governance: GovernanceStore,
    private val ids: IdGenerator,
    private val sources: QualitySourceReader,
) : QualityEvaluationRepository {
    override fun enqueue(record: QualityEvaluationRecord) {
        val now = record.createdAt.toJdbcTimestamp()
        jdbc.sql(
            """
            INSERT INTO background_job(id, project_id, job_type, idempotency_key, status, payload,
                available_at, created_at, updated_at)
            VALUES (:jobId, :projectId, 'QUALITY_EVALUATE', :evaluationId, 'QUEUED', CAST(:payload AS jsonb),
                :now, :now, :now)
            """.trimIndent(),
        ).param("jobId", record.jobId).param("projectId", record.projectId)
            .param("evaluationId", record.id).param("payload", record.request.toString())
            .param("now", now).update().requireOne()
        jdbc.sql(
            """
            INSERT INTO quality_evaluations(id, project_id, release_id, rule_set_version_id, job_id,
                requested_by, request_id, request_body, request_digest, state, created_at)
            VALUES (:id, :projectId, :releaseId, :ruleSetVersionId, :jobId,
                :actor, :requestId, CAST(:body AS jsonb), :digest, 'QUEUED', :now)
            """.trimIndent(),
        ).param("id", record.id).param("projectId", record.projectId)
            .param("releaseId", record.releaseId).param("ruleSetVersionId", record.ruleSetVersionId)
            .param("jobId", record.jobId).param("actor", record.requestedBy)
            .param("requestId", record.requestId).param("body", record.request.toString())
            .param("digest", record.requestDigest).param("now", now).update().requireOne()
        governance.appendAudit(record.projectId, record.requestedBy, "QUALITY_EVALUATION_REQUESTED",
            "QUALITY_EVALUATION", record.id, record.requestId, null, afterState = record.request)
        governance.appendOutbox("quality.evaluation.requested", "QUALITY_EVALUATION", record.id, record.request)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun claimNext(now: Instant): QualityEvaluationClaim? {
        val at = now.truncatedTo(ChronoUnit.MICROS)
        val candidate = jdbc.sql(
            """
            SELECT job.id AS job_id, job.idempotency_key AS evaluation_id, job.project_id, job.attempt_count
            FROM background_job job
            JOIN quality_evaluations evaluation ON evaluation.id = job.idempotency_key
                AND evaluation.project_id = job.project_id
            WHERE job.job_type = 'QUALITY_EVALUATE'
                AND evaluation.state IN ('QUEUED', 'RUNNING')
                AND ((job.status = 'QUEUED' AND job.available_at <= :now)
                  OR (job.status = 'RUNNING' AND job.started_at <= :reclaimBefore))
            ORDER BY job.created_at, job.id
            FOR UPDATE OF job, evaluation SKIP LOCKED
            LIMIT 1
            """.trimIndent(),
        ).param("now", at.toJdbcTimestamp())
            .param("reclaimBefore", at.minusSeconds(LEASE_SECONDS).toJdbcTimestamp())
            .query { rs, _ -> QualityEvaluationClaim(rs.getString("job_id"),
                rs.getString("evaluation_id"), rs.getString("project_id"), rs.getInt("attempt_count")) }
            .optional().orElse(null) ?: return null
        if (candidate.attemptCount >= MAX_ATTEMPTS) {
            terminalFailure(candidate, "QUALITY_RETRY_EXHAUSTED", at, "DEAD_LETTER")
            recordFailureAudit(candidate, "QUALITY_RETRY_EXHAUSTED")
            return null
        }
        jdbc.sql(
            """
            UPDATE quality_evaluations SET state='RUNNING', started_at=coalesce(started_at, :now)
            WHERE id=:id AND state IN ('QUEUED', 'RUNNING')
            """.trimIndent(),
        ).param("now", at.toJdbcTimestamp()).param("id", candidate.evaluationId).update().requireOne()
        val next = candidate.attemptCount + 1
        jdbc.sql(
            """
            UPDATE background_job SET status='RUNNING', attempt_count=:attempt,
                started_at=:now, completed_at=NULL, result_summary=NULL, updated_at=:now
            WHERE id=:id AND status IN ('QUEUED', 'RUNNING')
            """.trimIndent(),
        ).param("attempt", next).param("now", at.toJdbcTimestamp())
            .param("id", candidate.jobId).update().requireOne()
        return candidate.copy(attemptCount = next)
    }

    override fun request(claim: QualityEvaluationClaim): QualityEvaluationRecord = jdbc.sql(
        """
        SELECT id, job_id, project_id, release_id, rule_set_version_id, requested_by,
            request_id, request_body::text AS request_body, request_digest, created_at
        FROM quality_evaluations WHERE id=:id AND project_id=:projectId
        """.trimIndent(),
    ).param("id", claim.evaluationId).param("projectId", claim.projectId)
        .query { rs, _ -> QualityEvaluationRecord(
            rs.getString("id"), rs.getString("job_id"), rs.getString("project_id"),
            rs.getString("release_id"), rs.getString("rule_set_version_id"),
            rs.getString("requested_by"), rs.getString("request_id"),
            mapper.readTree(rs.getString("request_body")), rs.getString("request_digest"),
            rs.getTimestamp("created_at").toInstant(),
        ) }.single()

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    override fun seal(claim: QualityEvaluationClaim, input: QualityPinnedInput, at: Instant): JsonNode {
        lockClaim(claim, at)
        val request = request(claim)
        val current = sources.read(request.projectId, request.releaseId, request.request)
        if (current.snapshot.path("inputDigest").asText() != input.snapshot.path("inputDigest").asText() ||
            current.evidence != input.evidence) {
            throw QualityInputFailure("QUALITY_SOURCE_CHANGED")
        }
        val existing = jdbc.sql(
            "SELECT content::text FROM quality_input_snapshots WHERE evaluation_id=:id",
        ).param("id", claim.evaluationId).query(String::class.java).optional().orElse(null)
        if (existing != null) {
            val saved = mapper.readTree(existing)
            if (saved.path("inputDigest").asText() != input.snapshot.path("inputDigest").asText()) {
                throw QualityInputFailure("QUALITY_INPUT_CHANGED")
            }
            return saved
        }
        val snapshot = input.snapshot.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            .put("snapshotId", ids.nextId("qis_"))
        jdbc.sql(
            """
            INSERT INTO quality_input_snapshots(id, evaluation_id, project_id, release_id,
                content, input_digest, created_at)
            VALUES (:id, :evaluationId, :projectId, :releaseId, CAST(:content AS jsonb), :digest, :now)
            """.trimIndent(),
        ).param("id", snapshot.path("snapshotId").asText())
            .param("evaluationId", claim.evaluationId).param("projectId", request.projectId)
            .param("releaseId", request.releaseId).param("content", snapshot.toString())
            .param("digest", snapshot.path("inputDigest").asText()).param("now", at.toJdbcTimestamp())
            .update().requireOne()
        return snapshot
    }

    @Transactional
    override fun complete(
        claim: QualityEvaluationClaim, snapshot: JsonNode, ruleResults: List<JsonNode>,
        qualityResult: JsonNode?, errorCode: String?, at: Instant,
    ) {
        lockClaim(claim, at)
        val record = request(claim)
        val saved = jdbc.sql(
            "SELECT content::text FROM quality_input_snapshots WHERE evaluation_id=:id",
        ).param("id", claim.evaluationId).query(String::class.java).single()
        val persisted = mapper.readTree(saved)
        val encoder = QualityCanonicalEncoder()
        if (persisted.path("snapshotId") != snapshot.path("snapshotId") ||
            persisted.path("inputDigest") != snapshot.path("inputDigest") ||
            digest(encoder.encode(toQualityValue(persisted))) !=
            digest(encoder.encode(toQualityValue(snapshot)))) {
            throw QualityInputFailure("QUALITY_INPUT_CHANGED")
        }
        val timestamp = at.toJdbcTimestamp()
        ruleResults.forEachIndexed { ordinal, result ->
            jdbc.sql(
                """
                INSERT INTO quality_rule_results(id, evaluation_id, ordinal, rule_id, status, content, created_at)
                VALUES (:id, :evaluationId, :ordinal, :ruleId, :status, CAST(:content AS jsonb), :now)
                """.trimIndent(),
            ).param("id", ids.nextId("qrr_")).param("evaluationId", claim.evaluationId)
                .param("ordinal", ordinal).param("ruleId", result.path("ruleId").asText())
                .param("status", result.path("status").asText())
                .param("content", result.toString()).param("now", timestamp).update().requireOne()
        }
        if (qualityResult != null) {
            if (errorCode != null) throw QualityInputFailure("QUALITY_RESULT_ERROR_CONFLICT")
            jdbc.sql(
                """
                INSERT INTO quality_results(id, evaluation_id, action, result_digest, content, created_at)
                VALUES (:id, :evaluationId, :action, :digest, CAST(:content AS jsonb), :now)
                """.trimIndent(),
            ).param("id", qualityResult.path("resultId").asText())
                .param("evaluationId", claim.evaluationId).param("action", qualityResult.path("action").asText())
                .param("digest", qualityResult.path("resultDigest").asText())
                .param("content", qualityResult.toString()).param("now", timestamp).update().requireOne()
        }
        val state = if (qualityResult == null) "ERROR" else "COMPLETED"
        val diagnostic = if (state == "ERROR") errorCode ?: "QUALITY_EVALUATION_ERROR" else null
        jdbc.sql(
            """
            UPDATE quality_evaluations SET state=:state, error_code=:code, completed_at=:now
            WHERE id=:id AND state='RUNNING'
            """.trimIndent(),
        ).param("state", state).param("code", diagnostic).param("now", timestamp)
            .param("id", claim.evaluationId).update().requireOne()
        jdbc.sql(
            """
            UPDATE background_job SET status=:status, result_summary=CAST(:summary AS jsonb),
                completed_at=:now, updated_at=:now
            WHERE id=:id AND status='RUNNING' AND attempt_count=:attempt
            """.trimIndent(),
        ).param("status", if (state == "ERROR") "FAILED" else "SUCCEEDED")
            .param("summary", mapper.createObjectNode().put("state", state).toString())
            .param("now", timestamp).param("id", claim.jobId).param("attempt", claim.attemptCount)
            .update().requireOne()
        governance.appendAudit(record.projectId, record.requestedBy, "QUALITY_EVALUATION_FINISHED",
            "QUALITY_EVALUATION", record.id, record.requestId, diagnostic,
            afterState = qualityResult ?: mapper.createObjectNode().put("errorCode", diagnostic))
        governance.appendOutbox("quality.evaluation.finished", "QUALITY_EVALUATION", record.id,
            mapper.createObjectNode().put("state", state))
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun fail(claim: QualityEvaluationClaim, code: String, at: Instant) {
        lockClaim(claim, at)
        terminalFailure(claim, code, at, "FAILED")
        recordFailureAudit(claim, code)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun retryInfrastructure(claim: QualityEvaluationClaim, at: Instant) {
        lockClaim(claim, at)
        if (claim.attemptCount >= MAX_ATTEMPTS) {
            terminalFailure(claim, "QUALITY_RETRY_EXHAUSTED", at, "DEAD_LETTER")
            recordFailureAudit(claim, "QUALITY_RETRY_EXHAUSTED")
            return
        }
        jdbc.sql(
            """
            UPDATE background_job SET status='QUEUED', available_at=:available,
                started_at=NULL, updated_at=:now
            WHERE id=:id AND status='RUNNING' AND attempt_count=:attempt
            """.trimIndent(),
        ).param("available", at.plusSeconds(claim.attemptCount.toLong()).toJdbcTimestamp())
            .param("now", at.toJdbcTimestamp()).param("id", claim.jobId)
            .param("attempt", claim.attemptCount).update().requireOne()
    }

    override fun list(projectId: String, releaseId: String, cursor: String?): JsonNode {
        val anchor = cursor?.let {
            jdbc.sql(
                "SELECT created_at FROM quality_evaluations WHERE id=:id AND project_id=:projectId AND release_id=:releaseId",
            ).param("id", it).param("projectId", projectId).param("releaseId", releaseId)
                .query(java.sql.Timestamp::class.java).optional().orElse(null)
                ?: throw QualityInputFailure("QUALITY_CURSOR_INVALID")
        }
        val query = """
            SELECT id, request_body::text AS request_body, created_at, state, error_code, job_id
            FROM quality_evaluations
            WHERE project_id=:projectId AND release_id=:releaseId
        """.trimIndent() + if (anchor == null) "" else
            " AND (created_at, id) < (:cursorTime, :cursorId)"
        val statement = jdbc.sql(query + " ORDER BY created_at DESC, id DESC LIMIT 51")
            .param("projectId", projectId).param("releaseId", releaseId)
        val bound = if (anchor == null) statement else statement.param("cursorTime", anchor)
            .param("cursorId", cursor!!)
        val rows = bound.query { rs, _ -> row(rs, projectId, releaseId) }.list()
        val response = mapper.createObjectNode()
        val items = response.putArray("items")
        rows.take(50).forEach(items::add)
        if (rows.size > 50) response.put("nextCursor", rows[49].path("evaluationId").asText())
        else response.putNull("nextCursor")
        return response
    }

    private fun row(rs: ResultSet, projectId: String, releaseId: String): JsonNode {
        val id = rs.getString("id")
        val request = mapper.readTree(rs.getString("request_body"))
        val state = rs.getString("state")
        val result = mapper.createObjectNode().put("evaluationId", id).put("project", projectId)
            .put("releaseId", releaseId).put("createdAt", rs.getTimestamp("created_at").toInstant().toString())
            .put("state", state)
        result.set<JsonNode>("ruleSet", request.path("ruleSet").deepCopy())
        if (state in setOf("QUEUED", "RUNNING")) {
            result.put("jobId", rs.getString("job_id"))
        } else {
            val snapshot = jdbc.sql(
                "SELECT content::text FROM quality_input_snapshots WHERE evaluation_id=:id",
            ).param("id", id).query(String::class.java).optional().orElse(null)
            if (snapshot != null) result.set<JsonNode>("inputSnapshot", mapper.readTree(snapshot))
            if (state == "ERROR") {
                result.put("releaseQualityState", "NOT_EVALUATED")
                result.set<JsonNode>("error", mapper.createObjectNode()
                    .put("code", rs.getString("error_code")).set<JsonNode>("parameters", mapper.createObjectNode()))
                val rules = result.putArray("ruleResults")
                jdbc.sql(
                    "SELECT content::text FROM quality_rule_results WHERE evaluation_id=:id ORDER BY ordinal",
                ).param("id", id).query(String::class.java).list().forEach { rules.add(mapper.readTree(it)) }
            } else {
                val qualityResult = jdbc.sql(
                    "SELECT content::text FROM quality_results WHERE evaluation_id=:id",
                ).param("id", id).query(String::class.java).single()
                result.set<JsonNode>("qualityResult", mapper.readTree(qualityResult))
            }
        }
        return result
    }

    private fun lockClaim(claim: QualityEvaluationClaim, now: Instant) {
        val stillCurrent = jdbc.sql(
            """
            SELECT job.id FROM background_job job
            JOIN quality_evaluations evaluation ON evaluation.id=job.idempotency_key
            WHERE job.id=:jobId AND job.job_type='QUALITY_EVALUATE'
                AND job.project_id=:projectId AND job.status='RUNNING'
                AND job.attempt_count=:attempt AND job.started_at > :deadline
                AND evaluation.id=:evaluationId AND evaluation.state='RUNNING'
            FOR UPDATE OF job, evaluation
            """.trimIndent(),
        ).param("jobId", claim.jobId).param("projectId", claim.projectId)
            .param("attempt", claim.attemptCount)
            .param("deadline", now.minusSeconds(LEASE_SECONDS).toJdbcTimestamp())
            .param("evaluationId", claim.evaluationId)
            .query(String::class.java).optional().orElse(null)
        if (stillCurrent == null) throw QualityInputFailure("QUALITY_STALE_LEASE")
    }

    private fun terminalFailure(claim: QualityEvaluationClaim, code: String, at: Instant, jobState: String) {
        val timestamp = at.toJdbcTimestamp()
        jdbc.sql(
            """
            UPDATE quality_evaluations SET state='ERROR', error_code=:code, completed_at=:now
            WHERE id=:id AND state IN ('QUEUED', 'RUNNING')
            """.trimIndent(),
        ).param("code", code).param("now", timestamp)
            .param("id", claim.evaluationId).update().requireOne()
        jdbc.sql(
            """
            UPDATE background_job SET status=:status,
                result_summary=CAST(:summary AS jsonb), completed_at=:now, updated_at=:now
            WHERE id=:id AND status IN ('QUEUED', 'RUNNING')
            """.trimIndent(),
        ).param("status", jobState)
            .param("summary", mapper.createObjectNode().put("errorCode", code).toString())
            .param("now", timestamp).param("id", claim.jobId).update().requireOne()
    }

    private fun recordFailureAudit(claim: QualityEvaluationClaim, code: String) {
        val record = request(claim)
        governance.appendAudit(record.projectId, record.requestedBy, "QUALITY_EVALUATION_FINISHED",
            "QUALITY_EVALUATION", record.id, record.requestId, code,
            afterState = mapper.createObjectNode().put("errorCode", code))
        governance.appendOutbox("quality.evaluation.finished", "QUALITY_EVALUATION", record.id,
            mapper.createObjectNode().put("state", "ERROR").put("errorCode", code))
    }

    private fun Int.requireOne() {
        check(this == 1) { "QUALITY_EVALUATION_WRITE_CONFLICT" }
    }

    private companion object {
        const val LEASE_SECONDS = 300L
        const val MAX_ATTEMPTS = 3
    }
}
