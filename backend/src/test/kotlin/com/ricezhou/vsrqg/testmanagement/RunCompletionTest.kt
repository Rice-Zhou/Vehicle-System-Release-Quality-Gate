package com.ricezhou.vsrqg.testmanagement

import com.ricezhou.vsrqg.testmanagement.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(60)
class RunCompletionTest {
    private val resolved=CompletionAttempt(AttemptState.COMPLETED,true,true)
    @Test fun `optional published case cannot disappear from completion`() {
        assertFalse(RunCompletion.ready(listOf(CompletionCase(listOf(resolved)),CompletionCase(emptyList()))))
        assertFalse(RunCompletion.ready(emptyList()))
    }
    @Test fun `optional active attempt and unresolved evidence block completion`() {
        for(state in listOf(AttemptState.RUNNING,AttemptState.UPLOADING,AttemptState.RECOVERY_PENDING))
            assertFalse(RunCompletion.ready(listOf(CompletionCase(listOf(resolved)),CompletionCase(listOf(resolved.copy(state=state))))))
        assertFalse(RunCompletion.ready(listOf(CompletionCase(listOf(resolved.copy(hasResult=false))))))
        assertFalse(RunCompletion.ready(listOf(CompletionCase(listOf(resolved.copy(evidenceResolved=false))))))
    }
    @Test fun `all resolved cases allow failed timeout and cancelled attempts`() {
        assertTrue(RunCompletion.ready(listOf(CompletionCase(listOf(resolved)),CompletionCase(
            listOf(resolved.copy(state=AttemptState.ERROR),resolved.copy(state=AttemptState.TIMEOUT),resolved.copy(state=AttemptState.CANCELLED))))))
    }
}
