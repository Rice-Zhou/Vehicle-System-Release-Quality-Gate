package com.ricezhou.vsrqg.traceability.adapter

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.traceability.application.TraceabilityCanonicalizer
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationFailure
import com.ricezhou.vsrqg.traceability.domain.CanonicalTraceability
import com.ricezhou.vsrqg.traceability.domain.PinnedTraceabilityEdge
import com.ricezhou.vsrqg.traceability.domain.TraceabilityGap
import com.ricezhou.vsrqg.traceability.domain.TraceabilityIssueResult
import com.ricezhou.vsrqg.traceability.domain.TraceabilityOrdering
import com.ricezhou.vsrqg.traceability.domain.TraceabilityPathEdge
import com.ricezhou.vsrqg.traceability.domain.VerificationInput
import java.io.IOException
import java.security.MessageDigest
import java.util.HexFormat
import org.erdtman.jcs.JsonCanonicalizer
import org.springframework.stereotype.Component

@Component
class JcsTraceabilityCanonicalizer(
    private val objectMapper: ObjectMapper,
) : TraceabilityCanonicalizer {
    override fun canonicalizeInput(input: VerificationInput): CanonicalTraceability =
        canonicalize(inputDocument(input))

    override fun canonicalizeResult(
        input: VerificationInput,
        issueResults: List<TraceabilityIssueResult>,
        pathEdges: List<TraceabilityPathEdge>,
        gaps: List<TraceabilityGap>,
    ): CanonicalTraceability {
        val resultByIssue = issueResults.associateBy(TraceabilityIssueResult::issueId)
        val document = objectMapper.createObjectNode()
            .set<ObjectNode>("input", inputDocument(input))
        document.putArray("issueResults").addAll(
            issueResults.sortedWith(TraceabilityOrdering.issueResultOrder).map(::issueResultDocument),
        )
        document.putArray("pathEdges").addAll(
            pathEdges.sortedWith(pathEdgeOrder(resultByIssue)).map(::pathEdgeDocument),
        )
        document.putArray("gaps").addAll(
            gaps.sortedWith(gapOrder(resultByIssue)).map(::gapDocument),
        )
        return canonicalize(document)
    }

    private fun inputDocument(input: VerificationInput): ObjectNode = objectMapper.createObjectNode()
        .put("schemaVersion", input.schemaVersion)
        .put("policyVersion", input.policyVersion)
        .put("validatorVersion", input.validatorVersion)
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

    private fun issueResultDocument(result: TraceabilityIssueResult): ObjectNode = objectMapper.createObjectNode()
        .put("issueId", result.issueId)
        .put("sourceIssueId", result.sourceIssueId)
        .put("fixed", result.fixed)
        .put("included", result.included)
        .put("verified", result.verified)
        .put("minimumConfidence", result.minimumConfidence.name)

    private fun pathEdgeDocument(pathEdge: TraceabilityPathEdge): ObjectNode = objectMapper.createObjectNode()
        .put("issueId", pathEdge.issueId)
        .put("pathOrdinal", pathEdge.pathOrdinal)
        .set<ObjectNode>("edge", edgeDocument(pathEdge.edge))

    private fun edgeDocument(edge: PinnedTraceabilityEdge): ObjectNode = objectMapper.createObjectNode()
        .put("edgeType", edge.edgeType.name)
        .put("fromId", edge.fromId)
        .put("toId", edge.toId)
        .put("sourceEdgeId", edge.sourceEdgeId)
        .put("sourceEdgeRevision", edge.sourceEdgeRevision)
        .put("verificationStatus", edge.verificationStatus.name)
        .put("confidence", edge.confidence.name)
        .put("factDigest", edge.factDigest)
        .put("authority", edge.authority.name)

    private fun gapDocument(gap: TraceabilityGap): ObjectNode = objectMapper.createObjectNode()
        .put("issueId", gap.issueId)
        .put("diagnosticCode", gap.diagnosticCode.name)
        .put("breakEntityType", gap.breakEntityType.name)
        .put("breakEntityId", gap.breakEntityId)
        .also { document ->
            document.put("expectedEdgeType", gap.expectedEdgeType.name)
            document.putNullable("predecessorEdgeType", gap.predecessorEdge?.edgeType?.name)
            document.putNullable("predecessorSourceEdgeId", gap.predecessorEdge?.sourceEdgeId)
            if (gap.predecessorEdge == null) {
                document.putNull("predecessorSourceEdgeRevision")
            } else {
                document.put("predecessorSourceEdgeRevision", gap.predecessorEdge.sourceEdgeRevision)
            }
        }

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
        }

    private fun compareIssueIdentity(
        leftIssueId: String,
        rightIssueId: String,
        results: Map<String, TraceabilityIssueResult>,
    ): Int {
        val left = results.getValue(leftIssueId)
        val right = results.getValue(rightIssueId)
        return TraceabilityOrdering.issueResultOrder.compare(left, right)
    }

    private fun canonicalize(document: ObjectNode): CanonicalTraceability {
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
        return CanonicalTraceability(bytes, digest(bytes))
    }

    private fun ObjectNode.putNullable(name: String, value: String?) {
        if (value == null) putNull(name) else put(name, value)
    }

    private fun digest(bytes: ByteArray): String =
        "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun fail(): Nothing = throw TraceabilityVerificationFailure("TRACEABILITY_CANONICALIZATION_FAILED")
}
