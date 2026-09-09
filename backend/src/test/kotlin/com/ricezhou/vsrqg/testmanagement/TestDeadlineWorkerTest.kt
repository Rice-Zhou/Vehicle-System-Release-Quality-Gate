package com.ricezhou.vsrqg.testmanagement

import com.ricezhou.vsrqg.testmanagement.adapter.TestDeadlineWorker
import com.ricezhou.vsrqg.testmanagement.application.AdvanceTestDeadlines
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.*

@Timeout(60)
class TestDeadlineWorkerTest {
    @Test fun `a full first page cannot starve a later active run`() {
        val deadlines=mock(AdvanceTestDeadlines::class.java)
        val first=(1..100).map { "run_"+it.toString().padStart(3,'0') }
        `when`(deadlines.activeRuns("")).thenReturn(first)
        `when`(deadlines.activeRuns("run_100")).thenReturn(listOf("run_101"))
        `when`(deadlines.activeRuns("run_101")).thenReturn(emptyList())
        TestDeadlineWorker(deadlines).tick()
        verify(deadlines).advance("run_101")
        first.forEach { verify(deadlines).advance(it) }
    }
}
