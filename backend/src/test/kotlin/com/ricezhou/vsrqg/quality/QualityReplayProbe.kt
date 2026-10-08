package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.quality.application.QualityDecisionRunner
import com.ricezhou.vsrqg.quality.application.QualityRuleVersionRecord
import java.nio.file.Files
import java.nio.file.Path

object QualityReplayProbe {
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
