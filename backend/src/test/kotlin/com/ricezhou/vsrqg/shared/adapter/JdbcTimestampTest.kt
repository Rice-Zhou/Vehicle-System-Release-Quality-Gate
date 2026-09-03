package com.ricezhou.vsrqg.shared.adapter

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JdbcTimestampTest {
    @Test
    fun `JDBC timestamp uses PostgreSQL microsecond precision`() {
        val instant = Instant.parse("2026-09-03T10:15:30.123456789Z")

        assertThat(instant.toJdbcTimestamp().toInstant())
            .isEqualTo(Instant.parse("2026-09-03T10:15:30.123456Z"))
        assertThat(instant).isEqualTo(Instant.parse("2026-09-03T10:15:30.123456789Z"))
    }
}
