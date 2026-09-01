package com.ricezhou.vsrqg.issue

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.issue.adapter.JcsIssueMappingProfileCodec
import com.ricezhou.vsrqg.issue.application.MappingProfileInvalid
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import java.io.IOException
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import org.erdtman.jcs.JsonCanonicalizer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources

class IssueMappingProfileCodecTest {
    private val objectMapper = ObjectMapper()
    private val codec = JcsIssueMappingProfileCodec(objectMapper)

    @Test
    fun `compiling the same definition is deterministic`() {
        val definition = validDefinition()

        val versions = List(3) { codec.compile(definition).mappingVersion }

        assertThat(versions).containsOnly(versions.first())
        assertThat(versions.first()).startsWith("sha256:").hasSize(71)
    }

    @Test
    fun `object field order does not affect mapping version`() {
        val first = validDefinition()
        val reordered = objectMapper.readTree(
            """
            {
              "severityAliases":{"HIGH":["Major"]},
              "statusAliases":{"OPEN":["Open"]},
              "unknownSeverityPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
              "unknownStatusPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
              "normalizationVersion":"unicode-nfc-trim-root-lower/v1",
              "schemaVersion":"jira-mapping-profile/v1"
            }
            """.trimIndent(),
        )

        assertThat(codec.compile(reordered).mappingVersion).isEqualTo(codec.compile(first).mappingVersion)
    }

    @Test
    fun `aliases use NFC Unicode whitespace trim and Locale ROOT lowercase`() {
        val definition = validDefinition().apply {
            statusAliases().set<ArrayNode>("OPEN", arrayNode("\u2003A\u030Angstro\u0308M\u2002", "I"))
        }

        val compiled = codec.compile(definition)

        assertThat(compiled.statusByToken["\u00e5ngstr\u00f6m"]).isEqualTo(IssueStatus.OPEN)
        assertThat(compiled.statusByToken["i"]).isEqualTo(IssueStatus.OPEN)
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    fun `normalization remains Locale ROOT when JVM default locale is Turkish`() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val definition = validDefinition().apply {
                statusAliases().set<ArrayNode>("OPEN", arrayNode("I"))
            }

            assertThat(codec.compile(definition).statusByToken).containsEntry("i", IssueStatus.OPEN)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `normalized aliases assigned to conflicting targets reject the entire family`() {
        val secretAlias = "\u2003SeCrEt-A\u030A\u2002"
        val definition = validDefinition().apply {
            statusAliases().set<ArrayNode>("OPEN", arrayNode(secretAlias))
            statusAliases().set<ArrayNode>("CLOSED", arrayNode("secret-\u00c5"))
        }

        assertRedactedFailure(definition, "STATUS_ALIAS_COLLISION", secretAlias)
    }

    @Test
    fun `duplicate normalized aliases for the same target collapse safely`() {
        val definition = validDefinition().apply {
            statusAliases().set<ArrayNode>("OPEN", arrayNode(" Open ", "OPEN", "open"))
        }

        val compiled = codec.compile(definition)

        assertThat(compiled.statusByToken).containsOnlyKeys("open")
        assertThat(compiled.statusByToken["open"]).isEqualTo(IssueStatus.OPEN)
    }

    @Test
    fun `UNKNOWN is not an allowed status or severity target`() {
        assertRedactedFailure(
            validDefinition().apply { statusAliases().set<ArrayNode>("UNKNOWN", arrayNode("hidden-status")) },
            "STATUS_TARGET_INVALID",
            "hidden-status",
        )
        assertRedactedFailure(
            validDefinition().apply { severityAliases().set<ArrayNode>("UNKNOWN", arrayNode("hidden-severity")) },
            "SEVERITY_TARGET_INVALID",
            "hidden-severity",
        )
    }

    @Test
    fun `unknown fields and unsupported fixed values are rejected`() {
        assertViolation(validDefinition().apply { put("secret-extra", "hidden-fragment") }, "PROFILE_STRUCTURE_INVALID")
        assertViolation(validDefinition().apply { put("schemaVersion", "secret-schema") }, "SCHEMA_VERSION_UNSUPPORTED")
        assertViolation(
            validDefinition().apply { put("normalizationVersion", "secret-normalization") },
            "NORMALIZATION_VERSION_UNSUPPORTED",
        )
        assertViolation(
            validDefinition().apply { put("unknownStatusPolicy", "secret-status-policy") },
            "STATUS_POLICY_UNSUPPORTED",
        )
        assertViolation(
            validDefinition().apply { put("unknownSeverityPolicy", "secret-severity-policy") },
            "SEVERITY_POLICY_UNSUPPORTED",
        )
    }

    @Test
    fun `all six top level fields are required with strict JSON shapes`() {
        assertViolation(validDefinition().apply { remove("schemaVersion") }, "PROFILE_STRUCTURE_INVALID")
        assertViolation(validDefinition().apply { put("statusAliases", "not-an-object") }, "PROFILE_STRUCTURE_INVALID")
        assertViolation(validDefinition().apply { statusAliases().put("OPEN", "not-an-array") }, "PROFILE_STRUCTURE_INVALID")
        assertViolation(
            validDefinition().apply { statusAliases().set<ArrayNode>("OPEN", arrayNode("valid").add(7)) },
            "PROFILE_STRUCTURE_INVALID",
        )
        assertViolation(objectMapper.createArrayNode(), "PROFILE_STRUCTURE_INVALID")
    }

    @Test
    fun `empty blank control and 121 character aliases are rejected`() {
        listOf("", "\u2003\u2002", "line\nfeed", "x".repeat(121)).forEach { invalidToken ->
            val definition = validDefinition().apply {
                statusAliases().set<ArrayNode>("OPEN", arrayNode(invalidToken))
            }

            assertRedactedFailure(definition, "TOKEN_INVALID", invalidToken)
        }
    }

    @Test
    fun `120 character alias is accepted`() {
        val token = "X".repeat(120)

        val compiled = codec.compile(validDefinition().apply {
            statusAliases().set<ArrayNode>("OPEN", arrayNode(token))
        })

        assertThat(compiled.statusByToken[token.lowercase()]).isEqualTo(IssueStatus.OPEN)
    }

    @Test
    fun `each alias family accepts 256 raw aliases`() {
        val definition = validDefinition().apply {
            statusAliases().set<ArrayNode>("OPEN", aliases(256, "status"))
            severityAliases().set<ArrayNode>("HIGH", aliases(256, "severity"))
        }

        val compiled = codec.compile(definition)

        assertThat(compiled.statusByToken).hasSize(256)
        assertThat(compiled.severityByToken).hasSize(256)
    }

    @Test
    fun `257 aliases in either family are rejected before duplicate collapse`() {
        assertViolation(
            validDefinition().apply { statusAliases().set<ArrayNode>("OPEN", aliases(257, "status")) },
            "STATUS_ALIAS_LIMIT_EXCEEDED",
        )
        assertViolation(
            validDefinition().apply { severityAliases().set<ArrayNode>("HIGH", aliases(257, "severity")) },
            "SEVERITY_ALIAS_LIMIT_EXCEEDED",
        )
        assertViolation(
            validDefinition().apply {
                statusAliases().set<ArrayNode>("OPEN", arrayNode(*Array(257) { "duplicate" }))
            },
            "STATUS_ALIAS_LIMIT_EXCEEDED",
        )
    }

    @Test
    fun `serialized definitions larger than 64 KiB are rejected`() {
        val oversized = validDefinition().apply { put("schemaVersion", "S".repeat(66_000)) }

        assertRedactedFailure(oversized, "PROFILE_TOO_LARGE", "S".repeat(100))
    }

    @Test
    fun `regex and wildcard looking aliases remain literal tokens`() {
        val definition = validDefinition().apply {
            statusAliases().set<ArrayNode>("OPEN", arrayNode(" .* ", "BUG-*", "[A-Z]+", "prefix?"))
        }

        val compiled = codec.compile(definition)

        assertThat(compiled.statusByToken).containsOnlyKeys(".*", "bug-*", "[a-z]+", "prefix?")
        assertThat(compiled.statusByToken["bug-123"]).isNull()
        assertThat(compiled.statusByToken["prefixx"]).isNull()
    }

    @Test
    fun `compiled authority is isolated from submitted and exposed definition mutations`() {
        val submitted = validDefinition()
        val compiled = codec.compile(submitted)
        val mappingVersion = compiled.mappingVersion
        val statusByToken = compiled.statusByToken.toMap()
        val severityByToken = compiled.severityByToken.toMap()

        submitted.put("schemaVersion", "mutated-submission")
        submitted.statusAliases().set<ArrayNode>("OPEN", arrayNode("mutated-alias"))

        assertThat(compiled.definition).isEqualTo(validDefinition())
        assertThat(compiled.mappingVersion).isEqualTo(mappingVersion)
        assertThat(compiled.statusByToken).isEqualTo(statusByToken)
        assertThat(compiled.severityByToken).isEqualTo(severityByToken)

        (compiled.definition as ObjectNode).put("schemaVersion", "mutated-return")

        assertThat(compiled.definition).isEqualTo(validDefinition())
        assertThat(compiled.mappingVersion).isEqualTo(mappingVersion)
        assertThat(compiled.statusByToken).isEqualTo(statusByToken)
        assertThat(compiled.severityByToken).isEqualTo(severityByToken)
    }

    @Test
    fun `compiled status and severity maps cannot be mutated through casts`() {
        val compiled = codec.compile(validDefinition())

        assertThatThrownBy {
            (compiled.statusByToken as MutableMap)["injected"] = IssueStatus.CLOSED
        }.isInstanceOf(UnsupportedOperationException::class.java)
        assertThatThrownBy {
            (compiled.severityByToken as MutableMap)["injected"] = IssueSeverity.CRITICAL
        }.isInstanceOf(UnsupportedOperationException::class.java)

        assertThat(compiled.statusByToken).containsOnlyKeys("open")
        assertThat(compiled.severityByToken).containsOnlyKeys("major")
    }

    @Test
    fun `non canonical numeric input is rejected before JCS without leaking its content`() {
        val secretFragment = "1E+10000"
        val definition = validDefinition().apply {
            put("unexpectedNumber", BigDecimal(secretFragment))
        }

        assertRedactedFailure(definition, "PROFILE_STRUCTURE_INVALID", secretFragment, "unexpectedNumber")
    }

    @Test
    fun `serialization failures use a fixed redacted violation`() {
        val secretFragment = "SERIALIZATION-SECRET-CONTENT"
        val failingCodec = JcsIssueMappingProfileCodec(FailingObjectMapper(secretFragment))

        assertRedactedFailure(
            validDefinition(),
            "PROFILE_SERIALIZATION_INVALID",
            secretFragment,
            codecUnderTest = failingCodec,
        )
    }

    @Test
    fun `canonicalization failures use a fixed redacted violation`() {
        val secretFragment = "CANONICALIZATION-SECRET-CONTENT"
        val failingCodec = JcsIssueMappingProfileCodec(NonCanonicalObjectMapper(secretFragment, validDefinition()))

        assertRedactedFailure(
            validDefinition(),
            "PROFILE_CANONICALIZATION_INVALID",
            secretFragment,
            "1e10000",
            codecUnderTest = failingCodec,
        )
    }

    @Test
    fun `serialized bytes are the single authority for validation compilation definition and digest`() {
        val serialized =
            """
            {
              "severityAliases":{"LOW":[" MINOR "]},
              "statusAliases":{"CLOSED":[" Done "]},
              "unknownSeverityPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
              "unknownStatusPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
              "normalizationVersion":"unicode-nfc-trim-root-lower/v1",
              "schemaVersion":"jira-mapping-profile/v1"
            }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        val callerTree = validDefinition().apply { put("schemaVersion", "caller-tree-must-not-be-authoritative") }
        val compiled = JcsIssueMappingProfileCodec(RawBytesObjectMapper(serialized)).compile(callerTree)

        assertThat(compiled.schemaVersion).isEqualTo("jira-mapping-profile/v1")
        assertThat(compiled.definition).isEqualTo(objectMapper.readTree(serialized))
        assertThat(compiled.statusByToken).containsExactlyEntriesOf(mapOf("done" to IssueStatus.CLOSED))
        assertThat(compiled.severityByToken).containsExactlyEntriesOf(mapOf("minor" to IssueSeverity.LOW))
        assertThat(compiled.mappingVersion).isEqualTo(expectedMappingVersion(serialized))
    }

    @Test
    fun `empty null and malformed serialized profiles fail with a fixed redacted parse violation`() {
        listOf(
            ByteArray(0),
            "null".toByteArray(StandardCharsets.UTF_8),
            "{\"secret\":\"PARSE-SECRET-CONTENT\"".toByteArray(StandardCharsets.UTF_8),
        ).forEach { serialized ->
            assertRedactedFailure(
                validDefinition(),
                "PROFILE_DESERIALIZATION_INVALID",
                "PARSE-SECRET-CONTENT",
                codecUnderTest = JcsIssueMappingProfileCodec(RawBytesObjectMapper(serialized)),
            )
        }
    }

    @Test
    fun `plain parse IO failures use a fixed redacted violation without retaining the cause`() {
        val secretFragment = "PLAIN-IO-PARSE-SECRET-CONTENT"
        val serialized = objectMapper.writeValueAsBytes(validDefinition())

        assertRedactedFailure(
            validDefinition(),
            "PROFILE_DESERIALIZATION_INVALID",
            secretFragment,
            codecUnderTest = JcsIssueMappingProfileCodec(ReadFailingObjectMapper(serialized, secretFragment)),
        )
    }

    @Test
    fun `mapping profile violation codes are defensively copied and immutable`() {
        val callerCodes = mutableListOf("TOKEN_INVALID")
        val error = MappingProfileInvalid(callerCodes)

        callerCodes += "CALLER_MUTATION"

        assertThat(error.violationCodes).containsExactly("TOKEN_INVALID")
        assertThatThrownBy {
            (error.violationCodes as MutableList).add("EXPOSED_MUTATION")
        }.isInstanceOf(UnsupportedOperationException::class.java)
        assertThat(error.violationCodes).containsExactly("TOKEN_INVALID")
        assertThat(error.message).isEqualTo("MAPPING_PROFILE_INVALID")
    }

    @Test
    fun `every validation failure is redacted`() {
        val callerToken = "DO-NOT-LEAK-THIS-CALLER-TOKEN"
        val definition = validDefinition().apply {
            statusAliases().set<ArrayNode>("NOT_A_STATUS", arrayNode(callerToken))
        }

        assertRedactedFailure(definition, "STATUS_TARGET_INVALID", callerToken, "NOT_A_STATUS")
    }

    private fun validDefinition(): ObjectNode = objectMapper.readTree(
        """
        {
          "schemaVersion":"jira-mapping-profile/v1",
          "normalizationVersion":"unicode-nfc-trim-root-lower/v1",
          "unknownStatusPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
          "unknownSeverityPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
          "statusAliases":{"OPEN":["Open"]},
          "severityAliases":{"HIGH":["Major"]}
        }
        """.trimIndent(),
    ) as ObjectNode

    private fun ObjectNode.statusAliases(): ObjectNode = path("statusAliases") as ObjectNode

    private fun ObjectNode.severityAliases(): ObjectNode = path("severityAliases") as ObjectNode

    private fun arrayNode(vararg values: String): ArrayNode = objectMapper.createArrayNode().apply {
        values.forEach(::add)
    }

    private fun aliases(count: Int, prefix: String): ArrayNode = objectMapper.createArrayNode().apply {
        repeat(count) { add("$prefix-$it") }
    }

    private fun expectedMappingVersion(serialized: ByteArray): String {
        val canonical = JsonCanonicalizer(serialized).encodedUTF8
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(canonical)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "sha256:$hex"
    }

    private fun assertViolation(definition: JsonNode, expectedCode: String) {
        assertThatThrownBy { codec.compile(definition) }
            .isInstanceOfSatisfying(MappingProfileInvalid::class.java) { error ->
                assertThat(error.message).isEqualTo("MAPPING_PROFILE_INVALID")
                assertThat(error.violationCodes).containsExactly(expectedCode)
            }
    }

    private fun assertRedactedFailure(
        definition: JsonNode,
        expectedCode: String,
        vararg secrets: String,
        codecUnderTest: JcsIssueMappingProfileCodec = codec,
    ) {
        assertThatThrownBy { codecUnderTest.compile(definition) }
            .isInstanceOfSatisfying(MappingProfileInvalid::class.java) { error ->
                assertThat(error.message).isEqualTo("MAPPING_PROFILE_INVALID")
                assertThat(error.violationCodes).containsExactly(expectedCode)
                val visibleFailure = generateSequence(error as Throwable) { it.cause }
                    .joinToString { it.toString() } + error.violationCodes.joinToString()
                secrets.filter(String::isNotEmpty).forEach { secret ->
                    assertThat(visibleFailure).doesNotContain(secret)
                }
                assertThat(visibleFailure).doesNotContain(definition.toString())
            }
    }

    private class FailingObjectMapper(private val secretFragment: String) : ObjectMapper() {
        override fun writeValueAsBytes(value: Any): ByteArray {
            throw JsonMappingException.fromUnexpectedIOE(IOException(secretFragment))
        }
    }

    private class NonCanonicalObjectMapper(
        private val secretFragment: String,
        authoritativeDefinition: JsonNode,
    ) : ObjectMapper() {
        private val authoritativeDefinition = authoritativeDefinition.deepCopy<JsonNode>()

        override fun writeValueAsBytes(value: Any): ByteArray =
            "{\"secret\":\"$secretFragment\",\"number\":1e10000}".toByteArray(StandardCharsets.UTF_8)

        override fun readTree(content: ByteArray): JsonNode = authoritativeDefinition.deepCopy()
    }

    private class RawBytesObjectMapper(private val serialized: ByteArray) : ObjectMapper() {
        override fun writeValueAsBytes(value: Any): ByteArray = serialized.copyOf()
    }

    private class ReadFailingObjectMapper(
        private val serialized: ByteArray,
        private val secretFragment: String,
    ) : ObjectMapper() {
        override fun writeValueAsBytes(value: Any): ByteArray = serialized.copyOf()

        override fun readTree(content: ByteArray): JsonNode {
            throw IOException(secretFragment)
        }
    }
}
