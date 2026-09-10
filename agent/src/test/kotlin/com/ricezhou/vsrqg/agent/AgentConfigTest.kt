package com.ricezhou.vsrqg.agent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class AgentConfigTest {
    @org.junit.jupiter.api.io.TempDir lateinit var root:java.nio.file.Path
    @Test fun `CLI refuses auto selection missing credentials and unknown options`() {
        listOf(emptyArray(),arrayOf("--server","http://localhost"),arrayOf("--help"),arrayOf("--device","first"),arrayOf("--server=https://localhost","--unknown=x")).forEach { assertThrows(AgentFailure::class.java) {AgentConfig.parse(it)} }
    }
    @Test fun `original six arguments remain continuous and optional target is strict UUID`() {
        val file=java.nio.file.Files.writeString(root.resolve("input"),"test-only")
        val args=arrayOf("--server=https://localhost:8443","--tls-config=$file","--device=dev_1","--adb-config=$file","--apk=$file","--spool=${root.resolve("spool")}")
        assertNull(AgentConfig.parse(args).untilAttemptAcked)
        val target="01992560-aaab-7000-8000-123456789abc"
        assertEquals(target,AgentConfig.parse(args+"--until-attempt-acked=$target").untilAttemptAcked)
        assertThrows(AgentFailure::class.java) { AgentConfig.parse(args+"--until-attempt-acked=first") }
        assertThrows(AgentFailure::class.java) { AgentConfig.parse(args+arrayOf("--until-attempt-acked=$target","--until-attempt-acked=$target")) }
    }
}
