package com.ricezhou.vsrqg.issue

import com.ricezhou.vsrqg.issue.adapter.CanonicalFactEncoder
import com.ricezhou.vsrqg.issue.adapter.IssueFactCanonicalizer
import com.ricezhou.vsrqg.issue.adapter.PersistedIssueRevision
import com.ricezhou.vsrqg.issue.adapter.legacyIssueDigest
import com.ricezhou.vsrqg.issue.domain.IssueMappingWarning
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import com.ricezhou.vsrqg.issue.domain.NormalizedIssue
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IssueFactCanonicalizerTest {
    @Test
    fun `legacy digest remains byte compatible with the pre-version baseline`() {
        val issue = issue()

        assertThat(legacyIssueDigest(issue, issue.observedAt))
            .isEqualTo("sha256:bde01b36a81fdfd004fdc7e589fcb23dc00168bd377d793f4c89b1f46ff635a0")
    }

    @Test
    fun `canonical digest binds the PostgreSQL microsecond revision time`() {
        val nanos = issue(observedAt = Instant.parse("2026-09-02T12:00:00.123456789Z"))
        val micros = issue(observedAt = Instant.parse("2026-09-02T12:00:00.123456Z"))
        val later = issue(observedAt = Instant.parse("2026-09-02T12:00:10.123456Z"))

        val canonical = IssueFactCanonicalizer.canonicalize(nanos)

        assertThat(canonical.observedAt).isEqualTo(Instant.parse("2026-09-02T12:00:00.123456Z"))
        assertThat(canonical.factDigest).isEqualTo(IssueFactCanonicalizer.canonicalize(micros).factDigest)
        assertThat(canonical.factDigest).isNotEqualTo(IssueFactCanonicalizer.canonicalize(later).factDigest)
        assertThat(nanos.observedAt).isEqualTo(Instant.parse("2026-09-02T12:00:00.123456789Z"))
    }

    @Test
    fun `legacy revision reuse recovers truncated and rounded sub-microsecond timestamps`() {
        val initial = issue(observedAt = Instant.parse("2026-09-02T12:00:00.123456789Z"))
        val laterObservation = issue(observedAt = Instant.parse("2026-09-02T12:00:10Z"))
        val legacyDigest = legacyIssueDigest(initial, initial.observedAt)
        val truncated = persisted(initial).copy(
            observedAt = Instant.parse("2026-09-02T12:00:00.123456Z"),
            factDigest = legacyDigest,
            factDigestVersion = null,
        )
        val rounded = persisted(initial).copy(
            observedAt = Instant.parse("2026-09-02T12:00:00.123457Z"),
            factDigest = legacyDigest,
            factDigestVersion = null,
        )

        assertThat(truncated.matchesIssue("project-1", "source-1", laterObservation)).isTrue()
        assertThat(rounded.matchesIssue("project-1", "source-1", laterObservation)).isTrue()
    }

    @Test
    fun `legacy timestamp recovery remains bounded and still rejects different facts`() {
        val initial = issue(observedAt = Instant.parse("2026-09-02T12:00:00.123456789Z"))
        val laterObservation = issue(observedAt = Instant.parse("2026-09-02T12:00:10Z"))
        val persisted = persisted(initial).copy(
            observedAt = Instant.parse("2026-09-02T12:00:00.123455Z"),
            factDigest = legacyIssueDigest(initial, initial.observedAt),
            factDigestVersion = null,
        )

        assertThat(persisted.matchesIssue("project-1", "source-1", laterObservation)).isFalse()
        assertThat(persisted.copy(observedAt = Instant.parse("2026-09-02T12:00:00.123456Z"))
            .matchesIssue("project-1", "source-1", laterObservation.copy(title = "different fact"))).isFalse()
        assertThat(persisted.copy(factDigest = "sha256:${"0".repeat(64)}")
            .matchesIssue("project-1", "source-1", laterObservation)).isFalse()
    }

    @Test
    fun `v1 revision reuse ignores a later observation time but rejects different facts`() {
        val initial = issue(observedAt = Instant.parse("2026-09-02T12:00:00Z"))
        val persisted = persisted(initial)
        val laterObservation = initial.copy(observedAt = initial.observedAt.plusSeconds(10))

        assertThat(persisted.matchesIssue("project-1", "source-1", laterObservation)).isTrue()
        assertThat(
            persisted.copy(observedAt = initial.observedAt.plusSeconds(1))
                .matchesIssue("project-1", "source-1", laterObservation),
        ).isFalse()
        assertThat(persisted.matchesIssue("project-1", "source-1", laterObservation.copy(rawSeverity = "low")))
            .isFalse()
    }

    @Test
    fun `canonical encoding distinguishes null literals separators and field boundaries`() {
        assertThat(CanonicalFactEncoder.digest(listOf(null)))
            .isNotEqualTo(CanonicalFactEncoder.digest(listOf("null")))
        assertThat(CanonicalFactEncoder.digest(listOf("left\u001fright")))
            .isNotEqualTo(CanonicalFactEncoder.digest(listOf("left", "right")))
        assertThat(CanonicalFactEncoder.digest(listOf("a", "bc")))
            .isNotEqualTo(CanonicalFactEncoder.digest(listOf("ab", "c")))
    }

    @Test
    fun `warning encoding is order independent but content sensitive`() {
        val first = issue(
            warnings = linkedSetOf(IssueMappingWarning.UNKNOWN_STATUS, IssueMappingWarning.UNKNOWN_SEVERITY),
        )
        val reordered = issue(
            warnings = linkedSetOf(IssueMappingWarning.UNKNOWN_SEVERITY, IssueMappingWarning.UNKNOWN_STATUS),
        )
        val different = issue(warnings = setOf(IssueMappingWarning.UNKNOWN_STATUS))

        assertThat(IssueFactCanonicalizer.canonicalize(first).factDigest)
            .isEqualTo(IssueFactCanonicalizer.canonicalize(reordered).factDigest)
            .isNotEqualTo(IssueFactCanonicalizer.canonicalize(different).factDigest)
    }

    @Test
    fun `every materialized revision field participates in canonical collision comparison`() {
        val facts = IssueFactCanonicalizer.canonicalize(issue())
        val persisted = persisted(issue())

        assertThat(persisted.matches("project-1", "source-1", facts, facts.factDigest)).isTrue()
        assertThat(persisted.copy(observedAt = facts.observedAt.minusSeconds(10))
            .matches("project-1", "source-1", facts, facts.factDigest)).isTrue()
        assertThat(
            listOf(
                persisted.copy(projectId = "other"),
                persisted.copy(sourceId = "other"),
                persisted.copy(sourceIssueId = "other"),
                persisted.copy(title = "other"),
                persisted.copy(severity = "LOW"),
                persisted.copy(status = "CLOSED"),
                persisted.copy(rawStatus = "other"),
                persisted.copy(sourceVersion = "other"),
                persisted.copy(sourceReference = "other"),
                persisted.copy(mappingVersion = "other"),
                persisted.copy(tombstone = !facts.tombstone),
                persisted.copy(factDigest = "sha256:${"0".repeat(64)}"),
            ),
        ).allSatisfy { candidate ->
            assertThat(candidate.matches("project-1", "source-1", facts, facts.factDigest)).isFalse()
        }
    }

    private fun issue(
        observedAt: Instant = Instant.parse("2026-09-02T12:00:00Z"),
        warnings: Set<IssueMappingWarning> = emptySet(),
    ) = NormalizedIssue(
        source = "FIXTURE",
        sourceIssueId = "FIX-1",
        title = "separator \u001f and unicode 雪",
        severity = IssueSeverity.HIGH,
        status = IssueStatus.OPEN,
        rawSeverity = "high",
        rawStatus = "null",
        sourceVersion = "v1",
        sourceReference = "fixture:FIX-1",
        observedAt = observedAt,
        mappingVersion = "mapping-v1",
        warnings = warnings,
    )

    private fun persisted(issue: NormalizedIssue): PersistedIssueRevision {
        val facts = IssueFactCanonicalizer.canonicalize(issue)
        return PersistedIssueRevision(
            id = "issue-1",
            projectId = "project-1",
            sourceId = "source-1",
            sourceIssueId = facts.sourceIssueId,
            title = facts.title,
            severity = facts.severity,
            status = facts.status,
            rawStatus = facts.rawStatus,
            sourceVersion = facts.sourceVersion,
            sourceReference = facts.sourceReference,
            observedAt = facts.observedAt,
            mappingVersion = facts.mappingVersion,
            tombstone = facts.tombstone,
            factDigest = facts.factDigest,
            factDigestVersion = IssueFactCanonicalizer.FACT_DIGEST_VERSION,
        )
    }
}
