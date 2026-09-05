package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.dockerjava.api.model.ExposedPort
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.traceability.adapter.JdbcTraceabilityVerificationRepository
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.MountableFile

internal class TraceabilityVerificationRecoveryTest : TraceabilityVerificationWorkerPostgresTest() {
    @Autowired
    private lateinit var mapper: ObjectMapper

    @AfterEach
    fun removeRecoveryPoison() {
        jdbc.sql("DROP TRIGGER IF EXISTS reject_traceability_recovery_test ON traceability_snapshot").update()
        jdbc.sql("DROP FUNCTION IF EXISTS reject_traceability_recovery_test()").update()
    }

    @Test
    fun `recovery drill restores canonical digest reclaims persisted work and preserves dead letter history`() {
        val backupRun = start("recovery-backup-${fixture.suffix}")
        assertThat(worker.runNext()).isTrue()
        val backupSnapshotId = requireNotNull(runState(backupRun.verificationRunId)[1])
        val storedDigest = snapshotDigest(backupSnapshotId)

        val restartRun = start("recovery-restart-${fixture.suffix}")
        val firstClaim = requireNotNull(repository.claimNext(Instant.now()))
        assertThat(firstClaim.verificationRunId).isEqualTo(restartRun.verificationRunId)
        val restoredDigest = restoreSnapshotAndRestartDatabase(
            backupRun.verificationRunId,
            backupSnapshotId,
            restartRun.verificationRunId,
        )
        assertThat(restoredDigest).isEqualTo(storedDigest)

        val poisonIssue = TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendLatestSnapshot(fixture)
        TraceabilityVerificationStartFixtureSeeder(jdbc, transactionTemplate)
            .appendIssueCommitForIssue(fixture, poisonIssue.issueId, "recovery-poison")
        val poisonRun = start("recovery-poison-${fixture.suffix}")
        installPoison()
        repeat(3) { attempt ->
            if (attempt > 0) {
                jdbc.sql("UPDATE background_job SET available_at = now() WHERE idempotency_key = :runId")
                    .param("runId", poisonRun.verificationRunId).update()
            }
            assertThat(worker.runNext()).isTrue()
        }
        assertThat(runState(poisonRun.verificationRunId)).containsExactly(
            "FAILED",
            null,
            "TRACEABILITY_VERIFICATION_RETRY_EXHAUSTED",
        )
        assertThat(jobState(poisonRun.verificationRunId).take(2)).containsExactly("DEAD_LETTER", "3")

        removeRecoveryPoison()
        val manualRetry = start("recovery-manual-retry-${fixture.suffix}")
        assertThat(worker.runNext()).isTrue()
        assertThat(runState(manualRetry.verificationRunId)[0]).isEqualTo("SUCCEEDED")
        assertThat(manualRetry.verificationRunId).isNotEqualTo(poisonRun.verificationRunId)
        assertThat(runState(poisonRun.verificationRunId)).containsExactly(
            "FAILED",
            null,
            "TRACEABILITY_VERIFICATION_RETRY_EXHAUSTED",
        )
        assertThat(jobState(poisonRun.verificationRunId).take(2)).containsExactly("DEAD_LETTER", "3")

        writeRecoveryEvidence(restoredDigest)
    }

    private fun restoreSnapshotAndRestartDatabase(
        completedRunId: String,
        snapshotId: String,
        runningRunId: String,
    ): String {
        val outputDirectory = Path.of("build", "m2").toAbsolutePath().normalize()
        Files.createDirectories(outputDirectory)
        val dump = outputDirectory.resolve("traceability-recovery-${UUID.randomUUID()}.dump")
        val sourceDump = "/tmp/${dump.fileName}"
        val restored = PostgreSQLContainer<Nothing>("postgres:17.11")
        return preservingPrimaryFailure(
            block = {
                exportDatabase(sourceDump, dump)
                restored.start()
                restored.copyFileToContainer(MountableFile.forHostPath(dump), "/tmp/traceability-recovery.dump")
                containerCommand(
                    restored,
                    "pg_restore",
                    "-U",
                    restored.username,
                    "-d",
                    restored.databaseName,
                    "--exit-on-error",
                    "--no-owner",
                    "--no-privileges",
                    "/tmp/traceability-recovery.dump",
                )
                assertThat(restored.containerId).isNotEqualTo(PostgresIntegrationTest.postgres.containerId)
                val restoredRepository = restoredRepository(restored)
                val restoredJdbc = restoredJdbc(restored)
                val restoredInput = restoredRepository.loadPinnedExecution(runningRunId).input
                val restoredComputation = TraceabilityVerifier(canonicalizer).verify(restoredInput)
                val restoredHeader = requireNotNull(
                    restoredRepository.findSnapshotHeader(restoredInput.releaseId, snapshotId),
                )
                assertThat(
                    restoredJdbc.sql("SELECT verification_run_id FROM traceability_snapshot WHERE id = :snapshotId")
                        .param("snapshotId", snapshotId).query(String::class.java).single(),
                ).isEqualTo(completedRunId)
                assertRestoredSnapshotFacts(restoredJdbc, snapshotId, restoredComputation)
                assertThat(restoredComputation.contentDigest).isEqualTo(restoredHeader.contentDigest)

                val beforeRestart = postgresProcessIdentity(restored)
                DockerClientFactory.instance().client()
                    .restartContainerCmd(restored.containerId)
                    .withTimeout(10)
                    .exec()
                val afterRestart = awaitFreshProcessIdentity(restored, beforeRestart.postmasterStartedAt)
                assertThat(afterRestart.postmasterStartedAt).isAfter(beforeRestart.postmasterStartedAt)
                assertThat(beforeRestart.backendPid).isPositive()
                assertThat(afterRestart.backendPid).isPositive()

                val reconnectedDataSource = restoredDataSource(restored)
                val reconnectedRepository = JdbcTraceabilityVerificationRepository(
                    JdbcClient.create(reconnectedDataSource),
                    mapper,
                    NoopGovernanceStore,
                )
                val reclaimed = TransactionTemplate(
                    DataSourceTransactionManager(reconnectedDataSource),
                ).execute { reconnectedRepository.claimNext(Instant.now().plusSeconds(301)) }
                assertThat(reclaimed).isNotNull
                assertThat(reclaimed!!.verificationRunId).isEqualTo(runningRunId)
                assertThat(reclaimed.attemptCount).isEqualTo(2)
                restoredComputation.contentDigest
            },
            cleanup = {
                preservingPrimaryFailure(
                    block = { restored.stop() },
                    cleanup = { Files.deleteIfExists(dump) },
                )
            },
        )
    }

    private fun exportDatabase(containerDump: String, hostDump: Path) {
        preservingPrimaryFailure(
            block = {
                containerCommand(
                    PostgresIntegrationTest.postgres,
                    "pg_dump",
                    "-U",
                    PostgresIntegrationTest.postgres.username,
                    "-d",
                    PostgresIntegrationTest.postgres.databaseName,
                    "--format=custom",
                    "--no-owner",
                    "--no-privileges",
                    "--file=$containerDump",
                )
                PostgresIntegrationTest.postgres.copyFileFromContainer(containerDump, hostDump.toString())
            },
            cleanup = { containerCommand(PostgresIntegrationTest.postgres, "rm", "-f", containerDump) },
        )
    }

    private fun assertRestoredSnapshotFacts(
        restoredJdbc: JdbcClient,
        snapshotId: String,
        computation: com.ricezhou.vsrqg.traceability.domain.VerificationComputation,
    ) {
        val issueResults = restoredJdbc.sql(
            """
            SELECT issue_id, result_digest FROM traceability_snapshot_issue_result
            WHERE snapshot_id = :snapshotId ORDER BY ordinal
            """.trimIndent(),
        ).param("snapshotId", snapshotId).query { rs, _ ->
            SnapshotIssueResultBackup(rs.getString("issue_id"), rs.getString("result_digest"))
        }.list()
        assertThat(computation.issueResults.map { it.issueId to it.resultDigest })
            .containsExactlyElementsOf(issueResults.map { it.issueId to it.resultDigest })
        val pathEdges = restoredJdbc.sql(
            """
            SELECT issue.issue_id, path.path_ordinal, edge.source_edge_id, edge.source_edge_revision_id
            FROM traceability_snapshot_issue_path_edge path
            JOIN traceability_snapshot_issue_result issue
              ON issue.snapshot_id = path.snapshot_id AND issue.ordinal = path.issue_ordinal
            JOIN traceability_snapshot_edge edge
              ON edge.snapshot_id = path.snapshot_id AND edge.ordinal = path.snapshot_edge_ordinal
            WHERE path.snapshot_id = :snapshotId
            ORDER BY issue.ordinal, path.path_ordinal
            """.trimIndent(),
        ).param("snapshotId", snapshotId).query { rs, _ ->
            SnapshotPathBackup(
                rs.getString("issue_id"),
                rs.getInt("path_ordinal"),
                rs.getString("source_edge_id"),
                rs.getString("source_edge_revision_id"),
            )
        }.list()
        assertThat(computation.pathEdges.map {
            SnapshotPathBackup(it.issueId, it.pathOrdinal, it.edge.sourceEdgeId, it.edge.sourceEdgeRevisionId)
        }).containsExactlyElementsOf(pathEdges)
        val gaps = restoredJdbc.sql(
            """
            SELECT issue_id, gap_digest FROM traceability_snapshot_gap
            WHERE snapshot_id = :snapshotId ORDER BY ordinal
            """.trimIndent(),
        ).param("snapshotId", snapshotId).query { rs, _ ->
            SnapshotGapBackup(rs.getString("issue_id"), rs.getString("gap_digest"))
        }.list()
        assertThat(computation.gaps.map { SnapshotGapBackup(it.issueId, it.gapDigest) })
            .containsExactlyElementsOf(gaps)
    }

    private fun restoredRepository(container: PostgreSQLContainer<Nothing>) =
        JdbcTraceabilityVerificationRepository(restoredJdbc(container), mapper, NoopGovernanceStore)

    private fun restoredJdbc(container: PostgreSQLContainer<Nothing>) = JdbcClient.create(restoredDataSource(container))

    private fun restoredDataSource(container: PostgreSQLContainer<Nothing>): DriverManagerDataSource {
        val endpoint = inspectPostgresEndpoint(container)
        return DriverManagerDataSource(
            "jdbc:postgresql://${endpoint.host}:${endpoint.port}/${container.databaseName}",
            container.username,
            container.password,
        )
    }

    private fun inspectPostgresEndpoint(container: PostgreSQLContainer<Nothing>): PostgresEndpoint {
        val inspection = DockerClientFactory.instance().client()
            .inspectContainerCmd(container.containerId)
            .exec()
        check(inspection.state?.running == true) { "Restored PostgreSQL container is not running" }
        val binding = inspection.networkSettings?.ports?.bindings
            ?.get(ExposedPort.tcp(PostgreSQLContainer.POSTGRESQL_PORT))
            ?.singleOrNull()
        val port = binding?.hostPortSpec?.toIntOrNull()
        check(port != null && port in 1..65535) { "Restored PostgreSQL container has no current port binding" }
        return PostgresEndpoint(container.host, port)
    }

    private fun postgresProcessIdentity(container: PostgreSQLContainer<Nothing>): PostgresProcessIdentity =
        restoredDataSource(container).connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT 1 AS connection_probe,
                           pg_postmaster_start_time() AS postmaster_started_at,
                           pg_backend_pid() AS backend_pid
                    """.trimIndent(),
                ).use { result ->
                    check(result.next()) { "PostgreSQL process identity query returned no row" }
                    check(result.getInt("connection_probe") == 1) { "PostgreSQL connection probe failed" }
                    PostgresProcessIdentity(
                        result.getTimestamp("postmaster_started_at").toInstant(),
                        result.getInt("backend_pid"),
                    )
                }
            }
        }

    private fun awaitFreshProcessIdentity(
        container: PostgreSQLContainer<Nothing>,
        previousPostmasterStartedAt: Instant,
    ): PostgresProcessIdentity {
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos()
        var lastFailure: Exception? = null
        var lastObservedPostmasterStartedAt: Instant? = null
        while (System.nanoTime() < deadline) {
            try {
                val identity = postgresProcessIdentity(container)
                lastObservedPostmasterStartedAt = identity.postmasterStartedAt
                if (identity.postmasterStartedAt.isAfter(previousPostmasterStartedAt)) return identity
            } catch (failure: Exception) {
                lastFailure = failure
            }
            Thread.sleep(100)
        }
        throw IllegalStateException(
            postgresRestartTimeoutMessage(
                previousPostmasterStartedAt,
                lastObservedPostmasterStartedAt,
                lastFailure,
            ),
            lastFailure,
        )
    }

    private fun <T> preservingPrimaryFailure(block: () -> T, cleanup: () -> Unit): T {
        var primaryFailure: Throwable? = null
        try {
            return block()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                cleanup()
            } catch (cleanupFailure: Throwable) {
                val primary = primaryFailure
                if (primary == null) throw cleanupFailure
                primary.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun containerCommand(container: PostgreSQLContainer<Nothing>, vararg command: String) {
        val result = container.execInContainer(*command)
        check(result.exitCode == 0) {
            "Container command '${command.joinToString(" ")}' failed: ${result.stderr}"
        }
    }

    private fun snapshotDigest(snapshotId: String): String = jdbc.sql(
        "SELECT content_digest FROM traceability_snapshot WHERE id = :snapshotId",
    ).param("snapshotId", snapshotId).query(String::class.java).single()

    private fun installPoison() {
        jdbc.sql(
            """
            CREATE FUNCTION reject_traceability_recovery_test() RETURNS trigger
            LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
              RAISE EXCEPTION 'recovery fixture failure' USING ERRCODE = '40001';
            END;
            ${'$'}${'$'}
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            CREATE TRIGGER reject_traceability_recovery_test
            BEFORE INSERT ON traceability_snapshot
            FOR EACH ROW EXECUTE FUNCTION reject_traceability_recovery_test()
            """.trimIndent(),
        ).update()
    }

    private fun writeRecoveryEvidence(replayDigest: String) {
        val directory = Path.of("build", "m2")
        Files.createDirectories(directory)
        val target = directory.resolve("traceability-recovery.json")
        val temporary = directory.resolve("traceability-recovery.json.${UUID.randomUUID()}.tmp")
        val report = linkedMapOf(
            "schemaVersion" to 1,
            "backupRestore" to "PASS",
            "replayDigest" to replayDigest,
            "dbRestartReclaim" to "PASS",
            "deadLetter" to "PASS",
            "manualRetry" to "PASS",
        )
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), report)
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private data class SnapshotIssueResultBackup(val issueId: String, val resultDigest: String)

private data class SnapshotPathBackup(
    val issueId: String,
    val pathOrdinal: Int,
    val sourceEdgeId: String,
    val sourceEdgeRevisionId: String,
)

private data class SnapshotGapBackup(val issueId: String, val gapDigest: String)

private data class PostgresProcessIdentity(
    val postmasterStartedAt: Instant,
    val backendPid: Int,
)

private data class PostgresEndpoint(val host: String, val port: Int)

internal fun postgresRestartTimeoutMessage(
    beforePostmasterStartedAt: Instant,
    lastObservedPostmasterStartedAt: Instant?,
    lastConnectionFailure: Exception?,
): String {
    val outcome = if (lastObservedPostmasterStartedAt == null) {
        "NO_FRESH_CONNECTION"
    } else {
        "POSTMASTER_START_TIME_NOT_ADVANCED"
    }
    return "Restored PostgreSQL restart verification timed out: " +
        "outcome=$outcome " +
        "beforePostmasterStartedAt=$beforePostmasterStartedAt " +
        "lastObservedPostmasterStartedAt=${lastObservedPostmasterStartedAt ?: "NONE"} " +
        "lastConnectionFailure=${lastConnectionFailure?.javaClass?.name ?: "NONE"}"
}

private object NoopGovernanceStore : GovernanceStore {
    override fun appendAudit(
        projectId: String,
        actorId: String,
        action: String,
        resourceType: String,
        resourceId: String,
        requestId: String,
        reason: String?,
        beforeState: JsonNode?,
        afterState: JsonNode?,
    ) = error("Restored recovery repository must not append Audit")

    override fun appendOutbox(
        eventType: String,
        aggregateType: String,
        aggregateId: String,
        payload: JsonNode,
    ) = error("Restored recovery repository must not append Outbox")
}
