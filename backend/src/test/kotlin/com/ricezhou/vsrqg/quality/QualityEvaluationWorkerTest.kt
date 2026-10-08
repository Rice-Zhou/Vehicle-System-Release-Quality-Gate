package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.quality.adapter.QualityEvaluationWorker
import com.ricezhou.vsrqg.quality.application.QualityDecisionRunner
import com.ricezhou.vsrqg.quality.application.QualityEvaluationClaim
import com.ricezhou.vsrqg.quality.application.QualityEvaluationRecord
import com.ricezhou.vsrqg.quality.application.QualityEvaluationRepository
import com.ricezhou.vsrqg.quality.application.QualityInputFailure
import com.ricezhou.vsrqg.quality.application.QualityRepository
import com.ricezhou.vsrqg.quality.application.QualitySourceReader
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito

@Timeout(60)
class QualityEvaluationWorkerTest {
    @Test
    fun `formal source mismatch records a queryable evaluation error`() {
        val mapper = ObjectMapper()
        val repository = Mockito.mock(QualityEvaluationRepository::class.java)
        val source = Mockito.mock(QualitySourceReader::class.java)
        val rules = Mockito.mock(QualityRepository::class.java)
        val ids = Mockito.mock(IdGenerator::class.java)
        val clock = Mockito.mock(TimeProvider::class.java)
        val now = Instant.parse("2026-10-08T00:00:00Z")
        val claim = QualityEvaluationClaim("job-1", "evaluation-1", "project-1", 1)
        val body = mapper.readTree("""{"ruleSet":{"ruleSetId":"set-1","version":1},"testRunIds":["run-1"],"traceabilitySnapshotId":"trace-1"}""")
        val record = QualityEvaluationRecord(
            "evaluation-1", "job-1", "project-1", "release-1", "qrs-1", "actor-1",
            "request-1", body, "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", now,
        )
        Mockito.`when`(clock.now()).thenReturn(now)
        Mockito.`when`(repository.claimNext(now)).thenReturn(claim)
        Mockito.`when`(repository.request(claim)).thenReturn(record)
        Mockito.`when`(source.read("project-1", "release-1", body))
            .thenThrow(QualityInputFailure("QUALITY_SOURCE_CHANGED"))
        val worker = QualityEvaluationWorker(repository, source, rules, QualityDecisionRunner(mapper), ids, clock)
        assertTrue(worker.runNext())
        Mockito.verify(repository).fail(claim, "QUALITY_SOURCE_CHANGED", now)
    }
}
