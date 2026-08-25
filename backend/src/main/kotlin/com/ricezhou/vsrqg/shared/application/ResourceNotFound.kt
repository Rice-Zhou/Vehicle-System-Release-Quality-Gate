package com.ricezhou.vsrqg.shared.application

open class ResourceNotFound(
    val code: String,
    val resourceTitle: String,
    detail: String,
) : RuntimeException(detail)
