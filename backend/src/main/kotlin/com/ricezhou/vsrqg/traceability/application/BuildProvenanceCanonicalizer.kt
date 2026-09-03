package com.ricezhou.vsrqg.traceability.application

import com.ricezhou.vsrqg.traceability.domain.BuildProvenanceEnvelope
import com.ricezhou.vsrqg.traceability.domain.CanonicalBuildProvenance
import com.ricezhou.vsrqg.shared.application.SafeValidationDiagnostic
import com.ricezhou.vsrqg.shared.application.SafeValidationFailure

fun interface BuildProvenanceCanonicalizer {
    fun canonicalize(envelope: BuildProvenanceEnvelope): CanonicalBuildProvenance
}

class BuildProvenanceInvalid(violationCode: String) : SafeValidationFailure(
    diagnostic = if (violationCode == "FACT_LIMIT_EXCEEDED") {
        SafeValidationDiagnostic.BUILD_PROVENANCE_FACT_LIMIT_EXCEEDED
    } else {
        SafeValidationDiagnostic.BUILD_PROVENANCE_INVALID
    },
    violationCodes = listOf(violationCode),
)
