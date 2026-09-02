package com.ricezhou.vsrqg.shared

import java.security.MessageDigest
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
class M2MigrationConstraintTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val m2Tables = listOf(
        "background_job",
        "issue_mapping_profile",
        "issue_source",
        "issue_sync_run",
        "issue_sync_run_item",
        "issue_sync_cursor",
        "normalized_issue",
        "release_issue_snapshot",
        "release_issue_snapshot_item",
        "source_commit",
        "build_record",
        "issue_commit_edge_revision",
        "commit_build_edge_revision",
        "build_artifact_edge_revision",
        "traceability_verification_run",
        "traceability_gap",
        "traceability_snapshot",
        "traceability_snapshot_edge",
        "traceability_snapshot_gap",
    )

    private val m1Tables = listOf(
        "project", "principal", "project_assignment", "release_record", "release_state_history",
        "manifest_revision", "artifact", "manifest_artifact", "manifest_validation", "audit_event",
        "idempotency_record", "outbox_event",
    )

    @Test
    fun `flyway creates the complete M2 authority schema and read only manifest edge view`() {
        val tablesAddedAfterM1 = jdbc.sql(
            """
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
              AND table_name NOT IN (:m1Tables)
              AND table_name <> 'flyway_schema_history'
            ORDER BY table_name
            """.trimIndent(),
        ).param("m1Tables", m1Tables).query(String::class.java).list()
        assertThat(tablesAddedAfterM1).containsExactlyElementsOf(m2Tables.sorted())

        val viewCount = jdbc.sql(
            """
            SELECT count(*) FROM information_schema.views
            WHERE table_schema = 'public' AND table_name = 'artifact_release_edge_v'
            """.trimIndent(),
        ).query(Int::class.java).single()
        assertThat(viewCount).isOne()

        val artifactBuildId = jdbc.sql(
            """
            SELECT count(*) FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'artifact' AND column_name = 'build_id'
            """.trimIndent(),
        ).query(Int::class.java).single()
        assertThat(artifactBuildId).isZero()

        val writableArtifactReleaseTables = jdbc.sql(
            """
            SELECT count(*)
            FROM information_schema.tables t
            WHERE t.table_schema = 'public' AND t.table_type = 'BASE TABLE'
              AND EXISTS (SELECT 1 FROM information_schema.columns c WHERE c.table_schema = t.table_schema AND c.table_name = t.table_name AND c.column_name = 'artifact_id')
              AND EXISTS (SELECT 1 FROM information_schema.columns c WHERE c.table_schema = t.table_schema AND c.table_name = t.table_name AND c.column_name = 'release_id')
            """.trimIndent(),
        ).query(Int::class.java).single()
        assertThat(writableArtifactReleaseTables).isZero()
    }

    @Test
    fun `flyway preserves V5 history through V6 upgrade clean install and repeat migration`() {
        val schema = "m2_migration_" + UUID.randomUUID().toString().replace("-", "")
        val upgrade = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .schemas(schema).defaultSchema(schema).cleanDisabled(false).target("5").load()
        try {
            upgrade.clean()
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(5)
            val historyJdbc = JdbcClient.create(dataSource)
            historyJdbc.sql("INSERT INTO $schema.project(id, project_key, name, created_at) VALUES ('project_history', 'history', 'history', now())").update()
            historyJdbc.sql("INSERT INTO $schema.issue_source(id, project_id, source_key, source_type, adapter_version, mapping_version, created_at, updated_at) VALUES ('source_history', 'project_history', 'history', 'FIXTURE', 'adapter-v0', 'mapping-v0', now(), now())").update()
            historyJdbc.sql("INSERT INTO $schema.release_record(id, project_id, vehicle, platform, system_version, build_id, status, created_at, updated_at) VALUES ('release_history', 'project_history', 'vehicle', 'platform', '1.0', 'build-history', 'DRAFT', now(), now())").update()
            historyJdbc.sql("INSERT INTO $schema.issue_sync_run(id, project_id, source_id, sync_run_id, status, adapter_version, mapping_version, created_at) VALUES ('sync_history', 'project_history', 'source_history', 'run-history', 'SUCCEEDED', 'adapter-v0', 'mapping-v0', now())").update()
            historyJdbc.sql("INSERT INTO $schema.release_issue_snapshot(id, project_id, release_id, sync_run_id, snapshot_version, filter_reference, content_digest, created_at) VALUES ('snapshot_history', 'project_history', 'release_history', 'sync_history', 1, 'legacy-filter', :digest, now())")
                .param("digest", digest("history-snapshot")).update()

            val current = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema).cleanDisabled(false).load()
            assertThat(current.migrate().migrationsExecuted).isOne()
            assertThat(current.info().current()!!.version.version).isEqualTo("6")
            val historicalRun = historyJdbc.sql(
                "SELECT id, result_set_mode, filter_reference FROM $schema.issue_sync_run WHERE id = 'sync_history'",
            ).query { resultSet, _ ->
                resultSet.getString("id") to listOf(
                    resultSet.getObject("result_set_mode"),
                    resultSet.getObject("filter_reference"),
                )
            }.single()
            assertThat(historicalRun.first).isEqualTo("sync_history")
            assertThat(historicalRun.second).allSatisfy { assertThat(it).isNull() }
            val historicalSnapshot = historyJdbc.sql(
                """
                SELECT id, source_id, source_watermark, adapter_version, mapping_version,
                       canonicalization_version, age_policy_version,
                       observed_count, tombstone_count, selected_count
                FROM $schema.release_issue_snapshot WHERE id = 'snapshot_history'
                """.trimIndent(),
            ).query { resultSet, _ ->
                resultSet.getString("id") to listOf(
                    resultSet.getObject("source_id"),
                    resultSet.getObject("source_watermark"),
                    resultSet.getObject("adapter_version"),
                    resultSet.getObject("mapping_version"),
                    resultSet.getObject("canonicalization_version"),
                    resultSet.getObject("age_policy_version"),
                    resultSet.getObject("observed_count"),
                    resultSet.getObject("tombstone_count"),
                    resultSet.getObject("selected_count"),
                )
            }.single()
            assertThat(historicalSnapshot.first).isEqualTo("snapshot_history")
            assertThat(historicalSnapshot.second).allSatisfy { assertThat(it).isNull() }
            assertThat(current.migrate().migrationsExecuted).isZero()

            current.clean()
            assertThat(current.migrate().migrationsExecuted).isEqualTo(6)
            assertThat(current.info().pending()).isEmpty()
        } finally {
            upgrade.clean()
        }
    }

    @Test
    fun `M2 schema declares all required foreign keys uniqueness checks and indexes`() {
        val nonRestrictingForeignKeys = jdbc.sql(
            """
            SELECT count(*)
            FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname = 'public' AND t.relname IN (:tables)
              AND c.contype = 'f' AND c.confdeltype <> 'r'
            """.trimIndent(),
        ).param("tables", m2Tables).query(Int::class.java).single()
        assertThat(nonRestrictingForeignKeys).isZero()

        assertForeignKeyNames(
            setOf(
                "fk_background_job_project", "fk_background_job_outbox", "fk_issue_source_project",
                "fk_mapping_profile_source_project", "fk_mapping_profile_creator",
                "fk_sync_run_source_project", "fk_sync_cursor_source_project", "fk_sync_cursor_run_source_project",
                "fk_normalized_issue_source_project", "fk_sync_run_item_run_source_project",
                "fk_sync_run_item_issue_source_project", "fk_issue_snapshot_release_project", "fk_issue_snapshot_run_project",
                "fk_issue_snapshot_run_source_project",
                "fk_issue_snapshot_item_snapshot_project", "fk_issue_snapshot_item_issue_project",
                "fk_source_commit_project", "fk_build_record_project",
                "fk_issue_commit_issue_project", "fk_issue_commit_commit_project", "fk_issue_commit_verified_by",
                "fk_issue_commit_previous", "fk_commit_build_commit_project", "fk_commit_build_build_project",
                "fk_commit_build_verified_by", "fk_commit_build_previous", "fk_build_artifact_build_project",
                "fk_build_artifact_artifact", "fk_build_artifact_verified_by", "fk_build_artifact_previous",
                "fk_verification_run_release_project", "fk_gap_run_release_project", "fk_gap_issue_project",
                "fk_trace_snapshot_release_project", "fk_trace_snapshot_run_release_project",
                "fk_snapshot_edge_snapshot_project", "fk_snapshot_gap_snapshot_release_project",
                "fk_snapshot_gap_issue_project",
            ),
        )
        assertConstraintNames(
            "u",
            setOf(
                "uq_issue_source_project_key", "uq_sync_run_source_identity", "uq_normalized_issue_source_version_mapping",
                "uq_normalized_issue_id_source_project", "uq_normalized_issue_observation_identity",
                "uq_sync_run_item_issue", "uq_sync_run_item_source_issue",
                "uq_issue_snapshot_release_version", "uq_issue_snapshot_digest", "uq_issue_snapshot_run_filter",
                "uq_issue_snapshot_item_issue",
                "uq_source_commit_identity", "uq_build_record_identity",
                "uq_issue_commit_edge_revision", "uq_issue_commit_revision_identity",
                "uq_commit_build_edge_revision", "uq_commit_build_revision_identity",
                "uq_build_artifact_edge_revision", "uq_build_artifact_revision_identity",
                "uq_verification_run_identity", "uq_gap_run_digest", "uq_trace_snapshot_release_version",
                "uq_trace_snapshot_digest", "uq_trace_snapshot_id_release_project", "uq_snapshot_edge_digest",
                "uq_snapshot_gap_digest",
            ),
        )
        assertConstraintNames(
            "c",
            setOf(
                "ck_background_job_status", "ck_issue_source_type", "ck_sync_run_status",
                "ck_issue_sync_run_result_set_mode", "ck_sync_run_item_ordinal", "ck_normalized_issue_status",
                "ck_normalized_issue_digest", "ck_issue_snapshot_digest", "ck_issue_snapshot_counts",
                "ck_issue_snapshot_item_digest",
                "ck_issue_commit_revision_chain", "ck_issue_commit_digest", "ck_issue_commit_confidence", "ck_issue_commit_status",
                "ck_commit_build_revision_chain", "ck_commit_build_digest", "ck_commit_build_confidence", "ck_commit_build_status",
                "ck_build_artifact_revision_chain", "ck_build_artifact_digest", "ck_build_artifact_confidence", "ck_build_artifact_status",
                "ck_verification_run_status", "ck_gap_digest", "ck_trace_snapshot_digest", "ck_snapshot_edge_digest",
                "ck_snapshot_edge_confidence", "ck_snapshot_edge_status", "ck_snapshot_edge_manifest_authority",
                "ck_snapshot_gap_digest",
            ),
        )

        val requiredIndexes = setOf(
            "ix_background_job_dispatch", "ix_background_job_project", "ix_background_job_outbox",
            "ix_issue_sync_run_source_created", "ix_issue_sync_run_item_issue", "ix_issue_sync_cursor_run",
            "ix_normalized_issue_source_observed",
            "ix_issue_snapshot_release_version", "ix_issue_snapshot_sync_run", "ix_issue_snapshot_item_issue",
            "ix_source_commit_project", "ix_build_record_project", "ix_issue_commit_edge", "ix_issue_commit_endpoints",
            "ix_issue_commit_commit", "ix_issue_commit_verified_by", "ix_issue_commit_status_confidence",
            "ix_commit_build_edge", "ix_commit_build_endpoints", "ix_commit_build_build", "ix_commit_build_verified_by",
            "ix_commit_build_status_confidence", "ix_build_artifact_edge", "ix_build_artifact_endpoints",
            "ix_build_artifact_artifact", "ix_build_artifact_verified_by", "ix_build_artifact_status_confidence",
            "ix_verification_run_release_created", "ix_gap_run", "ix_gap_issue", "ix_trace_snapshot_release_version",
            "ix_trace_snapshot_verification_run", "ix_snapshot_edge_source", "ix_snapshot_gap_issue",
            "ix_snapshot_gap_release",
        )
        val indexes = jdbc.sql(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND indexname IN (:names)",
        ).param("names", requiredIndexes).query(String::class.java).list()
        assertThat(indexes).containsExactlyInAnyOrderElementsOf(requiredIndexes)

        val requiredTriggers = setOf(
            "stable_issue_commit_edge_identity", "stable_commit_build_edge_identity", "stable_build_artifact_edge_identity",
            "validate_issue_sync_run_item_insert", "immutable_issue_sync_run_item", "seal_terminal_issue_sync_run",
            "immutable_release_issue_snapshot", "immutable_release_issue_snapshot_item",
            "immutable_issue_commit_edge_revision", "immutable_commit_build_edge_revision", "immutable_build_artifact_edge_revision",
            "immutable_traceability_gap", "immutable_traceability_snapshot", "immutable_traceability_snapshot_edge",
            "immutable_traceability_snapshot_gap", "validate_traceability_snapshot_edge_source",
            "validate_release_artifact_snapshot_authority",
            "validate_release_issue_snapshot_v1", "atomic_release_issue_snapshot_item", "atomic_traceability_snapshot_edge",
            "atomic_traceability_snapshot_gap", "trusted_release_issue_snapshot_transaction",
            "trusted_traceability_snapshot_transaction",
        )
        val triggers = jdbc.sql(
            "SELECT tgname FROM pg_trigger WHERE NOT tgisinternal AND tgname IN (:names)",
        ).param("names", requiredTriggers).query(String::class.java).list()
        assertThat(triggers).containsExactlyInAnyOrderElementsOf(requiredTriggers)
    }

    @Test
    fun `m2 snapshot authority records exact observations and seals terminal runs`() {
        assertThat(tableNames()).contains("issue_sync_run_item")
        assertThat(columnNames("issue_sync_run"))
            .contains("result_set_mode", "filter_reference")
        assertThat(columnNames("release_issue_snapshot")).contains(
            "source_id", "source_watermark", "adapter_version", "mapping_version",
            "canonicalization_version", "age_policy_version",
            "observed_count", "tombstone_count", "selected_count",
        )
        assertThat(columnNames("issue_sync_run_item")).containsExactlyInAnyOrder(
            "sync_run_id", "ordinal", "project_id", "source_id", "issue_id",
            "source_issue_id", "observed_at", "created_at",
        )
        assertThat(columnDefinition("issue_sync_run", "result_set_mode")).isEqualTo("character varying(10):YES")
        assertThat(columnDefinition("issue_sync_run", "filter_reference")).isEqualTo("character varying(255):YES")
        listOf(
            "source_id" to "character varying(40):YES",
            "source_watermark" to "text:YES",
            "adapter_version" to "character varying(80):YES",
            "mapping_version" to "character varying(80):YES",
            "canonicalization_version" to "character varying(80):YES",
            "age_policy_version" to "character varying(80):YES",
            "observed_count" to "integer:YES",
            "tombstone_count" to "integer:YES",
            "selected_count" to "integer:YES",
        ).forEach { (column, definition) ->
            assertThat(columnDefinition("release_issue_snapshot", column)).isEqualTo(definition)
        }
        listOf(
            "sync_run_id" to "character varying(40):NO",
            "ordinal" to "integer:NO",
            "project_id" to "character varying(40):NO",
            "source_id" to "character varying(40):NO",
            "issue_id" to "character varying(40):NO",
            "source_issue_id" to "character varying(255):NO",
            "observed_at" to "timestamp with time zone:NO",
            "created_at" to "timestamp with time zone:NO",
        ).forEach { (column, definition) ->
            assertThat(columnDefinition("issue_sync_run_item", column)).isEqualTo(definition)
        }
        assertThat(uniqueConstraintExists("issue_sync_run_item", listOf("sync_run_id", "source_issue_id"))).isTrue()
        assertThat(primaryKeyDefinition("issue_sync_run_item"))
            .contains("PRIMARY KEY (sync_run_id, ordinal)")
        assertThat(constraintDefinition("uq_normalized_issue_id_source_project"))
            .contains("UNIQUE (id, source_id, project_id)")
        assertThat(constraintDefinition("uq_normalized_issue_observation_identity"))
            .contains("UNIQUE (id, source_id, project_id, source_issue_id)")
        assertThat(constraintDefinition("uq_sync_run_item_issue"))
            .contains("UNIQUE (sync_run_id, issue_id)")
        assertThat(constraintDefinition("uq_sync_run_item_source_issue"))
            .contains("UNIQUE (sync_run_id, source_issue_id)")
        assertThat(constraintDefinition("uq_issue_snapshot_run_filter"))
            .contains("UNIQUE (release_id, sync_run_id, filter_reference)")
        assertThat(constraintDefinition("ck_issue_sync_run_result_set_mode"))
            .contains("result_set_mode IS NULL", "FULL", "DELTA")
        assertThat(constraintDefinition("ck_sync_run_item_ordinal")).contains("ordinal >= 0")
        assertThat(constraintDefinition("ck_issue_snapshot_counts"))
            .contains("observed_count IS NULL", "tombstone_count IS NULL", "selected_count IS NULL")
            .contains("observed_count IS NOT NULL", "tombstone_count IS NOT NULL", "selected_count IS NOT NULL")
            .contains("observed_count >= 0", "tombstone_count >= 0", "selected_count >= 0")
            .contains("observed_count = (tombstone_count + selected_count)")
        assertThat(constraintDefinition("fk_sync_run_item_run_source_project"))
            .contains("FOREIGN KEY (sync_run_id, source_id, project_id)")
            .contains("REFERENCES issue_sync_run(id, source_id, project_id) ON DELETE RESTRICT")
        assertThat(constraintDefinition("fk_sync_run_item_issue_source_project"))
            .contains("FOREIGN KEY (issue_id, source_id, project_id, source_issue_id)")
            .contains("REFERENCES normalized_issue(id, source_id, project_id, source_issue_id) ON DELETE RESTRICT")
        assertThat(constraintDefinition("fk_issue_snapshot_run_source_project"))
            .contains("FOREIGN KEY (sync_run_id, source_id, project_id)")
            .contains("REFERENCES issue_sync_run(id, source_id, project_id) ON DELETE RESTRICT")
        assertThat(indexDefinition("ix_issue_sync_run_item_issue")).contains("(issue_id)")
        assertThat(triggerNames("issue_sync_run_item")).contains("immutable_issue_sync_run_item")
        assertThat(triggerNames("issue_sync_run_item")).contains("validate_issue_sync_run_item_insert")
        assertThat(triggerNames("issue_sync_run")).contains("seal_terminal_issue_sync_run")
        assertThat(triggerNames("release_issue_snapshot")).contains("validate_release_issue_snapshot_v1")
        assertThat(triggerDefinition("immutable_issue_sync_run_item"))
            .contains("BEFORE", "UPDATE", "DELETE", "reject_immutable_write()")
        assertThat(triggerDefinition("validate_issue_sync_run_item_insert"))
            .contains("BEFORE INSERT", "validate_issue_sync_run_item_insert()")
        assertThat(triggerDefinition("seal_terminal_issue_sync_run"))
            .contains("BEFORE", "UPDATE", "DELETE", "seal_terminal_issue_sync_run()")
        assertThat(triggerDefinition("validate_release_issue_snapshot_v1"))
            .contains("BEFORE INSERT", "validate_release_issue_snapshot_v1()")
        assertThat(hasCatalogOnlySearchPath("seal_terminal_issue_sync_run")).isTrue()
        assertThat(hasCatalogOnlySearchPath("validate_issue_sync_run_item_insert")).isTrue()
        assertThat(hasCatalogOnlySearchPath("validate_release_issue_snapshot_v1")).isTrue()
    }

    @Test
    fun `observation scope and snapshot v1 metadata fail closed`() {
        seedSnapshotAuthority("scope")
        assertThatThrownBy { insertCrossProjectObservation("scope") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertMismatchedObservationSourceIssue("scope") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertIncompleteV1Snapshot("scope") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertV1Snapshot("scope", "nonterminal", 7) }
            .hasRootCauseInstanceOf(SQLException::class.java)
        insertDeltaRun("scope")
        assertThatThrownBy { insertV1Snapshot("scope", "delta", 8, runId = "sync_scope_delta") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertDuplicateObservationSourceIssue("scope") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertDuplicateObservationIssue("scope") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        completeRun("sync_scope", "SUCCEEDED")
        assertThatThrownBy { updateTerminalRun("scope") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertObservation("scope", "sync_scope", 3, "issue_scope_a_2", "ISSUE-SCOPE-a") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        insertTerminalRun("scope", "failed", "FAILED")
        assertThatThrownBy { insertObservation("scope", "sync_scope_failed", 0, "issue_scope_a_2", "ISSUE-SCOPE-a") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        insertTerminalRun("scope", "succeeded_delete", "SUCCEEDED")
        assertThatThrownBy { deleteRun("sync_scope_succeeded_delete") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { deleteRun("sync_scope_failed") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertV1Snapshot("scope", "wrong-source", 9, sourceId = "source_scope_b") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertV1Snapshot("scope", "watermark", 10, sourceWatermark = "wrong") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertV1Snapshot("scope", "adapter", 11, adapterVersion = "wrong") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertV1Snapshot("scope", "mapping", 12, mappingVersion = "wrong") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertV1Snapshot("scope", "filter", 13, filterReference = "wrong") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertInvalidResultSetMode("scope") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertSnapshotWithCounts("scope", "partial", 2, "1", "NULL", "1") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertSnapshotWithCounts("scope", "negative", 3, "-1", "0", "-1") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertSnapshotWithCounts("scope", "unbalanced", 4, "2", "0", "1") }
            .hasRootCauseInstanceOf(SQLException::class.java)
        insertSnapshotRunFilter("scope", "first", 5)
        assertThatThrownBy { insertSnapshotRunFilter("scope", "duplicate", 6) }
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            jdbc.sql("UPDATE issue_sync_run_item SET observed_at = now() WHERE sync_run_id = 'sync_scope'").update()
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            jdbc.sql("DELETE FROM issue_sync_run_item WHERE sync_run_id = 'sync_scope'").update()
        }.hasRootCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `observation insert and terminal transition serialize on the run row`() {
        seedSnapshotAuthority("concurrency")
        val transition = dataSource.connection
        val observation = dataSource.connection
        val executor = Executors.newSingleThreadExecutor()
        try {
            transition.autoCommit = false
            observation.autoCommit = false
            val blockerPid = connectionPid(transition)
            val waiterPid = connectionPid(observation)
            transition.prepareStatement("UPDATE issue_sync_run SET status = 'SUCCEEDED' WHERE id = 'sync_concurrency'")
                .use { assertThat(it.executeUpdate()).isOne() }
            val insertStarted = CountDownLatch(1)
            val insertResult = executor.submit<Throwable?> {
                insertStarted.countDown()
                try {
                    observation.prepareStatement(
                        """
                        INSERT INTO issue_sync_run_item(
                          sync_run_id, ordinal, project_id, source_id, issue_id, source_issue_id,
                          observed_at, created_at
                        ) VALUES ('sync_concurrency', 3, 'project_concurrency_a', 'source_concurrency_a',
                                  'issue_concurrency_a_2', 'ISSUE-CONCURRENCY-a', now(), now())
                        """.trimIndent(),
                    ).use { it.executeUpdate() }
                    observation.commit()
                    null
                } catch (failure: Throwable) {
                    observation.rollback()
                    failure
                }
            }
            assertThat(insertStarted.await(5, TimeUnit.SECONDS)).isTrue()
            awaitDatabaseBlock(waiterPid, blockerPid)
            transition.commit()
            assertThat(insertResult.get(5, TimeUnit.SECONDS)).isInstanceOf(SQLException::class.java)
            assertThat(jdbc.sql("SELECT status FROM issue_sync_run WHERE id = 'sync_concurrency'")
                .query(String::class.java).single()).isEqualTo("SUCCEEDED")
            assertThat(jdbc.sql("SELECT count(*) FROM issue_sync_run_item WHERE sync_run_id = 'sync_concurrency'")
                .query(Int::class.java).single()).isOne()
        } finally {
            runCatching { transition.rollback() }
            runCatching { observation.rollback() }
            transition.close()
            observation.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `critical constraints and indexes have authoritative definitions`() {
        val definitions = jdbc.sql(
            """
            SELECT c.conname, pg_get_constraintdef(c.oid)
            FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname = 'public' AND t.relname IN (:tables)
              AND c.conname IN (:names)
            """.trimIndent(),
        ).param("tables", m2Tables).param(
            "names",
            listOf(
                "ck_normalized_issue_digest", "ck_normalized_issue_status", "ck_issue_commit_revision_chain",
                "uq_issue_commit_edge_revision", "uq_trace_snapshot_id_release_project",
            ),
        ).query { resultSet, _ -> resultSet.getString(1) to resultSet.getString(2) }.list().toMap()

        assertThat(definitions.getValue("ck_normalized_issue_digest")).contains("^sha256:[0-9a-f]{64}$")
        assertThat(definitions.getValue("ck_normalized_issue_status"))
            .contains("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED", "UNKNOWN")
        assertThat(definitions.getValue("ck_issue_commit_revision_chain"))
            .contains("revision = 1", "previous_revision IS NULL", "previous_revision = (revision - 1)")
        assertThat(definitions.getValue("uq_issue_commit_edge_revision")).contains("UNIQUE (edge_id, revision)")
        assertThat(definitions.getValue("uq_trace_snapshot_id_release_project"))
            .contains("UNIQUE (id, release_id, project_id)")

        val indexDefinitions = jdbc.sql(
            "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname IN (:names)",
        ).param(
            "names",
            listOf("ix_issue_commit_edge", "ix_trace_snapshot_release_version", "ix_snapshot_edge_source"),
        ).query { resultSet, _ -> resultSet.getString(1) to resultSet.getString(2) }.list().toMap()
        assertThat(indexDefinitions.getValue("ix_issue_commit_edge")).contains("(edge_id, revision DESC)")
        assertThat(indexDefinitions.getValue("ix_trace_snapshot_release_version"))
            .contains("(release_id, version DESC)")
        assertThat(indexDefinitions.getValue("ix_snapshot_edge_source"))
            .contains("(source_edge_id, source_edge_revision)")
    }

    @Test
    fun `revision chains preserve endpoints and source identity`() {
        seedTraceability("revision")
        insertIssueCommitRevision("icr_1", "edge_ic", 1, null, null, "issue_revision", "commit_revision", "CI", "batch-1")
        insertIssueCommitRevision("icr_2", "edge_ic", 2, "icr_1", 1, "issue_revision", "commit_revision", "CI", "batch-1")

        assertThatThrownBy {
            insertIssueCommitRevision("icr_bad_previous", "edge_ic", 3, "icr_1", 1, "issue_revision", "commit_revision", "CI", "batch-1")
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            insertIssueCommitRevision("icr_bad_endpoint", "edge_ic", 3, "icr_2", 2, "issue_revision_2", "commit_revision", "CI", "batch-1")
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            insertIssueCommitRevision("icr_bad_source", "edge_ic", 3, "icr_2", 2, "issue_revision", "commit_revision", "MANUAL", "ticket-1")
        }.isInstanceOf(DataAccessException::class.java)

        insertCommitBuildRevision("cbr_1", "edge_cb", 1, null, null, "commit_revision", "build_revision", "CI", "batch-1")
        insertCommitBuildRevision("cbr_2", "edge_cb", 2, "cbr_1", 1, "commit_revision", "build_revision", "CI", "batch-1")
        assertThatThrownBy {
            insertCommitBuildRevision("cbr_bad_endpoint", "edge_cb", 3, "cbr_2", 2, "commit_revision", "build_revision_2", "CI", "batch-1")
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            insertCommitBuildRevision("cbr_bad_source", "edge_cb", 3, "cbr_2", 2, "commit_revision", "build_revision", "MANUAL", "ticket-1")
        }.isInstanceOf(DataAccessException::class.java)

        insertBuildArtifactRevision("bar_1", "edge_ba", 1, null, null, "build_revision", "artifact_revision", "CI", "batch-1")
        insertBuildArtifactRevision("bar_2", "edge_ba", 2, "bar_1", 1, "build_revision", "artifact_revision", "CI", "batch-1")
        assertThatThrownBy {
            insertBuildArtifactRevision("bar_bad_source", "edge_ba", 3, "bar_2", 2, "build_revision", "artifact_revision", "MANUAL", "ticket-1")
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            insertBuildArtifactRevision("bar_bad_endpoint", "edge_ba", 3, "bar_2", 2, "build_revision", "artifact_revision_2", "CI", "batch-1")
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `M2 project scoped references reject cross project facts`() {
        seedProject("scope_a")
        seedProject("scope_b")
        insertIssueSource("source_scope", "project_scope_a")

        assertThatThrownBy {
            jdbc.sql(
                """
                INSERT INTO normalized_issue(
                  id, project_id, source_id, source_issue_id, title, severity, status, source_version,
                  source_reference, observed_at, mapping_version, fact_digest, created_at
                ) VALUES (
                  'issue_cross_project', 'project_scope_b', 'source_scope', 'ISSUE-1', 'title', 'MAJOR', 'OPEN', 'v1',
                  'ref-1', now(), 'mapping-v1', :digest, now()
                )
                """.trimIndent(),
            ).param("digest", digest('a')).update()
        }.isInstanceOf(DataAccessException::class.java)

        seedTraceability("edge_scope_a")
        seedTraceability("edge_scope_b")
        assertThatThrownBy {
            insertIssueCommitRevision(
                "icr_cross", "edge_ic_cross", 1, null, null,
                "issue_edge_scope_b", "commit_edge_scope_a", "CI", "batch", "project_edge_scope_a",
            )
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            insertCommitBuildRevision(
                "cbr_cross", "edge_cb_cross", 1, null, null,
                "commit_edge_scope_b", "build_edge_scope_a", "CI", "batch", "project_edge_scope_a",
            )
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            insertBuildArtifactRevision(
                "bar_cross", "edge_ba_cross", 1, null, null,
                "build_edge_scope_b", "artifact_edge_scope_a", "CI", "batch", "project_edge_scope_a",
            )
        }.isInstanceOf(DataAccessException::class.java)

        insertRelease("release_scope_b", "project_scope_b")
        jdbc.sql("INSERT INTO issue_sync_run(id, project_id, source_id, sync_run_id, status, adapter_version, mapping_version, created_at) VALUES ('sync_scope', 'project_scope_a', 'source_scope', 'run-scope', 'SUCCEEDED', 'adapter-v1', 'mapping-v1', now())").update()
        assertThatThrownBy {
            jdbc.sql("INSERT INTO release_issue_snapshot(id, project_id, release_id, sync_run_id, snapshot_version, filter_reference, content_digest, created_at) VALUES ('snapshot_cross', 'project_scope_a', 'release_scope_b', 'sync_scope', 1, 'all', :digest, now())")
                .param("digest", digest('9')).update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `revision snapshots edges and gaps reject update and delete`() {
        seedTraceability("immutable")
        insertIssueCommitRevision("immutable_revision", "immutable_edge", 1, null, null, "issue_immutable", "commit_immutable", "CI", "batch-1")
        insertCommitBuildRevision("immutable_commit_build", "immutable_cb_edge", 1, null, null, "commit_immutable", "build_immutable", "CI", "batch-1", "project_immutable")
        insertBuildArtifactRevision("immutable_build_artifact", "immutable_ba_edge", 1, null, null, "build_immutable", "artifact_immutable", "CI", "batch-1", "project_immutable")
        seedSnapshot("immutable")

        listOf(
            "issue_commit_edge_revision" to "id = 'immutable_revision'",
            "commit_build_edge_revision" to "id = 'immutable_commit_build'",
            "build_artifact_edge_revision" to "id = 'immutable_build_artifact'",
            "release_issue_snapshot" to "id = 'issue_snapshot_immutable'",
            "release_issue_snapshot_item" to "snapshot_id = 'issue_snapshot_immutable'",
            "traceability_gap" to "id = 'gap_immutable'",
            "traceability_snapshot" to "id = 'trace_snapshot_immutable'",
            "traceability_snapshot_edge" to "snapshot_id = 'trace_snapshot_immutable'",
            "traceability_snapshot_gap" to "snapshot_id = 'trace_snapshot_immutable'",
        ).forEach { (table, predicate) ->
            assertThatThrownBy { jdbc.sql("UPDATE $table SET created_at = now() WHERE $predicate").update() }
                .describedAs("UPDATE on %s", table).isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy { jdbc.sql("DELETE FROM $table WHERE $predicate").update() }
                .describedAs("DELETE on %s", table).isInstanceOf(DataAccessException::class.java)
        }
    }

    @Test
    fun `snapshot edges and gaps reject forged type project and release relationships`() {
        seedTraceability("snapshot_scope_a")
        seedTraceability("snapshot_scope_b")
        insertIssueCommitRevision(
            "snapshot_scope_a_revision", "snapshot_scope_a_edge", 1, null, null,
            "issue_snapshot_scope_a", "commit_snapshot_scope_a", "CI", "batch-a",
        )
        insertCommitBuildRevision(
            "snapshot_scope_a_commit_build", "snapshot_scope_a_cb_edge", 1, null, null,
            "commit_snapshot_scope_a", "build_snapshot_scope_a", "CI", "batch-a", "project_snapshot_scope_a",
        )
        insertBuildArtifactRevision(
            "snapshot_scope_a_build_artifact", "snapshot_scope_a_ba_edge", 1, null, null,
            "build_snapshot_scope_a", "artifact_snapshot_scope_a", "CI", "batch-a", "project_snapshot_scope_a",
        )
        insertIssueCommitRevision(
            "snapshot_scope_b_revision", "snapshot_scope_b_edge", 1, null, null,
            "issue_snapshot_scope_b", "commit_snapshot_scope_b", "CI", "batch-b",
        )
        inTransaction {
            seedTraceabilitySnapshotHeader("snapshot_scope_a")
            insertSnapshotIssueEdge(
                "trace_snapshot_snapshot_scope_a", 0, "project_snapshot_scope_a",
                "issue_snapshot_scope_a", "commit_snapshot_scope_a", "snapshot_scope_a_edge", "batch-a",
            )
            insertSnapshotCommitBuildEdge("trace_snapshot_snapshot_scope_a", 1)
            insertSnapshotBuildArtifactEdge("trace_snapshot_snapshot_scope_a", 2)
        }

        assertThatThrownBy {
            inTransaction {
                insertTraceabilitySnapshotHeader(
                    "trace_snapshot_scope_project", "project_snapshot_scope_a", "release_snapshot_scope_a",
                    "verify_snapshot_scope_a", 2,
                )
                insertSnapshotIssueEdge(
                    "trace_snapshot_scope_project", 0, "project_snapshot_scope_a",
                    "issue_snapshot_scope_b", "commit_snapshot_scope_b", "snapshot_scope_b_edge", "batch-b",
                )
            }
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            inTransaction {
                insertTraceabilitySnapshotHeader(
                    "trace_snapshot_scope_type", "project_snapshot_scope_a", "release_snapshot_scope_a",
                    "verify_snapshot_scope_a", 3,
                )
                insertSnapshotIssueEdge(
                    "trace_snapshot_scope_type", 0, "project_snapshot_scope_a",
                    "issue_snapshot_scope_a", "commit_snapshot_scope_a", "snapshot_scope_a_edge", "batch-a",
                    fromType = "COMMIT",
                    toType = "ISSUE",
                )
            }
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            inTransaction {
                insertTraceabilitySnapshotHeader(
                    "trace_snapshot_gap_project", "project_snapshot_scope_a", "release_snapshot_scope_a",
                    "verify_snapshot_scope_a", 4,
                )
                jdbc.sql("INSERT INTO traceability_snapshot_gap(snapshot_id, ordinal, project_id, issue_id, release_id, expected_edge_type, reason, diagnostic_code, gap_digest, created_at) VALUES ('trace_snapshot_gap_project', 0, 'project_snapshot_scope_b', 'issue_snapshot_scope_b', 'release_snapshot_scope_b', 'COMMIT_BUILD', 'missing', 'EDGE_MISSING', :digest, now())")
                    .param("digest", digest("snapshot-gap-project")).update()
            }
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            inTransaction {
                insertTraceabilitySnapshotHeader(
                    "trace_snapshot_gap_release", "project_snapshot_scope_a", "release_snapshot_scope_a",
                    "verify_snapshot_scope_a", 5,
                )
                jdbc.sql("INSERT INTO traceability_snapshot_gap(snapshot_id, ordinal, project_id, issue_id, release_id, expected_edge_type, reason, diagnostic_code, gap_digest, created_at) VALUES ('trace_snapshot_gap_release', 0, 'project_snapshot_scope_a', 'issue_snapshot_scope_a', 'release_snapshot_scope_a_2', 'COMMIT_BUILD', 'missing', 'EDGE_MISSING', :digest, now())")
                    .param("digest", digest("snapshot-gap-release")).update()
            }
        }.hasRootCauseInstanceOf(SQLException::class.java)

        assertTypedSnapshotDigestTamperRejected("ISSUE_COMMIT", 6)
        assertTypedSnapshotDigestTamperRejected("COMMIT_BUILD", 7)
        assertTypedSnapshotDigestTamperRejected("BUILD_ARTIFACT", 8)
    }

    @Test
    fun `snapshot children can only be inserted in their header creation transaction`() {
        seedTraceability("sealed")
        insertIssueCommitRevision(
            "sealed_revision", "sealed_edge", 1, null, null,
            "issue_sealed", "commit_sealed", "CI", "batch-sealed",
        )
        jdbc.sql("INSERT INTO issue_sync_run(id, project_id, source_id, sync_run_id, status, adapter_version, mapping_version, created_at) VALUES ('sync_sealed', 'project_sealed', 'source_sealed', 'run-sealed', 'SUCCEEDED', 'adapter-v1', 'mapping-v1', now())").update()
        jdbc.sql("INSERT INTO release_issue_snapshot(id, project_id, release_id, sync_run_id, snapshot_version, filter_reference, content_digest, created_at) VALUES ('issue_snapshot_sealed', 'project_sealed', 'release_sealed', 'sync_sealed', 1, 'all', :digest, now())")
            .param("digest", digest("issue-snapshot-sealed")).update()
        assertThatThrownBy {
            jdbc.sql("INSERT INTO release_issue_snapshot(id, project_id, release_id, sync_run_id, snapshot_version, filter_reference, content_digest, creation_transaction_id, created_at) VALUES ('issue_snapshot_spoofed', 'project_sealed', 'release_sealed', 'sync_sealed', 2, 'all', :digest, 0, now())")
                .param("digest", digest("issue-snapshot-spoofed")).update()
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            jdbc.sql("INSERT INTO release_issue_snapshot_item(snapshot_id, ordinal, project_id, issue_id, source_issue_id, title, severity, status, source_version, source_reference, observed_at, mapping_version, fact_digest, created_at) VALUES ('issue_snapshot_sealed', 0, 'project_sealed', 'issue_sealed', 'ISSUE-sealed', 'title', 'MAJOR', 'OPEN', 'v1', 'ref', now(), 'mapping-v1', :digest, now())")
                .param("digest", digest("sealed-item")).update()
        }.hasRootCauseInstanceOf(SQLException::class.java)

        seedTraceabilitySnapshotHeader("sealed")
        assertThatThrownBy {
            insertSnapshotIssueEdge(
                "trace_snapshot_sealed", 0, "project_sealed",
                "issue_sealed", "commit_sealed", "sealed_edge", "batch-sealed",
            )
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            jdbc.sql("INSERT INTO traceability_snapshot_gap(snapshot_id, ordinal, project_id, issue_id, release_id, expected_edge_type, reason, diagnostic_code, gap_digest, created_at) VALUES ('trace_snapshot_sealed', 0, 'project_sealed', 'issue_sealed', 'release_sealed', 'COMMIT_BUILD', 'missing', 'EDGE_MISSING', :digest, now())")
                .param("digest", digest("sealed-gap")).update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `snapshot authority uses trigger schema and ignores hostile temporary relations`() {
        seedTraceability("search_path")
        insertIssueCommitRevision(
            "search_path_revision", "search_path_edge", 1, null, null,
            "issue_search_path", "commit_search_path", "CI", "batch-real",
        )
        jdbc.sql("INSERT INTO traceability_verification_run(id, project_id, release_id, verification_run_id, status, policy_version, created_at) VALUES ('verify_search_path', 'project_search_path', 'release_search_path', 'verification-search-path', 'SUCCEEDED', 'policy-v1', now())").update()

        assertThatThrownBy {
            inTransaction {
                jdbc.sql("CREATE TEMP TABLE issue_commit_edge_revision ON COMMIT DROP AS SELECT * FROM public.issue_commit_edge_revision WITH NO DATA").update()
                jdbc.sql("INSERT INTO pg_temp.issue_commit_edge_revision SELECT * FROM public.issue_commit_edge_revision WHERE id = 'search_path_revision'").update()
                jdbc.sql("UPDATE pg_temp.issue_commit_edge_revision SET edge_id = 'forged_temp_edge', source_reference = 'batch-forged'").update()
                jdbc.sql("SET LOCAL search_path = pg_temp, public").update()
                jdbc.sql("INSERT INTO public.traceability_snapshot(id, project_id, release_id, verification_run_id, version, schema_version, policy_version, content_digest, created_at) VALUES ('trace_snapshot_search_path', 'project_search_path', 'release_search_path', 'verify_search_path', 1, '0.2', 'policy-v1', :digest, now())")
                    .param("digest", digest("search-path-snapshot")).update()
                jdbc.sql(
                    """
                    INSERT INTO public.traceability_snapshot_edge(
                      snapshot_id, ordinal, project_id, edge_type, from_entity_type, from_entity_id,
                      to_entity_type, to_entity_id, source_edge_id, source_edge_revision, source_type,
                      source_reference, confidence, verification_status, validator_version, fact_digest, created_at
                    ) VALUES (
                      'trace_snapshot_search_path', 0, 'project_search_path', 'ISSUE_COMMIT', 'ISSUE', 'issue_search_path',
                      'COMMIT', 'commit_search_path', 'forged_temp_edge', 1, 'CI', 'batch-forged',
                      'HIGH', 'VALID', 'validator-v1', :digest, now()
                    )
                    """.trimIndent(),
                ).param("digest", digest("search-path-edge")).update()
            }
        }.hasRootCauseInstanceOf(SQLException::class.java)

        val securedFunctions = jdbc.sql(
            """
            SELECT p.proname FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'public' AND p.proname IN (:names)
              AND p.proconfig @> ARRAY['search_path=pg_catalog']::text[]
            """.trimIndent(),
        ).param(
            "names",
            listOf(
                "enforce_issue_commit_edge_identity", "enforce_commit_build_edge_identity",
                "enforce_build_artifact_edge_identity", "validate_traceability_snapshot_edge_source",
                "validate_release_artifact_snapshot_authority",
            ),
        ).query(String::class.java).list()
        assertThat(securedFunctions).containsExactlyInAnyOrder(
            "enforce_issue_commit_edge_identity", "enforce_commit_build_edge_identity",
            "enforce_build_artifact_edge_identity", "validate_traceability_snapshot_edge_source",
            "validate_release_artifact_snapshot_authority",
        )
        val deferredAuthorityTriggers = jdbc.sql(
            """
            SELECT count(*) FROM pg_trigger
            WHERE tgname = 'validate_traceability_snapshot_edge_source'
              AND tgdeferrable AND tginitdeferred
            """.trimIndent(),
        ).query(Int::class.java).single()
        assertThat(deferredAuthorityTriggers).isOne()
    }

    @Test
    fun `deferred snapshot authority validates the final locked Manifest at commit`() {
        seedManifestAuthorities("deferred")
        jdbc.sql("INSERT INTO traceability_verification_run(id, project_id, release_id, verification_run_id, status, policy_version, created_at) VALUES ('verify_deferred', 'project_deferred', 'release_deferred_a', 'verification-deferred', 'SUCCEEDED', 'policy-v1', now())").update()

        val failure = org.assertj.core.api.Assertions.catchThrowable {
            inTransaction {
                jdbc.sql("ALTER TABLE release_record DISABLE TRIGGER release_locked_manifest_ownership").update()
                jdbc.sql("UPDATE release_record SET locked_manifest_id = 'manifest_deferred_b' WHERE id = 'release_deferred_a'").update()
                jdbc.sql("INSERT INTO traceability_snapshot(id, project_id, release_id, verification_run_id, version, schema_version, policy_version, content_digest, created_at) VALUES ('trace_snapshot_deferred', 'project_deferred', 'release_deferred_a', 'verify_deferred', 1, '0.2', 'policy-v1', :digest, now())")
                    .param("digest", digest("deferred-snapshot")).update()
                insertArtifactReleaseEdgeFromView(
                    snapshotId = "trace_snapshot_deferred",
                    ordinal = 0,
                    releaseId = "release_deferred_a",
                    artifactId = "artifact_deferred_b",
                )
                jdbc.sql("SET CONSTRAINTS validate_traceability_snapshot_edge_source IMMEDIATE").update()
                jdbc.sql("UPDATE release_record SET locked_manifest_id = 'manifest_deferred_a' WHERE id = 'release_deferred_a'").update()
            }
        }
        assertThat(failure).hasRootCauseInstanceOf(SQLException::class.java)
        val rootCause = generateSequence(failure) { it.cause }.last()
        assertThat(rootCause.message).contains("locked Manifest cannot invalidate artifact snapshot authority")
    }

    @Test
    fun `artifact release view only exposes locked manifest membership and rejects writes`() {
        seedProject("view")
        insertRelease("release_view", "project_view")
        insertRelease("release_view_other", "project_view")
        insertArtifact("artifact_view")
        insertArtifact("artifact_view_forged")
        jdbc.sql(
            """
            INSERT INTO manifest_revision(
              id, release_id, revision, content_digest, raw_manifest, canonical_bytes,
              schema_version, state, created_at, updated_at
            ) VALUES ('manifest_view', 'release_view', 1, :digest, '{}'::jsonb, convert_to('{}', 'UTF8'), '0.2', 'VALIDATED', now(), now())
            """.trimIndent(),
        ).param("digest", digest('c')).update()
        jdbc.sql("INSERT INTO manifest_artifact(manifest_id, artifact_id, ordinal, required, created_at) VALUES ('manifest_view', 'artifact_view', 0, true, now())").update()
        assertThat(jdbc.sql("SELECT count(*) FROM artifact_release_edge_v WHERE release_id = 'release_view'").query(Int::class.java).single()).isZero()

        jdbc.sql("UPDATE manifest_revision SET state = 'LOCKED' WHERE id = 'manifest_view'").update()
        jdbc.sql("UPDATE release_record SET locked_manifest_id = 'manifest_view' WHERE id = 'release_view'").update()
        jdbc.sql(
            """
            INSERT INTO manifest_revision(
              id, release_id, revision, content_digest, raw_manifest, canonical_bytes,
              schema_version, state, created_at, updated_at
            ) VALUES ('manifest_view_other', 'release_view_other', 1, :digest, '{}'::jsonb, convert_to('{}', 'UTF8'), '0.2', 'VALIDATED', now(), now())
            """.trimIndent(),
        ).param("digest", digest('4')).update()
        jdbc.sql("INSERT INTO manifest_artifact(manifest_id, artifact_id, ordinal, required, created_at) VALUES ('manifest_view_other', 'artifact_view_forged', 0, true, now())").update()
        jdbc.sql("UPDATE manifest_revision SET state = 'LOCKED' WHERE id = 'manifest_view_other'").update()
        jdbc.sql("UPDATE release_record SET locked_manifest_id = 'manifest_view_other' WHERE id = 'release_view_other'").update()
        assertThat(jdbc.sql("SELECT count(*) FROM artifact_release_edge_v WHERE release_id = 'release_view'").query(Int::class.java).single()).isOne()

        jdbc.sql("INSERT INTO traceability_verification_run(id, project_id, release_id, verification_run_id, status, policy_version, created_at) VALUES ('verify_view', 'project_view', 'release_view', 'verification-view', 'SUCCEEDED', 'policy-v1', now())").update()
        inTransaction {
            insertTraceabilitySnapshotHeader("trace_snapshot_view", "project_view", "release_view", "verify_view", 1)
            insertArtifactReleaseEdgeFromView("trace_snapshot_view", 0, "release_view", "artifact_view")
        }
        assertThat(jdbc.sql("SELECT count(*) FROM traceability_snapshot_edge WHERE snapshot_id = 'trace_snapshot_view'").query(Int::class.java).single()).isOne()

        assertArtifactReleaseTamperRejected(2, "'forged_source_edge_id'", field = "sourceEdgeId")
        assertArtifactReleaseTamperRejected(3, "'forged-source-reference'", field = "sourceReference")
        assertArtifactReleaseTamperRejected(4, "now()", field = "verifiedAt")
        assertArtifactReleaseTamperRejected(5, "'forged-validator'", field = "validatorVersion")
        assertArtifactReleaseTamperRejected(6, "'forged reason'", field = "reason")
        assertArtifactReleaseTamperRejected(7, "'evidence_forged'::varchar(40)", field = "evidenceId")
        assertArtifactReleaseTamperRejected(8, "'sha256:" + "9".repeat(64) + "'", field = "factDigest")
        assertArtifactReleaseTamperRejected(9, "'artifact_view_forged'", field = "fromEntityId")
        assertThatThrownBy {
            inTransaction {
                insertTraceabilitySnapshotHeader("trace_snapshot_view_cross", "project_view", "release_view", "verify_view", 10)
                insertArtifactReleaseEdgeFromView(
                    "trace_snapshot_view_cross", 0, "release_view_other", "artifact_view_forged",
                )
            }
        }.hasRootCauseInstanceOf(SQLException::class.java)
        assertThatThrownBy { jdbc.sql("DELETE FROM artifact_release_edge_v WHERE release_id = 'release_view'").update() }
            .isInstanceOf(DataAccessException::class.java)
    }

    private fun assertConstraintNames(type: String, expected: Set<String>) {
        val actual = jdbc.sql(
            """
            SELECT c.conname FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname = 'public' AND t.relname IN (:tables)
              AND c.contype = :type AND c.conname IN (:names)
            """.trimIndent(),
        ).param("tables", m2Tables).param("type", type).param("names", expected)
            .query(String::class.java).list()
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected)
    }

    private fun tableNames(): List<String> = jdbc.sql(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
    ).query(String::class.java).list()

    private fun columnNames(tableName: String): List<String> = jdbc.sql(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = :tableName",
    ).param("tableName", tableName).query(String::class.java).list()

    private fun columnDefinition(tableName: String, columnName: String): String = jdbc.sql(
        """
        SELECT data_type || COALESCE('(' || character_maximum_length::text || ')', '') || ':' || is_nullable
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = :tableName AND column_name = :columnName
        """.trimIndent(),
    ).param("tableName", tableName).param("columnName", columnName).query(String::class.java).single()

    private fun uniqueConstraintExists(tableName: String, columns: List<String>): Boolean = jdbc.sql(
        """
        SELECT EXISTS (
          SELECT 1
          FROM pg_constraint constraint_record
          JOIN pg_class table_record ON table_record.oid = constraint_record.conrelid
          JOIN pg_namespace namespace_record ON namespace_record.oid = table_record.relnamespace
          WHERE namespace_record.nspname = 'public'
            AND table_record.relname = :tableName
            AND constraint_record.contype = 'u'
            AND (
              SELECT string_agg(attribute_record.attname, ',' ORDER BY key_record.ordinality)
              FROM unnest(constraint_record.conkey) WITH ORDINALITY key_record(attribute_number, ordinality)
              JOIN pg_attribute attribute_record
                ON attribute_record.attrelid = table_record.oid
               AND attribute_record.attnum = key_record.attribute_number
            ) = :columns
        )
        """.trimIndent(),
    ).param("tableName", tableName).param("columns", columns.joinToString(","))
        .query(Boolean::class.java).single()

    private fun triggerNames(tableName: String): List<String> = jdbc.sql(
        """
        SELECT trigger_record.tgname
        FROM pg_trigger trigger_record
        JOIN pg_class table_record ON table_record.oid = trigger_record.tgrelid
        JOIN pg_namespace namespace_record ON namespace_record.oid = table_record.relnamespace
        WHERE namespace_record.nspname = 'public' AND table_record.relname = :tableName
          AND NOT trigger_record.tgisinternal
        """.trimIndent(),
    ).param("tableName", tableName).query(String::class.java).list()

    private fun triggerDefinition(triggerName: String): String = jdbc.sql(
        """
        SELECT pg_get_triggerdef(trigger_record.oid)
        FROM pg_trigger trigger_record
        JOIN pg_class table_record ON table_record.oid = trigger_record.tgrelid
        JOIN pg_namespace namespace_record ON namespace_record.oid = table_record.relnamespace
        WHERE namespace_record.nspname = 'public' AND trigger_record.tgname = :triggerName
          AND NOT trigger_record.tgisinternal
        """.trimIndent(),
    ).param("triggerName", triggerName).query(String::class.java).single()

    private fun hasCatalogOnlySearchPath(functionName: String): Boolean = jdbc.sql(
        """
        SELECT COALESCE(function_record.proconfig @> ARRAY['search_path=pg_catalog'], false)
        FROM pg_proc function_record
        JOIN pg_namespace namespace_record ON namespace_record.oid = function_record.pronamespace
        WHERE namespace_record.nspname = 'public' AND function_record.proname = :functionName
        """.trimIndent(),
    ).param("functionName", functionName).query(Boolean::class.java).single()

    private fun constraintDefinition(constraintName: String): String = jdbc.sql(
        """
        SELECT pg_get_constraintdef(constraint_record.oid)
        FROM pg_constraint constraint_record
        JOIN pg_namespace namespace_record ON namespace_record.oid = constraint_record.connamespace
        WHERE namespace_record.nspname = 'public' AND constraint_record.conname = :constraintName
        """.trimIndent(),
    ).param("constraintName", constraintName).query(String::class.java).single()

    private fun primaryKeyDefinition(tableName: String): String = jdbc.sql(
        """
        SELECT pg_get_constraintdef(constraint_record.oid)
        FROM pg_constraint constraint_record
        JOIN pg_class table_record ON table_record.oid = constraint_record.conrelid
        JOIN pg_namespace namespace_record ON namespace_record.oid = table_record.relnamespace
        WHERE namespace_record.nspname = 'public' AND table_record.relname = :tableName
          AND constraint_record.contype = 'p'
        """.trimIndent(),
    ).param("tableName", tableName).query(String::class.java).single()

    private fun indexDefinition(indexName: String): String = jdbc.sql(
        "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = :indexName",
    ).param("indexName", indexName).query(String::class.java).single()

    private fun assertForeignKeyNames(expected: Set<String>) {
        val actual = jdbc.sql(
            """
            SELECT c.conname FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname = 'public' AND t.relname IN (:tables)
              AND c.contype = 'f'
            """.trimIndent(),
        ).param("tables", m2Tables).query(String::class.java).list()
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected)
    }

    private fun seedTraceability(suffix: String) {
        seedProject(suffix)
        insertIssueSource("source_$suffix", "project_$suffix")
        jdbc.sql(
            """
            INSERT INTO normalized_issue(
              id, project_id, source_id, source_issue_id, title, severity, status, source_version,
              source_reference, observed_at, mapping_version, fact_digest, created_at
            ) VALUES ('issue_$suffix', 'project_$suffix', 'source_$suffix', 'ISSUE-$suffix', 'title', 'MAJOR', 'OPEN', 'v1', 'ref', now(), 'mapping-v1', :digest, now()),
                     ('issue_${suffix}_2', 'project_$suffix', 'source_$suffix', 'ISSUE-${suffix}-2', 'title', 'MAJOR', 'OPEN', 'v1', 'ref-2', now(), 'mapping-v1', :digest2, now())
            """.trimIndent(),
        ).param("digest", digest('a')).param("digest2", digest('b')).update()
        jdbc.sql("INSERT INTO source_commit(id, project_id, repository, commit_id, created_at) VALUES ('commit_$suffix', 'project_$suffix', 'repo', 'sha-$suffix', now())").update()
        jdbc.sql("INSERT INTO source_commit(id, project_id, repository, commit_id, created_at) VALUES ('commit_${suffix}_2', 'project_$suffix', 'repo', 'sha-${suffix}-2', now())").update()
        jdbc.sql("INSERT INTO build_record(id, project_id, provider, build_id, source_revision, created_at) VALUES ('build_$suffix', 'project_$suffix', 'ci', 'build-$suffix', 'sha-$suffix', now())").update()
        jdbc.sql("INSERT INTO build_record(id, project_id, provider, build_id, source_revision, created_at) VALUES ('build_${suffix}_2', 'project_$suffix', 'ci', 'build-${suffix}-2', 'sha-$suffix', now())").update()
        insertArtifact("artifact_$suffix")
        insertArtifact("artifact_${suffix}_2")
        insertRelease("release_$suffix", "project_$suffix")
        insertRelease("release_${suffix}_2", "project_$suffix")
    }

    private fun seedSnapshotAuthority(suffix: String) {
        listOf("a", "b").forEach { side ->
            val projectId = "project_${suffix}_$side"
            val sourceId = "source_${suffix}_$side"
            seedProject("${suffix}_$side")
            insertIssueSource(sourceId, projectId)
            jdbc.sql(
                """
                INSERT INTO normalized_issue(
                  id, project_id, source_id, source_issue_id, title, severity, status, source_version,
                  source_reference, observed_at, mapping_version, fact_digest, created_at
                ) VALUES (:issueId, :projectId, :sourceId, :sourceIssueId, 'title', 'MAJOR', 'OPEN', 'v1',
                          'ref', now(), 'mapping-v1', :digest, now())
                """.trimIndent(),
            ).param("issueId", "issue_${suffix}_$side").param("projectId", projectId)
                .param("sourceId", sourceId).param("sourceIssueId", "ISSUE-${suffix.uppercase()}-$side")
                .param("digest", digest("authority-$suffix-$side")).update()
        }
        insertRelease("release_$suffix", "project_${suffix}_a")
        jdbc.sql(
            """
            INSERT INTO normalized_issue(
              id, project_id, source_id, source_issue_id, title, severity, status, source_version,
              source_reference, observed_at, mapping_version, fact_digest, created_at
            ) VALUES ('issue_${suffix}_a_2', 'project_${suffix}_a', 'source_${suffix}_a',
                      'ISSUE-${suffix.uppercase()}-a', 'title', 'MAJOR', 'OPEN', 'v2', 'ref-2',
                      now(), 'mapping-v1', :digest, now())
            """.trimIndent(),
        ).param("digest", digest("authority-$suffix-a-2")).update()
        jdbc.sql(
            """
            INSERT INTO issue_sync_run(
              id, project_id, source_id, sync_run_id, status, result_set_mode, filter_reference,
              source_watermark, adapter_version, mapping_version, created_at
            ) VALUES ('sync_$suffix', 'project_${suffix}_a', 'source_${suffix}_a', 'run-$suffix',
                      'RUNNING', 'FULL', 'filter-v1', 'watermark-v1', 'adapter-v1', 'mapping-v1', now())
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            INSERT INTO issue_sync_run_item(
              sync_run_id, ordinal, project_id, source_id, issue_id, source_issue_id, observed_at, created_at
            ) VALUES ('sync_$suffix', 0, 'project_${suffix}_a', 'source_${suffix}_a',
                      'issue_${suffix}_a', 'ISSUE-${suffix.uppercase()}-a', now(), now())
            """.trimIndent(),
        ).update()
    }

    private fun insertCrossProjectObservation(suffix: String) = jdbc.sql(
        """
        INSERT INTO issue_sync_run_item(
          sync_run_id, ordinal, project_id, source_id, issue_id, source_issue_id, observed_at, created_at
        ) VALUES ('sync_$suffix', 1, 'project_${suffix}_a', 'source_${suffix}_a',
                  'issue_${suffix}_b', 'ISSUE-${suffix.uppercase()}-b', now(), now())
        """.trimIndent(),
    ).update()

    private fun insertMismatchedObservationSourceIssue(suffix: String) =
        insertObservation(suffix, "sync_$suffix", 1, "issue_${suffix}_a_2", "ISSUE-${suffix.uppercase()}-forged")

    private fun insertIncompleteV1Snapshot(suffix: String) = jdbc.sql(
        """
        INSERT INTO release_issue_snapshot(
          id, project_id, release_id, sync_run_id, snapshot_version, filter_reference,
          canonicalization_version, content_digest, created_at
        ) VALUES ('issue_snapshot_$suffix', 'project_${suffix}_a', 'release_$suffix', 'sync_$suffix', 1,
                  'filter-v1', 'release-issue-snapshot-jcs/v1', :digest, now())
        """.trimIndent(),
    ).param("digest", digest("incomplete-snapshot-$suffix")).update()

    private fun updateTerminalRun(suffix: String) = jdbc.sql(
        "UPDATE issue_sync_run SET warning_count = warning_count + 1 WHERE id = :id",
    ).param("id", "sync_$suffix").update()

    private fun insertInvalidResultSetMode(suffix: String) = jdbc.sql(
        """
        INSERT INTO issue_sync_run(
          id, project_id, source_id, sync_run_id, status, result_set_mode,
          adapter_version, mapping_version, created_at
        ) VALUES ('sync_${suffix}_invalid', 'project_${suffix}_a', 'source_${suffix}_a',
                  'run-${suffix}-invalid', 'RUNNING', 'WINDOW', 'adapter-v1', 'mapping-v1', now())
        """.trimIndent(),
    ).update()

    private fun insertSnapshotWithCounts(
        suffix: String,
        caseName: String,
        snapshotVersion: Int,
        observedCount: String,
        tombstoneCount: String,
        selectedCount: String,
    ) = jdbc.sql(
        """
        INSERT INTO release_issue_snapshot(
          id, project_id, release_id, sync_run_id, snapshot_version, filter_reference,
          observed_count, tombstone_count, selected_count, content_digest, created_at
        ) VALUES ('snapshot_${suffix}_$caseName', 'project_${suffix}_a', 'release_$suffix', 'sync_$suffix',
                  $snapshotVersion, '$caseName', $observedCount, $tombstoneCount, $selectedCount, :digest, now())
        """.trimIndent(),
    ).param("digest", digest("snapshot-$suffix-$caseName")).update()

    private fun insertDuplicateObservationSourceIssue(suffix: String) = jdbc.sql(
        """
        INSERT INTO issue_sync_run_item(
          sync_run_id, ordinal, project_id, source_id, issue_id, source_issue_id, observed_at, created_at
        ) VALUES ('sync_$suffix', 1, 'project_${suffix}_a', 'source_${suffix}_a',
                  'issue_${suffix}_a_2', 'ISSUE-${suffix.uppercase()}-a', now(), now())
        """.trimIndent(),
    ).update()

    private fun insertDuplicateObservationIssue(suffix: String) = jdbc.sql(
        """
        INSERT INTO issue_sync_run_item(
          sync_run_id, ordinal, project_id, source_id, issue_id, source_issue_id, observed_at, created_at
        ) VALUES ('sync_$suffix', 2, 'project_${suffix}_a', 'source_${suffix}_a',
                  'issue_${suffix}_a', 'ISSUE-${suffix.uppercase()}-a', now(), now())
        """.trimIndent(),
    ).update()

    private fun insertObservation(
        suffix: String,
        runId: String,
        ordinal: Int,
        issueId: String,
        sourceIssueId: String,
    ) = jdbc.sql(
        """
        INSERT INTO issue_sync_run_item(
          sync_run_id, ordinal, project_id, source_id, issue_id, source_issue_id, observed_at, created_at
        ) VALUES (:runId, :ordinal, 'project_${suffix}_a', 'source_${suffix}_a',
                  :issueId, :sourceIssueId, now(), now())
        """.trimIndent(),
    ).param("runId", runId).param("ordinal", ordinal).param("issueId", issueId)
        .param("sourceIssueId", sourceIssueId).update()

    private fun completeRun(runId: String, status: String) = jdbc.sql(
        "UPDATE issue_sync_run SET status = :status WHERE id = :runId",
    ).param("status", status).param("runId", runId).update()

    private fun insertTerminalRun(suffix: String, idSuffix: String, status: String) = jdbc.sql(
        """
        INSERT INTO issue_sync_run(
          id, project_id, source_id, sync_run_id, status, result_set_mode, filter_reference,
          source_watermark, adapter_version, mapping_version, created_at
        ) VALUES ('sync_${suffix}_$idSuffix', 'project_${suffix}_a', 'source_${suffix}_a',
                  'run-${suffix}-$idSuffix', :status, 'FULL', 'filter-v1', 'watermark-v1',
                  'adapter-v1', 'mapping-v1', now())
        """.trimIndent(),
    ).param("status", status).update()

    private fun insertDeltaRun(suffix: String) = jdbc.sql(
        """
        INSERT INTO issue_sync_run(
          id, project_id, source_id, sync_run_id, status, result_set_mode, filter_reference,
          source_watermark, adapter_version, mapping_version, created_at
        ) VALUES ('sync_${suffix}_delta', 'project_${suffix}_a', 'source_${suffix}_a',
                  'run-${suffix}-delta', 'SUCCEEDED', 'DELTA', 'filter-v1', 'watermark-v1',
                  'adapter-v1', 'mapping-v1', now())
        """.trimIndent(),
    ).update()

    private fun deleteRun(runId: String) = jdbc.sql(
        "DELETE FROM issue_sync_run WHERE id = :runId",
    ).param("runId", runId).update()

    private fun insertV1Snapshot(
        suffix: String,
        idSuffix: String,
        snapshotVersion: Int,
        runId: String = "sync_$suffix",
        sourceId: String = "source_${suffix}_a",
        sourceWatermark: String = "watermark-v1",
        adapterVersion: String = "adapter-v1",
        mappingVersion: String = "mapping-v1",
        filterReference: String = "filter-v1",
    ) = jdbc.sql(
        """
        INSERT INTO release_issue_snapshot(
          id, project_id, release_id, sync_run_id, snapshot_version, filter_reference,
          source_id, source_watermark, adapter_version, mapping_version, canonicalization_version,
          age_policy_version, observed_count, tombstone_count, selected_count, content_digest, created_at
        ) VALUES ('snapshot_${suffix}_$idSuffix', 'project_${suffix}_a', 'release_$suffix', :runId,
                  :snapshotVersion, :filterReference, :sourceId, :sourceWatermark, :adapterVersion,
                  :mappingVersion, 'release-issue-snapshot-jcs/v1', 'age-policy-v1',
                  1, 0, 1, :digest, now())
        """.trimIndent(),
    ).param("runId", runId).param("snapshotVersion", snapshotVersion)
        .param("filterReference", filterReference).param("sourceId", sourceId)
        .param("sourceWatermark", sourceWatermark).param("adapterVersion", adapterVersion)
        .param("mappingVersion", mappingVersion).param("digest", digest("snapshot-$suffix-$idSuffix")).update()

    private fun connectionPid(connection: java.sql.Connection): Int = connection.prepareStatement(
        "SELECT pg_backend_pid()",
    ).use { statement ->
        statement.executeQuery().use { resultSet ->
            check(resultSet.next())
            resultSet.getInt(1)
        }
    }

    private fun awaitDatabaseBlock(waiterPid: Int, blockerPid: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val blocked = jdbc.sql("SELECT :blockerPid = ANY(pg_blocking_pids(:waiterPid))")
                .param("blockerPid", blockerPid).param("waiterPid", waiterPid)
                .query(Boolean::class.java).single()
            if (blocked) return
            Thread.onSpinWait()
        }
        error("Timed out waiting for PostgreSQL lock waiter $waiterPid blocked by $blockerPid")
    }

    private fun insertSnapshotRunFilter(suffix: String, idSuffix: String, snapshotVersion: Int) = jdbc.sql(
        """
        INSERT INTO release_issue_snapshot(
          id, project_id, release_id, sync_run_id, snapshot_version, filter_reference,
          source_id, source_watermark, adapter_version, mapping_version, canonicalization_version,
          age_policy_version, observed_count, tombstone_count, selected_count, content_digest, created_at
        ) VALUES ('snapshot_${suffix}_$idSuffix', 'project_${suffix}_a', 'release_$suffix', 'sync_$suffix',
                  :snapshotVersion, 'filter-v1', 'source_${suffix}_a', 'watermark-v1',
                  'adapter-v1', 'mapping-v1', 'release-issue-snapshot-jcs/v1', 'age-policy-v1',
                  1, 0, 1, :digest, now())
        """.trimIndent(),
    ).param("snapshotVersion", snapshotVersion)
        .param("digest", digest("snapshot-$suffix-$idSuffix")).update()

    private fun seedSnapshot(suffix: String) {
        jdbc.sql("INSERT INTO issue_sync_run(id, project_id, source_id, sync_run_id, status, adapter_version, mapping_version, created_at) VALUES ('sync_$suffix', 'project_$suffix', 'source_$suffix', 'run-$suffix', 'SUCCEEDED', 'adapter-v1', 'mapping-v1', now())").update()
        inTransaction {
            jdbc.sql("INSERT INTO release_issue_snapshot(id, project_id, release_id, sync_run_id, snapshot_version, filter_reference, content_digest, created_at) VALUES ('issue_snapshot_$suffix', 'project_$suffix', 'release_$suffix', 'sync_$suffix', 1, 'all', :digest, now())").param("digest", digest('d')).update()
            jdbc.sql("INSERT INTO release_issue_snapshot_item(snapshot_id, ordinal, project_id, issue_id, source_issue_id, title, severity, status, source_version, source_reference, observed_at, mapping_version, fact_digest, created_at) VALUES ('issue_snapshot_$suffix', 0, 'project_$suffix', 'issue_$suffix', 'ISSUE-$suffix', 'title', 'MAJOR', 'OPEN', 'v1', 'ref', now(), 'mapping-v1', :digest, now())").param("digest", digest('e')).update()
        }
        jdbc.sql("INSERT INTO traceability_verification_run(id, project_id, release_id, verification_run_id, status, policy_version, created_at) VALUES ('verify_$suffix', 'project_$suffix', 'release_$suffix', 'verification-$suffix', 'SUCCEEDED', 'policy-v1', now())").update()
        jdbc.sql("INSERT INTO traceability_gap(id, project_id, verification_run_id, release_id, issue_id, expected_edge_type, reason, diagnostic_code, gap_digest, created_at) VALUES ('gap_$suffix', 'project_$suffix', 'verify_$suffix', 'release_$suffix', 'issue_$suffix', 'COMMIT_BUILD', 'missing', 'EDGE_MISSING', :digest, now())").param("digest", digest('f')).update()
        inTransaction {
            jdbc.sql("INSERT INTO traceability_snapshot(id, project_id, release_id, verification_run_id, version, schema_version, policy_version, content_digest, created_at) VALUES ('trace_snapshot_$suffix', 'project_$suffix', 'release_$suffix', 'verify_$suffix', 1, '0.2', 'policy-v1', :digest, now())").param("digest", digest('1')).update()
            jdbc.sql("INSERT INTO traceability_snapshot_edge(snapshot_id, ordinal, project_id, edge_type, from_entity_type, from_entity_id, to_entity_type, to_entity_id, source_edge_id, source_edge_revision, source_type, source_reference, confidence, verification_status, validator_version, fact_digest, created_at) VALUES ('trace_snapshot_$suffix', 0, 'project_$suffix', 'ISSUE_COMMIT', 'ISSUE', 'issue_$suffix', 'COMMIT', 'commit_$suffix', 'immutable_edge', 1, 'CI', 'batch-1', 'HIGH', 'VALID', 'validator-v1', (SELECT content_digest FROM issue_commit_edge_revision WHERE edge_id = 'immutable_edge' AND revision = 1), now())").update()
            jdbc.sql("INSERT INTO traceability_snapshot_gap(snapshot_id, ordinal, project_id, issue_id, release_id, expected_edge_type, reason, diagnostic_code, gap_digest, created_at) VALUES ('trace_snapshot_$suffix', 0, 'project_$suffix', 'issue_$suffix', 'release_$suffix', 'COMMIT_BUILD', 'missing', 'EDGE_MISSING', :digest, now())").param("digest", digest('3')).update()
        }
    }

    private fun seedTraceabilitySnapshotHeader(suffix: String) {
        jdbc.sql("INSERT INTO traceability_verification_run(id, project_id, release_id, verification_run_id, status, policy_version, created_at) VALUES ('verify_$suffix', 'project_$suffix', 'release_$suffix', 'verification-$suffix', 'SUCCEEDED', 'policy-v1', now())").update()
        jdbc.sql("INSERT INTO traceability_snapshot(id, project_id, release_id, verification_run_id, version, schema_version, policy_version, content_digest, created_at) VALUES ('trace_snapshot_$suffix', 'project_$suffix', 'release_$suffix', 'verify_$suffix', 1, '0.2', 'policy-v1', :digest, now())")
            .param("digest", digest("trace-snapshot-$suffix")).update()
    }

    private fun insertSnapshotIssueEdge(snapshotId: String, ordinal: Int, projectId: String, issueId: String, commitId: String, sourceEdgeId: String, sourceReference: String, fromType: String = "ISSUE", toType: String = "COMMIT", factDigest: String? = null) {
        val authoritativeDigest = jdbc.sql("SELECT content_digest FROM issue_commit_edge_revision WHERE edge_id = :edgeId AND revision = 1")
            .param("edgeId", sourceEdgeId).query(String::class.java).single()
        jdbc.sql(
            """
            INSERT INTO traceability_snapshot_edge(
              snapshot_id, ordinal, project_id, edge_type, from_entity_type, from_entity_id,
              to_entity_type, to_entity_id, source_edge_id, source_edge_revision, source_type,
              source_reference, confidence, verification_status, validator_version, fact_digest, created_at
            ) VALUES (
              :snapshotId, :ordinal, :projectId, 'ISSUE_COMMIT', :fromType, :issueId,
              :toType, :commitId, :sourceEdgeId, 1, 'CI', :sourceReference,
              'HIGH', 'VALID', 'validator-v1', :digest, now()
            )
            """.trimIndent(),
        ).param("snapshotId", snapshotId).param("ordinal", ordinal).param("projectId", projectId)
            .param("fromType", fromType).param("issueId", issueId).param("toType", toType).param("commitId", commitId)
            .param("sourceEdgeId", sourceEdgeId).param("sourceReference", sourceReference)
            .param("digest", factDigest ?: authoritativeDigest).update()
    }

    private fun insertSnapshotCommitBuildEdge(snapshotId: String, ordinal: Int, factDigest: String? = null) {
        val authoritativeDigest = jdbc.sql("SELECT content_digest FROM commit_build_edge_revision WHERE edge_id = 'snapshot_scope_a_cb_edge' AND revision = 1")
            .query(String::class.java).single()
        jdbc.sql(
            """
            INSERT INTO traceability_snapshot_edge(
              snapshot_id, ordinal, project_id, edge_type, from_entity_type, from_entity_id,
              to_entity_type, to_entity_id, source_edge_id, source_edge_revision, source_type,
              source_reference, confidence, verification_status, validator_version, fact_digest, created_at
            ) VALUES (
              :snapshotId, :ordinal, 'project_snapshot_scope_a', 'COMMIT_BUILD', 'COMMIT', 'commit_snapshot_scope_a',
              'BUILD', 'build_snapshot_scope_a', 'snapshot_scope_a_cb_edge', 1, 'CI', 'batch-a',
              'HIGH', 'VALID', 'validator-v1', :digest, now()
            )
            """.trimIndent(),
        ).param("snapshotId", snapshotId).param("ordinal", ordinal)
            .param("digest", factDigest ?: authoritativeDigest).update()
    }

    private fun insertSnapshotBuildArtifactEdge(snapshotId: String, ordinal: Int, factDigest: String? = null) {
        val authoritativeDigest = jdbc.sql("SELECT content_digest FROM build_artifact_edge_revision WHERE edge_id = 'snapshot_scope_a_ba_edge' AND revision = 1")
            .query(String::class.java).single()
        jdbc.sql(
            """
            INSERT INTO traceability_snapshot_edge(
              snapshot_id, ordinal, project_id, edge_type, from_entity_type, from_entity_id,
              to_entity_type, to_entity_id, source_edge_id, source_edge_revision, source_type,
              source_reference, confidence, verification_status, validator_version, fact_digest, created_at
            ) VALUES (
              :snapshotId, :ordinal, 'project_snapshot_scope_a', 'BUILD_ARTIFACT', 'BUILD', 'build_snapshot_scope_a',
              'ARTIFACT', 'artifact_snapshot_scope_a', 'snapshot_scope_a_ba_edge', 1, 'CI', 'batch-a',
              'HIGH', 'VALID', 'validator-v1', :digest, now()
            )
            """.trimIndent(),
        ).param("snapshotId", snapshotId).param("ordinal", ordinal)
            .param("digest", factDigest ?: authoritativeDigest).update()
    }

    private fun assertTypedSnapshotDigestTamperRejected(edgeType: String, version: Int) {
        assertThatThrownBy {
            inTransaction {
                val snapshotId = "trace_snapshot_digest_$version"
                insertTraceabilitySnapshotHeader(
                    snapshotId, "project_snapshot_scope_a", "release_snapshot_scope_a",
                    "verify_snapshot_scope_a", version,
                )
                val tamperedDigest = digest("tampered-$edgeType")
                when (edgeType) {
                    "ISSUE_COMMIT" -> insertSnapshotIssueEdge(
                        snapshotId, 0, "project_snapshot_scope_a", "issue_snapshot_scope_a",
                        "commit_snapshot_scope_a", "snapshot_scope_a_edge", "batch-a",
                        factDigest = tamperedDigest,
                    )
                    "COMMIT_BUILD" -> insertSnapshotCommitBuildEdge(snapshotId, 0, tamperedDigest)
                    "BUILD_ARTIFACT" -> insertSnapshotBuildArtifactEdge(snapshotId, 0, tamperedDigest)
                    else -> error("unsupported typed edge $edgeType")
                }
            }
        }.describedAs("tampered %s fact digest", edgeType)
            .hasRootCauseInstanceOf(SQLException::class.java)
    }

    private fun assertArtifactReleaseTamperRejected(version: Int, expression: String, field: String) {
        assertThatThrownBy {
            inTransaction {
                val snapshotId = "trace_snapshot_view_tamper_$version"
                insertTraceabilitySnapshotHeader(snapshotId, "project_view", "release_view", "verify_view", version)
                insertArtifactReleaseEdgeFromView(
                    snapshotId = snapshotId,
                    ordinal = 0,
                    releaseId = "release_view",
                    artifactId = "artifact_view",
                    sourceEdgeIdExpression = if (field == "sourceEdgeId") expression else "authority_edge.source_edge_id",
                    sourceReferenceExpression = if (field == "sourceReference") expression else "authority_edge.source_reference",
                    verifiedAtExpression = if (field == "verifiedAt") expression else "authority_edge.verified_at",
                    validatorVersionExpression = if (field == "validatorVersion") expression else "authority_edge.validator_version",
                    reasonExpression = if (field == "reason") expression else "authority_edge.reason",
                    evidenceIdExpression = if (field == "evidenceId") expression else "authority_edge.evidence_id",
                    factDigestExpression = if (field == "factDigest") expression else "authority_edge.fact_digest",
                    fromEntityIdExpression = if (field == "fromEntityId") expression else "authority_edge.artifact_id",
                )
            }
        }.describedAs("tampered ARTIFACT_RELEASE %s", field)
            .hasRootCauseInstanceOf(SQLException::class.java)
    }

    private fun insertArtifactReleaseEdgeFromView(
        snapshotId: String,
        ordinal: Int,
        releaseId: String,
        artifactId: String,
        sourceEdgeIdExpression: String = "authority_edge.source_edge_id",
        sourceReferenceExpression: String = "authority_edge.source_reference",
        verifiedAtExpression: String = "authority_edge.verified_at",
        validatorVersionExpression: String = "authority_edge.validator_version",
        reasonExpression: String = "authority_edge.reason",
        evidenceIdExpression: String = "authority_edge.evidence_id",
        factDigestExpression: String = "authority_edge.fact_digest",
        fromEntityIdExpression: String = "authority_edge.artifact_id",
    ) {
        val inserted = jdbc.sql(
            """
            INSERT INTO traceability_snapshot_edge(
              snapshot_id, ordinal, project_id, edge_type, from_entity_type, from_entity_id,
              to_entity_type, to_entity_id, source_edge_id, source_edge_revision, source_type,
              source_reference, confidence, verification_status, verified_at, validator_version, reason,
              evidence_id, fact_digest, manifest_revision_id, manifest_digest,
              manifest_artifact_ordinal, manifest_artifact_required, created_at
            )
            SELECT :snapshotId, :ordinal, authority_edge.project_id, 'ARTIFACT_RELEASE', 'ARTIFACT',
                   $fromEntityIdExpression, 'RELEASE', authority_edge.release_id, $sourceEdgeIdExpression,
                   authority_edge.source_edge_revision, authority_edge.source_type, $sourceReferenceExpression,
                   authority_edge.confidence, authority_edge.verification_status, $verifiedAtExpression,
                   $validatorVersionExpression, $reasonExpression, $evidenceIdExpression, $factDigestExpression,
                   authority_edge.manifest_revision_id, authority_edge.manifest_digest,
                   authority_edge.ordinal, authority_edge.required, now()
            FROM artifact_release_edge_v authority_edge
            WHERE authority_edge.release_id = :releaseId AND authority_edge.artifact_id = :artifactId
            """.trimIndent(),
        ).param("snapshotId", snapshotId).param("ordinal", ordinal)
            .param("releaseId", releaseId).param("artifactId", artifactId).update()
        assertThat(inserted).isOne()
    }

    private fun insertTraceabilitySnapshotHeader(
        id: String,
        projectId: String,
        releaseId: String,
        verificationRunId: String,
        version: Int,
    ) {
        jdbc.sql(
            """
            INSERT INTO traceability_snapshot(
              id, project_id, release_id, verification_run_id, version,
              schema_version, policy_version, content_digest, created_at
            ) VALUES (:id, :projectId, :releaseId, :verificationRunId, :version,
                      '0.2', 'policy-v1', :digest, now())
            """.trimIndent(),
        ).param("id", id).param("projectId", projectId).param("releaseId", releaseId)
            .param("verificationRunId", verificationRunId).param("version", version)
            .param("digest", digest(id)).update()
    }

    private fun seedManifestAuthorities(suffix: String) {
        seedProject(suffix)
        listOf("a", "b").forEach { side ->
            val releaseId = "release_${suffix}_$side"
            val artifactId = "artifact_${suffix}_$side"
            val manifestId = "manifest_${suffix}_$side"
            insertRelease(releaseId, "project_$suffix")
            insertArtifact(artifactId)
            jdbc.sql(
                """
                INSERT INTO manifest_revision(
                  id, release_id, revision, content_digest, raw_manifest, canonical_bytes,
                  schema_version, state, created_at, updated_at
                ) VALUES (:manifestId, :releaseId, 1, :digest, '{}'::jsonb,
                          convert_to('{}', 'UTF8'), '0.2', 'VALIDATED', now(), now())
                """.trimIndent(),
            ).param("manifestId", manifestId).param("releaseId", releaseId)
                .param("digest", digest(manifestId)).update()
            jdbc.sql("INSERT INTO manifest_artifact(manifest_id, artifact_id, ordinal, required, created_at) VALUES (:manifestId, :artifactId, 0, true, now())")
                .param("manifestId", manifestId).param("artifactId", artifactId).update()
            jdbc.sql("UPDATE manifest_revision SET state = 'LOCKED' WHERE id = :manifestId")
                .param("manifestId", manifestId).update()
            jdbc.sql("UPDATE release_record SET locked_manifest_id = :manifestId WHERE id = :releaseId")
                .param("manifestId", manifestId).param("releaseId", releaseId).update()
        }
    }

    private fun inTransaction(block: () -> Unit) {
        TransactionTemplate(transactionManager).executeWithoutResult { block() }
    }

    private fun seedProject(suffix: String) = jdbc.sql("INSERT INTO project(id, project_key, name, created_at) VALUES ('project_$suffix', 'key-$suffix', 'project', now())").update()
    private fun insertIssueSource(id: String, projectId: String) = jdbc.sql("INSERT INTO issue_source(id, project_id, source_key, source_type, adapter_version, mapping_version, created_at, updated_at) VALUES (:id, :projectId, :id, 'FIXTURE', 'adapter-v1', 'mapping-v1', now(), now())").param("id", id).param("projectId", projectId).update()
    private fun insertRelease(id: String, projectId: String) = jdbc.sql("INSERT INTO release_record(id, project_id, vehicle, platform, system_version, build_id, status, created_at, updated_at) VALUES (:id, :projectId, 'vehicle', 'platform', '1.0', :id, 'DRAFT', now(), now())").param("id", id).param("projectId", projectId).update()
    private fun insertArtifact(id: String) = jdbc.sql("INSERT INTO artifact(id, identity_digest, artifact_type, locator, checksum_algorithm, checksum_value, created_at) VALUES (:id, :digest, 'APK', '{}'::jsonb, 'SHA-256', :checksum, now())").param("id", id).param("digest", digest(id)).param("checksum", sha256(id)).update()

    private fun insertIssueCommitRevision(id: String, edgeId: String, revision: Int, previousId: String?, previousRevision: Int?, issueId: String, commitId: String, sourceType: String, sourceReference: String, projectId: String = "project_" + issueId.removePrefix("issue_").removeSuffix("_2")) {
        jdbc.sql(
            """
            INSERT INTO issue_commit_edge_revision(
              id, project_id, edge_id, revision, issue_id, commit_id, source_type, source_reference,
              confidence, verification_status, validator_version, previous_revision_id, previous_revision,
              content_digest, created_at
            ) VALUES (:id, :projectId, :edgeId, :revision, :issueId, :commitId, :sourceType, :sourceReference,
                      'HIGH', 'VALID', 'validator-v1', :previousId, :previousRevision, :digest, now())
            """.trimIndent(),
        ).param("id", id).param("projectId", projectId)
            .param("edgeId", edgeId).param("revision", revision).param("issueId", issueId)
            .param("commitId", commitId).param("sourceType", sourceType).param("sourceReference", sourceReference)
            .param("previousId", previousId).param("previousRevision", previousRevision).param("digest", digest(id)).update()
    }

    private fun insertCommitBuildRevision(id: String, edgeId: String, revision: Int, previousId: String?, previousRevision: Int?, commitId: String, buildId: String, sourceType: String, sourceReference: String, projectId: String = "project_revision") {
        jdbc.sql(
            """
            INSERT INTO commit_build_edge_revision(
              id, project_id, edge_id, revision, commit_id, build_id, source_type, source_reference,
              confidence, verification_status, validator_version, previous_revision_id, previous_revision,
              content_digest, created_at
            ) VALUES (:id, :projectId, :edgeId, :revision, :commitId, :buildId, :sourceType, :sourceReference,
                      'HIGH', 'VALID', 'validator-v1', :previousId, :previousRevision, :digest, now())
            """.trimIndent(),
        ).param("id", id).param("projectId", projectId).param("edgeId", edgeId).param("revision", revision).param("commitId", commitId)
            .param("buildId", buildId).param("sourceType", sourceType).param("sourceReference", sourceReference)
            .param("previousId", previousId).param("previousRevision", previousRevision).param("digest", digest(id)).update()
    }

    private fun insertBuildArtifactRevision(id: String, edgeId: String, revision: Int, previousId: String?, previousRevision: Int?, buildId: String, artifactId: String, sourceType: String, sourceReference: String, projectId: String = "project_revision") {
        jdbc.sql(
            """
            INSERT INTO build_artifact_edge_revision(
              id, project_id, edge_id, revision, build_id, artifact_id, source_type, source_reference,
              confidence, verification_status, validator_version, previous_revision_id, previous_revision,
              content_digest, created_at
            ) VALUES (:id, :projectId, :edgeId, :revision, :buildId, :artifactId, :sourceType, :sourceReference,
                      'HIGH', 'VALID', 'validator-v1', :previousId, :previousRevision, :digest, now())
            """.trimIndent(),
        ).param("id", id).param("projectId", projectId).param("edgeId", edgeId).param("revision", revision).param("buildId", buildId)
            .param("artifactId", artifactId).param("sourceType", sourceType).param("sourceReference", sourceReference)
            .param("previousId", previousId).param("previousRevision", previousRevision).param("digest", digest(id)).update()
    }

    private fun digest(character: Char) = "sha256:" + character.lowercaseChar().toString().repeat(64)
    private fun digest(value: String) = "sha256:" + sha256(value)
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
