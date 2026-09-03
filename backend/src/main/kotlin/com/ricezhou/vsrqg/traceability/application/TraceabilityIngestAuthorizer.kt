package com.ricezhou.vsrqg.traceability.application

import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.shared.application.SafeAccessDenied

fun interface TraceabilityIngestAuthorizer {
    fun require(
        principal: Principal,
        tokenProjectReference: String?,
        requestProjectReference: String,
    ): TraceabilityIngestAuthorization
}

data class TraceabilityIngestAuthorization(
    val principalId: String,
    val projectId: String,
    val projectReference: String,
)

class ProjectScopeMismatch : SafeAccessDenied(
    code = "PROJECT_SCOPE_MISMATCH",
    accessTitle = "Project scope mismatch",
    detail = "The service identity is not authorized for the requested project",
)
