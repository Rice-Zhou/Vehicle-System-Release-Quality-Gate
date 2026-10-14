package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.quality.application.QualityDecisionRunner
import com.ricezhou.vsrqg.quality.application.QualityRuleVersionRecord
import java.nio.file.Files
import java.nio.file.Path

object QualityReplayProbe {
    fun replayDigests(snapshot: JsonNode, rule: JsonNode, resultIds: Iterable<String>): List<String> {
        val snapshotFile = Files.createTempFile("quality-input-", ".json")
        val ruleFile = Files.createTempFile("quality-rule-", ".json")
        try {
            Files.writeString(snapshotFile, snapshot.toString())
            Files.writeString(ruleFile, rule.toString())
            val classpath = System.getProperty("java.class.path")
            check(classpath.isNotBlank()) { "QUALITY_REPLAY_CLASSPATH_MISSING" }
            val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
            val javaExecutable = Path.of(System.getProperty("java.home"), "bin", executable)
            return resultIds.map { resultId ->
                val process = ProcessBuilder(javaExecutable.toString(), "-cp", classpath,
                    QualityReplayProbe::class.java.name,
                    snapshotFile.toString(), ruleFile.toString(), resultId)
                    .redirectErrorStream(true).start()
                check(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    "QUALITY_REPLAY_TIMEOUT"
                }
                val output = process.inputStream.bufferedReader().readText()
                check(process.exitValue() == 0) { "QUALITY_REPLAY_CHILD_FAILED: $output" }
                output
            }
        } finally {
            Files.deleteIfExists(snapshotFile)
            Files.deleteIfExists(ruleFile)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3)
        val mapper = ObjectMapper()
        val snapshot = mapper.readTree(Files.readString(Path.of(args[0])))
        val rule = mapper.readTree(Files.readString(Path.of(args[1])))
        val version = QualityRuleVersionRecord(
            "qrv-1", "qrs-1", 0, "REQUIRED_ISSUE_VERIFIED", 1, null, rule,
            null, null, null, null, null,
        )
        val result = QualityDecisionRunner(mapper).evaluate(snapshot, listOf(version), args[2]).result
            ?: error("QUALITY_REPLAY_NO_RESULT")
        print(result.path("resultDigest").asText())
    }
}
