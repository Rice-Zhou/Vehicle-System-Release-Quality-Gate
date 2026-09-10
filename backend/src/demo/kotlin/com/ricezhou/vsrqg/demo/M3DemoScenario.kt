package com.ricezhou.vsrqg.demo

import com.fasterxml.jackson.databind.JsonNode
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

class M3Http(private val origin: URI, tls: SSLContext, private val token: String? = null): AutoCloseable {
    private val client=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    fun json(method: String, path: String, body: Any? = null, expected: Int = 200, version: String? = null): JsonNode =
        m3Json.readTree(bytes(method,path,body,expected,1048576,version))
    fun bytes(method: String, path: String, body: Any?, expected: Int, limit: Int, version: String? = null): ByteArray {
        val relative=URI(path)
        require(!relative.isAbsolute && path.startsWith('/') && !path.startsWith("//") && relative.rawFragment==null && !path.contains("..")) { "HTTP_ORIGIN_INVALID" }
        val target=origin.resolve(relative)
        require(target.scheme==origin.scheme && target.host==origin.host && target.port==origin.port && target.rawUserInfo==null) { "HTTP_ORIGIN_INVALID" }
        val request=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(30)).header("Accept","application/json")
        token?.let { request.header("Authorization","Bearer $it") }
        version?.let { request.header("If-Match",it) }
        if(method=="GET") request.GET() else request.header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString())
            .method(method,HttpRequest.BodyPublishers.ofByteArray(m3Json.writeValueAsBytes(body)))
        val response=client.send(request.build(),HttpResponse.BodyHandler { BoundedBody(limit) })
        check(response.statusCode()==expected) { "HTTP_STATUS_${response.statusCode()}" }
        return response.body()
    }
    override fun close() { client.close() }
    private class BoundedBody(private val limit: Int): HttpResponse.BodySubscriber<ByteArray> {
        private val result=CompletableFuture<ByteArray>()
        private val buffer=ByteArrayOutputStream()
        private lateinit var subscription: Flow.Subscription
        override fun getBody(): CompletionStage<ByteArray> = result
        override fun onSubscribe(value: Flow.Subscription) { subscription=value; value.request(1) }
        override fun onNext(items: List<ByteBuffer>) {
            for(item in items) {
                if(item.remaining()>limit-buffer.size()) { subscription.cancel(); result.completeExceptionally(IllegalStateException("HTTP_RESPONSE_LIMIT")); return }
                val bytes=ByteArray(item.remaining()); item.get(bytes); buffer.write(bytes)
            }
            subscription.request(1)
        }
        override fun onError(error: Throwable) { result.completeExceptionally(error) }
        override fun onComplete() { result.complete(buffer.toByteArray()) }
    }
}

class M3AgentProcess(private val process: Process): AutoCloseable {
    private val diagnostics=CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(65537) } }
    fun checkRunning() {
        check(!diagnostics.isDone || diagnostics.get(5,TimeUnit.SECONDS).size<=65536) { "AGENT_DIAGNOSTIC_LIMIT" }
        if(!process.isAlive && process.exitValue()!=0) {
            val bytes=diagnostics.get(5,TimeUnit.SECONDS)
            val code=if(bytes.size>65536) "AGENT_DIAGNOSTIC_LIMIT" else bytes.toString(Charsets.UTF_8).lineSequence()
                .filter { Regex("[A-Z][A-Z0-9_]{2,63}").matches(it) }.lastOrNull() ?: "AGENT_PROCESS_FAILED"
            error(code)
        }
    }
    fun awaitSuccessfulExit(timeout:Duration=Duration.ofSeconds(30)) {
        require(!timeout.isNegative && !timeout.isZero && timeout<=Duration.ofSeconds(30))
        val deadline=System.nanoTime()+timeout.toNanos()
        while(process.isAlive) {
            checkRunning()
            check(System.nanoTime()<deadline) { "AGENT_COMPLETION_TIMEOUT" }
            process.waitFor(25,TimeUnit.MILLISECONDS)
        }
        checkRunning()
    }
    override fun close() {
        try { checkRunning() } finally { stopOwnedProcess() }
    }
    private fun stopOwnedProcess() {
        if(process.isAlive) {
            val children=process.descendants().use { it.toList() }
            children.forEach { it.destroy() }
            process.destroy()
            if(!process.waitFor(5,TimeUnit.SECONDS)) {
                process.destroyForcibly()
                check(process.waitFor(5,TimeUnit.SECONDS)) { "PROCESS_CLEANUP_FAILED" }
            }
            children.filter { it.isAlive }.forEach { child ->
                child.destroyForcibly();child.onExit().get(5,TimeUnit.SECONDS)
            }
        }
    }
    companion object {
        fun start(config: M3Config, targetAttempt:String): M3AgentProcess {
            val repo=Path.of("..").toAbsolutePath().normalize()
            val libs=repo.resolve("agent/build/install/vsrqg-agent/lib")
            check(Files.isDirectory(libs)) { "AGENT_BUILD_REQUIRED" }
            return launch(config,targetAttempt,libs.resolve("*").toString(),"com.ricezhou.vsrqg.agent.AgentMainKt")
        }
        fun launch(config: M3Config, targetAttempt:String, classpath: String, main: String, extra: Map<String,String> = emptyMap()): M3AgentProcess {
            val java=Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            val args=listOf(java.toString(),"-cp",classpath,main,"--server=${config.origin}","--tls-config=${config.agentTlsFile}",
                "--device=${config.deviceId}","--adb-config=${text(config.device,"adbConfig")}","--apk=${config.apk}","--spool=${config.spool}","--until-attempt-acked=$targetAttempt")
            val builder=ProcessBuilder(args).redirectErrorStream(true)
            builder.environment().putAll(extra)
            return M3AgentProcess(builder.start())
        }
    }
}

class M3DemoScenario(private val config: M3Config, private val report: M3DemoReport) {
    fun run(project: String, token: String, launch: (M3Config,String)->M3AgentProcess = M3AgentProcess::start) {
        M3Http(config.origin,config.agentTls.context(false),token).use { user ->
            M3Http(config.origin,config.agentTls.context(true)).use { agent ->
                val registration=agent.json("POST","/agent-api/v1/agents:register",mapOf("messageType" to "AGENT_REGISTRATION","protocolVersion" to "1.0",
                    "agentVersion" to "1.0.0","supportedProtocolVersions" to listOf("1.0"),"deviceRef" to config.deviceId,
                    "capabilities" to listOf("ADB","APK_INSTALL","LOG","SCREENSHOT"),"collectorVersions" to mapOf("LOG" to "1.0","SCREENSHOT" to "1.0")))
                check(registration.path("agentId").asText()==config.agentId) { "AGENT_BINDING_MISMATCH" }
                val releaseFields=mapOf("project" to project,"vehicle" to "synthetic-vehicle","platform" to "synthetic-platform",
                    "systemVersion" to "SYNTHETIC_DEMO","buildId" to UUID.randomUUID().toString())
                val release=user.json("POST","/api/v1/releases",releaseFields,201)
                val releaseId=text(release,"releaseId"); report.releaseId=releaseId
                val manifest=releaseFields + mapOf("manifestVersion" to "0.2","releaseId" to releaseId,"createdAt" to Instant.now().toString(),
                    "artifacts" to listOf(
                        artifact("smoke-apk","APK",checkNotNull(report.apkSha256)) + mapOf("packageName" to "com.ricezhou.vsrqg.smoke","versionCode" to "1","signingCertificateSha256" to text(config.device,"signingCertificateSha256")),
                        artifact("smoke-environment","CONFIG",checkNotNull(report.configSha256))))
                val registered=user.json("POST","/api/v1/releases/$releaseId/manifests",manifest,201)
                report.manifestId=text(registered,"manifestId"); report.manifestDigest=text(registered,"contentDigest")
                check(registered.path("validation").path("status").asText()=="VALID") { "MANIFEST_NOT_VALID" }
                val path="/api/v1/releases/$releaseId/manifests/${report.manifestId}"
                check(user.json("POST","$path:validate",mapOf("reason" to "SYNTHETIC_DEMO")).path("status").asText()=="VALID") { "MANIFEST_NOT_VALID" }
                check(user.json("POST","$path:lock",mapOf("reason" to "SYNTHETIC_DEMO"),version="1").path("state").asText()=="LOCKED") { "MANIFEST_NOT_LOCKED" }
                val run=user.json("POST","/api/v1/test-runs",mapOf("releaseId" to releaseId,"testPlan" to mapOf("planId" to "single-device-smoke","version" to config.planVersion),
                    "deviceSelector" to mapOf("vehicle" to "synthetic-vehicle","requiredCapabilities" to listOf("ADB","APK_INSTALL","LOG","SCREENSHOT"))),201)
                report.runId=text(run,"testRunId")
                val resultsPath="/api/v1/test-runs/${report.runId}/results"
                try {
                    val initial=user.json("GET",resultsPath)
                    require(initial.path("attempts").size()==1) { "ATTEMPT_COUNT_INVALID" }
                    report.attemptId=text(initial.path("attempts")[0],"attemptId")
                    // AgentLoop owns poll -> context -> durable journal -> ACK; a QUEUED Attempt cannot expose context.
                    launch(config,checkNotNull(report.attemptId)).use { process ->
                        val deadline=System.nanoTime()+Duration.ofSeconds(610).toNanos()
                        var results=initial
                        while(results.path("status").asText() !in setOf("COMPLETED","ERROR","TIMEOUT","CANCELLED")) {
                            check(System.nanoTime()<deadline) { "SCENARIO_TIMEOUT" }
                            process.checkRunning(); Thread.sleep(200)
                            results=user.json("GET",resultsPath)
                        }
                        recordResults(results,user)
                        check(user.json("GET",resultsPath)==results) { "RESULT_HISTORY_CHANGED" }
                        process.awaitSuccessfulExit()
                        report.complete()
                    }
                } catch(failure: Exception) {
                    try { user.json("POST","/api/v1/test-runs/${report.runId}:cancel",mapOf("reason" to "SYNTHETIC_DEMO_SCENARIO_FAILED"),202) }
                    catch(cancel: Exception) { failure.addSuppressed(cancel); report.fail("RUN_CANCEL_FAILED") }
                    throw failure
                }
            }
        }
    }
    private fun artifact(id: String,type: String,sha: String): Map<String,Any> = mapOf("artifactId" to id,"type" to type,"name" to "SYNTHETIC_DEMO",
        "version" to "1","source" to "INTERNAL","required" to true,"target" to "synthetic-demo","checksum" to mapOf("algorithm" to "SHA-256","value" to sha))
    private fun recordResults(results: JsonNode, user: M3Http) {
        report.runStatus=text(results,"status")
        check(results.path("runId").asText()==report.runId && results.path("releaseId").asText()==report.releaseId && results.path("manifestId").asText()==report.manifestId &&
            results.path("manifestDigest").asText()==report.manifestDigest && results.path("attempts").size()==1 &&
            results.path("plan").path("planId").asText()=="single-device-smoke" && results.path("plan").path("version").asInt()==config.planVersion && results.path("environment")==m3Json.readTree(config.environmentBytes)) { "RESULT_BINDING_MISMATCH" }
        val attempt=results.path("attempts")[0]; val result=attempt.path("result")
        report.caseStatus=text(result,"status"); report.resultDigest=text(result,"resultDigest")
        check(attempt.path("attemptId").asText()==report.attemptId && result.path("attemptId").asText()==report.attemptId && result.path("testRunId").asText()==report.runId &&
            result.path("releaseId").asText()==report.releaseId && result.path("caseId").asText()=="apk-launch-smoke" && result.path("caseVersion").asInt()==config.planVersion &&
            result.path("origin").asText()=="AGENT" && result.path("agentId").asText()==config.agentId && result.path("deviceId").asText()==config.deviceId) { "RESULT_BINDING_MISMATCH" }
        val ids=result.path("evidenceIds").map { it.asText() }
        check(ids.size==2 && ids.toSet().size==2 && ids.all { Regex("[A-Za-z0-9_-]{1,128}").matches(it) }) { "EVIDENCE_COUNT_INVALID" }
        ids.forEach { id ->
            val metadata=user.json("GET","/api/v1/evidence/$id")
            val type=text(metadata,"type")
            check(type in setOf("LOG","SCREENSHOT") && metadata.path("state").asText()=="AVAILABLE" && metadata.path("integrity").asText()=="VERIFIED" &&
                metadata.path("testRunId").asText()==report.runId && metadata.path("attemptId").asText()==report.attemptId && metadata.path("releaseId").asText()==report.releaseId) { "EVIDENCE_BINDING_MISMATCH" }
            val grant=user.json("POST","/api/v1/evidence/$id:download",mapOf("reason" to "SYNTHETIC_DEMO_SHA256_VERIFICATION"))
            val download=text(grant,"url")
            check(Regex("/api/v1/evidence/${Regex.escape(id)}/payload\\?grantId=[A-Za-z0-9_-]{1,128}").matches(download)) { "DOWNLOAD_GRANT_INVALID" }
            val bytes=user.bytes("GET",download,null,200,if(type=="LOG") 1048576 else 8388608)
            val checksum="sha256:"+sha256(bytes)
            check(bytes.isNotEmpty() && metadata.path("sizeBytes").asLong()==bytes.size.toLong() && text(metadata,"payloadChecksum")==checksum) { "EVIDENCE_CHECKSUM_MISMATCH" }
            val name=if(type=="LOG") "log.txt" else "screenshot.png"
            Files.write(config.output.resolve(name),bytes,CREATE_NEW)
            report.evidence.add(mapOf("evidenceId" to id,"type" to type,"sizeBytes" to bytes.size,"payloadChecksum" to checksum,"downloadSha256" to checksum,"file" to name))
        }
        check(report.evidence.map { it["type"] }.toSet()==setOf("LOG","SCREENSHOT") && report.runStatus=="COMPLETED" &&
            result.path("evidenceRequirements").size()==2 && result.path("evidenceRequirements").all { it.path("state").asText()=="AVAILABLE" }) { "RESULT_INCOMPLETE" }
    }
}

object M3DemoMain {
    @JvmStatic fun main(args: Array<String>) {
        var config: M3Config?=null
        var report: M3DemoReport?=null
        var failed=false
        var stage="CONFIG_INVALID"
        try {
            require(args.isEmpty()) { "CONFIG_INVALID" }
            config=M3Config.read(Path.of(requireNotNull(System.getenv("VSRQG_M3_CONFIG"))))
            if(System.getenv("VSRQG_M3_PHASE")=="VALIDATE") return
            require(System.getenv("VSRQG_M3_PHASE") in setOf(null,"RUN")) { "CONFIG_INVALID" }
            val commit=requireNotNull(System.getenv("VSRQG_M3_COMMIT")); val dirty=requireNotNull(System.getenv("VSRQG_M3_DIRTY")).toBooleanStrict()
            report=M3DemoReport(commit,dirty,"REAL_DEVICE",config.planVersion)
            stage="SCENARIO_FAILED"
            execute(config,report)
        } catch(error: Exception) {
            val code=error.message?.takeIf { Regex("[A-Z][A-Z0-9_]{2,63}").matches(it) } ?: stage
            report?.fail(code); System.err.println(code); failed=true
        } finally {
            if(config!=null && report!=null && report.outputOwned) try { report.write(config.output) } catch(error: Exception) { System.err.println("REPORT_WRITE_FAILED"); failed=true }
        }
        if(failed) kotlin.system.exitProcess(1)
    }
    fun execute(config: M3Config, report: M3DemoReport, launch: (M3Config,String)->M3AgentProcess = M3AgentProcess::start) {
        config.validatePayloadPaths()
        Files.createDirectory(config.output)
        report.outputOwned=true
        Files.createDirectories(config.artifacts)
        check(Files.isDirectory(config.evidenceRoot)) { "EVIDENCE_DIRECTORY_REQUIRED" }
        Files.createDirectories(config.spool)
        fun retain(bytes: ByteArray): String {
            val hash=sha256(bytes); val target=safePath(config.artifacts.resolve(hash).toString(),false)
            if(Files.exists(target)) check(sha256(readBounded(safePath(target.toString()),1048576))==hash) { "ARTIFACT_COLLISION" }
            else Files.write(target,bytes,CREATE_NEW)
            return hash
        }
        report.apkSha256=retain(readBounded(config.apk,1048576)); report.configSha256=retain(config.environmentBytes)
        if(config.lifecycle=="START") {
            val identity=M1DemoIdentity()
            M3DemoBootstrap.start(config,identity).use { context ->
                val actors=M3DemoBootstrap(context).initialize(config)
                val scopes="release:create release:read manifest:write manifest:lock test:execute test:read evidence:read"
                M3DemoScenario(config,report).run(actors.projectKey,identity.token(actors.managerSubject,scopes=scopes),launch)
            }
        } else {
            val token=readBounded(privatePath(text(config.identity,"userTokenFile")),16384).toString(Charsets.UTF_8).trimEnd('\r','\n')
            M3DemoScenario(config,report).run(text(config.identity,"projectKey"),token,launch)
        }
    }
}
