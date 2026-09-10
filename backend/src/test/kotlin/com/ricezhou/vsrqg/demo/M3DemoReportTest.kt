package com.ricezhou.vsrqg.demo

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@Timeout(60)
class M3DemoReportTest {
    @TempDir lateinit var root: Path
    @Test fun `expected negative case retains FAIL without becoming release pass`() {
        val report = M3DemoReport("a".repeat(40), false, "CI_FIXTURE", 2)
        report.caseStatus = "FAIL"
        report.complete()
        report.write(root)
        val json = jacksonObjectMapper().readTree(root.resolve("summary.json").toFile())
        assertThat(json.path("generationStatus").asText()).isEqualTo("SUCCEEDED")
        assertThat(json.path("scenarioOutcome").asText()).isEqualTo("PASS")
        assertThat(json.path("caseStatus").asText()).isEqualTo("FAIL")
        assertThat(json.path("releaseQuality").asText()).isEqualTo("NOT_EVALUATED")
        assertThat(json.path("verified").asBoolean()).isFalse()
    }
    @Test fun `unexpected test status fails scenario while preserving source status`() {
        val report = M3DemoReport("a".repeat(40), true, "REAL_DEVICE", 1)
        report.caseStatus = "ERROR"
        assertThatThrownBy { report.complete() }.hasMessage("SCENARIO_OUTCOME_MISMATCH")
        report.fail("SCENARIO_OUTCOME_MISMATCH")
        report.write(root)
        assertThat(Files.readString(root.resolve("summary.json"))).contains("ERROR", "FAILED")
    }
    @Test fun `only explicit execution modes and fixed diagnostic codes may be reported`() {
        assertThatThrownBy { M3DemoReport("a".repeat(40), false, "AUTO", 1) }.isInstanceOf(IllegalArgumentException::class.java)
        val report = M3DemoReport("a".repeat(40), false, "CI_FIXTURE", 1)
        assertThatThrownBy { report.fail("https://secret/?token=hidden") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
