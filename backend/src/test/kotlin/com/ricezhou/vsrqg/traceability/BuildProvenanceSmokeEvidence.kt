package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal data class SmokeEvidenceContext(val commit: String, val runId: String, val runAttempt: Int)

internal object BuildProvenanceSmokeEvidenceContract {
    private val digest = Regex("^sha256:[0-9a-f]{64}$")
    private val sha = Regex("^[0-9a-f]{40}$")
    private val id = Regex("^[A-Za-z][A-Za-z0-9_-]{2,127}$")
    private val topFields = setOf(
        "schemaVersion", "exactCommit", "runId", "runAttempt", "validatorVersion",
        "envelopeDigest", "artifactDigest", "edgeRevisionIds", "replayResults",
        "fixedDiagnostics", "testCounts",
    )
    private val countValues = mapOf(
        "acceptedRequests" to 3, "rejectedRequests" to 3, "receipts" to 1,
        "rejectedReceipts" to 1, "edgeIdentities" to 3, "edgeRevisions" to 3,
        "auditEvents" to 2, "outboxEvents" to 1, "artifactReleaseEdges" to 0,
    )

    fun requireValid(document: JsonNode, context: SmokeEvidenceContext) {
        require(document.isObject && document.fieldNames().asSequence().toSet() == topFields) { "EVIDENCE_INVALID" }
        require(document.path("schemaVersion").isIntegralNumber && document.path("schemaVersion").asInt() == 2) { "EVIDENCE_INVALID" }
        require(document.path("exactCommit").isTextual && sha.matches(document.path("exactCommit").asText())) { "EVIDENCE_INVALID" }
        require(document.path("runId").isTextual && document.path("runId").asText().matches(Regex("^[1-9][0-9]*$"))) { "EVIDENCE_INVALID" }
        require(document.path("runAttempt").isIntegralNumber && document.path("runAttempt").asInt() >= 1) { "EVIDENCE_INVALID" }
        require(document.path("validatorVersion").asText() == "github-actions-provenance/v1") { "EVIDENCE_INVALID" }
        require(digest.matches(document.path("envelopeDigest").asText()) && digest.matches(document.path("artifactDigest").asText())) { "EVIDENCE_INVALID" }
        val edges = document.path("edgeRevisionIds")
        require(edges.isArray && edges.size() == 3) { "EVIDENCE_INVALID" }
        require(edges.all { it.isObject && it.fieldNames().asSequence().toSet() == setOf("edgeType", "edgeId", "revisionId") }) { "EVIDENCE_INVALID" }
        require(edges.map { it.path("edgeType").asText() }.toSet() == setOf("ISSUE_COMMIT", "COMMIT_BUILD", "BUILD_ARTIFACT")) { "EVIDENCE_INVALID" }
        require(edges.map { it.path("edgeId").asText() }.let { values -> values.toSet().size == 3 && values.all(id::matches) }) { "EVIDENCE_INVALID" }
        require(edges.map { it.path("revisionId").asText() }.let { values -> values.toSet().size == 3 && values.all(id::matches) }) { "EVIDENCE_INVALID" }
        val replay = document.path("replayResults")
        require(replay.isObject && replay.fieldNames().asSequence().toSet() == setOf("sameIdempotencyKey", "differentIdempotencyKey")) { "EVIDENCE_INVALID" }
        require(replay.path("sameIdempotencyKey").isBoolean && replay.path("sameIdempotencyKey").asBoolean()) { "EVIDENCE_INVALID" }
        require(replay.path("differentIdempotencyKey").isBoolean && replay.path("differentIdempotencyKey").asBoolean()) { "EVIDENCE_INVALID" }
        val diagnostics = document.path("fixedDiagnostics")
        require(diagnostics.isArray && diagnostics.map(JsonNode::asText) == listOf("BUILD_PROVENANCE_CONFLICT", "PROJECT_SCOPE_MISMATCH")) { "EVIDENCE_INVALID" }
        val counts = document.path("testCounts")
        require(counts.isObject && counts.fieldNames().asSequence().toSet() == countValues.keys) { "EVIDENCE_INVALID" }
        require(countValues.all { (name, expected) -> counts.path(name).isIntegralNumber && counts.path(name).asInt() == expected }) { "EVIDENCE_INVALID" }
        require(document.path("exactCommit").asText() == context.commit && document.path("runId").asText() == context.runId && document.path("runAttempt").asInt() == context.runAttempt) { "EVIDENCE_CONTEXT_MISMATCH" }
    }
}

internal class BuildProvenanceSmokeEvidencePublisher(private val objectMapper: ObjectMapper) {
    fun publish(finalPath: Path, document: JsonNode, context: SmokeEvidenceContext) {
        val target = finalPath.toAbsolutePath().normalize()
        Files.createDirectories(target.parent)
        Files.deleteIfExists(target)
        val temporary = Files.createTempFile(target.parent, "${target.fileName}.", ".tmp")
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), document)
            BuildProvenanceSmokeEvidenceContract.requireValid(objectMapper.readTree(temporary.toFile()), context)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Exception) {
            Files.deleteIfExists(temporary)
            Files.deleteIfExists(target)
            throw failure
        }
    }
}
