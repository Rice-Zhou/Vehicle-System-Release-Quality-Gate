package com.ricezhou.vsrqg.manifest.adapter

import com.ricezhou.vsrqg.manifest.application.ArtifactPayloadVerifier
import com.ricezhou.vsrqg.manifest.application.ManifestViolation
import com.ricezhou.vsrqg.manifest.application.PayloadVerification
import com.ricezhou.vsrqg.manifest.application.ValidationStatus
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

class LocalArtifactPayloadVerifier(root: Path) : ArtifactPayloadVerifier {
    private val root = root.toAbsolutePath().normalize()

    override fun verify(sha256Values: List<String>): PayloadVerification {
        if (sha256Values.isEmpty()) return result(listOf(violation("ARTIFACT_PAYLOAD_EMPTY", "/artifacts")))
        if (sha256Values.size > MAX_FILES) return result(listOf(violation("ARTIFACT_PAYLOAD_COUNT_EXCEEDED", "/artifacts")))
        return result(sha256Values.mapIndexedNotNull { index, sha -> verifyFile(sha, "/artifacts/$index/checksum/value") })
    }

    private fun verifyFile(sha: String, position: String): ManifestViolation? {
        if (!SHA256.matches(sha)) return violation("ARTIFACT_CHECKSUM_INVALID", position)
        val file = root.resolve(sha).normalize()
        if (file.parent != root) return violation("ARTIFACT_PAYLOAD_PATH_INVALID", position)
        try {
            if (generateSequence(root) { it.parent }.any { Files.isSymbolicLink(it) }) {
                return violation("ARTIFACT_PAYLOAD_PATH_INVALID", position)
            }
            val rootAttributes = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!rootAttributes.isDirectory) return violation("ARTIFACT_PAYLOAD_PATH_INVALID", position)
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isRegularFile || attributes.isSymbolicLink) return violation("ARTIFACT_PAYLOAD_NOT_REGULAR", position)
            if (attributes.size() > MAX_BYTES) return violation("ARTIFACT_PAYLOAD_SIZE_EXCEEDED", position)
            val digest = MessageDigest.getInstance("SHA-256")
            // Check the streamed length too: the file may grow after reading its attributes.
            Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - total))
                    if (count == -1) break
                    total += count
                    if (total > MAX_BYTES) return violation("ARTIFACT_PAYLOAD_SIZE_EXCEEDED", position)
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            return if (actual == sha) null else violation("ARTIFACT_CHECKSUM_MISMATCH", position)
        } catch (_: IOException) {
            return violation("ARTIFACT_PAYLOAD_UNAVAILABLE", position)
        } catch (_: SecurityException) {
            return violation("ARTIFACT_PAYLOAD_UNAVAILABLE", position)
        }
    }

    private fun violation(code: String, position: String) = ManifestViolation(code, position, when (code) {
        "ARTIFACT_PAYLOAD_EMPTY" -> "At least one artifact payload is required"
        "ARTIFACT_PAYLOAD_COUNT_EXCEEDED" -> "Artifact payload count exceeds the local verification limit"
        "ARTIFACT_CHECKSUM_INVALID" -> "Artifact checksum must be a lowercase SHA-256 digest"
        "ARTIFACT_PAYLOAD_PATH_INVALID" -> "Artifact payload must remain within a regular local directory"
        "ARTIFACT_PAYLOAD_NOT_REGULAR" -> "Artifact payload must be a regular file without symbolic links"
        "ARTIFACT_PAYLOAD_SIZE_EXCEEDED" -> "Artifact payload exceeds the local verification byte limit"
        "ARTIFACT_CHECKSUM_MISMATCH" -> "Artifact payload does not match its declared checksum"
        "ARTIFACT_PAYLOAD_UNAVAILABLE" -> "Artifact payload is missing or unavailable for reading"
        else -> error("Unknown artifact payload violation code")
    })

    private fun result(violations: List<ManifestViolation>) = PayloadVerification(
        when {
            violations.any { it.code != "ARTIFACT_PAYLOAD_UNAVAILABLE" } -> ValidationStatus.FAILED
            violations.isNotEmpty() -> ValidationStatus.INCOMPLETE
            else -> ValidationStatus.VALID
        },
        violations,
        "m1-local-payload/1",
    )

    private companion object {
        const val MAX_FILES = 16
        const val MAX_BYTES = 1024 * 1024
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
