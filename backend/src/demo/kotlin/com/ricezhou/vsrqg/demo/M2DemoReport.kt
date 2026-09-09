package com.ricezhou.vsrqg.demo

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.util.UUID

/** A deliberately small projection: raw requests, responses and identity material never cross this boundary. */
class M2DemoReport(
    val runId: String,
    val codeCommit: String,
    val workingTreeDirty: Boolean = false,
) {
    init {
        require(UUID.fromString(runId).toString() == runId && SHA1.matches(codeCommit)) { INVALID }
    }

    private val scenarios = linkedMapOf(
        "mappingProfile" to "NOT_RUN", "issueSync" to "NOT_RUN", "issueSnapshot" to "NOT_RUN",
        "buildIngestion" to "NOT_RUN", "snapshotA" to "NOT_RUN", "snapshotB" to "NOT_RUN",
        "sameKeyReplay" to "NOT_RUN", "userIngestionRejected" to "NOT_RUN",
        "invalidFactsRejected" to "NOT_RUN", "historyStable" to "NOT_RUN",
    )
    private val statuses = scenarios.keys.associateWith { mutableListOf<Int>() }
    private val verificationRunIds = linkedMapOf<String, String>()
    private val snapshotIds = linkedMapOf<String, String>()
    private val contentDigests = linkedMapOf<String, String>()
    private val issueResults = linkedMapOf<String, Map<String, Any>>()
    private val errors = mutableListOf<String>()
    private var releaseId: String? = null
    private var manifestId: String? = null
    private var syncRunId: String? = null
    private var issueSnapshotId: String? = null
    private var history: Map<String, Any>? = null

    fun http(scenario: String, status: Int) {
        require(scenario in scenarios && status in 100..599) { INVALID }
        statuses.getValue(scenario).add(status)
    }

    fun pass(scenario: String) {
        require(scenario in scenarios) { INVALID }
        scenarios[scenario] = "PASS"
    }

    fun identities(releaseId: String, manifestId: String, syncRunId: String, issueSnapshotId: String) {
        this.releaseId = safeId(releaseId)
        this.manifestId = safeId(manifestId)
        this.syncRunId = safeId(syncRunId)
        this.issueSnapshotId = safeId(issueSnapshotId)
    }

    fun runStatus(actual: JsonNode): String {
        val status = requiredText(actual, "status")
        require(status in setOf("QUEUED", "RUNNING", "SUCCEEDED", "FAILED")) { INVALID }
        return status
    }

    fun snapshot(label: String, actual: JsonNode, verificationRunId: String) {
        require(label in setOf("A", "B") && actual.isObject) { INVALID }
        val header = actual.path("snapshot").takeIf(JsonNode::isObject) ?: invalid()
        snapshotIds[label] = safeId(requiredText(header, "snapshotId"))
        contentDigests[label] = safeDigest(requiredText(header, "contentDigest"))
        verificationRunIds[label] = safeId(verificationRunId)
        val issues = actual.path("issues").takeIf(JsonNode::isArray) ?: invalid()
        require(issues.size() == 2) { INVALID }
        val projected = issues.associate { issue ->
            val sourceIssueId = requiredText(issue, "sourceIssueId")
            require(sourceIssueId in setOf("DEMO-1", "DEMO-2")) { INVALID }
            val verified = requiredBoolean(issue, "verified")
            require(!verified) { INVALID }
            sourceIssueId to linkedMapOf(
                "fixed" to requiredBoolean(issue, "fixed"),
                "included" to requiredBoolean(issue, "included"),
                "verified" to verified,
                "path" to requiredArray(issue, "path").map { edge -> linkedMapOf(
                    "edgeType" to requiredText(edge, "edgeType").also { require(it in EDGE_TYPES) { INVALID } },
                    "fromId" to safeId(requiredText(edge, "fromId")),
                    "toId" to safeId(requiredText(edge, "toId")),
                ) },
                "gaps" to requiredArray(issue, "gaps").map { gap ->
                    linkedMapOf("diagnosticCode" to requiredText(gap, "diagnosticCode").also {
                        require(it in GAP_CODES) { INVALID }
                    })
                },
            )
        }
        require(projected.size == 2) { INVALID }
        issueResults[label] = projected
        pass("snapshot$label")
    }

    fun historyStable(latestSnapshotId: String, snapshotABytesStable: Boolean) {
        require(snapshotABytesStable && snapshotIds["B"] == latestSnapshotId && snapshotIds["A"] != snapshotIds["B"]) { INVALID }
        history = linkedMapOf("latestSnapshotId" to safeId(latestSnapshotId), "snapshotABytesStable" to true)
        pass("historyStable")
    }

    fun fail(code: String = "M2_DEMO_FAILED") {
        require(SAFE_CODE.matches(code)) { INVALID }
        errors.add(code)
    }

    fun passed(): Boolean = errors.isEmpty() && scenarios.values.all { it == "PASS" } &&
        listOf(releaseId, manifestId, syncRunId, issueSnapshotId).none(String?::isNullOrBlank) &&
        verificationRunIds.keys == setOf("A", "B") && snapshotIds.keys == setOf("A", "B") &&
        contentDigests.keys == setOf("A", "B") && issueResults.keys == setOf("A", "B") && history != null

    fun write(output: Path) {
        val values = linkedMapOf(
            "classification" to "SYNTHETIC_DEMO", "proofKind" to "SYNTHETIC_FIXTURE",
            "status" to if (passed()) "PASS" else "FAILED", "runId" to runId,
            "codeCommit" to codeCommit, "workingTreeDirty" to workingTreeDirty,
            "scenarioStatuses" to scenarios, "httpStatuses" to statuses,
            "releaseId" to releaseId, "manifestId" to manifestId, "syncRunId" to syncRunId,
            "issueSnapshotId" to issueSnapshotId, "verificationRunIds" to verificationRunIds,
            "traceabilitySnapshotIds" to snapshotIds, "contentDigests" to contentDigests,
            "issues" to issueResults, "history" to history, "errorCodes" to errors,
        )
        jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("m2-summary.json").toFile(), values)
    }

    private fun requiredText(node: JsonNode, field: String): String =
        node.get(field)?.takeIf(JsonNode::isTextual)?.textValue()?.takeIf(String::isNotBlank) ?: invalid()
    private fun requiredBoolean(node: JsonNode, field: String): Boolean =
        node.get(field)?.takeIf(JsonNode::isBoolean)?.booleanValue() ?: invalid()
    private fun requiredArray(node: JsonNode, field: String): List<JsonNode> =
        node.get(field)?.takeIf(JsonNode::isArray)?.toList() ?: invalid()
    private fun safeId(value: String): String = value.also { require(SAFE_ID.matches(it)) { INVALID } }
    private fun safeDigest(value: String): String = value.also { require(DIGEST.matches(it)) { INVALID } }
    private fun invalid(): Nothing = throw IllegalArgumentException(INVALID)

    private companion object {
        const val INVALID = "M2_REPORT_INVALID"
        val SHA1 = Regex("[0-9a-f]{40}")
        val SAFE_ID = Regex("[a-zA-Z0-9_-]{1,128}")
        val DIGEST = Regex("sha256:[0-9a-f]{64}")
        val SAFE_CODE = Regex("[A-Z0-9_]{1,80}")
        val EDGE_TYPES = setOf("ISSUE_COMMIT", "COMMIT_BUILD", "BUILD_ARTIFACT", "ARTIFACT_RELEASE")
        val GAP_CODES = setOf("ISSUE_COMMIT_MISSING", "COMMIT_BUILD_MISSING", "BUILD_ARTIFACT_MISSING",
            "ARTIFACT_RELEASE_MISSING", "TEST_RESULT_EVIDENCE_MISSING")
    }
}
