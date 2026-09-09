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
    TEST_EXECUTE("test:execute", setOf(ProjectRole.ENGINEER, ProjectRole.RELEASE_MANAGER, ProjectRole.ADMINISTRATOR)),
    TEST_READ("test:read", ProjectRole.entries.toSet()),
    EVIDENCE_READ("evidence:read", ProjectRole.entries.toSet()),
    EVIDENCE_READ_SENSITIVE("evidence:read:sensitive", setOf(ProjectRole.QUALITY_OWNER, ProjectRole.ADMINISTRATOR)),
    AGENT_REGISTER("agent:register", setOf(ProjectRole.ENGINEER)),
    AGENT_HEARTBEAT("agent:heartbeat", setOf(ProjectRole.ENGINEER)),
    AGENT_POLL("agent:poll", setOf(ProjectRole.ENGINEER)),
    AGENT_EXECUTE("agent:execute", setOf(ProjectRole.ENGINEER)),
    AGENT_EVIDENCE_WRITE("agent:evidence:write", setOf(ProjectRole.ENGINEER)),
    ;

    fun isAllowedFor(role: ProjectRole): Boolean = role in allowedRoles
}
