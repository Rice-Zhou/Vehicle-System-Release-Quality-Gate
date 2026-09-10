package com.ricezhou.vsrqg.agent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
class JournalTest {
    @TempDir lateinit var root:Path
    private val id="01992560-aaab-7000-8000-123456789abc"
    @Test fun `Windows junction config and output parent are rejected`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val target=Files.createDirectory(root.resolve("target"));val link=root.resolve("link")
        val log=root.resolve("junction.log")
        val proc=ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-Command","New-Item -ItemType Junction -Path $"+"env:VSRQG_TEST_LINK -Target $"+"env:VSRQG_TEST_TARGET | Out-Null")
            .redirectErrorStream(true).redirectOutput(log.toFile()).apply {environment()["VSRQG_TEST_LINK"]=link.toString();environment()["VSRQG_TEST_TARGET"]=target.toString()}.start()
        assertTrue(proc.waitFor(10,java.util.concurrent.TimeUnit.SECONDS).also {if(!it) proc.destroyForcibly()});assertEquals(0,proc.exitValue())
        assertThrows(AgentFailure::class.java) {ExecutionJournal(link.resolve("child"))}
        assertFalse(Files.exists(target.resolve("child")))
    }
    @Test fun `fractional fencing token in journal is corruption not a new lease`() {
        ExecutionJournal(root).use {journal ->
            journal.save(JournalEntry(id,"cmd_1","lse_1","boot_1",1,0,Phase.ACKED,emptySet(),null))
            val path=root.resolve(id).resolve("journal.json")
            Files.writeString(path,Files.readString(path).replace("\"fencingToken\":1","\"fencingToken\":1.5"))
            assertEquals("JOURNAL_CORRUPT",assertThrows(AgentFailure::class.java) {journal.load(id)}.code)
        }
    }
    @Test fun `journal survives reopen and corruption is never treated as absent`() {
        val entry=JournalEntry(id,"cmd_1","lse_1","boot_1",1,0,Phase.INSTALL_INTENT,emptySet(),null)
        ExecutionJournal(root).use { journal -> journal.save(entry); assertEquals(entry,journal.load(id)) }
        ExecutionJournal(root).use { journal -> assertEquals(entry,journal.load(id)); Files.writeString(root.resolve(id).resolve("journal.json"),"{broken"); assertEquals("JOURNAL_CORRUPT",assertThrows(AgentFailure::class.java) { journal.load(id) }.code) }
    }
    @Test fun `second process cannot hold same device lock across spools`() {
        DeviceLock.acquire(root,"EXPLICIT-SERIAL").use {
            val result=BoundedProcess().run(listOf(Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString(),"-cp",System.getProperty("fixture.classpath"),LockFixture::class.java.name, root.toString()),java.time.Duration.ofSeconds(10),4096)
            assertEquals("DEVICE_LOCKED",result.stdout.toString(Charsets.UTF_8))
        }
        DeviceLock.acquire(root,"EXPLICIT-SERIAL").close()
    }
    @Test fun `journal traversal and duplicate owners are rejected`() {
        ExecutionJournal(root).use { journal ->
            assertThrows(AgentFailure::class.java) { journal.load("../escape") }
            assertThrows(AgentFailure::class.java) { ExecutionJournal(root) }
        }
    }
    @Test fun `unsafe output is rejected`() {
        val ordinary=root.resolve("ordinary"); Files.writeString(ordinary,"file")
        assertThrows(AgentFailure::class.java) { ExecutionJournal(ordinary) }
    }
}
object LockFixture {
    @JvmStatic fun main(args:Array<String>) {
        try { DeviceLock.acquire(Path.of(args[0]),"EXPLICIT-SERIAL").use { error("second process acquired lock") } }
        catch(e:AgentFailure) { if(e.code!="DEVICE_LOCKED") throw e; print(e.code) }
    }
}
