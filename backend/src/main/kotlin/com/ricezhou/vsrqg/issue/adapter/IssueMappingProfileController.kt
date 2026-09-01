package com.ricezhou.vsrqg.issue.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.access.application.AuthenticatedPrincipalResolver
import com.ricezhou.vsrqg.issue.application.ActivateIssueMappingProfile
import com.ricezhou.vsrqg.issue.application.ActivateIssueMappingProfileCommand
import com.ricezhou.vsrqg.issue.application.ActivateIssueMappingProfileResult
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
class IssueMappingProfileController(
    private val activateIssueMappingProfile: ActivateIssueMappingProfile,
    private val principalResolver: AuthenticatedPrincipalResolver,
) {
    @PostMapping("/api/v1/issue-sources/{sourceId}/mapping-profiles:activate")
    @PreAuthorize("hasAuthority('SCOPE_issue:configure')")
    fun activate(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable @Size(min = 1, max = 40) sourceId: String,
        @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) idempotencyKey: String,
        @RequestBody definition: JsonNode,
        request: HttpServletRequest,
    ): ResponseEntity<ActivateIssueMappingProfileResult> {
        val result = activateIssueMappingProfile.activate(
            ActivateIssueMappingProfileCommand(
                principal = principalResolver.resolve(
                    jwt.issuer?.toString(),
                    jwt.subject,
                    jwt.getClaimAsString("principal_type"),
                ),
                sourceId = sourceId,
                idempotencyKey = idempotencyKey,
                definition = definition,
                requestId = RequestIdFilter.from(request),
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(result)
    }
}
