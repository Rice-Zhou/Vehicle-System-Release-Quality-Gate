package com.ricezhou.vsrqg.access

import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.ProjectRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(60)
class AgentPermissionTest {
    @Test
    fun `execution and evidence permissions enforce project role boundaries`() {
        val expected = mapOf(
            "test:execute" to setOf(ProjectRole.ENGINEER, ProjectRole.RELEASE_MANAGER, ProjectRole.ADMINISTRATOR),
            "test:read" to ProjectRole.entries.toSet(),
            "evidence:read" to ProjectRole.entries.toSet(),
            "evidence:read:sensitive" to setOf(ProjectRole.QUALITY_OWNER, ProjectRole.ADMINISTRATOR),
            "agent:register" to setOf(ProjectRole.ENGINEER),
            "agent:heartbeat" to setOf(ProjectRole.ENGINEER),
            "agent:poll" to setOf(ProjectRole.ENGINEER),
            "agent:execute" to setOf(ProjectRole.ENGINEER),
            "agent:evidence:write" to setOf(ProjectRole.ENGINEER),
        )
        expected.forEach { (scope, roles) ->
            val permission = Permission.entries.find { it.scope == scope }
            assertThat(permission).describedAs(scope).isNotNull()
            assertThat(ProjectRole.entries.filter { permission!!.isAllowedFor(it) }.toSet()).isEqualTo(roles)
        }
    }
}
