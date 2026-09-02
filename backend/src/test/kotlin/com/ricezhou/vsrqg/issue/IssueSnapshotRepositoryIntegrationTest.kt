package com.ricezhou.vsrqg.issue

import com.ricezhou.vsrqg.issue.application.IssueSnapshotCandidate
import com.ricezhou.vsrqg.issue.application.IssueSnapshotCanonicalizer
import com.ricezhou.vsrqg.issue.application.IssueSnapshotRepository
import com.ricezhou.vsrqg.issue.application.MaterializedIssueSnapshot
import com.ricezhou.vsrqg.issue.application.SNAPSHOT_AGE_POLICY_VERSION
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionTemplate

@TestPropertySource(
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.vsrqg.test",
        "spring.security.oauth2.resourceserver.jwt.audiences[0]=vsrqg-api",
    ],
)
class IssueSnapshotRepositoryIntegrationTest : PostgresIntegrationTest() {
    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @Autowired private lateinit var repository: IssueSnapshotRepository
    @Autowired private lateinit var canonicalizer: IssueSnapshotCanonicalizer
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var transaction: TransactionTemplate
    @Autowired private lateinit var dataSource: DataSource

    private lateinit var projectId: String
    private lateinit var releaseId: String
    private lateinit var sourceId: String
    private lateinit var runId: String

    @AfterEach
    fun removeFailureTrigger() {
        jdbc.sql("DROP TRIGGER IF EXISTS reject_snapshot_item_for_test ON release_issue_snapshot_item").update()
        jdbc.sql("DROP FUNCTION IF EXISTS reject_snapshot_item_for_test()").update()
    }

    @BeforeEach
    fun seedAuthority() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        projectId = "project_snapshot_$suffix"
        releaseId = "release_snapshot_$suffix"
        sourceId = "source_snapshot_$suffix"
        runId = "run_snapshot_$suffix"
        jdbc.sql("INSERT INTO project(id, project_key, name, created_at) VALUES (:id, :id, :id, now())")
            .param("id", projectId).update()
        jdbc.sql(
            """
            INSERT INTO release_record(
              id, project_id, vehicle, platform, system_version, build_id,
              status, created_at, updated_at
            ) VALUES (:id, :projectId, 'vehicle', 'platform', 'v1', 'build-1', 'REGISTERED', now(), now())
            """.trimIndent(),
        ).param("id", releaseId).param("projectId", projectId).update()
        jdbc.sql(
            """
            INSERT INTO issue_source(
              id, project_id, source_key, source_type, adapter_version,
              mapping_version, enabled, created_at, updated_at
            ) VALUES (:id, :projectId, :id, 'FIXTURE', 'adapter-v1', 'mapping-v1', true, now(), now())
            """.trimIndent(),
        ).param("id", sourceId).param("projectId", projectId).update()
        seedSucceededFullRun(runId, Instant.parse("2026-09-02T12:00:00Z"), listOf("B", "A", "deleted"))
    }

    @Test
    fun `repository resolves locked context latest full run and ordered exact observations`() {
        val manifestId = lockManifest()
        val newerFailed = "failed_${runId.takeLast(8)}"
        seedTerminalRun(newerFailed, "FAILED", "FULL", Instant.parse("2026-09-02T13:00:00Z"))
        val newerDelta = "delta_${runId.takeLast(8)}"
        seedTerminalRun(newerDelta, "SUCCEEDED", "DELTA", Instant.parse("2026-09-02T14:00:00Z"))

        val context = repository.findContext(releaseId, sourceId)
        val locked = transaction.execute { repository.lockContext(releaseId, sourceId) }
        val run = repository.findLatestSuccessfulFullRun(projectId, sourceId)
        val observations = repository.loadObservations(requireNotNull(run))

        assertThat(context).isEqualTo(locked)
        assertThat(context?.lockedManifestId).isEqualTo(manifestId)
        assertThat(run.id).isEqualTo(runId)
        assertThat(observations.map { it.sourceIssueId }).containsExactly("A", "B", "deleted")
        assertThat(observations.map { it.tombstone }).containsExactly(false, false, true)
    }

    @Test
    fun `latest successful full run selects the greatest completion time among eligible runs`() {
        val olderEligible = "eligible_older_${runId.takeLast(8)}"
        val newerEligible = "eligible_newer_${runId.takeLast(8)}"
        seedTerminalRun(olderEligible, "SUCCEEDED", "FULL", Instant.parse("2026-09-02T13:00:00Z"))
        seedTerminalRun(newerEligible, "SUCCEEDED", "FULL", Instant.parse("2026-09-02T14:00:00Z"))

        val selected = requireNotNull(repository.findLatestSuccessfulFullRun(projectId, sourceId))

        assertThat(selected.id).isEqualTo(newerEligible)
        assertThat(selected.completedAt).isEqualTo(Instant.parse("2026-09-02T14:00:00Z"))
    }

    @Test
    fun `latest successful full run uses descending id as the deterministic completion time tie break`() {
        val lowerId = "eligible_a_${runId.takeLast(8)}"
        val greaterId = "eligible_z_${runId.takeLast(8)}"
        val sameCompletionTime = Instant.parse("2026-09-02T15:00:00Z")
        seedTerminalRun(lowerId, "SUCCEEDED", "FULL", sameCompletionTime)
        seedTerminalRun(greaterId, "SUCCEEDED", "FULL", sameCompletionTime)

        val selected = requireNotNull(repository.findLatestSuccessfulFullRun(projectId, sourceId))

        assertThat(selected.id).isEqualTo(greaterId)
        assertThat(selected.completedAt).isEqualTo(sameCompletionTime)
    }

    @Test
    fun `observation loading fails closed for count source or project mismatch`() {
        val run = requireNotNull(repository.findLatestSuccessfulFullRun(projectId, sourceId))

        listOf(
            run.copy(issueCount = run.issueCount + 1),
            run.copy(projectId = "project_outside_scope"),
            run.copy(sourceId = "source_outside_scope"),
        ).forEach { inconsistent ->
            assertThatThrownBy { repository.loadObservations(inconsistent) }
                .isInstanceOf(DataIntegrityViolationException::class.java)
        }
    }

    @Test
    fun `lock context holds release and source row locks until the transaction completes`() {
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val holder = executor.submit {
            transaction.executeWithoutResult {
                assertThat(repository.lockContext(releaseId, sourceId)).isNotNull()
                locked.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Lock test release signal timed out" }
            }
        }
        try {
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue()
            assertLockTimeout("UPDATE release_record SET updated_at = now() WHERE id = ?", releaseId)
            assertLockTimeout("UPDATE issue_source SET updated_at = now() WHERE id = ?", sourceId)
        } finally {
            release.countDown()
            holder.get(5, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    @Test
    fun `repository materializes one logical snapshot with stable next version and verified readback digest`() {
        lockManifest()
        val run = requireNotNull(repository.findLatestSuccessfulFullRun(projectId, sourceId))
        val observations = repository.loadObservations(run)
        val candidate = IssueSnapshotCandidate(
            projectId = projectId,
            releaseId = releaseId,
            snapshotVersion = repository.nextSnapshotVersion(releaseId),
            syncRunId = run.id,
            sourceId = sourceId,
            sourceWatermark = run.sourceWatermark,
            adapterVersion = run.adapterVersion,
            mappingVersion = run.mappingVersion,
            filterReference = run.filterReference,
            agePolicyVersion = SNAPSHOT_AGE_POLICY_VERSION,
            observations = observations.reversed(),
        )
        val canonical = canonicalizer.canonicalize(candidate)
        val stored = MaterializedIssueSnapshot(
            snapshotId = "snapshot_${runId.takeLast(8)}",
            candidate = candidate,
            canonical = canonical,
            createdAt = Instant.parse("2026-09-02T15:00:00Z"),
        )

        transaction.executeWithoutResult { repository.insert(stored) }

        val existing = requireNotNull(repository.findExisting(releaseId, runId, run.filterReference))
        val read = requireNotNull(repository.read(stored.snapshotId))
        assertThat(existing.snapshotId).isEqualTo(stored.snapshotId)
        assertThat(repository.nextSnapshotVersion(releaseId)).isEqualTo(2)
        assertThat(read.canonical.digest).isEqualTo(canonical.digest)
        assertThat(read.canonical.bytes).containsExactly(*canonical.bytes)
        assertThat(read.candidate.observedCount).isEqualTo(3)
        assertThat(read.candidate.tombstoneCount).isOne()
        assertThat(read.candidate.selectedCount).isEqualTo(2)
        assertThat(read.candidate.observations.map { it.sourceIssueId }).containsExactly("A", "B")

        replaceSnapshotDigestBypassingImmutability(stored.snapshotId, digest("corrupt-${stored.snapshotId}"))
        assertThatThrownBy { repository.read(stored.snapshotId) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("digest")
    }

    @Test
    fun `insert rejects a same project observation substituted from another source and run`() {
        lockManifest()
        val authorityRun = requireNotNull(repository.findLatestSuccessfulFullRun(projectId, sourceId))
        val authority = repository.loadObservations(authorityRun)
        val otherSourceId = "source_other_${sourceId.takeLast(8)}"
        seedSource(otherSourceId, "fixture-other")
        val otherRunId = "run_other_${runId.takeLast(8)}"
        seedSucceededFullRun(
            otherRunId,
            Instant.parse("2026-09-02T16:00:00Z"),
            listOf("OTHER-1"),
            otherSourceId,
        )
        val otherRun = requireNotNull(repository.findLatestSuccessfulFullRun(projectId, otherSourceId))
        val substituted = listOf(repository.loadObservations(otherRun).single()) + authority.drop(1)
        val snapshot = snapshot(authorityRun, substituted, "snapshot_other_${runId.takeLast(8)}")

        assertMembershipRejected(snapshot)
    }

    @Test
    fun `insert rejects a same source revision that was not observed by the candidate run`() {
        lockManifest()
        val authorityRun = requireNotNull(repository.findLatestSuccessfulFullRun(projectId, sourceId))
        val authority = repository.loadObservations(authorityRun)
        val otherRunId = "run_unobserved_${runId.takeLast(8)}"
        seedSucceededFullRun(
            otherRunId,
            Instant.parse("2026-09-02T16:00:00Z"),
            listOf("UNOBSERVED-1"),
        )
        val otherRun = requireNotNull(repository.findLatestSuccessfulFullRun(projectId, sourceId))
        val substituted = listOf(repository.loadObservations(otherRun).single()) + authority.drop(1)
        val snapshot = snapshot(authorityRun, substituted, "snapshot_unobserved_${runId.takeLast(8)}")

        assertMembershipRejected(snapshot)
    }

    @Test
    fun `item failure rolls back snapshot header and every item`() {
        lockManifest()
        val run = requireNotNull(repository.findLatestSuccessfulFullRun(projectId, sourceId))
        val candidate = IssueSnapshotCandidate(
            projectId = projectId,
            releaseId = releaseId,
            snapshotVersion = 1,
            syncRunId = run.id,
            sourceId = sourceId,
            sourceWatermark = run.sourceWatermark,
            adapterVersion = run.adapterVersion,
            mappingVersion = run.mappingVersion,
            filterReference = run.filterReference,
            agePolicyVersion = SNAPSHOT_AGE_POLICY_VERSION,
            observations = repository.loadObservations(run),
        )
        val canonical = canonicalizer.canonicalize(candidate)
        val snapshot = MaterializedIssueSnapshot(
            "snapshot_failure_${runId.takeLast(8)}",
            candidate,
            canonical,
            Instant.parse("2026-09-02T15:00:00Z"),
        )
        jdbc.sql(
            """
            CREATE FUNCTION reject_snapshot_item_for_test() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                IF NEW.ordinal = 1 THEN RAISE EXCEPTION 'injected snapshot item failure'; END IF;
                RETURN NEW;
            END;
            ${'$'}${'$'}
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            CREATE TRIGGER reject_snapshot_item_for_test
            BEFORE INSERT ON release_issue_snapshot_item
            FOR EACH ROW EXECUTE FUNCTION reject_snapshot_item_for_test()
            """.trimIndent(),
        ).update()

        assertThatThrownBy { transaction.executeWithoutResult { repository.insert(snapshot) } }
            .isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        assertThat(count("release_issue_snapshot", "id", snapshot.snapshotId)).isZero()
        assertThat(count("release_issue_snapshot_item", "snapshot_id", snapshot.snapshotId)).isZero()
    }

    private fun lockManifest(): String {
        val manifestId = "manifest_${releaseId.takeLast(8)}"
        jdbc.sql(
            """
            INSERT INTO manifest_revision(
              id, release_id, revision, content_digest, raw_manifest, canonical_bytes,
              schema_version, state, created_at, updated_at
            ) VALUES (
              :id, :releaseId, 1, :digest, CAST('{}' AS jsonb), CAST('{}' AS bytea),
              'release-manifest/v0.2', 'LOCKED', now(), now()
            )
            """.trimIndent(),
        ).param("id", manifestId).param("releaseId", releaseId).param("digest", digest("manifest")).update()
        jdbc.sql("UPDATE release_record SET locked_manifest_id = :manifestId WHERE id = :releaseId")
            .param("manifestId", manifestId).param("releaseId", releaseId).update()
        return manifestId
    }

    private fun seedSource(id: String, sourceKey: String) {
        jdbc.sql(
            """
            INSERT INTO issue_source(
              id, project_id, source_key, source_type, adapter_version,
              mapping_version, enabled, created_at, updated_at
            ) VALUES (:id, :projectId, :sourceKey, 'FIXTURE', 'adapter-v1', 'mapping-v1', true, now(), now())
            """.trimIndent(),
        ).param("id", id).param("projectId", projectId).param("sourceKey", sourceKey).update()
    }

    private fun seedSucceededFullRun(
        id: String,
        completedAt: Instant,
        issueIds: List<String>,
        targetSourceId: String = sourceId,
    ) {
        seedTerminalRun(id, "RUNNING", "FULL", completedAt, targetSourceId)
        issueIds.forEachIndexed { index, sourceIssueId ->
            val issueId = "issue_${sourceIssueId}_${id.takeLast(8)}"
            val tombstone = sourceIssueId == "deleted"
            val revisionObservedAt = Instant.parse("2026-09-02T11:00:00Z").plusSeconds(index.toLong())
            val factDigest = com.ricezhou.vsrqg.issue.adapter.IssueFactCanonicalizer.canonicalize(
                com.ricezhou.vsrqg.issue.domain.NormalizedIssue(
                    "FIXTURE", sourceIssueId, "Synthetic $sourceIssueId", IssueSeverity.HIGH, IssueStatus.OPEN,
                    "high", "open", "v1", "fixture:$sourceIssueId", revisionObservedAt, "mapping-v1", tombstone,
                ),
            ).factDigest
            jdbc.sql(
                """
                INSERT INTO normalized_issue(
                  id, project_id, source_id, source_issue_id, title, severity, status,
                  raw_status_token, canonical_source_token, raw_severity_token, mapping_warnings,
                  source_version, source_reference, observed_at,
                  mapping_version, tombstone, fact_digest, fact_digest_version, created_at
                ) VALUES (
                  :id, :projectId, :sourceId, :sourceIssueId, :title, 'HIGH', 'OPEN',
                  'open', 'FIXTURE', 'high', '', 'v1', :sourceReference, :observedAt,
                  'mapping-v1', :tombstone, :digest, 'normalized-issue-facts/v1', now()
                )
                """.trimIndent(),
            ).param("id", issueId).param("projectId", projectId).param("sourceId", targetSourceId)
                .param("sourceIssueId", sourceIssueId).param("title", "Synthetic $sourceIssueId")
                .param("sourceReference", "fixture:$sourceIssueId")
                .param("observedAt", revisionObservedAt.atOffset(ZoneOffset.UTC))
                .param("tombstone", tombstone).param("digest", factDigest).update()
            jdbc.sql(
                """
                INSERT INTO issue_sync_run_item(
                  sync_run_id, ordinal, project_id, source_id, issue_id,
                  source_issue_id, observed_at, created_at
                ) VALUES (:runId, :ordinal, :projectId, :sourceId, :issueId, :sourceIssueId, :observedAt, now())
                """.trimIndent(),
            ).param("runId", id).param("ordinal", index).param("projectId", projectId)
                .param("sourceId", targetSourceId).param("issueId", issueId).param("sourceIssueId", sourceIssueId)
                .param("observedAt", Instant.parse("2026-09-02T12:00:00Z").plusSeconds(index.toLong()).atOffset(ZoneOffset.UTC))
                .update()
        }
        jdbc.sql(
            """
            UPDATE issue_sync_run SET status = 'SUCCEEDED', issue_count = :count,
              source_watermark = 'watermark-v1', completed_at = :completedAt
            WHERE id = :id
            """.trimIndent(),
        ).param("count", issueIds.size).param("completedAt", completedAt.atOffset(ZoneOffset.UTC)).param("id", id).update()
    }

    private fun seedTerminalRun(
        id: String,
        status: String,
        mode: String,
        completedAt: Instant,
        targetSourceId: String = sourceId,
    ) {
        jdbc.sql(
            """
            INSERT INTO issue_sync_run(
              id, project_id, source_id, sync_run_id, status, source_watermark,
              adapter_version, mapping_version, result_set_mode, filter_reference,
              issue_count, completed_at, created_at
            ) VALUES (
              :id, :projectId, :sourceId, :id, :status, 'watermark-v1',
              'adapter-v1', 'mapping-v1', :mode, 'all-relevant-issues/v1',
              0, :completedAt, :createdAt
            )
            """.trimIndent(),
        ).param("id", id).param("projectId", projectId).param("sourceId", targetSourceId)
            .param("status", status).param("mode", mode)
            .param("completedAt", if (status == "RUNNING") null else completedAt.atOffset(ZoneOffset.UTC))
            .param("createdAt", completedAt.minusSeconds(60).atOffset(ZoneOffset.UTC)).update()
    }

    private fun digest(value: String): String = "sha256:" +
        java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun snapshot(
        run: com.ricezhou.vsrqg.issue.application.SuccessfulFullIssueSyncRun,
        observations: List<com.ricezhou.vsrqg.issue.application.SnapshotObservation>,
        snapshotId: String,
    ): MaterializedIssueSnapshot {
        val candidate = IssueSnapshotCandidate(
            projectId = projectId,
            releaseId = releaseId,
            snapshotVersion = 1,
            syncRunId = run.id,
            sourceId = sourceId,
            sourceWatermark = run.sourceWatermark,
            adapterVersion = run.adapterVersion,
            mappingVersion = run.mappingVersion,
            filterReference = run.filterReference,
            agePolicyVersion = SNAPSHOT_AGE_POLICY_VERSION,
            observations = observations,
        )
        return MaterializedIssueSnapshot(
            snapshotId,
            candidate,
            canonicalizer.canonicalize(candidate),
            Instant.parse("2026-09-02T17:00:00Z"),
        )
    }

    private fun assertMembershipRejected(snapshot: MaterializedIssueSnapshot) {
        assertThatThrownBy { transaction.executeWithoutResult { repository.insert(snapshot) } }
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("membership")
        assertThat(count("release_issue_snapshot", "id", snapshot.snapshotId)).isZero()
        assertThat(count("release_issue_snapshot_item", "snapshot_id", snapshot.snapshotId)).isZero()
    }

    private fun assertLockTimeout(sql: String, id: String) {
        assertThatThrownBy {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                connection.createStatement().use { it.execute("SET LOCAL lock_timeout = '100ms'") }
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, id)
                    statement.executeUpdate()
                }
            }
        }.isInstanceOf(java.sql.SQLException::class.java)
    }

    // The bypass is limited to this integrity test; production writes remain protected by immutable triggers.
    private fun replaceSnapshotDigestBypassingImmutability(snapshotId: String, digest: String) {
        dataSource.connection.use { connection ->
            var replicaMode = false
            try {
                connection.createStatement().use {
                    it.execute("SET session_replication_role = replica")
                    replicaMode = true
                }
                connection.prepareStatement(
                    "UPDATE release_issue_snapshot SET content_digest = ? WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, digest)
                    statement.setString(2, snapshotId)
                    assertThat(statement.executeUpdate()).isOne()
                }
            } finally {
                if (replicaMode) {
                    connection.createStatement().use { it.execute("SET session_replication_role = origin") }
                }
            }
        }
    }

    private fun count(table: String, column: String, value: String): Int = jdbc
        .sql("SELECT count(*) FROM $table WHERE $column = :value")
        .param("value", value)
        .query(Int::class.java)
        .single()
}
