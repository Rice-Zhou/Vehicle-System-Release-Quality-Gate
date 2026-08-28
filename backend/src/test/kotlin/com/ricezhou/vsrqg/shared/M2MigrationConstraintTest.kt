package com.ricezhou.vsrqg.shared

import java.security.MessageDigest
import java.util.UUID
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

    private val m2Tables = listOf(
        "background_job",
        "issue_source",
        "issue_sync_run",
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
    fun `flyway supports V3 upgrade clean install and repeat migration`() {
        val schema = "m2_migration_" + UUID.randomUUID().toString().replace("-", "")
        val upgrade = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .schemas(schema).defaultSchema(schema).cleanDisabled(false).target("3").load()
        upgrade.clean()
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(3)

        val current = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .schemas(schema).defaultSchema(schema).cleanDisabled(false).load()
        assertThat(current.migrate().migrationsExecuted).isOne()
        assertThat(current.info().current()!!.version.version).isEqualTo("4")
        assertThat(current.migrate().migrationsExecuted).isZero()

        current.clean()
        assertThat(current.migrate().migrationsExecuted).isEqualTo(4)
        assertThat(current.info().pending()).isEmpty()
        current.clean()
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

        assertConstraintNames(
            "f",
            setOf(
                "fk_background_job_project", "fk_background_job_outbox", "fk_issue_source_project",
                "fk_sync_run_source_project", "fk_sync_cursor_source_project", "fk_sync_cursor_run_source_project",
                "fk_normalized_issue_source_project", "fk_issue_snapshot_release_project", "fk_issue_snapshot_run_project",
                "fk_issue_snapshot_item_snapshot_project", "fk_issue_snapshot_item_issue_project",
                "fk_source_commit_project", "fk_build_record_project",
                "fk_issue_commit_issue_project", "fk_issue_commit_commit_project", "fk_issue_commit_previous",
                "fk_commit_build_commit_project", "fk_commit_build_build_project", "fk_commit_build_previous",
                "fk_build_artifact_build_project", "fk_build_artifact_artifact", "fk_build_artifact_previous",
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
                "uq_issue_snapshot_release_version", "uq_issue_snapshot_digest", "uq_issue_snapshot_item_issue",
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
                "ck_background_job_status", "ck_issue_source_type", "ck_sync_run_status", "ck_normalized_issue_status",
                "ck_normalized_issue_digest", "ck_issue_snapshot_digest", "ck_issue_snapshot_item_digest",
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
            "ix_issue_sync_run_source_created", "ix_issue_sync_cursor_run", "ix_normalized_issue_source_observed",
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
            "immutable_release_issue_snapshot", "immutable_release_issue_snapshot_item",
            "immutable_issue_commit_edge_revision", "immutable_commit_build_edge_revision", "immutable_build_artifact_edge_revision",
            "immutable_traceability_gap", "immutable_traceability_snapshot", "immutable_traceability_snapshot_edge",
            "immutable_traceability_snapshot_gap", "validate_traceability_snapshot_edge_source",
        )
        val triggers = jdbc.sql(
            "SELECT tgname FROM pg_trigger WHERE NOT tgisinternal AND tgname IN (:names)",
        ).param("names", requiredTriggers).query(String::class.java).list()
        assertThat(triggers).containsExactlyInAnyOrderElementsOf(requiredTriggers)
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
        seedTraceabilitySnapshotHeader("snapshot_scope_a")
        insertSnapshotIssueEdge(
            "trace_snapshot_snapshot_scope_a", 0, "project_snapshot_scope_a",
            "issue_snapshot_scope_a", "commit_snapshot_scope_a", "snapshot_scope_a_edge", "batch-a",
        )
        insertSnapshotCommitBuildEdge("trace_snapshot_snapshot_scope_a", 1)
        insertSnapshotBuildArtifactEdge("trace_snapshot_snapshot_scope_a", 2)

        assertThatThrownBy {
            insertSnapshotIssueEdge(
                "trace_snapshot_snapshot_scope_a", 3, "project_snapshot_scope_a",
                "issue_snapshot_scope_b", "commit_snapshot_scope_b", "snapshot_scope_b_edge", "batch-b",
            )
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            insertSnapshotIssueEdge(
                "trace_snapshot_snapshot_scope_a", 3, "project_snapshot_scope_a",
                "issue_snapshot_scope_a", "commit_snapshot_scope_a", "snapshot_scope_a_edge", "batch-a",
                fromType = "COMMIT",
                toType = "ISSUE",
            )
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            jdbc.sql("INSERT INTO traceability_snapshot_gap(snapshot_id, ordinal, project_id, issue_id, release_id, expected_edge_type, reason, diagnostic_code, gap_digest, created_at) VALUES ('trace_snapshot_snapshot_scope_a', 0, 'project_snapshot_scope_b', 'issue_snapshot_scope_b', 'release_snapshot_scope_b', 'COMMIT_BUILD', 'missing', 'EDGE_MISSING', :digest, now())")
                .param("digest", digest("snapshot-gap-project")).update()
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            jdbc.sql("INSERT INTO traceability_snapshot_gap(snapshot_id, ordinal, project_id, issue_id, release_id, expected_edge_type, reason, diagnostic_code, gap_digest, created_at) VALUES ('trace_snapshot_snapshot_scope_a', 0, 'project_snapshot_scope_a', 'issue_snapshot_scope_a', 'release_snapshot_scope_a_2', 'COMMIT_BUILD', 'missing', 'EDGE_MISSING', :digest, now())")
                .param("digest", digest("snapshot-gap-release")).update()
        }.isInstanceOf(DataAccessException::class.java)
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
        jdbc.sql("INSERT INTO traceability_snapshot(id, project_id, release_id, verification_run_id, version, schema_version, policy_version, content_digest, created_at) VALUES ('trace_snapshot_view', 'project_view', 'release_view', 'verify_view', 1, '0.2', 'policy-v1', :digest, now())").param("digest", digest("trace-snapshot-view")).update()
        jdbc.sql(
            """
            INSERT INTO traceability_snapshot_edge(
              snapshot_id, ordinal, project_id, edge_type, from_entity_type, from_entity_id,
              to_entity_type, to_entity_id, source_edge_id, source_edge_revision, source_type,
              source_reference, confidence, verification_status, validator_version, fact_digest,
              manifest_revision_id, manifest_digest, manifest_artifact_ordinal, manifest_artifact_required, created_at
            ) VALUES (
              'trace_snapshot_view', 0, 'project_view', 'ARTIFACT_RELEASE', 'ARTIFACT', 'artifact_view',
              'RELEASE', 'release_view', 'manifest_edge_view', 1, 'MANIFEST', 'manifest_view',
              'HIGH', 'VALID', 'manifest-membership-v1', :factDigest,
              'manifest_view', :manifestDigest, 0, true, now()
            )
            """.trimIndent(),
        ).param("factDigest", digest("artifact-release-view")).param("manifestDigest", digest('c')).update()
        assertThat(jdbc.sql("SELECT count(*) FROM traceability_snapshot_edge WHERE snapshot_id = 'trace_snapshot_view'").query(Int::class.java).single()).isOne()
        assertThatThrownBy {
            jdbc.sql(
                """
                INSERT INTO traceability_snapshot_edge(
                  snapshot_id, ordinal, project_id, edge_type, from_entity_type, from_entity_id,
                  to_entity_type, to_entity_id, source_edge_id, source_edge_revision, source_type,
                  source_reference, confidence, verification_status, validator_version, fact_digest,
                  manifest_revision_id, manifest_digest, manifest_artifact_ordinal, manifest_artifact_required, created_at
                ) VALUES (
                  'trace_snapshot_view', 1, 'project_view', 'ARTIFACT_RELEASE', 'ARTIFACT', 'artifact_view_forged',
                  'RELEASE', 'release_view', 'forged_manifest_edge', 1, 'MANIFEST', 'manifest_view',
                  'HIGH', 'VALID', 'manifest-membership-v1', :factDigest,
                  'manifest_view', :manifestDigest, 0, true, now()
                )
                """.trimIndent(),
            ).param("factDigest", digest("artifact-release-forged")).param("manifestDigest", digest('c')).update()
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            jdbc.sql(
                """
                INSERT INTO traceability_snapshot_edge(
                  snapshot_id, ordinal, project_id, edge_type, from_entity_type, from_entity_id,
                  to_entity_type, to_entity_id, source_edge_id, source_edge_revision, source_type,
                  source_reference, confidence, verification_status, validator_version, fact_digest,
                  manifest_revision_id, manifest_digest, manifest_artifact_ordinal, manifest_artifact_required, created_at
                ) VALUES (
                  'trace_snapshot_view', 1, 'project_view', 'ARTIFACT_RELEASE', 'ARTIFACT', 'artifact_view_forged',
                  'RELEASE', 'release_view_other', 'manifest_edge_other', 1, 'MANIFEST', 'manifest_view_other',
                  'HIGH', 'VALID', 'manifest-membership-v1', :factDigest,
                  'manifest_view_other', :manifestDigest, 0, true, now()
                )
                """.trimIndent(),
            ).param("factDigest", digest("artifact-release-cross-release")).param("manifestDigest", digest('4')).update()
        }.isInstanceOf(DataAccessException::class.java)
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

    private fun seedSnapshot(suffix: String) {
        jdbc.sql("INSERT INTO issue_sync_run(id, project_id, source_id, sync_run_id, status, adapter_version, mapping_version, created_at) VALUES ('sync_$suffix', 'project_$suffix', 'source_$suffix', 'run-$suffix', 'SUCCEEDED', 'adapter-v1', 'mapping-v1', now())").update()
        jdbc.sql("INSERT INTO release_issue_snapshot(id, project_id, release_id, sync_run_id, snapshot_version, filter_reference, content_digest, created_at) VALUES ('issue_snapshot_$suffix', 'project_$suffix', 'release_$suffix', 'sync_$suffix', 1, 'all', :digest, now())").param("digest", digest('d')).update()
        jdbc.sql("INSERT INTO release_issue_snapshot_item(snapshot_id, ordinal, project_id, issue_id, source_issue_id, title, severity, status, source_version, source_reference, observed_at, mapping_version, fact_digest, created_at) VALUES ('issue_snapshot_$suffix', 0, 'project_$suffix', 'issue_$suffix', 'ISSUE-$suffix', 'title', 'MAJOR', 'OPEN', 'v1', 'ref', now(), 'mapping-v1', :digest, now())").param("digest", digest('e')).update()
        jdbc.sql("INSERT INTO traceability_verification_run(id, project_id, release_id, verification_run_id, status, policy_version, created_at) VALUES ('verify_$suffix', 'project_$suffix', 'release_$suffix', 'verification-$suffix', 'SUCCEEDED', 'policy-v1', now())").update()
        jdbc.sql("INSERT INTO traceability_gap(id, project_id, verification_run_id, release_id, issue_id, expected_edge_type, reason, diagnostic_code, gap_digest, created_at) VALUES ('gap_$suffix', 'project_$suffix', 'verify_$suffix', 'release_$suffix', 'issue_$suffix', 'COMMIT_BUILD', 'missing', 'EDGE_MISSING', :digest, now())").param("digest", digest('f')).update()
        jdbc.sql("INSERT INTO traceability_snapshot(id, project_id, release_id, verification_run_id, version, schema_version, policy_version, content_digest, created_at) VALUES ('trace_snapshot_$suffix', 'project_$suffix', 'release_$suffix', 'verify_$suffix', 1, '0.2', 'policy-v1', :digest, now())").param("digest", digest('1')).update()
        jdbc.sql("INSERT INTO traceability_snapshot_edge(snapshot_id, ordinal, project_id, edge_type, from_entity_type, from_entity_id, to_entity_type, to_entity_id, source_edge_id, source_edge_revision, source_type, source_reference, confidence, verification_status, validator_version, fact_digest, created_at) VALUES ('trace_snapshot_$suffix', 0, 'project_$suffix', 'ISSUE_COMMIT', 'ISSUE', 'issue_$suffix', 'COMMIT', 'commit_$suffix', 'immutable_edge', 1, 'CI', 'batch-1', 'HIGH', 'VALID', 'validator-v1', :digest, now())").param("digest", digest('2')).update()
        jdbc.sql("INSERT INTO traceability_snapshot_gap(snapshot_id, ordinal, project_id, issue_id, release_id, expected_edge_type, reason, diagnostic_code, gap_digest, created_at) VALUES ('trace_snapshot_$suffix', 0, 'project_$suffix', 'issue_$suffix', 'release_$suffix', 'COMMIT_BUILD', 'missing', 'EDGE_MISSING', :digest, now())").param("digest", digest('3')).update()
    }

    private fun seedTraceabilitySnapshotHeader(suffix: String) {
        jdbc.sql("INSERT INTO traceability_verification_run(id, project_id, release_id, verification_run_id, status, policy_version, created_at) VALUES ('verify_$suffix', 'project_$suffix', 'release_$suffix', 'verification-$suffix', 'SUCCEEDED', 'policy-v1', now())").update()
        jdbc.sql("INSERT INTO traceability_snapshot(id, project_id, release_id, verification_run_id, version, schema_version, policy_version, content_digest, created_at) VALUES ('trace_snapshot_$suffix', 'project_$suffix', 'release_$suffix', 'verify_$suffix', 1, '0.2', 'policy-v1', :digest, now())")
            .param("digest", digest("trace-snapshot-$suffix")).update()
    }

    private fun insertSnapshotIssueEdge(snapshotId: String, ordinal: Int, projectId: String, issueId: String, commitId: String, sourceEdgeId: String, sourceReference: String, fromType: String = "ISSUE", toType: String = "COMMIT") {
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
            .param("digest", digest("$snapshotId-$ordinal-$sourceEdgeId-$fromType-$toType")).update()
    }

    private fun insertSnapshotCommitBuildEdge(snapshotId: String, ordinal: Int) {
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
            .param("digest", digest("$snapshotId-$ordinal-commit-build")).update()
    }

    private fun insertSnapshotBuildArtifactEdge(snapshotId: String, ordinal: Int) {
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
            .param("digest", digest("$snapshotId-$ordinal-build-artifact")).update()
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
            .param("previousId", previousId).param("previousRevision", previousRevision).param("digest", digest(id.first())).update()
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
