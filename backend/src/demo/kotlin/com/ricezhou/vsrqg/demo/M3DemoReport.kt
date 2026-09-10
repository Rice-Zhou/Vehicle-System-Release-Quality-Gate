package com.ricezhou.vsrqg.demo

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

class M3DemoReport(val codeCommit: String, val workingTreeDirty: Boolean, val executionMode: String, val planVersion: Int) {
    init { require(Regex("[a-f0-9]{40}").matches(codeCommit) && executionMode in setOf("CI_FIXTURE", "REAL_DEVICE") && planVersion in 1..2) }
    var releaseId: String? = null
    var manifestId: String? = null
    var manifestDigest: String? = null
    var runId: String? = null
    var attemptId: String? = null
    var runStatus: String? = null
    var outputOwned: Boolean = false
    var resultDigest: String? = null
    var caseStatus: String? = null
    var apkSha256: String? = null
    var configSha256: String? = null
    val evidence = mutableListOf<Map<String, Any>>()
    private var generationStatus = "FAILED"
    private var outcome = "FAILED"
    private val errors = mutableListOf<String>()
    fun complete() {
        check(caseStatus == if(planVersion == 1) "PASS" else "FAIL") { "SCENARIO_OUTCOME_MISMATCH" }
        generationStatus = "SUCCEEDED"; outcome = "PASS"
    }
    fun fail(code: String) {
        require(Regex("[A-Z][A-Z0-9_]{2,63}").matches(code))
        generationStatus = "FAILED"; outcome = "FAILED"; errors.add(code)
    }
    fun write(root: Path) {
        val report = linkedMapOf(
            "schemaVersion" to "1.0", "classification" to "SYNTHETIC_DEMO", "codeCommit" to codeCommit,
            "workingTreeDirty" to workingTreeDirty, "executionMode" to executionMode,
            "generationStatus" to generationStatus, "scenarioOutcome" to outcome, "errorCodes" to errors,
            "releaseId" to releaseId, "manifestId" to manifestId, "manifestDigest" to manifestDigest,
            "runId" to runId, "runStatus" to runStatus, "attemptId" to attemptId,
            "planId" to "single-device-smoke", "planVersion" to planVersion,
            "caseId" to "apk-launch-smoke", "caseVersion" to planVersion, "caseStatus" to caseStatus,
            "testResult" to mapOf("attemptId" to attemptId, "status" to caseStatus, "resultDigest" to resultDigest), "resultDigest" to resultDigest, "evidence" to evidence,
            "apkSha256" to apkSha256, "configSha256" to configSha256,
            "releaseQuality" to "NOT_EVALUATED", "verified" to false,
            "notCovered" to listOf("FULL_M3", "CRASH", "ANR", "POWER_LOSS", "COMPANY", "BACKUP_RESTORE", "DEVICE_DISCONNECT", "AGENT_RESTART") +
                if(executionMode == "CI_FIXTURE") listOf("REAL_DEVICE", "ADB_INSTALL", "DEVICE_UI") else emptyList(),
        )
        Files.write(root.resolve("summary.json"), jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(report), CREATE_NEW)
    }
}
