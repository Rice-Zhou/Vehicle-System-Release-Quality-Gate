package com.ricezhou.vsrqg.quality.adapter

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.application.AuthenticatedPrincipalResolver
import com.ricezhou.vsrqg.quality.application.QualityRepository
import com.ricezhou.vsrqg.quality.application.RulePublication
import com.ricezhou.vsrqg.quality.application.RulePublicationInvalid
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
class RuleSetController(
    private val publication: RulePublication,
    private val repository: QualityRepository,
    private val principalResolver: AuthenticatedPrincipalResolver,
    mapper: ObjectMapper,
) {
    private val strictMapper = mapper.copy()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    @PostMapping("/api/v1/rule-sets", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasAuthority('SCOPE_rule:write')")
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) key: String,
        request: HttpServletRequest,
    ): ResponseEntity<JsonNode> {
        val definition = parse(request, 128 * 1024)
        val project = definition["project"]?.takeIf(JsonNode::isTextual)?.textValue()
            ?: throw RulePublicationInvalid("RULE_SET_REQUEST_INVALID")
        return ResponseEntity.status(HttpStatus.CREATED).eTag("0").body(publication.create(
            project, definition, key, principal(jwt), RequestIdFilter.from(request)))
    }

    @PostMapping("/api/v1/rule-sets/{id}:publish", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasAuthority('SCOPE_rule:publish')")
    fun publish(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable @Size(min = 1, max = 128) id: String,
        @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) key: String,
        @RequestHeader("If-Match") ifMatch: String,
        request: HttpServletRequest,
    ): ResponseEntity<JsonNode> {
        val reasonBody = parse(request, 2048)
        if (!reasonBody.isObject || reasonBody.size() != 1 || reasonBody["reason"]?.isTextual != true) {
            throw RulePublicationInvalid("RULE_REVIEW_REASON_INVALID")
        }
        val set = repository.findByRuleSetId(id) ?: throw ResourceNotFound(
            "RULE_SET_NOT_FOUND", "Rule Set not found", "Rule Set not found")
        val match = ifMatch.trim()
        val text = when {
            UNQUOTED_VERSION.matches(match) -> match
            QUOTED_VERSION.matches(match) -> match.substring(1, match.length - 1)
            else -> null
        }
        val version = text?.toLongOrNull()?.takeIf { it >= 0 && it < Long.MAX_VALUE }
            ?: throw ResourceConflict(
            "RULE_SET_VERSION_CONFLICT", "Rule Set version conflict", "If-Match must be a non-negative row version")
        return ResponseEntity.ok().eTag((version + 1).toString()).body(publication.publish(set.projectId, id, version,
            reasonBody["reason"].textValue(), key, principal(jwt), RequestIdFilter.from(request)))
    }

    private fun principal(jwt: Jwt) = principalResolver.resolve(
        jwt.issuer?.toString(), jwt.subject, jwt.getClaimAsString("principal_type"))

    private fun parse(request: HttpServletRequest, limit: Int): JsonNode = try {
        val bytes = request.inputStream.readNBytes(limit + 1)
        if (bytes.size > limit) throw RulePublicationInvalid("RULE_SET_REQUEST_LIMIT")
        strictMapper.readTree(bytes) ?: throw RulePublicationInvalid("RULE_SET_REQUEST_INVALID")
    } catch (_: JsonProcessingException) {
        throw RulePublicationInvalid("RULE_SET_REQUEST_INVALID")
    }

    private companion object {
        val UNQUOTED_VERSION = Regex("[0-9]+")
        val QUOTED_VERSION = Regex("\"[0-9]+\"")
    }
}
