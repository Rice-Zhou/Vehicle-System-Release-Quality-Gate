package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BuildProvenanceSmokeEvidenceTest {
    @TempDir
    lateinit var directory: Path

    private val objectMapper = ObjectMapper()
    private val context = SmokeEvidenceContext("a".repeat(40), "33705417856", 1)

    @Test
    fun `publishes only a fully validated document with an atomic same-directory move`() {
        val target = directory.resolve("build-provenance-smoke.json")
        BuildProvenanceSmokeEvidencePublisher(objectMapper).publish(target, validDocument(), context)

        assertThat(target).exists()
        BuildProvenanceSmokeEvidenceContract.requireValid(objectMapper.readTree(target.toFile()), context)
        assertThat(temporaryFiles(target)).isEmpty()
    }

    @Test
    fun `invalid publication removes stale final and temporary evidence`() {
        val target = directory.resolve("build-provenance-smoke.json")
        Files.writeString(target, "stale")
        val invalid = validDocument().also { it.put("token", "must-not-survive") }

        assertThatThrownBy { BuildProvenanceSmokeEvidencePublisher(objectMapper).publish(target, invalid, context) }
            .hasMessage("EVIDENCE_INVALID")
        assertThat(target).doesNotExist()
        assertThat(temporaryFiles(target)).isEmpty()
    }

    @Test
    fun `diagnostics object cannot masquerade as the required array`() {
        val invalid = validDocument().also { document ->
            document.set<com.fasterxml.jackson.databind.JsonNode>(
                "fixedDiagnostics",
                objectMapper.createObjectNode()
                    .put("first", "BUILD_PROVENANCE_CONFLICT")
                    .put("second", "PROJECT_SCOPE_MISMATCH"),
            )
        }

        assertThatThrownBy { BuildProvenanceSmokeEvidenceContract.requireValid(invalid, context) }
            .hasMessage("EVIDENCE_INVALID")
    }

    private fun temporaryFiles(target: Path) = Files.list(directory).use { paths ->
        paths.filter { it.fileName.toString().startsWith("${target.fileName}.") }.toList()
    }

    private fun validDocument() = objectMapper.createObjectNode().apply {
        put("schemaVersion", 2); put("exactCommit", context.commit); put("runId", context.runId)
        put("runAttempt", context.runAttempt); put("validatorVersion", "github-actions-provenance/v1")
        put("envelopeDigest", "sha256:${"b".repeat(64)}"); put("artifactDigest", "sha256:${"c".repeat(64)}")
        putArray("edgeRevisionIds").apply {
            addObject().put("edgeType", "ISSUE_COMMIT").put("edgeId", "ted_1").put("revisionId", "icr_1")
            addObject().put("edgeType", "COMMIT_BUILD").put("edgeId", "ted_2").put("revisionId", "cbr_2")
            addObject().put("edgeType", "BUILD_ARTIFACT").put("edgeId", "ted_3").put("revisionId", "bar_3")
        }
        putObject("replayResults").put("sameIdempotencyKey", true).put("differentIdempotencyKey", true)
        putArray("fixedDiagnostics").add("BUILD_PROVENANCE_CONFLICT").add("PROJECT_SCOPE_MISMATCH")
        putObject("testCounts").apply {
            put("acceptedRequests", 3); put("rejectedRequests", 3); put("receipts", 1); put("rejectedReceipts", 1)
            put("edgeIdentities", 3); put("edgeRevisions", 3); put("auditEvents", 2); put("outboxEvents", 1)
            put("artifactReleaseEdges", 0)
        }
    }
}
