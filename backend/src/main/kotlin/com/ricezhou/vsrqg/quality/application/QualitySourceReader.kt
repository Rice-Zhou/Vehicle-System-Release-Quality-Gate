package com.ricezhou.vsrqg.quality.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.evidence.application.QualityEvidenceItem
import com.ricezhou.vsrqg.evidence.application.QualityEvidenceSource
import com.ricezhou.vsrqg.issue.application.IssueSnapshotRepository
import com.ricezhou.vsrqg.manifest.application.ManifestRepository
import com.ricezhou.vsrqg.quality.domain.QualityCanonicalEncoder
import com.ricezhou.vsrqg.quality.domain.QualityValue
import com.ricezhou.vsrqg.testmanagement.application.TestRunRepository
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRepository
import java.security.MessageDigest
import java.util.HexFormat
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

class QualityInputFailure(val code: String) : RuntimeException(code)

data class QualityPinnedInput(val snapshot: JsonNode, val evidence: List<QualityEvidenceItem>)

interface QualitySourceReader {
    fun read(projectId: String, releaseId: String, request: JsonNode): QualityPinnedInput
    fun verifyEvidence(input: QualityPinnedInput)
}

@Service
class FormalQualitySourceReader(
    private val manifests: ManifestRepository,
    private val issues: IssueSnapshotRepository,
    private val traceability: TraceabilityVerificationRepository,
    private val tests: TestRunRepository,
    private val quality: QualityRepository,
    private val evidence: QualityEvidenceSource,
    private val mapper: ObjectMapper,
) : QualitySourceReader {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun read(projectId: String, releaseId: String, request: JsonNode): QualityPinnedInput {
        val ruleSetRef = request.path("ruleSet")
        val ruleSetId = ruleSetRef.path("ruleSetId").textValue() ?: fail("QUALITY_RULE_SET_NOT_FOUND")
        val ruleSetVersion = ruleSetRef.path("version").takeIf { it.isIntegralNumber && it.canConvertToLong() }
            ?.longValue() ?: fail("QUALITY_RULE_SET_NOT_FOUND")
        val set = quality.findPublished(ruleSetId, ruleSetVersion) ?: fail("QUALITY_RULE_SET_NOT_PUBLISHED")
        if (set.projectId != projectId) fail("QUALITY_RULE_SET_SCOPE_MISMATCH")
        if (set.definition.path("catalogVersion").intValue() != 2 ||
            set.definition.path("engineVersion").textValue() != ENGINE_VERSION) fail("QUALITY_VERSION_UNSUPPORTED")
        val selectedCases = set.definition.path("selectedCaseRefs")
        if (!selectedCases.isArray || selectedCases.size() != 1) fail("QUALITY_SELECTED_CASES_INVALID")
        val runs = request.path("testRunIds")
        if (!runs.isArray || runs.size() != 1) fail("QUALITY_RUN_COUNT_INVALID")
        val runId = runs[0].textValue() ?: fail("QUALITY_RUN_COUNT_INVALID")
        val traceId = request.path("traceabilitySnapshotId").textValue() ?: fail("QUALITY_TRACEABILITY_NOT_FOUND")
        val trace = traceability.findSnapshotHeader(releaseId, traceId)
            ?: fail("QUALITY_TRACEABILITY_NOT_FOUND")
        if (trace.projectId != projectId || trace.releaseId != releaseId) fail("QUALITY_TRACEABILITY_SCOPE_MISMATCH")
        val manifest = manifests.findLockedExport(releaseId, trace.manifestRevisionId)
            ?: fail("QUALITY_MANIFEST_NOT_LOCKED")
        if (manifest.releaseId != releaseId || manifest.contentDigest != trace.manifestDigest) {
            fail("QUALITY_MANIFEST_MISMATCH")
        }
        val issue = issues.read(trace.issueSnapshotId) ?: fail("QUALITY_ISSUE_SNAPSHOT_NOT_FOUND")
        if (issue.candidate.projectId != projectId || issue.candidate.releaseId != releaseId) fail("QUALITY_ISSUE_SNAPSHOT_MISMATCH")
        val run = tests.run(runId)
        if (run.projectId != projectId || run.releaseId != releaseId || !run.state.terminal) {
            fail("QUALITY_TEST_RUN_INVALID")
        }
        val resultView = try {
            tests.terminalSnapshot(runId) ?: tests.resultView(runId)
        } catch (_: IllegalStateException) {
            fail("QUALITY_TEST_SNAPSHOT_INVALID")
        }
        if (resultView.path("releaseId").asText() != releaseId ||
            resultView.path("manifestId").asText() != trace.manifestRevisionId ||
            resultView.path("manifestDigest").asText() != trace.manifestDigest) {
            fail("QUALITY_TEST_MANIFEST_MISMATCH")
        }
        val attempts = resultView.path("attempts")
        if (!attempts.isArray || attempts.size() != 1) fail("QUALITY_ATTEMPT_SELECTION_INVALID")
        val attempt = attempts[0]
        val result = attempt.path("result")
        if (!result.isObject || result.path("resultDigest").textValue() == null) {
            fail("QUALITY_TEST_RESULT_MISSING")
        }
        val case = selectedCases[0]
        val caseId = case.path("caseId").textValue() ?: fail("QUALITY_SELECTED_CASES_INVALID")
        val caseVersion = case.path("version").takeIf { it.isIntegralNumber && it.canConvertToInt() && it.intValue() > 0 }
            ?.intValue() ?: fail("QUALITY_SELECTED_CASES_INVALID")
        if (result.path("caseId").asText() != caseId || result.path("caseVersion").intValue() != caseVersion ||
            result.path("testRunId").asText() != runId || result.path("releaseId").asText() != releaseId) {
            fail("QUALITY_CASE_SELECTION_MISMATCH")
        }
        val attemptId = attempt.path("attemptId").textValue() ?: fail("QUALITY_ATTEMPT_SELECTION_INVALID")
        if (result.path("attemptId").asText() != attemptId || result.path("attemptNo").intValue() != 1) {
            fail("QUALITY_ATTEMPT_SELECTION_INVALID")
        }

        val pinnedIssues = traceability.findSnapshotIssues(trace.snapshotId)
        val observations = issue.candidate.observations.filterNot { it.tombstone }
        if (pinnedIssues.size != observations.size || pinnedIssues.size > 20) fail("QUALITY_ISSUE_BINDING_INVALID")
        val byIssueId = pinnedIssues.associateBy { it.issueId }
        if (byIssueId.size != pinnedIssues.size) fail("QUALITY_ISSUE_BINDING_INVALID")
        val required = set.definition.path("requiredIssueRefs")
        if (!required.isArray || required.size() > 20) fail("QUALITY_REQUIRED_ISSUES_INVALID")
        val requiredKeys = required.map { it.path("source").asText() to it.path("sourceIssueId").asText() }.toSet()
        val observedKeys = observations.map { issue.candidate.sourceId to it.sourceIssueId }.toSet()
        if (!observedKeys.containsAll(requiredKeys)) fail("QUALITY_REQUIRED_ISSUE_NOT_FOUND")
        val facts = mapper.createObjectNode()
        val releaseFacts = facts.putObject("release").put("releaseId", releaseId).put("project", projectId)
        val manifestFacts = releaseFacts.putObject("manifest").put("digest", manifest.contentDigest)
        val manifestArtifacts = manifest.rawManifest.path("artifacts")
        if (!manifestArtifacts.isArray) fail("QUALITY_MANIFEST_ARTIFACTS_INVALID")
        val artifactFacts = manifestFacts.putArray("artifacts")
        manifestArtifacts.forEachIndexed { index, _ ->
            artifactFacts.addObject().put("manifestArrayIndex", index)
        }
        val issueFacts = facts.putArray("issues")
        observations.forEach { observed ->
            val traced = byIssueId[observed.issueId] ?: fail("QUALITY_ISSUE_BINDING_INVALID")
            issueFacts.addObject()
                .put("source", issue.candidate.sourceId)
                .put("sourceIssueId", observed.sourceIssueId)
                .put("severity", observed.severity.name)
                .put("fixed", traced.fixed).put("included", traced.included)
                .put("verified", traced.verified)
                .put("required", (issue.candidate.sourceId to observed.sourceIssueId) in requiredKeys)
        }
        val traceFacts = facts.putObject("traceability")
        val confidence = pinnedIssues.map { it.confidence.name }
        traceFacts.put("minimumConfidenceLevel", confidence.minByOrNull { CONFIDENCE.indexOf(it) } ?: "UNKNOWN")
        val gaps = traceFacts.putArray("gaps")
        val issueByOrdinal = pinnedIssues.associateBy { it.ordinal }
        traceability.findSnapshotGaps(trace.snapshotId).forEach { gap ->
            val linkedIssue = issueByOrdinal[gap.issueOrdinal] ?: fail("QUALITY_TRACEABILITY_GAP_INVALID")
            gaps.addObject().put("gapDigest", gap.gapDigest)
                .put("diagnosticCode", gap.diagnosticCode.name).put("issueId", linkedIssue.issueId)
                .put("breakEntityType", gap.breakEntityType.name).put("breakEntityId", gap.breakEntityId)
                .put("expectedEdgeType", gap.expectedEdgeType.name)
                .also { entry ->
                    if (gap.predecessorEdgeId == null) entry.putNull("predecessorEdgeId")
                    else entry.put("predecessorEdgeId", gap.predecessorEdgeId)
                    if (gap.predecessorRevision == null) entry.putNull("predecessorRevision")
                    else entry.put("predecessorRevision", gap.predecessorRevision)
                }
        }
        facts.putArray("testResults").addObject()
            .put("caseId", caseId).put("caseVersion", caseVersion).put("attemptNo", 1)
            .put("attemptId", attemptId).put("runId", runId)
            .put("resultDigest", result.path("resultDigest").asText())
            .put("status", result.path("status").asText())

        val requirements = attempt.path("evidenceRequirements")
        if (!requirements.isArray || requirements.any { it.path("state").asText() != "AVAILABLE" }) {
            fail("QUALITY_REQUIRED_EVIDENCE_MISSING")
        }
        val evidenceIds = result.path("evidenceIds").let { ids ->
            if (!ids.isArray) fail("QUALITY_EVIDENCE_IDS_INVALID")
            val values = ids.map { it.textValue() ?: fail("QUALITY_EVIDENCE_IDS_INVALID") }
            if (values.size != values.toSet().size) fail("QUALITY_EVIDENCE_IDS_INVALID")
            values.toSet()
        }
        val pinnedEvidence = evidence.pin(evidenceIds, projectId, releaseId, runId, attemptId)
        val requiredTypes = quality.rules(set.id).flatMap { rule ->
            rule.validatedAst.path("evidenceRequirements").map(JsonNode::asText)
        }.toSet()
        if (!pinnedEvidence.map { it.type }.toSet().containsAll(requiredTypes)) {
            fail("QUALITY_REQUIRED_EVIDENCE_MISSING")
        }
        val snapshot = mapper.createObjectNode()
        snapshot.put("project", projectId).put("releaseId", releaseId)
        snapshot.set<JsonNode>("manifest", sourceRef(manifest.manifestId, manifest.revision, manifest.contentDigest))
        snapshot.set<JsonNode>("issueSnapshot", sourceRef(issue.snapshotId, issue.candidate.snapshotVersion, issue.canonical.digest))
        snapshot.set<JsonNode>("traceabilitySnapshot", sourceRef(trace.snapshotId, trace.version, trace.contentDigest))
        snapshot.set<JsonNode>("ruleSet", ruleSetRef.deepCopy())
        snapshot.put("ruleSetDigest", set.contentDigest)
        snapshot.putObject("versions").put("catalogVersion", 2).put("engineVersion", ENGINE_VERSION)
            .put("canonicalizationVersion", QualityCanonicalEncoder.VERSION)
            .put("selectionPolicyVersion", "SINGLE-RUN-CASE-1")
        snapshot.set<JsonNode>("requiredIssueRefs", required.deepCopy())
        snapshot.set<JsonNode>("selectedCaseRefs", selectedCases.deepCopy())
        snapshot.putArray("selections").addObject().put("runId", runId)
            .set<JsonNode>("case", case.deepCopy())
        val selection = snapshot.withArray("selections")[0] as ObjectNode
        selection.putObject("selectedAttempt").put("attemptId", attemptId).put("attemptNo", 1)
        selection.put("resultDigest", result.path("resultDigest").asText())
        selection.putArray("otherAttempts")
        snapshot.set<JsonNode>("facts", facts)
        val references = snapshot.putArray("evidenceRefs")
        pinnedEvidence.forEach { item ->
            references.addObject().put("evidenceId", item.evidenceId).put("attemptId", attemptId)
                .put("runId", runId).put("type", item.type)
                .put("digest", item.payload.sha256).put("sizeBytes", item.payload.size)
        }
        snapshot.putArray("uncoveredFacts").add("evidence.crashes[]").add("evidence.anrs[]")
            .add("evidence.memory.samples[]")
        snapshot.put("inputDigest", digest(QualityCanonicalEncoder().encode(toQualityValue(snapshot))))
        return QualityPinnedInput(snapshot, pinnedEvidence)
    }

    override fun verifyEvidence(input: QualityPinnedInput) = evidence.verify(input.evidence)

    private fun sourceRef(id: String, version: Int, digest: String) =
        mapper.createObjectNode().put("id", id).put("version", version).put("digest", digest)

    private fun fail(code: String): Nothing = throw QualityInputFailure(code)

    private companion object {
        const val ENGINE_VERSION = "VSRQG-QUALITY-ENGINE-1"
        val CONFIDENCE = listOf("UNKNOWN", "LOW", "MEDIUM", "HIGH")
    }
}

internal fun toQualityValue(node: JsonNode): QualityValue = when {
    node.isNull -> QualityValue.Null
    node.isBoolean -> QualityValue.Bool(node.booleanValue())
    node.isTextual -> QualityValue.Text(node.textValue())
    node.isIntegralNumber -> QualityValue.Integer(node.bigIntegerValue())
    node.isNumber -> QualityValue.Decimal(node.decimalValue())
    node.isArray -> QualityValue.ArrayValue(node.map(::toQualityValue))
    node.isObject -> QualityValue.ObjectValue(node.properties().associate { it.key to toQualityValue(it.value) })
    else -> throw QualityInputFailure("QUALITY_VALUE_INVALID")
}

internal fun digest(bytes: ByteArray): String =
    "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
