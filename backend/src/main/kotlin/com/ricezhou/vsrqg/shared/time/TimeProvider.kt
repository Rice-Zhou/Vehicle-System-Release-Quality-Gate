package com.ricezhou.vsrqg.shared.time

import java.time.Instant
import org.springframework.stereotype.Component

fun interface TimeProvider {
    fun now(): Instant
}

@Component
class SystemTimeProvider : TimeProvider {
    override fun now(): Instant = Instant.now()
}
