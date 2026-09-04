package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.ObjectMapper
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.ricezhou.vsrqg.traceability.adapter.JcsTraceabilityCanonicalizer
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationFailure
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerifier
import com.ricezhou.vsrqg.traceability.domain.Confidence
import com.ricezhou.vsrqg.traceability.domain.LockedManifest
import com.ricezhou.vsrqg.traceability.domain.PinnedIssueSnapshot
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdge
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdgeAuthority
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdgeType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityEntityType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityExpectedEdgeType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityGapCode
import com.ricezhou.vsrqg.traceability.domain.TraceabilityIssue
import com.ricezhou.vsrqg.traceability.domain.VerificationInput
import com.ricezhou.vsrqg.traceability.domain.VerificationStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TraceabilityCanonicalizerTest {
    private val canonicalizer = JcsTraceabilityCanonicalizer(ObjectMapper())
    private val verifier = TraceabilityVerifier(canonicalizer)

    @Test
    fun `input canonical payload contains only frozen authority identity and ordered fact digests`() {
        val canonical = canonicalizer.canonicalizeInput(
            input(
                listOf(
                    edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "c", "b", "edge-10", revision = 10),
                    edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "c", "b", "edge-2", revision = 2),
                ),
            ),
        )

        assertThat(String(canonical.bytes, StandardCharsets.UTF_8)).isEqualTo(
            "{\"edgeFacts\":[" +
                "{\"edgeType\":\"COMMIT_BUILD\",\"factDigest\":\"sha256:${"a".repeat(64)}\"," +
                "\"sourceEdgeId\":\"edge-10\",\"sourceEdgeRevision\":10}," +
                "{\"edgeType\":\"COMMIT_BUILD\",\"factDigest\":\"sha256:${"a".repeat(64)}\"," +
                "\"sourceEdgeId\":\"edge-2\",\"sourceEdgeRevision\":2}]," +
                "\"issueSnapshot\":{\"digest\":\"sha256:${"1".repeat(64)}\",\"id\":\"isnap-1\"}," +
                "\"manifest\":{\"digest\":\"sha256:${"2".repeat(64)}\",\"revisionId\":\"mrev-1\"}," +
                "\"policyVersion\":\"m2.5-traceability-policy/v1\",\"projectId\":\"project-1\"," +
                "\"releaseId\":\"release-1\"," +
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
            assertThat(candidateResult.issueResults.map { it.resultDigest })
                .containsExactlyElementsOf(baselineResult.issueResults.map { it.resultDigest })
            assertThat(candidateResult.gaps.map { it.gapDigest })
                .containsExactlyElementsOf(baselineResult.gaps.map { it.gapDigest })
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
    fun `gap digest uses exact canonical facts and excludes its own digest`() {
        val result = verifier.verify(input(completeSingleIssueEdges()))
        val gap = result.gaps.single()
        val canonical = canonicalizer.canonicalizeGap(gap)

        assertThat(String(canonical.bytes, StandardCharsets.UTF_8)).isEqualTo(
            "{\"breakEntityId\":\"release-1\",\"breakEntityType\":\"RELEASE\"," +
                "\"diagnosticCode\":\"TEST_RESULT_EVIDENCE_MISSING\"," +
                "\"expectedEdgeType\":\"TEST_RESULT_EVIDENCE\",\"issueId\":\"issue-1\"," +
                "\"predecessorEdgeId\":\"ar-1\",\"predecessorEdgeRevision\":1," +
                "\"predecessorEdgeType\":\"ARTIFACT_RELEASE\"," +
                "\"reason\":\"M2_5_TEST_RESULT_EVIDENCE_NOT_AVAILABLE\"}",
        )
        assertThat(gap.reason).isEqualTo("M2_5_TEST_RESULT_EVIDENCE_NOT_AVAILABLE")
        assertThat(gap.gapDigest).isEqualTo(sha256(canonical.bytes))
        assertThat(String(canonical.bytes, StandardCharsets.UTF_8)).doesNotContain("gapDigest")
    }

    @Test
    fun `issue result digest includes exact derived flags path confidence and gap digest but excludes itself`() {
        val result = verifier.verify(input(emptyList())).issueResults.single()
        val canonical = canonicalizer.canonicalizeIssueResult(result)

        assertThat(String(canonical.bytes, StandardCharsets.UTF_8)).isEqualTo(
            "{\"confidence\":\"UNKNOWN\",\"fixed\":false,\"gaps\":[{" +
                "\"breakEntityId\":\"issue-1\",\"breakEntityType\":\"ISSUE\"," +
                "\"diagnosticCode\":\"ISSUE_COMMIT_MISSING\"," +
                "\"expectedEdgeType\":\"ISSUE_COMMIT\"," +
                "\"gapDigest\":\"${result.gaps.single().gapDigest}\",\"issueId\":\"issue-1\"," +
                "\"predecessorEdgeId\":null,\"predecessorEdgeRevision\":null," +
                "\"predecessorEdgeType\":null," +
                "\"reason\":\"POLICY_VALID_ISSUE_COMMIT_NOT_FOUND\"}]," +
                "\"included\":false,\"issueId\":\"issue-1\",\"path\":[]," +
                "\"sourceIssueId\":\"SRC-1\",\"verified\":false}",
        )
        assertThat(result.resultDigest).isEqualTo(sha256(canonical.bytes))
        assertThat(String(canonical.bytes, StandardCharsets.UTF_8))
            .contains("gapDigest")
            .doesNotContain("resultDigest")
    }

    @Test
    fun `row and global digests cover path fact confidence and fact digest without circular self inclusion`() {
        val baselineInput = input(completeSingleIssueEdges())
        val baseline = verifier.verify(baselineInput)
        val changedEdges = completeSingleIssueEdges().map { edge ->
            if (edge.sourceEdgeId == "ic-1") {
                edge.copy(confidence = Confidence.LOW, factDigest = "sha256:${"f".repeat(64)}")
            } else {
                edge
            }
        }
        val changed = verifier.verify(input(changedEdges))

        assertThat(changed.issueResults.single().confidence).isEqualTo(Confidence.LOW)
        assertThat(changed.issueResults.single().resultDigest)
            .isNotEqualTo(baseline.issueResults.single().resultDigest)
        assertThat(changed.gaps.single().gapDigest).isEqualTo(baseline.gaps.single().gapDigest)
        assertThat(changed.contentDigest).isNotEqualTo(baseline.contentDigest)

        val global = canonicalizer.canonicalizeResult(
            input = input(changedEdges),
            issueResults = changed.issueResults,
            pathEdges = changed.pathEdges,
            gaps = changed.gaps,
        )
        val globalText = String(global.bytes, StandardCharsets.UTF_8)
        assertThat(globalText)
            .contains(changed.issueResults.single().resultDigest)
            .contains(changed.gaps.single().gapDigest)
            .contains(changed.gaps.single().reason)
        assertThat(global.digest).isEqualTo(sha256(global.bytes))
    }

    @Test
    fun `global result payload is byte exact and contains every persisted row digest`() {
        val candidateInput = input(emptyList())
        val result = verifier.verify(candidateInput)
        val issue = result.issueResults.single()
        val gap = result.gaps.single()
        val canonical = canonicalizer.canonicalizeResult(
            candidateInput,
            result.issueResults,
            result.pathEdges,
            result.gaps,
        )

        assertThat(gap.gapDigest).isEqualTo(EXACT_GAP_DIGEST)
        assertThat(issue.resultDigest).isEqualTo(EXACT_ISSUE_RESULT_DIGEST)
        assertThat(String(canonical.bytes, StandardCharsets.UTF_8)).isEqualTo(
            "{\"gaps\":[{" +
                "\"breakEntityId\":\"issue-1\",\"breakEntityType\":\"ISSUE\"," +
                "\"diagnosticCode\":\"ISSUE_COMMIT_MISSING\"," +
                "\"expectedEdgeType\":\"ISSUE_COMMIT\",\"gapDigest\":\"$EXACT_GAP_DIGEST\"," +
                "\"issueId\":\"issue-1\",\"predecessorEdgeId\":null," +
                "\"predecessorEdgeRevision\":null,\"predecessorEdgeType\":null," +
                "\"reason\":\"POLICY_VALID_ISSUE_COMMIT_NOT_FOUND\"}]," +
                "\"input\":{\"edgeFacts\":[]," +
                "\"issueSnapshot\":{\"digest\":\"sha256:${"1".repeat(64)}\",\"id\":\"isnap-1\"}," +
                "\"manifest\":{\"digest\":\"sha256:${"2".repeat(64)}\",\"revisionId\":\"mrev-1\"}," +
                "\"policyVersion\":\"m2.5-traceability-policy/v1\",\"projectId\":\"project-1\"," +
                "\"releaseId\":\"release-1\",\"schemaVersion\":\"traceability-verification/v1\"," +
                "\"validatorVersion\":\"m2.5-path-validator/v1\"}," +
                "\"issueResults\":[{\"confidence\":\"UNKNOWN\",\"fixed\":false," +
                "\"included\":false,\"issueId\":\"issue-1\",\"resultDigest\":" +
                "\"$EXACT_ISSUE_RESULT_DIGEST\"," +
                "\"sourceIssueId\":\"SRC-1\",\"verified\":false}],\"pathEdges\":[]}",
        )
        assertThat(canonical.digest).isEqualTo(sha256(canonical.bytes))
    }

    @Test
    fun `only the JCS adapter may materialize digest bearing traceability domain objects`() {
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.ricezhou.vsrqg.traceability")
        val protectedOwners = listOf(
            "com.ricezhou.vsrqg.traceability.domain.CanonicalTraceability",
            "com.ricezhou.vsrqg.traceability.domain.TraceabilityGap",
            "com.ricezhou.vsrqg.traceability.domain.TraceabilityIssueResult",
            "com.ricezhou.vsrqg.traceability.domain.VerificationComputation",
        )
        val unauthorizedMaterializationCalls = classes.flatMap { javaClass ->
            javaClass.methodCallsFromSelf.filter { call ->
                call.target.name == "materialize" &&
                    protectedOwners.any(call.target.owner.name::startsWith) &&
                    call.originOwner.name != JCS_CANONICALIZER
            }.map { call -> "${call.originOwner.name} -> ${call.target.fullName}" }
        }
        val unauthorizedComputationConstructors = classes.flatMap { javaClass ->
            javaClass.constructorCallsFromSelf.filter { call ->
                call.target.owner.name == protectedOwners.last() &&
                    call.originOwner.name != JCS_CANONICALIZER &&
                    call.originOwner.name != protectedOwners.last() &&
                    call.originOwner.name != "${protectedOwners.last()}\$Companion"
            }.map { call -> "${call.originOwner.name} -> ${call.target.fullName}" }
        }
        val exposedConstructors = protectedOwners.flatMap { owner ->
            classes.get(owner).constructors.filterNot { constructor ->
                JavaModifier.PRIVATE in constructor.modifiers ||
                    constructor.rawParameterTypes.any { it.name == "kotlin.jvm.internal.DefaultConstructorMarker" }
            }
                .map { constructor -> constructor.fullName }
        }

        assertThat(unauthorizedMaterializationCalls + unauthorizedComputationConstructors + exposedConstructors)
            .isEmpty()
    }

    @Test
    fun `malformed UTF16 is rejected across nested input fields before canonicalization`() {
        val malformed = "bad-\uD800"
        val candidates = listOf(
            input(emptyList(), issues = listOf(TraceabilityIssue("issue-1", malformed))),
            input(
                listOf(edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, malformed, "build-1", "cb-1")),
            ),
            input(
                listOf(edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "commit-1", "build-1", malformed)),
            ),
            input(emptyList(), issueSnapshotId = malformed),
            input(emptyList(), manifestRevisionId = malformed),
            input(emptyList(), schemaVersion = malformed),
        )

        candidates.forEach { candidate ->
            assertMalformedInput { canonicalizer.canonicalizeInput(candidate) }
        }
        assertMalformedInput { verifier.verify(candidates.first()) }
    }

    @Test
    fun `malformed UTF16 is rejected at gap and issue result canonical boundaries without value disclosure`() {
        val malformed = "private-\uDC00-value"
        val gap = verifier.verify(input(emptyList())).gaps.single()

        assertCanonicalUnicodeFailure {
            canonicalizer.createGap(
                issueId = "issue-1",
                diagnosticCode = TraceabilityGapCode.ISSUE_COMMIT_MISSING,
                breakEntityType = TraceabilityEntityType.ISSUE,
                breakEntityId = malformed,
                expectedEdgeType = TraceabilityExpectedEdgeType.ISSUE_COMMIT,
                predecessorEdge = null,
            )
        }
        assertCanonicalUnicodeFailure {
            canonicalizer.createIssueResult("issue-1", malformed, emptyList(), listOf(gap))
        }
    }

    @Test
    fun `malformed surrogate cannot collide with a valid replacement character payload`() {
        val valid = canonicalizer.canonicalizeInput(
            input(emptyList(), issues = listOf(TraceabilityIssue("issue-1", "SRC-?"))),
        )

        assertThat(valid.digest).matches("^sha256:[0-9a-f]{64}$")
        assertMalformedInput {
            canonicalizer.canonicalizeInput(
                input(emptyList(), issues = listOf(TraceabilityIssue("issue-1", "SRC-\uD800"))),
            )
        }
    }

    @Test
    fun `issue result flags and confidence are derived rather than caller supplied`() {
        val gap = canonicalizer.createGap(
            issueId = "issue-1",
            diagnosticCode = com.ricezhou.vsrqg.traceability.domain.TraceabilityGapCode.ISSUE_COMMIT_MISSING,
            breakEntityType = com.ricezhou.vsrqg.traceability.domain.TraceabilityEntityType.ISSUE,
            breakEntityId = "issue-1",
            expectedEdgeType = com.ricezhou.vsrqg.traceability.domain.TraceabilityExpectedEdgeType.ISSUE_COMMIT,
            predecessorEdge = null,
        )
        val result = canonicalizer.createIssueResult("issue-1", "SRC-1", emptyList(), listOf(gap))

        assertThat(result.fixed).isFalse()
        assertThat(result.included).isFalse()
        assertThat(result.verified).isFalse()
        assertThat(result.confidence).isEqualTo(Confidence.UNKNOWN)
        assertThat(result.resultDigest).matches("^sha256:[0-9a-f]{64}$")
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

    private fun completeSingleIssueEdges(): List<PinnedTraceabilityEdge> = listOf(
        edge(PinnedTraceabilityEdgeType.ISSUE_COMMIT, "issue-1", "commit-1", "ic-1"),
        edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "commit-1", "build-1", "cb-1"),
        edge(PinnedTraceabilityEdgeType.BUILD_ARTIFACT, "build-1", "artifact-1", "ba-1"),
        manifestEdge("artifact-1", "release-1", "ar-1"),
    )

    private fun unicodeEdges(): List<PinnedTraceabilityEdge> = listOf(
        edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "commit-\uD800\uDC00", "build-1", "edge-\uD800\uDC00"),
        edge(PinnedTraceabilityEdgeType.COMMIT_BUILD, "commit-\uE000", "build-2", "edge-\uE000"),
        edge(PinnedTraceabilityEdgeType.BUILD_ARTIFACT, "build-1", "artifact-1", "ba-1"),
    )

    private fun input(
        edges: List<PinnedTraceabilityEdge>,
        issues: List<TraceabilityIssue> = listOf(TraceabilityIssue("issue-1", "SRC-1")),
        issueSnapshotId: String = "isnap-1",
        manifestRevisionId: String = "mrev-1",
        schemaVersion: String = "traceability-verification/v1",
    ) = VerificationInput(
        schemaVersion = schemaVersion,
        policyVersion = "m2.5-traceability-policy/v1",
        validatorVersion = "m2.5-path-validator/v1",
        projectId = "project-1",
        releaseId = "release-1",
        issueSnapshot = PinnedIssueSnapshot(
            "project-1",
            "release-1",
            issueSnapshotId,
            "sha256:${"1".repeat(64)}",
            issues,
        ),
        manifest = LockedManifest("project-1", "release-1", manifestRevisionId, "sha256:${"2".repeat(64)}"),
        edgeRevisions = edges,
    )

    private fun edge(
        type: PinnedTraceabilityEdgeType,
        fromId: String,
        toId: String,
        sourceEdgeId: String,
        revision: Int = 1,
    ) = PinnedTraceabilityEdge(
        projectId = "project-1",
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

    private fun assertMalformedInput(block: () -> Unit) {
        assertThatThrownBy(block)
            .isInstanceOf(TraceabilityVerificationFailure::class.java)
            .hasMessage("TRACEABILITY_INPUT_NOT_VALID:MALFORMED_UTF16_INPUT")
            .hasMessageNotContaining("private")
            .hasMessageNotContaining("bad")
    }

    private fun assertCanonicalUnicodeFailure(block: () -> Unit) {
        assertThatThrownBy(block)
            .isInstanceOf(TraceabilityVerificationFailure::class.java)
            .hasMessage("TRACEABILITY_CANONICALIZATION_FAILED:MALFORMED_UTF16_CANONICAL_VALUE")
            .hasMessageNotContaining("private")
    }

    private companion object {
        const val JCS_CANONICALIZER =
            "com.ricezhou.vsrqg.traceability.adapter.JcsTraceabilityCanonicalizer"
        const val EXACT_GAP_DIGEST =
            "sha256:5b895a28818cc69952fdd24e2195c96c0af0864450277845ac266f95a1ff898f"
        const val EXACT_ISSUE_RESULT_DIGEST =
            "sha256:c5e5d8cc625660acdc4d3e847367759577741f0d14788fa95563210c7c84abdc"
    }
}
