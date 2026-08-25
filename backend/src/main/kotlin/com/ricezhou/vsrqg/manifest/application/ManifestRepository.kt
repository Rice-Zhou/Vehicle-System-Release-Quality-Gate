package com.ricezhou.vsrqg.manifest.application

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

data class ManifestRelease(
    val id: String,
    val projectId: String,
    val projectReference: String,
    val vehicle: String,
    val platform: String,
    val systemVersion: String,
    val buildId: String,
    val status: String,
    val lockedManifestId: String? = null,
    val version: Long = 0,
)

data class ManifestLockCandidate(
    val id: String,
    val releaseId: String,
    val revision: Int,
    val contentDigest: String,
    val canonicalBytes: ByteArray,
    val schemaVersion: String,
    val state: ManifestState,
    val version: Long,
    val persistedValidationId: String,
    val persistedValidationStatus: String,
    val persistedValidationDigest: String,
    val persistedValidationSchemaVersion: String,
    val persistedValidatorVersion: String,
    val validation: ValidationReport,
)

data class LockedManifestRecord(
    val releaseId: String,
    val manifestId: String,
    val revision: Int,
    val rawManifest: JsonNode,
    val canonicalBytes: ByteArray,
    val contentDigest: String,
    val validation: ValidationReport,
    val lockedAt: Instant,
)

data class ManifestRevisionRecord(
    val id: String,
    val releaseId: String,
    val revision: Int,
    val contentDigest: String,
    val rawManifest: String,
    val canonicalBytes: ByteArray,
    val schemaVersion: String,
    val state: ManifestState,
    val createdAt: Instant,
)

data class ArtifactRecord(
    val id: String,
    val identityDigest: String,
    val type: String,
    val locator: JsonNode,
    val checksumAlgorithm: String,
    val checksumValue: String,
    val createdAt: Instant,
)

interface ManifestRepository {
    fun lockRelease(releaseId: String): ManifestRelease?

    fun findRelease(releaseId: String): ManifestRelease?

    fun findByDigest(releaseId: String, contentDigest: String): RegisterManifestResult?

    fun findById(releaseId: String, manifestId: String): RegisterManifestResult?

    fun nextRevision(releaseId: String): Int

    fun insertRevision(revision: ManifestRevisionRecord)

    fun findOrInsertArtifact(artifact: ArtifactRecord): String

    fun linkArtifact(manifestId: String, artifactId: String, ordinal: Int, required: Boolean, createdAt: Instant)

    fun insertValidation(report: ValidationReport)

    fun finalizeRevision(manifestId: String, state: ManifestState, updatedAt: Instant)

    fun markReleaseRegistered(releaseId: String, updatedAt: Instant): Boolean

    fun appendReleaseHistory(
        id: String,
        releaseId: String,
        actorId: String,
        occurredAt: Instant,
    )

    fun findLockCandidate(releaseId: String, manifestId: String): ManifestLockCandidate?

    fun artifactIntegrityMatches(manifestId: String): Boolean

    fun markManifestLocked(
        manifestId: String,
        validationId: String,
        expectedVersion: Long,
        lockedAt: Instant,
    ): Boolean

    fun markReleaseReady(releaseId: String, manifestId: String, updatedAt: Instant): Boolean

    fun appendManifestLockHistory(
        id: String,
        releaseId: String,
        actorId: String,
        reason: String,
        occurredAt: Instant,
    )

    fun findLockedExport(releaseId: String, manifestId: String): LockedManifestRecord?
}
