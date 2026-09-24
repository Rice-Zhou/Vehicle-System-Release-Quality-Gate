package com.ricezhou.vsrqg.quality

import com.ricezhou.vsrqg.quality.domain.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(60)
class QualityAggregatorTest {
    @Test fun `errors and no applicable gates cannot produce a quality action`() {
        assertEquals("ERROR", QualityAggregator.aggregate(listOf(RuleStatus.BLOCK, RuleStatus.ERROR)))
        assertEquals("ERROR", QualityAggregator.aggregate(emptyList()))
        assertEquals("ERROR", QualityAggregator.aggregate(listOf(RuleStatus.NOT_APPLICABLE)))
    }

    @Test fun `block warning pass precedence is stable`() {
        assertEquals("BLOCK", QualityAggregator.aggregate(listOf(RuleStatus.WARNING, RuleStatus.BLOCK, RuleStatus.PASS)))
        assertEquals("WARNING", QualityAggregator.aggregate(listOf(RuleStatus.NOT_APPLICABLE, RuleStatus.WARNING, RuleStatus.PASS)))
        assertEquals("PASS", QualityAggregator.aggregate(listOf(RuleStatus.NOT_APPLICABLE, RuleStatus.PASS)))
    }
}
