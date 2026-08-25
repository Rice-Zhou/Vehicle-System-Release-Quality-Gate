package com.ricezhou.vsrqg.manifest

import com.ricezhou.vsrqg.manifest.application.manifestIdempotencyScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ManifestIdempotencyScopeTest {
    @Test
    fun `real manifest identifiers fit the persisted scope and operations stay isolated`() {
        val manifestId = "man_01a037e8cfc57dad83dd370add40ea23"

        val validate = manifestIdempotencyScope("validate", manifestId)
        val lock = manifestIdempotencyScope("lock", manifestId)

        assertThat(validate).hasSizeLessThanOrEqualTo(80)
        assertThat(lock).hasSizeLessThanOrEqualTo(80)
        assertThat(validate).isNotEqualTo(lock)
    }
}
