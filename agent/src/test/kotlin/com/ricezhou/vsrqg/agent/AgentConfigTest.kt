package com.ricezhou.vsrqg.agent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class AgentConfigTest {
    @Test fun `CLI refuses auto selection missing credentials and unknown options`() {
        listOf(emptyArray(),arrayOf("--server","http://localhost"),arrayOf("--help"),arrayOf("--device","first"),arrayOf("--server=https://localhost","--unknown=x")).forEach { assertThrows(AgentFailure::class.java) {AgentConfig.parse(it)} }
    }
}
