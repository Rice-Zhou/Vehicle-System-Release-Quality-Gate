package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.evidence.application.EvidenceRepository
import com.ricezhou.vsrqg.evidence.application.PayloadStore
import com.ricezhou.vsrqg.evidence.application.QualityEvidenceSource
import com.ricezhou.vsrqg.issue.application.CreateIssueSnapshot
import com.ricezhou.vsrqg.issue.application.CreateIssueSnapshotCommand
import com.ricezhou.vsrqg.issue.application.IssueSnapshotRepository
import com.ricezhou.vsrqg.issue.adapter.IssueFactCanonicalizer
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import com.ricezhou.vsrqg.issue.domain.NormalizedIssue
import com.ricezhou.vsrqg.manifest.application.ValidationReport
import com.ricezhou.vsrqg.manifest.application.ValidationStatus
import com.ricezhou.vsrqg.quality.adapter.QualityEvaluationWorker
import com.ricezhou.vsrqg.quality.application.QualityEvaluationRepository
import com.ricezhou.vsrqg.quality.application.QualitySourceReader
import com.ricezhou.vsrqg.testmanagement.ResultFixture
import com.ricezhou.vsrqg.traceability.adapter.TraceabilityVerificationJobWorker
import com.ricezhou.vsrqg.traceability.application.StartTraceabilityVerification
import com.ricezhou.vsrqg.traceability.application.StartTraceabilityVerificationCommand
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRepository
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Timestamp
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Timeout(60)
@TestPropertySource(properties = ["vsrqg.traceability.verification.enabled=true"])
class QualityEvaluationEvidenceIntegrationTest : ResultFixture() {
    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @Autowired private lateinit var worker: QualityEvaluationWorker
    @Autowired private lateinit var evaluations: QualityEvaluationRepository
    @Autowired private lateinit var sources: QualitySourceReader
    @Autowired private lateinit var createIssueSnapshot: CreateIssueSnapshot
    @Autowired private lateinit var issueSnapshots: IssueSnapshotRepository
    @Autowired private lateinit var startTraceability: StartTraceabilityVerification
    @Autowired private lateinit var traceWorker: TraceabilityVerificationJobWorker
    @Autowired private lateinit var traceability: TraceabilityVerificationRepository
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var evidenceRows: EvidenceRepository
    @MockitoSpyBean private lateinit var observedEvidence: QualityEvidenceSource
    @MockitoSpyBean private lateinit var observedPayloads: PayloadStore
    @MockitoSpyBean private lateinit var observedJdbc: JdbcClient

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
    fun `HTTP evaluation preserves transaction windows and rejects cross-release Run and corruption`() {
        start()
        val (logUpload, log) = available()
        val (screenshotUpload, screenshot) = available(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), "SCREENSHOT")
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

        fun submit(selectedSet: String = setId, selectedRun: String = runId): String {
            val request = mapper.readTree("""{"ruleSet":{"ruleSetId":"$selectedSet","version":1},
                "testRunIds":["$selectedRun"],"traceabilitySnapshotId":"$traceId"}""")
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
        runWithTransactionAssertions(runId, setOf(log.path("evidenceId").asText(), screenshot.path("evidenceId").asText()))
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

        fun sourceGet(path: String, scope: String): JsonNode {
            val response = mvc.get(path) {
                with(jwt().jwt { it.issuer(user.issuer).subject(user.subject)
                    .claim("principal_type", "USER") }
                    .authorities(SimpleGrantedAuthority("SCOPE_$scope")))
            }.andReturn().response
            assertThat(response.status).describedAs(response.contentAsString).isEqualTo(200)
            return mapper.readTree(response.contentAsString)
        }
        val traceResponse = sourceGet(
            "/api/v1/releases/$release/traceability?snapshotId=$traceId", "traceability:read")
        val testRunResponse = sourceGet("/api/v1/test-runs/$runId/results", "test:read")
        val persistedSnapshot = mapper.readTree(jdbc.sql(
            "SELECT content::text FROM quality_input_snapshots WHERE evaluation_id=:id")
            .param("id", completedId).query(String::class.java).single())
        assertThat(snapshot).isEqualTo(persistedSnapshot)
        val persistedRule = mapper.readTree(jdbc.sql(
            "SELECT validated_ast::text FROM quality_rule_versions WHERE rule_set_version_id=:id")
            .param("id", setVersionId).query(String::class.java).single())
        val replayDigests = QualityReplayProbe.replayDigests(
            persistedSnapshot, persistedRule, (1..3).map { "qrl_formal_source_$it" },
        )
        assertThat(replayDigests).containsOnly(completed.path("qualityResult").path("resultDigest").asText())
        assertThat(snapshot.path("versions").path("selectionPolicyVersion").asText())
            .isEqualTo("SINGLE-RUN-CASE-1")
        assertThat(testRunResponse.path("attempts")).hasSize(1)
        assertThat(snapshot.path("selections")).hasSize(1)
        val selection = snapshot.path("selections")[0]
        val selectedAttempt = testRunResponse.path("attempts")[0]
        val selectedResult = selectedAttempt.path("result")
        assertThat(testRunResponse.path("runId").asText()).isEqualTo(runId)
        assertThat(selection.path("runId").asText()).isEqualTo(testRunResponse.path("runId").asText())
        assertThat(selection.path("case").path("caseId").asText())
            .isEqualTo(selectedResult.path("caseId").asText())
        assertThat(selection.path("case").path("version").asInt())
            .isEqualTo(selectedResult.path("caseVersion").asInt())
        assertThat(selection.path("case")).isEqualTo(snapshot.path("selectedCaseRefs")[0])
        assertThat(selection.path("selectedAttempt").path("attemptId").asText())
            .isEqualTo(selectedAttempt.path("attemptId").asText())
        assertThat(selection.path("selectedAttempt").path("attemptNo").asInt())
            .isEqualTo(selectedResult.path("attemptNo").asInt())
        assertThat(selectedResult.path("resultDigest").asText()).startsWith("sha256:")
        assertThat(selection.path("resultDigest").asText())
            .isEqualTo(selectedResult.path("resultDigest").asText())
        assertThat(selection.path("otherAttempts")).isEmpty()
        val evidenceResponses = mapper.createArrayNode()
        snapshot.path("evidenceRefs").forEach { ref ->
            val metadata = sourceGet("/api/v1/evidence/${ref.path("evidenceId").asText()}", "evidence:read")
            assertThat(metadata.path("evidenceId").asText()).isEqualTo(ref.path("evidenceId").asText())
            assertThat(metadata.path("testRunId").asText()).isEqualTo(ref.path("runId").asText())
            assertThat(metadata.path("attemptId").asText()).isEqualTo(ref.path("attemptId").asText())
            assertThat(metadata.path("payloadChecksum").asText()).isEqualTo(ref.path("digest").asText())
            assertThat(metadata.path("integrity").asText()).isEqualTo("VERIFIED")
            assertThat(metadata.has("url")).isFalse()
            evidenceResponses.add(metadata)
        }
        assertThat(jdbc.sql("SELECT count(*) FROM evidence_integrity_observation WHERE evidence_id IN (:log,:screenshot)")
            .param("log", log.path("evidenceId").asText())
            .param("screenshot", screenshot.path("evidenceId").asText())
            .query(Int::class.java).single()).isEqualTo(2)

        val smokeSetId = "smoke_$suffix"
        val smokeVersionId = "smoke_qrs_$suffix"
        val smokeDefinition = definition.deepCopy()
        smokeDefinition.put("ruleSetId", smokeSetId)
        smokeDefinition.withArray("requiredIssueRefs").removeAll()
        val smokeRule = mapper.valueToTree<JsonNode>(org.yaml.snakeyaml.Yaml().load<Any>(
            Files.readString(Path.of("../contracts/examples/v0.2/quality-rule/smoke-case-outcome.yaml"))))
        smokeDefinition.withArray("rules").removeAll().add(smokeRule)
        jdbc.sql("""INSERT INTO quality_rule_set_versions(
            id,project_id,rule_set_id,rule_set_version,state,definition,catalog_version,
            engine_version,required_issue_refs,selected_case_refs,content_digest,author_id,created_at)
            VALUES (:id,:project,:setId,1,'DRAFT',CAST(:definition AS jsonb),2,
            'VSRQG-QUALITY-ENGINE-1','[]'::jsonb,CAST(:cases AS jsonb),:digest,:author,:at)""")
            .param("id", smokeVersionId).param("project", project).param("setId", smokeSetId)
            .param("definition", smokeDefinition.toString())
            .param("cases", smokeDefinition.path("selectedCaseRefs").toString())
            .param("digest", "sha256:" + "c".repeat(64)).param("author", user.subject)
            .param("at", Timestamp.from(now)).update()
        jdbc.sql("""INSERT INTO quality_rule_versions(id,rule_set_version_id,ordinal,rule_id,
            rule_version,validated_ast) VALUES (:id,:setId,0,'SMOKE_CASE_OUTCOME',1,CAST(:ast AS jsonb))""")
            .param("id", "smoke_qrv_$suffix").param("setId", smokeVersionId)
            .param("ast", smokeRule.toString()).update()
        jdbc.sql("""UPDATE quality_rule_set_versions SET state='PUBLISHED',row_version=row_version+1,
            reviewer_id=:reviewer,review_reason='isolated fixture',published_at=:at WHERE id=:id""")
            .param("reviewer", serviceId).param("at", Timestamp.from(now))
            .param("id", smokeVersionId).update()

        fun smokeScenario(status: String, includeScreenshot: Boolean): Pair<JsonNode, JsonNode?> {
            start()
            val (_, scenarioLog) = available()
            val refs = mutableListOf(scenarioLog.path("evidenceId").asText())
            if (includeScreenshot) {
                val (_, scenarioScreenshot) = available(
                    byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), "SCREENSHOT")
                refs.add(scenarioScreenshot.path("evidenceId").asText())
            }
            submitHttp(resultBody(status, refs))
            val selectedRun = runId()
            val evaluationId = submit(smokeSetId, selectedRun)
            assertThat(worker.runNext()).isTrue()
            val evaluation = result(evaluationId)
            if (!includeScreenshot) {
                assertThat(evaluation.path("state").asText()).isEqualTo("ERROR")
                assertThat(evaluation.path("error").path("code").asText())
                    .isEqualTo("QUALITY_REQUIRED_EVIDENCE_MISSING")
                assertThat(evaluation.has("qualityResult")).isFalse()
                assertThat(evaluation.has("inputSnapshot")).isFalse()
                return evaluation to null
            }
            assertThat(evaluation.path("state").asText()).isEqualTo("COMPLETED")
            assertThat(evaluation.path("qualityResult").path("action").asText())
                .isEqualTo(if (status == "PASS") "PASS" else "BLOCK")
            assertThat(evaluation.path("inputSnapshot").path("requiredIssueRefs").size()).isZero()
            assertThat(evaluation.path("inputSnapshot").path("facts").path("testResults")[0]
                .path("status").asText()).isEqualTo(status)
            assertThat(evaluation.path("inputSnapshot").path("evidenceRefs").size()).isEqualTo(2)
            assertThat(evaluation.path("qualityResult").path("ruleResults")[0]
                .path("ruleId").asText()).isEqualTo("SMOKE_CASE_OUTCOME")
            val selectedResult = sourceGet("/api/v1/test-runs/$selectedRun/results", "test:read")
            return evaluation to selectedResult
        }
        val (smokePass, passRun) = smokeScenario("PASS", true)
        val (smokeFail, failRun) = smokeScenario("FAIL", true)
        val (missingEvidence, _) = smokeScenario("ERROR", false)

        val otherRelease = "rel_other_$suffix"
        val otherManifest = "man_other_$suffix"
        val otherValidation = "val_other_$suffix"
        jdbc.sql("""INSERT INTO release_record(id,project_id,vehicle,platform,system_version,
            build_id,status,created_at,updated_at)
            SELECT :other,project_id,vehicle,platform,:version,build_id,'DRAFT',now(),now()
            FROM release_record WHERE id=:original""")
            .param("other", otherRelease).param("version", "other-$suffix")
            .param("original", release).update()
        jdbc.sql("""INSERT INTO manifest_revision(id,release_id,revision,content_digest,
            raw_manifest,canonical_bytes,schema_version,state,created_at,updated_at)
            SELECT :other,:release,revision,content_digest,raw_manifest,canonical_bytes,
                schema_version,'DRAFT',now(),now() FROM manifest_revision WHERE id=:original""")
            .param("other", otherManifest).param("release", otherRelease)
            .param("original", manifestId).update()
        jdbc.sql("""INSERT INTO manifest_artifact(manifest_id,artifact_id,ordinal,required,created_at)
            SELECT :other,artifact_id,ordinal,required,now() FROM manifest_artifact
            WHERE manifest_id=:original""")
            .param("other", otherManifest).param("original", manifestId).update()
        val validation = ValidationReport(otherValidation, otherManifest, ValidationStatus.VALID,
            manifestDigest, "0.2", emptyList(), now, "RFC8785-JCS-1", "trusted-test/1",
            jdbc.sql("SELECT octet_length(canonical_bytes) FROM manifest_revision WHERE id=:id")
                .param("id", manifestId).query(Int::class.java).single())
        jdbc.sql("""INSERT INTO manifest_validation(id,manifest_id,status,content_digest,
            schema_version,validator_version,report,validated_at,created_at)
            VALUES (:id,:manifest,'VALID',:digest,'0.2','trusted-test/1',CAST(:report AS jsonb),now(),now())""")
            .param("id", otherValidation).param("manifest", otherManifest)
            .param("digest", manifestDigest).param("report", mapper.writeValueAsString(validation)).update()
        jdbc.sql("UPDATE manifest_revision SET state='LOCKED',locked_validation_id=:validation WHERE id=:id")
            .param("validation", otherValidation).param("id", otherManifest).update()
        jdbc.sql("UPDATE release_record SET status='READY_FOR_TEST',locked_manifest_id=:manifest WHERE id=:id")
            .param("manifest", otherManifest).param("id", otherRelease).update()
        fun apkId(selectedManifest: String): String = jdbc.sql("""SELECT ma.artifact_id
            FROM manifest_artifact ma JOIN artifact a ON a.id=ma.artifact_id
            WHERE ma.manifest_id=:manifest AND a.artifact_type='APK'""")
            .param("manifest", selectedManifest).query(String::class.java).single()
        assertThat(otherRelease).isNotEqualTo(release)
        assertThat(otherManifest).isNotEqualTo(manifestId)
        assertThat(apkId(otherManifest)).isEqualTo(apkId(manifestId))
        val originalRelease = release
        release = otherRelease
        start()
        val (_, otherLog) = available()
        val (_, otherScreenshot) = available(
            byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), "SCREENSHOT")
        submitHttp(resultBody("PASS", listOf(otherLog.path("evidenceId").asText(),
            otherScreenshot.path("evidenceId").asText())))
        val otherRun = runId()
        val otherResult = sourceGet("/api/v1/test-runs/$otherRun/results", "test:read")
        assertThat(otherResult.path("releaseId").asText()).isEqualTo(otherRelease)
        release = originalRelease
        fun releaseSources(): List<String> = jdbc.sql("""SELECT r.id || ':' || r.locked_manifest_id ||
            ':' || m.content_digest || ':' || r.status FROM release_record r
            JOIN manifest_revision m ON m.id=r.locked_manifest_id
            WHERE r.id IN (:first,:second) ORDER BY r.id""")
            .param("first", originalRelease).param("second", otherRelease)
            .query(String::class.java).list()
        val beforeSources = releaseSources()
        val beforeDecision = result(completedId)
        val rejectedId = submit(smokeSetId, otherRun)
        assertThat(worker.runNext()).isTrue()
        val rejected = result(rejectedId)
        assertThat(rejected.path("state").asText()).isEqualTo("ERROR")
        assertThat(rejected.path("error").path("code").asText()).isEqualTo("QUALITY_TEST_RUN_INVALID")
        assertThat(rejected.has("inputSnapshot")).isFalse()
        assertThat(rejected.has("qualityResult")).isFalse()
        assertThat(jdbc.sql("SELECT count(*) FROM quality_input_snapshots WHERE evaluation_id=:id")
            .param("id", rejectedId).query(Int::class.java).single()).isZero()
        assertThat(jdbc.sql("SELECT count(*) FROM quality_results WHERE evaluation_id=:id")
            .param("id", rejectedId).query(Int::class.java).single()).isZero()
        assertThat(releaseSources()).containsExactlyElementsOf(beforeSources)
        assertThat(apkId(otherManifest)).isEqualTo(apkId(manifestId))
        assertThat(sourceGet("/api/v1/test-runs/$otherRun/results", "test:read")).isEqualTo(otherResult)
        assertThat(result(completedId)).isEqualTo(beforeDecision)

        val windowId = submit()
        val claim = evaluations.claimNext(now)!!
        assertThat(claim.evaluationId).isEqualTo(windowId)
        val request = evaluations.request(claim)
        val pinned = sources.read(request.projectId, request.releaseId, request.request)
        sources.verifyEvidence(pinned)
        val screenshotPath = storage.resolve(screenshotUpload.path("uploadId").asText() + ".payload")
        val originalScreenshot = Files.readAllBytes(screenshotPath)
        try {
            val corrupted = originalScreenshot.copyOf()
            corrupted[corrupted.lastIndex] = (corrupted.last() + 1).toByte()
            Files.write(screenshotPath, corrupted)
            assertThatThrownBy { sources.verifyEvidence(pinned) }
                .isInstanceOf(com.ricezhou.vsrqg.evidence.application.EvidenceConflict::class.java)
            // TDR-026 pins metadata at sealing; later checks diagnose external byte changes.
            val sealed = evaluations.seal(claim, pinned, now)
            assertThat(sealed.path("evidenceRefs")).hasSize(2)
            assertThat(jdbc.sql("SELECT count(*) FROM quality_input_snapshots WHERE evaluation_id=:id")
                .param("id", windowId).query(Int::class.java).single()).isEqualTo(1)
        } finally {
            Files.write(screenshotPath, originalScreenshot)
        }
        evaluations.fail(claim, "TEST_WINDOW_PROBE", now)

        Files.delete(storage.resolve(logUpload.path("uploadId").asText() + ".payload"))
        val failedId = submit()
        assertThat(worker.runNext()).isTrue()
        val failed = result(failedId)
        assertThat(failed.path("state").asText()).isEqualTo("ERROR")
        assertThat(failed.path("error").path("code").asText()).isEqualTo("QUALITY_EVIDENCE_INTEGRITY_ERROR")
        assertThat(failed.has("qualityResult")).isFalse()

        if (System.getenv("VSRQG_CAPTURE_QUALITY_FIXTURE") == "1") {
            val commit = requireNotNull(System.getenv("GITHUB_SHA"))
            require(commit.matches(Regex("[0-9a-f]{40}")))
            val responses = mapper.createObjectNode()
            responses.set<JsonNode>("completed", completed)
            responses.set<JsonNode>("error", failed)
            responses.set<JsonNode>("traceability", traceResponse)
            responses.set<JsonNode>("testRun", testRunResponse)
            responses.set<JsonNode>("evidenceMetadata", evidenceResponses)
            val scenarios = responses.putObject("scenarios")
            fun scenario(name: String, evaluation: JsonNode, selectedRun: JsonNode?, metadata: JsonNode?) {
                val item = scenarios.putObject(name)
                item.set<JsonNode>("evaluation", evaluation)
                if (selectedRun != null) {
                    item.set<JsonNode>("traceability", traceResponse)
                    item.set<JsonNode>("testRun", selectedRun)
                    item.set<JsonNode>("evidenceMetadata", metadata)
                }
            }
            scenario("requiredIssueBlock", completed, testRunResponse, evidenceResponses)
            fun metadataFor(evaluation: JsonNode): JsonNode = mapper.createArrayNode().also { items ->
                evaluation.path("inputSnapshot").path("evidenceRefs").forEach { ref ->
                    items.add(sourceGet("/api/v1/evidence/${ref.path("evidenceId").asText()}", "evidence:read"))
                }
            }
            scenario("smokePass", smokePass, passRun, metadataFor(smokePass))
            scenario("smokeFail", smokeFail, failRun, metadataFor(smokeFail))
            scenario("missingEvidence", missingEvidence, null, null)
            val fixture = mapper.createObjectNode()
                .put("classification", "SYNTHETIC_FIXTURE")
                .put("fixtureId", "quality-upload-$commit")
            fixture.set<JsonNode>("responses", responses)
            val output = Path.of("build/m1/quality-fixture/upload/quality-evaluations.json")
            Files.createDirectories(output.parent)
            Files.writeString(output, mapper.writeValueAsString(fixture))
        }
    }
    private fun runWithTransactionAssertions(runId: String, evidenceIds: Set<String>) {
        val sessions = evidenceRows.inventory(evidenceIds)
        assertThat(sessions).hasSize(2)
        val observations = mutableListOf<TransactionObservation>()
        val events = mutableListOf<String>()
        val verifiedUploads = mutableListOf<String>()
        fun observe(stage: String) {
            val holder = TransactionSynchronizationManager.getResource(dataSource) as ConnectionHolder?
            val active = TransactionSynchronizationManager.isActualTransactionActive()
            if (active) {
                assertThat(holder).describedAs(stage).isNotNull()
                assertThat(holder!!.connection.autoCommit).describedAs(stage).isFalse()
                assertThat(holder.connection.transactionIsolation).describedAs(stage)
                    .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ)
            }
            observations.add(TransactionObservation(stage, active,
                TransactionSynchronizationManager.isSynchronizationActive(),
                TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(), holder))
            events.add(stage)
        }
        var reads = 0
        Mockito.doAnswer { invocation ->
            val stage = if (reads++ == 0) "source.read" else "source.recheck"
            observe(stage)
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) { events.add("$stage.completed=$status") }
                })
            }
            invocation.callRealMethod()
        }.`when`(observedEvidence).pin(evidenceIds, project, release, runId, sessions[0].binding.attemptId)
        sessions.forEach { session ->
            Mockito.doAnswer { invocation ->
                observe("bytes.verify")
                verifiedUploads.add(session.id)
                invocation.callRealMethod()
            }.`when`(observedPayloads).verify(session.id, session.expected)
        }
        Mockito.doAnswer { invocation ->
            val statement = invocation.callRealMethod() as JdbcClient.StatementSpec
            if (invocation.getArgument<String>(0).contains("INSERT INTO quality_input_snapshots")) {
                Mockito.spy(statement).also { insert ->
                    Mockito.doAnswer { update ->
                        observe("snapshot.insert")
                        update.callRealMethod()
                    }.`when`(insert).update()
                }
            } else statement
        }.`when`(observedJdbc).sql(Mockito.anyString())
        try {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertThat(worker.runNext()).isTrue()
        } finally {
            Mockito.reset(observedEvidence, observedPayloads, observedJdbc)
        }
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
        assertThat(TransactionSynchronizationManager.getResource(dataSource)).isNull()
        val committed = TransactionSynchronization.STATUS_COMMITTED
        assertThat(events).containsExactly("source.read", "source.read.completed=$committed",
            "bytes.verify", "bytes.verify", "source.recheck", "snapshot.insert", "source.recheck.completed=$committed")
        assertThat(verifiedUploads).containsExactlyInAnyOrderElementsOf(sessions.map { it.id })
        val read = observations.single { it.stage == "source.read" }
        val recheck = observations.single { it.stage == "source.recheck" }
        val insert = observations.single { it.stage == "snapshot.insert" }
        listOf(read, recheck, insert).forEach { observation ->
            assertThat(observation.active).describedAs(observation.stage).isTrue()
            assertThat(observation.synchronized).describedAs(observation.stage).isTrue()
            assertThat(observation.isolation).describedAs(observation.stage)
                .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ)
            assertThat(observation.resource).describedAs(observation.stage).isNotNull()
        }
        assertThat(read.readOnly).isTrue()
        assertThat(recheck.readOnly).isFalse()
        assertThat(insert.readOnly).isFalse()
        assertThat(recheck.resource).isSameAs(insert.resource).isNotSameAs(read.resource)
        observations.filter { it.stage == "bytes.verify" }.forEach { observation ->
            assertThat(observation.active).isFalse()
            assertThat(observation.synchronized).isFalse()
            assertThat(observation.isolation).isNull()
            assertThat(observation.resource).isNull()
        }
    }

    private data class TransactionObservation(
        val stage: String,
        val active: Boolean,
        val synchronized: Boolean,
        val readOnly: Boolean,
        val isolation: Int?,
        val resource: ConnectionHolder?,
    )
}
