package com.ricezhou.vsrqg.testmanagement.domain

import java.time.Instant

object LeaseWindow {
    fun writable(now: Instant, expiresAt: Instant, supplied: Long, current: Long, terminal: Boolean): Boolean =
        !terminal && supplied == current && now.isBefore(expiresAt)
}
