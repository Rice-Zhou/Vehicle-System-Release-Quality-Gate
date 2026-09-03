package com.ricezhou.vsrqg.shared.application

import java.util.Collections

enum class SafeValidationDiagnostic(
    val code: String,
    private val allowedViolationCodes: Set<String>,
) {
    MAPPING_PROFILE_INVALID(
        code = "MAPPING_PROFILE_INVALID",
        allowedViolationCodes = setOf(
            "PROFILE_TOO_LARGE",
            "PROFILE_SERIALIZATION_INVALID",
            "PROFILE_DESERIALIZATION_INVALID",
            "PROFILE_CANONICALIZATION_INVALID",
            "PROFILE_STRUCTURE_INVALID",
            "SCHEMA_VERSION_UNSUPPORTED",
            "NORMALIZATION_VERSION_UNSUPPORTED",
            "STATUS_POLICY_UNSUPPORTED",
            "SEVERITY_POLICY_UNSUPPORTED",
            "STATUS_ALIAS_LIMIT_EXCEEDED",
            "SEVERITY_ALIAS_LIMIT_EXCEEDED",
            "STATUS_TARGET_INVALID",
            "SEVERITY_TARGET_INVALID",
            "STATUS_ALIAS_COLLISION",
            "SEVERITY_ALIAS_COLLISION",
            "TOKEN_INVALID",
        ),
    ),
    ISSUE_SNAPSHOT_INVALID(
        code = "ISSUE_SNAPSHOT_INVALID",
        allowedViolationCodes = setOf(
            "SYNC_RUN_STALE",
            "SYNC_OBSERVATION_INTEGRITY_FAILED",
            "SNAPSHOT_INTEGRITY_FAILED",
        ),
    ),
    BUILD_PROVENANCE_INVALID(
        code = "PROOF_VALIDATION_FAILED",
        allowedViolationCodes = setOf(
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
            "CANONICALIZATION_FAILED",
        ),
    ),
    BUILD_PROVENANCE_FACT_LIMIT_EXCEEDED(
        code = "FACT_LIMIT_EXCEEDED",
        allowedViolationCodes = setOf("FACT_LIMIT_EXCEEDED"),
    ),
    ;

    internal fun accepts(violationCode: String): Boolean = violationCode in allowedViolationCodes
}

open class SafeValidationFailure(
    val diagnostic: SafeValidationDiagnostic,
    violationCodes: List<String>,
) : RuntimeException(diagnostic.code) {
    val violationCodes: List<String>

    init {
        require(violationCodes.all(diagnostic::accepts)) { "UNSAFE_VALIDATION_VIOLATION_CODE" }
        this.violationCodes = Collections.unmodifiableList(ArrayList(violationCodes))
    }
}
