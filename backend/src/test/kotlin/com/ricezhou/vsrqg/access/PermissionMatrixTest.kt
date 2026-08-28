package com.ricezhou.vsrqg.access

import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.ProjectRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class PermissionMatrixTest {
    @ParameterizedTest
    @EnumSource(ProjectRole::class)
    fun `fixed RBAC permissions match the approved matrix`(role: ProjectRole) {
        assertThat(Permission.RELEASE_READ.isAllowedFor(role)).isTrue()
        assertThat(Permission.RELEASE_CREATE.isAllowedFor(role)).isEqualTo(
            role in setOf(ProjectRole.ENGINEER, ProjectRole.RELEASE_MANAGER, ProjectRole.ADMINISTRATOR),
        )
        assertThat(Permission.MANIFEST_WRITE.isAllowedFor(role)).isEqualTo(
            role in setOf(ProjectRole.ENGINEER, ProjectRole.RELEASE_MANAGER, ProjectRole.ADMINISTRATOR),
        )
        assertThat(Permission.MANIFEST_LOCK.isAllowedFor(role)).isEqualTo(
            role in setOf(ProjectRole.RELEASE_MANAGER, ProjectRole.ADMINISTRATOR),
        )
        assertThat(Permission.ISSUE_SYNC.isAllowedFor(role)).isEqualTo(
            role in setOf(ProjectRole.ENGINEER, ProjectRole.RELEASE_MANAGER, ProjectRole.ADMINISTRATOR),
        )
        assertThat(Permission.ISSUE_READ.isAllowedFor(role)).isTrue()
        assertThat(Permission.ISSUE_SNAPSHOT.isAllowedFor(role)).isEqualTo(
            role in setOf(ProjectRole.ENGINEER, ProjectRole.RELEASE_MANAGER, ProjectRole.ADMINISTRATOR),
        )
        assertThat(Permission.TRACEABILITY_READ.isAllowedFor(role)).isTrue()
        assertThat(Permission.TRACEABILITY_VERIFY.isAllowedFor(role)).isEqualTo(
            role in setOf(ProjectRole.ENGINEER, ProjectRole.QUALITY_OWNER, ProjectRole.ADMINISTRATOR),
        )
    }

    @Test
    fun `traceability ingest is not mapped to a user project role`() {
        assertThat(Permission.entries.map(Permission::scope)).doesNotContain("traceability:ingest")
    }
}
