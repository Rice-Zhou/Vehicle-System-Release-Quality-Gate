package com.ricezhou.vsrqg.manifest.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.application.AuthenticatedPrincipalResolver
import com.ricezhou.vsrqg.manifest.application.ManifestSchemaInvalid
import com.ricezhou.vsrqg.manifest.application.ManifestViolation
import com.ricezhou.vsrqg.manifest.application.RegisterManifest
import com.ricezhou.vsrqg.manifest.application.RegisterManifestCommand
import com.ricezhou.vsrqg.manifest.application.ValidateManifest
import com.ricezhou.vsrqg.manifest.application.ValidateManifestCommand
import com.ricezhou.vsrqg.manifest.application.ValidationReport
import com.ricezhou.vsrqg.manifest.application.ValidationStatus
import com.ricezhou.vsrqg.manifest.domain.ManifestDocument
import com.ricezhou.vsrqg.shared.problem.ApiProblem
import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.security.MessageDigest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ValidateManifestRequest(
    @field:NotBlank
    @field:Size(max = 1000)
    val reason: String,
)

@Validated
@RestController
@RequestMapping("/api/v1/releases/{releaseId}/manifests")
class ManifestController(
    private val registerManifest: RegisterManifest,
    private val validateManifest: ValidateManifest,
    private val principalResolver: AuthenticatedPrincipalResolver,
    private val objectMapper: ObjectMapper,
    private val problemWriter: ProblemWriter,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasAuthority('SCOPE_manifest:write')")
    fun register(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable @Size(min = 1, max = 128) releaseId: String,
        @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) idempotencyKey: String,
        @RequestBody body: String,
        request: HttpServletRequest,
    ): ResponseEntity<out Any> {
        val document = ManifestDocument(body)
        val result = try {
            registerManifest.register(
                RegisterManifestCommand(
                    principal = principal(jwt),
                    releaseId = releaseId,
                    document = document,
                    idempotencyKey = idempotencyKey,
                    requestId = RequestIdFilter.from(request),
                ),
            )
        } catch (exception: ManifestSchemaInvalid) {
            return schemaProblem(request, exception.violations)
        }
        return if (result.validation.status == ValidationStatus.FAILED) {
            validationProblem(request, result.validation)
        } else {
            ResponseEntity.status(HttpStatus.CREATED).body(result)
        }
    }

    @PostMapping("/{manifestId}:validate")
    @PreAuthorize("hasAuthority('SCOPE_manifest:write')")
    fun validate(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable @Size(min = 1, max = 128) releaseId: String,
        @PathVariable @Size(min = 1, max = 128) manifestId: String,
        @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) idempotencyKey: String,
        @Valid @RequestBody body: ValidateManifestRequest,
        request: HttpServletRequest,
    ): ResponseEntity<out Any> {
        val report = validateManifest.validate(
            ValidateManifestCommand(
                principal = principal(jwt),
                releaseId = releaseId,
                manifestId = manifestId,
                idempotencyKey = idempotencyKey,
                requestDigest = digest(body),
                requestId = RequestIdFilter.from(request),
                reason = body.reason,
            ),
        )
        return if (report.status == ValidationStatus.FAILED) {
            validationProblem(request, report)
        } else {
            ResponseEntity.ok(report)
        }
    }

    private fun schemaProblem(
        request: HttpServletRequest,
        violations: List<ManifestViolation>,
    ): ResponseEntity<ApiProblem> = problem(
        request = request,
        code = "MANIFEST_SCHEMA_INVALID",
        title = "Manifest schema is invalid",
        detail = "The manifest does not satisfy the V0.2 structural contract",
        violations = violations,
    )

    private fun validationProblem(
        request: HttpServletRequest,
        report: ValidationReport,
    ): ResponseEntity<ApiProblem> = problem(
        request = request,
        code = "MANIFEST_VALIDATION_FAILED",
        title = "Manifest validation failed",
        detail = "The manifest identity or integrity metadata is inconsistent with the target release",
        violations = report.violations,
    )

    private fun problem(
        request: HttpServletRequest,
        code: String,
        title: String,
        detail: String,
        violations: List<ManifestViolation>,
    ): ResponseEntity<ApiProblem> = ResponseEntity
        .status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(
            problemWriter.problem(
                request = request,
                status = HttpStatus.UNPROCESSABLE_ENTITY,
                code = code,
                title = title,
                detail = detail,
                violations = violations.map { violation ->
                    mapOf(
                        "code" to violation.code,
                        "path" to violation.path,
                        "message" to violation.message,
                    )
                },
            ),
        )

    private fun principal(jwt: Jwt) = principalResolver.resolve(
        jwt.issuer?.toString(),
        jwt.subject,
        jwt.getClaimAsString("principal_type"),
    )

    private fun digest(body: ValidateManifestRequest): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(body))
        return "sha256:" + hash.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
