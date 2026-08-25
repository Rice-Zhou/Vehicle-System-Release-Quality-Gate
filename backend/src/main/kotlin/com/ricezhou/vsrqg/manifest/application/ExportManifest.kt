package com.ricezhou.vsrqg.manifest.application

import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import java.security.MessageDigest
import java.time.Instant
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ExportManifestResult(
    val releaseId: String,
    val manifestId: String,
    val manifestRevision: Int,
    val state: ManifestState,
    val rawManifest: JsonNode,
    val contentDigest: String,
    val validation: ValidationReport,
    val lockedAt: Instant,
)

@Service
class ExportManifest(
    private val repository: ManifestRepository,
    private val authorizer: ProjectAuthorizer,
) {
    @Transactional(readOnly = true)
    fun export(principal: Principal, releaseId: String, manifestId: String): ExportManifestResult {
        val release = repository.findRelease(releaseId) ?: throw ManifestNotFound(releaseId, manifestId)
        try {
            authorizer.require(principal, release.projectId, Permission.RELEASE_READ)
        } catch (_: AccessDeniedException) {
            throw ManifestNotFound(releaseId, manifestId)
        }
        val record = repository.findLockedExport(releaseId, manifestId)
            ?: throw ManifestNotFound(releaseId, manifestId)
        check(digest(record.canonicalBytes) == record.contentDigest) {
            "Locked manifest canonical bytes no longer match its authoritative digest"
        }
        return ExportManifestResult(
            releaseId = record.releaseId,
            manifestId = record.manifestId,
            manifestRevision = record.revision,
            state = ManifestState.LOCKED,
            rawManifest = record.rawManifest,
            contentDigest = record.contentDigest,
            validation = record.validation,
            lockedAt = record.lockedAt,
        )
    }

    private fun digest(bytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "sha256:" + hash.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
