package com.ricezhou.vsrqg.quality.domain

import java.math.BigInteger

sealed interface RuleAst {
    data class Rule(
        val ruleId: String,
        val version: BigInteger,
        val appliesWhen: RuleAst?,
        val condition: RuleAst,
        val onMatch: RuleStatus,
        val onNoMatch: RuleStatus,
        val explanationCode: String,
        val evidenceRequirements: List<String>,
    ) : RuleAst
    data class Logical(val op: String, val operands: List<RuleAst>) : RuleAst
    data class Not(val operand: RuleAst) : RuleAst
    data class Comparison(val op: String, val left: RuleAst, val right: RuleAst) : RuleAst
    data class Membership(val path: String, val values: List<QualityValue>) : RuleAst
    data class Collection(val op: String, val path: String, val where: RuleAst?) : RuleAst
    data class Consecutive(val path: String, val count: Int, val where: RuleAst) : RuleAst
    data class Path(val path: String) : RuleAst
    data class Literal(val value: QualityValue) : RuleAst

    companion object {
        private val CODE = Regex("[A-Z][A-Z0-9_]{2,63}")
        private val EVIDENCE_TYPES = setOf("LOG", "SCREENSHOT", "CRASH", "ANR", "TOMBSTONE", "PERFETTO", "MEMORY", "METRIC")

        fun parse(value: QualityValue): Rule {
            guard(value)
            val source = value.fields()
            source.only("schemaVersion", "ruleId", "version", "title", "scope", "appliesWhen",
                "condition", "onMatch", "onNoMatch", "explanation", "evidenceRequirements")
            if (source.string("schemaVersion") != "1.0" || source.string("scope") != "RELEASE") fail()
            val version = source.bigInteger("version")
            if (version <= BigInteger.ZERO) fail()
            val explanation = source.required("explanation").fields()
            explanation.only("code", "template")
            val code = explanation.string("code")
            val template = explanation.string("template")
            val title = source.string("title")
            val ruleId = source.string("ruleId")
            if (!CODE.matches(ruleId) || !CODE.matches(code) ||
                title.length !in 1..200 || template.length !in 1..500) fail()
            val requirements = source["evidenceRequirements"]?.array()?.map { it.string() } ?: emptyList()
            if (requirements.distinct().size != requirements.size || requirements.any { it !in EVIDENCE_TYPES }) fail()
            return Rule(
                ruleId, version, source["appliesWhen"]?.let(::parseNode),
                parseNode(source.required("condition")),
                source.status("onMatch"), source.status("onNoMatch"),
                code, requirements,
            )
        }

        fun parseExpression(value: QualityValue): RuleAst {
            guard(value)
            return parseNode(value)
        }

        private fun parseNode(value: QualityValue): RuleAst {
            val source = value.fields()
            return when (val op = source.string("op")) {
                "and", "or" -> {
                    source.only("op", "operands")
                    val operands = source.required("operands").array().map(::parseNode)
                    if (operands.size < 2) fail()
                    Logical(op, operands)
                }
                "not" -> {
                    source.only("op", "operand")
                    Not(parseNode(source.required("operand")))
                }
                "eq", "ne", "gt", "gte", "lt", "lte" -> {
                    if ("path" in source) {
                        source.only("op", "path", "value")
                        Comparison(op, Path(source.string("path")), Literal(source.required("value").scalar()))
                    } else {
                        source.only("op", "left", "right")
                        Comparison(op, operand(source.required("left")), operand(source.required("right")))
                    }
                }
                "in" -> {
                    source.only("op", "path", "values")
                    Membership(source.string("path"), source.required("values").array().map { it.scalar() })
                }
                "exists", "count", "all", "any" -> {
                    source.only("op", "path", "where")
                    val where = source["where"]?.let(::parseNode)
                    if ((op == "all" || op == "any") && where == null) fail()
                    Collection(op, source.string("path"), where)
                }
                "consecutive" -> {
                    source.only("op", "path", "count", "where")
                    val count = source.integer("count")
                    if (count !in 1..1000) fail()
                    Consecutive(source.string("path"), count, parseNode(source.required("where")))
                }
                else -> fail()
            }
        }

        private fun operand(value: QualityValue): RuleAst =
            if (value is QualityValue.ObjectValue) parseNode(value) else Literal(value.scalar())
        private fun guard(root: QualityValue) {
            val stack = ArrayDeque<Pair<QualityValue, Int>>()
            stack.addLast(root to 1)
            var nodes = 0
            while (stack.isNotEmpty()) {
                val (value, depth) = stack.removeLast()
                if (depth > 32) throw QualityFailure("RULE_DEPTH_LIMIT")
                if (++nodes > 4096) throw QualityFailure("RULE_NODES_LIMIT")
                when (value) {
                    is QualityValue.ObjectValue -> {
                        if (value.values.isNotEmpty() && depth + 1 > 32) throw QualityFailure("RULE_DEPTH_LIMIT")
                        nodes += value.values.size
                        if (nodes > 4096) throw QualityFailure("RULE_NODES_LIMIT")
                        value.values.values.forEach { stack.addLast(it to depth + 1) }
                    }
                    is QualityValue.ArrayValue -> value.values.forEach { stack.addLast(it to depth + 1) }
                    else -> Unit
                }
            }
        }
        private fun QualityValue.scalar(): QualityValue {
            if (this is QualityValue.ObjectValue || this is QualityValue.ArrayValue) fail()
            return this
        }
        private fun QualityValue.fields(): Map<String, QualityValue> =
            (this as? QualityValue.ObjectValue)?.values ?: fail()
        private fun QualityValue.array(): List<QualityValue> =
            (this as? QualityValue.ArrayValue)?.values ?: fail()
        private fun QualityValue.string(): String = (this as? QualityValue.Text)?.value ?: fail()
        private fun Map<String, QualityValue>.required(key: String): QualityValue = this[key] ?: fail()
        private fun Map<String, QualityValue>.string(key: String): String = required(key).string()
        private fun Map<String, QualityValue>.integer(key: String): Int = try {
            (required(key) as? QualityValue.Integer)?.value?.intValueExact() ?: fail()
        } catch (_: ArithmeticException) { fail() }
        private fun Map<String, QualityValue>.bigInteger(key: String): BigInteger =
            (required(key) as? QualityValue.Integer)?.value ?: fail()
        private fun Map<String, QualityValue>.status(key: String): RuleStatus =
            when (string(key)) {
                "PASS" -> RuleStatus.PASS
                "WARNING" -> RuleStatus.WARNING
                "BLOCK" -> RuleStatus.BLOCK
                else -> fail()
            }
        private fun Map<String, QualityValue>.only(vararg keys: String) {
            if (this.keys.any { it !in keys }) fail()
        }
        private fun fail(): Nothing = throw QualityFailure("RULE_AST_INVALID")
    }
}

enum class RuleStatus { PASS, WARNING, BLOCK, ERROR, NOT_APPLICABLE }

data class RuleOutcome(
    val status: RuleStatus,
    val matchedFacts: List<MatchedFact>,
    val evidenceRefs: List<String>,
    val explanationCode: String,
    val parameters: Map<String, QualityValue>,
    val errorCode: String? = null,
)

data class MatchedFact(val path: String, val value: QualityValue)

data class ExpressionResult(
    val boolean: Boolean? = null,
    val number: java.math.BigInteger? = null,
    val errorCode: String? = null,
)
