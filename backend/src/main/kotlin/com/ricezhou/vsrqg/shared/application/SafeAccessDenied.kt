package com.ricezhou.vsrqg.shared.application

import org.springframework.security.access.AccessDeniedException

open class SafeAccessDenied(
    val code: String,
    val accessTitle: String,
    detail: String,
) : AccessDeniedException(detail)
