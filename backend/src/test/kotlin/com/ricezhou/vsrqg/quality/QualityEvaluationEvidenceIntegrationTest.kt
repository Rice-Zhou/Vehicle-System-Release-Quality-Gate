package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.issue.application.CanonicalIssueSnapshot
import com.ricezhou.vsrqg.issue.application.IssueSnapshotCandidate
import com.ricezhou.vsrqg.issue.application.IssueSnapshotRepository
import com.ricezhou.vsrqg.issue.application.MaterializedIssueSnapshot
import com.ricezhou.vsrqg.quality.adapter.QualityEvaluationWorker
import com.ricezhou.vsrqg.testmanagement.ResultFixture
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotHeaderView
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRepository
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@Timeout(60)
class QualityEvaluationEvidenceIntegrationTest : ResultFixture() {
    @MockitoBean private lateinit var issueSnapshots: IssueSnapshotRepository
    @MockitoBean private lateinit var traceability: TraceabilityVerificationRepository
    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @Autowired private lateinit var worker: QualityEvaluationWorker

    @AfterEach
    fun retireUnfinishedEvaluations() {
        jdbc.sql("""UPDATE quality_evaluations SET state='ERROR',error_code='TEST_CLEANUP',
            completed_at=now() WHERE project_id=:project AND state IN ('QUEUED','RUNNING')""")
            .param("project", project).update()
        jdbc.sql("""UPDATE background_job SET status='DEAD_LETTER',completed_at=now(),updated_at=now(),
            result_summary='{"code":"TEST_CLEANUP"}'::jsonb
            WHERE project_id=:project AND job_type='QUALITY_EVALUATE' AND status IN ('QUEUED','RUNNING')""")
            .param("project", project).update()
    }

    @Test
    fun `HTTP evaluation pins actual release run and payload then reports payload corruption`() {
        start()
        val (logUpload, log) = available()
        val (_, screenshot) = available(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), "SCREENSHOT")
        submitHttp(resultBody("PASS", listOf(log.path("evidenceId").asText(),
            screenshot.path("evidenceId").asText())))
        val runId = runId()
        val manifestId = jdbc.sql("SELECT locked_manifest_id FROM release_record WHERE id=:id")
            .param("id", release).query(String::class.java).single()
        val manifestDigest = jdbc.sql("SELECT content_digest FROM manifest_revision WHERE id=:id")
            .param("id", manifestId).query(String::class.java).single()
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
        val setId = "set_$suffix"
        val setVersionId = "qrs_$suffix"
        val traceId = "trace_$suffix"
        val issueId = "issue_$suffix"
        val definition = mapper.readTree(Files.readString(
            Path.of("../contracts/examples/v0.2/quality-evaluation/rule-set.json"))) as ObjectNode
        definition.put("project", project).put("ruleSetId", setId)
            .put("engineVersion", "VSRQG-QUALITY-ENGINE-1")
        definition.withArray("selectedCaseRefs").removeAll()
            .addObject().put("caseId", "apk-launch-smoke").put("version", 1)
        val digest = "sha256:" + "b".repeat(64)
        jdbc.sql("""INSERT INTO quality_rule_set_versions(
            id,project_id,rule_set_id,rule_set_version,state,definition,catalog_version,
            engine_version,required_issue_refs,selected_case_refs,content_digest,author_id,created_at)
            VALUES (:id,:project,:setId,1,'DRAFT',CAST(:definition AS jsonb),2,
            'VSRQG-QUALITY-ENGINE-1','[]'::jsonb,CAST(:cases AS jsonb),:digest,:author,:at)""")
            .param("id", setVersionId).param("project", project).param("setId", setId)
            .param("definition", definition.toString())
            .param("cases", definition.path("selectedCaseRefs").toString())
            .param("digest", digest).param("author", user.subject)
            .param("at", Timestamp.from(now)).update()
        jdbc.sql("""INSERT INTO quality_rule_versions(id,rule_set_version_id,ordinal,rule_id,
            rule_version,validated_ast) VALUES (:id,:setId,0,'REQUIRED_ISSUE_VERIFIED',1,CAST(:ast AS jsonb))""")
            .param("id", "qrv_$suffix").param("setId", setVersionId)
            .param("ast", definition.path("rules")[0].toString()).update()
        jdbc.sql("""UPDATE quality_rule_set_versions SET state='PUBLISHED',row_version=row_version+1,
            reviewer_id=:reviewer,review_reason='isolated fixture',published_at=:at WHERE id=:id""")
            .param("reviewer", serviceId).param("at", Timestamp.from(now))
            .param("id", setVersionId).update()

        Mockito.`when`(traceability.findSnapshotHeader(release, traceId)).thenReturn(
            TraceabilitySnapshotHeaderView(traceId, project, release, 1, issueId, manifestId,
                manifestDigest, "policy", "validator", "sha256:" + "c".repeat(64),
                "sha256:" + "d".repeat(64), now))
        Mockito.`when`(issueSnapshots.read(issueId)).thenReturn(MaterializedIssueSnapshot(issueId,
            IssueSnapshotCandidate(project, release, 1, "sync", "source", "watermark", "adapter",
                "mapping", "filter", "age", emptyList()),
            CanonicalIssueSnapshot(byteArrayOf(), "sha256:" + "e".repeat(64)), now))
        Mockito.`when`(traceability.findSnapshotIssues(traceId)).thenReturn(emptyList())
        Mockito.`when`(traceability.findSnapshotGaps(traceId)).thenReturn(emptyList())

        fun submit(): String {
            val request = mapper.readTree("""{"ruleSet":{"ruleSetId":"$setId","version":1},
                "testRunIds":["$runId"],"traceabilitySnapshotId":"$traceId"}""")
            val response = mvc.post("/api/v1/releases/$release/quality-evaluations") {
                with(jwt().jwt { it.issuer(user.issuer).subject(user.subject)
                    .claim("principal_type", "USER") }
                    .authorities(SimpleGrantedAuthority("SCOPE_quality:evaluate")))
                header("Idempotency-Key", UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = request.toString()
            }.andReturn().response
            assertThat(response.status).describedAs(response.contentAsString).isEqualTo(202)
            val body = mapper.readTree(response.contentAsString)
            jdbc.sql("UPDATE background_job SET created_at=:at WHERE id=:id")
                .param("at", Timestamp.from(java.time.Instant.EPOCH))
                .param("id", body.path("jobId").asText()).update()
            return body.path("evaluationId").asText()
        }

        fun result(id: String): JsonNode {
            val response = mvc.get("/api/v1/releases/$release/quality-results") {
                with(jwt().jwt { it.issuer(user.issuer).subject(user.subject)
                    .claim("principal_type", "USER") }
                    .authorities(SimpleGrantedAuthority("SCOPE_quality:read")))
            }.andReturn().response
            assertThat(response.status).isEqualTo(200)
            return mapper.readTree(response.contentAsString).path("items")
                .first { it.path("evaluationId").asText() == id }
        }

        val completedId = submit()
        assertThat(worker.runNext()).isTrue()
        val completed = result(completedId)
        assertThat(completed.path("state").asText()).isEqualTo("COMPLETED")
        assertThat(completed.path("qualityResult").path("action").asText()).isEqualTo("BLOCK")
        val snapshot = completed.path("inputSnapshot")
        assertThat(snapshot.path("manifest").path("id").asText()).isEqualTo(manifestId)
        assertThat(snapshot.path("issueSnapshot").path("id").asText()).isEqualTo(issueId)
        assertThat(snapshot.path("traceabilitySnapshot").path("id").asText()).isEqualTo(traceId)
        assertThat(snapshot.path("selections")[0].path("runId").asText()).isEqualTo(runId)
        assertThat(snapshot.path("evidenceRefs").map { it.path("evidenceId").asText() })
            .containsExactlyInAnyOrder(log.path("evidenceId").asText(), screenshot.path("evidenceId").asText())

        Files.delete(storage.resolve(logUpload.path("uploadId").asText() + ".payload"))
        val failedId = submit()
        assertThat(worker.runNext()).isTrue()
        val failed = result(failedId)
        assertThat(failed.path("state").asText()).isEqualTo("ERROR")
        assertThat(failed.path("error").path("code").asText()).isEqualTo("QUALITY_EVIDENCE_INTEGRITY_ERROR")
        assertThat(failed.has("qualityResult")).isFalse()
    }
}
