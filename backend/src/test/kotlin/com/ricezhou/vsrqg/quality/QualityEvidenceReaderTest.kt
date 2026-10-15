package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.evidence.adapter.ControlledPayloadStore
import com.ricezhou.vsrqg.evidence.ownedTestRoot
import com.ricezhou.vsrqg.evidence.application.EvidenceRepository
import com.ricezhou.vsrqg.evidence.application.EvidenceSession
import com.ricezhou.vsrqg.evidence.application.EvidenceConflict
import com.ricezhou.vsrqg.evidence.application.PayloadStore
import com.ricezhou.vsrqg.evidence.application.ReadQualityEvidence
import com.ricezhou.vsrqg.evidence.application.StoredPayload
import com.ricezhou.vsrqg.evidence.domain.EvidenceState
import com.ricezhou.vsrqg.testmanagement.application.AttemptBinding
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider

@Timeout(60)
class QualityEvidenceReaderTest {
    @TempDir lateinit var root: Path

    @ParameterizedTest(name = "real payload {0} reports the Quality integrity error")
    @ValueSource(strings = ["HELLO", "bad", "hello!"])
    fun `real payload corruption retains the quality integrity diagnostic`(corruption: String) {
        val store = ControlledPayloadStore(ownedTestRoot(root))
        val expected = StoredPayload(5,
            "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
        assertEquals(expected, store.write("upload_test_01", "hello".byteInputStream(), 5))
        val original = session("run-1")
        val session = original.copy(id = "upload_test_01", request = original.request.deepCopy<ObjectNode>()
            .put("sizeBytes", 5).put("payloadChecksum", expected.sha256))
        val repository = Mockito.mock(EvidenceRepository::class.java)
        @Suppress("UNCHECKED_CAST")
        val payloads = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<PayloadStore>
        Mockito.`when`(repository.inventory(setOf("ev-1"))).thenReturn(listOf(session))
        Mockito.`when`(repository.evidence("ev-1", false)).thenReturn(session)
        Mockito.`when`(payloads.ifAvailable).thenReturn(store)
        val reader = ReadQualityEvidence(repository, payloads)
        val pinned = reader.pin(setOf("ev-1"), "project-1", "release-1", "run-1", "attempt-1")
        reader.verify(pinned)
        Files.write(root.resolve("upload_test_01.payload"), corruption.toByteArray())

        val failure = assertThrows(EvidenceConflict::class.java) { reader.verify(pinned) }
        assertEquals("QUALITY_EVIDENCE_INTEGRITY_ERROR", failure.code)
        assertEquals("PAYLOAD_INTEGRITY_ERROR", assertInstanceOf(EvidenceConflict::class.java, failure.cause).code)
    }

    @Test
    fun `evidence from another run cannot enter the quality input`() {
        val repository = Mockito.mock(EvidenceRepository::class.java)
        @Suppress("UNCHECKED_CAST")
        val payloads = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<PayloadStore>
        Mockito.`when`(repository.inventory(setOf("ev-1"))).thenReturn(listOf(session("other-run")))
        val reader = ReadQualityEvidence(repository, payloads)
        assertThrows(EvidenceConflict::class.java) {
            reader.pin(setOf("ev-1"), "project-1", "release-1", "run-1", "attempt-1")
        }
    }

    @Test
    fun `corrupted payload is rejected after metadata has been pinned`() {
        val repository = Mockito.mock(EvidenceRepository::class.java)
        val store = Mockito.mock(PayloadStore::class.java)
        @Suppress("UNCHECKED_CAST")
        val payloads = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<PayloadStore>
        val session = session("run-1")
        Mockito.`when`(repository.inventory(setOf("ev-1"))).thenReturn(listOf(session))
        Mockito.`when`(repository.evidence("ev-1", false)).thenReturn(session)
        Mockito.`when`(payloads.ifAvailable).thenReturn(store)
        Mockito.`when`(store.verify("upload-1", session.expected)).thenReturn(
            StoredPayload(4, "sha256:" + "b".repeat(64)))
        val reader = ReadQualityEvidence(repository, payloads)
        val pinned = reader.pin(setOf("ev-1"), "project-1", "release-1", "run-1", "attempt-1")
        val failure = assertThrows(EvidenceConflict::class.java) { reader.verify(pinned) }
        assertEquals("QUALITY_EVIDENCE_INTEGRITY_ERROR", failure.code)
    }

    private fun session(runId: String): EvidenceSession {
        val mapper = ObjectMapper()
        return EvidenceSession(
            "upload-1", "ev-1",
            AttemptBinding("attempt-1", runId, "release-1", "project-1", "agent-1", "device-1", "lease-1", 1),
            mapper.readTree("""{"sizeBytes":4,"payloadChecksum":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","evidenceType":"LOG"}"""),
            EvidenceState.AVAILABLE, Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
            mapper.readTree("""{"evidenceId":"ev-1"}"""), "RESTRICTED", null, false,
        )
    }
}
