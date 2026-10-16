package com.ricezhou.vsrqg.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class M3FixturePngInputTest {
    @TempDir lateinit var root:Path

    @Test fun `invalid PNG input reaches real collector and durable ERROR ACK with LOG only`() {
        withFixture("PNG_INVALID") { server, spool, exit, output ->
            assertEquals(0,exit,output)
            assertNotNull(server.result)
            val result=server.result!!
            assertEquals("ERROR",result.path("status").asText())
            assertEquals("PNG_INVALID",result.path("reasonCode").asText())
            assertEquals(listOf("ev_LOG"),result.path("evidenceIds").map { it.asText() })
            assertEquals(setOf("LOG"),server.declarations.keys)
            assertEquals(setOf("LOG"),server.payloads.keys)
            val log=server.payloads.getValue("LOG").toString(Charsets.UTF_8)
            assertTrue(log.contains("INSTALL_CONFIRMED"))
            assertTrue(log.contains("SMOKE_ASSERTIONS_CONFIRMED"))
            assertFalse(Files.exists(spool.resolve(server.id).resolve("screenshot.png")))
            ExecutionJournal(spool).use { journal ->
                assertEquals(Phase.RESULT_ACKED,journal.load(server.id)!!.phase)
                assertEquals(setOf("ev_LOG"),journal.load(server.id)!!.evidenceIds)
                assertEquals("PNG_INVALID",journal.read(server.id,"observation.json").path("reasonCode").asText())
                assertEquals(result,journal.read(server.id,"result-receipt.json"))
            }
        }
    }

    @Test fun `default fixture still completes PASS with both Evidence payloads`() {
        withFixture(null) { server, _, exit, output ->
            assertEquals(0,exit,output)
            assertEquals("PASS",server.result!!.path("status").asText())
            assertEquals(setOf("LOG","SCREENSHOT"),server.payloads.keys)
        }
    }

    @ParameterizedTest
    @ValueSource(strings=["PNG_INVALD", ""])
    fun `unknown or blank screenshot input is rejected before protocol activity`(input:String) {
        withFixture(input) { server, _, exit, output ->
            assertEquals(1,exit,output)
            assertEquals("FIXTURE_SCREENSHOT_INPUT_INVALID",output.trim())
            assertNull(server.result)
            assertTrue(server.events.isEmpty())
            assertTrue(server.declarations.isEmpty())
            assertEquals(0,server.heartbeats)
        }
    }

    private fun withFixture(input:String?, verify:(SmokeServer,Path,Int,String)->Unit) {
        SmokeServer(root).use { server ->
            fun json(name:String,value:Any)=Files.write(root.resolve(name),Wire.mapper.writeValueAsBytes(value))
            val apk=Files.write(root.resolve("fixture.apk"),byteArrayOf(1,2,3))
            (server.context.path("apk") as ObjectNode).put("checksum",Wire.sha256(Files.readAllBytes(apk)))
            val password=Files.writeString(root.resolve("tls-password"),"fixture-only-password")
            val store=root.resolve("ephemeral-test.p12")
            val tls=json("tls.json",mapOf("keyStore" to store.toString(),"trustStore" to store.toString(),
                "keyStorePasswordFile" to password.toString(),"trustStorePasswordFile" to password.toString()))
            val adb=Files.writeString(root.resolve("unused-adb.json"),"{}")
            val environment=json("environment.json",server.context.path("environment"))
            val spool=root.resolve("spool")
            val output=root.resolve("child.log")
            val java=Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            val builder=ProcessBuilder(java.toString(),"-cp",System.getProperty("fixture.classpath"),M3FixtureAgentMain::class.java.name,
                "--server=${server.clientOrigin()}","--tls-config=$tls","--device=device_demo_01","--adb-config=$adb","--apk=$apk","--spool=$spool",
                "--until-attempt-acked=${server.id}").redirectErrorStream(true).redirectOutput(output.toFile())
            builder.environment()["VSRQG_M3_EXECUTION_MODE"]="CI_FIXTURE"
            builder.environment()["VSRQG_M3_ENVIRONMENT"]=environment.toString()
            builder.environment().remove("VSRQG_M3_SCREENSHOT_INPUT")
            if(input!=null) builder.environment()["VSRQG_M3_SCREENSHOT_INPUT"]=input
            val child=builder.start()
            try {
                assertTrue(child.waitFor(20,TimeUnit.SECONDS),"fixture timeout; inspect $output")
                verify(server,spool,child.exitValue(),Files.readString(output))
            } finally {
                if(child.isAlive) {child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS)}
            }
        }
    }
}
