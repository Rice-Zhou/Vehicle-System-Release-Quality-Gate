package com.ricezhou.vsrqg.traceability.adapter

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.traceability.application.TraceabilityCanonicalizer
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationFailure
import com.ricezhou.vsrqg.traceability.domain.CanonicalTraceability
import com.ricezhou.vsrqg.traceability.domain.Confidence
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdge
import com.ricezhou.vsrqg.traceability.domain.TraceabilityEntityType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityExpectedEdgeType
import com.ricezhou.vsrqg.traceability.domain.TraceabilityGap
import com.ricezhou.vsrqg.traceability.domain.TraceabilityGapCode
import com.ricezhou.vsrqg.traceability.domain.TraceabilityIssueResult
import com.ricezhou.vsrqg.traceability.domain.TraceabilityMaterializationCapability
import com.ricezhou.vsrqg.traceability.domain.TraceabilityOrdering
import com.ricezhou.vsrqg.traceability.domain.TraceabilityPathEdge
import com.ricezhou.vsrqg.traceability.domain.VerificationComputation
import com.ricezhou.vsrqg.traceability.domain.VerificationInput
import com.ricezhou.vsrqg.traceability.domain.minimumConfidence
import java.io.IOException
import org.erdtman.jcs.JsonCanonicalizer
import org.springframework.stereotype.Component

@Component
class JcsTraceabilityCanonicalizer(
    private val objectMapper: ObjectMapper,
) : TraceabilityCanonicalizer {
    override fun validateInput(input: VerificationInput) {
        if (!TraceabilityUtf16Validator.inputIsWellFormed(input)) {
            throw TraceabilityVerificationFailure(
                "TRACEABILITY_INPUT_NOT_VALID",
                "MALFORMED_UTF16_INPUT",
            )
        }
    }

    override fun canonicalizeInput(input: VerificationInput): CanonicalTraceability {
        validateInput(input)
        return canonicalize(inputDocument(input))
    }

    override fun createGap(
        issueId: String,
        diagnosticCode: TraceabilityGapCode,
        breakEntityType: TraceabilityEntityType,
        breakEntityId: String,
        expectedEdgeType: TraceabilityExpectedEdgeType,
        predecessorEdge: PinnedTraceabilityEdge?,
    ): TraceabilityGap {
        val reason = diagnosticCode.stableReason
        requireCanonicalStrings(
            TraceabilityUtf16Validator.gapIsWellFormed(issueId, breakEntityId, predecessorEdge, reason),
        )
        val canonical = canonicalize(
            gapContentDocument(
                issueId,
                diagnosticCode,
                breakEntityType,
                breakEntityId,
                expectedEdgeType,
                predecessorEdge,
                reason,
            ),
        )
        return TraceabilityGap.materialize(
            TraceabilityMaterializationCapability,
            issueId,
            diagnosticCode,
            breakEntityType,
            breakEntityId,
            expectedEdgeType,
            predecessorEdge,
            reason,
            canonical,
        )
    }

    override fun canonicalizeGap(gap: TraceabilityGap): CanonicalTraceability {
        requireCanonicalStrings(TraceabilityUtf16Validator.gapIsWellFormed(gap))
        return canonicalize(gapContentDocument(
            gap.issueId,
            gap.diagnosticCode,
            gap.breakEntityType,
            gap.breakEntityId,
            gap.expectedEdgeType,
            gap.predecessorEdge,
            gap.reason,
        ))
    }

    override fun createIssueResult(
        issueId: String,
        sourceIssueId: String,
        path: List<PinnedTraceabilityEdge>,
        gaps: List<TraceabilityGap>,
    ): TraceabilityIssueResult {
        requireCanonicalStrings(
            TraceabilityUtf16Validator.issueResultIsWellFormed(issueId, sourceIssueId, path, gaps),
        )
        val fixed = path.isNotEmpty()
        val included = path.size == COMPLETE_PATH_SIZE
        val verified = false
        val confidence = path.minimumConfidence()
        val canonical = canonicalize(
            issueResultContentDocument(
                issueId,
                sourceIssueId,
                fixed,
                included,
                verified,
                confidence,
                path,
                gaps,
            ),
        )
        return TraceabilityIssueResult.materialize(
            TraceabilityMaterializationCapability,
            issueId,
            sourceIssueId,
            fixed,
            included,
            verified,
            path,
            gaps,
            confidence,
            canonical,
        )
    }

    override fun canonicalizeIssueResult(result: TraceabilityIssueResult): CanonicalTraceability {
        requireCanonicalStrings(TraceabilityUtf16Validator.issueResultIsWellFormed(result))
        return canonicalize(issueResultContentDocument(
            result.issueId,
            result.sourceIssueId,
            result.fixed,
            result.included,
            result.verified,
            result.confidence,
            result.path,
            result.gaps,
        ))
    }

    override fun canonicalizeResult(
        input: VerificationInput,
        issueResults: List<TraceabilityIssueResult>,
        pathEdges: List<TraceabilityPathEdge>,
        gaps: List<TraceabilityGap>,
    ): CanonicalTraceability {
        validateInput(input)
        requireCanonicalStrings(
            TraceabilityUtf16Validator.resultIsWellFormed(issueResults, pathEdges, gaps),
        )
        val resultByIssue = issueResults.associateBy(TraceabilityIssueResult::issueId)
        val document = objectMapper.createObjectNode()
            .set<ObjectNode>("input", inputDocument(input))
        document.putArray("issueResults").addAll(
            issueResults.sortedWith(TraceabilityOrdering.issueResultOrder).map(::persistedIssueResultDocument),
        )
        document.putArray("pathEdges").addAll(
            pathEdges.sortedWith(pathEdgeOrder(resultByIssue)).map(::pathEdgeDocument),
        )
        document.putArray("gaps").addAll(
            gaps.sortedWith(gapOrder(resultByIssue)).map(::persistedGapDocument),
        )
        return canonicalize(document)
    }

    override fun createComputation(
        input: VerificationInput,
        issueResults: List<TraceabilityIssueResult>,
        pathEdges: List<TraceabilityPathEdge>,
        gaps: List<TraceabilityGap>,
    ): VerificationComputation {
        val canonical = canonicalizeResult(input, issueResults, pathEdges, gaps)
        return VerificationComputation.materialize(
            TraceabilityMaterializationCapability,
            issueResults,
            pathEdges,
            gaps,
            canonical,
        )
    }

    private fun inputDocument(input: VerificationInput): ObjectNode = objectMapper.createObjectNode()
        .put("schemaVersion", input.schemaVersion)
        .put("policyVersion", input.policyVersion)
        .put("validatorVersion", input.validatorVersion)
        .put("projectId", input.projectId)
        .put("releaseId", input.releaseId)
        .also { root ->
            root.set<ObjectNode>(
                "manifest",
                objectMapper.createObjectNode()
                    .put("revisionId", input.manifest.revisionId)
                    .put("digest", input.manifest.digest),
            )
            root.set<ObjectNode>(
                "issueSnapshot",
                objectMapper.createObjectNode()
                    .put("id", input.issueSnapshot.snapshotId)
                    .put("digest", input.issueSnapshot.digest),
            )
            root.putArray("edgeFacts").addAll(
                input.edgeRevisions.sortedWith(TraceabilityOrdering.inputEdgeOrder).map { edge ->
                    objectMapper.createObjectNode()
                        .put("edgeType", edge.edgeType.name)
                        .put("sourceEdgeId", edge.sourceEdgeId)
                        .put("sourceEdgeRevision", edge.sourceEdgeRevision)
                        .put("factDigest", edge.factDigest)
                },
            )
        }

    private fun gapContentDocument(
        issueId: String,
        diagnosticCode: TraceabilityGapCode,
        breakEntityType: TraceabilityEntityType,
        breakEntityId: String,
        expectedEdgeType: TraceabilityExpectedEdgeType,
        predecessorEdge: PinnedTraceabilityEdge?,
        reason: String,
    ): ObjectNode = objectMapper.createObjectNode()
        .put("issueId", issueId)
        .put("diagnosticCode", diagnosticCode.name)
        .put("breakEntityType", breakEntityType.name)
        .put("breakEntityId", breakEntityId)
        .put("expectedEdgeType", expectedEdgeType.name)
        .putNullable("predecessorEdgeType", predecessorEdge?.edgeType?.name)
        .putNullable("predecessorEdgeId", predecessorEdge?.sourceEdgeId)
        .putNullable("predecessorEdgeRevision", predecessorEdge?.sourceEdgeRevision)
        .put("reason", reason)

    private fun persistedGapDocument(gap: TraceabilityGap): ObjectNode =
        gapContentDocument(
            gap.issueId,
            gap.diagnosticCode,
            gap.breakEntityType,
            gap.breakEntityId,
            gap.expectedEdgeType,
            gap.predecessorEdge,
            gap.reason,
        ).put("gapDigest", gap.gapDigest)

    private fun issueResultContentDocument(
        issueId: String,
        sourceIssueId: String,
        fixed: Boolean,
        included: Boolean,
        verified: Boolean,
        confidence: Confidence,
        path: List<PinnedTraceabilityEdge>,
        gaps: List<TraceabilityGap>,
    ): ObjectNode = objectMapper.createObjectNode()
        .put("issueId", issueId)
        .put("sourceIssueId", sourceIssueId)
        .put("fixed", fixed)
        .put("included", included)
        .put("verified", verified)
        .put("confidence", confidence.name)
        .also { document ->
            document.putArray("path").addAll(path.map(::edgeDocument))
            document.putArray("gaps").addAll(gaps.map(::persistedGapDocument))
        }

    private fun persistedIssueResultDocument(result: TraceabilityIssueResult): ObjectNode =
        objectMapper.createObjectNode()
            .put("issueId", result.issueId)
            .put("sourceIssueId", result.sourceIssueId)
            .put("fixed", result.fixed)
            .put("included", result.included)
            .put("verified", result.verified)
            .put("confidence", result.confidence.name)
            .put("resultDigest", result.resultDigest)

    private fun pathEdgeDocument(pathEdge: TraceabilityPathEdge): ObjectNode = objectMapper.createObjectNode()
        .put("issueId", pathEdge.issueId)
        .put("pathOrdinal", pathEdge.pathOrdinal)
        .set<ObjectNode>("edge", edgeDocument(pathEdge.edge))

    private fun edgeDocument(edge: PinnedTraceabilityEdge): ObjectNode = objectMapper.createObjectNode()
        .put("projectId", edge.projectId)
        .put("edgeType", edge.edgeType.name)
        .put("fromId", edge.fromId)
        .put("toId", edge.toId)
        .put("sourceEdgeId", edge.sourceEdgeId)
        .put("sourceEdgeRevision", edge.sourceEdgeRevision)
        .put("verificationStatus", edge.verificationStatus.name)
        .put("confidence", edge.confidence.name)
        .put("factDigest", edge.factDigest)
        .put("authority", edge.authority.name)

    private fun pathEdgeOrder(
        results: Map<String, TraceabilityIssueResult>,
    ): Comparator<TraceabilityPathEdge> = Comparator { left, right ->
        compareIssueIdentity(left.issueId, right.issueId, results)
            .takeIf { it != 0 }
            ?: left.pathOrdinal.compareTo(right.pathOrdinal)
    }

    private fun gapOrder(results: Map<String, TraceabilityIssueResult>): Comparator<TraceabilityGap> =
        Comparator { left, right ->
            compareIssueIdentity(left.issueId, right.issueId, results)
                .takeIf { it != 0 }
                ?: left.diagnosticCode.ordinal.compareTo(right.diagnosticCode.ordinal)
                    .takeIf { it != 0 }
                ?: TraceabilityOrdering.unicodeCodePointOrder.compare(left.breakEntityId, right.breakEntityId)
                    .takeIf { it != 0 }
                ?: compareNullable(left.predecessorEdge?.sourceEdgeId, right.predecessorEdge?.sourceEdgeId)
                    .takeIf { it != 0 }
                ?: compareNullable(left.predecessorEdge?.sourceEdgeRevision, right.predecessorEdge?.sourceEdgeRevision)
                    .takeIf { it != 0 }
                ?: TraceabilityOrdering.unicodeCodePointOrder.compare(left.gapDigest, right.gapDigest)
        }

    private fun compareIssueIdentity(
        leftIssueId: String,
        rightIssueId: String,
        results: Map<String, TraceabilityIssueResult>,
    ): Int = TraceabilityOrdering.issueResultOrder.compare(
        results.getValue(leftIssueId),
        results.getValue(rightIssueId),
    )

    private fun canonicalize(document: ObjectNode): CanonicalTraceability {
        validateCanonicalStrings(document)
        val serialized = try {
            objectMapper.writeValueAsBytes(document)
        } catch (_: JsonProcessingException) {
            fail()
        }
        val bytes = try {
            JsonCanonicalizer(serialized).encodedUTF8
        } catch (_: IOException) {
            fail()
        }
        return CanonicalTraceability.materialize(TraceabilityMaterializationCapability, bytes)
    }

    private fun validateCanonicalStrings(node: JsonNode) {
        when {
            node.isTextual -> requireCanonicalStrings(TraceabilityUtf16Validator.stringIsWellFormed(node.textValue()))
            node.isArray -> node.forEach(::validateCanonicalStrings)
            node.isObject -> node.properties().forEach { (name, value) ->
                requireCanonicalStrings(TraceabilityUtf16Validator.stringIsWellFormed(name))
                validateCanonicalStrings(value)
            }
        }
    }

    private fun requireCanonicalStrings(wellFormed: Boolean) {
        if (!wellFormed) malformedCanonicalString()
    }

    private fun malformedCanonicalString(): Nothing = throw TraceabilityVerificationFailure(
        "TRACEABILITY_CANONICALIZATION_FAILED",
        "MALFORMED_UTF16_CANONICAL_VALUE",
    )

    private fun ObjectNode.putNullable(name: String, value: String?): ObjectNode =
        if (value == null) putNull(name) else put(name, value)

    private fun ObjectNode.putNullable(name: String, value: Int?): ObjectNode =
        if (value == null) putNull(name) else put(name, value)

    private fun <T : Comparable<T>> compareNullable(left: T?, right: T?): Int = when {
        left == null && right == null -> 0
        left == null -> -1
        right == null -> 1
        else -> left.compareTo(right)
    }

    private fun fail(): Nothing = throw TraceabilityVerificationFailure(
        "TRACEABILITY_CANONICALIZATION_FAILED",
        "JCS_CANONICALIZATION_FAILED",
    )

    private companion object {
        const val COMPLETE_PATH_SIZE = 4
    }
}

private object TraceabilityUtf16Validator {
    fun inputIsWellFormed(input: VerificationInput): Boolean = allWellFormed(
        buildList {
            add(input.schemaVersion)
            add(input.policyVersion)
            add(input.validatorVersion)
            add(input.projectId)
            add(input.releaseId)
            add(input.issueSnapshot.projectId)
            add(input.issueSnapshot.releaseId)
            add(input.issueSnapshot.snapshotId)
            add(input.issueSnapshot.digest)
            input.issueSnapshot.issues.forEach { issue ->
                add(issue.issueId)
                add(issue.sourceIssueId)
            }
            add(input.manifest.projectId)
            add(input.manifest.releaseId)
            add(input.manifest.revisionId)
            add(input.manifest.digest)
            input.edgeRevisions.forEach { addEdge(it) }
        },
    )

    fun gapIsWellFormed(gap: TraceabilityGap): Boolean = gapIsWellFormed(
        gap.issueId,
        gap.breakEntityId,
        gap.predecessorEdge,
        gap.reason,
    ) && stringIsWellFormed(gap.gapDigest)

    fun gapIsWellFormed(
        issueId: String,
        breakEntityId: String,
        predecessorEdge: PinnedTraceabilityEdge?,
        reason: String,
    ): Boolean = allWellFormed(
        buildList {
            add(issueId)
            add(breakEntityId)
            add(reason)
            predecessorEdge?.let { addEdge(it) }
        },
    )

    fun issueResultIsWellFormed(result: TraceabilityIssueResult): Boolean =
        issueResultIsWellFormed(result.issueId, result.sourceIssueId, result.path, result.gaps) &&
            stringIsWellFormed(result.resultDigest)

    fun issueResultIsWellFormed(
        issueId: String,
        sourceIssueId: String,
        path: List<PinnedTraceabilityEdge>,
        gaps: List<TraceabilityGap>,
    ): Boolean = stringIsWellFormed(issueId) && stringIsWellFormed(sourceIssueId) &&
        path.all(::edgeIsWellFormed) && gaps.all(::gapIsWellFormed)

    fun resultIsWellFormed(
        issueResults: List<TraceabilityIssueResult>,
        pathEdges: List<TraceabilityPathEdge>,
        gaps: List<TraceabilityGap>,
    ): Boolean = issueResults.all(::issueResultIsWellFormed) &&
        pathEdges.all { stringIsWellFormed(it.issueId) && edgeIsWellFormed(it.edge) } &&
        gaps.all(::gapIsWellFormed)

    fun stringIsWellFormed(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            when {
                value[index].isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
                    index += 2
                }
                value[index].isLowSurrogate() -> return false
                else -> index++
            }
        }
        return true
    }

    private fun edgeIsWellFormed(edge: PinnedTraceabilityEdge): Boolean = allWellFormed(
        listOf(edge.projectId, edge.fromId, edge.toId, edge.sourceEdgeId, edge.factDigest),
    )

    private fun MutableList<String>.addEdge(edge: PinnedTraceabilityEdge) {
        add(edge.projectId)
        add(edge.fromId)
        add(edge.toId)
        add(edge.sourceEdgeId)
        add(edge.factDigest)
    }

    private fun allWellFormed(values: List<String>): Boolean = values.all(::stringIsWellFormed)
}
