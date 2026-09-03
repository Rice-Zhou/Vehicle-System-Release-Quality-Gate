package com.ricezhou.vsrqg.traceability.application

import com.ricezhou.vsrqg.traceability.domain.BuildProvenanceEnvelope
import com.ricezhou.vsrqg.traceability.domain.CanonicalBuildProvenance
import java.util.Collections

fun interface BuildProvenanceCanonicalizer {
    fun canonicalize(envelope: BuildProvenanceEnvelope): CanonicalBuildProvenance
}

class BuildProvenanceInvalid(violationCode: String) : RuntimeException(DIAGNOSTIC) {
    val violationCodes: List<String>

    init {
        require(violationCode in ALLOWED_VIOLATION_CODES) { "UNSAFE_BUILD_PROVENANCE_VIOLATION_CODE" }
        violationCodes = Collections.singletonList(violationCode)
    }

    private companion object {
        const val DIAGNOSTIC = "BUILD_PROVENANCE_INVALID"
        val ALLOWED_VIOLATION_CODES = setOf(
            "SCHEMA_VERSION_UNSUPPORTED",
            "PROJECT_REFERENCE_INVALID",
            "RELEASE_ISSUE_SNAPSHOT_ID_INVALID",
            "PROVIDER_INVALID",
            "REPOSITORY_INVALID",
            "SOURCE_REVISION_INVALID",
            "PIPELINE_INVALID",
            "BUILD_ID_INVALID",
            "BUILD_ATTEMPT_INVALID",
            "WORKFLOW_REFERENCE_INVALID",
            "PROOF_REFERENCE_INVALID",
            "PROOF_DIGEST_INVALID",
            "SOURCE_ISSUE_IDS_INVALID",
            "SOURCE_ISSUE_LIMIT_EXCEEDED",
            "SOURCE_ISSUE_ID_INVALID",
            "SOURCE_ISSUE_ID_DUPLICATE",
            "ARTIFACT_SHA256S_INVALID",
            "ARTIFACT_LIMIT_EXCEEDED",
            "ARTIFACT_SHA256_INVALID",
            "ARTIFACT_SHA256_DUPLICATE",
            "FACT_LIMIT_EXCEEDED",
            "CANONICALIZATION_FAILED",
        )
    }
}
