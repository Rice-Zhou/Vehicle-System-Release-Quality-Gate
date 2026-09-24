package com.ricezhou.vsrqg.quality.domain

enum class FactType { STRING, BOOLEAN, INTEGER, DECIMAL, OBJECT, TIMESTAMP }
private enum class ExprType { STRING, BOOLEAN, INTEGER, DECIMAL, OBJECT, COLLECTION, NULL }

data class FactDefinition(
    val type: FactType,
    val nullable: Boolean = false,
    val enumValues: Set<String> = emptySet(),
)

class FactBindings(
    val facts: QualityValue.ObjectValue,
    definitions: Map<String, FactDefinition>,
    itemBindings: Map<String, Map<String, String>>,
    evidenceRefs: List<String> = emptyList(),
) {
    private val definitions = definitions.mapValues { (_, definition) ->
        definition.copy(enumValues = definition.enumValues.toSet())
    }.toMap()
    private val itemBindings = itemBindings.mapValues { (_, bindings) -> bindings.toMap() }.toMap()
    val evidenceRefs = evidenceRefs.distinct().toList()
    data class Resolved(val value: QualityValue?, val missing: Boolean, val definition: FactDefinition)

    fun validateAst(ast: RuleAst) {
        var nodes = 0
        fun definition(path: String, collection: String?): FactDefinition {
            val canonical = if (path.startsWith("item.")) {
                if (collection == null) throw QualityFailure("RULE_ITEM_SCOPE")
                itemBindings[collection]?.get(path) ?: throw QualityFailure("RULE_PATH_UNKNOWN")
            } else path
            if (collection == null && path.contains("[]") && !path.endsWith("[]")) {
                throw QualityFailure("RULE_ITEM_SCOPE")
            }
            return definitions[canonical] ?: throw QualityFailure("RULE_PATH_UNKNOWN")
        }
        fun literal(path: String, collection: String?, value: QualityValue) {
            val declared = definition(path, collection)
            if (value is QualityValue.Null) return
            val correct = when (declared.type) {
                FactType.STRING -> value is QualityValue.Text &&
                    (declared.enumValues.isEmpty() || value.value in declared.enumValues)
                FactType.BOOLEAN -> value is QualityValue.Bool
                FactType.INTEGER -> value is QualityValue.Integer
                FactType.DECIMAL -> value is QualityValue.Decimal
                FactType.TIMESTAMP -> value is QualityValue.Text && runCatching {
                    java.time.Instant.parse(value.value)
                }.isSuccess
                FactType.OBJECT -> false
            }
            if (!correct) throw QualityFailure("RULE_FACT_TYPE")
        }
        fun pathType(path: String, collection: String?): ExprType {
            val declared = definition(path, collection)
            if (path.endsWith("[]")) return ExprType.COLLECTION
            return when (declared.type) {
                FactType.STRING, FactType.TIMESTAMP -> ExprType.STRING
                FactType.BOOLEAN -> ExprType.BOOLEAN
                FactType.INTEGER -> ExprType.INTEGER
                FactType.DECIMAL -> ExprType.DECIMAL
                FactType.OBJECT -> ExprType.OBJECT
            }
        }
        fun valueType(value: QualityValue): ExprType = when (value) {
            is QualityValue.Text -> ExprType.STRING
            is QualityValue.Bool -> ExprType.BOOLEAN
            is QualityValue.Integer -> ExprType.INTEGER
            is QualityValue.Decimal -> ExprType.DECIMAL
            is QualityValue.ObjectValue -> ExprType.OBJECT
            is QualityValue.ArrayValue -> ExprType.COLLECTION
            QualityValue.Null -> ExprType.NULL
        }
        fun requireBoolean(type: ExprType) {
            if (type != ExprType.BOOLEAN) throw QualityFailure("RULE_RESULT_TYPE")
        }
        fun visit(node: RuleAst, collection: String?, depth: Int): ExprType {
            if (++nodes > 4096) throw QualityFailure("RULE_NODES_LIMIT")
            if (depth > 32) throw QualityFailure("RULE_DEPTH_LIMIT")
            return when (node) {
                is RuleAst.Rule -> {
                    node.appliesWhen?.let { requireBoolean(visit(it, null, depth + 1)) }
                    requireBoolean(visit(node.condition, null, depth + 1))
                    ExprType.BOOLEAN
                }
                is RuleAst.Path -> pathType(node.path, collection)
                is RuleAst.Literal -> valueType(node.value)
                is RuleAst.Logical -> {
                    node.operands.forEach { requireBoolean(visit(it, collection, depth + 1)) }
                    ExprType.BOOLEAN
                }
                is RuleAst.Not -> {
                    requireBoolean(visit(node.operand, collection, depth + 1))
                    ExprType.BOOLEAN
                }
                is RuleAst.Comparison -> {
                    val leftType = visit(node.left, collection, depth + 1)
                    val rightType = visit(node.right, collection, depth + 1)
                    val path = (node.left as? RuleAst.Path)?.path ?: (node.right as? RuleAst.Path)?.path
                    val value = (node.left as? RuleAst.Literal)?.value ?: (node.right as? RuleAst.Literal)?.value
                    if (path != null && value != null) literal(path, collection, value)
                    if (leftType in setOf(ExprType.OBJECT, ExprType.COLLECTION) ||
                        rightType in setOf(ExprType.OBJECT, ExprType.COLLECTION) ||
                        (leftType != rightType && leftType != ExprType.NULL && rightType != ExprType.NULL)) {
                        throw QualityFailure("RULE_FACT_TYPE")
                    }
                    if (node.op in setOf("gt", "gte", "lt", "lte") &&
                        (leftType != rightType || leftType !in setOf(ExprType.INTEGER, ExprType.DECIMAL))) {
                        throw QualityFailure("RULE_FACT_TYPE")
                    }
                    ExprType.BOOLEAN
                }
                is RuleAst.Membership -> {
                    if (pathType(node.path, collection) in setOf(ExprType.OBJECT, ExprType.COLLECTION)) {
                        throw QualityFailure("RULE_FACT_TYPE")
                    }
                    if (node.values.size > 4096 - nodes) throw QualityFailure("RULE_NODES_LIMIT")
                    nodes += node.values.size
                    node.values.forEach { literal(node.path, collection, it) }
                    ExprType.BOOLEAN
                }
                is RuleAst.Collection -> {
                    definition(node.path, collection)
                    if (node.op != "exists" || node.where != null) {
                        if (!node.path.endsWith("[]")) throw QualityFailure("RULE_FACT_TYPE")
                    }
                    node.where?.let { requireBoolean(visit(it, node.path, depth + 1)) }
                    if (node.op == "count") ExprType.INTEGER else ExprType.BOOLEAN
                }
                is RuleAst.Consecutive -> {
                    definition(node.path, collection)
                    if (!node.path.endsWith("[]") ||
                        itemBindings[node.path]?.keys?.containsAll(listOf("item.capturedAt", "item.evidenceId")) != true) {
                        throw QualityFailure("RULE_FACT_TYPE")
                    }
                    requireBoolean(visit(node.where, node.path, depth + 1))
                    ExprType.BOOLEAN
                }
            }
        }
        visit(ast, null, 1)
    }

    fun resolve(path: String, collection: String? = null, item: QualityValue? = null, checkType: Boolean = true): Resolved {
        val canonical = if (path.startsWith("item.")) {
            if (collection == null || item == null) throw QualityFailure("RULE_ITEM_SCOPE")
            itemBindings[collection]?.get(path) ?: throw QualityFailure("RULE_PATH_UNKNOWN")
        } else path
        val definition = definitions[canonical] ?: throw QualityFailure("RULE_PATH_UNKNOWN")
        if (collection == null && path.contains("[]") && !path.endsWith("[]")) {
            throw QualityFailure("RULE_ITEM_SCOPE")
        }
        var current: QualityValue? = if (path.startsWith("item.")) item else facts
        for (part in path.split('.').drop(if (path.startsWith("item.")) 1 else 0)) {
            val key = part.removeSuffix("[]")
            val objectValue = current as? QualityValue.ObjectValue
                ?: throw QualityFailure("RULE_FACT_TYPE")
            current = objectValue.values[key] ?: return Resolved(null, true, definition)
        }
        if (current == null) return Resolved(null, true, definition)
        if (checkType) validate(current, definition, path.endsWith("[]"))
        return Resolved(current, false, definition)
    }

    private fun validate(value: QualityValue, definition: FactDefinition, collection: Boolean) {
        if (value is QualityValue.Null) {
            if (!definition.nullable) throw QualityFailure("RULE_FACT_NULL")
            return
        }
        if (collection) {
            if (value !is QualityValue.ArrayValue) throw QualityFailure("RULE_FACT_TYPE")
            value.values.forEach {
                if (it is QualityValue.Null && !definition.nullable) throw QualityFailure("RULE_FACT_NULL")
                validate(it, definition, false)
            }
            return
        }
        val correct = when (definition.type) {
            FactType.STRING -> value is QualityValue.Text && (definition.enumValues.isEmpty() || value.value in definition.enumValues)
            FactType.BOOLEAN -> value is QualityValue.Bool
            FactType.INTEGER -> value is QualityValue.Integer
            FactType.DECIMAL -> value is QualityValue.Decimal
            FactType.OBJECT -> value is QualityValue.ObjectValue
            FactType.TIMESTAMP -> value is QualityValue.Text && runCatching {
                java.time.Instant.parse(value.value)
            }.isSuccess
        }
        if (!correct) throw QualityFailure("RULE_FACT_TYPE")
    }
}
