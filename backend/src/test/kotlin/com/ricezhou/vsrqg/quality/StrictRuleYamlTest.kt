package com.ricezhou.vsrqg.quality

import com.ricezhou.vsrqg.quality.adapter.StrictRuleYaml
import com.ricezhou.vsrqg.quality.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

@Timeout(60)
class StrictRuleYamlTest {
    private fun parse(text: String) = StrictRuleYaml().parse(text.toByteArray())
    private fun rejects(text: String, code: String) {
        assertEquals(code, assertThrows<QualityFailure> { parse(text) }.code, text.take(100))
    }

    @Test fun `rejects duplicate decoded keys in each mapping`() {
        rejects("a: 1\n\"\\u0061\": 2", "RULE_DUPLICATE_KEY")
        rejects("outer: {a: 1, a: 2}", "RULE_DUPLICATE_KEY")
        parse("a: {x: 1}\nb: {x: 2}")
    }

    @Test fun `rejects yaml graph and construction features`() {
        listOf("&a text", "*a", "!foo text", "!!str text", "! text", "{<<: {a: 1}}",
            "{\"<<\": 1}", "[&a []]", "!foo {}", "&a {}").forEach {
            rejects(it, "RULE_YAML_FEATURE")
        }
        rejects("---\na\n---\nb", "RULE_DOCUMENT_COUNT")
        rejects("", "RULE_DOCUMENT_COUNT")
        rejects("# comment", "RULE_DOCUMENT_COUNT")
    }

    @Test fun `only strings can be mapping keys`() {
        listOf("{1: a}", "{true: a}", "{null: a}", "{[a]: b}", "{{a: b}: c}")
            .forEach { rejects(it, "RULE_KEY_TYPE") }
        parse("{\"1\": a, \"true\": b}")
    }

    @Test fun `ambiguous plain scalars are rejected while quoted text is retained`() {
        listOf("yes", "YES", "on", "OFF", "True", "NULL", "~", "012", "08", "09", "+08", "-09", "+1", "0x10", "0o10",
            ".nan", "-.Inf", "1:20", "2026-09-17", "2026-9-7", "2026-09-17T01:02:03Z", ".5", "1.", "1_000")
            .forEach {
                rejects(it, "RULE_SCALAR_INVALID")
                assertEquals(QualityValue.Text(it), parse("\"$it\""))
            }
        listOf("SMOKE_CASE_OUTCOME", "item.status", "release.artifacts", "rule-12", "08rule", "09-case", "普通文本")
            .forEach { assertEquals(QualityValue.Text(it), parse(it)) }
        assertEquals(QualityValue.Bool(true), parse("true"))
        assertEquals(QualityValue.Null, parse("null"))
        assertEquals(QualityValue.Integer("9007199254740993".toBigInteger()), parse("9007199254740993"))
        assertEquals(QualityValue.Decimal("1.2300e2".toBigDecimal()), parse("1.2300e2"))
    }

    @Test fun `rejects invalid UTF8 and decoded unpaired surrogates`() {
        listOf(byteArrayOf(0xc0.toByte(), 0xaf.toByte()), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf0.toByte(), 0x9f.toByte())).forEach {
            assertEquals("RULE_UTF8_INVALID", assertThrows<QualityFailure> { StrictRuleYaml().parse(it) }.code)
        }
        rejects("\"\\uD800\"", "QUALITY_UNICODE_INVALID")
        rejects("{\"\\uDC00\": a}", "QUALITY_UNICODE_INVALID")
        assertEquals(QualityValue.Text("😀"), parse("\"\\uD83D\\uDE00\""))
    }

    @Test fun `byte budget counts UTF8 bytes with inclusive boundary`() {
        assertEquals(QualityValue.Text("a".repeat(65536)), parse("a".repeat(65536)))
        rejects("a".repeat(65537), "RULE_BYTES_LIMIT")
        parse("中".repeat(21845) + "a")
        rejects("中".repeat(21845) + "aa", "RULE_BYTES_LIMIT")
    }

    @Test fun `root is depth one and keys count as nodes`() {
        parse("[".repeat(31) + "0" + "]".repeat(31))
        rejects("[".repeat(32) + "0" + "]".repeat(32), "RULE_DEPTH_LIMIT")
        parse("[" + List(4095) { "0" }.joinToString(",") + "]")
        rejects("[" + List(4096) { "0" }.joinToString(",") + "]", "RULE_NODES_LIMIT")
        val mapping = (1..2047).joinToString(",") { "k$it: 0" }
        parse("[{$mapping}]")
        rejects("{$mapping,last: []}", "RULE_NODES_LIMIT")
    }

    @Test fun `numeric token and exponent budgets precede expansion`() {
        parse("9".repeat(4096))
        rejects("9".repeat(4097), "QUALITY_NUMBER_LIMIT")
        parse("1e-4096")
        rejects("1e-4097", "QUALITY_NUMBER_LIMIT")
        rejects("1e2147483647", "QUALITY_NUMBER_LIMIT")
        rejects("1e999999999999999999999", "QUALITY_NUMBER_LIMIT")
        rejects("1e4096", "QUALITY_NUMBER_LIMIT")
        rejects("[", "RULE_YAML_INVALID")
    }

    @Test fun `legacy numeric ambiguity cannot bypass rejection through token length`() {
        listOf("+" + "1".repeat(1023), "+" + "1".repeat(1024), "." + "1".repeat(1024),
            "1:".repeat(512) + "1", "0" + "8".repeat(1024)).forEach {
            rejects(it, "RULE_SCALAR_INVALID")
            assertEquals(QualityValue.Text(it), parse("\"$it\""))
        }
        listOf("1:".repeat(10000) + "1", "+" + "1".repeat(65535),
            "." + "1".repeat(65535), "0x" + "f".repeat(65534), "1_".repeat(32768)).forEach {
            rejects(it, "QUALITY_NUMBER_LIMIT")
        }
        assertEquals(QualityValue.Text("1rule".repeat(10000)), parse("1rule".repeat(10000)))
    }

    @Test fun `YAML and TAG directives and implicit empty values cannot change scalar semantics`() {
        rejects("%YAML 1.1\n---\na", "RULE_YAML_FEATURE")
        rejects("%TAG !e! tag:example.org,2000:app/\n---\na", "RULE_YAML_FEATURE")
        rejects("a:", "RULE_SCALAR_INVALID")
        rejects("---\n...", "RULE_SCALAR_INVALID")
        assertEquals(QualityValue.Text(""), parse("\"\""))
    }

    @Test fun `real rule syntax remains supported without AST binding`() {
        val rule = StrictRuleYaml().parse(Files.readAllBytes(Path.of("../contracts/examples/v0.2/quality-rule/critical-anr.yaml")))
        assertInstanceOf(QualityValue.ObjectValue::class.java, rule)
        parse("condition: {futureOperator: {fact: item.status}}")
        assertEquals(
            QualityValue.ObjectValue(mapOf("a" to QualityValue.ArrayValue(listOf(
                QualityValue.Null, QualityValue.Bool(false), QualityValue.Text("on"),
                QualityValue.ObjectValue(mapOf("x" to QualityValue.Integer(2.toBigInteger()))),
            )))), parse("a: [null, false, 'on', {x: 2}]"),
        )
    }
}
