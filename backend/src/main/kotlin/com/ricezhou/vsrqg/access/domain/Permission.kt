package com.ricezhou.vsrqg.access.domain

enum class ProjectRole {
    VIEWER,
    ENGINEER,
    RELEASE_MANAGER,
    QUALITY_OWNER,
    ADMINISTRATOR,
}

enum class Permission(
    val scope: String,
    private val allowedRoles: Set<ProjectRole>,
) {
    RELEASE_CREATE(
        "release:create",
        setOf(ProjectRole.ENGINEER, ProjectRole.RELEASE_MANAGER, ProjectRole.ADMINISTRATOR),
    ),
    RELEASE_READ("release:read", ProjectRole.entries.toSet()),
    MANIFEST_WRITE(
        "manifest:write",
        setOf(ProjectRole.ENGINEER, ProjectRole.RELEASE_MANAGER, ProjectRole.ADMINISTRATOR),
    ),
    MANIFEST_LOCK(
        "manifest:lock",
        setOf(ProjectRole.RELEASE_MANAGER, ProjectRole.ADMINISTRATOR),
    ),
    ;

    fun isAllowedFor(role: ProjectRole): Boolean = role in allowedRoles
}
