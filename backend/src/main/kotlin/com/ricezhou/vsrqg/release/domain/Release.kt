package com.ricezhou.vsrqg.release.domain

import java.time.Instant

data class Release(
    val id: String,
    val projectId: String,
    val vehicle: String,
    val platform: String,
    val systemVersion: String,
    val declaredBuildId: String,
    val status: ReleaseStatus,
    val lockedManifestId: String?,
    val createdAt: Instant,
    val version: Long,
)

enum class ReleaseStatus {
    DRAFT,
    REGISTERED,
    READY_FOR_TEST,
    TESTING,
    QUALITY_EVALUATED,
    COMPLETED,
}

data class ReleaseStateHistory(
    val id: String,
    val releaseId: String,
    val previousStatus: ReleaseStatus?,
    val newStatus: ReleaseStatus,
    val actorId: String,
    val reason: String?,
    val occurredAt: Instant,
)
