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
