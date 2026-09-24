package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ricezhou.vsrqg.quality.adapter.StrictRuleYaml
import com.ricezhou.vsrqg.quality.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

@Timeout(60)
class RuleOperatorMatrixTest {
    private val mapper = jacksonObjectMapper()
    private val catalog = mapper.readTree(Files.readString(Path.of("../contracts/facts/v0.2/fact-catalog-v2.json")))
    private val definitions = catalog["facts"].associate {
        it["path"].asText() to FactDefinition(
            FactType.valueOf(it["type"].asText()),
            it["nullable"]?.asBoolean() ?: false,
            it["enumValues"]?.map(JsonNode::asText)?.toSet() ?: emptySet(),
        )
    }
    private val locals = catalog["itemBindings"].groupBy({ it["collectionPath"].asText() }) {
        it["localPath"].asText() to it["factPath"].asText()
    }.mapValues { (_, entries) -> entries.toMap() }

    private fun obj(vararg entries: Pair<String, QualityValue>) = QualityValue.ObjectValue(mapOf(*entries))
    private fun arr(vararg entries: QualityValue) = QualityValue.ArrayValue(entries.toList())
    private fun text(value: String) = QualityValue.Text(value)
    private fun integer(value: String) = QualityValue.Integer(BigInteger(value))
    private fun bindings(vararg entries: Pair<String, QualityValue>) =
        FactBindings(obj(*entries), definitions, locals)
    private fun expr(source: String): RuleAst {
        val quoted = Regex("path: ([A-Za-z][A-Za-z0-9_.\\[\\]]*)")
            .replace(source) { "path: \"${it.groupValues[1]}\"" }
        return RuleAst.parseExpression(StrictRuleYaml().parse(quoted.toByteArray()))
    }
    private fun evaluate(source: String, facts: FactBindings) = RuleEvaluator().evaluateExpression(expr(source), facts)

    @Test fun `comparison operators distinguish value empty missing null and type errors`() {
        val facts = bindings("release" to obj("releaseId" to text(""), "manifest" to obj("digest" to text("x"))))
        assertEquals(true, evaluate("{op: eq, path: release.releaseId, value: \"\"}", facts).boolean)
        assertEquals(false, evaluate("{op: ne, path: release.releaseId, value: \"\"}", facts).boolean)
        assertEquals("RULE_FACT_MISSING", evaluate("{op: eq, path: release.releaseId, value: x}", bindings()).errorCode)
        assertEquals("RULE_FACT_TYPE", evaluate("{op: eq, path: release.releaseId, value: x}", bindings("release" to obj("releaseId" to integer("1")))).errorCode)
        assertEquals("RULE_FACT_TYPE", evaluate("{op: gt, path: release.releaseId, value: x}", bindings("release" to obj("releaseId" to QualityValue.Null))).errorCode)
        assertEquals("RULE_FACT_NULL", evaluate("{op: eq, path: release.releaseId, value: null}", bindings("release" to obj("releaseId" to QualityValue.Null))).errorCode)
        assertEquals("RULE_FACT_NULL", evaluate("{op: ne, path: release.releaseId, value: null}", bindings("release" to obj("releaseId" to QualityValue.Null))).errorCode)
        for (op in listOf("gt", "gte", "lt", "lte")) {
            val source = "{op: $op, left: {op: count, path: issues[]}, right: 9007199254740993}"
            val equal = bindings("issues" to arr())
            assertEquals(op == "lt" || op == "lte", evaluate(source, equal).boolean, op)
            assertEquals("RULE_FACT_MISSING", evaluate(source, bindings()).errorCode, op)
            val numeric = "{op: any, path: testResults[], where: {op: $op, path: item.attemptNo, value: 2}}"
            assertEquals(op == "lt" || op == "lte", evaluate(numeric,
                bindings("testResults" to arr(obj("attemptNo" to integer("1"))))).boolean, op)
            assertEquals("RULE_FACT_MISSING", evaluate(numeric,
                bindings("testResults" to arr(obj()))).errorCode, op)
            assertEquals("RULE_FACT_NULL", evaluate(numeric,
                bindings("testResults" to arr(obj("attemptNo" to QualityValue.Null)))).errorCode, op)
            assertEquals("RULE_FACT_TYPE", evaluate(numeric,
                bindings("testResults" to arr(obj("attemptNo" to text("1"))))).errorCode, op)
            assertEquals("RULE_FACT_TYPE", evaluate("{op: $op, path: release.releaseId, value: R}", facts).errorCode, op)
            assertEquals("RULE_FACT_TYPE", evaluate("{op: $op, path: release.releaseId, value: R}", bindings("release" to obj("releaseId" to QualityValue.Null))).errorCode, op)
            assertEquals("RULE_FACT_TYPE", evaluate("{op: $op, path: release.releaseId, value: R}", bindings("release" to obj("releaseId" to integer("1")))).errorCode, op)
            assertEquals("RULE_FACT_TYPE", evaluate("{op: $op, path: release.releaseId, value: R}", bindings("release" to obj("releaseId" to text("R")))).errorCode, op)
            assertEquals("RULE_FACT_TYPE", evaluate("{op: $op, path: traceability.minimumConfidenceLevel, value: LOW}",
                bindings("traceability" to obj("minimumConfidenceLevel" to text("HIGH")))).errorCode, op)
        }
    }

    @Test fun `scalar operator matrix covers value empty missing null and type error`() {
        val value = bindings("release" to obj("releaseId" to text("R")))
        val empty = bindings("release" to obj("releaseId" to text("")))
        val missing = bindings()
        val nullValue = bindings("release" to obj("releaseId" to QualityValue.Null))
        val wrong = bindings("release" to obj("releaseId" to QualityValue.Bool(true)))
        for (op in listOf("eq", "ne")) {
            val rule = "{op: $op, path: release.releaseId, value: R}"
            assertEquals(op == "eq", evaluate(rule, value).boolean, op)
            assertEquals(op == "ne", evaluate(rule, empty).boolean, op)
            assertEquals("RULE_FACT_MISSING", evaluate(rule, missing).errorCode, op)
            assertEquals("RULE_FACT_NULL", evaluate(rule, nullValue).errorCode, op)
            assertEquals("RULE_FACT_TYPE", evaluate(rule, wrong).errorCode, op)
        }
        val inRule = "{op: in, path: release.releaseId, values: [R]}"
        assertEquals(true, evaluate(inRule, value).boolean)
        assertEquals(false, evaluate(inRule, empty).boolean)
        assertEquals("RULE_FACT_MISSING", evaluate(inRule, missing).errorCode)
        assertEquals("RULE_FACT_NULL", evaluate(inRule, nullValue).errorCode)
        assertEquals("RULE_FACT_TYPE", evaluate(inRule, wrong).errorCode)
        val exists = "{op: exists, path: release.releaseId}"
        assertEquals(true, evaluate(exists, value).boolean)
        assertEquals(true, evaluate(exists, empty).boolean)
        assertEquals(false, evaluate(exists, missing).boolean)
        assertEquals(true, evaluate(exists, nullValue).boolean)
        assertEquals(true, evaluate(exists, wrong).boolean) // exists(path) deliberately does not read the value.
    }

    @Test fun `collection operator matrix covers empty missing null type error and predicate error`() {
        val item = obj("required" to QualityValue.Bool(true))
        val scenarios = listOf(
            bindings("issues" to arr(item)),
            bindings("issues" to arr()),
            bindings(),
            bindings("issues" to QualityValue.Null),
            bindings("issues" to text("wrong")),
        )
        for (op in listOf("exists", "any", "all", "count")) {
            val source = "{op: $op, path: issues[], where: {op: eq, path: item.required, value: true}}"
            scenarios.forEachIndexed { index, facts ->
                val result = evaluate(source, facts)
                when (index) {
                    0 -> if (op == "count") assertEquals(BigInteger.ONE, result.number) else assertEquals(true, result.boolean)
                    1 -> if (op == "count") assertEquals(BigInteger.ZERO, result.number)
                         else assertEquals(op == "all", result.boolean)
                    2 -> assertEquals(if (op == "exists") false else null, result.boolean)
                    3 -> assertEquals("RULE_FACT_NULL", result.errorCode, op)
                    4 -> assertEquals("RULE_FACT_TYPE", result.errorCode, op)
                }
            }
            assertEquals("RULE_FACT_TYPE", evaluate(source, bindings("issues" to arr(text("wrong")))).errorCode, op)
        }
    }

    @Test fun `in exists and count cover empty missing null and invalid types`() {
        val empty = bindings("issues" to arr())
        assertEquals(false, evaluate("{op: in, path: release.releaseId, values: []}", bindings("release" to obj("releaseId" to text("R")))).boolean)
        assertEquals("RULE_FACT_MISSING", evaluate("{op: in, path: release.releaseId, values: [R]}", bindings()).errorCode)
        assertEquals("RULE_FACT_NULL", evaluate("{op: in, path: release.releaseId, values: [null]}", bindings("release" to obj("releaseId" to QualityValue.Null))).errorCode)
        assertEquals(false, evaluate("{op: exists, path: issues[]}", bindings()).boolean)
        assertEquals(true, evaluate("{op: exists, path: issues[]}", empty).boolean)
        assertEquals(true, evaluate("{op: exists, path: issues[]}", bindings("issues" to QualityValue.Null)).boolean)
        assertEquals(BigInteger.ZERO, evaluate("{op: count, path: issues[]}", empty).number)
        assertEquals(false, evaluate("{op: exists, path: issues[], where: {op: eq, path: item.required, value: true}}", empty).boolean)
        assertEquals(false, evaluate("{op: exists, path: issues[], where: {op: eq, path: item.required, value: true}}", bindings()).boolean)
        assertEquals("RULE_FACT_NULL", evaluate("{op: count, path: issues[]}", bindings("issues" to QualityValue.Null)).errorCode)
        assertEquals("RULE_FACT_TYPE", evaluate("{op: count, path: issues[]}", bindings("issues" to text("wrong"))).errorCode)
        assertEquals("RULE_FACT_TYPE", evaluate("{op: count, path: issues[]}", bindings("issues" to arr(text("wrong")))).errorCode)
        assertEquals("RULE_FACT_NULL", evaluate("{op: count, path: issues[]}", bindings("issues" to arr(QualityValue.Null))).errorCode)
        assertEquals("RULE_FACT_TYPE", evaluate("{op: exists, path: issues[], where: {op: eq, path: item.required, value: true}}", bindings("issues" to text("wrong"))).errorCode)
    }

    @Test fun `quantifiers preserve vacuous truth and propagate item failures`() {
        val empty = bindings("issues" to arr())
        assertEquals(true, evaluate("{op: all, path: issues[], where: {op: eq, path: item.required, value: true}}", empty).boolean)
        assertEquals(false, evaluate("{op: any, path: issues[], where: {op: eq, path: item.required, value: true}}", empty).boolean)
        val withError = bindings("issues" to arr(obj("required" to QualityValue.Bool(true)), obj()))
        for (op in listOf("all", "any", "exists", "count")) {
            val source = "{op: $op, path: issues[], where: {op: eq, path: item.required, value: true}}"
            assertEquals("RULE_FACT_MISSING", evaluate(source, withError).errorCode, op)
        }
        assertEquals("RULE_ITEM_SCOPE", evaluate("{op: eq, path: item.required, value: true}", empty).errorCode)
        assertEquals("RULE_PATH_UNKNOWN", evaluate("{op: any, path: testResults[], where: {op: eq, path: item.required, value: true}}", bindings("testResults" to arr(obj("status" to text("PASS"))))).errorCode)
    }

    @Test fun `boolean operators evaluate all operands regardless of order`() {
        val missing = bindings()
        for (op in listOf("and", "or")) {
            for (operands in listOf(
                "[{op: exists, path: issues[]}, {op: eq, path: release.releaseId, value: R}]",
                "[{op: eq, path: release.releaseId, value: R}, {op: exists, path: issues[]}]",
            )) {
                assertEquals("RULE_FACT_MISSING", evaluate("{op: $op, operands: $operands}", missing).errorCode)
            }
        }
        assertEquals("RULE_FACT_MISSING", evaluate("{op: not, operand: {op: eq, path: release.releaseId, value: R}}", missing).errorCode)
    }

    @Test fun `boolean operator matrix preserves empty and null scalar outcomes`() {
        val scenarios = listOf(
            bindings("release" to obj("releaseId" to text("R"))) to true,
            bindings("release" to obj("releaseId" to text(""))) to false,
        )
        for ((facts, equalsR) in scenarios) {
            val eq = "{op: eq, path: release.releaseId, value: R}"
            assertEquals(!equalsR, evaluate("{op: not, operand: $eq}", facts).boolean)
            assertEquals(false, evaluate("{op: and, operands: [$eq, {op: exists, path: issues[]}]}", facts).boolean)
            assertEquals(equalsR, evaluate("{op: or, operands: [$eq, {op: exists, path: issues[]}]}", facts).boolean)
        }
        val nullValue = bindings("release" to obj("releaseId" to QualityValue.Null))
        for (op in listOf("and", "or")) {
            assertEquals("RULE_FACT_NULL", evaluate("{op: $op, operands: [{op: exists, path: issues[]}, {op: eq, path: release.releaseId, value: R}]}", nullValue).errorCode)
        }
        assertEquals("RULE_FACT_NULL", evaluate("{op: not, operand: {op: eq, path: release.releaseId, value: R}}", nullValue).errorCode)
        val wrong = bindings("release" to obj("releaseId" to QualityValue.Bool(true)))
        for (op in listOf("and", "or")) {
            assertEquals("RULE_FACT_TYPE", evaluate("{op: $op, operands: [{op: exists, path: issues[]}, {op: eq, path: release.releaseId, value: R}]}", wrong).errorCode)
        }
        assertEquals("RULE_FACT_TYPE", evaluate("{op: not, operand: {op: eq, path: release.releaseId, value: R}}", wrong).errorCode)
    }

    @Test fun `integer and decimal operands reject implicit coercion in either order`() {
        val decimal = bindings("evidence" to obj("memory" to obj("samples" to arr(
            obj("pssMiB" to QualityValue.Decimal(BigDecimal("400.0")))))))
        assertEquals("RULE_FACT_TYPE", evaluate(
            "{op: any, path: evidence.memory.samples[], where: {op: eq, path: item.pssMiB, value: 400}}", decimal).errorCode)
        val integer = bindings("issues" to arr())
        assertEquals("RULE_FACT_TYPE", evaluate(
            "{op: eq, left: {op: count, path: issues[]}, right: 0.0}", integer).errorCode)
        assertEquals("RULE_FACT_TYPE", evaluate(
            "{op: eq, left: 0.0, right: {op: count, path: issues[]}}", integer).errorCode)
    }

    @Test fun `step limit is cumulative across a rule set`() {
        val source = """
            schemaVersion: "1.0"
            ruleId: COUNT_ISSUES
            version: 1
            title: Count Issues
            scope: RELEASE
            condition: {op: gt, left: {op: count, path: "issues[]"}, right: 0}
            onMatch: BLOCK
            onNoMatch: PASS
            explanation: {code: COUNT_ISSUES, template: Count Issues}
        """.trimIndent()
        val rule = RuleAst.parse(StrictRuleYaml().parse(source.toByteArray()))
        val outcomes = RuleEvaluator(maxSteps = 5).evaluateAll(listOf(rule, rule), bindings("issues" to arr()))
        assertEquals(listOf(RuleStatus.PASS, RuleStatus.ERROR), outcomes.map { it.status })
        assertEquals("RULE_STEPS_LIMIT", outcomes[1].errorCode)
    }

    @Test fun `collection scanning consumes budget before validation and sorting`() {
        val twoIssues = bindings("issues" to arr(obj(), obj()))
        assertEquals("RULE_STEPS_LIMIT", RuleEvaluator(maxSteps = 1).evaluateExpression(
            expr("{op: count, path: issues[]}"), twoIssues).errorCode)
        val invalidLateSample = bindings("evidence" to obj("memory" to obj("samples" to arr(
            obj("capturedAt" to text("2026-01-01T00:00:00Z"), "evidenceId" to text("a"),
                "pssMiB" to QualityValue.Decimal(BigDecimal("401"))),
            obj("evidenceId" to text("b"), "pssMiB" to QualityValue.Decimal(BigDecimal("402"))),
        ))))
        val rule = expr("{op: consecutive, path: evidence.memory.samples[], count: 2, where: {op: gt, path: item.pssMiB, value: 400.0}}")
        assertEquals("RULE_STEPS_LIMIT", RuleEvaluator(maxSteps = 1).evaluateExpression(rule, invalidLateSample).errorCode)
    }

    @Test fun `consecutive sorts by capturedAt and evidenceId and never hides errors`() {
        val sample = { time: String, id: String, pss: QualityValue ->
            obj("capturedAt" to text(time), "evidenceId" to text(id), "pssMiB" to pss)
        }
        val source = "{op: consecutive, path: evidence.memory.samples[], count: 2, where: {op: gt, path: item.pssMiB, value: 400.0}}"
        val facts = bindings("evidence" to obj("memory" to obj("samples" to arr(
            sample("2026-01-01T00:00:01Z", "a", QualityValue.Decimal(BigDecimal("402.00"))),
            sample("2026-01-01T00:00:03Z", "c", QualityValue.Decimal(BigDecimal("399"))),
            sample("2026-01-01T00:00:02Z", "b", QualityValue.Decimal(BigDecimal("401.0"))),
        ))))
        assertEquals(true, evaluate(source, facts).boolean)
        val tied = bindings("evidence" to obj("memory" to obj("samples" to arr(
            sample("2026-01-01T00:00:01Z", "a", QualityValue.Decimal(BigDecimal("402"))),
            sample("2026-01-01T00:00:01Z", "c", QualityValue.Decimal(BigDecimal("399"))),
            sample("2026-01-01T00:00:01Z", "b", QualityValue.Decimal(BigDecimal("401"))),
        ))))
        assertEquals(true, evaluate(source, tied).boolean)
        val offset = bindings("evidence" to obj("memory" to obj("samples" to arr(
            sample("2026-01-01T00:00:00+02:00", "a", QualityValue.Decimal(BigDecimal("402"))),
            sample("2025-12-31T23:00:00Z", "c", QualityValue.Decimal(BigDecimal("399"))),
            sample("2025-12-31T22:30:00Z", "b", QualityValue.Decimal(BigDecimal("401"))),
        ))))
        assertEquals(true, evaluate(source, offset).boolean)
        val bad = bindings("evidence" to obj("memory" to obj("samples" to arr(
            sample("2026-01-01T00:00:01Z", "a", QualityValue.Decimal(BigDecimal("402"))),
            sample("2026-01-01T00:00:02Z", "b", QualityValue.Decimal(BigDecimal("401"))),
            sample("2026-01-01T00:00:03Z", "c", QualityValue.Null),
        ))))
        assertEquals("RULE_FACT_NULL", evaluate(source, bad).errorCode)
        assertEquals(false, evaluate(source, bindings("evidence" to obj("memory" to obj("samples" to arr())))).boolean)
        assertEquals("RULE_FACT_MISSING", evaluate(source, bindings()).errorCode)
        assertEquals("RULE_FACT_NULL", evaluate(source, bindings("evidence" to obj("memory" to obj("samples" to QualityValue.Null)))).errorCode)
        assertEquals("RULE_FACT_TYPE", evaluate(source, bindings("evidence" to obj("memory" to obj("samples" to text("wrong"))))).errorCode)
        assertEquals("RULE_FACT_TYPE", evaluate(source.replace("400.0", "400"), facts).errorCode)
    }

    @Test fun `step budget is enforced across collection scans`() {
        val facts = bindings("issues" to arr(*Array(100) { obj("required" to QualityValue.Bool(false)) }))
        val ast = expr("{op: any, path: issues[], where: {op: eq, path: item.required, value: true}}")
        assertEquals("RULE_STEPS_LIMIT", RuleEvaluator(maxSteps = 10).evaluateExpression(ast, facts).errorCode)
    }

    @Test fun `bindings retain catalog identity when caller maps change`() {
        val mutableDefinitions = definitions.toMutableMap()
        val mutableLocals = locals.mapValues { it.value.toMutableMap() }.toMutableMap()
        val facts = FactBindings(obj("issues" to arr(obj("required" to QualityValue.Bool(true)))),
            mutableDefinitions, mutableLocals)
        mutableDefinitions.clear()
        mutableLocals.clear()
        assertEquals(true, evaluate("{op: any, path: issues[], where: {op: eq, path: item.required, value: true}}", facts).boolean)
    }

    @Test fun `static catalog validation cannot be hidden by empty collections or null facts`() {
        assertEquals("RULE_PATH_UNKNOWN", evaluate(
            "{op: any, path: testResults[], where: {op: eq, path: item.required, value: true}}",
            bindings("testResults" to arr())).errorCode)
        assertEquals("RULE_FACT_TYPE", evaluate(
            "{op: eq, path: release.releaseId, value: true}",
            bindings("release" to obj("releaseId" to QualityValue.Null))).errorCode)
        assertEquals("RULE_FACT_TYPE", evaluate(
            "{op: any, path: testResults[], where: {op: in, path: item.status, values: [RUNNING]}}",
            bindings("testResults" to arr())).errorCode)
        val invalidCondition = RuleAst.Rule("VALID_RULE", BigInteger.ONE,
            expr("{op: exists, path: issues[]}"),
            expr("{op: eq, path: nowhere.value, value: true}"),
            RuleStatus.BLOCK, RuleStatus.PASS, "INVALID_CONDITION", emptyList())
        assertEquals(RuleStatus.ERROR, RuleEvaluator().evaluate(invalidCondition, bindings()).status)
    }

    @Test fun `nullable catalog paths preserve eq ne and in null semantics`() {
        val facts = bindings("traceability" to obj("gaps" to arr(
            obj("predecessorEdgeId" to QualityValue.Null))))
        val path = "item.predecessorEdgeId"
        assertEquals(true, evaluate("{op: any, path: traceability.gaps[], where: {op: eq, path: $path, value: null}}", facts).boolean)
        assertEquals(false, evaluate("{op: any, path: traceability.gaps[], where: {op: ne, path: $path, value: null}}", facts).boolean)
        assertEquals(true, evaluate("{op: any, path: traceability.gaps[], where: {op: in, path: $path, values: [null]}}", facts).boolean)
    }

    @Test fun `collections and objects cannot be scalar comparison operands even with null literal`() {
        for (op in listOf("eq", "ne")) {
            for (value in listOf(arr(), QualityValue.Null, arr(obj()))) {
                assertEquals("RULE_FACT_TYPE", evaluate("{op: $op, path: issues[], value: null}",
                    bindings("issues" to value)).errorCode, op)
            }
            assertEquals("RULE_FACT_TYPE", evaluate("{op: $op, path: issues[], value: true}",
                bindings("issues" to arr())).errorCode, op)
        }
    }

    @Test fun `non Boolean predicates are rejected even when their collection is empty`() {
        val countPredicate = "{op: count, path: issues[]}"
        val empty = bindings("issues" to arr(), "evidence" to obj("memory" to obj("samples" to arr())))
        val nonempty = bindings("issues" to arr(obj()), "evidence" to obj("memory" to obj("samples" to arr(
            obj("capturedAt" to text("2026-01-01T00:00:00Z"), "evidenceId" to text("e1"),
                "pssMiB" to QualityValue.Decimal(BigDecimal("401.0")))))))
        for (facts in listOf(empty, nonempty)) {
            for (op in listOf("all", "any", "exists", "count")) {
                val source = "{op: $op, path: issues[], where: $countPredicate}"
                assertEquals("RULE_RESULT_TYPE", evaluate(source, facts).errorCode, op)
            }
            val consecutive = "{op: consecutive, path: evidence.memory.samples[], count: 1, where: $countPredicate}"
            assertEquals("RULE_RESULT_TYPE", evaluate(consecutive, facts).errorCode)
            assertEquals("RULE_RESULT_TYPE", evaluate(
                "{op: not, operand: $countPredicate}", facts).errorCode)
            assertEquals("RULE_RESULT_TYPE", evaluate(
                "{op: and, operands: [$countPredicate, {op: exists, path: issues[]}]}", facts).errorCode)
        }
        val invalidCondition = RuleAst.Rule("COUNT_AS_BOOL", BigInteger.ONE,
            expr("{op: exists, path: evidence.anrs[]}"), expr(countPredicate),
            RuleStatus.BLOCK, RuleStatus.PASS, "COUNT_AS_BOOL", emptyList())
        assertEquals("RULE_RESULT_TYPE", RuleEvaluator().evaluate(invalidCondition, empty).errorCode)
        val incompatibleComparison = RuleAst.Rule("BAD_COMPARISON", BigInteger.ONE,
            expr("{op: exists, path: evidence.anrs[]}"),
            expr("{op: eq, left: {op: count, path: issues[]}, right: 0.0}"),
            RuleStatus.BLOCK, RuleStatus.PASS, "BAD_COMPARISON", emptyList())
        assertEquals("RULE_FACT_TYPE", RuleEvaluator().evaluate(incompatibleComparison, empty).errorCode)
    }
}
