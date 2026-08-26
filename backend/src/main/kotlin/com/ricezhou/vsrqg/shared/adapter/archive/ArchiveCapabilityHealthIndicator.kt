package com.ricezhou.vsrqg.shared.adapter.archive

import com.ricezhou.vsrqg.shared.application.archive.ArchiveCapabilityState
import com.ricezhou.vsrqg.shared.application.archive.ArchivePolicy
import com.ricezhou.vsrqg.shared.application.archive.DeploymentMode
import com.ricezhou.vsrqg.shared.application.archive.EvaluateArchiveCapability
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

@Component("archiveCapability")
internal class ArchiveCapabilityHealthIndicator(
    private val policy: ArchivePolicy,
    private val evaluator: EvaluateArchiveCapability,
) : HealthIndicator {
    override fun health(): Health {
        val report = evaluator.evaluateReadiness(policy)
        val ready = report.mode == DeploymentMode.PILOT ||
            policy.enabled && report.state == ArchiveCapabilityState.EXTERNAL_VERIFIED
        val builder = if (ready) Health.up() else Health.down()
        return builder.withDetails(
            linkedMapOf(
                "mode" to report.mode.name,
                "provider" to report.provider.name,
                "state" to report.state.name,
                "policyFingerprint" to report.policyFingerprint,
                "checkedAt" to report.checkedAt,
                "checks" to report.checks.map { check ->
                    linkedMapOf(
                        "name" to healthCheckName(check.name),
                        "passed" to check.passed,
                        "detail" to if (check.passed) DETAIL_VERIFIED else DETAIL_NOT_VERIFIED,
                    )
                },
            ),
        ).build()
    }

    private fun healthCheckName(name: String): String =
        name.takeIf(APPROVED_CHECK_NAMES::contains) ?: UNRECOGNIZED_CHECK

    private companion object {
        const val DETAIL_VERIFIED = "verified"
        const val DETAIL_NOT_VERIFIED = "not verified"
        const val UNRECOGNIZED_CHECK = "unrecognized"
        val APPROVED_CHECK_NAMES = setOf(
            "provider",
            "stagingRoot",
            "writable",
            "checksum",
            "identity",
            "connection",
            "encryption",
            "privateAccess",
            "versioning",
            "immutability",
            "retention",
        )
    }
}
