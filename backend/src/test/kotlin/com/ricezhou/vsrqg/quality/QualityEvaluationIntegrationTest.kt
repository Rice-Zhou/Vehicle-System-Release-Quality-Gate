package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.quality.application.QualityEvaluationRecord
import com.ricezhou.vsrqg.quality.application.QualityEvaluationRepository
import com.ricezhou.vsrqg.quality.application.QualityInputFailure
import com.ricezhou.vsrqg.quality.application.QualityPinnedInput
import com.ricezhou.vsrqg.quality.application.QualitySourceReader
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
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
    fun `reclaim fences the prior worker and exhausted retries become queryable`() {
        val record = record()
        transactions.executeWithoutResult { evaluations.enqueue(record) }
        val first = evaluations.claimNext(now)!!
        val second = evaluations.claimNext(now.plusSeconds(301))!!
        assertThatThrownBy { evaluations.fail(first, "QUALITY_SOURCE_CHANGED", now.plusSeconds(301)) }
            .isInstanceOf(QualityInputFailure::class.java)
        evaluations.claimNext(now.plusSeconds(602))!!
        assertThat(evaluations.claimNext(now.plusSeconds(903))).isNull()
        val item = evaluations.list(projectId, releaseId, null).path("items")[0]
        assertThat(item.path("state").asText()).isEqualTo("ERROR")
        assertThat(item.path("error").path("code").asText()).isEqualTo("QUALITY_RETRY_EXHAUSTED")
        assertThat(jdbc.sql("SELECT status FROM background_job WHERE id=:id")
            .param("id", record.jobId).query(String::class.java).single()).isEqualTo("DEAD_LETTER")
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
