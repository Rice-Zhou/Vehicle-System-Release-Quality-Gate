package com.ricezhou.vsrqg.issue.adapter

import com.ricezhou.vsrqg.access.application.AuthenticatedPrincipalResolver
import com.ricezhou.vsrqg.issue.application.CreateIssueSnapshot
import com.ricezhou.vsrqg.issue.application.CreateIssueSnapshotCommand
import com.ricezhou.vsrqg.issue.application.CreateIssueSnapshotResult
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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

data class IdentifierInput(@field:Size(min = 1, max = 40) val sourceId: String)

@Validated
@RestController
class IssueSnapshotController(
    private val useCase: CreateIssueSnapshot,
    private val principalResolver: AuthenticatedPrincipalResolver,
) {
    @PostMapping("/api/v1/releases/{releaseId}/issue-snapshots")
    @PreAuthorize("hasAuthority('SCOPE_issue:snapshot')")
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable @Size(min = 1, max = 40) releaseId: String,
        @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) idempotencyKey: String,
        @Valid @RequestBody body: IdentifierInput,
        request: HttpServletRequest,
    ): ResponseEntity<CreateIssueSnapshotResult> {
        val requestDigest = digest(releaseId, body.sourceId)
        val result = useCase.create(
            CreateIssueSnapshotCommand(
                principalResolver.resolve(jwt.issuer?.toString(), jwt.subject, jwt.getClaimAsString("principal_type")),
                releaseId,
                body.sourceId,
                idempotencyKey,
                requestDigest,
                RequestIdFilter.from(request),
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(result)
    }

    private fun digest(releaseId: String, sourceId: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("$releaseId\u0000$sourceId".toByteArray(StandardCharsets.UTF_8))
        return "sha256:" + hash.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
