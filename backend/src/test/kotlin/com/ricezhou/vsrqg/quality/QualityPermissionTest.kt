package com.ricezhou.vsrqg.quality

import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.ProjectRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(60)
class QualityPermissionTest {
    @Test
    fun `evaluation is limited to the approved project roles`() {
        val permission = Permission.entries.singleOrNull { it.scope == "quality:evaluate" }
        assertNotNull(permission)
        assertEquals(
            setOf(ProjectRole.ENGINEER, ProjectRole.QUALITY_OWNER, ProjectRole.ADMINISTRATOR),
            ProjectRole.entries.filter { permission!!.isAllowedFor(it) }.toSet(),
        )
    }

    @Test
    fun `quality history is readable by project members`() {
        val permission = Permission.entries.singleOrNull { it.scope == "quality:read" }
        assertNotNull(permission)
        assertEquals(ProjectRole.entries.toSet(), ProjectRole.entries.filter { permission!!.isAllowedFor(it) }.toSet())
    }
}