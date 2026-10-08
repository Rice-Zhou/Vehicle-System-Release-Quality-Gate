package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.ricezhou.vsrqg.evidence.application.QualityEvidenceSource
import com.ricezhou.vsrqg.issue.application.CanonicalIssueSnapshot
import com.ricezhou.vsrqg.issue.application.IssueSnapshotCandidate
import com.ricezhou.vsrqg.issue.application.IssueSnapshotRepository
import com.ricezhou.vsrqg.issue.application.MaterializedIssueSnapshot
import com.ricezhou.vsrqg.manifest.application.LockedManifestRecord
import com.ricezhou.vsrqg.manifest.application.ManifestRepository
import com.ricezhou.vsrqg.manifest.application.ValidationReport
import com.ricezhou.vsrqg.manifest.application.ValidationStatus
import com.ricezhou.vsrqg.quality.application.FormalQualitySourceReader
import com.ricezhou.vsrqg.quality.application.QualityInputFailure
import com.ricezhou.vsrqg.quality.application.QualityRepository
import com.ricezhou.vsrqg.quality.application.QualityRuleSetRecord
import com.ricezhou.vsrqg.testmanagement.application.RunRecord
import com.ricezhou.vsrqg.testmanagement.application.TestRunRepository
import com.ricezhou.vsrqg.testmanagement.domain.RunState
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotHeaderView
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRepository
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito

@Timeout(60)
class QualityFormalSourceTest {
    private val mapper = ObjectMapper()

    @Test
    fun `formal sources produce schema shaped facts including manifest artifact indices`() {
        val (reader, request) = fixture("""{"artifacts":[{}]}""")
        val facts = reader.read("project-1", "release-1", request).snapshot.path("facts")
        assertTrue(facts.path("release").path("manifest").path("artifacts").isArray)
        assertEquals(1, facts.path("release").path("manifest").path("artifacts").size())
        assertEquals(0, facts.path("release").path("manifest").path("artifacts")[0].path("manifestArrayIndex").asInt())
        assertEquals("smoke", facts.path("testResults")[0].path("caseId").asText())
        assertEquals(0, facts.path("issues").size())
    }

    @Test
    fun `malformed locked manifest artifacts are rejected`() {
        val (reader, request) = fixture("""{"artifacts":{}}""")
        val failure = assertThrows(QualityInputFailure::class.java) {
            reader.read("project-1", "release-1", request)
        }
        assertEquals("QUALITY_MANIFEST_ARTIFACTS_INVALID", failure.code)
    }

    @Test
    fun formalSourceRejectsCrossReleaseAndIncompleteSelections() {
        val otherDigest = "sha256:" + "0".repeat(64)
        val failures = listOf(
            fixture(runReleaseId = "release-2") to "QUALITY_TEST_RUN_INVALID",
            fixture(lockedReleaseId = "release-2") to "QUALITY_MANIFEST_MISMATCH",
            fixture(lockedDigest = otherDigest) to "QUALITY_MANIFEST_MISMATCH",
            fixture(resultManifestId = "manifest-2") to "QUALITY_TEST_MANIFEST_MISMATCH",
            fixture(selectedCases = "[]") to "QUALITY_SELECTED_CASES_INVALID",
            fixture(runIds = """["run-1","run-2"]""") to "QUALITY_RUN_COUNT_INVALID",
            fixture(hasResult = false) to "QUALITY_TEST_RESULT_MISSING",
            fixture(requiredIssues = """[{"source":"source-1","sourceIssueId":"missing"}]""")
                to "QUALITY_REQUIRED_ISSUE_NOT_FOUND",
        )
        failures.forEach { (input, expected) ->
            val (reader, request) = input
            val failure = assertThrows(QualityInputFailure::class.java) {
                reader.read("project-1", "release-1", request)
            }
            assertEquals(expected, failure.code)
        }
        val (reader, request) = fixture()
        (request as ObjectNode).remove("traceabilitySnapshotId")
        assertEquals("QUALITY_TRACEABILITY_NOT_FOUND",
            assertThrows(QualityInputFailure::class.java) {
                reader.read("project-1", "release-1", request)
            }.code)
    }

    @Test
    fun producedSnapshotMatchesThePublishedSchema() {
        val (reader, request) = fixture()
        val snapshot = reader.read("project-1", "release-1", request).snapshot.deepCopy<ObjectNode>()
        snapshot.put("snapshotId", "qis-1")
        val schemaRoot = mapper.readTree(java.nio.file.Files.readString(
            java.nio.file.Path.of("../schemas/v0.2/quality-evaluation.schema.json")))
        val inputSchema = schemaRoot.path("\$defs").path("inputSnapshot").deepCopy<ObjectNode>()
        inputSchema.set<JsonNode>("\$defs", schemaRoot.path("\$defs"))
        val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(inputSchema.toString()).also { it.initializeValidators() }
        val violations = schema.validate(snapshot.toString(), InputFormat.JSON)
        assertTrue(violations.isEmpty(), violations.joinToString("; ") { it.message })
    }

    private fun fixture(
        rawManifest: String = """{"artifacts":[{}]}""",
        selectedCases: String = """[{"caseId":"smoke","version":1}]""",
        runIds: String = """["run-1"]""",
        runReleaseId: String = "release-1",
        requiredIssues: String = "[]",
        lockedDigest: String? = null,
        lockedReleaseId: String = "release-1",
        hasResult: Boolean = true,
        resultManifestId: String = "manifest-1",
    ): Pair<FormalQualitySourceReader, JsonNode> {
        val manifests = Mockito.mock(ManifestRepository::class.java)
        val issues = Mockito.mock(IssueSnapshotRepository::class.java)
        val traceability = Mockito.mock(TraceabilityVerificationRepository::class.java)
        val tests = Mockito.mock(TestRunRepository::class.java)
        val quality = Mockito.mock(QualityRepository::class.java)
        val evidence = Mockito.mock(QualityEvidenceSource::class.java)
        val now = Instant.EPOCH
        val manifestDigest = "sha256:" + "a".repeat(64)
        val definition = mapper.readTree("""{"catalogVersion":2,"engineVersion":"VSRQG-QUALITY-ENGINE-1","requiredIssueRefs":$requiredIssues,"selectedCaseRefs":$selectedCases}""")
        Mockito.`when`(quality.findPublished("set-1", 1)).thenReturn(QualityRuleSetRecord(
            "qrs-1", "project-1", "set-1", 1, 1, "PUBLISHED", definition, "sha256:" + "b".repeat(64),
            "author", "reviewer", now,
        ))
        Mockito.`when`(quality.rules("qrs-1")).thenReturn(emptyList())
        Mockito.`when`(traceability.findSnapshotHeader("release-1", "trace-1")).thenReturn(
            TraceabilitySnapshotHeaderView("trace-1", "project-1", "release-1", 1, "issue-1", "manifest-1",
                manifestDigest, "policy", "validator", "sha256:" + "c".repeat(64),
                "sha256:" + "d".repeat(64), now),
        )
        val validation = ValidationReport("validation-1", "manifest-1", ValidationStatus.VALID,
            manifestDigest, "schema", emptyList(), now, "canonical", "validator", 0)
        Mockito.`when`(manifests.findLockedExport("release-1", "manifest-1")).thenReturn(
            LockedManifestRecord(lockedReleaseId, "manifest-1", 1, mapper.readTree(rawManifest),
                byteArrayOf(), lockedDigest ?: manifestDigest, validation, now),
        )
        val candidate = IssueSnapshotCandidate("project-1", "release-1", 1, "sync-1", "source-1",
            "watermark", "adapter", "mapping", "filter", "age", emptyList())
        Mockito.`when`(issues.read("issue-1")).thenReturn(MaterializedIssueSnapshot("issue-1", candidate,
            CanonicalIssueSnapshot(byteArrayOf(), "sha256:" + "e".repeat(64)), now))
        Mockito.`when`(traceability.findSnapshotIssues("trace-1")).thenReturn(emptyList())
        Mockito.`when`(traceability.findSnapshotGaps("trace-1")).thenReturn(emptyList())
        Mockito.`when`(tests.run("run-1", false)).thenReturn(RunRecord("run-1", runReleaseId, "project-1",
            "agent-1", "device-1", "actor-1", RunState.COMPLETED, now, now, now, now, now))
        val resultDigest = "sha256:" + "f".repeat(64)
        val terminal = mapper.readTree(
            """{"releaseId":"release-1","manifestId":"$resultManifestId","manifestDigest":"$manifestDigest","attempts":[{"attemptId":"attempt-1","evidenceRequirements":[],"result":{"caseId":"smoke","caseVersion":1,"testRunId":"run-1","releaseId":"release-1","attemptId":"attempt-1","attemptNo":1,"resultDigest":"$resultDigest","status":"PASS","evidenceIds":[]}}]}""",
        ) as ObjectNode
        if (!hasResult) (terminal.path("attempts")[0] as ObjectNode).remove("result")
        Mockito.`when`(tests.terminalSnapshot("run-1")).thenReturn(terminal)
        val request = mapper.readTree("""{"ruleSet":{"ruleSetId":"set-1","version":1},"testRunIds":$runIds,"traceabilitySnapshotId":"trace-1"}""")
        return FormalQualitySourceReader(manifests, issues, traceability, tests, quality, evidence, mapper) to request
    }
}
