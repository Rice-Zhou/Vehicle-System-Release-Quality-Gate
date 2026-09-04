package com.ricezhou.vsrqg.traceability.application

import com.ricezhou.vsrqg.traceability.domain.CanonicalTraceability
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdge
import com.ricezhou.vsrqg.traceability.domain.TraceabilityEntityType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityExpectedEdgeType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityGap
import com.ricezhou.vsrqg.traceability.domain.TraceabilityGapCode
import com.ricezhou.vsrqg.traceability.domain.TraceabilityIssueResult
import com.ricezhou.vsrqg.traceability.domain.TraceabilityPathEdge
import com.ricezhou.vsrqg.traceability.domain.VerificationComputation
import com.ricezhou.vsrqg.traceability.domain.VerificationInput

typealias TraceabilityVerificationFailure =
    com.ricezhou.vsrqg.traceability.domain.TraceabilityVerificationFailure

interface TraceabilityCanonicalizer {
    fun validateInput(input: VerificationInput)

    fun canonicalizeInput(input: VerificationInput): CanonicalTraceability

    fun createGap(
        issueId: String,
        diagnosticCode: TraceabilityGapCode,
        breakEntityType: TraceabilityEntityType,
        breakEntityId: String,
        expectedEdgeType: TraceabilityExpectedEdgeType,
        predecessorEdge: PinnedTraceabilityEdge?,
    ): TraceabilityGap

    fun canonicalizeGap(gap: TraceabilityGap): CanonicalTraceability

    fun createIssueResult(
        issueId: String,
        sourceIssueId: String,
        path: List<PinnedTraceabilityEdge>,
        gaps: List<TraceabilityGap>,
    ): TraceabilityIssueResult

    fun canonicalizeIssueResult(result: TraceabilityIssueResult): CanonicalTraceability

    fun canonicalizeResult(
        input: VerificationInput,
        issueResults: List<TraceabilityIssueResult>,
        pathEdges: List<TraceabilityPathEdge>,
        gaps: List<TraceabilityGap>,
    ): CanonicalTraceability

    fun createComputation(
        input: VerificationInput,
        issueResults: List<TraceabilityIssueResult>,
        pathEdges: List<TraceabilityPathEdge>,
        gaps: List<TraceabilityGap>,
    ): VerificationComputation
}
