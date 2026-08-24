package com.ricezhou.vsrqg.shared.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.application.IdempotencyConflict
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class JdbcIdempotentExecutor(
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
    private val idGenerator: IdGenerator,
    private val timeProvider: TimeProvider,
    @param:Value("\${vsrqg.idempotency.retention}") private val recordRetention: Duration,
) : IdempotentExecutor {
    @Transactional
    override fun <T : Any> execute(
        scope: String,
        principalId: String,
        key: String,
        requestDigest: String,
        responseType: Class<T>,
        action: () -> T,
    ): T {
        validateInput(scope, principalId, key, requestDigest)
        val now = timeProvider.now()
        val inserted = jdbc.sql(
            """
            INSERT INTO idempotency_record(
              id, scope, principal_id, idempotency_key, request_hash,
              response_status, expires_at, created_at
            ) VALUES (
              :id, :scope, :principalId, :key, :requestHash,
              :pendingStatus, :expiresAt, :createdAt
            )
            ON CONFLICT (scope, principal_id, idempotency_key) DO NOTHING
            """.trimIndent(),
        )
            .param("id", idGenerator.nextId("idem_"))
            .param("scope", scope)
            .param("principalId", principalId)
            .param("key", key)
            .param("requestHash", requestDigest)
            .param("pendingStatus", PENDING_STATUS)
            .param("expiresAt", now.plus(recordRetention).toJdbcTimestamp())
            .param("createdAt", now.toJdbcTimestamp())
            .update()

        val record = lockRecord(scope, principalId, key)
        if (record.requestHash != requestDigest) {
            throw IdempotencyConflict(scope)
        }
        if (inserted == 0) {
            check(record.responseStatus != PENDING_STATUS && record.responseBody != null) {
                "Committed idempotency record has no replayable response"
            }
            return objectMapper.readValue(record.responseBody, responseType)
        }

        val response = action()
        val responseBody = objectMapper.writeValueAsString(response)
        val updated = jdbc.sql(
            """
            UPDATE idempotency_record
            SET response_status = :responseStatus,
                response_body = CAST(:responseBody AS jsonb)
            WHERE scope = :scope
              AND principal_id = :principalId
              AND idempotency_key = :key
            """.trimIndent(),
        )
            .param("responseStatus", SUCCESS_STATUS)
            .param("responseBody", responseBody)
            .param("scope", scope)
            .param("principalId", principalId)
            .param("key", key)
            .update()
        check(updated == 1) { "Idempotency response update did not affect exactly one record" }
        return response
    }

    private fun lockRecord(scope: String, principalId: String, key: String): StoredResponse = jdbc.sql(
        """
        SELECT request_hash, response_status, response_body::text AS response_body
        FROM idempotency_record
        WHERE scope = :scope
          AND principal_id = :principalId
          AND idempotency_key = :key
        FOR UPDATE
        """.trimIndent(),
    )
        .param("scope", scope)
        .param("principalId", principalId)
        .param("key", key)
        .query { resultSet, _ ->
            StoredResponse(
                requestHash = resultSet.getString("request_hash"),
                responseStatus = resultSet.getInt("response_status"),
                responseBody = resultSet.getString("response_body"),
            )
        }
        .single()

    private fun validateInput(scope: String, principalId: String, key: String, requestDigest: String) {
        require(scope.isNotBlank() && scope.length <= MAX_SCOPE_LENGTH) { "Invalid idempotency scope" }
        require(principalId.isNotBlank() && principalId.length <= MAX_PRINCIPAL_ID_LENGTH) {
            "Invalid idempotency principal"
        }
        require(key.isNotBlank() && key.length <= MAX_KEY_LENGTH) { "Invalid idempotency key" }
        require(REQUEST_DIGEST.matches(requestDigest)) { "Invalid request digest" }
    }

    private data class StoredResponse(
        val requestHash: String,
        val responseStatus: Int,
        val responseBody: String?,
    )

    private companion object {
        val REQUEST_DIGEST = Regex("^sha256:[0-9a-f]{64}$")
        const val MAX_SCOPE_LENGTH = 80
        const val MAX_PRINCIPAL_ID_LENGTH = 40
        const val MAX_KEY_LENGTH = 255
        const val PENDING_STATUS = 102
        const val SUCCESS_STATUS = 200
    }
}
