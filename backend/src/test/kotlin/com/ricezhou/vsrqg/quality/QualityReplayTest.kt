package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.quality.application.QualityDecisionRunner
import com.ricezhou.vsrqg.quality.application.QualityInputFailure
import com.ricezhou.vsrqg.quality.application.QualityRuleVersionRecord
import com.ricezhou.vsrqg.quality.application.digest
import com.ricezhou.vsrqg.quality.application.toQualityValue
import com.ricezhou.vsrqg.quality.domain.QualityCanonicalEncoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

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
    fun `fixed input replays to one digest in three fresh JVMs`() {
        val digests = QualityReplayProbe.replayDigests(input, rule, (1..3).map { "result-$it" })
        assertEquals(1, digests.toSet().size)
        assertEquals(input.path("inputDigest").asText().length, digests.first().length)
    }
    @Test
    fun `no applicable rule records error instead of passing an empty decision`() {
        val guarded = rule.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        guarded.set<com.fasterxml.jackson.databind.JsonNode>("appliesWhen",
            mapper.readTree("""{"op":"eq","path":"release.releaseId","value":"another-release"}"""))
        val version = QualityRuleVersionRecord(
            "qrv-1", "qrs-1", 0, "REQUIRED_ISSUE_VERIFIED", 1, null, guarded,
            null, null, null, null, null,
        )
        val decision = QualityDecisionRunner(mapper).evaluate(input, listOf(version), "result-1")
        assertEquals(null, decision.result)
        assertEquals("QUALITY_NO_APPLICABLE_RULE", decision.errorCode)
        assertEquals("NOT_APPLICABLE", decision.ruleResults.single().path("status").asText())
    }
    @ParameterizedTest(name = "unknown {0} is rejected with valid input and rules")
    @ValueSource(strings = ["catalogVersion", "canonicalizationVersion"])
    fun `unknown catalog and encoding versions cannot use current interpreters`(field: String) {
        val runner = QualityDecisionRunner(mapper)
        val rules = listOf(QualityRuleVersionRecord(
            "qrv-1", "qrs-1", 0, "REQUIRED_ISSUE_VERIFIED", 1, null, rule,
            null, null, null, null, null,
        ))
        val baseline = runner.evaluate(input, rules, "result-supported")
        assertNull(baseline.errorCode)
        assertEquals("BLOCK", baseline.result?.path("action")?.asText())
        assertEquals("BLOCK", baseline.ruleResults.single().path("status").asText())

        val changed = input.deepCopy()
        val versions = changed.path("versions") as com.fasterxml.jackson.databind.node.ObjectNode
        if (field == "catalogVersion") versions.put(field, 999)
        else versions.put(field, "UNAVAILABLE-ENCODING")
        val material = changed.deepCopy().apply {
            remove("snapshotId")
            remove("inputDigest")
        }
        changed.put("inputDigest", digest(QualityCanonicalEncoder().encode(toQualityValue(material))))

        val failure = assertThrows(QualityInputFailure::class.java) {
            runner.evaluate(changed, rules, "result-unsupported")
        }
        assertEquals("QUALITY_VERSION_UNSUPPORTED", failure.code)
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
