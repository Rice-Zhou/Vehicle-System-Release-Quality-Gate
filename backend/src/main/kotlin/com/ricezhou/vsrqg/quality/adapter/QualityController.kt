package com.ricezhou.vsrqg.quality.adapter

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.application.AuthenticatedPrincipalResolver
import com.ricezhou.vsrqg.manifest.application.ManifestRepository
import com.ricezhou.vsrqg.quality.application.QualityEvaluationService
import com.ricezhou.vsrqg.quality.application.QualityInputFailure
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.ricezhou.vsrqg.shared.problem.ApiProblem
import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class QualityController(
    private val evaluations: QualityEvaluationService,
    private val manifests: ManifestRepository,
    private val principals: AuthenticatedPrincipalResolver,
    private val problemWriter: ProblemWriter,
    mapper: ObjectMapper,
) {
    private val strictMapper = mapper.copy()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    @ExceptionHandler(QualityInputFailure::class)
    fun invalid(failure: QualityInputFailure, request: HttpServletRequest): ResponseEntity<ApiProblem> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problemWriter.problem(request, HttpStatus.UNPROCESSABLE_ENTITY, failure.code,
                "Quality Evaluation is invalid", failure.code))

    @PostMapping("/api/v1/releases/{releaseId}/quality-evaluations", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasAuthority('SCOPE_quality:evaluate')")
    fun request(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable releaseId: String,
        @RequestHeader("Idempotency-Key") key: String,
        request: HttpServletRequest,
    ): ResponseEntity<JsonNode> {
        if (key.isBlank() || key.length > 128) throw QualityInputFailure("QUALITY_EVALUATION_REQUEST_INVALID")
        val bytes = request.inputStream.readNBytes(8193)
        if (bytes.size > 8192) throw QualityInputFailure("QUALITY_EVALUATION_REQUEST_INVALID")
        val body = try { strictMapper.readTree(bytes) } catch (_: com.fasterxml.jackson.core.JsonProcessingException) {
            throw QualityInputFailure("QUALITY_EVALUATION_REQUEST_INVALID")
        } ?: throw QualityInputFailure("QUALITY_EVALUATION_REQUEST_INVALID")
        if (!body.isObject || body.properties().map { it.key }.toSet() !=
            setOf("ruleSet", "testRunIds", "traceabilitySnapshotId")) {
            throw QualityInputFailure("QUALITY_EVALUATION_REQUEST_INVALID")
        }
        val projectId = manifests.findRelease(releaseId)?.projectId ?: missing()
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            evaluations.request(projectId, releaseId, body, key, principals.resolve(
                jwt.issuer?.toString(), jwt.subject, jwt.getClaimAsString("principal_type")),
                RequestIdFilter.from(request)),
        )
    }

    @GetMapping("/api/v1/releases/{releaseId}/quality-results")
    @PreAuthorize("hasAuthority('SCOPE_quality:read')")
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable releaseId: String,
        @RequestParam(required = false) cursor: String?,
    ): JsonNode {
        val projectId = manifests.findRelease(releaseId)?.projectId ?: missing()
        return evaluations.list(projectId, releaseId, cursor, principals.resolve(
            jwt.issuer?.toString(), jwt.subject, jwt.getClaimAsString("principal_type")))
    }

    private fun missing(): Nothing = throw ResourceNotFound(
        "RELEASE_NOT_FOUND", "Release not found", "Release not found")
}