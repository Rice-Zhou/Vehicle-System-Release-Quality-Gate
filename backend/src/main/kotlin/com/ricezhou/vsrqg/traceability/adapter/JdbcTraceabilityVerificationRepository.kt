package com.ricezhou.vsrqg.traceability.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.shared.adapter.toJdbcTimestamp
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationAuthority
import com.ricezhou.vsrqg.traceability.application.PinnedTraceabilityVerificationExecution
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotMaterialization
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotVersionConflict
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationJobClaim
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRepository
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRunRecord
import com.ricezhou.vsrqg.traceability.domain.Confidence
import com.ricezhou.vsrqg.traceability.domain.LockedManifest
import com.ricezhou.vsrqg.traceability.domain.PinnedIssueSnapshot
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdge
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdgeAuthority
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdgeType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityIssue
import com.ricezhou.vsrqg.traceability.domain.TraceabilityOrdering
import com.ricezhou.vsrqg.traceability.domain.TraceabilityVerificationFailure
import com.ricezhou.vsrqg.traceability.domain.VerificationInput
import com.ricezhou.vsrqg.traceability.domain.VerificationStatus
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
class JdbcTraceabilityVerificationRepository(
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
    private val governanceStore: GovernanceStore,
) : TraceabilityVerificationRepository {
    override fun findProjectId(releaseId: String, issueSourceId: String): String? = jdbc.sql(
        """
        SELECT release.project_id
        FROM release_record release
        JOIN issue_source source
          ON source.id = :sourceId AND source.project_id = release.project_id
        WHERE release.id = :releaseId
        """.trimIndent(),
    ).param("releaseId", releaseId).param("sourceId", issueSourceId)
        .query(String::class.java).optional().orElse(null)

    override fun lockAndLoadAuthority(
        releaseId: String,
        issueSourceId: String,
        issueFetchLimit: Int,
        edgeFetchLimit: Int,
    ): TraceabilityVerificationAuthority? {
        require(issueFetchLimit in 1..MAX_ISSUE_FETCH_LIMIT)
        require(edgeFetchLimit in 1..MAX_EDGE_FETCH_LIMIT)
        val documents = jdbc.sql(AUTHORITY_SQL)
            .param("releaseId", releaseId)
            .param("sourceId", issueSourceId)
            .param("issueFetchLimit", issueFetchLimit)
            .param("edgeFetchLimit", edgeFetchLimit)
            .query(String::class.java)
            .list()
            .map(objectMapper::readTree)
        val header = documents.firstOrNull { it.path("kind").textValue() == HEADER } ?: return null
        val issues = documents.asSequence()
            .filter { it.path("kind").textValue() == ISSUE }
            .map { TraceabilityIssue(it.requiredText("issueId"), it.requiredText("sourceIssueId")) }
            .toList()
        val edges = documents.asSequence()
            .filter { it.path("kind").textValue() == EDGE }
            .map(::edge)
            .toList()
        return TraceabilityVerificationAuthority(
            projectId = header.requiredText("projectId"),
            releaseId = header.requiredText("releaseId"),
            manifestRevisionId = header.nullableText("manifestRevisionId"),
            manifestDigest = header.nullableText("manifestDigest"),
            manifestState = header.nullableText("manifestState"),
            issueSnapshotId = header.nullableText("issueSnapshotId"),
            issueSnapshotDigest = header.nullableText("issueSnapshotDigest"),
            issueSnapshotCanonicalizationVersion = header.nullableText("issueSnapshotCanonicalizationVersion"),
            declaredIssueCount = header.nullableInt("declaredIssueCount"),
            issues = issues,
            edges = edges,
        )
    }

    override fun insertRun(run: TraceabilityVerificationRunRecord) {
        jdbc.sql(
            """
            INSERT INTO traceability_verification_run(
              id, project_id, release_id, verification_run_id, status, policy_version,
              diagnostic_code, started_at, completed_at, created_at, issue_snapshot_id,
              manifest_revision_id, validator_version, input_digest, result_snapshot_id,
              requested_by, request_id, input_edge_count
            ) VALUES (
              :id, :projectId, :releaseId, :id, 'QUEUED', :policyVersion,
              NULL, NULL, NULL, :createdAt, :issueSnapshotId,
              :manifestRevisionId, :validatorVersion, :inputDigest, NULL,
              :requestedBy, :requestId, :inputEdgeCount
            )
            """.trimIndent(),
        ).param("id", run.id).param("projectId", run.projectId).param("releaseId", run.releaseId)
            .param("policyVersion", run.policyVersion).param("createdAt", run.createdAt.toJdbcTimestamp())
            .param("issueSnapshotId", run.issueSnapshotId).param("manifestRevisionId", run.manifestRevisionId)
            .param("validatorVersion", run.validatorVersion).param("inputDigest", run.inputDigest)
            .param("requestedBy", run.requestedBy).param("requestId", run.requestId)
            .param("inputEdgeCount", run.inputEdgeCount).update().requireOne()
    }

    override fun insertInputLedger(
        runId: String,
        projectId: String,
        edges: List<PinnedTraceabilityEdge>,
        createdAt: Instant,
    ) {
        if (edges.isEmpty()) return
        val input = objectMapper.createArrayNode().also { array ->
            edges.forEachIndexed { ordinal, edge ->
                array.add(
                    objectMapper.createObjectNode()
                        .put("ordinal", ordinal)
                        .put("edge_type", edge.edgeType.name)
                        .put("source_edge_id", edge.sourceEdgeId)
                        .put("source_edge_revision", edge.sourceEdgeRevision)
                        .put("source_edge_revision_id", edge.sourceEdgeRevisionId)
                        .put("fact_digest", edge.factDigest),
                )
            }
        }
        jdbc.sql(
            """
            INSERT INTO traceability_verification_run_edge_input(
              verification_run_id, ordinal, project_id, edge_type, source_edge_id,
              source_edge_revision, source_edge_revision_id, fact_digest, created_at
            )
            SELECT :runId, input.ordinal, :projectId, input.edge_type, input.source_edge_id,
                   input.source_edge_revision, input.source_edge_revision_id, input.fact_digest, :createdAt
            FROM jsonb_to_recordset(CAST(:input AS jsonb)) AS input(
              ordinal integer,
              edge_type varchar(40),
              source_edge_id varchar(40),
              source_edge_revision integer,
              source_edge_revision_id varchar(40),
              fact_digest varchar(71)
            )
            ORDER BY input.ordinal
            """.trimIndent(),
        ).param("runId", runId).param("projectId", projectId).param("input", input.toString())
            .param("createdAt", createdAt.toJdbcTimestamp()).update().requireCount(edges.size)
    }

    override fun insertJob(
        jobId: String,
        projectId: String,
        runId: String,
        payload: JsonNode,
        createdAt: Instant,
    ) {
        val timestamp = createdAt.toJdbcTimestamp()
        jdbc.sql(
            """
            INSERT INTO background_job(
              id, project_id, job_type, idempotency_key, status, payload,
              available_at, created_at, updated_at
            ) VALUES (
              :id, :projectId, 'TRACEABILITY_VERIFY', :runId, 'QUEUED', CAST(:payload AS jsonb),
              :createdAt, :createdAt, :createdAt
            )
            """.trimIndent(),
        ).param("id", jobId).param("projectId", projectId).param("runId", runId)
            .param("payload", payload.toString()).param("createdAt", timestamp).update().requireOne()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun claimNext(now: Instant): TraceabilityVerificationJobClaim? {
        val claimTime = now.truncatedTo(ChronoUnit.MICROS)
        val candidate = jdbc.sql(
            """
            SELECT job.id AS job_id, job.idempotency_key AS verification_run_id,
                   job.project_id, job.attempt_count, verification_run.status AS run_status
            FROM background_job job
            JOIN traceability_verification_run verification_run
              ON verification_run.id = job.idempotency_key
             AND verification_run.project_id = job.project_id
            WHERE job.job_type = 'TRACEABILITY_VERIFY'
              AND verification_run.status IN ('QUEUED', 'RUNNING')
              AND (
                (job.status = 'QUEUED' AND job.available_at <= :now)
                OR
                (job.status = 'RUNNING' AND job.started_at <= :reclaimBefore)
              )
            ORDER BY job.created_at, job.id
            FOR UPDATE OF job, verification_run SKIP LOCKED
            LIMIT 1
            """.trimIndent(),
        ).param("now", claimTime.toJdbcTimestamp())
            .param("reclaimBefore", claimTime.minusSeconds(CLAIM_LEASE_SECONDS).toJdbcTimestamp())
            .query { rs, _ ->
                ClaimCandidate(
                    rs.getString("job_id"),
                    rs.getString("verification_run_id"),
                    rs.getString("project_id"),
                    rs.getInt("attempt_count"),
                    rs.getString("run_status"),
                )
            }.optional().orElse(null) ?: return null

        if (candidate.attemptCount >= MAX_JOB_ATTEMPTS) {
            terminalFailure(candidate.jobId, candidate.verificationRunId, claimTime, RETRY_EXHAUSTED)
            return null
        }
        if (candidate.runStatus == "QUEUED") {
            jdbc.sql(
                """
                UPDATE traceability_verification_run
                SET status = 'RUNNING', started_at = :now
                WHERE id = :runId AND status = 'QUEUED'
                """.trimIndent(),
            ).param("now", claimTime.toJdbcTimestamp()).param("runId", candidate.verificationRunId)
                .update().requireOne()
        }
        val attemptCount = candidate.attemptCount + 1
        jdbc.sql(
            """
            UPDATE background_job
            SET status = 'RUNNING', attempt_count = :attemptCount,
                started_at = :now, completed_at = NULL, result_summary = NULL, updated_at = :now
            WHERE id = :jobId AND status IN ('QUEUED', 'RUNNING')
            """.trimIndent(),
        ).param("attemptCount", attemptCount).param("now", claimTime.toJdbcTimestamp())
            .param("jobId", candidate.jobId).update().requireOne()
        return TraceabilityVerificationJobClaim(
            candidate.jobId,
            candidate.verificationRunId,
            candidate.projectId,
            attemptCount,
        )
    }

    override fun loadPinnedExecution(verificationRunId: String): PinnedTraceabilityVerificationExecution {
        val header = jdbc.sql(
            """
            SELECT verification_run.id, verification_run.project_id, verification_run.release_id,
                   verification_run.issue_snapshot_id, verification_run.manifest_revision_id,
                   verification_run.policy_version, verification_run.validator_version,
                   verification_run.input_digest, verification_run.input_edge_count,
                   verification_run.requested_by, verification_run.request_id,
                   issue_snapshot.content_digest AS issue_snapshot_digest,
                   manifest.content_digest AS manifest_digest
            FROM traceability_verification_run verification_run
            JOIN release_issue_snapshot issue_snapshot
              ON issue_snapshot.id = verification_run.issue_snapshot_id
             AND issue_snapshot.release_id = verification_run.release_id
             AND issue_snapshot.project_id = verification_run.project_id
            JOIN manifest_revision manifest
              ON manifest.id = verification_run.manifest_revision_id
             AND manifest.release_id = verification_run.release_id
            WHERE verification_run.id = :runId AND verification_run.status = 'RUNNING'
            """.trimIndent(),
        ).param("runId", verificationRunId).query { rs, _ ->
            ExecutionHeader(
                id = rs.getString("id"),
                projectId = rs.getString("project_id"),
                releaseId = rs.getString("release_id"),
                issueSnapshotId = rs.getString("issue_snapshot_id"),
                issueSnapshotDigest = rs.getString("issue_snapshot_digest"),
                manifestRevisionId = rs.getString("manifest_revision_id"),
                manifestDigest = rs.getString("manifest_digest"),
                policyVersion = rs.getString("policy_version"),
                validatorVersion = rs.getString("validator_version"),
                inputDigest = rs.getString("input_digest"),
                inputEdgeCount = rs.getInt("input_edge_count"),
                requestedBy = rs.getString("requested_by"),
                requestId = rs.getString("request_id"),
            )
        }.optional().orElseThrow {
            TraceabilityVerificationFailure("TRACEABILITY_INPUT_NOT_VALID", "PINNED_RUN_NOT_RUNNING")
        }
        val issues = jdbc.sql(
            """
            SELECT issue_id, source_issue_id
            FROM release_issue_snapshot_item
            WHERE snapshot_id = :snapshotId AND project_id = :projectId
            ORDER BY ordinal
            """.trimIndent(),
        ).param("snapshotId", header.issueSnapshotId).param("projectId", header.projectId)
            .query { rs, _ -> TraceabilityIssue(rs.getString("issue_id"), rs.getString("source_issue_id")) }
            .list()
        val edges = jdbc.sql(PINNED_INPUT_SQL)
            .param("runId", verificationRunId)
            .query { rs, _ ->
                val type = PinnedTraceabilityEdgeType.valueOf(rs.getString("edge_type"))
                PinnedTraceabilityEdge(
                    projectId = rs.getString("project_id"),
                    edgeType = type,
                    fromId = rs.getString("from_id"),
                    toId = rs.getString("to_id"),
                    sourceEdgeId = rs.getString("source_edge_id"),
                    sourceEdgeRevision = rs.getInt("source_edge_revision"),
                    sourceEdgeRevisionId = rs.getString("source_edge_revision_id"),
                    verificationStatus = VerificationStatus.valueOf(rs.getString("verification_status")),
                    confidence = Confidence.valueOf(rs.getString("confidence")),
                    factDigest = rs.getString("fact_digest"),
                    authority = if (type == PinnedTraceabilityEdgeType.ARTIFACT_RELEASE) {
                        PinnedTraceabilityEdgeAuthority.LOCKED_MANIFEST
                    } else {
                        PinnedTraceabilityEdgeAuthority.EDGE_REVISION
                    },
                )
            }.list()
        if (edges.size != header.inputEdgeCount) {
            throw TraceabilityVerificationFailure("TRACEABILITY_INPUT_NOT_VALID", "PINNED_EDGE_LEDGER_INCOMPLETE")
        }
        val input = VerificationInput(
            schemaVersion = INPUT_SCHEMA_VERSION,
            policyVersion = header.policyVersion,
            validatorVersion = header.validatorVersion,
            projectId = header.projectId,
            releaseId = header.releaseId,
            issueSnapshot = PinnedIssueSnapshot(
                header.projectId,
                header.releaseId,
                header.issueSnapshotId,
                header.issueSnapshotDigest,
                issues,
            ),
            manifest = LockedManifest(
                header.projectId,
                header.releaseId,
                header.manifestRevisionId,
                header.manifestDigest,
            ),
            edgeRevisions = edges,
        )
        return PinnedTraceabilityVerificationExecution(
            header.id,
            header.projectId,
            header.releaseId,
            header.requestedBy,
            header.requestId,
            header.inputDigest,
            input,
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun failInvalidInput(
        claim: TraceabilityVerificationJobClaim,
        diagnosticCode: String,
        completedAt: Instant,
    ) {
        require(DIAGNOSTIC_CODE.matches(diagnosticCode)) { "Invalid diagnostic code" }
        val timestamp = completedAt.truncatedTo(ChronoUnit.MICROS)
        lockClaim(claim)
        terminalFailure(claim.jobId, claim.verificationRunId, timestamp, diagnosticCode)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun recordInfrastructureFailure(claim: TraceabilityVerificationJobClaim, failedAt: Instant) {
        val timestamp = failedAt.truncatedTo(ChronoUnit.MICROS)
        val locked = lockClaim(claim)
        if (locked.attemptCount >= MAX_JOB_ATTEMPTS) {
            terminalFailure(claim.jobId, claim.verificationRunId, timestamp, RETRY_EXHAUSTED)
            return
        }
        val summary = objectMapper.createObjectNode().put("diagnosticCode", RETRY_SCHEDULED)
        jdbc.sql(
            """
            UPDATE background_job
            SET status = 'QUEUED', available_at = :availableAt, started_at = NULL,
                result_summary = CAST(:summary AS jsonb), updated_at = :now
            WHERE id = :jobId AND status = 'RUNNING' AND attempt_count = :attemptCount
            """.trimIndent(),
        ).param("availableAt", timestamp.plusSeconds(locked.attemptCount.toLong()).toJdbcTimestamp())
            .param("summary", summary.toString()).param("now", timestamp.toJdbcTimestamp())
            .param("jobId", claim.jobId).param("attemptCount", claim.attemptCount)
            .update().requireOne()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun materializeResult(
        claim: TraceabilityVerificationJobClaim,
        execution: PinnedTraceabilityVerificationExecution,
        materialization: TraceabilitySnapshotMaterialization,
    ): String = try {
        val locked = lockClaim(claim)
        check(locked.verificationRunId == execution.verificationRunId && locked.projectId == execution.projectId) {
            "TRACEABILITY_VERIFICATION_CLAIM_MISMATCH"
        }
        reusableSnapshot(execution, materialization.computation.contentDigest)?.let { reused ->
            completeSuccess(claim, execution, reused, materialization.completedAt)
            return reused.id
        }

        jdbc.sql(
            "SELECT id FROM release_record WHERE id = :releaseId AND project_id = :projectId FOR UPDATE",
        ).param("releaseId", execution.releaseId).param("projectId", execution.projectId)
            .query(String::class.java).single()
        reusableSnapshot(execution, materialization.computation.contentDigest)?.let { reused ->
            completeSuccess(claim, execution, reused, materialization.completedAt)
            return reused.id
        }

        val version = jdbc.sql(
            "SELECT coalesce(max(version), 0) + 1 FROM traceability_snapshot WHERE release_id = :releaseId",
        ).param("releaseId", execution.releaseId).query(Int::class.java).single()
        val completedAt = materialization.completedAt.truncatedTo(ChronoUnit.MICROS)
        insertSnapshotHeader(execution, materialization, version, completedAt)
        insertIssueResults(execution, materialization, completedAt)
        insertSnapshotEdges(execution, materialization.snapshotId, completedAt)
        insertPathEdges(execution, materialization, completedAt)
        insertGaps(execution, materialization, completedAt)
        val created = ReusableSnapshot(materialization.snapshotId, version)
        completeSuccess(claim, execution, created, completedAt)
        materialization.snapshotId
    } catch (failure: DataIntegrityViolationException) {
        if (failure.isSnapshotVersionConflict()) throw TraceabilitySnapshotVersionConflict()
        throw failure
    }

    private fun insertSnapshotHeader(
        execution: PinnedTraceabilityVerificationExecution,
        materialization: TraceabilitySnapshotMaterialization,
        version: Int,
        createdAt: Instant,
    ) {
        jdbc.sql(
            """
            INSERT INTO traceability_snapshot(
              id, project_id, release_id, verification_run_id, version,
              schema_version, policy_version, content_digest, created_at
            ) VALUES (
              :id, :projectId, :releaseId, :runId, :version,
              :schemaVersion, :policyVersion, :contentDigest, :createdAt
            )
            """.trimIndent(),
        ).param("id", materialization.snapshotId).param("projectId", execution.projectId)
            .param("releaseId", execution.releaseId).param("runId", execution.verificationRunId)
            .param("version", version).param("schemaVersion", SNAPSHOT_SCHEMA_VERSION)
            .param("policyVersion", execution.input.policyVersion)
            .param("contentDigest", materialization.computation.contentDigest)
            .param("createdAt", createdAt.toJdbcTimestamp()).update().requireOne()
    }

    private fun insertIssueResults(
        execution: PinnedTraceabilityVerificationExecution,
        materialization: TraceabilitySnapshotMaterialization,
        createdAt: Instant,
    ) {
        val results = objectMapper.createArrayNode().also { array ->
            materialization.computation.issueResults.forEachIndexed { ordinal, result ->
                array.add(
                    objectMapper.createObjectNode()
                        .put("ordinal", ordinal)
                        .put("issue_id", result.issueId)
                        .put("source_issue_id", result.sourceIssueId)
                        .put("fixed", result.fixed)
                        .put("included", result.included)
                        .put("verified", result.verified)
                        .put("confidence", result.confidence.name)
                        .put("result_digest", result.resultDigest),
                )
            }
        }
        jdbc.sql(
            """
            INSERT INTO traceability_snapshot_issue_result(
              snapshot_id, ordinal, project_id, issue_id, source_issue_id,
              fixed, included, verified, confidence, result_digest, created_at
            )
            SELECT :snapshotId, result.ordinal, :projectId, result.issue_id, result.source_issue_id,
                   result.fixed, result.included, result.verified, result.confidence,
                   result.result_digest, :createdAt
            FROM jsonb_to_recordset(CAST(:results AS jsonb)) AS result(
              ordinal integer, issue_id varchar(40), source_issue_id varchar(255),
              fixed boolean, included boolean, verified boolean, confidence varchar(20),
              result_digest varchar(71)
            )
            ORDER BY result.ordinal
            """.trimIndent(),
        ).param("snapshotId", materialization.snapshotId).param("projectId", execution.projectId)
            .param("results", results.toString()).param("createdAt", createdAt.toJdbcTimestamp())
            .update().requireCount(materialization.computation.issueResults.size)
    }

    private fun insertSnapshotEdges(
        execution: PinnedTraceabilityVerificationExecution,
        snapshotId: String,
        createdAt: Instant,
    ) {
        val inserted = jdbc.sql(SNAPSHOT_EDGE_INSERT_SQL)
            .param("snapshotId", snapshotId)
            .param("runId", execution.verificationRunId)
            .param("createdAt", createdAt.toJdbcTimestamp())
            .update()
        inserted.requireCount(execution.input.edgeRevisions.size)
    }

    private fun insertPathEdges(
        execution: PinnedTraceabilityVerificationExecution,
        materialization: TraceabilitySnapshotMaterialization,
        createdAt: Instant,
    ) {
        val edgeOrdinals = execution.input.edgeRevisions
            .sortedWith(TraceabilityOrdering.inputEdgeOrder)
            .withIndex()
            .associate { indexed -> EdgeIdentity.of(indexed.value) to indexed.index }
        val issueOrdinals = materialization.computation.issueResults
            .withIndex()
            .associate { indexed -> indexed.value.issueId to indexed.index }
        val paths = objectMapper.createArrayNode().also { array ->
            materialization.computation.pathEdges.forEach { path ->
                array.add(
                    objectMapper.createObjectNode()
                        .put("issue_ordinal", issueOrdinals.getValue(path.issueId))
                        .put("path_ordinal", path.pathOrdinal)
                        .put("snapshot_edge_ordinal", edgeOrdinals.getValue(EdgeIdentity.of(path.edge))),
                )
            }
        }
        if (paths.isEmpty) return
        jdbc.sql(
            """
            INSERT INTO traceability_snapshot_issue_path_edge(
              snapshot_id, issue_ordinal, path_ordinal, snapshot_edge_ordinal, created_at
            )
            SELECT :snapshotId, path.issue_ordinal, path.path_ordinal,
                   path.snapshot_edge_ordinal, :createdAt
            FROM jsonb_to_recordset(CAST(:paths AS jsonb)) AS path(
              issue_ordinal integer, path_ordinal integer, snapshot_edge_ordinal integer
            )
            ORDER BY path.issue_ordinal, path.path_ordinal
            """.trimIndent(),
        ).param("snapshotId", materialization.snapshotId).param("paths", paths.toString())
            .param("createdAt", createdAt.toJdbcTimestamp()).update()
            .requireCount(materialization.computation.pathEdges.size)
    }

    private fun insertGaps(
        execution: PinnedTraceabilityVerificationExecution,
        materialization: TraceabilitySnapshotMaterialization,
        createdAt: Instant,
    ) {
        check(materialization.runGapIds.size == materialization.computation.gaps.size) {
            "TRACEABILITY_GAP_ID_COUNT_MISMATCH"
        }
        val gaps = objectMapper.createArrayNode().also { array ->
            materialization.computation.gaps.forEachIndexed { ordinal, gap ->
                array.add(
                    objectMapper.createObjectNode()
                        .put("id", materialization.runGapIds[ordinal])
                        .put("ordinal", ordinal)
                        .put("issue_id", gap.issueId)
                        .put("expected_edge_type", gap.expectedEdgeType.toStorageToken())
                        .put("reason", gap.reason)
                        .put("diagnostic_code", gap.diagnosticCode.name)
                        .put("gap_digest", gap.gapDigest)
                        .put("break_entity_type", gap.breakEntityType.name)
                        .put("break_entity_id", gap.breakEntityId)
                        .putNullable("predecessor_edge_type", gap.predecessorEdge?.edgeType?.name)
                        .putNullable("predecessor_edge_id", gap.predecessorEdge?.sourceEdgeId)
                        .putNullable("predecessor_edge_revision", gap.predecessorEdge?.sourceEdgeRevision),
                )
            }
        }
        jdbc.sql(RUN_GAP_INSERT_SQL)
            .param("runId", execution.verificationRunId).param("projectId", execution.projectId)
            .param("releaseId", execution.releaseId).param("gaps", gaps.toString())
            .param("createdAt", createdAt.toJdbcTimestamp()).update()
            .requireCount(materialization.computation.gaps.size)
        jdbc.sql(SNAPSHOT_GAP_INSERT_SQL)
            .param("snapshotId", materialization.snapshotId).param("projectId", execution.projectId)
            .param("releaseId", execution.releaseId).param("gaps", gaps.toString())
            .param("createdAt", createdAt.toJdbcTimestamp()).update()
            .requireCount(materialization.computation.gaps.size)
    }

    private fun completeSuccess(
        claim: TraceabilityVerificationJobClaim,
        execution: PinnedTraceabilityVerificationExecution,
        snapshot: ReusableSnapshot,
        completedAt: Instant,
    ) {
        val timestamp = completedAt.truncatedTo(ChronoUnit.MICROS)
        val metadata = objectMapper.createObjectNode()
            .put("schemaVersion", 1)
            .put("verificationRunId", execution.verificationRunId)
            .put("releaseId", execution.releaseId)
            .put("resultSnapshotId", snapshot.id)
            .put("snapshotVersion", snapshot.version)
            .put("policyVersion", execution.input.policyVersion)
            .put("validatorVersion", execution.input.validatorVersion)
            .put("inputDigest", execution.inputDigest)
            .put("status", "SUCCEEDED")
        governanceStore.appendAudit(
            projectId = execution.projectId,
            actorId = execution.requestedBy,
            action = SUCCESS_AUDIT_ACTION,
            resourceType = AGGREGATE_TYPE,
            resourceId = execution.verificationRunId,
            requestId = execution.requestId,
            reason = null,
            afterState = metadata,
        )
        governanceStore.appendOutbox(
            eventType = SUCCESS_OUTBOX_EVENT,
            aggregateType = AGGREGATE_TYPE,
            aggregateId = execution.verificationRunId,
            payload = metadata.deepCopy(),
        )
        jdbc.sql(
            """
            UPDATE traceability_verification_run
            SET status = 'SUCCEEDED', result_snapshot_id = :snapshotId, completed_at = :completedAt
            WHERE id = :runId AND status = 'RUNNING'
            """.trimIndent(),
        ).param("snapshotId", snapshot.id).param("completedAt", timestamp.toJdbcTimestamp())
            .param("runId", execution.verificationRunId).update().requireOne()
        jdbc.sql(
            """
            UPDATE background_job
            SET status = 'SUCCEEDED', result_summary = CAST(:summary AS jsonb),
                completed_at = :completedAt, updated_at = :completedAt
            WHERE id = :jobId AND status = 'RUNNING' AND attempt_count = :attemptCount
            """.trimIndent(),
        ).param("summary", objectMapper.createObjectNode().put("resultSnapshotId", snapshot.id).toString())
            .param("completedAt", timestamp.toJdbcTimestamp()).param("jobId", claim.jobId)
            .param("attemptCount", claim.attemptCount).update().requireOne()
    }

    private fun lockClaim(claim: TraceabilityVerificationJobClaim): LockedClaim = jdbc.sql(
        """
        SELECT job.id AS job_id, job.attempt_count, verification_run.id AS verification_run_id,
               verification_run.project_id
        FROM background_job job
        JOIN traceability_verification_run verification_run
          ON verification_run.id = job.idempotency_key
         AND verification_run.project_id = job.project_id
        WHERE job.id = :jobId AND job.job_type = 'TRACEABILITY_VERIFY'
          AND job.status = 'RUNNING' AND job.attempt_count = :attemptCount
          AND job.project_id = :projectId
          AND verification_run.id = :runId AND verification_run.status = 'RUNNING'
        FOR UPDATE OF job, verification_run
        """.trimIndent(),
    ).param("jobId", claim.jobId).param("attemptCount", claim.attemptCount)
        .param("projectId", claim.projectId)
        .param("runId", claim.verificationRunId).query { rs, _ ->
            LockedClaim(
                rs.getString("job_id"),
                rs.getString("verification_run_id"),
                rs.getString("project_id"),
                rs.getInt("attempt_count"),
            )
        }.single()

    private fun reusableSnapshot(
        execution: PinnedTraceabilityVerificationExecution,
        contentDigest: String,
    ): ReusableSnapshot? = jdbc.sql(
        """
        SELECT snapshot.id, snapshot.version
        FROM traceability_snapshot snapshot
        JOIN traceability_verification_run producer
          ON producer.id = snapshot.verification_run_id
         AND producer.result_snapshot_id = snapshot.id
         AND producer.status = 'SUCCEEDED'
        WHERE snapshot.project_id = :projectId AND snapshot.release_id = :releaseId
          AND snapshot.policy_version = :policyVersion AND snapshot.content_digest = :contentDigest
          AND producer.issue_snapshot_id = :issueSnapshotId
          AND producer.manifest_revision_id = :manifestRevisionId
          AND producer.validator_version = :validatorVersion
          AND producer.input_digest = :inputDigest
        ORDER BY snapshot.version
        LIMIT 1
        """.trimIndent(),
    ).param("projectId", execution.projectId).param("releaseId", execution.releaseId)
        .param("policyVersion", execution.input.policyVersion).param("contentDigest", contentDigest)
        .param("issueSnapshotId", execution.input.issueSnapshot.snapshotId)
        .param("manifestRevisionId", execution.input.manifest.revisionId)
        .param("validatorVersion", execution.input.validatorVersion).param("inputDigest", execution.inputDigest)
        .query { rs, _ -> ReusableSnapshot(rs.getString("id"), rs.getInt("version")) }
        .optional().orElse(null)

    private fun terminalFailure(jobId: String, runId: String, completedAt: Instant, diagnosticCode: String) {
        val timestamp = completedAt.toJdbcTimestamp()
        jdbc.sql(
            """
            UPDATE traceability_verification_run
            SET status = 'FAILED', diagnostic_code = :diagnosticCode, completed_at = :completedAt
            WHERE id = :runId AND status = 'RUNNING'
            """.trimIndent(),
        ).param("diagnosticCode", diagnosticCode).param("completedAt", timestamp)
            .param("runId", runId).update().requireOne()
        val summary = objectMapper.createObjectNode().put("diagnosticCode", diagnosticCode)
        jdbc.sql(
            """
            UPDATE background_job
            SET status = 'DEAD_LETTER', result_summary = CAST(:summary AS jsonb),
                completed_at = :completedAt, updated_at = :completedAt
            WHERE id = :jobId AND status = 'RUNNING'
            """.trimIndent(),
        ).param("summary", summary.toString()).param("completedAt", timestamp)
            .param("jobId", jobId).update().requireOne()
    }

    private fun edge(document: JsonNode): PinnedTraceabilityEdge {
        val type = PinnedTraceabilityEdgeType.valueOf(document.requiredText("edgeType"))
        return PinnedTraceabilityEdge(
            projectId = document.requiredText("projectId"),
            edgeType = type,
            fromId = document.requiredText("fromId"),
            toId = document.requiredText("toId"),
            sourceEdgeId = document.requiredText("sourceEdgeId"),
            sourceEdgeRevision = document.path("sourceEdgeRevision").intValue(),
            sourceEdgeRevisionId = document.requiredText("sourceEdgeRevisionId"),
            verificationStatus = VerificationStatus.valueOf(document.requiredText("verificationStatus")),
            confidence = Confidence.valueOf(document.requiredText("confidence")),
            factDigest = document.requiredText("factDigest"),
            authority = if (type == PinnedTraceabilityEdgeType.ARTIFACT_RELEASE) {
                PinnedTraceabilityEdgeAuthority.LOCKED_MANIFEST
            } else {
                PinnedTraceabilityEdgeAuthority.EDGE_REVISION
            },
        )
    }

    private fun JsonNode.requiredText(field: String): String = path(field).textValue()
        ?: error("Traceability authority row is missing $field")

    private fun JsonNode.nullableText(field: String): String? = path(field).takeUnless(JsonNode::isNull)?.textValue()

    private fun JsonNode.nullableInt(field: String): Int? = path(field).takeUnless(JsonNode::isNull)?.intValue()

    private companion object {
        const val HEADER = "HEADER"
        const val ISSUE = "ISSUE"
        const val EDGE = "EDGE"
        const val MAX_ISSUE_FETCH_LIMIT = 21
        const val MAX_EDGE_FETCH_LIMIT = 2_001
        const val MAX_JOB_ATTEMPTS = 3
        const val CLAIM_LEASE_SECONDS = 300L
        const val INPUT_SCHEMA_VERSION = "m2.5-traceability-input/v1"
        const val SNAPSHOT_SCHEMA_VERSION = "m2.5-traceability-snapshot/v1"
        const val RETRY_SCHEDULED = "TRACEABILITY_VERIFICATION_RETRY_SCHEDULED"
        const val RETRY_EXHAUSTED = "TRACEABILITY_VERIFICATION_RETRY_EXHAUSTED"
        const val SUCCESS_AUDIT_ACTION = "TRACEABILITY_VERIFICATION_SUCCEEDED"
        const val SUCCESS_OUTBOX_EVENT = "traceability.verification.succeeded"
        const val AGGREGATE_TYPE = "TRACEABILITY_VERIFICATION_RUN"
        val DIAGNOSTIC_CODE = Regex("^[A-Z][A-Z0-9_]{2,63}$")

        val PINNED_INPUT_SQL =
            """
            SELECT edge.* FROM (
              SELECT input.ordinal, input.project_id, input.edge_type,
                     revision.issue_id AS from_id, revision.commit_id AS to_id,
                     input.source_edge_id, input.source_edge_revision,
                     input.source_edge_revision_id, revision.verification_status,
                     revision.confidence, input.fact_digest
              FROM traceability_verification_run_edge_input input
              JOIN issue_commit_edge_revision revision
                ON input.edge_type = 'ISSUE_COMMIT'
               AND revision.project_id = input.project_id
               AND revision.id = input.source_edge_revision_id
               AND revision.edge_id = input.source_edge_id
               AND revision.revision = input.source_edge_revision
               AND revision.content_digest = input.fact_digest
              WHERE input.verification_run_id = :runId
              UNION ALL
              SELECT input.ordinal, input.project_id, input.edge_type,
                     revision.commit_id, revision.build_id,
                     input.source_edge_id, input.source_edge_revision,
                     input.source_edge_revision_id, revision.verification_status,
                     revision.confidence, input.fact_digest
              FROM traceability_verification_run_edge_input input
              JOIN commit_build_edge_revision revision
                ON input.edge_type = 'COMMIT_BUILD'
               AND revision.project_id = input.project_id
               AND revision.id = input.source_edge_revision_id
               AND revision.edge_id = input.source_edge_id
               AND revision.revision = input.source_edge_revision
               AND revision.content_digest = input.fact_digest
              WHERE input.verification_run_id = :runId
              UNION ALL
              SELECT input.ordinal, input.project_id, input.edge_type,
                     revision.build_id, revision.artifact_id,
                     input.source_edge_id, input.source_edge_revision,
                     input.source_edge_revision_id, revision.verification_status,
                     revision.confidence, input.fact_digest
              FROM traceability_verification_run_edge_input input
              JOIN build_artifact_edge_revision revision
                ON input.edge_type = 'BUILD_ARTIFACT'
               AND revision.project_id = input.project_id
               AND revision.id = input.source_edge_revision_id
               AND revision.edge_id = input.source_edge_id
               AND revision.revision = input.source_edge_revision
               AND revision.content_digest = input.fact_digest
              WHERE input.verification_run_id = :runId
              UNION ALL
              SELECT input.ordinal, input.project_id, input.edge_type,
                     authority.artifact_id, authority.release_id,
                     input.source_edge_id, input.source_edge_revision,
                     input.source_edge_revision_id, authority.verification_status,
                     authority.confidence, input.fact_digest
              FROM traceability_verification_run_edge_input input
              JOIN traceability_verification_run verification_run
                ON verification_run.id = input.verification_run_id
               AND verification_run.project_id = input.project_id
              JOIN artifact_release_edge_v authority
                ON input.edge_type = 'ARTIFACT_RELEASE'
               AND authority.project_id = input.project_id
               AND authority.release_id = verification_run.release_id
               AND authority.source_edge_id = input.source_edge_id
               AND authority.source_edge_revision = input.source_edge_revision
               AND authority.manifest_revision_id = input.source_edge_revision_id
               AND authority.fact_digest = input.fact_digest
              WHERE input.verification_run_id = :runId
            ) edge
            ORDER BY edge.ordinal
            """.trimIndent()

        val SNAPSHOT_EDGE_INSERT_SQL =
            """
            WITH edge_fact AS (
              SELECT input.ordinal, input.project_id, input.edge_type,
                     'ISSUE'::varchar AS from_entity_type, revision.issue_id AS from_entity_id,
                     'COMMIT'::varchar AS to_entity_type, revision.commit_id AS to_entity_id,
                     input.source_edge_id, input.source_edge_revision, input.source_edge_revision_id,
                     revision.source_type, revision.source_reference, revision.confidence,
                     revision.verification_status, revision.verified_at, revision.validator_version,
                     revision.reason, revision.evidence_id, input.fact_digest,
                     NULL::varchar AS manifest_revision_id, NULL::varchar AS manifest_digest,
                     NULL::integer AS manifest_artifact_ordinal, NULL::boolean AS manifest_artifact_required
              FROM traceability_verification_run_edge_input input
              JOIN issue_commit_edge_revision revision
                ON input.edge_type = 'ISSUE_COMMIT' AND revision.project_id = input.project_id
               AND revision.id = input.source_edge_revision_id AND revision.edge_id = input.source_edge_id
               AND revision.revision = input.source_edge_revision AND revision.content_digest = input.fact_digest
              WHERE input.verification_run_id = :runId
              UNION ALL
              SELECT input.ordinal, input.project_id, input.edge_type,
                     'COMMIT', revision.commit_id, 'BUILD', revision.build_id,
                     input.source_edge_id, input.source_edge_revision, input.source_edge_revision_id,
                     revision.source_type, revision.source_reference, revision.confidence,
                     revision.verification_status, revision.verified_at, revision.validator_version,
                     revision.reason, revision.evidence_id, input.fact_digest,
                     NULL, NULL, NULL, NULL
              FROM traceability_verification_run_edge_input input
              JOIN commit_build_edge_revision revision
                ON input.edge_type = 'COMMIT_BUILD' AND revision.project_id = input.project_id
               AND revision.id = input.source_edge_revision_id AND revision.edge_id = input.source_edge_id
               AND revision.revision = input.source_edge_revision AND revision.content_digest = input.fact_digest
              WHERE input.verification_run_id = :runId
              UNION ALL
              SELECT input.ordinal, input.project_id, input.edge_type,
                     'BUILD', revision.build_id, 'ARTIFACT', revision.artifact_id,
                     input.source_edge_id, input.source_edge_revision, input.source_edge_revision_id,
                     revision.source_type, revision.source_reference, revision.confidence,
                     revision.verification_status, revision.verified_at, revision.validator_version,
                     revision.reason, revision.evidence_id, input.fact_digest,
                     NULL, NULL, NULL, NULL
              FROM traceability_verification_run_edge_input input
              JOIN build_artifact_edge_revision revision
                ON input.edge_type = 'BUILD_ARTIFACT' AND revision.project_id = input.project_id
               AND revision.id = input.source_edge_revision_id AND revision.edge_id = input.source_edge_id
               AND revision.revision = input.source_edge_revision AND revision.content_digest = input.fact_digest
              WHERE input.verification_run_id = :runId
              UNION ALL
              SELECT input.ordinal, input.project_id, input.edge_type,
                     'ARTIFACT', authority.artifact_id, 'RELEASE', authority.release_id,
                     input.source_edge_id, input.source_edge_revision, input.source_edge_revision_id,
                     authority.source_type, authority.source_reference, authority.confidence,
                     authority.verification_status, authority.verified_at, authority.validator_version,
                     authority.reason, authority.evidence_id, input.fact_digest,
                     authority.manifest_revision_id, authority.manifest_digest,
                     authority.ordinal, authority.required
              FROM traceability_verification_run_edge_input input
              JOIN traceability_verification_run verification_run
                ON verification_run.id = input.verification_run_id
               AND verification_run.project_id = input.project_id
              JOIN artifact_release_edge_v authority
                ON input.edge_type = 'ARTIFACT_RELEASE' AND authority.project_id = input.project_id
               AND authority.release_id = verification_run.release_id
               AND authority.source_edge_id = input.source_edge_id
               AND authority.source_edge_revision = input.source_edge_revision
               AND authority.manifest_revision_id = input.source_edge_revision_id
               AND authority.fact_digest = input.fact_digest
              WHERE input.verification_run_id = :runId
            )
            INSERT INTO traceability_snapshot_edge(
              snapshot_id, ordinal, project_id, edge_type,
              from_entity_type, from_entity_id, to_entity_type, to_entity_id,
              source_edge_id, source_edge_revision, source_edge_revision_id,
              source_type, source_reference, confidence, verification_status,
              verified_at, validator_version, reason, evidence_id, fact_digest,
              manifest_revision_id, manifest_digest, manifest_artifact_ordinal,
              manifest_artifact_required, created_at
            )
            SELECT :snapshotId, ordinal, project_id, edge_type,
                   from_entity_type, from_entity_id, to_entity_type, to_entity_id,
                   source_edge_id, source_edge_revision, source_edge_revision_id,
                   source_type, source_reference, confidence, verification_status,
                   verified_at, validator_version, reason, evidence_id, fact_digest,
                   manifest_revision_id, manifest_digest, manifest_artifact_ordinal,
                   manifest_artifact_required, :createdAt
            FROM edge_fact ORDER BY ordinal
            """.trimIndent()

        val RUN_GAP_INSERT_SQL =
            """
            INSERT INTO traceability_gap(
              id, project_id, verification_run_id, release_id, issue_id,
              expected_edge_type, reason, diagnostic_code, gap_digest, created_at,
              break_entity_type, break_entity_id, predecessor_edge_type,
              predecessor_edge_id, predecessor_edge_revision
            )
            SELECT gap.id, :projectId, :runId, :releaseId, gap.issue_id,
                   gap.expected_edge_type, gap.reason, gap.diagnostic_code, gap.gap_digest, :createdAt,
                   gap.break_entity_type, gap.break_entity_id, gap.predecessor_edge_type,
                   gap.predecessor_edge_id, gap.predecessor_edge_revision
            FROM jsonb_to_recordset(CAST(:gaps AS jsonb)) AS gap(
              id varchar(40), ordinal integer, issue_id varchar(40), expected_edge_type varchar(40),
              reason text, diagnostic_code varchar(80), gap_digest varchar(71),
              break_entity_type varchar(40), break_entity_id varchar(40),
              predecessor_edge_type varchar(40), predecessor_edge_id varchar(40),
              predecessor_edge_revision integer
            ) ORDER BY gap.ordinal
            """.trimIndent()

        val SNAPSHOT_GAP_INSERT_SQL =
            """
            INSERT INTO traceability_snapshot_gap(
              snapshot_id, ordinal, project_id, issue_id, release_id,
              expected_edge_type, reason, diagnostic_code, gap_digest, created_at,
              break_entity_type, break_entity_id, predecessor_edge_type,
              predecessor_edge_id, predecessor_edge_revision
            )
            SELECT :snapshotId, gap.ordinal, :projectId, gap.issue_id, :releaseId,
                   gap.expected_edge_type, gap.reason, gap.diagnostic_code, gap.gap_digest, :createdAt,
                   gap.break_entity_type, gap.break_entity_id, gap.predecessor_edge_type,
                   gap.predecessor_edge_id, gap.predecessor_edge_revision
            FROM jsonb_to_recordset(CAST(:gaps AS jsonb)) AS gap(
              id varchar(40), ordinal integer, issue_id varchar(40), expected_edge_type varchar(40),
              reason text, diagnostic_code varchar(80), gap_digest varchar(71),
              break_entity_type varchar(40), break_entity_id varchar(40),
              predecessor_edge_type varchar(40), predecessor_edge_id varchar(40),
              predecessor_edge_revision integer
            ) ORDER BY gap.ordinal
            """.trimIndent()

        val AUTHORITY_SQL =
            """
            WITH locked_release AS MATERIALIZED (
              SELECT release.id AS release_id, release.project_id,
                     manifest.id AS manifest_revision_id,
                     manifest.content_digest AS manifest_digest,
                     manifest.state AS manifest_state
              FROM release_record release
              JOIN issue_source source
                ON source.id = :sourceId AND source.project_id = release.project_id
              LEFT JOIN manifest_revision manifest
                ON manifest.id = release.locked_manifest_id AND manifest.release_id = release.id
              WHERE release.id = :releaseId
              FOR UPDATE OF release
            ),
            latest_snapshot AS MATERIALIZED (
              SELECT snapshot.id, snapshot.project_id, snapshot.release_id,
                     snapshot.content_digest, snapshot.canonicalization_version,
                     snapshot.selected_count
              FROM release_issue_snapshot snapshot
              JOIN locked_release release
                ON release.release_id = snapshot.release_id
               AND release.project_id = snapshot.project_id
              WHERE snapshot.source_id = :sourceId
              ORDER BY snapshot.snapshot_version DESC, snapshot.id DESC
              LIMIT 1
            ),
            snapshot_issues AS MATERIALIZED (
              SELECT item.issue_id, item.source_issue_id
              FROM release_issue_snapshot_item item
              JOIN latest_snapshot snapshot
                ON snapshot.id = item.snapshot_id AND snapshot.project_id = item.project_id
              ORDER BY item.source_issue_id COLLATE "C", item.issue_id COLLATE "C"
              LIMIT :issueFetchLimit
            ),
            current_issue_commit AS MATERIALIZED (
              SELECT * FROM (
                SELECT edge.*, row_number() OVER (
                         PARTITION BY edge.edge_id ORDER BY edge.revision DESC, edge.id DESC
                       ) AS authority_rank
                FROM issue_commit_edge_revision edge
                JOIN locked_release release ON release.project_id = edge.project_id
              ) ranked WHERE ranked.authority_rank = 1
            ),
            relevant_issue_commit AS MATERIALIZED (
              SELECT edge.project_id, 'ISSUE_COMMIT'::varchar(40) AS edge_type,
                     edge.issue_id AS from_id, edge.commit_id AS to_id,
                     edge.edge_id, edge.revision, edge.id AS revision_id,
                     edge.verification_status, edge.confidence, edge.content_digest
              FROM current_issue_commit edge
              JOIN snapshot_issues issue ON issue.issue_id = edge.issue_id
            ),
            current_commit_build AS MATERIALIZED (
              SELECT * FROM (
                SELECT edge.*, row_number() OVER (
                         PARTITION BY edge.edge_id ORDER BY edge.revision DESC, edge.id DESC
                       ) AS authority_rank
                FROM commit_build_edge_revision edge
                JOIN locked_release release ON release.project_id = edge.project_id
              ) ranked WHERE ranked.authority_rank = 1
            ),
            relevant_commit_build AS MATERIALIZED (
              SELECT DISTINCT edge.project_id, 'COMMIT_BUILD'::varchar(40) AS edge_type,
                     edge.commit_id AS from_id, edge.build_id AS to_id,
                     edge.edge_id, edge.revision, edge.id AS revision_id,
                     edge.verification_status, edge.confidence, edge.content_digest
              FROM current_commit_build edge
              JOIN relevant_issue_commit predecessor ON predecessor.to_id = edge.commit_id
            ),
            current_build_artifact AS MATERIALIZED (
              SELECT * FROM (
                SELECT edge.*, row_number() OVER (
                         PARTITION BY edge.edge_id ORDER BY edge.revision DESC, edge.id DESC
                       ) AS authority_rank
                FROM build_artifact_edge_revision edge
                JOIN locked_release release ON release.project_id = edge.project_id
              ) ranked WHERE ranked.authority_rank = 1
            ),
            relevant_build_artifact AS MATERIALIZED (
              SELECT DISTINCT edge.project_id, 'BUILD_ARTIFACT'::varchar(40) AS edge_type,
                     edge.build_id AS from_id, edge.artifact_id AS to_id,
                     edge.edge_id, edge.revision, edge.id AS revision_id,
                     edge.verification_status, edge.confidence, edge.content_digest
              FROM current_build_artifact edge
              JOIN relevant_commit_build predecessor ON predecessor.to_id = edge.build_id
            ),
            typed_edges AS MATERIALIZED (
              SELECT * FROM relevant_issue_commit
              UNION ALL SELECT * FROM relevant_commit_build
              UNION ALL SELECT * FROM relevant_build_artifact
            ),
            artifact_release_edges AS MATERIALIZED (
              SELECT edge.project_id, 'ARTIFACT_RELEASE'::varchar(40) AS edge_type,
                     edge.artifact_id AS from_id, edge.release_id AS to_id,
                     edge.source_edge_id AS edge_id, edge.source_edge_revision AS revision,
                     edge.manifest_revision_id AS revision_id, edge.verification_status,
                     edge.confidence, edge.fact_digest AS content_digest
              FROM artifact_release_edge_v edge
              JOIN locked_release release
                ON release.project_id = edge.project_id
               AND release.release_id = edge.release_id
               AND release.manifest_revision_id = edge.manifest_revision_id
            ),
            limited_edges AS MATERIALIZED (
              SELECT project_id, edge_type, from_id, to_id, edge_id, revision,
                     revision_id, verification_status, confidence, content_digest
              FROM (
                SELECT project_id, edge_type, from_id, to_id, edge_id, revision,
                       revision_id, verification_status, confidence, content_digest
                FROM typed_edges
                UNION ALL
                SELECT project_id, edge_type, from_id, to_id, edge_id, revision,
                       revision_id, verification_status, confidence, content_digest
                FROM artifact_release_edges
              ) edge
              ORDER BY CASE edge_type
                         WHEN 'ISSUE_COMMIT' THEN 0
                         WHEN 'COMMIT_BUILD' THEN 1
                         WHEN 'BUILD_ARTIFACT' THEN 2
                         ELSE 3
                       END,
                       edge_id COLLATE "C", revision, revision_id COLLATE "C"
              LIMIT :edgeFetchLimit
            )
            SELECT row.payload::text
            FROM (
              SELECT 0 AS row_group, ''::text AS sort_a, ''::text AS sort_b,
                     jsonb_build_object(
                       'kind', 'HEADER',
                       'projectId', release.project_id,
                       'releaseId', release.release_id,
                       'manifestRevisionId', release.manifest_revision_id,
                       'manifestDigest', release.manifest_digest,
                       'manifestState', release.manifest_state,
                       'issueSnapshotId', snapshot.id,
                       'issueSnapshotDigest', snapshot.content_digest,
                       'issueSnapshotCanonicalizationVersion', snapshot.canonicalization_version,
                       'declaredIssueCount', snapshot.selected_count
                     ) AS payload
              FROM locked_release release
              LEFT JOIN latest_snapshot snapshot ON true
              UNION ALL
              SELECT 1, issue.source_issue_id, issue.issue_id,
                     jsonb_build_object(
                       'kind', 'ISSUE', 'issueId', issue.issue_id,
                       'sourceIssueId', issue.source_issue_id
                     )
              FROM snapshot_issues issue
              UNION ALL
              SELECT 2, edge.edge_type || chr(31) || edge.edge_id,
                     lpad(edge.revision::text, 10, '0') || chr(31) || edge.revision_id,
                     jsonb_build_object(
                       'kind', 'EDGE', 'projectId', edge.project_id,
                       'edgeType', edge.edge_type, 'fromId', edge.from_id, 'toId', edge.to_id,
                       'sourceEdgeId', edge.edge_id, 'sourceEdgeRevision', edge.revision,
                       'sourceEdgeRevisionId', edge.revision_id,
                       'verificationStatus', edge.verification_status,
                       'confidence', edge.confidence, 'factDigest', edge.content_digest
                     )
              FROM limited_edges edge
            ) row
            ORDER BY row.row_group, row.sort_a COLLATE "C", row.sort_b COLLATE "C"
            """.trimIndent()
    }
}

private data class ClaimCandidate(
    val jobId: String,
    val verificationRunId: String,
    val projectId: String,
    val attemptCount: Int,
    val runStatus: String,
)

private data class LockedClaim(
    val jobId: String,
    val verificationRunId: String,
    val projectId: String,
    val attemptCount: Int,
)

private data class ExecutionHeader(
    val id: String,
    val projectId: String,
    val releaseId: String,
    val issueSnapshotId: String,
    val issueSnapshotDigest: String,
    val manifestRevisionId: String,
    val manifestDigest: String,
    val policyVersion: String,
    val validatorVersion: String,
    val inputDigest: String,
    val inputEdgeCount: Int,
    val requestedBy: String,
    val requestId: String,
)

private data class ReusableSnapshot(val id: String, val version: Int)

private data class EdgeIdentity(
    val type: PinnedTraceabilityEdgeType,
    val sourceEdgeId: String,
    val revision: Int,
    val revisionId: String,
    val factDigest: String,
) {
    companion object {
        fun of(edge: PinnedTraceabilityEdge) = EdgeIdentity(
            edge.edgeType,
            edge.sourceEdgeId,
            edge.sourceEdgeRevision,
            edge.sourceEdgeRevisionId,
            edge.factDigest,
        )
    }
}

private fun com.ricezhou.vsrqg.traceability.domain.TraceabilityExpectedEdgeType.toStorageToken(): String =
    if (this == com.ricezhou.vsrqg.traceability.domain.TraceabilityExpectedEdgeType.TEST_RESULT_EVIDENCE) {
        "TEST_EVIDENCE"
    } else {
        name
    }

private fun ObjectNode.putNullable(field: String, value: String?): ObjectNode = apply {
    if (value == null) putNull(field) else put(field, value)
}

private fun ObjectNode.putNullable(field: String, value: Int?): ObjectNode = apply {
    if (value == null) putNull(field) else put(field, value)
}

private fun Throwable.isSnapshotVersionConflict(): Boolean = generateSequence(this) { it.cause }
    .filterIsInstance<SQLException>()
    .any { it.sqlState == "23505" && it.message.orEmpty().contains("uq_trace_snapshot_release_version") }

private fun Int.requireOne() {
    check(this == 1) { "Database write did not affect exactly one row" }
}

private fun Int.requireCount(expected: Int) {
    check(this == expected) { "Database write did not affect the expected row count" }
}
