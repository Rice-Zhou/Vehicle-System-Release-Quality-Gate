package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ricezhou.vsrqg.quality.adapter.StrictRuleYaml
import com.ricezhou.vsrqg.quality.application.DemoRuleGate
import com.ricezhou.vsrqg.quality.application.QualityRuleVersionRecord
import com.ricezhou.vsrqg.quality.application.RulePublicationInvalid
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.yaml.snakeyaml.Yaml

@Timeout(60)
class RulePublicationGateTest {
    private val mapper = jacksonObjectMapper()
    private val gate = DemoRuleGate(mapper)
    private val files = listOf("smoke-case-outcome.yaml", "required-issue-verified.yaml")

    @Test
    fun `only unchanged versioned sources match`() {
        val source = bytes(files[0])
        assertNotNull(gate.source(StrictRuleYaml().parse(source)))
        assertNull(gate.source(StrictRuleYaml().parse(
            source.toString(StandardCharsets.UTF_8).replace("onNoMatch: PASS", "onNoMatch: WARNING")
                .toByteArray(StandardCharsets.UTF_8))))
    }

    @Test
    fun `publication gate actually evaluates all fixed golden cases`() {
        val rules = files.map { mapper.valueToTree<JsonNode>(Yaml().load<Any>(bytes(it).toString(StandardCharsets.UTF_8))) }
        val definition = mapper.createObjectNode().put("catalogVersion", 2)
            .put("engineVersion", "VSRQG-QUALITY-ENGINE-1")
        definition.set<JsonNode>("rules", mapper.valueToTree(rules))
        val persisted = files.mapIndexed { index, file ->
            val value = StrictRuleYaml().parse(bytes(file))
            val source = gate.source(value)!!
            QualityRuleVersionRecord("rule-$index", "set", index, rules[index]["ruleId"].textValue(), 1,
                source.yaml, rules[index], source.path, source.commit,
                digest(source.yaml.toByteArray(StandardCharsets.UTF_8)), source.goldenPath, source.goldenDigest)
        }
        gate.requireGolden(definition, persisted)
        val altered = persisted.toMutableList()
        altered[0] = altered[0].copy(sourceCommit = "0".repeat(40))
        assertThrows<RulePublicationInvalid> { gate.requireGolden(definition, altered) }
    }

    private fun bytes(file: String) = requireNotNull(javaClass.classLoader.getResourceAsStream("contracts/quality-rule/$file"))
        .use { it.readBytes() }
    private fun digest(bytes: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
