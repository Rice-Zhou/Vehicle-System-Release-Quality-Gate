package com.ricezhou.vsrqg.testmanagement.adapter

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.ricezhou.vsrqg.testmanagement.application.TestInputValidator
import com.ricezhou.vsrqg.testmanagement.application.TestRunConflict
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

@Component
class TestWire(objectMapper:ObjectMapper):TestInputValidator {
    private val mapper=objectMapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val registry=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    private val protocol=resourceJson("agent-protocol.schema.json")
    private val openApi=resourceJson("openapi.json")
    private val contextSchema=registry.getSchema(requireNotNull(javaClass.getResource("/contracts/agent-execution-context.schema.json")).readText())
    private val createSchema=run {
        val node=openApi.path("components").path("schemas").path("CreateTestRunRequest").deepCopy<ObjectNode>()
        node.set<JsonNode>("components",openApi.path("components"))
        registry.getSchema(node.toString())
    }
    private val environmentSchema=run {
        val full=resourceJson("agent-execution-context.schema.json")
        val node=full.path("properties").path("environment").deepCopy<ObjectNode>()
        node.set<JsonNode>("\$defs",full.path("\$defs"))
        registry.getSchema(node.toString())
    }
    private val requestSchemas=mapOf("heartbeatRequest" to protocolSchema("heartbeatRequest"),
        "pollRequest" to protocolSchema("pollRequest"),"ackRequest" to protocolSchema("ackRequest"))
    private fun resourceJson(name:String):JsonNode = requireNotNull(javaClass.getResourceAsStream("/contracts/$name")).use(mapper::readTree)
    private fun protocolSchema(name:String):Schema {
        val node=protocol.path("\$defs").path(name).deepCopy<ObjectNode>()
        node.set<JsonNode>("\$defs",protocol.path("\$defs"))
        return registry.getSchema(node.toString())
    }
    fun read(request:HttpServletRequest,key:String,schema:String?=null):JsonNode {
        if(key.isBlank() || key.length>128) bad()
        val media=try { MediaType.parseMediaType(request.contentType ?: "") } catch(_:IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"UTF-8 application/json required")
        }
        if(media.type!="application" || media.subtype!="json" || (media.charset!=null && media.charset!=Charsets.UTF_8))
            throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"UTF-8 application/json required")
        if(request.contentLengthLong>MAX_BYTES) throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE)
        val bytes=request.inputStream.readNBytes(MAX_BYTES+1)
        if(bytes.size>MAX_BYTES) throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE)
        val node=parse(bytes)
        if(schema!=null && requestSchemas.getValue(schema).validate(node.toString(),InputFormat.JSON).isNotEmpty()) bad()
        return node
    }
    override fun validateCreate(body:JsonNode) {
        if(createSchema.validate(body.toString(),InputFormat.JSON).isNotEmpty()) bad()
    }
    override fun environment(bytes:ByteArray):JsonNode {
        if(bytes.isEmpty() || bytes.size>MAX_BYTES) throw TestRunConflict("ENVIRONMENT_CONFIG_INVALID")
        val node=try { parse(bytes) } catch(_:ResponseStatusException) { throw TestRunConflict("ENVIRONMENT_CONFIG_INVALID") }
        if(environmentSchema.validate(node.toString(),InputFormat.JSON).isNotEmpty()) throw TestRunConflict("ENVIRONMENT_CONFIG_INVALID")
        return node
    }
    override fun context(body:JsonNode) {
        if(contextSchema.validate(body.toString(),InputFormat.JSON).isNotEmpty()) throw TestRunConflict("EXECUTION_CONTEXT_INVALID")
    }
    private fun parse(bytes:ByteArray):JsonNode {
        val source=try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() } catch(_:CharacterCodingException) { bad() }
        return try { mapper.readTree(source) ?: bad() } catch(_:JsonProcessingException) { bad() }
    }
    private fun bad():Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST")
    companion object { const val MAX_BYTES=65536 }
}
