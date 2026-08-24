package com.ricezhou.vsrqg.shared.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.time.TimeProvider
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class JdbcGovernanceStore(
    private val jdbc: JdbcClient,
    private val idGenerator: IdGenerator,
    private val timeProvider: TimeProvider,
) : GovernanceStore {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun appendAudit(
        projectId: String,
        actorId: String,
        action: String,
        resourceType: String,
        resourceId: String,
        requestId: String,
        reason: String?,
        beforeState: JsonNode?,
        afterState: JsonNode?,
    ) {
        val now = timeProvider.now()
        jdbc.sql(
            """
            INSERT INTO audit_event(
              id, event_id, project_id, actor_id, action, aggregate_type,
              aggregate_id, reason, before_state, after_state, correlation_id,
              occurred_at, created_at
            ) VALUES (
              :id, :eventId, :projectId, :actorId, :action, :aggregateType,
              :aggregateId, :reason, CAST(:beforeState AS jsonb), CAST(:afterState AS jsonb),
              :correlationId, :occurredAt, :createdAt
            )
            """.trimIndent(),
        )
            .param("id", idGenerator.nextId("aud_"))
            .param("eventId", idGenerator.nextId("evt_"))
            .param("projectId", projectId)
            .param("actorId", actorId)
            .param("action", action)
            .param("aggregateType", resourceType)
            .param("aggregateId", resourceId)
            .param("reason", reason)
            .param("beforeState", beforeState?.toString())
            .param("afterState", afterState?.toString())
            .param("correlationId", requestId)
            .param("occurredAt", now)
            .param("createdAt", now)
            .update()
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun appendOutbox(
        eventType: String,
        aggregateType: String,
        aggregateId: String,
        payload: JsonNode,
    ) {
        val now = timeProvider.now()
        jdbc.sql(
            """
            INSERT INTO outbox_event(
              id, event_id, aggregate_type, aggregate_id, event_type, payload, created_at
            ) VALUES (
              :id, :eventId, :aggregateType, :aggregateId, :eventType,
              CAST(:payload AS jsonb), :createdAt
            )
            """.trimIndent(),
        )
            .param("id", idGenerator.nextId("out_"))
            .param("eventId", idGenerator.nextId("evt_"))
            .param("aggregateType", aggregateType)
            .param("aggregateId", aggregateId)
            .param("eventType", eventType)
            .param("payload", payload.toString())
            .param("createdAt", now)
            .update()
    }
}
