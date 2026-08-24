package com.ricezhou.vsrqg.shared.id

import java.security.SecureRandom
import java.util.UUID
import org.springframework.stereotype.Component

fun interface IdGenerator {
    fun nextId(prefix: String): String
}

@Component
class UuidV7IdGenerator : IdGenerator {
    private val random = SecureRandom()

    override fun nextId(prefix: String): String {
        require(PREFIX.matches(prefix)) { "ID prefix must be lowercase and end with an underscore" }
        val timestamp = System.currentTimeMillis() and TIMESTAMP_MASK
        val randomA = random.nextInt() and RANDOM_A_MASK
        val mostSignificantBits = (timestamp shl TIMESTAMP_SHIFT) or VERSION_BITS or randomA.toLong()
        val leastSignificantBits = (random.nextLong() and RANDOM_B_MASK) or VARIANT_BITS
        val compactUuid = UUID(mostSignificantBits, leastSignificantBits).toString().replace("-", "")
        return "$prefix$compactUuid".also {
            require(it.length <= MAX_DATABASE_ID_LENGTH) { "Generated ID exceeds database column length" }
        }
    }

    private companion object {
        val PREFIX = Regex("^[a-z][a-z0-9_]*_$")
        const val MAX_DATABASE_ID_LENGTH = 40
        const val TIMESTAMP_MASK = 0x0000_FFFF_FFFF_FFFFL
        const val TIMESTAMP_SHIFT = 16
        const val RANDOM_A_MASK = 0x0FFF
        const val VERSION_BITS = 0x7000L
        const val RANDOM_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL
        const val VARIANT_BITS = Long.MIN_VALUE
    }
}
