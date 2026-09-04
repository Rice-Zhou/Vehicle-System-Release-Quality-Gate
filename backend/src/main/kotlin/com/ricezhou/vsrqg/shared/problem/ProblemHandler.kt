package com.ricezhou.vsrqg.shared.problem

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.application.IdempotencyConflict
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import com.ricezhou.vsrqg.shared.application.SafeAccessDenied
import com.ricezhou.vsrqg.shared.application.SafeValidationDiagnostic
import com.ricezhou.vsrqg.shared.application.SafeValidationFailure
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import com.ricezhou.vsrqg.shared.web.RequestPaths
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.QueryTimeoutException
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Component
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException

@Component
class ProblemWriter(
    private val objectMapper: ObjectMapper,
) {
    fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        code: String,
        title: String,
        detail: String,
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        objectMapper.writeValue(
            response.outputStream,
            problem(request, status, code, title, detail),
        )
    }

    fun problem(
        request: HttpServletRequest,
        status: HttpStatus,
        code: String,
        title: String,
        detail: String,
        violations: List<Map<String, Any?>> = emptyList(),
    ) = ApiProblem(
        type = "$PROBLEM_BASE/${code.lowercase().replace('_', '-')}",
        title = title,
        status = status.value(),
        code = code,
        detail = detail,
        instance = request.requestURI,
        requestId = RequestIdFilter.from(request),
        violations = violations,
    )

    private companion object {
        const val PROBLEM_BASE = "https://vsrqg.example/problems"
    }
}

@RestControllerAdvice
class ProblemHandler(
    private val problemWriter: ProblemWriter,
) {
    @ExceptionHandler(SafeValidationFailure::class)
    fun safeValidationFailure(
        exception: SafeValidationFailure,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> {
        val problem = when (exception.diagnostic) {
            SafeValidationDiagnostic.MAPPING_PROFILE_INVALID -> SafeValidationProblem(
                code = "MAPPING_PROFILE_INVALID",
                title = "Mapping profile is invalid",
                detail = "The mapping profile does not satisfy the supported schema",
            )
            SafeValidationDiagnostic.ISSUE_SNAPSHOT_INVALID -> SafeValidationProblem(
                code = "ISSUE_SNAPSHOT_INVALID",
                title = "Issue snapshot is invalid",
                detail = "The issue snapshot could not be created from authoritative synchronized observations",
            )
            SafeValidationDiagnostic.BUILD_PROVENANCE_INVALID -> SafeValidationProblem(
                code = "PROOF_VALIDATION_FAILED",
                title = "Build provenance validation failed",
                detail = "The build provenance envelope does not satisfy the supported schema",
            )
            SafeValidationDiagnostic.BUILD_PROVENANCE_FACT_LIMIT_EXCEEDED -> SafeValidationProblem(
                code = "FACT_LIMIT_EXCEEDED",
                title = "Fact limit exceeded",
                detail = "The build provenance envelope derives too many facts",
            )
        }
        return response(
            request,
            HttpStatus.UNPROCESSABLE_ENTITY,
            problem.code,
            problem.title,
            problem.detail,
            exception.violationCodes.map { mapOf("code" to it) },
        )
    }

    @ExceptionHandler(IdempotencyConflict::class)
    fun idempotencyConflict(
        exception: IdempotencyConflict,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> {
        val provenanceIngestion = isBuildProvenanceIngestion(request)
        return response(
            request,
            HttpStatus.CONFLICT,
            if (provenanceIngestion) "IDEMPOTENCY_CONFLICT" else "IDEMPOTENCY_KEY_REUSED",
            "Idempotency key was reused",
            if (provenanceIngestion) {
                "The idempotency key was reused with a different build provenance envelope"
            } else {
                exception.message ?: "The idempotency key was reused with a different request"
            },
        )
    }

    @ExceptionHandler(ResourceNotFound::class)
    fun resourceNotFound(
        exception: ResourceNotFound,
        request: HttpServletRequest,
    ) = response(
        request,
        HttpStatus.NOT_FOUND,
        exception.code,
        exception.resourceTitle,
        exception.message ?: exception.resourceTitle,
    )

    @ExceptionHandler(SafeAccessDenied::class)
    fun safeAccessDenied(
        exception: SafeAccessDenied,
        request: HttpServletRequest,
    ) = response(
        request,
        HttpStatus.FORBIDDEN,
        exception.code,
        exception.accessTitle,
        exception.message ?: exception.accessTitle,
    )

    @ExceptionHandler(AccessDeniedException::class)
    fun accessDenied(request: HttpServletRequest) = response(
        request,
        HttpStatus.FORBIDDEN,
        "ACCESS_DENIED",
        "Access denied",
        "The authenticated principal is not allowed to perform this operation",
    )

    @ExceptionHandler(ResourceConflict::class)
    fun resourceConflict(
        exception: ResourceConflict,
        request: HttpServletRequest,
    ) = response(
        request,
        HttpStatus.CONFLICT,
        exception.code,
        exception.resourceTitle,
        exception.message ?: exception.resourceTitle,
    )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ) = response(
        request,
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        "Invalid request",
        "The request contains invalid fields",
        exception.bindingResult.fieldErrors.map(::violation),
    )

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun methodValidation(
        exception: HandlerMethodValidationException,
        request: HttpServletRequest,
    ) = response(
        request,
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        "Invalid request",
        "A request parameter or header is invalid",
        exception.allErrors.map { mapOf("message" to (it.defaultMessage ?: "Invalid value")) },
    )

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MissingRequestHeaderException::class,
        ConstraintViolationException::class,
    )
    fun invalidRequest(request: HttpServletRequest) = response(
        request,
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        "Invalid request",
        "The request could not be parsed or validated",
    )

    @ExceptionHandler(
        DataAccessResourceFailureException::class,
        TransientDataAccessResourceException::class,
        QueryTimeoutException::class,
        CannotCreateTransactionException::class,
    )
    fun persistenceUnavailable(
        exception: RuntimeException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> {
        if (!isBuildProvenanceIngestion(request)) return unexpected(exception, request)
        val requestId = RequestIdFilter.from(request)
        logger.warn(
            "Persistence unavailable requestId={} code={} exceptionType={}",
            requestId,
            "PERSISTENCE_UNAVAILABLE",
            exception.javaClass.name,
        )
        return response(
            request,
            HttpStatus.SERVICE_UNAVAILABLE,
            "PERSISTENCE_UNAVAILABLE",
            "Persistence unavailable",
            "The request could not be persisted; retry with the same idempotency key",
        )
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun persistenceIntegrityFailure(
        exception: DataIntegrityViolationException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> {
        val requestId = RequestIdFilter.from(request)
        logger.error(
            "Persistence integrity failure requestId={} code={} exceptionType={}",
            requestId,
            "PERSISTENCE_INTEGRITY_FAILURE",
            exception.javaClass.name,
        )
        return response(
            request,
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "Internal server error",
            "The request could not be completed",
        )
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiProblem> {
        val requestId = RequestIdFilter.from(request)
        logger.error(
            "Unhandled API exception requestId={} code={} exceptionType={}",
            requestId,
            "INTERNAL_ERROR",
            exception.javaClass.name,
        )
        return response(
            request,
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "Internal server error",
            "The request could not be completed",
        )
    }

    private fun response(
        request: HttpServletRequest,
        status: HttpStatus,
        code: String,
        title: String,
        detail: String,
        violations: List<Map<String, Any?>> = emptyList(),
    ): ResponseEntity<ApiProblem> = ResponseEntity
        .status(status)
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        .body(problemWriter.problem(request, status, code, title, detail, violations))

    private fun violation(error: FieldError): Map<String, Any?> = mapOf(
        "field" to error.field,
        "message" to (error.defaultMessage ?: "Invalid value"),
    )

    private fun isBuildProvenanceIngestion(request: HttpServletRequest): Boolean =
        RequestPaths.isExactPost(request, "/api/v1/traceability/facts:ingest")

    private companion object {
        val logger = LoggerFactory.getLogger(ProblemHandler::class.java)
    }

    private data class SafeValidationProblem(
        val code: String,
        val title: String,
        val detail: String,
    )
}
