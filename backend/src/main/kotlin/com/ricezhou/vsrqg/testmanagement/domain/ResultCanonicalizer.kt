package com.ricezhou.vsrqg.testmanagement.domain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.testmanagement.application.TestJson

object ResultCanonicalizer {
    private val mapper=ObjectMapper()
    fun digest(request:JsonNode):String {
        val input=request.deepCopy<ObjectNode>()
        input.remove("resultDigest")
        val ids=input.withArray("evidenceIds").map { it.textValue() }.toSortedSet()
        input.putArray("evidenceIds").also { array->ids.forEach(array::add) }
        return TestJson.sha256(org.erdtman.jcs.JsonCanonicalizer(mapper.writeValueAsBytes(input)).encodedUTF8)
    }
}
