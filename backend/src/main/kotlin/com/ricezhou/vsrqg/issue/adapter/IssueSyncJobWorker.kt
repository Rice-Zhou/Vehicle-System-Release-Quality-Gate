package com.ricezhou.vsrqg.issue.adapter

import com.ricezhou.vsrqg.issue.application.IssueSyncRepository
import com.ricezhou.vsrqg.issue.application.IssueSyncStatus
import com.ricezhou.vsrqg.issue.application.RunIssueSync
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class IssueSyncJobWorker(
    private val repository: IssueSyncRepository,
    private val runIssueSync: RunIssueSync,
    private val runtimeRegistry: IssueSourceRuntimeRegistry,
) {
    fun runNext(): Boolean {
        val job = repository.claimNextJob() ?: return false
        val run = repository.findRun(job.syncRunId)
            ?: throw IllegalStateException("ISSUE_SYNC_RUN_NOT_FOUND")
        val port = try {
            runtimeRegistry.open(run)
        } catch (failure: IssueRuntimeConfigurationException) {
            repository.markFailed(job.syncRunId, failure.code.name)
            repository.markJobFailed(job.jobId, failure.code.name)
            return true
        }
        try {
            val result = runIssueSync.run(job.syncRunId, port)
            if (result.status == IssueSyncStatus.SUCCEEDED) {
                repository.markJobSucceeded(job.jobId)
            } else {
                repository.markJobFailed(job.jobId, result.diagnosticCode ?: SYNC_FAILED)
            }
        } catch (_: RuntimeException) {
            repository.markFailed(job.syncRunId, INTERNAL_ERROR)
            repository.markJobFailed(job.jobId, INTERNAL_ERROR)
            throw IllegalStateException("ISSUE_SYNC_JOB_FAILED")
        }
        return true
    }

    private companion object {
        const val SYNC_FAILED = "SYNC_FAILED"
        const val INTERNAL_ERROR = "INTERNAL_ERROR"
    }
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "vsrqg.issue.sync", name = ["worker-enabled"], havingValue = "true")
class IssueSyncSchedulingConfiguration(
    private val worker: IssueSyncJobWorker,
) {
    @Scheduled(
        fixedDelayString = "\${vsrqg.issue.sync.poll-interval:PT1S}",
        initialDelayString = "\${vsrqg.issue.sync.initial-delay:PT1S}",
    )
    fun poll() {
        worker.runNext()
    }
}
