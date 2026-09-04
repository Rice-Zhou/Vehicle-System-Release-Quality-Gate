package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.traceability.adapter.TraceabilityConfidence
import com.ricezhou.vsrqg.traceability.adapter.TraceabilityEntityType
import com.ricezhou.vsrqg.traceability.adapter.TraceabilityExpectedEdgeType
import com.ricezhou.vsrqg.traceability.adapter.TraceabilityGap
import com.ricezhou.vsrqg.traceability.adapter.TraceabilityGapDiagnosticCode
import com.ricezhou.vsrqg.traceability.adapter.TraceabilityIssueResult
import com.ricezhou.vsrqg.traceability.adapter.TraceabilityPathEdge
import com.ricezhou.vsrqg.traceability.adapter.TraceabilityPathEdgeType
import com.ricezhou.vsrqg.traceability.adapter.TraceabilitySnapshotHeader
import com.ricezhou.vsrqg.traceability.adapter.TraceabilitySnapshotResponse
import com.ricezhou.vsrqg.traceability.adapter.TraceabilityVerifyRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class TraceabilityVerificationDtoTest {
    private val mapper = ObjectMapper().findAndRegisterModules()

    @Test
    fun `identifier sourceId means issue source and unknown fields fail`() {
        val request = mapper.readValue("""{"sourceId":"jira-main"}""", TraceabilityVerifyRequest::class.java)

        assertThat(request.issueSourceId).isEqualTo("jira-main")
        assertThatThrownBy {
            mapper.readValue(
                """{"sourceId":"jira-main","edgeSource":"latest"}""",
                TraceabilityVerifyRequest::class.java,
            )
        }.hasMessageContaining("INVALID_TRACEABILITY_VERIFY_REQUEST")
    }

    @Test
    fun `snapshot response normalizes issue path and gap order with deterministic tie breaks`() {
        val response = TraceabilitySnapshotResponse(
            snapshot = snapshotHeader(),
            issues = listOf(
                issue(
                    issueId = "issue-b",
                    sourceIssueId = "ISSUE-1",
                    path = listOf(
                        pathEdge("sha256:cccc"),
                        pathEdge("sha256:aaaa"),
                        pathEdge("sha256:dddd", revision = 10),
                        pathEdge("sha256:eeee", revision = 2),
                        pathEdge(
                            factDigest = "sha256:bbbb",
                            edgeType = TraceabilityPathEdgeType.BUILD_ARTIFACT,
                            fromId = "build-a",
                            toId = "artifact-a",
                            edgeId = "edge-build-artifact",
                        ),
                    ),
                    gaps = listOf(
                        gap("sha256:dddd", TraceabilityGapDiagnosticCode.BUILD_ARTIFACT_MISSING, "build-b"),
                        gap("sha256:cccc", TraceabilityGapDiagnosticCode.BUILD_ARTIFACT_MISSING, "build-a"),
                        gap("sha256:bbbb", TraceabilityGapDiagnosticCode.BUILD_ARTIFACT_MISSING, "build-a"),
                        gap("sha256:aaaa", TraceabilityGapDiagnosticCode.ARTIFACT_RELEASE_MISSING, "artifact-a"),
                    ),
                ),
                issue("issue-a", "ISSUE-1"),
                issue("issue-c", "ISSUE-0"),
            ),
        )

        assertThat(response.issues.map(TraceabilityIssueResult::issueId))
            .containsExactly("issue-c", "issue-a", "issue-b")
        assertThat(response.issues.last().path.map(TraceabilityPathEdge::factDigest))
            .containsExactly("sha256:aaaa", "sha256:cccc", "sha256:eeee", "sha256:dddd", "sha256:bbbb")
        assertThat(response.issues.last().gaps.map(TraceabilityGap::gapDigest))
            .containsExactly("sha256:bbbb", "sha256:cccc", "sha256:dddd", "sha256:aaaa")
    }

    private fun issue(
        issueId: String,
        sourceIssueId: String,
        path: List<TraceabilityPathEdge> = emptyList(),
        gaps: List<TraceabilityGap> = emptyList(),
    ) = TraceabilityIssueResult(
        issueId = issueId,
        sourceIssueId = sourceIssueId,
        fixed = false,
        included = false,
        verified = false,
        path = path,
        gaps = gaps,
        confidence = TraceabilityConfidence.UNKNOWN,
    )

    private fun pathEdge(
        factDigest: String,
        edgeType: TraceabilityPathEdgeType = TraceabilityPathEdgeType.ISSUE_COMMIT,
        fromId: String = "issue-a",
        toId: String = "commit-a",
        edgeId: String = "edge-issue-commit",
        revision: Int = 1,
    ) = TraceabilityPathEdge(
        edgeId = edgeId,
        edgeType = edgeType,
        revisionId = "revision-1",
        revision = revision,
        fromId = fromId,
        toId = toId,
        factDigest = factDigest,
    )

    private fun gap(
        gapDigest: String,
        diagnosticCode: TraceabilityGapDiagnosticCode,
        interruptedEntityId: String,
    ) = TraceabilityGap(
        diagnosticCode = diagnosticCode,
        interruptedEntityType = if (interruptedEntityId.startsWith("artifact")) {
            TraceabilityEntityType.ARTIFACT
        } else {
            TraceabilityEntityType.BUILD
        },
        interruptedEntityId = interruptedEntityId,
        expectedEdgeType = if (diagnosticCode == TraceabilityGapDiagnosticCode.ARTIFACT_RELEASE_MISSING) {
            TraceabilityExpectedEdgeType.ARTIFACT_RELEASE
        } else {
            TraceabilityExpectedEdgeType.BUILD_ARTIFACT
        },
        predecessorEdgeId = "edge-predecessor",
        predecessorRevision = 1,
        gapDigest = gapDigest,
    )

    private fun snapshotHeader() = TraceabilitySnapshotHeader(
        snapshotId = "snapshot-1",
        releaseId = "release-1",
        version = 1,
        issueSnapshotId = "issue-snapshot-1",
        manifestRevisionId = "manifest-revision-1",
        manifestDigest = "sha256:manifest",
        policyVersion = "policy-v1",
        validatorVersion = "validator-v1",
        inputDigest = "sha256:input",
        contentDigest = "sha256:content",
        createdAt = Instant.EPOCH,
    )
}
