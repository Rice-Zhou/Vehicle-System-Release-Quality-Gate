package com.ricezhou.vsrqg.testmanagement

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.testmanagement.domain.ResultCanonicalizer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(60)
class ResultCanonicalizerTest {
    private val mapper=ObjectMapper()
    @Test fun `golden request matches independently fixed canonical input and digest`() {
        val root=java.nio.file.Path.of("../contracts/examples/v0.2/agent")
        val input=mapper.readTree(java.nio.file.Files.readString(root.resolve("result-canonical-input.json")))
        val expected=mapper.readTree(java.nio.file.Files.readString(root.resolve("result-canonical-expected.json")))
        assertEquals(expected.path("resultDigest").asText(),ResultCanonicalizer.digest(input))
        assertEquals(expected.path("resultDigest").asText(),ResultCanonicalizer.digest(expected.path("canonicalInput")))
    }
    @Test fun `digest ignores the supplied digest and preserves the request`() {
        val request=mapper.readTree("""{"attemptId":"a","evidenceIds":["b","a"],"resultDigest":"ignored"}""")
        val before=request.deepCopy<JsonNode>()
        assertEquals(ResultCanonicalizer.digest(mapper.readTree("""{"evidenceIds":["a","b"],"attemptId":"a"}""")),ResultCanonicalizer.digest(request))
        assertEquals(before,request)
    }
    @Test fun `canonical evidence set ignores duplicates but content changes digest`() {
        val first=mapper.readTree("""{"status":"PASS","evidenceIds":["b","a","a"]}""")
        val same=mapper.readTree("""{"evidenceIds":["a","b"],"status":"PASS"}""")
        val changed=mapper.readTree("""{"evidenceIds":["a","b"],"status":"FAIL"}""")
        assertEquals(ResultCanonicalizer.digest(first),ResultCanonicalizer.digest(same))
        assertNotEquals(ResultCanonicalizer.digest(same),ResultCanonicalizer.digest(changed))
    }
}
