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
}
