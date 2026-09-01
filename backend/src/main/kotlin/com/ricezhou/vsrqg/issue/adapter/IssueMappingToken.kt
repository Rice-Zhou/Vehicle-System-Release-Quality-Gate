package com.ricezhou.vsrqg.issue.adapter

internal const val MAX_MAPPING_TOKEN_LENGTH = 120

internal fun isValidMappingTokenInput(raw: String): Boolean =
    raw.isNotBlank() && raw.length <= MAX_MAPPING_TOKEN_LENGTH && raw.none(Char::isISOControl)
