package com.ricezhou.vsrqg.access.application

import com.ricezhou.vsrqg.access.domain.Principal

fun interface AuthenticatedPrincipalResolver {
    fun resolve(
        issuer: String?,
        subject: String?,
        principalType: String?,
    ): Principal
}
