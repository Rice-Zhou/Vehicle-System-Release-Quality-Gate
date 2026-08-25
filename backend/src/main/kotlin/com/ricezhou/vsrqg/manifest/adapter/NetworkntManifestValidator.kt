package com.ricezhou.vsrqg.manifest.adapter

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.ricezhou.vsrqg.manifest.application.ManifestValidator
import com.ricezhou.vsrqg.manifest.application.ManifestViolation
import com.ricezhou.vsrqg.manifest.domain.ManifestDocument
import java.math.BigInteger
import java.text.Normalizer
import org.springframework.stereotype.Component

@Component
class NetworkntManifestValidator(
    objectMapper: ObjectMapper,
) : ManifestValidator {
    private val strictMapper = objectMapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    private val schema = requireNotNull(javaClass.getResource(BUNDLED_SCHEMA))
        .readText()
        .let { source ->
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(source)
                .also { it.initializeValidators() }
        }

    override fun validate(document: ManifestDocument): List<ManifestViolation> {
        val root = try {
            strictMapper.readTree(document.source)
        } catch (exception: JsonProcessingException) {
            return listOf(
                ManifestViolation(
                    code = "MANIFEST_INVALID_JSON",
                    path = "",
                    message = exception.originalMessage,
                ),
            )
        }

        val violations = schema.validate(
            document.source,
            InputFormat.JSON,
        ) { context ->
            context.executionConfig { config -> config.formatAssertionsEnabled(true) }
        }.map { error ->
            ManifestViolation(
                code = "MANIFEST_SCHEMA_VIOLATION",
                path = error.instanceLocation?.toString().orEmpty(),
                message = error.message,
            )
        }.toMutableList()
        inspect(root, "", violations)
        return violations.sortedWith(compareBy(ManifestViolation::path, ManifestViolation::code, ManifestViolation::message))
    }

    private fun inspect(
        node: JsonNode,
        path: String,
        violations: MutableList<ManifestViolation>,
    ) {
        when {
            node.isObject -> node.properties().forEach { (name, value) ->
                val propertyPath = "$path/${escape(name)}"
                checkNfc(name, propertyPath, violations)
                inspect(value, propertyPath, violations)
            }
            node.isArray -> node.forEachIndexed { index, value -> inspect(value, "$path/$index", violations) }
            node.isTextual -> checkNfc(node.textValue(), path, violations)
            node.isFloatingPointNumber -> violations += ManifestViolation(
                code = "MANIFEST_NUMBER_NOT_INTEGER",
                path = path,
                message = "Floating-point and exponent JSON numbers are not allowed",
            )
            node.isIntegralNumber && node.bigIntegerValue().abs() > MAX_SAFE_INTEGER -> violations += ManifestViolation(
                code = "MANIFEST_INTEGER_OUT_OF_RANGE",
                path = path,
                message = "JSON integer exceeds the RFC 8785 interoperable range",
            )
        }
    }

    private fun checkNfc(
        value: String,
        path: String,
        violations: MutableList<ManifestViolation>,
    ) {
        if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            violations += ManifestViolation(
                code = "MANIFEST_NOT_NFC",
                path = path,
                message = "JSON strings and property names must use Unicode NFC",
            )
        }
    }

    private fun escape(value: String): String = value.replace("~", "~0").replace("/", "~1")

    private companion object {
        const val BUNDLED_SCHEMA = "/contracts/release-manifest-v0.2.schema.json"
        val MAX_SAFE_INTEGER: BigInteger = BigInteger("9007199254740991")
    }
}
