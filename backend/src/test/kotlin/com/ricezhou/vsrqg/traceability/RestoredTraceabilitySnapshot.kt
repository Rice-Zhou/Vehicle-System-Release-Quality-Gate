package com.ricezhou.vsrqg.traceability

import com.ricezhou.vsrqg.traceability.adapter.JcsTraceabilityCanonicalizer
import com.ricezhou.vsrqg.traceability.domain.*
import com.ricezhou.vsrqg.traceability.adapter.JdbcTraceabilityVerificationRepository
import org.springframework.jdbc.core.simple.JdbcClient

/** Raw immutable snapshot fields; hash columns are comparison targets, never computed values. */
internal data class RestoredTraceabilitySnapshot(
    val projection: TraceabilityResultCanonicalProjection,
    val issueContents: List<TraceabilityIssueResultContentProjection>,
    val expectedDigest: String,
) {
    fun verify(canonicalizer: JcsTraceabilityCanonicalizer): String {
        val gaps = projection.gaps.map { gap ->
            val digest = canonicalizer.canonicalizeProjection(gap.copy(gapDigest = null)).digest
            check(digest == gap.gapDigest) { "RESTORED_GAP_DIGEST_MISMATCH" }
            gap.copy(gapDigest = digest)
        }
        val contents = issueContents.associateBy { it.issueId }
        val issues = projection.issueResults.map { issue ->
            val content = contents.getValue(issue.issueId)
            val digest = canonicalizer.canonicalizeProjection(content.copy(
                gaps = gaps.filter { it.issueId == issue.issueId },
            )).digest
            check(digest == issue.resultDigest) { "RESTORED_ISSUE_RESULT_DIGEST_MISMATCH" }
            issue.copy(resultDigest = digest)
        }
        val digest = canonicalizer.canonicalizeProjection(
            projection.copy(issueResults = issues, gaps = gaps),
        ).digest
        check(digest == expectedDigest) { "RESTORED_SNAPSHOT_DIGEST_MISMATCH" }
        return digest
    }
}

internal fun loadRestoredSnapshot(
    jdbc: JdbcClient,
    repository: JdbcTraceabilityVerificationRepository,
    snapshotId: String,
    completedRunId: String,
): RestoredTraceabilitySnapshot {
    // Only producer identity and immutable Issue Snapshot digest are metadata outside Snapshot tables.
    val header = jdbc.sql(
        """
        SELECT snapshot.project_id, snapshot.release_id, snapshot.schema_version,
               snapshot.policy_version, snapshot.content_digest,
               producer.validator_version, producer.issue_snapshot_id, producer.manifest_revision_id,
               issue_snapshot.content_digest AS issue_snapshot_digest
        FROM traceability_snapshot snapshot
        JOIN traceability_verification_run producer ON producer.id = snapshot.verification_run_id
          AND producer.result_snapshot_id = snapshot.id AND producer.status = 'SUCCEEDED'
        JOIN release_issue_snapshot issue_snapshot ON issue_snapshot.id = producer.issue_snapshot_id
        WHERE snapshot.id = :snapshotId AND producer.id = :completedRunId
        """.trimIndent(),
    ).param("snapshotId", snapshotId).param("completedRunId", completedRunId)
        .query { rs, _ ->
            check(rs.getString("schema_version") == "m2.5-traceability-snapshot/v1") {
                "RESTORED_SNAPSHOT_SCHEMA_UNSUPPORTED"
            }
            RestoredSnapshotIdentity(
                rs.getString("project_id"), rs.getString("release_id"), rs.getString("policy_version"),
                rs.getString("validator_version"), rs.getString("issue_snapshot_id"),
                rs.getString("manifest_revision_id"), rs.getString("issue_snapshot_digest"),
                rs.getString("content_digest"),
            )
        }.single()
    val edges = repository.findSnapshotEdges(snapshotId)
    val manifestDigest = jdbc.sql(
        """
        SELECT DISTINCT manifest_digest FROM traceability_snapshot_edge
        WHERE snapshot_id = :snapshotId AND edge_type = 'ARTIFACT_RELEASE'
        """.trimIndent(),
    ).param("snapshotId", snapshotId).query(String::class.java).single()
    val issues = jdbc.sql(
        """
        SELECT issue_id, source_issue_id, fixed, included, verified, confidence, result_digest
        FROM traceability_snapshot_issue_result WHERE snapshot_id = :snapshotId ORDER BY ordinal
        """.trimIndent(),
    ).param("snapshotId", snapshotId).query { rs, _ ->
        TraceabilityPersistedIssueResultProjection(
            rs.getString("issue_id"), rs.getString("source_issue_id"), rs.getBoolean("fixed"),
            rs.getBoolean("included"), rs.getBoolean("verified"),
            Confidence.valueOf(rs.getString("confidence")), rs.getString("result_digest"),
        )
    }.list()
    val input = VerificationInput(
        "m2.5-traceability-input/v1", header.policy, header.validator, header.project, header.release,
        PinnedIssueSnapshot(
            header.project, header.release, header.issueSnapshot, header.issueDigest,
            issues.map { TraceabilityIssue(it.issueId, it.sourceIssueId) },
        ),
        LockedManifest(header.project, header.release, header.manifest, manifestDigest),
        edges,
    )
    val paths = repository.findSnapshotPathEdges(snapshotId).map { path ->
        val edge = edges.single {
            it.edgeType == path.edgeType && it.sourceEdgeId == path.edgeId &&
                it.sourceEdgeRevision == path.revision && it.sourceEdgeRevisionId == path.revisionId
        }
        TraceabilityPathEdgeCanonicalProjection(
            issues[path.issueOrdinal].issueId, path.pathOrdinal, TraceabilityCanonicalProjectionFactory.edge(edge),
        )
    }
    val gaps = jdbc.sql(
        """
        SELECT issue_id, diagnostic_code, break_entity_type, break_entity_id, expected_edge_type,
               predecessor_edge_type, predecessor_edge_id, predecessor_edge_revision, reason, gap_digest
        FROM traceability_snapshot_gap WHERE snapshot_id = :snapshotId ORDER BY ordinal
        """.trimIndent(),
    ).param("snapshotId", snapshotId).query { rs, _ ->
        val predecessorId = rs.getString("predecessor_edge_id")
        val predecessor = predecessorId?.let { id ->
            edges.single {
                it.sourceEdgeId == id && it.edgeType.name == rs.getString("predecessor_edge_type") &&
                    it.sourceEdgeRevision == rs.getInt("predecessor_edge_revision")
            }
        }
        val expected = rs.getString("expected_edge_type")
        TraceabilityGapCanonicalProjection(
            rs.getString("issue_id"), TraceabilityGapCode.valueOf(rs.getString("diagnostic_code")),
            TraceabilityEntityType.valueOf(rs.getString("break_entity_type")), rs.getString("break_entity_id"),
            com.ricezhou.vsrqg.traceability.adapter.expectedEdgeType(expected),
            predecessor?.edgeType, predecessorId, predecessor?.sourceEdgeRevision,
            predecessor?.sourceEdgeRevisionId, rs.getString("reason"), rs.getString("gap_digest"),
        )
    }.list()
    val contents = issues.map { issue ->
        TraceabilityIssueResultContentProjection(
            issue.issueId, issue.sourceIssueId, issue.fixed, issue.included, issue.verified, issue.confidence,
            paths.filter { it.issueId == issue.issueId }.map { it.edge },
            gaps.filter { it.issueId == issue.issueId },
        )
    }
    return RestoredTraceabilitySnapshot(
        TraceabilityResultCanonicalProjection(TraceabilityCanonicalProjectionFactory.input(input), issues, paths, gaps),
        contents, header.digest,
    )
}

private data class RestoredSnapshotIdentity(
    val project: String,
    val release: String,
    val policy: String,
    val validator: String,
    val issueSnapshot: String,
    val manifest: String,
    val issueDigest: String,
    val digest: String,
)
