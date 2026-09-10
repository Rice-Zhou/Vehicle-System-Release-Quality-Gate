package com.ricezhou.vsrqg.agent
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class ResultDigestContractTest {
    @Test fun `agent shares backend canonical vectors directly`() {
        val input=Wire.parse(javaClass.getResourceAsStream("/contracts/examples/result-canonical-input.json")!!.readAllBytes())
        val expected=Wire.parse(javaClass.getResourceAsStream("/contracts/examples/result-canonical-expected.json")!!.readAllBytes())
        Wire.validate(input,"resultRequest")
        assertEquals(expected.path("resultDigest").asText(), ResultDigest.digest(input))
        val reversed=input.deepCopy<ObjectNode>(); reversed.putArray("evidenceIds").add("ev_png").add("ev_log")
        assertEquals(expected.path("resultDigest").asText(),ResultDigest.digest(reversed))
    }
    @Test fun `wire rejects invalid date unknown fields duplicate keys trailing JSON and lossy decimals`() {
        val input=javaClass.getResourceAsStream("/contracts/examples/result-canonical-input.json")!!.readAllBytes().toString(Charsets.UTF_8)
        listOf(input.replace("2026-09-09T00:00:00Z","invalid-date"), input.replace("\"fencingToken\": 1","\"fencingToken\": 1.000000000000000001"),input.replace("\"fencingToken\": 1","\"fencingToken\": 9007199254740992"),input.replace("\"fencingToken\": 1","\"fencingToken\": 1, \"commandId\": \"cmd\"" )).forEach {
            assertThrows(AgentFailure::class.java) { Wire.validate(Wire.parse(it.toByteArray()),"resultRequest") }
        }
        assertThrows(AgentFailure::class.java) { Wire.parse("{\"a\":1,\"a\":2}".toByteArray()) }
        assertThrows(AgentFailure::class.java) { Wire.parse("{} {}".toByteArray()) }
        assertThrows(AgentFailure::class.java) { Wire.parse(byteArrayOf(-1)) }
    }
}
