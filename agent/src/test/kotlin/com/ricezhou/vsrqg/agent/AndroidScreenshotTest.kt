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
    @ParameterizedTest @ValueSource(strings=["25","invalid"])
    fun `first preflight SDK failure keeps current attempt bound for screenshot`(sdk:String) {
        val calls=mutableListOf<List<String>>()
        val device=device(calls,bindInstalled=false,sdk=sdk)
        assertEquals(if(sdk=="25") "DEVICE_API_LEVEL_UNSUPPORTED" else "DEVICE_API_LEVEL_INVALID",
            assertThrows(AgentFailure::class.java) {device.preflight(context(id))}.code)
        assertEquals(listOf(listOf("shell","getprop","ro.build.version.sdk")),calls)
        calls.clear()
        assertArrayEquals(png,device.screenshot())
        assertEquals(listOf(foreground,capture,read,cleanup),calls)
    }
    @Test fun `next attempt early preflight failure replaces previous screenshot binding`() {
        val calls=mutableListOf<List<String>>()
        val next="01992560-aaab-7000-8000-123456789abd"
        val device=device(calls,screenshotAttempt=next)
        assertEquals("DEVICE_API_LEVEL_UNSUPPORTED",assertThrows(AgentFailure::class.java) {device.preflight(context(next))}.code)
        calls.clear()
        assertArrayEquals(png,device.screenshot())
        val nextRemote="/data/local/tmp/vsrqg-smoke-01992560-aaab-7000-8000-123456789abd.png"
        assertEquals(listOf(foreground,listOf("shell","screencap","-p",nextRemote),
            listOf("exec-out","cat",nextRemote),listOf("shell","rm","--",nextRemote)),calls)
    }
    @Test fun `unbound screenshot fails explicitly before any device operation`() {
        val calls=mutableListOf<List<String>>()
        val device=device(calls,bindInstalled=false)
        assertEquals("ATTEMPT_BINDING_REQUIRED",assertThrows(AgentFailure::class.java) {device.screenshot()}.code)
        assertTrue(calls.isEmpty())
    }
    @Test fun `invalid next context clears previous binding before any device operation`() {
        val calls=mutableListOf<List<String>>()
        val device=device(calls)
        assertEquals("ATTEMPT_INVALID",assertThrows(AgentFailure::class.java) {device.preflight(context("$id;reboot"))}.code)
        assertEquals("ATTEMPT_BINDING_REQUIRED",assertThrows(AgentFailure::class.java) {device.screenshot()}.code)
        assertTrue(calls.isEmpty())
    }
    private fun context(attemptId:String)=Wire.parse(javaClass.getResourceAsStream("/contracts/examples/execution-context.json")!!.readAllBytes())
        .deepCopy<ObjectNode>().put("attemptId",attemptId)
    private fun device(calls:MutableList<List<String>>,resumed:Boolean=true,failed:String?=null,cleanupFails:Boolean=false,
        bindInstalled:Boolean=true,sdk:String="25",screenshotAttempt:String=id):AndroidSmokeDevice {
        val apk=Files.write(root.resolve("source.apk"),byteArrayOf(1,2,3))
        val context=context(id)
        (context.path("apk") as ObjectNode).put("checksum",ApkInspector.checksum(apk))
        Files.createDirectory(root.resolve(id))
        val expectedRemote="/data/local/tmp/vsrqg-smoke-$screenshotAttempt.png"
        val commands=AdbCommands {args,timeout,limit ->
            calls.add(args)
            val bytes=when(args) {
                listOf("shell","pm","path",SmokeAssertions.PACKAGE) -> "package:/data/app/test/base.apk".toByteArray()
                listOf("exec-out","cat","/data/app/test/base.apk") -> Files.readAllBytes(apk)
                listOf("shell","getprop","ro.build.version.sdk") -> sdk.toByteArray()
                foreground -> if(resumed) "topResumedActivity=ActivityRecord{abc u0 com.ricezhou.vsrqg.smoke/.SmokeActivity t3}".toByteArray() else "mPausedActivity: ActivityRecord{abc u0 com.ricezhou.vsrqg.smoke/.SmokeActivity t3}".toByteArray()
                capture,listOf("shell","screencap","-p",expectedRemote) -> {
                    assertEquals(Duration.ofSeconds(10),timeout);assertEquals(1048576L,limit)
                    if(failed=="capture") throw AgentFailure("TEST_CAPTURE_FAILED")
                    "SurfaceFlinger diagnostic\n".toByteArray()+png
                }
                read,listOf("exec-out","cat",expectedRemote) -> {
                    assertEquals(Duration.ofSeconds(10),timeout);assertEquals(8388608L,limit)
                    if(failed=="read") throw AgentFailure("TEST_READ_FAILED")
                    png
                }
                cleanup,listOf("shell","rm","--",expectedRemote) -> {
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
        if(bindInstalled) device.verifyInstalled(context)
        calls.clear()
        return device
    }
}
