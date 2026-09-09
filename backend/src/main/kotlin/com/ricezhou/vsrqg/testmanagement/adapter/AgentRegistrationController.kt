package com.ricezhou.vsrqg.testmanagement.adapter

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import com.ricezhou.vsrqg.testmanagement.application.AgentProtocolUnsupported
import com.ricezhou.vsrqg.testmanagement.application.AgentRegistrationDisabled
import com.ricezhou.vsrqg.testmanagement.application.RegisterAgent
import jakarta.servlet.http.HttpServletRequest
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.HexFormat
import org.erdtman.jcs.JsonCanonicalizer
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class AgentRegistrationController(private val registration: RegisterAgent, objectMapper: ObjectMapper, private val problems: ProblemWriter) {
    private val mapper = objectMapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    private val schema = run {
        val protocol = mapper.readTree(requireNotNull(javaClass.getResource("/contracts/agent-protocol.schema.json")))
        val registration = protocol.path("\$defs").path("registrationRequest").deepCopy<ObjectNode>()
        registration.set<ObjectNode>("\$defs", protocol.path("\$defs").deepCopy<ObjectNode>())
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(registration.toString()).also { it.initializeValidators() }
    }

    @PostMapping("/agent-api/v1/agents:register")
    fun register(authentication: Authentication, @RequestHeader("Idempotency-Key") key: String,
                 request: HttpServletRequest): Any {
        if (key.isBlank() || key.length > 128) throw InvalidAgentRegistration()
        val source = readBody(request)
        val body = try { mapper.readTree(source) } catch (_: JsonProcessingException) { throw InvalidAgentRegistration() }
        if (body == null || schema.validate(source, InputFormat.JSON).isNotEmpty()) throw InvalidAgentRegistration()
        val digest = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JsonCanonicalizer(source).encodedUTF8))
        return registration.register(authentication.name, body, key, digest, RequestIdFilter.from(request))
    }

    @ExceptionHandler(AgentProtocolUnsupported::class)
    fun unsupported(request: HttpServletRequest) = problem(request, HttpStatus.UPGRADE_REQUIRED, "AGENT_PROTOCOL_UNSUPPORTED", "No supported Agent protocol")
    @ExceptionHandler(AgentRegistrationDisabled::class)
    fun disabled(request: HttpServletRequest) = problem(request, HttpStatus.SERVICE_UNAVAILABLE, "AGENT_REGISTRATION_DISABLED", "Demo Agent registration is disabled")
    @ExceptionHandler(InvalidAgentRegistration::class)
    fun invalid(request: HttpServletRequest) = problem(request, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid Agent registration")
    @ExceptionHandler(AgentRegistrationTooLarge::class)
    fun tooLarge(request: HttpServletRequest) = problem(request, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Agent registration exceeds the byte limit")
    @ExceptionHandler(UnsupportedAgentMediaType::class)
    fun unsupportedMedia(request: HttpServletRequest) = problem(request, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Agent registration requires UTF-8 application/json")

    private fun readBody(request: HttpServletRequest): String {
        val mediaType = try { MediaType.parseMediaType(request.contentType ?: "") }
            catch (_: IllegalArgumentException) { throw UnsupportedAgentMediaType() }
        if (mediaType.type != "application" || mediaType.subtype != "json" ||
            (mediaType.charset != null && mediaType.charset != Charsets.UTF_8)) throw UnsupportedAgentMediaType()
        if (request.contentLengthLong > MAX_REGISTRATION_BYTES) throw AgentRegistrationTooLarge()
        // Read at most one byte beyond the limit, including when Content-Length is absent or misleading.
        val bytes = request.inputStream.readNBytes(MAX_REGISTRATION_BYTES + 1)
        if (bytes.size > MAX_REGISTRATION_BYTES) throw AgentRegistrationTooLarge()
        if (bytes.isEmpty()) throw InvalidAgentRegistration()
        return try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: CharacterCodingException) { throw InvalidAgentRegistration() }
    }

    private fun problem(request: HttpServletRequest, status: HttpStatus, code: String, detail: String) =
        ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problems.problem(request, status, code, detail, detail))

    private class InvalidAgentRegistration : RuntimeException()
    private class AgentRegistrationTooLarge : RuntimeException()
    private class UnsupportedAgentMediaType : RuntimeException()
    private companion object { const val MAX_REGISTRATION_BYTES = 65536 }
}
