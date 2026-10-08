package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.evidence.application.QualityEvidenceSource
import com.ricezhou.vsrqg.issue.application.IssueSnapshotRepository
import com.ricezhou.vsrqg.manifest.application.ManifestRepository
import com.ricezhou.vsrqg.quality.application.FormalQualitySourceReader
import com.ricezhou.vsrqg.quality.application.QualityInputFailure
import com.ricezhou.vsrqg.quality.application.QualityRepository
import com.ricezhou.vsrqg.quality.application.QualityRuleSetRecord
import com.ricezhou.vsrqg.testmanagement.application.TestRunRepository
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotHeaderView
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRepository
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito

@Timeout(60)
class QualityInputBindingTest {
    @Test
    fun `traceability from another project cannot be bound to a release`() {
        val mapper = ObjectMapper()
        val manifest = Mockito.mock(ManifestRepository::class.java)
        val issues = Mockito.mock(IssueSnapshotRepository::class.java)
        val trace = Mockito.mock(TraceabilityVerificationRepository::class.java)
        val runs = Mockito.mock(TestRunRepository::class.java)
        val quality = Mockito.mock(QualityRepository::class.java)
        val evidence = Mockito.mock(QualityEvidenceSource::class.java)
        val rule = QualityRuleSetRecord(
            "qrs-1", "project-1", "set-1", 1, 1, "PUBLISHED",
            mapper.readTree("""{"ruleSetId":"set-1","version":1,"project":"project-1","catalogVersion":2,"engineVersion":"VSRQG-QUALITY-ENGINE-1","requiredIssueRefs":[],"selectedCaseRefs":[{"caseId":"smoke","version":1}],"rules":[]}"""),
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "author", "reviewer", Instant.EPOCH,
        )
        Mockito.`when`(quality.findPublished("set-1", 1)).thenReturn(rule)
        Mockito.`when`(trace.findSnapshotHeader("release-1", "trace-1")).thenReturn(
            TraceabilitySnapshotHeaderView(
                "trace-1", "another-project", "release-1", 1, "issue-1", "manifest-1",
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "policy", "validator", "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd", Instant.EPOCH,
            ),
        )
        val reader = FormalQualitySourceReader(manifest, issues, trace, runs, quality, evidence, mapper)
        val request = mapper.readTree("""{"ruleSet":{"ruleSetId":"set-1","version":1},"testRunIds":["run-1"],"traceabilitySnapshotId":"trace-1"}""")
        val failure = assertThrows(QualityInputFailure::class.java) {
            reader.read("project-1", "release-1", request)
        }
        assertEquals("QUALITY_TRACEABILITY_SCOPE_MISMATCH", failure.code)
    }
}