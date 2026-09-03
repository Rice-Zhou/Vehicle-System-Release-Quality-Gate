package com.ricezhou.vsrqg.traceability

import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.ricezhou.vsrqg.shared.runConcurrently
import com.ricezhou.vsrqg.traceability.application.ArtifactEndpoint
import com.ricezhou.vsrqg.traceability.application.BuildAttemptKey
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceReceipt
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceRepository
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceResult
import com.ricezhou.vsrqg.traceability.application.EdgeCandidate
import com.ricezhou.vsrqg.traceability.application.EdgeRevisionRecord
import com.ricezhou.vsrqg.traceability.application.IssueEndpoint
import com.ricezhou.vsrqg.traceability.domain.Confidence
import com.ricezhou.vsrqg.traceability.domain.ProvenanceProviderId
import com.ricezhou.vsrqg.traceability.domain.ProvenanceValidation
import com.ricezhou.vsrqg.traceability.domain.TraceabilityEdgeType
import com.ricezhou.vsrqg.traceability.domain.VerificationStatus
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class BuildProvenanceRepositoryIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var repository: BuildProvenanceRepository

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `repository resolves snapshot and artifact authority then appends revisions in stable order`() {
        val fixture = seed("resolve")

        val result = inTransaction {
            val context = repository.lockContext(fixture.projectKey, fixture.snapshotId)!!
            assertThat(context.projectId).isEqualTo(fixture.projectId)
            assertThat(context.releaseId).isEqualTo(fixture.releaseId)
            assertThat(context.snapshotDigest).isEqualTo(fixture.snapshotDigest)

            val issues = repository.resolveSnapshotIssues(context, listOf("ISSUE-2", "ISSUE-1"))
            val artifacts = repository.resolveArtifacts(fixture.projectId, listOf(DIGEST_B, DIGEST_A))
            val commit = repository.resolveCommit(fixture.projectId, REPOSITORY, SOURCE_REVISION, NOW)
            val build = repository.resolveBuild(
                fixture.projectId,
                fixture.attemptKey,
                REPOSITORY,
                SOURCE_REVISION,
                NOW,
            )
            val replayedCommit = repository.resolveCommit(fixture.projectId, REPOSITORY, SOURCE_REVISION, LATER)
            val replayedBuild = repository.resolveBuild(
                fixture.projectId,
                fixture.attemptKey,
                REPOSITORY,
                SOURCE_REVISION,
                LATER,
            )

            assertThat(issues).containsExactly(
                IssueEndpoint(fixture.issue1Id, "ISSUE-1"),
                IssueEndpoint(fixture.issue2Id, "ISSUE-2"),
            )
            assertThat(artifacts).containsExactly(
                ArtifactEndpoint(fixture.artifactAId, DIGEST_A),
                ArtifactEndpoint(fixture.artifactBId, DIGEST_B),
            )
            assertThat(replayedCommit).isEqualTo(commit)
            assertThat(replayedBuild).isEqualTo(build)

            val candidates = edgeCandidates(fixture.projectId, issues, commit.commitId, build.buildRecordId, artifacts)
                .reversed()
            repository.appendRevisions(candidates, valid(), NOW)
        }

        assertThat(result.map(EdgeRevisionRecord::edgeType)).containsExactly(
            TraceabilityEdgeType.BUILD_ARTIFACT,
            TraceabilityEdgeType.BUILD_ARTIFACT,
            TraceabilityEdgeType.COMMIT_BUILD,
            TraceabilityEdgeType.ISSUE_COMMIT,
            TraceabilityEdgeType.ISSUE_COMMIT,
        )
        assertThat(result).allMatch { it.revision == 1 }
        assertThat(count("source_commit", "project_id", fixture.projectId)).isOne()
        assertThat(count("build_record", "project_id", fixture.projectId)).isOne()
        assertThat(count("traceability_edge_identity", "project_id", fixture.projectId)).isEqualTo(5)
    }

    @Test
    fun `repository fails closed for cross project snapshot issue artifact and build identity`() {
        val fixture = seed("scope")

        assertThat(repository.lockContext(fixture.otherProjectKey, fixture.snapshotId)).isNull()
        val context = inTransaction { repository.lockContext(fixture.projectKey, fixture.snapshotId)!! }
        assertThatThrownBy {
            repository.resolveSnapshotIssues(context, listOf("ISSUE-1", "ISSUE-CURRENT-ONLY"))
        }.isInstanceOfSatisfying(ResourceNotFound::class.java) {
            assertThat(it.code).isEqualTo("SNAPSHOT_ISSUE_NOT_FOUND")
        }
        assertThatThrownBy {
            repository.resolveArtifacts(fixture.projectId, listOf(DIGEST_A, DIGEST_OTHER_PROJECT))
        }.isInstanceOfSatisfying(ResourceNotFound::class.java) {
            assertThat(it.code).isEqualTo("ARTIFACT_NOT_FOUND")
        }
        assertThatThrownBy {
            repository.resolveArtifacts(fixture.projectId, listOf(DIGEST_IDENTITY_ONLY))
        }.isInstanceOfSatisfying(ResourceNotFound::class.java) {
            assertThat(it.code).isEqualTo("ARTIFACT_NOT_FOUND")
        }

        inTransaction {
            repository.resolveBuild(fixture.projectId, fixture.attemptKey, REPOSITORY, SOURCE_REVISION, NOW)
        }
        assertThatThrownBy {
            inTransaction {
                repository.resolveBuild(
                    fixture.projectId,
                    fixture.attemptKey,
                    "owner/different-repository",
                    SOURCE_REVISION,
                    LATER,
                )
            }
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            inTransaction {
                repository.resolveBuild(
                    fixture.projectId,
                    fixture.attemptKey,
                    REPOSITORY,
                    "b".repeat(40),
                    LATER,
                )
            }
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `same fact reuses revision while new proof and validator observations append immutable history`() {
        val fixture = seed("revision")
        val commit = inTransaction {
            repository.resolveCommit(fixture.projectId, REPOSITORY, SOURCE_REVISION, NOW)
        }
        val candidate = issueCommitCandidate(fixture.projectId, fixture.issue1Id, commit.commitId)

        val first = inTransaction { repository.appendRevisions(listOf(candidate), valid(), NOW).single() }
        val firstBytes = revisionBytes("issue_commit_edge_revision", first.revisionId)
        val replay = inTransaction { repository.appendRevisions(listOf(candidate), valid(), LATER).single() }
        val second = inTransaction {
            repository.appendRevisions(
                listOf(candidate.copy(proofReference = "$PROOF_REFERENCE?check=2", proofDigest = PROOF_DIGEST_2)),
                valid(),
                LATER,
            ).single()
        }
        val error = inTransaction {
            repository.appendRevisions(
                listOf(candidate.copy(proofReference = "$PROOF_REFERENCE?check=3", proofDigest = PROOF_DIGEST_3)),
                ProvenanceValidation(VerificationStatus.ERROR, Confidence.UNKNOWN, VALIDATOR_V2, "PROVIDER_UNAVAILABLE"),
                LATER.plusSeconds(1),
            ).single()
        }

        assertThat(replay).isEqualTo(first)
        assertThat(second.edgeId).isEqualTo(first.edgeId)
        assertThat(second.revision).isEqualTo(2)
        assertThat(second.factDigest).isNotEqualTo(first.factDigest)
        assertThat(error.revision).isEqualTo(3)
        assertThat(error.verificationStatus).isEqualTo(VerificationStatus.ERROR)
        assertThat(error.confidence).isEqualTo(Confidence.UNKNOWN)
        assertThat(revisionBytes("issue_commit_edge_revision", first.revisionId)).isEqualTo(firstBytes)
        assertThat(count("issue_commit_edge_revision", "edge_id", first.edgeId)).isEqualTo(3)
    }

    @Test
    fun `invalid observation after valid proof appends conflict and replay reuses it`() {
        val fixture = seed("conflict")
        val commit = inTransaction {
            repository.resolveCommit(fixture.projectId, REPOSITORY, SOURCE_REVISION, NOW)
        }
        val candidate = issueCommitCandidate(fixture.projectId, fixture.issue1Id, commit.commitId)
        val contradictory = candidate.copy(
            proofReference = "$PROOF_REFERENCE?check=contradiction",
            proofDigest = PROOF_DIGEST_2,
        )
        val invalid = ProvenanceValidation(
            VerificationStatus.INVALID,
            Confidence.LOW,
            VALIDATOR_V2,
            "PROOF_SOURCE_REVISION_MISMATCH",
        )

        val accepted = inTransaction { repository.appendRevisions(listOf(candidate), valid(), NOW).single() }
        val conflict = inTransaction { repository.appendRevisions(listOf(contradictory), invalid, LATER).single() }
        val replay = inTransaction {
            repository.appendRevisions(listOf(contradictory), invalid, LATER.plusSeconds(1)).single()
        }

        assertThat(conflict.edgeId).isEqualTo(accepted.edgeId)
        assertThat(conflict.revision).isEqualTo(2)
        assertThat(conflict.verificationStatus).isEqualTo(VerificationStatus.CONFLICT)
        assertThat(conflict.confidence).isEqualTo(Confidence.LOW)
        assertThat(replay).isEqualTo(conflict)
        assertThat(
            jdbc.sql("SELECT reason_code FROM issue_commit_edge_revision WHERE id = :id")
                .param("id", conflict.revisionId)
                .query(String::class.java)
                .single(),
        ).isEqualTo("PROOF_CONTRADICTS_ACCEPTED")
    }

    @Test
    fun `concurrent identical observations converge on one header and revision`() {
        val fixture = seed("concurrent")
        val commit = inTransaction {
            repository.resolveCommit(fixture.projectId, REPOSITORY, SOURCE_REVISION, NOW)
        }
        val candidate = issueCommitCandidate(fixture.projectId, fixture.issue1Id, commit.commitId)

        val revisions = runConcurrently(2) {
            inTransaction { repository.appendRevisions(listOf(candidate), valid(), NOW).single() }
        }

        assertThat(revisions.map(EdgeRevisionRecord::edgeId)).containsOnly(revisions.first().edgeId)
        assertThat(revisions.map(EdgeRevisionRecord::revisionId)).containsOnly(revisions.first().revisionId)
        assertThat(count("traceability_edge_identity", "project_id", fixture.projectId)).isOne()
        assertThat(count("issue_commit_edge_revision", "project_id", fixture.projectId)).isOne()
    }

    @Test
    fun `receipt replay returns immutable typed result`() {
        val fixture = seed("receipt")
        val persisted = inTransaction {
            val context = repository.lockContext(fixture.projectKey, fixture.snapshotId)!!
            val issues = repository.resolveSnapshotIssues(context, listOf("ISSUE-1"))
            val artifacts = repository.resolveArtifacts(fixture.projectId, listOf(DIGEST_A))
            val commit = repository.resolveCommit(fixture.projectId, REPOSITORY, SOURCE_REVISION, NOW)
            val build = repository.resolveBuild(
                fixture.projectId,
                fixture.attemptKey,
                REPOSITORY,
                SOURCE_REVISION,
                NOW,
            )
            val revisions = repository.appendRevisions(
                edgeCandidates(fixture.projectId, issues, commit.commitId, build.buildRecordId, artifacts),
                valid(),
                NOW,
            )
            ReceiptFacts(revisions, commit.commitId, build.buildRecordId)
        }
        val source = persisted.revisions.reversed().toMutableList()
        val result = BuildProvenanceResult(
            receiptId = fixture.receiptId,
            releaseIssueSnapshotId = fixture.snapshotId,
            sourceCommitId = persisted.commitId,
            buildRecordId = persisted.buildRecordId,
            envelopeDigest = ENVELOPE_DIGEST,
            validatorVersion = VALIDATOR_V1,
            verificationStatus = VerificationStatus.VALID,
            confidence = Confidence.MEDIUM,
            edgeRevisions = source,
        )
        val receipt = BuildProvenanceReceipt(
            receiptId = fixture.receiptId,
            key = fixture.attemptKey,
            envelopeDigest = ENVELOPE_DIGEST,
            result = result,
            issueCount = 1,
            artifactCount = 1,
            actorId = fixture.actorId,
            createdAt = NOW,
        )

        source.clear()
        inTransaction {
            repository.insertReceipt(receipt)
            repository.insertReceipt(receipt)
        }

        assertThat(result.edgeRevisions.map(EdgeRevisionRecord::edgeType)).containsExactly(
            TraceabilityEdgeType.BUILD_ARTIFACT,
            TraceabilityEdgeType.COMMIT_BUILD,
            TraceabilityEdgeType.ISSUE_COMMIT,
        )
        assertThatThrownBy { (result.edgeRevisions as MutableList).clear() }
            .isInstanceOf(UnsupportedOperationException::class.java)
        assertThat(repository.findReceipt(fixture.attemptKey)).isEqualTo(receipt)
        assertThat(repository.readReceipt(fixture.receiptId)).isEqualTo(receipt)
        assertThat(count("build_provenance_receipt", "project_id", fixture.projectId)).isOne()
    }

    private fun seed(suffix: String): Fixture = inTransaction {
        val fixture = Fixture(suffix)
        insertProject(fixture.projectId, fixture.projectKey)
        insertProject(fixture.otherProjectId, fixture.otherProjectKey)
        jdbc.sql(
            "INSERT INTO principal(id, issuer, subject, principal_type, created_at) VALUES (:id, 'test', :id, 'SERVICE', :now)",
        ).param("id", fixture.actorId).param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()
        insertIssueAuthority(fixture)
        insertReleaseAndSnapshot(fixture)
        insertArtifacts(fixture)
        fixture
    }

    private fun insertProject(id: String, key: String) {
        jdbc.sql("INSERT INTO project(id, project_key, name, created_at) VALUES (:id, :key, :key, :now)")
            .param("id", id).param("key", key).param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()
    }

    private fun insertIssueAuthority(fixture: Fixture) {
        jdbc.sql(
            """
            INSERT INTO issue_source(
              id, project_id, source_key, source_type, adapter_version, mapping_version, created_at, updated_at
            ) VALUES (:id, :projectId, :key, 'FIXTURE', 'fixture/v1', 'mapping/v1', :now, :now)
            """.trimIndent(),
        ).param("id", fixture.sourceId).param("projectId", fixture.projectId)
            .param("key", "source-${fixture.suffix}").param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()
        listOf(
            Triple(fixture.issue1Id, "ISSUE-1", DIGEST_ISSUE_1),
            Triple(fixture.issue2Id, "ISSUE-2", DIGEST_ISSUE_2),
            Triple(fixture.currentOnlyIssueId, "ISSUE-CURRENT-ONLY", DIGEST_ISSUE_3),
        ).forEach { (id, sourceIssueId, digest) ->
            jdbc.sql(
                """
                INSERT INTO normalized_issue(
                  id, project_id, source_id, source_issue_id, title, severity, status,
                  source_version, source_reference, observed_at, mapping_version, fact_digest, created_at
                ) VALUES (
                  :id, :projectId, :sourceId, :sourceIssueId, :sourceIssueId, 'MAJOR', 'OPEN',
                  'v1', 'fixture', :now, 'mapping/v1', :digest, :now
                )
                """.trimIndent(),
            ).param("id", id).param("projectId", fixture.projectId).param("sourceId", fixture.sourceId)
                .param("sourceIssueId", sourceIssueId).param("digest", digest)
                .param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()
        }
    }

    private fun insertReleaseAndSnapshot(fixture: Fixture) {
        jdbc.sql(
            """
            INSERT INTO release_record(
              id, project_id, vehicle, platform, system_version, build_id, status, created_at, updated_at
            ) VALUES (:id, :projectId, 'vehicle', 'platform', '1.0', :id, 'DRAFT', :now, :now)
            """.trimIndent(),
        ).param("id", fixture.releaseId).param("projectId", fixture.projectId)
            .param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()
        jdbc.sql(
            """
            INSERT INTO issue_sync_run(
              id, project_id, source_id, sync_run_id, status, source_watermark,
              adapter_version, mapping_version, result_set_mode, filter_reference,
              issue_count, completed_at, created_at
            ) VALUES (
              :id, :projectId, :sourceId, :runId, 'SUCCEEDED', 'watermark',
              'fixture/v1', 'mapping/v1', 'FULL', 'all', 2, :now, :now
            )
            """.trimIndent(),
        ).param("id", fixture.syncRunId).param("projectId", fixture.projectId)
            .param("sourceId", fixture.sourceId).param("runId", "run-${fixture.suffix}")
            .param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()
        jdbc.sql(
            """
            INSERT INTO release_issue_snapshot(
              id, project_id, release_id, sync_run_id, snapshot_version, filter_reference,
              source_id, source_watermark, adapter_version, mapping_version,
              canonicalization_version, age_policy_version, observed_count, tombstone_count,
              selected_count, content_digest, created_at
            ) VALUES (
              :id, :projectId, :releaseId, :syncRunId, 1, 'all',
              :sourceId, 'watermark', 'fixture/v1', 'mapping/v1',
              'release-issue-snapshot-jcs/v1', 'issue-snapshot-age/v1', 2, 0,
              2, :digest, :now
            )
            """.trimIndent(),
        ).param("id", fixture.snapshotId).param("projectId", fixture.projectId)
            .param("releaseId", fixture.releaseId).param("syncRunId", fixture.syncRunId)
            .param("sourceId", fixture.sourceId).param("digest", fixture.snapshotDigest)
            .param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()
        listOf(
            Triple(fixture.issue1Id, "ISSUE-1", DIGEST_ISSUE_1),
            Triple(fixture.issue2Id, "ISSUE-2", DIGEST_ISSUE_2),
        ).forEachIndexed { ordinal, (issueId, sourceIssueId, digest) ->
            jdbc.sql(
                """
                INSERT INTO release_issue_snapshot_item(
                  snapshot_id, ordinal, project_id, issue_id, source_issue_id, title,
                  severity, status, source_version, source_reference, observed_at,
                  mapping_version, fact_digest, created_at
                ) VALUES (
                  :snapshotId, :ordinal, :projectId, :issueId, :sourceIssueId, :sourceIssueId,
                  'MAJOR', 'OPEN', 'v1', 'fixture', :now,
                  'mapping/v1', :digest, :now
                )
                """.trimIndent(),
            ).param("snapshotId", fixture.snapshotId).param("ordinal", ordinal)
                .param("projectId", fixture.projectId).param("issueId", issueId)
                .param("sourceIssueId", sourceIssueId).param("digest", digest)
                .param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()
        }
    }

    private fun insertArtifacts(fixture: Fixture) {
        insertManifestArtifact(fixture.projectId, fixture.releaseId, fixture.manifestId, fixture.artifactAId, DIGEST_A)
        insertArtifact(fixture.artifactBId, DIGEST_B, DIGEST_B)
        jdbc.sql(
            "INSERT INTO manifest_artifact(manifest_id, artifact_id, ordinal, required, created_at) VALUES (:manifestId, :artifactId, 1, true, :now)",
        ).param("manifestId", fixture.manifestId).param("artifactId", fixture.artifactBId)
            .param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()
        insertArtifact(
            fixture.identityOnlyArtifactId,
            DIGEST_IDENTITY_ONLY,
            DIGEST_NOT_REQUESTED,
            "sha256:$DIGEST_IDENTITY_ONLY",
        )
        jdbc.sql(
            "INSERT INTO manifest_artifact(manifest_id, artifact_id, ordinal, required, created_at) VALUES (:manifestId, :artifactId, 2, true, :now)",
        ).param("manifestId", fixture.manifestId).param("artifactId", fixture.identityOnlyArtifactId)
            .param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()

        jdbc.sql(
            """
            INSERT INTO release_record(
              id, project_id, vehicle, platform, system_version, build_id, status, created_at, updated_at
            ) VALUES (:id, :projectId, 'vehicle', 'platform', '1.0', :id, 'DRAFT', :now, :now)
            """.trimIndent(),
        ).param("id", fixture.otherReleaseId).param("projectId", fixture.otherProjectId)
            .param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()
        insertManifestArtifact(
            fixture.otherProjectId,
            fixture.otherReleaseId,
            fixture.otherManifestId,
            fixture.otherArtifactId,
            DIGEST_OTHER_PROJECT,
        )
    }

    private fun insertManifestArtifact(
        @Suppress("UNUSED_PARAMETER") projectId: String,
        releaseId: String,
        manifestId: String,
        artifactId: String,
        checksum: String,
    ) {
        jdbc.sql(
            """
            INSERT INTO manifest_revision(
              id, release_id, revision, content_digest, raw_manifest, canonical_bytes,
              schema_version, state, created_at, updated_at
            ) VALUES (
              :id, :releaseId, 1, :digest, '{}'::jsonb, decode('00', 'hex'),
              'manifest/v1', 'REGISTERED', :now, :now
            )
            """.trimIndent(),
        ).param("id", manifestId).param("releaseId", releaseId)
            .param("digest", prefixedDigest("manifest-$manifestId"))
            .param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()
        insertArtifact(artifactId, checksum, checksum)
        jdbc.sql(
            "INSERT INTO manifest_artifact(manifest_id, artifact_id, ordinal, required, created_at) VALUES (:manifestId, :artifactId, 0, true, :now)",
        ).param("manifestId", manifestId).param("artifactId", artifactId)
            .param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()
    }

    private fun insertArtifact(
        id: String,
        identitySeed: String,
        checksum: String,
        identityDigest: String = prefixedDigest("identity-$id-$identitySeed"),
    ) {
        jdbc.sql(
            """
            INSERT INTO artifact(
              id, identity_digest, artifact_type, locator, checksum_algorithm, checksum_value, created_at
            ) VALUES (:id, :identityDigest, 'APK', '{}'::jsonb, 'SHA-256', :checksum, :now)
            """.trimIndent(),
        ).param("id", id).param("identityDigest", identityDigest)
            .param("checksum", checksum).param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()
    }

    private fun edgeCandidates(
        projectId: String,
        issues: List<IssueEndpoint>,
        commitId: String,
        buildId: String,
        artifacts: List<ArtifactEndpoint>,
    ) = buildList {
        issues.forEach {
            add(issueCommitCandidate(projectId, it.issueId, commitId))
        }
        add(
            EdgeCandidate(
                projectId,
                TraceabilityEdgeType.COMMIT_BUILD,
                commitId,
                buildId,
                "GITHUB_ACTIONS",
                WORKFLOW_REFERENCE,
                PROOF_REFERENCE,
                PROOF_DIGEST_1,
            ),
        )
        artifacts.forEach {
            add(
                EdgeCandidate(
                    projectId,
                    TraceabilityEdgeType.BUILD_ARTIFACT,
                    buildId,
                    it.artifactId,
                    "GITHUB_ACTIONS",
                    WORKFLOW_REFERENCE,
                    PROOF_REFERENCE,
                    PROOF_DIGEST_1,
                ),
            )
        }
    }

    private fun issueCommitCandidate(projectId: String, issueId: String, commitId: String) = EdgeCandidate(
        projectId,
        TraceabilityEdgeType.ISSUE_COMMIT,
        issueId,
        commitId,
        "GITHUB_ACTIONS",
        WORKFLOW_REFERENCE,
        PROOF_REFERENCE,
        PROOF_DIGEST_1,
    )

    private fun valid() = ProvenanceValidation(
        VerificationStatus.VALID,
        Confidence.MEDIUM,
        VALIDATOR_V1,
        "GITHUB_ACTIONS_PROOF_VALID",
    )

    private fun revisionBytes(table: String, id: String): String {
        require(table == "issue_commit_edge_revision")
        return jdbc.sql("SELECT to_jsonb(revision)::text FROM $table revision WHERE id = :id")
            .param("id", id)
            .query(String::class.java)
            .single()
    }

    private fun count(table: String, column: String, value: String): Int {
        require(table in TABLES)
        require(column in COLUMNS)
        return jdbc.sql("SELECT count(*) FROM $table WHERE $column = :value")
            .param("value", value)
            .query(Int::class.java)
            .single()
    }

    private fun <T> inTransaction(action: () -> T): T = TransactionTemplate(transactionManager).execute { action() }!!

    private data class Fixture(val suffix: String) {
        val projectId = "prj_bpr_$suffix"
        val projectKey = "bpr-$suffix"
        val otherProjectId = "prj_bpr_${suffix}_other"
        val otherProjectKey = "bpr-$suffix-other"
        val actorId = "svc_bpr_$suffix"
        val sourceId = "src_bpr_$suffix"
        val releaseId = "rel_bpr_$suffix"
        val otherReleaseId = "rel_bpr_${suffix}_other"
        val syncRunId = "syn_bpr_$suffix"
        val snapshotId = "ris_bpr_$suffix"
        val snapshotDigest = prefixedDigest("snapshot-$suffix")
        val issue1Id = "iss_bpr_${suffix}_1"
        val issue2Id = "iss_bpr_${suffix}_2"
        val currentOnlyIssueId = "iss_bpr_${suffix}_3"
        val manifestId = "mft_bpr_$suffix"
        val otherManifestId = "mft_bpr_${suffix}_other"
        val artifactAId = "art_bpr_${suffix}_a"
        val artifactBId = "art_bpr_${suffix}_b"
        val identityOnlyArtifactId = "art_bpr_${suffix}_identity"
        val otherArtifactId = "art_bpr_${suffix}_other"
        val receiptId = "bpr_bpr_$suffix"
        val attemptKey = BuildAttemptKey(projectId, PROVIDER, PIPELINE, BUILD_ID, 1)
    }

    private data class ReceiptFacts(
        val revisions: List<EdgeRevisionRecord>,
        val commitId: String,
        val buildRecordId: String,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-03T10:15:30Z")
        val LATER: Instant = NOW.plusSeconds(60)
        val PROVIDER = ProvenanceProviderId("github-actions")
        const val PIPELINE = "m2-backend"
        const val BUILD_ID = "33705417856"
        const val REPOSITORY = "owner/repository"
        const val SOURCE_REVISION = "0123456789abcdef0123456789abcdef01234567"
        const val WORKFLOW_REFERENCE = "owner/repository/.github/workflows/m2-backend.yml@refs/heads/main"
        const val PROOF_REFERENCE = "https://github.com/owner/repository/actions/runs/33705417856/attempts/1"
        val DIGEST_A = "a".repeat(64)
        val DIGEST_B = "b".repeat(64)
        val DIGEST_OTHER_PROJECT = "c".repeat(64)
        val DIGEST_IDENTITY_ONLY = "d".repeat(64)
        val DIGEST_NOT_REQUESTED = "e".repeat(64)
        val DIGEST_ISSUE_1 = prefixedDigest("issue-1")
        val DIGEST_ISSUE_2 = prefixedDigest("issue-2")
        val DIGEST_ISSUE_3 = prefixedDigest("issue-3")
        val PROOF_DIGEST_1 = "sha256:${"1".repeat(64)}"
        val PROOF_DIGEST_2 = "sha256:${"2".repeat(64)}"
        val PROOF_DIGEST_3 = "sha256:${"3".repeat(64)}"
        val ENVELOPE_DIGEST = "sha256:${"4".repeat(64)}"
        const val VALIDATOR_V1 = "github-actions-provenance/v1"
        const val VALIDATOR_V2 = "github-actions-provenance/v2"
        val TABLES = setOf(
            "source_commit",
            "build_record",
            "traceability_edge_identity",
            "issue_commit_edge_revision",
            "build_provenance_receipt",
        )
        val COLUMNS = setOf("project_id", "edge_id")

        fun prefixedDigest(seed: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
            return "sha256:" + java.util.HexFormat.of().formatHex(digest)
        }
    }
}
