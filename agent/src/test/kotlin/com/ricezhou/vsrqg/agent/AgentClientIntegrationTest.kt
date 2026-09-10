package com.ricezhou.vsrqg.agent
import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import javax.net.ssl.*
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
class AgentClientIntegrationTest {
    @TempDir lateinit var root:Path
    @Test fun `mTLS required fixed paths same origin and redirects rejected`() {
        TestHttps(root).use { https ->
            val calls=AtomicInteger()
            https.server.createContext("/agent-api/v1/attempts/a/context") { x -> calls.incrementAndGet(); x.responseHeaders.add("Location",https.origin+"/outside"); x.sendResponseHeaders(302,-1);x.close() }
            val client=AgentClient(URI(https.origin),https.tls)
            assertEquals("HTTP_STATUS_302",assertThrows(AgentFailure::class.java) { client.call("GET","/agent-api/v1/attempts/a/context",null,null) }.code)
            listOf("http://localhost/a", "https://elsewhere/agent-api/v1/attempts/a/context", "//elsewhere/a", "/agent-api/v1/attempts/../a/context", "/agent-api/v1/attempts/%61/context", "/outside").forEach { path -> assertThrows(AgentFailure::class.java) { client.call("GET",path,null,null) } }
            assertEquals(1,calls.get())
            val noIdentity=SSLContext.getInstance("TLS").apply { init(null,https.trust.trustManagers,null) }
            assertThrows(AgentFailure::class.java) { AgentClient(URI(https.origin),noIdentity).call("GET","/agent-api/v1/attempts/a/context",null,null) }
            assertEquals(1,calls.get())
        }
    }
    @Test fun `upload failure is visible and same binary payload may be retried`() {
        TestHttps(root).use { https ->
            val bodies=mutableListOf<ByteArray>()
            https.server.createContext("/agent-api/v1/evidence/uploads/upl_1/payload") { x -> bodies.add(x.requestBody.readAllBytes()); x.sendResponseHeaders(if(bodies.size==1) 503 else 204,-1); x.close() }
            val bytes=byteArrayOf(0,-1,1,2,3);val file=root.resolve("payload");Files.write(file,bytes)
            val client=AgentClient(URI(https.origin),https.tls)
            assertEquals("HTTP_STATUS_503",assertThrows(AgentFailure::class.java) { client.putPayload("/agent-api/v1/evidence/uploads/upl_1/payload",file) }.code)
            client.putPayload("/agent-api/v1/evidence/uploads/upl_1/payload",file)
            assertEquals(2,bodies.size);bodies.forEach { assertArrayEquals(bytes,it) }
        }
    }
    @Test fun `HTTP total deadline and response byte limit include body streaming`() {
        TestHttps(root).use { https ->
            https.server.createContext("/agent-api/v1/attempts/slow/context") { x -> x.sendResponseHeaders(200,0);try { repeat(50) { x.responseBody.write(32);x.responseBody.flush();Thread.sleep(50) } } catch(_:java.io.IOException) {} finally { x.close() } }
            https.server.createContext("/agent-api/v1/attempts/large/context") { x -> val bytes=ByteArray(65537) {32};x.sendResponseHeaders(200,bytes.size.toLong());try { x.responseBody.write(bytes) } finally {x.close()} }
            assertThrows(AgentFailure::class.java) { AgentClient(URI(https.origin),https.tls,Duration.ofMillis(400)).call("GET","/agent-api/v1/attempts/slow/context",null,null) }
            assertThrows(AgentFailure::class.java) { AgentClient(URI(https.origin),https.tls).call("GET","/agent-api/v1/attempts/large/context",null,null) }
        }
    }
}
class TestHttps(root:Path):AutoCloseable {
    val tls:SSLContext
    val trust:TrustManagerFactory
    val server:HttpsServer
    val origin:String
    init {
        val storePath=root.resolve("ephemeral-test.p12")
        val password="fixture-only-password"
        val javaBin=Path.of(System.getProperty("java.home"),"bin")
        val proc=ProcessBuilder(javaBin.resolve(if(System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool").toString(),"-genkeypair","-alias","test","-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-validity","2","-ext","san=dns:localhost,ip:127.0.0.1","-keystore",storePath.toString(),"-storetype","PKCS12","-storepass:env","VSRQG_FIXTURE_PASSWORD").redirectErrorStream(true).redirectOutput(root.resolve("keytool.log").toFile()).apply { environment()["VSRQG_FIXTURE_PASSWORD"]=password }.start()
        check(proc.waitFor(15,java.util.concurrent.TimeUnit.SECONDS).also { if(!it) proc.destroyForcibly() });check(proc.exitValue()==0)
        val store=KeyStore.getInstance("PKCS12").apply { Files.newInputStream(storePath).use { load(it,password.toCharArray()) } }
        trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {init(store,password.toCharArray())}
        tls=SSLContext.getInstance("TLS").apply {init(keys.keyManagers,trust.trustManagers,null)}
        server=HttpsServer.create(InetSocketAddress("localhost",0),0)
        server.httpsConfigurator=object:HttpsConfigurator(tls) { override fun configure(params:HttpsParameters) { params.setSSLParameters(tls.defaultSSLParameters.apply { needClientAuth=true }) } }
        server.executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        server.start();origin="https://localhost:${server.address.port}"
    }
    override fun close() {server.stop(0);(server.executor as java.util.concurrent.ExecutorService).close()}
}
