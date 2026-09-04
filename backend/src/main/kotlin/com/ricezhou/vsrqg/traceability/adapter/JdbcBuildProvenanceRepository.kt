package com.ricezhou.vsrqg.traceability.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.adapter.toJdbcTimestamp
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.traceability.application.ArtifactDigestMismatch
import com.ricezhou.vsrqg.traceability.application.ArtifactEndpoint
import com.ricezhou.vsrqg.traceability.application.BuildAttemptKey
import com.ricezhou.vsrqg.traceability.application.BuildEndpoint
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceContext
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceInvalid
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceReceipt
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceRepository
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceResult
import com.ricezhou.vsrqg.traceability.application.CommitEndpoint
import com.ricezhou.vsrqg.traceability.application.EdgeCandidate
import com.ricezhou.vsrqg.traceability.application.EdgeRevisionRecord
import com.ricezhou.vsrqg.traceability.application.IssueEndpoint
import com.ricezhou.vsrqg.traceability.domain.Confidence
import com.ricezhou.vsrqg.traceability.domain.ProvenanceProviderId
import com.ricezhou.vsrqg.traceability.domain.ProvenanceValidation
import com.ricezhou.vsrqg.traceability.domain.TraceabilityEdgeType
import com.ricezhou.vsrqg.traceability.domain.VerificationStatus
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Collections
import java.util.HexFormat
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.erdtman.jcs.JsonCanonicalizer

@Repository
class JdbcBuildProvenanceRepository(
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
    private val idGenerator: IdGenerator,
) : BuildProvenanceRepository {
    override fun lockContext(projectReference: String, snapshotId: String): BuildProvenanceContext? {
        val project = jdbc.sql(
            "SELECT id, project_key FROM project WHERE project_key = :projectReference FOR UPDATE",
        )
            .param("projectReference", projectReference)
            .query { rs, _ -> ProjectIdentity(rs.getString("id"), rs.getString("project_key")) }
            .optional()
            .orElse(null) ?: return null
        val snapshot = jdbc.sql(
            """
            SELECT snapshot.id, snapshot.project_id, snapshot.release_id,
                   snapshot.content_digest, snapshot.canonicalization_version,
                   snapshot.selected_count,
                   (SELECT count(*)
                    FROM release_issue_snapshot_item item
                    WHERE item.snapshot_id = snapshot.id
                      AND item.project_id = snapshot.project_id) AS actual_count
            FROM release_issue_snapshot snapshot
            WHERE snapshot.id = :snapshotId AND snapshot.project_id = :projectId
            FOR UPDATE OF snapshot
            """.trimIndent(),
        )
            .param("snapshotId", snapshotId)
            .param("projectId", project.id)
            .query { rs, _ ->
                SnapshotIdentity(
                    id = rs.getString("id"),
                    projectId = rs.getString("project_id"),
                    releaseId = rs.getString("release_id"),
                    digest = rs.getString("content_digest"),
                    canonicalizationVersion = rs.getString("canonicalization_version"),
                    selectedCount = rs.getObject("selected_count", Int::class.javaObjectType),
                    actualCount = rs.getInt("actual_count"),
                )
            }
            .optional()
            .orElse(null) ?: return null
        if (
            snapshot.canonicalizationVersion != SNAPSHOT_CANONICALIZATION_VERSION ||
            snapshot.selectedCount == null ||
            snapshot.selectedCount != snapshot.actualCount
        ) {
            integrityFailure("Release issue snapshot authority is incomplete")
        }
        return BuildProvenanceContext(
            projectId = project.id,
            projectReference = project.reference,
            snapshotId = snapshot.id,
            releaseId = snapshot.releaseId,
            snapshotDigest = snapshot.digest,
        )
    }

    override fun findReceipt(key: BuildAttemptKey): BuildProvenanceReceipt? = findReceipt(key, true)

    override fun resolveSnapshotIssues(
        context: BuildProvenanceContext,
        sourceIssueIds: List<String>,
    ): List<IssueEndpoint> {
        if (sourceIssueIds.isEmpty()) return emptyList()
        val resolved = jdbc.sql(
            """
            SELECT item.issue_id, item.source_issue_id
            FROM release_issue_snapshot_item item
            WHERE item.snapshot_id = :snapshotId
              AND item.project_id = :projectId
              AND item.source_issue_id IN (:sourceIssueIds)
            """.trimIndent(),
        )
            .param("snapshotId", context.snapshotId)
            .param("projectId", context.projectId)
            .param("sourceIssueIds", sourceIssueIds)
            .query { rs, _ -> IssueEndpoint(rs.getString("issue_id"), rs.getString("source_issue_id")) }
            .list()
            .sortedWith(compareBy(UNICODE_CODE_POINT_ORDER, IssueEndpoint::sourceIssueId).thenBy(IssueEndpoint::issueId))
        if (resolved.size != sourceIssueIds.size) {
            throw ResourceNotFound(
                code = "SNAPSHOT_ISSUE_NOT_FOUND",
                resourceTitle = "Snapshot issue not found",
                detail = "One or more requested issues are not members of the release issue snapshot",
            )
        }
        return immutableList(resolved)
    }

    override fun resolveArtifacts(projectId: String, artifactSha256s: List<String>): List<ArtifactEndpoint> {
        if (artifactSha256s.isEmpty()) return emptyList()
        if (artifactSha256s.toSet().size != artifactSha256s.size) {
            throw BuildProvenanceInvalid("ARTIFACT_SHA256_DUPLICATE")
        }
        val resolved = jdbc.sql(
            """
            SELECT artifact.id, artifact.checksum_value
            FROM artifact
            WHERE artifact.checksum_algorithm = 'SHA-256'
              AND artifact.checksum_value IN (:artifactSha256s)
              AND EXISTS (
                SELECT 1
                FROM manifest_artifact link
                JOIN manifest_revision manifest ON manifest.id = link.manifest_id
                JOIN release_record project_release ON project_release.id = manifest.release_id
                WHERE link.artifact_id = artifact.id
                  AND project_release.project_id = :projectId
              )
            """.trimIndent(),
        )
            .param("artifactSha256s", artifactSha256s)
            .param("projectId", projectId)
            .query { rs, _ -> ArtifactEndpoint(rs.getString("id"), rs.getString("checksum_value")) }
            .list()
            .sortedWith(
                compareBy(UNICODE_CODE_POINT_ORDER, ArtifactEndpoint::checksumSha256)
                    .thenBy(UNICODE_CODE_POINT_ORDER, ArtifactEndpoint::artifactId),
            )
        val matchesByChecksum = resolved.groupBy(ArtifactEndpoint::checksumSha256)
        if (artifactSha256s.any { matchesByChecksum[it].isNullOrEmpty() }) {
            throw ResourceNotFound(
                code = "ARTIFACT_NOT_FOUND",
                resourceTitle = "Artifact not found",
                detail = "One or more requested SHA-256 artifacts were not found in the project",
            )
        }
        if (artifactSha256s.any { matchesByChecksum.getValue(it).size > 1 }) {
            throw ArtifactDigestMismatch()
        }
        return immutableList(resolved)
    }

    override fun resolveCommit(
        projectId: String,
        repository: String,
        sourceRevision: String,
        now: Instant,
    ): CommitEndpoint {
        jdbc.sql(
            """
            INSERT INTO source_commit(id, project_id, repository, commit_id, created_at)
            VALUES (:id, :projectId, :repository, :sourceRevision, :createdAt)
            ON CONFLICT (project_id, repository, commit_id) DO NOTHING
            """.trimIndent(),
        )
            .param("id", idGenerator.nextId("cmt_"))
            .param("projectId", projectId)
            .param("repository", repository)
            .param("sourceRevision", sourceRevision)
            .param("createdAt", now.toJdbcTimestamp())
            .update()
        val persisted = jdbc.sql(
            """
            SELECT id, project_id, repository, commit_id
            FROM source_commit
            WHERE project_id = :projectId
              AND repository = :repository
              AND commit_id = :sourceRevision
            """.trimIndent(),
        )
            .param("projectId", projectId)
            .param("repository", repository)
            .param("sourceRevision", sourceRevision)
            .query { rs, _ ->
                PersistedCommit(
                    rs.getString("id"),
                    rs.getString("project_id"),
                    rs.getString("repository"),
                    rs.getString("commit_id"),
                )
            }
            .optional()
            .orElseThrow { DataIntegrityViolationException("Source commit insert did not resolve authority") }
        if (
            persisted.projectId != projectId ||
            persisted.repository != repository ||
            persisted.sourceRevision != sourceRevision
        ) {
            integrityFailure("Source commit identity conflict")
        }
        return CommitEndpoint(persisted.id)
    }

    override fun resolveBuild(
        projectId: String,
        key: BuildAttemptKey,
        repository: String,
        sourceRevision: String,
        now: Instant,
    ): BuildEndpoint {
        if (key.projectId != projectId) integrityFailure("Build attempt project identity conflict")
        jdbc.sql(
            """
            INSERT INTO build_record(
              id, project_id, provider, pipeline, build_id, source_revision,
              repository, build_attempt, created_at
            ) VALUES (
              :id, :projectId, :provider, :pipeline, :buildId, :sourceRevision,
              :repository, :buildAttempt, :createdAt
            )
            ON CONFLICT (project_id, provider, pipeline, build_id, build_attempt)
              WHERE repository IS NOT NULL AND build_attempt IS NOT NULL
              DO NOTHING
            """.trimIndent(),
        )
            .param("id", idGenerator.nextId("bld_"))
            .param("projectId", projectId)
            .param("provider", key.provider.value)
            .param("pipeline", key.pipeline)
            .param("buildId", key.buildId)
            .param("sourceRevision", sourceRevision)
            .param("repository", repository)
            .param("buildAttempt", key.buildAttempt)
            .param("createdAt", now.toJdbcTimestamp())
            .update()
        val persisted = jdbc.sql(
            """
            SELECT id, project_id, provider, pipeline, build_id,
                   build_attempt, repository, source_revision
            FROM build_record
            WHERE project_id = :projectId
              AND provider = :provider
              AND pipeline = :pipeline
              AND build_id = :buildId
              AND build_attempt = :buildAttempt
            """.trimIndent(),
        )
            .param("projectId", projectId)
            .param("provider", key.provider.value)
            .param("pipeline", key.pipeline)
            .param("buildId", key.buildId)
            .param("buildAttempt", key.buildAttempt)
            .query(::mapBuild)
            .optional()
            .orElseThrow { DataIntegrityViolationException("Build insert did not resolve authority") }
        if (!persisted.matches(key, repository, sourceRevision)) {
            integrityFailure("Build attempt identity conflict")
        }
        return BuildEndpoint(persisted.id)
    }

    override fun appendRevisions(
        candidates: List<EdgeCandidate>,
        validation: ProvenanceValidation,
        now: Instant,
    ): List<EdgeRevisionRecord> = immutableList(
        candidates
            .sortedWith(compareBy({ it.edgeType.name }, EdgeCandidate::fromEntityId, EdgeCandidate::toEntityId))
            .map { appendRevision(it, validation, now) },
    )

    override fun insertReceipt(receipt: BuildProvenanceReceipt) {
        val canonicalReceipt = receipt.copy(createdAt = receipt.createdAt.toJdbcTimestamp().toInstant())
        requireReceiptConsistency(canonicalReceipt)
        lockAndVerifyReceiptAuthority(canonicalReceipt)
        jdbc.sql(
            """
            INSERT INTO build_provenance_receipt(
              id, project_id, provider, pipeline, provider_build_id, build_attempt,
              envelope_digest, release_issue_snapshot_id, source_commit_id, build_record_id,
              validator_version, verification_status, confidence, issue_count, artifact_count,
              edge_count, response_body, actor_id, created_at
            ) VALUES (
              :id, :projectId, :provider, :pipeline, :providerBuildId, :buildAttempt,
              :envelopeDigest, :snapshotId, :commitId, :buildRecordId,
              :validatorVersion, :verificationStatus, :confidence, :issueCount, :artifactCount,
              :edgeCount, CAST(:responseBody AS jsonb), :actorId, :createdAt
            )
            ON CONFLICT (project_id, provider, pipeline, provider_build_id, build_attempt) DO NOTHING
            """.trimIndent(),
        )
            .param("id", canonicalReceipt.receiptId)
            .param("projectId", canonicalReceipt.key.projectId)
            .param("provider", canonicalReceipt.key.provider.value)
            .param("pipeline", canonicalReceipt.key.pipeline)
            .param("providerBuildId", canonicalReceipt.key.buildId)
            .param("buildAttempt", canonicalReceipt.key.buildAttempt)
            .param("envelopeDigest", canonicalReceipt.envelopeDigest)
            .param("snapshotId", canonicalReceipt.result.releaseIssueSnapshotId)
            .param("commitId", canonicalReceipt.result.sourceCommitId)
            .param("buildRecordId", canonicalReceipt.result.buildRecordId)
            .param("validatorVersion", canonicalReceipt.result.validatorVersion)
            .param("verificationStatus", canonicalReceipt.result.verificationStatus.name)
            .param("confidence", canonicalReceipt.result.confidence.name)
            .param("issueCount", canonicalReceipt.issueCount)
            .param("artifactCount", canonicalReceipt.artifactCount)
            .param("edgeCount", canonicalReceipt.result.edgeRevisions.size)
            .param("responseBody", objectMapper.writeValueAsString(canonicalReceipt.result))
            .param("actorId", canonicalReceipt.actorId)
            .param("createdAt", canonicalReceipt.createdAt.toJdbcTimestamp())
            .update()
        val persisted = findReceipt(canonicalReceipt.key, false)
            ?: throw DataIntegrityViolationException("Build provenance receipt insert did not resolve authority")
        if (persisted != canonicalReceipt) integrityFailure("Build provenance receipt identity conflict")
    }

    override fun readReceipt(receiptId: String): BuildProvenanceReceipt? = jdbc.sql(
        "$RECEIPT_SELECT WHERE receipt.id = :receiptId",
    )
        .param("receiptId", receiptId)
        .query(::mapReceipt)
        .optional()
        .orElse(null)

    private fun appendRevision(
        candidate: EdgeCandidate,
        validation: ProvenanceValidation,
        now: Instant,
    ): EdgeRevisionRecord {
        val header = lockHeader(candidate, now)
        val latest = latestRevision(header)
        val effectiveValidation = effectiveValidation(hasAcceptedValidRevision(header), validation)
        val replayDigest = factDigest(candidate, effectiveValidation)
        if (latest != null && latest.factDigest == replayDigest) return latest.toRecord(header)

        val revision = (latest?.revision ?: 0) + 1
        val revisionId = idGenerator.nextId("rev_")
        val digest = replayDigest
        insertRevision(
            header = header,
            candidate = candidate,
            validation = effectiveValidation,
            revisionId = revisionId,
            revision = revision,
            previous = latest,
            digest = digest,
            now = now,
        )
        val persisted = revisionById(header, revisionId)
            ?: throw DataIntegrityViolationException("Edge revision insert did not resolve authority")
        if (
            persisted.revision != revision ||
            persisted.factDigest != digest ||
            persisted.verificationStatus != effectiveValidation.verificationStatus ||
            persisted.confidence != effectiveValidation.confidence ||
            persisted.previousRevisionId != latest?.revisionId ||
            persisted.previousRevision != latest?.revision
        ) {
            integrityFailure("Edge revision identity conflict")
        }
        return persisted.toRecord(header)
    }

    private fun lockHeader(candidate: EdgeCandidate, now: Instant): EdgeHeader {
        jdbc.sql(
            """
            INSERT INTO traceability_edge_identity(
              edge_id, project_id, edge_type, from_entity_id, to_entity_id, created_at
            ) VALUES (:edgeId, :projectId, :edgeType, :fromId, :toId, :createdAt)
            ON CONFLICT (project_id, edge_type, from_entity_id, to_entity_id) DO NOTHING
            """.trimIndent(),
        )
            .param("edgeId", idGenerator.nextId("edg_"))
            .param("projectId", candidate.projectId)
            .param("edgeType", candidate.edgeType.name)
            .param("fromId", candidate.fromEntityId)
            .param("toId", candidate.toEntityId)
            .param("createdAt", now.toJdbcTimestamp())
            .update()
        val header = jdbc.sql(
            """
            SELECT edge_id, project_id, edge_type, from_entity_id, to_entity_id
            FROM traceability_edge_identity
            WHERE project_id = :projectId
              AND edge_type = :edgeType
              AND from_entity_id = :fromId
              AND to_entity_id = :toId
            FOR UPDATE
            """.trimIndent(),
        )
            .param("projectId", candidate.projectId)
            .param("edgeType", candidate.edgeType.name)
            .param("fromId", candidate.fromEntityId)
            .param("toId", candidate.toEntityId)
            .query { rs, _ ->
                EdgeHeader(
                    rs.getString("edge_id"),
                    rs.getString("project_id"),
                    TraceabilityEdgeType.valueOf(rs.getString("edge_type")),
                    rs.getString("from_entity_id"),
                    rs.getString("to_entity_id"),
                )
            }
            .optional()
            .orElseThrow { DataIntegrityViolationException("Edge header insert did not resolve authority") }
        if (!header.matches(candidate)) integrityFailure("Edge header identity conflict")
        return header
    }

    private fun latestRevision(header: EdgeHeader): PersistedRevision? {
        val table = edgeTable(header.edgeType)
        return jdbc.sql(
            """
            SELECT id, revision, previous_revision_id, previous_revision,
                   verification_status, confidence, reason_code, content_digest
            FROM ${table.tableName}
            WHERE edge_id = :edgeId
            ORDER BY revision DESC
            LIMIT 1
            """.trimIndent(),
        )
            .param("edgeId", header.edgeId)
            .query(::mapRevision)
            .optional()
            .orElse(null)
    }

    private fun revisionById(header: EdgeHeader, revisionId: String): PersistedRevision? {
        val table = edgeTable(header.edgeType)
        return jdbc.sql(
            """
            SELECT id, revision, previous_revision_id, previous_revision,
                   verification_status, confidence, reason_code, content_digest
            FROM ${table.tableName}
            WHERE id = :revisionId AND edge_id = :edgeId
            """.trimIndent(),
        )
            .param("revisionId", revisionId)
            .param("edgeId", header.edgeId)
            .query(::mapRevision)
            .optional()
            .orElse(null)
    }

    private fun hasAcceptedValidRevision(header: EdgeHeader): Boolean {
        val table = edgeTable(header.edgeType)
        return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM ${table.tableName} " +
                "WHERE edge_id = :edgeId AND verification_status = 'VALID')",
        )
            .param("edgeId", header.edgeId)
            .query(Boolean::class.java)
            .single()
    }

    private fun insertRevision(
        header: EdgeHeader,
        candidate: EdgeCandidate,
        validation: ProvenanceValidation,
        revisionId: String,
        revision: Int,
        previous: PersistedRevision?,
        digest: String,
        now: Instant,
    ) {
        val table = edgeTable(header.edgeType)
        val inserted = jdbc.sql(
            """
            INSERT INTO ${table.tableName}(
              id, project_id, edge_id, revision, ${table.fromColumn}, ${table.toColumn},
              source_type, source_reference, proof_reference, proof_digest,
              reason_code, confidence, verification_status, verified_at,
              validator_version, previous_revision_id, previous_revision,
              content_digest, created_at
            ) VALUES (
              :id, :projectId, :edgeId, :revision, :fromId, :toId,
              :sourceType, :sourceReference, :proofReference, :proofDigest,
              :reasonCode, :confidence, :verificationStatus, :verifiedAt,
              :validatorVersion, :previousRevisionId, :previousRevision,
              :contentDigest, :createdAt
            )
            """.trimIndent(),
        )
            .param("id", revisionId)
            .param("projectId", candidate.projectId)
            .param("edgeId", header.edgeId)
            .param("revision", revision)
            .param("fromId", candidate.fromEntityId)
            .param("toId", candidate.toEntityId)
            .param("sourceType", candidate.sourceType)
            .param("sourceReference", candidate.sourceReference)
            .param("proofReference", candidate.proofReference)
            .param("proofDigest", candidate.proofDigest)
            .param("reasonCode", validation.reasonCode)
            .param("confidence", validation.confidence.name)
            .param("verificationStatus", validation.verificationStatus.name)
            .param("verifiedAt", now.toJdbcTimestamp())
            .param("validatorVersion", validation.validatorVersion)
            .param("previousRevisionId", previous?.revisionId)
            .param("previousRevision", previous?.revision)
            .param("contentDigest", digest)
            .param("createdAt", now.toJdbcTimestamp())
            .update()
        if (inserted != 1) integrityFailure("Edge revision insert did not affect exactly one row")
    }

    private fun effectiveValidation(
        hasAcceptedValidRevision: Boolean,
        incoming: ProvenanceValidation,
    ): ProvenanceValidation {
        val contradictsAccepted = incoming.verificationStatus == VerificationStatus.INVALID &&
            hasAcceptedValidRevision
        return if (contradictsAccepted) {
            incoming.copy(
                verificationStatus = VerificationStatus.CONFLICT,
                confidence = Confidence.LOW,
                reasonCode = PROOF_CONTRADICTS_ACCEPTED,
            )
        } else {
            incoming
        }
    }

    private fun factDigest(
        candidate: EdgeCandidate,
        validation: ProvenanceValidation,
    ): String {
        val document = objectMapper.createObjectNode()
            .put("projectId", candidate.projectId)
            .put("edgeType", candidate.edgeType.name)
            .put("fromEntityId", candidate.fromEntityId)
            .put("toEntityId", candidate.toEntityId)
            .put("sourceType", candidate.sourceType)
            .put("sourceReference", candidate.sourceReference)
            .put("proofReference", candidate.proofReference)
            .put("proofDigest", candidate.proofDigest)
            .put("verificationStatus", validation.verificationStatus.name)
            .put("confidence", validation.confidence.name)
            .put("validatorVersion", validation.validatorVersion)
            .put("reasonCode", validation.reasonCode)
        val canonical = JsonCanonicalizer(objectMapper.writeValueAsBytes(document)).encodedUTF8
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical))
    }

    private fun lockAndVerifyReceiptAuthority(receipt: BuildProvenanceReceipt) {
        val authority = jdbc.sql(
            """
            SELECT snapshot.id AS snapshot_id,
                   snapshot.project_id AS snapshot_project_id,
                   commit_authority.id AS commit_id,
                   commit_authority.project_id AS commit_project_id,
                   commit_authority.repository AS commit_repository,
                   commit_authority.commit_id AS commit_source_revision,
                   build_authority.id AS build_record_id,
                   build_authority.project_id AS build_project_id,
                   build_authority.provider AS build_provider,
                   build_authority.pipeline AS build_pipeline,
                   build_authority.build_id AS provider_build_id,
                   build_authority.build_attempt,
                   build_authority.repository AS build_repository,
                   build_authority.source_revision AS build_source_revision
            FROM release_issue_snapshot snapshot
            CROSS JOIN source_commit commit_authority
            CROSS JOIN build_record build_authority
            WHERE snapshot.id = :snapshotId
              AND commit_authority.id = :commitId
              AND build_authority.id = :buildRecordId
            FOR KEY SHARE OF snapshot, commit_authority, build_authority
            """.trimIndent(),
        )
            .param("snapshotId", receipt.result.releaseIssueSnapshotId)
            .param("commitId", receipt.result.sourceCommitId)
            .param("buildRecordId", receipt.result.buildRecordId)
            .query { rs, _ ->
                ReceiptAuthority(
                    snapshotId = rs.getString("snapshot_id"),
                    snapshotProjectId = rs.getString("snapshot_project_id"),
                    commitId = rs.getString("commit_id"),
                    commitProjectId = rs.getString("commit_project_id"),
                    commitRepository = rs.getString("commit_repository"),
                    commitSourceRevision = rs.getString("commit_source_revision"),
                    build = PersistedBuild(
                        id = rs.getString("build_record_id"),
                        projectId = rs.getString("build_project_id"),
                        provider = rs.getString("build_provider"),
                        pipeline = rs.getString("build_pipeline"),
                        buildId = rs.getString("provider_build_id"),
                        buildAttempt = rs.getInt("build_attempt"),
                        repository = rs.getString("build_repository"),
                        sourceRevision = rs.getString("build_source_revision"),
                    ),
                )
            }
            .optional()
            .orElseThrow { DataIntegrityViolationException("Build provenance receipt authority was not found") }
        if (!authority.matches(receipt)) integrityFailure("Build provenance receipt authority conflict")
    }

    private fun findReceipt(key: BuildAttemptKey, lock: Boolean): BuildProvenanceReceipt? = jdbc.sql(
        """
        $RECEIPT_SELECT
        WHERE receipt.project_id = :projectId
          AND receipt.provider = :provider
          AND receipt.pipeline = :pipeline
          AND receipt.provider_build_id = :buildId
          AND receipt.build_attempt = :buildAttempt
        ${if (lock) "FOR UPDATE OF receipt" else ""}
        """.trimIndent(),
    )
        .param("projectId", key.projectId)
        .param("provider", key.provider.value)
        .param("pipeline", key.pipeline)
        .param("buildId", key.buildId)
        .param("buildAttempt", key.buildAttempt)
        .query(::mapReceipt)
        .optional()
        .orElse(null)

    private fun mapReceipt(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): BuildProvenanceReceipt {
        val receiptId = rs.getString("id")
        val result = decodeResult(rs.getString("response_body"))
        val receipt = BuildProvenanceReceipt(
            receiptId = receiptId,
            key = BuildAttemptKey(
                projectId = rs.getString("project_id"),
                provider = ProvenanceProviderId(rs.getString("provider")),
                pipeline = rs.getString("pipeline"),
                buildId = rs.getString("provider_build_id"),
                buildAttempt = rs.getInt("build_attempt"),
            ),
            envelopeDigest = rs.getString("envelope_digest"),
            result = result,
            issueCount = rs.getInt("issue_count"),
            artifactCount = rs.getInt("artifact_count"),
            actorId = rs.getString("actor_id"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        )
        if (
            result.receiptId != receiptId ||
            result.releaseIssueSnapshotId != rs.getString("release_issue_snapshot_id") ||
            result.sourceCommitId != rs.getString("source_commit_id") ||
            result.buildRecordId != rs.getString("build_record_id") ||
            result.envelopeDigest != receipt.envelopeDigest ||
            result.validatorVersion != rs.getString("validator_version") ||
            result.verificationStatus.name != rs.getString("verification_status") ||
            result.confidence.name != rs.getString("confidence") ||
            result.edgeRevisions.size != rs.getInt("edge_count")
        ) {
            integrityFailure("Build provenance receipt response does not match authority columns")
        }
        requireReceiptConsistency(receipt)
        return receipt
    }

    private fun decodeResult(serialized: String): BuildProvenanceResult {
        val node = objectMapper.readTree(serialized)
        return BuildProvenanceResult(
            receiptId = node.requiredText("receiptId"),
            releaseIssueSnapshotId = node.requiredText("releaseIssueSnapshotId"),
            sourceCommitId = node.requiredText("sourceCommitId"),
            buildRecordId = node.requiredText("buildRecordId"),
            envelopeDigest = node.requiredText("envelopeDigest"),
            validatorVersion = node.requiredText("validatorVersion"),
            verificationStatus = VerificationStatus.valueOf(node.requiredText("verificationStatus")),
            confidence = Confidence.valueOf(node.requiredText("confidence")),
            edgeRevisions = node.required("edgeRevisions").map { edge ->
                EdgeRevisionRecord(
                    edgeId = edge.requiredText("edgeId"),
                    edgeType = TraceabilityEdgeType.valueOf(edge.requiredText("edgeType")),
                    revisionId = edge.requiredText("revisionId"),
                    revision = edge.required("revision").intValue(),
                    verificationStatus = VerificationStatus.valueOf(edge.requiredText("verificationStatus")),
                    confidence = Confidence.valueOf(edge.requiredText("confidence")),
                    factDigest = edge.requiredText("factDigest"),
                )
            },
        )
    }

    private fun requireReceiptConsistency(receipt: BuildProvenanceReceipt) {
        if (
            receipt.receiptId != receipt.result.receiptId ||
            receipt.envelopeDigest != receipt.result.envelopeDigest ||
            receipt.result.edgeRevisions.size != receipt.issueCount + receipt.artifactCount + 1
        ) {
            integrityFailure("Build provenance receipt is internally inconsistent")
        }
    }

    private fun mapBuild(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = PersistedBuild(
        id = rs.getString("id"),
        projectId = rs.getString("project_id"),
        provider = rs.getString("provider"),
        pipeline = rs.getString("pipeline"),
        buildId = rs.getString("build_id"),
        buildAttempt = rs.getInt("build_attempt"),
        repository = rs.getString("repository"),
        sourceRevision = rs.getString("source_revision"),
    )

    private fun mapRevision(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = PersistedRevision(
        revisionId = rs.getString("id"),
        revision = rs.getInt("revision"),
        previousRevisionId = rs.getString("previous_revision_id"),
        previousRevision = rs.getObject("previous_revision", Int::class.javaObjectType),
        verificationStatus = VerificationStatus.valueOf(rs.getString("verification_status")),
        confidence = Confidence.valueOf(rs.getString("confidence")),
        reasonCode = rs.getString("reason_code"),
        factDigest = rs.getString("content_digest"),
    )

    private fun edgeTable(edgeType: TraceabilityEdgeType): EdgeTable = when (edgeType) {
        TraceabilityEdgeType.ISSUE_COMMIT ->
            EdgeTable("issue_commit_edge_revision", "issue_id", "commit_id")
        TraceabilityEdgeType.COMMIT_BUILD ->
            EdgeTable("commit_build_edge_revision", "commit_id", "build_id")
        TraceabilityEdgeType.BUILD_ARTIFACT ->
            EdgeTable("build_artifact_edge_revision", "build_id", "artifact_id")
    }

    private companion object {
        const val SNAPSHOT_CANONICALIZATION_VERSION = "release-issue-snapshot-jcs/v1"
        const val PROOF_CONTRADICTS_ACCEPTED = "PROOF_CONTRADICTS_ACCEPTED"
        val UNICODE_CODE_POINT_ORDER = Comparator<String>(::compareCodePoints)
        val RECEIPT_SELECT = """
            SELECT receipt.id, receipt.project_id, receipt.provider, receipt.pipeline,
                   receipt.provider_build_id, receipt.build_attempt, receipt.envelope_digest,
                   receipt.release_issue_snapshot_id, receipt.source_commit_id,
                   receipt.build_record_id, receipt.validator_version,
                   receipt.verification_status, receipt.confidence, receipt.issue_count,
                   receipt.artifact_count, receipt.edge_count, receipt.response_body::text AS response_body,
                   receipt.actor_id, receipt.created_at
            FROM build_provenance_receipt receipt
        """.trimIndent()

        fun compareCodePoints(left: String, right: String): Int {
            val leftCodePoints = left.codePoints().iterator()
            val rightCodePoints = right.codePoints().iterator()
            while (leftCodePoints.hasNext() && rightCodePoints.hasNext()) {
                val compared = leftCodePoints.nextInt().compareTo(rightCodePoints.nextInt())
                if (compared != 0) return compared
            }
            return leftCodePoints.hasNext().compareTo(rightCodePoints.hasNext())
        }

        fun integrityFailure(message: String): Nothing = throw DataIntegrityViolationException(message)

        fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

        fun JsonNode.requiredText(field: String): String = required(field).textValue()
            ?: integrityFailure("Build provenance receipt response field is invalid")
    }
}

private data class ProjectIdentity(val id: String, val reference: String)

private data class SnapshotIdentity(
    val id: String,
    val projectId: String,
    val releaseId: String,
    val digest: String,
    val canonicalizationVersion: String?,
    val selectedCount: Int?,
    val actualCount: Int,
)

private data class PersistedCommit(
    val id: String,
    val projectId: String,
    val repository: String,
    val sourceRevision: String,
)

private data class PersistedBuild(
    val id: String,
    val projectId: String,
    val provider: String,
    val pipeline: String,
    val buildId: String,
    val buildAttempt: Int,
    val repository: String,
    val sourceRevision: String,
) {
    fun matches(key: BuildAttemptKey, expectedRepository: String, expectedRevision: String): Boolean =
        projectId == key.projectId &&
            provider == key.provider.value &&
            pipeline == key.pipeline &&
            buildId == key.buildId &&
            buildAttempt == key.buildAttempt &&
            repository == expectedRepository &&
            sourceRevision == expectedRevision
}

private data class ReceiptAuthority(
    val snapshotId: String,
    val snapshotProjectId: String,
    val commitId: String,
    val commitProjectId: String,
    val commitRepository: String,
    val commitSourceRevision: String,
    val build: PersistedBuild,
) {
    fun matches(receipt: BuildProvenanceReceipt): Boolean =
        snapshotId == receipt.result.releaseIssueSnapshotId &&
            snapshotProjectId == receipt.key.projectId &&
            commitId == receipt.result.sourceCommitId &&
            commitProjectId == receipt.key.projectId &&
            build.id == receipt.result.buildRecordId &&
            build.matches(receipt.key, commitRepository, commitSourceRevision)
}

private data class EdgeHeader(
    val edgeId: String,
    val projectId: String,
    val edgeType: TraceabilityEdgeType,
    val fromEntityId: String,
    val toEntityId: String,
) {
    fun matches(candidate: EdgeCandidate): Boolean =
        projectId == candidate.projectId &&
            edgeType == candidate.edgeType &&
            fromEntityId == candidate.fromEntityId &&
            toEntityId == candidate.toEntityId
}

private data class EdgeTable(
    val tableName: String,
    val fromColumn: String,
    val toColumn: String,
)

private data class PersistedRevision(
    val revisionId: String,
    val revision: Int,
    val previousRevisionId: String?,
    val previousRevision: Int?,
    val verificationStatus: VerificationStatus,
    val confidence: Confidence,
    val reasonCode: String?,
    val factDigest: String,
) {
    fun toRecord(header: EdgeHeader) = EdgeRevisionRecord(
        edgeId = header.edgeId,
        edgeType = header.edgeType,
        revisionId = revisionId,
        revision = revision,
        verificationStatus = verificationStatus,
        confidence = confidence,
        factDigest = factDigest,
    )
}
