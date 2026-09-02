package com.ricezhou.vsrqg.issue.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.issue.application.CanonicalIssueSnapshot
import com.ricezhou.vsrqg.issue.application.IssueSnapshotCandidate
import com.ricezhou.vsrqg.issue.application.IssueSnapshotCanonicalizer
import com.ricezhou.vsrqg.issue.application.SNAPSHOT_AGE_POLICY_VERSION
import com.ricezhou.vsrqg.issue.application.SNAPSHOT_CANONICALIZATION_VERSION
import com.ricezhou.vsrqg.issue.application.SNAPSHOT_SCHEMA_VERSION
import com.ricezhou.vsrqg.issue.application.selectedObservations
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoUnit
import org.erdtman.jcs.JsonCanonicalizer
import org.springframework.stereotype.Component

@Component
class JcsIssueSnapshotCanonicalizer(
    private val objectMapper: ObjectMapper,
) : IssueSnapshotCanonicalizer {
    override fun canonicalize(candidate: IssueSnapshotCandidate): CanonicalIssueSnapshot {
        validate(candidate)
        val document = objectMapper.createObjectNode()
            .put("schemaVersion", SNAPSHOT_SCHEMA_VERSION)
            .put("canonicalizationVersion", SNAPSHOT_CANONICALIZATION_VERSION)
            .put("projectId", candidate.projectId)
            .put("releaseId", candidate.releaseId)
            .put("snapshotVersion", candidate.snapshotVersion)
            .put("syncRunId", candidate.syncRunId)
            .put("sourceId", candidate.sourceId)
            .put("sourceWatermark", candidate.sourceWatermark)
            .put("adapterVersion", candidate.adapterVersion)
            .put("mappingVersion", candidate.mappingVersion)
            .put("filterReference", candidate.filterReference)
            .put("agePolicyVersion", candidate.agePolicyVersion)
            .put("observedCount", candidate.observedCount)
            .put("tombstoneCount", candidate.tombstoneCount)
            .put("selectedCount", candidate.selectedCount)
        val items = document.putArray("items")
        candidate.selectedObservations().forEachIndexed { ordinal, observation ->
            items.add(
                objectMapper.createObjectNode()
                    .put("ordinal", ordinal)
                    .put("sourceId", candidate.sourceId)
                    .put("issueId", observation.issueId)
                    .put("sourceIssueId", observation.sourceIssueId)
                    .put("title", observation.title)
                    .put("severity", observation.severity.name)
                    .put("status", observation.status.name)
                    .putNullable("rawStatusToken", observation.rawStatusToken)
                    .put("sourceVersion", observation.sourceVersion)
                    .put("sourceReference", observation.sourceReference)
                    .put("observedAt", formatInstant(observation.observedAt))
                    .put("mappingVersion", observation.mappingVersion)
                    .put("factDigest", observation.factDigest),
            )
        }
        val canonicalBytes = JsonCanonicalizer(objectMapper.writeValueAsBytes(document)).encodedUTF8
        return CanonicalIssueSnapshot(canonicalBytes, digest(canonicalBytes))
    }

    private fun validate(candidate: IssueSnapshotCandidate) {
        require(candidate.agePolicyVersion == SNAPSHOT_AGE_POLICY_VERSION) { "Unsupported snapshot age policy version" }
        require(candidate.snapshotVersion > 0) { "Snapshot version must be positive" }
        require(candidate.observedCount >= 0 && candidate.tombstoneCount >= 0 && candidate.selectedCount >= 0) {
            "Snapshot counts must be non-negative"
        }
        require(candidate.observedCount == candidate.tombstoneCount + candidate.selectedCount) {
            "Snapshot counts are inconsistent"
        }
        val selected = candidate.selectedObservations()
        require(selected.size == candidate.selectedCount) { "Snapshot selected count is inconsistent" }
        val suppliedTombstones = candidate.observations.count { it.tombstone }
        if (suppliedTombstones > 0) {
            require(candidate.observations.size == candidate.observedCount && suppliedTombstones == candidate.tombstoneCount) {
                "Snapshot observation counts are inconsistent"
            }
        }
        require(selected.map { it.issueId }.toSet().size == selected.size) { "Snapshot issue identity is duplicated" }
        require(selected.map { it.sourceIssueId }.toSet().size == selected.size) {
            "Snapshot source issue identity is duplicated"
        }
        require(candidate.observations.all { it.mappingVersion == candidate.mappingVersion }) {
            "Snapshot observation mapping version is inconsistent"
        }
        require(candidate.observations.all { DIGEST.matches(it.factDigest) }) { "Snapshot fact digest is invalid" }
    }

    private fun ObjectNode.putNullable(field: String, value: String?): ObjectNode = apply {
        if (value == null) putNull(field) else put(field, value)
    }

    private fun formatInstant(value: Instant): String = MICROSECOND_FORMATTER.format(
        value.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC),
    )

    private fun digest(canonicalBytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(canonicalBytes)
        return "sha256:" + hash.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        val DIGEST = Regex("^sha256:[0-9a-f]{64}$")
        val MICROSECOND_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder().appendInstant(6).toFormatter()
    }
}
