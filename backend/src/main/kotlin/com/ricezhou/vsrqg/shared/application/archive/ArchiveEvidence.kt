package com.ricezhou.vsrqg.shared.application.archive

class ArchiveEvidence internal constructor(
    private val policy: ArchivePolicy,
    private val evaluator: EvaluateArchiveCapability,
    adapters: List<ArchiveAdapter>,
) {
    private val adaptersByProvider = adapters.associateBy { it.provider }.also {
        require(it.size == adapters.size) { "Archive providers must be unique" }
    }

    fun archive(command: ArchiveCommand): ArchiveResult {
        val authorization = evaluator.authorizeArchive(policy)
        evaluator.requireIssued(authorization)
        if (!policy.enabled) {
            throw ArchiveUnavailable("Archive is disabled by policy")
        }

        val report = authorization.report
        val allowed = when (policy.mode) {
            DeploymentMode.PILOT -> report.state == ArchiveCapabilityState.LOCAL_PILOT ||
                report.state == ArchiveCapabilityState.EXTERNAL_VERIFIED
            DeploymentMode.COMPANY -> report.state == ArchiveCapabilityState.EXTERNAL_VERIFIED
        }
        if (!allowed) {
            throw ArchiveUnavailable("Archive capability is not available for the deployment mode")
        }
        if (report.mode != policy.mode || report.provider != policy.provider) {
            throw ArchiveUnavailable("Archive authorization does not match the active policy")
        }

        val adapter = adaptersByProvider[policy.provider]
            ?: throw ArchiveUnavailable("Archive provider is not registered")
        return adapter.archive(command, policy, authorization)
    }
}
