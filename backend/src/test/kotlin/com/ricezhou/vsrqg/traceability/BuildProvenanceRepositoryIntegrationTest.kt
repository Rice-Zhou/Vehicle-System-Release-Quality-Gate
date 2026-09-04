package com.ricezhou.vsrqg.traceability

import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.runConcurrently
import com.ricezhou.vsrqg.traceability.application.ArtifactDigestMismatch
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
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

internal data class RepositoryFixtureIdentitySet(val suffix: String) {
    val projectId = "prj_bpr_$suffix"
    val projectKey = "bpr-$suffix"
    val otherProjectId = "prj_bpr_${suffix}_other"
    val otherProjectKey = "bpr-$suffix-other"
    val actorId = "svc_bpr_$suffix"
    val sourceId = "src_bpr_$suffix"
    val sourceKey = "source-$suffix"
    val releaseId = "rel_bpr_$suffix"
    val otherReleaseId = "rel_bpr_${suffix}_other"
    val syncRunId = "syn_bpr_$suffix"
    val snapshotId = "ris_bpr_$suffix"
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

    val schemaBoundValues: Map<String, Pair<String, Int>> = mapOf(
        "project.id" to (projectId to 40),
        "project.other.id" to (otherProjectId to 40),
        "project.project_key" to (projectKey to 100),
        "project.other.project_key" to (otherProjectKey to 100),
        "principal.id" to (actorId to 40),
        "issue_source.id" to (sourceId to 40),
        "issue_source.source_key" to (sourceKey to 120),
        "release_record.id" to (releaseId to 40),
        "release_record.other.id" to (otherReleaseId to 40),
        "issue_sync_run.id" to (syncRunId to 40),
        "release_issue_snapshot.id" to (snapshotId to 40),
        "normalized_issue.first.id" to (issue1Id to 40),
        "normalized_issue.second.id" to (issue2Id to 40),
        "normalized_issue.current.id" to (currentOnlyIssueId to 40),
        "manifest_revision.id" to (manifestId to 40),
        "manifest_revision.other.id" to (otherManifestId to 40),
        "artifact.first.id" to (artifactAId to 40),
        "artifact.second.id" to (artifactBId to 40),
        "artifact.identity.id" to (identityOnlyArtifactId to 40),
        "artifact.other.id" to (otherArtifactId to 40),
        "build_provenance_receipt.id" to (receiptId to 40),
    )
}

class BuildProvenanceRepositoryIntegrationTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var repository: BuildProvenanceRepository

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var idGenerator: ControllableIdGenerator

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
        val commitBuildEndpoints = edgeEndpoints(result[2].edgeId)
        assertThat(
            result.filter { it.edgeType == TraceabilityEdgeType.BUILD_ARTIFACT }
                .map { edgeEndpoints(it.edgeId) },
        ).containsExactly(
            EdgeEndpoints(commitBuildEndpoints.toEntityId, fixture.artifactAId),
            EdgeEndpoints(commitBuildEndpoints.toEntityId, fixture.artifactBId),
        )
        assertThat(
            result.filter { it.edgeType == TraceabilityEdgeType.ISSUE_COMMIT }
                .map { edgeEndpoints(it.edgeId) },
        ).containsExactly(
            EdgeEndpoints(fixture.issue1Id, commitBuildEndpoints.fromEntityId),
            EdgeEndpoints(fixture.issue2Id, commitBuildEndpoints.fromEntityId),
        )
        assertThat(result).allMatch { it.revision == 1 }
        assertThat(count("source_commit", "project_id", fixture.projectId)).isOne()
        assertThat(count("build_record", "project_id", fixture.projectId)).isOne()
        assertThat(count("traceability_edge_identity", "project_id", fixture.projectId)).isEqualTo(5)
    }

    @Test
    fun `repository persists 512 513 and 1024 character source and proof references`() {
        listOf(512, 513, 1024).forEach { length ->
            val fixture = seed("reference-$length")
            inTransaction {
                val commit = repository.resolveCommit(
                    fixture.projectId,
                    REPOSITORY,
                    SOURCE_REVISION,
                    NOW,
                )
                val build = repository.resolveBuild(
                    fixture.projectId,
                    fixture.attemptKey,
                    REPOSITORY,
                    SOURCE_REVISION,
                    NOW,
                )
                val artifacts = repository.resolveArtifacts(fixture.projectId, listOf(DIGEST_A, DIGEST_B))
                val candidates = edgeCandidates(
                    fixture.projectId,
                    listOf(
                        IssueEndpoint(fixture.issue1Id, "ISSUE-1"),
                        IssueEndpoint(fixture.issue2Id, "ISSUE-2"),
                    ),
                    commit.commitId,
                    build.buildRecordId,
                    artifacts,
                ).map {
                    it.copy(
                        sourceReference = "s".repeat(length),
                        proofReference = "p".repeat(length),
                    )
                }
                repository.appendRevisions(candidates, valid(), NOW)
            }

            listOf(
                "issue_commit_edge_revision",
                "commit_build_edge_revision",
                "build_artifact_edge_revision",
            ).forEach { table ->
                val persistedLengths = jdbc.sql(
                    """
                    SELECT min(char_length(source_reference)) AS source_min,
                           max(char_length(source_reference)) AS source_max,
                           min(char_length(proof_reference)) AS proof_min,
                           max(char_length(proof_reference)) AS proof_max
                    FROM $table
                    WHERE project_id = :projectId
                    """.trimIndent(),
                ).param("projectId", fixture.projectId)
                    .query { rs, _ ->
                        listOf(
                            rs.getInt("source_min"),
                            rs.getInt("source_max"),
                            rs.getInt("proof_min"),
                            rs.getInt("proof_max"),
                        )
                    }.single()
                assertThat(persistedLengths).containsExactly(length, length, length, length)
            }
        }
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
            repository.resolveArtifacts(
                fixture.projectId,
                listOf(fixture.identityOnlyArtifactDigest.removePrefix("sha256:")),
            )
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
    fun `repository rejects multiple project artifact identities for one checksum`() {
        val fixture = seed("artifact-ambiguity")
        associateDuplicateChecksumArtifact(fixture)

        assertThatThrownBy {
            repository.resolveArtifacts(fixture.projectId, listOf(DIGEST_A))
        }.isInstanceOfSatisfying(ArtifactDigestMismatch::class.java) {
            assertThat(it.code).isEqualTo("ARTIFACT_DIGEST_MISMATCH")
        }
    }

    @Test
    fun `source commit primary key conflict cannot masquerade as resolved identity`() {
        val fixture = seed("commit-conflict")
        val conflictingId = "cmt_bpr_commit_conflict"
        inTransaction {
            jdbc.sql(
                """
                INSERT INTO source_commit(id, project_id, repository, commit_id, created_at)
                VALUES (:id, :projectId, :repository, :sourceRevision, :now)
                """.trimIndent(),
            )
                .param("id", conflictingId)
                .param("projectId", fixture.projectId)
                .param("repository", "owner/existing-repository")
                .param("sourceRevision", "f".repeat(40))
                .param("now", NOW.atOffset(java.time.ZoneOffset.UTC))
                .update()
        }
        idGenerator.forceNext("cmt_", conflictingId)

        assertThatThrownBy {
            inTransaction {
                repository.resolveCommit(fixture.projectId, REPOSITORY, SOURCE_REVISION, NOW)
            }
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(count("source_commit", "project_id", fixture.projectId)).isOne()
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
    fun `canonical fact binds semantic fields while revision chain metadata stays outside the digest`() {
        val fixture = seed("fact-fields")
        val commit = inTransaction {
            repository.resolveCommit(fixture.projectId, REPOSITORY, SOURCE_REVISION, NOW)
        }
        var candidate = issueCommitCandidate(fixture.projectId, fixture.issue1Id, commit.commitId)
        var validation = ProvenanceValidation(
            VerificationStatus.ERROR,
            Confidence.UNKNOWN,
            VALIDATOR_V1,
            "PROVIDER_UNAVAILABLE",
        )
        var latest = inTransaction { repository.appendRevisions(listOf(candidate), validation, NOW).single() }

        fun appendChanged(nextCandidate: EdgeCandidate, nextValidation: ProvenanceValidation): EdgeRevisionRecord {
            val previous = latest
            val next = inTransaction {
                repository.appendRevisions(listOf(nextCandidate), nextValidation, LATER).single()
            }
            assertThat(next.revision).isEqualTo(previous.revision + 1)
            assertThat(next.factDigest).isNotEqualTo(previous.factDigest)
            assertThat(revisionIdentity(next.revisionId))
                .isEqualTo(RevisionIdentity(previous.revisionId, previous.revision))
            candidate = nextCandidate
            validation = nextValidation
            latest = next
            return next
        }

        appendChanged(candidate.copy(sourceType = "GITHUB_ACTIONS_RECHECK"), validation)
        appendChanged(candidate.copy(sourceReference = "$WORKFLOW_REFERENCE?source=changed"), validation)
        appendChanged(candidate.copy(proofReference = "$PROOF_REFERENCE?proof=changed"), validation)
        appendChanged(candidate.copy(proofDigest = PROOF_DIGEST_2), validation)
        appendChanged(candidate, validation.copy(verificationStatus = VerificationStatus.INVALID))
        appendChanged(candidate, validation.copy(confidence = Confidence.LOW))
        appendChanged(candidate, validation.copy(validatorVersion = VALIDATOR_V2))
        val beforeReasonChange = latest
        val beforeReasonValidation = validation
        appendChanged(candidate, validation.copy(reasonCode = "PROOF_SOURCE_REVISION_MISMATCH"))
        val restoredFields = appendChanged(candidate, beforeReasonValidation)

        assertThat(restoredFields.factDigest).isEqualTo(beforeReasonChange.factDigest)
        assertThat(count("issue_commit_edge_revision", "edge_id", latest.edgeId)).isEqualTo(10)
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
    fun `valid authority survives an error before a later invalid observation`() {
        val fixture = seed("valid-error-invalid")
        val commit = inTransaction {
            repository.resolveCommit(fixture.projectId, REPOSITORY, SOURCE_REVISION, NOW)
        }
        val candidate = issueCommitCandidate(fixture.projectId, fixture.issue1Id, commit.commitId)

        inTransaction { repository.appendRevisions(listOf(candidate), valid(), NOW).single() }
        inTransaction {
            repository.appendRevisions(
                listOf(candidate.copy(proofReference = "$PROOF_REFERENCE?check=error", proofDigest = PROOF_DIGEST_2)),
                ProvenanceValidation(VerificationStatus.ERROR, Confidence.UNKNOWN, VALIDATOR_V2, "PROVIDER_UNAVAILABLE"),
                LATER,
            ).single()
        }
        val conflict = inTransaction {
            repository.appendRevisions(
                listOf(candidate.copy(proofReference = "$PROOF_REFERENCE?check=invalid", proofDigest = PROOF_DIGEST_3)),
                ProvenanceValidation(
                    VerificationStatus.INVALID,
                    Confidence.LOW,
                    VALIDATOR_V2,
                    "PROOF_SOURCE_REVISION_MISMATCH",
                ),
                LATER.plusSeconds(1),
            ).single()
        }

        assertThat(conflict.verificationStatus).isEqualTo(VerificationStatus.CONFLICT)
        assertThat(conflict.confidence).isEqualTo(Confidence.LOW)
        assertThat(revisionStatuses(conflict.edgeId)).containsExactly("VALID", "ERROR", "CONFLICT")
    }

    @Test
    fun `valid authority survives conflict and error before another invalid observation`() {
        val fixture = seed("valid-cf-err-invalid")
        val commit = inTransaction {
            repository.resolveCommit(fixture.projectId, REPOSITORY, SOURCE_REVISION, NOW)
        }
        val candidate = issueCommitCandidate(fixture.projectId, fixture.issue1Id, commit.commitId)
        val invalid = ProvenanceValidation(
            VerificationStatus.INVALID,
            Confidence.LOW,
            VALIDATOR_V2,
            "PROOF_SOURCE_REVISION_MISMATCH",
        )

        inTransaction { repository.appendRevisions(listOf(candidate), valid(), NOW).single() }
        inTransaction {
            repository.appendRevisions(
                listOf(candidate.copy(proofReference = "$PROOF_REFERENCE?check=conflict", proofDigest = PROOF_DIGEST_2)),
                invalid,
                LATER,
            ).single()
        }
        inTransaction {
            repository.appendRevisions(
                listOf(candidate.copy(proofReference = "$PROOF_REFERENCE?check=error", proofDigest = PROOF_DIGEST_3)),
                ProvenanceValidation(VerificationStatus.ERROR, Confidence.UNKNOWN, VALIDATOR_V2, "PROVIDER_UNAVAILABLE"),
                LATER.plusSeconds(1),
            ).single()
        }
        val conflict = inTransaction {
            repository.appendRevisions(
                listOf(candidate.copy(proofReference = "$PROOF_REFERENCE?check=invalid-2", proofDigest = PROOF_DIGEST_4)),
                invalid,
                LATER.plusSeconds(2),
            ).single()
        }

        assertThat(conflict.verificationStatus).isEqualTo(VerificationStatus.CONFLICT)
        assertThat(conflict.confidence).isEqualTo(Confidence.LOW)
        assertThat(revisionStatuses(conflict.edgeId)).containsExactly("VALID", "CONFLICT", "ERROR", "CONFLICT")
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
    fun `receipt replay normalizes PostgreSQL microseconds and returns immutable typed result`() {
        val fixture = seed("receipt")
        val arbitraryNanoseconds = Instant.parse("2026-09-03T10:15:30.123456789Z")
        val baseReceipt = createReceipt(fixture, arbitraryNanoseconds)
        val source = baseReceipt.result.edgeRevisions.toMutableList()
        val receipt = baseReceipt.copy(result = baseReceipt.result.copy(edgeRevisions = source))

        source.clear()
        inTransaction {
            repository.insertReceipt(receipt)
            repository.insertReceipt(receipt)
        }

        assertThat(receipt.result.edgeRevisions.map(EdgeRevisionRecord::edgeType)).containsExactly(
            TraceabilityEdgeType.BUILD_ARTIFACT,
            TraceabilityEdgeType.COMMIT_BUILD,
            TraceabilityEdgeType.ISSUE_COMMIT,
        )
        assertThatThrownBy { (receipt.result.edgeRevisions as MutableList).clear() }
            .isInstanceOf(UnsupportedOperationException::class.java)
        val canonicalReceipt = receipt.copy(createdAt = arbitraryNanoseconds.truncatedTo(ChronoUnit.MICROS))
        assertThat(inTransaction { repository.findReceipt(fixture.attemptKey) }).isEqualTo(canonicalReceipt)
        assertThat(repository.readReceipt(fixture.receiptId)).isEqualTo(canonicalReceipt)
        assertThat(count("build_provenance_receipt", "project_id", fixture.projectId)).isOne()
    }

    @Test
    fun `receipt rejects mismatched snapshot commit and build authorities without persistence`() {
        val fixture = seed("receipt-authority")
        val otherFixture = seed("receipt-authority-peer")
        val receipt = createReceipt(fixture, NOW)
        val incompatibleCommit = inTransaction {
            repository.resolveCommit(fixture.projectId, "owner/incompatible", "e".repeat(40), NOW)
        }
        val invalidReceipts = listOf(
            receiptVariant(
                receipt,
                "commit",
                result = receipt.result.copy(sourceCommitId = incompatibleCommit.commitId),
            ),
            receiptVariant(
                receipt,
                "provider",
                key = receipt.key.copy(provider = ProvenanceProviderId("other-provider")),
            ),
            receiptVariant(receipt, "pipeline", key = receipt.key.copy(pipeline = "other-pipeline")),
            receiptVariant(receipt, "build-id", key = receipt.key.copy(buildId = "other-build")),
            receiptVariant(receipt, "attempt", key = receipt.key.copy(buildAttempt = 2)),
            receiptVariant(receipt, "project", key = receipt.key.copy(projectId = otherFixture.projectId)),
            receiptVariant(
                receipt,
                "snapshot",
                result = receipt.result.copy(releaseIssueSnapshotId = otherFixture.snapshotId),
            ),
        )

        invalidReceipts.forEach { invalid ->
            assertThatThrownBy { inTransaction { repository.insertReceipt(invalid) } }
                .isInstanceOf(DataIntegrityViolationException::class.java)
        }
        assertThat(count("build_provenance_receipt", "project_id", fixture.projectId)).isZero()
        assertThat(count("build_provenance_receipt", "project_id", otherFixture.projectId)).isZero()
    }

    private fun createReceipt(fixture: Fixture, createdAt: Instant): BuildProvenanceReceipt {
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
        val result = BuildProvenanceResult(
            receiptId = fixture.receiptId,
            releaseIssueSnapshotId = fixture.snapshotId,
            sourceCommitId = persisted.commitId,
            buildRecordId = persisted.buildRecordId,
            envelopeDigest = ENVELOPE_DIGEST,
            validatorVersion = VALIDATOR_V1,
            verificationStatus = VerificationStatus.VALID,
            confidence = Confidence.MEDIUM,
            edgeRevisions = persisted.revisions,
        )
        return BuildProvenanceReceipt(
            receiptId = fixture.receiptId,
            key = fixture.attemptKey,
            envelopeDigest = ENVELOPE_DIGEST,
            result = result,
            issueCount = 1,
            artifactCount = 1,
            actorId = fixture.actorId,
            createdAt = createdAt,
        )
    }

    private fun receiptVariant(
        receipt: BuildProvenanceReceipt,
        suffix: String,
        key: BuildAttemptKey = receipt.key,
        result: BuildProvenanceResult = receipt.result,
    ): BuildProvenanceReceipt {
        val receiptId = "${receipt.receiptId}_$suffix"
        return receipt.copy(receiptId = receiptId, key = key, result = result.copy(receiptId = receiptId))
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
            .param("key", fixture.sourceKey).param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()
        listOf(
            Triple(fixture.issue1Id, "ISSUE-1", DIGEST_ISSUE_1),
            Triple(fixture.issue2Id, "ISSUE-2", DIGEST_ISSUE_2),
            Triple(fixture.currentOnlyIssueId, "ISSUE-CURRENT-ONLY", DIGEST_ISSUE_3),
        ).forEach { (id, sourceIssueId, digest) ->
            jdbc.sql(
                """
                INSERT INTO normalized_issue(
                  id, project_id, source_id, source_issue_id, title, severity, status,
                  raw_status_token, canonical_source_token, raw_severity_token, mapping_warnings,
                  source_version, source_reference, observed_at, mapping_version,
                  fact_digest, fact_digest_version, created_at
                ) VALUES (
                  :id, :projectId, :sourceId, :sourceIssueId, :sourceIssueId, 'MAJOR', 'OPEN',
                  'open', 'FIXTURE', 'major', '',
                  'v1', 'fixture', :now, 'mapping/v1',
                  :digest, 'normalized-issue-facts/v1', :now
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
            fixture.identityOnlyArtifactDigest,
        )
        jdbc.sql(
            "INSERT INTO manifest_artifact(manifest_id, artifact_id, ordinal, required, created_at) VALUES (:manifestId, :artifactId, 2, true, :now)",
        ).param("manifestId", fixture.manifestId).param("artifactId", fixture.identityOnlyArtifactId)
            .param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()
        registerManifest(fixture.manifestId)

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
        registerManifest(fixture.otherManifestId)
    }

    private fun associateDuplicateChecksumArtifact(fixture: Fixture) {
        inTransaction {
            val manifestId = "mfd_bpr_${fixture.suffix}"
            val artifactId = "afd_bpr_${fixture.suffix}"
            jdbc.sql(
                """
                INSERT INTO manifest_revision(
                  id, release_id, revision, content_digest, raw_manifest, canonical_bytes,
                  schema_version, state, created_at, updated_at
                ) VALUES (
                  :id, :releaseId, 2, :digest, '{}'::jsonb, decode('00', 'hex'),
                  'manifest/v1', 'DRAFT', :now, :now
                )
                """.trimIndent(),
            ).param("id", manifestId).param("releaseId", fixture.releaseId)
                .param("digest", prefixedDigest("manifest-$manifestId"))
                .param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()
            insertArtifact(artifactId, "duplicate-${fixture.suffix}", DIGEST_A)
            jdbc.sql(
                """
                INSERT INTO manifest_artifact(manifest_id, artifact_id, ordinal, required, created_at)
                VALUES (:manifestId, :artifactId, 0, true, :now)
                """.trimIndent(),
            ).param("manifestId", manifestId).param("artifactId", artifactId)
                .param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()
            registerManifest(manifestId)
        }
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
              'manifest/v1', 'DRAFT', :now, :now
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

    private fun registerManifest(manifestId: String) {
        val updated = jdbc.sql(
            """
            UPDATE manifest_revision
            SET state = 'REGISTERED', row_version = row_version + 1, updated_at = :now
            WHERE id = :manifestId AND state = 'DRAFT'
            """.trimIndent(),
        ).param("manifestId", manifestId).param("now", NOW.atOffset(java.time.ZoneOffset.UTC)).update()
        check(updated == 1) { "Fixture manifest did not transition from DRAFT to REGISTERED" }
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

    private fun edgeEndpoints(edgeId: String): EdgeEndpoints = jdbc.sql(
        "SELECT from_entity_id, to_entity_id FROM traceability_edge_identity WHERE edge_id = :edgeId",
    )
        .param("edgeId", edgeId)
        .query { rs, _ -> EdgeEndpoints(rs.getString("from_entity_id"), rs.getString("to_entity_id")) }
        .single()

    private fun revisionIdentity(revisionId: String): RevisionIdentity = jdbc.sql(
        """
        SELECT previous_revision_id, previous_revision
        FROM issue_commit_edge_revision
        WHERE id = :revisionId
        """.trimIndent(),
    )
        .param("revisionId", revisionId)
        .query { rs, _ ->
            RevisionIdentity(
                rs.getString("previous_revision_id"),
                rs.getObject("previous_revision", Int::class.javaObjectType)!!,
            )
        }
        .single()

    private fun revisionStatuses(edgeId: String): List<String> = jdbc.sql(
        "SELECT verification_status FROM issue_commit_edge_revision WHERE edge_id = :edgeId ORDER BY revision",
    )
        .param("edgeId", edgeId)
        .query(String::class.java)
        .list()

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
        private val identities = RepositoryFixtureIdentitySet(suffix)
        val projectId = identities.projectId
        val projectKey = identities.projectKey
        val otherProjectId = identities.otherProjectId
        val otherProjectKey = identities.otherProjectKey
        val actorId = identities.actorId
        val sourceId = identities.sourceId
        val sourceKey = identities.sourceKey
        val releaseId = identities.releaseId
        val otherReleaseId = identities.otherReleaseId
        val syncRunId = identities.syncRunId
        val snapshotId = identities.snapshotId
        val snapshotDigest = prefixedDigest("snapshot-$suffix")
        val issue1Id = identities.issue1Id
        val issue2Id = identities.issue2Id
        val currentOnlyIssueId = identities.currentOnlyIssueId
        val manifestId = identities.manifestId
        val otherManifestId = identities.otherManifestId
        val artifactAId = identities.artifactAId
        val artifactBId = identities.artifactBId
        val identityOnlyArtifactId = identities.identityOnlyArtifactId
        val identityOnlyArtifactDigest = prefixedDigest("identity-$identityOnlyArtifactId")
        val otherArtifactId = identities.otherArtifactId
        val receiptId = identities.receiptId
        val attemptKey = BuildAttemptKey(projectId, PROVIDER, PIPELINE, BUILD_ID, 1)
    }

    private data class ReceiptFacts(
        val revisions: List<EdgeRevisionRecord>,
        val commitId: String,
        val buildRecordId: String,
    )

    private data class EdgeEndpoints(val fromEntityId: String, val toEntityId: String)

    private data class RevisionIdentity(val revisionId: String, val revision: Int)

    @TestConfiguration(proxyBeanMethods = false)
    class IdGeneratorTestConfiguration {
        @Bean
        @Primary
        fun controllableIdGenerator(): ControllableIdGenerator = ControllableIdGenerator()
    }

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
        val PROOF_DIGEST_4 = "sha256:${"4".repeat(64)}"
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

class BuildProvenanceRepositoryFixtureBoundaryTest {
    @Test
    fun `authority memory fixture identifiers fit their schema columns`() {
        val fixture = RepositoryFixtureIdentitySet("valid-cf-err-invalid")

        assertThat(fixture.schemaBoundValues)
            .allSatisfy { name, bounded ->
                assertThat(bounded.first.length)
                    .describedAs(name)
                    .isLessThanOrEqualTo(bounded.second)
            }
    }
}

class ControllableIdGenerator : IdGenerator {
    private val counter = AtomicLong()
    private val forcedIds = ConcurrentHashMap<String, AtomicReference<String?>>()

    override fun nextId(prefix: String): String =
        forcedIds[prefix]?.getAndSet(null) ?: "$prefix${counter.incrementAndGet()}"

    fun forceNext(prefix: String, id: String) {
        forcedIds.computeIfAbsent(prefix) { AtomicReference() }.set(id)
    }
}
