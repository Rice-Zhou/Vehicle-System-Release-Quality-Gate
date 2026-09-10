package com.ricezhou.vsrqg.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class AndroidScreenshotTest {
    @TempDir lateinit var root:Path
    private val id="01992560-aaab-7000-8000-123456789abc"
    private val remote="/data/local/tmp/vsrqg-smoke-$id.png"
    private val foreground=listOf("shell","dumpsys","activity","activities")
    private val capture=listOf("shell","screencap","-p",remote)
    private val read=listOf("exec-out","cat",remote)
    private val cleanup=listOf("shell","rm","--",remote)
    private val png=byteArrayOf(-119,80,78,71,13,10,26,10,0,-1,-128)

    @Test fun `screenshot reads exact file bytes despite contaminated screencap stdout`() {
        val calls=mutableListOf<List<String>>()
        val device=device(calls)
        assertArrayEquals(png,device.screenshot())
        assertEquals(listOf(foreground,capture,read,cleanup),calls)
    }
    @Test fun `nonforeground device never writes or reads screenshot file`() {
        val calls=mutableListOf<List<String>>()
        val device=device(calls,resumed=false)
        assertEquals("SCREENSHOT_FOREGROUND_REQUIRED",assertThrows(AgentFailure::class.java) {device.screenshot()}.code)
        assertEquals(listOf(foreground),calls)
    }
    @ParameterizedTest @ValueSource(strings=["capture","read"])
    fun `capture or binary read failure still removes only this attempt file`(failed:String) {
        val calls=mutableListOf<List<String>>()
        val device=device(calls,failed=failed)
        assertEquals("TEST_${failed.uppercase()}_FAILED",assertThrows(AgentFailure::class.java) {device.screenshot()}.code)
        assertEquals(if(failed=="capture") listOf(foreground,capture,cleanup) else listOf(foreground,capture,read,cleanup),calls)
    }
    @Test fun `cleanup failure cannot return successful screenshot`() {
        val calls=mutableListOf<List<String>>()
        val device=device(calls,cleanupFails=true)
        assertEquals("TEST_CLEANUP_FAILED",assertThrows(AgentFailure::class.java) {device.screenshot()}.code)
        assertEquals(listOf(foreground,capture,read,cleanup),calls)
    }
    @Test fun `read and cleanup failures both remain visible`() {
        val calls=mutableListOf<List<String>>()
        val device=device(calls,failed="read",cleanupFails=true)
        val failure=assertThrows(AgentFailure::class.java) {device.screenshot()}
        assertEquals("TEST_READ_FAILED",failure.code)
        assertEquals(listOf("TEST_CLEANUP_FAILED"),failure.suppressed.map {(it as AgentFailure).code})
        assertEquals(listOf(foreground,capture,read,cleanup),calls)
    }
    private fun device(calls:MutableList<List<String>>,resumed:Boolean=true,failed:String?=null,cleanupFails:Boolean=false):AndroidSmokeDevice {
        val apk=Files.write(root.resolve("source.apk"),byteArrayOf(1,2,3))
        val context=Wire.parse(javaClass.getResourceAsStream("/contracts/examples/execution-context.json")!!.readAllBytes()).deepCopy<ObjectNode>()
        context.put("attemptId",id)
        (context.path("apk") as ObjectNode).put("checksum",ApkInspector.checksum(apk))
        Files.createDirectory(root.resolve(id))
        val commands=AdbCommands {args,timeout,limit ->
            calls.add(args)
            val bytes=when(args) {
                listOf("shell","pm","path",SmokeAssertions.PACKAGE) -> "package:/data/app/test/base.apk".toByteArray()
                listOf("exec-out","cat","/data/app/test/base.apk") -> Files.readAllBytes(apk)
                foreground -> if(resumed) "topResumedActivity=ActivityRecord{abc u0 com.ricezhou.vsrqg.smoke/.SmokeActivity t3}".toByteArray() else "mPausedActivity: ActivityRecord{abc u0 com.ricezhou.vsrqg.smoke/.SmokeActivity t3}".toByteArray()
                capture -> {
                    assertEquals(Duration.ofSeconds(10),timeout);assertEquals(1048576L,limit)
                    if(failed=="capture") throw AgentFailure("TEST_CAPTURE_FAILED")
                    "SurfaceFlinger diagnostic\n".toByteArray()+png
                }
                read -> {
                    assertEquals(Duration.ofSeconds(10),timeout);assertEquals(8388608L,limit)
                    if(failed=="read") throw AgentFailure("TEST_READ_FAILED")
                    png
                }
                cleanup -> {
                    assertEquals(Duration.ofSeconds(5),timeout);assertEquals(1048576L,limit)
                    if(cleanupFails) throw AgentFailure("TEST_CLEANUP_FAILED")
                    byteArrayOf()
                }
                listOf("exec-out","screencap","-p") -> "SurfaceFlinger diagnostic\n".toByteArray()+png
                else -> error("Unexpected device operation: $args")
            }
            CommandOutput(0,bytes,byteArrayOf())
        }
        val device=AndroidSmokeDevice(commands,ApkInspection {ApkIdentity(context.path("apk").path("versionCode").asInt(),context.path("apk").path("signingCertificateSha256").asText())},apk,root)
        device.verifyInstalled(context)
        calls.clear()
        return device
    }
}
