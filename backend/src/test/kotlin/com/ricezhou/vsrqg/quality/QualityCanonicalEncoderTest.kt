package com.ricezhou.vsrqg.quality

import com.ricezhou.vsrqg.quality.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.BigInteger

@Timeout(60)
class QualityCanonicalEncoderTest {
    private fun encode(value: QualityValue) = QualityCanonicalEncoder().encode(value)
    private fun golden(value: QualityValue, expected: String) = assertArrayEquals(expected.toByteArray(), encode(value))
    private fun rejects(value: QualityValue, code: String) =
        assertEquals(code, assertThrows<QualityFailure> { encode(value) }.code)

    @Test fun `all types have distinct literal golden bytes`() {
        golden(QualityValue.Null, "[\"NULL\",null]")
        golden(QualityValue.Bool(true), "[\"BOOLEAN\",true]")
        golden(QualityValue.Bool(false), "[\"BOOLEAN\",false]")
        golden(QualityValue.Text("中文/😀"), "[\"STRING\",\"中文/😀\"]")
        golden(QualityValue.Integer(BigInteger.ONE), "[\"INTEGER\",\"1\"]")
        golden(QualityValue.Decimal(BigDecimal.ONE), "[\"DECIMAL\",\"1\"]")
        golden(QualityValue.ArrayValue(listOf(QualityValue.Bool(false), QualityValue.Null)),
            "[\"ARRAY\",[[\"BOOLEAN\",false],[\"NULL\",null]]]")
        golden(QualityValue.ObjectValue(mapOf("b" to QualityValue.Null, "a" to QualityValue.Text("x"))),
            "[\"OBJECT\",[[\"a\",[\"STRING\",\"x\"]],[\"b\",[\"NULL\",null]]]]")
        golden(QualityValue.ArrayValue(emptyList()), "[\"ARRAY\",[]]")
        golden(QualityValue.ObjectValue(emptyMap()), "[\"OBJECT\",[]]")
    }

    @Test fun `strings escape only controls quote and backslash without normalization`() {
        golden(QualityValue.Text("\u0000\b\t\n\u000c\r\u001f\"\\/"),
            "[\"STRING\",\"\\u0000\\u0008\\u0009\\u000a\\u000c\\u000d\\u001f\\\"\\\\/\"]")
        golden(QualityValue.Text("é e\u0301\u2028\u007f"), "[\"STRING\",\"é e\u0301\u2028\u007f\"]")
        rejects(QualityValue.Text("\uD800"), "QUALITY_UNICODE_INVALID")
        rejects(QualityValue.ObjectValue(mapOf("\uDC00" to QualityValue.Null)), "QUALITY_UNICODE_INVALID")
    }

    @Test fun `object ordering follows Unicode scalars not UTF16`() {
        golden(QualityValue.ObjectValue(linkedMapOf("😀" to QualityValue.Null, "\uE000" to QualityValue.Null,
            "e\u0301" to QualityValue.Null, "é" to QualityValue.Null)),
            "[\"OBJECT\",[[\"e\u0301\",[\"NULL\",null]],[\"é\",[\"NULL\",null]],[\"\uE000\",[\"NULL\",null]],[\"😀\",[\"NULL\",null]]]]")
    }

    @Test fun `numbers stay exact and normalize zero trailing zeros and exponent`() {
        listOf("-0.000" to "0", "1.2300" to "1.23", "1e3" to "1000", "1e-3" to "0.001",
            "0.12345678901234567890123456789" to "0.12345678901234567890123456789")
            .forEach { (input, expected) -> golden(QualityValue.Decimal(BigDecimal(input)), "[\"DECIMAL\",\"$expected\"]") }
        golden(QualityValue.Integer(BigInteger("9007199254740992")), "[\"INTEGER\",\"9007199254740992\"]")
        golden(QualityValue.Integer(BigInteger("9007199254740993")), "[\"INTEGER\",\"9007199254740993\"]")
    }

    @Test fun `direct values enforce numeric budgets before expansion`() {
        encode(QualityValue.Integer(BigInteger("9".repeat(4096))))
        rejects(QualityValue.Integer(BigInteger("9".repeat(4097))), "QUALITY_NUMBER_LIMIT")
        encode(QualityValue.Decimal(BigDecimal("9".repeat(4096))))
        rejects(QualityValue.Decimal(BigDecimal("9".repeat(4097))), "QUALITY_NUMBER_LIMIT")
        encode(QualityValue.Decimal(BigDecimal(BigInteger.ONE, 4096)))
        rejects(QualityValue.Decimal(BigDecimal(BigInteger.ONE, 4097)), "QUALITY_NUMBER_LIMIT")
        rejects(QualityValue.Decimal(BigDecimal(BigInteger.ONE, Int.MIN_VALUE)), "QUALITY_NUMBER_LIMIT")
        rejects(QualityValue.Decimal(BigDecimal(BigInteger.ONE, Int.MAX_VALUE)), "QUALITY_NUMBER_LIMIT")
        encode(QualityValue.Decimal(BigDecimal(BigInteger.ONE, -4095)))
        rejects(QualityValue.Decimal(BigDecimal(BigInteger.ONE, -4096)), "QUALITY_NUMBER_LIMIT")
        rejects(QualityValue.Decimal(BigDecimal(BigInteger.ZERO, 4097)), "QUALITY_NUMBER_LIMIT")
        golden(QualityValue.Decimal(BigDecimal(BigInteger.ONE.negate(), 4096)),
            "[\"DECIMAL\",\"-0." + "0".repeat(4095) + "1\"]")
        rejects(QualityValue.Decimal(BigDecimal(BigInteger("9".repeat(4096)), -4096)), "QUALITY_NUMBER_LIMIT")
    }

    @Test fun `canonical byte budget includes UTF8 and escape overhead`() {
        val budget = 4 * 1024 * 1024
        assertEquals(budget, encode(QualityValue.Text("a".repeat(budget - 13))).size)
        rejects(QualityValue.Text("a".repeat(budget - 12)), "QUALITY_BYTES_LIMIT")
        rejects(QualityValue.Text("\u0000".repeat(budget / 6)), "QUALITY_BYTES_LIMIT")
        rejects(QualityValue.Text("中".repeat(budget / 3)), "QUALITY_BYTES_LIMIT")
        rejects(QualityValue.ObjectValue(mapOf("a".repeat(budget) to QualityValue.Null)), "QUALITY_BYTES_LIMIT")
        assertEquals(budget, encode(QualityValue.ObjectValue(mapOf("a".repeat(budget - 31) to QualityValue.Null))).size)
        rejects(QualityValue.ObjectValue(mapOf("a".repeat(budget - 30) to QualityValue.Null)), "QUALITY_BYTES_LIMIT")
    }

    @Test fun `directly constructed deep trees do not overflow JVM stack`() {
        var value: QualityValue = QualityValue.Null
        repeat(20000) { value = QualityValue.ArrayValue(listOf(value)) }
        assertEquals(240013, encode(value).size)
    }

    @Test fun `collections cannot be mutated through original or exposed views`() {
        val list = mutableListOf<QualityValue>(QualityValue.Null)
        val map = mutableMapOf<String, QualityValue>("a" to QualityValue.Null)
        val array = QualityValue.ArrayValue(list)
        val obj = QualityValue.ObjectValue(map)
        list.clear()
        map.clear()
        golden(array, "[\"ARRAY\",[[\"NULL\",null]]]")
        golden(obj, "[\"OBJECT\",[[\"a\",[\"NULL\",null]]]]")
        assertThrows<UnsupportedOperationException> { (array.values as MutableList).clear() }
        assertThrows<UnsupportedOperationException> { (obj.values as MutableMap).clear() }
    }
}
