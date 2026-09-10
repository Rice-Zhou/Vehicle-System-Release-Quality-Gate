package com.ricezhou.vsrqg.agent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AgentCompletionTest {
    @TempDir lateinit var root:Path
    @Test fun `finite fixture waits for target durable ACK after server terminal and old replay does not finish target`() {
        val accepted=CountDownLatch(1);val release=CountDownLatch(1)
        val old="01992560-aaab-7000-8000-123456789abd"
        SmokeServer(root,onResultAccepted={ body ->
            if(body.path("attemptId").asText()!=old) {
                accepted.countDown();check(release.await(15,TimeUnit.SECONDS))
            }
        }).use { server ->
            val spool=root.resolve("spool")
            ExecutionJournal(spool).use { journal ->
                val request=Wire.parse(javaClass.getResourceAsStream("/contracts/examples/result-canonical-input.json")!!.readAllBytes()) as com.fasterxml.jackson.databind.node.ObjectNode
                request.put("attemptId",old);request.put("resultDigest",ResultDigest.digest(request))
                journal.save(JournalEntry(old,"cmd_old","lse_golden","boot_1",1,0,Phase.UPLOADED,setOf("ev_log","ev_png"),request.path("resultDigest").asText()))
                journal.write(old,"result.json",request)
            }
            fun json(name:String,value:Any)=Files.write(root.resolve(name),Wire.mapper.writeValueAsBytes(value))
            val apk=Files.write(root.resolve("fixture.apk"),byteArrayOf(1,2,3))
            (server.context.path("apk") as com.fasterxml.jackson.databind.node.ObjectNode).put("checksum",Wire.sha256(Files.readAllBytes(apk)))
            val password=Files.writeString(root.resolve("tls-password"),"fixture-only-password")
            val store=root.resolve("ephemeral-test.p12")
            val tls=json("tls.json",mapOf("keyStore" to store.toString(),"trustStore" to store.toString(),
                "keyStorePasswordFile" to password.toString(),"trustStorePasswordFile" to password.toString()))
            val adb=Files.writeString(root.resolve("unused-adb.json"),"{}")
            val environment=json("environment.json",server.context.path("environment"))
            val executable=Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            val builder=ProcessBuilder(executable.toString(),"-cp",System.getProperty("fixture.classpath"),M3FixtureAgentMain::class.java.name,
                "--server=${server.clientOrigin()}","--tls-config=$tls","--device=device_demo_01","--adb-config=$adb","--apk=$apk","--spool=$spool",
                "--until-attempt-acked=${server.id}").redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile())
            builder.environment()["VSRQG_M3_EXECUTION_MODE"]="CI_FIXTURE";builder.environment()["VSRQG_M3_ENVIRONMENT"]=environment.toString()
            val child=builder.start()
            try {
                assertTrue(accepted.await(10,TimeUnit.SECONDS),"target result must reach server after old replay")
                fun phase(id:String)=Wire.parse(SafeFiles.read(spool.resolve(id).resolve("journal.json"),Wire.MAX_BYTES)).path("phase").asText()
                assertEquals("RESULT_ACKED",phase(old));assertEquals("UPLOADED",phase(server.id))
                assertFalse(Files.exists(spool.resolve(server.id).resolve("result-receipt.json")))
                assertTrue(child.isAlive,"server acceptance must not finish the finite Agent")
                release.countDown();assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertEquals(0,child.exitValue())
                ExecutionJournal(spool).use { journal ->
                    assertEquals(Phase.RESULT_ACKED,journal.load(server.id)!!.phase)
                    assertEquals(journal.load(server.id)!!.resultDigest,journal.read(server.id,"result-receipt.json").path("resultDigest").asText())
                }
                assertEquals(2,server.creates)
            } finally { release.countDown();if(child.isAlive) {child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS)} }
        }
    }
}
