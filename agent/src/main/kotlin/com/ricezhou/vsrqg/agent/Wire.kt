package com.ricezhou.vsrqg.agent

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.networknt.schema.*
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest

open class AgentFailure(val code:String):RuntimeException(code)
fun ensure(condition:Boolean, code:String) { if(!condition) throw AgentFailure(code) }
object Wire {
    const val MAX_BYTES=65536
    val mapper:ObjectMapper=jacksonObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    private val registry=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    private fun resource(name:String)=requireNotNull(javaClass.getResourceAsStream("/contracts/$name")).use { mapper.readTree(it) }
    private val protocol=resource("agent-protocol.schema.json")
    val openApi=resource("openapi.json")
    private val schemas=protocol.path("${'$'}defs").properties().asSequence().filter {it.value.path("type").asText()=="object"}.associate { (name,node) ->
        name to registry.getSchema(node.deepCopy<ObjectNode>().apply { set<JsonNode>("${'$'}defs",protocol.path("${'$'}defs")) }.toString())
    } + ("context" to registry.getSchema(resource("agent-execution-context.schema.json").toString()))
    fun parse(bytes:ByteArray):JsonNode {
        ensure(bytes.isNotEmpty() && bytes.size<=MAX_BYTES,"WIRE_SIZE_INVALID")
        try { return mapper.readTree(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()) ?: throw AgentFailure("WIRE_INVALID") }
        catch(_:JsonProcessingException) { throw AgentFailure("WIRE_INVALID") }
        catch(_:CharacterCodingException) { throw AgentFailure("WIRE_INVALID") }
    }
    fun validate(node:JsonNode, schema:String) {
        ensure(node.toString().toByteArray().size<=MAX_BYTES,"WIRE_SIZE_INVALID")
        ensure(schemas.getValue(schema).validate(node.toString(),InputFormat.JSON) { context -> context.executionConfig {it.formatAssertionsEnabled(true)} }.isEmpty(),"WIRE_INVALID")
        exactNumbers(node)
    }
    private fun exactNumbers(node:JsonNode) {
        if(node.isNumber) {
            val decimal=node.decimalValue()
            ensure(node.doubleValue().isFinite(),"WIRE_NUMBER_INVALID")
            ensure(decimal.stripTrailingZeros().scale()>0 || decimal.abs()<=BigDecimal("9007199254740991"),"WIRE_NUMBER_INVALID")
            ensure(!node.isFloatingPointNumber || decimal.compareTo(BigDecimal.valueOf(node.doubleValue()))==0,"WIRE_NUMBER_INVALID")
        }
        if(node.isContainerNode) node.forEach(::exactNumbers)
    }
    fun message(type:String):ObjectNode=mapper.createObjectNode().put("messageType",type).put("protocolVersion","1.0")
    fun id(value:String):String { ensure(Regex("[A-Za-z0-9_-]{1,128}").matches(value),"WIRE_ID_INVALID");return value }
    fun sha256(bytes:ByteArray)="sha256:"+MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
