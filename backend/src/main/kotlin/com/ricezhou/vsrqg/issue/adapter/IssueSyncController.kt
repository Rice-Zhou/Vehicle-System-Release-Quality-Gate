package com.ricezhou.vsrqg.issue.adapter

import com.ricezhou.vsrqg.access.application.AuthenticatedPrincipalResolver
import com.ricezhou.vsrqg.issue.application.GetIssueSync
import com.ricezhou.vsrqg.issue.application.IssueSyncRunResult
import com.ricezhou.vsrqg.issue.application.StartIssueSync
import com.ricezhou.vsrqg.issue.application.StartIssueSyncCommand
import com.ricezhou.vsrqg.issue.application.StartIssueSyncResult
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Size
import java.nio.charset.StandardCharsets
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
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
class IssueSyncController(
    private val startIssueSync: StartIssueSync,
    private val getIssueSync: GetIssueSync,
    private val principalResolver: AuthenticatedPrincipalResolver,
) {
    @PostMapping("/api/v1/issue-sources/{sourceId}/sync")
    @PreAuthorize("hasAuthority('SCOPE_issue:sync')")
    fun start(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable @Size(min = 1, max = 40) sourceId: String,
        @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) idempotencyKey: String,
        request: HttpServletRequest,
    ): ResponseEntity<StartIssueSyncResult> {
        val result = startIssueSync.start(
            StartIssueSyncCommand(
                principal = principal(jwt),
                sourceId = sourceId,
                idempotencyKey = idempotencyKey,
                requestDigest = digest(sourceId),
                requestId = RequestIdFilter.from(request),
            ),
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result)
    }

    @GetMapping("/api/v1/issue-sync-runs/{syncRunId}")
    @PreAuthorize("hasAuthority('SCOPE_issue:read')")
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable @Size(min = 1, max = 40) syncRunId: String,
    ): IssueSyncRunResult = getIssueSync.get(principal(jwt), syncRunId)

    private fun principal(jwt: Jwt) = principalResolver.resolve(
        jwt.issuer?.toString(),
        jwt.subject,
        jwt.getClaimAsString("principal_type"),
    )

    private fun digest(sourceId: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(sourceId.toByteArray(StandardCharsets.UTF_8))
        return "sha256:" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
