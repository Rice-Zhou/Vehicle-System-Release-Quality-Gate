package com.ricezhou.vsrqg.testmanagement

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.testmanagement.application.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito
import org.springframework.security.access.AccessDeniedException

@Timeout(60)
class RegisterAgentTest {
    private val mapper = ObjectMapper()
    private val body = mapper.readTree("""{"deviceRef":"device-1","supportedProtocolVersions":["1.0"]}""")
    private val actor = AgentActor("service-1", "project-1", "agent-1", "device-1")
    private val store = Mockito.mock(AgentRegistrationStore::class.java)
    private val audit = Mockito.mock(GovernanceStore::class.java)
    private val idempotency = object : IdempotentExecutor {
        override fun <T : Any> execute(scope: String, principalId: String, key: String, requestDigest: String, responseType: Class<T>, action: () -> T): T = action()
    }

    @Test
    fun `registration is disabled unless explicitly enabled`() {
        val access = Mockito.mock(AgentAccess::class.java)
        assertThatThrownBy { RegisterAgent(access, store, idempotency, audit, false).register("fingerprint", body, "key", "digest", "request") }
            .isInstanceOf(AgentRegistrationDisabled::class.java)
        Mockito.verifyNoInteractions(access, store, audit)
    }

    @Test
    fun `authorized registration saves identity metadata and records audit`() {
        val result = RegisterAgent(AgentAccess { _, _ -> actor }, store, idempotency, audit, true)
            .register("fingerprint", body, "key", "digest", "request")
        assertThat(result).isEqualTo(AgentRegistrationResult("1.0", "agent-1", 20, 90))
        Mockito.verify(store).register(actor, body)
        Mockito.verify(audit).appendAudit("project-1", "service-1", "AGENT_REGISTERED", "AGENT", "agent-1", "request", null, null, null)
    }

    @Test
    fun `device mismatch and unsupported protocol cannot persist registration`() {
        val registration = RegisterAgent(AgentAccess { _, _ -> actor }, store, idempotency, audit, true)
        assertThatThrownBy { registration.register("fingerprint", mapper.readTree("""{"deviceRef":"other","supportedProtocolVersions":["1.0"]}"""), "key", "digest", "request") }
            .isInstanceOf(AccessDeniedException::class.java)
        assertThatThrownBy { registration.register("fingerprint", mapper.readTree("""{"deviceRef":"device-1","supportedProtocolVersions":["2.0"]}"""), "key", "digest", "request") }
            .isInstanceOf(AgentProtocolUnsupported::class.java)
        Mockito.verifyNoInteractions(store, audit)
    }

    @Test
    fun `revocation is checked before the idempotency replay`() {
        val replay = Mockito.mock(IdempotentExecutor::class.java)
        val registration = RegisterAgent(AgentAccess { _, _ -> throw AccessDeniedException("revoked") }, store, replay, audit, true)
        assertThatThrownBy { registration.register("fingerprint", body, "key", "digest", "request") }
            .isInstanceOf(AccessDeniedException::class.java)
        Mockito.verifyNoInteractions(replay, store, audit)
    }
}
