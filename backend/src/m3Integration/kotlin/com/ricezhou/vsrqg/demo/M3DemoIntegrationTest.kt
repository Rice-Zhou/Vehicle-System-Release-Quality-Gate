package com.ricezhou.vsrqg.demo

import com.ricezhou.vsrqg.evidence.ownedTestRoot
import com.ricezhou.vsrqg.testmanagement.TestAgentCertificates
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.containers.PostgreSQLContainer
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Timeout(60)
class M3DemoIntegrationTest {
    @ParameterizedTest @ValueSource(ints=[1,2])
    fun `actual TLS API chain retains both payloads and expected source case status`(version:Int) {
        val apk=Path.of(requireNotNull(System.getenv("VSRQG_M3_APK")) { "M3_APK_REQUIRED" }).toAbsolutePath()
        val signer=requireNotNull(System.getenv("VSRQG_M3_APK_SIGNER")) { "M3_APK_SIGNER_REQUIRED" }
        val classpath=Files.readString(Path.of(requireNotNull(System.getenv("VSRQG_M3_FIXTURE_CLASSPATH")) { "M3_FIXTURE_CLASSPATH_REQUIRED" }))
        val commit=requireNotNull(System.getenv("VSRQG_M3_COMMIT")) { "M3_COMMIT_REQUIRED" }
        val root=Files.createTempDirectory("vsrqg-m3-ci-")
        val artifactRoot=Path.of(requireNotNull(System.getProperty("m3.artifactRoot")) { "M3_ARTIFACT_ROOT_REQUIRED" })
        val artifacts=Files.createDirectory(artifactRoot.resolve("plan-$version"))
        val report=M3DemoReport(commit,requireNotNull(System.getenv("VSRQG_M3_DIRTY")).toBooleanStrict(),"CI_FIXTURE",version)
        val output=root.resolve("output")
        try {
            PostgreSQLContainer("postgres:17.11").withDatabaseName("vsrqg_demo").use { postgres ->
                postgres.start()
                fun json(name:String,value:Any):String = Files.write(root.resolve(name),com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsBytes(value)).toString()
                fun file(name:String,value:String):String = Files.writeString(root.resolve(name),value).toString()
                val pass=file("tls-password",TestAgentCertificates.password)
                fun tls(name:String,key:Path)=json(name,mapOf("keyStore" to key.toString(),"trustStore" to TestAgentCertificates.trustStore.toString(),"keyStorePasswordFile" to pass,"trustStorePasswordFile" to pass))
                val clientTls=tls("client-tls.json",TestAgentCertificates.serverStore.resolveSibling("trusted.p12"))
                val serverTls=tls("server-tls.json",TestAgentCertificates.serverStore)
                val database=json("database.json",mapOf("url" to "jdbc:postgresql://127.0.0.1:${postgres.getMappedPort(5432)}/vsrqg_demo",
                    "usernameFile" to file("db-user",postgres.username),"passwordFile" to file("db-pass",postgres.password)))
                val identity=json("identity.json",mapOf("databaseConfig" to database,"serverTlsConfig" to serverTls,"agentTlsConfig" to clientTls))
                val environment=json("environment.json",mapOf("bootSessionId" to UUID.randomUUID().toString(),"buildId" to "CI_FIXTURE","buildFingerprint" to "synthetic/fixture:35"))
                val unused=file("unused-tool","CI_FIXTURE"); val dummy=json("adb-test-only.json",mapOf("serial" to "CI_FIXTURE","adbExecutable" to unused,"aaptExecutable" to unused,"apksignerJar" to unused))
                val suffix=UUID.randomUUID().toString().replace("-","")
                val device=json("device.json",mapOf("agentId" to "agt_$suffix","deviceId" to "dev_$suffix","adbConfig" to dummy,
                    "environmentConfig" to environment,"versionCode" to 1,"signingCertificateSha256" to signer))
                val port=ServerSocket(0).use { it.localPort }
                val payload=root.resolve("payload");Files.createDirectories(payload)
                ownedTestRoot(Files.createDirectory(payload.resolve("evidence"))).also { p ->
                    if(Files.getFileStore(p).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
                }
                val configPath=json("config.json",mapOf("server" to mapOf("origin" to "https://localhost:$port","lifecycle" to "START"),
                    "identityConfig" to identity,"deviceConfig" to device,"apk" to apk.toString(),"planVersion" to version,
                    "payloadRoot" to payload.toString(),"spool" to root.resolve("spool").toString(),"outputRoot" to output.toString()))
                val config=M3Config.read(Path.of(configPath))
                val launch: (M3Config)->M3AgentProcess = { c ->
                    M3AgentProcess.launch(c,classpath,"com.ricezhou.vsrqg.agent.M3FixtureAgentMain",mapOf("VSRQG_M3_EXECUTION_MODE" to "CI_FIXTURE","VSRQG_M3_ENVIRONMENT" to environment))
                }
                if(version==1) {
                    M3DemoMain.execute(config,report,launch)
                    val repeatedOutput=root.resolve("repeated-output")
                    val repeatedReport=M3DemoReport(commit,report.workingTreeDirty,"CI_FIXTURE",2)
                    val node=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(Files.readAllBytes(Path.of(configPath))) as com.fasterxml.jackson.databind.node.ObjectNode
                    node.put("planVersion",2).put("outputRoot",repeatedOutput.toString())
                    Files.writeString(Path.of(configPath),node.toString())
                    val repeatedConfig=M3Config.read(Path.of(configPath))
                    try {
                        M3DemoMain.execute(repeatedConfig,repeatedReport,launch)
                        assertThat(repeatedReport.runId).isNotEqualTo(report.runId)
                        assertThat(repeatedReport.caseStatus).isEqualTo("FAIL")
                        assertThat(repeatedReport.evidence).hasSize(2)
                        M3DemoBootstrap.start(config,M1DemoIdentity()).use { retained ->
                            val jdbc=retained.getBean(org.springframework.jdbc.core.simple.JdbcClient::class.java)
                            val before=jdbc.sql("SELECT result_digest FROM test_result ORDER BY id").query(String::class.java).list()
                            assertThat(before).containsExactlyInAnyOrder(report.resultDigest,repeatedReport.resultDigest)
                            assertThat(jdbc.sql("SELECT count(*) FROM agent").query(Int::class.java).single()).isEqualTo(1)
                            assertThat(jdbc.sql("SELECT count(*) FROM device").query(Int::class.java).single()).isEqualTo(1)
                            assertThat(jdbc.sql("SELECT count(*) FROM project").query(Int::class.java).single()).isEqualTo(1)
                            val changedDevice=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(Files.readAllBytes(Path.of(device))) as com.fasterxml.jackson.databind.node.ObjectNode
                            changedDevice.put("agentId","agt_conflict")
                            node.put("deviceConfig",json("conflicting-device.json",changedDevice)).put("outputRoot",root.resolve("never-created").toString())
                            Files.writeString(Path.of(configPath),node.toString())
                            val conflict=M3Config.read(Path.of(configPath))
                            val users=jdbc.sql("SELECT count(*) FROM principal WHERE principal_type='USER'").query(Int::class.java).single()
                            assertThatThrownBy { M3DemoBootstrap(retained).initialize(conflict) }.hasMessage("BOOTSTRAP_IDENTITY_CONFLICT")
                            assertThat(jdbc.sql("SELECT count(*) FROM principal WHERE principal_type='USER'").query(Int::class.java).single()).isEqualTo(users)
                            assertThat(jdbc.sql("SELECT result_digest FROM test_result ORDER BY id").query(String::class.java).list()).isEqualTo(before)
                            assertThat(Files.exists(root.resolve("never-created"))).isFalse()
                        }
                    } catch(error:Throwable) { repeatedReport.fail("REPEAT_START_FAILED");throw error }
                    finally {
                        Files.createDirectories(repeatedOutput);repeatedReport.write(repeatedOutput)
                        val retained=Files.createDirectory(artifactRoot.resolve("plan-2-reuse"))
                        for(name in listOf("summary.json","log.txt","screenshot.png")) {
                            val file=repeatedOutput.resolve(name)
                            if(Files.exists(file)) Files.copy(file,retained.resolve(name))
                        }
                    }
                }
                else {
                    val userIdentity=M1DemoIdentity()
                    M3DemoBootstrap.start(config,userIdentity).use { existing ->
                        val actors=M3DemoBootstrap(existing).initialize(config)
                        val token=userIdentity.token(actors.managerSubject,scopes="release:create release:read manifest:write manifest:lock test:execute test:read evidence:read")
                        val existingIdentity=json("existing-identity.json",mapOf("projectKey" to actors.projectKey,"agentTlsConfig" to clientTls,"userTokenFile" to file("user-token",token)))
                        val node=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(Files.readAllBytes(Path.of(configPath))) as com.fasterxml.jackson.databind.node.ObjectNode
                        (node.path("server") as com.fasterxml.jackson.databind.node.ObjectNode).put("lifecycle","EXISTING")
                        node.put("identityConfig",existingIdentity)
                        Files.writeString(Path.of(configPath),node.toString())
                        M3DemoMain.execute(M3Config.read(Path.of(configPath)),report,launch)
                        assertThat(existing.isActive).isTrue()
                        M3Http(config.origin,config.agentTls.context(false),token).use { http ->
                            assertThat(http.json("GET","/api/v1/test-runs/${report.runId}/results").path("status").asText()).isEqualTo("COMPLETED")
                        }
                    }
                }
                assertThat(report.caseStatus).isEqualTo(if(version==1) "PASS" else "FAIL")
                assertThat(report.runStatus).isEqualTo("COMPLETED")
                assertThat(report.runId).startsWith("run_")
                assertThat(report.evidence.map { it["type"] }).containsExactlyInAnyOrder("LOG","SCREENSHOT")
                report.evidence.forEach { evidence ->
                    val bytes=Files.readAllBytes(output.resolve(evidence["file"].toString()))
                    assertThat("sha256:"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(evidence["payloadChecksum"]).isEqualTo(evidence["downloadSha256"])
                    assertThat(bytes.size).isEqualTo(evidence["sizeBytes"])
                }
                assertThat(report.apkSha256).isEqualTo(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(apk))))
            }
        } catch(error:Throwable) {
            report.fail(error.message?.takeIf { Regex("[A-Z][A-Z0-9_]{2,63}").matches(it) } ?: "CI_FIXTURE_FAILED")
            throw error
        } finally {
            Files.createDirectories(output)
            report.write(output)
            for(name in listOf("summary.json","log.txt","screenshot.png")) {
                val source=output.resolve(name)
                if(Files.exists(source)) Files.copy(source,artifacts.resolve(name))
            }
            // Private ephemeral material stays outside exported artifacts. Test cert helper handles its own lifecycle.
        }
    }
}
