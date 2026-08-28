package com.ricezhou.vsrqg.shared.adapter.archive.operations

import java.nio.file.Path

enum class OperationStatus {
    PASS,
    FAIL,
}

class EvidenceArchiveInputFailure(
    val code: String,
) : IllegalArgumentException(code)

class EvidenceArchiveVerificationFailure(
    val code: String,
) : IllegalStateException(code)

data class VerifiedArchiveSource(
    val artifactId: String,
    val sourceRunId: String,
    val sourceCommit: String,
    val path: Path,
    val sizeBytes: Long,
    val sha256: String,
)

data class VerifiedEvidenceArchiveWorkPackage(
    val workPackageId: String,
    val descriptorSha256: String,
    val pilotManifestSha256: String,
    val artifacts: List<VerifiedArchiveSource>,
)
