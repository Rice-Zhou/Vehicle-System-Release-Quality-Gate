package com.ricezhou.vsrqg.quality.domain

import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections

sealed interface QualityValue {
    data object Null : QualityValue
    data class Bool(val value: Boolean) : QualityValue
    data class Text(val value: String) : QualityValue
    data class Integer(val value: BigInteger) : QualityValue
    data class Decimal(val value: BigDecimal) : QualityValue
    class ArrayValue(values: List<QualityValue>) : QualityValue {
        val values: List<QualityValue> = Collections.unmodifiableList(ArrayList(values))
        override fun equals(other: Any?) = other is ArrayValue && values == other.values
        override fun hashCode() = values.hashCode()
    }
    class ObjectValue(values: Map<String, QualityValue>) : QualityValue {
        val values: Map<String, QualityValue> = Collections.unmodifiableMap(LinkedHashMap(values))
        override fun equals(other: Any?) = other is ObjectValue && values == other.values
        override fun hashCode() = values.hashCode()
    }
}

internal object QualityScalarLimits {
    const val NUMBER_LIMIT = 4096
    private const val EXPANDED_LIMIT = 8192
    // ceil(4096 * log2(10)); larger magnitudes cannot have at most 4096 digits.
    private const val INTEGER_BITS = 13607

    fun unicode(text: String) {
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (char.isHighSurrogate()) {
                if (index == text.length || !text[index++].isLowSurrogate()) failUnicode()
            } else if (char.isLowSurrogate()) failUnicode()
        }
    }

    fun integer(value: BigInteger): String {
        if (value.bitLength() > INTEGER_BITS) failNumber()
        val text = value.toString()
        if (text.length - (if (value.signum() < 0) 1 else 0) > NUMBER_LIMIT) failNumber()
        return text
    }

    fun decimal(value: BigDecimal): String {
        val scale = value.scale().toLong()
        if (scale !in -NUMBER_LIMIT.toLong()..NUMBER_LIMIT.toLong()) failNumber()
        // Bound the unscaled magnitude before precision() can perform expensive conversion.
        if (value.unscaledValue().bitLength() > INTEGER_BITS) failNumber()
        val precision = value.precision().toLong()
        if (precision > NUMBER_LIMIT || maxOf(1, precision - scale) > NUMBER_LIMIT) failNumber()
        val length = (if (value.signum() < 0) 1 else 0) + when {
            scale <= 0 -> precision - scale
            scale >= precision -> 2 + scale
            else -> precision + 1
        }
        if (length > EXPANDED_LIMIT) failNumber()
        return if (value.signum() == 0) "0" else value.stripTrailingZeros().toPlainString()
    }

    fun numberToken(text: String): QualityValue {
        if (text.length > NUMBER_LIMIT) failNumber()
        try {
            return if (text.any { it == '.' || it == 'e' || it == 'E' }) {
                val value = BigDecimal(text)
                decimal(value)
                QualityValue.Decimal(value)
            } else {
                val value = BigInteger(text)
                integer(value)
                QualityValue.Integer(value)
            }
        } catch (_: NumberFormatException) {
            failNumber()
        }
    }

    private fun failNumber(): Nothing = throw QualityFailure("QUALITY_NUMBER_LIMIT")
    private fun failUnicode(): Nothing = throw QualityFailure("QUALITY_UNICODE_INVALID")
}
