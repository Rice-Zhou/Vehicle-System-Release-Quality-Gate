package com.ricezhou.vsrqg.issue.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.issue.application.IssueMappingProfileRecord
import com.ricezhou.vsrqg.issue.application.IssueMappingProfileRepository
import com.ricezhou.vsrqg.issue.application.IssueSourceRecord
import com.ricezhou.vsrqg.shared.adapter.toJdbcTimestamp
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class JdbcIssueMappingProfileRepository(
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
) : IssueMappingProfileRepository {
    override fun findSource(sourceId: String): IssueSourceRecord? = source(sourceId, lock = false)

    override fun lockSource(sourceId: String): IssueSourceRecord? = source(sourceId, lock = true)

    override fun insert(profile: IssueMappingProfileRecord) {
        val inserted = jdbc.sql(
            """
            INSERT INTO issue_mapping_profile(
              id, project_id, source_id, schema_version, mapping_version,
              definition, created_by, created_at
            ) VALUES (
              :id, :projectId, :sourceId, :schemaVersion, :mappingVersion,
              CAST(:definition AS jsonb), :createdBy, :createdAt
            )
            ON CONFLICT (source_id, mapping_version) DO NOTHING
            """.trimIndent(),
        )
            .param("id", profile.id)
            .param("projectId", profile.projectId)
            .param("sourceId", profile.sourceId)
            .param("schemaVersion", profile.schemaVersion)
            .param("mappingVersion", profile.mappingVersion)
            .param("definition", profile.definition.toString())
            .param("createdBy", profile.createdBy)
            .param("createdAt", profile.createdAt.toJdbcTimestamp())
            .update()
        if (inserted == 0) verifyExisting(profile)
    }

    override fun activate(
        sourceId: String,
        adapterVersion: String,
        mappingVersion: String,
        activatedAt: Instant,
    ) {
        val updated = jdbc.sql(
            """
            UPDATE issue_source
            SET adapter_version = :adapterVersion,
                mapping_version = :mappingVersion,
                updated_at = :activatedAt
            WHERE id = :sourceId
            """.trimIndent(),
        )
            .param("adapterVersion", adapterVersion)
            .param("mappingVersion", mappingVersion)
            .param("activatedAt", activatedAt.toJdbcTimestamp())
            .param("sourceId", sourceId)
            .update()
        check(updated == 1) { "Issue source activation did not affect exactly one row" }
    }

    override fun find(sourceId: String, mappingVersion: String): IssueMappingProfileRecord? = jdbc.sql(
        """
        SELECT id, project_id, source_id, schema_version, mapping_version,
               definition::text AS definition, created_by, created_at
        FROM issue_mapping_profile
        WHERE source_id = :sourceId AND mapping_version = :mappingVersion
        """.trimIndent(),
    )
        .param("sourceId", sourceId)
        .param("mappingVersion", mappingVersion)
        .query(::mapProfile)
        .optional()
        .orElse(null)

    private fun source(sourceId: String, lock: Boolean): IssueSourceRecord? {
        val suffix = if (lock) " FOR UPDATE" else ""
        return jdbc.sql(
            """
            SELECT id, project_id, source_type, adapter_version, mapping_version, enabled
            FROM issue_source
            WHERE id = :sourceId$suffix
            """.trimIndent(),
        )
            .param("sourceId", sourceId)
            .query { resultSet, _ ->
                IssueSourceRecord(
                    id = resultSet.getString("id"),
                    projectId = resultSet.getString("project_id"),
                    sourceType = resultSet.getString("source_type"),
                    adapterVersion = resultSet.getString("adapter_version"),
                    mappingVersion = resultSet.getString("mapping_version"),
                    enabled = resultSet.getBoolean("enabled"),
                )
            }
            .optional()
            .orElse(null)
    }

    private fun mapProfile(resultSet: ResultSet, @Suppress("UNUSED_PARAMETER") rowNumber: Int) =
        IssueMappingProfileRecord(
            id = resultSet.getString("id"),
            projectId = resultSet.getString("project_id"),
            sourceId = resultSet.getString("source_id"),
            schemaVersion = resultSet.getString("schema_version"),
            mappingVersion = resultSet.getString("mapping_version"),
            definition = objectMapper.readTree(resultSet.getString("definition")).deepCopy<JsonNode>(),
            createdBy = resultSet.getString("created_by"),
            createdAt = resultSet.getTimestamp("created_at").toInstant(),
        )

    private fun verifyExisting(expected: IssueMappingProfileRecord) {
        val existing = find(expected.sourceId, expected.mappingVersion) ?: throw profileConflict()
        if (
            existing.projectId != expected.projectId ||
            existing.sourceId != expected.sourceId ||
            existing.schemaVersion != expected.schemaVersion ||
            existing.definition != expected.definition
        ) {
            throw profileConflict()
        }
    }

    private fun profileConflict() = ResourceConflict(
        code = "MAPPING_PROFILE_VERSION_CONFLICT",
        resourceTitle = "Mapping profile version conflicts",
        detail = "The mapping profile version is already bound to different authoritative content",
    )
}
