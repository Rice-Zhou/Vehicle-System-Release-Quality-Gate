package com.ricezhou.vsrqg.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class PackagePreflightTest {
    @TempDir lateinit var root:Path
    private val calls=mutableListOf<List<String>>()
    private val inspected=mutableListOf<String>()
    private fun context():ObjectNode=Wire.parse(javaClass.getResourceAsStream("/contracts/examples/execution-context.json")!!.readAllBytes()).deepCopy<ObjectNode>().also {
        (it.path("environment") as ObjectNode).put("bootSessionId","01992560-aaab-7000-8000-123456789abc")
    }
    private fun device(mode:String,context:ObjectNode,conflictingSigner:Boolean=false):AndroidSmokeDevice {
        val source=Files.write(root.resolve("fixture.apk"),"bounded test APK bytes".toByteArray())
        (context.path("apk") as ObjectNode).put("checksum",ApkInspector.checksum(source))
        val environment=context.path("environment")
        val commands=AdbCommands {args,timeout,limit ->
            calls.add(args)
            if(args==listOf("shell","pm","path",SmokeAssertions.PACKAGE)) {
                BoundedProcess().run(listOf(Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString(),
                    "-cp",System.getProperty("fixture.classpath"),PackagePathFixture::class.java.name,mode),timeout,limit)
            } else {
                val output=when {
                    args==listOf("shell","getprop","ro.build.version.sdk") -> "26"
                    args==listOf("get-state") -> "device"
                    args==listOf("shell","cat","/proc/sys/kernel/random/boot_id") -> environment.path("bootSessionId").asText()
                    args==listOf("shell","getprop","ro.build.id") -> environment.path("buildId").asText()
                    args==listOf("shell","getprop","ro.build.fingerprint") -> environment.path("buildFingerprint").asText()
                    args==listOf("exec-out","cat","/data/app/fixture/base.apk") -> Files.readString(source)
                    args.take(2)==listOf("install","-r") -> "Success"
                    else -> error("unexpected operation")
                }
                CommandOutput(0,output.toByteArray(),byteArrayOf())
            }
        }
        val inspection=ApkInspection {path ->
            inspected.add(path.fileName.toString())
            ApkIdentity(1,if(conflictingSigner && path.fileName.toString()=="installed-before.apk") "sha256:"+"0".repeat(64) else context.path("apk").path("signingCertificateSha256").asText())
        }
        return AndroidSmokeDevice(commands,inspection,source,root.resolve("spool"))
    }
    @Test fun `real exit one empty package lookup permits clean preflight and installation`() {
        val context=context();val device=device("absent",context)
        device.preflight(context);device.install()
        assertEquals(listOf("source.apk"),inspected)
        assertEquals(1,calls.count {it.take(2)==listOf("install","-r")})
        assertFalse(calls.any {it.take(2)==listOf("exec-out","cat")})
    }
    @Test fun `real unique package path requires existing signature check before installation`() {
        val context=context();val device=device("present",context)
        device.preflight(context);device.install()
        assertEquals(listOf("source.apk","installed-before.apk"),inspected)
        assertTrue(calls.indexOfFirst {it.take(2)==listOf("exec-out","cat")} < calls.indexOfFirst {it.take(2)==listOf("install","-r")})
        assertEquals(1,calls.count {it.take(2)==listOf("install","-r")})
    }
    @ParameterizedTest
    @CsvSource("permission,PROCESS_EXIT_NONZERO", "connection,PROCESS_EXIT_NONZERO", "stdout-error,PROCESS_EXIT_NONZERO", "other-exit,PROCESS_EXIT_NONZERO", "whitespace,PROCESS_EXIT_NONZERO", "empty-success,APK_BASE_UNAVAILABLE", "stderr-success,APK_BASE_UNAVAILABLE", "split,APK_BASE_UNAVAILABLE", "unknown,APK_BASE_UNAVAILABLE")
    fun `real ambiguous or failed package query prevents installation`(mode:String,code:String) {
        val context=context();val device=device(mode,context)
        val failure=assertThrows(AgentFailure::class.java) {device.preflight(context);device.install()}
        assertEquals(code,failure.code)
        assertFalse(failure.toString().contains("permission denied"))
        assertFalse(failure.toString().contains("device offline"))
        assertFalse(calls.any {it.take(2)==listOf("install","-r")})
        assertEquals(listOf("source.apk"),inspected)
    }
    @Test fun `present package signature conflict still prevents installation`() {
        val context=context();val device=device("present",context,true)
        assertEquals("APK_SIGNATURE_CONFLICT",assertThrows(AgentFailure::class.java) {device.preflight(context);device.install()}.code)
        assertFalse(calls.any {it.take(2)==listOf("install","-r")})
    }
    @Test fun `generic process runner still rejects the same absent exit`() {
        val java=Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        assertEquals("PROCESS_EXIT_NONZERO",assertThrows(AgentFailure::class.java) {
            BoundedProcess().run(listOf(java.toString(),"-cp",System.getProperty("fixture.classpath"),PackagePathFixture::class.java.name,"absent"),Duration.ofSeconds(5),1024)
        }.code)
    }
}

object PackagePathFixture {
    @JvmStatic fun main(args:Array<String>) {
        when(args.single()) {
            "absent" -> kotlin.system.exitProcess(1)
            "present" -> println("package:/data/app/fixture/base.apk")
            "permission" -> {System.err.print("Error: permission denied");kotlin.system.exitProcess(1)}
            "connection" -> {System.err.print("error: device offline");kotlin.system.exitProcess(1)}
            "stdout-error" -> {print("Error: permission denied");kotlin.system.exitProcess(1)}
            "other-exit" -> kotlin.system.exitProcess(2)
            "whitespace" -> {print("\n");kotlin.system.exitProcess(1)}
            "empty-success" -> Unit
            "stderr-success" -> {println("package:/data/app/fixture/base.apk");System.err.print("unexpected diagnostic")}
            "split" -> {println("package:/data/app/fixture/base.apk");println("package:/data/app/fixture/split.apk")}
            "unknown" -> println("unknown package output")
            else -> error("unknown fixture mode")
        }
    }
}
