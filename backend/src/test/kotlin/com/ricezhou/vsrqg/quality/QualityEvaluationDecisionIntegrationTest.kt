package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.evidence.application.QualityEvidenceSource
import com.ricezhou.vsrqg.issue.application.CanonicalIssueSnapshot
import com.ricezhou.vsrqg.issue.application.IssueSnapshotCandidate
import com.ricezhou.vsrqg.issue.application.IssueSnapshotRepository
import com.ricezhou.vsrqg.issue.application.MaterializedIssueSnapshot
import com.ricezhou.vsrqg.manifest.application.LockedManifestRecord
import com.ricezhou.vsrqg.manifest.application.ManifestRelease
import com.ricezhou.vsrqg.manifest.application.ManifestRepository
import com.ricezhou.vsrqg.manifest.application.ValidationReport
import com.ricezhou.vsrqg.manifest.application.ValidationStatus
import com.ricezhou.vsrqg.quality.adapter.QualityEvaluationWorker
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import com.ricezhou.vsrqg.testmanagement.application.RunRecord
import com.ricezhou.vsrqg.testmanagement.application.TestRunRepository
import com.ricezhou.vsrqg.testmanagement.domain.RunState
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotHeaderView
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRepository
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@Timeout(60)
@AutoConfigureMockMvc
class QualityEvaluationDecisionIntegrationTest : PostgresIntegrationTest() {
    @MockitoBean private lateinit var manifests: ManifestRepository
    @MockitoBean private lateinit var issues: IssueSnapshotRepository
    @MockitoBean private lateinit var traceability: TraceabilityVerificationRepository
    @MockitoBean private lateinit var runs: TestRunRepository
    @MockitoBean private lateinit var evidence: QualityEvidenceSource
    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var worker: QualityEvaluationWorker
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var mapper: ObjectMapper

    @Test
    fun `HTTP request uses formal sources and worker to expose completed and failed decisions`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
        val project = "prj_$suffix"
        val release = "rel_$suffix"
        val actor = "usr_$suffix"
        val reviewer = "rev_$suffix"
        val setId = "set_$suffix"
        val setVersionId = "qrs_$suffix"
        val runId = "run_$suffix"
        val traceId = "trace_$suffix"
        val manifestId = "man_$suffix"
        val issueId = "iss_$suffix"
        val at = Instant.parse("2026-10-08T00:00:00Z")
        val manifestDigest = "sha256:" + "a".repeat(64)
        val ruleDigest = "sha256:" + "b".repeat(64)

        jdbc.sql("INSERT INTO project(id,project_key,name,created_at) VALUES (:id,:id,'Quality decision fixture',:at)")
            .param("id", project).param("at", Timestamp.from(at)).update()
        for (id in listOf(actor, reviewer)) {
            jdbc.sql("INSERT INTO principal(id,issuer,subject,principal_type,created_at) VALUES (:id,'https://idp.vsrqg.test',:id,'USER',:at)")
                .param("id", id).param("at", Timestamp.from(at)).update()
        }
        jdbc.sql("INSERT INTO project_assignment(project_id,principal_id,role,created_at) VALUES (:project,:actor,'ENGINEER',:at)")
            .param("project", project).param("actor", actor).param("at", Timestamp.from(at)).update()
        jdbc.sql("""INSERT INTO release_record(id,project_id,vehicle,platform,system_version,build_id,
            status,created_at,updated_at) VALUES (:id,:project,'vehicle','platform','v1','build',
            'READY_FOR_TEST',:at,:at)""")
            .param("id", release).param("project", project).param("at", Timestamp.from(at)).update()
        val definition = mapper.readTree(Files.readString(
            Path.of("../contracts/examples/v0.2/quality-evaluation/rule-set.json")))
        (definition as com.fasterxml.jackson.databind.node.ObjectNode)
            .put("ruleSetId", setId).put("project", project).put("engineVersion", "VSRQG-QUALITY-ENGINE-1")
        jdbc.sql("""INSERT INTO quality_rule_set_versions(
            id,project_id,rule_set_id,rule_set_version,state,definition,catalog_version,
            engine_version,required_issue_refs,selected_case_refs,content_digest,
            author_id,created_at)
            VALUES (:id,:project,:setId,1,'DRAFT',CAST(:definition AS jsonb),2,
            'VSRQG-QUALITY-ENGINE-1','[]'::jsonb,'[{"caseId":"smoke","version":1}]'::jsonb,
            :digest,:actor,:at)""")
            .param("id", setVersionId).param("project", project).param("setId", setId)
            .param("definition", definition.toString()).param("digest", ruleDigest)
            .param("actor", actor).param("at", Timestamp.from(at)).update()
        jdbc.sql("""INSERT INTO quality_rule_versions(id,rule_set_version_id,ordinal,rule_id,
            rule_version,validated_ast) VALUES (:id,:setId,0,'REQUIRED_ISSUE_VERIFIED',1,CAST(:ast AS jsonb))""")
            .param("id", "qrv_$suffix").param("setId", setVersionId)
            .param("ast", definition.path("rules")[0].toString()).update()
        jdbc.sql("""UPDATE quality_rule_set_versions SET state='PUBLISHED',row_version=row_version+1,reviewer_id=:reviewer,
            review_reason='isolated fixture',published_at=:at WHERE id=:id""")
            .param("reviewer", reviewer).param("at", Timestamp.from(at))
            .param("id", setVersionId).update()

        Mockito.`when`(manifests.findRelease(release)).thenReturn(ManifestRelease(
            release, project, project, "vehicle", "platform", "v1", "build", "READY_FOR_TEST", manifestId))
        Mockito.`when`(traceability.findSnapshotHeader(release, traceId)).thenReturn(
            TraceabilitySnapshotHeaderView(traceId, project, release, 1, issueId, manifestId,
                manifestDigest, "policy", "validator", "sha256:" + "c".repeat(64),
                "sha256:" + "d".repeat(64), at))
        Mockito.`when`(manifests.findLockedExport(release, manifestId)).thenReturn(
            LockedManifestRecord(release, manifestId, 1, mapper.readTree("""{"artifacts":[{}]}"""),
                byteArrayOf(), manifestDigest, ValidationReport("val_$suffix", manifestId,
                    ValidationStatus.VALID, manifestDigest, "schema", emptyList(), at,
                    "canonical", "validator", 0), at))
        Mockito.`when`(issues.read(issueId)).thenReturn(MaterializedIssueSnapshot(issueId,
            IssueSnapshotCandidate(project, release, 1, "sync", "source", "watermark", "adapter",
                "mapping", "filter", "age", emptyList()),
            CanonicalIssueSnapshot(byteArrayOf(), "sha256:" + "e".repeat(64)), at))
        Mockito.`when`(traceability.findSnapshotIssues(traceId)).thenReturn(emptyList())
        Mockito.`when`(traceability.findSnapshotGaps(traceId)).thenReturn(emptyList())
        Mockito.`when`(runs.run(runId, false)).thenReturn(RunRecord(runId, release, project,
            "agent", "device", actor, RunState.COMPLETED, at, at, at, at, at))
        Mockito.`when`(runs.terminalSnapshot(runId)).thenReturn(mapper.readTree("""{
            "releaseId":"$release","manifestId":"$manifestId","manifestDigest":"$manifestDigest",
            "attempts":[{"attemptId":"attempt-1","evidenceRequirements":[],"result":{
                "caseId":"smoke","caseVersion":1,"testRunId":"$runId","releaseId":"$release",
                "attemptId":"attempt-1","attemptNo":1,
                "resultDigest":"sha256:${"f".repeat(64)}","status":"PASS","evidenceIds":[]}}]}"""))
        Mockito.`when`(evidence.pin(emptySet(), project, release, runId, "attempt-1"))
            .thenReturn(emptyList())

        fun submit(selectedTrace: String): String {
            val body = mapper.readTree("""{"ruleSet":{"ruleSetId":"$setId","version":1},
                "testRunIds":["$runId"],"traceabilitySnapshotId":"$selectedTrace"}""")
            val response = mvc.post("/api/v1/releases/$release/quality-evaluations") {
                with(jwt().jwt { it.issuer("https://idp.vsrqg.test").subject(actor)
                    .claim("principal_type", "USER") }
                    .authorities(SimpleGrantedAuthority("SCOPE_quality:evaluate")))
                header("Idempotency-Key", UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body.toString()
            }.andReturn().response
            assertThat(response.status).describedAs(response.contentAsString).isEqualTo(202)
            val responseBody = mapper.readTree(response.contentAsString)
            val jobId = responseBody.path("jobId").asText()
            jdbc.sql("UPDATE background_job SET created_at=:at WHERE id=:id")
                .param("at", Timestamp.from(Instant.EPOCH)).param("id", jobId).update()
            return responseBody.path("evaluationId").asText()
        }

        fun result(evaluationId: String): com.fasterxml.jackson.databind.JsonNode {
            val response = mvc.get("/api/v1/releases/$release/quality-results") {
                with(jwt().jwt { it.issuer("https://idp.vsrqg.test").subject(actor)
                    .claim("principal_type", "USER") }
                    .authorities(SimpleGrantedAuthority("SCOPE_quality:read")))
            }.andReturn().response
            assertThat(response.status).isEqualTo(200)
            return mapper.readTree(response.contentAsString).path("items")
                .first { it.path("evaluationId").asText() == evaluationId }
        }

        val completedId = submit(traceId)
        assertThat(worker.runNext()).isTrue()
        val completed = result(completedId)
        assertThat(completed.path("state").asText()).isEqualTo("COMPLETED")
        assertThat(completed.path("qualityResult").path("action").asText()).isEqualTo("BLOCK")
        assertThat(jdbc.sql("SELECT count(*) FROM quality_input_snapshots WHERE evaluation_id=:id")
            .param("id", completedId).query(Int::class.java).single()).isEqualTo(1)

        val failedId = submit("missing_$suffix")
        assertThat(worker.runNext()).isTrue()
        val failed = result(failedId)
        assertThat(failed.path("state").asText()).isEqualTo("ERROR")
        assertThat(failed.path("error").path("code").asText()).isEqualTo("QUALITY_TRACEABILITY_NOT_FOUND")
        assertThat(failed.has("qualityResult")).isFalse()

        if (System.getenv("VSRQG_CAPTURE_QUALITY_FIXTURE") == "1") {
            val commit = requireNotNull(System.getenv("GITHUB_SHA"))
            require(commit.matches(Regex("[0-9a-f]{40}")))
            val responses = mapper.createObjectNode()
            responses.set<com.fasterxml.jackson.databind.JsonNode>("completed", completed)
            responses.set<com.fasterxml.jackson.databind.JsonNode>("error", failed)
            val fixture = mapper.createObjectNode()
                .put("classification", "SYNTHETIC_FIXTURE")
                .put("fixtureId", "quality-decision-$commit")
            fixture.set<com.fasterxml.jackson.databind.JsonNode>("responses", responses)
            val output = Path.of("build/m1/quality-fixture/quality-evaluations.json")
            Files.createDirectories(output.parent)
            Files.writeString(output, mapper.writeValueAsString(fixture))
        }
    }
}
