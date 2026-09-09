package com.ricezhou.vsrqg.testmanagement.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.testmanagement.application.AgentAccess
import com.ricezhou.vsrqg.testmanagement.application.AgentActor
import com.ricezhou.vsrqg.testmanagement.application.AgentRegistrationStore
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class JdbcAgentAccess(private val jdbc: JdbcClient, private val authorizer: ProjectAuthorizer) : AgentAccess, AgentRegistrationStore {
    // This method only reads/locks identity. A Worker may handle an expected denial and
    // commit an ERROR result; uncaught denial still rolls back the caller's mutation transaction.
    @Transactional(noRollbackFor = [AccessDeniedException::class])
    override fun requireAgent(certificateSha256: String, scope: String): AgentActor {
        val permission = Permission.entries.find { it.scope == scope && it.scope.startsWith("agent:") }
            ?: throw AccessDeniedException("Unsupported Agent scope")
        if (!Regex("^[0-9a-f]{64}$").matches(certificateSha256)) throw AccessDeniedException("Invalid Agent identity")
        // Serialize each Agent before idempotency writes; upgrading shared Agent locks can deadlock.
        val binding = jdbc.sql("""
            SELECT a.id, a.principal_id, a.project_id, a.device_id, p.issuer, p.subject
            FROM agent a
            JOIN device d ON d.id = a.device_id AND d.project_id = a.project_id
            JOIN principal p ON p.id = a.principal_id
            JOIN project prj ON prj.id = a.project_id
            JOIN project_assignment pa ON pa.project_id = a.project_id AND pa.principal_id = a.principal_id
            WHERE a.certificate_sha256 = :fingerprint AND a.revoked = false
              AND d.disabled = false AND p.principal_type = 'SERVICE'
            FOR UPDATE OF a
            FOR SHARE OF d, p, prj, pa
        """.trimIndent()).param("fingerprint", certificateSha256).query { row, _ ->
            Binding(AgentActor(row.getString("principal_id"), row.getString("project_id"), row.getString("id"), row.getString("device_id")),
                Principal(row.getString("issuer"), row.getString("subject"), true))
        }.optional().orElseThrow { AccessDeniedException("Agent identity is not authorized") }
        authorizer.require(binding.principal, binding.actor.projectId, permission)
        return binding.actor
    }

    override fun register(actor: AgentActor, request: JsonNode) {
        val updated = jdbc.sql("""
            UPDATE agent SET agent_version = :version, negotiated_protocol = '1.0',
              capabilities = CAST(:capabilities AS jsonb), collector_versions = CAST(:collectors AS jsonb),
              registered_at = now()
            WHERE id = :id
        """.trimIndent()).param("id", actor.agentId).param("version", request.path("agentVersion").asText())
            .param("capabilities", request.path("capabilities").toString())
            .param("collectors", request.path("collectorVersions").toString()).update()
        check(updated == 1) { "Agent registration did not update exactly one binding" }
    }

    private data class Binding(val actor: AgentActor, val principal: Principal)
}
