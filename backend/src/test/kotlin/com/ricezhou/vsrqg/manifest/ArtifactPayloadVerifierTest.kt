package com.ricezhou.vsrqg.manifest

import com.ricezhou.vsrqg.manifest.adapter.ArtifactPayloadVerificationConfiguration
import com.ricezhou.vsrqg.manifest.adapter.LocalArtifactPayloadVerifier
import com.ricezhou.vsrqg.manifest.application.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@Timeout(60)
class ArtifactPayloadVerifierTest {
    @TempDir lateinit var root: Path
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun store(bytes: ByteArray): String = sha(bytes).also { Files.write(root.resolve(it), bytes) }
    private fun verify(vararg values: String) = LocalArtifactPayloadVerifier(root).verify(values.toList())

    @Test fun `actual bytes must match and corruption is rejected`() {
        val digest = store("demo".toByteArray())
        assertThat(verify(digest)).isEqualTo(PayloadVerification(ValidationStatus.VALID, emptyList(), "m1-local-payload/1"))
        Files.writeString(root.resolve(digest), "changed")
        val result = verify(digest)
        assertThat(result.status).isEqualTo(ValidationStatus.FAILED)
        assertThat(result.violations.map { it.code }).containsExactly("ARTIFACT_CHECKSUM_MISMATCH")
        assertThat(result.violations.map { it.path }).containsExactly("/artifacts/0/checksum/value")
    }
    @Test fun `missing payload is incomplete and failed dominates with exact input positions`() {
        val good = store("demo".toByteArray())
        val missing = "a".repeat(64)
        assertThat(verify(missing).status).isEqualTo(ValidationStatus.INCOMPLETE)
        val result = verify(good, missing, "../escape")
        assertThat(result.status).isEqualTo(ValidationStatus.FAILED)
        assertThat(result.violations.map { it.path }).containsExactly("/artifacts/1/checksum/value", "/artifacts/2/checksum/value")
        assertThat(result.violations.map { it.code }).containsExactly("ARTIFACT_PAYLOAD_UNAVAILABLE", "ARTIFACT_CHECKSUM_INVALID")
        assertThat(result.toString()).doesNotContain(root.toString(), "escape")
    }
    @Test fun `empty list and illegal digest values are rejected`() {
        assertThat(verify().status).isEqualTo(ValidationStatus.FAILED)
        listOf("", "A".repeat(64), "a".repeat(63), "a".repeat(65), "/tmp/file", "https://example.test/file", "..\\escape").forEach {
            assertThat(verify(it).violations.map { violation -> violation.code }).containsExactly("ARTIFACT_CHECKSUM_INVALID")
        }
    }
    @Test fun `file count and byte count bounds are enforced inclusively`() {
        val digest = store(ByteArray(1024 * 1024) { 7 })
        assertThat(LocalArtifactPayloadVerifier(root).verify(List(16) { digest }).status).isEqualTo(ValidationStatus.VALID)
        assertThat(LocalArtifactPayloadVerifier(root).verify(List(17) { digest }).violations.map { it.code })
            .containsExactly("ARTIFACT_PAYLOAD_COUNT_EXCEEDED")
        val oversized = store(ByteArray(1024 * 1024 + 1))
        assertThat(verify(oversized).violations.map { it.code }).containsExactly("ARTIFACT_PAYLOAD_SIZE_EXCEEDED")
    }
    @Test fun `directory cannot be verified as a payload`() {
        val digest = "b".repeat(64)
        Files.createDirectory(root.resolve(digest))
        assertThat(verify(digest).violations.map { it.code }).containsExactly("ARTIFACT_PAYLOAD_NOT_REGULAR")
    }
    @Test fun `symbolic links cannot redirect a payload`() {
        val digest = sha("demo".toByteArray())
        val target = Files.writeString(root.resolve("target"), "demo")
        createLink(root.resolve(digest), target)
        assertThat(verify(digest).violations.map { it.code }).containsExactly("ARTIFACT_PAYLOAD_NOT_REGULAR")
    }
    private fun createLink(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (failure: java.nio.file.FileSystemException) {
            if (!System.getProperty("os.name").startsWith("Windows")) throw failure
            org.junit.jupiter.api.Assumptions.abort<Unit>("Windows symbolic link creation unavailable: ${failure.reason}")
        }
    }
    @Test fun `symbolic root and symbolic ancestor are rejected`() {
        val actual = Files.createDirectory(root.resolve("actual"))
        val digest = sha("demo".toByteArray())
        Files.writeString(actual.resolve(digest), "demo")
        val alias = root.resolve("alias")
        createLink(alias, actual)
        assertThat(LocalArtifactPayloadVerifier(alias).verify(listOf(digest)).violations.map { it.code })
            .containsExactly("ARTIFACT_PAYLOAD_PATH_INVALID")
        val nested = Files.createDirectory(actual.resolve("nested"))
        Files.writeString(nested.resolve(digest), "demo")
        assertThat(LocalArtifactPayloadVerifier(alias.resolve("nested")).verify(listOf(digest)).status)
            .isEqualTo(ValidationStatus.FAILED)
    }
    @Test fun `unreadable payload is incomplete`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"), "POSIX file permissions unavailable")
        val digest = store("demo".toByteArray())
        val file = root.resolve(digest)
        val permissions = Files.getPosixFilePermissions(file)
        try {
            Files.setPosixFilePermissions(file, emptySet())
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(file), "Runtime can bypass file permissions")
            assertThat(verify(digest).violations.map { it.code }).containsExactly("ARTIFACT_PAYLOAD_UNAVAILABLE")
            assertThat(verify(digest).status).isEqualTo(ValidationStatus.INCOMPLETE)
        } finally {
            Files.setPosixFilePermissions(file, permissions)
        }
    }
    @Test fun `missing and corrupt files retain both indexed violations and failed dominates`() {
        val digest = store("demo".toByteArray())
        Files.writeString(root.resolve(digest), "changed")
        val result = verify("a".repeat(64), digest)
        assertThat(result.status).isEqualTo(ValidationStatus.FAILED)
        assertThat(result.violations.map { it.code }).containsExactly("ARTIFACT_PAYLOAD_UNAVAILABLE", "ARTIFACT_CHECKSUM_MISMATCH")
        assertThat(result.violations.map { it.path }).containsExactly("/artifacts/0/checksum/value", "/artifacts/1/checksum/value")
    }
    @Test fun `absent root yields incomplete without exposing filesystem details`() {
        val result = LocalArtifactPayloadVerifier(root.resolve("absent")).verify(listOf("a".repeat(64)))
        assertThat(result.status).isEqualTo(ValidationStatus.INCOMPLETE)
        assertThat(result.toString()).doesNotContain(root.toString())
    }
    @Test fun `default configuration preserves the existing incomplete report`() {
        ApplicationContextRunner().withUserConfiguration(ArtifactPayloadVerificationConfiguration::class.java).run { context ->
            assertThat(context).hasSingleBean(ArtifactPayloadVerifier::class.java)
            assertThat(context.getBean(ArtifactPayloadVerifier::class.java).verify(listOf("a".repeat(64))))
                .isEqualTo(PayloadVerification(ValidationStatus.INCOMPLETE, listOf(ManifestViolation(
                    "ARTIFACT_CHECKSUM_NOT_VERIFIED", "/artifacts",
                    "Declared checksums are stored but no artifact payload was available for verification",
                )), ValidateManifest.VALIDATOR_VERSION))
        }
    }
    @Test fun `spring wires the explicit verifier into manifest evaluation`() {
        val digest = store("demo".toByteArray())
        ApplicationContextRunner()
            .withBean(ArtifactPayloadVerifier::class.java, { LocalArtifactPayloadVerifier(root) })
            .withBean(ManifestRepository::class.java, { org.mockito.Mockito.mock(ManifestRepository::class.java) })
            .withBean(com.ricezhou.vsrqg.access.application.ProjectAuthorizer::class.java, { org.mockito.Mockito.mock(com.ricezhou.vsrqg.access.application.ProjectAuthorizer::class.java) })
            .withBean(com.ricezhou.vsrqg.shared.application.IdempotentExecutor::class.java, { org.mockito.Mockito.mock(com.ricezhou.vsrqg.shared.application.IdempotentExecutor::class.java) })
            .withBean(com.ricezhou.vsrqg.shared.application.GovernanceStore::class.java, { org.mockito.Mockito.mock(com.ricezhou.vsrqg.shared.application.GovernanceStore::class.java) })
            .withUserConfiguration(ArtifactPayloadVerificationConfiguration::class.java, ValidateManifest::class.java)
            .run { context ->
                val node = com.fasterxml.jackson.databind.ObjectMapper().readTree("""{"releaseId":"release","project":"project","vehicle":"vehicle","platform":"platform","systemVersion":"system","buildId":"build","artifacts":[{"artifactId":"one","checksum":{"value":"$digest"}}]}""")
                val report = context.getBean(ValidateManifest::class.java).evaluate(
                    ManifestRelease("release", "project-id", "project", "vehicle", "platform", "system", "build", "DRAFT"),
                    node, "validation", "manifest", "digest", "0.2", 123, java.time.Instant.EPOCH,
                )
                assertThat(report.status).isEqualTo(ValidationStatus.VALID)
                assertThat(report.validatorVersion).isEqualTo("m1-local-payload/1")
            }
    }
    @Test fun `explicit local verifier replaces default configuration`() {
        val local = LocalArtifactPayloadVerifier(root)
        ApplicationContextRunner().withBean(ArtifactPayloadVerifier::class.java, { local })
            .withUserConfiguration(ArtifactPayloadVerificationConfiguration::class.java).run { context ->
                assertThat(context).hasSingleBean(ArtifactPayloadVerifier::class.java)
                assertThat(context.getBean(ArtifactPayloadVerifier::class.java)).isSameAs(local)
            }
    }
}

@Timeout(60)
class ArtifactPayloadEvaluationTest {
    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()
    private val release = ManifestRelease("release", "project-id", "project", "vehicle", "platform", "system", "build", "DRAFT")
    private fun root() = mapper.readTree("""{"releaseId":"release","project":"project","vehicle":"vehicle","platform":"platform","systemVersion":"system","buildId":"build","artifacts":[{"artifactId":"one","checksum":{"value":"${"a".repeat(64)}"}},{"artifactId":"two","checksum":{"value":"${"b".repeat(64)}"}}]}""")
    private fun evaluate(verifier: ArtifactPayloadVerifier, root: com.fasterxml.jackson.databind.JsonNode = root()): ValidationReport = ValidateManifest(
        org.mockito.Mockito.mock(ManifestRepository::class.java),
        org.mockito.Mockito.mock(com.ricezhou.vsrqg.access.application.ProjectAuthorizer::class.java),
        org.mockito.Mockito.mock(com.ricezhou.vsrqg.shared.application.IdempotentExecutor::class.java),
        org.mockito.Mockito.mock(com.ricezhou.vsrqg.shared.application.GovernanceStore::class.java),
        verifier,
    ).evaluate(release, root, "validation", "manifest", "digest", "0.2", 123, java.time.Instant.EPOCH)

    @Test fun `evaluation passes ordered checksums and preserves verifier result and report metadata`() {
        ValidationStatus.entries.forEach { status ->
            val violations = if (status == ValidationStatus.VALID) emptyList() else listOf(ManifestViolation("FIXTURE", "/artifacts/1/checksum/value", "fixture"))
            val verifier = object : ArtifactPayloadVerifier {
                override fun verify(sha256Values: List<String>): PayloadVerification {
                    assertThat(sha256Values).containsExactly("a".repeat(64), "b".repeat(64))
                    return PayloadVerification(status, violations, "fixture/1")
                }
            }
            assertThat(evaluate(verifier)).isEqualTo(ValidationReport("validation", "manifest", status,
                "digest", "0.2", violations, java.time.Instant.EPOCH, ValidateManifest.CANONICALIZATION_ID, "fixture/1", 123))
        }
    }
    @Test fun `semantic failure never invokes payload verification`() {
        val verifier = object : ArtifactPayloadVerifier {
            override fun verify(sha256Values: List<String>): PayloadVerification = error("must not verify invalid manifest")
        }
        val node = root() as com.fasterxml.jackson.databind.node.ObjectNode
        node.put("releaseId", "wrong")
        val report = evaluate(verifier, node)
        assertThat(report.status).isEqualTo(ValidationStatus.FAILED)
        assertThat(report.validatorVersion).isEqualTo(ValidateManifest.VALIDATOR_VERSION)
        assertThat(report.violations.map { it.code }).containsExactly("MANIFEST_RELEASE_ID_MISMATCH")
    }
}
