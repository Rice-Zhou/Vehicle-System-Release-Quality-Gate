package com.ricezhou.vsrqg.testmanagement.adapter

import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=[TestRunController::class,AgentExecutionController::class])
class TestExecutionProblemHandler(private val problems:ProblemWriter) {
    @ExceptionHandler(ResponseStatusException::class)
    fun invalid(error:ResponseStatusException,request:HttpServletRequest):Any {
        val status=HttpStatus.valueOf(error.statusCode.value())
        val code=when(status) {
            HttpStatus.PAYLOAD_TOO_LARGE -> "PAYLOAD_TOO_LARGE"
            HttpStatus.UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE"
            else -> "INVALID_REQUEST"
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problems.problem(request,status,code,code,code))
    }
}
