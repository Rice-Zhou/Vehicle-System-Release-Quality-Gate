package com.ricezhou.vsrqg.traceability.domain

@JvmInline
value class ProvenanceProviderId(val value: String)

enum class TraceabilityEdgeType { ISSUE_COMMIT, COMMIT_BUILD, BUILD_ARTIFACT }

enum class VerificationStatus { VALID, INVALID, CONFLICT, ERROR }

enum class Confidence { HIGH, MEDIUM, LOW, UNKNOWN }

data class BuildProvenanceEnvelope(
    val schemaVersion: Int,
    val projectReference: String,
    val releaseIssueSnapshotId: String,
    val provider: ProvenanceProviderId,
    val repository: String,
    val sourceRevision: String,
    val pipeline: String,
    val buildId: String,
    val buildAttempt: Int,
    val workflowReference: String,
    val proofReference: String,
    val proofDigest: String,
    val sourceIssueIds: List<String>,
    val artifactSha256s: List<String>,
)

data class ProvenanceValidation(
    val verificationStatus: VerificationStatus,
    val confidence: Confidence,
    val validatorVersion: String,
    val reasonCode: String,
)

data class CanonicalBuildProvenance(
    val normalized: BuildProvenanceEnvelope,
    val canonicalBytes: ByteArray,
    val envelopeDigest: String,
    val recomputedProofDigest: String,
    val derivedFactCount: Int,
)
