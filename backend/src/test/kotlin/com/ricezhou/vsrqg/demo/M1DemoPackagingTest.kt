package com.ricezhou.vsrqg.demo

import java.nio.file.Path
import java.time.Instant
import java.util.jar.JarFile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider

@Timeout(60)
class M1DemoPackagingTest {
    @Test
    fun `production jar and component scanning exclude demonstration`() {
        JarFile(Path.of(System.getProperty("demo.productionJar")).toFile()).use { jar ->
            assertThat(jar.entries().asSequence().map { it.name }.toList())
                .noneMatch { it.contains("com/ricezhou/vsrqg/demo/") }
        }
        assertThat(ClassPathScanningCandidateComponentProvider(true)
            .findCandidateComponents("com.ricezhou.vsrqg.demo")).isEmpty()
    }

    @Test
    fun `database boundary rejects remote other database and all connection parameters`() {
        val accepted = DemoDatabase("jdbc:postgresql://127.0.0.1:55432/vsrqg_demo", "demo", "secret")
        assertThat(accepted.toString()).doesNotContain("secret", "jdbc:")
        listOf("jdbc:postgresql://example.com/vsrqg_demo", "jdbc:postgresql://127.0.0.1/postgres",
            "jdbc:postgresql://127.0.0.1/vsrqg_demo?options=-csearch_path%3Dpublic",
            "jdbc:postgresql://127.0.0.1/vsrqg_demo?socketFactory=x",
            "jdbc:postgresql://127.0.0.1/vsrqg_demo?loggerLevel=OFF",
            "jdbc:postgresql://localhost.evil/vsrqg_demo", "jdbc:postgresql://127.0.0.1/vsrqg_demo#x"
        ).forEach { url ->
            assertThatThrownBy { DemoDatabase(url, "demo", "secret") }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessage("DEMO_DATABASE_INVALID")
        }
    }

    @Test
    fun `external system configuration cannot override isolated environment`() {
        val poison = mapOf("spring.config.import" to "file:/must-not-load-company.yml",
            "spring.config.location" to "file:/must-not-load-company.yml",
            "spring.profiles.active" to "company", "spring.application.json" to "{\"server\":{\"address\":\"0.0.0.0\"}}",
            "server.address" to "0.0.0.0", "vsrqg.deployment.mode" to "COMPANY",
            "vsrqg.evidence.archive.provider" to "S3", "vsrqg.issue.sync.worker-enabled" to "true")
        val previous = poison.mapValues { (key, _) -> System.getProperty(key) }
        try {
            poison.forEach(System::setProperty)
            val environment = M1DemoMain.environment(DemoDatabase("jdbc:postgresql://127.0.0.1/vsrqg_demo", "demo", "secret"))
            assertThat(environment.getProperty("spring.config.import")).isNull()
            assertThat(environment.getProperty("spring.application.json")).isNull()
            assertThat(environment.getProperty("spring.config.location")).isEqualTo("classpath:/application.yml")
            assertThat(environment.getProperty("spring.profiles.active")).isEqualTo("m1-isolated")
            assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1")
            assertThat(environment.getProperty("vsrqg.deployment.mode")).isEqualTo("PILOT")
            assertThat(environment.getProperty("vsrqg.evidence.archive.provider")).isEqualTo("NONE")
            assertThat(environment.getProperty("vsrqg.issue.sync.worker-enabled")).isEqualTo("false")
            assertThat(environment.getProperty("vsrqg.traceability.verification.worker-enabled")).isEqualTo("false")
        } finally {
            previous.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
        }
    }

    @Test
    fun `temporary decoder verifies signed claims and ten minute lifetime`() {
        val identity = M1DemoIdentity()
        val jwt = identity.decoder.decode(identity.token("manager"))
        assertThat(jwt.expiresAt!!.epochSecond - jwt.issuedAt!!.epochSecond).isEqualTo(600)
        assertThat(jwt.notBefore).isNotNull()
        assertThat(jwt.audience).containsExactly(M1DemoIdentity.AUDIENCE)
        listOf(
            M1DemoIdentity().token("manager"),
            identity.token("manager", issuedAt = Instant.now().minusSeconds(1200)),
            identity.token("manager", issuer = "http://localhost/wrong"),
            identity.token("manager", audience = "wrong"),
            identity.token("manager", notBefore = Instant.now().plusSeconds(120)),
        ).forEach { token ->
            assertThatThrownBy { identity.decoder.decode(token) }
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException::class.java)
        }
    }
}
