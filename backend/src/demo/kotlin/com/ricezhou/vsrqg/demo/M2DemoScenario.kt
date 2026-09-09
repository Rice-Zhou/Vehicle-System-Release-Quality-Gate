package com.ricezhou.vsrqg.demo

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ricezhou.vsrqg.traceability.adapter.JcsBuildProvenanceCanonicalizer
import com.ricezhou.vsrqg.traceability.domain.BuildProvenanceEnvelope
import com.ricezhou.vsrqg.traceability.domain.ProvenanceProviderId
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

class M2DemoScenario(
    private val report: M2DemoReport,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
    private val invalidFixture: M2InvalidFixture? = null,
    private val observeSnapshotBytes: (String, ByteArray) -> Unit = { _, _ -> },
) {
    fun run(
        baseUri: URI,
        managerToken: String,
        engineerToken: String,
        serviceToken: String,
        sourceId: String,
        m1: DemoResult,
        payloadSha256: String,
    ) {
        requireOrigin(baseUri)
        HttpClient.newBuilder().connectTimeout(REQUEST_LIMIT).build().use { client ->
            val http = Http(baseUri, client)
            val mapping = http.post("mappingProfile", "/api/v1/issue-sources/$sourceId/mapping-profiles:activate",
                mapper.writeValueAsString(M2DemoInputs.mappingDefinition()), managerToken, 201)
            requiredText(mapping.node, "mappingVersion")
            report.pass("mappingProfile")

            val sync = http.post("issueSync", "/api/v1/issue-sources/$sourceId/sync", "{}", managerToken, 202)
            val syncRunId = requiredText(sync.node, "syncRunId")
            val syncResult = poll(http, "issueSync", "/api/v1/issue-sync-runs/$syncRunId", managerToken)
            check(syncResult.path("issueCount").takeIf(JsonNode::isInt)?.intValue() == 2) { "M2_ISSUE_COUNT_INVALID" }
            report.pass("issueSync")

            val snapshot = http.post("issueSnapshot", "/api/v1/releases/${m1.releaseId}/issue-snapshots",
                mapper.writeValueAsString(mapOf("sourceId" to sourceId)), managerToken, 201)
            val issueSnapshotId = requiredText(snapshot.node, "snapshotId")
            check(snapshot.node.path("selectedCount").takeIf(JsonNode::isInt)?.intValue() == 2) { "M2_ISSUE_SNAPSHOT_INVALID" }
            report.pass("issueSnapshot")
            report.identities(m1.releaseId, m1.manifestId, syncRunId, issueSnapshotId)

            val denied = http.post("userIngestionRejected", INGEST, buildBody(m1, issueSnapshotId, payloadSha256, 1),
                engineerToken, 403)
            check(requiredText(denied.node, "code") == "ACCESS_DENIED") { "M2_USER_INGESTION_NOT_REJECTED" }
            report.pass("userIngestionRejected")

            val build1Key = UUID.randomUUID().toString()
            val build1 = http.post("buildIngestion", INGEST, buildBody(m1, issueSnapshotId, payloadSha256, 1),
                serviceToken, 200, build1Key)
            val build1Replay = http.post("sameKeyReplay", INGEST, buildBody(m1, issueSnapshotId, payloadSha256, 1),
                serviceToken, 200, build1Key)
            check(build1.bytes.contentEquals(build1Replay.bytes)) { "M2_INGEST_REPLAY_CHANGED" }
            check(requiredText(build1.node, "verificationStatus") == "VALID") { "M2_BUILD_INVALID" }
            report.pass("buildIngestion")
            report.pass("sameKeyReplay")

            val a = verify(http, m1.releaseId, sourceId, engineerToken, "A")
            observeSnapshotBytes("A", a.snapshot.bytes.copyOf())
            assertSnapshot(a.snapshot.node, secondIncluded = false)
            report.snapshot("A", a.snapshot.node, a.runId)

            val build2 = http.post("buildIngestion", INGEST, buildBody(m1, issueSnapshotId, payloadSha256, 2),
                serviceToken, 200)
            check(requiredText(build2.node, "verificationStatus") == "VALID" &&
                requiredText(build2.node, "buildRecordId") != requiredText(build1.node, "buildRecordId")) { "M2_BUILD_2_INVALID" }
            val b = verify(http, m1.releaseId, sourceId, engineerToken, "B")
            observeSnapshotBytes("B", b.snapshot.bytes.copyOf())
            assertSnapshot(b.snapshot.node, secondIncluded = true)
            report.snapshot("B", b.snapshot.node, b.runId)

            val aAgain = http.get("historyStable", "/api/v1/releases/${m1.releaseId}/traceability?snapshotId=${a.snapshotId}", engineerToken, 200)
            observeSnapshotBytes("A_AGAIN", aAgain.bytes.copyOf())
            check(aAgain.bytes.contentEquals(a.snapshot.bytes)) { "M2_SNAPSHOT_A_CHANGED" }
            val latest = http.get("historyStable", "/api/v1/releases/${m1.releaseId}/traceability", engineerToken, 200)
            check(requiredText(latest.node.path("snapshot"), "snapshotId") == b.snapshotId) { "M2_LATEST_NOT_B" }
            report.historyStable(b.snapshotId, snapshotABytesStable = true)
            runInvalidFacts(http, payloadSha256)
        }
    }

    private fun runInvalidFacts(http: Http, payloadSha256: String) {
        val fixture = checkNotNull(invalidFixture) { "M2_INVALID_FIXTURE_REQUIRED" }
        http.post("invalidFactsRejected", "/api/v1/issue-sources/${fixture.sourceId}/mapping-profiles:activate",
            mapper.writeValueAsString(M2DemoInputs.mappingDefinition()), fixture.managerToken, 201)
        val sync = http.post("invalidFactsRejected", "/api/v1/issue-sources/${fixture.sourceId}/sync", "{}",
            fixture.managerToken, 202)
        poll(http, "invalidFactsRejected", "/api/v1/issue-sync-runs/${requiredText(sync.node, "syncRunId")}", fixture.managerToken)
        val issueSnapshot = http.post("invalidFactsRejected",
            "/api/v1/releases/${fixture.m1.releaseId}/issue-snapshots",
            mapper.writeValueAsString(mapOf("sourceId" to fixture.sourceId)), fixture.managerToken, 201)
        val issueSnapshotId = requiredText(issueSnapshot.node, "snapshotId")
        val invalid = http.post("invalidFactsRejected", INGEST,
            buildBody(fixture.m1, issueSnapshotId, payloadSha256, 1, validProof = false), fixture.serviceToken, 200)
        check(requiredText(invalid.node, "verificationStatus") == "INVALID") { "M2_INVALID_FACT_NOT_PERSISTED" }
        val rejected = http.post("invalidFactsRejected",
            "/api/v1/releases/${fixture.m1.releaseId}/traceability:verify",
            mapper.writeValueAsString(mapOf("sourceId" to fixture.sourceId)), fixture.engineerToken, 422)
        check(requiredText(rejected.node, "code") == "TRACEABILITY_INPUT_NOT_VALID") { "M2_INVALID_FACT_NOT_REJECTED" }
        report.pass("invalidFactsRejected")
    }

    private fun verify(http: Http, releaseId: String, sourceId: String, token: String, label: String): Verification {
        val key = UUID.randomUUID().toString()
        val body = mapper.writeValueAsString(mapOf("sourceId" to sourceId))
        val accepted = http.post("sameKeyReplay", "/api/v1/releases/$releaseId/traceability:verify", body, token, 202, key)
        val replay = http.post("sameKeyReplay", "/api/v1/releases/$releaseId/traceability:verify", body, token, 202, key)
        check(accepted.bytes.contentEquals(replay.bytes)) { "M2_VERIFY_REPLAY_CHANGED" }
        val runId = requiredText(accepted.node, "verificationRunId")
        val statusUrl = requiredText(accepted.node, "statusUrl")
        requireLocalPath(statusUrl, "/api/v1/traceability-verification-runs/$runId")
        val completed = poll(http, "snapshot$label", statusUrl, token)
        val snapshotId = requiredText(completed, "resultSnapshotId")
        val snapshot = http.get("snapshot$label", "/api/v1/releases/$releaseId/traceability?snapshotId=$snapshotId", token, 200)
        return Verification(runId, snapshotId, snapshot)
    }

    private fun poll(http: Http, scenario: String, path: String, token: String): JsonNode {
        val deadline = System.nanoTime() + TOTAL_LIMIT.toNanos()
        while (true) {
            val remaining = Duration.ofNanos(deadline - System.nanoTime())
            check(!remaining.isNegative && !remaining.isZero) { "M2_POLL_TIMEOUT" }
            val result = http.get(scenario, path, token, 200, minOf(REQUEST_LIMIT, remaining))
            when (report.runStatus(result.node)) {
                "SUCCEEDED" -> return result.node
                "FAILED" -> error("M2_ASYNC_RUN_FAILED")
                else -> {
                    val sleepNanos = minOf(POLL_INTERVAL.toNanos(), deadline - System.nanoTime())
                    check(sleepNanos > 0) { "M2_POLL_TIMEOUT" }
                    Thread.sleep(Duration.ofNanos(sleepNanos))
                }
            }
        }
    }

    private fun buildBody(m1: DemoResult, issueSnapshotId: String, sha: String, number: Int, validProof: Boolean = true): String {
        val issue = "DEMO-$number"
        val revision = if (number == 1) "a".repeat(40) else "b".repeat(40)
        val draft = BuildProvenanceEnvelope(2, "unused", issueSnapshotId, ProvenanceProviderId("github-actions"),
            "vsrqg-synthetic/demo", revision, "synthetic-m2", number.toString(), 1,
            "vsrqg-synthetic/demo/.github/workflows/demo.yml@synthetic",
            "https://github.com/vsrqg-synthetic/demo/actions/runs/$number/attempts/1",
            "sha256:${"0".repeat(64)}", listOf(issue), listOf(sha))
        val project = m1.projectKey
        val envelope = draft.copy(projectReference = project)
        val digest = JcsBuildProvenanceCanonicalizer(mapper).canonicalize(envelope).recomputedProofDigest
        return mapper.writeValueAsString(linkedMapOf(
            "schemaVersion" to 2, "project" to project, "releaseIssueSnapshotId" to issueSnapshotId,
            "provider" to "GITHUB_ACTIONS", "repository" to envelope.repository,
            "sourceRevision" to revision, "pipeline" to envelope.pipeline, "buildId" to number.toString(),
            "buildAttempt" to 1, "workflowReference" to envelope.workflowReference,
            "proofReference" to envelope.proofReference,
            "proofDigest" to if (validProof) digest else "sha256:${"0".repeat(64)}",
            "sourceIssueIds" to listOf(issue), "artifactSha256s" to listOf(sha),
        ))
    }

    private fun assertSnapshot(root: JsonNode, secondIncluded: Boolean) {
        val issues = root.path("issues").associateBy { requiredText(it, "sourceIssueId") }
        val one = issues.getValue("DEMO-1")
        val two = issues.getValue("DEMO-2")
        check(requiredBoolean(one, "fixed") && requiredBoolean(one, "included") && !requiredBoolean(one, "verified"))
        check(pathEdgeTypes(one) == EXPECTED_INCLUDED_PATH)
        check(requiredArray(one, "gaps").map { requiredText(it, "diagnosticCode") } == listOf("TEST_RESULT_EVIDENCE_MISSING"))
        check(requiredBoolean(two, "included") == secondIncluded && !requiredBoolean(two, "verified"))
        check(if (secondIncluded) pathEdgeTypes(two) == EXPECTED_INCLUDED_PATH else requiredArray(two, "path").isEmpty())
        check(requiredArray(two, "gaps").map { requiredText(it, "diagnosticCode") } ==
            listOf(if (secondIncluded) "TEST_RESULT_EVIDENCE_MISSING" else "ISSUE_COMMIT_MISSING"))
    }

    private fun requiredText(node: JsonNode, field: String): String =
        node.get(field)?.takeIf(JsonNode::isTextual)?.textValue()?.takeIf(String::isNotBlank) ?: error("M2_HTTP_FIELD_INVALID")
    private fun requiredBoolean(node: JsonNode, field: String): Boolean =
        node.get(field)?.takeIf(JsonNode::isBoolean)?.booleanValue() ?: error("M2_HTTP_FIELD_INVALID")
    private fun requiredArray(node: JsonNode, field: String): List<JsonNode> =
        node.get(field)?.takeIf(JsonNode::isArray)?.toList() ?: error("M2_HTTP_FIELD_INVALID")
    private fun pathEdgeTypes(issue: JsonNode): List<String> =
        requiredArray(issue, "path").map { requiredText(it, "edgeType") }
    private fun requireOrigin(uri: URI) = require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1..65535 &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "M2_HTTP_ORIGIN_INVALID" }
    private fun requireLocalPath(actual: String, expected: String) = require(actual == expected) { "M2_STATUS_URL_INVALID" }

    private inner class Http(private val origin: URI, private val client: HttpClient) {
        fun post(scenario: String, path: String, body: String, token: String, expected: Int,
                 key: String = UUID.randomUUID().toString()): Response {
            val request = builder(path, token, REQUEST_LIMIT).header("Content-Type", "application/json")
                .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(body)).build()
            return send(scenario, request, expected)
        }
        fun get(scenario: String, path: String, token: String, expected: Int, timeout: Duration = REQUEST_LIMIT): Response =
            send(scenario, builder(path, token, timeout).GET().build(), expected)
        private fun builder(path: String, token: String, timeout: Duration) = HttpRequest.newBuilder(origin.resolve(path))
            .timeout(timeout).header("Authorization", "Bearer $token")
        private fun send(scenario: String, request: HttpRequest, expected: Int): Response {
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            report.http(scenario, response.statusCode())
            if (response.statusCode() != expected) throw DemoHttpFailure()
            return Response(response.body(), mapper.readTree(response.body()))
        }
    }

    private data class Response(val bytes: ByteArray, val node: JsonNode)
    private data class Verification(val runId: String, val snapshotId: String, val snapshot: Response)
    private companion object {
        val REQUEST_LIMIT: Duration = Duration.ofSeconds(5)
        val TOTAL_LIMIT: Duration = Duration.ofSeconds(30)
        val POLL_INTERVAL: Duration = Duration.ofMillis(250)
        val EXPECTED_INCLUDED_PATH = listOf("ISSUE_COMMIT", "COMMIT_BUILD", "BUILD_ARTIFACT", "ARTIFACT_RELEASE")
        const val INGEST = "/api/v1/traceability/facts:ingest"
    }
}

data class M2InvalidFixture(
    val sourceId: String,
    val managerToken: String,
    val engineerToken: String,
    val serviceToken: String,
    val m1: DemoResult,
)
