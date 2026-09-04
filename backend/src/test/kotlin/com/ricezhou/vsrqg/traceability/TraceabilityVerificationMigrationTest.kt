package com.ricezhou.vsrqg.traceability

import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@TestPropertySource(
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.vsrqg.test",
        "spring.security.oauth2.resourceserver.jwt.audiences[0]=vsrqg-api",
    ],
)
class TraceabilityVerificationMigrationTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `v11 adds fixed input and issue result authority`() {
        assertThat(columnNames("traceability_verification_run")).contains(
            "issue_snapshot_id",
            "manifest_revision_id",
            "validator_version",
            "input_digest",
            "result_snapshot_id",
            "requested_by",
            "request_id",
            "creation_transaction_id",
            "input_edge_count",
        )
        assertThat(tableNames()).contains(
            "traceability_verification_run_edge_input",
            "traceability_snapshot_issue_result",
            "traceability_snapshot_issue_path_edge",
        )
        assertThat(columnNames("traceability_verification_run_edge_input")).containsExactlyInAnyOrder(
            "verification_run_id",
            "ordinal",
            "project_id",
            "edge_type",
            "source_edge_id",
            "source_edge_revision",
            "fact_digest",
            "created_at",
        )
        assertThat(columnNames("traceability_snapshot_issue_result")).contains(
            "snapshot_id",
            "ordinal",
            "project_id",
            "issue_id",
            "source_issue_id",
            "fixed",
            "included",
            "verified",
            "result_digest",
            "created_at",
        )
        assertThat(columnNames("traceability_snapshot_issue_path_edge")).contains(
            "snapshot_id",
            "issue_ordinal",
            "path_ordinal",
            "snapshot_edge_ordinal",
            "created_at",
        )
        listOf("traceability_gap", "traceability_snapshot_gap").forEach { table ->
            assertThat(columnNames(table)).contains(
                "break_entity_type",
                "break_entity_id",
                "predecessor_edge_type",
                "predecessor_edge_id",
                "predecessor_edge_revision",
            )
        }
        assertThat(columnDefault("traceability_snapshot_issue_result", "verified"))
            .contains("false")
        assertThat(writableArtifactReleaseTableCount()).isZero()
    }

    @Test
    fun `fixed run identities reject cross project and release authorities`() {
        val left = seedAuthority(uniqueSuffix("scope_left"))
        val right = seedAuthority(uniqueSuffix("scope_right"))

        assertThatThrownBy {
            insertQueuedRun(
                left,
                uniqueSuffix("cross_snapshot"),
                issueSnapshotId = right.issueSnapshotId,
            )
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            insertQueuedRun(
                left,
                uniqueSuffix("cross_manifest"),
                manifestRevisionId = right.manifestRevisionId,
            )
        }.hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `fixed inputs reject duplicate ordinal duplicate source and non valid edges`() {
        val authority = seedAuthority(uniqueSuffix("input"))
        val otherValidEdge = seedIssueCommitEdge(authority, uniqueSuffix("valid_edge"), "VALID")
        val invalidEdge = seedIssueCommitEdge(authority, uniqueSuffix("invalid_edge"), "INVALID")
        assertThatThrownBy {
            insertRunWithInputs(authority, uniqueSuffix("duplicate_ordinal"), listOf(authority.edge, otherValidEdge), listOf(0, 0))
        }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            insertRunWithInputs(authority, uniqueSuffix("duplicate_source"), listOf(authority.edge, authority.edge), listOf(0, 1))
        }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            insertRunWithInputs(authority, uniqueSuffix("invalid_status"), listOf(authority.edge, invalidEdge))
        }
            .hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `fixed inputs must be inserted in the run creation transaction and sealed before running`() {
        val authority = seedAuthority(uniqueSuffix("sealed_input"))
        val runId = insertQueuedRun(authority, uniqueSuffix("unsealed"), inputEdgeCount = 1)

        assertThatThrownBy { insertRunInput(runId, authority, authority.edge, 0) }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { startRun(runId) }
            .hasRootCauseInstanceOf(SQLException::class.java)

        val atomicRun = insertRunWithInputs(authority, uniqueSuffix("atomic"), authority.pathEdges)
        startRun(atomicRun)
        assertThat(runStatus(atomicRun)).isEqualTo("RUNNING")
    }

    @ParameterizedTest
    @ValueSource(strings = ["INVALID", "CONFLICT", "ERROR"])
    fun `fixed inputs reject a valid historical revision when current authority is non valid`(status: String) {
        val authority = seedAuthority(uniqueSuffix("current_${status.lowercase()}"))
        appendIssueCommitRevision(authority, status)

        assertThatThrownBy {
            insertRunWithInputs(authority, uniqueSuffix("historical_${status.lowercase()}"), authority.pathEdges)
        }.hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `verification status transitions require a result and seal terminal runs`() {
        val authority = seedAuthority(uniqueSuffix("status"))
        val runId = insertRunWithInputs(authority, uniqueSuffix("status_run"), authority.pathEdges)

        assertThatThrownBy {
            jdbc.sql(
                "UPDATE traceability_verification_run SET status = 'SUCCEEDED', completed_at = now() WHERE id = :runId",
            ).param("runId", runId).update()
        }.hasRootCauseInstanceOf(SQLException::class.java)
        startRun(runId)
        assertThatThrownBy {
            jdbc.sql(
                "UPDATE traceability_verification_run SET status = 'QUEUED', started_at = NULL WHERE id = :runId",
            ).param("runId", runId).update()
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            jdbc.sql(
                "UPDATE traceability_verification_run SET status = 'SUCCEEDED', completed_at = now() WHERE id = :runId",
            ).param("runId", runId).update()
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            jdbc.sql(
                "UPDATE traceability_verification_run SET status = 'FAILED', completed_at = now() WHERE id = :runId",
            ).param("runId", runId).update()
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            jdbc.sql(
                "UPDATE traceability_verification_run SET status = 'FAILED', diagnostic_code = 'not-fixed', completed_at = now() WHERE id = :runId",
            ).param("runId", runId).update()
        }.hasRootCauseInstanceOf(SQLException::class.java)

        createResultSnapshot(runId, authority, uniqueSuffix("status_result"))
        assertThatThrownBy {
            jdbc.sql(
                "UPDATE traceability_verification_run SET policy_version = 'policy-v2' WHERE id = :runId",
            ).param("runId", runId).update()
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            jdbc.sql("DELETE FROM traceability_verification_run WHERE id = :runId")
                .param("runId", runId).update()
        }.hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `policy identity cannot change while queued or running`() {
        val authority = seedAuthority(uniqueSuffix("policy"))
        val queued = insertRunWithInputs(authority, uniqueSuffix("queued_policy"), authority.pathEdges)
        assertThatThrownBy { updatePolicy(queued, "policy-v2") }
            .hasRootCauseInstanceOf(SQLException::class.java)

        val running = insertRunWithInputs(authority, uniqueSuffix("running_policy"), authority.pathEdges)
        startRun(running)
        assertThatThrownBy { updatePolicy(running, "policy-v2") }
            .hasRootCauseInstanceOf(SQLException::class.java)

        assertThatThrownBy {
            inTransaction {
                val snapshotId = uniqueSuffix("wrong_policy_snapshot")
                insertCompleteSnapshotChildren(
                    snapshotId,
                    running,
                    authority,
                    1,
                    policyVersion = "policy-v2",
                )
                succeedRun(running, snapshotId)
            }
        }.hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `snapshot result and fixed inputs reject update and delete`() {
        val completed = seedCompletedVerification(uniqueSuffix("immutable"))
        listOf(
            "traceability_verification_run_edge_input" to
                "verification_run_id = '${completed.runId}'",
            "traceability_snapshot_issue_result" to
                "snapshot_id = '${completed.snapshotId}'",
            "traceability_snapshot_issue_path_edge" to
                "snapshot_id = '${completed.snapshotId}'",
        ).forEach { (table, predicate) ->
            assertThatThrownBy {
                jdbc.sql("UPDATE $table SET created_at = now() WHERE $predicate").update()
            }.describedAs("UPDATE on %s", table).isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                jdbc.sql("DELETE FROM $table WHERE $predicate").update()
            }.describedAs("DELETE on %s", table).isInstanceOf(DataAccessException::class.java)
        }
    }

    @Test
    fun `gap ledgers reject unapproved diagnostic codes`() {
        val authority = seedAuthority(uniqueSuffix("gap"))
        val runId = insertRunWithInputs(authority, uniqueSuffix("gap_run"), authority.pathEdges)
        startRun(runId)

        assertThatThrownBy {
            inTransaction {
                insertSnapshotHeader(uniqueSuffix("gap_result_tx"), runId, authority, 1)
                jdbc.sql(
                    """
                    INSERT INTO traceability_gap(
                      id, project_id, verification_run_id, release_id, issue_id,
                      expected_edge_type, reason, diagnostic_code, gap_digest,
                      break_entity_type, break_entity_id,
                      predecessor_edge_type, predecessor_edge_id, predecessor_edge_revision, created_at
                    ) VALUES (
                      :id, :projectId, :runId, :releaseId, :issueId,
                      'COMMIT_BUILD', 'missing build edge', 'UNAPPROVED_GAP', :digest,
                      'COMMIT', :commitId, 'ISSUE_COMMIT', :edgeId, 1, now()
                    )
                    """.trimIndent(),
                ).param("id", uniqueSuffix("gap_row")).param("projectId", authority.projectId)
                    .param("runId", runId).param("releaseId", authority.releaseId)
                    .param("issueId", authority.issueId).param("commitId", authority.commitId)
                    .param("edgeId", authority.edge.id).param("digest", digest(uniqueSuffix("gap_digest")))
                    .update()
            }
        }.hasRootCauseInstanceOf(SQLException::class.java)

        assertThatThrownBy {
            inTransaction {
                val snapshotId = uniqueSuffix("gap_snapshot")
                insertSnapshotHeader(snapshotId, runId, authority, 1)
                jdbc.sql(
                    """
                    INSERT INTO traceability_snapshot_gap(
                      snapshot_id, ordinal, project_id, issue_id, release_id,
                      expected_edge_type, reason, diagnostic_code, gap_digest,
                      break_entity_type, break_entity_id,
                      predecessor_edge_type, predecessor_edge_id, predecessor_edge_revision, created_at
                    ) VALUES (
                      :snapshotId, 0, :projectId, :issueId, :releaseId,
                      'COMMIT_BUILD', 'missing build edge', 'UNAPPROVED_GAP', :digest,
                      'COMMIT', :commitId, NULL, NULL, NULL, now()
                    )
                    """.trimIndent(),
                ).param("snapshotId", snapshotId).param("projectId", authority.projectId)
                    .param("issueId", authority.issueId).param("releaseId", authority.releaseId)
                    .param("commitId", authority.commitId)
                    .param("digest", digest(uniqueSuffix("snapshot_gap_digest"))).update()
            }
        }.hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `issue path edges must belong to the run fixed input`() {
        val authority = seedAuthority(uniqueSuffix("path"))
        val outsideInput = seedIssueCommitEdge(authority, uniqueSuffix("outside_input"), "VALID")
        val runId = insertRunWithInputs(authority, uniqueSuffix("path_run"), authority.pathEdges)
        startRun(runId)

        assertThatThrownBy {
            inTransaction {
                val snapshotId = uniqueSuffix("path_snapshot")
                insertSnapshotHeader(snapshotId, runId, authority, 1)
                insertSnapshotEdge(snapshotId, authority, outsideInput, 0)
                insertIssueResult(snapshotId, authority, 0, fixed = true, included = true)
                insertIssuePathEdge(snapshotId, 0, 0, 0)
            }
        }.hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `result snapshots must match the completing run fixed identity`() {
        val authority = seedAuthority(uniqueSuffix("result_scope"))
        val ownerRunId = insertRunWithInputs(authority, uniqueSuffix("owner_run"), authority.pathEdges)
        val otherRunId = insertRunWithInputs(
            authority,
            uniqueSuffix("other_run"),
            authority.pathEdges,
            inputDigest = digest(uniqueSuffix("different_input")),
        )
        listOf(ownerRunId, otherRunId).forEach { runId ->
            startRun(runId)
        }
        val ownerSnapshotId = createResultSnapshot(ownerRunId, authority, uniqueSuffix("owner_snapshot"))

        assertThatThrownBy { succeedRun(otherRunId, ownerSnapshotId) }
            .hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `new result snapshots must be complete and finished in their creation transaction`() {
        val authority = seedAuthority(uniqueSuffix("atomic_result"))
        val emptyRun = insertRunWithInputs(authority, uniqueSuffix("empty_run"), authority.pathEdges)
        startRun(emptyRun)
        assertThatThrownBy {
            inTransaction {
                val snapshotId = uniqueSuffix("empty_snapshot")
                insertSnapshotHeader(snapshotId, emptyRun, authority, 1)
                succeedRun(emptyRun, snapshotId)
            }
        }.hasRootCauseInstanceOf(SQLException::class.java)

        val splitRun = insertRunWithInputs(authority, uniqueSuffix("split_run"), authority.pathEdges)
        startRun(splitRun)
        val splitSnapshot = uniqueSuffix("split_snapshot")
        inTransaction { insertCompleteSnapshotChildren(splitSnapshot, splitRun, authority, 2) }
        assertThatThrownBy { succeedRun(splitRun, splitSnapshot) }
            .hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `same fixed input can reuse an existing successful snapshot`() {
        val authority = seedAuthority(uniqueSuffix("reuse"))
        val sharedDigest = digest(uniqueSuffix("shared_input"))
        val producer = insertRunWithInputs(
            authority,
            uniqueSuffix("reuse_producer"),
            authority.pathEdges,
            inputDigest = sharedDigest,
        )
        startRun(producer)
        val snapshotId = createResultSnapshot(producer, authority, uniqueSuffix("reuse_snapshot"))

        val consumer = insertRunWithInputs(
            authority,
            uniqueSuffix("reuse_consumer"),
            authority.pathEdges,
            inputDigest = sharedDigest,
        )
        startRun(consumer)
        succeedRun(consumer, snapshotId)

        assertThat(runResultSnapshot(consumer)).isEqualTo(snapshotId)
    }

    @Test
    fun `same digest cannot reuse a snapshot when the fixed edge ledger differs`() {
        val authority = seedAuthority(uniqueSuffix("reuse_ledger"))
        val sharedDigest = digest(uniqueSuffix("shared_digest"))
        val producer = insertRunWithInputs(
            authority,
            uniqueSuffix("ledger_producer"),
            authority.pathEdges,
            inputDigest = sharedDigest,
        )
        startRun(producer)
        val snapshotId = createResultSnapshot(producer, authority, uniqueSuffix("ledger_snapshot"))

        val alternateIssueEdge = seedIssueCommitEdge(authority, uniqueSuffix("alternate_issue"), "VALID")
        val consumer = insertRunWithInputs(
            authority,
            uniqueSuffix("ledger_consumer"),
            listOf(alternateIssueEdge) + authority.pathEdges.drop(1),
            inputDigest = sharedDigest,
        )
        startRun(consumer)

        assertThatThrownBy { succeedRun(consumer, snapshotId) }
            .hasRootCauseInstanceOf(SQLException::class.java)

        val reordered = insertRunWithInputs(
            authority,
            uniqueSuffix("ordinal_consumer"),
            authority.pathEdges,
            ordinals = listOf(1, 0, 2, 3),
            inputDigest = sharedDigest,
        )
        startRun(reordered)
        assertThatThrownBy { succeedRun(reordered, snapshotId) }
            .hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `producer owned snapshot is not reusable unless it is the producer result`() {
        val authority = seedAuthority(uniqueSuffix("reuse_result"))
        val sharedDigest = digest(uniqueSuffix("producer_result_digest"))
        val producer = insertRunWithInputs(
            authority,
            uniqueSuffix("result_producer"),
            authority.pathEdges,
            inputDigest = sharedDigest,
        )
        startRun(producer)
        val emptyHeader = uniqueSuffix("producer_empty")
        inTransaction {
            insertSnapshotHeader(emptyHeader, producer, authority, 1)
        }
        createResultSnapshot(producer, authority, uniqueSuffix("producer_actual"))

        val consumer = insertRunWithInputs(
            authority,
            uniqueSuffix("result_consumer"),
            authority.pathEdges,
            inputDigest = sharedDigest,
        )
        startRun(consumer)
        assertThatThrownBy { succeedRun(consumer, emptyHeader) }
            .hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `issue paths and gaps enforce the frozen four segment and break mappings`() {
        val authority = seedAuthority(uniqueSuffix("path_mapping"))
        val runId = insertRunWithInputs(authority, uniqueSuffix("mapping_run"), authority.pathEdges)
        startRun(runId)

        assertSqlFailure("23514", "snapshot issue path violates the frozen four-segment chain") {
            inTransaction {
                val snapshotId = uniqueSuffix("wrong_path")
                insertSnapshotHeader(snapshotId, runId, authority, 1)
                authority.pathEdges.forEachIndexed { ordinal, edge ->
                    insertSnapshotEdge(snapshotId, authority, edge, ordinal)
                }
                insertIssueResult(snapshotId, authority, 0, fixed = true, included = true)
                insertIssuePathEdge(snapshotId, 0, 0, 1)
            }
        }

        assertSqlFailure("23514", "gap does not match the frozen diagnostic break mapping") {
            inTransaction {
                val snapshotId = uniqueSuffix("mapping_result_tx")
                insertCompleteSnapshotChildren(snapshotId, runId, authority, 1)
                jdbc.sql(
                    """
                    INSERT INTO traceability_gap(
                      id, project_id, verification_run_id, release_id, issue_id,
                      expected_edge_type, reason, diagnostic_code, gap_digest,
                      break_entity_type, break_entity_id,
                      predecessor_edge_type, predecessor_edge_id, predecessor_edge_revision, created_at
                    ) VALUES (
                      :id, :projectId, :runId, :releaseId, :issueId,
                      'BUILD_ARTIFACT', 'wrong frozen mapping', 'COMMIT_BUILD_MISSING', :digest,
                      'COMMIT', :commitId, 'BUILD_ARTIFACT', :edgeId, 1, now()
                    )
                    """.trimIndent(),
                ).param("id", uniqueSuffix("wrong_gap")).param("projectId", authority.projectId)
                    .param("runId", runId).param("releaseId", authority.releaseId)
                    .param("issueId", authority.issueId).param("commitId", authority.commitId)
                    .param("edgeId", authority.pathEdges[2].id)
                    .param("digest", digest(uniqueSuffix("wrong_gap_digest"))).update()
                succeedRun(runId, snapshotId)
            }
        }

        assertSqlFailure("23514", "snapshot gap predecessor is not in the issue path at the break entity") {
            inTransaction {
                val snapshotId = uniqueSuffix("wrong_break")
                insertCompleteSnapshotChildren(snapshotId, runId, authority, 1)
                insertSnapshotGap(
                    snapshotId = snapshotId,
                    ordinal = 1,
                    authority = authority,
                    diagnosticCode = "COMMIT_BUILD_MISSING",
                    expectedEdgeType = "COMMIT_BUILD",
                    breakEntityType = "COMMIT",
                    breakEntityId = authority.buildId,
                    predecessor = authority.edge,
                )
                succeedRun(runId, snapshotId)
            }
        }
    }

    @Test
    fun `genuine missing edge gaps commit with a consistent snapshot result`() {
        val authority = seedAuthority(uniqueSuffix("true_missing"))
        val runId = insertRunWithInputs(authority, uniqueSuffix("true_missing_run"), listOf(authority.edge))
        startRun(runId)
        val snapshotId = uniqueSuffix("true_missing_snapshot")

        inTransaction {
            insertSnapshotHeader(snapshotId, runId, authority, 1)
            insertSnapshotEdge(snapshotId, authority, authority.edge, 0)
            insertIssueResult(snapshotId, authority, 0, fixed = true, included = false)
            insertIssuePathEdge(snapshotId, 0, 0, 0)
            insertSnapshotGap(
                snapshotId,
                0,
                authority,
                "COMMIT_BUILD_MISSING",
                "COMMIT_BUILD",
                "COMMIT",
                authority.commitId,
                authority.edge,
            )
            insertRunGap(
                runId,
                authority,
                diagnosticCode = "COMMIT_BUILD_MISSING",
                expectedEdgeType = "COMMIT_BUILD",
                breakEntityType = "COMMIT",
                breakEntityId = authority.commitId,
                predecessor = authority.edge,
            )
            succeedRun(runId, snapshotId)
        }

        assertThat(runStatus(runId)).isEqualTo("SUCCEEDED")
        assertThat(runResultSnapshot(runId)).isEqualTo(snapshotId)
    }

    @Test
    fun `complete fixed ledger rejects false missing snapshot and run gaps`() {
        val authority = seedAuthority(uniqueSuffix("false_missing"))
        val snapshotRun = insertRunWithInputs(authority, uniqueSuffix("false_snapshot_run"), authority.pathEdges)
        startRun(snapshotRun)

        assertSqlFailure("23514", "snapshot gap reports an edge that exists in the fixed input") {
            inTransaction {
                val snapshotId = uniqueSuffix("false_snapshot")
                insertSnapshotHeader(snapshotId, snapshotRun, authority, 1)
                authority.pathEdges.forEachIndexed { ordinal, edge ->
                    insertSnapshotEdge(snapshotId, authority, edge, ordinal)
                }
                insertIssueResult(snapshotId, authority, 0, fixed = true, included = false)
                insertIssuePathEdge(snapshotId, 0, 0, 0)
                insertSnapshotGap(
                    snapshotId,
                    0,
                    authority,
                    "COMMIT_BUILD_MISSING",
                    "COMMIT_BUILD",
                    "COMMIT",
                    authority.commitId,
                    authority.edge,
                )
                succeedRun(snapshotRun, snapshotId)
            }
        }

        val runGapRun = insertRunWithInputs(authority, uniqueSuffix("false_run_gap"), authority.pathEdges)
        startRun(runGapRun)
        assertSqlFailure("23514", "run gap reports an edge that exists in the fixed input") {
            inTransaction {
                val snapshotId = uniqueSuffix("false_run_result")
                insertCompleteSnapshotChildren(snapshotId, runGapRun, authority, 1)
                insertRunGap(
                    runGapRun,
                    authority,
                    diagnosticCode = "COMMIT_BUILD_MISSING",
                    expectedEdgeType = "COMMIT_BUILD",
                    breakEntityType = "COMMIT",
                    breakEntityId = authority.commitId,
                    predecessor = authority.edge,
                )
                succeedRun(runGapRun, snapshotId)
            }
        }

        val completedRun = insertRunWithInputs(authority, uniqueSuffix("terminal_gap"), authority.pathEdges)
        startRun(completedRun)
        createResultSnapshot(completedRun, authority, uniqueSuffix("terminal_gap_result"))

        assertSqlFailure("23514", "V11 run gaps require a running producer and its current result transaction") {
            insertRunGap(
                completedRun,
                authority,
                diagnosticCode = "TEST_RESULT_EVIDENCE_MISSING",
                expectedEdgeType = "TEST_EVIDENCE",
                breakEntityType = "RELEASE",
                breakEntityId = authority.releaseId,
                predecessor = authority.pathEdges.last(),
            )
        }
    }

    @Test
    fun `included issue results must be fixed`() {
        val authority = seedAuthority(uniqueSuffix("flags"))
        val runId = insertRunWithInputs(authority, uniqueSuffix("flags_run"), authority.pathEdges)
        startRun(runId)

        assertThatThrownBy {
            inTransaction {
                val snapshotId = uniqueSuffix("flags_snapshot")
                insertSnapshotHeader(snapshotId, runId, authority, 1)
                insertIssueResult(snapshotId, authority, 0, fixed = false, included = true)
            }
        }.hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `legacy v10 writers remain compatible after v11`() {
        val authority = seedAuthority(uniqueSuffix("legacy"))
        val runId = uniqueSuffix("legacy_run")
        jdbc.sql(
            """
            INSERT INTO traceability_verification_run(
              id, project_id, release_id, verification_run_id, status, policy_version, created_at
            ) VALUES (:id, :projectId, :releaseId, :workerId, 'QUEUED', 'policy-v0', now())
            """.trimIndent(),
        ).param("id", runId).param("projectId", authority.projectId)
            .param("releaseId", authority.releaseId).param("workerId", uniqueSuffix("legacy_worker")).update()
        jdbc.sql(
            """
            INSERT INTO traceability_gap(
              id, project_id, verification_run_id, release_id, issue_id,
              expected_edge_type, reason, diagnostic_code, gap_digest, created_at
            ) VALUES (
              :id, :projectId, :runId, :releaseId, :issueId,
              'COMMIT_BUILD', 'legacy gap', 'EDGE_MISSING', :digest, now()
            )
            """.trimIndent(),
        ).param("id", uniqueSuffix("legacy_gap")).param("projectId", authority.projectId)
            .param("runId", runId).param("releaseId", authority.releaseId)
            .param("issueId", authority.issueId).param("digest", digest(uniqueSuffix("legacy_gap_digest")))
            .update()

        inTransaction {
            val snapshotId = uniqueSuffix("legacy_snapshot")
            insertSnapshotHeader(snapshotId, runId, authority, 1, policyVersion = "policy-v0")
            jdbc.sql(
                """
                INSERT INTO traceability_snapshot_gap(
                  snapshot_id, ordinal, project_id, issue_id, release_id,
                  expected_edge_type, reason, diagnostic_code, gap_digest, created_at
                ) VALUES (
                  :snapshotId, 0, :projectId, :issueId, :releaseId,
                  'COMMIT_BUILD', 'legacy gap', 'EDGE_MISSING', :digest, now()
                )
                """.trimIndent(),
            ).param("snapshotId", snapshotId).param("projectId", authority.projectId)
                .param("issueId", authority.issueId).param("releaseId", authority.releaseId)
                .param("digest", digest(uniqueSuffix("legacy_snapshot_gap_digest"))).update()
        }
    }

    @Test
    fun `m25 policy cannot use a legacy shaped run`() {
        val authority = seedAuthority(uniqueSuffix("m25_shape"))
        assertThatThrownBy {
            insertLegacyRun(authority, uniqueSuffix("m25_legacy_shape"), "m2.5-traceability-policy/v1")
        }.hasRootCauseInstanceOf(SQLException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["SUCCEEDED", "FAILED"])
    fun `legacy terminal runs remain sealed`(terminalStatus: String) {
        val authority = seedAuthority(uniqueSuffix("legacy_terminal_${terminalStatus.lowercase()}"))
        val runId = insertLegacyRun(authority, uniqueSuffix("legacy_terminal"), "policy-v0")
        jdbc.sql(
            "UPDATE traceability_verification_run SET status = 'RUNNING', started_at = now() WHERE id = :runId",
        ).param("runId", runId).update()
        jdbc.sql(
            "UPDATE traceability_verification_run SET status = :status, completed_at = now() WHERE id = :runId",
        ).param("status", terminalStatus).param("runId", runId).update()

        assertThatThrownBy {
            jdbc.sql(
                "UPDATE traceability_verification_run SET status = 'QUEUED', started_at = NULL, completed_at = NULL WHERE id = :runId",
            ).param("runId", runId).update()
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            jdbc.sql("DELETE FROM traceability_verification_run WHERE id = :runId")
                .param("runId", runId).update()
        }.hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `v11 failed terminal run rejects update and delete`() {
        val authority = seedAuthority(uniqueSuffix("v11_failed_terminal"))
        val runId = insertRunWithInputs(authority, uniqueSuffix("failed_terminal"), authority.pathEdges)
        startRun(runId)
        jdbc.sql(
            """
            UPDATE traceability_verification_run
            SET status = 'FAILED', diagnostic_code = 'WORKER_FAILED', completed_at = now()
            WHERE id = :runId
            """.trimIndent(),
        ).param("runId", runId).update()

        assertSqlFailure("55000", "terminal traceability verification run is immutable") {
            jdbc.sql("UPDATE traceability_verification_run SET diagnostic_code = 'RETRY_FAILED' WHERE id = :runId")
                .param("runId", runId).update()
        }
        assertSqlFailure("55000", "terminal traceability verification run is immutable") {
            jdbc.sql("DELETE FROM traceability_verification_run WHERE id = :runId")
                .param("runId", runId).update()
        }
    }

    @Test
    fun `same release snapshot version serializes and the loser can retry`() {
        val authority = seedAuthority(uniqueSuffix("version"))
        val firstRun = insertRunWithInputs(authority, uniqueSuffix("version_first"), authority.pathEdges)
        val secondRun = insertRunWithInputs(authority, uniqueSuffix("version_second"), authority.pathEdges)
        listOf(firstRun, secondRun).forEach { runId ->
            startRun(runId)
        }
        val firstInserted = CountDownLatch(1)
        val contenderStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val winner: Future<Boolean> = pool.submit<Boolean> {
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        insertSnapshotHeader(connection, uniqueSuffix("version_winner"), firstRun, authority, 1)
                        firstInserted.countDown()
                        check(releaseFirst.await(10, TimeUnit.SECONDS))
                        connection.commit()
                        true
                    } catch (failure: Throwable) {
                        connection.rollback()
                        throw failure
                    }
                }
            }
            val contenderPid = AtomicInteger()
            val contender: Future<SQLException> = pool.submit<SQLException> {
                check(firstInserted.await(10, TimeUnit.SECONDS))
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        contenderPid.set(connectionBackendPid(connection))
                        contenderStarted.countDown()
                        insertSnapshotHeader(connection, uniqueSuffix("version_loser"), secondRun, authority, 1)
                        connection.commit()
                        error("contending version unexpectedly committed")
                    } catch (failure: SQLException) {
                        connection.rollback()
                        failure
                    }
                }
            }

            check(contenderStarted.await(10, TimeUnit.SECONDS))
            awaitDatabaseBlock(contenderPid.get())
            releaseFirst.countDown()
            assertThat(winner.get(10, TimeUnit.SECONDS)).isTrue()
            val conflict = contender.get(10, TimeUnit.SECONDS)
            assertThat(conflict.sqlState).isEqualTo("23505")
            assertThat(conflict.message).contains("uq_trace_snapshot_release_version")
        } finally {
            releaseFirst.countDown()
            pool.shutdownNow()
        }

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                insertSnapshotHeader(connection, uniqueSuffix("version_retry"), secondRun, authority, 2)
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    @Test
    fun `v11 upgrades v10 history forward only and repeats safely`() {
        val schema = "verification_upgrade_" + UUID.randomUUID().toString().replace("-", "")
        val v10 = flyway(schema, "10")
        try {
            v10.clean()
            assertThat(v10.migrate().migrationsExecuted).isEqualTo(10)
            val schemaJdbc = JdbcClient.create(dataSource)
            schemaJdbc.sql(
                "INSERT INTO $schema.project(id, project_key, name, created_at) VALUES ('project_history', 'history', 'history', now())",
            ).update()
            schemaJdbc.sql(
                "INSERT INTO $schema.release_record(id, project_id, vehicle, platform, system_version, build_id, status, created_at, updated_at) VALUES ('release_history', 'project_history', 'vehicle', 'platform', '1.0', 'build', 'DRAFT', now(), now())",
            ).update()
            schemaJdbc.sql(
                "INSERT INTO $schema.traceability_verification_run(id, project_id, release_id, verification_run_id, status, policy_version, created_at) VALUES ('run_history', 'project_history', 'release_history', 'request-history', 'QUEUED', 'policy-v0', now())",
            ).update()

            val current = flyway(schema)
            assertThat(current.migrate().migrationsExecuted).isOne()
            assertThat(current.info().current()!!.version.version).isEqualTo("11")
            assertThat(tableNames(schemaJdbc, schema)).contains(
                "traceability_verification_run_edge_input",
                "traceability_snapshot_issue_result",
                "traceability_snapshot_issue_path_edge",
            )
            val historicalInputs = schemaJdbc.sql(
                """
                SELECT issue_snapshot_id, manifest_revision_id, validator_version, input_digest,
                       result_snapshot_id, requested_by, request_id, input_edge_count
                FROM $schema.traceability_verification_run WHERE id = 'run_history'
                """.trimIndent(),
            ).query { resultSet, _ ->
                (1..8).map(resultSet::getObject)
            }.single()
            assertThat(historicalInputs).allSatisfy { assertThat(it).isNull() }
            assertThat(current.migrate().migrationsExecuted).isZero()

            current.clean()
            assertThat(current.migrate().migrationsExecuted).isEqualTo(11)
            assertThat(current.info().pending()).isEmpty()
        } finally {
            v10.clean()
        }
    }

    private fun seedCompletedVerification(suffix: String): CompletedVerification {
        val authority = seedAuthority(suffix)
        val runId = insertRunWithInputs(authority, "${suffix}_run", authority.pathEdges)
        startRun(runId)
        val snapshotId = createResultSnapshot(runId, authority, "${suffix}_result")
        return CompletedVerification(runId, snapshotId)
    }

    private fun seedAuthority(suffix: String): Authority {
        val projectId = "p_$suffix"
        val principalId = "u_$suffix"
        val releaseId = "r_$suffix"
        val manifestRevisionId = "m_$suffix"
        val issueSnapshotId = "is_$suffix"
        val issueId = "i_$suffix"
        val sourceIssueId = "ISSUE-$suffix"
        val commitId = "c_$suffix"
        val buildId = "b_$suffix"
        val artifactId = "a_$suffix"
        jdbc.sql(
            "INSERT INTO project(id, project_key, name, created_at) VALUES (:id, :key, :key, now())",
        ).param("id", projectId).param("key", "key-$suffix").update()
        jdbc.sql(
            "INSERT INTO principal(id, issuer, subject, principal_type, created_at) VALUES (:id, 'fixture', :subject, 'USER', now())",
        ).param("id", principalId).param("subject", suffix).update()
        jdbc.sql(
            """
            INSERT INTO release_record(
              id, project_id, vehicle, platform, system_version, build_id, status, created_at, updated_at
            ) VALUES (:id, :projectId, 'vehicle', 'platform', :version, :buildId, 'DRAFT', now(), now())
            """.trimIndent(),
        ).param("id", releaseId).param("projectId", projectId)
            .param("version", "1.0-$suffix").param("buildId", "build-$suffix").update()
        jdbc.sql(
            """
            INSERT INTO artifact(
              id, identity_digest, artifact_type, locator, checksum_algorithm, checksum_value, created_at
            ) VALUES (:id, :digest, 'BINARY', '{}'::jsonb, 'SHA-256', :checksum, now())
            """.trimIndent(),
        ).param("id", artifactId).param("digest", digest("artifact-$suffix"))
            .param("checksum", digest("checksum-$suffix").removePrefix("sha256:")).update()
        jdbc.sql(
            """
            INSERT INTO manifest_revision(
              id, release_id, revision, content_digest, raw_manifest, canonical_bytes,
              schema_version, state, created_at, updated_at
            ) VALUES (:id, :releaseId, 1, :digest, '{}'::jsonb,
                      convert_to('{}', 'UTF8'), '0.2', 'DRAFT', now(), now())
            """.trimIndent(),
        ).param("id", manifestRevisionId).param("releaseId", releaseId)
            .param("digest", digest("manifest-$suffix")).update()
        jdbc.sql(
            "INSERT INTO manifest_artifact(manifest_id, artifact_id, ordinal, required, created_at) VALUES (:manifestId, :artifactId, 0, true, now())",
        ).param("manifestId", manifestRevisionId).param("artifactId", artifactId).update()
        jdbc.sql("UPDATE manifest_revision SET state = 'LOCKED' WHERE id = :manifestId")
            .param("manifestId", manifestRevisionId).update()
        jdbc.sql("UPDATE release_record SET locked_manifest_id = :manifestId WHERE id = :releaseId")
            .param("manifestId", manifestRevisionId).param("releaseId", releaseId).update()

        val sourceId = "src_$suffix"
        val syncRunId = "sync_$suffix"
        jdbc.sql(
            "INSERT INTO issue_source(id, project_id, source_key, source_type, adapter_version, mapping_version, created_at, updated_at) VALUES (:id, :projectId, :key, 'FIXTURE', 'adapter-v1', 'mapping-v1', now(), now())",
        ).param("id", sourceId).param("projectId", projectId).param("key", suffix).update()
        jdbc.sql(
            "INSERT INTO issue_sync_run(id, project_id, source_id, sync_run_id, status, adapter_version, mapping_version, created_at) VALUES (:id, :projectId, :sourceId, :syncRunId, 'SUCCEEDED', 'adapter-v1', 'mapping-v1', now())",
        ).param("id", syncRunId).param("projectId", projectId).param("sourceId", sourceId)
            .param("syncRunId", "source-run-$suffix").update()
        jdbc.sql(
            """
            INSERT INTO normalized_issue(
              id, project_id, source_id, source_issue_id, title, severity, status,
              raw_status_token, canonical_source_token, raw_severity_token, mapping_warnings,
              source_version, source_reference, observed_at, mapping_version,
              fact_digest, fact_digest_version, created_at
            ) VALUES (
              :id, :projectId, :sourceId, :sourceIssueId, 'issue', 'MAJOR', 'OPEN',
              'open', 'FIXTURE', 'major', '', 'v1', :sourceIssueId, now(), 'mapping-v1',
              :digest, 'normalized-issue-facts/v1', now()
            )
            """.trimIndent(),
        ).param("id", issueId).param("projectId", projectId)
            .param("sourceId", sourceId).param("sourceIssueId", sourceIssueId)
            .param("digest", digest("issue-$suffix")).update()
        inTransaction {
            jdbc.sql(
                """
                INSERT INTO release_issue_snapshot(
                  id, project_id, release_id, sync_run_id, snapshot_version,
                  filter_reference, content_digest, created_at
                ) VALUES (:id, :projectId, :releaseId, :syncRunId, 1, 'all', :digest, now())
                """.trimIndent(),
            ).param("id", issueSnapshotId).param("projectId", projectId)
                .param("releaseId", releaseId).param("syncRunId", syncRunId)
                .param("digest", digest("issue-snapshot-$suffix")).update()
            jdbc.sql(
                """
                INSERT INTO release_issue_snapshot_item(
                  snapshot_id, ordinal, project_id, issue_id, source_issue_id, title,
                  severity, status, raw_status_token, source_version, source_reference,
                  observed_at, mapping_version, fact_digest, created_at
                ) VALUES (
                  :snapshotId, 0, :projectId, :issueId, :sourceIssueId, 'issue',
                  'MAJOR', 'OPEN', 'open', 'v1', :sourceIssueId,
                  now(), 'mapping-v1', :digest, now()
                )
                """.trimIndent(),
            ).param("snapshotId", issueSnapshotId).param("projectId", projectId)
                .param("issueId", issueId).param("sourceIssueId", sourceIssueId)
                .param("digest", digest("snapshot-item-$suffix")).update()
        }
        jdbc.sql(
            "INSERT INTO source_commit(id, project_id, repository, commit_id, created_at) VALUES (:id, :projectId, :repository, :revision, now())",
        ).param("id", commitId).param("projectId", projectId)
            .param("repository", "owner/$suffix").param("revision", "revision-$suffix").update()
        jdbc.sql(
            """
            INSERT INTO build_record(
              id, project_id, provider, build_id, pipeline, source_revision,
              repository, build_attempt, created_at
            ) VALUES (
              :id, :projectId, 'fixture', :providerBuildId, 'pipeline', :sourceRevision,
              :repository, 1, now()
            )
            """.trimIndent(),
        ).param("id", buildId).param("projectId", projectId)
            .param("providerBuildId", "provider-$suffix").param("sourceRevision", "revision-$suffix")
            .param("repository", "owner/$suffix").update()

        val issueCommit = Edge(
            "ISSUE_COMMIT", "e_i_$suffix", "er_i_$suffix", 1, "VALID", digest("edge-i-$suffix"),
            "ISSUE", issueId, "COMMIT", commitId,
        )
        val commitBuild = Edge(
            "COMMIT_BUILD", "e_c_$suffix", "er_c_$suffix", 1, "VALID", digest("edge-c-$suffix"),
            "COMMIT", commitId, "BUILD", buildId,
        )
        val buildArtifact = Edge(
            "BUILD_ARTIFACT", "e_b_$suffix", "er_b_$suffix", 1, "VALID", digest("edge-b-$suffix"),
            "BUILD", buildId, "ARTIFACT", artifactId,
        )
        val base = Authority(
            projectId, principalId, releaseId, manifestRevisionId, issueSnapshotId,
            issueId, sourceIssueId, commitId, buildId, artifactId,
            issueCommit, emptyList(),
        )
        insertEdge(base, issueCommit)
        insertTypedEdge(base, commitBuild)
        insertTypedEdge(base, buildArtifact)
        val artifactRelease = jdbc.sql(
            """
            SELECT source_edge_id, source_edge_revision, fact_digest
            FROM artifact_release_edge_v
            WHERE project_id = :projectId AND release_id = :releaseId AND artifact_id = :artifactId
            """.trimIndent(),
        ).param("projectId", projectId).param("releaseId", releaseId).param("artifactId", artifactId)
            .query { rs, _ ->
                Edge(
                    "ARTIFACT_RELEASE", rs.getString("source_edge_id"), "", rs.getInt("source_edge_revision"),
                    "VALID", rs.getString("fact_digest"), "ARTIFACT", artifactId, "RELEASE", releaseId,
                )
            }.single()
        return base.copy(pathEdges = listOf(issueCommit, commitBuild, buildArtifact, artifactRelease))
    }

    private fun seedIssueCommitEdge(authority: Authority, suffix: String, status: String): Edge {
        val edge = Edge(
            "ISSUE_COMMIT", "e_$suffix", "er_$suffix", 1, status, digest("edge-$suffix"),
            "ISSUE", authority.issueId, "COMMIT", "c_$suffix",
        )
        val commitId = "c_$suffix"
        jdbc.sql(
            "INSERT INTO source_commit(id, project_id, repository, commit_id, created_at) VALUES (:id, :projectId, :repository, :revision, now())",
        ).param("id", commitId).param("projectId", authority.projectId)
            .param("repository", "owner/$suffix").param("revision", "revision-$suffix").update()
        insertEdge(authority, edge, commitId)
        return edge
    }

    private fun insertEdge(authority: Authority, edge: Edge, commitId: String = authority.commitId) {
        jdbc.sql(
            """
            INSERT INTO traceability_edge_identity(
              edge_id, project_id, edge_type, from_entity_id, to_entity_id, created_at
            ) VALUES (:edgeId, :projectId, 'ISSUE_COMMIT', :issueId, :commitId, now())
            """.trimIndent(),
        ).param("edgeId", edge.id).param("projectId", authority.projectId)
            .param("issueId", authority.issueId).param("commitId", commitId).update()
        jdbc.sql(
            """
            INSERT INTO issue_commit_edge_revision(
              id, project_id, edge_id, revision, issue_id, commit_id, source_type,
              source_reference, confidence, verification_status, validator_version,
              content_digest, created_at
            ) VALUES (
              :id, :projectId, :edgeId, :revision, :issueId, :commitId, 'CI',
              :sourceReference, 'HIGH', :status, 'fixture-validator/v1', :digest, now()
            )
            """.trimIndent(),
        ).param("id", edge.revisionId).param("projectId", authority.projectId)
            .param("edgeId", edge.id).param("issueId", authority.issueId)
            .param("revision", edge.revision)
            .param("commitId", commitId).param("sourceReference", "fixture:${edge.id}")
            .param("status", edge.status).param("digest", edge.digest).update()
    }

    private fun insertTypedEdge(authority: Authority, edge: Edge) {
        val table = when (edge.type) {
            "COMMIT_BUILD" -> "commit_build_edge_revision"
            "BUILD_ARTIFACT" -> "build_artifact_edge_revision"
            else -> error("unsupported fixture edge type ${edge.type}")
        }
        val fromColumn = if (edge.type == "COMMIT_BUILD") "commit_id" else "build_id"
        val toColumn = if (edge.type == "COMMIT_BUILD") "build_id" else "artifact_id"
        jdbc.sql(
            """
            INSERT INTO traceability_edge_identity(
              edge_id, project_id, edge_type, from_entity_id, to_entity_id, created_at
            ) VALUES (:edgeId, :projectId, :edgeType, :fromId, :toId, now())
            """.trimIndent(),
        ).param("edgeId", edge.id).param("projectId", authority.projectId)
            .param("edgeType", edge.type).param("fromId", edge.fromId).param("toId", edge.toId).update()
        jdbc.sql(
            """
            INSERT INTO $table(
              id, project_id, edge_id, revision, $fromColumn, $toColumn, source_type,
              source_reference, confidence, verification_status, validator_version,
              content_digest, created_at
            ) VALUES (
              :id, :projectId, :edgeId, :revision, :fromId, :toId, 'CI',
              :sourceReference, 'HIGH', :status, 'fixture-validator/v1', :digest, now()
            )
            """.trimIndent(),
        ).param("id", edge.revisionId).param("projectId", authority.projectId)
            .param("edgeId", edge.id).param("revision", edge.revision)
            .param("fromId", edge.fromId).param("toId", edge.toId)
            .param("sourceReference", "fixture:${edge.id}").param("status", edge.status)
            .param("digest", edge.digest).update()
    }

    private fun appendIssueCommitRevision(authority: Authority, status: String) {
        val previous = authority.edge
        jdbc.sql(
            """
            INSERT INTO issue_commit_edge_revision(
              id, project_id, edge_id, revision, issue_id, commit_id, source_type,
              source_reference, confidence, verification_status, validator_version,
              previous_revision_id, previous_revision, content_digest, created_at
            ) VALUES (
              :id, :projectId, :edgeId, 2, :issueId, :commitId, 'CI',
              :sourceReference, 'HIGH', :status, 'fixture-validator/v1',
              :previousId, 1, :digest, now()
            )
            """.trimIndent(),
        ).param("id", uniqueSuffix("rev2_${status.lowercase()}"))
            .param("projectId", authority.projectId).param("edgeId", previous.id)
            .param("issueId", authority.issueId).param("commitId", authority.commitId)
            .param("sourceReference", "fixture:${previous.id}").param("status", status)
            .param("previousId", previous.revisionId).param("digest", digest("${previous.id}-rev2-$status"))
            .update()
    }

    private fun insertQueuedRun(
        authority: Authority,
        suffix: String,
        issueSnapshotId: String = authority.issueSnapshotId,
        manifestRevisionId: String = authority.manifestRevisionId,
        inputEdgeCount: Int = authority.pathEdges.size,
        inputDigest: String = digest("input-$suffix"),
        policyVersion: String = "m2.5-traceability-policy/v1",
    ): String {
        val runId = "v_$suffix"
        jdbc.sql(
            """
            INSERT INTO traceability_verification_run(
              id, project_id, release_id, verification_run_id, status, policy_version,
              issue_snapshot_id, manifest_revision_id, validator_version, input_digest,
              requested_by, request_id, input_edge_count, created_at
            ) VALUES (
              :id, :projectId, :releaseId, :verificationRunId, 'QUEUED', :policyVersion,
              :issueSnapshotId, :manifestRevisionId, 'traceability-validator/v1', :inputDigest,
              :requestedBy, :requestId, :inputEdgeCount, now()
            )
            """.trimIndent(),
        ).param("id", runId).param("projectId", authority.projectId)
            .param("releaseId", authority.releaseId).param("verificationRunId", "worker-$suffix")
            .param("issueSnapshotId", issueSnapshotId).param("manifestRevisionId", manifestRevisionId)
            .param("inputDigest", inputDigest).param("requestedBy", authority.principalId)
            .param("requestId", "request-$suffix").param("inputEdgeCount", inputEdgeCount)
            .param("policyVersion", policyVersion).update()
        return runId
    }

    private fun insertRunWithInputs(
        authority: Authority,
        suffix: String,
        edges: List<Edge>,
        ordinals: List<Int> = edges.indices.toList(),
        inputDigest: String = digest("input-$suffix"),
    ): String {
        lateinit var runId: String
        inTransaction {
            runId = insertQueuedRun(
                authority,
                suffix,
                inputEdgeCount = edges.size,
                inputDigest = inputDigest,
            )
            edges.zip(ordinals).forEach { (edge, ordinal) ->
                insertRunInput(runId, authority, edge, ordinal)
            }
        }
        return runId
    }

    private fun insertLegacyRun(authority: Authority, suffix: String, policyVersion: String): String {
        val runId = "v_$suffix"
        jdbc.sql(
            """
            INSERT INTO traceability_verification_run(
              id, project_id, release_id, verification_run_id, status, policy_version, created_at
            ) VALUES (:id, :projectId, :releaseId, :workerId, 'QUEUED', :policyVersion, now())
            """.trimIndent(),
        ).param("id", runId).param("projectId", authority.projectId)
            .param("releaseId", authority.releaseId).param("workerId", "worker-$suffix")
            .param("policyVersion", policyVersion).update()
        return runId
    }

    private fun insertRunInput(runId: String, authority: Authority, edge: Edge, ordinal: Int) {
        jdbc.sql(
            """
            INSERT INTO traceability_verification_run_edge_input(
              verification_run_id, ordinal, project_id, edge_type,
              source_edge_id, source_edge_revision, fact_digest, created_at
            ) VALUES (:runId, :ordinal, :projectId, :edgeType, :edgeId, :revision, :digest, now())
            """.trimIndent(),
        ).param("runId", runId).param("ordinal", ordinal).param("projectId", authority.projectId)
            .param("edgeType", edge.type).param("edgeId", edge.id).param("revision", edge.revision)
            .param("digest", edge.digest).update()
    }

    private fun startRun(runId: String) {
        jdbc.sql(
            "UPDATE traceability_verification_run SET status = 'RUNNING', started_at = now() WHERE id = :runId",
        ).param("runId", runId).update()
    }

    private fun succeedRun(runId: String, snapshotId: String) {
        jdbc.sql(
            """
            UPDATE traceability_verification_run
            SET status = 'SUCCEEDED', result_snapshot_id = :snapshotId, completed_at = now()
            WHERE id = :runId
            """.trimIndent(),
        ).param("snapshotId", snapshotId).param("runId", runId).update()
    }

    private fun insertRunGap(
        runId: String,
        authority: Authority,
        diagnosticCode: String,
        expectedEdgeType: String,
        breakEntityType: String,
        breakEntityId: String,
        predecessor: Edge?,
    ) {
        jdbc.sql(
            """
            INSERT INTO traceability_gap(
              id, project_id, verification_run_id, release_id, issue_id,
              expected_edge_type, reason, diagnostic_code, gap_digest,
              break_entity_type, break_entity_id,
              predecessor_edge_type, predecessor_edge_id, predecessor_edge_revision, created_at
            ) VALUES (
              :id, :projectId, :runId, :releaseId, :issueId,
              :expectedEdgeType, 'fixture gap', :diagnosticCode, :digest,
              :breakEntityType, :breakEntityId,
              :predecessorType, :predecessorId, :predecessorRevision, now()
            )
            """.trimIndent(),
        ).param("id", uniqueSuffix("run_gap_row")).param("projectId", authority.projectId)
            .param("runId", runId).param("releaseId", authority.releaseId)
            .param("issueId", authority.issueId).param("expectedEdgeType", expectedEdgeType)
            .param("diagnosticCode", diagnosticCode).param("digest", digest(uniqueSuffix("run_gap_digest")))
            .param("breakEntityType", breakEntityType).param("breakEntityId", breakEntityId)
            .param("predecessorType", predecessor?.type).param("predecessorId", predecessor?.id)
            .param("predecessorRevision", predecessor?.revision).update()
    }

    private fun insertSnapshotGap(
        snapshotId: String,
        ordinal: Int,
        authority: Authority,
        diagnosticCode: String,
        expectedEdgeType: String,
        breakEntityType: String,
        breakEntityId: String,
        predecessor: Edge?,
    ) {
        jdbc.sql(
            """
            INSERT INTO traceability_snapshot_gap(
              snapshot_id, ordinal, project_id, issue_id, release_id,
              expected_edge_type, reason, diagnostic_code, gap_digest,
              break_entity_type, break_entity_id,
              predecessor_edge_type, predecessor_edge_id, predecessor_edge_revision, created_at
            ) VALUES (
              :snapshotId, :ordinal, :projectId, :issueId, :releaseId,
              :expectedEdgeType, 'fixture gap', :diagnosticCode, :digest,
              :breakEntityType, :breakEntityId,
              :predecessorType, :predecessorId, :predecessorRevision, now()
            )
            """.trimIndent(),
        ).param("snapshotId", snapshotId).param("ordinal", ordinal)
            .param("projectId", authority.projectId).param("issueId", authority.issueId)
            .param("releaseId", authority.releaseId).param("expectedEdgeType", expectedEdgeType)
            .param("diagnosticCode", diagnosticCode).param("digest", digest(uniqueSuffix("snapshot_gap_digest")))
            .param("breakEntityType", breakEntityType).param("breakEntityId", breakEntityId)
            .param("predecessorType", predecessor?.type).param("predecessorId", predecessor?.id)
            .param("predecessorRevision", predecessor?.revision).update()
    }

    private fun createResultSnapshot(runId: String, authority: Authority, snapshotId: String): String {
        inTransaction {
            insertCompleteSnapshotChildren(snapshotId, runId, authority, nextSnapshotVersion(authority.releaseId))
            succeedRun(runId, snapshotId)
        }
        return snapshotId
    }

    private fun insertCompleteSnapshotChildren(
        snapshotId: String,
        runId: String,
        authority: Authority,
        version: Int,
        policyVersion: String = "m2.5-traceability-policy/v1",
    ) {
        insertSnapshotHeader(snapshotId, runId, authority, version, policyVersion)
        authority.pathEdges.forEachIndexed { ordinal, edge ->
            insertSnapshotEdge(snapshotId, authority, edge, ordinal)
        }
        insertIssueResult(snapshotId, authority, 0, fixed = true, included = true)
        authority.pathEdges.indices.forEach { ordinal ->
            insertIssuePathEdge(snapshotId, 0, ordinal, ordinal)
        }
        jdbc.sql(
            """
            INSERT INTO traceability_snapshot_gap(
              snapshot_id, ordinal, project_id, issue_id, release_id,
              expected_edge_type, reason, diagnostic_code, gap_digest,
              break_entity_type, break_entity_id,
              predecessor_edge_type, predecessor_edge_id, predecessor_edge_revision, created_at
            ) VALUES (
              :snapshotId, 0, :projectId, :issueId, :releaseId,
              'TEST_EVIDENCE', 'test result evidence is not verified',
              'TEST_RESULT_EVIDENCE_MISSING', :digest,
              'RELEASE', :releaseId, 'ARTIFACT_RELEASE', :edgeId, :revision, now()
            )
            """.trimIndent(),
        ).param("snapshotId", snapshotId).param("projectId", authority.projectId)
            .param("issueId", authority.issueId).param("releaseId", authority.releaseId)
            .param("edgeId", authority.pathEdges.last().id)
            .param("revision", authority.pathEdges.last().revision)
            .param("digest", digest("test-gap-$snapshotId")).update()
    }

    private fun insertSnapshotHeader(
        snapshotId: String,
        runId: String,
        authority: Authority,
        version: Int,
        policyVersion: String = "m2.5-traceability-policy/v1",
    ) {
        jdbc.sql(
            """
            INSERT INTO traceability_snapshot(
              id, project_id, release_id, verification_run_id, version,
              schema_version, policy_version, content_digest, created_at
            ) VALUES (
              :id, :projectId, :releaseId, :runId, :version,
              '0.2', :policyVersion, :digest, now()
            )
            """.trimIndent(),
        ).param("id", snapshotId).param("projectId", authority.projectId)
            .param("releaseId", authority.releaseId).param("runId", runId)
            .param("version", version).param("policyVersion", policyVersion)
            .param("digest", digest("result-$snapshotId")).update()
    }

    private fun insertSnapshotHeader(
        connection: Connection,
        snapshotId: String,
        runId: String,
        authority: Authority,
        version: Int,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO traceability_snapshot(
              id, project_id, release_id, verification_run_id, version,
              schema_version, policy_version, content_digest, created_at
            ) VALUES (?, ?, ?, ?, ?, '0.2', 'm2.5-traceability-policy/v1', ?, now())
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, snapshotId)
            statement.setString(2, authority.projectId)
            statement.setString(3, authority.releaseId)
            statement.setString(4, runId)
            statement.setInt(5, version)
            statement.setString(6, digest("result-$snapshotId"))
            statement.executeUpdate()
        }
    }

    private fun insertSnapshotEdge(snapshotId: String, authority: Authority, edge: Edge, ordinal: Int) {
        val artifactRelease = edge.type == "ARTIFACT_RELEASE"
        jdbc.sql(
            """
            INSERT INTO traceability_snapshot_edge(
              snapshot_id, ordinal, project_id, edge_type, from_entity_type, from_entity_id,
              to_entity_type, to_entity_id, source_edge_id, source_edge_revision, source_type,
              source_reference, confidence, verification_status, validator_version,
              fact_digest, manifest_revision_id, manifest_digest,
              manifest_artifact_ordinal, manifest_artifact_required, created_at
            ) VALUES (
              :snapshotId, :ordinal, :projectId, :edgeType, :fromType, :fromId,
              :toType, :toId, :edgeId, :revision, :sourceType, :sourceReference,
              'HIGH', :status, :validatorVersion, :digest, :manifestId, :manifestDigest,
              :manifestOrdinal, :manifestRequired, now()
            )
            """.trimIndent(),
        ).param("snapshotId", snapshotId).param("ordinal", ordinal)
            .param("projectId", authority.projectId).param("edgeType", edge.type)
            .param("fromType", edge.fromType).param("fromId", edge.fromId)
            .param("toType", edge.toType).param("toId", edge.toId)
            .param("edgeId", edge.id).param("revision", edge.revision)
            .param("sourceType", if (artifactRelease) "MANIFEST" else "CI")
            .param("sourceReference", if (artifactRelease) authority.manifestRevisionId else "fixture:${edge.id}")
            .param("status", edge.status)
            .param("validatorVersion", if (artifactRelease) "artifact-release-manifest-v1" else "fixture-validator/v1")
            .param("digest", edge.digest)
            .param("manifestId", if (artifactRelease) authority.manifestRevisionId else null)
            .param("manifestDigest", if (artifactRelease) digest("manifest-${authority.releaseId.removePrefix("r_")}") else null)
            .param("manifestOrdinal", if (artifactRelease) 0 else null)
            .param("manifestRequired", if (artifactRelease) true else null).update()
    }

    private fun insertIssueResult(
        snapshotId: String,
        authority: Authority,
        ordinal: Int,
        fixed: Boolean,
        included: Boolean,
    ) {
        jdbc.sql(
            """
            INSERT INTO traceability_snapshot_issue_result(
              snapshot_id, ordinal, project_id, issue_id, source_issue_id,
              fixed, included, verified, result_digest, created_at
            ) VALUES (
              :snapshotId, :ordinal, :projectId, :issueId, :sourceIssueId,
              :fixed, :included, false, :digest, now()
            )
            """.trimIndent(),
        ).param("snapshotId", snapshotId).param("ordinal", ordinal)
            .param("projectId", authority.projectId).param("issueId", authority.issueId)
            .param("sourceIssueId", authority.sourceIssueId)
            .param("fixed", fixed).param("included", included)
            .param("digest", digest("issue-result-$snapshotId-$ordinal")).update()
    }

    private fun insertIssuePathEdge(
        snapshotId: String,
        issueOrdinal: Int,
        pathOrdinal: Int,
        snapshotEdgeOrdinal: Int,
    ) {
        jdbc.sql(
            """
            INSERT INTO traceability_snapshot_issue_path_edge(
              snapshot_id, issue_ordinal, path_ordinal, snapshot_edge_ordinal, created_at
            ) VALUES (:snapshotId, :issueOrdinal, :pathOrdinal, :snapshotEdgeOrdinal, now())
            """.trimIndent(),
        ).param("snapshotId", snapshotId).param("issueOrdinal", issueOrdinal)
            .param("pathOrdinal", pathOrdinal).param("snapshotEdgeOrdinal", snapshotEdgeOrdinal).update()
    }

    private fun inTransaction(block: () -> Unit) {
        TransactionTemplate(transactionManager).executeWithoutResult { block() }
    }

    private fun assertSqlFailure(sqlState: String, message: String, block: () -> Unit) {
        val failure = checkNotNull(runCatching(block).exceptionOrNull()) { "expected SQL failure" }
        val sqlFailure = checkNotNull(generateSequence(failure) { it.cause }
            .filterIsInstance<SQLException>()
            .lastOrNull()) { "expected root SQL failure" }
        assertThat(sqlFailure.sqlState).isEqualTo(sqlState)
        assertThat(sqlFailure.message).contains(message)
    }

    private fun updatePolicy(runId: String, policyVersion: String) {
        jdbc.sql("UPDATE traceability_verification_run SET policy_version = :policy WHERE id = :runId")
            .param("policy", policyVersion).param("runId", runId).update()
    }

    private fun runStatus(runId: String): String = jdbc.sql(
        "SELECT status FROM traceability_verification_run WHERE id = :runId",
    ).param("runId", runId).query(String::class.java).single()

    private fun runResultSnapshot(runId: String): String? = jdbc.sql(
        "SELECT result_snapshot_id FROM traceability_verification_run WHERE id = :runId",
    ).param("runId", runId).query(String::class.java).optional().orElse(null)

    private fun nextSnapshotVersion(releaseId: String): Int = jdbc.sql(
        "SELECT coalesce(max(version), 0) + 1 FROM traceability_snapshot WHERE release_id = :releaseId",
    ).param("releaseId", releaseId).query(Int::class.java).single()

    private fun connectionBackendPid(connection: Connection): Int = connection.prepareStatement(
        "SELECT pg_backend_pid()",
    ).use { statement ->
        statement.executeQuery().use { result ->
            check(result.next())
            result.getInt(1)
        }
    }

    private fun awaitDatabaseBlock(blockedPid: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val blocking = jdbc.sql("SELECT cardinality(pg_blocking_pids(:pid))")
                .param("pid", blockedPid).query(Int::class.java).single()
            if (blocking > 0) return
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20))
        }
        error("backend $blockedPid did not block on snapshot version authority")
    }

    private fun flyway(schema: String, target: String? = null): Flyway {
        val configuration = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .schemas(schema).defaultSchema(schema).cleanDisabled(false)
        if (target != null) configuration.target(target)
        return configuration.load()
    }

    private fun tableNames(): List<String> = tableNames(jdbc, "public")

    private fun tableNames(client: JdbcClient, schema: String): List<String> = client.sql(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = :schema AND table_type = 'BASE TABLE'",
    ).param("schema", schema).query(String::class.java).list()

    private fun columnNames(tableName: String): List<String> = jdbc.sql(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = :tableName",
    ).param("tableName", tableName).query(String::class.java).list()

    private fun columnDefault(tableName: String, columnName: String): String? = jdbc.sql(
        """
        SELECT column_default FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = :tableName AND column_name = :columnName
        """.trimIndent(),
    ).param("tableName", tableName).param("columnName", columnName)
        .query(String::class.java).optional().orElse(null)

    private fun writableArtifactReleaseTableCount(): Int = jdbc.sql(
        """
        SELECT count(*)
        FROM information_schema.tables table_record
        WHERE table_record.table_schema = 'public' AND table_record.table_type = 'BASE TABLE'
          AND EXISTS (
            SELECT 1 FROM information_schema.columns column_record
            WHERE column_record.table_schema = table_record.table_schema
              AND column_record.table_name = table_record.table_name
              AND column_record.column_name = 'artifact_id'
          )
          AND EXISTS (
            SELECT 1 FROM information_schema.columns column_record
            WHERE column_record.table_schema = table_record.table_schema
              AND column_record.table_name = table_record.table_name
              AND column_record.column_name = 'release_id'
          )
        """.trimIndent(),
    ).query(Int::class.java).single()

    private fun uniqueSuffix(prefix: String): String =
        (prefix.take(16) + UUID.randomUUID().toString().replace("-", "").take(12)).take(28)

    private fun digest(value: String): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class Authority(
        val projectId: String,
        val principalId: String,
        val releaseId: String,
        val manifestRevisionId: String,
        val issueSnapshotId: String,
        val issueId: String,
        val sourceIssueId: String,
        val commitId: String,
        val buildId: String,
        val artifactId: String,
        val edge: Edge,
        val pathEdges: List<Edge>,
    )

    private data class Edge(
        val type: String,
        val id: String,
        val revisionId: String,
        val revision: Int,
        val status: String,
        val digest: String,
        val fromType: String,
        val fromId: String,
        val toType: String,
        val toId: String,
    )

    private data class CompletedVerification(
        val runId: String,
        val snapshotId: String,
    )
}
