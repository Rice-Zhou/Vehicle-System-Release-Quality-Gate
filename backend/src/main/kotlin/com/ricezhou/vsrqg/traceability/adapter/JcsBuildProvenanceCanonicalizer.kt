package com.ricezhou.vsrqg.traceability.adapter

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceCanonicalizer
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceInvalid
import com.ricezhou.vsrqg.traceability.domain.BuildProvenanceEnvelope
import com.ricezhou.vsrqg.traceability.domain.CanonicalBuildProvenance
import com.ricezhou.vsrqg.traceability.domain.ProvenanceProviderId
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Collections
import java.util.HexFormat
import org.erdtman.jcs.JsonCanonicalizer
import org.springframework.stereotype.Component

@Component
class JcsBuildProvenanceCanonicalizer(
    private val objectMapper: ObjectMapper,
) : BuildProvenanceCanonicalizer {
    override fun canonicalize(envelope: BuildProvenanceEnvelope): CanonicalBuildProvenance {
        val normalized = normalize(envelope)
        val derivedFactCount = normalized.sourceIssueIds.size + 1 + normalized.artifactSha256s.size
        val canonicalBytes = canonicalize(envelopeDocument(normalized))
        val proofBytes = canonicalize(proofDocument(normalized))
        return CanonicalBuildProvenance(
            normalized = normalized,
            canonicalBytes = canonicalBytes,
            envelopeDigest = digest(canonicalBytes),
            recomputedProofDigest = digest(proofBytes),
            derivedFactCount = derivedFactCount,
        )
    }

    private fun normalize(source: BuildProvenanceEnvelope): BuildProvenanceEnvelope {
        if (source.schemaVersion != SCHEMA_VERSION) invalid("SCHEMA_VERSION_UNSUPPORTED")

        val projectReference = normalizeText(source.projectReference, "PROJECT_REFERENCE_INVALID")
            .requireTextLength(1, 128, "PROJECT_REFERENCE_INVALID")
        val releaseIssueSnapshotId = normalizeText(
            source.releaseIssueSnapshotId,
            "RELEASE_ISSUE_SNAPSHOT_ID_INVALID",
        ).requireTextLength(1, 128, "RELEASE_ISSUE_SNAPSHOT_ID_INVALID")
        val provider = normalizeText(source.provider.value, "PROVIDER_INVALID")
            .also { if (!PROVIDER.matches(it)) invalid("PROVIDER_INVALID") }
        val repository = normalizeText(source.repository, "REPOSITORY_INVALID")
            .also { if (!isAllowedRepository(it)) invalid("REPOSITORY_INVALID") }
        val sourceRevision = normalizeText(source.sourceRevision, "SOURCE_REVISION_INVALID")
            .also { if (!GIT_SHA.matches(it)) invalid("SOURCE_REVISION_INVALID") }
        val pipeline = normalizeText(source.pipeline, "PIPELINE_INVALID")
            .requireTextLength(1, 255, "PIPELINE_INVALID")
        val buildId = normalizeText(source.buildId, "BUILD_ID_INVALID")
            .requireTextLength(1, 255, "BUILD_ID_INVALID")
        if (source.buildAttempt < 1) invalid("BUILD_ATTEMPT_INVALID")
        val workflowReference = normalizeText(source.workflowReference, "WORKFLOW_REFERENCE_INVALID")
            .also { if (it.length > 1024 || !WORKFLOW_REFERENCE.matches(it)) invalid("WORKFLOW_REFERENCE_INVALID") }
        val proofReference = normalizeText(source.proofReference, "PROOF_REFERENCE_INVALID")
            .also { if (it.length > 1024 || !PROOF_REFERENCE.matches(it)) invalid("PROOF_REFERENCE_INVALID") }
        val proofDigest = normalizeText(source.proofDigest, "PROOF_DIGEST_INVALID")
            .also { if (!PREFIXED_SHA256.matches(it)) invalid("PROOF_DIGEST_INVALID") }

        val sourceIssueIds = normalizeSourceIssueIds(source.sourceIssueIds)
        val artifactSha256s = normalizeArtifactDigests(source.artifactSha256s)
        val factCount = sourceIssueIds.size.toLong() + 1L + artifactSha256s.size.toLong()
        if (factCount > MAX_DERIVED_FACTS) invalid("FACT_LIMIT_EXCEEDED")

        return BuildProvenanceEnvelope(
            schemaVersion = source.schemaVersion,
            projectReference = projectReference,
            releaseIssueSnapshotId = releaseIssueSnapshotId,
            provider = ProvenanceProviderId(provider),
            repository = repository,
            sourceRevision = sourceRevision,
            pipeline = pipeline,
            buildId = buildId,
            buildAttempt = source.buildAttempt,
            workflowReference = workflowReference,
            proofReference = proofReference,
            proofDigest = proofDigest,
            sourceIssueIds = immutableList(sourceIssueIds),
            artifactSha256s = immutableList(artifactSha256s),
        )
    }

    private fun normalizeSourceIssueIds(source: List<String>): List<String> {
        if (source.isEmpty()) invalid("SOURCE_ISSUE_IDS_INVALID")
        if (source.size > MAX_SOURCE_ISSUES) invalid("SOURCE_ISSUE_LIMIT_EXCEEDED")
        val normalized = source.map { value ->
            normalizeText(value, "SOURCE_ISSUE_ID_INVALID")
                .requireTextLength(1, 255, "SOURCE_ISSUE_ID_INVALID")
        }
        if (normalized.toSet().size != normalized.size) invalid("SOURCE_ISSUE_ID_DUPLICATE")
        return normalized.sortedWith(UNICODE_CODE_POINT_ORDER)
    }

    private fun normalizeArtifactDigests(source: List<String>): List<String> {
        if (source.isEmpty()) invalid("ARTIFACT_SHA256S_INVALID")
        if (source.size > MAX_ARTIFACTS) invalid("ARTIFACT_LIMIT_EXCEEDED")
        val normalized = source.map { value ->
            normalizeText(value, "ARTIFACT_SHA256_INVALID")
                .also { if (!SHA256.matches(it)) invalid("ARTIFACT_SHA256_INVALID") }
        }
        if (normalized.toSet().size != normalized.size) invalid("ARTIFACT_SHA256_DUPLICATE")
        return normalized.sortedWith(UNICODE_CODE_POINT_ORDER)
    }

    private fun normalizeText(value: String, violationCode: String): String {
        if (!value.hasWellFormedUtf16() || value.any { it.code <= 0x1f || it.code == 0x7f }) invalid(violationCode)
        return Normalizer.normalize(value, Normalizer.Form.NFC)
    }

    private fun isAllowedRepository(value: String): Boolean =
        value.length <= 512 && REPOSITORY.matches(value) && value.split('/').none { it == "." || it == ".." }

    private fun String.requireTextLength(min: Int, max: Int, violationCode: String): String = also {
        if (it.codePointCount(0, it.length) !in min..max) invalid(violationCode)
    }

    private fun envelopeDocument(value: BuildProvenanceEnvelope): ObjectNode =
        objectMapper.createObjectNode()
            .put("schemaVersion", value.schemaVersion)
            .put("projectReference", value.projectReference)
            .put("releaseIssueSnapshotId", value.releaseIssueSnapshotId)
            .put("provider", value.provider.value)
            .put("repository", value.repository)
            .put("sourceRevision", value.sourceRevision)
            .put("pipeline", value.pipeline)
            .put("buildId", value.buildId)
            .put("buildAttempt", value.buildAttempt)
            .put("workflowReference", value.workflowReference)
            .put("proofReference", value.proofReference)
            .put("proofDigest", value.proofDigest)
            .also { document ->
                document.putArray("sourceIssueIds").addAll(value.sourceIssueIds.map(objectMapper.nodeFactory::textNode))
                document.putArray("artifactSha256s").addAll(value.artifactSha256s.map(objectMapper.nodeFactory::textNode))
            }

    private fun proofDocument(value: BuildProvenanceEnvelope): ObjectNode =
        objectMapper.createObjectNode()
            .put("provider", value.provider.value)
            .put("repository", value.repository)
            .put("sourceRevision", value.sourceRevision)
            .put("pipeline", value.pipeline)
            .put("buildId", value.buildId)
            .put("buildAttempt", value.buildAttempt)
            .put("workflowReference", value.workflowReference)
            .put("proofReference", value.proofReference)

    private fun canonicalize(document: ObjectNode): ByteArray {
        val serialized = try {
            objectMapper.writeValueAsBytes(document)
        } catch (_: JsonProcessingException) {
            invalid("CANONICALIZATION_FAILED")
        }
        return try {
            JsonCanonicalizer(serialized).encodedUTF8
        } catch (_: IOException) {
            invalid("CANONICALIZATION_FAILED")
        }
    }

    private fun digest(bytes: ByteArray): String =
        "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

    private fun invalid(code: String): Nothing = throw BuildProvenanceInvalid(code)

    private companion object {
        const val SCHEMA_VERSION = 2
        const val MAX_SOURCE_ISSUES = 20
        const val MAX_ARTIFACTS = 20
        const val MAX_DERIVED_FACTS = 100

        val PROVIDER = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        val REPOSITORY = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")
        val GIT_SHA = Regex("^[0-9a-f]{40}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val PREFIXED_SHA256 = Regex("^sha256:[0-9a-f]{64}$")
        val WORKFLOW_REFERENCE = Regex(
            "^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/\\.github/workflows/[A-Za-z0-9][A-Za-z0-9._-]*\\.ya?ml@[A-Za-z0-9][A-Za-z0-9._/-]*$",
        )
        val PROOF_REFERENCE = Regex(
            "^https://github\\.com/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/actions/runs/[0-9]+/attempts/[1-9][0-9]*$",
        )
        val UNICODE_CODE_POINT_ORDER = Comparator<String> { left, right -> compareCodePoints(left, right) }

        fun compareCodePoints(left: String, right: String): Int {
            val leftCodePoints = left.codePoints().iterator()
            val rightCodePoints = right.codePoints().iterator()
            while (leftCodePoints.hasNext() && rightCodePoints.hasNext()) {
                val compared = leftCodePoints.nextInt().compareTo(rightCodePoints.nextInt())
                if (compared != 0) return compared
            }
            return leftCodePoints.hasNext().compareTo(rightCodePoints.hasNext())
        }

        fun String.hasWellFormedUtf16(): Boolean {
            var index = 0
            while (index < length) {
                val current = this[index]
                when {
                    current.isHighSurrogate() -> {
                        if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                        index += 2
                    }
                    current.isLowSurrogate() -> return false
                    else -> index++
                }
            }
            return true
        }
    }
}
