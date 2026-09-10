package com.ricezhou.vsrqg.demo

import com.ricezhou.vsrqg.testmanagement.TestAgentCertificates
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@Timeout(60)
class M3ConfigTest {
    @TempDir lateinit var root:Path
    private fun fixture(unknownAdb:Boolean=false):Path {
        fun json(name:String,value:Any)=Files.write(root.resolve(name),com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsBytes(value)).toString()
        val pass=Files.writeString(root.resolve("password"),TestAgentCertificates.password).toString()
        val tls=json("tls.json",mapOf("keyStore" to TestAgentCertificates.serverStore.resolveSibling("trusted.p12").toString(),
            "trustStore" to TestAgentCertificates.trustStore.toString(),"keyStorePasswordFile" to pass,"trustStorePasswordFile" to pass))
        val token=Files.writeString(root.resolve("user-token"),"TEST_ONLY_NOT_A_VALID_TOKEN").toString()
        val identity=json("identity.json",mapOf("projectKey" to "synthetic","userTokenFile" to token,"agentTlsConfig" to tls))
        val executable=Files.writeString(root.resolve("unused-tool"),"test-only").toString()
        val adb=linkedMapOf<String,Any>("serial" to "CI_FIXTURE","adbExecutable" to executable,"aaptExecutable" to executable,"apksignerJar" to executable)
        if(unknownAdb) adb["secretLiteral"]="SENSITIVE_SENTINEL"
        val environment=json("environment.json",mapOf("bootSessionId" to "00000000-0000-4000-8000-000000000001","buildId" to "fixture","buildFingerprint" to "fixture:35"))
        val device=json("device.json",mapOf("agentId" to "agt_test","deviceId" to "dev_test","adbConfig" to json("adb.json",adb),
            "environmentConfig" to environment,"versionCode" to 1,"signingCertificateSha256" to "a".repeat(64)))
        val apk=Files.write(root.resolve("smoke.apk"),byteArrayOf(1,2,3)).toString()
        return Path.of(json("config.json",mapOf("server" to mapOf("origin" to "https://localhost:8443","lifecycle" to "EXISTING"),
            "identityConfig" to identity,"deviceConfig" to device,"apk" to apk,"planVersion" to 1,
            "payloadRoot" to root.resolve("payload").toString(),"spool" to root.resolve("spool").toString(),"outputRoot" to root.resolve("output").toString())))
    }
    @Test fun `strict valid refs can be parsed without starting any service or device`() {
        val config=M3Config.read(fixture())
        assertThat(config.lifecycle).isEqualTo("EXISTING")
        assertThat(Files.exists(root.resolve("output"))).isFalse()
        assertThat(Files.exists(root.resolve("payload"))).isFalse()
    }
    @Test fun `unknown nested adb config is rejected before any report or execution`() {
        assertThatThrownBy { M3Config.read(fixture(true)) }.hasMessage("CONFIG_INVALID")
        assertThat(Files.exists(root.resolve("output"))).isFalse()
    }
    @Test fun `duplicate fields cannot silently change execution plan`() {
        val path=fixture()
        Files.writeString(path,Files.readString(path).replace("\"planVersion\":1","\"planVersion\":1,\"planVersion\":2"))
        assertThatThrownBy { M3Config.read(path) }.isInstanceOf(com.fasterxml.jackson.core.JsonParseException::class.java)
    }
    @Test fun `credential references cannot be inside Agent spool or payload directories`() {
        val path=fixture()
        val privateDir=Files.createDirectory(root.resolve("sensitive"))
        val pass=Files.move(root.resolve("password"),privateDir.resolve("password"))
        val mapper=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val tls=mapper.readTree(Files.readAllBytes(root.resolve("tls.json"))) as com.fasterxml.jackson.databind.node.ObjectNode
        tls.put("keyStorePasswordFile",pass.toString()).put("trustStorePasswordFile",pass.toString())
        Files.writeString(root.resolve("tls.json"),tls.toString())
        val config=mapper.readTree(Files.readAllBytes(path)) as com.fasterxml.jackson.databind.node.ObjectNode
        config.put("spool",privateDir.toString())
        Files.writeString(path,config.toString())
        assertThatThrownBy { M3Config.read(path) }.hasMessage("CONFIG_INVALID")
        assertThat(Files.exists(root.resolve("output"))).isFalse()
    }
    @Test fun `existing outputs and overlapping execution directories are rejected`() {
        val path=fixture()
        Files.createDirectory(root.resolve("output"))
        assertThatThrownBy { M3Config.read(path) }.hasMessage("CONFIG_INVALID")
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings=["url","username","password"])
    fun `invalid database inputs have fixed configuration diagnostic without reports`(invalid:String) {
        val path=fixture()
        val mapper=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val user=Files.writeString(root.resolve("db-user"),if(invalid=="username") "  " else "demo")
        val password=Files.writeString(root.resolve("db-password"),if(invalid=="password") "\n" else "test-only")
        val db=Files.write(root.resolve("database.json"),mapper.writeValueAsBytes(mapOf(
            "url" to if(invalid=="url") "jdbc:postgresql://remote.invalid:5432/company" else "jdbc:postgresql://localhost:5432/vsrqg_demo",
            "usernameFile" to user.toString(),"passwordFile" to password.toString())))
        Files.write(root.resolve("identity.json"),mapper.writeValueAsBytes(mapOf("databaseConfig" to db.toString(),
            "agentTlsConfig" to root.resolve("tls.json").toString(),"serverTlsConfig" to root.resolve("tls.json").toString())))
        val node=mapper.readTree(Files.readAllBytes(path)) as com.fasterxml.jackson.databind.node.ObjectNode
        (node.path("server") as com.fasterxml.jackson.databind.node.ObjectNode).put("lifecycle","START")
        Files.writeString(path,node.toString())
        assertThatThrownBy { M3Config.read(path) }.hasMessage("CONFIG_INVALID")
        assertThat(Files.exists(root.resolve("output"))).isFalse()
    }
    @Test fun `derived artifacts directory link cannot write external payload or create a report`() {
        val config=M3Config.read(fixture())
        val external=Files.createDirectory(root.resolve("external"))
        Files.createDirectories(config.evidenceRoot)
        if(System.getProperty("os.name").startsWith("Windows")) {
            val builder=ProcessBuilder("pwsh","-NoProfile","-Command",
                "New-Item -ItemType Junction -Path \u0024env:M3_LINK -Target \u0024env:M3_TARGET | Out-Null")
            builder.environment()["M3_LINK"]=config.artifacts.toString();builder.environment()["M3_TARGET"]=external.toString()
            val child=builder.start()
            check(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS) && child.exitValue()==0) { "TEST_JUNCTION_REQUIRED" }
        } else Files.createSymbolicLink(config.artifacts,external)
        // Keep the pre-fix RED bounded and offline after its unauthorized payload writes.
        Files.writeString(root.resolve("user-token"),"")
        try {
            assertThatThrownBy { M3DemoMain.execute(config,M3DemoReport("a".repeat(40),true,"CI_FIXTURE",1)) }.hasMessage("CONFIG_INVALID")
            assertThat(Files.list(external).use { it.toList() }).isEmpty()
            assertThat(Files.exists(config.output)).isFalse()
        } finally { Files.delete(config.artifacts) }
    }
}
