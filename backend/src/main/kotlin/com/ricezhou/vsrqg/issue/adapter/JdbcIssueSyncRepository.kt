package com.ricezhou.vsrqg.issue.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.issue.application.IssueSourceRecord
import com.ricezhou.vsrqg.issue.application.IssueSyncRepository
import com.ricezhou.vsrqg.issue.application.IssueSyncResultSetMode
import com.ricezhou.vsrqg.issue.application.IssueSyncRunRecord
import com.ricezhou.vsrqg.issue.application.IssueSyncStatus
import com.ricezhou.vsrqg.issue.application.QueuedIssueSync
import com.ricezhou.vsrqg.issue.domain.IssuePage
import com.ricezhou.vsrqg.issue.domain.NormalizedIssue
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
class JdbcIssueSyncRepository(
    private val jdbc: JdbcClient,
    private val idGenerator: IdGenerator,
    private val timeProvider: TimeProvider,
) : IssueSyncRepository {
    override fun findSource(sourceId: String): IssueSourceRecord? = source(sourceId, lock = false)

    override fun lockSource(sourceId: String): IssueSourceRecord? = source(sourceId, lock = true)

    override fun currentSuccessfulCursor(sourceId: String): String? = jdbc.sql(
        "SELECT cursor_value FROM issue_sync_cursor WHERE source_id = :sourceId",
    )
        .param("sourceId", sourceId)
        .query(String::class.java)
        .optional()
        .orElse(null)

    override fun insertRun(run: IssueSyncRunRecord) {
        require(run.resultSetMode != null && !run.filterReference.isNullOrBlank()) {
            "ISSUE_SYNC_RESULT_METADATA_REQUIRED"
        }
        jdbc.sql(
            """
            INSERT INTO issue_sync_run(
              id, project_id, source_id, sync_run_id, status, cursor_before, cursor_after,
              source_watermark, adapter_version, mapping_version, issue_count, warning_count,
              result_set_mode, filter_reference, diagnostic_code, created_at
            ) VALUES (
              :id, :projectId, :sourceId, :syncRunId, :status, :cursorBefore, :cursorAfter,
              :sourceWatermark, :adapterVersion, :mappingVersion, :issueCount, :warningCount,
              :resultSetMode, :filterReference, :diagnosticCode, :createdAt
            )
            """.trimIndent(),
        )
            .param("id", run.id)
            .param("projectId", run.projectId)
            .param("sourceId", run.sourceId)
            .param("syncRunId", run.id)
            .param("status", run.status.name)
            .param("cursorBefore", run.cursorBefore)
            .param("cursorAfter", run.cursorAfter)
            .param("sourceWatermark", run.sourceWatermark)
            .param("adapterVersion", run.adapterVersion)
            .param("mappingVersion", run.mappingVersion)
            .param("issueCount", run.issueCount)
            .param("warningCount", run.warningCount)
            .param("resultSetMode", run.resultSetMode?.name)
            .param("filterReference", run.filterReference)
            .param("diagnosticCode", run.diagnosticCode)
            .param("createdAt", run.createdAt.atOffset(java.time.ZoneOffset.UTC))
            .update()
    }

    override fun insertJob(
        jobId: String,
        projectId: String,
        idempotencyKey: String,
        payload: JsonNode,
        createdAt: Instant,
    ) {
        val timestamp = createdAt.atOffset(java.time.ZoneOffset.UTC)
        jdbc.sql(
            """
            INSERT INTO background_job(
              id, project_id, job_type, idempotency_key, status, payload,
              available_at, created_at, updated_at
            ) VALUES (
              :id, :projectId, :jobType, :idempotencyKey, 'QUEUED', CAST(:payload AS jsonb),
              :availableAt, :createdAt, :updatedAt
            )
            """.trimIndent(),
        )
            .param("id", jobId)
            .param("projectId", projectId)
            .param("jobType", JOB_TYPE)
            .param("idempotencyKey", idempotencyKey)
            .param("payload", payload.toString())
            .param("availableAt", timestamp)
            .param("createdAt", timestamp)
            .param("updatedAt", timestamp)
            .update()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun markRunning(syncRunId: String): IssueSyncRunRecord {
        val run = lockRun(syncRunId)
        if (run.status != IssueSyncStatus.QUEUED) throw invalidState(syncRunId, run.status)
        jdbc.sql(
            """
            SELECT id FROM issue_source WHERE id = :sourceId FOR UPDATE
            """.trimIndent(),
        ).param("sourceId", run.sourceId).query(String::class.java).single()
        val anotherRunning = jdbc.sql(
            """
            SELECT count(*) FROM issue_sync_run
            WHERE source_id = :sourceId AND status = 'RUNNING' AND id <> :syncRunId
            """.trimIndent(),
        )
            .param("sourceId", run.sourceId)
            .param("syncRunId", syncRunId)
            .query(Int::class.java)
            .single()
        if (anotherRunning > 0) throw sourceBusy(run.sourceId)
        val now = timeProvider.now().atOffset(java.time.ZoneOffset.UTC)
        jdbc.sql(
            """
            UPDATE issue_sync_run SET status = 'RUNNING', started_at = :startedAt
            WHERE id = :syncRunId AND status = 'QUEUED'
            """.trimIndent(),
        ).param("startedAt", now).param("syncRunId", syncRunId).update().requireOne()
        return findRun(syncRunId)!!
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun persistPage(syncRunId: String, page: IssuePage) {
        val run = lockRun(syncRunId)
        if (run.status != IssueSyncStatus.RUNNING) throw invalidState(syncRunId, run.status)
        val sourceType = source(run.sourceId, lock = false)?.sourceType
            ?: throw runNotFound(syncRunId)
        page.issues.forEachIndexed { pageIndex, issue ->
            val revision = resolveIssueRevision(run, sourceType, issue)
            insertObservation(
                run,
                revision,
                run.issueCount + pageIndex,
                IssueFactCanonicalizer.canonicalPostgresInstant(page.observedAt),
            )
        }
        val warningCount = page.issues.sumOf { it.warnings.size }
        jdbc.sql(
            """
            UPDATE issue_sync_run
            SET cursor_after = :cursorAfter,
                source_watermark = :sourceWatermark,
                issue_count = issue_count + :issueCount,
                warning_count = warning_count + :warningCount
            WHERE id = :syncRunId AND status = 'RUNNING'
            """.trimIndent(),
        )
            .param("cursorAfter", page.nextCursor)
            .param("sourceWatermark", page.sourceWatermark)
            .param("issueCount", page.issues.size)
            .param("warningCount", warningCount)
            .param("syncRunId", syncRunId)
            .update()
            .requireOne()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun markSucceeded(
        syncRunId: String,
        successfulCursor: String?,
        sourceWatermark: String,
    ): IssueSyncRunRecord {
        val run = lockRun(syncRunId)
        if (run.status == IssueSyncStatus.SUCCEEDED &&
            run.cursorAfter == successfulCursor &&
            run.sourceWatermark == sourceWatermark
        ) {
            return run
        }
        if (run.status != IssueSyncStatus.RUNNING) throw invalidState(syncRunId, run.status)
        val now = timeProvider.now().atOffset(java.time.ZoneOffset.UTC)
        jdbc.sql(
            """
            UPDATE issue_sync_run
            SET status = 'SUCCEEDED', cursor_after = :cursorAfter,
                source_watermark = :sourceWatermark, diagnostic_code = NULL,
                completed_at = :completedAt
            WHERE id = :syncRunId AND status = 'RUNNING'
            """.trimIndent(),
        )
            .param("cursorAfter", successfulCursor)
            .param("sourceWatermark", sourceWatermark)
            .param("completedAt", now)
            .param("syncRunId", syncRunId)
            .update()
            .requireOne()
        jdbc.sql(
            """
            INSERT INTO issue_sync_cursor(
              source_id, project_id, cursor_value, source_watermark,
              last_successful_sync_run_id, updated_at
            ) VALUES (
              :sourceId, :projectId, :cursorValue, :sourceWatermark,
              :syncRunId, :updatedAt
            )
            ON CONFLICT (source_id) DO UPDATE SET
              project_id = EXCLUDED.project_id,
              cursor_value = EXCLUDED.cursor_value,
              source_watermark = EXCLUDED.source_watermark,
              last_successful_sync_run_id = EXCLUDED.last_successful_sync_run_id,
              updated_at = EXCLUDED.updated_at
            """.trimIndent(),
        )
            .param("sourceId", run.sourceId)
            .param("projectId", run.projectId)
            .param("cursorValue", successfulCursor)
            .param("sourceWatermark", sourceWatermark)
            .param("syncRunId", syncRunId)
            .param("updatedAt", now)
            .update()
            .requireOne()
        return findRun(syncRunId)!!
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun markFailed(syncRunId: String, diagnosticCode: String): IssueSyncRunRecord {
        require(DIAGNOSTIC_CODE.matches(diagnosticCode)) { "Invalid diagnostic code" }
        val run = lockRun(syncRunId)
        if (run.status == IssueSyncStatus.FAILED && run.diagnosticCode == diagnosticCode) return run
        if (run.status == IssueSyncStatus.SUCCEEDED || run.status == IssueSyncStatus.FAILED) {
            throw invalidState(syncRunId, run.status)
        }
        jdbc.sql(
            """
            UPDATE issue_sync_run
            SET status = 'FAILED', diagnostic_code = :diagnosticCode, completed_at = :completedAt
            WHERE id = :syncRunId AND status IN ('QUEUED', 'RUNNING')
            """.trimIndent(),
        )
            .param("diagnosticCode", diagnosticCode)
            .param("completedAt", timeProvider.now().atOffset(java.time.ZoneOffset.UTC))
            .param("syncRunId", syncRunId)
            .update()
            .requireOne()
        return findRun(syncRunId)!!
    }

    override fun findRun(syncRunId: String): IssueSyncRunRecord? = jdbc.sql(RUN_SELECT)
        .param("syncRunId", syncRunId)
        .query(::mapRun)
        .optional()
        .orElse(null)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun claimNextJob(): QueuedIssueSync? {
        val job = jdbc.sql(
            """
            SELECT id, payload ->> 'syncRunId' AS sync_run_id
            FROM background_job
            WHERE job_type = :jobType AND status = 'QUEUED' AND available_at <= :now
            ORDER BY created_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """.trimIndent(),
        )
            .param("jobType", JOB_TYPE)
            .param("now", timeProvider.now().atOffset(java.time.ZoneOffset.UTC))
            .query { rs, _ -> QueuedIssueSync(rs.getString("sync_run_id"), rs.getString("id")) }
            .optional()
            .orElse(null) ?: return null
        jdbc.sql(
            """
            UPDATE background_job
            SET status = 'RUNNING', attempt_count = attempt_count + 1,
                started_at = :startedAt, updated_at = :updatedAt
            WHERE id = :jobId AND status = 'QUEUED'
            """.trimIndent(),
        )
            .param("startedAt", timeProvider.now().atOffset(java.time.ZoneOffset.UTC))
            .param("updatedAt", timeProvider.now().atOffset(java.time.ZoneOffset.UTC))
            .param("jobId", job.jobId)
            .update()
            .requireOne()
        return job
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun markJobSucceeded(jobId: String) {
        completeJob(jobId, "SUCCEEDED", null)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun markJobFailed(jobId: String, diagnosticCode: String) {
        require(DIAGNOSTIC_CODE.matches(diagnosticCode)) { "Invalid diagnostic code" }
        completeJob(jobId, "FAILED", diagnosticCode)
    }

    private fun completeJob(jobId: String, status: String, diagnosticCode: String?) {
        val now = timeProvider.now().atOffset(java.time.ZoneOffset.UTC)
        val result = diagnosticCode?.let { "{\"diagnosticCode\":\"$it\"}" } ?: "{}"
        jdbc.sql(
            """
            UPDATE background_job
            SET status = :status, result_summary = CAST(:result AS jsonb),
                completed_at = :completedAt, updated_at = :updatedAt
            WHERE id = :jobId AND status = 'RUNNING'
            """.trimIndent(),
        )
            .param("status", status)
            .param("result", result)
            .param("completedAt", now)
            .param("updatedAt", now)
            .param("jobId", jobId)
            .update()
            .requireOne()
    }

    private fun lockRun(syncRunId: String): IssueSyncRunRecord = jdbc.sql("$RUN_SELECT FOR UPDATE")
        .param("syncRunId", syncRunId)
        .query(::mapRun)
        .optional()
        .orElseThrow { runNotFound(syncRunId) }

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
            .query(::mapSource)
            .optional()
            .orElse(null)
    }

    private fun resolveIssueRevision(
        run: IssueSyncRunRecord,
        sourceType: String,
        issue: NormalizedIssue,
    ): PersistedIssueRevision {
        val canonical = IssueFactCanonicalizer.canonicalize(issue)
        require(canonical.source == sourceType) { "Issue source does not match configured source" }
        require(canonical.mappingVersion == run.mappingVersion) { "Issue mapping version does not match sync run" }
        jdbc.sql(
            """
            INSERT INTO normalized_issue(
              id, project_id, source_id, source_issue_id, title, severity, status,
              raw_status_token, raw_severity_token, mapping_warnings,
              source_version, source_reference, observed_at,
              mapping_version, tombstone, fact_digest, fact_digest_version, created_at
            ) VALUES (
              :id, :projectId, :sourceId, :sourceIssueId, :title, :severity, :status,
              :rawStatus, :rawSeverity, :mappingWarnings, :sourceVersion, :sourceReference, :observedAt,
              :mappingVersion, :tombstone, :factDigest, :factDigestVersion, :createdAt
            )
            ON CONFLICT (source_id, source_issue_id, source_version, mapping_version) DO NOTHING
            """.trimIndent(),
        )
            .param("id", idGenerator.nextId("iss_"))
            .param("projectId", run.projectId)
            .param("sourceId", run.sourceId)
            .param("sourceIssueId", canonical.sourceIssueId)
            .param("title", canonical.title)
            .param("severity", canonical.severity)
            .param("status", canonical.status)
            .param("rawStatus", canonical.rawStatus)
            .param("rawSeverity", canonical.rawSeverity)
            .param("mappingWarnings", IssueFactCanonicalizer.encodeWarnings(canonical.warnings))
            .param("sourceVersion", canonical.sourceVersion)
            .param("sourceReference", canonical.sourceReference)
            .param("observedAt", canonical.observedAt.atOffset(java.time.ZoneOffset.UTC))
            .param("mappingVersion", canonical.mappingVersion)
            .param("tombstone", canonical.tombstone)
            .param("factDigest", canonical.factDigest)
            .param("factDigestVersion", IssueFactCanonicalizer.FACT_DIGEST_VERSION)
            .param("createdAt", timeProvider.now().atOffset(java.time.ZoneOffset.UTC))
            .update()
        val persisted = jdbc.sql(
            """
            SELECT id, project_id, source_id, source_issue_id, title, severity, status,
                   raw_status_token, raw_severity_token, mapping_warnings,
                   source_version, source_reference, observed_at,
                   mapping_version, tombstone, fact_digest, fact_digest_version
            FROM normalized_issue
            WHERE source_id = :sourceId
              AND source_issue_id = :sourceIssueId
              AND source_version = :sourceVersion
              AND mapping_version = :mappingVersion
            """.trimIndent(),
        )
            .param("sourceId", run.sourceId)
            .param("sourceIssueId", canonical.sourceIssueId)
            .param("sourceVersion", canonical.sourceVersion)
            .param("mappingVersion", canonical.mappingVersion)
            .query(::mapIssueRevision)
            .single()
        if (!persisted.matchesIssue(run.projectId, run.sourceId, issue)) {
            throw DataIntegrityViolationException("Normalized issue identity resolved to different canonical facts")
        }
        return persisted
    }

    private fun insertObservation(
        run: IssueSyncRunRecord,
        revision: PersistedIssueRevision,
        ordinal: Int,
        observedAt: Instant,
    ) {
        jdbc.sql(
            """
            INSERT INTO issue_sync_run_item(
              sync_run_id, ordinal, project_id, source_id, issue_id,
              source_issue_id, observed_at, created_at
            ) VALUES (
              :syncRunId, :ordinal, :projectId, :sourceId, :issueId,
              :sourceIssueId, :observedAt, :createdAt
            )
            """.trimIndent(),
        )
            .param("syncRunId", run.id)
            .param("ordinal", ordinal)
            .param("projectId", run.projectId)
            .param("sourceId", run.sourceId)
            .param("issueId", revision.id)
            .param("sourceIssueId", revision.sourceIssueId)
            .param("observedAt", observedAt.atOffset(java.time.ZoneOffset.UTC))
            .param("createdAt", timeProvider.now().atOffset(java.time.ZoneOffset.UTC))
            .update()
            .requireOne()
    }

    private fun mapIssueRevision(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = PersistedIssueRevision(
        id = rs.getString("id"),
        projectId = rs.getString("project_id"),
        sourceId = rs.getString("source_id"),
        sourceIssueId = rs.getString("source_issue_id"),
        title = rs.getString("title"),
        severity = rs.getString("severity"),
        status = rs.getString("status"),
        rawStatus = rs.getString("raw_status_token"),
        rawSeverity = rs.getString("raw_severity_token"),
        mappingWarnings = rs.getString("mapping_warnings"),
        sourceVersion = rs.getString("source_version"),
        sourceReference = rs.getString("source_reference"),
        observedAt = rs.getObject("observed_at", OffsetDateTime::class.java).toInstant(),
        mappingVersion = rs.getString("mapping_version"),
        tombstone = rs.getBoolean("tombstone"),
        factDigest = rs.getString("fact_digest"),
        factDigestVersion = rs.getString("fact_digest_version"),
    )

    private fun mapSource(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = IssueSourceRecord(
        id = rs.getString("id"),
        projectId = rs.getString("project_id"),
        sourceType = rs.getString("source_type"),
        adapterVersion = rs.getString("adapter_version"),
        mappingVersion = rs.getString("mapping_version"),
        enabled = rs.getBoolean("enabled"),
    )

    private fun mapRun(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = IssueSyncRunRecord(
        id = rs.getString("id"),
        projectId = rs.getString("project_id"),
        sourceId = rs.getString("source_id"),
        status = IssueSyncStatus.valueOf(rs.getString("status")),
        cursorBefore = rs.getString("cursor_before"),
        cursorAfter = rs.getString("cursor_after"),
        sourceWatermark = rs.getString("source_watermark"),
        adapterVersion = rs.getString("adapter_version"),
        mappingVersion = rs.getString("mapping_version"),
        resultSetMode = rs.getString("result_set_mode")?.let(IssueSyncResultSetMode::valueOf),
        filterReference = rs.getString("filter_reference"),
        issueCount = rs.getInt("issue_count"),
        warningCount = rs.getInt("warning_count"),
        diagnosticCode = rs.getString("diagnostic_code"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
    )

    private companion object {
        const val JOB_TYPE = "ISSUE_SYNC"
        val DIAGNOSTIC_CODE = Regex("^[A-Z][A-Z0-9_]{0,79}$")
        val RUN_SELECT = """
            SELECT id, project_id, source_id, status, cursor_before, cursor_after,
                   source_watermark, adapter_version, mapping_version, issue_count,
                   result_set_mode, filter_reference, warning_count, diagnostic_code, created_at
            FROM issue_sync_run
            WHERE id = :syncRunId
        """.trimIndent()
    }
}

internal data class PersistedIssueRevision(
    val id: String,
    val projectId: String,
    val sourceId: String,
    val sourceIssueId: String,
    val title: String,
    val severity: String,
    val status: String,
    val rawStatus: String?,
    val rawSeverity: String?,
    val mappingWarnings: String?,
    val sourceVersion: String,
    val sourceReference: String,
    val observedAt: Instant,
    val mappingVersion: String,
    val tombstone: Boolean,
    val factDigest: String,
    val factDigestVersion: String?,
) {
    fun matches(
        projectId: String,
        sourceId: String,
        facts: CanonicalIssueFacts,
        expectedDigest: String,
    ): Boolean =
        this.projectId == projectId &&
            this.sourceId == sourceId &&
            sourceIssueId == facts.sourceIssueId &&
            title == facts.title &&
            severity == facts.severity &&
            status == facts.status &&
            rawStatus == facts.rawStatus &&
            sourceVersion == facts.sourceVersion &&
            sourceReference == facts.sourceReference &&
            mappingVersion == facts.mappingVersion &&
            tombstone == facts.tombstone &&
            factDigest == expectedDigest

    fun matchesIssue(projectId: String, sourceId: String, issue: NormalizedIssue): Boolean {
        val incomingCanonical = IssueFactCanonicalizer.canonicalize(issue)
        val expectedDigest = when (factDigestVersion) {
            null -> {
                if (!legacyIssueDigestMatches(issue, observedAt, factDigest)) return false
                factDigest
            }
            IssueFactCanonicalizer.FACT_DIGEST_VERSION -> IssueFactCanonicalizer.factDigest(
                incomingCanonical.copy(observedAt = observedAt),
            )
            else -> throw DataIntegrityViolationException("Normalized issue has unsupported fact digest version")
        }
        val persistedInputsMatch = factDigestVersion == null || (
            rawSeverity == incomingCanonical.rawSeverity &&
                mappingWarnings == IssueFactCanonicalizer.encodeWarnings(incomingCanonical.warnings)
            )
        return persistedInputsMatch && matches(projectId, sourceId, incomingCanonical, expectedDigest)
    }
}

private fun Int.requireOne() {
    check(this == 1) { "Database write did not affect exactly one row" }
}

private fun runNotFound(id: String) = ResourceNotFound(
    code = "ISSUE_SYNC_RUN_NOT_FOUND",
    resourceTitle = "Issue sync run not found",
    detail = "Issue sync run '$id' was not found",
)

private fun invalidState(id: String, status: IssueSyncStatus) = ResourceConflict(
    code = "ISSUE_SYNC_STATE_CONFLICT",
    resourceTitle = "Issue sync state conflict",
    detail = "Issue sync run '$id' cannot execute from state ${status.name}",
)

private fun sourceBusy(id: String) = ResourceConflict(
    code = "ISSUE_SOURCE_SYNC_IN_PROGRESS",
    resourceTitle = "Issue source synchronization is in progress",
    detail = "Issue source '$id' already has a running synchronization",
)
