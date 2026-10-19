package com.ricezhou.vsrqg.demo

import com.ricezhou.vsrqg.manifest.application.ManifestValidationTrustPolicy
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.io.ClassPathResource
import org.springframework.core.env.StandardEnvironment
import org.springframework.boot.env.OriginTrackedMapPropertySource
import org.springframework.boot.origin.OriginTrackedValue
import org.springframework.boot.origin.TextResourceOrigin
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.test.util.ReflectionTestUtils

@Timeout(60)
class PrivateReadOnlyBackendBootstrapTest {
    @TempDir lateinit var root: Path
    private val database = DemoDatabase("jdbc:postgresql://127.0.0.1:56432/vsrqg_demo", "synthetic-user", "synthetic-db-secret")
    private val decoder = JwtDecoder { throw JwtException("UNUSED_TEST_DECODER") }

    @Test
    fun `BC01 scalar consumer matches pinned validator and proof excludes secrets`() {
        val f = fixture()
        val policy = ManifestValidationTrustPolicy(f.environment.getProperty(VALIDATOR, ""))
        assertThat(policy.isTrusted("m1-local-payload/1")).isTrue()
        assertThat(policy.isTrusted("unexpected")).isFalse()
        val proof = PrivateReadOnlyBackendBootstrap.inspect(f.environment, f.context)
        assertThat(proof.entries).hasSize(40)
        assertThat(proof.entries.single { it.key == VALIDATOR }.value).isEqualTo(listOf("m1-local-payload/1"))
        assertThat(proof.writeInitSqlAbsentAcrossSources).isTrue()
        assertThat(proof.entries.filter { !it.present }.map { it.key }).contains(INIT_SQL, "spring.config.import")
        assertThat(proof.toString()).doesNotContain("synthetic-db-secret", "synthetic-tls-secret", root.toString(), "password")
    }

    @Test
    fun `BC01 Binder indexed collection cannot stand in for scalar consumption`() {
        val phase = phase().toMutableMap().apply { remove(VALIDATOR); put("$VALIDATOR[0]", "m1-local-payload/1") }
        val f = fixture(phase, omitBase = setOf(VALIDATOR))
        ConfigurationPropertySources.attach(f.environment)
        assertThat(Binder.get(f.environment).bind(VALIDATOR, Bindable.listOf(String::class.java)).get())
            .containsExactly("m1-local-payload/1")
        assertThat(ManifestValidationTrustPolicy(f.environment.getProperty(VALIDATOR, "")).isTrusted("m1-local-payload/1")).isFalse()
        rejected { PrivateReadOnlyBackendBootstrap.inspect(f.environment, f.context) }
    }

    @Test
    fun `BC01 empty or duplicate CSV items are not a single configured version`() {
        for (raw in listOf("m1-local-payload/1,", "m1-local-payload/1,m1-local-payload/1")) {
            val f = fixture(phase().toMutableMap().apply { put(VALIDATOR, raw) })
            assertThat(ManifestValidationTrustPolicy(raw).isTrusted("m1-local-payload/1")).isTrue()
            rejected { PrivateReadOnlyBackendBootstrap.inspect(f.environment, f.context) }
        }
        val scalar = fixture(phase().toMutableMap().apply { put(VALIDATOR, " m1-local-payload/1 ") })
        assertThatCode { PrivateReadOnlyBackendBootstrap.inspect(scalar.environment, scalar.context) }.doesNotThrowAnyException()
    }

    @Test
    fun `BC02 preparation and final profile state are independent checks`() {
        val f = fixture()
        assertThat(f.environment.activeProfiles).containsExactly("m1-isolated")
        assertThat(f.environment.defaultProfiles).containsExactly("m1-isolated")
        assertThatCode { PrivateReadOnlyBackendBootstrap.inspect(f.environment, f.context) }.doesNotThrowAnyException()
        f.environment.setActiveProfiles("unexpected-active")
        assertThat(f.environment.getProperty("spring.profiles.active")).isEqualTo("m1-isolated")
        rejected { PrivateReadOnlyBackendBootstrap.inspect(f.environment, f.context) }
        val other = fixture()
        other.environment.setDefaultProfiles("unexpected-default")
        rejected { PrivateReadOnlyBackendBootstrap.inspect(other.environment, other.context) }
    }

    @Test
    fun `BC02 late correct properties cannot replace missing preparation or repair cached profiles`() {
        val f = fixture()
        val missing = InspectionContext(f.environment, f.registrations, f.tls, null, f.context.frameworkView)
        rejected { PrivateReadOnlyBackendBootstrap.inspect(f.environment, missing) }
        val environment = M1DemoMain.environment(database)
        environment.setActiveProfiles("unexpected")
        rejected { ProfilePreparation.capture(environment) }
        assertThat(environment.activeProfiles).containsExactly("unexpected")
    }

    @Test
    fun `BC03 source identity must not use PropertySource name equality`() {
        val f = fixture()
        val impostor = MapPropertySource("phase", phase())
        assertThat(impostor).isEqualTo(f.phase)
        assertThat(impostor).isNotSameAs(f.phase)
        f.environment.propertySources.replace("phase", impostor)
        rejected { PrivateReadOnlyBackendBootstrap.inspect(f.environment, f.context) }
        val other = fixture()
        rejected { PrivateReadOnlyBackendBootstrap.inspect(other.environment, f.context) }
        val values = phase().toMutableMap()
        val mutated = fixture(values)
        values["server.port"] = "0"
        rejected { PrivateReadOnlyBackendBootstrap.inspect(mutated.environment, mutated.context) }
    }

    @Test
    fun `BC03 missing and foreign TLS evidence cannot be self certified by matching URI`() {
        val f = fixture()
        val absent = InspectionContext(f.environment, f.registrations, null, f.preparation, f.context.frameworkView)
        rejected { PrivateReadOnlyBackendBootstrap.inspect(f.environment, absent) }
        val foreign = fixture().tls
        val mismatched = InspectionContext(f.environment, f.registrations, foreign, f.preparation, f.context.frameworkView)
        rejected { PrivateReadOnlyBackendBootstrap.inspect(f.environment, mismatched) }
        val (helper, evidence) = tlsHelper()
        rejected { ResolvedTlsInput.from(helper, null) }
        rejected { ResolvedTlsInput.from(helper, TlsReferenceEvidence("", evidence.keyStore, evidence.trustStore)) }
        rejected { ResolvedTlsInput.from(helper, TlsReferenceEvidence("SYNTHETIC_R1", root.resolve("not-approved.p12"), evidence.trustStore)) }
    }

    @Test
    fun `allowed classpath and M1 defaults can be overridden without treating them as forbidden sources`() {
        val f = fixture(includeClasspath = true)
        ConfigurationPropertySources.attach(f.environment)
        val proof = PrivateReadOnlyBackendBootstrap.inspect(f.environment, f.context)
        assertThat(proof.entries.single { it.key == "server.port" }.value).isEqualTo(59443)
        assertThat(proof.entries.single { it.key == "vsrqg.evidence.archive.enabled" }.value).isEqualTo(false)
        assertThat(proof.entries.single { it.key == "server.port" }.propertySource).isEqualTo("PHASE_FINAL")
    }

    @Test
    fun `forbidden sources remain forbidden even when harmless or shadowed`() {
        for (source in listOf("systemProperties", "systemEnvironment", "commandLineArgs", "old-preparation", "unregistered")) {
            val f = fixture()
            f.environment.propertySources.addLast(MapPropertySource(source, mapOf("server.port" to "0")))
            rejected { PrivateReadOnlyBackendBootstrap.inspect(f.environment, f.context) }
        }
    }

    @Test
    fun `foreign Boot view cannot conceal SQL or replace the inspected source collection`() {
        for (hiddenSql in listOf(false, true)) {
            val f = fixture()
            val foreign = StandardEnvironment().apply {
                propertySources.toList().forEach { propertySources.remove(it.name) }
                f.environment.propertySources.forEach(propertySources::addLast)
                if (hiddenSql) propertySources.addLast(MapPropertySource("hidden", mapOf(INIT_SQL to "NONEXECUTABLE_SENTINEL")))
            }
            ConfigurationPropertySources.attach(foreign)
            f.environment.propertySources.addFirst(foreign.propertySources["configurationProperties"]!!)
            rejected { PrivateReadOnlyBackendBootstrap.inspect(f.environment, f.context) }
        }
        val f = fixture()
        f.environment.propertySources.addFirst(MapPropertySource("configurationProperties", emptyMap()))
        rejected { PrivateReadOnlyBackendBootstrap.inspect(f.environment, f.context) }
    }

    @Test
    fun `unregistered harmless map cannot approve itself by declaring a classpath Origin`() {
        val f = fixture()
        val origin = TextResourceOrigin(ClassPathResource("application.yml"), TextResourceOrigin.Location(0, 0))
        val forged = OriginTrackedMapPropertySource("forged-classpath", mapOf("unrelated.harmless" to OriginTrackedValue.of("value", origin)))
        f.environment.propertySources.addLast(forged)
        rejected { PrivateReadOnlyBackendBootstrap.inspect(f.environment, f.context) }
        val app = PrivateReadOnlyBackendBootstrap.application(inputs()) { error("MUST_NOT_PASS") }
        val env = ReflectionTestUtils.getField(app, "environment") as ConfigurableEnvironment
        env.propertySources.addLast(forged)
        val context = GenericApplicationContext().apply { environment = env }
        rejected { ReflectionTestUtils.invokeMethod<Any>(app, "postProcessApplicationContext", context) }
    }

    @Test
    fun `normal Boot configureEnvironment sources reach proof before substitute refresh`() {
        val events = mutableListOf<String>()
        val app = PrivateReadOnlyBackendBootstrap.application(inputs()) { events.add("proof") }
        val environment = ReflectionTestUtils.getField(app, "environment") as ConfigurableEnvironment
        ReflectionTestUtils.invokeMethod<Any>(app, "configureEnvironment", environment, emptyArray<String>())
        YamlPropertySourceLoader().load("classpath-fixture", ClassPathResource("application.yml"))
            .forEach(environment.propertySources::addLast)
        environment.propertySources.addLast(org.springframework.boot.env.RandomValuePropertySource())
        val context = object : GenericApplicationContext() { override fun refresh() { events.add("refresh") } }
            .apply { this.environment = environment }
        ReflectionTestUtils.invokeMethod<Any>(app, "postProcessApplicationContext", context)
        ReflectionTestUtils.invokeMethod<Any>(app, "refresh", context)
        assertThat(events).containsExactly("proof", "refresh")
    }

    @Test
    fun `even an identical resource copy injected after preparation is not a registered instance`() {
        val app = PrivateReadOnlyBackendBootstrap.application(inputs()) { error("MUST_NOT_PASS") }
        val environment = ReflectionTestUtils.getField(app, "environment") as ConfigurableEnvironment
        ReflectionTestUtils.invokeMethod<Any>(app, "configureEnvironment", environment, emptyArray<String>())
        YamlPropertySourceLoader().load("classpath-original", ClassPathResource("application.yml"))
            .forEach(environment.propertySources::addLast)
        val context = GenericApplicationContext().apply { this.environment = environment }
        ReflectionTestUtils.invokeMethod<Any>(app, "postProcessApplicationContext", context)
        YamlPropertySourceLoader().load("late-copy", ClassPathResource("application.yml"))
            .forEach(environment.propertySources::addLast)
        rejected { ReflectionTestUtils.invokeMethod<Any>(app, "refresh", context) }
    }

    @Test
    fun `an already instantiated datasource is rejected even under a different bean name`() {
        val app = PrivateReadOnlyBackendBootstrap.application(inputs()) { error("MUST_NOT_PASS") }
        val environment = ReflectionTestUtils.getField(app, "environment") as ConfigurableEnvironment
        var refreshed = false
        val context = object : GenericApplicationContext() { override fun refresh() { refreshed = true } }
            .apply { this.environment = environment }
        context.beanFactory.registerSingleton("pool-alias", org.springframework.jdbc.datasource.DriverManagerDataSource())
        rejected { ReflectionTestUtils.invokeMethod<Any>(app, "refresh", context) }
        assertThat(refreshed).isFalse()
    }

    @Test
    fun `write init presence rejects raw null empty shadowed and Boot alias keys independently`() {
        for ((key, value) in listOf(INIT_SQL to null, INIT_SQL to "", INIT_SQL to "NONEXECUTABLE_SENTINEL",
            "spring.datasource.hikari.connectionInitSql" to "NONEXECUTABLE_SENTINEL")) {
            val f = fixture(baseExtra = mapOf(key to value))
            rejected { PrivateReadOnlyBackendBootstrap.inspect(f.environment, f.context) }
        }
        val f = fixture(phase().toMutableMap().apply { put(INIT_SQL, null) }, mapOf(INIT_SQL to "NONEXECUTABLE_SENTINEL"))
        rejected { PrivateReadOnlyBackendBootstrap.inspect(f.environment, f.context) }
    }

    @Test
    fun `each final non secret control rejects a legal but different value`() {
        val changes = mapOf("server.address" to "0.0.0.0", "server.port" to "58443",
            "spring.datasource.url" to "jdbc:postgresql://127.0.0.1:55432/vsrqg_demo",
            "spring.datasource.hikari.maximum-pool-size" to "5", "spring.flyway.enabled" to "true",
            "spring.profiles.active" to "unexpected", "spring.profiles.default" to "unexpected",
            "vsrqg.deployment.mode" to "COMPANY", "vsrqg.evidence.archive.provider" to "S3", VALIDATOR to "unexpected") +
            phase().filterValues { it == "false" }.keys.associateWith { "true" }
        for ((key, value) in changes) {
            val f = fixture(phase().toMutableMap().apply { put(key, value) })
            rejected { PrivateReadOnlyBackendBootstrap.inspect(f.environment, f.context) }
        }
    }

    @Test
    fun `factory returns unstarted object with T0 established without business policy or identity`() {
        var proofs = 0
        val app = PrivateReadOnlyBackendBootstrap.application(inputs()) { proofs++ }
        val environment = ReflectionTestUtils.getField(app, "environment") as ConfigurableEnvironment
        assertThat(environment.activeProfiles).containsExactly("m1-isolated")
        assertThat(environment.defaultProfiles).containsExactly("m1-isolated")
        assertThat(proofs).isZero()
    }

    @Test
    fun `final boundary records proof before substitute refresh without datasource construction`() {
        val events = mutableListOf<String>()
        val app = PrivateReadOnlyBackendBootstrap.application(inputs()) { proof ->
            assertThat(proof.writeInitSqlAbsentAcrossSources).isTrue(); events.add("proof")
        }
        val environment = ReflectionTestUtils.getField(app, "environment") as ConfigurableEnvironment
        val context = object : GenericApplicationContext() {
            override fun refresh() { events.add("refresh") }
        }.apply { this.environment = environment }
        var dataSourcesConstructed = 0
        context.registerBean("dataSource", javax.sql.DataSource::class.java, java.util.function.Supplier {
            dataSourcesConstructed++
            org.springframework.jdbc.datasource.DriverManagerDataSource()
        })
        ReflectionTestUtils.invokeMethod<Any>(app, "refresh", context)
        assertThat(events).containsExactly("proof", "refresh")
        assertThat(context.beanFactory.singletonNames).doesNotContain("dataSource")
        assertThat(dataSourcesConstructed).isZero()
    }

    @Test
    fun `same named poison is rejected before final addFirst can erase it`() {
        val app = PrivateReadOnlyBackendBootstrap.application(inputs()) { error("PROOF_MUST_NOT_PASS") }
        val environment = ReflectionTestUtils.getField(app, "environment") as ConfigurableEnvironment
        val poison = MapPropertySource("privateReadOnlyPhase", mapOf("server.port" to "0"))
        environment.propertySources.addFirst(poison)
        var refreshed = false
        val context = object : GenericApplicationContext() { override fun refresh() { refreshed = true } }
            .apply { this.environment = environment }
        rejected { ReflectionTestUtils.invokeMethod<Any>(app, "refresh", context) }
        assertThat(refreshed).isFalse()
        assertThat(environment.propertySources["privateReadOnlyPhase"]).isSameAs(poison)
    }

    @Test
    fun `late profile poison and failed proof sink both prevent refresh without repair`() {
        val app = PrivateReadOnlyBackendBootstrap.application(inputs()) { error("PROOF_MUST_NOT_PASS") }
        val environment = ReflectionTestUtils.getField(app, "environment") as ConfigurableEnvironment
        environment.setDefaultProfiles("unexpected")
        var refreshed = false
        val context = object : GenericApplicationContext() { override fun refresh() { refreshed = true } }
            .apply { this.environment = environment }
        rejected { ReflectionTestUtils.invokeMethod<Any>(app, "refresh", context) }
        assertThat(refreshed).isFalse()
        assertThat(environment.defaultProfiles).containsExactly("unexpected")
        val sinkFailure = IllegalStateException("SYNTHETIC_SINK_FAILURE")
        val other = PrivateReadOnlyBackendBootstrap.application(inputs()) { throw sinkFailure }
        val otherEnvironment = ReflectionTestUtils.getField(other, "environment") as ConfigurableEnvironment
        context.environment = otherEnvironment
        assertThatThrownBy { ReflectionTestUtils.invokeMethod<Any>(other, "refresh", context) }.isSameAs(sinkFailure)
        assertThat(refreshed).isFalse()
    }

    @Test
    fun `missing TLS decoder or wrong private database refuses before app construction`() {
        val tls = tls()
        rejected { PrivateReadOnlyBackendBootstrap.application(PrivateReadOnlyInputs(database, null, decoder, root)) {} }
        rejected { PrivateReadOnlyBackendBootstrap.application(PrivateReadOnlyInputs(database, tls, null, root)) {} }
        for (url in listOf("jdbc:postgresql://127.0.0.1:55432/vsrqg_demo", "jdbc:postgresql://localhost:56432/vsrqg_demo")) {
            rejected { PrivateReadOnlyBackendBootstrap.application(PrivateReadOnlyInputs(DemoDatabase(url, "synthetic-user", "synthetic-secret"), tls, decoder, root)) {} }
        }
        assertThat(inputs().toString()).doesNotContain("synthetic-db-secret", root.toString())
        assertThat(tls.toString()).doesNotContain("synthetic-tls-secret", root.toString())
    }

    private fun inputs() = PrivateReadOnlyInputs(database, tls(), decoder, root)
    private fun tls() = tlsHelper().let { ResolvedTlsInput.from(it.first, it.second) }
    private fun tlsHelper(): Pair<M3Tls, TlsReferenceEvidence> {
        val dir = Files.createTempDirectory(root, "tls-")
        val key = Files.writeString(dir.resolve("key.p12"), "SYNTHETIC_NOT_A_CERTIFICATE")
        val trust = Files.writeString(dir.resolve("trust.p12"), "SYNTHETIC_NOT_A_CERTIFICATE")
        val password = Files.writeString(dir.resolve("password.txt"), "synthetic-tls-secret")
        val evidence = TlsReferenceEvidence("SYNTHETIC_R1_NOT_ACTUAL_APPROVAL", key, trust)
        fun json(p: Path) = p.toString().replace("\\", "\\\\")
        val config = Files.writeString(dir.resolve("tls.json"),
            """{"keyStore":"${json(key)}","trustStore":"${json(trust)}","keyStorePasswordFile":"${json(password)}","trustStorePasswordFile":"${json(password)}"}""")
        return M3Tls.read(config) to evidence
    }

    private fun fixture(final: Map<String, Any?> = phase(), baseExtra: Map<String, Any?> = emptyMap(), omitBase: Set<String> = emptySet(), includeClasspath: Boolean = false): Fixture {
        val env = M1DemoMain.environment(database)
        val preparation = ProfilePreparation.capture(env)
        val originalBase = env.propertySources["isolatedM1Demo"] as MapPropertySource
        val base = MapPropertySource("isolatedM1Demo", (originalBase.source - omitBase) + baseExtra)
        env.propertySources.replace("isolatedM1Demo", base)
        val tls = tls()
        val tlsSource = MapPropertySource("tls", tls.properties)
        val phaseSource = MapPropertySource("phase", final)
        env.propertySources.addFirst(tlsSource); env.propertySources.addFirst(phaseSource)
        val registrations = mutableListOf(SourceRegistration("M1_BASE", base), SourceRegistration("TLS_RESOLVED", tlsSource), SourceRegistration("PHASE_FINAL", phaseSource))
        if (includeClasspath) {
            val evidence = ClasspathEvidence.capture()
            YamlPropertySourceLoader().load("classpath-fixture", ClassPathResource("application.yml")).forEach { source ->
                env.propertySources.addLast(source)
                registrations.add(SourceRegistration("CLASSPATH", source, evidence))
            }
        }
        val view = FrameworkView.attach(env)
        return Fixture(env, registrations, tls, preparation, phaseSource, InspectionContext(env, registrations, tls, preparation, view))
    }

    private class Fixture(val environment: ConfigurableEnvironment, val registrations: List<SourceRegistration>,
        val tls: ResolvedTlsInput, val preparation: ProfilePreparation, val phase: MapPropertySource, val context: InspectionContext)

    private fun rejected(block: () -> Unit) {
        assertThatThrownBy(block).isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageStartingWith("PRIVATE_READ_ONLY_INVALID:")
            .hasMessageNotContaining("synthetic-db-secret").hasMessageNotContaining("synthetic-tls-secret")
    }

    private fun phase(): Map<String, Any?> = mapOf(
        "server.address" to "127.0.0.1", "server.port" to "59443",
        "spring.datasource.url" to "jdbc:postgresql://127.0.0.1:56432/vsrqg_demo",
        "spring.datasource.hikari.maximum-pool-size" to "4", "spring.flyway.enabled" to "false",
        "spring.profiles.active" to "m1-isolated", "spring.profiles.default" to "m1-isolated",
        "vsrqg.demo.smoke.enabled" to "false", "vsrqg.demo.agent-registration.enabled" to "false",
        "vsrqg.demo.evidence.enabled" to "false", "vsrqg.quality.worker-enabled" to "false",
        "vsrqg.test.deadline-worker.enabled" to "false", "vsrqg.issue.sync.worker-enabled" to "false",
        "vsrqg.traceability.verification.worker-enabled" to "false", "vsrqg.traceability.ingestion.enabled" to "false",
        "vsrqg.jira.pilot.enabled" to "false", "vsrqg.evidence.archive.enabled" to "false",
        "vsrqg.evidence.archive.provider" to "NONE", "vsrqg.deployment.mode" to "PILOT", VALIDATOR to "m1-local-payload/1",
    )

    private companion object {
        const val VALIDATOR = "vsrqg.manifest.trusted-validator-versions"
        const val INIT_SQL = "spring.datasource.hikari.connection-init-sql"
    }
}
