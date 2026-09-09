package com.ricezhou.vsrqg.testmanagement

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ricezhou.vsrqg.testmanagement.adapter.ConfiguredSmokeEnvironment
import com.ricezhou.vsrqg.testmanagement.adapter.TestWire
import com.ricezhou.vsrqg.testmanagement.application.TestRunConflict
import com.ricezhou.vsrqg.testmanagement.application.TestJson
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.web.server.ResponseStatusException
import java.util.Base64

@Timeout(60)
class SmokeEnvironmentTest {
    private val mapper=jacksonObjectMapper()
    private val wire=TestWire(mapper)
    private val bytes="""{"bootSessionId":"boot","buildId":"build","buildFingerprint":"synthetic"}""".toByteArray()
    @Test fun `exact CONFIG bytes survive encoding and environment parsing without canonicalizing away byte identity`() {
        val padded=bytes + "\n".toByteArray()
        val source=ConfiguredSmokeEnvironment("agt_test","dev_test",Base64.getEncoder().encodeToString(padded))
        val loaded=source.load()
        assertThat(loaded.configBytes).containsExactly(*padded)
        assertThat(wire.environment(loaded.configBytes).path("bootSessionId").asText()).isEqualTo("boot")
        assertThat(TestJson.sha256(loaded.configBytes)).isNotEqualTo(TestJson.sha256(bytes))
        loaded.configBytes[0]=0
        assertThat(loaded.configBytes).containsExactly(*padded)
    }
    @Test fun `missing oversized and malformed environment encodings are rejected`() {
        for(encoded in listOf("","!bad", "a".repeat(87385),Base64.getEncoder().encodeToString(ByteArray(65537)))) {
            assertThatThrownBy { ConfiguredSmokeEnvironment("agt_test","dev_test",encoded).load() }.isInstanceOf(TestRunConflict::class.java)
        }
        assertThatThrownBy { ConfiguredSmokeEnvironment("","dev","e30=").load() }.isInstanceOf(TestRunConflict::class.java)
    }
    @Test fun `environment rejects unknown duplicate missing fields and invalid UTF8`() {
        val invalid=listOf("{}",String(bytes).dropLast(1)+""","environmentMatched":true}""",
            """{"bootSessionId":"a","bootSessionId":"b","buildId":"build","buildFingerprint":"synthetic"}""",
            """{"bootSessionId":"a","buildId":"","buildFingerprint":"synthetic"}""")
        invalid.forEach { assertThatThrownBy { wire.environment(it.toByteArray()) }.isInstanceOf(TestRunConflict::class.java) }
        assertThatThrownBy { wire.environment(byteArrayOf(0xC3.toByte(),0x28)) }.isInstanceOf(TestRunConflict::class.java)
    }
    @Test fun `a second JSON root after a valid CONFIG is rejected`() {
        assertThatThrownBy { wire.environment(bytes+" {}".toByteArray()) }.isInstanceOf(TestRunConflict::class.java)
    }
    @Test fun `Create schema rejects client environment booleans and unknown selectors`() {
        val valid=mapper.readTree("""{"releaseId":"rel_test","testPlan":{"planId":"single-device-smoke","version":1},"deviceSelector":{"vehicle":"synthetic","requiredCapabilities":["ADB"]}}""")
        wire.validateCreate(valid)
        val unknown=valid.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("environmentMatched",true)
        assertThatThrownBy { wire.validateCreate(unknown) }.isInstanceOf(ResponseStatusException::class.java)
        val selector=valid.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (selector.path("deviceSelector") as com.fasterxml.jackson.databind.node.ObjectNode).put("deviceId","arbitrary")
        assertThatThrownBy { wire.validateCreate(selector) }.isInstanceOf(ResponseStatusException::class.java)
    }
}
