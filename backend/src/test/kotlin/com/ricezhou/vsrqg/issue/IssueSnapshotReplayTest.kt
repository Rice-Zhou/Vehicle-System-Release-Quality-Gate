package com.ricezhou.vsrqg.issue

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.issue.adapter.IssueFactCanonicalizer
import com.ricezhou.vsrqg.issue.application.ActivateIssueMappingProfile
import com.ricezhou.vsrqg.issue.application.ActivateIssueMappingProfileCommand
import com.ricezhou.vsrqg.issue.application.CreateIssueSnapshot
import com.ricezhou.vsrqg.issue.application.CreateIssueSnapshotCommand
import com.ricezhou.vsrqg.issue.application.IssueSnapshotRepository
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import com.ricezhou.vsrqg.issue.domain.NormalizedIssue
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean

class IssueSnapshotReplayTest : PostgresIntegrationTest() {
    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @Autowired private lateinit var createSnapshot: CreateIssueSnapshot
    @Autowired private lateinit var activateProfile: ActivateIssueMappingProfile
    @Autowired private lateinit var snapshotRepository: IssueSnapshotRepository
    @Autowired private lateinit var jdbc: JdbcClient

    private val objectMapper = ObjectMapper()
    private lateinit var suffix: String
    private lateinit var projectId: String
    private lateinit var releaseId: String
    private lateinit var sourceId: String
    private lateinit var initialRunId: String
    private lateinit var principal: Principal

    @BeforeEach
    fun seedAuthority() {
        suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        projectId = "project_replay_$suffix"
        releaseId = "release_replay_$suffix"
        sourceId = "source_replay_$suffix"
        initialRunId = "run_replay_a_$suffix"
        val principalId = "principal_replay_$suffix"
        principal = Principal(ISSUER, "owner-replay-$suffix", false)

        jdbc.sql("INSERT INTO project(id, project_key, name, created_at) VALUES (:id, :id, :id, now())")
            .param("id", projectId).update()
        jdbc.sql(
            """INSERT INTO principal(id, issuer, subject, principal_type, disabled, created_at)
               VALUES (:id, :issuer, :subject, 'USER', false, now())""",
        ).param("id", principalId).param("issuer", ISSUER).param("subject", principal.subject).update()
        jdbc.sql(
            """INSERT INTO project_assignment(project_id, principal_id, role, created_at)
               VALUES (:projectId, :principalId, 'RELEASE_MANAGER', now())""",
        ).param("projectId", projectId).param("principalId", principalId).update()
        jdbc.sql(
            """INSERT INTO release_record(id, project_id, vehicle, platform, system_version, build_id,
                 status, created_at, updated_at)
               VALUES (:id, :projectId, 'vehicle', 'platform', 'v1', 'build', 'REGISTERED', now(), now())""",
        ).param("id", releaseId).param("projectId", projectId).update()
        val manifestId = "manifest_replay_$suffix"
        jdbc.sql(
            """INSERT INTO manifest_revision(id, release_id, revision, content_digest, raw_manifest,
                 canonical_bytes, schema_version, state, created_at, updated_at)
               VALUES (:id, :releaseId, 1, :digest, CAST('{}' AS jsonb), CAST('{}' AS bytea),
                 'release-manifest/v0.2', 'LOCKED', now(), now())""",
        ).param("id", manifestId).param("releaseId", releaseId).param("digest", digest("manifest-$suffix")).update()
        jdbc.sql("UPDATE release_record SET locked_manifest_id=:manifestId WHERE id=:releaseId")
            .param("manifestId", manifestId).param("releaseId", releaseId).update()
        jdbc.sql(
            """INSERT INTO issue_source(id, project_id, source_key, source_type, adapter_version,
                 mapping_version, enabled, created_at, updated_at)
               VALUES (:id, :projectId, :id, 'JIRA', 'jira-cli-pilot-adapter-v1',
                 'mapping-initial-v1', true, now(), now())""",
        ).param("id", sourceId).param("projectId", projectId).update()
        seedFullRun(initialRunId, "mapping-initial-v1", "v1", "Initial issue")
    }

    @Test
    fun `historical snapshot bytes and digest survive later revisions mappings and full syncs`() {
        val created = createSnapshot.create(
            CreateIssueSnapshotCommand(
                principal = principal,
                releaseId = releaseId,
                sourceId = sourceId,
                idempotencyKey = "snapshot-replay-$suffix",
                requestDigest = digest("$releaseId\u0000$sourceId"),
                requestId = "request-replay-$suffix",
            ),
        )
        val baseline = requireNotNull(snapshotRepository.read(created.snapshotId))
        val baselineBytes = baseline.canonical.bytes.copyOf()
        val baselineDigest = baseline.canonical.digest

        insertRevision("revision-later-$suffix", "v2", "mapping-initial-v1", "Later revision")
        assertHistoricalSnapshot(created.snapshotId, baselineBytes, baselineDigest)

        val activated = activateProfile.activate(
            ActivateIssueMappingProfileCommand(
                principal = principal,
                sourceId = sourceId,
                idempotencyKey = "mapping-replay-$suffix",
                definition = mappingDefinition(),
                requestId = "request-mapping-replay-$suffix",
            ),
        )
        assertHistoricalSnapshot(created.snapshotId, baselineBytes, baselineDigest)

        seedFullRun("run_replay_b_$suffix", activated.mappingVersion, "v3", "Newest issue")
        assertHistoricalSnapshot(created.snapshotId, baselineBytes, baselineDigest)
    }

    private fun assertHistoricalSnapshot(snapshotId: String, expectedBytes: ByteArray, expectedDigest: String) {
        val replayed = requireNotNull(snapshotRepository.read(snapshotId))
        assertThat(replayed.canonical.bytes).containsExactly(*expectedBytes)
        assertThat(replayed.canonical.digest).isEqualTo(expectedDigest)
    }

    private fun seedFullRun(runId: String, mappingVersion: String, sourceVersion: String, title: String) {
        jdbc.sql(
            """INSERT INTO issue_sync_run(id, project_id, source_id, sync_run_id, status,
                 source_watermark, adapter_version, mapping_version, result_set_mode, filter_reference,
                 issue_count, completed_at, created_at)
               VALUES (:id, :projectId, :sourceId, :id, 'RUNNING', :watermark,
                 'jira-cli-pilot-adapter-v1', :mappingVersion, 'FULL', 'all-relevant-issues/v1', 0, null, now())""",
        ).param("id", runId).param("projectId", projectId).param("sourceId", sourceId)
            .param("watermark", "watermark-$runId").param("mappingVersion", mappingVersion).update()
        val issueId = "issue_${sourceVersion}_$suffix"
        insertRevision(issueId, sourceVersion, mappingVersion, title)
        jdbc.sql(
            """INSERT INTO issue_sync_run_item(sync_run_id, ordinal, project_id, source_id, issue_id,
                 source_issue_id, observed_at, created_at)
               VALUES (:runId, 0, :projectId, :sourceId, :issueId, 'REPLAY-1', now(), now())""",
        ).param("runId", runId).param("projectId", projectId).param("sourceId", sourceId)
            .param("issueId", issueId).update()
        jdbc.sql(
            "UPDATE issue_sync_run SET status='SUCCEEDED', issue_count=1, completed_at=now() WHERE id=:id",
        ).param("id", runId).update()
    }

    private fun insertRevision(issueId: String, sourceVersion: String, mappingVersion: String, title: String) {
        val observedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val issue = NormalizedIssue(
            source = "JIRA",
            sourceIssueId = "REPLAY-1",
            title = title,
            severity = IssueSeverity.HIGH,
            status = IssueStatus.OPEN,
            rawSeverity = "high",
            rawStatus = "open",
            sourceVersion = sourceVersion,
            sourceReference = "jira:REPLAY-1:$sourceVersion",
            observedAt = observedAt,
            mappingVersion = mappingVersion,
        )
        val factDigest = IssueFactCanonicalizer.canonicalize(issue).factDigest
        jdbc.sql(
            """INSERT INTO normalized_issue(id, project_id, source_id, source_issue_id, title,
                 severity, status, raw_status_token, canonical_source_token, raw_severity_token,
                 mapping_warnings, source_version, source_reference, observed_at, mapping_version,
                 tombstone, fact_digest, fact_digest_version, created_at)
               VALUES (:id, :projectId, :sourceId, :sourceIssueId, :title, 'HIGH', 'OPEN',
                 'open', 'JIRA', 'high', '', :sourceVersion, :sourceReference, :observedAt,
                 :mappingVersion, false, :factDigest, 'normalized-issue-facts/v1', now())""",
        ).param("id", issueId).param("projectId", projectId).param("sourceId", sourceId)
            .param("sourceIssueId", issue.sourceIssueId).param("title", title)
            .param("sourceVersion", sourceVersion).param("sourceReference", issue.sourceReference)
            .param("observedAt", observedAt.atOffset(ZoneOffset.UTC)).param("mappingVersion", mappingVersion)
            .param("factDigest", factDigest).update()
    }

    private fun mappingDefinition(): JsonNode = objectMapper.readTree(
        """{
          "schemaVersion":"jira-mapping-profile/v1",
          "normalizationVersion":"unicode-nfc-trim-root-lower/v1",
          "unknownStatusPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
          "unknownSeverityPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
          "statusAliases":{"OPEN":["open"],"CLOSED":["closed"]},
          "severityAliases":{"HIGH":["high"],"LOW":["low"]}
        }""".trimIndent(),
    )

    private fun digest(value: String): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val ISSUER = "https://idp.vsrqg.test"
    }
}
