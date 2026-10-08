package com.ricezhou.vsrqg.quality.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.manifest.application.ManifestRepository
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.testmanagement.application.TestJson
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class QualityEvaluationRecord(
    val id: String,
    val jobId: String,
    val projectId: String,
    val releaseId: String,
    val ruleSetVersionId: String,
    val requestedBy: String,
    val requestId: String,
    val request: JsonNode,
    val requestDigest: String,
    val createdAt: Instant,
)

data class QualityEvaluationClaim(
    val jobId: String,
    val evaluationId: String,
    val projectId: String,
    val attemptCount: Int,
)

interface QualityEvaluationRepository {
    fun enqueue(record: QualityEvaluationRecord)
    fun claimNext(now: Instant): QualityEvaluationClaim?
    fun request(claim: QualityEvaluationClaim): QualityEvaluationRecord
    fun seal(claim: QualityEvaluationClaim, input: QualityPinnedInput, at: Instant): JsonNode
    fun complete(claim: QualityEvaluationClaim, snapshot: JsonNode, ruleResults: List<JsonNode>,
                 qualityResult: JsonNode?, errorCode: String?, at: Instant)
    fun fail(claim: QualityEvaluationClaim, code: String, at: Instant)
    fun retryInfrastructure(claim: QualityEvaluationClaim, at: Instant)
    fun list(projectId: String, releaseId: String, cursor: String?): JsonNode
}

@Service
class QualityEvaluationService(
    private val manifests: ManifestRepository,
    private val authorizer: ProjectAuthorizer,
    private val idempotent: IdempotentExecutor,
    private val rules: QualityRepository,
    private val evaluations: QualityEvaluationRepository,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
    private val mapper: ObjectMapper,
) {
    @Transactional
    fun request(projectId: String, releaseId: String, body: JsonNode, idempotencyKey: String,
                principal: Principal, requestId: String): JsonNode {
        val release = manifests.findRelease(releaseId) ?: missing()
        if (release.projectId != projectId) missing()
        val actor = authorizer.require(principal, projectId, Permission.QUALITY_EVALUATE).principalId
        validate(body)
        val ruleSet = body.path("ruleSet")
        val set = rules.findPublished(ruleSet.path("ruleSetId").asText(), ruleSet.path("version").longValue())
            ?: throw QualityInputFailure("QUALITY_RULE_SET_NOT_PUBLISHED")
        if (set.projectId != projectId) throw QualityInputFailure("QUALITY_RULE_SET_SCOPE_MISMATCH")
        val requestDigest = TestJson.digest(body)
        return idempotent.execute("quality:evaluation:" + releaseId, actor, idempotencyKey,
            requestDigest, JsonNode::class.java) {
            val record = QualityEvaluationRecord(
                ids.nextId("qev_"), ids.nextId("job_"), projectId, releaseId, set.id, actor,
                requestId, body.deepCopy(), requestDigest, clock.now(),
            )
            evaluations.enqueue(record)
            mapper.createObjectNode().put("evaluationId", record.id).put("project", projectId)
                .put("releaseId", releaseId).set<JsonNode>("ruleSet", ruleSet.deepCopy()).also {
                    (it as com.fasterxml.jackson.databind.node.ObjectNode)
                        .put("createdAt", record.createdAt.toString()).put("state", "QUEUED").put("jobId", record.jobId)
                }
        }
    }

    @Transactional(readOnly = true)
    fun list(projectId: String, releaseId: String, cursor: String?, principal: Principal): JsonNode {
        val release = manifests.findRelease(releaseId) ?: missing()
        if (release.projectId != projectId) missing()
        authorizer.require(principal, projectId, Permission.QUALITY_READ)
        return evaluations.list(projectId, releaseId, cursor)
    }

    private fun validate(body: JsonNode) {
        if (!body.isObject || body.properties().map { it.key }.toSet() !=
            setOf("ruleSet", "testRunIds", "traceabilitySnapshotId")) {
            throw QualityInputFailure("QUALITY_EVALUATION_REQUEST_INVALID")
        }
        val ruleSet = body.path("ruleSet")
        val runs = body.path("testRunIds")
        if (!ruleSet.isObject || ruleSet.properties().map { it.key }.toSet() != setOf("ruleSetId", "version") ||
            !ruleSet.path("ruleSetId").isTextual || ruleSet.path("ruleSetId").asText().isBlank() ||
            !ruleSet.path("version").isIntegralNumber || !ruleSet.path("version").canConvertToLong() ||
            ruleSet.path("version").longValue() < 1 || !runs.isArray || runs.size() != 1 ||
            !runs[0].isTextual || runs[0].asText().isBlank() ||
            !body.path("traceabilitySnapshotId").isTextual ||
            body.path("traceabilitySnapshotId").asText().isBlank()) {
            throw QualityInputFailure("QUALITY_EVALUATION_REQUEST_INVALID")
        }
    }

    private fun missing(): Nothing = throw ResourceNotFound(
        "RELEASE_NOT_FOUND", "Release not found", "Release not found")
}