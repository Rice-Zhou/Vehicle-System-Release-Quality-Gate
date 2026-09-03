package com.ricezhou.vsrqg.traceability.adapter

import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.traceability.application.ProjectScopeMismatch
import com.ricezhou.vsrqg.traceability.application.TraceabilityIngestAuthorization
import com.ricezhou.vsrqg.traceability.application.TraceabilityIngestAuthorizer
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

@Component
class JdbcTraceabilityIngestAuthorizer(
    private val jdbc: JdbcClient,
) : TraceabilityIngestAuthorizer {
    override fun require(
        principal: Principal,
        tokenProjectReference: String?,
        requestProjectReference: String,
    ): TraceabilityIngestAuthorization {
        if (
            !principal.service ||
            tokenProjectReference == null ||
            tokenProjectReference != requestProjectReference
        ) {
            throw ProjectScopeMismatch()
        }
        return jdbc.sql(
            """
            SELECT p.id AS principal_id,
                   prj.id AS project_id,
                   prj.project_key AS project_reference
            FROM principal p
            JOIN project_assignment pa ON pa.principal_id = p.id
            JOIN project prj ON prj.id = pa.project_id
            WHERE p.issuer = :issuer
              AND p.subject = :subject
              AND p.principal_type = 'SERVICE'
              AND p.disabled = false
              AND prj.project_key = :projectReference
              AND prj.archived = false
            """.trimIndent(),
        )
            .param("issuer", principal.issuer)
            .param("subject", principal.subject)
            .param("projectReference", requestProjectReference)
            .query { rs, _ ->
                TraceabilityIngestAuthorization(
                    principalId = rs.getString("principal_id"),
                    projectId = rs.getString("project_id"),
                    projectReference = rs.getString("project_reference"),
                )
            }
            .optional()
            .orElseThrow(::ProjectScopeMismatch)
    }
}
