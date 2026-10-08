package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.evidence.application.EvidenceRepository
import com.ricezhou.vsrqg.evidence.application.EvidenceSession
import com.ricezhou.vsrqg.evidence.application.EvidenceConflict
import com.ricezhou.vsrqg.evidence.application.PayloadStore
import com.ricezhou.vsrqg.evidence.application.ReadQualityEvidence
import com.ricezhou.vsrqg.evidence.domain.EvidenceState
import com.ricezhou.vsrqg.testmanagement.application.AttemptBinding
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider

@Timeout(60)
class QualityEvidenceReaderTest {
    @Test
    fun `evidence from another run cannot enter the quality input`() {
        val repository = Mockito.mock(EvidenceRepository::class.java)
        @Suppress("UNCHECKED_CAST")
        val payloads = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<PayloadStore>
        val mapper = ObjectMapper()
        val session = EvidenceSession(
            "upload-1", "ev-1",
            AttemptBinding("attempt-1", "other-run", "release-1", "project-1", "agent-1", "device-1", "lease-1", 1),
            mapper.readTree("""{"sizeBytes":4,"payloadChecksum":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","evidenceType":"LOG"}"""),
            EvidenceState.AVAILABLE, Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
            mapper.readTree("""{"evidenceId":"ev-1"}"""), "RESTRICTED", null, false,
        )
        Mockito.`when`(repository.inventory(setOf("ev-1"))).thenReturn(listOf(session))
        val reader = ReadQualityEvidence(repository, payloads)
        assertThrows(EvidenceConflict::class.java) {
            reader.pin(setOf("ev-1"), "project-1", "release-1", "run-1", "attempt-1")
        }
    }
}