package com.ricezhou.vsrqg.shared.application.archive

internal class ArchiveAuthorization internal constructor(
    internal val report: ArchiveCapabilityReport,
    private val issuer: Any,
) {
    internal fun requireIssuedBy(expectedIssuer: Any) {
        require(issuer === expectedIssuer) { "Archive authorization was not issued by the trusted evaluator" }
    }
}

internal interface ArchiveAdapter {
    val provider: ArchiveProvider

    fun probe(policy: ArchivePolicy, context: CapabilityProbeContext): List<CapabilityCheck>

    fun archive(
        command: ArchiveCommand,
        policy: ArchivePolicy,
        authorization: ArchiveAuthorization,
    ): ArchiveResult
}
