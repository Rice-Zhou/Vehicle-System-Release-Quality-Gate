package com.ricezhou.vsrqg.shared

import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
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
class IssueMappingProfileMigrationTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `mapping profile table has the exact authority columns constraints index and trigger`() {
        val columns = jdbc.sql(
            """
            SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod), a.attnotnull
            FROM pg_catalog.pg_attribute a
            JOIN pg_catalog.pg_class t ON t.oid = a.attrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname = 'public' AND t.relname = 'issue_mapping_profile'
              AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum
            """.trimIndent(),
        ).query { resultSet, _ ->
            ColumnDefinition(resultSet.getString(1), resultSet.getString(2), resultSet.getBoolean(3))
        }.list()
        assertThat(columns).containsExactly(
            ColumnDefinition("id", "character varying(40)", true),
            ColumnDefinition("project_id", "character varying(40)", true),
            ColumnDefinition("source_id", "character varying(40)", true),
            ColumnDefinition("schema_version", "character varying(80)", true),
            ColumnDefinition("mapping_version", "character varying(80)", true),
            ColumnDefinition("definition", "jsonb", true),
            ColumnDefinition("created_by", "character varying(40)", true),
            ColumnDefinition("created_at", "timestamp with time zone", true),
        )

        val constraints = jdbc.sql(
            """
            SELECT c.conname, pg_get_constraintdef(c.oid)
            FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname = 'public' AND t.relname = 'issue_mapping_profile'
            """.trimIndent(),
        ).query { resultSet, _ -> resultSet.getString(1) to resultSet.getString(2) }.list().toMap()
        assertThat(constraints.keys).containsExactlyInAnyOrder(
            "issue_mapping_profile_pkey",
            "uq_mapping_profile_source_version",
            "fk_mapping_profile_source_project",
            "fk_mapping_profile_creator",
            "ck_mapping_profile_version",
            "ck_mapping_profile_definition_object",
        )
        assertThat(constraints.getValue("uq_mapping_profile_source_version"))
            .isEqualTo("UNIQUE (source_id, mapping_version)")
        assertThat(constraints.getValue("fk_mapping_profile_source_project"))
            .contains("FOREIGN KEY (source_id, project_id)", "REFERENCES issue_source(id, project_id)", "ON DELETE RESTRICT")
        assertThat(constraints.getValue("fk_mapping_profile_creator"))
            .contains("FOREIGN KEY (created_by)", "REFERENCES principal(id)", "ON DELETE RESTRICT")
        assertThat(constraints.getValue("ck_mapping_profile_version")).contains("^sha256:[0-9a-f]{64}$")
        assertThat(constraints.getValue("ck_mapping_profile_definition_object"))
            .contains("jsonb_typeof(definition)", "'object'::text")

        val indexes = jdbc.sql(
            "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'issue_mapping_profile'",
        ).query { resultSet, _ -> resultSet.getString(1) to resultSet.getString(2) }.list().toMap()
        assertThat(indexes.getValue("ix_mapping_profile_project_source_created"))
            .contains("(project_id, source_id, created_at DESC)")

        val triggerDefinition = jdbc.sql(
            """
            SELECT pg_get_triggerdef(t.oid)
            FROM pg_trigger t
            JOIN pg_class c ON c.oid = t.tgrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relname = 'issue_mapping_profile'
              AND t.tgname = 'immutable_issue_mapping_profile' AND NOT t.tgisinternal
            """.trimIndent(),
        ).query(String::class.java).single()
        assertThat(triggerDefinition).contains("BEFORE UPDATE OR DELETE", "reject_immutable_write()")
    }

    @Test
    fun `mapping profile enforces source scoped uniqueness and allows the same digest for different sources`() {
        val fixture = seedFixture()
        insertProfile("profile_${fixture.suffix}_1", fixture.projectA, fixture.sourceA, fixture.principal, VALID_MAPPING_VERSION)

        assertSqlState("23505") {
            insertProfile("profile_${fixture.suffix}_duplicate", fixture.projectA, fixture.sourceA, fixture.principal, VALID_MAPPING_VERSION)
        }

        insertProfile("profile_${fixture.suffix}_2", fixture.projectA, fixture.sourceB, fixture.principal, VALID_MAPPING_VERSION)
        val stored = jdbc.sql(
            """
            SELECT count(*) FROM issue_mapping_profile
            WHERE source_id IN (:sourceIds) AND mapping_version = :mappingVersion
              AND definition = '{"fields":{}}'::jsonb
            """.trimIndent(),
        ).param("sourceIds", listOf(fixture.sourceA, fixture.sourceB))
            .param("mappingVersion", VALID_MAPPING_VERSION).query(Int::class.java).single()
        assertThat(stored).isEqualTo(2)
    }

    @Test
    fun `mapping profile rejects cross project source missing creator and invalid digest`() {
        val fixture = seedFixture(includeSecondProject = true)

        assertSqlState("23503") {
            insertProfile("profile_${fixture.suffix}_cross", fixture.projectB!!, fixture.sourceA, fixture.principal, VALID_MAPPING_VERSION)
        }
        assertSqlState("23503") {
            insertProfile("profile_${fixture.suffix}_creator", fixture.projectA, fixture.sourceA, "missing_${fixture.suffix}", VALID_MAPPING_VERSION)
        }
        assertSqlState("23514") {
            insertProfile("profile_${fixture.suffix}_digest", fixture.projectA, fixture.sourceA, fixture.principal, "mapping-v1")
        }
        assertSqlState("23514") {
            insertProfile(
                "profile_${fixture.suffix}_array",
                fixture.projectA,
                fixture.sourceA,
                fixture.principal,
                VALID_MAPPING_VERSION,
                "[]",
            )
        }
    }

    @Test
    fun `mapping profile rejects update and delete as immutable writes`() {
        val fixture = seedFixture()
        val profileId = "profile_${fixture.suffix}"
        insertProfile(profileId, fixture.projectA, fixture.sourceA, fixture.principal, VALID_MAPPING_VERSION)

        assertSqlState("55000") {
            jdbc.sql("UPDATE issue_mapping_profile SET schema_version = '2' WHERE id = :id")
                .param("id", profileId).update()
        }
        assertSqlState("55000") {
            jdbc.sql("DELETE FROM issue_mapping_profile WHERE id = :id").param("id", profileId).update()
        }
        assertThat(
            jdbc.sql("SELECT schema_version FROM issue_mapping_profile WHERE id = :id")
                .param("id", profileId).query(String::class.java).single(),
        ).isEqualTo("1")
    }

    @Test
    fun `flyway upgrades V4 to V5 preserves historical rows and supports clean and repeated migration`() {
        val schema = "mapping_profile_" + UUID.randomUUID().toString().replace("-", "")
        val v4 = flyway(schema, "4")
        try {
            v4.clean()
            assertThat(v4.migrate().migrationsExecuted).isEqualTo(4)
            assertThat(v4.info().current()!!.version.version).isEqualTo("4")
            dataSource.connection.use { connection -> seedV4History(connection, schema) }

            val current = flyway(schema)
            assertThat(current.migrate().migrationsExecuted).isOne()
            assertThat(current.info().current()!!.version.version).isEqualTo("5")
            assertThat(current.migrate().migrationsExecuted).isZero()
            dataSource.connection.use { connection -> assertV4HistoryUnchanged(connection, schema) }

            current.clean()
            assertThat(current.migrate().migrationsExecuted).isEqualTo(5)
            assertThat(current.info().current()!!.version.version).isEqualTo("5")
            assertThat(current.info().pending()).isEmpty()
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM $schema.issue_mapping_profile").use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getInt(1)).isZero()
                    }
                }
            }
        } finally {
            v4.clean()
        }
    }

    private fun seedFixture(includeSecondProject: Boolean = false): Fixture {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val projectA = "project_a_$suffix"
        val projectB = if (includeSecondProject) "project_b_$suffix" else null
        val principal = "principal_$suffix"
        val sourceA = "source_a_$suffix"
        val sourceB = "source_b_$suffix"
        jdbc.sql("INSERT INTO project(id, project_key, name, created_at) VALUES (:id, :key, 'project', now())")
            .param("id", projectA).param("key", "key-a-$suffix").update()
        if (projectB != null) {
            jdbc.sql("INSERT INTO project(id, project_key, name, created_at) VALUES (:id, :key, 'project', now())")
                .param("id", projectB).param("key", "key-b-$suffix").update()
        }
        jdbc.sql(
            "INSERT INTO principal(id, issuer, subject, principal_type, created_at) VALUES (:id, 'test', :subject, 'USER', now())",
        ).param("id", principal).param("subject", "subject-$suffix").update()
        insertSource(sourceA, projectA, "source-a-$suffix")
        insertSource(sourceB, projectA, "source-b-$suffix")
        return Fixture(suffix, projectA, projectB, principal, sourceA, sourceB)
    }

    private fun insertSource(id: String, projectId: String, sourceKey: String) {
        jdbc.sql(
            """
            INSERT INTO issue_source(
              id, project_id, source_key, source_type, adapter_version, mapping_version, created_at, updated_at
            ) VALUES (:id, :projectId, :sourceKey, 'FIXTURE', 'adapter-v1', 'mapping-v1', now(), now())
            """.trimIndent(),
        ).param("id", id).param("projectId", projectId).param("sourceKey", sourceKey).update()
    }

    private fun insertProfile(
        id: String,
        projectId: String,
        sourceId: String,
        principalId: String,
        mappingVersion: String,
        definition: String = "{\"fields\":{}}",
    ) {
        jdbc.sql(
            """
            INSERT INTO issue_mapping_profile(
              id, project_id, source_id, schema_version, mapping_version, definition, created_by, created_at
            ) VALUES (:id, :projectId, :sourceId, '1', :mappingVersion, CAST(:definition AS jsonb), :principalId, now())
            """.trimIndent(),
        ).param("id", id).param("projectId", projectId).param("sourceId", sourceId)
            .param("mappingVersion", mappingVersion).param("definition", definition)
            .param("principalId", principalId).update()
    }

    private fun assertSqlState(expected: String, action: () -> Unit) {
        var failure: Throwable? = null
        try {
            action()
        } catch (caught: Throwable) {
            failure = caught
        }
        assertThat(failure)
            .isInstanceOf(DataAccessException::class.java)
            .hasRootCauseInstanceOf(SQLException::class.java)
        assertThat(rootSqlException(failure!!).sqlState).isEqualTo(expected)
    }

    private fun rootSqlException(failure: Throwable): SQLException {
        var current = failure
        while (current.cause != null) current = current.cause!!
        return current as SQLException
    }

    private fun flyway(schema: String, target: String? = null): Flyway {
        val configuration = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .schemas(schema).defaultSchema(schema).cleanDisabled(false)
        if (target != null) configuration.target(target)
        return configuration.load()
    }

    private fun seedV4History(connection: Connection, schema: String) {
        connection.createStatement().use { statement ->
            statement.execute("INSERT INTO $schema.project(id, project_key, name, created_at) VALUES ('project_history', 'history', 'history', now())")
            statement.execute("INSERT INTO $schema.issue_source(id, project_id, source_key, source_type, adapter_version, mapping_version, created_at, updated_at) VALUES ('source_history', 'project_history', 'history', 'FIXTURE', 'adapter-v1', 'legacy-mapping', now(), now())")
            statement.execute("INSERT INTO $schema.issue_sync_run(id, project_id, source_id, sync_run_id, status, adapter_version, mapping_version, created_at) VALUES ('sync_history', 'project_history', 'source_history', 'run-history', 'SUCCEEDED', 'adapter-v1', 'legacy-mapping', now())")
            statement.execute("INSERT INTO $schema.normalized_issue(id, project_id, source_id, source_issue_id, title, severity, status, source_version, source_reference, observed_at, mapping_version, fact_digest, created_at) VALUES ('issue_history', 'project_history', 'source_history', 'ISSUE-1', 'history', 'MAJOR', 'OPEN', 'v1', 'ref', now(), 'legacy-mapping', '${digest('a')}', now())")
            statement.execute("INSERT INTO $schema.source_commit(id, project_id, repository, commit_id, created_at) VALUES ('commit_history', 'project_history', 'repo', 'sha', now())")
            statement.execute("INSERT INTO $schema.issue_commit_edge_revision(id, project_id, edge_id, revision, issue_id, commit_id, source_type, source_reference, confidence, verification_status, validator_version, content_digest, created_at) VALUES ('revision_history', 'project_history', 'edge_history', 1, 'issue_history', 'commit_history', 'CI', 'batch', 'HIGH', 'VALID', 'validator-v1', '${digest('b')}', now())")
            statement.execute("INSERT INTO $schema.release_record(id, project_id, vehicle, platform, system_version, build_id, status, created_at, updated_at) VALUES ('release_history', 'project_history', 'vehicle', 'platform', '1.0', 'build', 'DRAFT', now(), now())")
            statement.execute("INSERT INTO $schema.release_issue_snapshot(id, project_id, release_id, sync_run_id, snapshot_version, filter_reference, content_digest, created_at) VALUES ('snapshot_history', 'project_history', 'release_history', 'sync_history', 1, 'all', '${digest('c')}', now())")
        }
    }

    private fun assertV4HistoryUnchanged(connection: Connection, schema: String) {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT s.id, s.adapter_version, s.mapping_version,
                       r.id, r.sync_run_id, r.status, r.adapter_version, r.mapping_version,
                       i.id, i.source_version, i.mapping_version, i.fact_digest,
                       e.id, e.revision, e.validator_version, e.content_digest,
                       p.id, p.snapshot_version, p.content_digest
                FROM $schema.issue_source s
                JOIN $schema.issue_sync_run r ON r.id = 'sync_history' AND r.source_id = s.id
                JOIN $schema.normalized_issue i ON i.id = 'issue_history' AND i.source_id = s.id
                JOIN $schema.issue_commit_edge_revision e ON e.id = 'revision_history' AND e.issue_id = i.id
                JOIN $schema.release_issue_snapshot p ON p.id = 'snapshot_history' AND p.sync_run_id = r.id
                WHERE s.id = 'source_history'
                """.trimIndent(),
            ).use { rows ->
                assertThat(rows.next()).isTrue()
                assertThat(rows.getString(1)).isEqualTo("source_history")
                assertThat(rows.getString(2)).isEqualTo("adapter-v1")
                assertThat(rows.getString(3)).isEqualTo("legacy-mapping")
                assertThat(rows.getString(4)).isEqualTo("sync_history")
                assertThat(rows.getString(5)).isEqualTo("run-history")
                assertThat(rows.getString(6)).isEqualTo("SUCCEEDED")
                assertThat(rows.getString(7)).isEqualTo("adapter-v1")
                assertThat(rows.getString(8)).isEqualTo("legacy-mapping")
                assertThat(rows.getString(9)).isEqualTo("issue_history")
                assertThat(rows.getString(10)).isEqualTo("v1")
                assertThat(rows.getString(11)).isEqualTo("legacy-mapping")
                assertThat(rows.getString(12)).isEqualTo(digest('a'))
                assertThat(rows.getString(13)).isEqualTo("revision_history")
                assertThat(rows.getInt(14)).isOne()
                assertThat(rows.getString(15)).isEqualTo("validator-v1")
                assertThat(rows.getString(16)).isEqualTo(digest('b'))
                assertThat(rows.getString(17)).isEqualTo("snapshot_history")
                assertThat(rows.getInt(18)).isOne()
                assertThat(rows.getString(19)).isEqualTo(digest('c'))
                assertThat(rows.next()).isFalse()
            }
            statement.executeQuery(
                """
                SELECT (SELECT count(*) FROM $schema.issue_source),
                       (SELECT count(*) FROM $schema.issue_sync_run),
                       (SELECT count(*) FROM $schema.normalized_issue),
                       (SELECT count(*) FROM $schema.issue_commit_edge_revision),
                       (SELECT count(*) FROM $schema.release_issue_snapshot)
                """.trimIndent(),
            ).use { rows ->
                assertThat(rows.next()).isTrue()
                assertThat(rows.getInt(1)).isOne()
                assertThat(rows.getInt(2)).isOne()
                assertThat(rows.getInt(3)).isOne()
                assertThat(rows.getInt(4)).isOne()
                assertThat(rows.getInt(5)).isOne()
                assertThat(rows.next()).isFalse()
            }
            statement.executeQuery("SELECT count(*) FROM $schema.issue_mapping_profile").use { rows ->
                assertThat(rows.next()).isTrue()
                assertThat(rows.getInt(1)).isZero()
            }
        }
    }

    private fun digest(character: Char) = "sha256:" + character.toString().repeat(64)

    private data class ColumnDefinition(val name: String, val type: String, val notNull: Boolean)

    private data class Fixture(
        val suffix: String,
        val projectA: String,
        val projectB: String?,
        val principal: String,
        val sourceA: String,
        val sourceB: String,
    )

    private companion object {
        const val VALID_MAPPING_VERSION = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
