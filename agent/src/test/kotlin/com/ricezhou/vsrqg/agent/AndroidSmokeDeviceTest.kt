package com.ricezhou.vsrqg.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import java.time.Duration

class AndroidSmokeDeviceTest {
    @TempDir lateinit var root:Path
    @ParameterizedTest
    @CsvSource("25,DEVICE_API_LEVEL_UNSUPPORTED", "0,DEVICE_API_LEVEL_INVALID", "'',DEVICE_API_LEVEL_INVALID", "abc,DEVICE_API_LEVEL_INVALID", "26.5,DEVICE_API_LEVEL_INVALID", "-1,DEVICE_API_LEVEL_INVALID")
    fun `unsupported or malformed API Level is rejected before APK inspection or install`(sdk:String,code:String) {
        val calls=mutableListOf<List<String>>()
        val context=Wire.parse(javaClass.getResourceAsStream("/contracts/examples/execution-context.json")!!.readAllBytes()).deepCopy<ObjectNode>()
        val boot="01992560-aaab-7000-8000-123456789abc"
        (context.path("environment") as ObjectNode).put("bootSessionId",boot)
        val commands=AdbCommands {args,_,_ ->
            calls.add(args)
            val output=when(args) {
                listOf("shell","getprop","ro.build.version.sdk") -> sdk
                listOf("get-state") -> "device"
                listOf("shell","cat","/proc/sys/kernel/random/boot_id") -> boot
                listOf("shell","getprop","ro.build.id") -> context.path("environment").path("buildId").asText()
                listOf("shell","getprop","ro.build.fingerprint") -> context.path("environment").path("buildFingerprint").asText()
                else -> error("unexpected device operation")
            }
            CommandOutput(0,output.toByteArray(),byteArrayOf())
        }
        val device=AndroidSmokeDevice(commands,ApkInspector(root.resolve("unused-aapt"),root.resolve("unused-signer"),BoundedProcess()),root.resolve("missing.apk"),root)
        assertEquals(code,assertThrows(AgentFailure::class.java) {device.preflight(context)}.code)
        assertEquals(listOf(listOf("shell","getprop","ro.build.version.sdk")),calls)
    }
    @ParameterizedTest
    @CsvSource("bootSessionId", "buildId", "buildFingerprint", "unchanged")
    fun `environment recheck reads current device identities without APK or install actions`(changed:String) {
        val context=Wire.parse(javaClass.getResourceAsStream("/contracts/examples/execution-context.json")!!.readAllBytes()).deepCopy<ObjectNode>()
        val expected=context.path("environment") as ObjectNode
        expected.put("bootSessionId","01992560-aaab-7000-8000-123456789abc")
        val actual=expected.deepCopy()
        if(changed!="unchanged") actual.put(changed,if(changed=="bootSessionId") "01992560-aaab-7000-8000-123456789abd" else "changed")
        val calls=mutableListOf<List<String>>()
        val commands=AdbCommands {args,_,_ ->
            calls.add(args)
            val output=when(args) {
                listOf("get-state") -> "device"
                listOf("shell","cat","/proc/sys/kernel/random/boot_id") -> actual.path("bootSessionId").asText()
                listOf("shell","getprop","ro.build.id") -> actual.path("buildId").asText()
                listOf("shell","getprop","ro.build.fingerprint") -> actual.path("buildFingerprint").asText()
                else -> error("environment verification performed a non-environment operation")
            }
            CommandOutput(0,output.toByteArray(),byteArrayOf())
        }
        val device=AndroidSmokeDevice(commands,ApkInspector(root.resolve("unused-aapt"),root.resolve("unused-signer"),BoundedProcess()),root.resolve("unused.apk"),root)
        if(changed=="unchanged") {device.verifyEnvironment(context);assertEquals(4,calls.size)}
        else assertEquals("ENVIRONMENT_IDENTITY_CHANGED",assertThrows(AgentFailure::class.java) {device.verifyEnvironment(context)}.code)
    }
    @ParameterizedTest
    @CsvSource("26", "35")
    fun `supported API Level proceeds to the independent environment prerequisite`(sdk:String) {
        val calls=mutableListOf<List<String>>()
        val device=AndroidSmokeDevice(AdbCommands {args,_,_ ->
            calls.add(args)
            when(args) {
                listOf("shell","getprop","ro.build.version.sdk") -> CommandOutput(0,sdk.toByteArray(),byteArrayOf())
                listOf("get-state") -> throw AgentFailure("DEVICE_DISCONNECTED")
                else -> error("unexpected action")
            }
        },ApkInspector(root.resolve("unused-aapt"),root.resolve("unused-signer"),BoundedProcess()),root.resolve("unused.apk"),root)
        val context=Wire.parse(javaClass.getResourceAsStream("/contracts/examples/execution-context.json")!!.readAllBytes())
        assertEquals("DEVICE_DISCONNECTED",assertThrows(AgentFailure::class.java) {device.preflight(context)}.code)
        assertEquals(listOf(listOf("shell","getprop","ro.build.version.sdk"),listOf("get-state")),calls)
    }
    @Test fun `only fixed SDK property is allowed by ADB whitelist`() {
        val adb=AdbExecutor(root.resolve("missing-adb"),"EXPLICIT-SERIAL")
        assertEquals("PROCESS_START_FAILED",assertThrows(AgentFailure::class.java) {adb.run(listOf("shell","getprop","ro.build.version.sdk"),Duration.ofSeconds(1),1024)}.code)
        assertEquals("ADB_COMMAND_DENIED",assertThrows(AgentFailure::class.java) {adb.run(listOf("shell","getprop","ro.build.version.sdk;reboot"),Duration.ofSeconds(1),1024)}.code)
    }
}
