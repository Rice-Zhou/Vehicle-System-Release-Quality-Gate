package com.ricezhou.vsrqg.traceability.adapter

import com.ricezhou.vsrqg.access.application.AuthenticatedPrincipalResolver
import com.ricezhou.vsrqg.shared.problem.ApiProblem
import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import com.ricezhou.vsrqg.traceability.application.StartTraceabilityVerification
import com.ricezhou.vsrqg.traceability.application.StartTraceabilityVerificationCommand
import com.ricezhou.vsrqg.traceability.application.GetTraceabilityVerification
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotResult
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRunResult
import com.ricezhou.vsrqg.access.domain.Principal
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
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.transaction.CannotCreateTransactionException

@Validated
@RestController
class TraceabilityVerificationController(
    private val useCase: StartTraceabilityVerification,
    private val query: GetTraceabilityVerification,
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
                principal = principal(jwt),
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

    @GetMapping("/api/v1/traceability-verification-runs/{verificationRunId}")
    @PreAuthorize("hasAuthority('SCOPE_traceability:read')")
    fun getRun(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable @Size(min = 1, max = 128) verificationRunId: String,
    ): TraceabilityVerificationRunResponse = query.getRun(principal(jwt), verificationRunId).toResponse()

    @GetMapping("/api/v1/releases/{releaseId}/traceability")
    @PreAuthorize("hasAuthority('SCOPE_traceability:read')")
    fun getSnapshot(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable @Size(min = 1, max = 128) releaseId: String,
        @RequestParam(required = false) @Size(min = 1, max = 128) snapshotId: String?,
    ): TraceabilitySnapshotResponse = query.getSnapshot(principal(jwt), releaseId, snapshotId).toResponse()

    private fun requestDigest(releaseId: String, issueSourceId: String): String =
        "sha256:" + HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest("$releaseId\u0000$issueSourceId".toByteArray(StandardCharsets.UTF_8)),
        )

    private fun principal(jwt: Jwt): Principal = principalResolver.resolve(
        jwt.issuer?.toString(),
        jwt.subject,
        jwt.getClaimAsString("principal_type"),
    )
}

private fun TraceabilityVerificationRunResult.toResponse() = TraceabilityVerificationRunResponse(
    verificationRunId = verificationRunId,
    releaseId = releaseId,
    status = TraceabilityVerificationStatus.valueOf(status.name),
    policyVersion = policyVersion,
    validatorVersion = validatorVersion,
    inputDigest = inputDigest,
    resultSnapshotId = resultSnapshotId,
    diagnosticCode = diagnosticCode,
    createdAt = createdAt,
    startedAt = startedAt,
    completedAt = completedAt,
)

private fun TraceabilitySnapshotResult.toResponse(): TraceabilitySnapshotResponse = TraceabilitySnapshotResponse.from(
    snapshot = TraceabilitySnapshotHeader(
        snapshotId = header.snapshotId,
        releaseId = header.releaseId,
        version = header.version,
        issueSnapshotId = header.issueSnapshotId,
        manifestRevisionId = header.manifestRevisionId,
        manifestDigest = header.manifestDigest,
        policyVersion = header.policyVersion,
        validatorVersion = header.validatorVersion,
        inputDigest = header.inputDigest,
        contentDigest = header.contentDigest,
        createdAt = header.createdAt,
    ),
    issues = issues.map { issue ->
        TraceabilityIssueResult(
            issueId = issue.issueId,
            sourceIssueId = issue.sourceIssueId,
            fixed = issue.fixed,
            included = issue.included,
            verified = issue.verified,
            path = issue.path.map { edge ->
                TraceabilityPathEdge(
                    edgeId = edge.edgeId,
                    edgeType = TraceabilityPathEdgeType.valueOf(edge.edgeType.name),
                    revisionId = edge.revisionId,
                    revision = edge.revision,
                    fromId = edge.fromId,
                    toId = edge.toId,
                    factDigest = edge.factDigest,
                )
            },
            gaps = issue.gaps.map { gap ->
                TraceabilityGap(
                    diagnosticCode = TraceabilityGapDiagnosticCode.valueOf(gap.diagnosticCode.name),
                    interruptedEntityType = TraceabilityEntityType.valueOf(gap.breakEntityType.name),
                    interruptedEntityId = gap.breakEntityId,
                    expectedEdgeType = TraceabilityExpectedEdgeType.valueOf(gap.expectedEdgeType.name),
                    predecessorEdgeId = gap.predecessorEdgeId,
                    predecessorRevision = gap.predecessorRevision,
                    gapDigest = gap.gapDigest,
                )
            },
            confidence = TraceabilityConfidence.valueOf(issue.confidence.name),
        )
    },
)

@RestControllerAdvice(assignableTypes = [TraceabilityVerificationController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceabilityVerificationProblemAdvice(
    private val problemWriter: ProblemWriter,
) {
    @ExceptionHandler(
        DataAccessResourceFailureException::class,
        TransientDataAccessResourceException::class,
        QueryTimeoutException::class,
        CannotCreateTransactionException::class,
    )
    fun persistenceUnavailable(request: HttpServletRequest): ResponseEntity<ApiProblem> = problem(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "PERSISTENCE_UNAVAILABLE",
        "Persistence unavailable",
        if (request.method == HttpMethod.GET.name()) {
            "The traceability result could not be read; retry the request"
        } else {
            "The request could not be persisted; retry with the same idempotency key"
        },
    )

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
