package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.quality.application.QualityDecisionRunner
import com.ricezhou.vsrqg.quality.application.QualityInputFailure
import com.ricezhou.vsrqg.quality.application.QualityRuleVersionRecord
import com.ricezhou.vsrqg.quality.application.digest
import com.ricezhou.vsrqg.quality.application.toQualityValue
import com.ricezhou.vsrqg.quality.domain.QualityCanonicalEncoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(60)
class QualityReplayTest {
    private val mapper = ObjectMapper()
    private val input = mapper.readTree(java.nio.file.Files.readString(
        java.nio.file.Path.of("../contracts/examples/v0.2/quality-evaluation/snapshot.json")))
        .deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
            (path("versions") as com.fasterxml.jackson.databind.node.ObjectNode)
                .put("engineVersion", "VSRQG-QUALITY-ENGINE-1")
            remove("snapshotId")
            remove("inputDigest")
            put("inputDigest", digest(QualityCanonicalEncoder().encode(toQualityValue(this))))
            put("snapshotId", "fixture-snapshot")
        }
    private val rule = mapper.readTree(java.nio.file.Files.readString(
        java.nio.file.Path.of("../contracts/examples/v0.2/quality-evaluation/rule-set.json")))
        .path("rules")[0]

    @Test
    fun `result digest excludes generated result identity`() {
        val runner = QualityDecisionRunner(mapper)
        val versions = listOf(QualityRuleVersionRecord(
            "qrv-1", "qrs-1", 0, "REQUIRED_ISSUE_VERIFIED", 1, null, rule,
            null, null, null, null, null,
        ))
        val digests = (1..3).map { number ->
            runner.evaluate(input, versions, "result-" + number).result?.path("resultDigest")?.asText()
        }
        assertEquals(1, digests.toSet().size)
    }

    @Test
    fun `unknown historical engine version is not interpreted by current engine`() {
        val changed = input.deepCopy()
        (changed.path("versions") as com.fasterxml.jackson.databind.node.ObjectNode)
            .put("engineVersion", "UNAVAILABLE-ENGINE")
        assertThrows(QualityInputFailure::class.java) {
            QualityDecisionRunner(mapper).evaluate(changed, emptyList(), "result-1")
        }
    }
}
