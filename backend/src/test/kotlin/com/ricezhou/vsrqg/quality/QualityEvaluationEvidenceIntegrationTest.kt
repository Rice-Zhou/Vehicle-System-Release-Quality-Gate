package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.issue.application.CreateIssueSnapshot
import com.ricezhou.vsrqg.issue.application.CreateIssueSnapshotCommand
import com.ricezhou.vsrqg.issue.application.IssueSnapshotRepository
import com.ricezhou.vsrqg.issue.adapter.IssueFactCanonicalizer
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import com.ricezhou.vsrqg.issue.domain.NormalizedIssue
import com.ricezhou.vsrqg.quality.adapter.QualityEvaluationWorker
import com.ricezhou.vsrqg.testmanagement.ResultFixture
import com.ricezhou.vsrqg.traceability.adapter.TraceabilityVerificationJobWorker
import com.ricezhou.vsrqg.traceability.application.StartTraceabilityVerification
import com.ricezhou.vsrqg.traceability.application.StartTraceabilityVerificationCommand
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRepository
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Timestamp
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@Timeout(60)
@TestPropertySource(properties = ["vsrqg.traceability.verification.enabled=true"])
class QualityEvaluationEvidenceIntegrationTest : ResultFixture() {
    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @Autowired private lateinit var worker: QualityEvaluationWorker
    @Autowired private lateinit var createIssueSnapshot: CreateIssueSnapshot
    @Autowired private lateinit var issueSnapshots: IssueSnapshotRepository
    @Autowired private lateinit var startTraceability: StartTraceabilityVerification
    @Autowired private lateinit var traceWorker: TraceabilityVerificationJobWorker
    @Autowired private lateinit var traceability: TraceabilityVerificationRepository

    @AfterEach
    fun retireUnfinishedEvaluations() {
        jdbc.sql("""UPDATE traceability_verification_run SET status='FAILED',
            diagnostic_code='TEST_CLEANUP',completed_at=now()
            WHERE project_id=:project AND status IN ('QUEUED','RUNNING')""")
            .param("project", project).update()
        jdbc.sql("""UPDATE background_job SET status='DEAD_LETTER',completed_at=now(),updated_at=now(),
            result_summary='{"diagnosticCode":"TEST_CLEANUP"}'::jsonb
            WHERE project_id=:project AND job_type='TRACEABILITY_VERIFY'
              AND status IN ('QUEUED','RUNNING')""")
            .param("project", project).update()
        jdbc.sql("""UPDATE quality_evaluations SET state='ERROR',error_code='TEST_CLEANUP',
            completed_at=now() WHERE project_id=:project AND state IN ('QUEUED','RUNNING')""")
            .param("project", project).update()
        jdbc.sql("""UPDATE background_job SET status='DEAD_LETTER',completed_at=now(),updated_at=now(),
            result_summary='{"code":"TEST_CLEANUP"}'::jsonb
            WHERE project_id=:project AND job_type='QUALITY_EVALUATE' AND status IN ('QUEUED','RUNNING')""")
            .param("project", project).update()
    }

    @Test
    fun `HTTP evaluation pins persisted issue traceability run and payload then reports corruption`() {
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
        val sourceId = "source_$suffix"
        val syncRunId = "sync_$suffix"
        val normalizedIssueId = "issue_$suffix"
        val sourceIssueId = "QUALITY-$suffix"
        val issueObservedAt = now.minusSeconds(120)
        jdbc.sql("""INSERT INTO issue_source(id,project_id,source_key,source_type,adapter_version,
            mapping_version,created_at,updated_at) VALUES (:id,:project,:id,'FIXTURE',
            'fixture/v1','mapping/v1',now(),now())""")
            .param("id", sourceId).param("project", project).update()
        val issue = NormalizedIssue("FIXTURE", sourceIssueId, "Unverified quality issue",
            IssueSeverity.HIGH, IssueStatus.OPEN, "high", "open", "v1", "fixture:$sourceIssueId",
            issueObservedAt, "mapping/v1")
        val issueDigest = IssueFactCanonicalizer.canonicalize(issue).factDigest
        jdbc.sql("""INSERT INTO normalized_issue(id,project_id,source_id,source_issue_id,title,
            severity,status,raw_status_token,canonical_source_token,raw_severity_token,
            mapping_warnings,source_version,source_reference,observed_at,mapping_version,
            fact_digest,fact_digest_version,created_at) VALUES (:id,:project,:source,:key,
            :title,'HIGH','OPEN','open','FIXTURE','high','', 'v1',:reference,:observed,
            'mapping/v1',:digest,'normalized-issue-facts/v1',now())""")
            .param("id", normalizedIssueId).param("project", project).param("source", sourceId)
            .param("key", sourceIssueId).param("title", issue.title)
            .param("reference", issue.sourceReference).param("observed", Timestamp.from(issueObservedAt))
            .param("digest", issueDigest).update()
        jdbc.sql("""INSERT INTO issue_sync_run(id,project_id,source_id,sync_run_id,status,
            source_watermark,adapter_version,mapping_version,result_set_mode,filter_reference,
            issue_count,completed_at,created_at) VALUES (:id,:project,:source,:id,'RUNNING',
            'populated-full/v1','fixture/v1','mapping/v1','FULL','all-relevant-issues/v1',
            0,null,now())""")
            .param("id", syncRunId).param("project", project).param("source", sourceId).update()
        jdbc.sql("""INSERT INTO issue_sync_run_item(sync_run_id,ordinal,project_id,source_id,
            issue_id,source_issue_id,observed_at,created_at) VALUES (:run,0,:project,:source,
            :issue,:key,:observed,now())""")
            .param("run", syncRunId).param("project", project).param("source", sourceId)
            .param("issue", normalizedIssueId).param("key", sourceIssueId)
            .param("observed", Timestamp.from(issueObservedAt)).update()
        jdbc.sql("""UPDATE issue_sync_run SET status='SUCCEEDED',issue_count=1,
            completed_at=:completed WHERE id=:id""")
            .param("completed", Timestamp.from(now.minusSeconds(60)))
            .param("id", syncRunId).update()
        val snapshotRequestDigest = "sha256:" + MessageDigest.getInstance("SHA-256")
            .digest("$release\u0000$sourceId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val issueId = createIssueSnapshot.create(CreateIssueSnapshotCommand(
            user, release, sourceId, "snapshot-$suffix", snapshotRequestDigest, "request-$suffix",
        )).snapshotId
        val actualIssue = issueSnapshots.read(issueId)
        assertThat(actualIssue).isNotNull
        assertThat(actualIssue!!.candidate.selectedCount).isOne()
        assertThat(actualIssue.candidate.observations.single().issueId).isEqualTo(normalizedIssueId)
        val traceRun = startTraceability.start(StartTraceabilityVerificationCommand(
            user, release, sourceId, "trace-$suffix", snapshotRequestDigest, "trace-$suffix",
        )).verificationRunId
        val traceJobId = jdbc.sql("""SELECT id FROM background_job
            WHERE job_type='TRACEABILITY_VERIFY' AND idempotency_key=:run""")
            .param("run", traceRun).query(String::class.java).single()
        jdbc.sql("UPDATE background_job SET created_at=:at WHERE id=:id")
            .param("at", Timestamp.from(java.time.Instant.EPOCH)).param("id", traceJobId).update()
        assertThat(traceWorker.runNext()).isTrue()
        val traceId = jdbc.sql("SELECT result_snapshot_id FROM traceability_verification_run WHERE id=:id")
            .param("id", traceRun).query(String::class.java).single()
        val actualTrace = traceability.findSnapshotHeader(release, traceId)
        assertThat(actualTrace).isNotNull
        assertThat(actualTrace!!.issueSnapshotId).isEqualTo(issueId)
        assertThat(actualTrace.manifestDigest).isEqualTo(manifestDigest)
        assertThat(traceability.findSnapshotIssues(traceId).single().verified).isFalse()
        assertThat(traceability.findSnapshotGaps(traceId).single().diagnosticCode.name)
            .isEqualTo("ISSUE_COMMIT_MISSING")
        val definition = mapper.readTree(Files.readString(
            Path.of("../contracts/examples/v0.2/quality-evaluation/rule-set.json"))) as ObjectNode
        definition.put("project", project).put("ruleSetId", setId)
            .put("engineVersion", "VSRQG-QUALITY-ENGINE-1")
        definition.withArray("requiredIssueRefs").removeAll()
            .addObject().put("source", sourceId).put("sourceIssueId", sourceIssueId)
        definition.withArray("selectedCaseRefs").removeAll()
            .addObject().put("caseId", "apk-launch-smoke").put("version", 1)
        val digest = "sha256:" + "b".repeat(64)
        jdbc.sql("""INSERT INTO quality_rule_set_versions(
            id,project_id,rule_set_id,rule_set_version,state,definition,catalog_version,
            engine_version,required_issue_refs,selected_case_refs,content_digest,author_id,created_at)
            VALUES (:id,:project,:setId,1,'DRAFT',CAST(:definition AS jsonb),2,
            'VSRQG-QUALITY-ENGINE-1',CAST(:issues AS jsonb),CAST(:cases AS jsonb),:digest,:author,:at)""")
            .param("id", setVersionId).param("project", project).param("setId", setId)
            .param("definition", definition.toString())
            .param("issues", definition.path("requiredIssueRefs").toString())
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
        assertThat(snapshot.path("issueSnapshot").path("digest").asText())
            .isEqualTo(actualIssue.canonical.digest)
        assertThat(snapshot.path("traceabilitySnapshot").path("id").asText()).isEqualTo(traceId)
        assertThat(snapshot.path("traceabilitySnapshot").path("digest").asText())
            .isEqualTo(actualTrace.contentDigest)
        val issueFact = snapshot.path("facts").path("issues")[0]
        assertThat(issueFact.path("sourceIssueId").asText()).isEqualTo(sourceIssueId)
        assertThat(issueFact.path("required").asBoolean()).isTrue()
        assertThat(issueFact.path("verified").asBoolean()).isFalse()
        assertThat(snapshot.path("facts").path("traceability").path("gaps")[0]
            .path("diagnosticCode").asText()).isEqualTo("ISSUE_COMMIT_MISSING")
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
