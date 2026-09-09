package com.ricezhou.vsrqg.demo

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class DemoResult(
    val runId: String,
    val projectKey: String,
    val releaseId: String,
    val manifestId: String,
    val contentDigest: String,
    val scenarioStatuses: Map<String, String>,
)

class M1DemoScenario(
    private val actors: DemoActors,
    private val root: Path,
    private val lookupRejectedManifestId: (String, String) -> String,
    private val sample: Path,
    private val report: DemoReport,
    private val output: Path,
) {
    private val mapper = jacksonObjectMapper()

    fun run(baseUri: URI, managerToken: String, viewerToken: String): DemoResult {
        require(baseUri.scheme == "http" && baseUri.host == "127.0.0.1" && baseUri.port in 1..65535 &&
            baseUri.rawUserInfo == null && baseUri.rawQuery == null && baseUri.rawFragment == null) { "DEMO_HTTP_ORIGIN_INVALID" }
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().use { client ->
            val http = DemoHttp(baseUri, client)
            val createBody = releaseBody("valid")
            report.begin(DemoScenario.UNAUTHENTICATED)
            http.post("/api/v1/releases", createBody, null, 401)
            report.pass()
            report.begin(DemoScenario.VIEWER)
            http.post("/api/v1/releases", createBody, viewerToken, 403)
            report.pass()
            report.begin(DemoScenario.VALID_FILE)
            val createKey = UUID.randomUUID().toString()
            val release = http.post("/api/v1/releases", createBody, managerToken, 201, createKey)
            val releaseId = release.requiredText("releaseId")
            report.releaseId = releaseId
            val bytes = sampleBytes()
            val sha = writePayload(bytes)
            report.payloadSha256 = sha
            val registrationPath = "/api/v1/releases/$releaseId/manifests"
            val manifest = manifest(releaseId, "valid", sha)
            val registerKey = UUID.randomUUID().toString()
            val registration = http.post(registrationPath, manifest, managerToken, 201, registerKey)
            val manifestId = registration.requiredText("manifestId")
            report.manifestId = manifestId
            report.contentDigest = registration.requiredText("contentDigest")
            Files.writeString(output.resolve("manifest.json"), manifest)
            check(registration.path("validation").path("status").asText() == "VALID") { "DEMO_VALIDATION_NOT_VALID" }
            val manifestPath = "$registrationPath/$manifestId"
            val reason = mapper.writeValueAsString(mapOf("reason" to "SYNTHETIC_DEMO"))
            val validation = http.post("$manifestPath:validate", reason, managerToken, 200)
            check(validation == registration.path("validation")) { "DEMO_VALIDATION_HISTORY_CHANGED" }
            // Registration replay requires a registrable Release, so it must precede Lock.
            report.begin(DemoScenario.REPLAY)
            check(release == http.post("/api/v1/releases", createBody, managerToken, 201, createKey)) { "DEMO_RELEASE_REPLAY_CHANGED" }
            check(registration == http.post(registrationPath, manifest, managerToken, 201, registerKey)) { "DEMO_REGISTRATION_REPLAY_CHANGED" }
            report.begin(DemoScenario.VALID_FILE)
            val lockKey = UUID.randomUUID().toString()
            val locked = http.post("$manifestPath:lock", reason, managerToken, 200, lockKey, "1")
            check(locked.path("state").asText() == "LOCKED") { "DEMO_MANIFEST_NOT_LOCKED" }
            report.begin(DemoScenario.REPLAY)
            check(locked == http.post("$manifestPath:lock", reason, managerToken, 200, lockKey, "1")) { "DEMO_LOCK_REPLAY_CHANGED" }
            report.pass()
            report.begin(DemoScenario.VALID_FILE)
            val exported = http.get(manifestPath, managerToken, 200)
            val digest = registration.requiredText("contentDigest")
            check(exported.requiredText("contentDigest") == digest && exported.path("rawManifest") == mapper.readTree(manifest)) { "DEMO_EXPORT_CHANGED" }
            check(exported.path("validation") == validation) { "DEMO_EXPORTED_VALIDATION_CHANGED" }
            report.pass()
            report.begin(DemoScenario.HISTORY)
            Files.write(root.resolve(sha), "changed after registration".toByteArray(Charsets.UTF_8))
            check(http.get(manifestPath, managerToken, 200) == exported) { "DEMO_EXPORT_HISTORY_CHANGED" }
            check(http.post("$manifestPath:validate", reason, managerToken, 200) == validation) { "DEMO_VALIDATION_HISTORY_CHANGED" }
            report.pass()
            report.begin(DemoScenario.CORRUPT_FILE)
            rejectCorruptFile(http, managerToken, reason)
            report.pass()
            return DemoResult(actors.runId, actors.projectKey, releaseId, manifestId, digest, report.scenarioStatuses())
        }
    }

    private fun rejectCorruptFile(http: DemoHttp, token: String, reason: String) {
        val release = http.post("/api/v1/releases", releaseBody("corrupt"), token, 201)
        val releaseId = release.requiredText("releaseId")
        report.corruptReleaseId = releaseId
        val bytes = sampleBytes()
        val sha = writePayload(bytes)
        val damaged = bytes.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        Files.write(root.resolve(sha), damaged)
        val path = "/api/v1/releases/$releaseId/manifests"
        val rejected = http.post(path, manifest(releaseId, "corrupt", sha), token, 422)
        check(rejected.path("code").asText() == "MANIFEST_VALIDATION_FAILED") { "DEMO_CORRUPT_WRONG_FAILURE" }
        check(rejected.path("violations").any { it.path("code").asText() == "ARTIFACT_CHECKSUM_MISMATCH" }) { "DEMO_CORRUPT_CHECKSUM_NOT_REJECTED" }
        report.apiError(DemoApiError.MANIFEST_VALIDATION_FAILED)
        report.apiError(DemoApiError.ARTIFACT_CHECKSUM_MISMATCH)
        val id = lookupRejectedManifestId(actors.projectId, releaseId)
        report.corruptManifestId = id
        val conflict = http.post("$path/$id:lock", reason, token, 409, version = "1")
        check(conflict.path("code").asText() == "MANIFEST_LOCK_CONFLICT") { "DEMO_CORRUPT_LOCK_WRONG_FAILURE" }
        report.apiError(DemoApiError.MANIFEST_LOCK_CONFLICT)
        Files.write(root.resolve(sha), bytes)
        val persisted = http.post("$path/$id:validate", reason, token, 422)
        check(persisted.path("violations") == rejected.path("violations")) { "DEMO_REJECTED_HISTORY_CHANGED" }
    }

    private fun sampleBytes(): ByteArray {
        require(Files.size(sample) in 1..(1024 * 1024)) { "DEMO_SAMPLE_INVALID" }
        return Files.readAllBytes(sample).also {
            require(it.toString(Charsets.UTF_8).startsWith("SYNTHETIC_DEMO")) { "DEMO_SAMPLE_INVALID" }
        }
    }

    private fun writePayload(bytes: ByteArray): String {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        Files.write(root.resolve(sha), bytes)
        return sha
    }

    private fun releaseBody(scenario: String): String = mapper.writeValueAsString(releaseFields(scenario))

    private fun releaseFields(scenario: String): Map<String, String> = mapOf(
        "project" to actors.projectKey, "vehicle" to "synthetic-vehicle", "platform" to "synthetic-platform",
        "systemVersion" to "SYNTHETIC_DEMO", "buildId" to "${actors.runId}-$scenario",
    )

    private fun manifest(releaseId: String, scenario: String, sha: String): String = mapper.writeValueAsString(
        releaseFields(scenario) + mapOf(
            "manifestVersion" to "0.2", "releaseId" to releaseId, "createdAt" to Instant.now().toString(),
            "artifacts" to listOf(mapOf(
                "artifactId" to "synthetic-config", "type" to "CONFIG", "name" to "SYNTHETIC_DEMO.txt",
                "version" to "1", "source" to "INTERNAL", "required" to true, "target" to "synthetic-demo",
                "checksum" to mapOf("algorithm" to "SHA-256", "value" to sha),
            )),
        ),
    )

    private fun JsonNode.requiredText(field: String): String = path(field).asText().takeIf(String::isNotBlank)
        ?: error("DEMO_HTTP_REQUIRED_FIELD_MISSING")

    private inner class DemoHttp(private val origin: URI, private val client: HttpClient) {
        fun post(path: String, body: String, token: String?, status: Int,
                 key: String = UUID.randomUUID().toString(), version: String? = null): JsonNode {
            val request = builder(path, token).header("Content-Type", "application/json")
                .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(body))
            if (version != null) request.header("If-Match", version)
            return send(request.build(), status)
        }
        fun get(path: String, token: String, status: Int): JsonNode = send(builder(path, token).GET().build(), status)
        private fun builder(path: String, token: String?): HttpRequest.Builder = HttpRequest.newBuilder(origin.resolve(path))
            .timeout(Duration.ofSeconds(10)).apply { if (token != null) header("Authorization", "Bearer $token") }
        private fun send(request: HttpRequest, expected: Int): JsonNode {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            report.http(response.statusCode())
            if (response.statusCode() != expected) throw DemoHttpFailure()
            return mapper.readTree(response.body())
        }
    }
}
