package com.ricezhou.vsrqg.shared

import com.ricezhou.vsrqg.shared.id.UuidV7IdGenerator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UuidV7IdGeneratorTest {
    private val generator = UuidV7IdGenerator()

    @Test
    fun `generates compact prefixed UUIDv7 identifiers within database limit`() {
        val identifiers = (1..100).map { generator.nextId("rel_") }

        assertThat(identifiers).doesNotHaveDuplicates()
        assertThat(identifiers).allMatch { it.matches(Regex("^rel_[0-9a-f]{12}7[0-9a-f]{3}[89ab][0-9a-f]{15}$")) }
        assertThat(identifiers).allMatch { it.length <= 40 }
    }

    @Test
    fun `rejects prefixes that violate the public identifier convention`() {
        assertThatThrownBy { generator.nextId("Release-") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
