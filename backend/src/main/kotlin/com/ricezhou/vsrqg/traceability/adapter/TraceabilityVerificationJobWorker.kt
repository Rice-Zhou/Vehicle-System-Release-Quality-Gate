package com.ricezhou.vsrqg.traceability.adapter

import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.traceability.application.RunTraceabilityVerification
import com.ricezhou.vsrqg.traceability.application.TraceabilitySnapshotVersionConflict
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationJobClaim
import com.ricezhou.vsrqg.traceability.application.TraceabilityVerificationRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class TraceabilityVerificationJobWorker(
    private val repository: TraceabilityVerificationRepository,
    private val runTraceabilityVerification: RunTraceabilityVerification,
    private val timeProvider: TimeProvider,
) {
    fun runNext(): Boolean {
        val claim = repository.claimNext(timeProvider.now()) ?: return false
        try {
            runTraceabilityVerification.run(claim)
        } catch (_: DataAccessException) {
            recordRetry(claim)
        } catch (_: TraceabilitySnapshotVersionConflict) {
            recordRetry(claim)
        }
        return true
    }

    private fun recordRetry(claim: TraceabilityVerificationJobClaim) {
        try {
            repository.recordInfrastructureFailure(claim, timeProvider.now())
        } catch (failure: RuntimeException) {
            throw IllegalStateException("TRACEABILITY_VERIFICATION_FAILURE_COORDINATION_FAILED", failure)
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    prefix = "vsrqg.traceability.verification",
    name = ["worker-enabled"],
    havingValue = "true",
)
class TraceabilityVerificationSchedulingConfiguration(
    private val worker: TraceabilityVerificationJobWorker,
) {
    @Scheduled(
        fixedDelayString = "\${vsrqg.traceability.verification.poll-interval:PT1S}",
        initialDelayString = "\${vsrqg.traceability.verification.initial-delay:PT1S}",
    )
    fun poll() {
        worker.runNext()
    }
}
