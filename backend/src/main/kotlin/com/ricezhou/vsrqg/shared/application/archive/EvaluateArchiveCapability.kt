package com.ricezhou.vsrqg.shared.application.archive

import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale

internal class EvaluateArchiveCapability(
    adapters: List<ArchiveAdapter>,
    private val timeProvider: TimeProvider,
) {
    private val issuer = Any()
    private val adaptersByProvider = adapters.associateBy { it.provider }.also {
        require(it.size == adapters.size) { "Archive providers must be unique" }
    }

    internal fun evaluateReadiness(policy: ArchivePolicy): ArchiveCapabilityReport = evaluate(policy)

    internal fun authorizeArchive(policy: ArchivePolicy): ArchiveAuthorization =
        ArchiveAuthorization(evaluate(policy), issuer)

    internal fun requireIssued(authorization: ArchiveAuthorization) {
        authorization.requireIssuedBy(issuer)
    }

    private fun evaluate(policy: ArchivePolicy): ArchiveCapabilityReport {
        val checkedAt = timeProvider.now()
        val policyFingerprint = fingerprint(policy)
        val context = CapabilityProbeContext(policyFingerprint, checkedAt)
        val checks = adaptersByProvider[policy.provider]
            ?.probe(policy, context)
            ?: listOf(CapabilityCheck("provider", false, "No adapter is registered"))
        val passed = checks.isNotEmpty() && checks.all { it.passed }
        val state = when (policy.provider) {
            ArchiveProvider.NONE -> ArchiveCapabilityState.UNCONFIGURED
            ArchiveProvider.FILESYSTEM_STAGING -> {
                if (passed) ArchiveCapabilityState.LOCAL_PILOT else ArchiveCapabilityState.UNCONFIGURED
            }
            ArchiveProvider.S3_COMPATIBLE -> {
                if (passed) ArchiveCapabilityState.EXTERNAL_VERIFIED else ArchiveCapabilityState.EXTERNAL_UNVERIFIED
            }
        }
        return ArchiveCapabilityReport(
            mode = policy.mode,
            provider = policy.provider,
            state = state,
            policyFingerprint = policyFingerprint,
            checkedAt = checkedAt,
            checks = checks.toList(),
        )
    }

    private fun fingerprint(policy: ArchivePolicy): String {
        val canonical = listOf(
            "mode=${policy.mode.name}",
            "enabled=${policy.enabled}",
            "checksumVerificationEnabled=${policy.checksumVerificationEnabled}",
            "encryptionRequired=${policy.encryptionRequired}",
            "privateAccessRequired=${policy.privateAccessRequired}",
            "retentionPolicyRequired=${policy.retentionPolicyRequired}",
            "immutabilityRequired=${policy.immutabilityRequired}",
            "provider=${policy.provider.name}",
            "stagingRoot=${canonicalArchivePath(policy.stagingRoot)}",
            "endpoint=${policy.endpoint?.normalize()?.toASCIIString().orEmpty()}",
            "region=${policy.region.orEmpty()}",
            "bucket=${policy.bucket.orEmpty()}",
            "objectPrefix=${policy.objectPrefix}",
            "accessOwner=${policy.accessOwner.orEmpty()}",
            "retentionPeriod=${policy.retentionPeriod?.toString().orEmpty()}",
            "probeTimeout=${policy.probeTimeout}",
            "operationTimeout=${policy.operationTimeout}",
        ).joinToString("") { field ->
            "${field.toByteArray(UTF_8).size}:$field"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }
}

internal fun canonicalArchivePath(path: Path?): String {
    val normalized = path?.normalize() ?: return "0"
    val root = normalized.root
        ?.toString()
        ?.replace(File.separatorChar, '/')
    val elements = normalized.map { element -> element.toString() }
    return buildString {
        append('1')
        if (root == null) {
            append('0')
        } else {
            append('1')
            appendUtf8LengthPrefixed(root)
        }
        append(elements.size).append(':')
        elements.forEach(::appendUtf8LengthPrefixed)
    }
}

private fun StringBuilder.appendUtf8LengthPrefixed(value: String) {
    append(value.toByteArray(UTF_8).size).append(':').append(value)
}
