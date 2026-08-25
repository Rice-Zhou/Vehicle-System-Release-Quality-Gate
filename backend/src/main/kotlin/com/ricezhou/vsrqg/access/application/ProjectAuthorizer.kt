package com.ricezhou.vsrqg.access.application

import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal

data class ProjectAuthorization(
    val principalId: String,
)

fun interface ProjectAuthorizer {
    fun require(
        principal: Principal,
        projectId: String,
        permission: Permission,
    ): ProjectAuthorization
}
