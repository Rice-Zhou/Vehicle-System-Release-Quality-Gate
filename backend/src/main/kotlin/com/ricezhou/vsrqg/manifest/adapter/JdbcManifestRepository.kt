package com.ricezhou.vsrqg.manifest.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.manifest.application.ArtifactRecord
import com.ricezhou.vsrqg.manifest.application.ManifestRelease
import com.ricezhou.vsrqg.manifest.application.ManifestRepository
import com.ricezhou.vsrqg.manifest.application.ManifestRevisionRecord
import com.ricezhou.vsrqg.manifest.application.ManifestState
import com.ricezhou.vsrqg.manifest.application.RegisterManifestResult
import com.ricezhou.vsrqg.manifest.application.ValidationReport
import com.ricezhou.vsrqg.manifest.application.ValidationStatus
import com.ricezhou.vsrqg.shared.adapter.toJdbcTimestamp
import java.time.Instant
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class JdbcManifestRepository(
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
) : ManifestRepository {
    override fun lockRelease(releaseId: String): ManifestRelease? = jdbc.sql(
        """
        SELECT r.id, r.project_id, p.project_key, r.vehicle, r.platform,
               r.system_version, r.build_id, r.status
        FROM release_record r
        JOIN project p ON p.id = r.project_id
        WHERE r.id = :releaseId
        FOR UPDATE OF r
        """.trimIndent(),
    )
        .param("releaseId", releaseId)
        .query { resultSet, _ ->
            ManifestRelease(
                id = resultSet.getString("id"),
                projectId = resultSet.getString("project_id"),
                projectReference = resultSet.getString("project_key"),
                vehicle = resultSet.getString("vehicle"),
                platform = resultSet.getString("platform"),
                systemVersion = resultSet.getString("system_version"),
                buildId = resultSet.getString("build_id"),
                status = resultSet.getString("status"),
            )
        }
        .optional()
        .orElse(null)

    override fun findByDigest(releaseId: String, contentDigest: String): RegisterManifestResult? = find(
        "m.release_id = :releaseId AND m.content_digest = :lookup",
        releaseId,
        contentDigest,
    )

    override fun findById(releaseId: String, manifestId: String): RegisterManifestResult? = find(
        "m.release_id = :releaseId AND m.id = :lookup",
        releaseId,
        manifestId,
    )

    private fun find(predicate: String, releaseId: String, lookup: String): RegisterManifestResult? = jdbc.sql(
        """
        SELECT m.id, m.revision, m.state, m.content_digest, v.report::text AS report
        FROM manifest_revision m
        JOIN manifest_validation v ON v.manifest_id = m.id
        WHERE $predicate
        """.trimIndent(),
    )
        .param("releaseId", releaseId)
        .param("lookup", lookup)
        .query { resultSet, _ ->
            RegisterManifestResult(
                manifestId = resultSet.getString("id"),
                revision = resultSet.getInt("revision"),
                state = ManifestState.valueOf(resultSet.getString("state")),
                contentDigest = resultSet.getString("content_digest"),
                validation = objectMapper.readValue(
                    resultSet.getString("report"),
                    ValidationReport::class.java,
                ),
            )
        }
        .optional()
        .orElse(null)

    override fun nextRevision(releaseId: String): Int = jdbc.sql(
        "SELECT COALESCE(MAX(revision), 0) + 1 FROM manifest_revision WHERE release_id = :releaseId",
    )
        .param("releaseId", releaseId)
        .query(Int::class.java)
        .single()

    override fun insertRevision(revision: ManifestRevisionRecord) {
        val inserted = jdbc.sql(
            """
            INSERT INTO manifest_revision(
              id, release_id, revision, content_digest, raw_manifest,
              canonical_bytes, schema_version, state, row_version, created_at, updated_at
            ) VALUES (
              :id, :releaseId, :revision, :contentDigest, CAST(:rawManifest AS jsonb),
              :canonicalBytes, :schemaVersion, :state, 0, :createdAt, :updatedAt
            )
            """.trimIndent(),
        )
            .param("id", revision.id)
            .param("releaseId", revision.releaseId)
            .param("revision", revision.revision)
            .param("contentDigest", revision.contentDigest)
            .param("rawManifest", revision.rawManifest)
            .param("canonicalBytes", revision.canonicalBytes)
            .param("schemaVersion", revision.schemaVersion)
            .param("state", revision.state.name)
            .param("createdAt", revision.createdAt.toJdbcTimestamp())
            .param("updatedAt", revision.createdAt.toJdbcTimestamp())
            .update()
        check(inserted == 1) { "Manifest revision insert did not affect exactly one record" }
    }

    override fun findOrInsertArtifact(artifact: ArtifactRecord): String {
        jdbc.sql(
            """
            INSERT INTO artifact(
              id, identity_digest, artifact_type, locator,
              checksum_algorithm, checksum_value, created_at
            ) VALUES (
              :id, :identityDigest, :artifactType, CAST(:locator AS jsonb),
              :checksumAlgorithm, :checksumValue, :createdAt
            )
            ON CONFLICT (identity_digest) DO NOTHING
            """.trimIndent(),
        )
            .param("id", artifact.id)
            .param("identityDigest", artifact.identityDigest)
            .param("artifactType", artifact.type)
            .param("locator", artifact.locator.toString())
            .param("checksumAlgorithm", artifact.checksumAlgorithm)
            .param("checksumValue", artifact.checksumValue)
            .param("createdAt", artifact.createdAt.toJdbcTimestamp())
            .update()
        return jdbc.sql("SELECT id FROM artifact WHERE identity_digest = :identityDigest")
            .param("identityDigest", artifact.identityDigest)
            .query(String::class.java)
            .single()
    }

    override fun linkArtifact(
        manifestId: String,
        artifactId: String,
        ordinal: Int,
        required: Boolean,
        createdAt: Instant,
    ) {
        val inserted = jdbc.sql(
            """
            INSERT INTO manifest_artifact(manifest_id, artifact_id, ordinal, required, created_at)
            VALUES (:manifestId, :artifactId, :ordinal, :required, :createdAt)
            """.trimIndent(),
        )
            .param("manifestId", manifestId)
            .param("artifactId", artifactId)
            .param("ordinal", ordinal)
            .param("required", required)
            .param("createdAt", createdAt.toJdbcTimestamp())
            .update()
        check(inserted == 1) { "Manifest artifact link insert did not affect exactly one record" }
    }

    override fun insertValidation(report: ValidationReport) {
        val persistedStatus = when (report.status) {
            ValidationStatus.VALID -> "VALID"
            ValidationStatus.FAILED -> "INVALID"
            ValidationStatus.INCOMPLETE -> "INCOMPLETE"
        }
        val inserted = jdbc.sql(
            """
            INSERT INTO manifest_validation(
              id, manifest_id, status, content_digest, schema_version,
              validator_version, report, validated_at, created_at
            ) VALUES (
              :id, :manifestId, :status, :contentDigest, :schemaVersion,
              :validatorVersion, CAST(:report AS jsonb), :validatedAt, :createdAt
            )
            """.trimIndent(),
        )
            .param("id", report.validationId)
            .param("manifestId", report.manifestId)
            .param("status", persistedStatus)
            .param("contentDigest", report.contentDigest)
            .param("schemaVersion", report.schemaVersion)
            .param("validatorVersion", report.validatorVersion)
            .param("report", objectMapper.writeValueAsString(report))
            .param("validatedAt", report.validatedAt.toJdbcTimestamp())
            .param("createdAt", report.validatedAt.toJdbcTimestamp())
            .update()
        check(inserted == 1) { "Manifest validation insert did not affect exactly one record" }
    }

    override fun finalizeRevision(manifestId: String, state: ManifestState, updatedAt: Instant) {
        require(state != ManifestState.DRAFT) { "Final manifest state cannot be DRAFT" }
        val updated = jdbc.sql(
            """
            UPDATE manifest_revision
            SET state = :state, row_version = row_version + 1, updated_at = :updatedAt
            WHERE id = :manifestId AND state = 'DRAFT'
            """.trimIndent(),
        )
            .param("state", state.name)
            .param("updatedAt", updatedAt.toJdbcTimestamp())
            .param("manifestId", manifestId)
            .update()
        check(updated == 1) { "Manifest finalization did not affect exactly one draft record" }
    }

    override fun markReleaseRegistered(releaseId: String, updatedAt: Instant): Boolean = jdbc.sql(
        """
        UPDATE release_record
        SET status = 'REGISTERED', row_version = row_version + 1, updated_at = :updatedAt
        WHERE id = :releaseId AND status = 'DRAFT'
        """.trimIndent(),
    )
        .param("updatedAt", updatedAt.toJdbcTimestamp())
        .param("releaseId", releaseId)
        .update() == 1

    override fun appendReleaseHistory(
        id: String,
        releaseId: String,
        actorId: String,
        occurredAt: Instant,
    ) {
        val inserted = jdbc.sql(
            """
            INSERT INTO release_state_history(
              id, release_id, previous_status, new_status, actor_id,
              reason, occurred_at, created_at
            ) VALUES (
              :id, :releaseId, 'DRAFT', 'REGISTERED', :actorId,
              'Manifest revision registered', :occurredAt, :createdAt
            )
            """.trimIndent(),
        )
            .param("id", id)
            .param("releaseId", releaseId)
            .param("actorId", actorId)
            .param("occurredAt", occurredAt.toJdbcTimestamp())
            .param("createdAt", occurredAt.toJdbcTimestamp())
            .update()
        check(inserted == 1) { "Release registration history insert did not affect exactly one record" }
    }
}
