package com.ricezhou.vsrqg.demo

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.interfaces.RSAPublicKey
import javax.sql.DataSource
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.ApplicationContextFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.support.GenericApplicationContext

// Completeness of external records is checked here; their existence cannot grant approval.
class ReadOnlyHandoff(references: Map<String, String?>) {
    private val references = references.toMap()
    fun requireReady() {
        require(REQUIRED.all { !references[it].isNullOrBlank() }) { "PRIVATE_READ_ONLY_HANDOFF_INVALID" }
    }
    fun requireMatches(inputs: PrivateReadOnlyInputs) {
        require(references["tlsIndependentRecord"] == inputs.tls?.evidence?.recordReference) { "PRIVATE_READ_ONLY_HANDOFF_INVALID" }
    }
    override fun toString() = "ReadOnlyHandoff[redacted]"
    private companion object {
        val REQUIRED = setOf(
            "materialReadAuthorization", "startAuthorization", "callerReference", "proofSinkReference", "lifecycleReference", "tlsIndependentRecord",
            "keyStoreReference", "trustStoreReference", "tlsPasswordReferences", "databaseCredentialReference", "decoderProducerReference",
            "decoderTrustPolicyEvidence", "approvedArtifactRootEvidence", "freshPrivateDatabaseEvidence", "newContextPoolPlan",
        )
    }
}

class ReadOnlyMaterialReferences(val databaseConfig: Path?, val tlsConfig: Path?, val tlsEvidence: TlsReferenceEvidence?,
    val verificationKey: RSAPublicKey?, val artifactRoot: Path?) {
    fun resolve(): PrivateReadOnlyInputs {
        require(listOf(databaseConfig, tlsConfig, tlsEvidence, verificationKey, artifactRoot).all { it != null }) { "PRIVATE_READ_ONLY_MATERIAL_INVALID" }
        val root = privatePath(artifactRoot!!.toString(), existing = false)
        val database = readDemoDatabase(databaseConfig!!)
        val tls = ResolvedTlsInput.from(M3Tls.read(tlsConfig!!), tlsEvidence)
        return PrivateReadOnlyInputs(database, tls, DemoJwtDecoder.from(verificationKey!!), root)
    }
    override fun toString() = "ReadOnlyMaterialReferences[redacted]"
}

class ReadOnlyProofFile(private val output: Path,
    private val open: (Path) -> OutputStream = { Files.newOutputStream(it, CREATE_NEW, WRITE, NOFOLLOW_LINKS) }) : (ReadOnlyStartupProof) -> Unit {
    override fun invoke(proof: ReadOnlyStartupProof) {
        val target = privatePath(output.toString(), existing = false)
        val bytes = jacksonObjectMapper().writeValueAsBytes(proof)
        open(target).use { it.write(bytes) }
    }
    override fun toString() = "ReadOnlyProofFile[redacted]"
}

class PrivateReadOnlyCaller(private val contexts: ApplicationContextFactory = ApplicationContextFactory.DEFAULT,
    private val start: (SpringApplication) -> ConfigurableApplicationContext = { it.run() }) {
    fun execute(handoff: ReadOnlyHandoff, resolveInputs: () -> PrivateReadOnlyInputs, proofSink: ReadOnlyProofFile,
        observe: (ConfigurableApplicationContext) -> Unit) {
        handoff.requireReady()
        val inputs = resolveInputs()
        handoff.requireMatches(inputs)
        val application = PrivateReadOnlyBackendBootstrap.application(inputs, proofSink)
        ContextOwner(contexts).use { owner ->
            // Capture ownership when the Context is created, even when run fails before returning it.
            application.setApplicationContextFactory { type -> owner.create(type) }
            val started = start(application)
            check(started === owner.context) { "PRIVATE_READ_ONLY_CONTEXT_INVALID" }
            observe(started)
        }
    }

    private class ContextOwner(private val factory: ApplicationContextFactory) : AutoCloseable,
        MergedBeanDefinitionPostProcessor, InstantiationAwareBeanPostProcessor {
        var context: ConfigurableApplicationContext? = null
            private set
        private val pools = mutableListOf<AutoCloseable>()
        fun create(type: WebApplicationType): ConfigurableApplicationContext {
            check(context == null) { "PRIVATE_READ_ONLY_CONTEXT_INVALID" }
            return factory.create(type).also {
                context = it
                check(it is GenericApplicationContext) { "PRIVATE_READ_ONLY_CONTEXT_INVALID" }
                it.beanFactory.addBeanPostProcessor(this)
            }
        }
        override fun postProcessMergedBeanDefinition(definition: RootBeanDefinition, type: Class<*>, name: String) {
            // Spring's inferred destroy method catches and logs close failures. This owner closes its pool instead.
            if (DataSource::class.java.isAssignableFrom(type)) definition.destroyMethodName = ""
        }
        override fun postProcessAfterInstantiation(bean: Any, name: String): Boolean {
            if (bean !is DataSource) return true
            check(bean is AutoCloseable) { "PRIVATE_READ_ONLY_DATASOURCE_INVALID" }
            if (pools.none { it === bean }) pools.add(bean)
            check(bean !is DisposableBean && pools.size == 1) { "PRIVATE_READ_ONLY_DATASOURCE_INVALID" }
            return true
        }
        override fun close() {
            // Context close must still run when pool close fails; use retains both failures and the run failure.
            context?.use {
                var failure: Throwable? = null
                for (pool in pools) {
                    try { pool.close() } catch (error: Throwable) {
                        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
                    }
                }
                failure?.let { throw it }
            }
        }
    }
}
