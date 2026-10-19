package com.ricezhou.vsrqg.demo

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.UndeclaredThrowableException
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.sql.Connection
import java.time.Instant
import java.util.Date
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.ApplicationContextFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.jdbc.datasource.AbstractDataSource
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.test.util.ReflectionTestUtils

@Timeout(60)
class PrivateReadOnlyCallerTest {
    @TempDir lateinit var root: Path
    private val events = mutableListOf<String>()
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun `H01 missing or blank admission references never resolve materials or create context`() {
        for (key in references().keys) for (value in listOf(null, " ")) {
            var resolutions = 0
            val context = OwnedContext()
            assertThatThrownBy {
                caller(context).execute(ReadOnlyHandoff(references() + (key to value)), {
                    resolutions++; inputs()
                }, ReadOnlyProofFile(root.resolve("must-not-exist.json"))) { error("MUST_NOT_OBSERVE") }
            }.isInstanceOf(IllegalArgumentException::class.java).hasMessage("PRIVATE_READ_ONLY_HANDOFF_INVALID")
            assertThat(resolutions).isZero()
            assertThat(events).isEmpty()
            assertThat(root.resolve("must-not-exist.json")).doesNotExist()
        }
    }

    @Test
    fun `H01 admission is captured and does not require its own runtime outputs or human identities`() {
        val mutable = references().toMutableMap()
        val admission = ReadOnlyHandoff(mutable)
        mutable.clear()
        val context = OwnedContext()
        caller(context).execute(admission, { inputs() }, ReadOnlyProofFile(root.resolve("proof.json"))) {
            assertThat(it).isSameAs(context); events.add("observe")
        }
        assertThat(events).containsExactly("create", "refresh", "observe", "close")
        assertThat(admission.toString()).doesNotContain("SYNTHETIC")
    }

    @Test
    fun `H01 a different nonblank TLS record is rejected before context creation`() {
        assertThatThrownBy {
            caller(OwnedContext()).execute(ReadOnlyHandoff(references() + ("tlsIndependentRecord" to "SYNTHETIC_OTHER_R1")),
                { inputs() }, ReadOnlyProofFile(root.resolve("proof.json"))) { error("MUST_NOT_OBSERVE") }
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessage("PRIVATE_READ_ONLY_HANDOFF_INVALID")
        assertThat(events).isEmpty()
        assertThat(root.resolve("proof.json")).doesNotExist()
    }

    @Test
    fun `H02 unresolved material references fail before opening a nonexistent database file`() {
        val missing = root.resolve("absent-db.json")
        val evidence = TlsReferenceEvidence("SYNTHETIC_R1", root.resolve("key"), root.resolve("trust"))
        val complete = ReadOnlyMaterialReferences(missing, root.resolve("tls.json"), evidence, keys.public as RSAPublicKey, root)
        val candidates = listOf(
            ReadOnlyMaterialReferences(null, complete.tlsConfig, evidence, complete.verificationKey, root),
            ReadOnlyMaterialReferences(missing, null, evidence, complete.verificationKey, root),
            ReadOnlyMaterialReferences(missing, complete.tlsConfig, null, complete.verificationKey, root),
            ReadOnlyMaterialReferences(missing, complete.tlsConfig, evidence, null, root),
            ReadOnlyMaterialReferences(missing, complete.tlsConfig, evidence, complete.verificationKey, null),
        )
        for (candidate in candidates) assertThatThrownBy { candidate.resolve() }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("PRIVATE_READ_ONLY_MATERIAL_INVALID")
        assertThat(missing).doesNotExist()
    }

    @Test
    fun `H02 minimal material adapter resolves only synthetic database and TLS descriptors`() {
        val material = materials()
        val inputs = material.resolve()
        assertThat(inputs.database.url).isEqualTo("jdbc:postgresql://127.0.0.1:56432/vsrqg_demo")
        assertThat(inputs.database.username).isEqualTo("SYNTHETIC_DB_USER")
        assertThat(inputs.tls!!.evidence.recordReference).isEqualTo("SYNTHETIC_R1")
        assertThat(inputs.artifactRoot).isEqualTo(root)
        assertThat(inputs.decoder!!.decode(token()).subject).isEqualTo("SYNTHETIC_SUBJECT")
        assertThat(material.toString()).doesNotContain(root.toString(), "SYNTHETIC")
    }

    @Test
    fun `H02 verify only decoder enforces signature algorithm issuer audience and required times`() {
        val decoder = DemoJwtDecoder.from(keys.public as RSAPublicKey)
        assertThat(decoder.decode(token()).subject).isEqualTo("SYNTHETIC_SUBJECT")
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val invalid = listOf(
            token(signer = other), token(algorithm = JWSAlgorithm.RS512), token(issuer = "http://localhost/wrong"),
            token(audience = listOf("wrong")), token(audience = listOf(M1DemoIdentity.AUDIENCE, "extra")),
            token(expiry = Instant.now().minusSeconds(120)), token(notBefore = Instant.now().plusSeconds(120)),
            token(expiry = null), token(notBefore = null),
        )
        for (value in invalid) assertThatThrownBy { decoder.decode(value) }.isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `H03 real factory proof is fully saved and closed before substitute refresh without secret values`() {
        val output = root.resolve("proof.json")
        val context = OwnedContext(beforeRefresh = {
            val proof = jacksonObjectMapper().readTree(output.toFile())
            assertThat(proof.path("entries").size()).isEqualTo(40)
            assertThat(proof.path("writeInitSqlAbsentAcrossSources").asBoolean()).isTrue()
            val serialized = Files.readString(output)
            assertThat(serialized).doesNotContain("SYNTHETIC_DB_USER", "SYNTHETIC_DB_SECRET", "SYNTHETIC_TLS_SECRET", root.toString())
            val entries = proof.path("entries").associateBy { it.path("key").asText() }
            assertThat(entries).doesNotContainKeys("spring.datasource.username", "spring.datasource.password", "server.ssl.key-store-password", "server.ssl.trust-store-password")
            assertThat(entries.getValue("server.ssl.key-store").path("value").asBoolean()).isTrue()
            assertThat(entries.getValue("spring.datasource.hikari.connection-init-sql").path("present").asBoolean()).isFalse()
        })
        caller(context).execute(ReadOnlyHandoff(references()), { inputs() }, ReadOnlyProofFile(output)) { events.add("observe") }
        assertThat(events).containsExactly("create", "refresh", "observe", "close")
    }

    @Test
    fun `H03 output collision preserves original proof and prevents refresh`() {
        val output = Files.writeString(root.resolve("proof.json"), "ORIGINAL_PROOF")
        assertThatThrownBy {
            caller(OwnedContext()).execute(ReadOnlyHandoff(references()), { inputs() }, ReadOnlyProofFile(output)) { error("MUST_NOT_OBSERVE") }
        }.isInstanceOf(IOException::class.java)
        assertThat(Files.readString(output)).isEqualTo("ORIGINAL_PROOF")
        assertThat(events).containsExactly("create", "close")
    }

    @Test
    fun `H03 missing output parent prevents refresh without creating directories`() {
        val output = root.resolve("missing/proof.json")
        assertThatThrownBy {
            caller(OwnedContext()).execute(ReadOnlyHandoff(references()), { inputs() }, ReadOnlyProofFile(output)) { error("MUST_NOT_OBSERVE") }
        }.isInstanceOf(IOException::class.java)
        assertThat(output.parent).doesNotExist()
        assertThat(events).containsExactly("create", "close")
    }

    @Test
    fun `H03 stream write failure remains primary and close failure remains visible before refresh`() {
        val writeFailure = IOException("SYNTHETIC_WRITE_FAILURE")
        val closeFailure = IOException("SYNTHETIC_CLOSE_FAILURE")
        val sink = ReadOnlyProofFile(root.resolve("proof.json")) {
            object : OutputStream() {
                override fun write(value: Int) { throw writeFailure }
                override fun close() { throw closeFailure }
            }
        }
        assertThatThrownBy {
            caller(OwnedContext()).execute(ReadOnlyHandoff(references()), { inputs() }, sink) { error("MUST_NOT_OBSERVE") }
        }.isSameAs(writeFailure)
        assertThat(writeFailure.suppressed).containsExactly(closeFailure)
        assertThat(events).containsExactly("create", "close")
    }

    @Test
    fun `H03 stream close failure prevents refresh even after successful write`() {
        val failure = IOException("SYNTHETIC_CLOSE_FAILURE")
        val sink = ReadOnlyProofFile(root.resolve("proof.json")) {
            object : OutputStream() {
                override fun write(value: Int) = Unit
                override fun close() { throw failure }
            }
        }
        assertThatThrownBy {
            caller(OwnedContext()).execute(ReadOnlyHandoff(references()), { inputs() }, sink) { error("MUST_NOT_OBSERVE") }
        }.isSameAs(failure)
        assertThat(events).containsExactly("create", "close")
    }

    @Test
    fun `H04 startup failure after context creation closes the partial owned context`() {
        val failure = IllegalStateException("SYNTHETIC_START_FAILURE")
        val operation = caller(OwnedContext(), failStart = failure)
        assertThatThrownBy {
            operation.execute(ReadOnlyHandoff(references()), { inputs() }, ReadOnlyProofFile(root.resolve("proof.json"))) { error("MUST_NOT_OBSERVE") }
        }.isSameAs(failure)
        assertThat(events).containsExactly("create", "close")
    }

    @Test
    fun `H04 observation failure remains primary when owned context close also fails`() {
        val primary = IllegalStateException("SYNTHETIC_OBSERVE_FAILURE")
        val cleanup = IllegalStateException("SYNTHETIC_CONTEXT_CLOSE_FAILURE")
        assertThatThrownBy {
            caller(OwnedContext(closeFailure = cleanup)).execute(ReadOnlyHandoff(references()), { inputs() }, ReadOnlyProofFile(root.resolve("proof.json"))) {
                events.add("observe"); throw primary
            }
        }.isSameAs(primary)
        assertThat(primary.suppressed).containsExactly(cleanup)
        assertThat(events).containsExactly("create", "refresh", "observe", "close")
    }

    @Test
    fun `H04 successful observation cannot hide an owned context close failure`() {
        val cleanup = IllegalStateException("SYNTHETIC_CONTEXT_CLOSE_FAILURE")
        assertThatThrownBy {
            caller(OwnedContext(closeFailure = cleanup)).execute(ReadOnlyHandoff(references()), { inputs() }, ReadOnlyProofFile(root.resolve("proof.json"))) { events.add("observe") }
        }.isSameAs(cleanup)
        assertThat(events).containsExactly("create", "refresh", "observe", "close")
    }

    @Test
    fun `H04 real Spring bean destruction cannot swallow owned pool close failure`() {
        val failure = IllegalStateException("SYNTHETIC_POOL_CLOSE_FAILURE")
        assertThatThrownBy {
            realPoolCaller(listOf(TestPool("pool", failure))).execute(ReadOnlyHandoff(references()), { inputs() },
                ReadOnlyProofFile(root.resolve("proof.json"))) { events.add("observe") }
        }.isSameAs(failure)
        assertThat(events).containsExactly("observe", "pool-close", "context-close")
    }

    @Test
    fun `H04 Boot style early context close cannot discard owned pool failure or close pool twice`() {
        val primary = IllegalStateException("SYNTHETIC_START_FAILURE")
        val cleanup = IllegalStateException("SYNTHETIC_POOL_CLOSE_FAILURE")
        assertThatThrownBy {
            realPoolCaller(listOf(TestPool("pool", cleanup)), earlyFailure = primary).execute(ReadOnlyHandoff(references()),
                { inputs() }, ReadOnlyProofFile(root.resolve("proof.json"))) { error("MUST_NOT_OBSERVE") }
        }.isSameAs(primary)
        assertThat(primary.suppressed).containsExactly(cleanup)
        assertThat(events).containsExactly("context-close", "pool-close", "context-close")
    }

    @Test
    fun `H04 pool failure still closes context and retains both cleanup errors`() {
        val poolFailure = IllegalStateException("SYNTHETIC_POOL_CLOSE_FAILURE")
        val contextFailure = IllegalStateException("SYNTHETIC_CONTEXT_CLOSE_FAILURE")
        assertThatThrownBy {
            realPoolCaller(listOf(TestPool("pool", poolFailure)), closeFailure = contextFailure).execute(ReadOnlyHandoff(references()),
                { inputs() }, ReadOnlyProofFile(root.resolve("proof.json"))) { events.add("observe") }
        }.isSameAs(poolFailure)
        assertThat(poolFailure.suppressed).containsExactly(contextFailure)
        assertThat(events).containsExactly("observe", "pool-close", "context-close")
    }

    @Test
    fun `H04 multiple data sources fail but every captured pool is still closed once`() {
        assertThatThrownBy {
            realPoolCaller(listOf(TestPool("first"), TestPool("second"))).execute(ReadOnlyHandoff(references()),
                { inputs() }, ReadOnlyProofFile(root.resolve("proof.json"))) { error("MUST_NOT_OBSERVE") }
        }.hasRootCauseMessage("PRIVATE_READ_ONLY_DATASOURCE_INVALID")
        assertThat(events).containsExactlyInAnyOrder("first-close", "second-close", "context-close")
    }

    @Test
    fun `H04 property population failure still closes the exposed pool once`() {
        assertThatThrownBy {
            realPoolCaller(listOf(TestPool("pool")), invalidProperty = true).execute(ReadOnlyHandoff(references()),
                { inputs() }, ReadOnlyProofFile(root.resolve("proof.json"))) { error("MUST_NOT_OBSERVE") }
        }.hasRootCauseInstanceOf(org.springframework.beans.NotWritablePropertyException::class.java)
        assertThat(events).containsExactly("pool-close", "context-close")
    }

    @Test
    fun `H04 primary observation failure retains pool and context cleanup failures`() {
        val primary = IllegalStateException("SYNTHETIC_OBSERVE_FAILURE")
        val poolFailure = IllegalStateException("SYNTHETIC_POOL_CLOSE_FAILURE")
        val contextFailure = IllegalStateException("SYNTHETIC_CONTEXT_CLOSE_FAILURE")
        assertThatThrownBy {
            realPoolCaller(listOf(TestPool("pool", poolFailure)), closeFailure = contextFailure).execute(ReadOnlyHandoff(references()),
                { inputs() }, ReadOnlyProofFile(root.resolve("proof.json"))) { events.add("observe"); throw primary }
        }.isSameAs(primary)
        assertThat(primary.suppressed).containsExactly(poolFailure)
        assertThat(poolFailure.suppressed).containsExactly(contextFailure)
        assertThat(events).containsExactly("observe", "pool-close", "context-close")
    }

    private inner class TestPool(private val label: String, private val failure: Throwable? = null) : AbstractDataSource(), AutoCloseable {
        override fun getConnection(): Connection = error("MUST_NOT_CONNECT")
        override fun getConnection(username: String, password: String): Connection = error("MUST_NOT_CONNECT")
        override fun close() { events.add("$label-close"); failure?.let { throw it } }
    }

    private fun realPoolCaller(pools: List<TestPool>, earlyFailure: Throwable? = null, closeFailure: Throwable? = null,
        invalidProperty: Boolean = false): PrivateReadOnlyCaller {
        val context = object : GenericApplicationContext() {
            override fun close() { events.add("context-close"); super.close(); closeFailure?.let { throw it } }
        }
        return PrivateReadOnlyCaller(ApplicationContextFactory { context }, { app ->
            val factory = ReflectionTestUtils.getField(app, "applicationContextFactory") as ApplicationContextFactory
            val owned = factory.create(WebApplicationType.NONE) as GenericApplicationContext
            owned.environment = ReflectionTestUtils.getField(app, "environment") as ConfigurableEnvironment
            pools.forEachIndexed { i, pool -> owned.registerBean("pool-$i", TestPool::class.java, java.util.function.Supplier { pool }) }
            if (invalidProperty) owned.getBeanDefinition("pool-0").propertyValues.add("mustNotExist", "SYNTHETIC")
            ReflectionTestUtils.invokeMethod<Any>(app, "refresh", owned)
            if (earlyFailure != null) { owned.close(); throw earlyFailure }
            owned
        })
    }

    private inner class OwnedContext(private val closeFailure: Throwable? = null, private val beforeRefresh: () -> Unit = {}) : GenericApplicationContext() {
        override fun refresh() { beforeRefresh(); events.add("refresh") }
        override fun close() { events.add("close"); closeFailure?.let { throw it } }
    }

    private fun caller(context: OwnedContext, failStart: Throwable? = null) = PrivateReadOnlyCaller(
        ApplicationContextFactory { events.add("create"); context },
        { app: SpringApplication ->
            val factory = ReflectionTestUtils.getField(app, "applicationContextFactory") as ApplicationContextFactory
            val owned = factory.create(WebApplicationType.NONE)
            if (failStart != null) throw failStart
            owned.environment = ReflectionTestUtils.getField(app, "environment") as ConfigurableEnvironment
            try {
                ReflectionTestUtils.invokeMethod<Any>(app, "refresh", owned)
            } catch (transport: UndeclaredThrowableException) {
                // The test reflection transport wraps checked IO failures; preserve the sink failure itself.
                throw transport.undeclaredThrowable
            }
            owned
        },
    )

    private fun inputs(): PrivateReadOnlyInputs {
        val refs = materials()
        return PrivateReadOnlyInputs(DemoDatabase("jdbc:postgresql://127.0.0.1:56432/vsrqg_demo", "SYNTHETIC_DB_USER", "SYNTHETIC_DB_SECRET"),
            ResolvedTlsInput.from(M3Tls.read(refs.tlsConfig!!), refs.tlsEvidence),
            org.springframework.security.oauth2.jwt.JwtDecoder { throw UnsupportedOperationException("NOT_EVALUATED") }, root)
    }

    private fun materials(): ReadOnlyMaterialReferences {
        fun json(path: Path) = path.toString().replace("\\", "\\\\")
        val key = Files.writeString(root.resolve("key.p12"), "SYNTHETIC_NOT_A_CERTIFICATE")
        val trust = Files.writeString(root.resolve("trust.p12"), "SYNTHETIC_NOT_A_CERTIFICATE")
        val pass = Files.writeString(root.resolve("tls-password.txt"), "SYNTHETIC_TLS_SECRET")
        val user = Files.writeString(root.resolve("db-user.txt"), "SYNTHETIC_DB_USER")
        val dbPass = Files.writeString(root.resolve("db-password.txt"), "SYNTHETIC_DB_SECRET")
        val tls = Files.writeString(root.resolve("tls.json"), """{"keyStore":"${json(key)}","trustStore":"${json(trust)}","keyStorePasswordFile":"${json(pass)}","trustStorePasswordFile":"${json(pass)}"}""")
        val db = Files.writeString(root.resolve("db.json"), """{"url":"jdbc:postgresql://127.0.0.1:56432/vsrqg_demo","usernameFile":"${json(user)}","passwordFile":"${json(dbPass)}"}""")
        return ReadOnlyMaterialReferences(db, tls, TlsReferenceEvidence("SYNTHETIC_R1", key, trust), keys.public as RSAPublicKey, root)
    }

    private fun token(signer: KeyPair = keys, algorithm: JWSAlgorithm = JWSAlgorithm.RS256, issuer: String = M1DemoIdentity.ISSUER,
        audience: List<String> = listOf(M1DemoIdentity.AUDIENCE), expiry: Instant? = Instant.now().plusSeconds(600), notBefore: Instant? = Instant.now().minusSeconds(1)): String {
        val claims = JWTClaimsSet.Builder().subject("SYNTHETIC_SUBJECT").issuer(issuer).audience(audience)
            .apply { expiry?.let { expirationTime(Date.from(it)) }; notBefore?.let { notBeforeTime(Date.from(it)) } }.build()
        return SignedJWT(JWSHeader(algorithm), claims).apply { sign(RSASSASigner(signer.private)) }.serialize()
    }

    private fun references(): Map<String, String?> = listOf(
        "materialReadAuthorization", "startAuthorization", "callerReference", "proofSinkReference", "lifecycleReference", "tlsIndependentRecord",
        "keyStoreReference", "trustStoreReference", "tlsPasswordReferences", "databaseCredentialReference", "decoderProducerReference",
        "decoderTrustPolicyEvidence", "approvedArtifactRootEvidence", "freshPrivateDatabaseEvidence", "newContextPoolPlan",
    ).associateWith { if (it == "tlsIndependentRecord") "SYNTHETIC_R1" else "SYNTHETIC_REFERENCE_$it" }
}
