package com.ricezhou.vsrqg.shared.application

open class ResourceConflict(
    val code: String,
    val resourceTitle: String,
    detail: String,
    cause: Throwable? = null,
) : RuntimeException(detail, cause)
