package com.ricezhou.vsrqg.testmanagement

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** SSL setup is separate from the shared PostgreSQL dynamic property authority and pool budget. */
class AgentTlsTestInitializer : org.springframework.context.ApplicationContextInitializer<org.springframework.context.ConfigurableApplicationContext> {
    override fun initialize(context: org.springframework.context.ConfigurableApplicationContext) {
        val properties = linkedMapOf<String, String>()
        TestAgentCertificates.configureTls { name, supplier -> properties[name] = supplier.get().toString() }
        org.springframework.boot.test.util.TestPropertyValues.of(properties).applyTo(context.environment)
    }
}

/** Ephemeral test identities only; no repository or developer credentials are read. */
object TestAgentCertificates {
    private val directory = Files.createTempDirectory("vsrqg-agent-tls-")
    val password: String = UUID.randomUUID().toString()
    val serverStore: Path = directory.resolve("server.p12")
    val trustStore: Path = directory.resolve("trust.p12")
    private val keytool = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool").toString()

    init {
        generate("ca", "bc=ca:true")
        run("-exportcert", "-alias", "ca", "-keystore", store("ca").toString(), "-file", file("ca.crt"))
        run("-importcert", "-noprompt", "-alias", "ca", "-keystore", trustStore.toString(), "-file", file("ca.crt"))
        signed("server", false, false)
        signed("trusted", true, false)
        signed("expired", true, true)
        generate("untrusted", "eku=clientAuth")
        Runtime.getRuntime().addShutdownHook(Thread {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        })
    }

    val trustedCertificate: X509Certificate get() = certificate("trusted")
    fun configureTls(registry: org.springframework.test.context.DynamicPropertyRegistry) {
        registry.add("vsrqg.demo.agent-registration.enabled") { "true" }
        registry.add("server.address") { "127.0.0.1" }
        registry.add("server.ssl.enabled") { "true" }
        registry.add("server.ssl.key-store") { serverStore.toUri().toString() }
        registry.add("server.ssl.key-store-password") { password }
        registry.add("server.ssl.key-store-type") { "PKCS12" }
        registry.add("server.ssl.trust-store") { trustStore.toUri().toString() }
        registry.add("server.ssl.trust-store-password") { password }
        registry.add("server.ssl.trust-store-type") { "PKCS12" }
        registry.add("server.ssl.client-auth") { "want" }
    }
    fun certificate(alias: String) = load(store(alias)).getCertificate(alias) as X509Certificate

    fun sslContext(identity: String?): SSLContext {
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(load(trustStore)) }
        val keys = identity?.let {
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(load(store(it)), password.toCharArray()) }.keyManagers.map { manager ->
                if (manager is javax.net.ssl.X509ExtendedKeyManager) ForcedClientAlias(manager, it) else manager
            }.toTypedArray()
        }
        return SSLContext.getInstance("TLS").apply { init(keys, trust.trustManagers, null) }
    }

    private fun signed(alias: String, client: Boolean, expired: Boolean) {
        generate(alias, if (client) "eku=clientAuth" else "san=dns:localhost,ip:127.0.0.1")
        run("-certreq", "-alias", alias, "-keystore", store(alias).toString(), "-file", file("$alias.csr"))
        val extra = if (expired) arrayOf("-startdate", "2020/01/01 00:00:00", "-validity", "1") else arrayOf("-validity", "2")
        run("-gencert", "-alias", "ca", "-keystore", store("ca").toString(), "-infile", file("$alias.csr"), "-outfile", file("$alias.crt"), "-ext", if (client) "eku=clientAuth" else "eku=serverAuth", "-ext", if (client) "ku=digitalSignature" else "san=dns:localhost,ip:127.0.0.1", *extra)
        run("-importcert", "-noprompt", "-alias", "ca", "-keystore", store(alias).toString(), "-file", file("ca.crt"))
        run("-importcert", "-alias", alias, "-keystore", store(alias).toString(), "-file", file("$alias.crt"))
    }

    private fun generate(alias: String, extension: String) = run("-genkeypair", "-alias", alias, "-keystore", store(alias).toString(), "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=$alias", "-validity", "2", "-ext", extension)
    private fun file(name: String) = directory.resolve(name).toString()
    private fun store(alias: String) = directory.resolve("$alias.p12")
    private fun load(path: Path) = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(path).use { load(it, password.toCharArray()) } }

    private fun run(vararg args: String) {
        val log = Files.createTempFile(directory, "keytool-", ".log")
        val process = ProcessBuilder(listOf(keytool) + args + listOf("-storetype", "PKCS12", "-storepass:env", "VSRQG_TEST_KEY_PASSWORD"))
            .redirectErrorStream(true).redirectOutput(log.toFile()).apply { environment()["VSRQG_TEST_KEY_PASSWORD"] = password }.start()
        check(process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS).also { if (!it) process.destroyForcibly() }) { "Test certificate generation timed out; diagnostic log: $log" }
        check(process.exitValue() == 0) { "Test certificate generation failed for operation ${args.first()} (exit ${process.exitValue()}); diagnostic log: $log" }
    }

    // Send even invalid test identities so rejection proves server TLS validation, not client omission.
    private class ForcedClientAlias(private val delegate: javax.net.ssl.X509ExtendedKeyManager, private val alias: String) : javax.net.ssl.X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?) = alias
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, engine: javax.net.ssl.SSLEngine?) = alias
        override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf(alias)
        override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = delegate.getServerAliases(keyType, issuers)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?) = delegate.chooseServerAlias(keyType, issuers, socket)
        override fun getCertificateChain(selectedAlias: String?) = delegate.getCertificateChain(selectedAlias)
        override fun getPrivateKey(selectedAlias: String?) = delegate.getPrivateKey(selectedAlias)
    }
}
