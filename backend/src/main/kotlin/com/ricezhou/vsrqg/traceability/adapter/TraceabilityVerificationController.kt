package com.ricezhou.vsrqg.traceability.adapter

import com.ricezhou.vsrqg.access.application.AuthenticatedPrincipalResolver
import com.ricezhou.vsrqg.shared.problem.ApiProblem
import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import com.ricezhou.vsrqg.traceability.application.StartTraceabilityVerification
import com.ricezhou.vsrqg.traceability.application.StartTraceabilityVerificationCommand
import com.ricezhou.vsrqg.traceability.application.TraceabilityInputRejected
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationFailure
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationUnavailable
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

@Validated
@RestController
class TraceabilityVerificationController(
    private val useCase: StartTraceabilityVerification,
    private val principalResolver: AuthenticatedPrincipalResolver,
) {
    @PostMapping("/api/v1/releases/{releaseId}/traceability:verify")
    @PreAuthorize("hasAuthority('SCOPE_traceability:verify')")
    fun start(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable @Size(min = 1, max = 128) releaseId: String,
        @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) idempotencyKey: String,
        @Valid @RequestBody body: TraceabilityVerifyRequest,
        request: HttpServletRequest,
    ): ResponseEntity<TraceabilityVerificationAccepted> {
        val result = useCase.start(
            StartTraceabilityVerificationCommand(
                principal = principalResolver.resolve(
                    jwt.issuer?.toString(),
                    jwt.subject,
                    jwt.getClaimAsString("principal_type"),
                ),
                releaseId = releaseId,
                issueSourceId = body.issueSourceId,
                idempotencyKey = idempotencyKey,
                requestDigest = requestDigest(releaseId, body.issueSourceId),
                requestId = RequestIdFilter.from(request),
            ),
        )
        val accepted = TraceabilityVerificationAccepted(
            verificationRunId = result.verificationRunId,
            releaseId = result.releaseId,
            issueSnapshotId = result.issueSnapshotId,
            inputDigest = result.inputDigest,
            statusUrl = result.statusUrl,
        )
        return ResponseEntity.accepted()
            .location(URI.create(accepted.statusUrl))
            .body(accepted)
    }

    private fun requestDigest(releaseId: String, issueSourceId: String): String =
        "sha256:" + HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest("$releaseId\u0000$issueSourceId".toByteArray(StandardCharsets.UTF_8)),
        )
}

@RestControllerAdvice(assignableTypes = [TraceabilityVerificationController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceabilityVerificationProblemAdvice(
    private val problemWriter: ProblemWriter,
) {
    @ExceptionHandler(TraceabilityVerificationUnavailable::class)
    fun unavailable(request: HttpServletRequest): ResponseEntity<ApiProblem> = problem(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "TRACEABILITY_VERIFICATION_UNAVAILABLE",
        "Traceability verification unavailable",
        "Traceability verification is disabled by deployment policy",
    )

    @ExceptionHandler(TraceabilityInputRejected::class)
    fun invalidInput(
        failure: TraceabilityInputRejected,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> = problem(
        request,
        HttpStatus.UNPROCESSABLE_ENTITY,
        failure.code,
        "Traceability input is invalid",
        "The authoritative traceability input cannot be verified",
    )

    @ExceptionHandler(TraceabilityVerificationFailure::class)
    fun canonicalizationFailure(
        failure: TraceabilityVerificationFailure,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> = problem(
        request,
        HttpStatus.UNPROCESSABLE_ENTITY,
        failure.diagnosticCode,
        "Traceability input is invalid",
        "The authoritative traceability input cannot be verified",
    )

    private fun problem(
        request: HttpServletRequest,
        status: HttpStatus,
        code: String,
        title: String,
        detail: String,
    ): ResponseEntity<ApiProblem> = ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problemWriter.problem(request, status, code, title, detail))
}
