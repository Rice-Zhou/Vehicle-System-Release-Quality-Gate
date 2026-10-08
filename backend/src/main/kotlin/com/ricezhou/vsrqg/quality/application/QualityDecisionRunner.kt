package com.ricezhou.vsrqg.quality.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.quality.domain.FactBindings
import com.ricezhou.vsrqg.quality.domain.FactDefinition
import com.ricezhou.vsrqg.quality.domain.FactType
import com.ricezhou.vsrqg.quality.domain.QualityAggregator
import com.ricezhou.vsrqg.quality.domain.QualityCanonicalEncoder
import com.ricezhou.vsrqg.quality.domain.QualityValue
import com.ricezhou.vsrqg.quality.domain.RuleAst
import com.ricezhou.vsrqg.quality.domain.RuleEvaluator
import com.ricezhou.vsrqg.quality.domain.RuleOutcome
import com.ricezhou.vsrqg.quality.domain.RuleStatus
import org.springframework.stereotype.Service

data class QualityDecision(
    val ruleResults: List<JsonNode>,
    val result: JsonNode?,
    val errorCode: String?,
)

@Service
class QualityDecisionRunner(private val mapper: ObjectMapper) {
    fun evaluate(snapshot: JsonNode, rules: List<QualityRuleVersionRecord>, resultId: String): QualityDecision {
        val versions = snapshot.path("versions")
        if (versions.path("engineVersion").asText() != ENGINE_VERSION ||
            versions.path("catalogVersion").asInt() != 2 ||
            versions.path("canonicalizationVersion").asText() != QualityCanonicalEncoder.VERSION) {
            throw QualityInputFailure("QUALITY_VERSION_UNSUPPORTED")
        }
        val toHash = snapshot.deepCopy<ObjectNode>()
        toHash.remove("snapshotId")
        toHash.remove("inputDigest")
        if (digest(QualityCanonicalEncoder().encode(toQualityValue(toHash))) !=
            snapshot.path("inputDigest").asText()) throw QualityInputFailure("QUALITY_INPUT_DIGEST_MISMATCH")
        if (rules.isEmpty() || rules.size > 32) throw QualityInputFailure("QUALITY_RULE_SET_INVALID")
        val catalog = catalog()
        val definitions = catalog.path("facts").associate {
            it.path("path").asText() to FactDefinition(
                FactType.valueOf(it.path("type").asText()),
                it.path("nullable").asBoolean(false),
                it.path("enumValues").map(JsonNode::asText).toSet(),
            )
        }
        val itemBindings = catalog.path("itemBindings").groupBy(
            { it.path("collectionPath").asText() },
            { it.path("localPath").asText() to it.path("factPath").asText() },
        ).mapValues { (_, values) -> values.toMap() }
        val refs = snapshot.path("evidenceRefs").map { it.path("evidenceId").asText() }
        val facts = FactBindings(toQualityValue(snapshot.path("facts")) as QualityValue.ObjectValue,
            definitions, itemBindings, refs)
        val asts = rules.sortedBy(QualityRuleVersionRecord::ordinal).map { record ->
            val ast = RuleAst.parse(toQualityValue(record.validatedAst))
            if (ast.ruleId != record.ruleId || ast.version.longValueExact() != record.version) {
                throw QualityInputFailure("QUALITY_RULE_VERSION_MISMATCH")
            }
            ast
        }
        val outcomes = RuleEvaluator().evaluateAll(asts, facts)
        val results = asts.zip(outcomes).map { (rule, outcome) -> result(rule, outcome) }
        val action = QualityAggregator.aggregate(outcomes.map(RuleOutcome::status))
        if (action == "ERROR") {
            val code = outcomes.firstOrNull { it.status == RuleStatus.ERROR }?.errorCode
                ?: "QUALITY_NO_APPLICABLE_RULE"
            return QualityDecision(results, null, code)
        }
        val digestMaterial = mapper.createObjectNode()
            .put("inputDigest", snapshot.path("inputDigest").asText()).put("action", action)
        digestMaterial.set<JsonNode>("versions", versions.deepCopy())
        digestMaterial.set<JsonNode>("ruleResults", mapper.valueToTree(results))
        val result = mapper.createObjectNode().put("resultId", resultId).put("action", action)
        result.set<JsonNode>("ruleResults", mapper.valueToTree(results))
        result.put("resultDigest", digest(QualityCanonicalEncoder().encode(toQualityValue(digestMaterial))))
        return QualityDecision(results, result, null)
    }

    private fun result(rule: RuleAst.Rule, outcome: RuleOutcome): JsonNode {
        val result = mapper.createObjectNode().put("ruleId", rule.ruleId)
            .put("version", rule.version.longValueExact()).put("status", outcome.status.name)
        val matched = result.putArray("matchedFacts")
        outcome.matchedFacts.forEach { fact ->
            matched.addObject().put("path", fact.path).set<JsonNode>("value", diagnostic(fact.value))
        }
        val refs = result.putArray("evidenceRefs")
        outcome.evidenceRefs.forEach(refs::add)
        val explanation = result.putObject("explanation")
            .put("code", outcome.errorCode ?: outcome.explanationCode)
        val parameters = explanation.putObject("parameters")
        outcome.parameters.forEach { (key, value) -> parameters.set<JsonNode>(key, diagnostic(value)) }
        return result
    }

    private fun diagnostic(value: QualityValue): JsonNode = when (value) {
        QualityValue.Null -> mapper.nullNode()
        is QualityValue.Bool -> mapper.nodeFactory.booleanNode(value.value)
        is QualityValue.Text -> mapper.nodeFactory.textNode(value.value)
        is QualityValue.Integer -> numeric("INTEGER", value.value.toString())
        is QualityValue.Decimal -> numeric("DECIMAL",
            if (value.value.signum() == 0) "0" else value.value.stripTrailingZeros().toPlainString())
        else -> throw QualityInputFailure("QUALITY_DIAGNOSTIC_TYPE_INVALID")
    }

    private fun numeric(type: String, value: String): ArrayNode =
        mapper.createArrayNode().add(type).add(value)

    private fun catalog(): JsonNode {
        val stream = javaClass.classLoader.getResourceAsStream("contracts/quality-rule/fact-catalog-v2.json")
            ?: throw QualityInputFailure("QUALITY_CATALOG_UNAVAILABLE")
        return stream.use(mapper::readTree)
    }

    private companion object {
        const val ENGINE_VERSION = "VSRQG-QUALITY-ENGINE-1"
    }
}