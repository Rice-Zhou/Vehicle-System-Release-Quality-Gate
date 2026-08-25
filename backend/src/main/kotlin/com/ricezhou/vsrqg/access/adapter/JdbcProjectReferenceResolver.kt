package com.ricezhou.vsrqg.access.adapter

import com.ricezhou.vsrqg.access.application.ProjectNotFound
import com.ricezhou.vsrqg.access.application.ProjectReferenceResolver
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

@Component
class JdbcProjectReferenceResolver(
    private val jdbc: JdbcClient,
) : ProjectReferenceResolver {
    override fun resolve(projectKey: String): String = jdbc.sql(
        "SELECT id FROM project WHERE project_key = :projectKey AND archived = false",
    )
        .param("projectKey", projectKey)
        .query(String::class.java)
        .optional()
        .orElseThrow { ProjectNotFound(projectKey) }
}
