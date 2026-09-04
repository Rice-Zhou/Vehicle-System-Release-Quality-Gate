package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.traceability.adapter.JcsTraceabilityCanonicalizer
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerifier
import com.ricezhou.vsrqg.traceability.domain.Confidence
import com.ricezhou.vsrqg.traceability.domain.LockedManifest
import com.ricezhou.vsrqg.traceability.domain.PinnedIssueSnapshot
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdge
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdgeAuthority
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdgeType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityIssue
import com.ricezhou.vsrqg.traceability.domain.VerificationInput
import com.ricezhou.vsrqg.traceability.domain.VerificationStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TraceabilityCanonicalizerTest {
    private val canonicalizer = JcsTraceabilityCanonicalizer(ObjectMapper())
    private val verifier = TraceabilityVerifier(canonicalizer)

    @Test
    fun `input canonical payload contains only frozen authority identity and ordered fact digests`() {
        val canonical = canonicalizer.canonicalizeInput(
            input(
                listOf(
                    edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "c", "b", "edge", revision = 10),
                    edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "c", "b", "edge", revision = 2),
                ),
            ),
        )

        assertThat(String(canonical.bytes, StandardCharsets.UTF_8)).isEqualTo(
            "{\"edgeFacts\":[" +
                "{\"edgeType\":\"COMMIT_BUILD\",\"factDigest\":\"sha256:${"a".repeat(64)}\"," +
                "\"sourceEdgeId\":\"edge\",\"sourceEdgeRevision\":2}," +
                "{\"edgeType\":\"COMMIT_BUILD\",\"factDigest\":\"sha256:${"a".repeat(64)}\"," +
                "\"sourceEdgeId\":\"edge\",\"sourceEdgeRevision\":10}]," +
                "\"issueSnapshot\":{\"digest\":\"sha256:${"1".repeat(64)}\",\"id\":\"isnap-1\"}," +
                "\"manifest\":{\"digest\":\"sha256:${"2".repeat(64)}\",\"revisionId\":\"mrev-1\"}," +
                "\"policyVersion\":\"m2.5-traceability-policy/v1\",\"releaseId\":\"release-1\"," +
                "\"schemaVersion\":\"traceability-verification/v1\"," +
                "\"validatorVersion\":\"m2.5-path-validator/v1\"}",
        )
        assertThat(canonical.digest).isEqualTo(sha256(canonical.bytes))
        assertThat(String(canonical.bytes, StandardCharsets.UTF_8))
            .doesNotContain("fromId", "toId", "verificationStatus", "confidence", "request", "actor", "timestamp")
    }

    @Test
    fun `semantically identical input order has identical canonical bytes and digest one hundred times`() {
        val edges = unicodeEdges()
        val issues = listOf(
            TraceabilityIssue("issue-astral", "SRC-\uD800\uDC00"),
            TraceabilityIssue("issue-bmp", "SRC-\uE000"),
        )
        val baseline = canonicalizer.canonicalizeInput(input(edges, issues))

        repeat(100) { seed ->
            val shuffled = canonicalizer.canonicalizeInput(
                input(edges.shuffled(Random(seed)), issues.shuffled(Random(seed + 100))),
            )
            assertThat(shuffled.bytes).containsExactly(*baseline.bytes)
            assertThat(shuffled.digest).isEqualTo(baseline.digest)
        }
    }

    @Test
    fun `result canonical bytes and digest are stable for shuffled equivalent graphs`() {
        val edges = completeTwoIssueEdges()
        val issues = listOf(
            TraceabilityIssue("issue-astral", "SRC-\uD800\uDC00"),
            TraceabilityIssue("issue-bmp", "SRC-\uE000"),
        )
        val baselineInput = input(edges, issues)
        val baselineResult = verifier.verify(baselineInput)
        val baseline = canonicalizer.canonicalizeResult(
            baselineInput,
            baselineResult.issueResults,
            baselineResult.pathEdges,
            baselineResult.gaps,
        )

        repeat(100) { seed ->
            val candidateInput = input(edges.shuffled(Random(seed)), issues.shuffled(Random(seed + 200)))
            val candidateResult = verifier.verify(candidateInput)
            val candidate = canonicalizer.canonicalizeResult(
                candidateInput,
                candidateResult.issueResults.shuffled(Random(seed + 300)),
                candidateResult.pathEdges.shuffled(Random(seed + 400)),
                candidateResult.gaps.shuffled(Random(seed + 500)),
            )

            assertThat(candidate.bytes).containsExactly(*baseline.bytes)
            assertThat(candidate.digest).isEqualTo(baseline.digest)
            assertThat(candidateResult.contentDigest).isEqualTo(baselineResult.contentDigest)
        }
    }

    @Test
    fun `canonical serialization replay is byte exact and defensive`() {
        val candidateInput = input(completeTwoIssueEdges())
        val result = verifier.verify(candidateInput)
        val replays = List(3) {
            canonicalizer.canonicalizeResult(candidateInput, result.issueResults, result.pathEdges, result.gaps)
        }

        assertThat(replays.map { it.digest }).containsOnly(replays.first().digest)
        assertThat(replays.drop(1)).allSatisfy { replay ->
            assertThat(replay.bytes).containsExactly(*replays.first().bytes)
        }

        val exposed = replays.first().bytes
        exposed[0] = 'X'.code.toByte()
        assertThat(replays.first().bytes.first()).isEqualTo('{'.code.toByte())
    }

    @Test
    fun `unicode issue ordering uses code points rather than utf16 units`() {
        val issues = listOf(
            TraceabilityIssue("issue-astral", "SRC-\uD800\uDC00"),
            TraceabilityIssue("issue-bmp", "SRC-\uE000"),
        )
        val candidateInput = input(emptyList(), issues)
        val result = verifier.verify(candidateInput)
        val text = String(
            canonicalizer.canonicalizeResult(candidateInput, result.issueResults, result.pathEdges, result.gaps).bytes,
            StandardCharsets.UTF_8,
        )

        assertThat(text.indexOf("issue-bmp")).isLessThan(text.indexOf("issue-astral"))
    }

    @Test
    fun `numeric revision changes digest and remains a json number`() {
        val revisionTwo = canonicalizer.canonicalizeInput(
            input(listOf(edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "c", "b", "edge", revision = 2))),
        )
        val revisionTen = canonicalizer.canonicalizeInput(
            input(listOf(edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "c", "b", "edge", revision = 10))),
        )

        assertThat(revisionTwo.digest).isNotEqualTo(revisionTen.digest)
        assertThat(String(revisionTwo.bytes, StandardCharsets.UTF_8))
            .contains("\"sourceEdgeRevision\":2")
            .doesNotContain("\"sourceEdgeRevision\":\"2\"")
    }

    private fun completeTwoIssueEdges(): List<PinnedTraceabilityEdge> = listOf(
        edge(PinnedTraceabilityEdgeType.ISSUE_COMMIT, "issue-bmp", "commit-bmp", "ic-bmp"),
        edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "commit-bmp", "build-bmp", "cb-bmp"),
        edge(PinnedTraceabilityEdgeType.BUILD_ARTIFACT, "build-bmp", "artifact-bmp", "ba-bmp"),
        manifestEdge("artifact-bmp", "release-1", "ar-bmp"),
        edge(PinnedTraceabilityEdgeType.ISSUE_COMMIT, "issue-astral", "commit-astral", "ic-astral"),
        edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "commit-astral", "build-astral", "cb-astral"),
        edge(PinnedTraceabilityEdgeType.BUILD_ARTIFACT, "build-astral", "artifact-astral", "ba-astral"),
        manifestEdge("artifact-astral", "release-1", "ar-astral"),
    )

    private fun unicodeEdges(): List<PinnedTraceabilityEdge> = listOf(
        edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "commit-\uD800\uDC00", "build-1", "edge-\uD800\uDC00"),
        edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "commit-\uE000", "build-2", "edge-\uE000"),
        edge(PinnedTraceabilityEdgeType.BUILD_ARTIFACT, "build-1", "artifact-1", "ba-1"),
    )

    private fun input(
        edges: List<PinnedTraceabilityEdge>,
        issues: List<TraceabilityIssue> = listOf(TraceabilityIssue("issue-1", "SRC-1")),
    ) = VerificationInput(
        schemaVersion = "traceability-verification/v1",
        policyVersion = "m2.5-traceability-policy/v1",
        validatorVersion = "m2.5-path-validator/v1",
        releaseId = "release-1",
        issueSnapshot = PinnedIssueSnapshot("isnap-1", "sha256:${"1".repeat(64)}", issues),
        manifest = LockedManifest("release-1", "mrev-1", "sha256:${"2".repeat(64)}"),
        edgeRevisions = edges,
    )

    private fun edge(
        type: PinnedTraceabilityEdgeType,
        fromId: String,
        toId: String,
        sourceEdgeId: String,
        revision: Int = 1,
    ) = PinnedTraceabilityEdge(
        edgeType = type,
        fromId = fromId,
        toId = toId,
        sourceEdgeId = sourceEdgeId,
        sourceEdgeRevision = revision,
        verificationStatus = VerificationStatus.VALID,
        confidence = Confidence.HIGH,
        factDigest = "sha256:${"a".repeat(64)}",
        authority = PinnedTraceabilityEdgeAuthority.EDGE_REVISION,
    )

    private fun manifestEdge(fromId: String, toId: String, sourceEdgeId: String) =
        edge(PinnedTraceabilityEdgeType.ARTIFACT_RELEASE, fromId, toId, sourceEdgeId)
            .copy(authority = PinnedTraceabilityEdgeAuthority.LOCKED_MANIFEST)

    private fun sha256(bytes: ByteArray): String =
        "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
