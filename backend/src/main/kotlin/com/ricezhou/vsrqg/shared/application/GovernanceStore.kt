package com.ricezhou.vsrqg.shared.application

import com.fasterxml.jackson.databind.JsonNode

interface GovernanceStore {
    fun appendAudit(
        projectId: String,
        actorId: String,
        action: String,
        resourceType: String,
        resourceId: String,
        requestId: String,
        reason: String?,
        beforeState: JsonNode? = null,
        afterState: JsonNode? = null,
    )

    fun appendOutbox(
        eventType: String,
        aggregateType: String,
        aggregateId: String,
        payload: JsonNode,
    )
}
