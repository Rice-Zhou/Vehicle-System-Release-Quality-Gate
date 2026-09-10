package com.ricezhou.vsrqg.demo

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

@Timeout(60)
class M3BoundaryTest {
    @Test fun `bounded HTTP download rejects oversized actual response bytes`() {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/payload") { exchange ->
            exchange.sendResponseHeaders(200,0)
            exchange.responseBody.use { it.write(ByteArray(4097)) }
        }
        server.start()
        try {
            M3Http(URI("http://127.0.0.1:${server.address.port}"),SSLContext.getDefault()).use { http ->
                assertThatThrownBy { http.bytes("GET","/payload",null,200,4096) }.hasStackTraceContaining("HTTP_RESPONSE_LIMIT")
            }
        } finally { server.stop(0) }
    }
    @Test fun `HTTP redirects cannot forward identity to another origin`() {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/redirect") { exchange -> exchange.responseHeaders.add("Location","https://example.invalid");exchange.sendResponseHeaders(302,-1);exchange.close() }
        server.start()
        try {
            M3Http(URI("http://127.0.0.1:${server.address.port}"),SSLContext.getDefault(),"SYNTHETIC_SECRET").use { http ->
                assertThatThrownBy { http.bytes("GET","/redirect",null,200,4096) }.hasMessage("HTTP_STATUS_302")
                assertThatThrownBy { http.bytes("GET","https://example.invalid/payload",null,200,4096) }.hasMessage("HTTP_ORIGIN_INVALID")
            }
        } finally { server.stop(0) }
    }
    @Test fun `cleanup only stops owned child and leaves existing process running`() {
        val owned=sleeper();val existing=sleeper()
        try {
            M3AgentProcess(owned).use { assertThat(owned.isAlive).isTrue() }
            assertThat(owned.isAlive).isFalse()
            assertThat(existing.isAlive).isTrue()
        } finally { owned.destroyForcibly();existing.destroyForcibly();existing.waitFor(5,TimeUnit.SECONDS) }
    }
    @Test fun `child failure remains a visible failure`() {
        val javaExecutable=Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val process=ProcessBuilder(javaExecutable.toString(),"--invalid-m3-test-option").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        check(process.waitFor(5,TimeUnit.SECONDS))
        assertThatThrownBy { M3AgentProcess(process).use { it.checkRunning() } }.hasMessage("AGENT_PROCESS_FAILED")
    }
    @Test fun `diagnostic overflow still cleans owned running process`() {
        val process=sleeper("overflow")
        try {
            assertThatThrownBy {
                M3AgentProcess(process).use { child ->
                    val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
                    while(System.nanoTime()<deadline) { child.checkRunning();Thread.sleep(10) }
                    error("TEST_DIAGNOSTIC_NOT_OBSERVED")
                }
            }.hasMessage("AGENT_DIAGNOSTIC_LIMIT")
            assertThat(process.isAlive).isFalse()
        } finally { process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS) }
    }
    private fun sleeper(mode:String="idle"):Process {
        val javaExecutable=Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val classpath=listOf(M3Sleeper::class.java,Unit::class.java).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(java.io.File.pathSeparator)
        val process=ProcessBuilder(javaExecutable.toString(),"-cp",classpath,M3Sleeper::class.java.name,mode).start()
        try {
            check(java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readLine() }.get(5,TimeUnit.SECONDS)=="READY")
            return process
        } catch(error:Exception) { process.destroyForcibly();throw error }
    }
}
object M3Sleeper { @JvmStatic fun main(args:Array<String>) {
    println("READY");Thread.sleep(200)
    if(args.single()=="overflow") { System.out.write(ByteArray(65537));System.out.flush() }
    Thread.sleep(60000)
} }
