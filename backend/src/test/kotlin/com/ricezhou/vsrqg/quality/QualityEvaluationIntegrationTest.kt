package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.evidence.application.QualityEvidenceItem
import com.ricezhou.vsrqg.evidence.application.StoredPayload
import com.ricezhou.vsrqg.quality.application.QualityEvaluationRecord
import com.ricezhou.vsrqg.quality.application.QualityEvaluationRepository
import com.ricezhou.vsrqg.quality.application.QualityInputFailure
import com.ricezhou.vsrqg.quality.application.QualityPinnedInput
import com.ricezhou.vsrqg.quality.application.QualitySourceReader
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import com.ricezhou.vsrqg.testmanagement.application.AttemptBinding
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionTemplate

@Timeout(60)
@AutoConfigureMockMvc
class QualityEvaluationIntegrationTest : PostgresIntegrationTest() {
    @MockitoBean private lateinit var sources: QualitySourceReader
    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var evaluations: QualityEvaluationRepository
    @Autowired private lateinit var evaluationService: com.ricezhou.vsrqg.quality.application.QualityEvaluationService
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var transactions: TransactionTemplate

    private lateinit var projectId: String
    private lateinit var releaseId: String
    private lateinit var ruleSetVersionId: String
    private lateinit var actorId: String
    private val now = Instant.parse("2026-10-08T00:00:00Z")

    @BeforeEach
    fun seed() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
        projectId = "prj_$suffix"
        releaseId = "rel_$suffix"
        ruleSetVersionId = "qrs_$suffix"
        actorId = "usr_$suffix"
        jdbc.sql("INSERT INTO project(id,project_key,name,created_at) VALUES (:id,:id,'Quality fixture',:at)")
            .param("id", projectId).param("at", Timestamp.from(now)).update()
        jdbc.sql("INSERT INTO principal(id,issuer,subject,principal_type,created_at) VALUES (:id,'https://idp.vsrqg.test',:id,'USER',:at)")
            .param("id", actorId).param("at", Timestamp.from(now)).update()
        jdbc.sql("INSERT INTO project_assignment(project_id,principal_id,role,created_at) VALUES (:project,:actor,'ENGINEER',:at)")
            .param("project", projectId).param("actor", actorId).param("at", Timestamp.from(now)).update()
        jdbc.sql("""INSERT INTO release_record(id,project_id,vehicle,platform,system_version,build_id,status,created_at,updated_at)
            VALUES (:id,:project,'vehicle','platform','v1',:build,'DRAFT',:at,:at)""")
            .param("id", releaseId).param("project", projectId).param("build", suffix)
            .param("at", Timestamp.from(now)).update()
        jdbc.sql("""INSERT INTO quality_rule_set_versions(
            id,project_id,rule_set_id,rule_set_version,state,definition,catalog_version,
            engine_version,required_issue_refs,selected_case_refs,content_digest,
            author_id,reviewer_id,review_reason,created_at,published_at)
            VALUES (:id,:project,:setId,1,'PUBLISHED',CAST(:definition AS jsonb),2,
            'VSRQG-QUALITY-ENGINE-1','[]'::jsonb,'[{"caseId":"smoke","version":1}]'::jsonb,
            :digest,:actor,:actor2,'fixture',:at,:at)""")
            .param("id", ruleSetVersionId).param("project", projectId).param("setId", "set_$suffix")
            .param("definition", """{"catalogVersion":2,"engineVersion":"VSRQG-QUALITY-ENGINE-1"}""")
            .param("digest", DIGEST).param("actor", actorId)
            .param("actor2", seedReviewer(suffix)).param("at", Timestamp.from(now)).update()
    }

    @AfterEach
    fun retireUnfinishedEvaluations() {
        jdbc.sql("""UPDATE quality_evaluations SET state='ERROR',error_code='TEST_CLEANUP',
            completed_at=now() WHERE project_id=:project AND state IN ('QUEUED','RUNNING')""")
            .param("project", projectId).update()
        jdbc.sql("""UPDATE background_job SET status='DEAD_LETTER',completed_at=now(),updated_at=now(),
            result_summary='{"code":"TEST_CLEANUP"}'::jsonb
            WHERE project_id=:project AND job_type='QUALITY_EVALUATE' AND status IN ('QUEUED','RUNNING')""")
            .param("project", projectId).update()
    }

    @Test
    fun `snapshot and final result are immutable and a stale claim cannot overwrite them`() {
        val record = record()
        transactions.executeWithoutResult { evaluations.enqueue(record) }
        val claim = evaluations.claimNext(now)!!
        val snapshot = mapper.createObjectNode().put("project", projectId)
            .put("releaseId", releaseId).put("inputDigest", DIGEST)
            .put("sizeBytes", 5L)
        val pinned = QualityPinnedInput(snapshot, emptyList())
        Mockito.`when`(sources.read(projectId, releaseId, record.request)).thenReturn(pinned)
        val sealed = evaluations.seal(claim, pinned, now)
        val result = mapper.readTree("""{"resultId":"qrl_${UUID.randomUUID().toString().take(8)}","action":"PASS","resultDigest":"$DIGEST"}""")
        evaluations.complete(claim, sealed, emptyList(), result, null, now)
        assertThat(jdbc.sql("SELECT state FROM quality_evaluations WHERE id=:id")
            .param("id", record.id).query(String::class.java).single()).isEqualTo("COMPLETED")
        assertThat(evaluations.list(projectId, releaseId, null).path("items")[0]
            .path("qualityResult").path("action").asText()).isEqualTo("PASS")
        assertThatThrownBy { evaluations.fail(claim, "QUALITY_SOURCE_CHANGED", now) }
            .isInstanceOf(QualityInputFailure::class.java)
        assertThatThrownBy {
            jdbc.sql("UPDATE quality_results SET action='BLOCK' WHERE evaluation_id=:id")
                .param("id", record.id).update()
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            jdbc.sql("""INSERT INTO quality_results(id,evaluation_id,action,result_digest,content,created_at)
                SELECT 'qrl_duplicate',evaluation_id,action,result_digest,content,created_at
                FROM quality_results WHERE evaluation_id=:id""")
                .param("id", record.id).update()
        }.isInstanceOf(DataAccessException::class.java)
        assertThat(evaluations.claimNext(now.plusSeconds(301))).isNull()
    }

    @Test
    fun `source failure remains queryable without a fabricated quality result`() {
        val record = record()
        transactions.executeWithoutResult { evaluations.enqueue(record) }
        val claim = evaluations.claimNext(now)!!
        evaluations.fail(claim, "QUALITY_SOURCE_CHANGED", now)
        val item = evaluations.list(projectId, releaseId, null).path("items")[0]
        assertThat(item.path("state").asText()).isEqualTo("ERROR")
        assertThat(item.path("error").path("code").asText()).isEqualTo("QUALITY_SOURCE_CHANGED")
        assertThat(item.has("qualityResult")).isFalse()
        assertThat(jdbc.sql("SELECT count(*) FROM quality_results WHERE evaluation_id=:id")
            .param("id", record.id).query(Int::class.java).single()).isZero()
    }

    @Test
    fun `source change between reads rejects sealing and remains queryable`() {
        val record = record()
        transactions.executeWithoutResult { evaluations.enqueue(record) }
        val initial = QualityPinnedInput(mapper.createObjectNode().put("inputDigest", DIGEST), emptyList())
        val changed = QualityPinnedInput(mapper.createObjectNode().put("inputDigest", "sha256:${"b".repeat(64)}"), emptyList())
        Mockito.`when`(sources.read(projectId, releaseId, record.request)).thenReturn(initial, changed)

        val claim = evaluations.claimNext(now)!!
        val pinned = sources.read(projectId, releaseId, record.request)
        val failure = org.junit.jupiter.api.Assertions.assertThrows(QualityInputFailure::class.java) {
            evaluations.seal(claim, pinned, now)
        }
        assertThat(failure.code).isEqualTo("QUALITY_SOURCE_CHANGED")
        evaluations.fail(claim, failure.code, now)

        val item = evaluations.list(projectId, releaseId, null).path("items")[0]
        assertThat(item.path("state").asText()).isEqualTo("ERROR")
        assertThat(item.path("error").path("code").asText()).isEqualTo("QUALITY_SOURCE_CHANGED")
        assertThat(item.has("inputSnapshot")).isFalse()
        assertThat(item.has("qualityResult")).isFalse()
        assertThat(jdbc.sql("SELECT count(*) FROM quality_input_snapshots WHERE evaluation_id=:id")
            .param("id", record.id).query(Int::class.java).single()).isZero()
        assertThat(jdbc.sql("SELECT count(*) FROM quality_results WHERE evaluation_id=:id")
            .param("id", record.id).query(Int::class.java).single()).isZero()
        assertThat(jdbc.sql("SELECT status FROM background_job WHERE id=:id")
            .param("id", record.jobId).query(String::class.java).single()).isEqualTo("FAILED")
    }

    @Test
    fun `evidence metadata change rejects sealing even when input digest is unchanged`() {
        val record = record()
        transactions.executeWithoutResult { evaluations.enqueue(record) }
        val claim = evaluations.claimNext(now)!!
        assertThat(claim.evaluationId).isEqualTo(record.id)
        val item = QualityEvidenceItem("ev-1", "upload-1",
            AttemptBinding("attempt-1", "run-1", releaseId, projectId, "agent-1", "device-1", "lease-1", 1),
            "LOG", StoredPayload(5, DIGEST), DIGEST)
        val pinned = QualityPinnedInput(mapper.createObjectNode().put("inputDigest", DIGEST), listOf(item))
        val changed = pinned.copy(evidence = listOf(item.copy(metadataDigest = "sha256:${"b".repeat(64)}")))
        Mockito.`when`(sources.read(projectId, releaseId, record.request)).thenReturn(changed)
        val before = jobState(record)

        val failure = org.junit.jupiter.api.Assertions.assertThrows(QualityInputFailure::class.java) {
            evaluations.seal(claim, pinned, now)
        }
        assertThat(failure.code).isEqualTo("QUALITY_SOURCE_CHANGED")
        assertThat(jobState(record)).isEqualTo(before)
        assertThat(jdbc.sql("SELECT count(*) FROM quality_input_snapshots WHERE evaluation_id=:id")
            .param("id", record.id).query(Int::class.java).single()).isZero()
        assertNoCompletion(record)
        evaluations.fail(claim, failure.code, now)
        val failed = evaluations.list(projectId, releaseId, null).path("items")[0]
        assertThat(failed.path("evaluationId").asText()).isEqualTo(record.id)
        assertThat(failed.path("state").asText()).isEqualTo("ERROR")
        assertThat(failed.path("error").path("code").asText()).isEqualTo("QUALITY_SOURCE_CHANGED")
        assertThat(failed.has("inputSnapshot")).isFalse()
        assertThat(failed.has("qualityResult")).isFalse()
    }

    @ParameterizedTest(name = "seal at {0}s allowed={1} without reclaim")
    @CsvSource("299, true", "300, false", "301, false")
    fun `sealing observes lease expiry without a reclaim`(elapsedSeconds: Long, allowed: Boolean) {
        val record = record()
        transactions.executeWithoutResult { evaluations.enqueue(record) }
        val claim = evaluations.claimNext(now)!!
        assertThat(claim.evaluationId).isEqualTo(record.id)
        assertThat(claim.attemptCount).isEqualTo(1)
        val pinned = QualityPinnedInput(mapper.createObjectNode().put("inputDigest", DIGEST), emptyList())
        Mockito.`when`(sources.read(projectId, releaseId, record.request)).thenReturn(pinned)
        val before = jobState(record)

        if (allowed) {
            assertThat(evaluations.seal(claim, pinned, now.plusSeconds(elapsedSeconds)).path("inputDigest").asText())
                .isEqualTo(DIGEST)
        } else {
            val failure = org.junit.jupiter.api.Assertions.assertThrows(QualityInputFailure::class.java) {
                evaluations.seal(claim, pinned, now.plusSeconds(elapsedSeconds))
            }
            assertThat(failure.code).isEqualTo("QUALITY_STALE_LEASE")
        }
        assertThat(jobState(record)).isEqualTo(before)
        assertThat(jdbc.sql("SELECT count(*) FROM quality_input_snapshots WHERE evaluation_id=:id")
            .param("id", record.id).query(Int::class.java).single()).isEqualTo(if (allowed) 1 else 0)
        assertNoCompletion(record)
    }

    @ParameterizedTest(name = "complete at {0}s allowed={1} without reclaim")
    @CsvSource("299, true", "300, false", "301, false")
    fun `completion observes lease expiry without a reclaim`(elapsedSeconds: Long, allowed: Boolean) {
        val record = record()
        transactions.executeWithoutResult { evaluations.enqueue(record) }
        val claim = evaluations.claimNext(now)!!
        assertThat(claim.evaluationId).isEqualTo(record.id)
        assertThat(claim.attemptCount).isEqualTo(1)
        val pinned = QualityPinnedInput(mapper.createObjectNode().put("inputDigest", DIGEST), emptyList())
        Mockito.`when`(sources.read(projectId, releaseId, record.request)).thenReturn(pinned)
        val sealed = evaluations.seal(claim, pinned, now)
        val before = jobState(record)
        val rule = mapper.readTree("""{"ruleId":"smoke","status":"PASS"}""")
        val result = mapper.readTree("""{"resultId":"qrl_${UUID.randomUUID().toString().replace("-", "")}","action":"PASS","resultDigest":"$DIGEST"}""")

        if (allowed) {
            evaluations.complete(claim, sealed, listOf(rule), result, null, now.plusSeconds(elapsedSeconds))
            assertThat(evaluations.list(projectId, releaseId, null).path("items")[0].path("qualityResult"))
                .isEqualTo(result)
            val completed = jobState(record)
            assertThat(completed.path("status").asText()).isEqualTo("SUCCEEDED")
            assertThat(completed.path("attempt_count").asInt()).isEqualTo(1)
        } else {
            val failure = org.junit.jupiter.api.Assertions.assertThrows(QualityInputFailure::class.java) {
                evaluations.complete(claim, sealed, listOf(rule), result, null, now.plusSeconds(elapsedSeconds))
            }
            assertThat(failure.code).isEqualTo("QUALITY_STALE_LEASE")
            assertThat(jobState(record)).isEqualTo(before)
            assertNoCompletion(record)
        }
        val snapshots = jdbc.sql("SELECT content::text FROM quality_input_snapshots WHERE evaluation_id=:id")
            .param("id", record.id).query(String::class.java).list().map(mapper::readTree)
        assertThat(snapshots).containsExactly(sealed)
    }

    @Test
    fun `a failed result write rolls back rule results and job completion`() {
        val record = record()
        transactions.executeWithoutResult { evaluations.enqueue(record) }
        val claim = evaluations.claimNext(now)!!
        val snapshot = mapper.readTree("""{"inputDigest":"$DIGEST"}""")
        val pinned = QualityPinnedInput(snapshot, emptyList())
        Mockito.`when`(sources.read(projectId, releaseId, record.request)).thenReturn(pinned)
        val sealed = evaluations.seal(claim, pinned, now)
        val rule = mapper.readTree("""{"ruleId":"smoke","status":"PASS"}""")
        val invalid = mapper.readTree("""{"resultId":"qrl_invalid","action":"INVALID","resultDigest":"$DIGEST"}""")
        assertThatThrownBy {
            evaluations.complete(claim, sealed, listOf(rule), invalid, null, now)
        }.isInstanceOf(DataAccessException::class.java)
        assertThat(jdbc.sql("SELECT count(*) FROM quality_rule_results WHERE evaluation_id=:id")
            .param("id", record.id).query(Int::class.java).single()).isZero()
        assertThat(jdbc.sql("SELECT state FROM quality_evaluations WHERE id=:id")
            .param("id", record.id).query(String::class.java).single()).isEqualTo("RUNNING")
        val valid = mapper.readTree("""{"resultId":"qrl_valid","action":"PASS","resultDigest":"$DIGEST"}""")
        evaluations.complete(claim, sealed, listOf(rule), valid, null, now)
        assertThat(jdbc.sql("SELECT count(*) FROM quality_rule_results WHERE evaluation_id=:id")
            .param("id", record.id).query(Int::class.java).single()).isEqualTo(1)
    }

    @Test
    fun `outbox failure rolls back the entire completion and a reclaimed job can finish once`() {
        val record = record()
        transactions.executeWithoutResult { evaluations.enqueue(record) }
        val first = evaluations.claimNext(now)!!
        val pinned = QualityPinnedInput(mapper.createObjectNode().put("inputDigest", DIGEST), emptyList())
        Mockito.`when`(sources.read(projectId, releaseId, record.request)).thenReturn(pinned)
        val sealed = evaluations.seal(first, pinned, now)
        val rule = mapper.readTree("""{"ruleId":"smoke","status":"PASS"}""")
        val result = mapper.readTree("""{"resultId":"qrl_${UUID.randomUUID().toString().take(8)}","action":"PASS","resultDigest":"$DIGEST"}""")
        fun count(sql: String): Int = jdbc.sql(sql).param("id", record.id).query(Int::class.java).single()
        jdbc.sql("""
            CREATE FUNCTION reject_quality_finish_outbox_fixture() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
              RAISE EXCEPTION 'fixture outbox failure';
            END ${'$'}${'$'}
        """.trimIndent()).update()
        jdbc.sql("""
            CREATE TRIGGER reject_quality_finish_outbox_fixture BEFORE INSERT ON outbox_event
            FOR EACH ROW WHEN (NEW.event_type = 'quality.evaluation.finished')
            EXECUTE FUNCTION reject_quality_finish_outbox_fixture()
        """.trimIndent()).update()
        try {
            assertThatThrownBy { evaluations.complete(first, sealed, listOf(rule), result, null, now) }
                .isInstanceOf(DataAccessException::class.java)
            assertThat(count("SELECT count(*) FROM quality_rule_results WHERE evaluation_id=:id")).isZero()
            assertThat(count("SELECT count(*) FROM quality_results WHERE evaluation_id=:id")).isZero()
            assertThat(jdbc.sql("SELECT state FROM quality_evaluations WHERE id=:id")
                .param("id", record.id).query(String::class.java).single()).isEqualTo("RUNNING")
            assertThat(jdbc.sql("SELECT status FROM background_job WHERE id=:id")
                .param("id", record.jobId).query(String::class.java).single()).isEqualTo("RUNNING")
            assertThat(count("""SELECT count(*) FROM audit_event WHERE aggregate_id=:id
                AND action='QUALITY_EVALUATION_FINISHED'""")).isZero()
            assertThat(count("""SELECT count(*) FROM outbox_event WHERE aggregate_id=:id
                AND event_type='quality.evaluation.finished'""")).isZero()
        } finally {
            jdbc.sql("DROP TRIGGER reject_quality_finish_outbox_fixture ON outbox_event").update()
            jdbc.sql("DROP FUNCTION reject_quality_finish_outbox_fixture()").update()
        }

        evaluations.retryInfrastructure(first, now)
        assertThat(jdbc.sql("SELECT status FROM background_job WHERE id=:id")
            .param("id", record.jobId).query(String::class.java).single()).isEqualTo("QUEUED")
        val second = evaluations.claimNext(now.plusSeconds(2))!!
        assertThat(second.evaluationId).isEqualTo(record.id)
        assertThat(second.attemptCount).isEqualTo(2)
        evaluations.complete(second, sealed, listOf(rule), result, null, now.plusSeconds(2))
        assertThat(count("SELECT count(*) FROM quality_rule_results WHERE evaluation_id=:id")).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM quality_results WHERE evaluation_id=:id")).isEqualTo(1)
        assertThat(count("""SELECT count(*) FROM audit_event WHERE aggregate_id=:id
            AND action='QUALITY_EVALUATION_FINISHED'""")).isEqualTo(1)
        assertThat(count("""SELECT count(*) FROM outbox_event WHERE aggregate_id=:id
            AND event_type='quality.evaluation.finished'""")).isEqualTo(1)
        assertThat(jdbc.sql("SELECT status FROM background_job WHERE id=:id")
            .param("id", record.jobId).query(String::class.java).single()).isEqualTo("SUCCEEDED")
        assertThat(evaluations.list(projectId, releaseId, null).path("items")[0]
            .path("qualityResult").path("action").asText()).isEqualTo("PASS")
    }

    @Test
    fun `reclaim fences the prior worker and exhausted retries become queryable`() {
        val record = record()
        transactions.executeWithoutResult { evaluations.enqueue(record) }
        val first = evaluations.claimNext(now)!!
        assertThat(first.evaluationId).isEqualTo(record.id)
        val second = evaluations.claimNext(now.plusSeconds(301))!!
        assertThat(second.evaluationId).isEqualTo(record.id)
        assertThatThrownBy { evaluations.fail(first, "QUALITY_SOURCE_CHANGED", now.plusSeconds(301)) }
            .isInstanceOf(QualityInputFailure::class.java)
        val third = evaluations.claimNext(now.plusSeconds(602))!!
        assertThat(third.evaluationId).isEqualTo(record.id)
        assertThat(evaluations.claimNext(now.plusSeconds(903))).isNull()
        val item = evaluations.list(projectId, releaseId, null).path("items")[0]
        assertThat(item.path("state").asText()).isEqualTo("ERROR")
        assertThat(item.path("error").path("code").asText()).isEqualTo("QUALITY_RETRY_EXHAUSTED")
        assertThat(jdbc.sql("SELECT status FROM background_job WHERE id=:id")
            .param("id", record.jobId).query(String::class.java).single()).isEqualTo("DEAD_LETTER")
    }

    @Test
    fun `reclaim prevents the prior worker from sealing input`() {
        val record = record()
        transactions.executeWithoutResult { evaluations.enqueue(record) }
        val first = evaluations.claimNext(now)!!
        val second = evaluations.claimNext(now.plusSeconds(301))!!
        val pinned = QualityPinnedInput(mapper.createObjectNode().put("inputDigest", DIGEST), emptyList())
        Mockito.`when`(sources.read(projectId, releaseId, record.request)).thenReturn(pinned)

        val failure = org.junit.jupiter.api.Assertions.assertThrows(QualityInputFailure::class.java) {
            evaluations.seal(first, pinned, now.plusSeconds(301))
        }
        assertThat(failure.code).isEqualTo("QUALITY_STALE_LEASE")
        assertThat(jdbc.sql("SELECT count(*) FROM quality_input_snapshots WHERE evaluation_id=:id")
            .param("id", record.id).query(Int::class.java).single()).isZero()
        assertThat(evaluations.seal(second, pinned, now.plusSeconds(301)).path("inputDigest").asText())
            .isEqualTo(DIGEST)
    }

    @Test
    fun `reclaim prevents the prior worker from completing a sealed input`() {
        val record = record()
        transactions.executeWithoutResult { evaluations.enqueue(record) }
        val first = evaluations.claimNext(now)!!
        val pinned = QualityPinnedInput(mapper.createObjectNode().put("inputDigest", DIGEST), emptyList())
        Mockito.`when`(sources.read(projectId, releaseId, record.request)).thenReturn(pinned)
        val sealed = evaluations.seal(first, pinned, now)
        val second = evaluations.claimNext(now.plusSeconds(301))!!
        val rule = mapper.readTree("""{"ruleId":"smoke","status":"PASS"}""")
        val result = mapper.readTree("""{"resultId":"qrl_${UUID.randomUUID().toString().take(8)}","action":"PASS","resultDigest":"$DIGEST"}""")

        val failure = org.junit.jupiter.api.Assertions.assertThrows(QualityInputFailure::class.java) {
            evaluations.complete(first, sealed, listOf(rule), result, null, now.plusSeconds(301))
        }
        assertThat(failure.code).isEqualTo("QUALITY_STALE_LEASE")
        assertThat(jdbc.sql("SELECT count(*) FROM quality_rule_results WHERE evaluation_id=:id")
            .param("id", record.id).query(Int::class.java).single()).isZero()
        assertThat(jdbc.sql("SELECT count(*) FROM quality_results WHERE evaluation_id=:id")
            .param("id", record.id).query(Int::class.java).single()).isZero()
        assertThat(jdbc.sql("SELECT attempt_count FROM background_job WHERE id=:id")
            .param("id", record.jobId).query(Int::class.java).single()).isEqualTo(second.attemptCount)
        evaluations.complete(second, sealed, listOf(rule), result, null, now.plusSeconds(301))
        assertThat(evaluations.list(projectId, releaseId, null).path("items")[0]
            .path("qualityResult").path("action").asText()).isEqualTo("PASS")
    }
    @Test
    fun `same submission key returns one queued evaluation and changed content conflicts`() {
        val request = record().request
        val principal = com.ricezhou.vsrqg.access.domain.Principal(
            "https://idp.vsrqg.test", actorId, false)
        val service = evaluationService
        val first = service.request(projectId, releaseId, request, "quality-idem", principal, "request-1")
        val replay = service.request(projectId, releaseId, request, "quality-idem", principal, "request-2")
        assertThat(replay).isEqualTo(first)
        val changed = request.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        changed.putArray("testRunIds").add("run-changed")
        assertThatThrownBy {
            service.request(projectId, releaseId, changed, "quality-idem", principal, "request-3")
        }.isInstanceOf(com.ricezhou.vsrqg.shared.application.IdempotencyConflict::class.java)
        assertThat(jdbc.sql("SELECT count(*) FROM quality_evaluations WHERE project_id=:id")
            .param("id", projectId).query(Int::class.java).single()).isEqualTo(1)
    }

    @Test
    fun `foreign project rule set is rejected without changing existing sources`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
        val otherProject = "prj_other_$suffix"
        val otherSet = "set_other_$suffix"
        val otherVersion = "qrs_other_$suffix"
        jdbc.sql("INSERT INTO project(id,project_key,name,created_at) VALUES (:id,:id,'Other quality fixture',:at)")
            .param("id", otherProject).param("at", Timestamp.from(now)).update()
        jdbc.sql("""INSERT INTO quality_rule_set_versions(
            id,project_id,rule_set_id,rule_set_version,state,definition,catalog_version,
            engine_version,required_issue_refs,selected_case_refs,content_digest,
            author_id,reviewer_id,review_reason,created_at,published_at)
            SELECT :id,:project,:setId,rule_set_version,state,definition,catalog_version,
                engine_version,required_issue_refs,selected_case_refs,content_digest,
                author_id,reviewer_id,review_reason,created_at,published_at
            FROM quality_rule_set_versions WHERE id=:original""")
            .param("id", otherVersion).param("project", otherProject).param("setId", otherSet)
            .param("original", ruleSetVersionId).update()
        val beforeRelease = jdbc.sql("SELECT status FROM release_record WHERE id=:id")
            .param("id", releaseId).query(String::class.java).single()
        val beforeRule = jdbc.sql("SELECT definition::text || ':' || content_digest FROM quality_rule_set_versions WHERE id=:id")
            .param("id", otherVersion).query(String::class.java).single()
        val request = record().request.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (request.path("ruleSet") as com.fasterxml.jackson.databind.node.ObjectNode).put("ruleSetId", otherSet)
        val principal = com.ricezhou.vsrqg.access.domain.Principal(
            "https://idp.vsrqg.test", actorId, false)
        val failure = org.junit.jupiter.api.Assertions.assertThrows(QualityInputFailure::class.java) {
            evaluationService.request(projectId, releaseId, request, "foreign-set-$suffix", principal, "request-$suffix")
        }
        assertThat(failure.code).isEqualTo("QUALITY_RULE_SET_SCOPE_MISMATCH")
        assertThat(jdbc.sql("SELECT count(*) FROM quality_evaluations WHERE release_id=:id")
            .param("id", releaseId).query(Int::class.java).single()).isZero()
        assertThat(jdbc.sql("SELECT status FROM release_record WHERE id=:id")
            .param("id", releaseId).query(String::class.java).single()).isEqualTo(beforeRelease)
        assertThat(jdbc.sql("SELECT definition::text || ':' || content_digest FROM quality_rule_set_versions WHERE id=:id")
            .param("id", otherVersion).query(String::class.java).single()).isEqualTo(beforeRule)
    }
    @Test
    fun formalApiQueuesAndReadsOnlyTheProjectEvaluation() {
        val body = record().request
        val posted = mockMvc.post("/api/v1/releases/$releaseId/quality-evaluations") {
            with(jwt().jwt { it.issuer("https://idp.vsrqg.test").subject(actorId).claim("principal_type", "USER") }
                .authorities(SimpleGrantedAuthority("SCOPE_quality:evaluate")))
            header("Idempotency-Key", "api-quality-" + UUID.randomUUID())
            contentType = MediaType.APPLICATION_JSON
            content = body.toString()
        }.andReturn().response
        assertThat(posted.status).isEqualTo(202)
        val evaluationId = mapper.readTree(posted.contentAsString).path("evaluationId").asText()
        assertThat(evaluationId).startsWith("qev_")

        val history = mockMvc.get("/api/v1/releases/$releaseId/quality-results") {
            with(jwt().jwt { it.issuer("https://idp.vsrqg.test").subject(actorId).claim("principal_type", "USER") }
                .authorities(SimpleGrantedAuthority("SCOPE_quality:read")))
        }.andReturn().response
        assertThat(history.status).isEqualTo(200)
        val items = mapper.readTree(history.contentAsString).path("items")
        assertThat(items.size()).isEqualTo(1)
        assertThat(items[0].path("evaluationId").asText()).isEqualTo(evaluationId)
        assertThat(items[0].path("state").asText()).isEqualTo("QUEUED")
        assertThat(items[0].has("qualityResult")).isFalse()
    }

    private fun jobState(record: QualityEvaluationRecord): JsonNode = mapper.readTree(
        jdbc.sql("SELECT row_to_json(job)::text FROM background_job job WHERE id=:id")
            .param("id", record.jobId).query(String::class.java).single(),
    )

    private fun assertNoCompletion(record: QualityEvaluationRecord) {
        assertThat(evaluations.list(projectId, releaseId, null).path("items")[0].path("state").asText())
            .isEqualTo("RUNNING")
        assertThat(jdbc.sql("SELECT count(*) FROM quality_rule_results WHERE evaluation_id=:id")
            .param("id", record.id).query(Int::class.java).single()).isZero()
        assertThat(jdbc.sql("SELECT count(*) FROM quality_results WHERE evaluation_id=:id")
            .param("id", record.id).query(Int::class.java).single()).isZero()
        assertThat(jdbc.sql("""SELECT count(*) FROM audit_event WHERE aggregate_id=:id
            AND action='QUALITY_EVALUATION_FINISHED'""")
            .param("id", record.id).query(Int::class.java).single()).isZero()
        assertThat(jdbc.sql("""SELECT count(*) FROM outbox_event WHERE aggregate_id=:id
            AND event_type='quality.evaluation.finished'""")
            .param("id", record.id).query(Int::class.java).single()).isZero()
    }

    private fun record(): QualityEvaluationRecord {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
        val request = mapper.readTree("""{"ruleSet":{"ruleSetId":"set_${projectId.removePrefix("prj_")}","version":1},"testRunIds":["run-1"],"traceabilitySnapshotId":"trace-1"}""")
        return QualityEvaluationRecord("qev_$suffix", "job_$suffix", projectId, releaseId,
            ruleSetVersionId, actorId, "request-$suffix", request, DIGEST, now)
    }

    private fun seedReviewer(suffix: String): String {
        val id = "rev_$suffix"
        jdbc.sql("INSERT INTO principal(id,issuer,subject,principal_type,created_at) VALUES (:id,'https://idp.vsrqg.test',:id,'USER',:at)")
            .param("id", id).param("at", Timestamp.from(now)).update()
        return id
    }

    private companion object {
        const val DIGEST = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
