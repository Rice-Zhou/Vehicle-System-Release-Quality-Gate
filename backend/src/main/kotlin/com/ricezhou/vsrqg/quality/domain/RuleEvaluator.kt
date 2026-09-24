package com.ricezhou.vsrqg.quality.domain

import java.math.BigInteger
import java.time.Instant

class RuleEvaluator(private val maxSteps: Int = 100_000) {
    init { require(maxSteps > 0) }

    fun evaluate(ast: RuleAst, facts: FactBindings): RuleOutcome {
        return evaluate(ast, facts, Budget(maxSteps))
    }

    fun evaluateAll(rules: List<RuleAst.Rule>, facts: FactBindings): List<RuleOutcome> {
        if (rules.size > 32) throw QualityFailure("RULE_SET_LIMIT")
        val budget = Budget(maxSteps)
        return rules.map { evaluate(it, facts, budget) }
    }

    private fun evaluate(ast: RuleAst, facts: FactBindings, budget: Budget): RuleOutcome {
        val rule = ast as? RuleAst.Rule ?: throw QualityFailure("RULE_AST_INVALID")
        val execution = Execution(facts, budget)
        return try {
            facts.validateAst(rule)
            if (rule.appliesWhen != null && !execution.boolean(rule.appliesWhen)) {
                RuleOutcome(RuleStatus.NOT_APPLICABLE, execution.matchedFacts(), emptyList(), rule.explanationCode, emptyMap())
            } else {
                val matched = execution.boolean(rule.condition)
                val factsUsed = execution.matchedFacts()
                val parameters = linkedMapOf<String, QualityValue>(
                    "ruleId" to QualityValue.Text(rule.ruleId),
                    "ruleVersion" to QualityValue.Integer(rule.version),
                    "matched" to QualityValue.Bool(matched),
                )
                factsUsed.groupBy { it.path.substringAfterLast('.') }
                    .filterValues { it.size == 1 }
                    .forEach { (name, values) -> parameters.putIfAbsent(name, values.single().value) }
                RuleOutcome(
                    if (matched) rule.onMatch else rule.onNoMatch,
                    factsUsed,
                    facts.evidenceRefs.distinct(),
                    rule.explanationCode,
                    parameters,
                )
            }
        } catch (failure: QualityFailure) {
            RuleOutcome(RuleStatus.ERROR, execution.matchedFacts(), emptyList(), rule.explanationCode, emptyMap(), failure.code)
        }
    }

    fun evaluateExpression(ast: RuleAst, facts: FactBindings): ExpressionResult {
        val execution = Execution(facts, Budget(maxSteps))
        return try {
            facts.validateAst(ast)
            when (val value = execution.value(ast)) {
                is QualityValue.Bool -> ExpressionResult(boolean = value.value)
                is QualityValue.Integer -> ExpressionResult(number = value.value)
                else -> ExpressionResult(errorCode = "RULE_RESULT_TYPE")
            }
        } catch (failure: QualityFailure) {
            ExpressionResult(errorCode = failure.code)
        }
    }

    private class Budget(private val limit: Int) {
        private var steps = 0
        fun consume() {
            if (steps >= limit) throw QualityFailure("RULE_STEPS_LIMIT")
            steps++
        }
        fun consumeCollection(size: Int) {
            if (size > limit - steps) throw QualityFailure("RULE_STEPS_LIMIT")
            steps += size
        }
    }

    private class Execution(private val facts: FactBindings, private val budget: Budget) {
        private val usedFacts = linkedMapOf<String, QualityValue>()
        private data class Item(val collection: String, val value: QualityValue, val index: Int)

        fun matchedFacts(): List<MatchedFact> = usedFacts.map { (path, value) -> MatchedFact(path, value) }
        private fun record(path: String, value: QualityValue?) {
            if (value != null && value !is QualityValue.ArrayValue && value !is QualityValue.ObjectValue) {
                usedFacts.putIfAbsent(path, value)
            }
        }
        fun boolean(ast: RuleAst, item: Item? = null): Boolean =
            (value(ast, item) as? QualityValue.Bool)?.value ?: throw QualityFailure("RULE_RESULT_TYPE")

        fun value(ast: RuleAst, item: Item? = null): QualityValue {
            step()
            return when (ast) {
                is RuleAst.Rule -> throw QualityFailure("RULE_AST_INVALID")
                is RuleAst.Literal -> ast.value
                is RuleAst.Path -> fact(ast.path, item)
                is RuleAst.Not -> QualityValue.Bool(!boolean(ast.operand, item))
                is RuleAst.Logical -> {
                    // Evaluate every operand: an earlier true/false cannot hide a later error.
                    var firstError: QualityFailure? = null
                    val results = ast.operands.map {
                        try { boolean(it, item) } catch (failure: QualityFailure) {
                            if (firstError == null) firstError = failure
                            false
                        }
                    }
                    firstError?.let { throw it }
                    QualityValue.Bool(if (ast.op == "and") results.all { it } else results.any { it })
                }
                is RuleAst.Comparison -> QualityValue.Bool(compare(ast.op, value(ast.left, item), value(ast.right, item)))
                is RuleAst.Membership -> {
                    val left = fact(ast.path, item)
                    ast.values.forEach { comparable(left, it) }
                    QualityValue.Bool(ast.values.any { equal(left, it) })
                }
                is RuleAst.Collection -> collection(ast, item)
                is RuleAst.Consecutive -> consecutive(ast, item)
            }
        }

        private fun fact(path: String, item: Item?, checkType: Boolean = true): QualityValue {
            val resolved = facts.resolve(path, item?.collection, item?.value, checkType)
            if (resolved.missing) throw QualityFailure("RULE_FACT_MISSING")
            record(if (item == null) path else path.replaceFirst("item", "${item.collection.removeSuffix("[]")}[${item.index}]"), resolved.value)
            return resolved.value!!
        }

        private fun collection(ast: RuleAst.Collection, item: Item?): QualityValue {
            val resolved = facts.resolve(ast.path, item?.collection, item?.value,
                checkType = false)
            record(ast.path, resolved.value)
            if (ast.op == "exists" && ast.where == null) return QualityValue.Bool(!resolved.missing)
            if (resolved.missing) {
                if (ast.op == "exists") return QualityValue.Bool(false)
                throw QualityFailure("RULE_FACT_MISSING")
            }
            val collection = resolved.value as? QualityValue.ArrayValue
                ?: throw QualityFailure(if (resolved.value is QualityValue.Null) "RULE_FACT_NULL" else "RULE_FACT_TYPE")
            // Charge one step per item before type checks or predicate evaluation.
            budget.consumeCollection(collection.values.size)
            facts.resolve(ast.path, item?.collection, item?.value)
            val where = ast.where
            if (where == null) {
                if (ast.op != "count") throw QualityFailure("RULE_AST_INVALID")
                return QualityValue.Integer(BigInteger.valueOf(collection.values.size.toLong()))
            }
            var trueCount = 0
            for ((index, element) in collection.values.withIndex()) {
                if (boolean(where, Item(ast.path, element, index))) trueCount++
            }
            return when (ast.op) {
                "exists", "any" -> QualityValue.Bool(trueCount > 0)
                "all" -> QualityValue.Bool(trueCount == collection.values.size)
                "count" -> QualityValue.Integer(BigInteger.valueOf(trueCount.toLong()))
                else -> throw QualityFailure("RULE_AST_INVALID")
            }
        }

        private fun consecutive(ast: RuleAst.Consecutive, item: Item?): QualityValue {
            val resolved = facts.resolve(ast.path, item?.collection, item?.value, checkType = false)
            if (resolved.missing) throw QualityFailure("RULE_FACT_MISSING")
            val collection = resolved.value as? QualityValue.ArrayValue
                ?: throw QualityFailure(if (resolved.value is QualityValue.Null) "RULE_FACT_NULL" else "RULE_FACT_TYPE")
            budget.consumeCollection(collection.values.size)
            facts.resolve(ast.path, item?.collection, item?.value)
            record(ast.path, resolved.value)
            val ordered = collection.values.withIndex().map { indexed ->
                val frame = Item(ast.path, indexed.value, indexed.index)
                val capturedAt = fact("item.capturedAt", frame) as? QualityValue.Text
                    ?: throw QualityFailure("RULE_FACT_TYPE")
                val evidenceId = fact("item.evidenceId", frame) as? QualityValue.Text
                    ?: throw QualityFailure("RULE_FACT_TYPE")
                Triple(Instant.parse(capturedAt.value), evidenceId.value, frame)
            }.sortedWith(compareBy<Triple<Instant, String, Item>>({ it.first }, { it.second }, { it.third.index }))
            var run = 0
            var found = false
            for ((_, _, frame) in ordered) {
                run = if (boolean(ast.where, frame)) run + 1 else 0
                if (run >= ast.count) found = true
            }
            return QualityValue.Bool(found)
        }

        private fun compare(op: String, left: QualityValue, right: QualityValue): Boolean {
            if (op == "eq" || op == "ne") {
                val same = equal(left, right)
                return if (op == "eq") same else !same
            }
            if (left is QualityValue.Null || right is QualityValue.Null) throw QualityFailure("RULE_FACT_NULL")
            comparable(left, right)
            if (left is QualityValue.Text && left.value.isEmpty() ||
                right is QualityValue.Text && right.value.isEmpty()) throw QualityFailure("RULE_FACT_EMPTY")
            val order = when (left) {
                is QualityValue.Integer -> left.value.compareTo((right as QualityValue.Integer).value)
                is QualityValue.Decimal -> left.value.compareTo((right as QualityValue.Decimal).value)
                else -> throw QualityFailure("RULE_FACT_TYPE")
            }
            return when (op) {
                "gt" -> order > 0
                "gte" -> order >= 0
                "lt" -> order < 0
                "lte" -> order <= 0
                else -> throw QualityFailure("RULE_AST_INVALID")
            }
        }

        private fun equal(left: QualityValue, right: QualityValue): Boolean {
            if (left is QualityValue.Null || right is QualityValue.Null) return left is QualityValue.Null && right is QualityValue.Null
            comparable(left, right)
            return when (left) {
                is QualityValue.Decimal -> left.value.compareTo((right as QualityValue.Decimal).value) == 0
                else -> left == right
            }
        }

        private fun comparable(left: QualityValue, right: QualityValue) {
            if (left is QualityValue.Null || right is QualityValue.Null) return
            if (left::class != right::class ||
                left is QualityValue.ArrayValue || left is QualityValue.ObjectValue) {
                throw QualityFailure("RULE_FACT_TYPE")
            }
        }

        private fun step() {
            budget.consume()
        }
    }
}
