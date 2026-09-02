package com.ricezhou.vsrqg.issue.adapter

import com.ricezhou.vsrqg.issue.application.IssueSnapshotPolicy
import com.ricezhou.vsrqg.shared.adapter.archive.DeploymentProperties
import com.ricezhou.vsrqg.shared.application.archive.DeploymentMode
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("vsrqg.issue.snapshot")
data class IssueSnapshotProperties(
    val enabled: Boolean = true,
    val maxSyncAge: Duration = Duration.ofHours(24),
) {
    init {
        require(maxSyncAge > Duration.ZERO) { "ISSUE_SNAPSHOT_MAX_SYNC_AGE_INVALID" }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IssueSnapshotProperties::class, DeploymentProperties::class)
class IssueSnapshotConfiguration {
    @Bean
    fun issueSnapshotPolicy(
        deployment: DeploymentProperties,
        properties: IssueSnapshotProperties,
    ) = IssueSnapshotPolicy(
        enabled = deployment.mode == DeploymentMode.PILOT && properties.enabled,
        maxSyncAge = properties.maxSyncAge,
    )
}
