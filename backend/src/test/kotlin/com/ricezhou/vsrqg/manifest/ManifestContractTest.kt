package com.ricezhou.vsrqg.manifest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.manifest.adapter.JcsCanonicalizer
import com.ricezhou.vsrqg.manifest.adapter.NetworkntManifestValidator
import com.ricezhou.vsrqg.manifest.domain.ManifestDocument
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ManifestContractTest {
    private val objectMapper = ObjectMapper()
    private val validator = NetworkntManifestValidator(objectMapper)
    private val canonicalizer = JcsCanonicalizer()
    private val validSource = Files.readString(repositoryRoot.resolve(VALID_FIXTURE))

    @Test
    fun `application schema is byte identical to the frozen contract`() {
        val frozen = Files.readAllBytes(repositoryRoot.resolve(SCHEMA_PATH))
        val bundled = requireNotNull(javaClass.getResourceAsStream(BUNDLED_SCHEMA)).use { it.readAllBytes() }

        assertThat(bundled).isEqualTo(frozen)
    }

    @Test
    fun `valid fixture passes and missing required flag is rejected`() {
        val valid = validator.validate(ManifestDocument(validSource))
        val invalidSource = Files.readString(repositoryRoot.resolve(INVALID_FIXTURE))
        val invalid = validator.validate(ManifestDocument(invalidSource))

        assertThat(valid).isEmpty()
        assertThat(invalid).isNotEmpty
        assertThat(invalid).extracting<String> { it.code }.contains("MANIFEST_SCHEMA_VIOLATION")
    }

    @Test
    fun `duplicate keys and non NFC strings are rejected`() {
        val duplicate = validSource.replace(
            "\"project\": \"vehicle-x\"",
            "\"project\": \"duplicate\", \"project\": \"vehicle-x\"",
        )
        val nonNfc = validSource.replace("model-a", "mode\u0301l-a")

        assertThat(validator.validate(ManifestDocument(duplicate)))
            .extracting<String> { it.code }
            .contains("MANIFEST_INVALID_JSON")
        assertThat(validator.validate(ManifestDocument(nonNfc)))
            .extracting<String> { it.code }
            .contains("MANIFEST_NOT_NFC")
    }

    @Test
    fun `floating point exponent and unsafe integers are rejected`() {
        val floatingPoint = withTopLevelProperty(validSource, "sequence", objectMapper.readTree("1.5"))
        val exponent = withTopLevelProperty(validSource, "sequence", objectMapper.readTree("1e2"))
        val unsafeInteger = withTopLevelProperty(validSource, "sequence", objectMapper.readTree("9007199254740992"))

        assertThat(validator.validate(ManifestDocument(floatingPoint)))
            .extracting<String> { it.code }
            .contains("MANIFEST_NUMBER_NOT_INTEGER")
        assertThat(validator.validate(ManifestDocument(exponent)))
            .extracting<String> { it.code }
            .contains("MANIFEST_NUMBER_NOT_INTEGER")
        assertThat(validator.validate(ManifestDocument(unsafeInteger)))
            .extracting<String> { it.code }
            .contains("MANIFEST_INTEGER_OUT_OF_RANGE")
    }

    @Test
    fun `safe integer boundaries are accepted by semantic validation`() {
        val upperBoundary = withTopLevelProperty(validSource, "sequence", objectMapper.readTree("9007199254740991"))
        val lowerBoundary = withTopLevelProperty(validSource, "sequence", objectMapper.readTree("-9007199254740991"))
        val belowBoundary = withTopLevelProperty(validSource, "sequence", objectMapper.readTree("-9007199254740992"))

        assertThat(validator.validate(ManifestDocument(upperBoundary)).map { it.code })
            .doesNotContain("MANIFEST_INTEGER_OUT_OF_RANGE")
        assertThat(validator.validate(ManifestDocument(lowerBoundary)).map { it.code })
            .doesNotContain("MANIFEST_INTEGER_OUT_OF_RANGE")
        assertThat(validator.validate(ManifestDocument(belowBoundary)).map { it.code })
            .contains("MANIFEST_INTEGER_OUT_OF_RANGE")
    }

    @Test
    fun `escaped and literal NFC Unicode have the same canonical digest`() {
        val literal = validSource.replace(
            "\"project\": \"vehicle-x\"",
            "\"project\": \"v\u00e9hicle-x\"",
        )
        val escaped = validSource.replace(
            "\"project\": \"vehicle-x\"",
            "\"project\": \"v\\u00e9hicle-x\"",
        )

        assertThat(validator.validate(ManifestDocument(literal))).isEmpty()
        assertThat(validator.validate(ManifestDocument(escaped))).isEmpty()
        assertThat(canonicalizer.canonicalize(ManifestDocument(literal)).contentDigest)
            .isEqualTo(canonicalizer.canonicalize(ManifestDocument(escaped)).contentDigest)
    }

    @Test
    fun `property order is ignored but artifact array order changes digest`() {
        val root = objectMapper.readTree(validSource) as ObjectNode
        val reversedProperties = objectMapper.createObjectNode().also { reordered ->
            root.properties().asSequence().toList().asReversed().forEach { (name, value) ->
                reordered.set<ObjectNode>(name, value)
            }
        }
        val reversedArtifacts = root.deepCopy().also { copy ->
            val artifacts = copy.withArray("artifacts")
            val first = artifacts.remove(0)
            artifacts.add(first)
        }

        val canonical = canonicalizer.canonicalize(ManifestDocument(validSource))
        val propertyReordered = canonicalizer.canonicalize(
            ManifestDocument(objectMapper.writeValueAsString(reversedProperties)),
        )
        val artifactReordered = canonicalizer.canonicalize(
            ManifestDocument(objectMapper.writeValueAsString(reversedArtifacts)),
        )

        assertThat(propertyReordered.bytes).isEqualTo(canonical.bytes)
        assertThat(propertyReordered.contentDigest).isEqualTo(canonical.contentDigest)
        assertThat(artifactReordered.contentDigest).isNotEqualTo(canonical.contentDigest)
        assertThat(canonical.contentDigest).matches("^sha256:[0-9a-f]{64}$")
        assertThat(canonical.bytes.take(3).toByteArray())
            .isNotEqualTo(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        assertThat(String(canonical.bytes, StandardCharsets.UTF_8)).doesNotEndWith("\n")
    }

    @Test
    fun `JVM and Node produce the same digest`() {
        val jvmDigest = canonicalizer.canonicalize(ManifestDocument(validSource)).contentDigest
        val process = ProcessBuilder("node", NODE_SCRIPT.toString())
            .redirectErrorStream(true)
            .start()
        process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(validSource) }
        val output = process.inputStream.bufferedReader().use { it.readText() }

        assertThat(process.waitFor()).describedAs(output).isZero()
        assertThat(output).isEqualTo(jvmDigest)
    }

    @Test
    fun `canonical bytes cannot be mutated by a caller`() {
        val canonical = canonicalizer.canonicalize(ManifestDocument(validSource))
        val originalFirstByte = canonical.bytes.first()

        canonical.bytes[0] = (originalFirstByte + 1).toByte()

        assertThat(canonical.bytes.first()).isEqualTo(originalFirstByte)
    }

    private fun withTopLevelProperty(source: String, name: String, value: com.fasterxml.jackson.databind.JsonNode): String {
        val root = objectMapper.readTree(source) as ObjectNode
        root.set<ObjectNode>(name, value)
        return objectMapper.writeValueAsString(root)
    }

    private companion object {
        val repositoryRoot: Path = Path.of("..").toAbsolutePath().normalize()
        const val SCHEMA_PATH = "schemas/v0.2/release-manifest.schema.json"
        const val VALID_FIXTURE = "contracts/examples/v0.2/manifest/valid-apk.json"
        const val INVALID_FIXTURE = "contracts/examples/v0.2/manifest/invalid-missing-required.json"
        const val BUNDLED_SCHEMA = "/contracts/release-manifest-v0.2.schema.json"
        val NODE_SCRIPT: Path = repositoryRoot.resolve("scripts/m1/verify-jcs.mjs")
    }
}
