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
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
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
        val runId = insertQueuedRun(authority, uniqueSuffix("input_run"))
        insertRunInput(runId, authority, authority.edge, 0)

        assertThatThrownBy { insertRunInput(runId, authority, otherValidEdge, 0) }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertRunInput(runId, authority, authority.edge, 1) }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertRunInput(runId, authority, invalidEdge, 1) }
            .hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `verification status transitions require a result and seal terminal runs`() {
        val authority = seedAuthority(uniqueSuffix("status"))
        val runId = insertQueuedRun(authority, uniqueSuffix("status_run"))
        insertRunInput(runId, authority, authority.edge, 0)

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
                "UPDATE traceability_verification_run SET status = 'FAILED', diagnostic_code = 'UNAPPROVED_FAILURE', completed_at = now() WHERE id = :runId",
            ).param("runId", runId).update()
        }.hasRootCauseInstanceOf(SQLException::class.java)

        val snapshotId = createResultSnapshot(runId, authority, uniqueSuffix("status_result"))
        succeedRun(runId, snapshotId)
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
        val runId = insertQueuedRun(authority, uniqueSuffix("gap_run"))
        insertRunInput(runId, authority, authority.edge, 0)
        startRun(runId)

        assertThatThrownBy {
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
                      'COMMIT', :commitId, 'ISSUE_COMMIT', :edgeId, 1, now()
                    )
                    """.trimIndent(),
                ).param("snapshotId", snapshotId).param("projectId", authority.projectId)
                    .param("issueId", authority.issueId).param("releaseId", authority.releaseId)
                    .param("commitId", authority.commitId).param("edgeId", authority.edge.id)
                    .param("digest", digest(uniqueSuffix("snapshot_gap_digest"))).update()
            }
        }.hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `issue path edges must belong to the run fixed input`() {
        val authority = seedAuthority(uniqueSuffix("path"))
        val outsideInput = seedIssueCommitEdge(authority, uniqueSuffix("outside_input"), "VALID")
        val runId = insertQueuedRun(authority, uniqueSuffix("path_run"))
        insertRunInput(runId, authority, authority.edge, 0)
        startRun(runId)

        inRollbackTransaction {
            val snapshotId = uniqueSuffix("path_snapshot")
            insertSnapshotHeader(snapshotId, runId, authority, 1)
            insertSnapshotEdge(snapshotId, authority, outsideInput, 0)
            insertIssueResult(snapshotId, authority, 0)
            assertThatThrownBy {
                insertIssuePathEdge(snapshotId, 0, 0, 0)
            }.hasRootCauseInstanceOf(SQLException::class.java)
        }
    }

    @Test
    fun `result snapshots must belong to the completing run`() {
        val authority = seedAuthority(uniqueSuffix("result_scope"))
        val ownerRunId = insertQueuedRun(authority, uniqueSuffix("owner_run"))
        val otherRunId = insertQueuedRun(authority, uniqueSuffix("other_run"))
        listOf(ownerRunId, otherRunId).forEach { runId ->
            insertRunInput(runId, authority, authority.edge, 0)
            startRun(runId)
        }
        val ownerSnapshotId = createResultSnapshot(ownerRunId, authority, uniqueSuffix("owner_snapshot"))

        assertThatThrownBy { succeedRun(otherRunId, ownerSnapshotId) }
            .hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `same release snapshot version serializes and the loser can retry`() {
        val authority = seedAuthority(uniqueSuffix("version"))
        val firstRun = insertQueuedRun(authority, uniqueSuffix("version_first"))
        val secondRun = insertQueuedRun(authority, uniqueSuffix("version_second"))
        listOf(firstRun, secondRun).forEach { runId ->
            insertRunInput(runId, authority, authority.edge, 0)
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
            val contender: Future<Boolean> = pool.submit<Boolean> {
                check(firstInserted.await(10, TimeUnit.SECONDS))
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        contenderStarted.countDown()
                        insertSnapshotHeader(connection, uniqueSuffix("version_loser"), secondRun, authority, 1)
                        connection.commit()
                        false
                    } catch (_: SQLException) {
                        connection.rollback()
                        true
                    } finally {
                        releaseFirst.countDown()
                    }
                }
            }

            check(contenderStarted.await(10, TimeUnit.SECONDS))
            releaseFirst.countDown()
            assertThat(winner.get(10, TimeUnit.SECONDS)).isTrue()
            assertThat(contender.get(10, TimeUnit.SECONDS)).isTrue()
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
                       result_snapshot_id, requested_by, request_id
                FROM $schema.traceability_verification_run WHERE id = 'run_history'
                """.trimIndent(),
            ).query { resultSet, _ ->
                (1..7).map(resultSet::getObject)
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
        val runId = insertQueuedRun(authority, "${suffix}_run")
        insertRunInput(runId, authority, authority.edge, 0)
        startRun(runId)
        val snapshotId = createResultSnapshot(runId, authority, "${suffix}_result")
        succeedRun(runId, snapshotId)
        return CompletedVerification(runId, snapshotId)
    }

    private fun seedAuthority(suffix: String): Authority {
        val authority = Authority(
            projectId = "p_$suffix",
            principalId = "u_$suffix",
            releaseId = "r_$suffix",
            manifestRevisionId = "m_$suffix",
            issueSnapshotId = "is_$suffix",
            issueId = "i_$suffix",
            sourceIssueId = "ISSUE-$suffix",
            commitId = "c_$suffix",
            edge = Edge("e_$suffix", "er_$suffix", "VALID", digest("edge-$suffix")),
        )
        jdbc.sql(
            "INSERT INTO project(id, project_key, name, created_at) VALUES (:id, :key, :key, now())",
        ).param("id", authority.projectId).param("key", "key-$suffix").update()
        jdbc.sql(
            "INSERT INTO principal(id, issuer, subject, principal_type, created_at) VALUES (:id, 'fixture', :subject, 'USER', now())",
        ).param("id", authority.principalId).param("subject", suffix).update()
        jdbc.sql(
            """
            INSERT INTO release_record(
              id, project_id, vehicle, platform, system_version, build_id, status, created_at, updated_at
            ) VALUES (:id, :projectId, 'vehicle', 'platform', :version, :buildId, 'DRAFT', now(), now())
            """.trimIndent(),
        ).param("id", authority.releaseId).param("projectId", authority.projectId)
            .param("version", "1.0-$suffix").param("buildId", "build-$suffix").update()
        jdbc.sql(
            """
            INSERT INTO manifest_revision(
              id, release_id, revision, content_digest, raw_manifest, canonical_bytes,
              schema_version, state, created_at, updated_at
            ) VALUES (:id, :releaseId, 1, :digest, '{}'::jsonb,
                      convert_to('{}', 'UTF8'), '0.2', 'LOCKED', now(), now())
            """.trimIndent(),
        ).param("id", authority.manifestRevisionId).param("releaseId", authority.releaseId)
            .param("digest", digest("manifest-$suffix")).update()
        jdbc.sql("UPDATE release_record SET locked_manifest_id = :manifestId WHERE id = :releaseId")
            .param("manifestId", authority.manifestRevisionId).param("releaseId", authority.releaseId).update()

        val sourceId = "src_$suffix"
        val syncRunId = "sync_$suffix"
        jdbc.sql(
            "INSERT INTO issue_source(id, project_id, source_key, source_type, adapter_version, mapping_version, created_at, updated_at) VALUES (:id, :projectId, :key, 'FIXTURE', 'adapter-v1', 'mapping-v1', now(), now())",
        ).param("id", sourceId).param("projectId", authority.projectId).param("key", suffix).update()
        jdbc.sql(
            "INSERT INTO issue_sync_run(id, project_id, source_id, sync_run_id, status, adapter_version, mapping_version, created_at) VALUES (:id, :projectId, :sourceId, :syncRunId, 'SUCCEEDED', 'adapter-v1', 'mapping-v1', now())",
        ).param("id", syncRunId).param("projectId", authority.projectId).param("sourceId", sourceId)
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
        ).param("id", authority.issueId).param("projectId", authority.projectId)
            .param("sourceId", sourceId).param("sourceIssueId", authority.sourceIssueId)
            .param("digest", digest("issue-$suffix")).update()
        inTransaction {
            jdbc.sql(
                """
                INSERT INTO release_issue_snapshot(
                  id, project_id, release_id, sync_run_id, snapshot_version,
                  filter_reference, content_digest, created_at
                ) VALUES (:id, :projectId, :releaseId, :syncRunId, 1, 'all', :digest, now())
                """.trimIndent(),
            ).param("id", authority.issueSnapshotId).param("projectId", authority.projectId)
                .param("releaseId", authority.releaseId).param("syncRunId", syncRunId)
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
            ).param("snapshotId", authority.issueSnapshotId).param("projectId", authority.projectId)
                .param("issueId", authority.issueId).param("sourceIssueId", authority.sourceIssueId)
                .param("digest", digest("snapshot-item-$suffix")).update()
        }
        jdbc.sql(
            "INSERT INTO source_commit(id, project_id, repository, commit_id, created_at) VALUES (:id, :projectId, :repository, :revision, now())",
        ).param("id", authority.commitId).param("projectId", authority.projectId)
            .param("repository", "owner/$suffix").param("revision", "revision-$suffix").update()
        insertEdge(authority, authority.edge)
        return authority
    }

    private fun seedIssueCommitEdge(authority: Authority, suffix: String, status: String): Edge {
        val edge = Edge("e_$suffix", "er_$suffix", status, digest("edge-$suffix"))
        insertEdge(authority, edge)
        return edge
    }

    private fun insertEdge(authority: Authority, edge: Edge) {
        jdbc.sql(
            """
            INSERT INTO traceability_edge_identity(
              edge_id, project_id, edge_type, from_entity_id, to_entity_id, created_at
            ) VALUES (:edgeId, :projectId, 'ISSUE_COMMIT', :issueId, :commitId, now())
            """.trimIndent(),
        ).param("edgeId", edge.id).param("projectId", authority.projectId)
            .param("issueId", authority.issueId).param("commitId", authority.commitId).update()
        jdbc.sql(
            """
            INSERT INTO issue_commit_edge_revision(
              id, project_id, edge_id, revision, issue_id, commit_id, source_type,
              source_reference, confidence, verification_status, validator_version,
              content_digest, created_at
            ) VALUES (
              :id, :projectId, :edgeId, 1, :issueId, :commitId, 'CI',
              :sourceReference, 'HIGH', :status, 'fixture-validator/v1', :digest, now()
            )
            """.trimIndent(),
        ).param("id", edge.revisionId).param("projectId", authority.projectId)
            .param("edgeId", edge.id).param("issueId", authority.issueId)
            .param("commitId", authority.commitId).param("sourceReference", "fixture:${edge.id}")
            .param("status", edge.status).param("digest", edge.digest).update()
    }

    private fun insertQueuedRun(
        authority: Authority,
        suffix: String,
        issueSnapshotId: String = authority.issueSnapshotId,
        manifestRevisionId: String = authority.manifestRevisionId,
    ): String {
        val runId = "v_$suffix"
        jdbc.sql(
            """
            INSERT INTO traceability_verification_run(
              id, project_id, release_id, verification_run_id, status, policy_version,
              issue_snapshot_id, manifest_revision_id, validator_version, input_digest,
              requested_by, request_id, created_at
            ) VALUES (
              :id, :projectId, :releaseId, :verificationRunId, 'QUEUED', 'policy-v1',
              :issueSnapshotId, :manifestRevisionId, 'traceability-validator/v1', :inputDigest,
              :requestedBy, :requestId, now()
            )
            """.trimIndent(),
        ).param("id", runId).param("projectId", authority.projectId)
            .param("releaseId", authority.releaseId).param("verificationRunId", "worker-$suffix")
            .param("issueSnapshotId", issueSnapshotId).param("manifestRevisionId", manifestRevisionId)
            .param("inputDigest", digest("input-$suffix")).param("requestedBy", authority.principalId)
            .param("requestId", "request-$suffix").update()
        return runId
    }

    private fun insertRunInput(runId: String, authority: Authority, edge: Edge, ordinal: Int) {
        jdbc.sql(
            """
            INSERT INTO traceability_verification_run_edge_input(
              verification_run_id, ordinal, project_id, edge_type,
              source_edge_id, source_edge_revision, fact_digest, created_at
            ) VALUES (:runId, :ordinal, :projectId, 'ISSUE_COMMIT', :edgeId, 1, :digest, now())
            """.trimIndent(),
        ).param("runId", runId).param("ordinal", ordinal).param("projectId", authority.projectId)
            .param("edgeId", edge.id).param("digest", edge.digest).update()
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

    private fun createResultSnapshot(runId: String, authority: Authority, snapshotId: String): String {
        inTransaction {
            insertSnapshotHeader(snapshotId, runId, authority, 1)
            insertSnapshotEdge(snapshotId, authority, authority.edge, 0)
            insertIssueResult(snapshotId, authority, 0)
            insertIssuePathEdge(snapshotId, 0, 0, 0)
        }
        return snapshotId
    }

    private fun insertSnapshotHeader(
        snapshotId: String,
        runId: String,
        authority: Authority,
        version: Int,
    ) {
        jdbc.sql(
            """
            INSERT INTO traceability_snapshot(
              id, project_id, release_id, verification_run_id, version,
              schema_version, policy_version, content_digest, created_at
            ) VALUES (
              :id, :projectId, :releaseId, :runId, :version,
              '0.2', 'policy-v1', :digest, now()
            )
            """.trimIndent(),
        ).param("id", snapshotId).param("projectId", authority.projectId)
            .param("releaseId", authority.releaseId).param("runId", runId)
            .param("version", version).param("digest", digest("result-$snapshotId")).update()
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
            ) VALUES (?, ?, ?, ?, ?, '0.2', 'policy-v1', ?, now())
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
        jdbc.sql(
            """
            INSERT INTO traceability_snapshot_edge(
              snapshot_id, ordinal, project_id, edge_type, from_entity_type, from_entity_id,
              to_entity_type, to_entity_id, source_edge_id, source_edge_revision, source_type,
              source_reference, confidence, verification_status, validator_version,
              fact_digest, created_at
            ) VALUES (
              :snapshotId, :ordinal, :projectId, 'ISSUE_COMMIT', 'ISSUE', :issueId,
              'COMMIT', :commitId, :edgeId, 1, 'CI', :sourceReference,
              'HIGH', :status, 'fixture-validator/v1', :digest, now()
            )
            """.trimIndent(),
        ).param("snapshotId", snapshotId).param("ordinal", ordinal)
            .param("projectId", authority.projectId).param("issueId", authority.issueId)
            .param("commitId", authority.commitId).param("edgeId", edge.id)
            .param("sourceReference", "fixture:${edge.id}").param("status", edge.status)
            .param("digest", edge.digest).update()
    }

    private fun insertIssueResult(snapshotId: String, authority: Authority, ordinal: Int) {
        jdbc.sql(
            """
            INSERT INTO traceability_snapshot_issue_result(
              snapshot_id, ordinal, project_id, issue_id, source_issue_id,
              fixed, included, verified, result_digest, created_at
            ) VALUES (
              :snapshotId, :ordinal, :projectId, :issueId, :sourceIssueId,
              false, true, false, :digest, now()
            )
            """.trimIndent(),
        ).param("snapshotId", snapshotId).param("ordinal", ordinal)
            .param("projectId", authority.projectId).param("issueId", authority.issueId)
            .param("sourceIssueId", authority.sourceIssueId)
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

    private fun inRollbackTransaction(block: () -> Unit) {
        TransactionTemplate(transactionManager).executeWithoutResult { status ->
            block()
            status.setRollbackOnly()
        }
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
        val edge: Edge,
    )

    private data class Edge(
        val id: String,
        val revisionId: String,
        val status: String,
        val digest: String,
    )

    private data class CompletedVerification(
        val runId: String,
        val snapshotId: String,
    )
}
