package com.ricezhou.vsrqg.traceability.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.adapter.toJdbcTimestamp
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationAuthority
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRepository
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRunRecord
import com.ricezhou.vsrqg.traceability.domain.Confidence
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdge
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdgeAuthority
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdgeType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityIssue
import com.ricezhou.vsrqg.traceability.domain.VerificationStatus
import java.time.Instant
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class JdbcTraceabilityVerificationRepository(
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
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
                     snapshot.content_digest, snapshot.selected_count
              FROM release_issue_snapshot snapshot
              JOIN locked_release release
                ON release.release_id = snapshot.release_id
               AND release.project_id = snapshot.project_id
              WHERE snapshot.source_id = :sourceId
                AND snapshot.canonicalization_version = 'release-issue-snapshot-jcs/v1'
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

private fun Int.requireOne() {
    check(this == 1) { "Database write did not affect exactly one row" }
}

private fun Int.requireCount(expected: Int) {
    check(this == expected) { "Database write did not affect the expected row count" }
}
