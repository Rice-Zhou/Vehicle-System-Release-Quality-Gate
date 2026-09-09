package com.ricezhou.vsrqg.testmanagement.application

data class AgentActor(val principalId: String, val projectId: String, val agentId: String, val deviceId: String)

fun interface AgentAccess {
    fun requireAgent(certificateSha256: String, scope: String): AgentActor
}
