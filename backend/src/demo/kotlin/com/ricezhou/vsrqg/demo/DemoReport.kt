package com.ricezhou.vsrqg.demo

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.util.UUID

enum class DemoScenario(val field: String) {
    VALID_FILE("validFileLockExport"), CORRUPT_FILE("corruptFileRejected"),
    UNAUTHENTICATED("unauthenticatedRejected"), VIEWER("viewerWriteRejected"),
    REPLAY("idempotentReplay"), HISTORY("historicalExportStable")
}

enum class DemoFailure(val code: String) {
    INPUT("DEMO_INPUT_INVALID"), STARTUP("DEMO_STARTUP_FAILED"), BOOTSTRAP("DEMO_BOOTSTRAP_FAILED"),
    SCENARIO("DEMO_SCENARIO_FAILED"), HTTP_STATUS("DEMO_HTTP_STATUS"), OUTPUT("DEMO_OUTPUT_FAILED")
}

enum class DemoApiError { MANIFEST_VALIDATION_FAILED, ARTIFACT_CHECKSUM_MISMATCH, MANIFEST_LOCK_CONFLICT }

class DemoHttpFailure : RuntimeException(DemoFailure.HTTP_STATUS.code)

/** Only allowlisted values enter the report; exceptions, identities and HTTP bodies never do. */
class DemoReport(val runId: String, val codeCommit: String, val workingTreeDirty: Boolean = false) {
    init {
        require(UUID.fromString(runId).toString() == runId && Regex("[0-9a-f]{40}").matches(codeCommit))
    }
    private val statuses = DemoScenario.entries.associate { it.field to "NOT_RUN" }.toMutableMap()
    private val httpStatuses = DemoScenario.entries.associate { it.field to mutableListOf<Int>() }
    private val errors = mutableListOf<String>()
    private val apiErrorCodes = mutableSetOf<String>()
    private var active: DemoScenario? = null
    var releaseId: String? = null
    var manifestId: String? = null
    var corruptReleaseId: String? = null
    var corruptManifestId: String? = null
    var contentDigest: String? = null
    var payloadSha256: String? = null

    fun begin(scenario: DemoScenario) { active = scenario; statuses[scenario.field] = "RUNNING" }
    fun http(status: Int) { require(status in 100..599); httpStatuses.getValue(checkNotNull(active).field).add(status) }
    fun pass() { statuses[checkNotNull(active).field] = "PASS"; active = null }
    fun fail(failure: DemoFailure) {
        active?.let { statuses[it.field] = "FAILED" }
        active = null
        errors.add(failure.code)
    }
    fun apiError(code: DemoApiError) { apiErrorCodes.add(code.name) }
    fun scenarioStatuses(): Map<String, String> = statuses.toMap()
    fun passed(): Boolean = errors.isEmpty() && statuses.values.all { it == "PASS" }
    fun write(output: Path) {
        val values = linkedMapOf(
            "classification" to "SYNTHETIC_DEMO", "status" to if (passed()) "PASS" else "FAILED",
            "runId" to runId, "codeCommit" to codeCommit, "workingTreeDirty" to workingTreeDirty, "scenarioStatuses" to statuses,
            "httpStatuses" to httpStatuses, "releaseId" to safeId(releaseId), "manifestId" to safeId(manifestId),
            "corruptReleaseId" to safeId(corruptReleaseId), "corruptManifestId" to safeId(corruptManifestId),
            "contentDigest" to safeDigest(contentDigest), "payloadSha256" to safeDigest(payloadSha256), "errorCodes" to errors, "apiErrorCodes" to apiErrorCodes,
        )
        jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("summary.json").toFile(), values)
    }
    private fun safeId(value: String?): String? = value?.also { require(Regex("[a-zA-Z0-9_-]{1,128}").matches(it)) }
    private fun safeDigest(value: String?): String? = value?.also { require(Regex("(?:sha256:)?[0-9a-f]{64}").matches(it)) }
}
