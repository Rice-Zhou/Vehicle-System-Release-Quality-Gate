package com.ricezhou.vsrqg.manifest.adapter

import com.ricezhou.vsrqg.manifest.domain.CanonicalManifest
import com.ricezhou.vsrqg.manifest.domain.ManifestDocument
import java.security.MessageDigest
import org.erdtman.jcs.JsonCanonicalizer
import org.springframework.stereotype.Component

@Component
class JcsCanonicalizer {
    fun canonicalize(document: ManifestDocument): CanonicalManifest {
        val bytes = JsonCanonicalizer(document.source).encodedUTF8
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return CanonicalManifest(
            bytes = bytes,
            contentDigest = "sha256:$digest",
        )
    }
}
