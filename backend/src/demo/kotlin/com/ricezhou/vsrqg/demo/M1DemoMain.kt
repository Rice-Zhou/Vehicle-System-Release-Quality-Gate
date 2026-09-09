package com.ricezhou.vsrqg.demo

import com.ricezhou.vsrqg.VsrqgApplication
import com.ricezhou.vsrqg.manifest.adapter.LocalArtifactPayloadVerifier
import com.ricezhou.vsrqg.manifest.application.ArtifactPayloadVerifier
import com.ricezhou.vsrqg.issue.adapter.IssueSourceRuntimeFactory
import com.ricezhou.vsrqg.issue.application.IssueSourceDescriptorRegistry
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceValidatorPort
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.system.exitProcess
import org.springframework.boot.Banner
import org.springframework.boot.SpringApplication
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
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
        var stage = DemoFailure.INPUT
        var report: DemoReport? = null
        var output: Path? = null
        var m2Report: M2DemoReport? = null
        var failed = false
        try {
            require(args.isEmpty())
            output = Path.of(requiredEnv("VSRQG_DEMO_OUTPUT_DIRECTORY"))
            require(Files.isDirectory(output))
            report = DemoReport(requiredEnv("VSRQG_DEMO_RUN_ID"), requiredEnv("VSRQG_DEMO_CODE_COMMIT"),
                requiredEnv("VSRQG_DEMO_WORKING_TREE_DIRTY").toBooleanStrict())
            report.write(output)
            val database = DemoDatabase(requiredEnv("VSRQG_DEMO_DATABASE_URL"),
                requiredEnv("VSRQG_DEMO_DATABASE_USERNAME"), requiredEnv("VSRQG_DEMO_DATABASE_PASSWORD"))
            val sample = Path.of(requiredEnv("VSRQG_DEMO_SAMPLE_FILE"))
            val includeM2 = optionalStrictBoolean("VSRQG_DEMO_INCLUDE_M2")
            val payloadSha256 = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sample))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (includeM2) {
                m2Report = M2DemoReport(report.runId, report.codeCommit, report.workingTreeDirty)
                m2Report.write(output)
            }
            val root = Files.createDirectory(output.resolve("payload"))
            val identity = M1DemoIdentity()
            stage = DemoFailure.STARTUP
            start(database, root, identity, includeM2, payloadSha256).use { context ->
                stage = DemoFailure.BOOTSTRAP
                val bootstrap = M1DemoBootstrap(context)
                val actors = bootstrap.initialize(report.runId)
                stage = DemoFailure.SCENARIO
                val m1 = M1DemoScenario(actors, root, bootstrap::lookupRejectedManifestId, sample, report, output).run(
                    URI("http://127.0.0.1:${context.webServer.port}"),
                    identity.token(actors.managerSubject), identity.token(actors.viewerSubject),
                )
                if (includeM2) {
                    val m2Actors = bootstrap.initializeM2(actors)
                    val invalidBase = bootstrap.initialize()
                    val invalidActors = bootstrap.initializeM2(invalidBase)
                    val invalidOutput = Files.createDirectory(output.resolve("invalid-fixture"))
                    val invalidM1 = M1DemoScenario(invalidBase, root, bootstrap::lookupRejectedManifestId, sample,
                        DemoReport(invalidBase.runId, report.codeCommit, report.workingTreeDirty), invalidOutput).run(
                        URI("http://127.0.0.1:${context.webServer.port}"),
                        identity.token(invalidBase.managerSubject), identity.token(invalidBase.viewerSubject),
                    )
                    val manager = identity.token(actors.managerSubject,
                        scopes = "issue:configure issue:sync issue:read issue:snapshot traceability:read")
                    val engineer = identity.token(m2Actors.engineerSubject,
                        scopes = "traceability:verify traceability:read traceability:ingest",
                        principalType = "USER", projectReference = actors.projectKey)
                    val service = identity.token(m2Actors.serviceSubject, scopes = "traceability:ingest",
                        principalType = "SERVICE", projectReference = actors.projectKey)
                    val invalidFixture = M2InvalidFixture(
                        invalidActors.sourceId,
                        identity.token(invalidBase.managerSubject,
                            scopes = "issue:configure issue:sync issue:read issue:snapshot traceability:read"),
                        identity.token(invalidActors.engineerSubject,
                            scopes = "traceability:verify traceability:read", principalType = "USER",
                            projectReference = invalidBase.projectKey),
                        identity.token(invalidActors.serviceSubject, scopes = "traceability:ingest",
                            principalType = "SERVICE", projectReference = invalidBase.projectKey),
                        invalidM1,
                    )
                    M2DemoScenario(checkNotNull(m2Report), invalidFixture = invalidFixture).run(
                        URI("http://127.0.0.1:${context.webServer.port}"), manager, engineer, service,
                        m2Actors.sourceId, m1, payloadSha256,
                    )
                }
            }
        } catch (failure: Exception) {
            // Exceptions from JDBC and HTTP can contain credentials. Only typed codes cross this boundary.
            val code = if (failure is DemoHttpFailure) DemoFailure.HTTP_STATUS else stage
            report?.fail(code)
            m2Report?.fail("M2_DEMO_${code.name}")
            System.err.println(code.code)
            failed = true
        } finally {
            if (report != null && output != null) {
                try { report.write(output) } catch (_: Exception) {
                    System.err.println(DemoFailure.OUTPUT.code)
                    failed = true
                }
                try { m2Report?.write(output) } catch (_: Exception) {
                    System.err.println(DemoFailure.OUTPUT.code)
                    failed = true
                }
            }
        }
        if (m2Report?.passed() == false) failed = true
        if (failed) exitProcess(1)
        println("SYNTHETIC_DEMO ${report?.runId}: PASS")
    }

    fun start(
        database: DemoDatabase,
        root: Path,
        identity: M1DemoIdentity,
        includeM2: Boolean = false,
        payloadSha256: String? = null,
    ): ServletWebServerApplicationContext {
        val m2Validator = if (includeM2) {
            M2DemoProvenanceValidator(payloadSha256 ?: error("M2_DEMO_PAYLOAD_SHA256_REQUIRED"))
        } else {
            null
        }
        val app = SpringApplication(VsrqgApplication::class.java)
        app.setEnvironment(environment(database, includeM2))
        app.setAddCommandLineProperties(false)
        app.setLogStartupInfo(false)
        app.setBannerMode(Banner.Mode.OFF)
        app.addInitializers(org.springframework.context.ApplicationContextInitializer<org.springframework.context.ConfigurableApplicationContext> { context ->
            (context as GenericApplicationContext).registerBean("demoJwtDecoder", JwtDecoder::class.java, java.util.function.Supplier { identity.decoder })
            context.registerBean("demoPayloadVerifier", ArtifactPayloadVerifier::class.java,
                java.util.function.Supplier { LocalArtifactPayloadVerifier(root) })
            if (m2Validator != null) {
                context.registerBean("m2DemoIssueFactory", IssueSourceRuntimeFactory::class.java,
                    java.util.function.Supplier { M2DemoInputs.factory(Instant.now()) })
                context.registerBean("m2DemoDescriptorRegistry", IssueSourceDescriptorRegistry::class.java,
                    java.util.function.Supplier {
                        IssueSourceDescriptorRegistry { sourceType ->
                            if (sourceType == M2DemoInputs.descriptor.sourceType) M2DemoInputs.descriptor else {
                                throw ResourceConflict(
                                    code = "ADAPTER_NOT_CONFIGURED",
                                    resourceTitle = "Issue source adapter is not configured",
                                    detail = "No adapter descriptor is configured for this source type",
                                )
                            }
                        }
                    }, BeanDefinitionCustomizer { definition -> definition.isPrimary = true })
                context.registerBean("m2DemoProvenanceValidator", BuildProvenanceValidatorPort::class.java,
                    java.util.function.Supplier { m2Validator },
                    BeanDefinitionCustomizer { definition -> definition.isPrimary = true })
            }
        })
        return app.run() as ServletWebServerApplicationContext
    }

    fun environment(database: DemoDatabase, includeM2: Boolean = false): StandardEnvironment = StandardEnvironment().apply {
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
            "vsrqg.issue.snapshot.enabled" to "true",
            "vsrqg.traceability.ingestion.enabled" to includeM2.toString(),
            "vsrqg.traceability.verification.enabled" to includeM2.toString(),
            "vsrqg.traceability.verification.worker-enabled" to "false",
            "vsrqg.jira.pilot.enabled" to "false",
            "vsrqg.manifest.trusted-validator-versions" to "m1-local-payload/1",
            "logging.level.root" to "OFF",
        ).let { properties ->
            if (includeM2) properties + mapOf(
                "vsrqg.issue.sync.worker-enabled" to "true",
                "vsrqg.traceability.verification.worker-enabled" to "true",
            ) else properties
        }))
    }

    private fun requiredEnv(name: String): String = System.getenv(name)?.takeIf(String::isNotBlank)
        ?: error("DEMO_INPUT_INVALID")

    private fun optionalStrictBoolean(name: String): Boolean = System.getenv(name)?.let {
        try { it.toBooleanStrict() } catch (_: IllegalArgumentException) { error("DEMO_INPUT_INVALID") }
    } ?: false
}
