package com.ricezhou.vsrqg.demo

import com.ricezhou.vsrqg.VsrqgApplication
import com.ricezhou.vsrqg.manifest.adapter.LocalArtifactPayloadVerifier
import com.ricezhou.vsrqg.manifest.application.ArtifactPayloadVerifier
import com.ricezhou.vsrqg.manifest.application.ManifestValidationTrustPolicy
import java.nio.file.Path
import java.util.Collections
import javax.sql.DataSource
import org.springframework.boot.Banner
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.ConfigurationPropertyState
import org.springframework.boot.env.OriginTrackedMapPropertySource
import org.springframework.boot.env.RandomValuePropertySource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.origin.OriginLookup
import org.springframework.boot.origin.TextResourceOrigin
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.PropertySource
import org.springframework.core.io.ClassPathResource
import org.springframework.security.oauth2.jwt.JwtDecoder

class PrivateReadOnlyInputs(val database: DemoDatabase, val tls: ResolvedTlsInput?, val decoder: JwtDecoder?, val artifactRoot: Path) {
    override fun toString() = "PrivateReadOnlyInputs[redacted]"
}

class TlsReferenceEvidence(val recordReference: String, val keyStore: Path, val trustStore: Path) {
    override fun toString() = "TlsReferenceEvidence[redacted]"
}

class ResolvedTlsInput private constructor(val properties: Map<String, String>, val evidence: TlsReferenceEvidence) {
    val keyStore: Path get() = evidence.keyStore
    val trustStore: Path get() = evidence.trustStore
    override fun toString() = "ResolvedTlsInput[redacted]"
    companion object {
        // Approval belongs to the controlled caller; matching a URI cannot create that approval.
        fun from(helper: M3Tls, evidence: TlsReferenceEvidence?): ResolvedTlsInput {
            val reference = evidence ?: invalidReadOnly("TLS_REFERENCE")
            requireReadOnly(reference.recordReference.isNotBlank() && reference.keyStore.isAbsolute && reference.trustStore.isAbsolute &&
                helper.keyStore == reference.keyStore && helper.trustStore == reference.trustStore, "TLS_REFERENCE")
            return ResolvedTlsInput(Collections.unmodifiableMap(helper.serverProperties()), reference)
        }
    }
}

class ProfilePreparation private constructor(val environment: ConfigurableEnvironment) {
    companion object {
        fun capture(environment: ConfigurableEnvironment): ProfilePreparation {
            requireReadOnly(environment.getProperty("spring.profiles.active") == "m1-isolated" &&
                environment.getProperty("spring.profiles.default") == "m1-isolated", "PROFILE_PREPARATION")
            verifyProfiles(environment)
            return ProfilePreparation(environment)
        }
    }
}

class FrameworkView private constructor(val environment: ConfigurableEnvironment, val source: PropertySource<*>) {
    companion object {
        fun attach(environment: ConfigurableEnvironment): FrameworkView {
            requireReadOnly(environment.propertySources["configurationProperties"] == null, "SOURCE")
            ConfigurationPropertySources.attach(environment)
            return FrameworkView(environment, environment.propertySources["configurationProperties"]!!)
        }
    }
    override fun toString() = "FrameworkView[redacted]"
}

class ClasspathEvidence private constructor(private val documents: List<Map<String, Any?>>) {
    fun matches(source: PropertySource<*>): Boolean = source is OriginTrackedMapPropertySource &&
        documents.any { it == source.propertyNames.associateWith(source::getProperty) } &&
        source.propertyNames.all { key ->
            (OriginLookup.getOrigin(source, key) as? TextResourceOrigin)?.resource == ClassPathResource("application.yml")
        }
    companion object {
        // Capture fixed resource evidence before Boot preparation; inspect itself performs no file reads.
        fun capture() = ClasspathEvidence(YamlPropertySourceLoader().load("fixed-resource-evidence", ClassPathResource("application.yml"))
            .map { source -> (source as EnumerablePropertySource<*>).propertyNames.associateWith(source::getProperty) })
    }
    override fun toString() = "ClasspathEvidence[redacted]"
}

class SourceRegistration(val role: String, val source: PropertySource<*>, private val classpath: ClasspathEvidence? = null) {
    private val snapshot = (source as? MapPropertySource)?.source?.toMap()
    fun unchanged() = if (role == "FRAMEWORK_RANDOM") source.javaClass == RandomValuePropertySource::class.java
        else snapshot != null && (source as MapPropertySource).source == snapshot &&
            (role != "CLASSPATH" || classpath?.matches(source) == true)
    override fun toString() = "SourceRegistration[redacted]"
}
class InspectionContext(val environment: ConfigurableEnvironment, registrations: List<SourceRegistration>,
    val tls: ResolvedTlsInput?, val preparation: ProfilePreparation?, val frameworkView: FrameworkView? = null) {
    val registrations: List<SourceRegistration> = Collections.unmodifiableList(registrations.toList())
    override fun toString() = "InspectionContext[redacted]"
}

data class PropertyObservation(val key: String, val present: Boolean, val value: Any?, val propertySource: String?, val origin: String?)
data class ReadOnlyStartupProof(val entries: List<PropertyObservation>, val writeInitSqlAbsentAcrossSources: Boolean)

object PrivateReadOnlyBackendBootstrap {
    fun inspect(environment: ConfigurableEnvironment, context: InspectionContext): ReadOnlyStartupProof {
        requireReadOnly(environment === context.environment && context.preparation?.environment === environment, "CONTEXT")
        verifyProfiles(environment)
        requireReadOnly(listOf("M1_BASE", "PHASE_FINAL", "TLS_RESOLVED").all { role -> context.registrations.count { it.role == role } == 1 } &&
            context.registrations.all { it.role in setOf("M1_BASE", "PHASE_FINAL", "TLS_RESOLVED", "CLASSPATH", "FRAMEWORK_INFO", "FRAMEWORK_RANDOM") }, "SOURCE")
        val tls = context.tls ?: invalidReadOnly("TLS_REFERENCE")
        val tlsSource = context.registrations.single { it.role == "TLS_RESOLVED" }.source
        requireReadOnly(tlsSource is MapPropertySource && tlsSource.source === tls.properties, "TLS_REFERENCE")
        verifySources(environment, context.registrations, context.frameworkView)
        val entries = RULES.mapNotNull { rule -> observe(environment, context, rule) }
        return ReadOnlyStartupProof(Collections.unmodifiableList(entries), true)
    }

    fun application(inputs: PrivateReadOnlyInputs, proofSink: (ReadOnlyStartupProof) -> Unit): SpringApplication {
        requireReadOnly(inputs.database.url == PRIVATE_DATABASE && inputs.artifactRoot.isAbsolute &&
            inputs.artifactRoot.normalize() == inputs.artifactRoot, "INPUT")
        val tls = inputs.tls ?: invalidReadOnly("TLS_REFERENCE")
        val decoder = inputs.decoder ?: invalidReadOnly("DECODER")
        val environment = M1DemoMain.environment(inputs.database, includeM2 = false)
        val preparation = ProfilePreparation.capture(environment)
        val base = environment.propertySources["isolatedM1Demo"] ?: invalidReadOnly("SOURCE")
        val tlsSource = MapPropertySource("privateReadOnlyTls", tls.properties)
        environment.propertySources.addFirst(tlsSource)
        val registrations = listOf(SourceRegistration("M1_BASE", base), SourceRegistration("TLS_RESOLVED", tlsSource))
        val view = FrameworkView.attach(environment)
        val classpath = ClasspathEvidence.capture()
        return object : SpringApplication(VsrqgApplication::class.java) {
            private var preparedSources = registrations

            override fun configureEnvironment(environment: ConfigurableEnvironment, args: Array<String>) {
                requireReadOnly(environment === preparation.environment, "CONTEXT")
                verifySources(environment, preparedSources, view)
                super.configureEnvironment(environment, args)
                val info = environment.propertySources["applicationInfo"] ?: invalidReadOnly("SOURCE")
                requireReadOnly(info.javaClass.name == "org.springframework.boot.ApplicationInfoPropertySource", "SOURCE")
                preparedSources = registrations + SourceRegistration("FRAMEWORK_INFO", info)
                verifySources(environment, preparedSources, view)
            }

            override fun postProcessApplicationContext(context: ConfigurableApplicationContext) {
                requireReadOnly(context.environment === environment, "CONTEXT")
                super.postProcessApplicationContext(context)
                // ConfigData is now complete; later initializer replacements are not registered here.
                preparedSources = environment.propertySources.filterNot { it === view.source }.map { source ->
                    preparedSources.singleOrNull { it.source === source } ?: when {
                        source.javaClass == RandomValuePropertySource::class.java -> SourceRegistration("FRAMEWORK_RANDOM", source)
                        classpath.matches(source) -> SourceRegistration("CLASSPATH", source, classpath)
                        else -> invalidReadOnly("SOURCE")
                    }
                }
                verifySources(environment, preparedSources, view)
            }

            override fun refresh(context: ConfigurableApplicationContext) {
                requireReadOnly(context.environment === environment, "CONTEXT")
                requireReadOnly(context.beanFactory.singletonNames.none { context.beanFactory.getSingleton(it) is DataSource }, "DATASOURCE")
                // Inspect before addFirst: its name-based removal could otherwise erase an impostor.
                verifySources(environment, preparedSources, view)
                val phase = MapPropertySource("privateReadOnlyPhase", PHASE_PROPERTIES)
                environment.propertySources.addFirst(phase)
                val inspection = InspectionContext(environment, preparedSources + SourceRegistration("PHASE_FINAL", phase), tls, preparation, view)
                proofSink(inspect(environment, inspection))
                super.refresh(context)
            }
        }.apply {
            setEnvironment(environment)
            setAddCommandLineProperties(false)
            setLogStartupInfo(false)
            setBannerMode(Banner.Mode.OFF)
            addInitializers(org.springframework.context.ApplicationContextInitializer<ConfigurableApplicationContext> { context ->
                val registry = context as GenericApplicationContext
                registry.registerBean("demoJwtDecoder", JwtDecoder::class.java, java.util.function.Supplier { decoder })
                registry.registerBean("demoPayloadVerifier", ArtifactPayloadVerifier::class.java,
                    java.util.function.Supplier { LocalArtifactPayloadVerifier(inputs.artifactRoot) })
            })
        }
    }

    private fun verifySources(environment: ConfigurableEnvironment, registrations: List<SourceRegistration>, view: FrameworkView?) {
        requireReadOnly(view == null || (view.environment === environment && environment.propertySources.any { it === view.source }), "SOURCE")
        for (registration in registrations) {
            requireReadOnly(environment.propertySources.any { it === registration.source } && registration.unchanged(), "SOURCE")
        }
        for (source in environment.propertySources) {
            if (registrations.any { it.source === source }) continue
            requireReadOnly(source === view?.source, "SOURCE")
        }
    }

    private fun observe(environment: ConfigurableEnvironment, context: InspectionContext, rule: Rule): PropertyObservation? {
        val sources = environment.propertySources.filterNot { it === context.frameworkView?.source }
        val name = ConfigurationPropertyName.of(rule.key)
        if (rule.role == "ABSENT") {
            requireReadOnly(sources.none { source ->
                logicalProperty(source, name, descendants = true) || (source is EnumerablePropertySource<*> &&
                    source.propertyNames.any { raw ->
                        val adapted = ConfigurationPropertyName.adapt(raw, '.')
                        adapted == name || name.isAncestorOf(adapted)
                    })
            }, rule.id)
            return PropertyObservation(rule.key, false, null, null, null)
        }
        val winner = sources.firstOrNull { logicalProperty(it, name, rule.expected is List<*> && rule.id != "P35") }
            ?: invalidReadOnly(rule.id)
        val role = context.registrations.singleOrNull { it.source === winner }?.role
        requireReadOnly(role == rule.role, rule.id)
        val raw = try { environment.getProperty(rule.key) } catch (_: IllegalArgumentException) { invalidReadOnly(rule.id) }
        if (rule.secret) {
            requireReadOnly(!raw.isNullOrBlank(), rule.id)
            return null
        }
        if (rule.id in setOf("P05", "P06")) {
            val reference = if (rule.id == "P05") context.tls!!.keyStore else context.tls!!.trustStore
            requireReadOnly(raw == reference.toUri().toString(), rule.id)
            return PropertyObservation(rule.key, true, true, role, if (rule.id == "P05") "APPROVED_KEY_STORE" else "APPROVED_TRUST_STORE")
        }
        val value: Any? = try {
            when (rule.expected) {
                is Boolean -> Binder.get(environment).bind(rule.key, Boolean::class.javaObjectType).orElse(null)
                is Int -> Binder.get(environment).bind(rule.key, Int::class.javaObjectType).orElse(null)
                is List<*> -> if (rule.id == "P35") {
                    val version = rule.expected.single() as String
                    requireReadOnly(raw?.trim() == version && ManifestValidationTrustPolicy(raw!!).isTrusted(version), rule.id)
                    rule.expected
                } else Binder.get(environment).bind(rule.key, Bindable.listOf(String::class.java)).orElse(null)
                else -> raw
            }
        } catch (_: BindException) { invalidReadOnly(rule.id) }
        requireReadOnly(value == rule.expected, rule.id)
        return PropertyObservation(rule.key, true, rule.expected, role, null)
    }

    private fun logicalProperty(source: PropertySource<*>, name: ConfigurationPropertyName, descendants: Boolean) =
        ConfigurationPropertySources.from(source).any {
            it.getConfigurationProperty(name) != null || (descendants && it.containsDescendantOf(name) == ConfigurationPropertyState.PRESENT)
        }

    private data class Rule(val id: String, val key: String, val role: String, val expected: Any?, val secret: Boolean = false)
    private const val PRIVATE_DATABASE = "jdbc:postgresql://127.0.0.1:56432/vsrqg_demo"
    private val RULES = listOf(
        Rule("P01", "server.address", "PHASE_FINAL", "127.0.0.1"),
        Rule("P02", "server.port", "PHASE_FINAL", 59443),
        Rule("P03", "server.ssl.enabled", "TLS_RESOLVED", true),
        Rule("P04", "server.ssl.client-auth", "TLS_RESOLVED", "want"),
        Rule("P05", "server.ssl.key-store", "TLS_RESOLVED", null),
        Rule("P06", "server.ssl.trust-store", "TLS_RESOLVED", null),
        Rule("P07", "server.ssl.key-store-type", "TLS_RESOLVED", "PKCS12"),
        Rule("P08", "server.ssl.trust-store-type", "TLS_RESOLVED", "PKCS12"),
        Rule("P09", "server.ssl.key-store-password", "TLS_RESOLVED", null, secret = true),
        Rule("P10", "server.ssl.trust-store-password", "TLS_RESOLVED", null, secret = true),
        Rule("P11", "spring.datasource.url", "PHASE_FINAL", PRIVATE_DATABASE),
        Rule("P12", "spring.datasource.username", "M1_BASE", null, secret = true),
        Rule("P13", "spring.datasource.password", "M1_BASE", null, secret = true),
        Rule("P14", "spring.datasource.hikari.connection-init-sql", "ABSENT", null),
        Rule("P15", "spring.datasource.hikari.maximum-pool-size", "PHASE_FINAL", 4),
        Rule("P16", "spring.flyway.enabled", "PHASE_FINAL", false),
        Rule("P17", "spring.profiles.active", "PHASE_FINAL", listOf("m1-isolated")),
        Rule("P18", "spring.profiles.default", "PHASE_FINAL", listOf("m1-isolated")),
        Rule("P19", "vsrqg.demo.smoke.enabled", "PHASE_FINAL", false),
        Rule("P20", "vsrqg.demo.agent-registration.enabled", "PHASE_FINAL", false),
        Rule("P21", "vsrqg.demo.evidence.enabled", "PHASE_FINAL", false),
        Rule("P22", "vsrqg.quality.worker-enabled", "PHASE_FINAL", false),
        Rule("P23", "vsrqg.test.deadline-worker.enabled", "PHASE_FINAL", false),
        Rule("P24", "vsrqg.demo.smoke.agent-id", "ABSENT", null),
        Rule("P25", "vsrqg.demo.smoke.device-id", "ABSENT", null),
        Rule("P26", "vsrqg.demo.smoke.environment-config-base64", "ABSENT", null),
        Rule("P27", "vsrqg.demo.evidence.root", "ABSENT", null),
        Rule("P28", "vsrqg.issue.sync.worker-enabled", "PHASE_FINAL", false),
        Rule("P29", "vsrqg.traceability.verification.worker-enabled", "PHASE_FINAL", false),
        Rule("P30", "vsrqg.traceability.ingestion.enabled", "PHASE_FINAL", false),
        Rule("P31", "vsrqg.jira.pilot.enabled", "PHASE_FINAL", false),
        Rule("P32", "vsrqg.evidence.archive.enabled", "PHASE_FINAL", false),
        Rule("P33", "vsrqg.evidence.archive.provider", "PHASE_FINAL", "NONE"),
        Rule("P34", "vsrqg.deployment.mode", "PHASE_FINAL", "PILOT"),
        Rule("P35", "vsrqg.manifest.trusted-validator-versions", "PHASE_FINAL", listOf("m1-local-payload/1")),
        Rule("P36", "spring.config.location", "M1_BASE", "classpath:/application.yml"),
        Rule("P37", "spring.datasource.hikari.connection-timeout", "M1_BASE", 5000),
        Rule("P38", "spring.security.oauth2.resourceserver.jwt.issuer-uri", "M1_BASE", M1DemoIdentity.ISSUER),
        Rule("P39", "spring.security.oauth2.resourceserver.jwt.audiences", "M1_BASE", listOf(M1DemoIdentity.AUDIENCE)),
        Rule("P40", "vsrqg.issue.snapshot.enabled", "M1_BASE", true),
        Rule("P41", "vsrqg.traceability.verification.enabled", "M1_BASE", false),
        Rule("P42", "logging.level.root", "M1_BASE", "OFF"),
        Rule("P43", "spring.config.import", "ABSENT", null),
        Rule("P44", "spring.application.json", "ABSENT", null),
    )
    private val PHASE_PROPERTIES: Map<String, Any> = Collections.unmodifiableMap(RULES.filter { it.role == "PHASE_FINAL" }
        .associate { it.key to if (it.expected is List<*>) it.expected.joinToString(",") else it.expected.toString() })
}

private fun requireReadOnly(condition: Boolean, key: String) { if (!condition) invalidReadOnly(key) }
private fun invalidReadOnly(key: String): Nothing = throw IllegalArgumentException("PRIVATE_READ_ONLY_INVALID:$key")
private fun verifyProfiles(environment: ConfigurableEnvironment) {
    requireReadOnly(environment.activeProfiles.toList() == listOf("m1-isolated") &&
        environment.defaultProfiles.toList() == listOf("m1-isolated"), "PROFILE_STATE")
}
