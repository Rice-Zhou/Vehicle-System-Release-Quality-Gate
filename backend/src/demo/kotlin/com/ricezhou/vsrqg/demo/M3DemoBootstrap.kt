package com.ricezhou.vsrqg.demo

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ricezhou.vsrqg.VsrqgApplication
import com.ricezhou.vsrqg.manifest.adapter.LocalArtifactPayloadVerifier
import com.ricezhou.vsrqg.manifest.application.ArtifactPayloadVerifier
import org.springframework.boot.Banner
import org.springframework.boot.SpringApplication
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

internal val m3Json = jacksonObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
internal fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun exact(node: JsonNode, fields: Set<String>) {
    require(node.isObject && node.fieldNames().asSequence().toSet() == fields) { "CONFIG_INVALID" }
}
internal fun text(node: JsonNode, field: String): String {
    val value = node.path(field)
    require(value.isTextual && value.asText().isNotBlank()) { "CONFIG_INVALID" }
    return value.asText()
}
internal fun safePath(value: String, existing: Boolean = true): Path {
    val path = Path.of(value)
    require(path.isAbsolute && path.normalize() == path) { "CONFIG_INVALID" }
    generateSequence(path) { it.parent }.forEach { parent ->
        if(Files.exists(parent, NOFOLLOW_LINKS)) {
            val attr = Files.readAttributes(parent, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(!attr.isSymbolicLink && !attr.isOther && parent.toRealPath() == parent.toAbsolutePath()) { "CONFIG_INVALID" }
        }
    }
    if(existing) require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "CONFIG_INVALID" }
    return path
}
internal fun privatePath(value: String, existing: Boolean = true): Path = safePath(value, existing).also { path ->
    require(generateSequence(path.parent) { it.parent }.none { Files.exists(it.resolve(".git")) || Files.exists(it.resolve(".openai/hosting.json")) || it.fileName?.toString() in setOf("public", "static", "wwwroot") }) { "CONFIG_INVALID" }
}
internal fun readBounded(path: Path, limit: Int = 65536): ByteArray {
    safePath(path.toString())
    require(Files.size(path) in 1..limit.toLong()) { "CONFIG_INVALID" }
    return Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(limit + 1) }.also { require(it.size <= limit) { "CONFIG_INVALID" } }
}
internal fun configJson(path: Path) = m3Json.readTree(readBounded(path))

class M3Tls private constructor(val keyStore: Path, val trustStore: Path, private val keyPassword: Path, private val trustPassword: Path) {
    fun references() = listOf(keyStore,trustStore,keyPassword,trustPassword)
    private fun password(path: Path) = readBounded(path, 4096).toString(Charsets.UTF_8).trimEnd('\r', '\n').also { require(it.isNotEmpty()) { "CONFIG_INVALID" } }
    private fun load(path: Path, password: String) = KeyStore.getInstance("PKCS12").apply { readBounded(path, 1048576).inputStream().use { load(it, password.toCharArray()) } }
    fun certificateSha256(): String {
        val store = load(keyStore, password(keyPassword))
        val aliases = store.aliases().asSequence().filter { store.isKeyEntry(it) }.toList()
        require(aliases.size == 1) { "CONFIG_INVALID" }
        return sha256(store.getCertificate(aliases.single()).encoded)
    }
    fun context(clientIdentity: Boolean): SSLContext {
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(load(trustStore, password(trustPassword))) }
        val keys = if(clientIdentity) KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            val pass = password(keyPassword); init(load(keyStore, pass), pass.toCharArray())
        }.keyManagers else null
        return SSLContext.getInstance("TLS").apply { init(keys, trust.trustManagers, null) }
    }
    fun serverProperties(): Map<String, String> = mapOf(
        "server.ssl.enabled" to "true", "server.ssl.key-store" to keyStore.toUri().toString(),
        "server.ssl.key-store-password" to password(keyPassword), "server.ssl.key-store-type" to "PKCS12",
        "server.ssl.trust-store" to trustStore.toUri().toString(), "server.ssl.trust-store-password" to password(trustPassword),
        "server.ssl.trust-store-type" to "PKCS12", "server.ssl.client-auth" to "want",
    )
    companion object {
        fun read(path: Path): M3Tls {
            val node = configJson(path)
            exact(node, setOf("keyStore", "trustStore", "keyStorePasswordFile", "trustStorePasswordFile"))
            return M3Tls(privatePath(text(node,"keyStore")), privatePath(text(node,"trustStore")),
                privatePath(text(node,"keyStorePasswordFile")), privatePath(text(node,"trustStorePasswordFile")))
        }
    }
}

class M3Config private constructor(val origin: URI, val lifecycle: String, val identity: JsonNode, val device: JsonNode,
    val apk: Path, val payloadRoot: Path, val spool: Path, val output: Path, val planVersion: Int, val agentTlsFile: Path,
    val agentTls: M3Tls, val environmentBytes: ByteArray) {
    val agentId = text(device,"agentId")
    val deviceId = text(device,"deviceId")
    val artifacts: Path = payloadRoot.resolve("artifacts")
    val evidenceRoot: Path = payloadRoot.resolve("evidence")
    fun validatePayloadPaths() {
        for(directory in listOf(artifacts,evidenceRoot)) {
            safePath(directory.toString(),false)
            require(!Files.exists(directory,NOFOLLOW_LINKS) || Files.isDirectory(directory,NOFOLLOW_LINKS)) { "CONFIG_INVALID" }
        }
        for(hash in listOf(sha256(readBounded(apk,1048576)),sha256(environmentBytes))) {
            val target=safePath(artifacts.resolve(hash).toString(),false)
            require(!Files.exists(target,NOFOLLOW_LINKS) || Files.isRegularFile(target,NOFOLLOW_LINKS)) { "CONFIG_INVALID" }
        }
    }
    fun database(references: MutableSet<Path>? = null): DemoDatabase {
        val config=privatePath(text(identity,"databaseConfig"));references?.add(config)
        val node = configJson(config)
        exact(node,setOf("url","usernameFile","passwordFile"))
        val user=privatePath(text(node,"usernameFile"));val pass=privatePath(text(node,"passwordFile"))
        references?.addAll(listOf(user,pass))
        return try {
            DemoDatabase(text(node,"url"), readBounded(user,4096).toString(Charsets.UTF_8).trimEnd('\r','\n'),
                readBounded(pass,4096).toString(Charsets.UTF_8).trimEnd('\r','\n'))
        } catch(error:IllegalArgumentException) { throw IllegalArgumentException("CONFIG_INVALID",error) }
    }
    companion object {
        fun read(path: Path): M3Config {
            val references=mutableSetOf<Path>()
            fun ref(value: String) = privatePath(value).also { references.add(it) }
            val node = configJson(ref(path.toString()))
            exact(node,setOf("server","identityConfig","apk","deviceConfig","payloadRoot","spool","outputRoot","planVersion"))
            val server = node.path("server"); exact(server,setOf("origin","lifecycle"))
            val origin = URI(text(server,"origin")); val lifecycle = text(server,"lifecycle")
            require(origin.scheme == "https" && origin.host in setOf("localhost","127.0.0.1","[::1]") && origin.port in 1..65535 &&
                origin.rawUserInfo == null && origin.rawQuery == null && origin.rawFragment == null && origin.path in setOf("","/") && lifecycle in setOf("START","EXISTING")) { "CONFIG_INVALID" }
            val identity = configJson(ref(text(node,"identityConfig")))
            exact(identity,if(lifecycle=="START") setOf("databaseConfig","serverTlsConfig","agentTlsConfig") else setOf("projectKey","userTokenFile","agentTlsConfig"))
            val device = configJson(ref(text(node,"deviceConfig")))
            exact(device,setOf("agentId","deviceId","adbConfig","environmentConfig","versionCode","signingCertificateSha256"))
            require(Regex("[A-Za-z0-9_-]{1,40}").matches(text(device,"agentId")) && Regex("[A-Za-z0-9_-]{1,128}").matches(text(device,"deviceId")) &&
                device.path("versionCode").isIntegralNumber && device.path("versionCode").canConvertToInt() && device.path("versionCode").asInt()==1 && Regex("[a-f0-9]{64}").matches(text(device,"signingCertificateSha256"))) { "CONFIG_INVALID" }
            val adb=configJson(ref(text(device,"adbConfig")))
            exact(adb,setOf("serial","adbExecutable","aaptExecutable","apksignerJar"))
            require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(text(adb,"serial")) && text(adb,"serial") !in setOf("auto","first")) { "CONFIG_INVALID" }
            for(tool in listOf("adbExecutable","aaptExecutable","apksignerJar")) safePath(text(adb,tool))
            val env = readBounded(ref(text(device,"environmentConfig")))
            val envNode=m3Json.readTree(env); exact(envNode,setOf("bootSessionId","buildId","buildFingerprint"))
            envNode.fieldNames().forEachRemaining { require(text(envNode,it).length <= 1024) { "CONFIG_INVALID" } }
            val agentTlsFile=ref(text(identity,"agentTlsConfig")); val tls=M3Tls.read(agentTlsFile);references.addAll(tls.references())
            require(node.path("planVersion").isIntegralNumber && node.path("planVersion").canConvertToInt() && node.path("planVersion").asInt() in 1..2) { "CONFIG_INVALID" }
            val output=privatePath(text(node,"outputRoot"),false); val payload=privatePath(text(node,"payloadRoot"),false); val spool=privatePath(text(node,"spool"),false)
            require(!Files.exists(output,NOFOLLOW_LINKS)) { "CONFIG_INVALID" }
            val roots=listOf(output,payload,spool)
            require(roots.all { !Files.exists(it,NOFOLLOW_LINKS) || Files.isDirectory(it,NOFOLLOW_LINKS) }) { "CONFIG_INVALID" }
            require(roots.indices.all { i -> roots.indices.all { j -> i==j || !roots[i].startsWith(roots[j]) } }) { "CONFIG_INVALID" }
            val apk=safePath(text(node,"apk")); readBounded(apk,1048576)
            require(roots.none { apk.startsWith(it) || path.startsWith(it) }) { "CONFIG_INVALID" }
            val config=M3Config(origin,lifecycle,identity,device,apk,payload,spool,output,node.path("planVersion").asInt(),agentTlsFile,tls,env)
            if(lifecycle=="START") { config.database(references); M3Tls.read(ref(text(identity,"serverTlsConfig"))).also { references.addAll(it.references()) }.serverProperties() }
            else { require(Regex("[A-Za-z0-9_-]{1,120}").matches(text(identity,"projectKey"))) { "CONFIG_INVALID" }; ref(text(identity,"userTokenFile")) }
            require(roots.none { directory -> references.any { it.startsWith(directory) } }) { "CONFIG_INVALID" }
            tls.certificateSha256(); tls.context(false);config.validatePayloadPaths()
            return config
        }
    }
}

data class M3Actors(val projectId: String, val projectKey: String, val managerSubject: String)

class M3DemoBootstrap(private val context: ServletWebServerApplicationContext) {
    fun initialize(config: M3Config): M3Actors {
        val jdbc=context.getBean(JdbcClient::class.java)
        val tx=TransactionTemplate(context.getBean(PlatformTransactionManager::class.java))
        return tx.execute {
            val fingerprint=config.agentTls.certificateSha256()
            val bindings=jdbc.sql("""SELECT a.id,a.device_id,a.certificate_sha256,a.revoked,a.project_id,
                p.project_key,p.name,p.archived,s.id AS principal_id,s.issuer,s.subject,s.principal_type,s.disabled AS principal_disabled,
                d.disabled AS device_disabled,d.vehicle,d.platform,pa.role
                FROM agent a JOIN project p ON p.id=a.project_id JOIN principal s ON s.id=a.principal_id
                JOIN device d ON d.id=a.device_id AND d.project_id=a.project_id
                LEFT JOIN project_assignment pa ON pa.project_id=a.project_id AND pa.principal_id=a.principal_id
                WHERE a.id=:id OR a.device_id=:device OR a.certificate_sha256=:fingerprint""")
                .param("id",config.agentId).param("device",config.deviceId).param("fingerprint",fingerprint).query { row,_ ->
                    mapOf("id" to row.getString("id"),"device" to row.getString("device_id"),"fingerprint" to row.getString("certificate_sha256"),
                        "projectId" to row.getString("project_id"),"projectKey" to row.getString("project_key"),
                        "valid" to (row.getString("name")=="SYNTHETIC_DEMO" && row.getString("project_key").startsWith("demo-") &&
                            !row.getBoolean("archived") && !row.getBoolean("revoked") && !row.getBoolean("principal_disabled") && !row.getBoolean("device_disabled") &&
                            row.getString("issuer")==M1DemoIdentity.ISSUER && row.getString("subject")==row.getString("principal_id") &&
                            row.getString("principal_type")=="SERVICE" && row.getString("role")=="ENGINEER" &&
                            row.getString("vehicle")=="synthetic-vehicle" && row.getString("platform")=="synthetic-platform"))
                }.list()
            val projectId: String
            val projectKey: String
            if(bindings.isNotEmpty()) {
                check(bindings.size==1) { "BOOTSTRAP_IDENTITY_CONFLICT" }
                val binding=bindings.single()
                check(binding["valid"]==true && binding["id"]==config.agentId && binding["device"]==config.deviceId && binding["fingerprint"]==fingerprint) { "BOOTSTRAP_IDENTITY_CONFLICT" }
                projectId=binding.getValue("projectId").toString();projectKey=binding.getValue("projectKey").toString()
                plans(jdbc,false)
            } else {
                check(jdbc.sql("SELECT count(*) FROM device WHERE id=:id").param("id",config.deviceId).query(Int::class.java).single()==0) { "BOOTSTRAP_IDENTITY_CONFLICT" }
                projectId=UUID.randomUUID().toString();projectKey="demo-${UUID.randomUUID()}"
                jdbc.sql("INSERT INTO project(id,project_key,name,created_at) VALUES (:id,:key,'SYNTHETIC_DEMO',now())").param("id",projectId).param("key",projectKey).update()
                val principal=UUID.randomUUID().toString()
                principal(jdbc,projectId,principal,"SERVICE","ENGINEER")
                jdbc.sql("INSERT INTO device(id,project_id,vehicle,platform,created_at) VALUES (:id,:p,'synthetic-vehicle','synthetic-platform',now())")
                    .param("id",config.deviceId).param("p",projectId).update()
                jdbc.sql("INSERT INTO agent(id,principal_id,project_id,device_id,certificate_sha256,created_at) VALUES (:id,:s,:p,:d,:f,now())")
                    .param("id",config.agentId).param("s",principal).param("p",projectId).param("d",config.deviceId).param("f",fingerprint).update()
                plans(jdbc,true)
            }
            // The decoder is process-local. Only the scoped USER session changes on an exact bootstrap reuse.
            val manager=UUID.randomUUID().toString()
            principal(jdbc,projectId,manager,"USER","RELEASE_MANAGER")
            M3Actors(projectId,projectKey,manager)
        } ?: error("BOOTSTRAP_FAILED")
    }
    private fun principal(jdbc: JdbcClient,project: String,id: String,type: String,role: String) {
        jdbc.sql("INSERT INTO principal(id,issuer,subject,principal_type,created_at) VALUES (:id,:issuer,:id,:type,now())")
            .param("id",id).param("issuer",M1DemoIdentity.ISSUER).param("type",type).update()
        jdbc.sql("INSERT INTO project_assignment(project_id,principal_id,role,created_at) VALUES (:p,:s,:role,now())")
            .param("p",project).param("s",id).param("role",role).update()
    }
    private fun plans(jdbc: JdbcClient,allowCreate: Boolean) {
        val count=jdbc.sql("""SELECT (SELECT count(*) FROM test_case_version WHERE case_id='apk-launch-smoke')+
            (SELECT count(*) FROM test_plan_version WHERE plan_id='single-device-smoke')""").query(Int::class.java).single()
        if(count==0) {
            check(allowCreate) { "PUBLISHED_PLAN_CONFLICT" }
            for(version in 1..2) {
                val case=UUID.randomUUID().toString();val plan=UUID.randomUUID().toString()
                jdbc.sql("INSERT INTO test_case_version(id,case_id,version,state,definition,created_at) VALUES (:id,'apk-launch-smoke',:v,'PUBLISHED',CAST(:d AS jsonb),now())")
                    .param("id",case).param("v",version).param("d",definition(version)).update()
                jdbc.sql("INSERT INTO test_plan_version(id,plan_id,version,state,max_attempts,created_at) VALUES (:id,'single-device-smoke',:v,'PUBLISHED',1,now())")
                    .param("id",plan).param("v",version).update()
                jdbc.sql("INSERT INTO test_plan_case(plan_version_id,case_version_id,ordinal,required) VALUES (:p,:c,0,true)").param("p",plan).param("c",case).update()
            }
        }
        check(count==0 || count==4) { "PUBLISHED_PLAN_CONFLICT" }
        for(version in 1..2) {
            val definitions=jdbc.sql("""SELECT c.definition::text FROM test_plan_version p
                JOIN test_plan_case pc ON pc.plan_version_id=p.id JOIN test_case_version c ON c.id=pc.case_version_id
                WHERE p.plan_id='single-device-smoke' AND c.case_id='apk-launch-smoke' AND p.version=:v AND c.version=:v
                AND p.state='PUBLISHED' AND c.state='PUBLISHED' AND p.max_attempts=1 AND pc.ordinal=0 AND pc.required=true
                AND (SELECT count(*) FROM test_plan_case all_cases WHERE all_cases.plan_version_id=p.id)=1""")
                .param("v",version).query(String::class.java).list()
            check(definitions.size==1 && m3Json.readTree(definitions.single())==m3Json.readTree(definition(version))) { "PUBLISHED_PLAN_CONFLICT" }
        }
    }
    private fun definition(version:Int)="""{"caseId":"apk-launch-smoke","version":$version,"mode":"${if(version==1) "normal" else "assertion-failure"}","timeoutMs":300000,"requiredEvidence":["LOG","SCREENSHOT"]}"""
    companion object {
        fun start(config: M3Config, identity: M1DemoIdentity): ServletWebServerApplicationContext {
            val env=M1DemoMain.environment(config.database())
            val props=mapOf("server.port" to config.origin.port.toString(),
                "server.address" to if(config.origin.host=="localhost") "127.0.0.1" else config.origin.host.removeSurrounding("[","]"),
                "vsrqg.demo.agent-registration.enabled" to "true", "vsrqg.demo.smoke.enabled" to "true",
                "vsrqg.demo.smoke.agent-id" to config.agentId, "vsrqg.demo.smoke.device-id" to config.deviceId,
                "vsrqg.demo.smoke.environment-config-base64" to Base64.getEncoder().encodeToString(config.environmentBytes),
                "vsrqg.demo.evidence.enabled" to "true", "vsrqg.demo.evidence.root" to config.evidenceRoot.toString()) +
                M3Tls.read(privatePath(text(config.identity,"serverTlsConfig"))).serverProperties()
            env.propertySources.addFirst(MapPropertySource("isolatedM3Demo",props))
            val app=SpringApplication(VsrqgApplication::class.java)
            app.setEnvironment(env); app.setAddCommandLineProperties(false); app.setLogStartupInfo(false); app.setBannerMode(Banner.Mode.OFF)
            app.addInitializers(org.springframework.context.ApplicationContextInitializer<org.springframework.context.ConfigurableApplicationContext> { raw ->
                val ctx=raw as GenericApplicationContext
                ctx.registerBean("demoJwtDecoder",JwtDecoder::class.java,java.util.function.Supplier { identity.decoder })
                ctx.registerBean("demoPayloadVerifier",ArtifactPayloadVerifier::class.java,java.util.function.Supplier { LocalArtifactPayloadVerifier(config.artifacts) })
            })
            return app.run() as ServletWebServerApplicationContext
        }
    }
}
