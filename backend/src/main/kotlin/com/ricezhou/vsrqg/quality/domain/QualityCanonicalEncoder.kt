package com.ricezhou.vsrqg.quality.domain

import java.io.ByteArrayOutputStream

class QualityCanonicalEncoder {
    fun encode(value: QualityValue): ByteArray {
        val output = BoundedOutput()
        val pending = ArrayDeque<Any>()
        pending.addLast(value)
        while (pending.isNotEmpty()) {
            when (val task = pending.removeLast()) {
                is String -> output.ascii(task)
                is ArrayCursor -> {
                    if (!task.items.hasNext()) { output.ascii("]]"); continue }
                    if (!task.first) output.ascii(",")
                    task.first = false
                    pending.addLast(task)
                    pending.addLast(task.items.next())
                }
                is ObjectCursor -> {
                    if (!task.items.hasNext()) { output.ascii("]]"); continue }
                    if (!task.first) output.ascii(",")
                    task.first = false
                    val entry = task.items.next()
                    output.ascii("[")
                    output.quoted(entry.key)
                    output.ascii(",")
                    pending.addLast(task)
                    pending.addLast("]")
                    pending.addLast(entry.value)
                }
                QualityValue.Null -> output.ascii("[\"NULL\",null]")
                is QualityValue.Bool -> output.ascii("[\"BOOLEAN\",${task.value}]")
                is QualityValue.Text -> {
                    output.ascii("[\"STRING\",")
                    output.quoted(task.value)
                    output.ascii("]")
                }
                is QualityValue.Integer -> output.ascii("[\"INTEGER\",\"${QualityScalarLimits.integer(task.value)}\"]")
                is QualityValue.Decimal -> output.ascii("[\"DECIMAL\",\"${QualityScalarLimits.decimal(task.value)}\"]")
                is QualityValue.ArrayValue -> {
                    output.ascii("[\"ARRAY\",[")
                    pending.addLast(ArrayCursor(task.values.iterator()))
                }
                is QualityValue.ObjectValue -> {
                    // Check a cheap lower bound before sorting or scanning untrusted keys.
                    var minimumBytes = 13L
                    for (key in task.values.keys) {
                        minimumBytes += key.length.toLong() + 6
                        if (minimumBytes > BYTE_LIMIT) throw QualityFailure("QUALITY_BYTES_LIMIT")
                        QualityScalarLimits.unicode(key)
                    }
                    output.ascii("[\"OBJECT\",[")
                    pending.addLast(ObjectCursor(task.values.entries.sortedWith { a, b -> compareKeys(a.key, b.key) }.iterator()))
                }
                else -> error("Unsupported canonical encoding task")
            }
        }
        return output.bytes()
    }

    private class ArrayCursor(val items: Iterator<QualityValue>, var first: Boolean = true)
    private class ObjectCursor(val items: Iterator<Map.Entry<String, QualityValue>>, var first: Boolean = true)

    private class BoundedOutput {
        private val output = ByteArrayOutputStream()
        fun ascii(text: String) { text.forEach { byte(it.code) } }
        fun quoted(text: String) {
            byte('"'.code)
            var index = 0
            while (index < text.length) {
                val char = text[index++]
                when {
                    char == '"' || char == '\\' -> { byte('\\'.code); byte(char.code) }
                    char.code < 32 -> ascii("\\u" + char.code.toString(16).padStart(4, '0'))
                    char.isHighSurrogate() -> {
                        if (index == text.length || !text[index].isLowSurrogate()) throw QualityFailure("QUALITY_UNICODE_INVALID")
                        codePoint(Character.toCodePoint(char, text[index++]))
                    }
                    char.isLowSurrogate() -> throw QualityFailure("QUALITY_UNICODE_INVALID")
                    else -> codePoint(char.code)
                }
            }
            byte('"'.code)
        }
        private fun codePoint(point: Int) {
            when {
                point < 0x80 -> byte(point)
                point < 0x800 -> { byte(0xc0 or (point shr 6)); byte(0x80 or (point and 63)) }
                point < 0x10000 -> {
                    byte(0xe0 or (point shr 12)); byte(0x80 or ((point shr 6) and 63)); byte(0x80 or (point and 63))
                }
                else -> {
                    byte(0xf0 or (point shr 18)); byte(0x80 or ((point shr 12) and 63))
                    byte(0x80 or ((point shr 6) and 63)); byte(0x80 or (point and 63))
                }
            }
        }
        private fun byte(value: Int) {
            if (output.size() == BYTE_LIMIT) throw QualityFailure("QUALITY_BYTES_LIMIT")
            output.write(value)
        }
        fun bytes(): ByteArray = output.toByteArray()
    }

    companion object {
        const val VERSION = "VSRQG-QUALITY-TYPED-1"
        private const val BYTE_LIMIT = 4 * 1024 * 1024
        private fun compareKeys(left: String, right: String): Int {
            var a = 0
            var b = 0
            while (a < left.length && b < right.length) {
                val ac = left.codePointAt(a)
                val bc = right.codePointAt(b)
                if (ac != bc) return ac.compareTo(bc)
                a += Character.charCount(ac)
                b += Character.charCount(bc)
            }
            return (left.length - a).compareTo(right.length - b)
        }
    }
}
