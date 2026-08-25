package com.ricezhou.vsrqg.release.adapter

import com.ricezhou.vsrqg.release.application.ReleaseRepository
import com.ricezhou.vsrqg.release.domain.Release
import com.ricezhou.vsrqg.release.domain.ReleaseStateHistory
import com.ricezhou.vsrqg.release.domain.ReleaseStatus
import com.ricezhou.vsrqg.shared.adapter.toJdbcTimestamp
import java.time.OffsetDateTime
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class JdbcReleaseRepository(
    private val jdbc: JdbcClient,
) : ReleaseRepository {
    override fun insert(release: Release) {
        val inserted = jdbc.sql(
            """
            INSERT INTO release_record(
              id, project_id, vehicle, platform, system_version, build_id,
              status, locked_manifest_id, row_version, created_at, updated_at
            ) VALUES (
              :id, :projectId, :vehicle, :platform, :systemVersion, :buildId,
              :status, :lockedManifestId, :rowVersion, :createdAt, :updatedAt
            )
            """.trimIndent(),
        )
            .param("id", release.id)
            .param("projectId", release.projectId)
            .param("vehicle", release.vehicle)
            .param("platform", release.platform)
            .param("systemVersion", release.systemVersion)
            .param("buildId", release.declaredBuildId)
            .param("status", release.status.name)
            .param("lockedManifestId", release.lockedManifestId)
            .param("rowVersion", release.version)
            .param("createdAt", release.createdAt.toJdbcTimestamp())
            .param("updatedAt", release.createdAt.toJdbcTimestamp())
            .update()
        check(inserted == 1) { "Release insert did not affect exactly one record" }
    }

    override fun appendStateHistory(history: ReleaseStateHistory) {
        val inserted = jdbc.sql(
            """
            INSERT INTO release_state_history(
              id, release_id, previous_status, new_status, actor_id,
              reason, occurred_at, created_at
            ) VALUES (
              :id, :releaseId, :previousStatus, :newStatus, :actorId,
              :reason, :occurredAt, :createdAt
            )
            """.trimIndent(),
        )
            .param("id", history.id)
            .param("releaseId", history.releaseId)
            .param("previousStatus", history.previousStatus?.name)
            .param("newStatus", history.newStatus.name)
            .param("actorId", history.actorId)
            .param("reason", history.reason)
            .param("occurredAt", history.occurredAt.toJdbcTimestamp())
            .param("createdAt", history.occurredAt.toJdbcTimestamp())
            .update()
        check(inserted == 1) { "Release state history insert did not affect exactly one record" }
    }

    override fun find(id: String): Release? = jdbc.sql(
        """
        SELECT id, project_id, vehicle, platform, system_version, build_id,
               status, locked_manifest_id, row_version, created_at
        FROM release_record
        WHERE id = :id
        """.trimIndent(),
    )
        .param("id", id)
        .query { resultSet, _ ->
            Release(
                id = resultSet.getString("id"),
                projectId = resultSet.getString("project_id"),
                vehicle = resultSet.getString("vehicle"),
                platform = resultSet.getString("platform"),
                systemVersion = resultSet.getString("system_version"),
                declaredBuildId = resultSet.getString("build_id"),
                status = ReleaseStatus.valueOf(resultSet.getString("status")),
                lockedManifestId = resultSet.getString("locked_manifest_id"),
                createdAt = resultSet.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                version = resultSet.getLong("row_version"),
            )
        }
        .optional()
        .orElse(null)
}
