package com.ricezhou.vsrqg.issue.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.issue.application.CompiledIssueMappingProfile
import com.ricezhou.vsrqg.issue.application.IssueMappingProfileCodec
import com.ricezhou.vsrqg.issue.application.MappingProfileInvalid
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
import org.erdtman.jcs.JsonCanonicalizer
import org.springframework.stereotype.Component

@Component
class JcsIssueMappingProfileCodec(
    private val objectMapper: ObjectMapper,
) : IssueMappingProfileCodec {
    override fun compile(definition: JsonNode): CompiledIssueMappingProfile {
        val mappingVersion = digest(definition)
        requireExactTopLevelStructure(definition)

        val schemaVersion = requiredText(definition, SCHEMA_VERSION_FIELD)
        requireSupported(schemaVersion == SCHEMA_VERSION, SCHEMA_VERSION_UNSUPPORTED)
        requireSupported(
            requiredText(definition, NORMALIZATION_VERSION_FIELD) == NORMALIZATION_VERSION,
            NORMALIZATION_VERSION_UNSUPPORTED,
        )
        requireSupported(
            requiredText(definition, UNKNOWN_STATUS_POLICY_FIELD) == UNKNOWN_POLICY,
            STATUS_POLICY_UNSUPPORTED,
        )
        requireSupported(
            requiredText(definition, UNKNOWN_SEVERITY_POLICY_FIELD) == UNKNOWN_POLICY,
            SEVERITY_POLICY_UNSUPPORTED,
        )

        val statusByToken = compileAliases(
            aliases = definition.path(STATUS_ALIASES_FIELD),
            allowedTargets = STATUS_TARGETS,
            limitViolation = STATUS_ALIAS_LIMIT_EXCEEDED,
            targetViolation = STATUS_TARGET_INVALID,
            collisionViolation = STATUS_ALIAS_COLLISION,
        )
        val severityByToken = compileAliases(
            aliases = definition.path(SEVERITY_ALIASES_FIELD),
            allowedTargets = SEVERITY_TARGETS,
            limitViolation = SEVERITY_ALIAS_LIMIT_EXCEEDED,
            targetViolation = SEVERITY_TARGET_INVALID,
            collisionViolation = SEVERITY_ALIAS_COLLISION,
        )

        return CompiledIssueMappingProfile(
            schemaVersion = schemaVersion,
            mappingVersion = mappingVersion,
            definition = definition.deepCopy(),
            statusByToken = statusByToken,
            severityByToken = severityByToken,
        )
    }

    private fun digest(definition: JsonNode): String {
        val serialized = objectMapper.writeValueAsBytes(definition)
        if (serialized.size > MAX_PROFILE_BYTES) invalid(PROFILE_TOO_LARGE)
        val canonical = JsonCanonicalizer(serialized).encodedUTF8
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(canonical)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "sha256:$hex"
    }

    private fun requireExactTopLevelStructure(definition: JsonNode) {
        if (!definition.isObject || definition.fieldNames().asSequence().toSet() != TOP_LEVEL_FIELDS) {
            invalid(PROFILE_STRUCTURE_INVALID)
        }
        TEXT_FIELDS.forEach { requiredText(definition, it) }
        listOf(STATUS_ALIASES_FIELD, SEVERITY_ALIASES_FIELD).forEach { field ->
            val aliases = definition.path(field)
            if (!aliases.isObject) invalid(PROFILE_STRUCTURE_INVALID)
            aliases.properties().forEach { (_, rawAliases) ->
                if (!rawAliases.isArray || rawAliases.any { !it.isTextual }) {
                    invalid(PROFILE_STRUCTURE_INVALID)
                }
            }
        }
    }

    private fun requiredText(definition: JsonNode, field: String): String {
        val value = definition.path(field)
        if (!value.isTextual) invalid(PROFILE_STRUCTURE_INVALID)
        return value.textValue()
    }

    private fun <T : Enum<T>> compileAliases(
        aliases: JsonNode,
        allowedTargets: Map<String, T>,
        limitViolation: String,
        targetViolation: String,
        collisionViolation: String,
    ): Map<String, T> {
        val aliasCount = aliases.properties().sumOf { (_, values) -> values.size() }
        if (aliasCount > MAX_ALIASES_PER_FAMILY) invalid(limitViolation)

        val compiled = linkedMapOf<String, T>()
        aliases.properties().forEach { (targetName, rawAliases) ->
            val target = allowedTargets[targetName] ?: invalid(targetViolation)
            rawAliases.forEach { rawAlias ->
                val token = normalizeMappingToken(rawAlias.textValue())
                val existing = compiled[token]
                if (existing != null && existing != target) invalid(collisionViolation)
                compiled[token] = target
            }
        }
        return Collections.unmodifiableMap(compiled.toMap())
    }

    private fun requireSupported(condition: Boolean, violation: String) {
        if (!condition) invalid(violation)
    }

    private fun invalid(violation: String): Nothing = throw MappingProfileInvalid(listOf(violation))

    private companion object {
        const val SCHEMA_VERSION = "jira-mapping-profile/v1"
        const val NORMALIZATION_VERSION = "unicode-nfc-trim-root-lower/v1"
        const val UNKNOWN_POLICY = "MAP_TO_UNKNOWN_WITH_WARNING"
        const val MAX_PROFILE_BYTES = 64 * 1024
        const val MAX_ALIASES_PER_FAMILY = 256

        const val SCHEMA_VERSION_FIELD = "schemaVersion"
        const val NORMALIZATION_VERSION_FIELD = "normalizationVersion"
        const val UNKNOWN_STATUS_POLICY_FIELD = "unknownStatusPolicy"
        const val UNKNOWN_SEVERITY_POLICY_FIELD = "unknownSeverityPolicy"
        const val STATUS_ALIASES_FIELD = "statusAliases"
        const val SEVERITY_ALIASES_FIELD = "severityAliases"

        const val PROFILE_TOO_LARGE = "PROFILE_TOO_LARGE"
        const val PROFILE_STRUCTURE_INVALID = "PROFILE_STRUCTURE_INVALID"
        const val SCHEMA_VERSION_UNSUPPORTED = "SCHEMA_VERSION_UNSUPPORTED"
        const val NORMALIZATION_VERSION_UNSUPPORTED = "NORMALIZATION_VERSION_UNSUPPORTED"
        const val STATUS_POLICY_UNSUPPORTED = "STATUS_POLICY_UNSUPPORTED"
        const val SEVERITY_POLICY_UNSUPPORTED = "SEVERITY_POLICY_UNSUPPORTED"
        const val STATUS_ALIAS_LIMIT_EXCEEDED = "STATUS_ALIAS_LIMIT_EXCEEDED"
        const val SEVERITY_ALIAS_LIMIT_EXCEEDED = "SEVERITY_ALIAS_LIMIT_EXCEEDED"
        const val STATUS_TARGET_INVALID = "STATUS_TARGET_INVALID"
        const val SEVERITY_TARGET_INVALID = "SEVERITY_TARGET_INVALID"
        const val STATUS_ALIAS_COLLISION = "STATUS_ALIAS_COLLISION"
        const val SEVERITY_ALIAS_COLLISION = "SEVERITY_ALIAS_COLLISION"

        val TEXT_FIELDS = setOf(
            SCHEMA_VERSION_FIELD,
            NORMALIZATION_VERSION_FIELD,
            UNKNOWN_STATUS_POLICY_FIELD,
            UNKNOWN_SEVERITY_POLICY_FIELD,
        )
        val TOP_LEVEL_FIELDS = TEXT_FIELDS + setOf(STATUS_ALIASES_FIELD, SEVERITY_ALIASES_FIELD)
        val STATUS_TARGETS = IssueStatus.entries
            .filterNot { it == IssueStatus.UNKNOWN }
            .associateBy(IssueStatus::name)
        val SEVERITY_TARGETS = IssueSeverity.entries
            .filterNot { it == IssueSeverity.UNKNOWN }
            .associateBy(IssueSeverity::name)
    }
}

internal fun normalizeMappingToken(raw: String): String {
    if (raw.isBlank() || raw.length > MAX_MAPPING_TOKEN_LENGTH || raw.any(Char::isISOControl)) {
        throw MappingProfileInvalid(listOf("TOKEN_INVALID"))
    }
    return Normalizer.normalize(raw, Normalizer.Form.NFC)
        .trim(Char::isWhitespace)
        .lowercase(Locale.ROOT)
        .ifBlank { throw MappingProfileInvalid(listOf("TOKEN_INVALID")) }
}

private const val MAX_MAPPING_TOKEN_LENGTH = 120
