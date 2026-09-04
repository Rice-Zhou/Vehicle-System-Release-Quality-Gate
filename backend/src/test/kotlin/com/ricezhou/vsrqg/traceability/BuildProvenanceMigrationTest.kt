package com.ricezhou.vsrqg.traceability

import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean

class BuildProvenanceMigrationTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `v10 creates provenance authority with 1024 character revision references`() {
        assertThat(tableNames()).contains(
            "traceability_edge_identity",
            "build_provenance_receipt",
            "build_provenance_rejected_receipt",
        )
        assertThat(columnNames("build_record")).contains("repository", "build_attempt")
        listOf(
            "issue_commit_edge_revision",
            "commit_build_edge_revision",
            "build_artifact_edge_revision",
        ).forEach { table ->
            assertThat(columnNames(table)).contains("proof_reference", "proof_digest", "reason_code")
            assertThat(columnLength(jdbc, "public", table, "source_reference")).isEqualTo(1024)
            assertThat(columnLength(jdbc, "public", table, "proof_reference")).isEqualTo(1024)
        }
        assertThat(
            uniqueIndexExists(
                "build_record",
                listOf("project_id", "provider", "pipeline", "build_id", "build_attempt"),
            ),
        ).isTrue()
        assertThat(columnNames("artifact")).doesNotContain("build_id")
        assertThat(writableArtifactReleaseTableCount()).isZero()
        assertThat(columnNames("build_provenance_rejected_receipt"))
            .doesNotContain("raw_payload", "provider_response", "response_body")
        listOf(
            "valid_issue_commit_edge_header",
            "valid_commit_build_edge_header",
            "valid_build_artifact_edge_header",
        ).forEach { triggerName ->
            assertThat(triggerDefinition(triggerName))
                .contains("DEFERRABLE INITIALLY DEFERRED")
        }
    }

    @Test
    fun `v9 provenance upgrades through current v11 and repeats safely`() {
        val schema = isolatedSchema("reference_upgrade")
        val v9 = flyway(schema, "9")
        try {
            v9.clean()
            assertThat(v9.migrate().migrationsExecuted).isEqualTo(9)
            val schemaJdbc = JdbcClient.create(dataSource)
            listOf(
                "issue_commit_edge_revision",
                "commit_build_edge_revision",
                "build_artifact_edge_revision",
            ).forEach { table ->
                assertThat(columnLength(schemaJdbc, schema, table, "source_reference")).isEqualTo(512)
                assertThat(columnLength(schemaJdbc, schema, table, "proof_reference")).isEqualTo(512)
            }

            val current = flyway(schema)
            assertThat(current.migrate().migrationsExecuted).isEqualTo(2)
            assertThat(current.info().current()!!.version.version).isEqualTo("11")
            listOf(
                "issue_commit_edge_revision",
                "commit_build_edge_revision",
                "build_artifact_edge_revision",
            ).forEach { table ->
                assertThat(columnLength(schemaJdbc, schema, table, "source_reference")).isEqualTo(1024)
                assertThat(columnLength(schemaJdbc, schema, table, "proof_reference")).isEqualTo(1024)
            }
            assertThat(current.migrate().migrationsExecuted).isZero()
        } finally {
            v9.clean()
        }
    }

    @Test
    fun `edge headers reject artifact release identities`() {
        seedProject("project_edge_type", "edge-type")

        assertThatThrownBy {
            insertHeader(
                "edge_artifact_release",
                "project_edge_type",
                "ARTIFACT_RELEASE",
                "artifact",
                "release",
            )
        }.hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `v8 legacy builds upgrade through current v11 preserving nullable history and repeats safely`() {
        val schema = isolatedSchema("build_upgrade")
        val v8 = flyway(schema, "8")
        try {
            v8.clean()
            assertThat(v8.migrate().migrationsExecuted).isEqualTo(8)
            val schemaJdbc = JdbcClient.create(dataSource)
            schemaJdbc.sql(
                "INSERT INTO $schema.project(id, project_key, name, created_at) VALUES ('project_history', 'history', 'history', now())",
            ).update()
            schemaJdbc.sql(
                "INSERT INTO $schema.issue_source(id, project_id, source_key, source_type, adapter_version, mapping_version, created_at, updated_at) VALUES ('source_history', 'project_history', 'history', 'FIXTURE', 'adapter-v1', 'mapping-v1', now(), now())",
            ).update()
            schemaJdbc.sql(
                """
                INSERT INTO $schema.normalized_issue(
                  id, project_id, source_id, source_issue_id, title, severity, status,
                  raw_status_token, canonical_source_token, raw_severity_token, mapping_warnings,
                  source_version, source_reference, observed_at, mapping_version,
                  fact_digest, fact_digest_version, created_at
                ) VALUES (
                  'issue_history', 'project_history', 'source_history', 'ISSUE-HISTORY', 'title',
                  'MAJOR', 'OPEN', 'open', 'FIXTURE', 'major', '', 'v1', 'fixture', now(),
                  'mapping-v1', :digest, 'normalized-issue-facts/v1', now()
                )
                """.trimIndent(),
            ).param("digest", digest("issue-history")).update()
            schemaJdbc.sql(
                "INSERT INTO $schema.source_commit(id, project_id, repository, commit_id, created_at) VALUES ('commit_history', 'project_history', 'owner/repository', 'revision', now())",
            ).update()
            schemaJdbc.sql(
                """
                INSERT INTO $schema.build_record(
                  id, project_id, provider, pipeline, build_id, source_revision, created_at
                ) VALUES ('build_history', 'project_history', 'GITHUB_ACTIONS', 'pipeline', '42', 'revision', now())
                """.trimIndent(),
            ).update()
            schemaJdbc.sql(
                """
                INSERT INTO $schema.issue_commit_edge_revision(
                  id, project_id, edge_id, revision, issue_id, commit_id, source_type,
                  source_reference, confidence, verification_status, validator_version,
                  content_digest, created_at
                ) VALUES (
                  'revision_history', 'project_history', 'edge_history', 1, 'issue_history',
                  'commit_history', 'CI', 'run/history', 'HIGH', 'VALID', 'validator-v0',
                  :digest, now()
                )
                """.trimIndent(),
            ).param("digest", digest("revision-history")).update()

            val current = flyway(schema)
            assertThat(current.migrate().migrationsExecuted).isEqualTo(3)
            assertThat(current.info().current()!!.version.version).isEqualTo("11")
            val historicalAuthority = schemaJdbc.sql(
                "SELECT repository, build_attempt FROM $schema.build_record WHERE id = 'build_history'",
            ).query { resultSet, _ ->
                resultSet.getObject("repository") to resultSet.getObject("build_attempt")
            }.single()
            assertThat(historicalAuthority.first).isNull()
            assertThat(historicalAuthority.second).isNull()
            assertThat(
                schemaJdbc.sql(
                    "SELECT count(*) FROM $schema.traceability_edge_identity WHERE edge_id = 'edge_history' AND edge_type = 'ISSUE_COMMIT' AND from_entity_id = 'issue_history' AND to_entity_id = 'commit_history'",
                ).query(Int::class.java).single(),
            ).isOne()
            assertThat(current.migrate().migrationsExecuted).isZero()

            current.clean()
            assertThat(current.migrate().migrationsExecuted).isEqualTo(11)
            assertThat(current.info().pending()).isEmpty()
        } finally {
            v8.clean()
        }
    }

    @Test
    fun `v9 precondition fails closed with a fixed diagnostic`() {
        val schema = isolatedSchema("build_precondition")
        val v8 = flyway(schema, "8")
        try {
            v8.clean()
            assertThat(v8.migrate().migrationsExecuted).isEqualTo(8)
            val schemaJdbc = JdbcClient.create(dataSource)
            schemaJdbc.sql(
                "INSERT INTO $schema.project(id, project_key, name, created_at) VALUES ('project_conflict', 'conflict', 'conflict', now())",
            ).update()
            schemaJdbc.sql("ALTER TABLE $schema.build_record DROP CONSTRAINT uq_build_record_identity").update()
            listOf("a", "b").forEach { suffix ->
                schemaJdbc.sql(
                    """
                    INSERT INTO $schema.build_record(
                      id, project_id, provider, pipeline, build_id, source_revision, created_at
                    ) VALUES (:id, 'project_conflict', 'GITHUB_ACTIONS', 'pipeline', '42', :revision, now())
                    """.trimIndent(),
                ).param("id", "build_$suffix").param("revision", "revision_$suffix").update()
            }

            assertThatThrownBy { flyway(schema).migrate() }
                .hasStackTraceContaining("BUILD_AUTHORITY_PRECONDITION_FAILED")
        } finally {
            v8.clean()
        }
    }

    @Test
    fun `build attempt authority rejects partial and duplicate v2 identities`() {
        seedProject("project_build_attempt", "build-attempt")
        insertBuild("build_history_a", "project_build_attempt", null, null)
        insertBuild("build_history_b", "project_build_attempt", null, null, pipeline = null)
        assertThat(
            jdbc.sql(
                "SELECT count(*) FROM build_record WHERE project_id = 'project_build_attempt' AND repository IS NULL AND build_attempt IS NULL",
            ).query(Int::class.java).single(),
        ).isEqualTo(2)

        assertThatThrownBy {
            insertBuild("build_partial_repository", "project_build_attempt", "owner/repository", null)
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            insertBuild("build_partial_attempt", "project_build_attempt", null, 1)
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            insertBuild("build_zero_attempt", "project_build_attempt", "owner/repository", 0)
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            insertBuild(
                "build_missing_pipeline",
                "project_build_attempt",
                "owner/repository",
                1,
                pipeline = null,
            )
        }.hasRootCauseInstanceOf(SQLException::class.java)

        insertBuild("build_attempt_a", "project_build_attempt", "owner/repository", 1)
        assertThatThrownBy {
            insertBuild("build_attempt_b", "project_build_attempt", "other/repository", 1)
        }.hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `accepted receipts reject a second row for the same build attempt`() {
        seedAuthority("receipt_unique")
        insertAcceptedReceipt("receipt_unique")

        assertThatThrownBy {
            insertAcceptedReceipt("receipt_unique", "receipt_unique_duplicate")
        }.hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `rejected receipts require the accepted project and a distinct digest`() {
        seedAuthority("rejected_scope_a")
        seedAuthority("rejected_scope_b")
        insertAcceptedReceipt("rejected_scope_a")

        assertThatThrownBy {
            insertRejectedReceipt(
                acceptedSuffix = "rejected_scope_a",
                projectSuffix = "rejected_scope_b",
                id = "rejected_cross_project",
            )
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            insertRejectedReceipt(
                acceptedSuffix = "rejected_scope_a",
                rejectedDigest = digest("receipt_rejected_scope_a"),
                id = "rejected_same_digest",
            )
        }.hasRootCauseInstanceOf(SQLException::class.java)

        insertRejectedReceipt("rejected_scope_a")
        assertThat(
            jdbc.sql(
                "SELECT count(*) FROM build_provenance_rejected_receipt WHERE accepted_receipt_id = 'receipt_rejected_scope_a'",
            ).query(Int::class.java).single(),
        ).isOne()
    }

    @Test
    fun `overlapping edge transactions converge after real unique lock contention`() {
        seedProject("project_edge_concurrency", "edge-concurrency")
        val transactionsStarted = CyclicBarrier(2)
        val firstInsertHeld = CountDownLatch(1)
        val contenderWriteStarted = CountDownLatch(1)
        val releaseFirstTransaction = CountDownLatch(1)
        val contenderBackendPid = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)

        try {
            val first = pool.submit<Int> {
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT 1").use { resultSet -> check(resultSet.next()) }
                        }
                        transactionsStarted.await(10, TimeUnit.SECONDS)
                        val inserted = insertConcurrentHeader(connection, "edge_concurrent_first")
                        firstInsertHeld.countDown()
                        check(releaseFirstTransaction.await(10, TimeUnit.SECONDS))
                        connection.commit()
                        inserted
                    } catch (failure: Throwable) {
                        connection.rollback()
                        throw failure
                    }
                }
            }
            val contender = pool.submit<Int> {
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        contenderBackendPid.set(
                            connection.createStatement().use { statement ->
                                statement.executeQuery("SELECT pg_backend_pid()").use { resultSet ->
                                    check(resultSet.next())
                                    resultSet.getInt(1)
                                }
                            },
                        )
                        transactionsStarted.await(10, TimeUnit.SECONDS)
                        check(firstInsertHeld.await(10, TimeUnit.SECONDS))
                        contenderWriteStarted.countDown()
                        val inserted = insertConcurrentHeader(connection, "edge_concurrent_contender")
                        connection.commit()
                        inserted
                    } catch (failure: Throwable) {
                        connection.rollback()
                        throw failure
                    }
                }
            }

            check(contenderWriteStarted.await(10, TimeUnit.SECONDS))
            val lockContentionObserved = awaitLockWait(contenderBackendPid.get())
            releaseFirstTransaction.countDown()
            val inserted = listOf(
                first.get(10, TimeUnit.SECONDS),
                contender.get(10, TimeUnit.SECONDS),
            )

            assertThat(lockContentionObserved).isTrue()
            assertThat(inserted.sum()).isOne()
        } finally {
            releaseFirstTransaction.countDown()
            pool.shutdownNow()
        }
        assertThat(
            jdbc.sql(
                """
                SELECT count(*) FROM traceability_edge_identity
                WHERE project_id = 'project_edge_concurrency'
                  AND edge_type = 'ISSUE_COMMIT'
                  AND from_entity_id = 'issue_shared'
                  AND to_entity_id = 'commit_shared'
                """.trimIndent(),
            ).query(Int::class.java).single(),
        ).isOne()
    }

    private fun insertConcurrentHeader(connection: Connection, edgeId: String): Int =
        connection.prepareStatement(
            """
            INSERT INTO traceability_edge_identity(
              edge_id, project_id, edge_type, from_entity_id, to_entity_id, created_at
            ) VALUES (?, 'project_edge_concurrency', 'ISSUE_COMMIT', 'issue_shared', 'commit_shared', now())
            ON CONFLICT (project_id, edge_type, from_entity_id, to_entity_id) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, edgeId)
            statement.executeUpdate()
        }

    private fun awaitLockWait(backendPid: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val waitingForLock = jdbc.sql(
                """
                SELECT COALESCE(wait_event_type = 'Lock', false)
                FROM pg_stat_activity
                WHERE pid = :backendPid
                """.trimIndent(),
            ).param("backendPid", backendPid).query(Boolean::class.java).optional().orElse(false)
            if (waitingForLock) return true
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
        }
        return false
    }

    @Test
    fun `typed revisions reject mismatched headers mutation and broken chains`() {
        seedAuthority("typed")
        insertHeader(
            "edge_typed_issue_commit",
            "project_typed",
            "ISSUE_COMMIT",
            "issue_typed",
            "commit_typed",
        )
        insertIssueCommitRevision(
            id = "revision_typed_1",
            edgeId = "edge_typed_issue_commit",
            projectId = "project_typed",
            issueId = "issue_typed",
            commitId = "commit_typed",
            revision = 1,
        )

        assertThatThrownBy {
            insertIssueCommitRevision(
                id = "revision_typed_wrong_endpoint",
                edgeId = "edge_typed_issue_commit",
                projectId = "project_typed",
                issueId = "issue_typed_other",
                commitId = "commit_typed",
                revision = 2,
                previousId = "revision_typed_1",
                previousRevision = 1,
            )
        }.hasRootCauseInstanceOf(SQLException::class.java)

        insertHeader(
            "edge_typed_wrong_type",
            "project_typed",
            "COMMIT_BUILD",
            "issue_typed",
            "commit_typed",
        )
        assertThatThrownBy {
            insertIssueCommitRevision(
                id = "revision_typed_wrong_type",
                edgeId = "edge_typed_wrong_type",
                projectId = "project_typed",
                issueId = "issue_typed",
                commitId = "commit_typed",
                revision = 1,
            )
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            jdbc.sql(
                "UPDATE traceability_edge_identity SET to_entity_id = 'commit_typed_other' WHERE edge_id = 'edge_typed_issue_commit'",
            ).update()
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            insertIssueCommitRevision(
                id = "revision_typed_skipped",
                edgeId = "edge_typed_issue_commit",
                projectId = "project_typed",
                issueId = "issue_typed",
                commitId = "commit_typed",
                revision = 3,
                previousId = "revision_typed_1",
                previousRevision = 1,
            )
        }.hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `typed revisions allow source proof and decision fields to evolve`() {
        seedAuthority("evolution")
        insertHeader(
            "edge_evolution",
            "project_evolution",
            "ISSUE_COMMIT",
            "issue_evolution",
            "commit_evolution",
        )
        insertIssueCommitRevision(
            id = "revision_evolution_1",
            edgeId = "edge_evolution",
            projectId = "project_evolution",
            issueId = "issue_evolution",
            commitId = "commit_evolution",
            revision = 1,
        )
        insertIssueCommitRevision(
            id = "revision_evolution_2",
            edgeId = "edge_evolution",
            projectId = "project_evolution",
            issueId = "issue_evolution",
            commitId = "commit_evolution",
            revision = 2,
            previousId = "revision_evolution_1",
            previousRevision = 1,
            sourceType = "CI_REVALIDATION",
            sourceReference = "run/2",
            validatorVersion = "github-actions-provenance/v1",
            proofReference = "https://github.com/owner/repository/actions/runs/2/attempts/1",
            proofDigest = digest("proof-evolution"),
            reasonCode = "PROVENANCE_VERIFIED",
            verificationStatus = "VALID",
            confidence = "MEDIUM",
        )

        assertThat(
            jdbc.sql("SELECT count(*) FROM issue_commit_edge_revision WHERE edge_id = 'edge_evolution'")
                .query(Int::class.java).single(),
        ).isEqualTo(2)
    }

    @Test
    fun `github validator revisions require complete proof metadata`() {
        seedAuthority("proof")
        insertHeader("edge_proof", "project_proof", "ISSUE_COMMIT", "issue_proof", "commit_proof")
        insertHeader(
            "edge_proof_commit_build",
            "project_proof",
            "COMMIT_BUILD",
            "commit_proof",
            "build_proof",
        )
        insertHeader(
            "edge_proof_build_artifact",
            "project_proof",
            "BUILD_ARTIFACT",
            "build_proof",
            "artifact_proof",
        )

        assertThatThrownBy {
            insertIssueCommitRevision(
                id = "revision_proof_missing",
                edgeId = "edge_proof",
                projectId = "project_proof",
                issueId = "issue_proof",
                commitId = "commit_proof",
                revision = 1,
                validatorVersion = "github-actions-provenance/v1",
            )
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            insertCommitBuildRevision(
                "revision_proof_commit_build_missing",
                "edge_proof_commit_build",
                "project_proof",
                "commit_proof",
                "build_proof",
                validatorVersion = "github-actions-provenance/v1",
            )
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            insertBuildArtifactRevision(
                "revision_proof_build_artifact_missing",
                "edge_proof_build_artifact",
                "project_proof",
                "build_proof",
                "artifact_proof",
                validatorVersion = "github-actions-provenance/v1",
            )
        }.hasRootCauseInstanceOf(SQLException::class.java)
        insertIssueCommitRevision(
            id = "revision_proof_complete",
            edgeId = "edge_proof",
            projectId = "project_proof",
            issueId = "issue_proof",
            commitId = "commit_proof",
            revision = 1,
            validatorVersion = "github-actions-provenance/v1",
            proofReference = "https://github.com/owner/repository/actions/runs/1/attempts/1",
            proofDigest = digest("proof-complete"),
            reasonCode = "PROVENANCE_VERIFIED",
            confidence = "MEDIUM",
        )
    }

    @Test
    fun `typed edge headers reject cross project revisions`() {
        seedAuthority("bpv9_scope_a")
        seedAuthority("bpv9_scope_b")
        insertHeader(
            "edge_bpv9_scope_a",
            "project_bpv9_scope_a",
            "ISSUE_COMMIT",
            "issue_bpv9_scope_a",
            "commit_bpv9_scope_a",
        )

        assertThatThrownBy {
            insertIssueCommitRevision(
                id = "revision_bpv9_scope_b",
                edgeId = "edge_bpv9_scope_a",
                projectId = "project_bpv9_scope_b",
                issueId = "issue_bpv9_scope_b",
                commitId = "commit_bpv9_scope_b",
                revision = 1,
            )
        }.hasRootCauseInstanceOf(SQLException::class.java)

        insertHeader(
            "edge_bpv9_scope_a_commit_build",
            "project_bpv9_scope_a",
            "COMMIT_BUILD",
            "commit_bpv9_scope_a",
            "build_bpv9_scope_a",
        )
        assertThatThrownBy {
            insertCommitBuildRevision(
                "revision_bpv9_scope_b_commit_build",
                "edge_bpv9_scope_a_commit_build",
                "project_bpv9_scope_b",
                "commit_bpv9_scope_b",
                "build_bpv9_scope_b",
            )
        }.hasRootCauseInstanceOf(SQLException::class.java)

        insertHeader(
            "edge_bpv9_scope_a_build_artifact",
            "project_bpv9_scope_a",
            "BUILD_ARTIFACT",
            "build_bpv9_scope_a",
            "artifact_bpv9_scope_a",
        )
        assertThatThrownBy {
            insertBuildArtifactRevision(
                "revision_bpv9_scope_b_build_artifact",
                "edge_bpv9_scope_a_build_artifact",
                "project_bpv9_scope_b",
                "build_bpv9_scope_b",
                "artifact_bpv9_scope_b",
            )
        }.hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `three revision tables and both receipt tables are append only`() {
        seedAuthority("bpv9_immutable")
        insertAllHeadersAndRevisions("bpv9_immutable")
        insertAcceptedReceipt("bpv9_immutable")
        insertRejectedReceipt("bpv9_immutable")

        listOf(
            Triple(
                "issue_commit_edge_revision",
                "id = 'revision_bpv9_immutable_issue_commit'",
                "created_at",
            ),
            Triple(
                "commit_build_edge_revision",
                "id = 'revision_bpv9_immutable_commit_build'",
                "created_at",
            ),
            Triple(
                "build_artifact_edge_revision",
                "id = 'revision_bpv9_immutable_build_artifact'",
                "created_at",
            ),
            Triple("build_provenance_receipt", "id = 'receipt_bpv9_immutable'", "created_at"),
            Triple(
                "build_provenance_rejected_receipt",
                "id = 'rejected_bpv9_immutable'",
                "attempted_at",
            ),
        ).forEach { (table, predicate, timestampColumn) ->
            assertThatThrownBy { jdbc.sql("UPDATE $table SET $timestampColumn = now() WHERE $predicate").update() }
                .describedAs("UPDATE on %s", table)
                .hasRootCauseInstanceOf(SQLException::class.java)
            assertThatThrownBy { jdbc.sql("DELETE FROM $table WHERE $predicate").update() }
                .describedAs("DELETE on %s", table)
                .hasRootCauseInstanceOf(SQLException::class.java)
        }
    }

    private fun flyway(schema: String, target: String? = null): Flyway {
        val configuration = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .schemas(schema).defaultSchema(schema).cleanDisabled(false)
        if (target != null) configuration.target(target)
        return configuration.load()
    }

    private fun isolatedSchema(prefix: String) = prefix + "_" + UUID.randomUUID().toString().replace("-", "")

    private fun seedAuthority(suffix: String) {
        val projectId = "project_$suffix"
        seedProject(projectId, suffix)
        jdbc.sql(
            "INSERT INTO principal(id, issuer, subject, principal_type, created_at) VALUES (:id, 'test', :id, 'SERVICE', now())",
        ).param("id", "principal_$suffix").update()
        jdbc.sql(
            "INSERT INTO issue_source(id, project_id, source_key, source_type, adapter_version, mapping_version, created_at, updated_at) VALUES (:id, :projectId, :key, 'FIXTURE', 'adapter-v1', 'mapping-v1', now(), now())",
        ).param("id", "source_$suffix").param("projectId", projectId).param("key", suffix).update()
        listOf("", "_other").forEach { variant ->
            jdbc.sql(
                """
                INSERT INTO normalized_issue(
                  id, project_id, source_id, source_issue_id, title, severity, status,
                  raw_status_token, canonical_source_token, raw_severity_token, mapping_warnings,
                  source_version, source_reference, observed_at, mapping_version,
                  fact_digest, fact_digest_version, created_at
                ) VALUES (
                  :id, :projectId, :sourceId, :sourceIssueId, 'title', 'MAJOR', 'OPEN',
                  'open', 'FIXTURE', 'major', '', 'v1', 'fixture', now(), 'mapping-v1',
                  :digest, 'normalized-issue-facts/v1', now()
                )
                """.trimIndent(),
            ).param("id", "issue_${suffix}$variant").param("projectId", projectId)
                .param("sourceId", "source_$suffix").param("sourceIssueId", "ISSUE-${suffix}$variant")
                .param("digest", digest("issue-${suffix}$variant")).update()
            jdbc.sql(
                "INSERT INTO source_commit(id, project_id, repository, commit_id, created_at) VALUES (:id, :projectId, 'owner/repository', :commitId, now())",
            ).param("id", "commit_${suffix}$variant").param("projectId", projectId)
                .param("commitId", digest("commit-${suffix}$variant").removePrefix("sha256:")).update()
        }
        insertBuild("build_$suffix", projectId, "owner/repository", 1)
        jdbc.sql(
            "INSERT INTO artifact(id, identity_digest, artifact_type, locator, checksum_algorithm, checksum_value, created_at) VALUES (:id, :digest, 'APK', '{}'::jsonb, 'SHA-256', :checksum, now())",
        ).param("id", "artifact_$suffix").param("digest", digest("artifact-$suffix"))
            .param("checksum", sha256("artifact-$suffix")).update()
        jdbc.sql(
            "INSERT INTO release_record(id, project_id, vehicle, platform, system_version, build_id, status, created_at, updated_at) VALUES (:id, :projectId, 'vehicle', 'platform', '1.0', :id, 'DRAFT', now(), now())",
        ).param("id", "release_$suffix").param("projectId", projectId).update()
        jdbc.sql(
            "INSERT INTO issue_sync_run(id, project_id, source_id, sync_run_id, status, adapter_version, mapping_version, created_at) VALUES (:id, :projectId, :sourceId, :runId, 'RUNNING', 'adapter-v1', 'mapping-v1', now())",
        ).param("id", "sync_$suffix").param("projectId", projectId).param("sourceId", "source_$suffix")
            .param("runId", "run-$suffix").update()
        jdbc.sql(
            """
            INSERT INTO release_issue_snapshot(
              id, project_id, release_id, sync_run_id, snapshot_version,
              filter_reference, content_digest, created_at
            ) VALUES (:id, :projectId, :releaseId, :syncId, 1, 'legacy-filter', :digest, now())
            """.trimIndent(),
        ).param("id", "snapshot_$suffix").param("projectId", projectId)
            .param("releaseId", "release_$suffix").param("syncId", "sync_$suffix")
            .param("digest", digest("snapshot-$suffix")).update()
    }

    private fun insertAllHeadersAndRevisions(suffix: String) {
        val projectId = "project_$suffix"
        insertHeader("edge_${suffix}_issue_commit", projectId, "ISSUE_COMMIT", "issue_$suffix", "commit_$suffix")
        insertHeader("edge_${suffix}_commit_build", projectId, "COMMIT_BUILD", "commit_$suffix", "build_$suffix")
        insertHeader("edge_${suffix}_build_artifact", projectId, "BUILD_ARTIFACT", "build_$suffix", "artifact_$suffix")
        insertIssueCommitRevision(
            "revision_${suffix}_issue_commit", "edge_${suffix}_issue_commit", projectId,
            "issue_$suffix", "commit_$suffix", 1,
        )
        insertCommitBuildRevision(
            "revision_${suffix}_commit_build", "edge_${suffix}_commit_build", projectId,
            "commit_$suffix", "build_$suffix",
        )
        insertBuildArtifactRevision(
            "revision_${suffix}_build_artifact", "edge_${suffix}_build_artifact", projectId,
            "build_$suffix", "artifact_$suffix",
        )
    }

    private fun insertAcceptedReceipt(suffix: String, receiptId: String = "receipt_$suffix") {
        jdbc.sql(
            """
            INSERT INTO build_provenance_receipt(
              id, project_id, provider, pipeline, provider_build_id, build_attempt,
              envelope_digest, release_issue_snapshot_id, source_commit_id, build_record_id,
              validator_version, verification_status, confidence, issue_count, artifact_count,
              edge_count, response_body, actor_id, created_at
            ) VALUES (
              :id, :projectId, 'GITHUB_ACTIONS', 'pipeline', 'build-id', 1,
              :digest, :snapshotId, :commitId, :buildId, 'github-actions-provenance/v1',
              'VALID', 'MEDIUM', 1, 1, 3, '{}'::jsonb, :actorId, now()
            )
            """.trimIndent(),
        ).param("id", receiptId).param("projectId", "project_$suffix")
            .param("digest", digest(receiptId)).param("snapshotId", "snapshot_$suffix")
            .param("commitId", "commit_$suffix").param("buildId", "build_$suffix")
            .param("actorId", "principal_$suffix").update()
    }

    private fun insertRejectedReceipt(
        acceptedSuffix: String,
        projectSuffix: String = acceptedSuffix,
        rejectedDigest: String = digest("rejected-$acceptedSuffix"),
        id: String = "rejected_$acceptedSuffix",
    ) {
        jdbc.sql(
            """
            INSERT INTO build_provenance_rejected_receipt(
              id, project_id, accepted_receipt_id, rejected_envelope_digest,
              diagnostic_code, actor_id, attempted_at
            ) VALUES (
              :id, :projectId, :receiptId, :digest,
              'BUILD_PROVENANCE_CONFLICT', :actorId, now()
            )
            """.trimIndent(),
        ).param("id", id).param("projectId", "project_$projectSuffix")
            .param("receiptId", "receipt_$acceptedSuffix").param("digest", rejectedDigest)
            .param("actorId", "principal_$projectSuffix").update()
    }

    private fun insertHeader(edgeId: String, projectId: String, edgeType: String, fromId: String, toId: String) {
        jdbc.sql(
            """
            INSERT INTO traceability_edge_identity(
              edge_id, project_id, edge_type, from_entity_id, to_entity_id, created_at
            ) VALUES (:edgeId, :projectId, :edgeType, :fromId, :toId, now())
            """.trimIndent(),
        ).param("edgeId", edgeId).param("projectId", projectId).param("edgeType", edgeType)
            .param("fromId", fromId).param("toId", toId).update()
    }

    private fun insertIssueCommitRevision(
        id: String,
        edgeId: String,
        projectId: String,
        issueId: String,
        commitId: String,
        revision: Int,
        previousId: String? = null,
        previousRevision: Int? = null,
        sourceType: String = "CI",
        sourceReference: String = "run/1",
        validatorVersion: String = "validator-v1",
        proofReference: String? = null,
        proofDigest: String? = null,
        reasonCode: String? = null,
        verificationStatus: String = "VALID",
        confidence: String = "HIGH",
    ) {
        jdbc.sql(
            """
            INSERT INTO issue_commit_edge_revision(
              id, project_id, edge_id, revision, issue_id, commit_id, source_type, source_reference,
              proof_reference, proof_digest, reason_code, confidence, verification_status,
              validator_version, previous_revision_id, previous_revision, content_digest, created_at
            ) VALUES (
              :id, :projectId, :edgeId, :revision, :issueId, :commitId, :sourceType, :sourceReference,
              :proofReference, :proofDigest, :reasonCode, :confidence, :verificationStatus,
              :validatorVersion, :previousId, :previousRevision, :digest, now()
            )
            """.trimIndent(),
        ).param("id", id).param("projectId", projectId).param("edgeId", edgeId).param("revision", revision)
            .param("issueId", issueId).param("commitId", commitId).param("sourceType", sourceType)
            .param("sourceReference", sourceReference).param("proofReference", proofReference)
            .param("proofDigest", proofDigest).param("reasonCode", reasonCode).param("confidence", confidence)
            .param("verificationStatus", verificationStatus).param("validatorVersion", validatorVersion)
            .param("previousId", previousId).param("previousRevision", previousRevision)
            .param("digest", digest(id)).update()
    }

    private fun insertCommitBuildRevision(
        id: String,
        edgeId: String,
        projectId: String,
        commitId: String,
        buildId: String,
        validatorVersion: String = "validator-v1",
        proofReference: String? = null,
        proofDigest: String? = null,
        reasonCode: String? = null,
    ) {
        jdbc.sql(
            """
            INSERT INTO commit_build_edge_revision(
              id, project_id, edge_id, revision, commit_id, build_id, source_type, source_reference,
              proof_reference, proof_digest, reason_code, confidence, verification_status,
              validator_version, content_digest, created_at
            ) VALUES (:id, :projectId, :edgeId, 1, :commitId, :buildId, 'CI', 'run/1',
                      :proofReference, :proofDigest, :reasonCode, 'HIGH', 'VALID',
                      :validatorVersion, :digest, now())
            """.trimIndent(),
        ).param("id", id).param("projectId", projectId).param("edgeId", edgeId)
            .param("commitId", commitId).param("buildId", buildId).param("proofReference", proofReference)
            .param("proofDigest", proofDigest).param("reasonCode", reasonCode)
            .param("validatorVersion", validatorVersion).param("digest", digest(id)).update()
    }

    private fun insertBuildArtifactRevision(
        id: String,
        edgeId: String,
        projectId: String,
        buildId: String,
        artifactId: String,
        validatorVersion: String = "validator-v1",
        proofReference: String? = null,
        proofDigest: String? = null,
        reasonCode: String? = null,
    ) {
        jdbc.sql(
            """
            INSERT INTO build_artifact_edge_revision(
              id, project_id, edge_id, revision, build_id, artifact_id, source_type, source_reference,
              proof_reference, proof_digest, reason_code, confidence, verification_status,
              validator_version, content_digest, created_at
            ) VALUES (:id, :projectId, :edgeId, 1, :buildId, :artifactId, 'CI', 'run/1',
                      :proofReference, :proofDigest, :reasonCode, 'HIGH', 'VALID',
                      :validatorVersion, :digest, now())
            """.trimIndent(),
        ).param("id", id).param("projectId", projectId).param("edgeId", edgeId)
            .param("buildId", buildId).param("artifactId", artifactId).param("proofReference", proofReference)
            .param("proofDigest", proofDigest).param("reasonCode", reasonCode)
            .param("validatorVersion", validatorVersion).param("digest", digest(id)).update()
    }

    private fun seedProject(id: String, key: String) {
        jdbc.sql(
            "INSERT INTO project(id, project_key, name, created_at) VALUES (:id, :key, :key, now())",
        ).param("id", id).param("key", key).update()
    }

    private fun insertBuild(
        id: String,
        projectId: String,
        repository: String?,
        attempt: Int?,
        pipeline: String? = "pipeline",
    ) {
        jdbc.sql(
            """
            INSERT INTO build_record(
              id, project_id, provider, pipeline, build_id, source_revision,
              repository, build_attempt, created_at
            ) VALUES (
              :id, :projectId, 'GITHUB_ACTIONS', :pipeline, '42', 'revision',
              :repository, :attempt, now()
            )
            """.trimIndent(),
        ).param("id", id).param("projectId", projectId).param("pipeline", pipeline)
            .param("repository", repository)
            .param("attempt", attempt).update()
    }

    private fun tableNames(): List<String> = jdbc.sql(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
    ).query(String::class.java).list()

    private fun columnNames(tableName: String): List<String> = jdbc.sql(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = :tableName",
    ).param("tableName", tableName).query(String::class.java).list()

    private fun columnLength(
        client: JdbcClient,
        schema: String,
        table: String,
        column: String,
    ): Int = client.sql(
        """
        SELECT character_maximum_length
        FROM information_schema.columns
        WHERE table_schema = :schema
          AND table_name = :table
          AND column_name = :column
        """.trimIndent(),
    ).param("schema", schema).param("table", table).param("column", column)
        .query(Int::class.java).single()

    private fun uniqueIndexExists(tableName: String, columns: List<String>): Boolean = jdbc.sql(
        """
        SELECT EXISTS (
          SELECT 1
          FROM pg_index index_record
          JOIN pg_class table_record ON table_record.oid = index_record.indrelid
          JOIN pg_namespace namespace_record ON namespace_record.oid = table_record.relnamespace
          WHERE namespace_record.nspname = 'public'
            AND table_record.relname = :tableName
            AND index_record.indisunique
            AND index_record.indpred IS NOT NULL
            AND (
              SELECT string_agg(attribute_record.attname, ',' ORDER BY key_record.ordinality)
              FROM unnest(index_record.indkey) WITH ORDINALITY key_record(attribute_number, ordinality)
              JOIN pg_attribute attribute_record
                ON attribute_record.attrelid = table_record.oid
               AND attribute_record.attnum = key_record.attribute_number
            ) = :columns
        )
        """.trimIndent(),
    ).param("tableName", tableName).param("columns", columns.joinToString(","))
        .query(Boolean::class.java).single()

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

    private fun triggerDefinition(triggerName: String): String = jdbc.sql(
        """
        SELECT pg_get_triggerdef(trigger_record.oid)
        FROM pg_trigger trigger_record
        JOIN pg_class table_record ON table_record.oid = trigger_record.tgrelid
        JOIN pg_namespace namespace_record ON namespace_record.oid = table_record.relnamespace
        WHERE namespace_record.nspname = 'public'
          AND trigger_record.tgname = :triggerName
          AND NOT trigger_record.tgisinternal
        """.trimIndent(),
    ).param("triggerName", triggerName).query(String::class.java).single()

    private fun digest(value: String) = "sha256:" + sha256(value)

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
