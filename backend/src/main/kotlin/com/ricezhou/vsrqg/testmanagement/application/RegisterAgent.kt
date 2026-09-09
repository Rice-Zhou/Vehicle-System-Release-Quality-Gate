package com.ricezhou.vsrqg.testmanagement.application

import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class AgentRegistrationResult(
    val protocolVersion: String = "1.0",
    val agentId: String,
    val heartbeatIntervalSeconds: Int = 20,
    val leaseDurationSeconds: Int = 90,
)

interface AgentRegistrationStore {
    fun register(actor: AgentActor, request: JsonNode)
}

class AgentProtocolUnsupported : RuntimeException("No supported Agent protocol")
class AgentRegistrationDisabled : RuntimeException("Agent registration is disabled")

@Service
class RegisterAgent(
    private val access: AgentAccess,
    private val store: AgentRegistrationStore,
    private val idempotency: IdempotentExecutor,
    private val governance: GovernanceStore,
    @param:Value("\${vsrqg.demo.agent-registration.enabled:false}") private val enabled: Boolean,
) {
    @Transactional
    fun register(fingerprint: String, body: JsonNode, key: String, digest: String, requestId: String): AgentRegistrationResult {
        if (!enabled) throw AgentRegistrationDisabled()
        // Authorization precedes replay; revoked certificates cannot replay old successful registration.
        val actor = access.requireAgent(fingerprint, "agent:register")
        if (body.path("deviceRef").asText() != actor.deviceId) throw AccessDeniedException("Agent device binding mismatch")
        if (body.path("supportedProtocolVersions").none { it.asText() == "1.0" }) throw AgentProtocolUnsupported()
        return idempotency.execute("agent:register:${actor.agentId}", actor.principalId, key, digest, AgentRegistrationResult::class.java) {
            store.register(actor, body)
            governance.appendAudit(actor.projectId, actor.principalId, "AGENT_REGISTERED", "AGENT", actor.agentId, requestId, null)
            AgentRegistrationResult(agentId = actor.agentId)
        }
    }
}
