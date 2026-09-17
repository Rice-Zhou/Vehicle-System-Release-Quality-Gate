package com.ricezhou.vsrqg.quality.adapter

import com.ricezhou.vsrqg.quality.domain.QualityValue
import com.ricezhou.vsrqg.quality.domain.QualityFailure
import com.ricezhou.vsrqg.quality.domain.QualityScalarLimits
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.events.*
import org.yaml.snakeyaml.error.YAMLException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

class StrictRuleYaml {
    fun parse(bytes: ByteArray): QualityValue {
        if (bytes.size > 64 * 1024) fail("RULE_BYTES_LIMIT")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            fail("RULE_UTF8_INVALID")
        }
        val options = LoaderOptions().apply {
            codePointLimit = 64 * 1024
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 0
            nestingDepthLimit = 32
        }
        try {
            return readEvents(Yaml(options).parse(StringReader(text)))
        } catch (_: YAMLException) {
            fail("RULE_YAML_INVALID")
        }
    }

    private fun readEvents(events: Iterable<Event>): QualityValue {
        val stack = ArrayDeque<Frame>()
        var result: QualityValue? = null
        var documents = 0
        var nodes = 0
        fun accept(value: QualityValue) {
            val frame = stack.lastOrNull()
            if (frame == null) result = value else frame.accept(value)
        }
        for (event in events) {
            if (event is DocumentStartEvent && ++documents > 1) fail("RULE_DOCUMENT_COUNT")
            if (event is DocumentStartEvent && (event.version != null || !event.tags.isNullOrEmpty())) fail("RULE_YAML_FEATURE")
            if (event is AliasEvent) fail("RULE_YAML_FEATURE")
            if (event is NodeEvent && event.anchor != null) fail("RULE_YAML_FEATURE")
            if (event is ScalarEvent && event.tag != null || event is CollectionStartEvent && event.tag != null) {
                fail("RULE_YAML_FEATURE")
            }
            if (event is ScalarEvent || event is CollectionStartEvent) {
                if (++nodes > 4096) fail("RULE_NODES_LIMIT")
                if (stack.size + 1 > 32) fail("RULE_DEPTH_LIMIT")
                if (event is CollectionStartEvent && stack.lastOrNull()?.needsKey() == true) fail("RULE_KEY_TYPE")
            }
            when (event) {
                is ScalarEvent -> {
                    if (stack.lastOrNull()?.needsKey() == true && event.value == "<<") fail("RULE_YAML_FEATURE")
                    accept(scalar(event))
                }
                is MappingStartEvent -> stack.addLast(Frame(true))
                is SequenceStartEvent -> stack.addLast(Frame(false))
                is CollectionEndEvent -> accept(stack.removeLast().finish())
                else -> Unit
            }
        }
        if (documents != 1 || result == null) fail("RULE_DOCUMENT_COUNT")
        return result
    }

    private fun scalar(event: ScalarEvent): QualityValue {
        val text = event.value
        QualityScalarLimits.unicode(text)
        if (!event.isPlain) return QualityValue.Text(text)
        return when {
            text == "true" -> QualityValue.Bool(true)
            text == "false" -> QualityValue.Bool(false)
            text == "null" -> QualityValue.Null
            NUMBER.matches(text) -> QualityScalarLimits.numberToken(text)
            legacyNumber(text) -> {
                if (text.length > QualityScalarLimits.NUMBER_LIMIT) fail("QUALITY_NUMBER_LIMIT")
                fail("RULE_SCALAR_INVALID")
            }
            text.isEmpty() || text.lowercase() in AMBIGUOUS_WORDS || DATE.matches(text) -> fail("RULE_SCALAR_INVALID")
            else -> QualityValue.Text(text)
        }
    }

    private fun legacyNumber(text: String): Boolean {
        if (LEGACY_DECIMAL.matches(text) || RADIX.matches(text)) return true
        var index = if (text.startsWith('+') || text.startsWith('-')) 1 else 0
        if (index == text.length || text[index] !in '0'..'9') return false
        var colon = false
        var digit = false
        var fractional = false
        // Scan repeated sexagesimal groups without recursive regex backtracking.
        while (index < text.length) {
            when (text[index++]) {
                in '0'..'9' -> digit = true
                '_' -> if (!digit) return false
                ':' -> {
                    if (!digit || fractional) return false
                    colon = true
                    digit = false
                }
                '.' -> {
                    if (!colon || !digit || fractional) return false
                    fractional = true
                }
                else -> return false
            }
        }
        return colon && digit
    }

    private class Frame(private val mapping: Boolean) {
        private val fields = linkedMapOf<String, QualityValue>()
        private val elements = mutableListOf<QualityValue>()
        private var key: String? = null
        fun needsKey() = mapping && key == null
        fun accept(value: QualityValue) {
            if (!mapping) { elements.add(value); return }
            if (key == null) {
                if (value !is QualityValue.Text) fail("RULE_KEY_TYPE")
                if (value.value == "<<") fail("RULE_YAML_FEATURE")
                if (fields.containsKey(value.value)) fail("RULE_DUPLICATE_KEY")
                key = value.value
            } else {
                fields[key!!] = value
                key = null
            }
        }
        fun finish(): QualityValue = if (mapping) QualityValue.ObjectValue(fields) else QualityValue.ArrayValue(elements)
    }

    companion object {
        private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
        private val LEGACY_DECIMAL = Regex("[+-]?(?:[0-9][0-9_]*+(?:\\.[0-9_]*+)?|\\.[0-9_]++)(?:[eE][+-]?[0-9]++)?")
        private val RADIX = Regex("[+-]?0[oObBxX][0-9a-fA-F_]++")
        private val DATE = Regex("[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}(?:[Tt ].*)?")
        private val AMBIGUOUS_WORDS = setOf("y", "n", "yes", "no", "on", "off", "true", "false", "null", "~",
            ".nan", "+.nan", "-.nan", ".inf", "+.inf", "-.inf")
        private fun fail(code: String): Nothing = throw QualityFailure(code)
    }
}
