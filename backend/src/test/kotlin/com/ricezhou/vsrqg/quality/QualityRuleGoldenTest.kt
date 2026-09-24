package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ricezhou.vsrqg.quality.adapter.StrictRuleYaml
import com.ricezhou.vsrqg.quality.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

@Timeout(60)
class QualityRuleGoldenTest {
    private val mapper = jacksonObjectMapper()
    private val examples = Path.of("../contracts/examples/v0.2/quality-rule")
    private val catalog = mapper.readTree(Files.readString(Path.of("../contracts/facts/v0.2/fact-catalog-v2.json")))
    private val definitions = catalog["facts"].associate {
        it["path"].asText() to FactDefinition(FactType.valueOf(it["type"].asText()),
            it["nullable"]?.asBoolean() ?: false,
            it["enumValues"]?.map(JsonNode::asText)?.toSet() ?: emptySet())
    }
    private val locals = catalog["itemBindings"].groupBy({ it["collectionPath"].asText() }) {
        it["localPath"].asText() to it["factPath"].asText()
    }.mapValues { (_, entries) -> entries.toMap() }

    private fun value(node: JsonNode): QualityValue = when {
        node.isNull -> QualityValue.Null
        node.isBoolean -> QualityValue.Bool(node.booleanValue())
        node.isTextual -> QualityValue.Text(node.textValue())
        node.isIntegralNumber -> QualityValue.Integer(node.bigIntegerValue())
        node.isNumber -> QualityValue.Decimal(node.decimalValue())
        node.isArray -> QualityValue.ArrayValue(node.map(::value))
        node.isObject -> QualityValue.ObjectValue(node.properties().associate { it.key to value(it.value) })
        else -> error("unsupported fixture node")
    }
    private fun rule(name: String) = RuleAst.parse(StrictRuleYaml().parse(Files.readAllBytes(examples.resolve(name))))
    private fun facts(node: JsonNode, refs: List<String> = emptyList()) =
        FactBindings(value(node) as QualityValue.ObjectValue, definitions, locals, refs)

    @Test fun `versioned golden cases fix both demonstration rules and aggregation`() {
        val fixture = mapper.readTree(Files.readString(examples.resolve("golden-cases-v1.json")))
        assertEquals(1, fixture["version"].intValue())
        val rules = mapOf(
            "smoke-case-outcome.yaml" to rule("smoke-case-outcome.yaml"),
            "required-issue-verified.yaml" to rule("required-issue-verified.yaml"),
        )
        for (case in fixture["cases"]) {
            val rule = rules.getValue(case["rule"].asText())
            val refs = case["evidenceRefs"]?.map(JsonNode::asText) ?: emptyList()
            val result = RuleEvaluator().evaluate(rule, facts(case["facts"], refs))
            assertEquals(RuleStatus.valueOf(case["status"].asText()), result.status, case["id"].asText())
            assertEquals(case["errorCode"]?.asText(), result.errorCode, case["id"].asText())
            assertEquals(rule.explanationCode, result.explanationCode, case["id"].asText())
            if (result.status != RuleStatus.ERROR) assertEquals(refs, result.evidenceRefs, case["id"].asText())
            if (case["id"].asText() == "case-fail") {
                assertTrue(MatchedFact("testResults[0].status", QualityValue.Text("FAIL")) in result.matchedFacts)
                assertEquals(QualityValue.Text("FAIL"), result.parameters["status"])
            }
        }
        val pass = RuleEvaluator().evaluate(rules.getValue("smoke-case-outcome.yaml"),
            facts(mapper.readTree("""{"testResults":[{"status":"PASS"}]}"""), listOf("LOG#1", "SCREENSHOT#1")))
        val blocked = RuleEvaluator().evaluate(rules.getValue("required-issue-verified.yaml"),
            facts(mapper.readTree("""{"issues":[{"required":true,"verified":false}]}""")))
        assertEquals("BLOCK", QualityAggregator.aggregate(listOf(pass.status, blocked.status)))
        val repeatedRefs = RuleEvaluator().evaluate(rules.getValue("smoke-case-outcome.yaml"),
            facts(mapper.readTree("""{"testResults":[{"status":"FAIL"}]}"""), listOf("LOG#1", "LOG#1", "SCREENSHOT#1")))
        assertEquals(listOf("LOG#1", "SCREENSHOT#1"), repeatedRefs.evidenceRefs)
        assertTrue(repeatedRefs.matchedFacts.all {
            it.value !is QualityValue.ArrayValue && it.value !is QualityValue.ObjectValue
        })
        assertEquals(QualityValue.Integer(java.math.BigInteger.ONE), repeatedRefs.parameters["ruleVersion"])
    }

    @Test fun `AST rejects invalid metadata and unexpected fields before evaluation`() {
        val base = Files.readString(examples.resolve("smoke-case-outcome.yaml"))
        for (changed in listOf(
            base.replace("SMOKE_CASE_OUTCOME", "bad-id"),
            base.replace("SMOKE_CASE_OUTCOME", "A".repeat(65)),
            base.replace("LOG\n", "SECRET\n"),
            base.replace("schemaVersion: \"1.0\"", "schemaVersion: \"2.0\""),
            base.replace("version: 1", "version: 0"),
            base + "\nunsupported: true\n",
        )) {
            assertEquals("RULE_AST_INVALID",
                assertThrows<QualityFailure> { RuleAst.parse(StrictRuleYaml().parse(changed.toByteArray())) }.code)
        }
    }

    @Test fun `AST direct values respect the same node and depth budgets as YAML`() {
        var tooDeep: QualityValue = QualityValue.Bool(true)
        repeat(33) { tooDeep = QualityValue.ArrayValue(listOf(tooDeep)) }
        assertEquals("RULE_DEPTH_LIMIT", assertThrows<QualityFailure> {
            RuleAst.parseExpression(tooDeep)
        }.code)
        val tooMany = QualityValue.ArrayValue(List(4096) { QualityValue.Bool(true) })
        assertEquals("RULE_NODES_LIMIT", assertThrows<QualityFailure> {
            RuleAst.parseExpression(tooMany)
        }.code)
    }
}
