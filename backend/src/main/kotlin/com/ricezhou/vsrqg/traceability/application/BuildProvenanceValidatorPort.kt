package com.ricezhou.vsrqg.traceability.application

import com.ricezhou.vsrqg.traceability.domain.CanonicalBuildProvenance
import com.ricezhou.vsrqg.traceability.domain.ProvenanceValidation

fun interface BuildProvenanceValidatorPort {
    fun validate(provenance: CanonicalBuildProvenance): ProvenanceValidation
}
