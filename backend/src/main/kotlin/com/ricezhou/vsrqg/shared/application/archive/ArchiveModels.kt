package com.ricezhou.vsrqg.shared.application.archive

import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

enum class DeploymentMode {
    PILOT,
    COMPANY,
}

enum class ArchiveProvider {
    NONE,
    FILESYSTEM_STAGING,
    S3_COMPATIBLE,
}

enum class ArchiveCapabilityState {
    UNCONFIGURED,
    LOCAL_PILOT,
    EXTERNAL_UNVERIFIED,
    EXTERNAL_VERIFIED,
}

data class ArchivePolicy(
    val mode: DeploymentMode,
    val enabled: Boolean,
    val checksumVerificationEnabled: Boolean,
    val encryptionRequired: Boolean,
    val privateAccessRequired: Boolean,
    val retentionPolicyRequired: Boolean,
    val immutabilityRequired: Boolean,
    val provider: ArchiveProvider,
    val stagingRoot: Path?,
    val endpoint: URI?,
    val region: String?,
    val bucket: String?,
    val objectPrefix: String,
    val accessOwner: String?,
    val retentionPeriod: Duration?,
    val probeTimeout: Duration,
    val operationTimeout: Duration,
)

data class CapabilityCheck(
    val name: String,
    val passed: Boolean,
    val detail: String,
)

data class CapabilityProbeContext(
    val policyFingerprint: String,
    val checkedAt: Instant,
)

data class ArchiveCapabilityReport(
    val mode: DeploymentMode,
    val provider: ArchiveProvider,
    val state: ArchiveCapabilityState,
    val policyFingerprint: String,
    val checkedAt: Instant,
    val checks: List<CapabilityCheck>,
)

data class ArchiveCommand(
    val acceptanceId: String,
    val sourceArtifactId: String,
    val sourceRunId: String,
    val sourceCommit: String,
    val source: Path,
    val expectedSha256: String,
)

data class StoredObjectRef(
    val provider: ArchiveProvider,
    val locator: String,
    val bucket: String?,
    val key: String,
    val versionId: String?,
    val sha256: String,
    val sizeBytes: Long,
)

data class RuntimeIdentityRef(
    val provider: ArchiveProvider,
    val principalFingerprint: String,
)

enum class MutationCheckResult {
    DENIED_AS_EXPECTED,
    ALLOWED,
    INDETERMINATE,
}

data class DailyControlRecord(
    val policyFingerprint: String,
    val identity: RuntimeIdentityRef,
    val utcDate: LocalDate,
    val validUntil: Instant,
    val target: StoredObjectRef,
    val overwrite: MutationCheckResult,
    val delete: MutationCheckResult,
    val bypass: MutationCheckResult,
)

data class DailyControlSnapshot(
    val record: DailyControlRecord,
    val resultReference: StoredObjectRef,
)

data class ArchiveReceipt(
    val acceptanceId: String,
    val sourceArtifactId: String,
    val sourceRunId: String,
    val sourceCommit: String,
    val sourceSha256: String,
    val payload: StoredObjectRef,
    val accessOwner: String,
    val retentionPolicy: String,
    val immutabilityControl: String,
    val policyFingerprint: String,
    val capabilityCheckedAt: Instant,
    val archivedAt: Instant,
    val verifier: String,
    val longTerm: Boolean,
)

data class ArchiveReceiptReference(
    val locator: String,
    val versionId: String?,
    val sha256: String,
)

data class ArchiveResult(
    val receipt: ArchiveReceipt,
    val receiptReference: ArchiveReceiptReference,
)

class ArchiveUnavailable(message: String) : IllegalStateException(message)

class ArchiveIntegrityFailure(message: String) : IllegalStateException(message)
