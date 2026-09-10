package com.ricezhou.vsrqg.evidence

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.evidence.application.*
import com.ricezhou.vsrqg.evidence.domain.EvidenceState
import com.ricezhou.vsrqg.testmanagement.application.AttemptBinding
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.*
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import java.time.Instant

@Timeout(60)
class ResolveAttemptEvidenceTest {
    private val repository=mock(EvidenceRepository::class.java)
    private val binding=AttemptBinding("attempt","run","release","project","agent","device","lease",1)
    private val now=Instant.parse("2026-09-09T00:00:00Z")
    private val mapper=ObjectMapper()
    private val service=ResolveAttemptEvidence(repository,DefaultListableBeanFactory().getBeanProvider(PayloadStore::class.java))
    @Test fun `disabled payload storage rejects resolution explicitly but still seals pending metadata`() {
        var session=EvidenceSession("upload","evidence",binding,mapper.createObjectNode(),EvidenceState.UPLOADING,now.plusSeconds(300),now,null,"RESTRICTED",null,false)
        `when`(repository.sessions(binding.attemptId)).thenAnswer { listOf(session) }
        doAnswer { session=session.copy(state=it.arguments[1] as EvidenceState);null }.`when`(repository).state(eq("upload") ?: "upload",any(EvidenceState::class.java) ?: EvidenceState.EXPIRED)
        assertThatThrownBy { service.resolve(binding,emptySet()) }.isInstanceOf(EvidenceConflict::class.java).extracting("code").isEqualTo("EVIDENCE_STORAGE_DISABLED")
        service.seal(binding,now)
        assertThat(session.state).isEqualTo(EvidenceState.EXPIRED)
    }
    @Test fun `seal rejects changed generation rather than closing sessions under a new owner`() {
        `when`(repository.sessions(binding.attemptId)).thenReturn(listOf(EvidenceSession("upload","evidence",binding,
            mapper.createObjectNode(),EvidenceState.UPLOADING,now.plusSeconds(300),now,null,"RESTRICTED",null,false)))
        assertThatThrownBy { service.seal(binding.copy(fencingToken=2),now) }.isInstanceOf(EvidenceConflict::class.java)
    }
}
