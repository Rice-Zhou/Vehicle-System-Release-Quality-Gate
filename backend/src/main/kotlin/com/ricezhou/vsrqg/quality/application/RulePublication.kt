package com.ricezhou.vsrqg.quality.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.quality.domain.*
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class QualityRuleSetRecord(
    val id: String,
    val projectId: String,
    val ruleSetId: String,
    val version: Long,
    val rowVersion: Long,
    val state: String,
    val definition: JsonNode,
    val contentDigest: String,
    val authorId: String,
    val reviewerId: String?,
    val createdAt: Instant,
)

data class QualityRuleVersionRecord(
    val id: String,
    val setVersionId: String,
    val ordinal: Int,
    val ruleId: String,
    val version: Long,
    val sourceYaml: String?,
    val validatedAst: JsonNode,
    val sourcePath: String?,
    val sourceCommit: String?,
    val sourceDigest: String?,
    val goldenFixture: String?,
    val goldenDigest: String?,
)

interface QualityRepository {
    fun insert(set: QualityRuleSetRecord, rules: List<QualityRuleVersionRecord>)
    fun findByRuleSetId(ruleSetId: String): QualityRuleSetRecord?
    fun findPublished(ruleSetId: String, version: Long): QualityRuleSetRecord?
    fun lockByRuleSetId(ruleSetId: String): QualityRuleSetRecord?
    fun rules(setVersionId: String): List<QualityRuleVersionRecord>
    fun publish(setVersionId: String, expectedVersion: Long, reviewerId: String, reason: String, at: Instant): Boolean
}

fun interface RuleYamlParser {
    fun parse(bytes: ByteArray): QualityValue
}

class RulePublicationInvalid(val code: String) : RuntimeException(code)

@Service
class RulePublication(
    private val authorizer: ProjectAuthorizer,
    private val idempotent: IdempotentExecutor,
    private val repository: QualityRepository,
    private val governance: GovernanceStore,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
    private val mapper: ObjectMapper,
    private val yamlParser: RuleYamlParser,
) {
    private val demo = DemoRuleGate(mapper, yamlParser)

    @Transactional
    fun create(projectId: String, body: JsonNode, idempotencyKey: String,
               principal: Principal, requestId: String): JsonNode {
        val actorId = authorizer.require(principal, projectId, Permission.RULE_WRITE).principalId
        val definition = validateDefinition(body, projectId)
        val digest = try {
            digest(QualityCanonicalEncoder().encode(toValue(definition)))
        } catch (failure: QualityFailure) {
            invalid(failure.code)
        }
        val keyDigest = digest("$projectId\n$digest".toByteArray(StandardCharsets.UTF_8))
        return idempotent.execute("quality:rule-set:create", actorId, idempotencyKey,
            keyDigest, JsonNode::class.java) {
            val latest = repository.findByRuleSetId(definition["ruleSetId"].textValue())
            if (latest != null && (latest.state != "PUBLISHED" ||
                    latest.projectId != projectId || latest.version >= definition["version"].longValue())) {
                throw conflict("RULE_SET_VERSION_CONFLICT")
            }
            val set = QualityRuleSetRecord(
                ids.nextId("qrs_"), projectId, definition["ruleSetId"].textValue(),
                definition["version"].longValue(), 0, "DRAFT", definition.deepCopy(), digest,
                actorId, null, clock.now(),
            )
            val rules = definition["rules"].mapIndexed { index, node ->
                val value = toValue(node)
                val ast = validRule(value)
                val source = demo.source(value)
                QualityRuleVersionRecord(
                    ids.nextId("qrv_"), set.id, index, ast.ruleId, ast.version.longValueExact(),
                    source?.yaml, node.deepCopy(), source?.path,
                    source?.commit, source?.let { digest(it.yaml.toByteArray(StandardCharsets.UTF_8)) },
                    source?.goldenPath, source?.goldenDigest,
                )
            }
            if (rules.map { it.ruleId }.distinct().size != rules.size) invalid("RULE_SET_DUPLICATE_RULE")
            repository.insert(set, rules)
            val result = response(set)
            governance.appendAudit(projectId, actorId, "QUALITY_RULE_SET_CREATED", "QUALITY_RULE_SET",
                set.id, requestId, null, afterState = result)
            result
        }
    }

    @Transactional
    fun publish(projectId: String, id: String, version: Long, reason: String,
                idempotencyKey: String, principal: Principal, requestId: String): JsonNode {
        if (reason.isBlank() || reason.length > 1000) invalid("RULE_REVIEW_REASON_INVALID")
        val actorId = authorizer.require(principal, projectId, Permission.RULE_PUBLISH).principalId
        val first = repository.findByRuleSetId(id) ?: throw notFound()
        if (first.projectId != projectId) throw AccessDeniedException("Rule Set project mismatch")
        val keyDigest = digest("$projectId\n$id\n$version\n$reason".toByteArray(StandardCharsets.UTF_8))
        return idempotent.execute("quality:rule-set:publish", actorId, idempotencyKey,
            keyDigest, JsonNode::class.java) {
            val set = repository.lockByRuleSetId(id) ?: throw notFound()
            if (set.projectId != projectId) throw AccessDeniedException("Rule Set project changed")
            if (set.state != "DRAFT" || set.rowVersion != version) throw conflict("RULE_SET_VERSION_CONFLICT")
            if (set.authorId == actorId) throw AccessDeniedException("Rule Set author cannot review own rule")
            val rules = repository.rules(set.id)
            demo.requireGolden(set.definition, rules)
            if (!repository.publish(set.id, version, actorId, reason, clock.now())) {
                throw conflict("RULE_SET_VERSION_CONFLICT")
            }
            val result = response(set.copy(state = "PUBLISHED", rowVersion = version + 1, reviewerId = actorId))
            governance.appendAudit(projectId, actorId, "QUALITY_RULE_SET_PUBLISHED", "QUALITY_RULE_SET",
                set.id, requestId, reason, beforeState = response(set), afterState = result)
            result
        }
    }

    private fun validateDefinition(body: JsonNode, projectId: String): JsonNode {
        if (!body.isObject || body.properties().map { it.key }.toSet() !=
            setOf("ruleSetId", "version", "project", "catalogVersion", "engineVersion",
                "requiredIssueRefs", "selectedCaseRefs", "rules")) invalid("RULE_SET_REQUEST_INVALID")
        if (!body["ruleSetId"].isTextual || !ID.matches(body["ruleSetId"].textValue()) ||
            !body["project"].isTextual || body["project"].textValue() != projectId ||
            !body["version"].isIntegralNumber || !body["version"].canConvertToLong() ||
            body["version"].longValue() <= 0 || !body["catalogVersion"].isIntegralNumber ||
            body["catalogVersion"].intValue() != 2 ||
            !body["engineVersion"].isTextual || body["engineVersion"].textValue() != ENGINE_VERSION) {
            invalid("RULE_SET_REQUEST_INVALID")
        }
        val issues = body["requiredIssueRefs"]
        val cases = body["selectedCaseRefs"]
        val rules = body["rules"]
        if (!issues.isArray || issues.size() > 20 || !cases.isArray || cases.size() != 1 ||
            !rules.isArray || rules.size() !in 1..32) invalid("RULE_SET_REQUEST_INVALID")
        if (issues.any { !it.isObject || it.size() != 2 ||
                it["source"]?.isTextual != true || it["source"]?.textValue().isNullOrBlank() ||
                it["sourceIssueId"]?.isTextual != true || it["sourceIssueId"]?.textValue().isNullOrBlank() } ||
            issues.map(JsonNode::toString).distinct().size != issues.size()) invalid("RULE_SET_REQUEST_INVALID")
        val case = cases[0]
        if (!case.isObject || case.size() != 2 || case["caseId"]?.isTextual != true ||
            !ID.matches(case["caseId"].textValue()) || case["version"]?.isIntegralNumber != true ||
            !case["version"].canConvertToInt() || case["version"].intValue() <= 0) invalid("RULE_SET_REQUEST_INVALID")
        rules.forEach { validRule(toValue(it)) }
        return body.deepCopy()
    }

    private fun validRule(value: QualityValue): RuleAst.Rule = try {
        RuleAst.parse(value).also { it.version.longValueExact() }
    } catch (failure: QualityFailure) {
        invalid(failure.code)
    } catch (_: ArithmeticException) {
        invalid("RULE_VERSION_OUT_OF_RANGE")
    }

    private fun response(set: QualityRuleSetRecord): JsonNode = mapper.createObjectNode().also {
        it.set<JsonNode>("definition", set.definition.deepCopy())
        it.put("state", set.state)
        it.put("contentDigest", set.contentDigest)
    }

    private fun digest(bytes: ByteArray): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun toValue(node: JsonNode): QualityValue = when {
        node.isNull -> QualityValue.Null
        node.isBoolean -> QualityValue.Bool(node.booleanValue())
        node.isTextual -> QualityValue.Text(node.textValue())
        node.isIntegralNumber -> QualityValue.Integer(node.bigIntegerValue())
        node.isNumber -> QualityValue.Decimal(node.decimalValue())
        node.isArray -> QualityValue.ArrayValue(node.map(::toValue))
        node.isObject -> QualityValue.ObjectValue(node.properties().associate { it.key to toValue(it.value) })
        else -> invalid("RULE_SET_REQUEST_INVALID")
    }

    private fun invalid(code: String): Nothing = throw RulePublicationInvalid(code)
    private fun conflict(code: String) = ResourceConflict(code, "Rule Set conflict", code)
    private fun notFound() = ResourceNotFound("RULE_SET_NOT_FOUND", "Rule Set not found", "Rule Set not found")

    private companion object {
        const val ENGINE_VERSION = "VSRQG-QUALITY-ENGINE-1"
        val ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
    }
}

internal class DemoRuleGate(private val mapper: ObjectMapper, private val yamlParser: RuleYamlParser) {
    data class Source(
        val path: String,
        val commit: String,
        val yaml: String,
        val goldenPath: String,
        val goldenDigest: String,
    )

    private val files = listOf("smoke-case-outcome.yaml", "required-issue-verified.yaml")
    private val goldenFile = "contracts/quality-rule/golden-cases-v1.json"
    private val goldenBytes by lazy(::golden)
    private val goldenNode by lazy { mapper.readTree(goldenBytes) }
    private val catalog by lazy {
        val bytes = resource("contracts/quality-rule/fact-catalog-v2.json")
        if (digest(bytes) != CATALOG_DIGEST) invalid("RULE_SOURCE_MISMATCH")
        mapper.readTree(bytes)
    }

    fun source(value: QualityValue): Source? = files.firstNotNullOfOrNull { file ->
        val bytes = resource("contracts/quality-rule/$file")
        if (digest(bytes) != SOURCE_DIGESTS.getValue(file)) invalid("RULE_SOURCE_MISMATCH")
        val parsed = yamlParser.parse(bytes)
        if (parsed != value) null else Source(
            "contracts/examples/v0.2/quality-rule/$file", SOURCE_COMMIT,
            bytes.toString(StandardCharsets.UTF_8),
            "contracts/examples/v0.2/quality-rule/golden-cases-v1.json", digest(goldenBytes),
        )
    }

    fun requireGolden(definition: JsonNode, persisted: List<QualityRuleVersionRecord>) {
        if (persisted.size != files.size || definition["rules"].size() != files.size ||
            definition["engineVersion"].textValue() != ENGINE_VERSION ||
            definition["catalogVersion"].isIntegralNumber != true ||
            definition["catalogVersion"].intValue() != 2) invalid("RULE_GOLDEN_UNSUPPORTED")
        val definitions = catalog["facts"].associate {
            it["path"].textValue() to FactDefinition(FactType.valueOf(it["type"].textValue()),
                it["nullable"]?.asBoolean() ?: false,
                it["enumValues"]?.map(JsonNode::asText)?.toSet() ?: emptySet())
        }
        val locals = catalog["itemBindings"].groupBy({ it["collectionPath"].textValue() }) {
            it["localPath"].textValue() to it["factPath"].textValue()
        }.mapValues { (_, entries) -> entries.toMap() }
        val asts = persisted.mapIndexed { index, record ->
            val source = source(toValue(definition["rules"][index])) ?: invalid("RULE_GOLDEN_UNSUPPORTED")
            if (record.ordinal != index || record.sourcePath != source.path ||
                record.sourceCommit != source.commit || record.sourceYaml != source.yaml ||
                record.sourceDigest != digest(source.yaml.toByteArray(StandardCharsets.UTF_8)) ||
                record.goldenFixture != source.goldenPath || record.goldenDigest != source.goldenDigest ||
                record.validatedAst != definition["rules"][index]) invalid("RULE_GOLDEN_UNSUPPORTED")
            val ast = RuleAst.parse(yamlParser.parse(source.yaml.toByteArray(StandardCharsets.UTF_8)))
            FactBindings(QualityValue.ObjectValue(emptyMap()), definitions, locals).validateAst(ast)
            files[index] to ast
        }.toMap()
        if (goldenNode["version"]?.intValue() != 1 || goldenNode["cases"]?.isArray != true) {
            invalid("RULE_GOLDEN_INVALID")
        }
        val caseIds = goldenNode["cases"].map { it["id"].textValue() }.toSet()
        if (!caseIds.containsAll(REQUIRED_CASES)) invalid("RULE_GOLDEN_INCOMPLETE")
        goldenNode["cases"].forEach { test ->
            val ast = asts[test["rule"].textValue()] ?: invalid("RULE_GOLDEN_INVALID")
            val refs = test["evidenceRefs"]?.map(JsonNode::asText) ?: emptyList()
            val facts = FactBindings(toValue(test["facts"]) as QualityValue.ObjectValue,
                definitions, locals, refs)
            val outcome = RuleEvaluator().evaluate(ast, facts)
            if (outcome.status.name != test["status"].textValue() ||
                outcome.errorCode != test["errorCode"]?.textValue()) invalid("RULE_GOLDEN_FAILED")
        }
    }

    private fun toValue(node: JsonNode): QualityValue = when {
        node.isNull -> QualityValue.Null
        node.isBoolean -> QualityValue.Bool(node.booleanValue())
        node.isTextual -> QualityValue.Text(node.textValue())
        node.isIntegralNumber -> QualityValue.Integer(node.bigIntegerValue())
        node.isNumber -> QualityValue.Decimal(node.decimalValue())
        node.isArray -> QualityValue.ArrayValue(node.map(::toValue))
        node.isObject -> QualityValue.ObjectValue(node.properties().associate { it.key to toValue(it.value) })
        else -> invalid("RULE_GOLDEN_INVALID")
    }

    private fun golden() = resource(goldenFile).also {
        if (digest(it) != GOLDEN_DIGEST) invalid("RULE_SOURCE_MISMATCH")
    }
    private fun resource(path: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { "Missing versioned quality resource $path" }
            .use { it.readBytes() }
    private fun digest(bytes: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun invalid(code: String): Nothing = throw RulePublicationInvalid(code)

    private companion object {
        const val SOURCE_COMMIT = "750307ecb614f2fa37d9f494a24f6a232c37c273"
        const val ENGINE_VERSION = "VSRQG-QUALITY-ENGINE-1"
        const val GOLDEN_DIGEST = "sha256:eb219ef3040d5526a61c0cf2d4995db0d06bd12697a97b823748ee108ac636a6"
        const val CATALOG_DIGEST = "sha256:4ce9208d5d01e4aa42b673c7308c7d82fee257967641e2c699faa65adbfc9537"
        val SOURCE_DIGESTS = mapOf(
            "smoke-case-outcome.yaml" to "sha256:6432f1ebdc515e0c8e7bfa45714739358d9e2ffd1b97f1f16df1bc15ada307c3",
            "required-issue-verified.yaml" to "sha256:1722be9310f3d4c66caa9146b4641a868c7441830b41c7f12c001622253d756c",
        )
        val REQUIRED_CASES = setOf(
            "case-pass", "case-fail", "case-empty-pure-only", "case-missing", "case-null", "case-type-error",
            "required-unverified", "required-verified", "required-empty", "required-missing-verification",
            "required-null-verification", "required-type-error",
        )
    }
}
