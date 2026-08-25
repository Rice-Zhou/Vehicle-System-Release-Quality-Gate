package com.ricezhou.vsrqg.release.application

import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

class ReleaseNotFound(releaseId: String) :
    ResourceNotFound(
        "RELEASE_NOT_FOUND",
        "Release not found",
        "Release '$releaseId' was not found or is not visible",
    )

@Service
class GetRelease(
    private val repository: ReleaseRepository,
    private val authorizer: ProjectAuthorizer,
) {
    @Transactional(readOnly = true)
    fun get(principal: Principal, releaseId: String): CreateReleaseResult {
        val release = repository.find(releaseId) ?: throw ReleaseNotFound(releaseId)
        try {
            authorizer.require(principal, release.projectId, Permission.RELEASE_READ)
        } catch (_: AccessDeniedException) {
            throw ReleaseNotFound(releaseId)
        }
        return CreateReleaseResult(
            releaseId = release.id,
            status = release.status,
            manifestId = release.lockedManifestId,
            createdAt = release.createdAt,
            version = release.version,
        )
    }
}
