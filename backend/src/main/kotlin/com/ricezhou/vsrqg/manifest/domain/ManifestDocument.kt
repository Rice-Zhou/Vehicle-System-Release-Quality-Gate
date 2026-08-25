package com.ricezhou.vsrqg.manifest.domain

data class ManifestDocument(
    val source: String,
)

class CanonicalManifest(
    bytes: ByteArray,
    val contentDigest: String,
) {
    private val canonicalBytes = bytes.copyOf()

    val bytes: ByteArray
        get() = canonicalBytes.copyOf()
}
