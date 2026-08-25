package com.ricezhou.vsrqg.manifest.application

import com.ricezhou.vsrqg.manifest.domain.ManifestDocument

data class ManifestViolation(
    val code: String,
    val path: String,
    val message: String,
)

fun interface ManifestValidator {
    fun validate(document: ManifestDocument): List<ManifestViolation>
}
