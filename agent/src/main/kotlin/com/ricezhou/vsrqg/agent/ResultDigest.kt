package com.ricezhou.vsrqg.agent
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.erdtman.jcs.JsonCanonicalizer
object ResultDigest {
    fun digest(request:JsonNode):String {
        val copy=request.deepCopy<ObjectNode>();copy.remove("resultDigest")
        val ids=copy.path("evidenceIds").map {it.textValue()}.toSortedSet()
        copy.putArray("evidenceIds").also { array -> ids.forEach(array::add) }
        return Wire.sha256(JsonCanonicalizer(Wire.mapper.writeValueAsBytes(copy)).encodedUTF8)
    }
}
