package com.ricezhou.vsrqg.issue.adapter

import com.ricezhou.vsrqg.issue.application.IssueSnapshotCandidate
import com.ricezhou.vsrqg.issue.application.IssueSnapshotCanonicalizer
import com.ricezhou.vsrqg.issue.application.IssueSnapshotContext
import com.ricezhou.vsrqg.issue.application.IssueSnapshotRepository
import com.ricezhou.vsrqg.issue.application.MaterializedIssueSnapshot
import com.ricezhou.vsrqg.issue.application.SNAPSHOT_CANONICALIZATION_VERSION
import com.ricezhou.vsrqg.issue.application.SnapshotObservation
import com.ricezhou.vsrqg.issue.application.SuccessfulFullIssueSyncRun
import com.ricezhou.vsrqg.issue.application.orderedObservations
import com.ricezhou.vsrqg.issue.application.selectedObservations
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

class JdbcIssueSnapshotRepository(
    private val jdbc: JdbcClient,
    private val canonicalizer: IssueSnapshotCanonicalizer,
) : IssueSnapshotRepository {
    override fun findContext(releaseId: String, sourceId: String): IssueSnapshotContext? =
        context(releaseId, sourceId, lock = false)

    override fun lockContext(releaseId: String, sourceId: String): IssueSnapshotContext? =
        context(releaseId, sourceId, lock = true)

    override fun findLatestSuccessfulFullRun(
        projectId: String,
        sourceId: String,
    ): SuccessfulFullIssueSyncRun? = jdbc.sql(
        """
        SELECT id, project_id, source_id, source_watermark, adapter_version, mapping_version,
               result_set_mode, filter_reference, issue_count, completed_at
        FROM issue_sync_run
        WHERE source_id = :sourceId AND project_id = :projectId
          AND status = 'SUCCEEDED' AND result_set_mode = 'FULL'
        ORDER BY completed_at DESC, id DESC
        LIMIT 1
        """.trimIndent(),
    )
        .param("sourceId", sourceId)
        .param("projectId", projectId)
        .query(::mapSuccessfulFullRun)
        .optional()
        .orElse(null)

    override fun findExisting(
        releaseId: String,
        syncRunId: String,
        filterReference: String,
    ): MaterializedIssueSnapshot? {
        val snapshotId = jdbc.sql(
            """
            SELECT id FROM release_issue_snapshot
            WHERE release_id = :releaseId
              AND sync_run_id = :syncRunId
              AND filter_reference = :filterReference
            """.trimIndent(),
        )
            .param("releaseId", releaseId)
            .param("syncRunId", syncRunId)
            .param("filterReference", filterReference)
            .query(String::class.java)
            .optional()
            .orElse(null) ?: return null
        return read(snapshotId)
    }

    override fun nextSnapshotVersion(releaseId: String): Int = jdbc.sql(
        "SELECT COALESCE(MAX(snapshot_version), 0) + 1 FROM release_issue_snapshot WHERE release_id = :releaseId",
    )
        .param("releaseId", releaseId)
        .query(Int::class.java)
        .single()

    override fun loadObservations(run: SuccessfulFullIssueSyncRun): List<SnapshotObservation> =
        loadAuthoritativeObservations(
            MembershipExpectation(
                runId = run.id,
                projectId = run.projectId,
                sourceId = run.sourceId,
                sourceWatermark = run.sourceWatermark,
                adapterVersion = run.adapterVersion,
                mappingVersion = run.mappingVersion,
                filterReference = run.filterReference,
                issueCount = run.issueCount,
            ),
        )

    private fun loadAuthoritativeObservations(expectation: MembershipExpectation): List<SnapshotObservation> {
        val metadata = jdbc.sql(
            """
            SELECT source_watermark, adapter_version, mapping_version, filter_reference, issue_count
            FROM issue_sync_run
            WHERE id = :syncRunId AND project_id = :projectId AND source_id = :sourceId
              AND status = 'SUCCEEDED' AND result_set_mode = 'FULL'
            """.trimIndent(),
        )
            .param("syncRunId", expectation.runId)
            .param("projectId", expectation.projectId)
            .param("sourceId", expectation.sourceId)
            .query { rs, _ ->
                MembershipMetadata(
                    sourceWatermark = rs.getString("source_watermark"),
                    adapterVersion = rs.getString("adapter_version"),
                    mappingVersion = rs.getString("mapping_version"),
                    filterReference = rs.getString("filter_reference"),
                    issueCount = rs.getInt("issue_count"),
                )
            }
            .optional()
            .orElse(null)
        if (metadata == null || metadata != expectation.metadata()) {
            throw DataIntegrityViolationException("Snapshot observation membership does not match authoritative sync run")
        }
        val observations = jdbc.sql(
            """
            SELECT issue.id, issue.source_issue_id, issue.title, issue.severity, issue.status,
                   issue.raw_status_token, issue.source_version, issue.source_reference,
                   observed.observed_at, issue.mapping_version, issue.tombstone, issue.fact_digest,
                   issue.fact_digest_version
            FROM issue_sync_run_item observed
            JOIN normalized_issue issue
              ON issue.id = observed.issue_id
             AND issue.source_id = observed.source_id
             AND issue.project_id = observed.project_id
             AND issue.source_issue_id = observed.source_issue_id
            WHERE observed.sync_run_id = :syncRunId
              AND observed.project_id = :projectId
              AND observed.source_id = :sourceId
            ORDER BY observed.source_id, observed.source_issue_id, observed.issue_id
            """.trimIndent(),
        )
            .param("syncRunId", expectation.runId)
            .param("projectId", expectation.projectId)
            .param("sourceId", expectation.sourceId)
            .query { rs, _ ->
                val factDigestVersion = rs.getString("fact_digest_version")
                if (factDigestVersion != null && factDigestVersion != NORMALIZED_FACT_DIGEST_VERSION) {
                    throw DataIntegrityViolationException("Snapshot observation has unsupported fact digest version")
                }
                mapObservation(rs)
            }
            .list()
        if (observations.size != expectation.issueCount ||
            observations.any { it.mappingVersion != expectation.mappingVersion }
        ) {
            throw DataIntegrityViolationException(
                "Snapshot observation membership does not match authoritative sync run",
            )
        }
        return observations
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun insert(snapshot: MaterializedIssueSnapshot) {
        val candidate = snapshot.candidate
        if (candidate.observations.size != candidate.observedCount) {
            throw DataIntegrityViolationException("Snapshot insert requires the complete observation membership")
        }
        val authoritative = loadAuthoritativeObservations(MembershipExpectation.from(candidate))
        if (candidate.orderedObservations() != authoritative) {
            throw DataIntegrityViolationException(
                "Snapshot observation membership does not match authoritative sync run",
            )
        }
        val verified = canonicalizer.canonicalize(candidate)
        if (!verified.bytes.contentEquals(snapshot.canonical.bytes) || verified.digest != snapshot.canonical.digest) {
            throw DataIntegrityViolationException("Snapshot canonical content does not match candidate")
        }
        jdbc.sql(
            """
            INSERT INTO release_issue_snapshot(
              id, project_id, release_id, sync_run_id, snapshot_version, filter_reference,
              source_id, source_watermark, adapter_version, mapping_version,
              canonicalization_version, age_policy_version, observed_count,
              tombstone_count, selected_count, content_digest, created_at
            ) VALUES (
              :id, :projectId, :releaseId, :syncRunId, :snapshotVersion, :filterReference,
              :sourceId, :sourceWatermark, :adapterVersion, :mappingVersion,
              :canonicalizationVersion, :agePolicyVersion, :observedCount,
              :tombstoneCount, :selectedCount, :contentDigest, :createdAt
            )
            """.trimIndent(),
        )
            .param("id", snapshot.snapshotId)
            .param("projectId", candidate.projectId)
            .param("releaseId", candidate.releaseId)
            .param("syncRunId", candidate.syncRunId)
            .param("snapshotVersion", candidate.snapshotVersion)
            .param("filterReference", candidate.filterReference)
            .param("sourceId", candidate.sourceId)
            .param("sourceWatermark", candidate.sourceWatermark)
            .param("adapterVersion", candidate.adapterVersion)
            .param("mappingVersion", candidate.mappingVersion)
            .param("canonicalizationVersion", SNAPSHOT_CANONICALIZATION_VERSION)
            .param("agePolicyVersion", candidate.agePolicyVersion)
            .param("observedCount", candidate.observedCount)
            .param("tombstoneCount", candidate.tombstoneCount)
            .param("selectedCount", candidate.selectedCount)
            .param("contentDigest", verified.digest)
            .param("createdAt", snapshot.createdAt.atOffset(ZoneOffset.UTC))
            .update()
            .requireOne()
        candidate.selectedObservations().forEachIndexed { ordinal, observation ->
            insertItem(snapshot, ordinal, observation)
        }
        val persisted = read(snapshot.snapshotId)
            ?: throw DataIntegrityViolationException("Inserted snapshot could not be read back")
        if (!persisted.canonical.bytes.contentEquals(verified.bytes) || persisted.canonical.digest != verified.digest) {
            throw DataIntegrityViolationException("Inserted snapshot failed canonical read-back verification")
        }
    }

    override fun read(snapshotId: String): MaterializedIssueSnapshot? {
        val header = jdbc.sql(
            """
            SELECT id, project_id, release_id, sync_run_id, snapshot_version, filter_reference,
                   source_id, source_watermark, adapter_version, mapping_version,
                   canonicalization_version, age_policy_version, observed_count,
                   tombstone_count, selected_count, content_digest, created_at
            FROM release_issue_snapshot
            WHERE id = :snapshotId
            """.trimIndent(),
        )
            .param("snapshotId", snapshotId)
            .query(::mapHeader)
            .optional()
            .orElse(null) ?: return null
        if (header.canonicalizationVersion != SNAPSHOT_CANONICALIZATION_VERSION) {
            throw DataIntegrityViolationException("Snapshot canonicalization version is unsupported")
        }
        val observations = jdbc.sql(
            """
            SELECT ordinal, issue_id AS id, source_issue_id, title, severity, status,
                   raw_status_token, source_version, source_reference, observed_at,
                   mapping_version, false AS tombstone, fact_digest
            FROM release_issue_snapshot_item
            WHERE snapshot_id = :snapshotId AND project_id = :projectId
            ORDER BY ordinal
            """.trimIndent(),
        )
            .param("snapshotId", snapshotId)
            .param("projectId", header.projectId)
            .query { rs, row ->
                if (rs.getInt("ordinal") != row) {
                    throw DataIntegrityViolationException("Snapshot item ordinals are not contiguous")
                }
                mapObservation(rs)
            }
            .list()
        val candidate = IssueSnapshotCandidate(
            projectId = header.projectId,
            releaseId = header.releaseId,
            snapshotVersion = header.snapshotVersion,
            syncRunId = header.syncRunId,
            sourceId = header.sourceId,
            sourceWatermark = header.sourceWatermark,
            adapterVersion = header.adapterVersion,
            mappingVersion = header.mappingVersion,
            filterReference = header.filterReference,
            agePolicyVersion = header.agePolicyVersion,
            observations = observations,
            observedCount = header.observedCount,
            tombstoneCount = header.tombstoneCount,
            selectedCount = header.selectedCount,
        )
        val canonical = canonicalizer.canonicalize(candidate)
        if (canonical.digest != header.contentDigest) {
            throw DataIntegrityViolationException("Snapshot content digest failed read-back verification")
        }
        return MaterializedIssueSnapshot(header.id, candidate, canonical, header.createdAt)
    }

    private fun context(releaseId: String, sourceId: String, lock: Boolean): IssueSnapshotContext? {
        val release = jdbc.sql(
            """
            SELECT id, project_id, locked_manifest_id
            FROM release_record
            WHERE id = :releaseId
            ${if (lock) "FOR UPDATE" else ""}
            """.trimIndent(),
        )
            .param("releaseId", releaseId)
            .query { rs, _ -> ReleaseContext(rs.getString("id"), rs.getString("project_id"), rs.getString("locked_manifest_id")) }
            .optional()
            .orElse(null) ?: return null
        val resolvedSourceId = jdbc.sql(
            """
            SELECT id FROM issue_source
            WHERE id = :sourceId AND project_id = :projectId
            ${if (lock) "FOR UPDATE" else ""}
            """.trimIndent(),
        )
            .param("sourceId", sourceId)
            .param("projectId", release.projectId)
            .query(String::class.java)
            .optional()
            .orElse(null) ?: return null
        return IssueSnapshotContext(release.projectId, release.id, release.lockedManifestId, resolvedSourceId)
    }

    private fun insertItem(snapshot: MaterializedIssueSnapshot, ordinal: Int, observation: SnapshotObservation) {
        jdbc.sql(
            """
            INSERT INTO release_issue_snapshot_item(
              snapshot_id, ordinal, project_id, issue_id, source_issue_id, title,
              severity, status, raw_status_token, source_version, source_reference,
              observed_at, mapping_version, fact_digest, created_at
            ) VALUES (
              :snapshotId, :ordinal, :projectId, :issueId, :sourceIssueId, :title,
              :severity, :status, :rawStatusToken, :sourceVersion, :sourceReference,
              :observedAt, :mappingVersion, :factDigest, :createdAt
            )
            """.trimIndent(),
        )
            .param("snapshotId", snapshot.snapshotId)
            .param("ordinal", ordinal)
            .param("projectId", snapshot.candidate.projectId)
            .param("issueId", observation.issueId)
            .param("sourceIssueId", observation.sourceIssueId)
            .param("title", observation.title)
            .param("severity", observation.severity.name)
            .param("status", observation.status.name)
            .param("rawStatusToken", observation.rawStatusToken)
            .param("sourceVersion", observation.sourceVersion)
            .param("sourceReference", observation.sourceReference)
            .param("observedAt", observation.observedAt.atOffset(ZoneOffset.UTC))
            .param("mappingVersion", observation.mappingVersion)
            .param("factDigest", observation.factDigest)
            .param("createdAt", snapshot.createdAt.atOffset(ZoneOffset.UTC))
            .update()
            .requireOne()
    }

    private fun mapSuccessfulFullRun(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) =
        SuccessfulFullIssueSyncRun(
            id = rs.getString("id"),
            projectId = rs.getString("project_id"),
            sourceId = rs.getString("source_id"),
            sourceWatermark = rs.getString("source_watermark")
                ?: throw DataIntegrityViolationException("Successful full sync is missing source watermark"),
            adapterVersion = rs.getString("adapter_version"),
            mappingVersion = rs.getString("mapping_version"),
            filterReference = rs.getString("filter_reference")
                ?: throw DataIntegrityViolationException("Successful full sync is missing filter reference"),
            issueCount = rs.getInt("issue_count"),
            completedAt = rs.getObject("completed_at", OffsetDateTime::class.java)?.toInstant()
                ?: throw DataIntegrityViolationException("Successful full sync is missing completion time"),
        )

    private fun mapObservation(rs: ResultSet) = SnapshotObservation(
        issueId = rs.getString("id"),
        sourceIssueId = rs.getString("source_issue_id"),
        title = rs.getString("title"),
        severity = IssueSeverity.valueOf(rs.getString("severity")),
        status = IssueStatus.valueOf(rs.getString("status")),
        rawStatusToken = rs.getString("raw_status_token"),
        sourceVersion = rs.getString("source_version"),
        sourceReference = rs.getString("source_reference"),
        observedAt = rs.getObject("observed_at", OffsetDateTime::class.java).toInstant(),
        mappingVersion = rs.getString("mapping_version"),
        tombstone = rs.getBoolean("tombstone"),
        factDigest = rs.getString("fact_digest"),
    )

    private fun mapHeader(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = SnapshotHeader(
        id = rs.getString("id"),
        projectId = rs.getString("project_id"),
        releaseId = rs.getString("release_id"),
        syncRunId = rs.getString("sync_run_id"),
        snapshotVersion = rs.getInt("snapshot_version"),
        filterReference = rs.getString("filter_reference"),
        sourceId = rs.getString("source_id"),
        sourceWatermark = rs.getString("source_watermark"),
        adapterVersion = rs.getString("adapter_version"),
        mappingVersion = rs.getString("mapping_version"),
        canonicalizationVersion = rs.getString("canonicalization_version"),
        agePolicyVersion = rs.getString("age_policy_version"),
        observedCount = rs.getInt("observed_count"),
        tombstoneCount = rs.getInt("tombstone_count"),
        selectedCount = rs.getInt("selected_count"),
        contentDigest = rs.getString("content_digest"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
    )

    private data class ReleaseContext(val id: String, val projectId: String, val lockedManifestId: String?)

    private data class MembershipExpectation(
        val runId: String,
        val projectId: String,
        val sourceId: String,
        val sourceWatermark: String,
        val adapterVersion: String,
        val mappingVersion: String,
        val filterReference: String,
        val issueCount: Int,
    ) {
        fun metadata() = MembershipMetadata(
            sourceWatermark = sourceWatermark,
            adapterVersion = adapterVersion,
            mappingVersion = mappingVersion,
            filterReference = filterReference,
            issueCount = issueCount,
        )

        companion object {
            fun from(candidate: IssueSnapshotCandidate) = MembershipExpectation(
                runId = candidate.syncRunId,
                projectId = candidate.projectId,
                sourceId = candidate.sourceId,
                sourceWatermark = candidate.sourceWatermark,
                adapterVersion = candidate.adapterVersion,
                mappingVersion = candidate.mappingVersion,
                filterReference = candidate.filterReference,
                issueCount = candidate.observedCount,
            )
        }
    }

    private data class MembershipMetadata(
        val sourceWatermark: String?,
        val adapterVersion: String,
        val mappingVersion: String,
        val filterReference: String?,
        val issueCount: Int,
    )

    private data class SnapshotHeader(
        val id: String,
        val projectId: String,
        val releaseId: String,
        val syncRunId: String,
        val snapshotVersion: Int,
        val filterReference: String,
        val sourceId: String,
        val sourceWatermark: String,
        val adapterVersion: String,
        val mappingVersion: String,
        val canonicalizationVersion: String,
        val agePolicyVersion: String,
        val observedCount: Int,
        val tombstoneCount: Int,
        val selectedCount: Int,
        val contentDigest: String,
        val createdAt: java.time.Instant,
    )

    private companion object {
        const val NORMALIZED_FACT_DIGEST_VERSION = "normalized-issue-facts/v1"
    }
}

private fun Int.requireOne() {
    if (this != 1) throw DataIntegrityViolationException("Snapshot write did not affect exactly one row")
}

@Configuration(proxyBeanMethods = false)
class IssueSnapshotPersistenceConfiguration {
    @Bean
    @ConditionalOnBean(JdbcClient::class)
    fun issueSnapshotRepository(
        jdbc: JdbcClient,
        canonicalizer: IssueSnapshotCanonicalizer,
    ): IssueSnapshotRepository = JdbcIssueSnapshotRepository(jdbc, canonicalizer)
}
