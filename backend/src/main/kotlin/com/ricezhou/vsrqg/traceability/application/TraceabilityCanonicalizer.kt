package com.ricezhou.vsrqg.traceability.application

import com.ricezhou.vsrqg.traceability.domain.CanonicalTraceability
import com.ricezhou.vsrqg.traceability.domain.TraceabilityGap
import com.ricezhou.vsrqg.traceability.domain.TraceabilityIssueResult
import com.ricezhou.vsrqg.traceability.domain.TraceabilityPathEdge
import com.ricezhou.vsrqg.traceability.domain.VerificationInput

interface TraceabilityCanonicalizer {
    fun canonicalizeInput(input: VerificationInput): CanonicalTraceability

    fun canonicalizeResult(
        input: VerificationInput,
        issueResults: List<TraceabilityIssueResult>,
        pathEdges: List<TraceabilityPathEdge>,
        gaps: List<TraceabilityGap>,
    ): CanonicalTraceability
}
