package com.ricezhou.vsrqg.issue.adapter

import com.ricezhou.vsrqg.issue.domain.NormalizedIssue
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit

internal data class CanonicalIssueFacts(
    val source: String,
    val sourceIssueId: String,
    val title: String,
    val severity: String,
    val status: String,
    val rawSeverity: String,
    val rawStatus: String,
    val sourceVersion: String,
    val sourceReference: String,
    val observedAt: Instant,
    val mappingVersion: String,
    val tombstone: Boolean,
    val warnings: List<String>,
    val factDigest: String,
)

internal object IssueFactCanonicalizer {
    fun canonicalize(issue: NormalizedIssue): CanonicalIssueFacts {
        val observedAt = canonicalPostgresInstant(issue.observedAt)
        val warnings = issue.warnings.map(Enum<*>::name).sorted()
        val values = listOf(
            DIGEST_SCHEMA,
            issue.source,
            issue.sourceIssueId,
            issue.title,
            issue.severity.name,
            issue.status.name,
            issue.rawSeverity,
            issue.rawStatus,
            issue.sourceVersion,
            issue.sourceReference,
            observedAt.toString(),
            issue.mappingVersion,
            issue.tombstone,
            warnings,
        )
        return CanonicalIssueFacts(
            source = issue.source,
            sourceIssueId = issue.sourceIssueId,
            title = issue.title,
            severity = issue.severity.name,
            status = issue.status.name,
            rawSeverity = issue.rawSeverity,
            rawStatus = issue.rawStatus,
            sourceVersion = issue.sourceVersion,
            sourceReference = issue.sourceReference,
            observedAt = observedAt,
            mappingVersion = issue.mappingVersion,
            tombstone = issue.tombstone,
            warnings = warnings,
            factDigest = CanonicalFactEncoder.digest(values),
        )
    }

    fun canonicalPostgresInstant(value: Instant): Instant = value.truncatedTo(ChronoUnit.MICROS)

    private const val DIGEST_SCHEMA = "normalized-issue-facts/v1"
}

internal object CanonicalFactEncoder {
    fun digest(values: List<Any?>): String {
        val output = ByteArrayOutputStream()
        values.forEach { value -> encode(output, value) }
        val digest = MessageDigest.getInstance("SHA-256").digest(output.toByteArray())
        return "sha256:" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun encode(output: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> output.writeAscii("N;")
            is String -> output.writeLengthPrefixed('S', value.toByteArray(StandardCharsets.UTF_8))
            is Boolean -> output.writeAscii(if (value) "B1;" else "B0;")
            is List<*> -> {
                output.writeAscii("L${value.size}:")
                value.forEach { item -> encode(output, item) }
            }
            else -> error("Unsupported canonical fact type: ${value::class.java.name}")
        }
    }

    private fun ByteArrayOutputStream.writeLengthPrefixed(type: Char, bytes: ByteArray) {
        writeAscii("$type${bytes.size}:")
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(StandardCharsets.US_ASCII))
    }
}
