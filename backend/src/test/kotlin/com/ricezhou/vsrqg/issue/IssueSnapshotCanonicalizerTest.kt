package com.ricezhou.vsrqg.issue

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.issue.adapter.JcsIssueSnapshotCanonicalizer
import com.ricezhou.vsrqg.issue.application.IssueSnapshotCandidate
import com.ricezhou.vsrqg.issue.application.SNAPSHOT_AGE_POLICY_VERSION
import com.ricezhou.vsrqg.issue.application.SnapshotObservation
import com.ricezhou.vsrqg.issue.application.SnapshotContentIntegrityFailure
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class IssueSnapshotCanonicalizerTest {
    private val objectMapper = ObjectMapper()
    private val canonicalizer = JcsIssueSnapshotCanonicalizer(objectMapper)

    @Test
    fun `canonical snapshot is byte stable and excludes creation metadata`() {
        val first = canonicalizer.canonicalize(candidate(listOf(issue("B"), issue("A"))))
        val second = canonicalizer.canonicalize(candidate(listOf(issue("A"), issue("B"))))
        val third = canonicalizer.canonicalize(candidate(listOf(issue("B"), issue("A"))))

        assertThat(first.bytes).containsExactly(*second.bytes).containsExactly(*third.bytes)
        assertThat(first.digest).isEqualTo(second.digest).isEqualTo(third.digest)
        assertThat(first.digest).matches("sha256:[0-9a-f]{64}")

        val json = String(first.bytes, StandardCharsets.UTF_8)
        assertThat(json).doesNotContain("snapshotId", "createdAt", "actor", "idempotency", "transaction")
    }

    @Test
    fun `canonical snapshot filters tombstones assigns stable ordinals and renders UTC microseconds`() {
        val canonical = canonicalizer.canonicalize(
            candidate(
                listOf(
                    issue("B", observedAt = Instant.parse("2026-09-02T12:00:00.987654999Z")),
                    issue("deleted", tombstone = true),
                    issue("A", observedAt = Instant.parse("2026-09-02T12:00:00Z")),
                ),
            ),
        )
        val root = objectMapper.readTree(canonical.bytes)

        assertThat(root.path("schemaVersion").asText()).isEqualTo("release-issue-snapshot/v1")
        assertThat(root.path("canonicalizationVersion").asText()).isEqualTo("release-issue-snapshot-jcs/v1")
        assertThat(root.path("observedCount").asInt()).isEqualTo(3)
        assertThat(root.path("tombstoneCount").asInt()).isOne()
        assertThat(root.path("selectedCount").asInt()).isEqualTo(2)
        assertThat(root.path("items").map { it.path("sourceIssueId").asText() }).containsExactly("A", "B")
        assertThat(root.path("items").map { it.path("ordinal").asInt() }).containsExactly(0, 1)
        assertThat(root.path("items")[0].path("observedAt").asText()).isEqualTo("2026-09-02T12:00:00.000000Z")
        assertThat(root.path("items")[1].path("observedAt").asText()).isEqualTo("2026-09-02T12:00:00.987654Z")
        assertThat(String(canonical.bytes, StandardCharsets.UTF_8)).doesNotContain("deleted")
    }

    @Test
    fun `empty snapshot remains a valid versioned fact and unknown policy fails closed`() {
        val empty = canonicalizer.canonicalize(candidate(emptyList()))
        val root = objectMapper.readTree(empty.bytes)

        assertThat(root.path("observedCount").asInt()).isZero()
        assertThat(root.path("selectedCount").asInt()).isZero()
        assertThat(root.path("items")).isEmpty()
        assertThatThrownBy {
            canonicalizer.canonicalize(candidate(emptyList()).copy(agePolicyVersion = "unknown-policy/v2"))
        }.isInstanceOf(SnapshotContentIntegrityFailure::class.java)
            .hasCauseInstanceOf(IllegalArgumentException::class.java)
            .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `duplicate issue or source issue identity fails closed`() {
        val first = issue("A")

        listOf(
            listOf(first, issue("B").copy(issueId = first.issueId)),
            listOf(first, issue("B").copy(sourceIssueId = first.sourceIssueId)),
        ).forEach { duplicated ->
            assertThatThrownBy { canonicalizer.canonicalize(candidate(duplicated)) }
                .isInstanceOf(SnapshotContentIntegrityFailure::class.java)
                .hasCauseInstanceOf(IllegalArgumentException::class.java)
                .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private fun candidate(observations: List<SnapshotObservation>) = IssueSnapshotCandidate(
        projectId = "project-1",
        releaseId = "release-1",
        snapshotVersion = 1,
        syncRunId = "sync-1",
        sourceId = "source-1",
        sourceWatermark = "watermark-1",
        adapterVersion = "adapter-v1",
        mappingVersion = "mapping-v1",
        filterReference = "all-relevant-issues/v1",
        agePolicyVersion = SNAPSHOT_AGE_POLICY_VERSION,
        observations = observations,
    )

    private fun issue(
        sourceIssueId: String,
        observedAt: Instant = Instant.parse("2026-09-02T12:00:00.123456Z"),
        tombstone: Boolean = false,
    ) = SnapshotObservation(
        issueId = "issue-$sourceIssueId",
        sourceIssueId = sourceIssueId,
        title = "Synthetic $sourceIssueId",
        severity = IssueSeverity.HIGH,
        status = IssueStatus.OPEN,
        rawStatusToken = "open",
        sourceVersion = "version-$sourceIssueId",
        sourceReference = "fixture:$sourceIssueId",
        observedAt = observedAt,
        mappingVersion = "mapping-v1",
        tombstone = tombstone,
        factDigest = "sha256:${sourceIssueId.first().code.toString(16).padStart(2, '0').repeat(32)}",
    )
}
