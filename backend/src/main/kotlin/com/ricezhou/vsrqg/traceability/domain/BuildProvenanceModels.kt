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

class CanonicalBuildProvenance(
    val normalized: BuildProvenanceEnvelope,
    canonicalBytes: ByteArray,
    val envelopeDigest: String,
    val recomputedProofDigest: String,
    val derivedFactCount: Int,
) {
    private val canonicalBytesSnapshot = canonicalBytes.copyOf()

    val canonicalBytes: ByteArray
        get() = canonicalBytesSnapshot.copyOf()

    fun copy(
        normalized: BuildProvenanceEnvelope = this.normalized,
        canonicalBytes: ByteArray = this.canonicalBytes,
        envelopeDigest: String = this.envelopeDigest,
        recomputedProofDigest: String = this.recomputedProofDigest,
        derivedFactCount: Int = this.derivedFactCount,
    ) = CanonicalBuildProvenance(
        normalized,
        canonicalBytes,
        envelopeDigest,
        recomputedProofDigest,
        derivedFactCount,
    )

    operator fun component1(): BuildProvenanceEnvelope = normalized

    operator fun component2(): ByteArray = canonicalBytes

    operator fun component3(): String = envelopeDigest

    operator fun component4(): String = recomputedProofDigest

    operator fun component5(): Int = derivedFactCount

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is CanonicalBuildProvenance &&
                    normalized == other.normalized &&
                    canonicalBytesSnapshot.contentEquals(other.canonicalBytesSnapshot) &&
                    envelopeDigest == other.envelopeDigest &&
                    recomputedProofDigest == other.recomputedProofDigest &&
                    derivedFactCount == other.derivedFactCount
            )

    override fun hashCode(): Int {
        var result = normalized.hashCode()
        result = 31 * result + canonicalBytesSnapshot.contentHashCode()
        result = 31 * result + envelopeDigest.hashCode()
        result = 31 * result + recomputedProofDigest.hashCode()
        result = 31 * result + derivedFactCount
        return result
    }

    override fun toString(): String =
        "CanonicalBuildProvenance(" +
            "envelopeDigest=$envelopeDigest, " +
            "recomputedProofDigest=$recomputedProofDigest, " +
            "derivedFactCount=$derivedFactCount, " +
            "canonicalByteCount=${canonicalBytesSnapshot.size})"
}
