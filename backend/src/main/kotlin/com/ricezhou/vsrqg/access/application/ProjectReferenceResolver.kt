package com.ricezhou.vsrqg.access.application

import com.ricezhou.vsrqg.shared.application.ResourceNotFound

fun interface ProjectReferenceResolver {
    fun resolve(projectKey: String): String
}

class ProjectNotFound(projectKey: String) :
    ResourceNotFound(
        "PROJECT_NOT_FOUND",
        "Project not found",
        "Project '$projectKey' was not found",
    )
