package com.ricezhou.vsrqg.agent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration
class AdbExecutorTest {
    private fun run(mode:String, limit:Long=1048576, timeout:Duration=Duration.ofSeconds(5)) = BoundedProcess().run(
        listOf(Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString(),
        "-cp", System.getProperty("fixture.classpath"),ProcessFixture::class.java.name, mode),timeout,limit)
    @Test fun `lease loss stops the real running subprocess`() {
        val permitted=java.util.concurrent.atomic.AtomicBoolean(true)
        val stop=Thread {Thread.sleep(300);permitted.set(false)};stop.start()
        val failure=assertThrows(AgentFailure::class.java) {BoundedProcess {permitted.get()}.run(
            listOf(Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString(),"-cp",System.getProperty("fixture.classpath"),ProcessFixture::class.java.name,"sleep"),Duration.ofSeconds(10),1024)}
        stop.join();assertEquals("LEASE_LOST",failure.code)
    }
    @Test fun `whitelist permits only the exact launch and UUID UI operations`() {
        val id="01992560-aaab-7000-8000-123456789abc";val adb=AdbExecutor(Path.of("missing-adb"),"EXPLICIT-SERIAL")
        listOf(listOf("shell","am","start","-W","-n","com.ricezhou.vsrqg.smoke/.SmokeActivity","--es","attemptId",id,"--es","mode","normal"),
            listOf("shell","uiautomator","dump","/data/local/tmp/vsrqg-smoke-$id.xml"),listOf("exec-out","cat","/data/local/tmp/vsrqg-smoke-$id.xml"),
            listOf("shell","rm","--","/data/local/tmp/vsrqg-smoke-$id.xml")).forEach {args ->
            assertEquals("PROCESS_START_FAILED",assertThrows(AgentFailure::class.java) {adb.run(args,Duration.ofSeconds(1),1024)}.code)
        }
    }
    @Test fun `binary stdout is preserved and stderr consumed concurrently`() {
        val out=run("binary")
        assertEquals(0,out.exitCode)
        assertArrayEquals(byteArrayOf(0,1,127,-128,-1),out.stdout)
        assertEquals("diagnostic",out.stderr.toString(Charsets.UTF_8))
    }
    @Test fun `screenshot whitelist allows only fixed UUID PNG capture read and cleanup`() {
        val remote="/data/local/tmp/vsrqg-smoke-01992560-aaab-7000-8000-123456789abc.png"
        val adb=AdbExecutor(Path.of("missing-adb"),"EXPLICIT-SERIAL")
        listOf(listOf("shell","screencap","-p",remote),listOf("exec-out","cat",remote),listOf("shell","rm","--",remote)).forEach {args ->
            assertEquals("PROCESS_START_FAILED",assertThrows(AgentFailure::class.java) {adb.run(args,Duration.ofSeconds(1),1024)}.code)
        }
    }
    @Test fun `screenshot whitelist denies old stdout capture arbitrary files and shell injection`() {
        val adb=AdbExecutor(Path.of("missing-adb"),"EXPLICIT-SERIAL")
        val remote="/data/local/tmp/vsrqg-smoke-01992560-aaab-7000-8000-123456789abc.png"
        assertEquals("ADB_COMMAND_DENIED",assertThrows(AgentFailure::class.java) {adb.run(listOf("exec-out","screencap","-p"),Duration.ofSeconds(1),1024)}.code)
        listOf("/sdcard/screenshot.png",remote+";reboot",remote+".other",remote.replace(".png","/../other.png"),remote.replace("01992560","invalid"),remote.replace("vsrqg-smoke-","other-")).forEach {path ->
            listOf(listOf("shell","screencap","-p",path),listOf("exec-out","cat",path),listOf("shell","rm","--",path)).forEach {args ->
                assertEquals("ADB_COMMAND_DENIED",assertThrows(AgentFailure::class.java) {adb.run(args,Duration.ofSeconds(1),1024)}.code)
            }
        }
        assertEquals("ADB_COMMAND_DENIED",assertThrows(AgentFailure::class.java) {adb.run(listOf("shell","screencap","-p",remote.replace(".png",".xml")),Duration.ofSeconds(1),1024)}.code)
    }
    @Test fun `timeout terminates owned subprocess`() { assertEquals("PROCESS_TIMEOUT",assertThrows(AgentFailure::class.java) { run("sleep",timeout=Duration.ofMillis(300)) }.code) }
    @Test fun `stdout and stderr limits abort instead of truncating success`() {
        assertEquals("PROCESS_OUTPUT_LIMIT",assertThrows(AgentFailure::class.java) { run("stdout",64) }.code)
        assertEquals("PROCESS_OUTPUT_LIMIT",assertThrows(AgentFailure::class.java) { run("stderr") }.code)
    }
    @Test fun `nonzero result fails visibly`() { assertEquals("PROCESS_EXIT_NONZERO",assertThrows(AgentFailure::class.java) { run("exit") }.code) }
    @Test fun `device selector and arbitrary commands are rejected before process start`() {
        assertThrows(AgentFailure::class.java) { AdbExecutor(Path.of("missing"),"first;reboot") }
        val adb=AdbExecutor(Path.of("missing"),"EXPLICIT-SERIAL")
        listOf(listOf("devices"),listOf("kill-server"),listOf("shell","rm","-rf","/"),listOf("shell","am","start","-n","evil/.Activity")).forEach {
            assertThrows(AgentFailure::class.java) { adb.run(it,Duration.ofSeconds(1),1024) }
        }
    }
}
object ProcessFixture {
    @JvmStatic fun main(args:Array<String>) {
        when(args[0]) {
            "binary" -> { System.out.write(byteArrayOf(0,1,127,-128,-1)); System.err.print("diagnostic") }
            "sleep" -> Thread.sleep(10000)
            "stdout" -> repeat(10000) { System.out.print("0123456789") }
            "stderr" -> repeat(200000) { System.err.print("0123456789") }
            "exit" -> kotlin.system.exitProcess(7)
        }
    }
}
