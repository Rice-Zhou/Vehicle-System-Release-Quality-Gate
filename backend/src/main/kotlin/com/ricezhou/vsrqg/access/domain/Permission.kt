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
    ISSUE_SYNC(
        "issue:sync",
        setOf(ProjectRole.ENGINEER, ProjectRole.RELEASE_MANAGER, ProjectRole.ADMINISTRATOR),
    ),
    ISSUE_CONFIGURE(
        "issue:configure",
        setOf(ProjectRole.RELEASE_MANAGER, ProjectRole.ADMINISTRATOR),
    ),
    ISSUE_READ("issue:read", ProjectRole.entries.toSet()),
    ISSUE_SNAPSHOT(
        "issue:snapshot",
        setOf(ProjectRole.ENGINEER, ProjectRole.RELEASE_MANAGER, ProjectRole.ADMINISTRATOR),
    ),
    TRACEABILITY_READ("traceability:read", ProjectRole.entries.toSet()),
    TRACEABILITY_VERIFY(
        "traceability:verify",
        setOf(ProjectRole.ENGINEER, ProjectRole.QUALITY_OWNER, ProjectRole.ADMINISTRATOR),
    ),
    ;

    fun isAllowedFor(role: ProjectRole): Boolean = role in allowedRoles
}
