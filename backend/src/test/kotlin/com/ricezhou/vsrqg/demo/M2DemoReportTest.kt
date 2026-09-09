package com.ricezhou.vsrqg.demo

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

@Timeout(60)
class M2DemoReportTest {
    @TempDir lateinit var output: Path
    private val mapper = jacksonObjectMapper()

    @Test
    fun `report projects only allowlisted values from actual HTTP snapshots`() {
        val report = M2DemoReport(RUN_ID, "a".repeat(40), false)
        report.http("mappingProfile", 201)
        report.identities("release_1", "manifest_1", "sync_1", "issue_snapshot_1")
        report.snapshot("A", snapshot("trace_a", "a", false), "verify_a")
        report.snapshot("B", snapshot("trace_b", "b", true), "verify_b")
        report.historyStable(latestSnapshotId = "trace_b", snapshotABytesStable = true)
        report.write(output)

        val raw = Files.readString(output.resolve("m2-summary.json"))
        val json = mapper.readTree(raw)
        assertThat(json.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "classification", "proofKind", "status", "runId", "codeCommit", "workingTreeDirty",
            "scenarioStatuses", "httpStatuses", "releaseId", "manifestId", "syncRunId",
            "issueSnapshotId", "verificationRunIds", "traceabilitySnapshotIds", "contentDigests",
            "issues", "history", "errorCodes",
        )
        assertThat(json.path("issues").path("A").path("DEMO-1").path("included").asBoolean()).isTrue()
        assertThat(json.path("issues").path("A").path("DEMO-2").path("included").asBoolean()).isFalse()
        assertThat(json.path("issues").path("B").elements().asSequence().all { !it.path("verified").asBoolean() }).isTrue()
        assertThat(raw).doesNotContain("token", "password", "locator", "jdbc", "proofReference", "repository")
    }

    @Test
    fun `report rejects missing fields unknown status and verified claims`() {
        val report = M2DemoReport(RUN_ID, "a".repeat(40), false)
        assertThatThrownBy { report.snapshot("A", mapper.readTree("{}"), "verify_a") }
            .hasMessageContaining("M2_REPORT_INVALID")
        assertThatThrownBy { report.runStatus(mapper.readTree("""{"status":"PAUSED"}""")) }
            .hasMessageContaining("M2_REPORT_INVALID")
        assertThatThrownBy { report.snapshot("A", snapshot("trace_a", "a", false, verified = true), "verify_a") }
            .hasMessageContaining("M2_REPORT_INVALID")
        val wrongBoolean = snapshot("trace_a", "a", false).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (wrongBoolean.path("issues").get(0) as com.fasterxml.jackson.databind.node.ObjectNode).put("included", "false")
        assertThatThrownBy { report.snapshot("A", wrongBoolean, "verify_a") }.hasMessageContaining("M2_REPORT_INVALID")
        val missingPath = snapshot("trace_a", "a", false).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (missingPath.path("issues").get(0) as com.fasterxml.jackson.databind.node.ObjectNode).remove("path")
        assertThatThrownBy { report.snapshot("A", missingPath, "verify_a") }.hasMessageContaining("M2_REPORT_INVALID")
        val missingGaps = snapshot("trace_a", "a", false).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (missingGaps.path("issues").get(0) as com.fasterxml.jackson.databind.node.ObjectNode).remove("gaps")
        assertThatThrownBy { report.snapshot("A", missingGaps, "verify_a") }.hasMessageContaining("M2_REPORT_INVALID")
        val unknownEdge = snapshot("trace_a", "a", false).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (unknownEdge.path("issues").get(0).path("path").get(0) as com.fasterxml.jackson.databind.node.ObjectNode)
            .put("edgeType", "SECRET_EDGE")
        assertThatThrownBy { report.snapshot("A", unknownEdge, "verify_a") }.hasMessageContaining("M2_REPORT_INVALID")
        val unknownGap = snapshot("trace_a", "a", false).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (unknownGap.path("issues").get(0).path("gaps").get(0) as com.fasterxml.jackson.databind.node.ObjectNode)
            .put("diagnosticCode", "SECRET_GAP")
        assertThatThrownBy { report.snapshot("A", unknownGap, "verify_a") }.hasMessageContaining("M2_REPORT_INVALID")
    }

    @Test
    fun `report remains failed when history scenario is incomplete`() {
        val report = M2DemoReport(RUN_ID, "a".repeat(40), false)
        report.identities("release_1", "manifest_1", "sync_1", "issue_snapshot_1")
        report.snapshot("A", snapshot("trace_a", "a", false), "verify_a")
        report.write(output)
        val json = mapper.readTree(output.resolve("m2-summary.json").toFile())
        assertThat(json.path("status").asText()).isEqualTo("FAILED")
        assertThat(json.path("scenarioStatuses").path("historyStable").asText()).isEqualTo("NOT_RUN")
    }

    private fun snapshot(id: String, digest: String, includeSecond: Boolean, verified: Boolean = false) = mapper.readTree("""
        {"snapshot":{"snapshotId":"$id","contentDigest":"sha256:${digest.repeat(64)}"},"issues":[
          {"sourceIssueId":"DEMO-1","fixed":true,"included":true,"verified":$verified,
           "path":[{"edgeType":"ISSUE_COMMIT","fromId":"issue_1","toId":"commit_1"},{"edgeType":"COMMIT_BUILD","fromId":"commit_1","toId":"build_1"},{"edgeType":"BUILD_ARTIFACT","fromId":"build_1","toId":"artifact_1"},{"edgeType":"ARTIFACT_RELEASE","fromId":"artifact_1","toId":"release_1"}],
           "gaps":[{"diagnosticCode":"TEST_RESULT_EVIDENCE_MISSING"}]},
          {"sourceIssueId":"DEMO-2","fixed":$includeSecond,"included":$includeSecond,"verified":false,
           "path":${if (includeSecond) "[{\"edgeType\":\"ISSUE_COMMIT\",\"fromId\":\"issue_2\",\"toId\":\"commit_2\"},{\"edgeType\":\"COMMIT_BUILD\",\"fromId\":\"commit_2\",\"toId\":\"build_2\"},{\"edgeType\":\"BUILD_ARTIFACT\",\"fromId\":\"build_2\",\"toId\":\"artifact_1\"},{\"edgeType\":\"ARTIFACT_RELEASE\",\"fromId\":\"artifact_1\",\"toId\":\"release_1\"}]" else "[]"},
           "gaps":[{"diagnosticCode":"${if (includeSecond) "TEST_RESULT_EVIDENCE_MISSING" else "ISSUE_COMMIT_MISSING"}"}]}
        ]}
    """)

    private companion object { const val RUN_ID = "d029fa0b-a4a7-415a-bf3e-86b4f65147ef" }
}
