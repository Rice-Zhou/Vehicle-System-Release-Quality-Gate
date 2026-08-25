package com.ricezhou.vsrqg.manifest

import com.ricezhou.vsrqg.manifest.application.manifestIdempotencyScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ManifestIdempotencyScopeTest {
    @Test
    fun `real manifest identifiers fit the persisted scope and operations stay isolated`() {
        val manifestId = "man_01a037e8cfc57dad83dd370add40ea23"
        val releaseId = "rel_01a037e8ce9d7b5597ad9d1aa44abba8"

        val validate = manifestIdempotencyScope("validate", releaseId, manifestId)
        val lock = manifestIdempotencyScope("lock", releaseId, manifestId)
        val otherRelease = manifestIdempotencyScope("validate", "rel_other", manifestId)

        assertThat(validate).hasSizeLessThanOrEqualTo(80)
        assertThat(lock).hasSizeLessThanOrEqualTo(80)
        assertThat(validate).isNotEqualTo(lock)
        assertThat(validate).isNotEqualTo(otherRelease)
    }
}
