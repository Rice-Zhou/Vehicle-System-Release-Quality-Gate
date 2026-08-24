package com.ricezhou.vsrqg.access.adapter

import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.access.domain.ProjectRole
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Component

@Component
class JdbcProjectAuthorizer(
    private val jdbc: JdbcClient,
) : ProjectAuthorizer {
    override fun require(
        principal: Principal,
        projectId: String,
        permission: Permission,
    ) {
        val role = jdbc.sql(
            """
            SELECT pa.role
            FROM principal p
            JOIN project_assignment pa ON pa.principal_id = p.id
            JOIN project prj ON prj.id = pa.project_id
            WHERE p.issuer = :issuer
              AND p.subject = :subject
              AND p.disabled = false
              AND prj.id = :projectId
              AND prj.archived = false
            """.trimIndent(),
        )
            .param("issuer", principal.issuer)
            .param("subject", principal.subject)
            .param("projectId", projectId)
            .query(String::class.java)
            .optional()
            .map(::parseRole)
            .orElseThrow { denied(projectId, permission) }

        if (!permission.isAllowedFor(role)) {
            throw denied(projectId, permission)
        }
    }

    private fun parseRole(value: String): ProjectRole = try {
        ProjectRole.valueOf(value)
    } catch (_: IllegalArgumentException) {
        throw AccessDeniedException("Project assignment has an unsupported role")
    }

    private fun denied(
        projectId: String,
        permission: Permission,
    ): AccessDeniedException = AccessDeniedException(
        "Principal lacks ${permission.scope} for project $projectId",
    )
}
