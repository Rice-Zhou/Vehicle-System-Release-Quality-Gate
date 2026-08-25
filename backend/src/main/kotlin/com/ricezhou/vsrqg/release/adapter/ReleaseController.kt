package com.ricezhou.vsrqg.release.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.application.AuthenticatedPrincipalResolver
import com.ricezhou.vsrqg.access.application.ProjectReferenceResolver
import com.ricezhou.vsrqg.release.application.CreateRelease
import com.ricezhou.vsrqg.release.application.CreateReleaseCommand
import com.ricezhou.vsrqg.release.application.CreateReleaseResult
import com.ricezhou.vsrqg.release.application.GetRelease
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.security.MessageDigest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class CreateReleaseRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val project: String,
    @field:NotBlank
    @field:Size(max = 120)
    val vehicle: String,
    @field:NotBlank
    @field:Size(max = 120)
    val platform: String,
    @field:NotBlank
    @field:Size(max = 255)
    val systemVersion: String,
    @field:NotBlank
    @field:Size(max = 255)
    val buildId: String,
)

@Validated
@RestController
@RequestMapping("/api/v1/releases")
class ReleaseController(
    private val createRelease: CreateRelease,
    private val getRelease: GetRelease,
    private val projectResolver: ProjectReferenceResolver,
    private val principalResolver: AuthenticatedPrincipalResolver,
    private val objectMapper: ObjectMapper,
) {
    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_release:create')")
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) idempotencyKey: String,
        @Valid @RequestBody body: CreateReleaseRequest,
        request: HttpServletRequest,
    ): ResponseEntity<CreateReleaseResult> {
        val result = createRelease.create(
            CreateReleaseCommand(
                principal = principal(jwt),
                projectId = projectResolver.resolve(body.project),
                vehicle = body.vehicle,
                platform = body.platform,
                systemVersion = body.systemVersion,
                buildId = body.buildId,
                idempotencyKey = idempotencyKey,
                requestDigest = digest(body),
                requestId = RequestIdFilter.from(request),
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(result)
    }

    @GetMapping("/{releaseId}")
    @PreAuthorize("hasAuthority('SCOPE_release:read')")
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable @Size(min = 1, max = 128) releaseId: String,
    ): CreateReleaseResult = getRelease.get(principal(jwt), releaseId)

    private fun principal(jwt: Jwt) = principalResolver.resolve(
        jwt.issuer?.toString(),
        jwt.subject,
        jwt.getClaimAsString("principal_type"),
    )

    private fun digest(body: CreateReleaseRequest): String {
        val bytes = objectMapper.writeValueAsBytes(body)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "sha256:" + hash.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
