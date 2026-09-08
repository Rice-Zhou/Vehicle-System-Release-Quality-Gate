package com.ricezhou.vsrqg.demo

import com.ricezhou.vsrqg.VsrqgApplication
import com.ricezhou.vsrqg.manifest.adapter.LocalArtifactPayloadVerifier
import com.ricezhou.vsrqg.manifest.application.ArtifactPayloadVerifier
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import org.springframework.boot.Banner
import org.springframework.boot.SpringApplication
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.context.support.GenericApplicationContext
import org.springframework.security.oauth2.jwt.JwtDecoder

class DemoDatabase(val url: String, val username: String, val password: String) {
    init {
        require(URL.matches(url) && username.isNotBlank() && password.isNotBlank()) { "DEMO_DATABASE_INVALID" }
        val port = URI(url.removePrefix("jdbc:")).port
        require(port == -1 || port in 1..65535) { "DEMO_DATABASE_INVALID" }
    }
    private companion object {
        val URL = Regex("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost|\\[::1])(?::[0-9]{1,5})?/vsrqg_demo")
    }
}

object M1DemoMain {
    @JvmStatic
    fun main(args: Array<String>) {
        var stage = "DEMO_INPUT_INVALID"
        try {
            require(args.isEmpty()) { "DEMO_ARGUMENTS_UNSUPPORTED" }
            val database = DemoDatabase(requiredEnv("VSRQG_DEMO_DATABASE_URL"),
                requiredEnv("VSRQG_DEMO_DATABASE_USERNAME"), requiredEnv("VSRQG_DEMO_DATABASE_PASSWORD"))
            val root = Files.createTempDirectory("vsrqg-m1-")
            val identity = M1DemoIdentity()
            stage = "DEMO_STARTUP_FAILED"
            start(database, root, identity).use { context ->
                stage = "DEMO_BOOTSTRAP_FAILED"
                val bootstrap = M1DemoBootstrap(context)
                val actors = bootstrap.initialize()
                stage = "DEMO_SCENARIO_FAILED"
                val result = M1DemoScenario(actors, root, bootstrap::lookupRejectedManifestId).run(
                    URI("http://127.0.0.1:${context.webServer.port}"),
                    identity.token(actors.managerSubject), identity.token(actors.viewerSubject),
                )
                println("SYNTHETIC_DEMO ${result.runId}: ${result.scenarioStatuses.keys.joinToString()} PASS")
            }
        } catch (_: Exception) {
            // CLI boundary emits a fixed failure stage; JDBC/HTTP exceptions can contain credentials.
            System.err.println(stage)
            exitProcess(1)
        }
    }

    fun start(database: DemoDatabase, root: Path, identity: M1DemoIdentity): ServletWebServerApplicationContext {
        val app = SpringApplication(VsrqgApplication::class.java)
        app.setEnvironment(environment(database))
        app.setAddCommandLineProperties(false)
        app.setLogStartupInfo(false)
        app.setBannerMode(Banner.Mode.OFF)
        app.addInitializers(org.springframework.context.ApplicationContextInitializer<org.springframework.context.ConfigurableApplicationContext> { context ->
            (context as GenericApplicationContext).registerBean("demoJwtDecoder", JwtDecoder::class.java, java.util.function.Supplier { identity.decoder })
            context.registerBean("demoPayloadVerifier", ArtifactPayloadVerifier::class.java,
                java.util.function.Supplier { LocalArtifactPayloadVerifier(root) })
        })
        return app.run() as ServletWebServerApplicationContext
    }

    fun environment(database: DemoDatabase): StandardEnvironment = StandardEnvironment().apply {
        // Do not load user/system profile, Company imports, providers, or logging configuration.
        propertySources.toList().forEach { propertySources.remove(it.name) }
        propertySources.addFirst(MapPropertySource("isolatedM1Demo", mapOf(
            "spring.config.location" to "classpath:/application.yml",
            "spring.profiles.active" to "m1-isolated",
            "spring.profiles.default" to "m1-isolated",
            "server.address" to "127.0.0.1", "server.port" to "0",
            "spring.datasource.url" to database.url,
            "spring.datasource.username" to database.username,
            "spring.datasource.password" to database.password,
            "spring.datasource.hikari.connection-timeout" to "5000",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri" to M1DemoIdentity.ISSUER,
            "spring.security.oauth2.resourceserver.jwt.audiences" to M1DemoIdentity.AUDIENCE,
            "vsrqg.deployment.mode" to "PILOT", "vsrqg.evidence.archive.provider" to "NONE",
            "vsrqg.issue.sync.worker-enabled" to "false",
            "vsrqg.traceability.verification.worker-enabled" to "false",
            "vsrqg.jira.pilot.enabled" to "false",
            "vsrqg.manifest.trusted-validator-versions" to "m1-local-payload/1",
            "logging.level.root" to "OFF",
        )))
    }

    private fun requiredEnv(name: String): String = System.getenv(name)?.takeIf(String::isNotBlank)
        ?: error("DEMO_INPUT_INVALID")
}
