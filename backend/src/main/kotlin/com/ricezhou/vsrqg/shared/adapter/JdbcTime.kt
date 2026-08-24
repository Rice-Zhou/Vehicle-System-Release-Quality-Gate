package com.ricezhou.vsrqg.shared.adapter

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal fun Instant.toJdbcTimestamp(): OffsetDateTime = atOffset(ZoneOffset.UTC)
